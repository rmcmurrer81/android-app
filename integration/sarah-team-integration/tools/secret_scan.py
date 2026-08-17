from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import zipfile


TEXT_SUFFIXES = {
    ".java", ".json", ".md", ".py", ".txt", ".yaml", ".yml", ".xml",
}
KNOWN_HOST_MARKERS = (
    "workers" + r"\.dev",
    "api" + r"\.elevenlabs",
    "cloudflare" + r"[_-]?account",
    "sarah_model" + r"_backend_token",
    "sarah_tavily" + r"_api_key",
)
FORBIDDEN_TEXT_PATTERNS = {
    "private_key_block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "service_url": re.compile(r"https?://", re.IGNORECASE),
    "email_address": re.compile(
        r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE
    ),
    "credential_assignment": re.compile(
        r"(?i)(?:api[_-]?key|access[_-]?token|backend[_-]?token|password|"
        r"client[_-]?secret)\s*[:=]\s*[\"'][^\"']{4,}[\"']"
    ),
    "authorization_header": re.compile(r"(?i)authorization\s*[:=]"),
    "known_host_marker": re.compile(r"(?i)(?:" + "|".join(KNOWN_HOST_MARKERS) + r")"),
}
SUSPICIOUS_JSON_KEY = re.compile(
    r"(?i)(?:secret|password|api.?key|access.?token|backend.?token|service.?endpoint)"
)


def _scan_json_values(value, location: str, findings: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            child_location = f"{location}.{key}"
            if SUSPICIOUS_JSON_KEY.search(str(key)):
                if child not in (None, "", False, 0, [], {}):
                    findings.append(f"credential-shaped JSON value at {child_location}")
            _scan_json_values(child, child_location, findings)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _scan_json_values(child, f"{location}[{index}]", findings)


def scan_text(label: str, data: bytes) -> list[str]:
    findings: list[str] = []
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return [f"non-UTF-8 text file: {label}"]
    for name, pattern in FORBIDDEN_TEXT_PATTERNS.items():
        if pattern.search(text):
            findings.append(f"{name}: {label}")
    if label.lower().endswith(".json"):
        try:
            parsed = json.loads(text)
        except ValueError:
            findings.append(f"invalid JSON: {label}")
        else:
            _scan_json_values(parsed, label, findings)
    return findings


def scan_tree(root: Path) -> list[str]:
    findings: list[str] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if "__pycache__" in path.parts:
            continue
        findings.extend(scan_text(path.relative_to(root).as_posix(), path.read_bytes()))
    return findings


def scan_zip(path: Path) -> list[str]:
    findings: list[str] = []
    with zipfile.ZipFile(path, "r") as archive:
        for name in sorted(archive.namelist()):
            if Path(name).suffix.lower() in TEXT_SUFFIXES:
                findings.extend(scan_text(name, archive.read(name)))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan Sarah integration source or ZIP")
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    findings = scan_zip(args.target) if args.target.suffix.lower() == ".zip" else scan_tree(args.target)
    if findings:
        for finding in findings:
            print(f"FAIL {finding}")
        return 1
    print("SARAH_TEAM_INTEGRATION_SECRET_SCAN_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
