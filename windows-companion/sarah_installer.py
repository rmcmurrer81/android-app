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
UNINSTALL_REGISTRY_KEY = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\SarahTravelOS"
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


def powershell_executable() -> Path:
    discovered = shutil.which("powershell.exe") or shutil.which("powershell")
    if discovered:
        return Path(discovered)
    system_root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
    return system_root / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"


def powershell_quote(value: Path | str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def command_argument_quote(value: str) -> str:
    return '"' + value.replace('"', '\\"') + '"'


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


def removal_powershell_command() -> str:
    """Return a Windows-built-in uninstall command that does not reopen Sarah's unsigned installer."""
    root = powershell_quote(install_root())
    desktop_link = powershell_quote(desktop_directory() / "Sarah Travel OS.lnk")
    start_folder = powershell_quote(start_menu_directory())
    registry_key = powershell_quote(r"HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SarahTravelOS")
    return (
        "$ErrorActionPreference='SilentlyContinue';"
        "Get-Process -Name 'SarahTravelOS' | Stop-Process -Force;"
        f"Remove-Item -LiteralPath {desktop_link} -Force;"
        f"Remove-Item -LiteralPath {start_folder} -Recurse -Force;"
        f"Remove-Item -LiteralPath {registry_key} -Recurse -Force;"
        f"Remove-Item -LiteralPath {root} -Recurse -Force"
    )


def removal_arguments(*, hidden: bool = True) -> str:
    window = " -WindowStyle Hidden" if hidden else ""
    return (
        f"-NoProfile -ExecutionPolicy Bypass{window} -Command "
        + command_argument_quote(removal_powershell_command())
    )


def write_builtin_remover(root: Path) -> Path:
    """Create a readable remover that invokes only Microsoft's built-in PowerShell executable."""
    remover = root / "Remove Sarah Travel OS.cmd"
    executable = str(powershell_executable())
    remover.write_text(
        "@echo off\r\n"
        "rem Sarah Travel OS account-local remover. This does not disable Smart App Control.\r\n"
        f'"{executable}" {removal_arguments(hidden=False)}\r\n'
        "exit /b %errorlevel%\r\n",
        encoding="utf-8",
    )
    return remover


def register_windows_uninstaller(target: Path) -> None:
    """Make Installed Apps use trusted Windows PowerShell instead of rerunning the unsigned installer."""
    if os.name != "nt":
        return
    import winreg

    uninstall_string = f'"{powershell_executable()}" {removal_arguments(hidden=False)}'
    quiet_uninstall_string = f'"{powershell_executable()}" {removal_arguments(hidden=True)}'
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, UNINSTALL_REGISTRY_KEY) as key:
        winreg.SetValueEx(key, "DisplayName", 0, winreg.REG_SZ, DISPLAY_NAME)
        winreg.SetValueEx(key, "DisplayVersion", 0, winreg.REG_SZ, "2.5.1-uninstall-hotfix")
        winreg.SetValueEx(key, "Publisher", 0, winreg.REG_SZ, "Kira World")
        winreg.SetValueEx(key, "InstallLocation", 0, winreg.REG_SZ, str(install_root()))
        winreg.SetValueEx(key, "DisplayIcon", 0, winreg.REG_SZ, str(target))
        winreg.SetValueEx(key, "UninstallString", 0, winreg.REG_SZ, uninstall_string)
        winreg.SetValueEx(key, "QuietUninstallString", 0, winreg.REG_SZ, quiet_uninstall_string)
        winreg.SetValueEx(key, "NoModify", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "NoRepair", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(key, "EstimatedSize", 0, winreg.REG_DWORD, max(1, target.stat().st_size // 1024))


def remove_uninstall_registry_entry() -> None:
    if os.name != "nt":
        return
    import winreg

    try:
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, UNINSTALL_REGISTRY_KEY)
    except FileNotFoundError:
        pass
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

    metadata = {
        "display_name": DISPLAY_NAME,
        "version": "2.5.1-uninstall-hotfix",
        "installed_at": int(time.time()),
        "executable": str(target),
        "uninstall_method": "windows-built-in-powershell",
    }
    (root / "install.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
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
    root = install_root()
    for shortcut in [
        desktop_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Sarah Travel OS.lnk",
        start_menu_directory() / "Uninstall Sarah Travel OS.lnk",
        start_menu_directory() / "Remove Sarah Travel OS.lnk",
    ]:
        try:
            shortcut.unlink()
        except FileNotFoundError:
            pass
    try:
        start_menu_directory().rmdir()
    except OSError:
        pass
    remove_uninstall_registry_entry()

    # A running installer cannot delete itself immediately. Schedule cleanup in trusted Windows cmd.
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
        remover = target.parent / "Remove Sarah Travel OS.cmd"
        if not remover.is_file() or "powershell" not in remover.read_text(encoding="utf-8").lower():
            raise RuntimeError("The Smart App Control-safe remover was not installed.")
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
                "The Remove Sarah shortcut and Windows Installed Apps entry now use Windows' built-in PowerShell, so removal does not require rerunning the unsigned installer.\n\n"
                "When Sarah finds your phone on private Wi-Fi, approve only when the device name and six-digit code match.",
            )
        return 0
    except Exception as error:
        show_result("Sarah installation could not finish", str(error), error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
