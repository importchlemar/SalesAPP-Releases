@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :JAVA_OK
set "JAVA_HOME="
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jre\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
if not defined JAVA_HOME (
  for /d %%D in ("C:\Program Files\Java\jdk-*") do if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
)
if not defined JAVA_HOME (
  echo Brak JAVA.
  pause
  exit /b 90
)

:JAVA_OK
set "PATH=%JAVA_HOME%\bin;%PATH%"
if exist "BUILD_KOTLIN_TEST.log" del /q "BUILD_KOTLIN_TEST.log"

echo Sprawdzam kompilacje Kotlin...
call gradlew.bat :app:compileReleaseKotlin --console=plain >"BUILD_KOTLIN_TEST.log" 2>&1
if errorlevel 1 goto :ERR

echo.
echo ============================================================
echo KOMPILACJA KOTLIN OK
echo ============================================================
echo Mozesz uruchomic 2_ZBUDUJ_PRODUKCYJNY_APK.bat
pause
exit /b 0

:ERR
echo.
echo ============================================================
echo BLAD KOMPILACJI
echo ============================================================
findstr /I /C:" e: " /C:"error:" /C:"Unresolved reference" /C:"Compilation error" "BUILD_KOTLIN_TEST.log"
echo.
echo Pelny log: %CD%\BUILD_KOTLIN_TEST.log
pause
exit /b 20
