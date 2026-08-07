from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

try:
    from tkinter import messagebox
except Exception:
    messagebox = None


APP_FOLDER = "SarahTravelOS"
APP_EXE = "SarahTravelOS.exe"
DISPLAY_NAME = "Sarah Travel OS"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def bundle_root() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def bundled_payload() -> Path:
    candidates = [
        bundle_root() / "payload" / APP_EXE,
        bundle_root() / APP_EXE,
        Path(__file__).resolve().parent / "dist" / APP_EXE,
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("The Sarah Travel OS application payload was not bundled into this installer.")


def install_root() -> Path:
    local = os.environ.get("LOCALAPPDATA", "").strip()
    if not local:
        local = str(Path.home() / "AppData" / "Local")
    return Path(local) / APP_FOLDER


def desktop_directory() -> Path:
    return Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Desktop"


def start_menu_directory() -> Path:
    appdata = os.environ.get("APPDATA", "").strip()
    if not appdata:
        appdata = str(Path.home() / "AppData" / "Roaming")
    return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / DISPLAY_NAME


def powershell_quote(value: Path | str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def create_shortcut(path: Path, target: Path, arguments: str = "", description: str = "") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    script = (
        "$w=New-Object -ComObject WScript.Shell;"
        f"$s=$w.CreateShortcut({powershell_quote(path)});"
        f"$s.TargetPath={powershell_quote(target)};"
        f"$s.WorkingDirectory={powershell_quote(target.parent)};"
        f"$s.Arguments={powershell_quote(arguments)};"
        f"$s.Description={powershell_quote(description or DISPLAY_NAME)};"
        f"$s.IconLocation={powershell_quote(str(target) + ',0')};"
        "$s.Save()"
    )
    completed = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
        capture_output=True,
        text=True,
        creationflags=CREATE_NO_WINDOW,
        timeout=45,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or "Windows could not create the Sarah shortcut.")


def install(*, launch: bool = True, shortcuts: bool = True, target_root: Path | None = None) -> Path:
    source = bundled_payload()
    root = target_root or install_root()
    root.mkdir(parents=True, exist_ok=True)
    target = root / APP_EXE
    temporary = root / (APP_EXE + ".new")
    shutil.copy2(source, temporary)
    if target.exists():
        try:
            target.unlink()
        except PermissionError as error:
            raise RuntimeError("Sarah is currently running. Close Sarah and run the installer again.") from error
    temporary.replace(target)

    metadata = {
        "display_name": DISPLAY_NAME,
        "version": "2.5-event-ready",
        "installed_at": int(time.time()),
        "executable": str(target),
    }
    (root / "install.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    uninstall_command = f'"{Path(sys.executable).resolve()}" --uninstall'
    (root / "Uninstall Sarah Travel OS.cmd").write_text(
        "@echo off\r\n"
        "taskkill /IM SarahTravelOS.exe /F >nul 2>nul\r\n"
        f"{uninstall_command}\r\n",
        encoding="utf-8",
    )

    if shortcuts:
        create_shortcut(
            desktop_directory() / "Sarah Travel OS.lnk",
            target,
            description="Talk, plan trips, organize photos, and synchronize Sarah across your devices.",
        )
        start = start_menu_directory()
        create_shortcut(start / "Sarah Travel OS.lnk", target, description=DISPLAY_NAME)
        create_shortcut(
            start / "Uninstall Sarah Travel OS.lnk",
            Path(sys.executable).resolve(),
            "--uninstall",
            "Remove Sarah Travel OS from this Windows account.",
        )

    if launch:
        subprocess.Popen([str(target)], cwd=str(root), creationflags=CREATE_NO_WINDOW)
    return target


def uninstall() -> None:
    root = install_root()
    for shortcut in [
        desktop_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Uninstall Sarah Travel OS.lnk",
    ]:
        try:
            shortcut.unlink()
        except FileNotFoundError:
            pass
    try:
        start_menu_directory().rmdir()
    except OSError:
        pass

    # A running installer cannot delete itself immediately. Schedule cleanup in a detached command.
    if root.exists():
        command = f'timeout /t 2 /nobreak >nul & rmdir /s /q "{root}"'
        subprocess.Popen(
            ["cmd", "/c", command],
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) | CREATE_NO_WINDOW,
        )


def self_test() -> int:
    payload = bundled_payload()
    if payload.stat().st_size < 100_000:
        raise RuntimeError("The bundled Sarah application is unexpectedly small.")
    with tempfile.TemporaryDirectory(prefix="sarah-installer-test-") as folder:
        target = install(launch=False, shortcuts=False, target_root=Path(folder) / APP_FOLDER)
        completed = subprocess.run(
            [str(target), "--self-test"],
            timeout=90,
            creationflags=CREATE_NO_WINDOW,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"The installed Sarah application self-test returned {completed.returncode}.")
        if not target.is_file():
            raise RuntimeError("The Sarah application was not installed.")
    return 0


def show_result(title: str, message: str, error: bool = False) -> None:
    if messagebox is None:
        return
    try:
        if error:
            messagebox.showerror(title, message)
        else:
            messagebox.showinfo(title, message)
    except Exception:
        pass


def main() -> int:
    arguments = {argument.lower() for argument in sys.argv[1:]}
    try:
        if "--self-test" in arguments:
            return self_test()
        if "--uninstall" in arguments:
            uninstall()
            if "/s" not in arguments and "--silent" not in arguments:
                show_result(DISPLAY_NAME, "Sarah Travel OS was removed from this Windows account.")
            return 0
        silent = "/s" in arguments or "--silent" in arguments
        launch = "--no-launch" not in arguments
        target = install(launch=launch, shortcuts=True)
        if not silent:
            show_result(
                DISPLAY_NAME,
                "Sarah Travel OS is installed. The desktop and Start menu shortcuts are ready.\n\n"
                "When Sarah finds your phone on private Wi-Fi, approve only when the device name and six-digit code match.",
            )
        return 0
    except Exception as error:
        show_result("Sarah installation could not finish", str(error), error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
