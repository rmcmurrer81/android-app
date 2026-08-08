from __future__ import annotations

import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import socket
import threading
import time
from typing import Any

from sarah_core import SarahDatabase, sync_decrypt, sync_encrypt, sync_signature


class SarahSyncServer:
    """Loopback-only sync harness retained until authenticated transport exists.

    The former LAN mode returned the same bearer secret used to authenticate
    and encrypt later sync requests over plain HTTP. Payload encryption could
    not protect a key exposed during pairing. R2 therefore keeps this server
    on loopback for bounded self-tests only; owner-facing apps do not start it.
    """

    DISCOVERY_PORT = 8770

    def __init__(
        self,
        database: SarahDatabase,
        host: str = "127.0.0.1",
        port: int = 8769,
        device_name: str | None = None,
    ):
        normalized_host = str(host).strip().lower()
        if normalized_host not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError(
                "Sarah R2 trusted-device sync is unavailable until pairing "
                "uses TLS or an authenticated key agreement."
            )
        self.database = database
        self.host = host
        self.port = port
        self.device_id = database.get_setting("device_id") or secrets.token_hex(16)
        if not database.get_setting("device_id"):
            database.set_setting("device_id", self.device_id)
        self.device_name = device_name or f"Sarah on {socket.gethostname()}"
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        self.httpd: ThreadingHTTPServer | None = None
        self.thread: threading.Thread | None = None
        self.discovery_thread: threading.Thread | None = None
        self.discovery_socket: socket.socket | None = None
        self.stop_event = threading.Event()
        self.pending_lock = threading.RLock()
        self.pending: dict[str, dict[str, Any]] = {}

    def rotate_code(self) -> str:
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        return self.pairing_code

    def start(self) -> None:
        if self.httpd is not None:
            return
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:
                return

            def _json(self) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > 30_000_000:
                    raise ValueError("Payload too large")
                raw = self.rfile.read(length).decode("utf-8") if length else "{}"
                value = json.loads(raw or "{}")
                if not isinstance(value, dict):
                    raise ValueError("A JSON object is required")
                return value

            def _send(self, status: int, payload: dict[str, Any]) -> None:
                data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self) -> None:
                if self.path == "/health":
                    self._send(
                        200,
                        {
                            "status": "Sarah Windows ready",
                            "schema": "sarah-sync-v1",
                            "device_id": outer.device_id,
                            "device_name": outer.device_name,
                            "approval_required": True,
                        },
                    )
                else:
                    self._send(404, {"error": "not found"})

            def do_POST(self) -> None:
                try:
                    if self.path == "/pair/request":
                        body = self._json()
                        device_id = str(body.get("device_id", "")).strip()
                        device_name = str(body.get("device_name", "Android phone")).strip()[:120]
                        device_type = str(body.get("device_type", "unknown")).strip()[:60]
                        verification_code = str(body.get("verification_code", "")).strip()
                        if not device_id:
                            self._send(400, {"error": "device_id required"})
                            return
                        if len(verification_code) != 6 or not verification_code.isdigit():
                            self._send(400, {"error": "A six-digit verification code is required"})
                            return
                        request_id = outer.create_pair_request(
                            device_id=device_id,
                            device_name=device_name or "Android phone",
                            device_type=device_type,
                            verification_code=verification_code,
                            remote_address=self.client_address[0],
                        )
                        self._send(
                            202,
                            {
                                "request_id": request_id,
                                "status": "pending",
                                "device_name": outer.device_name,
                                "verification_code": verification_code,
                                "expires_in_seconds": 150,
                                "message": "Approve this named device and matching code on the existing Sarah device.",
                            },
                        )
                        return

                    if self.path == "/pair/status":
                        body = self._json()
                        result = outer.pair_status(
                            str(body.get("request_id", "")).strip(),
                            str(body.get("device_id", "")).strip(),
                        )
                        code = 200 if result.get("status") != "missing" else 404
                        self._send(code, result)
                        return

                    if self.path == "/pair":
                        # Manual fallback for guest Wi-Fi that blocks UDP discovery.
                        body = self._json()
                        if time.time() > outer.pairing_expires or str(body.get("code", "")) != outer.pairing_code:
                            self._send(403, {"error": "Pairing code is wrong or expired"})
                            return
                        device_id = str(body.get("device_id", "")).strip()
                        device_name = str(body.get("device_name", "Android phone")).strip()[:120]
                        if not device_id:
                            self._send(400, {"error": "device_id required"})
                            return
                        token = outer.trust_device(device_id, device_name or "Android phone")
                        outer.rotate_code()
                        self._send(200, {"token": token, "message": f"Paired {device_name}."})
                        return

                    if self.path == "/sync":
                        body = self._json()
                        token = self.headers.get("X-Sarah-Device-Token", "")
                        if not token:
                            self._send(401, {"error": "Missing device token"})
                            return
                        token_hash = hashlib.sha256(token.encode()).hexdigest()
                        with outer.database.connect() as db:
                            row = db.execute(
                                "SELECT device_id FROM trusted_devices WHERE token_hash=? AND revoked=0",
                                (token_hash,),
                            ).fetchone()
                        if not row:
                            self._send(403, {"error": "Device is not trusted or was revoked"})
                            return
                        encrypted = str(body.get("payload", ""))
                        signature = str(body.get("signature", ""))
                        if not hmac.compare_digest(sync_signature(token, encrypted), signature):
                            self._send(403, {"error": "Signature failed"})
                            return
                        payload = json.loads(sync_decrypt(token, encrypted))
                        counts = outer.database.import_sync(payload)
                        with outer.database.connect() as db:
                            db.execute(
                                "UPDATE trusted_devices SET last_seen=? WHERE token_hash=?",
                                (int(time.time() * 1000), token_hash),
                            )
                        outgoing = json.dumps(
                            outer.database.export_sync(include_photos=True),
                            ensure_ascii=False,
                        )
                        encrypted_reply = sync_encrypt(token, outgoing)
                        self._send(
                            200,
                            {
                                "message": "Sarah synchronized the phone and Windows companion in both directions.",
                                "imported": counts,
                                "payload": encrypted_reply,
                                "signature": sync_signature(token, encrypted_reply),
                            },
                        )
                        return

                    self._send(404, {"error": "not found"})
                except Exception as exc:
                    self._send(500, {"error": str(exc)[:500]})

        self.stop_event.clear()
        self.httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self.port = int(self.httpd.server_address[1])
        self.thread = threading.Thread(
            target=self.httpd.serve_forever,
            name="SarahSyncServer",
            daemon=True,
        )
        self.thread.start()
        # Do not advertise a loopback-only diagnostic service on the LAN.
        self.discovery_thread = None

    def create_pair_request(
        self,
        *,
        device_id: str,
        device_name: str,
        device_type: str,
        verification_code: str,
        remote_address: str,
    ) -> str:
        now = time.time()
        request_id = secrets.token_urlsafe(24)
        with self.pending_lock:
            self._purge_pending(now)
            self.pending[request_id] = {
                "request_id": request_id,
                "device_id": device_id,
                "device_name": device_name,
                "device_type": device_type,
                "verification_code": verification_code,
                "remote_address": remote_address,
                "created_at": now,
                "expires_at": now + 150,
                "status": "pending",
                "token": "",
            }
        return request_id

    def pending_requests(self) -> list[dict[str, Any]]:
        now = time.time()
        with self.pending_lock:
            self._purge_pending(now)
            return [dict(value) for value in self.pending.values() if value.get("status") == "pending"]

    def approve_request(self, request_id: str) -> bool:
        now = time.time()
        with self.pending_lock:
            self._purge_pending(now)
            request = self.pending.get(request_id)
            if not request or request.get("status") != "pending":
                return False
            token = self.trust_device(request["device_id"], request["device_name"])
            request["token"] = token
            request["status"] = "approved"
            request["decided_at"] = now
            return True

    def deny_request(self, request_id: str) -> bool:
        with self.pending_lock:
            request = self.pending.get(request_id)
            if not request or request.get("status") != "pending":
                return False
            request["status"] = "denied"
            request["decided_at"] = time.time()
            return True

    def pair_status(self, request_id: str, device_id: str) -> dict[str, Any]:
        now = time.time()
        with self.pending_lock:
            self._purge_pending(now)
            request = self.pending.get(request_id)
            if not request or request.get("device_id") != device_id:
                return {"status": "missing", "message": "Pairing request was not found."}
            status = str(request.get("status", "pending"))
            result: dict[str, Any] = {
                "status": status,
                "device_name": self.device_name,
                "verification_code": request.get("verification_code", ""),
            }
            if status == "approved":
                result["token"] = request.get("token", "")
                result["message"] = "The existing Sarah device approved this named device."
            elif status == "denied":
                result["message"] = "The existing Sarah device denied the request."
            elif status == "expired":
                result["message"] = "The request expired before approval."
            else:
                result["message"] = "Waiting for approval on the existing Sarah device."
            return result

    def trust_device(self, device_id: str, device_name: str) -> str:
        token = secrets.token_urlsafe(32)
        timestamp = int(time.time() * 1000)
        with self.database.connect() as db:
            db.execute(
                "INSERT INTO trusted_devices VALUES(?,?,?,?,?,0) "
                "ON CONFLICT(device_id) DO UPDATE SET "
                "device_name=excluded.device_name,token_hash=excluded.token_hash,"
                "paired_at=excluded.paired_at,last_seen=excluded.last_seen,revoked=0",
                (
                    device_id,
                    device_name,
                    hashlib.sha256(token.encode()).hexdigest(),
                    timestamp,
                    timestamp,
                ),
            )
        return token

    def _purge_pending(self, now: float) -> None:
        for request in self.pending.values():
            if request.get("status") == "pending" and now > float(request.get("expires_at", 0)):
                request["status"] = "expired"
        stale = [
            key
            for key, request in self.pending.items()
            if now - float(request.get("created_at", now)) > 15 * 60
        ]
        for key in stale:
            self.pending.pop(key, None)

    def _discovery_loop(self) -> None:
        sock: socket.socket | None = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("0.0.0.0", self.DISCOVERY_PORT))
            sock.settimeout(0.5)
            self.discovery_socket = sock
            while not self.stop_event.is_set():
                try:
                    data, address = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not data.startswith(b"SARAH_DISCOVER_V1"):
                    continue
                response = json.dumps(
                    {
                        "protocol": "sarah-discovery-v1",
                        "device_id": self.device_id,
                        "device_name": self.device_name,
                        "device_type": "windows",
                        "port": self.port,
                        "approval_required": True,
                    },
                    ensure_ascii=False,
                ).encode("utf-8")
                try:
                    sock.sendto(response, address)
                except OSError:
                    pass
        except OSError:
            # Discovery can be blocked by a firewall; manual address/code remains available.
            pass
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
            self.discovery_socket = None

    def stop(self) -> None:
        self.stop_event.set()
        if self.discovery_socket is not None:
            try:
                self.discovery_socket.close()
            except OSError:
                pass
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None
        self.thread = None
        self.discovery_thread = None
