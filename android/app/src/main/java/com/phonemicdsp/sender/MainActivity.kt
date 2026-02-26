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
import android.widget.SeekBar
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
    private lateinit var micGainValueText: TextView
    private lateinit var micGainClippingWarningText: TextView
    private lateinit var micGainSeekBar: SeekBar
    private lateinit var microphoneSpinner: Spinner
    private lateinit var audioSourceSpinner: Spinner
    private lateinit var opusPresetSpinner: Spinner
    private lateinit var opusBitrateValueText: TextView
    private lateinit var opusBitrateSeekBar: SeekBar
    private lateinit var opusComplexityValueText: TextView
    private lateinit var opusComplexitySeekBar: SeekBar
    private lateinit var opusPacketLossValueText: TextView
    private lateinit var opusPacketLossSeekBar: SeekBar
    private lateinit var opusFrameDurationSpinner: Spinner
    private lateinit var opusFecSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var stereoSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var stereoSupportText: TextView
    private lateinit var microphoneAdapter: ArrayAdapter<String>
    private lateinit var audioSourceAdapter: ArrayAdapter<String>
    private lateinit var opusPresetAdapter: ArrayAdapter<String>
    private lateinit var opusFrameDurationAdapter: ArrayAdapter<String>
    private lateinit var audioManager: AudioManager

    private var lastServiceError: String? = null
    private var microphoneOptions: List<MicrophoneOption> = emptyList()
    private var audioSourceOptions: List<AudioSourceMode> = emptyList()
    private lateinit var opusPresetOptions: List<OpusPreset>
    private lateinit var opusFrameDurationOptions: List<OpusFrameDuration>
    private var suppressOpusUiEvents = false

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
            val clippingWarning = intent.getBooleanExtra(AudioStreamingService.EXTRA_STATUS_CLIPPING_WARNING, false)

            updateStatusViews(isStreaming, packetsPerSecond, dspSummary, activeMic, routingWarning, clippingWarning)
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
        opusPresetOptions = OpusPreset.entries
        opusFrameDurationOptions = OpusFrameDuration.entries
        Log.i(TAG, "MainActivity.onCreate: initializing UI with default preset=${OpusPreset.VOICE_CLEAN.name}, default config=${OpusStreamingConfig.DEFAULT.summary()}")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        pcIpInput = findViewById(R.id.pcIpInput)
        portInput = findViewById(R.id.portInput)
        streamingStatusText = findViewById(R.id.streamingStatusText)
        packetRateText = findViewById(R.id.packetRateText)
        dspStatusText = findViewById(R.id.dspStatusText)
        activeMicText = findViewById(R.id.activeMicText)
        micRoutingStatusText = findViewById(R.id.micRoutingStatusText)
        micGainValueText = findViewById(R.id.micGainValueText)
        micGainClippingWarningText = findViewById(R.id.micGainClippingWarningText)
        micGainSeekBar = findViewById(R.id.micGainSeekBar)
        microphoneSpinner = findViewById(R.id.microphoneSpinner)
        audioSourceSpinner = findViewById(R.id.audioSourceSpinner)
        opusPresetSpinner = findViewById(R.id.opusPresetSpinner)
        opusBitrateValueText = findViewById(R.id.opusBitrateValueText)
        opusBitrateSeekBar = findViewById(R.id.opusBitrateSeekBar)
        opusComplexityValueText = findViewById(R.id.opusComplexityValueText)
        opusComplexitySeekBar = findViewById(R.id.opusComplexitySeekBar)
        opusPacketLossValueText = findViewById(R.id.opusPacketLossValueText)
        opusPacketLossSeekBar = findViewById(R.id.opusPacketLossSeekBar)
        opusFrameDurationSpinner = findViewById(R.id.opusFrameDurationSpinner)
        opusFecSwitch = findViewById(R.id.opusFecSwitch)
        stereoSwitch = findViewById(R.id.stereoSwitch)
        stereoSupportText = findViewById(R.id.stereoSupportText)

        val startButton = findViewById<Button>(R.id.startServiceButton)
        val stopButton = findViewById<Button>(R.id.stopServiceButton)
        val refreshMicsButton = findViewById<Button>(R.id.refreshMicsButton)

        microphoneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        microphoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        microphoneSpinner.adapter = microphoneAdapter

        audioSourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        audioSourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSourceSpinner.adapter = audioSourceAdapter

        opusPresetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        opusPresetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        opusPresetSpinner.adapter = opusPresetAdapter

        opusFrameDurationAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        opusFrameDurationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        opusFrameDurationSpinner.adapter = opusFrameDurationAdapter

        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pcIpInput.setText(sharedPreferences.getString(PREF_KEY_PC_IP, ""))
        portInput.setText(sharedPreferences.getInt(PREF_KEY_PORT, DEFAULT_PORT).toString())

        val restoredMicGainPercent = sharedPreferences.getInt(PREF_KEY_MIC_GAIN_PERCENT, DEFAULT_MIC_GAIN_PERCENT)
            .coerceIn(MIN_MIC_GAIN_PERCENT, MAX_MIC_GAIN_PERCENT)
        val restoredMicGain = restoredMicGainPercent / MIC_GAIN_PERCENT_SCALE
        micGainSeekBar.progress = restoredMicGainPercent - MIN_MIC_GAIN_PERCENT
        micGainValueText.text = getString(R.string.mic_gain_value_format, restoredMicGain)

        val restoredMicId = sharedPreferences.getInt(PREF_KEY_MIC_DEVICE_ID, DEVICE_ID_DEFAULT)
        val restoredAudioSourceMode = AudioSourceMode.fromStored(
            sharedPreferences.getString(PREF_KEY_AUDIO_SOURCE_MODE, AudioSourceMode.VOICE_COMMUNICATION.name)
        )
        val restoredOpusPreset = OpusPreset.fromStored(
            sharedPreferences.getString(PREF_KEY_OPUS_PRESET, OpusPreset.VOICE_CLEAN.name)
        )
        val restoredOpusConfig = OpusStreamingConfig.sanitize(
            bitrateBps = sharedPreferences.getInt(PREF_KEY_OPUS_BITRATE_BPS, restoredOpusPreset.config.bitrateBps),
            complexity = sharedPreferences.getInt(PREF_KEY_OPUS_COMPLEXITY, restoredOpusPreset.config.complexity),
            frameDurationMs = sharedPreferences.getInt(PREF_KEY_OPUS_FRAME_DURATION_MS, restoredOpusPreset.config.frameDuration.millis),
            fecEnabled = sharedPreferences.getBoolean(PREF_KEY_OPUS_FEC_ENABLED, restoredOpusPreset.config.fecEnabled),
            expectedPacketLossPercent = sharedPreferences.getInt(
                PREF_KEY_OPUS_EXPECTED_PACKET_LOSS_PERCENT,
                restoredOpusPreset.config.expectedPacketLossPercent
            ),
            channelCount = sharedPreferences.getInt(PREF_KEY_OPUS_CHANNEL_COUNT, restoredOpusPreset.config.channelCount)
        )

        bindAudioSourceOptions(restoredAudioSourceMode)
        bindOpusOptions(restoredOpusPreset, restoredOpusConfig)
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
                refreshStereoSupportUi()

                startServiceSafely(
                    AudioStreamingService.selectMicIntent(this@MainActivity, selectedOption.deviceId, selectedOption.direction),
                    "selectMic"
                )
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
                startServiceSafely(
                    AudioStreamingService.selectAudioSourceIntent(this@MainActivity, selectedMode),
                    "selectAudioSource"
                )
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        opusPresetSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressOpusUiEvents || position !in opusPresetOptions.indices) {
                    return
                }

                val preset = opusPresetOptions[position]
                applyOpusPreset(preset, sharedPreferences)
                pushCurrentOpusConfigToService(sharedPreferences)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        opusFrameDurationSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressOpusUiEvents || position !in opusFrameDurationOptions.indices) {
                    return
                }

                pushCurrentOpusConfigToService(sharedPreferences)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        opusBitrateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val kbps = progress + (OpusStreamingConfig.MIN_BITRATE_BPS / 1000)
                opusBitrateValueText.text = getString(R.string.opus_bitrate_value_format, kbps)
                if (!suppressOpusUiEvents) {
                    pushCurrentOpusConfigToService(sharedPreferences)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        opusComplexitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opusComplexityValueText.text = getString(R.string.opus_complexity_value_format, progress)
                if (!suppressOpusUiEvents) {
                    pushCurrentOpusConfigToService(sharedPreferences)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        opusPacketLossSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opusPacketLossValueText.text = getString(R.string.opus_packet_loss_value_format, progress)
                if (!suppressOpusUiEvents) {
                    pushCurrentOpusConfigToService(sharedPreferences)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        opusFecSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressOpusUiEvents) {
                pushCurrentOpusConfigToService(sharedPreferences)
            }
        }

        stereoSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressOpusUiEvents) {
                pushCurrentOpusConfigToService(sharedPreferences)
            }
        }

        micGainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gainPercent = progress + MIN_MIC_GAIN_PERCENT
                val gain = gainPercent / MIC_GAIN_PERCENT_SCALE
                micGainValueText.text = getString(R.string.mic_gain_value_format, gain)
                sharedPreferences.edit().putInt(PREF_KEY_MIC_GAIN_PERCENT, gainPercent).apply()
                startServiceSafely(AudioStreamingService.setGainIntent(this@MainActivity, gain), "setGain")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        startServiceSafely(AudioStreamingService.setGainIntent(this, restoredMicGain), "restoreGain")
        pushCurrentOpusConfigToService(sharedPreferences)

        updateStatusViews(
            isStreaming = false,
            packetsPerSecond = 0,
            dspSummary = getString(R.string.dsp_status_placeholder),
            activeMic = getString(R.string.default_microphone_label),
            routingWarning = null,
            clippingWarning = false
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
                startForegroundServiceSafely(startIntent, "startStreaming")
            } else {
                startServiceSafely(startIntent, "startStreamingLegacy")
            }
        }

        stopButton.setOnClickListener {
            startServiceSafely(AudioStreamingService.stopIntent(this), "stopStreaming")
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


    private fun bindOpusOptions(preferredPreset: OpusPreset, restoredConfig: OpusStreamingConfig) {
        val presetLabels = opusPresetOptions.map { getString(it.displayNameResId) }
        opusPresetAdapter.clear()
        opusPresetAdapter.addAll(presetLabels)
        opusPresetAdapter.notifyDataSetChanged()

        val frameLabels = opusFrameDurationOptions.map {
            when (it) {
                OpusFrameDuration.MS_10 -> getString(R.string.opus_frame_duration_10ms)
                OpusFrameDuration.MS_20 -> getString(R.string.opus_frame_duration_20ms)
            }
        }
        opusFrameDurationAdapter.clear()
        opusFrameDurationAdapter.addAll(frameLabels)
        opusFrameDurationAdapter.notifyDataSetChanged()

        suppressOpusUiEvents = true
        val presetIndex = opusPresetOptions.indexOf(preferredPreset).takeIf { it >= 0 } ?: 0
        opusPresetSpinner.setSelection(presetIndex, false)

        opusBitrateSeekBar.max = (OpusStreamingConfig.MAX_BITRATE_BPS - OpusStreamingConfig.MIN_BITRATE_BPS) / 1000
        opusBitrateSeekBar.progress = (restoredConfig.bitrateBps - OpusStreamingConfig.MIN_BITRATE_BPS) / 1000
        opusBitrateValueText.text = getString(R.string.opus_bitrate_value_format, restoredConfig.bitrateBps / 1000)

        opusComplexitySeekBar.max = OpusStreamingConfig.MAX_COMPLEXITY
        opusComplexitySeekBar.progress = restoredConfig.complexity
        opusComplexityValueText.text = getString(R.string.opus_complexity_value_format, restoredConfig.complexity)

        opusPacketLossSeekBar.max = OpusStreamingConfig.MAX_EXPECTED_PACKET_LOSS_PERCENT
        opusPacketLossSeekBar.progress = restoredConfig.expectedPacketLossPercent
        opusPacketLossValueText.text = getString(R.string.opus_packet_loss_value_format, restoredConfig.expectedPacketLossPercent)

        val frameIndex = opusFrameDurationOptions.indexOf(restoredConfig.frameDuration).takeIf { it >= 0 } ?: 0
        opusFrameDurationSpinner.setSelection(frameIndex, false)
        opusFecSwitch.isChecked = restoredConfig.fecEnabled
        stereoSwitch.isChecked = restoredConfig.channelCount == OpusStreamingConfig.STEREO_CHANNEL_COUNT
        refreshStereoSupportUi()
        suppressOpusUiEvents = false
    }

    private fun applyOpusPreset(preset: OpusPreset, sharedPreferences: android.content.SharedPreferences) {
        suppressOpusUiEvents = true
        val config = preset.config
        opusBitrateSeekBar.progress = (config.bitrateBps - OpusStreamingConfig.MIN_BITRATE_BPS) / 1000
        opusBitrateValueText.text = getString(R.string.opus_bitrate_value_format, config.bitrateBps / 1000)
        opusComplexitySeekBar.progress = config.complexity
        opusComplexityValueText.text = getString(R.string.opus_complexity_value_format, config.complexity)
        opusPacketLossSeekBar.progress = config.expectedPacketLossPercent
        opusPacketLossValueText.text = getString(R.string.opus_packet_loss_value_format, config.expectedPacketLossPercent)
        val frameIndex = opusFrameDurationOptions.indexOf(config.frameDuration).takeIf { it >= 0 } ?: 0
        opusFrameDurationSpinner.setSelection(frameIndex, false)
        opusFecSwitch.isChecked = config.fecEnabled
        stereoSwitch.isChecked = config.channelCount == OpusStreamingConfig.STEREO_CHANNEL_COUNT
        refreshStereoSupportUi()
        suppressOpusUiEvents = false
        sharedPreferences.edit().putString(PREF_KEY_OPUS_PRESET, preset.name).apply()
    }

    private fun buildCurrentOpusConfig(): OpusStreamingConfig {
        val rawBitrateBps = (opusBitrateSeekBar.progress + (OpusStreamingConfig.MIN_BITRATE_BPS / 1000)) * 1000
        val complexity = opusComplexitySeekBar.progress
        val frameDuration = opusFrameDurationOptions.getOrNull(opusFrameDurationSpinner.selectedItemPosition)
            ?: OpusStreamingConfig.DEFAULT.frameDuration
        val expectedPacketLoss = opusPacketLossSeekBar.progress
        val fecEnabled = opusFecSwitch.isChecked
        val channelCount = if (stereoSwitch.isChecked) OpusStreamingConfig.STEREO_CHANNEL_COUNT else OpusStreamingConfig.MONO_CHANNEL_COUNT
        val bitrateBps = if (channelCount == OpusStreamingConfig.STEREO_CHANNEL_COUNT) {
            rawBitrateBps.coerceAtLeast(96_000)
        } else {
            rawBitrateBps
        }
        return OpusStreamingConfig.sanitize(
            bitrateBps = bitrateBps,
            complexity = complexity,
            frameDurationMs = frameDuration.millis,
            fecEnabled = fecEnabled,
            expectedPacketLossPercent = expectedPacketLoss,
            channelCount = channelCount
        )
    }

    private fun pushCurrentOpusConfigToService(sharedPreferences: android.content.SharedPreferences) {
        val config = buildCurrentOpusConfig()
        sharedPreferences.edit()
            .putInt(PREF_KEY_OPUS_BITRATE_BPS, config.bitrateBps)
            .putInt(PREF_KEY_OPUS_COMPLEXITY, config.complexity)
            .putInt(PREF_KEY_OPUS_FRAME_DURATION_MS, config.frameDuration.millis)
            .putBoolean(PREF_KEY_OPUS_FEC_ENABLED, config.fecEnabled)
            .putInt(PREF_KEY_OPUS_EXPECTED_PACKET_LOSS_PERCENT, config.expectedPacketLossPercent)
            .putInt(PREF_KEY_OPUS_CHANNEL_COUNT, config.channelCount)
            .apply()

        startServiceSafely(AudioStreamingService.setOpusConfigIntent(this, config), "setOpusConfig")
    }


    private fun refreshStereoSupportUi() {
        val selectedOption = microphoneOptions.getOrNull(microphoneSpinner.selectedItemPosition)
        val stereoSupported = selectedOption?.supportsStereo == true
        val unsupportedByPlatform = Build.VERSION.SDK_INT < Build.VERSION_CODES.M

        stereoSwitch.isEnabled = stereoSupported
        if (!stereoSupported) {
            suppressOpusUiEvents = true
            stereoSwitch.isChecked = false
            suppressOpusUiEvents = false
        }

        stereoSupportText.text = when {
            unsupportedByPlatform -> getString(R.string.stereo_status_not_supported_platform)
            stereoSupported -> getString(R.string.stereo_status_supported)
            else -> getString(R.string.stereo_status_not_supported)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "MainActivity.onStart: registering status receiver")
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(AudioStreamingService.ACTION_STATUS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startServiceSafely(AudioStreamingService.statusQueryIntent(this), "queryStatus")
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun startServiceSafely(intent: Intent, reason: String): Boolean {
        return try {
            startService(intent)
            true
        } catch (exception: Exception) {
            Log.e(TAG, "startService failed for $reason (action=${intent.action})", exception)
            false
        }
    }

    private fun startForegroundServiceSafely(intent: Intent, reason: String): Boolean {
        return try {
            startForegroundService(intent)
            true
        } catch (exception: Exception) {
            Log.e(TAG, "startForegroundService failed for $reason (action=${intent.action})", exception)
            false
        }
    }

    private fun refreshMicrophoneList(preferredDeviceId: Int?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            microphoneOptions = listOf(MicrophoneOption.defaultOption(this))
            bindMicrophoneOptions(preferredDeviceId)
            micRoutingStatusText.text = getString(R.string.mic_unsupported_message)
            refreshStereoSupportUi()
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
                    direction = inferDirection(info.type),
                    supportsStereo = info.channelCounts.any { it >= OpusStreamingConfig.STEREO_CHANNEL_COUNT }
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
        refreshStereoSupportUi()
    }

    private fun updateStatusViews(
        isStreaming: Boolean,
        packetsPerSecond: Int,
        dspSummary: String,
        activeMic: String,
        routingWarning: String?,
        clippingWarning: Boolean
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
        micGainClippingWarningText.text = if (clippingWarning) {
            getString(R.string.mic_gain_clipping_warning)
        } else {
            getString(R.string.mic_gain_clipping_placeholder)
        }
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
        val direction: Int,
        val supportsStereo: Boolean
    ) {
        companion object {
            fun defaultOption(context: Context): MicrophoneOption {
                return MicrophoneOption(
                    deviceId = DEVICE_ID_DEFAULT,
                    displayName = context.getString(R.string.default_microphone_label),
                    direction = AudioRecord.MIC_DIRECTION_UNSPECIFIED,
                    supportsStereo = false
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
        private const val PREF_KEY_MIC_GAIN_PERCENT = "mic_gain"
        private const val PREF_KEY_OPUS_PRESET = "opus_preset"
        private const val PREF_KEY_OPUS_BITRATE_BPS = "opus_bitrate_bps"
        private const val PREF_KEY_OPUS_COMPLEXITY = "opus_complexity"
        private const val PREF_KEY_OPUS_FRAME_DURATION_MS = "opus_frame_duration_ms"
        private const val PREF_KEY_OPUS_FEC_ENABLED = "opus_fec_enabled"
        private const val PREF_KEY_OPUS_EXPECTED_PACKET_LOSS_PERCENT = "opus_expected_packet_loss_percent"
        private const val PREF_KEY_OPUS_CHANNEL_COUNT = "opus_channel_count"
        private const val DEFAULT_PORT = 5555
        private const val MIN_MIC_GAIN_PERCENT = 100
        private const val MAX_MIC_GAIN_PERCENT = 800
        private const val DEFAULT_MIC_GAIN_PERCENT = 200
        private const val MIC_GAIN_PERCENT_SCALE = 100f
        private const val DEVICE_ID_DEFAULT = -1
        private const val TAG = "MainActivity"
    }
}
