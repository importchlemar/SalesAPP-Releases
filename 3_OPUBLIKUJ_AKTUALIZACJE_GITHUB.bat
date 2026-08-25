@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "REPO=importchlemar/SalesAPP-Releases"
set "APK=RELEASE\SalesAPP.apk"

if not exist "%APK%" (
  echo Najpierw zbuduj RELEASE\SalesAPP.apk
  pause
  exit /b 10
)
where gh >nul 2>nul
if errorlevel 1 (
  echo Brak GitHub CLI. Zainstaluj gh i wykonaj gh auth login.
  pause
  exit /b 11
)

set /p VERSION=Podaj wersje, np. 1.34.0: 
if not defined VERSION exit /b 12
set /p NOTES=Opis zmian: 
if not defined NOTES set "NOTES=Aktualizacja CHLE-MAR SalesAPP"

gh release create "v%VERSION%" "%APK%" --repo "%REPO%" --title "SalesAPP v%VERSION%" --notes "%NOTES%"
if errorlevel 1 (
  echo Jesli release juz istnieje:
  echo gh release upload "v%VERSION%" "%APK%" --repo "%REPO%" --clobber
  pause
  exit /b 20
)

echo.
echo Gotowe. SalesAPP wykryje release przy uruchomieniu
echo i pokaze systemowe powiadomienie o nowej wersji.
pause
