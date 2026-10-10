@echo off
rem Makes the private Python environment in .venv once (PyTorch for the CPU and a few packages, ~1 GB).
rem .venv\ready marks a finished install; an interrupted one starts over. Called by rvc2phone.bat and rvc2phone_gui.bat.
set "HERE=%~dp0"
set "PY=%HERE%.venv\Scripts\python.exe"
if exist "%HERE%.venv\ready" exit /b 0
echo [준비] 처음 한 번만: 변환에 쓸 파이썬 환경을 만들어요. 몇 분 걸려요...
rmdir /s /q "%HERE%.venv" 2>nul
py -3 -m venv "%HERE%.venv" 2>nul || python -m venv "%HERE%.venv" || goto nopython
"%PY%" -m pip install --upgrade pip || goto failed
"%PY%" -m pip install -r "%HERE%requirements.txt" || goto failed
echo ok> "%HERE%.venv\ready"
exit /b 0

:nopython
echo 파이썬 3.10 이상이 필요해요: https://www.python.org/downloads/ (설치할 때 "Add python.exe to PATH" 체크)
pause
exit /b 1

:failed
echo 설치하지 못했어요. 인터넷 연결을 확인하고 다시 실행하세요.
pause
exit /b 1
