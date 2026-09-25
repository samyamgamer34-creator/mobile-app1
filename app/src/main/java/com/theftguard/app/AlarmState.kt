package com.theftguard.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent state shared by all components.
 *
 * Stored in device-protected storage so it can be read before the phone is
 * unlocked after a reboot (Direct Boot); otherwise a thief could silence the
 * alarm simply by restarting the phone.
 */
object AlarmState {
    /** The exact SMS text that triggers the alarm. */
    const val TRIGGER_TEXT = "Stolen"

    private const val PREFS = "theft_guard"
    private const val KEY_ARMED = "armed"
    private const val KEY_ACTIVE = "alarm_active"
    private const val KEY_TRUSTED = "trusted_numbers"

    /** In-memory mirror of [KEY_ACTIVE] for hot paths such as key events. */
    @Volatile
    var activeInMemory: Boolean = false
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isArmed(context: Context): Boolean = prefs(context).getBoolean(KEY_ARMED, true)

    fun setArmed(context: Context, armed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    /** Phone numbers that receive the location SMS while the alarm is sounding. */
    fun trustedNumbers(context: Context): List<String> =
        prefs(context).getString(KEY_TRUSTED, "").orEmpty()
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setTrustedNumbers(context: Context, numbers: String) {
        prefs(context).edit().putString(KEY_TRUSTED, numbers.trim()).apply()
    }

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false).also { activeInMemory = it }

    fun setActive(context: Context, active: Boolean) {
        activeInMemory = active
        // commit() so the flag survives even if the process is killed right away.
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).commit()
    }
}
