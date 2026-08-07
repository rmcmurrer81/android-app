@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul || (echo Python 3.11 or newer is required.& pause & exit /b 1)
py -3 -m venv .venv || exit /b 1
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
python -m pytest -q
if errorlevel 1 (echo Tests failed. Sarah was not marked ready.& pause & exit /b 1)
echo Sarah Windows setup and tests completed.
pause
