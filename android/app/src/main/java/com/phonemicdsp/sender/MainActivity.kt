package com.phonemicdsp.sender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    private lateinit var pcIpInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var streamingStatusText: TextView
    private lateinit var packetRateText: TextView
    private lateinit var dspStatusText: TextView
    private lateinit var activeMicText: TextView
    private lateinit var micRoutingStatusText: TextView
    private lateinit var microphoneSpinner: Spinner
    private lateinit var audioSourceSpinner: Spinner
    private lateinit var microphoneAdapter: ArrayAdapter<String>
    private lateinit var audioSourceAdapter: ArrayAdapter<String>
    private lateinit var audioManager: AudioManager

    private var lastServiceError: String? = null
    private var microphoneOptions: List<MicrophoneOption> = emptyList()
    private var audioSourceOptions: List<AudioSourceMode> = emptyList()

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.record_permission_required, Toast.LENGTH_SHORT).show()
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioStreamingService.ACTION_STATUS_UPDATE) {
                return
            }

            val isStreaming = intent.getBooleanExtra(AudioStreamingService.EXTRA_STATUS_STREAMING, false)
            val packetsPerSecond = intent.getIntExtra(AudioStreamingService.EXTRA_STATUS_PACKETS_PER_SECOND, 0)
            val dspSummary = intent.getStringExtra(AudioStreamingService.EXTRA_STATUS_DSP_SUMMARY)
                ?: getString(R.string.dsp_status_placeholder)
            val serviceError = intent.getStringExtra(AudioStreamingService.EXTRA_STATUS_ERROR)
            val activeMic = intent.getStringExtra(AudioStreamingService.EXTRA_STATUS_ACTIVE_MIC)
                ?: getString(R.string.default_microphone_label)
            val routingWarning = intent.getStringExtra(AudioStreamingService.EXTRA_STATUS_ROUTING_WARNING)

            updateStatusViews(isStreaming, packetsPerSecond, dspSummary, activeMic, routingWarning)
            if (!serviceError.isNullOrBlank() && serviceError != lastServiceError) {
                lastServiceError = serviceError
                Toast.makeText(this@MainActivity, serviceError, Toast.LENGTH_SHORT).show()
            } else if (serviceError.isNullOrBlank()) {
                lastServiceError = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        pcIpInput = findViewById(R.id.pcIpInput)
        portInput = findViewById(R.id.portInput)
        streamingStatusText = findViewById(R.id.streamingStatusText)
        packetRateText = findViewById(R.id.packetRateText)
        dspStatusText = findViewById(R.id.dspStatusText)
        activeMicText = findViewById(R.id.activeMicText)
        micRoutingStatusText = findViewById(R.id.micRoutingStatusText)
        microphoneSpinner = findViewById(R.id.microphoneSpinner)
        audioSourceSpinner = findViewById(R.id.audioSourceSpinner)

        val startButton = findViewById<Button>(R.id.startServiceButton)
        val stopButton = findViewById<Button>(R.id.stopServiceButton)
        val refreshMicsButton = findViewById<Button>(R.id.refreshMicsButton)

        microphoneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        microphoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        microphoneSpinner.adapter = microphoneAdapter

        audioSourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        audioSourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSourceSpinner.adapter = audioSourceAdapter

        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pcIpInput.setText(sharedPreferences.getString(PREF_KEY_PC_IP, ""))
        portInput.setText(sharedPreferences.getInt(PREF_KEY_PORT, DEFAULT_PORT).toString())

        val restoredMicId = sharedPreferences.getInt(PREF_KEY_MIC_DEVICE_ID, DEVICE_ID_DEFAULT)
        val restoredAudioSourceMode = AudioSourceMode.fromStored(
            sharedPreferences.getString(PREF_KEY_AUDIO_SOURCE_MODE, AudioSourceMode.VOICE_COMMUNICATION.name)
        )

        bindAudioSourceOptions(restoredAudioSourceMode)
        refreshMicrophoneList(restoredMicId)

        microphoneSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in microphoneOptions.indices) {
                    return
                }

                val selectedOption = microphoneOptions[position]
                sharedPreferences.edit()
                    .putInt(PREF_KEY_MIC_DEVICE_ID, selectedOption.deviceId)
                    .putInt(PREF_KEY_MIC_DIRECTION, selectedOption.direction)
                    .apply()

                activeMicText.text = getString(R.string.active_mic_format, selectedOption.displayName)

                startService(AudioStreamingService.selectMicIntent(this@MainActivity, selectedOption.deviceId, selectedOption.direction))
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        audioSourceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in audioSourceOptions.indices) {
                    return
                }

                val selectedMode = audioSourceOptions[position]
                sharedPreferences.edit().putString(PREF_KEY_AUDIO_SOURCE_MODE, selectedMode.name).apply()
                startService(AudioStreamingService.selectAudioSourceIntent(this@MainActivity, selectedMode))
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        updateStatusViews(
            isStreaming = false,
            packetsPerSecond = 0,
            dspSummary = getString(R.string.dsp_status_placeholder),
            activeMic = getString(R.string.default_microphone_label),
            routingWarning = null
        )

        refreshMicsButton.setOnClickListener {
            refreshMicrophoneList(microphoneOptions.getOrNull(microphoneSpinner.selectedItemPosition)?.deviceId)
        }

        startButton.setOnClickListener {
            val destinationIp = pcIpInput.text?.toString()?.trim().orEmpty()
            if (destinationIp.isEmpty()) {
                Toast.makeText(this, R.string.pc_ip_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val destinationPort = portInput.text?.toString()?.toIntOrNull()
            if (destinationPort == null || destinationPort !in 1..65535) {
                Toast.makeText(this, R.string.invalid_port_message, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidIpAddress(destinationIp)) {
                Toast.makeText(this, R.string.invalid_ip_message, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!hasRecordAudioPermission()) {
                requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            sharedPreferences.edit()
                .putString(PREF_KEY_PC_IP, destinationIp)
                .putInt(PREF_KEY_PORT, destinationPort)
                .apply()

            val startIntent = AudioStreamingService.startIntent(this, destinationIp, destinationPort)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }

        stopButton.setOnClickListener {
            startService(AudioStreamingService.stopIntent(this))
        }
    }

    private fun bindAudioSourceOptions(preferredMode: AudioSourceMode) {
        audioSourceOptions = AudioSourceMode.supportedModes()
        if (audioSourceOptions.isEmpty()) {
            val message = getString(R.string.audio_source_mode_unavailable)
            audioSourceAdapter.clear()
            audioSourceAdapter.add(message)
            audioSourceAdapter.notifyDataSetChanged()
            audioSourceSpinner.isEnabled = false
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = audioSourceOptions.map { getAudioSourceLabel(it) }
        audioSourceAdapter.clear()
        audioSourceAdapter.addAll(labels)
        audioSourceAdapter.notifyDataSetChanged()
        audioSourceSpinner.isEnabled = true

        val selectedIndex = audioSourceOptions.indexOf(preferredMode).takeIf { it >= 0 } ?: 0
        audioSourceSpinner.setSelection(selectedIndex, false)
    }

    private fun getAudioSourceLabel(mode: AudioSourceMode): String {
        return when (mode) {
            AudioSourceMode.VOICE_COMMUNICATION -> getString(R.string.audio_source_mode_voice_communication)
            AudioSourceMode.VOICE_RECOGNITION -> getString(R.string.audio_source_mode_voice_recognition)
            AudioSourceMode.CAMCORDER -> getString(R.string.audio_source_mode_camcorder)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(AudioStreamingService.ACTION_STATUS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startService(AudioStreamingService.statusQueryIntent(this))
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun refreshMicrophoneList(preferredDeviceId: Int?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            microphoneOptions = listOf(MicrophoneOption.defaultOption(this))
            bindMicrophoneOptions(preferredDeviceId)
            micRoutingStatusText.text = getString(R.string.mic_unsupported_message)
            return
        }

        val discoveredInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }

        discoveredInputs.forEach { device ->
            Log.i(TAG, "Input device discovered: id=${device.id}, name=${device.productName}, type=${deviceTypeToString(device.type)}")
        }

        microphoneOptions = if (discoveredInputs.isEmpty()) {
            listOf(MicrophoneOption.defaultOption(this))
        } else {
            listOf(MicrophoneOption.defaultOption(this)) + discoveredInputs.map { info ->
                val displayName = buildString {
                    append(info.productName?.toString()?.takeIf { it.isNotBlank() } ?: getString(R.string.microphone_unknown_name))
                    append(" | ")
                    append(deviceTypeToString(info.type))
                    append(" | id=")
                    append(info.id)
                    append(" | source=")
                    append(info.isSource)
                    append(" | channels=")
                    append(info.channelCounts.joinToString(prefix = "[", postfix = "]"))
                }

                MicrophoneOption(
                    deviceId = info.id,
                    displayName = displayName,
                    direction = inferDirection(info.type)
                )
            }
        }

        bindMicrophoneOptions(preferredDeviceId)
    }

    private fun bindMicrophoneOptions(preferredDeviceId: Int?) {
        microphoneAdapter.clear()
        microphoneAdapter.addAll(microphoneOptions.map { it.displayName })
        microphoneAdapter.notifyDataSetChanged()

        val selectedIndex = microphoneOptions.indexOfFirst { it.deviceId == preferredDeviceId }.takeIf { it >= 0 } ?: 0
        microphoneSpinner.setSelection(selectedIndex, false)

        val selectedOption = microphoneOptions.getOrNull(selectedIndex) ?: MicrophoneOption.defaultOption(this)
        activeMicText.text = getString(R.string.active_mic_format, selectedOption.displayName)
    }

    private fun updateStatusViews(
        isStreaming: Boolean,
        packetsPerSecond: Int,
        dspSummary: String,
        activeMic: String,
        routingWarning: String?
    ) {
        streamingStatusText.text = if (isStreaming) {
            getString(R.string.streaming_on)
        } else {
            getString(R.string.streaming_off)
        }

        packetRateText.text = getString(R.string.packets_per_second_format, packetsPerSecond)
        dspStatusText.text = getString(R.string.dsp_status_format, dspSummary)
        activeMicText.text = getString(R.string.active_mic_format, activeMic)
        micRoutingStatusText.text = routingWarning ?: getString(R.string.mic_routing_status_ok)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun isValidIpAddress(value: String): Boolean = runCatching {
        InetAddress.getByName(value)
    }.isSuccess

    private fun inferDirection(deviceType: Int): Int {
        return when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRecord.MIC_DIRECTION_EXTERNAL
            else -> AudioRecord.MIC_DIRECTION_UNSPECIFIED
        }
    }

    private fun deviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line analog"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line digital"
            else -> "Type $type"
        }
    }

    private data class MicrophoneOption(
        val deviceId: Int,
        val displayName: String,
        val direction: Int
    ) {
        companion object {
            fun defaultOption(context: Context): MicrophoneOption {
                return MicrophoneOption(
                    deviceId = DEVICE_ID_DEFAULT,
                    displayName = context.getString(R.string.default_microphone_label),
                    direction = AudioRecord.MIC_DIRECTION_UNSPECIFIED
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "phone_mic_sender_prefs"
        private const val PREF_KEY_PC_IP = "pc_ip"
        private const val PREF_KEY_PORT = "port"
        private const val PREF_KEY_MIC_DEVICE_ID = "mic_device_id"
        private const val PREF_KEY_MIC_DIRECTION = "mic_direction"
        private const val PREF_KEY_AUDIO_SOURCE_MODE = "audio_source_mode"
        private const val DEFAULT_PORT = 5555
        private const val DEVICE_ID_DEFAULT = -1
        private const val TAG = "MainActivity"
    }
}
