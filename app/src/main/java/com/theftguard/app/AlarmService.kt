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
    private var soundThread: Thread? = null
    private var tts: TextToSpeech? = null

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
        lockDeviceIfAdmin()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard:alarm")
            .apply { acquire() }

        overrideDoNotDisturb()
        rememberOriginalVolumes()
        startVolumeEnforcement()
        registerUnlockReceiver()
        initTextToSpeech()

        soundThread = Thread(::soundLoop, "theft-alarm-sound").apply { start() }
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
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44_100
        private const val VOLUME_POLL_MS = 200L

        /** Sent (package-local) when the alarm stops so the alarm screen can close. */
        const val ACTION_ALARM_STOPPED = "com.theftguard.app.ALARM_STOPPED"

        fun start(context: Context) {
            AlarmState.setActive(context, true)
            context.startForegroundService(Intent(context, AlarmService::class.java))
        }

        /** Used when we know the owner already unlocked the phone (e.g. after reboot). */
        fun cancel(context: Context) {
            AlarmState.setActive(context, false)
            context.stopService(Intent(context, AlarmService::class.java))
            context.sendBroadcast(Intent(ACTION_ALARM_STOPPED).setPackage(context.packageName))
        }
    }
}
