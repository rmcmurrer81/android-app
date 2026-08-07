from __future__ import annotations

import base64
import hashlib
from pathlib import Path


EXPECTED = {
    "sarah_full_neutral.webp": "3217b403b293730bc21956020de7c5df8bf52643714494cb4018db3b7a1467c0",
}


def build_assets() -> list[Path]:
    root = Path(__file__).resolve().parent
    source_root = root / "assets_b64"
    target_root = root / "assets"
    target_root.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    for filename, expected_sha in EXPECTED.items():
        source = source_root / f"{filename}.b64"
        if not source.is_file():
            raise FileNotFoundError(f"Missing encoded Sarah asset: {source}")
        encoded = "".join(source.read_text(encoding="ascii").split())
        raw = base64.b64decode(encoded, validate=True)
        actual_sha = hashlib.sha256(raw).hexdigest()
        if actual_sha != expected_sha:
            raise RuntimeError(
                f"Sarah asset checksum mismatch for {filename}: expected {expected_sha}, got {actual_sha}"
            )
        target = target_root / filename
        target.write_bytes(raw)
        written.append(target)
        print(f"Verified {filename}: {len(raw)} bytes, sha256:{actual_sha}")

    return written


if __name__ == "__main__":
    build_assets()
