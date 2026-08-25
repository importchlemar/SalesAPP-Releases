@echo off
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

if not exist "signing\salesapp-release.jks" call "1_PRZYGOTUJ_STALY_PODPIS.bat"
if errorlevel 1 exit /b 10
call "2_ZBUDUJ_PRODUKCYJNY_APK.bat"
if errorlevel 1 exit /b 11
call "3_OPUBLIKUJ_AKTUALIZACJE_GITHUB.bat"
