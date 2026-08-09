from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zipfile

from secret_scan import scan_tree, scan_zip


PACKAGE_NAME = "Sarah-Team-Integration-Source"
FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)
TEXT_SUFFIXES = {
    ".java", ".json", ".md", ".py", ".txt", ".yaml", ".yml", ".xml",
}
IGNORED_PARTS = {"__pycache__", ".pytest_cache"}


def package_root() -> Path:
    return Path(__file__).resolve().parents[1]


def repository_root() -> Path:
    return Path(__file__).resolve().parents[3]


def source_files(root: Path) -> list[Path]:
    files = []
    for path in root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(root)
        if any(part in IGNORED_PARTS for part in relative.parts):
            continue
        if path.suffix.lower() in {".pyc", ".class", ".zip"}:
            continue
        files.append(path)
    return sorted(files, key=lambda item: item.relative_to(root).as_posix())


def normalized_bytes(path: Path) -> bytes:
    data = path.read_bytes()
    if path.suffix.lower() not in TEXT_SUFFIXES:
        return data
    text = data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    return text.encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(output_dir: Path) -> dict[str, object]:
    root = package_root()
    source_findings = scan_tree(root)
    if source_findings:
        raise RuntimeError("Source secret scan failed: " + "; ".join(source_findings))

    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / f"{PACKAGE_NAME}.zip"
    manifest_path = output_dir / f"{PACKAGE_NAME}.manifest.json"
    checksum_path = output_dir / f"{PACKAGE_NAME}.zip.sha256"
    inventory = []

    with tempfile.NamedTemporaryFile(
            prefix=f".{PACKAGE_NAME}-", suffix=".tmp", dir=output_dir, delete=False) as handle:
        temporary_path = Path(handle.name)
    try:
        with zipfile.ZipFile(
                temporary_path,
                mode="w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=9) as archive:
            for path in source_files(root):
                relative = path.relative_to(root).as_posix()
                archive_name = f"{PACKAGE_NAME}/{relative}"
                data = normalized_bytes(path)
                info = zipfile.ZipInfo(archive_name, FIXED_ZIP_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = (0o100644 & 0xFFFF) << 16
                info.flag_bits |= 0x800
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                inventory.append({
                    "path": archive_name,
                    "sha256": sha256(data),
                    "size": len(data),
                })
        os.replace(temporary_path, archive_path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()

    archive_findings = scan_zip(archive_path)
    if archive_findings:
        raise RuntimeError("Archive secret scan failed: " + "; ".join(archive_findings))

    archive_digest = sha256(archive_path.read_bytes())
    manifest = {
        "schema": "sarah-team-integration-source-manifest-v1",
        "archive": archive_path.name,
        "archive_sha256": archive_digest,
        "deterministic_zip_time": "2020-01-01T00:00:00Z",
        "file_count": len(inventory),
        "files": inventory,
    }
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )
    checksum_path.write_text(
        f"{archive_digest}  {archive_path.name}\n", encoding="ascii", newline="\n"
    )
    return {
        "archive": archive_path,
        "manifest": manifest_path,
        "checksum": checksum_path,
        "archive_sha256": archive_digest,
        "file_count": len(inventory),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build deterministic Sarah team source package")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=repository_root() / "dist",
    )
    args = parser.parse_args()
    result = build(args.output_dir.resolve())
    print(f"SARAH_TEAM_INTEGRATION_PACKAGE_OK {result['archive_sha256']}")
    print(result["archive"])
    print(result["manifest"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
