# Theft Guard

An Android anti-theft app. When the phone receives an SMS whose text is exactly

```
Stolen
```

it announces **"This phone is stolen!"** with a siren, at full volume, over and over.
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
| Do Not Disturb | Alarm-stream audio normally bypasses Do Not Disturb. If the app has DND access, it also switches DND off for the duration and then restores it. |

When the alarm stops, the original volume levels and DND mode are restored.

## Setup on the phone

Open **Theft Guard** and complete the checklist:

1. **Secure screen lock** (required). Without a PIN or pattern, anyone can "unlock" the phone and stop the alarm.
2. **SMS and notification permission** (required).
3. **Full-screen alarm** (required, Android 14+).
4. **Ignore battery optimisation** (required). This stops Android from delaying the alarm.
5. **Lock screen on alarm** (recommended). This is the device admin.
6. **Block volume buttons** (recommended). Turn on *Theft Guard volume lock* in Accessibility settings.
   On Android 13+, a sideloaded APK may first need *App info → ⋮ → Allow restricted settings*.
7. **Override Do Not Disturb** (recommended).

Use **Test alarm** to try it. To stop the test, unlock the phone. If device admin is off, press the power button to lock the phone, then unlock it.

## Build

Requirements: Android Studio (or the Android SDK with platform 35) and JDK 17+.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Limitations

- A thief can still power the phone off, or remove the SIM before the SMS arrives. The alarm comes back if the phone is powered on again before being unlocked.
- Some manufacturers (e.g. Xiaomi, Huawei, Oppo) add their own "autostart" or battery restrictions. Allow autostart for Theft Guard on those phones.
- Google Play restricts apps that use `RECEIVE_SMS` and accessibility services. The app is meant to be sideloaded for personal use.
