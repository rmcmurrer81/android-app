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
except Exception:  # pragma: no cover
    messagebox = None


APP_FOLDER = "SarahTravelOS"
APP_EXE = "SarahTravelOS.exe"
DISPLAY_NAME = "Sarah Travel OS"
DISPLAY_VERSION = "2.6-windows-repair"
UNINSTALL_REGISTRY_KEY = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\SarahTravelOS"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def bundle_root() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def bundled_payload() -> Path:
    for candidate in (
        bundle_root() / "payload" / APP_EXE,
        bundle_root() / APP_EXE,
        Path(__file__).resolve().parent / "dist" / APP_EXE,
    ):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("The Sarah Travel OS application payload was not bundled into this installer.")


def install_root() -> Path:
    local = os.environ.get("LOCALAPPDATA", "").strip() or str(Path.home() / "AppData" / "Local")
    return Path(local) / APP_FOLDER


def desktop_directory() -> Path:
    return Path(os.environ.get("USERPROFILE", str(Path.home()))) / "Desktop"


def start_menu_directory() -> Path:
    appdata = os.environ.get("APPDATA", "").strip() or str(Path.home() / "AppData" / "Roaming")
    return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / DISPLAY_NAME


def powershell_executable() -> Path:
    discovered = shutil.which("powershell.exe") or shutil.which("powershell")
    if discovered:
        return Path(discovered)
    system_root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
    return system_root / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"


def ps_quote(value: Path | str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def command_quote(value: str) -> str:
    return '"' + value.replace('"', '\\"') + '"'


def create_shortcut(path: Path, target: Path, arguments: str = "", description: str = "") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    script = (
        "$w=New-Object -ComObject WScript.Shell;"
        f"$s=$w.CreateShortcut({ps_quote(path)});"
        f"$s.TargetPath={ps_quote(target)};"
        f"$s.WorkingDirectory={ps_quote(target.parent)};"
        f"$s.Arguments={ps_quote(arguments)};"
        f"$s.Description={ps_quote(description or DISPLAY_NAME)};"
        f"$s.IconLocation={ps_quote(str(target) + ',0')};"
        "$s.Save()"
    )
    result = subprocess.run(
        [str(powershell_executable()), "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
        capture_output=True,
        text=True,
        timeout=45,
        creationflags=CREATE_NO_WINDOW,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "Windows could not create the Sarah shortcut.")


def removal_powershell_command() -> str:
    root = ps_quote(install_root())
    desktop_link = ps_quote(desktop_directory() / "Sarah Travel OS.lnk")
    start_folder = ps_quote(start_menu_directory())
    registry_key = ps_quote(r"HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SarahTravelOS")
    return (
        "$ErrorActionPreference='SilentlyContinue';"
        "Get-Process -Name 'SarahTravelOS' | Stop-Process -Force;"
        f"Remove-Item -LiteralPath {desktop_link} -Force;"
        f"Remove-Item -LiteralPath {start_folder} -Recurse -Force;"
        f"Remove-Item -LiteralPath {registry_key} -Recurse -Force;"
        f"Remove-Item -LiteralPath {root} -Recurse -Force"
    )


def removal_arguments(*, hidden: bool) -> str:
    window = " -WindowStyle Hidden" if hidden else ""
    return f"-NoProfile -ExecutionPolicy Bypass{window} -Command " + command_quote(removal_powershell_command())


def write_builtin_remover(root: Path) -> Path:
    remover = root / "Remove Sarah Travel OS.cmd"
    remover.write_text(
        "@echo off\r\n"
        "rem Uses Windows PowerShell and does not disable Smart App Control.\r\n"
        f'"{powershell_executable()}" {removal_arguments(hidden=False)}\r\n'
        "exit /b %errorlevel%\r\n",
        encoding="utf-8",
    )
    return remover


def register_windows_uninstaller(target: Path) -> None:
    if os.name != "nt":
        return
    import winreg

    uninstall_string = f'"{powershell_executable()}" {removal_arguments(hidden=False)}'
    quiet_string = f'"{powershell_executable()}" {removal_arguments(hidden=True)}'
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, UNINSTALL_REGISTRY_KEY) as key:
        values = {
            "DisplayName": DISPLAY_NAME,
            "DisplayVersion": DISPLAY_VERSION,
            "Publisher": "Kira World",
            "InstallLocation": str(install_root()),
            "DisplayIcon": str(target),
            "UninstallString": uninstall_string,
            "QuietUninstallString": quiet_string,
        }
        for name, value in values.items():
            winreg.SetValueEx(key, name, 0, winreg.REG_SZ, value)
        winreg.SetValueEx(key, "NoModify", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "NoRepair", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "EstimatedSize", 0, winreg.REG_DWORD, max(1, target.stat().st_size // 1024))


def remove_uninstall_registry_entry() -> None:
    if os.name != "nt":
        return
    import winreg

    try:
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, UNINSTALL_REGISTRY_KEY)
    except OSError:
        pass


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

    (root / "install.json").write_text(
        json.dumps(
            {
                "display_name": DISPLAY_NAME,
                "version": DISPLAY_VERSION,
                "installed_at": int(time.time()),
                "executable": str(target),
                "uninstall_method": "windows-built-in-powershell",
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    write_builtin_remover(root)

    if shortcuts:
        create_shortcut(
            desktop_directory() / "Sarah Travel OS.lnk",
            target,
            description="Talk, plan trips, organize photos, and synchronize Sarah across your devices.",
        )
        start = start_menu_directory()
        create_shortcut(start / "Sarah Travel OS.lnk", target, description=DISPLAY_NAME)
        create_shortcut(
            start / "Remove Sarah Travel OS.lnk",
            powershell_executable(),
            removal_arguments(hidden=False),
            "Remove Sarah Travel OS without disabling Smart App Control.",
        )
        register_windows_uninstaller(target)

    if launch:
        subprocess.Popen([str(target)], cwd=str(root), creationflags=CREATE_NO_WINDOW)
    return target


def uninstall() -> None:
    for shortcut in (
        desktop_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Uninstall Sarah Travel OS.lnk",
        start_menu_directory() / "Remove Sarah Travel OS.lnk",
    ):
        try:
            shortcut.unlink()
        except FileNotFoundError:
            pass
    try:
        start_menu_directory().rmdir()
    except OSError:
        pass
    remove_uninstall_registry_entry()
    root = install_root()
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
        completed = subprocess.run([str(target), "--self-test"], timeout=120, creationflags=CREATE_NO_WINDOW)
        if completed.returncode != 0:
            raise RuntimeError(f"The installed Sarah application self-test returned {completed.returncode}.")
        remover = target.parent / "Remove Sarah Travel OS.cmd"
        if not remover.is_file() or "powershell" not in remover.read_text(encoding="utf-8").lower():
            raise RuntimeError("The Smart App Control-safe remover was not installed.")
    return 0


def show_result(title: str, message: str, error: bool = False) -> None:
    if messagebox is None:
        return
    try:
        (messagebox.showerror if error else messagebox.showinfo)(title, message)
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
        install(launch="--no-launch" not in arguments, shortcuts=True)
        if not silent:
            show_result(
                DISPLAY_NAME,
                "Sarah Travel OS 2.6 is installed.\n\n"
                "The chat composer is responsive, Sarah has a reliable Windows voice fallback, and the Remove Sarah shortcut uses Windows PowerShell instead of reopening the unsigned installer.\n\n"
                "Approve a phone only when its name and six-digit code match.",
            )
        return 0
    except Exception as error:
        show_result("Sarah installation could not finish", str(error), error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
