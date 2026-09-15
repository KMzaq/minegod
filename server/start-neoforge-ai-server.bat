@echo off
setlocal
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo [ERROR] Java 21 was not found at:
    echo %JAVA_HOME%
    echo Install Java 21, then update JAVA_HOME in this file.
    echo.
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo ==============================================
echo  MythicTRPG Integration Test Server
echo ==============================================
if exist "%~dp0mods\mythai_ai_response*.jar" (
    echo  Separate local AI engine: detected
    echo  Ollama must be running on port 11434.
) else (
    echo  Separate local AI engine: NOT INSTALLED
    echo  Testing safe no-engine fallback and core interaction only.
)
echo.

call "%~dp0run.bat" nogui
exit /b %ERRORLEVEL%
