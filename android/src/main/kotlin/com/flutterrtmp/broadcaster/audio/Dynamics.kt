package com.flutterrtmp.broadcaster.audio

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Full scale for 16-bit PCM; every level in this package is in int16 units. */
internal const val FULL_SCALE = 32768f

internal fun dbToLinear(db: Double): Float = (10.0.pow(db / 20.0)).toFloat()

/** One-pole smoothing coefficient for a time constant at a sample rate. */
internal fun coef(timeMs: Double, sampleRate: Int): Float =
    if (timeMs <= 0) 0f else exp(-1.0 / (timeMs / 1000.0 * sampleRate)).toFloat()

/**
 * Linked noise gate / downward expander. Opens when the peak envelope passes [thresholdDb] or the denoiser's voice
 * activity is ≥ 0.5; holds, then closes to [floorDb] (−20 dB by default, not full mute, so room tone doesn't pump).
 */
class NoiseGate(
    sampleRate: Int,
    thresholdDb: Double = -50.0,
    floorDb: Double = -20.0,
    attackMs: Double = 10.0,
    holdMs: Double = 200.0,
    releaseMs: Double = 150.0
) {
    private val threshold = dbToLinear(thresholdDb) * FULL_SCALE
    private val floor = dbToLinear(floorDb)
    private val envAttack = coef(1.0, sampleRate)
    private val envRelease = coef(50.0, sampleRate)
    private val gainAttack = coef(attackMs, sampleRate)
    private val gainRelease = coef(releaseMs, sampleRate)
    private val holdSamples = (holdMs / 1000.0 * sampleRate).toInt()
    private var env = 0f
    private var hold = 0
    private var gain = floor

    var isOpen = false
        private set

    /** [level] = max |sample| across channels; [vad] = voice probability or null without a denoiser. */
    fun next(level: Float, vad: Float?): Float {
        env = if (level > env) envAttack * env + (1 - envAttack) * level else envRelease * env + (1 - envRelease) * level
        val trigger = env > threshold || (vad != null && vad >= 0.5f)
        if (trigger) hold = holdSamples else if (hold > 0) hold--
        isOpen = trigger || hold > 0
        val target = if (isOpen) 1f else floor
        val c = if (target > gain) gainAttack else gainRelease
        gain = c * gain + (1 - c) * target
        return gain
    }
}

/**
 * Slow automatic gain toward [targetDb] RMS. Measures only while the gate is open (speech), so pauses don't pump the
 * noise up. Range [minDb]..[maxDb]; reduces fast ([attackMs]), raises slowly ([releaseMs]).
 */
class Agc(
    sampleRate: Int,
    targetDb: Double = -20.0,
    private val minDb: Double = -6.0,
    private val maxDb: Double = 24.0,
    attackMs: Double = 50.0,
    releaseMs: Double = 1000.0,
    rmsWindowMs: Double = 300.0
) {
    private val target = dbToLinear(targetDb) * FULL_SCALE
    private val minGain = dbToLinear(minDb)
    private val maxGain = dbToLinear(maxDb)
    private val msCoef = coef(rmsWindowMs, sampleRate)
    private val down = coef(attackMs, sampleRate)
    private val up = coef(releaseMs, sampleRate)
    private var meanSquare = 0f
    var gain = 1f
        private set

    fun next(level: Float, measuring: Boolean): Float {
        if (measuring) {
            meanSquare = msCoef * meanSquare + (1 - msCoef) * level * level
            val rms = sqrt(meanSquare)
            if (rms > 1f) {
                val desired = (target / rms).coerceIn(minGain, maxGain)
                val c = if (desired < gain) down else up
                gain = c * gain + (1 - c) * desired
            }
        }
        return gain
    }
}

/** Peak limiter at [ceilingDb]: instant gain reduction, [releaseMs] recovery. Linked across channels. */
class Limiter(sampleRate: Int, ceilingDb: Double = -1.0, releaseMs: Double = 50.0) {
    private val ceiling = dbToLinear(ceilingDb) * FULL_SCALE
    private val release = coef(releaseMs, sampleRate)
    private var gain = 1f

    fun next(peak: Float): Float {
        gain = release * gain + (1 - release) * 1f
        if (peak * gain > ceiling) gain = ceiling / peak
        return gain
    }
}
