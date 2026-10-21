@echo off
chcp 65001 > nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-desktop.ps1"
if errorlevel 1 (
    echo.
    echo [ОШИБКА] Установка завершилась с ошибкой.
    pause
    exit /b 1
)
echo.
pause
