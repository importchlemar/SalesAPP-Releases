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

if exist "signing\salesapp-release.jks" if exist "keystore.properties" (
  echo Klucz SalesAPP juz istnieje. Nie zmieniam go.
  pause
  exit /b 0
)
if not exist "signing" mkdir "signing"

echo Najpierw probuje zachowac podpis obecnej aplikacji...
if exist "%USERPROFILE%\.android\debug.keystore" (
  copy /Y "%USERPROFILE%\.android\debug.keystore" "signing\salesapp-release.jks" >nul
  >"keystore.properties" echo storeFile=signing/salesapp-release.jks
  >>"keystore.properties" echo storePassword=android
  >>"keystore.properties" echo keyAlias=androiddebugkey
  >>"keystore.properties" echo keyPassword=android
  echo.
  echo OK - skopiowano dotychczasowy klucz Android.
  echo Jesli obecny SalesAPP byl budowany na tym komputerze,
  echo wersja 1.33 powinna wejsc jako zwykla aktualizacja.
  goto :DONE
)

echo Nie znaleziono starego debug.keystore.
echo Tworzymy nowy staly klucz. Pierwsza instalacja moze wtedy
echo wymagac jednorazowego odinstalowania starego SalesAPP.
set /p PASS=Podaj haslo nowego klucza: 
if not defined PASS exit /b 10

if not exist "%JAVA_HOME%\bin\keytool.exe" (
  echo Brak keytool.exe w %JAVA_HOME%.
  pause
  exit /b 11
)

"%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore "signing\salesapp-release.jks" -alias salesapp ^
 -keyalg RSA -keysize 2048 -validity 10000 -storepass "%PASS%" -keypass "%PASS%" ^
 -dname "CN=CHLE-MAR SalesAPP, OU=IT, O=CHLE-MAR, L=Wieliczka, ST=Malopolskie, C=PL"
if errorlevel 1 exit /b 12

>"keystore.properties" echo storeFile=signing/salesapp-release.jks
>>"keystore.properties" echo storePassword=%PASS%
>>"keystore.properties" echo keyAlias=salesapp
>>"keystore.properties" echo keyPassword=%PASS%

:DONE
echo.
echo ZROB KOPIE ZAPASOWA:
echo signing\salesapp-release.jks
echo keystore.properties
echo.
echo Tego klucza nie wolno zmienic przy kolejnych aktualizacjach.
echo Nie wrzucaj go do GitHub.
pause
