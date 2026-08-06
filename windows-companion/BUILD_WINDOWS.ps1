# Build a portable Windows executable from a Windows runner.
pyinstaller --noconfirm --clean --onefile --windowed --name Sarah-Morgan-Windows --collect-all pystray --collect-all PIL sarah_windows.py
