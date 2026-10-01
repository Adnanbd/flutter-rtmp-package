package com.flutterrtmp.broadcaster.audio

import com.pedro.encoder.input.audio.CustomAudioEffect

/**
 * Runs [AudioCleanupChain] on RootEncoder's phone-mic reads (`MicrophoneSource.setAudioEffect`).
 * [AudioCleanupChain.process] never throws, so RootEncoder's mic thread can't die here.
 */
class CleanupAudioEffect(private val chain: AudioCleanupChain) : CustomAudioEffect() {
    override fun process(pcmBuffer: ByteArray): ByteArray {
        chain.process(pcmBuffer, pcmBuffer.size)
        return pcmBuffer
    }
}
