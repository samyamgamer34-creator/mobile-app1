package com.theftguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays "This phone is stolen" followed by a siren, in a loop, at maximum
 * volume. Any attempt to lower the volume is immediately reverted. The alarm
 * only stops once the phone is unlocked (ACTION_USER_PRESENT).
 */
class AlarmService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    @Volatile
    private var running = false

    /** True when this run is the silent "secret mission" (no sound, no screen, no lock). */
    private var silent = false
    private var soundThread: Thread? = null
    private var tts: TextToSpeech? = null
    private var locationReporter: LocationReporter? = null

    @Volatile
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val originalVolumes = mutableMapOf<Int, Int>()
    private var originalInterruptionFilter: Int? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Silent "secret mission": send location quietly once, then stop. Not persisted
        // across reboots and never sounds or locks, so a thief sees nothing unusual.
        if (intent?.getBooleanExtra(EXTRA_SILENT, false) == true) {
            if (!running) startSilent()
            return START_NOT_STICKY
        }
        // Restarted by the system after being killed: only continue if the alarm is still owed.
        if (!AlarmState.isActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startAlarm()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarmResources()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun startAlarm() {
        running = true

        startForegroundWithNotification()
        // If Shizuku is set up, turn mobile data / Wi-Fi / location on so the phone
        // stays reachable. No-op when Shizuku isn't available.
        ShizukuManager.enableConnectivity(this)
        lockDeviceIfAdmin()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard:alarm")
            .apply { acquire() }

        overrideDoNotDisturb()
        rememberOriginalVolumes()
        startVolumeEnforcement()
        registerUnlockReceiver()
        initTextToSpeech()
        locationReporter = LocationReporter(this).apply { start() }

        soundThread = Thread(::soundLoop, "theft-alarm-sound").apply { start() }
    }

    /**
     * Silent mode: no sound, no alarm screen, no lock. Turn connectivity on via
     * Shizuku, silence the phone so nothing chirps, send the location once, then stop.
     */
    private fun startSilent() {
        running = true
        silent = true

        startSilentForeground()
        ShizukuManager.enableConnectivity(this)
        silenceEverything()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard:silent")
            .apply { acquire(SILENT_MAX_MS) }

        locationReporter = LocationReporter(this) { mainHandler.post { finishSilent() } }.apply { start() }

        // Safety net in case the location never resolves (e.g. no signal).
        mainHandler.postDelayed({ finishSilent() }, SILENT_MAX_MS)
    }

    private fun finishSilent() {
        if (!silent) return
        Log.i(TAG, "Silent location run finished")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Switches the phone to total silence so no incoming alert tips off a thief. */
    private fun silenceEverything() {
        runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
        }
    }

    /** Called when the phone has been unlocked. */
    private fun stopAlarm() {
        Log.i(TAG, "Phone unlocked, stopping theft alarm")
        AlarmState.setActive(this, false)
        sendBroadcast(Intent(ACTION_ALARM_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAlarmResources() {
        if (!running) return
        running = false

        mainHandler.removeCallbacksAndMessages(null)
        locationReporter?.stop()
        locationReporter = null
        runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { unregisterReceiver(unlockReceiver) }

        tts?.stop()
        tts?.shutdown()
        tts = null

        soundThread?.interrupt()
        soundThread = null

        restoreOriginalVolumes()
        restoreDoNotDisturb()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_description)
                setSound(null, null) // the service plays its own sound
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        val fullScreen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(getString(R.string.alarm_subtitle))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * A deliberately boring, low-priority notification for silent mode. Android
     * requires a foreground service to show something; this makes it look like a
     * routine background sync so a thief doesn't get suspicious. It plays no sound.
     */
    private fun startSilentForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(SILENT_CHANNEL_ID, getString(R.string.silent_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        val notification = Notification.Builder(this, SILENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.silent_notification_title))
            .setContentText(getString(R.string.silent_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- locking

    /**
     * If the app is a device admin, lock the screen right away so that the
     * alarm can only be silenced by someone who knows the PIN/pattern/fingerprint.
     */
    private fun lockDeviceIfAdmin() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            runCatching { dpm.lockNow() }.onFailure { Log.w(TAG, "lockNow failed", it) }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) stopAlarm()
        }
    }

    private fun registerUnlockReceiver() {
        // ACTION_USER_PRESENT is sent when the keyguard is dismissed, i.e. the phone was unlocked.
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    // ---------------------------------------------------------------- volume lock

    private val enforcedStreams = intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)

    private fun rememberOriginalVolumes() {
        for (stream in enforcedStreams) originalVolumes[stream] = audioManager.getStreamVolume(stream)
    }

    private fun restoreOriginalVolumes() {
        for ((stream, volume) in originalVolumes) {
            runCatching { audioManager.setStreamVolume(stream, volume, 0) }
        }
        originalVolumes.clear()
    }

    private fun enforceMaxVolume() {
        if (!running) return
        for (stream in enforcedStreams) {
            runCatching {
                if (audioManager.isStreamMute(stream)) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                }
                val max = audioManager.getStreamMaxVolume(stream)
                if (audioManager.getStreamVolume(stream) < max) {
                    audioManager.setStreamVolume(stream, max, 0)
                }
            }
        }
    }

    /** Fires whenever a system volume setting changes; snaps it straight back to max. */
    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = enforceMaxVolume()
    }

    /** Backup in case a volume change doesn't produce a settings notification. */
    private val volumePoller = object : Runnable {
        override fun run() {
            enforceMaxVolume()
            mainHandler.postDelayed(this, VOLUME_POLL_MS)
        }
    }

    private fun startVolumeEnforcement() {
        enforceMaxVolume()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        mainHandler.post(volumePoller)
    }

    /** Alarms normally bypass DND, but if we have policy access make sure nothing is filtered. */
    private fun overrideDoNotDisturb() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        originalInterruptionFilter = nm.currentInterruptionFilter
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
    }

    private fun restoreDoNotDisturb() {
        val filter = originalInterruptionFilter ?: return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(filter) }
        originalInterruptionFilter = null
    }

    // ---------------------------------------------------------------- sound

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun initTextToSpeech() {
        // TTS may be unavailable (e.g. before first unlock after reboot); the siren still plays.
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.US
                engine.setAudioAttributes(alarmAttributes)
                engine.setSpeechRate(0.9f)
                ttsReady = true
            }
        }
    }

    /** Alternates the spoken warning with a siren until stopped. */
    private fun soundLoop() {
        val siren = buildSiren()
        val track = AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            )
            .build()

        try {
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            val chunk = SAMPLE_RATE / 4 // write 250 ms at a time so we can stop quickly
            while (running) {
                speakWarning()
                var offset = 0
                while (running && offset < siren.size) {
                    val n = minOf(chunk, siren.size - offset)
                    track.write(siren, offset, n)
                    offset += n
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sound loop ended", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Speaks the warning and blocks until it is finished (or times out). */
    private fun speakWarning() {
        val engine = tts
        if (!ttsReady || engine == null) return
        val done = CountDownLatch(1)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done.countDown()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done.countDown()
        })
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (engine.speak(getString(R.string.spoken_warning), TextToSpeech.QUEUE_FLUSH, params, "warning") ==
            TextToSpeech.SUCCESS
        ) {
            try {
                done.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // stopping
            }
        }
    }

    /** ~3 s of a rising/falling "wail" siren as 16-bit PCM. */
    private fun buildSiren(): ShortArray {
        val samples = SAMPLE_RATE * 3
        val out = ShortArray(samples)
        var phase = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            // Sweep 700 Hz -> 1700 Hz -> 700 Hz every 1.5 s.
            val sweep = (t % 1.5) / 1.5
            val tri = if (sweep < 0.5) sweep * 2 else (1 - sweep) * 2
            val freq = 700 + 1000 * tri
            phase += 2 * PI * freq / SAMPLE_RATE
            // Overdriven sine: louder and harsher than a pure tone.
            val v = (sin(phase) * 1.8).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE * 0.98).toInt().toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "theft_alarm"
        private const val SILENT_CHANNEL_ID = "theft_silent"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44_100
        private const val VOLUME_POLL_MS = 200L

        /** How long the silent run may stay up waiting for a fix / email before giving up. */
        private const val SILENT_MAX_MS = 150_000L

        private const val EXTRA_SILENT = "com.theftguard.app.EXTRA_SILENT"

        /** Sent (package-local) when the alarm stops so the alarm screen can close. */
        const val ACTION_ALARM_STOPPED = "com.theftguard.app.ALARM_STOPPED"

        /** Loud alarm (default) or the silent "secret mission" location run. */
        fun start(context: Context, silent: Boolean = false) {
            if (!silent) AlarmState.setActive(context, true)
            val intent = Intent(context, AlarmService::class.java).putExtra(EXTRA_SILENT, silent)
            context.startForegroundService(intent)
        }

        /** Used when we know the owner already unlocked the phone (e.g. after reboot). */
        fun cancel(context: Context) {
            AlarmState.setActive(context, false)
            context.stopService(Intent(context, AlarmService::class.java))
            context.sendBroadcast(Intent(ACTION_ALARM_STOPPED).setPackage(context.packageName))
        }
    }
}
