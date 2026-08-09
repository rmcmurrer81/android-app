from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import secrets
import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


DISCOVERY_SCHEMA = "sarah-device-discovery-v2"
PAIRING_SCHEMA = "sarah-device-pairing-x25519-sas-v1"
DISCOVERY_MAGIC = b"SARAH_DISCOVER_V2"
DISCOVERY_PORT = 8771
PAIRING_LIFETIME_SECONDS = 120
MAX_CLOCK_SKEW_SECONDS = 30
MAX_DISCOVERY_BYTES = 2048
MAX_PAIRING_BYTES = 8192
MAX_SECURE_SYNC_BYTES = 8_000_000


class DeviceDiscoveryError(ValueError):
    pass


class PairingError(RuntimeError):
    pass


def _now_seconds() -> int:
    return int(time.time())


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _unb64(value: str, expected: int, field: str) -> bytes:
    text = str(value).strip()
    if not text or len(text) > expected * 2 + 8:
        raise PairingError(f"Pairing {field} is missing or malformed")
    try:
        raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except Exception as exc:
        raise PairingError(f"Pairing {field} is not valid base64url") from exc
    if len(raw) != expected:
        raise PairingError(f"Pairing {field} has the wrong length")
    return raw


def _label(value: Any, fallback: str) -> str:
    text = " ".join(str(value or fallback).split())[:80]
    if not text or any(ord(character) < 32 for character in text):
        raise PairingError("Pairing device label is invalid")
    return text


def _instance_id(value: Any) -> str:
    text = _label(value, "")
    if not text:
        raise PairingError("Pairing session instance ID is missing")
    return text


def _canonical(value: Mapping[str, Any]) -> bytes:
    return json.dumps(
        dict(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def _private_source(host: str) -> str:
    try:
        address = ipaddress.ip_address(str(host).split("%")[0])
    except ValueError as exc:
        raise DeviceDiscoveryError("Discovery response did not come from an IP address") from exc
    if not (address.is_private or address.is_loopback or address.is_link_local):
        raise DeviceDiscoveryError("Discovery accepts only local/private network responses")
    return str(address)


@dataclass(frozen=True)
class DiscoveredSarahDevice:
    host: str
    instance_id: str
    device_name: str
    device_type: str
    pairing_port: int
    expires_at: int


class SarahLocalDiscovery:
    """Discovery-only LAN notices; no profile, stable ID, or key is exposed."""

    def __init__(
        self,
        *,
        device_name: str = "Sarah on Windows",
        device_type: str = "windows",
        pairing_port: int = 0,
        clock: Callable[[], int] = _now_seconds,
    ):
        self.device_name = _label(device_name, "Sarah device")
        self.device_type = _label(device_type, "device")
        self.pairing_port = int(pairing_port)
        if self.pairing_port < 0 or self.pairing_port > 65535:
            raise DeviceDiscoveryError("Pairing port is outside the valid range")
        self.clock = clock
        # This rotates with each process and is deliberately not Sarah's stored
        # device ID. Discovery is an invitation to verify, never proof of trust.
        self.instance_id = _b64(secrets.token_bytes(18))

    @staticmethod
    def probe() -> bytes:
        return DISCOVERY_MAGIC

    def announcement(self) -> bytes:
        now = int(self.clock())
        value = {
            "schema": DISCOVERY_SCHEMA,
            "instance_id": self.instance_id,
            "device_name": self.device_name,
            "device_type": self.device_type,
            "pairing_protocol": PAIRING_SCHEMA,
            "pairing_port": self.pairing_port,
            "expires_at": now + 15,
            "approval_required_on_both_devices": True,
        }
        encoded = _canonical(value)
        if len(encoded) > MAX_DISCOVERY_BYTES:
            raise DeviceDiscoveryError("Discovery announcement exceeds its size limit")
        return encoded

    @staticmethod
    def parse_announcement(
        data: bytes,
        host: str,
        *,
        now: int | None = None,
    ) -> DiscoveredSarahDevice:
        if not data or len(data) > MAX_DISCOVERY_BYTES:
            raise DeviceDiscoveryError("Discovery response is empty or oversized")
        try:
            value = json.loads(data.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise DeviceDiscoveryError("Discovery response is not valid JSON") from exc
        if not isinstance(value, dict) or value.get("schema") != DISCOVERY_SCHEMA:
            raise DeviceDiscoveryError("Discovery response has the wrong protocol")
        if value.get("pairing_protocol") != PAIRING_SCHEMA:
            raise DeviceDiscoveryError("Discovery response has no accepted pairing protocol")
        if value.get("approval_required_on_both_devices") is not True:
            raise DeviceDiscoveryError("Discovery response does not require two-device approval")
        current = _now_seconds() if now is None else int(now)
        expires = int(value.get("expires_at", 0))
        if expires < current or expires > current + 60:
            raise DeviceDiscoveryError("Discovery response is expired or has an invalid lifetime")
        instance_id = str(value.get("instance_id", "")).strip()
        _unb64(instance_id, 18, "discovery instance")
        port = int(value.get("pairing_port", 0))
        if port < 0 or port > 65535:
            raise DeviceDiscoveryError("Discovery pairing port is invalid")
        return DiscoveredSarahDevice(
            host=_private_source(host),
            instance_id=instance_id,
            device_name=_label(value.get("device_name"), "Sarah device"),
            device_type=_label(value.get("device_type"), "device"),
            pairing_port=port,
            expires_at=expires,
        )

    def scan(
        self,
        *,
        timeout_seconds: float = 2.0,
        socket_factory: Callable[..., socket.socket] = socket.socket,
    ) -> list[DiscoveredSarahDevice]:
        timeout = max(0.1, min(float(timeout_seconds), 5.0))
        discovered: dict[tuple[str, str], DiscoveredSarahDevice] = {}
        sock = socket_factory(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(min(timeout, 0.25))
            sock.sendto(self.probe(), ("255.255.255.255", DISCOVERY_PORT))
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    data, address = sock.recvfrom(MAX_DISCOVERY_BYTES + 1)
                except socket.timeout:
                    continue
                try:
                    peer = self.parse_announcement(data, address[0])
                except DeviceDiscoveryError:
                    continue
                if peer.instance_id != self.instance_id:
                    discovered[(peer.host, peer.instance_id)] = peer
        finally:
            sock.close()
        return sorted(discovered.values(), key=lambda item: (item.device_name, item.host))


class SarahDiscoveryResponder:
    """Owner-started UDP responder for discovery only, never profile sync."""

    def __init__(
        self,
        discovery: SarahLocalDiscovery,
        *,
        bind_host: str = "0.0.0.0",
        port: int = DISCOVERY_PORT,
    ):
        self.discovery = discovery
        self.bind_host = str(bind_host)
        self.port = int(port)
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    @property
    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.bind_host, self.port))
        sock.settimeout(0.25)
        self._socket = sock
        self._thread = threading.Thread(
            target=self._run, name="SarahDiscoveryV2", daemon=True
        )
        self._thread.start()

    def _run(self) -> None:
        assert self._socket is not None
        while not self._stop.is_set():
            try:
                data, address = self._socket.recvfrom(MAX_DISCOVERY_BYTES + 1)
            except socket.timeout:
                continue
            except OSError:
                break
            if data != DISCOVERY_MAGIC:
                continue
            try:
                _private_source(address[0])
                self._socket.sendto(self.discovery.announcement(), address)
            except (OSError, DeviceDiscoveryError):
                continue

    def stop(self) -> None:
        self._stop.set()
        sock = self._socket
        self._socket = None
        if sock is not None:
            sock.close()
        thread = self._thread
        self._thread = None
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)


def _validate_lifetime(value: Mapping[str, Any], now: int) -> tuple[int, int]:
    created = int(value.get("created_at", 0))
    expires = int(value.get("expires_at", 0))
    if created > now + MAX_CLOCK_SKEW_SECONDS:
        raise PairingError("Pairing request was created too far in the future")
    if expires <= created or expires - created > PAIRING_LIFETIME_SECONDS:
        raise PairingError("Pairing request has an invalid lifetime")
    if now > expires:
        raise PairingError("Pairing request expired")
    return created, expires


def _parse_pairing_json(value: Mapping[str, Any] | bytes | str) -> dict[str, Any]:
    if isinstance(value, bytes):
        if not value or len(value) > MAX_PAIRING_BYTES:
            raise PairingError("Pairing message is empty or oversized")
        try:
            parsed = json.loads(value.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise PairingError("Pairing message is not valid JSON") from exc
    elif isinstance(value, str):
        if not value or len(value.encode("utf-8")) > MAX_PAIRING_BYTES:
            raise PairingError("Pairing message is empty or oversized")
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError as exc:
            raise PairingError("Pairing message is not valid JSON") from exc
    else:
        parsed = dict(value)
    if parsed.get("schema") != PAIRING_SCHEMA:
        raise PairingError("Pairing message has the wrong protocol")
    return parsed


def _offer_core(value: Mapping[str, Any], now: int) -> dict[str, Any]:
    if value.get("kind") != "offer":
        raise PairingError("Expected a pairing offer")
    if value.get("approval_required_on_both_devices") is not True:
        raise PairingError("Pairing offer does not require approval on both devices")
    created, expires = _validate_lifetime(value, now)
    request_id = str(value.get("request_id", "")).strip()
    _unb64(request_id, 18, "request ID")
    public_key = str(value.get("public_key", "")).strip()
    nonce = str(value.get("nonce", "")).strip()
    _unb64(public_key, 32, "public key")
    _unb64(nonce, 24, "nonce")
    return {
        "schema": PAIRING_SCHEMA,
        "kind": "offer",
        "request_id": request_id,
        "created_at": created,
        "expires_at": expires,
        "instance_id": _instance_id(value.get("instance_id")),
        "device_name": _label(value.get("device_name"), "Sarah device"),
        "device_type": _label(value.get("device_type"), "device"),
        "public_key": public_key,
        "nonce": nonce,
        "approval_required_on_both_devices": True,
    }


def _response_core(
    value: Mapping[str, Any], offer: Mapping[str, Any], now: int
) -> dict[str, Any]:
    if value.get("kind") != "response":
        raise PairingError("Expected a pairing response")
    if value.get("approval_required_on_both_devices") is not True:
        raise PairingError("Pairing response does not require approval on both devices")
    created, _expires = _validate_lifetime(value, now)
    if value.get("request_id") != offer.get("request_id"):
        raise PairingError("Pairing response belongs to a different request")
    if int(value.get("expires_at", 0)) != int(offer.get("expires_at", -1)):
        raise PairingError("Pairing response changed the request lifetime")
    if created < int(offer.get("created_at", 0)):
        raise PairingError("Pairing response predates its request")
    public_key = str(value.get("public_key", "")).strip()
    nonce = str(value.get("nonce", "")).strip()
    _unb64(public_key, 32, "public key")
    _unb64(nonce, 24, "nonce")
    return {
        "schema": PAIRING_SCHEMA,
        "kind": "response",
        "request_id": str(value.get("request_id")),
        "created_at": int(value.get("created_at", 0)),
        "expires_at": int(value.get("expires_at", 0)),
        "instance_id": _instance_id(value.get("instance_id")),
        "device_name": _label(value.get("device_name"), "Sarah device"),
        "device_type": _label(value.get("device_type"), "device"),
        "public_key": public_key,
        "nonce": nonce,
        "approval_required_on_both_devices": True,
    }


def _raw_public(key: X25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )


def _derive_session(
    private_key: X25519PrivateKey,
    peer_public: bytes,
    offer: Mapping[str, Any],
    response: Mapping[str, Any],
) -> tuple[bytes, bytes, str]:
    transcript = _canonical({"offer": dict(offer), "response": dict(response)})
    transcript_hash = hashlib.sha256(transcript).digest()
    shared = private_key.exchange(X25519PublicKey.from_public_bytes(peer_public))
    salt = hashlib.sha256(
        _unb64(str(offer["nonce"]), 24, "offer nonce")
        + _unb64(str(response["nonce"]), 24, "response nonce")
    ).digest()
    material = HKDF(
        algorithm=hashes.SHA256(),
        length=64,
        salt=salt,
        info=b"SarahDevicePairingV1\x00" + transcript_hash,
    ).derive(shared)
    sas_value = int.from_bytes(
        hmac.new(material[32:], b"sas\x00" + transcript_hash, hashlib.sha256).digest()[:8],
        "big",
    ) % 1_000_000
    return material, transcript_hash, f"{sas_value:06d}"


@dataclass(frozen=True)
class PairingCredential:
    request_id: str
    peer_instance_id: str
    peer_device_name: str
    peer_device_type: str
    token: str
    established_at: int


class PairingSession:
    def __init__(
        self,
        *,
        local_role: str,
        offer: Mapping[str, Any],
        response: Mapping[str, Any],
        material: bytes,
        transcript_hash: bytes,
        sas_code: str,
        clock: Callable[[], int],
    ):
        if local_role not in {"initiator", "responder"}:
            raise PairingError("Pairing role is invalid")
        self.local_role = local_role
        self.peer_role = "responder" if local_role == "initiator" else "initiator"
        self.offer = dict(offer)
        self.response = dict(response)
        self._material = material
        self._transcript_hash = transcript_hash
        self.sas_code = sas_code
        self.clock = clock
        self._local_confirmed = False
        self._peer_confirmed = False

    @property
    def expires_at(self) -> int:
        return int(self.offer["expires_at"])

    def local_confirmation(self, *, owner_confirmed_matching_code: bool) -> dict[str, Any]:
        if int(self.clock()) > self.expires_at:
            raise PairingError("Pairing confirmation expired")
        if not owner_confirmed_matching_code:
            raise PairingError("The owner did not confirm the matching code on this device")
        self._local_confirmed = True
        proof = hmac.new(
            self._material[32:],
            b"confirm\x00" + self.local_role.encode("ascii") + b"\x00" + self._transcript_hash,
            hashlib.sha256,
        ).digest()
        return {
            "schema": PAIRING_SCHEMA,
            "kind": "confirmation",
            "request_id": self.offer["request_id"],
            "role": self.local_role,
            "transcript_sha256": self._transcript_hash.hex(),
            "expires_at": self.expires_at,
            "proof": _b64(proof),
        }

    def accept_peer_confirmation(self, value: Mapping[str, Any] | bytes | str) -> None:
        confirmation = _parse_pairing_json(value)
        if confirmation.get("kind") != "confirmation":
            raise PairingError("Expected a pairing confirmation")
        if int(self.clock()) > self.expires_at:
            raise PairingError("Pairing confirmation expired")
        if confirmation.get("request_id") != self.offer["request_id"]:
            raise PairingError("Pairing confirmation belongs to a different request")
        if int(confirmation.get("expires_at", 0)) != self.expires_at:
            raise PairingError("Pairing confirmation changed the request lifetime")
        if confirmation.get("role") != self.peer_role:
            raise PairingError("Pairing confirmation has the wrong device role")
        if confirmation.get("transcript_sha256") != self._transcript_hash.hex():
            raise PairingError("Pairing confirmation transcript does not match")
        proof = _unb64(str(confirmation.get("proof", "")), 32, "confirmation proof")
        expected = hmac.new(
            self._material[32:],
            b"confirm\x00" + self.peer_role.encode("ascii") + b"\x00" + self._transcript_hash,
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(proof, expected):
            raise PairingError("Pairing confirmation proof failed")
        self._peer_confirmed = True

    def finalize(self) -> PairingCredential:
        if int(self.clock()) > self.expires_at:
            raise PairingError("Pairing session expired")
        if not self._local_confirmed or not self._peer_confirmed:
            raise PairingError("Both devices must explicitly confirm the same code")
        token = _b64(
            hmac.new(
                self._material[:32],
                b"sync-credential\x00" + self._transcript_hash,
                hashlib.sha256,
            ).digest()
        )
        peer = self.response if self.local_role == "initiator" else self.offer
        return PairingCredential(
            request_id=str(self.offer["request_id"]),
            peer_instance_id=str(peer.get("instance_id", "")),
            peer_device_name=str(peer["device_name"]),
            peer_device_type=str(peer["device_type"]),
            token=token,
            established_at=int(self.clock()),
        )


class PairingInitiator:
    def __init__(
        self,
        *,
        instance_id: str,
        device_name: str,
        device_type: str,
        clock: Callable[[], int] = _now_seconds,
    ):
        self.clock = clock
        self._private = X25519PrivateKey.generate()
        now = int(clock())
        self.offer = {
            "schema": PAIRING_SCHEMA,
            "kind": "offer",
            "request_id": _b64(secrets.token_bytes(18)),
            "created_at": now,
            "expires_at": now + PAIRING_LIFETIME_SECONDS,
            "instance_id": _instance_id(instance_id),
            "device_name": _label(device_name, "Sarah device"),
            "device_type": _label(device_type, "device"),
            "public_key": _b64(_raw_public(self._private)),
            "nonce": _b64(secrets.token_bytes(24)),
            "approval_required_on_both_devices": True,
        }

    def offer_message(self) -> dict[str, Any]:
        return dict(self.offer)

    def accept_response(self, value: Mapping[str, Any] | bytes | str) -> PairingSession:
        now = int(self.clock())
        offer = _offer_core(self.offer, now)
        response = _response_core(_parse_pairing_json(value), offer, now)
        material, transcript_hash, sas_code = _derive_session(
            self._private,
            _unb64(str(response["public_key"]), 32, "public key"),
            offer,
            response,
        )
        return PairingSession(
            local_role="initiator",
            offer=offer,
            response=response,
            material=material,
            transcript_hash=transcript_hash,
            sas_code=sas_code,
            clock=self.clock,
        )


class PairingResponder:
    def __init__(
        self,
        *,
        instance_id: str,
        device_name: str,
        device_type: str,
        clock: Callable[[], int] = _now_seconds,
    ):
        self.instance_id = _instance_id(instance_id)
        self.device_name = _label(device_name, "Sarah device")
        self.device_type = _label(device_type, "device")
        self.clock = clock

    def respond(
        self, value: Mapping[str, Any] | bytes | str
    ) -> tuple[dict[str, Any], PairingSession]:
        now = int(self.clock())
        offer = _offer_core(_parse_pairing_json(value), now)
        private = X25519PrivateKey.generate()
        response = {
            "schema": PAIRING_SCHEMA,
            "kind": "response",
            "request_id": offer["request_id"],
            "created_at": now,
            "expires_at": offer["expires_at"],
            "instance_id": self.instance_id,
            "device_name": self.device_name,
            "device_type": self.device_type,
            "public_key": _b64(_raw_public(private)),
            "nonce": _b64(secrets.token_bytes(24)),
            "approval_required_on_both_devices": True,
        }
        response = _response_core(response, offer, now)
        material, transcript_hash, sas_code = _derive_session(
            private,
            _unb64(str(offer["public_key"]), 32, "public key"),
            offer,
            response,
        )
        session = PairingSession(
            local_role="responder",
            offer=offer,
            response=response,
            material=material,
            transcript_hash=transcript_hash,
            sas_code=sas_code,
            clock=self.clock,
        )
        return dict(response), session


_OFFER_FIELDS = {
    "schema", "kind", "request_id", "created_at", "expires_at", "instance_id",
    "device_name", "device_type", "public_key", "nonce",
    "approval_required_on_both_devices",
}
_CONFIRMATION_FIELDS = {
    "schema", "kind", "request_id", "role", "transcript_sha256", "expires_at",
    "proof",
}


def _require_exact_wire_fields(value: Mapping[str, Any], allowed: set[str], kind: str) -> None:
    keys = set(value)
    if keys != allowed:
        raise PairingError(f"Pairing {kind} contains unexpected or missing fields")
    for item in value.values():
        if not isinstance(item, (str, bool, int)):
            raise PairingError(f"Pairing {kind} contains an unsupported value")


def _read_exact(connection: socket.socket, size: int) -> bytes:
    output = bytearray()
    while len(output) < size:
        piece = connection.recv(size - len(output))
        if not piece:
            raise PairingError("The other Sarah device closed pairing early")
        output.extend(piece)
    return bytes(output)


def read_pairing_frame(connection: socket.socket) -> dict[str, Any]:
    """Read one Android-compatible 4-byte big-endian length-prefixed JSON frame."""
    length = struct.unpack(">I", _read_exact(connection, 4))[0]
    if length < 1 or length > MAX_PAIRING_BYTES:
        raise PairingError("Pairing frame is empty or oversized")
    return _parse_pairing_json(_read_exact(connection, length))


def read_json_frame(connection: socket.socket, *, maximum: int = MAX_SECURE_SYNC_BYTES) -> dict[str, Any]:
    """Read a bounded JSON frame without assuming the pairing schema."""
    length = struct.unpack(">I", _read_exact(connection, 4))[0]
    if length < 1 or length > maximum:
        raise PairingError("Sarah device frame is empty or oversized")
    try:
        value = json.loads(_read_exact(connection, length).decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise PairingError("Sarah device frame is not valid JSON") from exc
    if not isinstance(value, dict):
        raise PairingError("Sarah device frame must be a JSON object")
    return value


def write_pairing_frame(connection: socket.socket, value: Mapping[str, Any]) -> None:
    """Write one bounded Android-compatible pairing frame."""
    encoded = _canonical(value)
    if len(encoded) < 1 or len(encoded) > MAX_PAIRING_BYTES:
        raise PairingError("Pairing frame is empty or oversized")
    connection.sendall(struct.pack(">I", len(encoded)) + encoded)


def write_json_frame(
    connection: socket.socket,
    value: Mapping[str, Any],
    *,
    maximum: int = MAX_SECURE_SYNC_BYTES,
) -> None:
    encoded = _canonical(value)
    if len(encoded) < 1 or len(encoded) > maximum:
        raise PairingError("Sarah device frame is empty or oversized")
    connection.sendall(struct.pack(">I", len(encoded)) + encoded)


class PendingResponderPairing:
    """A SAS decision gate. It contains no profile or service credential."""

    def __init__(
        self,
        *,
        session: PairingSession,
        peer_host: str,
    ):
        self.session = session
        self.peer_host = peer_host
        self.request_id = str(session.offer["request_id"])
        self.peer_instance_id = str(session.offer["instance_id"])
        self.peer_device_name = str(session.offer["device_name"])
        self.peer_device_type = str(session.offer["device_type"])
        self.sas_code = session.sas_code
        self.expires_at = session.expires_at
        self._decision: bool | None = None
        self._decision_event = threading.Event()
        self._decision_lock = threading.Lock()

    def approve(self, expected_sas_code: str | None = None) -> None:
        if expected_sas_code is not None and str(expected_sas_code) != self.sas_code:
            raise PairingError("The Windows matching code changed before approval")
        self._set_decision(True)

    def reject(self) -> None:
        self._set_decision(False)

    def _set_decision(self, approved: bool) -> None:
        with self._decision_lock:
            if self._decision is not None:
                raise PairingError("This pairing request already has an owner decision")
            self._decision = bool(approved)
            self._decision_event.set()

    def wait_for_owner(self, timeout_seconds: float) -> bool:
        if not self._decision_event.wait(max(0.05, float(timeout_seconds))):
            raise PairingError("Windows owner confirmation timed out")
        return self._decision is True


class PendingInitiatorPairing:
    """Windows-initiated connection to an established Android responder."""

    def __init__(self, connection: socket.socket, session: PairingSession, peer: DiscoveredSarahDevice):
        self.connection = connection
        self.session = session
        self.peer = peer
        self.sas_code = session.sas_code
        self.expires_at = session.expires_at
        self._completed = False

    def complete(self, *, owner_confirmed_matching_code: bool) -> PairingCredential:
        if self._completed:
            raise PairingError("This Windows pairing request was already decided")
        self._completed = True
        try:
            confirmation = self.session.local_confirmation(
                owner_confirmed_matching_code=owner_confirmed_matching_code
            )
            remaining = max(1.0, min(125.0, float(self.expires_at - _now_seconds() + 1)))
            self.connection.settimeout(remaining)
            write_pairing_frame(self.connection, confirmation)
            peer_confirmation = read_pairing_frame(self.connection)
            _require_exact_wire_fields(peer_confirmation, _CONFIRMATION_FIELDS, "confirmation")
            self.session.accept_peer_confirmation(peer_confirmation)
            return self.session.finalize()
        finally:
            self.close()

    def close(self) -> None:
        try:
            self.connection.close()
        except OSError:
            pass


def begin_pairing_initiator(
    peer: DiscoveredSarahDevice,
    *,
    local_instance_id: str,
    local_device_name: str,
    local_device_type: str = "windows",
) -> PendingInitiatorPairing:
    if peer.pairing_port < 1 or peer.pairing_port > 65535:
        raise PairingError("The discovered Android Sarah is not accepting secure pairing")
    if _now_seconds() > peer.expires_at:
        raise PairingError("The Android Sarah discovery notice expired")
    connection = socket.create_connection((peer.host, peer.pairing_port), timeout=5.0)
    try:
        connection.settimeout(10.0)
        initiator = PairingInitiator(
            instance_id=local_instance_id,
            device_name=local_device_name,
            device_type=local_device_type,
        )
        write_pairing_frame(connection, initiator.offer_message())
        response = read_pairing_frame(connection)
        _require_exact_wire_fields(response, _OFFER_FIELDS, "response")
        if str(response.get("instance_id", "")).strip() != peer.instance_id:
            raise PairingError("Pairing response instance does not match discovery")
        if str(response.get("device_name", "")).strip() != peer.device_name:
            raise PairingError("Pairing response name does not match discovery")
        if str(response.get("device_type", "")).strip() != peer.device_type:
            raise PairingError("Pairing response type does not match discovery")
        session = initiator.accept_response(response)
        return PendingInitiatorPairing(connection, session, peer)
    except Exception:
        connection.close()
        raise


class SarahPairingResponderServer:
    """Bounded private-LAN responder for the Android offer/confirmation sequence."""

    def __init__(
        self,
        discovery: SarahLocalDiscovery,
        *,
        bind_host: str = "0.0.0.0",
        port: int = 0,
        initial_timeout_seconds: float = 10.0,
        approval_timeout_seconds: float = PAIRING_LIFETIME_SECONDS,
        on_pending: Callable[[PendingResponderPairing], None] | None = None,
        on_complete: Callable[[PairingCredential], None] | None = None,
        on_secure_sync: Callable[[socket.socket, Mapping[str, Any], str], None] | None = None,
        on_error: Callable[[Exception], None] | None = None,
        clock: Callable[[], int] = _now_seconds,
    ):
        self.discovery = discovery
        self.bind_host = str(bind_host)
        self.requested_port = int(port)
        if self.requested_port < 0 or self.requested_port > 65535:
            raise PairingError("Pairing listener port is outside the valid range")
        self.initial_timeout_seconds = max(0.25, min(float(initial_timeout_seconds), 15.0))
        self.approval_timeout_seconds = max(
            0.05, min(float(approval_timeout_seconds), PAIRING_LIFETIME_SECONDS + 5.0)
        )
        self.on_pending = on_pending
        self.on_complete = on_complete
        self.on_secure_sync = on_secure_sync
        self.on_error = on_error
        self.clock = clock
        self._listener: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._one_client = threading.BoundedSemaphore(1)
        self._connections: set[socket.socket] = set()
        self._connections_lock = threading.Lock()
        self.port = 0

    @property
    def running(self) -> bool:
        return bool(self._thread and self._thread.is_alive() and self.port > 0)

    def start(self) -> int:
        if self.running:
            return self.port
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((self.bind_host, self.requested_port))
            listener.listen(4)
            listener.settimeout(0.25)
            self.port = int(listener.getsockname()[1])
            if self.port < 1:
                raise PairingError("Windows did not allocate a pairing port")
            self.discovery.pairing_port = self.port
            self._listener = listener
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._accept_loop, name="SarahPairingResponderV1", daemon=True
            )
            self._thread.start()
            return self.port
        except Exception:
            listener.close()
            self.discovery.pairing_port = 0
            self.port = 0
            raise

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            listener = self._listener
            if listener is None:
                break
            try:
                connection, address = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self._one_client.acquire(blocking=False):
                connection.close()
                continue
            with self._connections_lock:
                self._connections.add(connection)
            threading.Thread(
                target=self._handle_connection,
                args=(connection, address),
                name="SarahPairingClientV1",
                daemon=True,
            ).start()

    def _handle_connection(self, connection: socket.socket, address: tuple[Any, ...]) -> None:
        try:
            peer_host = _private_source(str(address[0]))
            connection.settimeout(self.initial_timeout_seconds)
            # Pairing offers and secure-sync request envelopes are intentionally
            # small. Only the encrypted response may use the larger bound.
            first = read_json_frame(connection, maximum=MAX_PAIRING_BYTES)
            if first.get("schema") != PAIRING_SCHEMA:
                if self.on_secure_sync is None:
                    raise PairingError("No accepted post-trust Sarah protocol is connected")
                self.on_secure_sync(connection, first, peer_host)
                return
            offer = _parse_pairing_json(first)
            _require_exact_wire_fields(offer, _OFFER_FIELDS, "offer")
            responder = PairingResponder(
                instance_id=self.discovery.instance_id,
                device_name=self.discovery.device_name,
                device_type=self.discovery.device_type,
                clock=self.clock,
            )
            response, session = responder.respond(offer)
            _require_exact_wire_fields(response, _OFFER_FIELDS, "response")
            write_pairing_frame(connection, response)

            pending = PendingResponderPairing(session=session, peer_host=peer_host)
            if self.on_pending is None:
                raise PairingError("No Windows owner-approval handler is connected")
            self.on_pending(pending)
            remaining = min(
                self.approval_timeout_seconds,
                max(0.05, float(session.expires_at - int(self.clock()) + 1)),
            )
            if not pending.wait_for_owner(remaining):
                raise PairingError("The Windows owner rejected the pairing request")

            responder_confirmation = session.local_confirmation(
                owner_confirmed_matching_code=True
            )
            connection.settimeout(min(15.0, remaining))
            initiator_confirmation = read_pairing_frame(connection)
            _require_exact_wire_fields(
                initiator_confirmation, _CONFIRMATION_FIELDS, "confirmation"
            )
            session.accept_peer_confirmation(initiator_confirmation)
            write_pairing_frame(connection, responder_confirmation)
            credential = session.finalize()
            if self.on_complete is not None:
                self.on_complete(credential)
        except Exception as error:
            if self.on_error is not None:
                try:
                    self.on_error(error)
                except Exception:
                    pass
        finally:
            with self._connections_lock:
                self._connections.discard(connection)
            try:
                connection.close()
            finally:
                self._one_client.release()

    def stop(self) -> None:
        self._stop.set()
        listener = self._listener
        self._listener = None
        if listener is not None:
            listener.close()
        with self._connections_lock:
            connections = list(self._connections)
        for connection in connections:
            try:
                connection.close()
            except OSError:
                pass
        thread = self._thread
        self._thread = None
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)
        self.discovery.pairing_port = 0
        self.port = 0
