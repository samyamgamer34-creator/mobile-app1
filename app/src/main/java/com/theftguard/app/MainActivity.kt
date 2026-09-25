package com.theftguard.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup screen: grants the permissions the alarm needs and arms/disarms protection. */
class MainActivity : Activity() {

    private lateinit var armedSwitch: Switch
    private lateinit var checklist: LinearLayout
    private lateinit var trustedNumbers: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.instructions).text =
            getString(R.string.instructions, AlarmState.TRIGGER_TEXT)

        armedSwitch = findViewById(R.id.armed_switch)
        armedSwitch.setOnCheckedChangeListener { _, checked -> AlarmState.setArmed(this, checked) }

        checklist = findViewById(R.id.checklist)

        trustedNumbers = findViewById(R.id.trusted_numbers)
        trustedNumbers.setText(AlarmState.trustedNumbers(this).joinToString(", "))
        findViewById<Button>(R.id.trusted_save).setOnClickListener {
            AlarmState.setTrustedNumbers(this, trustedNumbers.text.toString())
            Toast.makeText(this, R.string.trusted_saved, Toast.LENGTH_SHORT).show()
            renderChecklist()
        }

        findViewById<Button>(R.id.test_button).setOnClickListener { confirmTest() }
    }

    override fun onResume() {
        super.onResume()
        armedSwitch.isChecked = AlarmState.isArmed(this)
        renderChecklist()
    }

    private fun renderChecklist() {
        checklist.removeAllViews()

        addItem(
            title = getString(R.string.item_lock_title),
            detail = getString(R.string.item_lock_detail),
            done = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure,
            required = true,
        ) { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }

        addItem(
            title = getString(R.string.item_sms_title),
            detail = getString(R.string.item_sms_detail),
            done = missingRuntimePermissions().isEmpty(),
            required = true,
        ) { requestPermissions(missingRuntimePermissions().toTypedArray(), REQUEST_PERMISSIONS) }

        addItem(
            title = getString(R.string.item_trusted_title),
            detail = getString(R.string.item_trusted_detail),
            done = AlarmState.trustedNumbers(this).isNotEmpty(),
            required = true,
        ) { trustedNumbers.requestFocus() }

        addItem(
            title = getString(R.string.item_location_title),
            detail = getString(R.string.item_location_detail),
            done = hasForegroundLocation(),
            required = true,
        ) { requestForegroundLocation() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addItem(
                title = getString(R.string.item_bg_location_title),
                detail = getString(R.string.item_bg_location_detail),
                done = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
                required = true,
            ) {
                // Android only offers "Allow all the time" after the normal location permission is granted.
                if (hasForegroundLocation()) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_PERMISSIONS)
                } else {
                    requestForegroundLocation()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addItem(
                title = getString(R.string.item_location_on_title),
                detail = getString(R.string.item_location_on_detail),
                done = (getSystemService(LOCATION_SERVICE) as LocationManager).isLocationEnabled,
                required = true,
            ) { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            addItem(
                title = getString(R.string.item_fullscreen_title),
                detail = getString(R.string.item_fullscreen_detail),
                done = nm.canUseFullScreenIntent(),
                required = true,
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
        }

        addItem(
            title = getString(R.string.item_battery_title),
            detail = getString(R.string.item_battery_detail),
            done = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName),
            required = true,
        ) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        val admin = ComponentName(this, AdminReceiver::class.java)
        addItem(
            title = getString(R.string.item_admin_title),
            detail = getString(R.string.item_admin_detail),
            done = (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin),
            required = false,
        ) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
            )
        }

        addItem(
            title = getString(R.string.item_volume_keys_title),
            detail = getString(R.string.item_volume_keys_detail),
            done = isVolumeLockServiceEnabled(),
            required = false,
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        addItem(
            title = getString(R.string.item_dnd_title),
            detail = getString(R.string.item_dnd_detail),
            done = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted,
            required = false,
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

    private fun addItem(title: String, detail: String, done: Boolean, required: Boolean, onFix: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_check, checklist, false)
        val status = when {
            done -> "✅"
            required -> "❌"
            else -> "⚠️"
        }
        row.findViewById<TextView>(R.id.item_title).text =
            if (required) "$status  $title" else "$status  $title ${getString(R.string.recommended)}"
        row.findViewById<TextView>(R.id.item_detail).text = detail
        row.findViewById<Button>(R.id.item_button).apply {
            isEnabled = !done
            text = getString(if (done) R.string.done else R.string.fix)
            setOnClickListener { onFix() }
        }
        checklist.addView(row)
    }

    private fun missingRuntimePermissions(): List<String> {
        val needed = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed += Manifest.permission.POST_NOTIFICATIONS
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun hasForegroundLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestForegroundLocation() {
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_PERMISSIONS
        )
    }

    private fun isVolumeLockServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == VolumeLockService::class.java.name
        }
    }

    private fun confirmTest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.test_title)
            .setMessage(R.string.test_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.test_start) { _, _ -> AlarmService.start(this) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) renderChecklist()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }
}
