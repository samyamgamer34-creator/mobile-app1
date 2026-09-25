package com.theftguard.app

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service that swallows the hardware volume keys while
 * the alarm is sounding, even on the lock screen. Without it the app still
 * snaps the volume back to maximum instantly, but the keys are not blocked.
 */
class VolumeLockService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean =
        AlarmState.activeInMemory && AlarmActivity.isVolumeKey(event.keyCode)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Load the persisted flag into memory in case the alarm is already running.
        AlarmState.isActive(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
