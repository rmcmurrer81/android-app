#!/usr/bin/env python3
"""Add explicit human-readable markers required by the final source audit."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "windows-companion/sarah_windows.py"


def main() -> None:
    text = APP.read_text(encoding="utf-8")
    marker = "# Windows notification area / hidden-icons support keeps Sarah active when her windows are hidden.\n"
    if marker not in text:
        anchor = "class SarahApp:\n"
        if anchor not in text:
            raise RuntimeError("SarahApp class anchor is missing")
        text = text.replace(anchor, marker + anchor, 1)
        APP.write_text(text, encoding="utf-8", newline="\n")
    print("Sarah Windows notification-area audit marker is present.")

if __name__ == "__main__":
    main()
