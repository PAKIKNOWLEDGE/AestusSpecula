@echo off
chcp 65001 >nul
cd /d "%~dp0"

:: Load .env
for /f "usebackq delims=" %%a in (.env) do set "%%a"

:: Kill leftover
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3011 ^| findstr LISTENING') do taskkill /f /pid %%a >nul 2>&1

echo Step 1: Starting relay...
start /min python app.py

echo Waiting for relay...
for /l %%i in (1,1,8) do (
    timeout /t 1 /nobreak >nul
    >nul 2>&1 python -c "import urllib.request; urllib.request.urlopen('http://localhost:3011/healthz', timeout=2)" && goto relay_ok
)
echo Relay failed. Try: cd /d "%~dp0" ^&^& python app.py
pause
exit /b

:relay_ok
echo Relay OK!

echo Step 2: Starting AI bridge...
echo Browser: http://localhost:3011
echo.
set RELAY_URL=http://localhost:3011
set BRIDGE_STATE_DIR=%~dp0bridge_state
python bridge.py

:: Cleanup
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3011 ^| findstr LISTENING') do taskkill /f /pid %%a >nul 2>&1
echo Stopped.
pause
