package com.flutterrtmp.broadcaster.overlay

/**
 * Carousel item selection over a looping wall clock (docs/specs/dynamic-overlays.md §12). Pure Kotlin.
 *
 * Item `i` owns a slot of `intervalsMs[i]`. The transition to the next item takes the last [transitionMs] of that
 * slot. Each item has its own clock that starts when it begins to appear (at the transition into it), so a GIF item
 * never jumps when its slot starts.
 */
class CarouselTimeline(intervalsMs: LongArray, transitionMs: Long) {

    /** What to draw at one instant. [next] and [progress] are set only during a transition (linear 0..1). */
    data class State(
        val index: Int,
        val itemTimeMs: Long,
        val next: Int?,
        val nextItemTimeMs: Long,
        val progress: Float?
    )

    private val intervals = intervalsMs.copyOf()
    private val starts = LongArray(intervals.size).also { acc ->
        var sum = 0L
        for (i in intervals.indices) { acc[i] = sum; sum += intervals[i] }
    }

    val itemCount: Int get() = intervals.size
    val cycleMs: Long = intervals.sum()

    /** Effective transition length: none for a single item. */
    val transitionMs: Long = if (intervals.size > 1) transitionMs.coerceAtLeast(0L) else 0L

    init {
        require(intervals.isNotEmpty()) { "carousel needs at least one item" }
        require(intervals.all { it > 0L }) { "intervals must be > 0" }
        require(this.transitionMs == 0L || intervals.all { it > this.transitionMs }) {
            "transition must be shorter than every interval"
        }
    }

    fun slotStartMs(index: Int): Long = starts[index]

    fun at(timeMs: Long): State {
        if (intervals.size == 1) return State(0, timeMs.coerceAtLeast(0L), null, 0L, null)
        val t = timeMs.coerceAtLeast(0L).mod(cycleMs)
        val i = indexAt(t)
        val local = t - starts[i]
        // Every slot's item has already been appearing for one transition when its slot starts.
        val itemTime = local + transitionMs
        val transitionStart = intervals[i] - transitionMs
        if (transitionMs > 0L && local >= transitionStart) {
            val into = local - transitionStart
            return State(i, itemTime, (i + 1) % intervals.size, into, into.toFloat() / transitionMs.toFloat())
        }
        return State(i, itemTime, null, 0L, null)
    }

    fun inTransition(timeMs: Long): Boolean = at(timeMs).progress != null

    /**
     * Ms from [timeMs] until the drawn content next changes by itself: the next transition start, or the next cut.
     * Null for a single item. 0 while a transition is running (frames are needed now).
     */
    fun msUntilNextChange(timeMs: Long): Long? {
        if (intervals.size == 1) return null
        val t = timeMs.coerceAtLeast(0L).mod(cycleMs)
        val i = indexAt(t)
        val local = t - starts[i]
        val changeAt = intervals[i] - transitionMs
        return if (local >= changeAt) 0L else changeAt - local
    }

    private fun indexAt(t: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }
}
