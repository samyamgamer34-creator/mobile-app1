package com.theftguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sends the phone's location exactly ONCE per alarm: it waits briefly for a fresh
 * GPS fix (falling back to the last known position) and then sends a single
 * message - either by SMS to the trusted numbers, or by email if the user chose
 * that and Shizuku has turned data/Wi-Fi on. To get another location, the owner
 * sends "Stolen" again.
 */
class LocationReporter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var activeListener: LocationListener? = null
    private var running = false
    private var sent = false

    fun start() {
        if (!hasAnyRecipient()) {
            Log.w(TAG, "No recipient configured; not sending location")
            return
        }
        running = true
        requestSingleFix()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopListening()
    }

    private fun hasAnyRecipient(): Boolean = when (AlarmState.deliveryMethod(context)) {
        AlarmState.DELIVERY_EMAIL -> AlarmState.mailConfig(context).isComplete
        else -> AlarmState.trustedNumbers(context).isNotEmpty()
    }

    // ---------------------------------------------------------------- location

    private fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            providers().isNotEmpty()
        }

    private fun providers(): List<String> {
        val candidates = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) candidates += LocationManager.FUSED_PROVIDER
        return candidates.filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return providers()
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Listens for one good fix within [FIX_TIMEOUT_MS], then sends a single message. */
    @SuppressLint("MissingPermission")
    private fun requestSingleFix() {
        if (!hasLocationPermission() || !isLocationEnabled()) {
            send(location = null, fresh = false)
            return
        }
        stopListening()

        var best: Location? = null
        val finish = Runnable {
            stopListening()
            send(best ?: lastKnownLocation(), fresh = best != null)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (best == null || location.accuracy < best!!.accuracy) best = location
                if (location.accuracy <= GOOD_ACCURACY_M) {
                    handler.removeCallbacks(finish)
                    finish.run()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = listener
        for (provider in providers()) {
            runCatching { locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "requestLocationUpdates($provider) failed", it) }
        }
        handler.postDelayed(finish, FIX_TIMEOUT_MS)
    }

    private fun stopListening() {
        activeListener?.let { runCatching { locationManager.removeUpdates(it) } }
        activeListener = null
    }

    // ---------------------------------------------------------------- send once

    private fun send(location: Location?, fresh: Boolean) {
        if (sent) return
        sent = true
        val battery = batteryPercent()
        val body = when {
            location != null -> format(
                if (fresh) R.string.msg_location else R.string.msg_location_last_known,
                location.latitude, location.longitude,
                location.accuracy.toInt(), time(location.time), battery,
            )
            !hasLocationPermission() -> format(R.string.msg_no_permission, battery)
            else -> format(R.string.msg_location_off, battery)
        }
        when (AlarmState.deliveryMethod(context)) {
            AlarmState.DELIVERY_EMAIL -> sendEmail(body)
            else -> sendSms(body)
        }
    }

    private fun sendSms(text: String) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS not granted")
            return
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        for (number in AlarmState.trustedNumbers(context)) {
            runCatching { sms.sendMultipartTextMessage(number, null, parts, null, null) }
                .onFailure { Log.w(TAG, "Failed to text $number", it) }
        }
    }

    /** Sends the email on a background thread, waiting for the network Shizuku brought up. */
    private fun sendEmail(body: String) {
        val config = AlarmState.mailConfig(context)
        if (!config.isComplete) {
            Log.w(TAG, "Email delivery chosen but mail settings are incomplete")
            return
        }
        val subject = context.getString(R.string.mail_subject)
        Thread({
            if (!waitForNetwork()) Log.w(TAG, "No network after waiting; trying to send anyway")
            val ok = EmailSender.send(config, subject, body)
            Log.i(TAG, if (ok) "Location email sent" else "Location email failed")
        }, "theft-email").start()
    }

    /** Shizuku has just enabled data/Wi-Fi; give the connection up to ~45 s to come up. */
    private fun waitForNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        repeat(NETWORK_WAIT_TRIES) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
            try {
                Thread.sleep(NETWORK_WAIT_STEP_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** Always format with Locale.US so coordinates use '.' and ASCII digits and the map link works. */
    private fun format(resId: Int, vararg args: Any): String =
        String.format(Locale.US, context.getString(resId), *args)

    private fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

    companion object {
        private const val TAG = "LocationReporter"
        private const val FIX_TIMEOUT_MS = 60_000L
        private const val GOOD_ACCURACY_M = 25f
        private const val NETWORK_WAIT_TRIES = 15
        private const val NETWORK_WAIT_STEP_MS = 3_000L
    }
}
