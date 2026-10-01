package com.flutterrtmp.broadcaster.audio

/** Frame-based speech denoiser (RNNoise in production, fakes in JVM tests). */
interface Denoiser {
    val frameSize: Int

    /** Denoises [frame] (int16-scale floats, [frameSize] long) in place; returns voice probability 0..1. */
    fun process(frame: FloatArray): Float

    fun close()
}

/**
 * Turns a frame-based [Denoiser] into a per-sample one with exactly [Denoiser.frameSize] samples of latency
 * (10 ms for RNNoise at 48 kHz), so the chain's output length always equals its input length.
 */
class FrameDenoiser(private val denoiser: Denoiser) {
    private val size = denoiser.frameSize
    private val input = FloatArray(size)
    private var output = FloatArray(size)
    private var pos = 0

    /** Voice probability of the last processed frame. */
    var vad = 0f
        private set

    fun push(x: Float): Float {
        input[pos] = x
        val y = output[pos]
        if (++pos == size) {
            val work = input.copyOf()
            vad = denoiser.process(work)
            output = work
            pos = 0
        }
        return y
    }

    fun close() = denoiser.close()
}
