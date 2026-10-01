package com.flutterrtmp.broadcaster.usb

/**
 * Detects a USB audio input that stopped delivering PCM (ADR 0023). Pure: injected clock, JVM-tested.
 *
 * Stalled when, after [graceMs] from [start]:
 * - no bytes for [noDataMs], or
 * - less than [minRatio] of the expected bytes arrived over the last [windowMs].
 * [onBytes] runs on the audio thread and [check] on the main thread, so both are synchronized.
 */
class AudioStallDetector(
    private val clock: () -> Long,
    private val expectedBytesPerSec: Int,
    private val noDataMs: Long = 1_500,
    private val windowMs: Long = 3_000,
    private val minRatio: Double = 0.5,
    private val graceMs: Long = 1_000
) {
    private var startedAt = -1L
    private var lastDataAt = -1L
    private val window = ArrayDeque<Pair<Long, Int>>()

    @Synchronized
    fun start() {
        startedAt = clock()
        lastDataAt = -1L
        window.clear()
    }

    @Synchronized
    fun stop() {
        startedAt = -1L
        window.clear()
    }

    @Synchronized
    fun onBytes(n: Int) {
        if (startedAt < 0 || n <= 0) return
        val now = clock()
        lastDataAt = now
        window.addLast(now to n)
        trim(now)
    }

    /** Null while healthy, else a short reason. */
    @Synchronized
    fun check(): String? {
        if (startedAt < 0) return null
        val now = clock()
        val age = now - startedAt
        if (age < graceMs) return null
        val since = now - (if (lastDataAt >= 0) lastDataAt else startedAt)
        if (since >= noDataMs) return "no PCM for ${since} ms"
        if (age >= graceMs + windowMs) {
            trim(now)
            val got = window.sumOf { it.second.toLong() }
            val expected = expectedBytesPerSec.toLong() * windowMs / 1000
            if (got < expected * minRatio) return "only ${got * 100 / expected}% of expected PCM in ${windowMs} ms"
        }
        return null
    }

    private fun trim(now: Long) {
        while (window.isNotEmpty() && now - window.first().first > windowMs) window.removeFirst()
    }
}
