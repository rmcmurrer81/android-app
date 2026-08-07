from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import time

import sarah_installer as base


VERSION = "2.6-windows-repair"


def _write_version(target: Path) -> None:
    metadata_path = target.parent / "install.json"
    metadata = {}
    if metadata_path.is_file():
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except Exception:
            metadata = {}
    metadata.update(
        {
            "display_name": base.DISPLAY_NAME,
            "version": VERSION,
            "installed_at": int(time.time()),
            "executable": str(target),
            "uninstall_method": "windows-built-in-powershell",
        }
    )
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    if os.name == "nt":
        try:
            import winreg
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, base.UNINSTALL_REGISTRY_KEY) as key:
                winreg.SetValueEx(key, "DisplayVersion", 0, winreg.REG_SZ, VERSION)
                winreg.SetValueEx(key, "Publisher", 0, winreg.REG_SZ, "Kira World")
        except Exception:
            pass


def install(*, launch: bool = True, shortcuts: bool = True, target_root: Path | None = None) -> Path:
    target = base.install(launch=launch, shortcuts=shortcuts, target_root=target_root)
    _write_version(target)
    return target


def self_test() -> int:
    # The inherited test verifies the bundled application, exact installed application
    # self-test, and the Smart App Control-safe PowerShell remover.
    base.self_test()
    return 0


def main() -> int:
    arguments = {argument.lower() for argument in sys.argv[1:]}
    try:
        if "--self-test" in arguments:
            return self_test()
        if "--uninstall" in arguments:
            base.uninstall()
            if "/s" not in arguments and "--silent" not in arguments:
                base.show_result(base.DISPLAY_NAME, "Sarah Travel OS was removed from this Windows account.")
            return 0
        silent = "/s" in arguments or "--silent" in arguments
        launch = "--no-launch" not in arguments
        install(launch=launch, shortcuts=True)
        if not silent:
            base.show_result(
                base.DISPLAY_NAME,
                "Sarah Travel OS 2.6 is installed.\n\n"
                "The chat composer remains visible at ordinary window sizes, Sarah has an offline conversational mind, voice failures are shown instead of hidden, and the Remove Sarah shortcut uses Windows' built-in PowerShell.\n\n"
                "Approve a phone only when its name and six-digit verification code match.",
            )
        return 0
    except Exception as error:
        base.show_result("Sarah installation could not finish", str(error), error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
