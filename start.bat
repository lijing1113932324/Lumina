@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================
echo Starting Lumina Toolkit...
echo ============================================

echo.
echo [1/4] Compiling CryptoServer (library)...
javac -cp "lib/*" -d out src\CryptoServer.java
if !errorlevel! neq 0 (
    echo Failed to compile CryptoServer!
    pause
    exit /b 1
)

echo.
echo [2/4] Compiling HttpProxyServer (library)...
javac -cp "lib/*" -d out src\HttpProxyServer.java
if !errorlevel! neq 0 (
    echo Failed to compile HttpProxyServer!
    pause
    exit /b 1
)

echo.
echo [3/4] Compiling DbQueryServer (library)...
javac -cp "lib/*" -d out src\DbQueryServer.java
if !errorlevel! neq 0 (
    echo Failed to compile DbQueryServer!
    pause
    exit /b 1
)

echo.
echo [4/4] Compiling and Starting SimpleServer...
javac -cp "lib/*;out" -d out src\SimpleServer.java
if !errorlevel! neq 0 (
    echo Failed to compile SimpleServer!
    pause
    exit /b 1
)

echo.
echo SimpleServer will run on port 8080 (auto-switch if occupied)
echo.

start "SimpleServer" java -cp "lib/*;out" src.SimpleServer

echo.
echo Server started successfully!
echo Access: http://localhost:8080
echo Press any key to exit...
pause