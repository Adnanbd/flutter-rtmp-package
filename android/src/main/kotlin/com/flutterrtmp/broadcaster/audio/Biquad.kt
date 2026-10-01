package com.flutterrtmp.broadcaster.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RBJ-cookbook biquad, transposed direct form II. One instance per channel. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    companion object {
        fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.7071): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun notch(sampleRate: Int, centerHz: Double, q: Double): Biquad {
            val w0 = 2 * PI * centerHz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad(1 / a0, -2 * c / a0, 1 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
