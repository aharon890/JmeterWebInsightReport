@echo off
REM Thin wrapper - all logic lives in measure-run-data.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0measure-run-data.sh" %*
exit /b %ERRORLEVEL%
