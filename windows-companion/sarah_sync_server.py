from __future__ import annotations
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import threading
import time
from typing import Any

from sarah_core import SarahDatabase, sync_decrypt, sync_encrypt, sync_signature


class SarahSyncServer:
    def __init__(self, database: SarahDatabase, host: str = "0.0.0.0", port: int = 8769):
        self.database = database
        self.host = host
        self.port = port
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        self.httpd: ThreadingHTTPServer | None = None
        self.thread: threading.Thread | None = None

    def rotate_code(self) -> str:
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        return self.pairing_code

    def start(self) -> None:
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:
                return
            def _json(self) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0"))
                if length > 30_000_000:
                    raise ValueError("Payload too large")
                return json.loads(self.rfile.read(length).decode("utf-8") or "{}")
            def _send(self, status: int, payload: dict[str, Any]) -> None:
                data = json.dumps(payload).encode("utf-8")
                self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_GET(self) -> None:
                if self.path == "/health": self._send(200, {"status": "Sarah Windows ready", "schema": "sarah-sync-v1"})
                else: self._send(404, {"error": "not found"})
            def do_POST(self) -> None:
                try:
                    if self.path == "/pair":
                        body = self._json()
                        if time.time() > outer.pairing_expires or str(body.get("code", "")) != outer.pairing_code:
                            self._send(403, {"error": "Pairing code is wrong or expired"}); return
                        device_id = str(body.get("device_id", "")).strip()
                        device_name = str(body.get("device_name", "Android phone")).strip()
                        if not device_id: self._send(400, {"error": "device_id required"}); return
                        token = secrets.token_urlsafe(32)
                        with outer.database.connect() as db:
                            db.execute("INSERT INTO trusted_devices VALUES(?,?,?,?,?,0) ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,token_hash=excluded.token_hash,paired_at=excluded.paired_at,last_seen=excluded.last_seen,revoked=0", (device_id, device_name, hashlib.sha256(token.encode()).hexdigest(), int(time.time()*1000), int(time.time()*1000)))
                        outer.rotate_code()
                        self._send(200, {"token": token, "message": f"Paired {device_name}."}); return
                    if self.path == "/sync":
                        body = self._json(); token = self.headers.get("X-Sarah-Device-Token", "")
                        if not token: self._send(401, {"error": "Missing device token"}); return
                        token_hash = hashlib.sha256(token.encode()).hexdigest()
                        with outer.database.connect() as db:
                            row = db.execute("SELECT device_id FROM trusted_devices WHERE token_hash=? AND revoked=0", (token_hash,)).fetchone()
                        if not row: self._send(403, {"error": "Device is not trusted or was revoked"}); return
                        encrypted = str(body.get("payload", "")); signature = str(body.get("signature", ""))
                        if not hmac.compare_digest(sync_signature(token, encrypted), signature): self._send(403, {"error": "Signature failed"}); return
                        payload = json.loads(sync_decrypt(token, encrypted))
                        counts = outer.database.import_sync(payload)
                        with outer.database.connect() as db: db.execute("UPDATE trusted_devices SET last_seen=? WHERE token_hash=?", (int(time.time()*1000), token_hash))
                        outgoing = json.dumps(outer.database.export_sync(include_photos=True), ensure_ascii=False)
                        encrypted_reply = sync_encrypt(token, outgoing)
                        self._send(200, {
                            "message": "Sarah synchronized the phone and Windows companion in both directions.",
                            "imported": counts,
                            "payload": encrypted_reply,
                            "signature": sync_signature(token, encrypted_reply),
                        }); return
                    self._send(404, {"error": "not found"})
                except Exception as exc:
                    self._send(500, {"error": str(exc)[:500]})
        self.httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="SarahSyncServer", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        if self.httpd:
            self.httpd.shutdown(); self.httpd.server_close(); self.httpd = None
