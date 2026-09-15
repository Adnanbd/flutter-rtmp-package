package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.FrameSize
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length
import kotlin.math.floor
import kotlin.math.max

/** What the controller needs to know about ticker content (measured natively, pure data). */
data class TickerMetrics(
    /** Flattened text; a change resets the scroll. */
    val text: String,
    val textWidthPx: Float,
    val bandHeightPx: Int,
    val speedPxPerSec: Float?,
    val cycleDurationMs: Long?,
    val loop: Boolean,
    val loopGap: Length?,
    /** True = text moves right→left (entering from the right edge). */
    val movesLeft: Boolean
)

/**
 * Ticker scroll geometry (docs/specs/dynamic-overlays.md §6). Pure Kotlin.
 *
 * `distance` = px scrolled since the first pass started. Copy `k` enters at distance `k × period` and has fully
 * left at `passLength + k × period`. A non-looping ticker has only copy 0.
 */
object TickerMath {
    const val DEFAULT_SPEED_PX_PER_SEC = 120f
    const val DEFAULT_GAP_PERCENT_OF_BAND = 33f

    data class Layout(val bandWidthPx: Float, val textWidthPx: Float, val gapPx: Float, val speedPxPerSec: Float) {
        val passLengthPx: Float get() = textWidthPx + bandWidthPx
        val periodPx: Float get() = max(1f, textWidthPx + gapPx)
    }

    /** The band width is the placement `width`, default 100 % of the frame. */
    fun bandOf(placement: OverlayGeometry.Placement): Length = placement.width ?: Length.Percent(100f)

    fun layout(m: TickerMetrics, placement: OverlayGeometry.Placement, frame: FrameSize): Layout {
        val band = bandWidthPx(bandOf(placement), frame)
        val gap = when (val g = m.loopGap) {
            null -> band * DEFAULT_GAP_PERCENT_OF_BAND / 100f
            is Length.Percent -> band * g.value / 100f
            is Length.Px -> g.value
        }
        val pass = m.textWidthPx + band
        val speed = m.speedPxPerSec
            ?: m.cycleDurationMs?.let { pass / (it / 1000f) }
            ?: DEFAULT_SPEED_PX_PER_SEC
        return Layout(band, m.textWidthPx, gap, speed)
    }

    fun bandWidthPx(band: Length, frame: FrameSize): Float =
        (band.toPercent(frame.width).coerceIn(0f, 100f) / 100f * frame.width).coerceAtLeast(1f)

    /** Index of the newest copy that has entered at [distance]. */
    fun newestCopy(distance: Float, l: Layout): Int = max(0, floor(distance / l.periodPx).toInt())

    /** Copies to draw at [distance]; [lastCopy] caps spawning (non-loop = 0, finishing after expiry = the copy then). */
    fun visibleCopies(distance: Float, l: Layout, lastCopy: Int?): IntRange {
        val first = max(0, floor((distance - l.passLengthPx) / l.periodPx).toInt() + 1)
        var last = newestCopy(distance, l)
        if (lastCopy != null) last = minOf(last, lastCopy)
        return first..last
    }

    /** True once copy [lastCopy] has fully left the band. */
    fun isFinished(distance: Float, l: Layout, lastCopy: Int): Boolean =
        distance >= l.passLengthPx + lastCopy * l.periodPx

    /** Band-local x of copy [k]'s left edge. */
    fun copyX(k: Int, distance: Float, l: Layout, movesLeft: Boolean): Float =
        if (movesLeft) l.bandWidthPx - distance + k * l.periodPx
        else -l.textWidthPx + distance - k * l.periodPx
}

/** Scrolled distance that advances only while running; speed may change mid-scroll. */
class ScrollProgress {
    private var basePx = 0f
    private var runningSinceMs: Long? = null
    private var speedPxPerSec = 0f

    val isRunning: Boolean get() = runningSinceMs != null

    fun distance(now: Long): Float =
        basePx + (runningSinceMs?.let { (now - it) * speedPxPerSec / 1000f } ?: 0f)

    fun setRunning(running: Boolean, now: Long) {
        if (running == isRunning) return
        if (running) {
            runningSinceMs = now
        } else {
            basePx = distance(now)
            runningSinceMs = null
        }
    }

    fun setSpeed(speed: Float, now: Long) {
        if (speed == speedPxPerSec) return
        basePx = distance(now)
        if (isRunning) runningSinceMs = now
        speedPxPerSec = speed
    }

    fun reset(now: Long) {
        basePx = 0f
        if (isRunning) runningSinceMs = now
    }

    /** Keep relative position when the pass length changes (style-only update). */
    fun rescale(ratio: Float, now: Long) {
        basePx = distance(now) * ratio
        if (isRunning) runningSinceMs = now
    }
}
