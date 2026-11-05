@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title T'OS oppsett
echo.
echo  T'OS oppsett med PC
echo  ====================
echo  Gir T'OS alle tillatelsene paa en gang, uten Android-menyene paa stereoen.
echo  Legg denne fila i samme mappe som adb.exe (platform-tools).
echo  Se docs/ADB-OPPSETT.md for hele veiledningen.
echo.
cd /d "%~dp0"
if not exist adb.exe (
  echo  Fant ikke adb.exe her. Legg tos-oppsett.bat i mappa platform-tools og proev igjen.
  pause
  exit /b 1
)
set /p IP= Stereoens adresse (f.eks. 192.168.1.50:5555 - trykk bare Enter hvis den alt er koblet til): 
if not "%IP%"=="" adb connect %IP%
adb wait-for-device
echo.
echo  Koblet til. Gir tillatelser ...
echo.
set KS=no.bil.launcher/no.bil.launcher.KeyService
rem Knappestyring: legg T'OS til tjenestene stereoen alt har paa (ikke slaa av dem)
set CUR=
for /f "delims=" %%A in ('adb shell settings get secure enabled_accessibility_services') do set CUR=%%A
if /i "%CUR%"=="null" set CUR=
echo %CUR% | find /i "%KS%" >nul
if errorlevel 1 (
  if "%CUR%"=="" (set NEW=%KS%) else (set NEW=%CUR%:%KS%)
) else (
  set NEW=%CUR%
)
adb shell settings put secure enabled_accessibility_services !NEW!
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener no.bil.launcher/no.bil.launcher.MediaListener
adb shell appops set no.bil.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops set no.bil.launcher WRITE_SETTINGS allow
adb shell appops set no.bil.launcher REQUEST_INSTALL_PACKAGES allow
adb shell appops set no.bil.launcher ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set no.bil.launcher GET_USAGE_STATS allow
adb shell pm grant no.bil.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant no.bil.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_CONNECT
adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_SCAN
adb shell pm grant no.bil.launcher android.permission.POST_NOTIFICATIONS
adb shell pm grant no.bil.launcher android.permission.READ_CONTACTS
adb shell pm grant no.bil.launcher android.permission.READ_CALL_LOG
adb shell pm grant no.bil.launcher android.permission.CALL_PHONE
adb shell pm grant no.bil.launcher android.permission.READ_PHONE_STATE
adb shell cmd wifi network-suggestions-set-user-approved no.bil.launcher yes
adb shell dumpsys deviceidle whitelist +no.bil.launcher
adb shell cmd package set-home-activity no.bil.launcher/.MainActivity
adb shell cmd role add-role-holder android.app.role.DIALER no.bil.launcher
echo.
echo  Ferdig! Feilmeldinger som "Unknown permission" eller "Unknown operation" kan overses
echo  (de gjelder andre Android-versjoner).
echo  Sjekk hakene i T'OS: Innstillinger - Om stereoen - Foerstegangsoppsett.
echo.
pause
