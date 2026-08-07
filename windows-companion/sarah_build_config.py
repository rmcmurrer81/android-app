"""Build-time connection values for Sarah Travel OS.

The public source intentionally contains no credentials. GitHub Actions replaces
CONFIG only inside the temporary Windows build workspace before PyInstaller runs.
Environment variables always override bundled values.
"""
from __future__ import annotations

import os
from typing import Any

CONFIG: dict[str, str] = {}


def get(name: str, default: str = "") -> str:
    environment = os.environ.get(name)
    if environment is not None and str(environment).strip():
        return str(environment).strip()
    value: Any = CONFIG.get(name, default)
    return "" if value is None else str(value).strip()


def configured(name: str) -> bool:
    return bool(get(name))
