package com.flutterrtmp.broadcaster.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val SR = 48_000

internal fun sine(hz: Double, dbfs: Double, seconds: Double, phase: Double = 0.0): FloatArray {
    val amp = 32768.0 * Math.pow(10.0, dbfs / 20.0)
    return FloatArray((SR * seconds).toInt()) { (amp * sin(2 * PI * hz * it / SR + phase)).toFloat() }
}

internal fun whiteNoise(dbfs: Double, seconds: Double, seed: Int = 1): FloatArray {
    val r = Random(seed)
    val rms = 32768.0 * Math.pow(10.0, dbfs / 20.0)
    // Uniform in [-a, a] has RMS a/√3.
    val a = rms * sqrt(3.0)
    return FloatArray((SR * seconds).toInt()) { ((r.nextDouble() * 2 - 1) * a).toFloat() }
}

internal fun mix(vararg s: FloatArray): FloatArray = FloatArray(s.minOf { it.size }) { i -> s.sumOf { it[i].toDouble() }.toFloat() }

internal fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
    var s = 0.0
    for (i in from until to) s += x[i].toDouble() * x[i]
    return 20 * log10(sqrt(s / (to - from)) / 32768.0)
}

/** Interleaved stereo 16-bit PCM with the same signal in both channels. */
internal fun toStereoPcm(x: FloatArray): ByteArray {
    val out = ByteArray(x.size * 4)
    for (i in x.indices) {
        val s = x[i].toInt().coerceIn(-32768, 32767)
        for (c in 0..1) { out[i * 4 + c * 2] = (s and 0xFF).toByte(); out[i * 4 + c * 2 + 1] = (s shr 8).toByte() }
    }
    return out
}

internal fun leftChannel(pcm: ByteArray): FloatArray =
    FloatArray(pcm.size / 4) { i -> ((pcm[i * 4 + 1].toInt() shl 8) or (pcm[i * 4].toInt() and 0xFF)).toShort().toFloat() }

/** Passes audio through unchanged and reports a fixed voice probability. */
internal class FakeDenoiser(private val vad: Float = 0f) : Denoiser {
    var frames = 0
    var closed = false
    override val frameSize = 480
    override fun process(frame: FloatArray): Float { frames++; return vad }
    override fun close() { closed = true }
}
