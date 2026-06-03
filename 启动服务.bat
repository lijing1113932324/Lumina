@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================
echo  Timestamp & JSON Formatter & MySQL Query
echo          Startup Script (Auto JDK Detection)
echo ============================================
echo.

set "LIB_DIR=%~dp0lib"

echo Checking Java environment...
java -version
if %errorlevel% neq 0 (
    echo Error: Java not found. Please install JDK 1.7+ and add to PATH
    pause
    exit /b 1
)

echo.
echo Checking lib directory...
if not exist "%LIB_DIR%" (
    echo Error: lib directory not found. Please create it and add JAR files
    pause
    exit /b 1
)

set "CLASSPATH="
for %%f in ("%LIB_DIR%\*.jar") do (
    set "CLASSPATH=!CLASSPATH!;%%f"
)

echo.
echo Compiling server code...
javac -cp "%CLASSPATH%" SimpleServer.java

if %errorlevel% neq 0 (
    echo Compilation failed! Please check JAR files
    pause
    exit /b 1
)

echo Compilation successful!

echo.
echo Starting server...
java -cp ".;%CLASSPATH%" SimpleServer

echo.
echo Server stopped
pause