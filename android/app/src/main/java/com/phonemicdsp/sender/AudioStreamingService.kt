package com.phonemicdsp.sender

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamingService : Service() {
    private var isStreaming = false
    private var destinationIp: String = ""
    private var destinationPort: Int = DEFAULT_PORT

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var statusThread: Thread? = null
    private val captureActive = AtomicBoolean(false)
    private var lastMeasuredFramesPerSecond = 0
    private var currentDspSummary: String = ""

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private lateinit var audioManager: AudioManager
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        currentDspSummary = getString(R.string.dsp_status_placeholder)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServiceSafely()
            ACTION_QUERY_STATUS -> sendStatusUpdate()
            ACTION_START,
            null -> {
                destinationIp = intent?.getStringExtra(EXTRA_DESTINATION_IP).orEmpty()
                destinationPort = intent?.getIntExtra(EXTRA_DESTINATION_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                startForeground(NOTIFICATION_ID, buildStreamingNotification())
                startCapturePipeline()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapturePipeline()
        super.onDestroy()
    }

    private fun stopServiceSafely() {
        stopCapturePipeline()
        sendStatusUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatusUpdate() {
        val statusIntent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_STREAMING, isStreaming)
            putExtra(EXTRA_STATUS_PACKETS_PER_SECOND, lastMeasuredFramesPerSecond)
            putExtra(EXTRA_STATUS_DSP_SUMMARY, currentDspSummary)
        }
        sendBroadcast(statusIntent)
    }

    @SuppressLint("MissingPermission")
    private fun startCapturePipeline() {
        if (captureActive.get()) {
            Log.d(TAG, "Capture pipeline already running.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; capture pipeline cannot start.")
            currentDspSummary = getString(R.string.dsp_status_permission_missing)
            isStreaming = false
            sendStatusUpdate()
            return
        }

        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBufferBytes == AudioRecord.ERROR || minimumBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Failed to determine minimum AudioRecord buffer size: $minimumBufferBytes")
            currentDspSummary = getString(R.string.dsp_status_audio_init_failed)
            isStreaming = false
            sendStatusUpdate()
            return
        }

        val targetBufferSize = maxOf(minimumBufferBytes, FRAME_SIZE_SAMPLES * BYTES_PER_SAMPLE * 4)

        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "AudioManager mode set to MODE_IN_COMMUNICATION (was $previousAudioMode)")

        val localAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            targetBufferSize
        )

        if (localAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.")
            audioManager.mode = previousAudioMode
            localAudioRecord.release()
            currentDspSummary = getString(R.string.dsp_status_audio_init_failed)
            isStreaming = false
            sendStatusUpdate()
            return
        }

        audioRecord = localAudioRecord

        val audioSessionId = localAudioRecord.audioSessionId
        val aecStatus = enableAcousticEchoCanceler(audioSessionId)
        val nsStatus = enableNoiseSuppressor(audioSessionId)
        val agcStatus = enableAutomaticGainControl(audioSessionId)
        currentDspSummary = getString(R.string.dsp_status_effect_summary_format, aecStatus, nsStatus, agcStatus)
        Log.i(TAG, "DSP effect status: $currentDspSummary")

        captureActive.set(true)
        isStreaming = true
        lastMeasuredFramesPerSecond = 0
        sendStatusUpdate()

        captureThread = Thread({ captureLoop(localAudioRecord) }, "audio-capture-thread").also { it.start() }
        statusThread = Thread({ statusLoop() }, "audio-status-thread").also { it.start() }
    }

    private fun captureLoop(localAudioRecord: AudioRecord) {
        val frameBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        var framesSinceLastTick = 0
        var lastTickTime = System.currentTimeMillis()
        val destinationAddress = resolveDestinationAddress() ?: run {
            captureActive.set(false)
            isStreaming = false
            return
        }

        val udpSocket = try {
            DatagramSocket()
        } catch (socketException: SocketException) {
            Log.e(TAG, "Failed to create UDP socket.", socketException)
            captureActive.set(false)
            isStreaming = false
            return
        }

        val pcmPacketBuffer = ByteArray(FRAME_SIZE_SAMPLES * BYTES_PER_SAMPLE)

        try {
            localAudioRecord.startRecording()
            Log.i(TAG, "AudioRecord started: source=VOICE_COMMUNICATION, sampleRate=$SAMPLE_RATE, frameSamples=$FRAME_SIZE_SAMPLES")
            Log.i(TAG, "UDP PCM debug sender started: target=${destinationAddress.hostAddress}:$destinationPort")

            while (captureActive.get()) {
                val samplesRead = localAudioRecord.read(frameBuffer, 0, frameBuffer.size)
                if (samplesRead <= 0) {
                    Log.w(TAG, "AudioRecord read returned $samplesRead")
                    continue
                }

                val packetSizeBytes = samplesRead * BYTES_PER_SAMPLE
                convertPcmToLittleEndian(frameBuffer, samplesRead, pcmPacketBuffer)
                val packet = DatagramPacket(pcmPacketBuffer, packetSizeBytes, destinationAddress, destinationPort)
                udpSocket.send(packet)

                framesSinceLastTick++
                val now = System.currentTimeMillis()
                if (now - lastTickTime >= STATS_INTERVAL_MS) {
                    lastMeasuredFramesPerSecond = framesSinceLastTick
                    framesSinceLastTick = 0
                    lastTickTime = now
                }
            }
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Capture loop failed with IllegalStateException", exception)
        } catch (exception: Exception) {
            Log.e(TAG, "Capture loop failed while sending UDP PCM.", exception)
        } finally {
            udpSocket.close()
            try {
                localAudioRecord.stop()
            } catch (stopException: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed", stopException)
            }
            Log.i(TAG, "Capture loop stopped.")
        }
    }

    private fun resolveDestinationAddress(): InetAddress? {
        return try {
            InetAddress.getByName(destinationIp)
        } catch (exception: UnknownHostException) {
            Log.e(TAG, "Unable to resolve destination IP: $destinationIp", exception)
            null
        }
    }

    private fun convertPcmToLittleEndian(source: ShortArray, samplesRead: Int, target: ByteArray) {
        for (index in 0 until samplesRead) {
            val sample = source[index].toInt()
            target[index * BYTES_PER_SAMPLE] = (sample and 0xFF).toByte()
            target[index * BYTES_PER_SAMPLE + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
    }

    private fun statusLoop() {
        while (captureActive.get()) {
            sendStatusUpdate()
            try {
                Thread.sleep(STATS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun stopCapturePipeline() {
        captureActive.set(false)

        captureThread?.interrupt()
        captureThread?.join(THREAD_JOIN_TIMEOUT_MS)
        captureThread = null

        statusThread?.interrupt()
        statusThread?.join(THREAD_JOIN_TIMEOUT_MS)
        statusThread = null

        acousticEchoCanceler?.release()
        acousticEchoCanceler = null

        noiseSuppressor?.release()
        noiseSuppressor = null

        automaticGainControl?.release()
        automaticGainControl = null

        audioRecord?.release()
        audioRecord = null

        audioManager.mode = previousAudioMode
        isStreaming = false
        lastMeasuredFramesPerSecond = 0
        Log.i(TAG, "Capture pipeline released. AudioManager mode restored to $previousAudioMode")
    }

    private fun enableAcousticEchoCanceler(sessionId: Int): String {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.w(TAG, "AcousticEchoCanceler unavailable on this device.")
            return getString(R.string.dsp_effect_unavailable)
        }

        acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
        val enabled = acousticEchoCanceler?.let {
            it.enabled = true
            it.enabled
        } ?: false

        return if (enabled) getString(R.string.dsp_effect_enabled) else getString(R.string.dsp_effect_disabled)
    }

    private fun enableNoiseSuppressor(sessionId: Int): String {
        if (!NoiseSuppressor.isAvailable()) {
            Log.w(TAG, "NoiseSuppressor unavailable on this device.")
            return getString(R.string.dsp_effect_unavailable)
        }

        noiseSuppressor = NoiseSuppressor.create(sessionId)
        val enabled = noiseSuppressor?.let {
            it.enabled = true
            it.enabled
        } ?: false

        return if (enabled) getString(R.string.dsp_effect_enabled) else getString(R.string.dsp_effect_disabled)
    }

    private fun enableAutomaticGainControl(sessionId: Int): String {
        if (!AutomaticGainControl.isAvailable()) {
            Log.w(TAG, "AutomaticGainControl unavailable on this device.")
            return getString(R.string.dsp_effect_unavailable)
        }

        automaticGainControl = AutomaticGainControl.create(sessionId)
        val enabled = automaticGainControl?.let {
            it.enabled = true
            it.enabled
        } ?: false

        return if (enabled) getString(R.string.dsp_effect_enabled) else getString(R.string.dsp_effect_disabled)
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

        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SIZE_SAMPLES = 960
        private const val BYTES_PER_SAMPLE = 2
        private const val STATS_INTERVAL_MS = 1_000L
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
        private const val TAG = "AudioStreamingService"

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
