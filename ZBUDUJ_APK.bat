@echo off
chcp 65001 >nul
cd /d "%~dp0"
call gradlew.bat assembleDebug
if errorlevel 1 pause
echo.
echo APK: app\build\outputs\apk\debug\app-debug.apk
pause
