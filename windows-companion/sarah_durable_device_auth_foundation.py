"""Staged Windows foundation for Sarah's durable per-device authentication.

Status: STAGED_NOT_CONNECTED. This module is deliberately not imported by the
current UI, event candidate, model/voice routes, installer, or build workflow.
It contains no endpoint, bearer, provider credential, device ID, access token,
or refresh token.

On Windows, the private P-256 key is a persisted Microsoft Software Key Storage
Provider (CNG) key. Its export policy is explicitly zero, it is scoped to the
current user (the machine-key flag is never used), and only signing is allowed.
The caller must supply durable device-binding truth. A bound identity whose CNG
key is missing becomes KEY_MISSING_REENROLL_REQUIRED; this module never creates
a replacement under the old device identity.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import base64
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
import re
import struct
from typing import Any
from urllib.parse import urlsplit


IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED"
CURRENT_KEY_VERSION = 1
DEFAULT_KEY_NAMESPACE = "SarahMorganDurableDeviceAuthP256V1.Key"
P256_COORDINATE_BYTES = 32
P256_SIGNATURE_BYTES = 64

_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")


class DeviceBinding(str, Enum):
    UNENROLLED = "UNENROLLED"
    BOUND_TO_DEVICE = "BOUND_TO_DEVICE"


class KeyState(str, Enum):
    UNENROLLED_KEY_ABSENT = "UNENROLLED_KEY_ABSENT"
    READY = "READY"
    KEY_MISSING = "KEY_MISSING"
    KEYSTORE_ERROR = "KEYSTORE_ERROR"


class DurableCredentialError(RuntimeError):
    """Credential operation failed closed without changing device identity."""


@dataclass(frozen=True)
class PublicCredential:
    key_version: int
    key_name: str
    public_jwk: dict[str, str]
    key_thumbprint: str
    protection: str = "WINDOWS_CNG_CURRENT_USER_NON_EXPORTABLE"


@dataclass(frozen=True)
class CredentialInspection:
    state: KeyState
    key_version: int
    key_name: str
    credential: PublicCredential | None
    diagnostic_code: str

    @property
    def usable(self) -> bool:
        return self.state is KeyState.READY and self.credential is not None


def _base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def public_jwk_from_coordinates(x: bytes, y: bytes) -> dict[str, str]:
    if len(x) != P256_COORDINATE_BYTES or len(y) != P256_COORDINATE_BYTES:
        raise ValueError("P-256 public coordinates must each be exactly 32 bytes")
    return {"kty": "EC", "crv": "P-256", "x": _base64url(x), "y": _base64url(y)}


def public_jwk_thumbprint(public_jwk: dict[str, Any]) -> str:
    if not isinstance(public_jwk, dict) or set(public_jwk) != {"kty", "crv", "x", "y"}:
        raise ValueError("public JWK must contain exactly kty, crv, x, and y")
    if public_jwk.get("kty") != "EC" or public_jwk.get("crv") != "P-256":
        raise ValueError("public JWK must be P-256 EC")
    for name in ("x", "y"):
        value = public_jwk.get(name)
        if not isinstance(value, str) or not _BASE64URL_RE.fullmatch(value):
            raise ValueError(f"public JWK {name} must be canonical base64url")
        try:
            decoded = base64.urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4))
        except Exception as error:
            raise ValueError(f"public JWK {name} is not base64url") from error
        if len(decoded) != P256_COORDINATE_BYTES or _base64url(decoded) != value:
            raise ValueError(f"public JWK {name} must encode exactly 32 bytes")
    canonical = json.dumps(
        {
            "crv": "P-256",
            "kty": "EC",
            "x": public_jwk["x"],
            "y": public_jwk["y"],
        },
        ensure_ascii=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return _base64url(hashlib.sha256(canonical).digest())


def canonical_enrollment_challenge(
    enrollment_id: str,
    challenge: str,
    api_origin: str,
    key_thumbprint: str,
) -> str:
    thumbprint = _canonical_base64url(key_thumbprint, "key_thumbprint", 32)
    return "\n".join((
        "SARAH-ENROLLMENT-V1",
        _canonical_field(enrollment_id, "enrollment_id", 256),
        _canonical_field(challenge, "challenge", 1024),
        _canonical_api_origin(api_origin),
        thumbprint,
    ))


def canonical_session_challenge(
    device_id: str,
    challenge_id: str,
    nonce: str,
    api_origin: str,
    key_version: int,
) -> str:
    _require_key_version(key_version)
    return "\n".join((
        "SARAH-AUTH-V1",
        _canonical_field(device_id, "device_id", 256),
        _canonical_field(challenge_id, "challenge_id", 256),
        _canonical_field(nonce, "nonce", 1024),
        _canonical_api_origin(api_origin),
        str(key_version),
    ))


def _canonical_field(value: str, name: str, maximum: int) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum or value != value.strip():
        raise ValueError(f"{name} is missing or noncanonical")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError(f"{name} contains a control character")
    return value


def _canonical_base64url(value: str, name: str, exact_bytes: int) -> str:
    clean = _canonical_field(value, name, exact_bytes * 2)
    if not _BASE64URL_RE.fullmatch(clean):
        raise ValueError(f"{name} must be unpadded base64url")
    try:
        decoded = base64.urlsafe_b64decode(clean + "=" * ((4 - len(clean) % 4) % 4))
    except Exception as error:
        raise ValueError(f"{name} must be canonical base64url") from error
    if len(decoded) != exact_bytes or _base64url(decoded) != clean:
        raise ValueError(f"{name} must encode exactly {exact_bytes} bytes")
    return clean


def _canonical_api_origin(value: str) -> str:
    origin = _canonical_field(value, "api_origin", 2048)
    try:
        parsed = urlsplit(origin)
        port = parsed.port
    except ValueError as error:
        raise ValueError("api_origin must be one canonical HTTPS origin") from error
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.hostname != parsed.hostname.lower()
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path
        or parsed.query
        or parsed.fragment
        or port == 443
        or not origin.isascii()
    ):
        raise ValueError("api_origin must be one canonical HTTPS origin")
    canonical_netloc = parsed.hostname if port is None else f"{parsed.hostname}:{port}"
    if parsed.netloc != canonical_netloc or origin != f"https://{canonical_netloc}":
        raise ValueError("api_origin must be one canonical HTTPS origin")
    return origin


def _require_key_version(key_version: int) -> None:
    if isinstance(key_version, bool) or not isinstance(key_version, int) or key_version < 1:
        raise ValueError("key_version must be a positive integer")


def _require_binding(binding: DeviceBinding) -> DeviceBinding:
    if not isinstance(binding, DeviceBinding):
        raise ValueError("durable device-binding truth is required")
    return binding


class _NcryptApi:
    """Small fail-closed ctypes boundary around Microsoft CNG NCrypt."""

    ERROR_SUCCESS = 0
    NTE_BAD_KEYSET = 0x80090016
    NTE_NOT_FOUND = 0x80090011
    HRESULT_FILE_NOT_FOUND = 0x80070002

    NCRYPT_PERSIST_FLAG = 0x80000000
    NCRYPT_ALLOW_SIGNING_FLAG = 0x00000002
    NCRYPT_SILENT_FLAG = 0x00000040

    ECDSA_P256_ALGORITHM = "ECDSA_P256"
    SOFTWARE_KEY_STORAGE_PROVIDER = "Microsoft Software Key Storage Provider"
    ECCPUBLICBLOB = "ECCPUBLICBLOB"
    ECCPRIVATEBLOB = "ECCPRIVATEBLOB"
    ECCPRIVATEBLOB = "ECCPRIVATEBLOB"
    ECDSA_PUBLIC_P256_MAGIC = 0x31534345

    EXPORT_POLICY_PROPERTY = "Export Policy"
    KEY_USAGE_PROPERTY = "Key Usage"

    def __init__(self) -> None:
        if os.name != "nt":
            raise OSError("Windows CNG device credentials are available only on Windows")
        self.lib = ctypes.WinDLL("ncrypt.dll", use_last_error=True)
        self._configure_signatures()

    def _configure_signatures(self) -> None:
        handle_pointer = ctypes.POINTER(ctypes.c_void_p)
        byte_pointer = ctypes.POINTER(ctypes.c_ubyte)
        dword_pointer = ctypes.POINTER(wintypes.DWORD)

        self.lib.NCryptOpenStorageProvider.argtypes = [
            handle_pointer, wintypes.LPCWSTR, wintypes.DWORD,
        ]
        self.lib.NCryptOpenStorageProvider.restype = ctypes.c_long
        self.lib.NCryptOpenKey.argtypes = [
            ctypes.c_void_p, handle_pointer, wintypes.LPCWSTR,
            wintypes.DWORD, wintypes.DWORD,
        ]
        self.lib.NCryptOpenKey.restype = ctypes.c_long
        self.lib.NCryptCreatePersistedKey.argtypes = [
            ctypes.c_void_p, handle_pointer, wintypes.LPCWSTR, wintypes.LPCWSTR,
            wintypes.DWORD, wintypes.DWORD,
        ]
        self.lib.NCryptCreatePersistedKey.restype = ctypes.c_long
        self.lib.NCryptSetProperty.argtypes = [
            ctypes.c_void_p, wintypes.LPCWSTR, byte_pointer,
            wintypes.DWORD, wintypes.DWORD,
        ]
        self.lib.NCryptSetProperty.restype = ctypes.c_long
        self.lib.NCryptGetProperty.argtypes = [
            ctypes.c_void_p, wintypes.LPCWSTR, byte_pointer,
            wintypes.DWORD, dword_pointer, wintypes.DWORD,
        ]
        self.lib.NCryptGetProperty.restype = ctypes.c_long
        self.lib.NCryptFinalizeKey.argtypes = [ctypes.c_void_p, wintypes.DWORD]
        self.lib.NCryptFinalizeKey.restype = ctypes.c_long
        self.lib.NCryptExportKey.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, wintypes.LPCWSTR, ctypes.c_void_p,
            byte_pointer, wintypes.DWORD, dword_pointer, wintypes.DWORD,
        ]
        self.lib.NCryptExportKey.restype = ctypes.c_long
        self.lib.NCryptSignHash.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, byte_pointer, wintypes.DWORD,
            byte_pointer, wintypes.DWORD, dword_pointer, wintypes.DWORD,
        ]
        self.lib.NCryptSignHash.restype = ctypes.c_long
        self.lib.NCryptDeleteKey.argtypes = [ctypes.c_void_p, wintypes.DWORD]
        self.lib.NCryptDeleteKey.restype = ctypes.c_long
        self.lib.NCryptFreeObject.argtypes = [ctypes.c_void_p]
        self.lib.NCryptFreeObject.restype = ctypes.c_long

    @staticmethod
    def status_code(status: int) -> int:
        return int(status) & 0xFFFFFFFF

    def check(self, status: int, operation: str) -> None:
        code = self.status_code(status)
        if code != self.ERROR_SUCCESS:
            raise DurableCredentialError(f"{operation} failed closed (CNG 0x{code:08X})")

    def is_missing(self, status: int) -> bool:
        return self.status_code(status) in {
            self.NTE_BAD_KEYSET,
            self.NTE_NOT_FOUND,
            self.HRESULT_FILE_NOT_FOUND,
        }

    def open_provider(self) -> ctypes.c_void_p:
        provider = ctypes.c_void_p()
        self.check(
            self.lib.NCryptOpenStorageProvider(
                ctypes.byref(provider), self.SOFTWARE_KEY_STORAGE_PROVIDER, 0
            ),
            "open CNG provider",
        )
        return provider

    def try_open_key(self, provider: ctypes.c_void_p, key_name: str) -> ctypes.c_void_p | None:
        key = ctypes.c_void_p()
        status = self.lib.NCryptOpenKey(
            provider, ctypes.byref(key), key_name, 0, self.NCRYPT_SILENT_FLAG
        )
        if self.is_missing(status):
            return None
        self.check(status, "open CNG key")
        return key

    def free(self, handle: ctypes.c_void_p | None) -> None:
        if handle and handle.value:
            self.lib.NCryptFreeObject(handle)

    def set_dword(self, handle: ctypes.c_void_p, name: str, value: int) -> None:
        encoded = wintypes.DWORD(value)
        pointer = ctypes.cast(ctypes.byref(encoded), ctypes.POINTER(ctypes.c_ubyte))
        self.check(
            self.lib.NCryptSetProperty(
                handle,
                name,
                pointer,
                ctypes.sizeof(encoded),
                self.NCRYPT_PERSIST_FLAG,
            ),
            f"set CNG {name}",
        )

    def get_dword(self, handle: ctypes.c_void_p, name: str) -> int:
        encoded = wintypes.DWORD()
        written = wintypes.DWORD()
        pointer = ctypes.cast(ctypes.byref(encoded), ctypes.POINTER(ctypes.c_ubyte))
        self.check(
            self.lib.NCryptGetProperty(
                handle, name, pointer, ctypes.sizeof(encoded), ctypes.byref(written), 0
            ),
            f"read CNG {name}",
        )
        if written.value != ctypes.sizeof(encoded):
            raise DurableCredentialError(f"CNG {name} has an unexpected size")
        return int(encoded.value)

    def export_public_blob(self, key: ctypes.c_void_p) -> bytes:
        size = wintypes.DWORD()
        self.check(
            self.lib.NCryptExportKey(
                key, None, self.ECCPUBLICBLOB, None, None, 0, ctypes.byref(size), 0
            ),
            "size CNG public key",
        )
        if size.value < 8 or size.value > 4096:
            raise DurableCredentialError("CNG public-key blob size is invalid")
        buffer = (ctypes.c_ubyte * size.value)()
        self.check(
            self.lib.NCryptExportKey(
                key,
                None,
                self.ECCPUBLICBLOB,
                None,
                buffer,
                size.value,
                ctypes.byref(size),
                0,
            ),
            "export CNG public key",
        )
        return bytes(buffer[:size.value])

    def private_export_is_blocked(self, key: ctypes.c_void_p) -> bool:
        """Probe only the required size; never allocate or receive private bytes."""
        size = wintypes.DWORD()
        status = self.lib.NCryptExportKey(
            key, None, self.ECCPRIVATEBLOB, None, None, 0, ctypes.byref(size), 0
        )
        return self.status_code(status) != self.ERROR_SUCCESS

    def private_export_is_blocked(self, key: ctypes.c_void_p) -> bool:
        """Probe only the required size; never allocate or receive private bytes."""
        size = wintypes.DWORD()
        status = self.lib.NCryptExportKey(
            key, None, self.ECCPRIVATEBLOB, None, None, 0, ctypes.byref(size), 0
        )
        return self.status_code(status) != self.ERROR_SUCCESS

    def sign_hash(self, key: ctypes.c_void_p, digest: bytes) -> bytes:
        if len(digest) != 32:
            raise ValueError("P-256 SHA-256 signing requires a 32-byte digest")
        digest_buffer = (ctypes.c_ubyte * len(digest)).from_buffer_copy(digest)
        size = wintypes.DWORD()
        self.check(
            self.lib.NCryptSignHash(
                key,
                None,
                digest_buffer,
                len(digest),
                None,
                0,
                ctypes.byref(size),
                self.NCRYPT_SILENT_FLAG,
            ),
            "size CNG signature",
        )
        if size.value != P256_SIGNATURE_BYTES:
            raise DurableCredentialError("CNG P-256 signature is not IEEE-P1363 width")
        signature = (ctypes.c_ubyte * size.value)()
        self.check(
            self.lib.NCryptSignHash(
                key,
                None,
                digest_buffer,
                len(digest),
                signature,
                size.value,
                ctypes.byref(size),
                self.NCRYPT_SILENT_FLAG,
            ),
            "create CNG signature",
        )
        if size.value != P256_SIGNATURE_BYTES:
            raise DurableCredentialError("CNG P-256 signature length changed")
        return bytes(signature[:size.value])


class WindowsCngDeviceCredentialStore:
    """Non-exportable current-user CNG key store; not connected to runtime."""

    def __init__(self, key_namespace: str = DEFAULT_KEY_NAMESPACE) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]{8,160}", key_namespace or ""):
            raise ValueError("CNG key namespace is invalid")
        self.key_namespace = key_namespace
        self._api = _NcryptApi()

    def key_name(self, key_version: int) -> str:
        _require_key_version(key_version)
        return f"{self.key_namespace}.{key_version}"

    def inspect(self, binding: DeviceBinding, key_version: int) -> CredentialInspection:
        _require_binding(binding)
        key_name = self.key_name(key_version)
        provider: ctypes.c_void_p | None = None
        key: ctypes.c_void_p | None = None
        try:
            provider = self._api.open_provider()
            key = self._api.try_open_key(provider, key_name)
            if key is None:
                missing = binding is DeviceBinding.BOUND_TO_DEVICE
                return CredentialInspection(
                    KeyState.KEY_MISSING if missing else KeyState.UNENROLLED_KEY_ABSENT,
                    key_version,
                    key_name,
                    None,
                    "KEY_MISSING_REENROLL_REQUIRED" if missing else "UNENROLLED_KEY_ABSENT",
                )
            return CredentialInspection(
                KeyState.READY,
                key_version,
                key_name,
                self._descriptor(key, key_name, key_version),
                "READY",
            )
        except Exception:
            return CredentialInspection(
                KeyState.KEYSTORE_ERROR,
                key_version,
                key_name,
                None,
                "KEYSTORE_UNAVAILABLE",
            )
        finally:
            self._api.free(key)
            self._api.free(provider)

    def create_for_fresh_enrollment(
        self,
        binding: DeviceBinding,
        key_version: int,
    ) -> PublicCredential:
        _require_binding(binding)
        if binding is not DeviceBinding.UNENROLLED:
            raise DurableCredentialError(
                "A bound device may not generate a replacement key; use rotation or re-enrollment"
            )
        key_name = self.key_name(key_version)
        provider: ctypes.c_void_p | None = None
        key: ctypes.c_void_p | None = None
        created = False
        deleted = False
        try:
            provider = self._api.open_provider()
            key = self._api.try_open_key(provider, key_name)
            if key is None:
                key = ctypes.c_void_p()
                self._api.check(
                    self._api.lib.NCryptCreatePersistedKey(
                        provider,
                        ctypes.byref(key),
                        self._api.ECDSA_P256_ALGORITHM,
                        key_name,
                        0,
                        0,
                    ),
                    "create persisted CNG P-256 key",
                )
                created = True
                # Explicit zero export policy: no encrypted or plaintext private export.
                self._api.set_dword(key, self._api.EXPORT_POLICY_PROPERTY, 0)
                self._api.set_dword(
                    key, self._api.KEY_USAGE_PROPERTY, self._api.NCRYPT_ALLOW_SIGNING_FLAG
                )
                self._api.check(
                    self._api.lib.NCryptFinalizeKey(key, self._api.NCRYPT_SILENT_FLAG),
                    "finalize persisted CNG P-256 key",
                )
            return self._descriptor(key, key_name, key_version)
        except Exception:
            if created and key and key.value:
                # Remove only the exact unfinished key created by this call.
                status = self._api.lib.NCryptDeleteKey(key, self._api.NCRYPT_SILENT_FLAG)
                deleted = self._api.status_code(status) == self._api.ERROR_SUCCESS
            raise
        finally:
            if not deleted:
                self._api.free(key)
            self._api.free(provider)

    def sign_enrollment_challenge(
        self,
        binding: DeviceBinding,
        key_version: int,
        enrollment_id: str,
        challenge: str,
        api_origin: str,
    ) -> str:
        if binding is not DeviceBinding.UNENROLLED:
            raise DurableCredentialError("Enrollment proof requires an unbound credential")
        credential = self.inspect(binding, key_version)
        if not credential.usable or credential.credential is None:
            raise DurableCredentialError(
                f"Durable device credential unavailable: {credential.diagnostic_code}"
            )
        payload = canonical_enrollment_challenge(
            enrollment_id,
            challenge,
            api_origin,
            credential.credential.key_thumbprint,
        )
        return self._sign_existing(binding, key_version, payload)

    def sign_session_challenge(
        self,
        binding: DeviceBinding,
        key_version: int,
        device_id: str,
        challenge_id: str,
        nonce: str,
        api_origin: str,
    ) -> str:
        if binding is not DeviceBinding.BOUND_TO_DEVICE:
            raise DurableCredentialError("Session proof requires a bound device")
        payload = canonical_session_challenge(
            device_id, challenge_id, nonce, api_origin, key_version
        )
        return self._sign_existing(binding, key_version, payload)

    def delete_staged_test_key(self, key_version: int) -> bool:
        """Test cleanup only; refuses to delete a non-Test namespace."""
        if ".Test." not in self.key_namespace:
            raise DurableCredentialError("Only an isolated staged Test key may be deleted here")
        key_name = self.key_name(key_version)
        provider: ctypes.c_void_p | None = None
        key: ctypes.c_void_p | None = None
        deleted = False
        try:
            provider = self._api.open_provider()
            key = self._api.try_open_key(provider, key_name)
            if key is None:
                return False
            self._api.check(
                self._api.lib.NCryptDeleteKey(key, self._api.NCRYPT_SILENT_FLAG),
                "delete isolated staged test key",
            )
            deleted = True
            return True
        finally:
            if not deleted:
                self._api.free(key)
            self._api.free(provider)

    def _sign_existing(
        self,
        binding: DeviceBinding,
        key_version: int,
        canonical_payload: str,
    ) -> str:
        inspection = self.inspect(binding, key_version)
        if not inspection.usable:
            raise DurableCredentialError(
                f"Durable device credential unavailable: {inspection.diagnostic_code}"
            )
        provider: ctypes.c_void_p | None = None
        key: ctypes.c_void_p | None = None
        try:
            provider = self._api.open_provider()
            key = self._api.try_open_key(provider, inspection.key_name)
            if key is None:
                raise DurableCredentialError("KEY_MISSING_REENROLL_REQUIRED")
            digest = hashlib.sha256(canonical_payload.encode("utf-8")).digest()
            signature = self._api.sign_hash(key, digest)
            return _base64url(signature)
        finally:
            self._api.free(key)
            self._api.free(provider)

    def _descriptor(
        self,
        key: ctypes.c_void_p,
        key_name: str,
        key_version: int,
    ) -> PublicCredential:
        export_policy = self._api.get_dword(key, self._api.EXPORT_POLICY_PROPERTY)
        key_usage = self._api.get_dword(key, self._api.KEY_USAGE_PROPERTY)
        if export_policy != 0:
            raise DurableCredentialError("CNG private key export policy is not fail closed")
        if not self._api.private_export_is_blocked(key):
            raise DurableCredentialError("CNG provider allowed private-key export")
        if not self._api.private_export_is_blocked(key):
            raise DurableCredentialError("CNG provider allowed private-key export")
        if key_usage & self._api.NCRYPT_ALLOW_SIGNING_FLAG == 0:
            raise DurableCredentialError("CNG key is not signing-enabled")
        blob = self._api.export_public_blob(key)
        if len(blob) < 8:
            raise DurableCredentialError("CNG P-256 public blob is truncated")
        magic, coordinate_size = struct.unpack_from("<II", blob, 0)
        if (
            magic != self._api.ECDSA_PUBLIC_P256_MAGIC
            or coordinate_size != P256_COORDINATE_BYTES
            or len(blob) != 8 + coordinate_size * 2
        ):
            raise DurableCredentialError("CNG public key is not canonical P-256")
        public_jwk = public_jwk_from_coordinates(
            blob[8:8 + coordinate_size],
            blob[8 + coordinate_size:8 + coordinate_size * 2],
        )
        return PublicCredential(
            key_version=key_version,
            key_name=key_name,
            public_jwk=public_jwk,
            key_thumbprint=public_jwk_thumbprint(public_jwk),
        )


__all__ = [
    "IMPLEMENTATION_STATUS",
    "CURRENT_KEY_VERSION",
    "DEFAULT_KEY_NAMESPACE",
    "DeviceBinding",
    "KeyState",
    "DurableCredentialError",
    "PublicCredential",
    "CredentialInspection",
    "public_jwk_from_coordinates",
    "public_jwk_thumbprint",
    "canonical_enrollment_challenge",
    "canonical_session_challenge",
    "WindowsCngDeviceCredentialStore",
]
