@echo off
REM Thin wrapper — all logic lives in run-docker-tests.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0run-docker-tests.sh" %*
exit /b %ERRORLEVEL%
