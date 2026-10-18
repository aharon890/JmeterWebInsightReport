@echo off
REM Thin wrapper — all logic lives in run-config-tests.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0run-config-tests.sh" %*
exit /b %ERRORLEVEL%
