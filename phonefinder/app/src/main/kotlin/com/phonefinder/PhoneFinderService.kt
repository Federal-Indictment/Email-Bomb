package com.phonefinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the voice detection engine running at all times,
 * including when the screen is off. Returns START_STICKY so the OS restarts it
 * if killed due to memory pressure.
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground() within 5-10 s of onCreate() or the OS will crash the app
        startForeground(NOTIFICATION_ID, buildNotification())

        // PARTIAL_WAKE_LOCK keeps the CPU running when the screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneFinder::ListeningWakeLock"
        ).apply { acquire(10 * 60 * 60 * 1000L) } // 10-hour max, re-acquired on restart

        alarmController = AlarmController(this)
        audioEngine = AudioListenerEngine(
            context = this,
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
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while the voice listener is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
