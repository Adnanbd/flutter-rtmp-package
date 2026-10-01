package com.flutterrtmp.broadcaster.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Measures mains hum vs. broadband noise from the log alone (docs/specs/audio-cleanup.md). Goertzel power at
 * [hz] over 100 ms blocks of one channel, counted only for blocks that were entirely noise (gate closed).
 */
class HumProbe(private val sampleRate: Int, private val hz: DoubleArray = doubleArrayOf(50.0, 100.0, 150.0)) {
    private val blockSize = sampleRate / 10
    private val coeffs = DoubleArray(hz.size) { 2 * cos(2 * PI * hz[it] / sampleRate) }
    private val s1 = DoubleArray(hz.size)
    private val s2 = DoubleArray(hz.size)
    private var n = 0
    private var sumSq = 0.0
    private var blockQuiet = true

    private val humPower = DoubleArray(hz.size)
    private var totalPower = 0.0
    private var blocks = 0

    fun next(x: Float, quiet: Boolean) {
        if (!quiet) blockQuiet = false
        for (k in hz.indices) {
            val s = x + coeffs[k] * s1[k] - s2[k]
            s2[k] = s1[k]
            s1[k] = s
        }
        sumSq += x.toDouble() * x
        if (++n == blockSize) endBlock()
    }

    private fun endBlock() {
        if (blockQuiet) {
            for (k in hz.indices) {
                val mag2 = s1[k] * s1[k] + s2[k] * s2[k] - coeffs[k] * s1[k] * s2[k]
                // Sine amplitude A = 2|X|/N; mean power of that sine = A²/2.
                humPower[k] += 2 * mag2 / (blockSize.toDouble() * blockSize)
            }
            totalPower += sumSq / blockSize
            blocks++
        }
        s1.fill(0.0); s2.fill(0.0); n = 0; sumSq = 0.0; blockQuiet = true
    }

    /** `hum50=…dBFS hum100=… hum150=… noise=…dBFS humShare=…% blocks=…`, then reset. Null when no quiet block. */
    fun summarize(): String? {
        if (blocks == 0) return null
        val parts = hz.indices.joinToString(" ") { "hum${hz[it].toInt()}=${dbfs(humPower[it] / blocks)}dBFS" }
        val total = totalPower / blocks
        val share = if (total > 0) (humPower.sum() / blocks / total * 100).coerceAtMost(100.0) else 0.0
        val line = "$parts noise=${dbfs(total)}dBFS humShare=${share.toInt()}% blocks=$blocks"
        humPower.fill(0.0); totalPower = 0.0; blocks = 0
        return line
    }

    private fun dbfs(power: Double): String =
        if (power <= 0) "-120" else "%.1f".format(java.util.Locale.US, 10 * log10(power) - 20 * log10(FULL_SCALE.toDouble()))
}
