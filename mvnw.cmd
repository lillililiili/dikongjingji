@echo off
setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
if "%JAVA_HOME%"=="" set "JAVA_HOME=%SCRIPT_DIR%.runtime\jdk\jdk-21.0.12+8"
set "MAVEN_ARGS=%*"
set "MAVEN_ARGS=!MAVEN_ARGS:-pl bootstrap=-pl :bootstrap!"
call "%SCRIPT_DIR%.runtime\maven\apache-maven-3.9.16\bin\mvn.cmd" !MAVEN_ARGS!
exit /b %ERRORLEVEL%
