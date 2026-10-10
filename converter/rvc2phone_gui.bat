@echo off
chcp 65001 >nul
rem The converter with a window. Double-click it, or drag .pth files onto it to open them in the window.
setlocal
set "HERE=%~dp0"
call "%HERE%setup.bat" || exit /b 1
start "" "%HERE%.venv\Scripts\pythonw.exe" "%HERE%rvc2phone_gui.py" %*
