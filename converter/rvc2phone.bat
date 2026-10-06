@echo off
chcp 65001 >nul
rem RVC voice (.pth) -> phone app files. Drag a .pth onto this file, or run: rvc2phone.bat voice.pth [--name NAME]
rem "rvc2phone.bat --common" builds the shared models (hubert.onnx, rmvpe.onnx).
rem The first run makes a private Python environment in .venv (PyTorch for the CPU and a few packages, ~1 GB).
setlocal
set "HERE=%~dp0"
set "PY=%HERE%.venv\Scripts\python.exe"
rem .venv\ready marks a finished install; an interrupted one starts over
if not exist "%HERE%.venv\ready" (
  echo [준비] 처음 한 번만: 변환에 쓸 파이썬 환경을 만들어요. 몇 분 걸려요...
  rmdir /s /q "%HERE%.venv" 2>nul
  py -3 -m venv "%HERE%.venv" 2>nul || python -m venv "%HERE%.venv" || goto nopython
  "%PY%" -m pip install --upgrade pip || goto failed
  "%PY%" -m pip install -r "%HERE%requirements.txt" || goto failed
  echo ok> "%HERE%.venv\ready"
)
if "%~1"=="" (
  "%PY%" "%HERE%rvc2phone.py" --help
  echo.
  echo 목소리 모델 .pth 파일을 이 파일 위에 끌어다 놓으세요. 공통 모델은 "rvc2phone.bat --common" 으로 만들어요.
  pause
  exit /b 0
)
"%PY%" "%HERE%rvc2phone.py" %*
pause
exit /b

:nopython
echo 파이썬 3.10 이상이 필요해요: https://www.python.org/downloads/ (설치할 때 "Add python.exe to PATH" 체크)
pause
exit /b 1

:failed
echo 설치하지 못했어요. 인터넷 연결을 확인하고 다시 실행하세요.
pause
exit /b 1
