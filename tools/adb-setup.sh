#!/usr/bin/env bash
#
# Theft Guard - one-shot setup over ADB (USB debugging).
#
# For tech users: instead of tapping through Settings on the phone, connect it
# by USB with "USB debugging" enabled (Developer options) and run this script.
# It grants every permission the app needs - including the ones Android normally
# hides behind "Allow restricted settings" and the "Allow all the time" location
# prompt - in one go.
#
# Usage:
#   1. On the phone: Settings > About phone > tap "Build number" 7 times,
#      then Settings > Developer options > turn on "USB debugging".
#   2. Install Theft Guard (adb install TheftGuard.apk, or from the phone).
#   3. Connect USB, accept the "Allow USB debugging?" prompt on the phone.
#   4. Run:  ./adb-setup.sh
#
set -euo pipefail

PKG="com.theftguard.app"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools first." >&2
  exit 1
fi

echo "Waiting for a device (authorise the USB debugging prompt on the phone)..."
adb wait-for-device

grant() {
  # Grant a runtime permission; ignore if the OS version doesn't define it.
  adb shell pm grant "$PKG" "$1" 2>/dev/null && echo "  granted $1" || echo "  skipped $1 (not applicable)"
}

echo "Unlocking restricted settings (lets the SMS + accessibility toggles work)..."
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true

echo "Granting runtime permissions..."
grant android.permission.RECEIVE_SMS
grant android.permission.SEND_SMS
grant android.permission.POST_NOTIFICATIONS
grant android.permission.ACCESS_FINE_LOCATION
grant android.permission.ACCESS_COARSE_LOCATION
grant android.permission.ACCESS_BACKGROUND_LOCATION   # "Allow all the time"

echo "Allowing full-screen alarm + ignoring battery optimisation..."
adb shell appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

echo "Enabling the device-admin lock (locks the screen when the alarm starts)..."
adb shell dpm set-active-admin "$PKG"/.AdminReceiver >/dev/null 2>&1 || \
  echo "  (device admin not set - enable it in the app if you want auto-lock)"

echo "Enabling the volume-lock accessibility service..."
SVC="$PKG/.VolumeLockService"
CUR=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
  adb shell settings put secure enabled_accessibility_services "$SVC"
elif ! echo "$CUR" | grep -q "$SVC"; then
  adb shell settings put secure enabled_accessibility_services "$CUR:$SVC"
fi
adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true

echo
echo "Done. Open Theft Guard - the required items should show green."
echo "Still do yourself: set a screen lock (PIN/pattern) and keep Location turned on."
echo
echo "Note: mobile data cannot be granted to the app. If you want to test the"
echo "location SMS over data, you can turn data on now with:  adb shell svc data enable"
