@echo off
REM Theft Guard - one-shot setup over ADB (USB debugging) for Windows.
REM See adb-setup.sh for the full explanation and prerequisites.
setlocal
set PKG=com.theftguard.app

where adb >nul 2>nul || (echo adb not found. Install Android platform-tools first. & exit /b 1)

echo Waiting for a device (authorise the USB debugging prompt on the phone)...
adb wait-for-device

echo Unlocking restricted settings...
adb shell appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow

echo Granting runtime permissions...
for %%P in (RECEIVE_SMS SEND_SMS POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION) do adb shell pm grant %PKG% android.permission.%%P

echo Allowing full-screen alarm and ignoring battery optimisation...
adb shell appops set %PKG% USE_FULL_SCREEN_INTENT allow
adb shell dumpsys deviceidle whitelist +%PKG%

echo Enabling device-admin lock...
adb shell dpm set-active-admin %PKG%/.AdminReceiver

echo Enabling volume-lock accessibility service (may overwrite other services - re-check Accessibility settings)...
adb shell settings put secure enabled_accessibility_services %PKG%/.VolumeLockService
adb shell settings put secure accessibility_enabled 1

echo.
echo Done. Set a screen lock and keep Location on. Mobile data cannot be granted to the app.
echo To turn data on for a test:  adb shell svc data enable
endlocal
