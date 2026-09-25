package com.theftguard.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Full-screen "THIS PHONE IS STOLEN" screen shown over the lock screen.
 * It has no stop button: the only way to silence the alarm is to unlock the phone.
 */
class AlarmActivity : Activity() {

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        val filter = IntentFilter(AlarmService.ACTION_ALARM_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stoppedReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AlarmState.isActive(this)) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(stoppedReceiver)
        super.onDestroy()
    }

    /** Swallow the volume keys while this screen is showing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyUp(keyCode, event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore back: the screen goes away only when the phone is unlocked.
    }

    companion object {
        fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }
}
