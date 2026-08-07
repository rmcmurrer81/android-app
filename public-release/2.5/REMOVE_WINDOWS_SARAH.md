# Remove Sarah Travel OS from Windows without running the blocked EXE

Smart App Control may block the unsigned Sarah installer when the Start-menu uninstall shortcut tries to run that installer again. You do **not** need to disable Smart App Control.

This removal uses only Windows' built-in PowerShell and preserves Sarah's separate mind/data folder at `%APPDATA%\SarahMorgan` so it can be restored or reused later.

1. Open **Start**, type **PowerShell**, and open **Windows PowerShell** or **Terminal**. Administrator mode is not required because Sarah was installed for the current Windows account.
2. Paste this exact command and press Enter:

```powershell
Get-Process SarahTravelOS -ErrorAction SilentlyContinue | Stop-Process -Force; Remove-Item "$env:LOCALAPPDATA\SarahTravelOS" -Recurse -Force -ErrorAction SilentlyContinue; Remove-Item "$env:USERPROFILE\Desktop\Sarah Travel OS.lnk" -Force -ErrorAction SilentlyContinue; Remove-Item "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Sarah Travel OS" -Recurse -Force -ErrorAction SilentlyContinue; Remove-Item "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SarahTravelOS" -Recurse -Force -ErrorAction SilentlyContinue
```

3. Close PowerShell.
4. If the Sarah window or shortcut is still visible, restart Windows once.

## Optional: erase Sarah's local Windows mind/data too

Only do this when the user explicitly wants the Windows copy's conversations, memories, trips, photos, voice cache, and backups removed from this account:

```powershell
Remove-Item "$env:APPDATA\SarahMorgan" -Recurse -Force -ErrorAction SilentlyContinue
```

The installer hotfix changes future uninstall shortcuts and the Windows Installed Apps entry to use Windows' built-in removal command instead of reopening the unsigned Sarah installer EXE.
