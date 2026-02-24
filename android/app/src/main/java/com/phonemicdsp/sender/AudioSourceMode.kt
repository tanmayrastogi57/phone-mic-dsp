package com.phonemicdsp.sender

import android.media.MediaRecorder
import android.os.Build

enum class AudioSourceMode(
    val audioSource: Int,
    val minSdk: Int
) {
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Build.VERSION_CODES.HONEYCOMB),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, Build.VERSION_CODES.FROYO),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, Build.VERSION_CODES.BASE);

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= minSdk

    companion object {
        fun fromStored(value: String?): AudioSourceMode {
            return entries.firstOrNull { it.name == value && it.isSupported() } ?: VOICE_COMMUNICATION
        }

        fun supportedModes(): List<AudioSourceMode> = entries.filter { it.isSupported() }
    }
}
