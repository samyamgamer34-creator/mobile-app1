package com.theftguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Watches incoming SMS and starts the alarm when a message's text is exactly
 * [AlarmState.TRIGGER_TEXT] ("Stolen"). Any other text, including different
 * capitalisation ("stolen", "STOLEN") or extra words ("Stolen phone"), is ignored.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AlarmState.isArmed(context)) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // A long SMS arrives in several parts; join them per sender before comparing.
        val bodiesBySender = parts
            .filterNotNull()
            .groupBy { it.displayOriginatingAddress.orEmpty() }
            .mapValues { (_, msgs) -> msgs.joinToString("") { it.displayMessageBody.orEmpty() } }

        if (bodiesBySender.values.any(::isTrigger)) {
            Log.w(TAG, "Trigger SMS received, starting theft alarm")
            AlarmService.start(context)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * Only the exact word "Stolen" counts. Leading/trailing whitespace is
         * ignored because some phones and carriers append a newline or space.
         */
        fun isTrigger(body: String): Boolean = body.trim() == AlarmState.TRIGGER_TEXT
    }
}
