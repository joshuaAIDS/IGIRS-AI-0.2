@echo off
title IGIRS AI 0.2 - Cyber Command Center
cd /d "%~dp0"
if exist ".venv\Scripts\activate.bat" (
    call .venv\Scripts\activate.bat
)
python run_desktop.py
if errorlevel 1 (
    echo.
    echo [ERROR] Application exited with an error.
    pause
)
