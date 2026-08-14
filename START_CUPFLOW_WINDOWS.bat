@echo off
setlocal
title CupFlow Local Server
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo Node.js 22.13 or later is required.
  echo Install it, then run this file again.
  echo.
  pause
  exit /b 1
)

if not exist node_modules (
  echo Installing CupFlow dependencies. This is only needed the first time.
  call npm install
  if errorlevel 1 (
    echo.
    echo Installation failed. Please check the network connection and run this file again.
    pause
    exit /b 1
  )
)

echo Starting CupFlow...
start "CupFlow Server" /D "%~dp0" cmd /k "npx vinext dev"
timeout /t 5 /nobreak >nul
start "" http://localhost:3000/
exit
