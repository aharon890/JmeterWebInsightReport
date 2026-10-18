@echo off
REM Thin wrapper — all logic lives in open-jmeter-gui.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0open-jmeter-gui.sh" %*
exit /b %ERRORLEVEL%
