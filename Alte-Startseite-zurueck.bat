@echo off
chcp 65001 >nul
rem Schaltet die originale Startseite wieder ein. Es wird nichts gelöscht.
set ADB=adb
where adb >nul 2>nul || set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set /p IP=IP-Adresse des Fernsehers:
"%ADB%" connect %IP%:5555
set T=-s %IP%:5555
"%ADB%" %T% shell settings put secure enabled_accessibility_services ""
for %%L in (com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher) do (
  "%ADB%" %T% shell pm enable %%L >nul 2>nul
)
"%ADB%" %T% shell "C=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -E 'tvlauncher|launcherx|leanbacklauncher' | head -1 | tr -d ' '); cmd package set-home-activity $C"
"%ADB%" %T% shell input keyevent 3
echo Fertig. Die originale Startseite ist wieder da.
pause
