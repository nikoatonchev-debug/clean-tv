@echo off
chcp 65001 >nul
rem Richtet Clean TV auf einem Android-TV ein. Es wird nichts gelöscht; die alte Startseite
rem wird nur abgeschaltet und lässt sich jederzeit zurückholen (Alte-Startseite-zurueck.bat
rem oder in Clean TV: Alle Apps -> Normales System).
echo.
echo  Clean TV einrichten
echo  ===================
echo  Voraussetzungen: Am Fernseher die Entwickleroptionen und "USB-Debugging" bzw.
echo  "Netzwerk-Debugging" einschalten. Fernseher und PC im gleichen WLAN.
echo.

set ADB=adb
where adb >nul 2>nul || set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" if not "%ADB%"=="adb" (
  echo FEHLER: adb nicht gefunden. Bitte die "SDK Platform-Tools" von Google herunterladen
  echo und adb.exe in diesen Ordner legen.
  pause & exit /b 1
)

set /p IP=IP-Adresse des Fernsehers (Einstellungen - Netzwerk):
"%ADB%" connect %IP%:5555
echo Falls am Fernseher "Debugging zulassen?" erscheint: "Immer zulassen" + OK.
timeout /t 5 >nul
set T=-s %IP%:5555

if not exist "%~dp0CleanTV.apk" (
  echo FEHLER: CleanTV.apk liegt nicht neben dieser Datei.
  pause & exit /b 1
)
"%ADB%" %T% install -r "%~dp0CleanTV.apk" || (echo FEHLER beim Installieren & pause & exit /b 1)

rem Rechte: schärferes Bild/Startseite, Musik-Anzeige, Home-Leiste
"%ADB%" %T% shell pm grant de.cleantv.launcher android.permission.WRITE_SECURE_SETTINGS
"%ADB%" %T% shell pm grant de.cleantv.launcher android.permission.RECORD_AUDIO
"%ADB%" %T% shell cmd notification allow_listener de.cleantv.launcher/de.cleantv.launcher.MediaListener
"%ADB%" %T% shell settings put secure enabled_accessibility_services de.cleantv.launcher/de.cleantv.launcher.HomeKeyService
"%ADB%" %T% shell settings put secure accessibility_enabled 1

rem Originale Startseite abschalten (nicht löschen) und Clean TV als Startseite setzen
for %%L in (com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher) do (
  "%ADB%" %T% shell pm disable-user --user 0 %%L >nul 2>nul
)
"%ADB%" %T% shell cmd package set-home-activity de.cleantv.launcher/.HomeActivity
"%ADB%" %T% shell input keyevent 3

echo.
echo FERTIG. Clean TV ist jetzt die Startseite.
pause
