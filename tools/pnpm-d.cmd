@echo off
setlocal
set "COREPACK_HOME=D:\DevCaches\corepack"
set "NPM_CONFIG_CACHE=D:\DevCaches\npm-cache"
set "PNPM_HOME=D:\DevTools\pnpm"
set "PNPM_STORE_DIR=D:\DevCaches\pnpm-store"
node "%COREPACK_HOME%\v1\pnpm\11.9.0\bin\pnpm.cjs" %*
exit /b %ERRORLEVEL%

