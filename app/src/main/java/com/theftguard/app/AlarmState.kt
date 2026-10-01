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
    private const val KEY_DELIVERY = "delivery_method"
    private const val KEY_SMTP_HOST = "smtp_host"
    private const val KEY_SMTP_PORT = "smtp_port"
    private const val KEY_MAIL_USER = "mail_user"
    private const val KEY_MAIL_PASS = "mail_pass"
    private const val KEY_MAIL_TO = "mail_to"

    /** Where the single location message goes. Email needs Shizuku (for data/Wi-Fi). */
    const val DELIVERY_SMS = "sms"
    const val DELIVERY_EMAIL = "email"

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

    fun deliveryMethod(context: Context): String =
        prefs(context).getString(KEY_DELIVERY, DELIVERY_SMS) ?: DELIVERY_SMS

    fun setDeliveryMethod(context: Context, method: String) {
        prefs(context).edit().putString(KEY_DELIVERY, method).apply()
    }

    /** SMTP settings used when the delivery method is email. */
    data class MailConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val recipient: String,
    ) {
        val isComplete: Boolean
            get() = host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty() && recipient.isNotEmpty()
    }

    fun mailConfig(context: Context): MailConfig {
        val p = prefs(context)
        return MailConfig(
            host = p.getString(KEY_SMTP_HOST, "").orEmpty().trim(),
            port = p.getString(KEY_SMTP_PORT, "587").orEmpty().trim().toIntOrNull() ?: 587,
            user = p.getString(KEY_MAIL_USER, "").orEmpty().trim(),
            password = p.getString(KEY_MAIL_PASS, "").orEmpty(),
            recipient = p.getString(KEY_MAIL_TO, "").orEmpty().trim(),
        )
    }

    fun setMailConfig(context: Context, host: String, port: String, user: String, password: String, recipient: String) {
        prefs(context).edit()
            .putString(KEY_SMTP_HOST, host.trim())
            .putString(KEY_SMTP_PORT, port.trim())
            .putString(KEY_MAIL_USER, user.trim())
            .putString(KEY_MAIL_PASS, password)
            .putString(KEY_MAIL_TO, recipient.trim())
            .apply()
    }

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false).also { activeInMemory = it }

    fun setActive(context: Context, active: Boolean) {
        activeInMemory = active
        // commit() so the flag survives even if the process is killed right away.
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).commit()
    }
}
