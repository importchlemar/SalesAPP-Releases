@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

rem ============================================================
rem  AUTO-WYKRYWANIE JAVA / JDK
rem ============================================================
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :JAVA_OK

set "JAVA_HOME="

if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
  goto :JAVA_OK
)

if exist "C:\Program Files\Android\Android Studio\jre\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Android\Android Studio\jre"
  goto :JAVA_OK
)

for /d %%D in ("C:\Program Files\Java\jdk-*") do (
  if exist "%%~fD\bin\java.exe" (
    set "JAVA_HOME=%%~fD"
    goto :JAVA_OK
  )
)

for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*") do (
  if exist "%%~fD\bin\java.exe" (
    set "JAVA_HOME=%%~fD"
    goto :JAVA_OK
  )
)

for /d %%D in ("C:\Program Files\Microsoft\jdk-*") do (
  if exist "%%~fD\bin\java.exe" (
    set "JAVA_HOME=%%~fD"
    goto :JAVA_OK
  )
)

echo.
echo ============================================================
echo BLAD: NIE ZNALEZIONO JAVA / JDK
echo ============================================================
echo.
echo Najprosciej zainstaluj Android Studio z domyslnym JBR
echo albo JDK 17.
echo.
echo Sprawdzilem m.in.:
echo C:\Program Files\Android\Android Studio\jbr
echo C:\Program Files\Java\jdk-*
echo C:\Program Files\Eclipse Adoptium\jdk-*
echo C:\Program Files\Microsoft\jdk-*
echo.
pause
exit /b 90

:JAVA_OK
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
echo.

if not exist "signing\salesapp-release.jks" (
  echo Najpierw uruchom 1_PRZYGOTUJ_STALY_PODPIS.bat
  pause
  exit /b 10
)
if not exist "keystore.properties" exit /b 11

if exist "BUILD_RELEASE.log" del /q "BUILD_RELEASE.log"

echo Czyszczenie projektu...
call gradlew.bat clean --console=plain >>"BUILD_RELEASE.log" 2>&1
if errorlevel 1 goto :ERR

echo Kompilacja podpisanej wersji RELEASE...
call gradlew.bat assembleRelease --console=plain >>"BUILD_RELEASE.log" 2>&1
if errorlevel 1 goto :ERR

if not exist "app\build\outputs\apk\release\app-release.apk" goto :ERR
if not exist "RELEASE" mkdir "RELEASE"
copy /Y "app\build\outputs\apk\release\app-release.apk" "RELEASE\SalesAPP.apk" >nul

echo.
echo GOTOWE:
echo %CD%\RELEASE\SalesAPP.apk
pause
exit /b 0

:ERR
echo.
echo ============================================================
echo BLAD BUDOWANIA APK
echo ============================================================
echo.
echo Dokladne bledy kompilatora:
echo ------------------------------------------------------------
findstr /I /C:" e: " /C:"error:" /C:"Unresolved reference" /C:"Compilation error" "BUILD_RELEASE.log"
echo ------------------------------------------------------------
echo.
echo Pelny log:
echo %CD%\BUILD_RELEASE.log
echo.
pause
exit /b 20
