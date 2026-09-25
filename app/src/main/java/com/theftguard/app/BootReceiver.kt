package com.theftguard.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps the alarm going if the thief reboots the phone.
 *
 * LOCKED_BOOT_COMPLETED arrives before anyone has entered the PIN, so the alarm
 * is restarted. BOOT_COMPLETED only arrives after the PIN/pattern has been entered
 * for the first time, which means the owner unlocked the phone: the alarm is cancelled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!AlarmState.isActive(context)) return

        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> AlarmService.start(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguard.isDeviceSecure) {
                    AlarmService.cancel(context)
                } else {
                    // No PIN set, so no LOCKED_BOOT_COMPLETED phase; keep alarming until unlocked.
                    AlarmService.start(context)
                }
            }
        }
    }
}
