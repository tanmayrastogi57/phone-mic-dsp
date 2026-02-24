package com.phonemicdsp.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioStreamingService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServiceSafely()
            ACTION_START,
            null -> startForeground(NOTIFICATION_ID, buildStreamingNotification())
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServiceSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.streaming_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.streaming_channel_description)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    private fun buildStreamingNotification(): Notification {
        val stopIntent = Intent(this, AudioStreamingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_streaming_action),
                stopPendingIntent
            )
            .build()
    }

    companion object {
        const val ACTION_START = "com.phonemicdsp.sender.action.START_STREAMING"
        const val ACTION_STOP = "com.phonemicdsp.sender.action.STOP_STREAMING"

        private const val CHANNEL_ID = "audio_streaming_channel"
        private const val NOTIFICATION_ID = 2201
        private const val REQUEST_CODE_STOP = 2202

        fun startIntent(context: Context): Intent = Intent(context, AudioStreamingService::class.java).apply {
            action = ACTION_START
        }

        fun stopIntent(context: Context): Intent = Intent(context, AudioStreamingService::class.java).apply {
            action = ACTION_STOP
        }
    }
}
