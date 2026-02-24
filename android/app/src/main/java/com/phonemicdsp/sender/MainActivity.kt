package com.phonemicdsp.sender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var pcIpInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var streamingStatusText: TextView
    private lateinit var packetRateText: TextView
    private lateinit var dspStatusText: TextView

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

            updateStatusViews(isStreaming, packetsPerSecond, dspSummary)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pcIpInput = findViewById(R.id.pcIpInput)
        portInput = findViewById(R.id.portInput)
        streamingStatusText = findViewById(R.id.streamingStatusText)
        packetRateText = findViewById(R.id.packetRateText)
        dspStatusText = findViewById(R.id.dspStatusText)

        val startButton = findViewById<Button>(R.id.startServiceButton)
        val stopButton = findViewById<Button>(R.id.stopServiceButton)

        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pcIpInput.setText(sharedPreferences.getString(PREF_KEY_PC_IP, ""))
        portInput.setText(sharedPreferences.getInt(PREF_KEY_PORT, DEFAULT_PORT).toString())

        updateStatusViews(false, 0, getString(R.string.dsp_status_placeholder))

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

    private fun updateStatusViews(isStreaming: Boolean, packetsPerSecond: Int, dspSummary: String) {
        streamingStatusText.text = if (isStreaming) {
            getString(R.string.streaming_on)
        } else {
            getString(R.string.streaming_off)
        }

        packetRateText.text = getString(R.string.packets_per_second_format, packetsPerSecond)
        dspStatusText.text = getString(R.string.dsp_status_format, dspSummary)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val PREFS_NAME = "phone_mic_sender_prefs"
        private const val PREF_KEY_PC_IP = "pc_ip"
        private const val PREF_KEY_PORT = "port"
        private const val DEFAULT_PORT = 5555
    }
}
