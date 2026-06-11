@echo off
:: Ensure we are in the script's directory
cd /d "%~dp0"

:: Use the -d flag to run the bot in detached mode.

call gradlew.bat --no-daemon build
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

if "%1"=="-d" (start javaw -jar "build\libs\ukulele.jar") else (java -jar "build\libs\ukulele.jar")
