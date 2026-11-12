@echo off
rem Double-click to start desk-timer. Keep this window open: closing it stops the app.
chcp 65001 >nul
rem Usage: run.cmd                      -> resident with default hotkey ctrl+alt+f12
rem        run.cmd ctrl+alt+t           -> custom hotkey; overrides the key saved in tray > Settings
rem        run.cmd selftest             -> build, run tests and the end-to-end self check
rem ASCII only in this file: it is read with the OEM codepage before chcp takes effect.
cd /d "%~dp0"

if "%~1"=="selftest" (
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run-app.ps1
  goto :eof
)

echo [1/2] build + unit tests + package: 1-2 min with no output, that is normal.
echo        For daily use, prefer the portable app: D:\timer-app\desk-timer\desk-timer.exe
if "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run-app.ps1 -Task run
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File .\run-app.ps1 -Task run -Hotkey "%~1"
)
