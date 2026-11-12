package com.theftguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Watches incoming SMS and reacts to two exact keywords:
 *  - [AlarmState.TRIGGER_TEXT] ("Stolen") -> the loud alarm.
 *  - [AlarmState.SILENT_TRIGGER_TEXT] ("silent_stolen") -> silent location run.
 * Any other text, including different capitalisation or extra words, is ignored.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AlarmState.isArmed(context)) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // A long SMS arrives in several parts; join them per sender before comparing.
        val bodies = parts
            .filterNotNull()
            .groupBy { it.displayOriginatingAddress.orEmpty() }
            .mapValues { (_, msgs) -> msgs.joinToString("") { it.displayMessageBody.orEmpty() } }
            .values

        when {
            // Loud alarm wins if both somehow arrive together.
            bodies.any(::isTrigger) -> {
                Log.w(TAG, "Trigger SMS received, starting loud theft alarm")
                AlarmService.start(context, silent = false)
            }
            bodies.any(::isSilentTrigger) -> {
                Log.w(TAG, "Silent trigger SMS received, starting silent location run")
                AlarmService.start(context, silent = true)
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * Only the exact words count. Leading/trailing whitespace is ignored
         * because some phones and carriers append a newline or space.
         */
        fun isTrigger(body: String): Boolean = body.trim() == AlarmState.TRIGGER_TEXT

        fun isSilentTrigger(body: String): Boolean = body.trim() == AlarmState.SILENT_TRIGGER_TEXT
    }
}
