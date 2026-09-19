@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\pythonw.exe (echo Run SETUP_SARAH_WINDOWS.bat first.& pause & exit /b 1)
start "Sarah Morgan" .venv\Scripts\pythonw.exe sarah_windows.py
