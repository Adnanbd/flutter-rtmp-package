package com.flutterrtmp.broadcaster.overlay

/**
 * Looping GIF frame selection by per-frame delay. Pure Kotlin.
 * Delays ≤ 10 ms are treated as 100 ms, like browsers do for "as fast as possible" GIFs.
 */
class GifTimeline(rawDelaysMs: IntArray) {
    companion object {
        const val MIN_DELAY_MS = 20
        const val DEFAULT_DELAY_MS = 100
    }

    private val delays = IntArray(rawDelaysMs.size) { i ->
        val d = rawDelaysMs[i]
        if (d <= 10) DEFAULT_DELAY_MS else maxOf(MIN_DELAY_MS, d)
    }
    private val ends = LongArray(delays.size).also { acc ->
        var sum = 0L
        for (i in delays.indices) { sum += delays[i]; acc[i] = sum }
    }

    val frameCount: Int get() = delays.size
    val loopMs: Long get() = if (ends.isEmpty()) 0L else ends.last()

    fun frameAt(timeMs: Long): Int {
        if (delays.size <= 1) return 0
        val t = timeMs.mod(loopMs)
        var lo = 0
        var hi = ends.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (t < ends[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }
}
