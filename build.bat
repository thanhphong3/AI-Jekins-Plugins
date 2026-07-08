@echo off
title AI Log Analyzer Jenkins Plugin Builder
echo ========================================================
echo   AI Log Analyzer Jenkins Plugin - Packaging Tool
echo ========================================================
echo.

:: Check if Maven is available in PATH
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Maven (mvn) was not found in your PATH.
    echo Please make sure Maven is installed and added to your System Environment Variables.
    echo.
    pause
    exit /b 1
)

echo [INFO] Running 'mvn clean package'...
echo.
call mvn clean package

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Build failed! Please check the errors above.
    echo.
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================================
echo [SUCCESS] Build completed successfully!
echo.
echo Your plugin package is located at:
echo target\ai-log-analyzer.hpi
echo ========================================================
echo.
pause
