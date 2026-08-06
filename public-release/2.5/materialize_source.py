from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import tarfile
from pathlib import Path

EXPECTED_SHA256 = "9b03c30ffa18c8a1f23e6a6b73defe940d3a768627e83a26b06bbaf71c355dc9"
EXPECTED_PARTS = 19
EXPECTED_VERSION = "2.5.0"


def safe_extract(archive: Path, destination: Path) -> None:
    destination = destination.resolve()
    with tarfile.open(archive, mode="r:xz") as package:
        for member in package.getmembers():
            target = (destination / member.name).resolve()
            if destination != target and destination not in target.parents:
                raise RuntimeError(f"Unsafe archive path: {member.name}")
        package.extractall(destination, filter="data")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--destination", type=Path, default=Path.cwd())
    parser.add_argument("--parts", type=Path, default=Path("public-release/2.5/source-parts"))
    parser.add_argument("--archive-output", type=Path, default=Path("build/Sarah_Travel_OS_2.5_Source.tar.xz"))
    args = parser.parse_args()

    parts = sorted(args.parts.glob("part-*.txt"))
    if len(parts) != EXPECTED_PARTS:
        raise SystemExit(f"Expected {EXPECTED_PARTS} Sarah 2.5 source parts; found {len(parts)}")
    encoded = b"".join(part.read_bytes() for part in parts)
    try:
        raw = base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise SystemExit(f"The Sarah 2.5 source parts are invalid base64: {exc}") from exc
    actual = hashlib.sha256(raw).hexdigest()
    if actual != EXPECTED_SHA256:
        raise SystemExit(f"Sarah 2.5 source checksum mismatch: expected {EXPECTED_SHA256}, got {actual}")

    args.archive_output.parent.mkdir(parents=True, exist_ok=True)
    args.archive_output.write_bytes(raw)
    destination = args.destination.resolve()
    safe_extract(args.archive_output, destination)

    release_file = destination / "PUBLIC_RELEASE.json"
    release = json.loads(release_file.read_text(encoding="utf-8"))
    if release.get("version") != EXPECTED_VERSION:
        raise SystemExit(f"Unexpected Sarah source version: {release.get('version')}")
    if release.get("pairing_protocol") != 3:
        raise SystemExit("Unexpected Sarah trusted-device pairing protocol")

    required = [
        destination / "windows-companion" / "SarahMorganInstaller.spec",
        destination / "windows-companion" / "sarah_core" / "desktop.py",
        destination / "windows-companion" / "sarah_core" / "pairing_v3.py",
        destination / "development-tools" / "sarah-2.5-android-upgrade" / "apply_android_upgrade.py",
        destination / "development-tools" / "sarah-2.5-android-upgrade" / "new_files" / "java" / "com" / "kiraworld" / "sarahtravel" / "AndroidSarahNetworkService.java",
        destination / "docs" / "SARAH_2_5_PUBLIC_PRODUCT.md",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("Missing required Sarah 2.5 source files: " + ", ".join(missing))

    for cache in destination.rglob("__pycache__"):
        if cache.is_dir():
            shutil.rmtree(cache, ignore_errors=True)
    for bytecode in destination.rglob("*.pyc"):
        bytecode.unlink(missing_ok=True)

    count = sum(1 for path in destination.rglob("*") if path.is_file() and ".git" not in path.parts)
    print(json.dumps({"ok": True, "sha256": actual, "version": EXPECTED_VERSION, "files_in_workspace": count}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
