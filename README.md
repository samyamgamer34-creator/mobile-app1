# Theft Guard

An Android anti-theft app. When the phone receives an SMS whose text is exactly

```
Stolen
```

it announces **"This phone is stolen!"** with a siren, at full volume, over and over,
and texts the phone's **GPS location** to your trusted numbers.
Nobody can turn the volume down. The alarm stops only when someone unlocks the phone
with the owner's PIN, pattern, password or fingerprint.

> This is Android only. iOS does not let apps read incoming SMS, so the app can't be built for iPhone.

## How it works

| Requirement | Implementation |
|---|---|
| Trigger only on the exact text "Stolen" | `SmsReceiver` joins multi-part messages and checks `body.trim() == "Stolen"`. It is case-sensitive, so `stolen`, `STOLEN` and `Stolen phone` are ignored. Only surrounding whitespace is trimmed, because some carriers add it. |
| Say "this phone is stolen" | `AlarmService` alternates text-to-speech ("Warning! This phone is stolen!") with a generated siren. Both play on the **alarm** audio stream, so silent or vibrate mode doesn't mute them. |
| Full volume that can't be lowered | The alarm and media streams are forced to maximum and unmuted. A `ContentObserver` on the volume settings and a 200 ms poll put the volume straight back if anything lowers it. The alarm screen consumes the volume keys. The optional **accessibility service** (`VolumeLockService`) blocks the volume buttons system-wide, including on the lock screen. |
| Stops only when unlocked | The alarm listens for `ACTION_USER_PRESENT`, which Android sends when the lock screen is dismissed. The alarm screen has no stop button. The back button is ignored. |
| Thief has the phone unlocked | With the optional **device admin** enabled, the phone locks itself (`lockNow()`) as soon as the alarm starts. |
| Thief reboots the phone | The alarm state is stored in device-protected storage. The alarm restarts on `LOCKED_BOOT_COMPLETED`, before the PIN is entered. The first PIN unlock after boot (`BOOT_COMPLETED`) cancels it. |
| GPS location (one message) | `LocationReporter` waits for one fresh fix (falling back to last known) and sends a Google Maps link, accuracy, time and battery level **once** per alarm. To get an updated location, the owner sends "Stolen" again. Delivery is either **SMS** to the trusted numbers (works without data) or **email** over SMTP. Email is offered only when Shizuku is granted, because it needs the data/Wi-Fi Shizuku turns on; the SMTP host, app password, sender and recipient are entered in setup. Only the configured recipients get the location, whoever sends "Stolen". |
| Do Not Disturb | Alarm-stream audio normally bypasses Do Not Disturb. If the app has DND access, it also switches DND off for the duration and then restores it. |

When the alarm stops, the original volume levels and DND mode are restored.

## Setup on the phone

Open **Theft Guard** and complete the checklist:

1. **Secure screen lock** (required). Without a PIN or pattern, anyone can "unlock" the phone and stop the alarm.
2. **SMS and notification permission** (required).
3. **Trusted numbers**, **Location permission** (choose *Allow all the time*) and **Location turned on** (required for the GPS SMS).
4. **Full-screen alarm** (required, Android 14+).
5. **Ignore battery optimisation** (required). This stops Android from delaying the alarm.
6. **Lock screen on alarm** (recommended). This is the device admin.
7. **Block volume buttons** (recommended). Turn on *Theft Guard volume lock* in Accessibility settings.
   On Android 13+, a sideloaded APK must first get *App info → ⋮ → Allow restricted settings*. This also applies to the SMS permission.
8. **Override Do Not Disturb** (recommended).

Use **Test alarm** to try it. To stop the test, unlock the phone. If device admin is off, press the power button to lock the phone, then unlock it.

## Auto-enabling data & location with Shizuku (optional)

Plain ADB can't let the app switch mobile data on by itself. [Shizuku](https://shizuku.rikka.app/)
can: it runs a small service at ADB/shell privilege that apps call at runtime.

With Shizuku installed and running, and access granted in Theft Guard's setup
checklist ("Auto-enable data & location"), the alarm turns **mobile data, Wi-Fi
and location ON** automatically the moment the "Stolen" SMS arrives, so the phone
stays reachable.

The app talks to Shizuku through a tiny service (`UserService`) that exposes only
three fixed switches — enable mobile data, Wi-Fi, location — and takes no arbitrary
commands, so it isn't a general remote shell.

Caveat: on a non-rooted phone, Shizuku must be restarted after each reboot, so the
auto-enable won't fire again until it's running. The SMS alarm and location-by-SMS
don't depend on Shizuku and keep working either way.

## Fast setup over USB (for tech users)

If you have USB debugging, you can grant every permission in one command instead
of tapping through Settings - including the "Allow restricted settings" gate and
the "Allow all the time" location prompt.

1. On the phone: **Settings → About phone**, tap **Build number** 7 times, then
   **Settings → Developer options → USB debugging** (on).
2. Install the app, connect USB, accept the "Allow USB debugging?" prompt.
3. Run [`tools/adb-setup.sh`](tools/adb-setup.sh) (macOS/Linux) or
   [`tools/adb-setup.bat`](tools/adb-setup.bat) (Windows). It needs Android
   `platform-tools` (`adb`) installed.

You still set a screen lock yourself and keep Location turned on.

### What USB / ADB can and can't do

- **Can**: grant SMS, location (including background "all the time"), notifications,
  full-screen alarm, battery-optimisation exemption, device admin and the volume-lock
  service - the ADB shell is allowed to grant these, which is why the script skips the
  manual Settings hunt and the restricted-settings gate.
- **Can (one-off)**: turn mobile data on *at that moment* with
  `adb shell svc data enable` - useful for testing, but it's a manual command from a
  computer, not something that happens automatically when the phone is stolen.
- **Can't**: let the **app itself** turn mobile data on when the "Stolen" SMS arrives.
  That needs `MODIFY_PHONE_STATE`, a privileged/signature permission that `adb pm grant`
  cannot give a normal app - only the phone maker or carrier can. So the location SMS
  still goes over plain text (no data needed), and mobile data stays a manual toggle.

## Build

Requirements: Android Studio (or the Android SDK with platform 35) and JDK 17+.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Limitations

- A thief can still power the phone off, or remove the SIM before the SMS arrives. The alarm comes back if the phone is powered on again before being unlocked.
- Android doesn't let normal apps switch **mobile data** or **location** on, so the location SMS needs location to be on already.
- Some manufacturers (e.g. Xiaomi, Huawei, Oppo) add their own "autostart" or battery restrictions. Allow autostart for Theft Guard on those phones.
- Google Play restricts apps that use `RECEIVE_SMS` and accessibility services. The app is meant to be sideloaded for personal use.
