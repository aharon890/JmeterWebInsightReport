@echo off
REM Thin wrapper — all logic lives in wir-collect.sh (single source of truth).
REM Requires Git Bash's or MSYS2's bash on PATH, ahead of System32's. Copy both files together.
bash "%~dp0wir-collect.sh" %*
exit /b %ERRORLEVEL%
