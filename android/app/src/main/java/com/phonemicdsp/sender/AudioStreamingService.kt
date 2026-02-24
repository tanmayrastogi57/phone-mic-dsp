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
import android.media.MediaCodec
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.MediaFormat
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
import java.net.InetSocketAddress
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
    private var lastErrorMessage: String? = null
    private var activeMicLabel: String = ""
    private var routingWarningMessage: String? = null
    private val audioRecordLock = Any()

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var opusEncoder: OpusFrameEncoder? = null
    private var udpSocket: DatagramSocket? = null

    private lateinit var audioManager: AudioManager
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var selectedMicDeviceId: Int = DEVICE_ID_DEFAULT
    private var preferredMicDirection: Int = AudioRecord.MIC_DIRECTION_UNSPECIFIED

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        currentDspSummary = getString(R.string.dsp_status_placeholder)
        activeMicLabel = getString(R.string.default_microphone_label)

        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedMicDeviceId = sharedPreferences.getInt(PREF_KEY_MIC_DEVICE_ID, DEVICE_ID_DEFAULT)
        preferredMicDirection = sharedPreferences.getInt(PREF_KEY_MIC_DIRECTION, AudioRecord.MIC_DIRECTION_UNSPECIFIED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServiceSafely()
            ACTION_QUERY_STATUS -> sendStatusUpdate()
            ACTION_SELECT_MIC -> {
                selectedMicDeviceId = intent.getIntExtra(EXTRA_MIC_DEVICE_ID, DEVICE_ID_DEFAULT)
                preferredMicDirection = intent.getIntExtra(EXTRA_MIC_DIRECTION, AudioRecord.MIC_DIRECTION_UNSPECIFIED)
                persistMicSelection()
                applyMicSelectionToActiveRecord()
                sendStatusUpdate()
            }
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
            putExtra(EXTRA_STATUS_ERROR, lastErrorMessage)
            putExtra(EXTRA_STATUS_ACTIVE_MIC, activeMicLabel)
            putExtra(EXTRA_STATUS_ROUTING_WARNING, routingWarningMessage)
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
            lastErrorMessage = getString(R.string.error_permission_missing)
            isStreaming = false
            sendStatusUpdate()
            return
        }

        val validatedEndpoint = parseDestinationEndpoint() ?: run {
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
            lastErrorMessage = getString(R.string.error_audio_record_init)
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
            lastErrorMessage = getString(R.string.error_audio_record_init)
            isStreaming = false
            sendStatusUpdate()
            return
        }

        audioRecord = localAudioRecord
        applyMicSelection(localAudioRecord)

        val audioSessionId = localAudioRecord.audioSessionId
        val aecStatus = enableAcousticEchoCanceler(audioSessionId)
        val nsStatus = enableNoiseSuppressor(audioSessionId)
        val agcStatus = enableAutomaticGainControl(audioSessionId)
        currentDspSummary = getString(R.string.dsp_status_effect_summary_format, aecStatus, nsStatus, agcStatus)
        Log.i(TAG, "DSP effect status: $currentDspSummary")

        captureActive.set(true)
        isStreaming = true
        lastErrorMessage = null
        lastMeasuredFramesPerSecond = 0
        sendStatusUpdate()

        captureThread = Thread({ captureLoop(localAudioRecord, validatedEndpoint.address) }, "audio-capture-thread").also { it.start() }
        statusThread = Thread({ statusLoop() }, "audio-status-thread").also { it.start() }
    }

    private fun captureLoop(localAudioRecord: AudioRecord, destinationAddress: InetAddress) {
        val frameBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        var framesSinceLastTick = 0
        var lastTickTime = System.currentTimeMillis()
        val localSocket = try {
            DatagramSocket()
        } catch (socketException: SocketException) {
            Log.e(TAG, "Failed to create UDP socket.", socketException)
            captureActive.set(false)
            isStreaming = false
            lastErrorMessage = getString(R.string.error_udp_socket_init)
            sendStatusUpdate()
            return
        }
        udpSocket = localSocket

        val localOpusEncoder = try {
            OpusFrameEncoder(SAMPLE_RATE, CHANNEL_COUNT, BITRATE_BPS, OPUS_COMPLEXITY, OPUS_FEC_ENABLED)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to initialize Opus encoder.", exception)
            captureActive.set(false)
            isStreaming = false
            lastErrorMessage = getString(R.string.error_opus_encoder_init)
            sendStatusUpdate()
            localSocket.close()
            udpSocket = null
            return
        }
        opusEncoder = localOpusEncoder

        try {
            localAudioRecord.startRecording()
            Log.i(TAG, "AudioRecord started: source=VOICE_COMMUNICATION, sampleRate=$SAMPLE_RATE, frameSamples=$FRAME_SIZE_SAMPLES")
            Log.i(TAG, "UDP Opus sender started: target=${destinationAddress.hostAddress}:$destinationPort")

            while (captureActive.get()) {
                val samplesRead = localAudioRecord.read(frameBuffer, 0, frameBuffer.size)
                if (samplesRead <= 0) {
                    Log.w(TAG, "AudioRecord read returned $samplesRead")
                    continue
                }

                if (samplesRead != FRAME_SIZE_SAMPLES) {
                    Log.w(TAG, "Expected $FRAME_SIZE_SAMPLES samples per frame, got $samplesRead; dropping partial frame.")
                    continue
                }

                val opusPayload = localOpusEncoder.encodeFrame(frameBuffer) ?: continue
                val packet = DatagramPacket(opusPayload, opusPayload.size, destinationAddress, destinationPort)
                localSocket.send(packet)

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
            lastErrorMessage = getString(R.string.error_capture_illegal_state)
        } catch (exception: Exception) {
            Log.e(TAG, "Capture loop failed while sending UDP Opus.", exception)
            lastErrorMessage = getString(R.string.error_capture_loop_failure)
        } finally {
            localOpusEncoder.release()
            opusEncoder = null
            localSocket.close()
            udpSocket = null
            try {
                localAudioRecord.stop()
            } catch (stopException: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed", stopException)
            }
            Log.i(TAG, "Capture loop stopped.")
            sendStatusUpdate()
        }
    }

    private fun applyMicSelectionToActiveRecord() {
        val currentRecord = synchronized(audioRecordLock) { audioRecord }
        if (currentRecord == null) {
            activeMicLabel = getMicLabelForSelection(selectedMicDeviceId)
            return
        }

        applyMicSelection(currentRecord)
    }

    private fun applyMicSelection(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            routingWarningMessage = getString(R.string.mic_unsupported_message)
            activeMicLabel = getString(R.string.default_microphone_label)
            return
        }

        val selectedDevice = resolveSelectedInputDevice()
        activeMicLabel = selectedDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: getMicLabelForSelection(selectedMicDeviceId)

        val preferredDeviceSet = record.setPreferredDevice(selectedDevice)
        Log.i(TAG, "setPreferredDevice(deviceId=$selectedMicDeviceId, device=${selectedDevice?.id}) result=$preferredDeviceSet")

        val directionResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val result = record.setPreferredMicrophoneDirection(preferredMicDirection)
            Log.i(TAG, "setPreferredMicrophoneDirection(direction=$preferredMicDirection) result=$result")
            result
        } else {
            true
        }

        routingWarningMessage = if (!preferredDeviceSet || !directionResult) {
            getString(R.string.mic_routing_not_supported)
        } else {
            null
        }
    }

    private fun resolveSelectedInputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || selectedMicDeviceId == DEVICE_ID_DEFAULT) {
            return null
        }

        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        inputDevices.forEach {
            Log.i(TAG, "Input device discovered: id=${it.id}, name=${it.productName}, type=${it.type}")
        }
        return inputDevices.firstOrNull { it.id == selectedMicDeviceId }
    }

    private fun getMicLabelForSelection(deviceId: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || deviceId == DEVICE_ID_DEFAULT) {
            return getString(R.string.default_microphone_label)
        }

        val selected = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == deviceId }

        return selected?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_microphone_label)
    }

    private fun persistMicSelection() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_MIC_DEVICE_ID, selectedMicDeviceId)
            .putInt(PREF_KEY_MIC_DIRECTION, preferredMicDirection)
            .apply()
    }

    private fun parseDestinationEndpoint(): InetSocketAddress? {
        if (destinationPort !in 1..65535) {
            Log.e(TAG, "Invalid destination port: $destinationPort")
            currentDspSummary = getString(R.string.dsp_status_invalid_target)
            lastErrorMessage = getString(R.string.error_invalid_port)
            return null
        }

        return try {
            val address = InetAddress.getByName(destinationIp)
            InetSocketAddress(address, destinationPort)
        } catch (exception: UnknownHostException) {
            Log.e(TAG, "Unable to resolve destination IP: $destinationIp", exception)
            currentDspSummary = getString(R.string.dsp_status_invalid_target)
            lastErrorMessage = getString(R.string.error_invalid_ip)
            null
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

        runCatching { audioRecord?.stop() }
        udpSocket?.close()
        udpSocket = null

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

        opusEncoder?.release()
        opusEncoder = null

        audioRecord?.release()
        audioRecord = null

        audioManager.mode = previousAudioMode
        isStreaming = false
        lastMeasuredFramesPerSecond = 0
        routingWarningMessage = null
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
        const val ACTION_SELECT_MIC = "com.phonemicdsp.sender.action.SELECT_MIC"
        const val ACTION_STATUS_UPDATE = "com.phonemicdsp.sender.action.STATUS_UPDATE"

        const val EXTRA_DESTINATION_IP = "extra_destination_ip"
        const val EXTRA_DESTINATION_PORT = "extra_destination_port"
        const val EXTRA_MIC_DEVICE_ID = "extra_mic_device_id"
        const val EXTRA_MIC_DIRECTION = "extra_mic_direction"
        const val EXTRA_STATUS_STREAMING = "extra_status_streaming"
        const val EXTRA_STATUS_PACKETS_PER_SECOND = "extra_status_packets_per_second"
        const val EXTRA_STATUS_DSP_SUMMARY = "extra_status_dsp_summary"
        const val EXTRA_STATUS_ERROR = "extra_status_error"
        const val EXTRA_STATUS_ACTIVE_MIC = "extra_status_active_mic"
        const val EXTRA_STATUS_ROUTING_WARNING = "extra_status_routing_warning"

        private const val CHANNEL_ID = "audio_streaming_channel"
        private const val NOTIFICATION_ID = 2201
        private const val REQUEST_CODE_STOP = 2202
        private const val DEFAULT_PORT = 5555
        private const val DEVICE_ID_DEFAULT = -1
        private const val PREFS_NAME = "phone_mic_sender_prefs"
        private const val PREF_KEY_MIC_DEVICE_ID = "mic_device_id"
        private const val PREF_KEY_MIC_DIRECTION = "mic_direction"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val FRAME_SIZE_SAMPLES = 960
        private const val BYTES_PER_SAMPLE = 2
        private const val BITRATE_BPS = 48_000
        private const val OPUS_COMPLEXITY = 8
        private const val OPUS_FEC_ENABLED = true
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

        fun selectMicIntent(context: Context, deviceId: Int, direction: Int): Intent =
            Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_SELECT_MIC
                putExtra(EXTRA_MIC_DEVICE_ID, deviceId)
                putExtra(EXTRA_MIC_DIRECTION, direction)
            }
    }
}

private class OpusFrameEncoder(
    sampleRate: Int,
    channelCount: Int,
    bitrateBps: Int,
    complexity: Int,
    enableFec: Boolean
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val outputBufferInfo = MediaCodec.BufferInfo()
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(KEY_OPUS_COMPLEXITY, complexity)
            setInteger(KEY_OPUS_INBAND_FEC, if (enableFec) 1 else 0)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodeFrame(pcmFrame: ShortArray): ByteArray? {
        val inputIndex = codec.dequeueInputBuffer(BUFFER_TIMEOUT_US)
        if (inputIndex < 0) {
            return null
        }

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
        inputBuffer.clear()
        pcmFrame.forEach { sample ->
            inputBuffer.put((sample.toInt() and 0xFF).toByte())
            inputBuffer.put(((sample.toInt() ushr 8) and 0xFF).toByte())
        }

        codec.queueInputBuffer(
            inputIndex,
            0,
            FRAME_SIZE_BYTES,
            System.nanoTime() / 1_000,
            0
        )

        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(outputBufferInfo, BUFFER_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> {
                    if (outputIndex < 0) {
                        return null
                    }

                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: run {
                        codec.releaseOutputBuffer(outputIndex, false)
                        return null
                    }

                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    val encoded = ByteArray(outputBufferInfo.size)
                    outputBuffer.position(outputBufferInfo.offset)
                    outputBuffer.limit(outputBufferInfo.offset + outputBufferInfo.size)
                    outputBuffer.get(encoded)
                    codec.releaseOutputBuffer(outputIndex, false)
                    return encoded
                }
            }
        }
    }

    fun release() {
        if (released) {
            return
        }

        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val FRAME_SIZE_BYTES = 960 * 2
        private const val BUFFER_TIMEOUT_US = 10_000L
        private const val KEY_OPUS_COMPLEXITY = "complexity"
        private const val KEY_OPUS_INBAND_FEC = "inband-fec"
    }
}
