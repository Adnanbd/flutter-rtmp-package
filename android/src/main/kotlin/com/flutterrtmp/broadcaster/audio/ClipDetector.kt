package com.flutterrtmp.broadcaster.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Finds a mic input that is clipped before it reaches us (docs/specs/audio-cleanup.md): a hot mixer or capture-card
 * ADC flattens loud syllables at a fixed ceiling, which sounds like crackle and no filter can undo.
 *
 * Feed one channel. A clipped wave sits on the same value for several samples in a row; a real wave is curved at its
 * top (a 200 Hz peak at 48 kHz moves ~4 LSB per sample). So we count samples in runs of ≥ [MIN_RUN] consecutive
 * samples within [TOLERANCE_LSB] of the window peak (the approach of Audacity's "Find Clipping"), and call it clipping
 * when they exceed [MIN_RATIO] of the window and the peak is above [MIN_CEILING_DBFS].
 */
class ClipDetector {
    data class Result(val clipping: Boolean, val ratio: Double, val ceilingDbfs: Double, val samples: Long, val flat: Long)

    private var peak = 0f
    private var run = 0
    private var flat = 0L
    private var samples = 0L

    fun next(x: Float) {
        samples++
        val a = abs(x)
        if (a > peak + TOLERANCE_LSB) {
            // A clearly higher peak: flat runs at the old, lower level weren't at the ceiling.
            peak = a
            flat = 0
            run = 1
            return
        }
        if (a > peak) peak = a
        if (a >= peak - TOLERANCE_LSB) {
            run++
            if (run == MIN_RUN) flat += MIN_RUN.toLong() else if (run > MIN_RUN) flat++
        } else {
            run = 0
        }
    }

    /** Result for the window since the last call, then reset. */
    fun summarize(): Result {
        val ratio = if (samples > 0) flat.toDouble() / samples else 0.0
        val ceiling = if (peak > 0) 20 * log10(peak / FULL_SCALE.toDouble()) else -120.0
        val result = Result(ratio >= MIN_RATIO && ceiling >= MIN_CEILING_DBFS, ratio, ceiling, samples, flat)
        peak = 0f; run = 0; flat = 0; samples = 0
        return result
    }

    companion object {
        const val TOLERANCE_LSB = 1.5f
        const val MIN_RUN = 3
        /** 0.05 % of samples: ~120 per channel per 5 s at 48 kHz. */
        const val MIN_RATIO = 0.0005
        const val MIN_CEILING_DBFS = -20.0

        /** Combines per-channel results: the worst channel decides. */
        fun merge(results: List<Result>): Result = results.maxByOrNull { it.ratio } ?: Result(false, 0.0, -120.0, 0, 0)
    }
}
