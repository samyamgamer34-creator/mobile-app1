package com.theftguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
 * While the alarm is sounding, texts the phone's GPS location to the owner's
 * trusted numbers: once immediately (last known position), again as soon as a
 * fresh fix arrives, and then every [UPDATE_INTERVAL_MS].
 *
 * Plain SMS is used so it works without mobile data.
 */
class LocationReporter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var activeListener: LocationListener? = null
    private var running = false

    private val periodicUpdate = object : Runnable {
        override fun run() {
            requestFreshFix()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun start() {
        if (AlarmState.trustedNumbers(context).isEmpty()) {
            Log.w(TAG, "No trusted numbers saved; not sending location SMS")
            return
        }
        running = true
        // Send the last known position right away, then a fresh fix (the first periodic update runs now).
        lastKnownLocation()?.let { send(it, fresh = false) }
        handler.post(periodicUpdate)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopListening()
    }

    /** Tells the trusted numbers that the phone was unlocked. */
    fun sendStopped() {
        if (AlarmState.trustedNumbers(context).isEmpty()) return
        sendSms(format(R.string.sms_stopped, time(System.currentTimeMillis()), batteryPercent()))
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

    /** Listens on every enabled provider and sends the best fix within [FIX_TIMEOUT_MS]. */
    @SuppressLint("MissingPermission")
    private fun requestFreshFix() {
        if (!running) return
        if (!hasLocationPermission() || !isLocationEnabled()) {
            send(null, fresh = true)
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

    // ---------------------------------------------------------------- SMS

    private fun send(location: Location?, fresh: Boolean) {
        if (!running) return
        val battery = batteryPercent()
        val text = when {
            location != null -> format(
                if (fresh) R.string.sms_location else R.string.sms_location_last_known,
                location.latitude, location.longitude,
                location.accuracy.toInt(), time(location.time), battery
            )
            !hasLocationPermission() -> format(R.string.sms_no_permission, battery)
            else -> format(R.string.sms_location_off, battery)
        }
        sendSms(text)
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

    /** Always format with Locale.US so coordinates use '.' and ASCII digits and the map link works. */
    private fun format(resId: Int, vararg args: Any): String =
        String.format(Locale.US, context.getString(resId), *args)

    private fun batteryPercent(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

    companion object {
        private const val TAG = "LocationReporter"
        private const val UPDATE_INTERVAL_MS = 5 * 60_000L
        private const val FIX_TIMEOUT_MS = 60_000L
        private const val GOOD_ACCURACY_M = 25f
    }
}
