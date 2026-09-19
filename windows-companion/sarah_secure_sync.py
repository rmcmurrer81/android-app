from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import socket
import threading
import time
from pathlib import Path
from typing import Any, Mapping

from sarah_core import (
    _protect_runtime_secret,
    _unprotect_runtime_secret,
    safe_text,
    sync_decrypt,
    sync_encrypt,
    sync_signature,
)
from sarah_device_pairing import PairingError, read_json_frame, write_json_frame


SECURE_SYNC_SCHEMA = "sarah-secure-sync-v2"
VAULT_SCHEMA = "sarah-pairing-credential-v1"
_REQUEST_FIELDS = {"schema", "kind", "device_id", "request_id", "payload", "signature"}


class PairingCredentialVault:
    """User-bound storage for finalized credentials; raw tokens never enter SQLite."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.path = self.root / "secure_pairing_credentials.json"
        self._lock = threading.RLock()

    def _read(self) -> dict[str, Any]:
        if not self.path.is_file():
            return {"schema": VAULT_SCHEMA, "devices": {}, "history": []}
        value = json.loads(self.path.read_text(encoding="utf-8"))
        if value.get("schema") != VAULT_SCHEMA or not isinstance(value.get("devices"), dict):
            raise PairingError("Sarah's pairing credential vault is malformed")
        value.setdefault("history", [])
        return value

    def _write(self, value: Mapping[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f".{self.path.name}.{secrets.token_hex(6)}.tmp")
        try:
            temporary.write_text(json.dumps(dict(value), indent=2, sort_keys=True), encoding="utf-8")
            try:
                temporary.chmod(0o600)
            except OSError:
                pass
            os.replace(temporary, self.path)
        finally:
            if temporary.exists():
                temporary.unlink()

    def save(self, credential: Any) -> None:
        device_id = safe_text(credential.peer_instance_id)
        token = safe_text(credential.token)
        if not device_id or not token:
            raise PairingError("Finalized pairing credential is incomplete")
        name = f"SARAH_DEVICE_PAIRING:{device_id}"
        with self._lock:
            value = self._read()
            now = int(time.time() * 1000)
            previous = value["devices"].get(device_id)
            value["devices"][device_id] = {
                "device_name": safe_text(credential.peer_device_name),
                "device_type": safe_text(credential.peer_device_type),
                "protected_token": _protect_runtime_secret(name, token, self.root),
                "paired_at": int(credential.established_at) * 1000,
                "updated_at": now,
            }
            value["history"].append({
                "event": "credential_replaced" if previous else "credential_created",
                "device_id_sha256": hashlib.sha256(device_id.encode()).hexdigest(),
                "recorded_at": now,
            })
            self._write(value)

    def token_for(self, device_id: str) -> str:
        exact = safe_text(device_id)
        with self._lock:
            row = self._read()["devices"].get(exact)
            if not isinstance(row, dict):
                return ""
            protected = safe_text(row.get("protected_token"))
            if not protected:
                return ""
            token, was_plaintext = _unprotect_runtime_secret(
                f"SARAH_DEVICE_PAIRING:{exact}", protected, self.root
            )
            if was_plaintext:
                raise PairingError("Unprotected pairing credentials are rejected")
            return token


def _only_rows(payload: Mapping[str, Any], key: str) -> list[dict[str, Any]]:
    rows = payload.get(key, [])
    return [dict(item) for item in rows if isinstance(item, Mapping)] if isinstance(rows, list) else []


def owner_review_export(database: Any) -> dict[str, Any]:
    """Export only owner-approved continuity data; no photos, private mind, or secrets."""
    source = database.export_sync(include_photos=False)
    profile = source.get("profile") if isinstance(source.get("profile"), Mapping) else {}
    allowed_profile = {
        key: profile[key]
        for key in ("name", "age", "age_known", "hometown", "interests", "memory_consent")
        if key in profile
    }
    return {
        "schema": "sarah-sync-v1",
        "device_id": safe_text(source.get("device_id")),
        "created_at": int(source.get("created_at", int(time.time() * 1000))),
        "profile": allowed_profile,
        "messages": _only_rows(source, "messages"),
        "memories": _only_rows(source, "memories"),
        "trips": _only_rows(source, "trips"),
        "wishes": _only_rows(source, "wishes"),
        "mind_events": [],
        "discoveries": [],
        "photos": [],
        "transfer_boundary": {
            "included": ["profile", "messages", "memories", "trips", "wishes"],
            "excluded": [
                "gmail_oauth_tokens", "provider_tokens", "model_tokens", "voice_tokens",
                "raw_private_photos", "private_mind", "discoveries", "other_people",
            ],
        },
    }


def preview_counts(payload: Mapping[str, Any]) -> dict[str, int]:
    return {
        key: len(payload.get(key, [])) if isinstance(payload.get(key), list) else 0
        for key in ("messages", "memories", "trips", "wishes")
    }


class SarahSecureSyncService:
    """Authenticated encrypted post-trust pull service on the pairing TCP listener."""

    def __init__(self, database: Any, vault: PairingCredentialVault):
        self.database = database
        self.vault = vault
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    def __call__(self, connection: Any, first: Mapping[str, Any], peer_host: str) -> None:
        if set(first) != _REQUEST_FIELDS:
            raise PairingError("Secure sync request contains unexpected or missing fields")
        if first.get("schema") != SECURE_SYNC_SCHEMA or first.get("kind") != "preview_request":
            raise PairingError("Secure sync request has the wrong protocol or operation")
        device_id = safe_text(first.get("device_id"))
        request_id = safe_text(first.get("request_id"))
        encrypted = safe_text(first.get("payload"))
        signature = safe_text(first.get("signature"))
        if not device_id or not request_id or len(request_id) > 128:
            raise PairingError("Secure sync identity or request ID is missing")
        token = self.vault.token_for(device_id)
        if not token:
            raise PairingError("This device has no finalized non-revoked pairing credential")
        try:
            with self.database.connect() as connection_db:
                row = connection_db.execute(
                    "SELECT revoked FROM trusted_devices WHERE device_id=?", (device_id,)
                ).fetchone()
            if row is None or int(row[0]) != 0:
                raise PairingError("This device trust was revoked or is not finalized")
        except PairingError:
            raise
        except Exception as exc:
            raise PairingError("Sarah could not verify the device trust record") from exc
        expected = sync_signature(token, encrypted)
        if not hmac.compare_digest(expected, signature):
            raise PairingError("Secure sync request authentication failed")
        try:
            request = json.loads(sync_decrypt(token, encrypted))
        except Exception as exc:
            raise PairingError("Secure sync request decryption failed") from exc
        if (not isinstance(request, dict)
                or request.get("kind") != "preview_request"
                or safe_text(request.get("device_id")) != device_id
                or safe_text(request.get("request_id")) != request_id):
            raise PairingError("Secure sync encrypted identity binding failed")
        with self._lock:
            cutoff = time.monotonic() - 300.0
            self._seen = {key: value for key, value in self._seen.items() if value >= cutoff}
            replay_key = f"{device_id}\0{request_id}"
            if replay_key in self._seen:
                raise PairingError("Secure sync replay was rejected")
            self._seen[replay_key] = time.monotonic()

        payload = owner_review_export(self.database)
        transfer_id = secrets.token_urlsafe(18)
        response_plain = {
            "kind": "preview_response",
            "request_id": request_id,
            "transfer_id": transfer_id,
            "source_name": safe_text(payload.get("profile", {}).get("name")),
            "counts": preview_counts(payload),
            "payload": payload,
            "owner_import_required": True,
            "merge_policy": "APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS",
            "transfer_direction": "WINDOWS_TO_ANDROID_PULL_ONLY",
        }
        response_encrypted = sync_encrypt(token, json.dumps(response_plain, separators=(",", ":")))
        write_json_frame(connection, {
            "schema": SECURE_SYNC_SCHEMA,
            "kind": "preview_response",
            "device_id": device_id,
            "request_id": request_id,
            "payload": response_encrypted,
            "signature": sync_signature(token, response_encrypted),
        })


class SecureSyncPreview:
    def __init__(self, *, host: str, source_name: str, transfer_id: str,
                 counts: Mapping[str, Any], payload: Mapping[str, Any]):
        self.host = host
        self.source_name = source_name
        self.transfer_id = transfer_id
        self.counts = {key: int(counts.get(key, 0)) for key in ("messages", "memories", "trips", "wishes")}
        self.payload = dict(payload)

    def summary(self) -> str:
        return (
            f"Profile: {self.source_name or 'confirmed owner'}\n"
            f"Conversation messages: {self.counts['messages']}\n"
            f"Approved memories: {self.counts['memories']}\n"
            f"Trips: {self.counts['trips']}\n"
            f"Travel wishes: {self.counts['wishes']}\n\n"
            "Photos, Gmail access, provider/model/voice secrets, private-mind records, "
            "discoveries, and other people's data are excluded."
        )


def pull_android_preview(
    *, host: str, port: int, device_id: str, token: str
) -> SecureSyncPreview:
    request_id = secrets.token_urlsafe(18)
    inside = {"kind": "preview_request", "device_id": device_id, "request_id": request_id}
    encrypted = sync_encrypt(token, json.dumps(inside, separators=(",", ":")))
    request = {
        "schema": SECURE_SYNC_SCHEMA, "kind": "preview_request",
        "device_id": device_id, "request_id": request_id,
        "payload": encrypted, "signature": sync_signature(token, encrypted),
    }
    with socket.create_connection((host, int(port)), timeout=10.0) as connection:
        connection.settimeout(30.0)
        write_json_frame(connection, request)
        response = read_json_frame(connection)
    if set(response) != _REQUEST_FIELDS:
        raise PairingError("Android preview response contains unexpected or missing fields")
    if (response.get("schema") != SECURE_SYNC_SCHEMA
            or response.get("kind") != "preview_response"
            or safe_text(response.get("device_id")) != device_id
            or safe_text(response.get("request_id")) != request_id):
        raise PairingError("Android preview response is not bound to this Windows request")
    incoming = safe_text(response.get("payload"))
    if not hmac.compare_digest(sync_signature(token, incoming), safe_text(response.get("signature"))):
        raise PairingError("Android preview response authentication failed")
    try:
        plain = json.loads(sync_decrypt(token, incoming))
    except Exception as exc:
        raise PairingError("Android preview response decryption failed") from exc
    if (not isinstance(plain, dict)
            or plain.get("kind") != "preview_response"
            or safe_text(plain.get("request_id")) != request_id
            or plain.get("owner_import_required") is not True
            or plain.get("transfer_direction") != "ANDROID_TO_WINDOWS_PULL_ONLY"):
        raise PairingError("Android preview encrypted direction or request binding failed")
    payload = plain.get("payload")
    if not isinstance(payload, dict) or payload.get("schema") != "sarah-sync-v1":
        raise PairingError("Android preview has no supported continuity package")
    for key in ("photos", "mind_events", "discoveries"):
        if payload.get(key):
            raise PairingError(f"Android preview crossed the approved boundary: {key}")
    if not isinstance(payload.get("transfer_boundary"), dict):
        raise PairingError("Android preview omitted its transfer-boundary receipt")
    return SecureSyncPreview(
        host=host, source_name=safe_text(plain.get("source_name")),
        transfer_id=safe_text(plain.get("transfer_id")),
        counts=plain.get("counts") if isinstance(plain.get("counts"), dict) else {},
        payload=payload,
    )


def _append_import_receipt(root: Path, value: Mapping[str, Any]) -> None:
    path = Path(root) / "secure_sync_import_history.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as output:
        output.write(json.dumps(dict(value), sort_keys=True, separators=(",", ":")) + "\n")
        output.flush()
        os.fsync(output.fileno())


def import_reviewed_android_preview(database: Any, preview: SecureSyncPreview) -> dict[str, int]:
    digest = hashlib.sha256(
        json.dumps(preview.payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    common = {
        "recorded_at": int(time.time() * 1000), "host": preview.host,
        "transfer_id": preview.transfer_id, "package_sha256": digest,
        "offered_counts": preview.counts,
        "merge_policy": "APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS",
        "excluded": "Gmail/provider/model/voice tokens; photos; private mind; discoveries; other people",
    }
    _append_import_receipt(database.root, {**common, "event": "OWNER_APPROVED_SECURE_SYNC_IMPORT"})
    counts = database.import_sync(preview.payload, confirm_owner_change=True)
    offered = sum(preview.counts.values())
    imported = sum(int(value) for value in counts.values())
    _append_import_receipt(database.root, {
        **common, "recorded_at": int(time.time() * 1000),
        "event": "SECURE_SYNC_IMPORT_COMPLETED", "new_rows_imported": imported,
        "not_added_existing_or_rejected": max(0, offered - imported),
    })
    return counts
