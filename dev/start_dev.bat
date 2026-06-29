@echo off
chcp 65001 >nul
cd /d "%~dp0"

:: Create .env from example if not exists
if not exist .env (
    copy .env.example .env >nul
    echo RELAY_SECRET=dev_secret_123>> .env
    echo RELAY_WEB_DIR=%~dp0..\web>> .env
    echo Created .env with default secret (dev_secret_123)
)

:: Load .env
for /f "usebackq delims=" %%a in (.env) do set "%%a"

set RELAY_WEB_DIR=%~dp0..\web

echo.
echo Starting dev server...
echo   URL:  http://localhost:%RELAY_PORT%
echo   Secret: %RELAY_SECRET%
echo.
echo Press Ctrl+C to stop.
echo.

:: Run in same window so Ctrl+C actually kills it
python app.py

:: Force kill python on our port (gentle, only our process)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%RELAY_PORT% ^| findstr LISTENING') do (
    taskkill /f /pid %%a >nul 2>&1
)

echo Server stopped.
