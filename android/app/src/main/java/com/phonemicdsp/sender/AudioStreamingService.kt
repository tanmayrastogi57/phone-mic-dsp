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
    private var isStreaming = false
    private var destinationIp: String = ""
    private var destinationPort: Int = DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServiceSafely()
            ACTION_QUERY_STATUS -> sendStatusUpdate()
            ACTION_START,
            null -> {
                destinationIp = intent?.getStringExtra(EXTRA_DESTINATION_IP).orEmpty()
                destinationPort = intent?.getIntExtra(EXTRA_DESTINATION_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                isStreaming = true
                startForeground(NOTIFICATION_ID, buildStreamingNotification())
                sendStatusUpdate()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServiceSafely() {
        isStreaming = false
        sendStatusUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatusUpdate() {
        val statusIntent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_STREAMING, isStreaming)
            putExtra(EXTRA_STATUS_PACKETS_PER_SECOND, 0)
            putExtra(EXTRA_STATUS_DSP_SUMMARY, getString(R.string.dsp_status_pending_summary))
        }
        sendBroadcast(statusIntent)
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
            .setContentText(getString(R.string.streaming_notification_body, destinationIp, destinationPort))
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
        const val ACTION_QUERY_STATUS = "com.phonemicdsp.sender.action.QUERY_STATUS"
        const val ACTION_STATUS_UPDATE = "com.phonemicdsp.sender.action.STATUS_UPDATE"

        const val EXTRA_DESTINATION_IP = "extra_destination_ip"
        const val EXTRA_DESTINATION_PORT = "extra_destination_port"
        const val EXTRA_STATUS_STREAMING = "extra_status_streaming"
        const val EXTRA_STATUS_PACKETS_PER_SECOND = "extra_status_packets_per_second"
        const val EXTRA_STATUS_DSP_SUMMARY = "extra_status_dsp_summary"

        private const val CHANNEL_ID = "audio_streaming_channel"
        private const val NOTIFICATION_ID = 2201
        private const val REQUEST_CODE_STOP = 2202
        private const val DEFAULT_PORT = 5555

        fun startIntent(context: Context, ip: String, port: Int): Intent =
            Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DESTINATION_IP, ip)
                putExtra(EXTRA_DESTINATION_PORT, port)
            }

        fun stopIntent(context: Context): Intent = Intent(context, AudioStreamingService::class.java).apply {
            action = ACTION_STOP
        }

        fun statusQueryIntent(context: Context): Intent = Intent(context, AudioStreamingService::class.java).apply {
            action = ACTION_QUERY_STATUS
        }
    }
}
