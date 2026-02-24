package com.phonemicdsp.sender

import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.startServiceButton)
        val stopButton = findViewById<Button>(R.id.stopServiceButton)

        startButton.setOnClickListener {
            val startIntent = AudioStreamingService.startIntent(this)
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
}
