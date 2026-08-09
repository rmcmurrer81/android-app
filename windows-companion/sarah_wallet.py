from __future__ import annotations

"""Profile-isolated, current-user-protected Sarah loyalty/ticket wallet.

This module is deliberately backend-only.  It does not purchase anything,
open provider sessions, sync image bytes, or infer that an owner-provided pass
proves admission or payment.
"""

import base64
import contextlib
import dataclasses
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
from typing import Any, Mapping
from urllib.parse import urlsplit

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageOps, UnidentifiedImageError

from sarah_core import (
    SarahDatabase,
    _protect_runtime_secret,
    _unprotect_runtime_secret,
    app_home,
    safe_text,
)


WALLET_SCHEMA = "sarah-windows-owner-wallet-v1"
KEY_SCHEMA = "sarah-windows-owner-wallet-key-v1"
KEY_PROTECTION_NAME = "SARAH_WINDOWS_OWNER_WALLET_DATA_KEY"
RECORD_AAD_PREFIX = b"SarahWindowsOwnerWalletRecordV1:"

MAX_RECORDS_PER_PROFILE = 100
MAX_TOTAL_RECORDS = 400
MAX_VAULT_BYTES = 64 * 1024 * 1024
MAX_SOURCE_IMAGE_BYTES = 12 * 1024 * 1024
MAX_SANITIZED_IMAGE_BYTES = 4 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_IMAGE_EDGE = 2048
MAX_URL_LENGTH = 2048

LOYALTY_FIELDS = {
    "program_name": 120,
    "member_name": 120,
    "member_identifier": 160,
    "tier": 80,
    "notes": 500,
}
TICKET_FIELDS = {
    "title": 160,
    "issuer": 120,
    "event_name": 160,
    "venue": 200,
    "starts_at": 80,
    "ends_at": 80,
    "seat": 100,
    "notes": 500,
}
SENSITIVE_TEXT = re.compile(
    r"(?i)\b(?:password|passphrase|passcode|security code|cvv|cvc|pin|api[ _-]?key|"
    r"private[ _-]?key|bank account|routing number)\b"
)
PAYMENT_NUMBER = re.compile(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)")


class WalletError(RuntimeError):
    pass


class WalletValidationError(ValueError):
    pass


class WalletLimitError(WalletValidationError):
    pass


@dataclasses.dataclass(frozen=True)
class SanitizedImage:
    data: bytes
    mime_type: str
    sha256: str
    width: int
    height: int
    original_name: str


def _now_ms() -> int:
    return int(time.time() * 1000)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _luhn_valid(candidate: str) -> bool:
    digits = [int(char) for char in re.sub(r"\D", "", candidate)]
    if not 13 <= len(digits) <= 19:
        return False
    total = 0
    parity = len(digits) % 2
    for index, digit in enumerate(digits):
        if index % 2 == parity:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
    return total % 10 == 0


def _bounded_text(
    name: str,
    value: Any,
    limit: int,
    *,
    required: bool = False,
    reject_secrets: bool = False,
    reject_payment: bool = False,
) -> str:
    text = re.sub(r"\s+", " ", safe_text(value)).strip()
    if required and not text:
        raise WalletValidationError(f"{name} is required")
    if len(text) > limit:
        raise WalletValidationError(f"{name} exceeds {limit} characters")
    if reject_secrets and SENSITIVE_TEXT.search(text):
        raise WalletValidationError(f"{name} appears to contain a password, payment secret, or credential")
    if reject_payment:
        for match in PAYMENT_NUMBER.finditer(text):
            if _luhn_valid(match.group(0)):
                raise WalletValidationError(f"{name} appears to contain a payment-card number")
    return text


def exact_https_url(value: Any, *, required: bool) -> str:
    text = safe_text(value)
    if not text:
        if required:
            raise WalletValidationError("An exact official HTTPS URL is required")
        return ""
    if len(text) > MAX_URL_LENGTH or any(char.isspace() or ord(char) < 32 for char in text):
        raise WalletValidationError("Official URL is invalid or too long")
    try:
        parsed = urlsplit(text)
    except ValueError as error:
        raise WalletValidationError("Official URL is malformed") from error
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise WalletValidationError("Official URL must use HTTPS and include a host")
    try:
        parsed.port
    except ValueError as error:
        raise WalletValidationError("Official URL contains an invalid port") from error
    if parsed.username or parsed.password:
        raise WalletValidationError("Official URL must not contain embedded credentials")
    sensitive_query_name = re.compile(r"(?i)(?:password|passcode|secret|api[_-]?key|access[_-]?token|auth)")
    for pair in parsed.query.split("&") if parsed.query else ():
        name = pair.split("=", 1)[0]
        if sensitive_query_name.fullmatch(name):
            raise WalletValidationError("Official URL must not contain a credential query parameter")
    hostname = parsed.hostname.rstrip(".").lower()
    if hostname == "localhost" or hostname.endswith(".local"):
        raise WalletValidationError("Official URL must not target a local host")
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        address = None
    if address is not None and not address.is_global:
        raise WalletValidationError("Official URL must not target a private or loopback address")
    # Preserve the owner's exact text. Validation must not silently rewrite a
    # ticket path/query into a different destination.
    return text


def sanitize_owner_image(path: Path) -> SanitizedImage:
    source = Path(path).expanduser().resolve()
    if not source.is_file():
        raise WalletValidationError("The selected ticket/pass image does not exist")
    source_size = source.stat().st_size
    if source_size <= 0 or source_size > MAX_SOURCE_IMAGE_BYTES:
        raise WalletLimitError("The selected image exceeds the 12 MiB source limit")
    try:
        with Image.open(source) as opened:
            opened.verify()
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened)
            width, height = image.size
            if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
                raise WalletLimitError("The selected image exceeds the pixel limit")
            # QR/barcode edges must not be converted to lossy JPEG.  RGB/RGBA
            # content is converted to PNG and metadata is intentionally omitted.
            if image.mode not in {"1", "L", "LA", "P", "RGB", "RGBA"}:
                image = image.convert("RGBA")
            if max(image.size) > MAX_IMAGE_EDGE:
                image.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.Resampling.LANCZOS)
            buffer = io.BytesIO()
            image.save(buffer, format="PNG", optimize=True)
    except (UnidentifiedImageError, OSError, ValueError) as error:
        raise WalletValidationError("The selected file is not a readable supported image") from error
    data = buffer.getvalue()
    if len(data) > MAX_SANITIZED_IMAGE_BYTES:
        raise WalletLimitError("Sanitized PNG exceeds the 4 MiB ticket/pass limit")
    return SanitizedImage(
        data=data,
        mime_type="image/png",
        sha256=_sha256(data),
        width=image.size[0],
        height=image.size[1],
        original_name=_bounded_text("image filename", source.name, 180, required=True),
    )


class SarahWallet:
    """Encrypted-at-rest wallet bound to an exact Sarah profile."""

    def __init__(self, database: SarahDatabase, root: Path | None = None):
        self.db = database
        self.root = (root or database.root or app_home()).resolve()
        self.wallet_root = self.root / "owner_wallet"
        self.key_path = self.wallet_root / "wallet-key.json"
        self.vault_path = self.wallet_root / "wallet-records.json"
        self.lock = threading.RLock()
        self.wallet_root.mkdir(parents=True, exist_ok=True)
        self._data_key = self._load_or_create_data_key()

    def _load_or_create_data_key(self) -> bytes:
        if self.key_path.exists():
            try:
                raw = json.loads(self.key_path.read_text(encoding="utf-8"))
                if raw.get("schema") != KEY_SCHEMA:
                    raise WalletError("Wallet key schema is not supported")
                opened, _legacy = _unprotect_runtime_secret(
                    KEY_PROTECTION_NAME, safe_text(raw.get("protected_key")), self.root,
                )
                key = base64.b64decode(opened, validate=True)
                if len(key) != 32:
                    raise WalletError("Wallet data key has an invalid length")
                return key
            except WalletError:
                raise
            except Exception as error:
                raise WalletError("Wallet key could not be opened for this Windows user") from error
        key = AESGCM.generate_key(bit_length=256)
        protected = _protect_runtime_secret(
            KEY_PROTECTION_NAME, base64.b64encode(key).decode("ascii"), self.root,
        )
        self._atomic_json_write(self.key_path, {
            "schema": KEY_SCHEMA,
            "protected_key": protected,
            "created_at": _now_ms(),
        })
        return key

    def _empty_vault(self) -> dict[str, Any]:
        return {"schema": WALLET_SCHEMA, "records": [], "audit": []}

    def _load_vault(self) -> dict[str, Any]:
        if not self.vault_path.exists():
            return self._empty_vault()
        if self.vault_path.stat().st_size > MAX_VAULT_BYTES:
            raise WalletError("Wallet vault exceeds its documented maximum")
        try:
            raw = json.loads(self.vault_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError) as error:
            raise WalletError("Wallet vault is unreadable; it was not overwritten") from error
        if raw.get("schema") != WALLET_SCHEMA or not isinstance(raw.get("records"), list):
            raise WalletError("Wallet vault schema is not supported")
        return raw

    def _save_vault(self, vault: Mapping[str, Any]) -> None:
        encoded = json.dumps(dict(vault), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > MAX_VAULT_BYTES:
            raise WalletLimitError("Wallet vault would exceed its 64 MiB maximum")
        self._atomic_bytes_write(self.vault_path, encoded)

    @staticmethod
    def _atomic_json_write(path: Path, payload: Mapping[str, Any]) -> None:
        SarahWallet._atomic_bytes_write(
            path, json.dumps(dict(payload), indent=2, ensure_ascii=False).encode("utf-8"),
        )

    @staticmethod
    def _atomic_bytes_write(path: Path, payload: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(f".{path.name}.{secrets.token_hex(6)}.tmp")
        try:
            with temporary.open("xb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            with contextlib.suppress(OSError):
                temporary.chmod(0o600)
            os.replace(temporary, path)
        finally:
            with contextlib.suppress(FileNotFoundError):
                temporary.unlink()

    def _profile_id(self, person_id: str | None) -> str:
        selected = safe_text(person_id) or safe_text(self.db.get_setting("active_person_id"))
        if not selected or not self.db.profile(selected).get("person_id"):
            raise WalletValidationError("Select an exact Sarah profile before using the wallet")
        return selected

    def _seal_record(self, record_id: str, record: Mapping[str, Any]) -> str:
        nonce = secrets.token_bytes(12)
        plaintext = json.dumps(dict(record), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        encrypted = AESGCM(self._data_key).encrypt(
            nonce, plaintext, RECORD_AAD_PREFIX + record_id.encode("ascii"),
        )
        return base64.b64encode(nonce + encrypted).decode("ascii")

    def _open_record(self, envelope: Mapping[str, Any]) -> dict[str, Any]:
        record_id = safe_text(envelope.get("record_id"))
        try:
            raw = base64.b64decode(safe_text(envelope.get("ciphertext")), validate=True)
            if len(raw) < 29:
                raise ValueError("truncated")
            plaintext = AESGCM(self._data_key).decrypt(
                raw[:12], raw[12:], RECORD_AAD_PREFIX + record_id.encode("ascii"),
            )
            record = json.loads(plaintext.decode("utf-8"))
        except Exception as error:
            raise WalletError(f"Wallet record {record_id or '<unknown>'} failed authentication") from error
        if safe_text(record.get("record_id")) != record_id:
            raise WalletError("Wallet record identity does not match its encrypted envelope")
        return record

    def _insert(self, record: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            vault = self._load_vault()
            opened = [self._open_record(item) for item in vault["records"]]
            profile_records = [row for row in opened if row.get("person_id") == record["person_id"]]
            if len(profile_records) >= MAX_RECORDS_PER_PROFILE:
                raise WalletLimitError("This profile has reached the 100-record wallet maximum")
            if len(opened) >= MAX_TOTAL_RECORDS:
                raise WalletLimitError("This device has reached the 400-record wallet maximum")
            fingerprint = safe_text(record.get("fingerprint"))
            if any(row.get("person_id") == record["person_id"] and row.get("fingerprint") == fingerprint for row in opened):
                raise WalletValidationError("This exact wallet record is already saved for the profile")
            vault["records"].append({
                "record_id": record["record_id"],
                "ciphertext": self._seal_record(record["record_id"], record),
            })
            audit = vault.setdefault("audit", [])
            audit.append({
                "event": "record_added",
                "record_id_hash": hashlib.sha256(record["record_id"].encode("ascii")).hexdigest(),
                "created_at": record["created_at"],
            })
            vault["audit"] = audit[-1000:]
            self._save_vault(vault)
        return self._public_record(record)

    def add_loyalty(
        self,
        *,
        program_name: str,
        member_identifier: str,
        member_name: str = "",
        tier: str = "",
        notes: str = "",
        official_url: str = "",
        code_image_path: Path | None = None,
        person_id: str | None = None,
    ) -> dict[str, Any]:
        profile_id = self._profile_id(person_id)
        fields = {
            "program_name": _bounded_text("program name", program_name, LOYALTY_FIELDS["program_name"], required=True),
            "member_name": _bounded_text("member name", member_name, LOYALTY_FIELDS["member_name"]),
            "member_identifier": _bounded_text(
                "member identifier", member_identifier, LOYALTY_FIELDS["member_identifier"],
                required=True, reject_secrets=True, reject_payment=True,
            ),
            "tier": _bounded_text("tier", tier, LOYALTY_FIELDS["tier"]),
            "notes": _bounded_text(
                "notes", notes, LOYALTY_FIELDS["notes"], reject_secrets=True, reject_payment=True,
            ),
        }
        return self._insert(self._new_record(
            "loyalty", profile_id, fields,
            exact_https_url(official_url, required=False), code_image_path,
        ))

    def add_ticket_pass(
        self,
        *,
        title: str,
        official_url: str,
        image_path: Path,
        metadata: Mapping[str, Any] | None = None,
        person_id: str | None = None,
    ) -> dict[str, Any]:
        profile_id = self._profile_id(person_id)
        supplied = dict(metadata or {})
        unknown = sorted(set(supplied) - set(TICKET_FIELDS))
        if unknown:
            raise WalletValidationError("Unsupported ticket/pass metadata: " + ", ".join(unknown))
        supplied["title"] = title
        fields = {}
        for name, limit in TICKET_FIELDS.items():
            fields[name] = _bounded_text(
                name.replace("_", " "), supplied.get(name), limit,
                required=name == "title",
                reject_secrets=name == "notes",
                reject_payment=name == "notes",
            )
        return self._insert(self._new_record(
            "ticket_pass", profile_id, fields,
            exact_https_url(official_url, required=True), image_path,
        ))

    def _new_record(
        self,
        record_type: str,
        person_id: str,
        fields: Mapping[str, str],
        official_url: str,
        image_path: Path | None,
    ) -> dict[str, Any]:
        image = sanitize_owner_image(Path(image_path)) if image_path is not None else None
        canonical = json.dumps({
            "record_type": record_type,
            "person_id": person_id,
            "fields": dict(fields),
            "official_url": official_url,
            "image_sha256": image.sha256 if image else "",
        }, sort_keys=True, ensure_ascii=False)
        created_at = _now_ms()
        record_id = "wallet_" + secrets.token_urlsafe(18)
        return {
            "record_id": record_id,
            "record_type": record_type,
            "person_id": person_id,
            "fields": dict(fields),
            "official_url": official_url,
            "image": ({
                "data_b64": base64.b64encode(image.data).decode("ascii"),
                "mime_type": image.mime_type,
                "sha256": image.sha256,
                "width": image.width,
                "height": image.height,
                "original_name": image.original_name,
            } if image else None),
            "fingerprint": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
            "created_at": created_at,
            "owner_truth": "OWNER_PROVIDED_REFERENCE_NOT_PURCHASE_OR_ADMISSION_PROOF",
        }

    def list_records(self, *, person_id: str | None = None, record_type: str = "") -> list[dict[str, Any]]:
        profile_id = self._profile_id(person_id)
        if record_type and record_type not in {"loyalty", "ticket_pass"}:
            raise WalletValidationError("record_type must be loyalty or ticket_pass")
        with self.lock:
            vault = self._load_vault()
            records = [self._open_record(item) for item in vault["records"]]
        selected = [
            self._public_record(record) for record in records
            if record.get("person_id") == profile_id
            and (not record_type or record.get("record_type") == record_type)
        ]
        return sorted(selected, key=lambda row: int(row.get("created_at", 0)), reverse=True)

    def get_image_bytes(self, record_id: str, *, person_id: str | None = None) -> tuple[bytes, str]:
        record = self._find_owned(record_id, person_id)
        image = record.get("image")
        if not isinstance(image, dict):
            raise WalletValidationError("This wallet record has no image")
        try:
            data = base64.b64decode(safe_text(image.get("data_b64")), validate=True)
        except ValueError as error:
            raise WalletError("Wallet image encoding is invalid") from error
        if len(data) > MAX_SANITIZED_IMAGE_BYTES or _sha256(data) != image.get("sha256"):
            raise WalletError("Wallet image failed its size or SHA-256 check")
        return data, safe_text(image.get("mime_type"))

    def _find_owned(self, record_id: str, person_id: str | None) -> dict[str, Any]:
        profile_id = self._profile_id(person_id)
        exact_id = safe_text(record_id)
        with self.lock:
            vault = self._load_vault()
            for item in vault["records"]:
                if safe_text(item.get("record_id")) != exact_id:
                    continue
                record = self._open_record(item)
                if record.get("person_id") != profile_id:
                    break
                return record
        raise WalletValidationError("Wallet record was not found for the active profile")

    def remove_record(self, record_id: str, *, person_id: str | None = None) -> dict[str, Any]:
        profile_id = self._profile_id(person_id)
        exact_id = safe_text(record_id)
        with self.lock:
            vault = self._load_vault()
            kept: list[dict[str, Any]] = []
            removed: dict[str, Any] | None = None
            for item in vault["records"]:
                if safe_text(item.get("record_id")) == exact_id:
                    candidate = self._open_record(item)
                    if candidate.get("person_id") == profile_id:
                        removed = candidate
                        continue
                kept.append(item)
            if removed is None:
                raise WalletValidationError("Wallet record was not found for the active profile")
            vault["records"] = kept
            audit = vault.setdefault("audit", [])
            audit.append({
                "event": "record_removed",
                "record_id_hash": hashlib.sha256(exact_id.encode("ascii")).hexdigest(),
                "created_at": _now_ms(),
            })
            vault["audit"] = audit[-1000:]
            self._save_vault(vault)
        return {
            "record_id": exact_id,
            "removed": True,
            "image_removed": bool(removed.get("image")),
            "logical_record_recovery_supported": False,
            "forensic_storage_recovery": "NOT_ASSESSED",
        }

    @staticmethod
    def _public_record(record: Mapping[str, Any]) -> dict[str, Any]:
        image = record.get("image") if isinstance(record.get("image"), dict) else None
        return {
            "record_id": record.get("record_id"),
            "record_type": record.get("record_type"),
            "fields": dict(record.get("fields") or {}),
            "official_url": record.get("official_url", ""),
            "has_image": bool(image),
            "image_sha256": image.get("sha256") if image else "",
            "image_mime_type": image.get("mime_type") if image else "",
            "image_width": image.get("width") if image else 0,
            "image_height": image.get("height") if image else 0,
            "created_at": record.get("created_at"),
            "owner_truth": record.get("owner_truth"),
        }

    def sync_projection(self, *, person_id: str | None = None) -> list[dict[str, Any]]:
        """Non-connected metadata projection; image/member bytes are excluded.

        No current sync path calls this method.  It exists so a future sync
        implementation has an explicit fail-closed contract rather than
        serializing encrypted record payloads accidentally.
        """
        projection: list[dict[str, Any]] = []
        for record in self.list_records(person_id=person_id):
            fields = record["fields"]
            title = fields.get("program_name") or fields.get("title") or "Wallet item"
            projection.append({
                "record_id": record["record_id"],
                "record_type": record["record_type"],
                "title": title,
                "official_url": record["official_url"],
                "has_image": record["has_image"],
                "image_sha256": record["image_sha256"],
                "owner_truth": record["owner_truth"],
            })
        return projection

    def storage_status(self) -> dict[str, Any]:
        with self.lock:
            vault = self._load_vault()
        return {
            "record_count": len(vault["records"]),
            "vault_bytes": self.vault_path.stat().st_size if self.vault_path.exists() else 0,
            "max_records_per_profile": MAX_RECORDS_PER_PROFILE,
            "max_total_records": MAX_TOTAL_RECORDS,
            "max_source_image_bytes": MAX_SOURCE_IMAGE_BYTES,
            "max_sanitized_image_bytes": MAX_SANITIZED_IMAGE_BYTES,
            "max_vault_bytes": MAX_VAULT_BYTES,
            "current_user_key_protection": "WINDOWS_DPAPI" if os.name == "nt" else "LOCAL_TEST_FALLBACK",
        }
