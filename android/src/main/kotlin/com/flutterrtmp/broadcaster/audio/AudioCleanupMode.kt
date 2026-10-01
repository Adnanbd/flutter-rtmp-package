package com.flutterrtmp.broadcaster.audio

/** Wire values of `audioCleanup` / `setAudioCleanup` (docs/specs/audio-cleanup.md). */
enum class AudioCleanupMode(val wire: String) {
    /** Raw mic audio, no processing. */
    OFF("off"),

    /** High-pass + 50 Hz hum notches + gate + gain + limiter (pure Kotlin). */
    BASIC("basic"),

    /** [BASIC] plus RNNoise speech denoising (48 kHz only). */
    VOICE("voice");

    companion object {
        fun fromWire(value: String?): AudioCleanupMode? = entries.firstOrNull { it.wire == value }
    }
}
