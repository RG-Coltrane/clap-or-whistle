package com.rgcoltrane.clapwhistle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps listening in the background (with a notification) and sets off the alarm. */
class ListenerService : Service() {

    companion object {
        const val ACTION_START = "com.rgcoltrane.clapwhistle.START"
        const val ACTION_STOP_ALARM = "com.rgcoltrane.clapwhistle.STOP_ALARM"
        const val ACTION_QUIT = "com.rgcoltrane.clapwhistle.QUIT"
        private const val CHANNEL_ID = "listening"
        private const val NOTIF_ID = 1
        private const val LISTENING_TEXT = "Listening… clap twice or whistle"

        // Tiny shared "scoreboard" the screen reads from.
        @Volatile var listening = false
        @Volatile var ringing = false
        @Volatile var status = "Off"
        @Volatile var level = 0f
        @Volatile var sensitivity = 6
        @Volatile var onChange: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var detector: SoundDetector? = null
    private lateinit var alarm: AlarmController

    override fun onCreate() {
        super.onCreate()
        alarm = AlarmController(applicationContext)
        alarm.onStopped = {
            ringing = false
            status = LISTENING_TEXT
            refreshNotification()
            changed()
            // Wait a second so the alarm's own tail end doesn't set it off again.
            main.postDelayed({ detector?.paused = false }, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALARM -> alarm.stop()
            ACTION_QUIT -> shutdown()
            else -> startListening()
        }
        return START_NOT_STICKY
    }

    private fun startListening() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        status = LISTENING_TEXT
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            status = "Couldn't start: ${e.message}"
            listening = false
            changed()
            stopSelf()
            return
        }

        if (detector == null) {
            detector = SoundDetector(
                sensitivity = { sensitivity },
                onLevel = { level = it },
                onHeard = { kind -> main.post { handleHeard(kind) } }
            ).also { it.start() }
        }
        listening = true
        changed()
    }

    private fun handleHeard(kind: String) {
        if (alarm.ringing) return
        detector?.paused = true
        ringing = true
        status = "Heard a $kind — ringing!"
        alarm.ring()
        refreshNotification()
        changed()
    }

    private fun cleanup() {
        alarm.onStopped = null
        alarm.stop()
        detector?.stop()
        detector = null
        listening = false
        ringing = false
        level = 0f
        status = "Off"
        changed()
    }

    private fun shutdown() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAlarm = PendingIntent.getService(
            this, 1,
            Intent(this, ListenerService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE
        )
        val quit = PendingIntent.getService(
            this, 2,
            Intent(this, ListenerService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Clap or Whistle")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stop alarm",
                    stopAlarm
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete),
                    "Turn off",
                    quit
                ).build()
            )
            .build()
    }
}
