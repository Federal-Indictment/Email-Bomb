package com.phonefinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the voice detection engine running at all times,
 * including when the screen is off. Returns START_STICKY so the OS restarts it
 * if it is killed due to memory pressure.
 */
class PhoneFinderService : Service() {

    companion object {
        const val CHANNEL_ID = "phone_finder_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phonefinder.ACTION_STOP"
        const val ACTION_STOP_ALARM = "com.phonefinder.ACTION_STOP_ALARM"
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var audioEngine: AudioListenerEngine
    private lateinit var alarmController: AlarmController
    private lateinit var speechThread: HandlerThread

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground() within 5–10 s of onCreate() or the OS will ANR/crash
        startForeground(NOTIFICATION_ID, buildNotification())

        // PARTIAL_WAKE_LOCK keeps the CPU running when the screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneFinder::ListeningWakeLock"
        ).apply { acquire(Long.MAX_VALUE) }

        // SpeechRecognizer must run on a thread that has a Looper
        speechThread = HandlerThread("SpeechRecognizerThread").apply { start() }

        alarmController = AlarmController(this)
        audioEngine = AudioListenerEngine(
            context = this,
            speechLooper = speechThread.looper,
            onPhraseDetected = ::onTriggerDetected,
            onEngineError = ::restartEngine
        )
        audioEngine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALARM -> {
                alarmController.stopAlarm()
                return START_STICKY
            }
        }
        // START_STICKY: if killed, Android restarts the service with a null Intent
        return START_STICKY
    }

    private fun onTriggerDetected() {
        alarmController.soundAlarm()
        // Broadcast so MainActivity can show the "Stop Alarm" button
        sendBroadcast(Intent("com.phonefinder.ALARM_FIRING"))
    }

    private fun restartEngine() {
        audioEngine.stop()
        audioEngine.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.stop()
        alarmController.stopAlarm()
        speechThread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PhoneFinderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone Finder Service",
            NotificationManager.IMPORTANCE_LOW  // LOW = shows in bar, no sound
        ).apply {
            description = "Persistent notification while the voice listener is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
