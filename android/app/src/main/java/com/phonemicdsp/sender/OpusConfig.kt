package com.phonemicdsp.sender

enum class OpusFrameDuration(val millis: Int, val samplesPerFrame: Int) {
    MS_10(10, 480),
    MS_20(20, 960);

    companion object {
        fun fromMillis(value: Int): OpusFrameDuration = entries.firstOrNull { it.millis == value } ?: MS_20
    }
}

data class OpusStreamingConfig(
    val bitrateBps: Int,
    val complexity: Int,
    val frameDuration: OpusFrameDuration,
    val fecEnabled: Boolean,
    val expectedPacketLossPercent: Int
) {
    fun summary(): String {
        return "Opus: ${bitrateBps / 1000}kbps, complexity=$complexity, frame=${frameDuration.millis}ms, fec=${if (fecEnabled) "on" else "off"}, loss=$expectedPacketLossPercent%"
    }

    companion object {
        const val MIN_BITRATE_BPS = 32_000
        const val MAX_BITRATE_BPS = 128_000
        const val MIN_COMPLEXITY = 0
        const val MAX_COMPLEXITY = 10
        const val MIN_EXPECTED_PACKET_LOSS_PERCENT = 0
        const val MAX_EXPECTED_PACKET_LOSS_PERCENT = 20

        val DEFAULT = OpusPreset.VOICE_CLEAN.config

        fun sanitize(
            bitrateBps: Int,
            complexity: Int,
            frameDurationMs: Int,
            fecEnabled: Boolean,
            expectedPacketLossPercent: Int
        ): OpusStreamingConfig {
            return OpusStreamingConfig(
                bitrateBps = bitrateBps.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS),
                complexity = complexity.coerceIn(MIN_COMPLEXITY, MAX_COMPLEXITY),
                frameDuration = OpusFrameDuration.fromMillis(frameDurationMs),
                fecEnabled = fecEnabled,
                expectedPacketLossPercent = expectedPacketLossPercent.coerceIn(
                    MIN_EXPECTED_PACKET_LOSS_PERCENT,
                    MAX_EXPECTED_PACKET_LOSS_PERCENT
                )
            )
        }
    }
}

enum class OpusPreset(val displayNameResId: Int, val config: OpusStreamingConfig) {
    VOICE_CLEAN(
        R.string.opus_preset_voice_clean,
        OpusStreamingConfig(
            bitrateBps = 48_000,
            complexity = 8,
            frameDuration = OpusFrameDuration.MS_20,
            fecEnabled = true,
            expectedPacketLossPercent = 5
        )
    ),
    HIGH_QUALITY_VOICE(
        R.string.opus_preset_high_quality,
        OpusStreamingConfig(
            bitrateBps = 96_000,
            complexity = 10,
            frameDuration = OpusFrameDuration.MS_20,
            fecEnabled = true,
            expectedPacketLossPercent = 5
        )
    ),
    LOW_LATENCY(
        R.string.opus_preset_low_latency,
        OpusStreamingConfig(
            bitrateBps = 40_000,
            complexity = 6,
            frameDuration = OpusFrameDuration.MS_10,
            fecEnabled = false,
            expectedPacketLossPercent = 0
        )
    );

    companion object {
        fun fromStored(value: String?): OpusPreset = entries.firstOrNull { it.name == value } ?: VOICE_CLEAN
    }
}
