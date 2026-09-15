@echo off
setlocal
set "JAVA_HOME=C:\Users\ADMIN\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0gradlew.bat" %*
endlocal
