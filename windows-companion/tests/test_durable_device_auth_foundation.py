from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import sys
import uuid

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils


WINDOWS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(WINDOWS_ROOT))

from sarah_durable_device_auth_foundation import (  # noqa: E402
    IMPLEMENTATION_STATUS,
    DeviceBinding,
    DurableCredentialError,
    KeyState,
    WindowsCngDeviceCredentialStore,
    canonical_enrollment_challenge,
    canonical_session_challenge,
    public_jwk_from_coordinates,
    public_jwk_thumbprint,
)


GENERATOR_X = bytes.fromhex(
    "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
)
GENERATOR_Y = bytes.fromhex(
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
)
VECTOR_THUMBPRINT = "xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M"


def _decode_base64url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4))


def test_public_jwk_and_rfc7638_vector_are_worker_compatible() -> None:
    jwk = public_jwk_from_coordinates(GENERATOR_X, GENERATOR_Y)
    assert jwk == {
        "kty": "EC",
        "crv": "P-256",
        "x": "axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY",
        "y": "T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU",
    }
    assert public_jwk_thumbprint(jwk) == VECTOR_THUMBPRINT
    assert json.dumps(
        {"crv": jwk["crv"], "kty": jwk["kty"], "x": jwk["x"], "y": jwk["y"]},
        separators=(",", ":"),
    ) == (
        '{"crv":"P-256","kty":"EC",'
        '"x":"axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY",'
        '"y":"T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"}'
    )


def test_canonical_challenges_are_exact_and_ambiguous_inputs_fail_closed() -> None:
    assert canonical_session_challenge(
        "device_1", "challenge_2", "nonce_3", "https://full.sarah.example", 7
    ) == (
        "SARAH-AUTH-V1\ndevice_1\nchallenge_2\nnonce_3\n"
        "https://full.sarah.example\n7"
    )
    assert canonical_enrollment_challenge(
        "enrollment_1",
        "challenge_2",
        "https://full.sarah.example",
        VECTOR_THUMBPRINT,
    ) == (
        "SARAH-ENROLLMENT-V1\nenrollment_1\nchallenge_2\n"
        f"https://full.sarah.example\n{VECTOR_THUMBPRINT}"
    )
    for invalid in (
        lambda: canonical_session_challenge(
            "device\nforged", "challenge", "nonce", "https://full.sarah.example", 1
        ),
        lambda: canonical_session_challenge(
            "device", "challenge", "nonce", "https://full.sarah.example/", 1
        ),
        lambda: canonical_session_challenge(
            "device", "challenge", "nonce", "http://full.sarah.example", 1
        ),
        lambda: canonical_session_challenge(
            "device", "challenge", "nonce", "https://full.sarah.example", 0
        ),
        lambda: canonical_enrollment_challenge(
            "enrollment", "challenge", "https://full.sarah.example", "not-a-thumbprint"
        ),
    ):
        with pytest.raises(ValueError):
            invalid()


def test_module_is_staged_and_unreferenced_by_current_runtime_and_artifacts() -> None:
    assert IMPLEMENTATION_STATUS == "STAGED_NOT_CONNECTED"
    module_name = "sarah_durable_device_auth_foundation"
    for path in (
        WINDOWS_ROOT / "sarah_core.py",
        WINDOWS_ROOT / "sarah_event_ready.py",
        WINDOWS_ROOT / "sarah_windows.py",
        WINDOWS_ROOT / "sarah_installer.py",
        WINDOWS_ROOT / "BUILD_WINDOWS.ps1",
        WINDOWS_ROOT / "SarahTravelOS-R3-Candidate.spec",
        WINDOWS_ROOT / "SarahMorganTravelOS-R3-Candidate-Setup.spec",
    ):
        assert module_name not in path.read_text(encoding="utf-8")


def test_source_uses_current_user_nonexportable_cng_and_has_no_secret_input() -> None:
    source = (WINDOWS_ROOT / "sarah_durable_device_auth_foundation.py").read_text(
        encoding="utf-8"
    )
    for required in (
        "Microsoft Software Key Storage Provider",
        'ECDSA_P256_ALGORITHM = "ECDSA_P256"',
        'EXPORT_POLICY_PROPERTY = "Export Policy"',
        "self._api.set_dword(key, self._api.EXPORT_POLICY_PROPERTY, 0)",
        "private_export_is_blocked",
        "CNG provider allowed private-key export",
        "NCRYPT_ALLOW_SIGNING_FLAG",
        "KEY_MISSING_REENROLL_REQUIRED",
        "A bound device may not generate a replacement key",
    ):
        assert required in source
    for forbidden in (
        "NCRYPT_MACHINE_KEY_FLAG",
        "NCRYPT_ALLOW_EXPORT_FLAG",
        "NCRYPT_ALLOW_PLAINTEXT_EXPORT_FLAG",
        "SARAH_EVENT_BACKEND_TOKEN",
        "SARAH_MODEL_BACKEND_TOKEN",
        "OPENAI_API_KEY",
        "ELEVENLABS_API_KEY",
    ):
        assert forbidden not in source


@pytest.mark.skipif(os.name != "nt", reason="actual Microsoft CNG gate requires Windows")
def test_actual_cng_key_signs_and_missing_bound_key_never_regenerates() -> None:
    namespace = f"SarahMorganDurableDeviceAuthP256V1.Test.{uuid.uuid4().hex}"
    store = WindowsCngDeviceCredentialStore(namespace)
    try:
        initial = store.inspect(DeviceBinding.UNENROLLED, 1)
        assert initial.state is KeyState.UNENROLLED_KEY_ABSENT

        credential = store.create_for_fresh_enrollment(DeviceBinding.UNENROLLED, 1)
        assert credential.protection == "WINDOWS_CNG_CURRENT_USER_NON_EXPORTABLE"
        assert credential.key_thumbprint == public_jwk_thumbprint(credential.public_jwk)

        ready = store.inspect(DeviceBinding.BOUND_TO_DEVICE, 1)
        assert ready.state is KeyState.READY
        payload = canonical_session_challenge(
            "device-test",
            "challenge-test",
            "nonce-test",
            "https://full.sarah.example",
            1,
        )
        signature = _decode_base64url(store.sign_session_challenge(
            DeviceBinding.BOUND_TO_DEVICE,
            1,
            "device-test",
            "challenge-test",
            "nonce-test",
            "https://full.sarah.example",
        ))
        assert len(signature) == 64

        jwk = credential.public_jwk
        public_key = ec.EllipticCurvePublicNumbers(
            int.from_bytes(_decode_base64url(jwk["x"]), "big"),
            int.from_bytes(_decode_base64url(jwk["y"]), "big"),
            ec.SECP256R1(),
        ).public_key()
        der = utils.encode_dss_signature(
            int.from_bytes(signature[:32], "big"),
            int.from_bytes(signature[32:], "big"),
        )
        public_key.verify(der, payload.encode("utf-8"), ec.ECDSA(hashes.SHA256()))

        assert store.delete_staged_test_key(1) is True
        missing = store.inspect(DeviceBinding.BOUND_TO_DEVICE, 1)
        assert missing.state is KeyState.KEY_MISSING
        assert missing.diagnostic_code == "KEY_MISSING_REENROLL_REQUIRED"
        with pytest.raises(DurableCredentialError, match="may not generate a replacement key"):
            store.create_for_fresh_enrollment(DeviceBinding.BOUND_TO_DEVICE, 1)
        assert store.inspect(DeviceBinding.BOUND_TO_DEVICE, 1).state is KeyState.KEY_MISSING
    finally:
        store.delete_staged_test_key(1)
