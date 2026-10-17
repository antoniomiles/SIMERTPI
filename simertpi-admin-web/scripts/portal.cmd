@echo off
setlocal
set "PORTAL_NODE=%~dp0..\..\.local-tools\node-v22.23.3-win-x64"
if not exist "%PORTAL_NODE%\node.exe" (
  echo Node portable no encontrado. Consulte README.md.
  exit /b 1
)
set "PATH=%PORTAL_NODE%;%PATH%"
set "npm_config_cache=%~dp0..\..\.local-tools\npm-cache"
set "NG_CLI_ANALYTICS=false"
cd /d "%~dp0.."
call "%PORTAL_NODE%\npm.cmd" %*
exit /b %errorlevel%
