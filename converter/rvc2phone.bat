@echo off
chcp 65001 >nul
rem RVC voice (.pth) -> phone app files. Drag a .pth onto this file, or run: rvc2phone.bat voice.pth [--name NAME]
rem "rvc2phone.bat --common" builds the shared models (hubert.onnx, rmvpe.onnx). rvc2phone_gui.bat does the same in a window.
rem The first run makes a private Python environment in .venv (PyTorch for the CPU and a few packages, ~1 GB; setup.bat).
setlocal
set "HERE=%~dp0"
set "PY=%HERE%.venv\Scripts\python.exe"
call "%HERE%setup.bat" || exit /b 1
if "%~1"=="" (
  "%PY%" "%HERE%rvc2phone.py" --help
  echo.
  echo 목소리 모델 .pth 파일을 이 파일 위에 끌어다 놓으세요. 공통 모델은 "rvc2phone.bat --common" 으로 만들어요.
  echo 창으로 쓰려면 rvc2phone_gui.bat 을 여세요.
  pause
  exit /b 0
)
"%PY%" "%HERE%rvc2phone.py" %*
pause
