"""Independent Windows/Python verification of Sarah's public P-256 fixture.

This is a focused interoperability test, not a Windows credential manager and
not production authentication code.
"""

from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils


FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "p256-v1.json"


def b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def verify(public_key: ec.EllipticCurvePublicKey, payload: str, signature: str) -> None:
    raw = b64url_decode(signature)
    assert len(raw) == 64
    r = int.from_bytes(raw[:32], "big")
    s = int.from_bytes(raw[32:], "big")
    public_key.verify(utils.encode_dss_signature(r, s), payload.encode("utf-8"), ec.ECDSA(hashes.SHA256()))


def main() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    jwk = fixture["public_jwk"]
    assert set(jwk) == {"kty", "crv", "x", "y"}
    assert jwk["kty"] == "EC" and jwk["crv"] == "P-256"

    canonical = json.dumps(
        {"crv": jwk["crv"], "kty": jwk["kty"], "x": jwk["x"], "y": jwk["y"]},
        separators=(",", ":"),
    )
    assert canonical == fixture["public_jwk_canonical_json"]
    thumbprint = base64.urlsafe_b64encode(hashlib.sha256(canonical.encode()).digest()).rstrip(b"=").decode()
    assert thumbprint == fixture["jwk_thumbprint_sha256_base64url"]

    numbers = ec.EllipticCurvePublicNumbers(
        int.from_bytes(b64url_decode(jwk["x"]), "big"),
        int.from_bytes(b64url_decode(jwk["y"]), "big"),
        ec.SECP256R1(),
    )
    public_key = numbers.public_key()

    for label in ("auth", "enrollment"):
        vector = fixture[label]
        digest = hashlib.sha256(vector["payload_utf8"].encode("utf-8")).hexdigest()
        assert digest == vector["payload_sha256_hex"]
        verify(public_key, vector["payload_utf8"], vector["signature_p1363_base64url"])
        try:
            verify(public_key, vector["payload_utf8"] + "x", vector["signature_p1363_base64url"])
        except InvalidSignature:
            pass
        else:
            raise AssertionError("tampered payload unexpectedly verified")

    print("PASS: Python verified Sarah P-256 auth and enrollment vectors")


if __name__ == "__main__":
    main()
