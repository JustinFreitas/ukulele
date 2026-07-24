@echo off
:: Ensure we are in the script's directory
cd /d "%~dp0"

:: Use the -d flag to run the bot in detached mode.

call gradlew.bat --no-daemon bootJar
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

set "JAVA_EXE=C:\Users\Justin\.jdks\azul-25.0.2\bin\java.exe"
set "JAVAW_EXE=C:\Users\Justin\.jdks\azul-25.0.2\bin\javaw.exe"

if not exist "%JAVA_EXE%" (
    if defined JAVA_HOME (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        set "JAVAW_EXE=%JAVA_HOME%\bin\javaw.exe"
    ) else (
        set "JAVA_EXE=java"
        set "JAVAW_EXE=javaw"
    )
)

if "%1"=="-d" (start "" "%JAVAW_EXE%" --enable-native-access=ALL-UNNAMED -jar "build\libs\ukulele.jar") else ("%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -jar "build\libs\ukulele.jar")

