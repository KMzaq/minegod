@echo off
setlocal
set "PROJECT_DIR=C:\Users\ADMIN\Desktop\markmar\mythictrpg-main"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] Java 21 runtime not found: %JAVA_HOME%
    exit /b 1
)

if not exist "%PROJECT_DIR%\gradlew.bat" (
    echo [ERROR] MythicTRPG development project not found: %PROJECT_DIR%
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%PROJECT_DIR%"
call gradlew.bat "-Dorg.gradle.java.home=%JAVA_HOME%" %*
exit /b %ERRORLEVEL%
