@echo off
setlocal
cd /d "%~dp0"
set "OFFICE_JAVA=java"
if defined JAVA_HOME set "OFFICE_JAVA=%JAVA_HOME%\bin\java.exe"
"%OFFICE_JAVA%" -jar AI-Office-Java.jar
if errorlevel 1 pause
