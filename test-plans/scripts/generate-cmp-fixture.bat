@echo off
REM Thin wrapper — all logic lives in generate-cmp-fixture.sh (single source of truth).
REM Requires bash on PATH: Git Bash, WSL, or MSYS2.
bash "%~dp0generate-cmp-fixture.sh" %*
exit /b %ERRORLEVEL%
