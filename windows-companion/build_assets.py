from __future__ import annotations

import base64
import hashlib
from pathlib import Path


EXPECTED = {
    "sarah_full_neutral.webp": {
        "sha256": "3217b403b293730bc21956020de7c5df8bf52643714494cb4018db3b7a1467c0",
        "size": 13_934,
    },
}


def encoded_asset(source_root: Path, filename: str) -> str:
    """Read a complete encoded asset from ordered GitHub-safe chunks."""
    chunks = sorted(source_root.glob(f"{filename}.b64.part*"))
    if chunks:
        expected_names = [f"{filename}.b64.part{index:02d}" for index in range(len(chunks))]
        actual_names = [path.name for path in chunks]
        if actual_names != expected_names:
            raise RuntimeError(
                f"Sarah asset chunks are incomplete or out of order: expected {expected_names}, got {actual_names}"
            )
        return "".join("".join(path.read_text(encoding="ascii").split()) for path in chunks)

    single = source_root / f"{filename}.b64"
    if single.is_file():
        return "".join(single.read_text(encoding="ascii").split())
    raise FileNotFoundError(f"Missing encoded Sarah asset: {filename}")


def build_assets() -> list[Path]:
    root = Path(__file__).resolve().parent
    source_root = root / "assets_b64"
    target_root = root / "assets"
    target_root.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    for filename, contract in EXPECTED.items():
        encoded = encoded_asset(source_root, filename)
        raw = base64.b64decode(encoded, validate=True)
        actual_sha = hashlib.sha256(raw).hexdigest()
        expected_sha = contract["sha256"]
        expected_size = int(contract["size"])
        if actual_sha != expected_sha or len(raw) != expected_size:
            raise RuntimeError(
                f"Sarah asset mismatch for {filename}: expected {expected_size} bytes / {expected_sha}, "
                f"got {len(raw)} bytes / {actual_sha}"
            )
        target = target_root / filename
        target.write_bytes(raw)
        written.append(target)
        print(f"Verified {filename}: {len(raw)} bytes, sha256:{actual_sha}")

    return written


if __name__ == "__main__":
    build_assets()
