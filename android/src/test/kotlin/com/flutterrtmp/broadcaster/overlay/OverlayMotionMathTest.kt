package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.FrameSize
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Length
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.Placement
import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.PostRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Animation easing/frames, ticker scroll geometry and GIF timeline. */
internal class OverlayMotionMathTest {

    private val eps = 1e-4f
    private val rect = PostRect(x = 60f, y = 10f, w = 30f, h = 20f)

    // ---- easing / frames ----

    @Test
    fun easings_hitEndpoints_andAreMonotonic() {
        for (e in Easing.values()) {
            assertEquals(0f, OverlayAnimationMath.ease(e, 0f), eps)
            assertEquals(1f, OverlayAnimationMath.ease(e, 1f), eps)
            var prev = -1f
            for (i in 0..100) {
                val v = OverlayAnimationMath.ease(e, i / 100f)
                assertTrue(v >= prev - eps, "$e not monotonic at $i")
                prev = v
            }
        }
        assertEquals(0.125f, OverlayAnimationMath.ease(Easing.EASE_IN, 0.5f), eps)
        assertEquals(0.875f, OverlayAnimationMath.ease(Easing.EASE_OUT, 0.5f), eps)
        assertEquals(0.5f, OverlayAnimationMath.ease(Easing.EASE_IN_OUT, 0.5f), eps)
        assertEquals(1f, OverlayAnimationMath.ease(Easing.LINEAR, 7f), eps)   // clamped
    }

    @Test
    fun visible_exitAppliesEasingToElapsedExitTime() {
        val spec = AnimationSpec(AnimationType.SLIDE, 400, Easing.EASE_IN)
        // Exit at progress 0.5 = half the exit elapsed → visible = 1 − easeIn(0.5).
        assertEquals(0.875f, OverlayAnimationMath.visible(spec, 0.5f, exiting = true), eps)
        assertEquals(0.125f, OverlayAnimationMath.visible(spec, 0.5f, exiting = false), eps)
        assertEquals(1f, OverlayAnimationMath.visible(spec, 1f, exiting = true), eps)
        assertEquals(0f, OverlayAnimationMath.visible(spec, 0f, exiting = true), eps)
    }

    @Test
    fun slide_startsFullyOutsideEachEdge_andEndsAtRect() {
        fun at(edge: Edge, v: Float) = OverlayAnimationMath.frame(rect, AnimationType.SLIDE, edge, v).rect
        assertEquals(rect.copy(x = -30f), at(Edge.LEFT, 0f))
        assertEquals(rect.copy(x = 100f), at(Edge.RIGHT, 0f))
        assertEquals(rect.copy(y = -20f), at(Edge.TOP, 0f))
        assertEquals(rect.copy(y = 100f), at(Edge.BOTTOM, 0f))
        for (edge in Edge.values()) assertEquals(rect, at(edge, 1f))
        assertEquals(80f, at(Edge.RIGHT, 0.5f).x, eps)
    }

    @Test
    fun pop_scalesAroundCenter() {
        val half = OverlayAnimationMath.frame(rect, AnimationType.POP, Edge.BOTTOM, 0.5f).rect
        assertEquals(PostRect(67.5f, 15f, 15f, 10f), half)
        val zero = OverlayAnimationMath.frame(rect, AnimationType.POP, Edge.BOTTOM, 0f).rect
        assertEquals(75f, zero.x, eps)
        assertEquals(20f, zero.y, eps)
        assertEquals(0f, zero.w, eps)
    }

    @Test
    fun curtain_keepsRect_andReveals() {
        val f = OverlayAnimationMath.frame(rect, AnimationType.CURTAIN, Edge.LEFT, 0.25f)
        assertEquals(rect, f.rect)
        assertEquals(0.25f, f.reveal, eps)
        assertEquals(1f, OverlayAnimationMath.frame(rect, AnimationType.NONE, Edge.LEFT, 0f).reveal, eps)
    }

    // ---- ticker ----

    private val frame = FrameSize(1000, 500)

    private fun metrics(
        textW: Float = 300f,
        speed: Float? = null,
        cycleMs: Long? = null,
        loop: Boolean = true,
        gap: Length? = null,
        movesLeft: Boolean = true
    ) = TickerMetrics("hello", textW, 50, speed, cycleMs, loop, gap, movesLeft)

    @Test
    fun layout_defaults_percentBand_gapAndCycle() {
        val l = TickerMath.layout(metrics(), Placement(), frame)
        assertEquals(1000f, l.bandWidthPx, eps)
        assertEquals(330f, l.gapPx, eps)                      // 33 % of band
        assertEquals(TickerMath.DEFAULT_SPEED_PX_PER_SEC, l.speedPxPerSec, eps)
        assertEquals(1300f, l.passLengthPx, eps)
        assertEquals(630f, l.periodPx, eps)

        val half = TickerMath.layout(metrics(gap = Length.Px(20f), cycleMs = 2000), Placement(width = Length.Percent(50f)), frame)
        assertEquals(500f, half.bandWidthPx, eps)
        assertEquals(20f, half.gapPx, eps)
        assertEquals(400f, half.speedPxPerSec, eps)            // (300 + 500) px / 2 s

        val pctGap = TickerMath.layout(metrics(gap = Length.Percent(10f), speed = 50f), Placement(width = Length.Px(400f)), frame)
        assertEquals(40f, pctGap.gapPx, eps)                   // percent of the band, not the frame
        assertEquals(50f, pctGap.speedPxPerSec, eps)
    }

    @Test
    fun copies_enterAndLeave_withPeriod() {
        val l = TickerMath.Layout(bandWidthPx = 1000f, textWidthPx = 300f, gapPx = 100f, speedPxPerSec = 100f)
        // period 400, pass 1300
        assertEquals(0..0, TickerMath.visibleCopies(0f, l, null))
        assertEquals(0..1, TickerMath.visibleCopies(400f, l, null))
        assertEquals(0..3, TickerMath.visibleCopies(1299f, l, null))
        assertEquals(1..3, TickerMath.visibleCopies(1300f, l, null))   // copy 0 fully gone
        assertEquals(0..0, TickerMath.visibleCopies(900f, l, lastCopy = 0))
        assertTrue(TickerMath.visibleCopies(1300f, l, lastCopy = 0).isEmpty())
        assertFalse(TickerMath.isFinished(1299f, l, 0))
        assertTrue(TickerMath.isFinished(1300f, l, 0))
        assertTrue(TickerMath.isFinished(1700f, l, 1))
        assertEquals(2, TickerMath.newestCopy(800f, l))
    }

    @Test
    fun copyX_bothDirections_passEdges() {
        val l = TickerMath.Layout(1000f, 300f, 100f, 100f)
        assertEquals(1000f, TickerMath.copyX(0, 0f, l, movesLeft = true), eps)     // leading edge at right edge
        assertEquals(-300f, TickerMath.copyX(0, 1300f, l, movesLeft = true), eps)  // trailing edge at left edge
        assertEquals(1400f, TickerMath.copyX(1, 0f, l, movesLeft = true), eps)
        assertEquals(-300f, TickerMath.copyX(0, 0f, l, movesLeft = false), eps)
        assertEquals(1000f, TickerMath.copyX(0, 1300f, l, movesLeft = false), eps)
        assertEquals(-700f, TickerMath.copyX(1, 0f, l, movesLeft = false), eps)
    }

    @Test
    fun scrollProgress_runsPausesChangesSpeedAndRescales() {
        val s = ScrollProgress()
        s.setSpeed(100f, 0)
        assertEquals(0f, s.distance(5_000), eps)
        s.setRunning(true, 1_000)
        assertEquals(200f, s.distance(3_000), eps)
        s.setSpeed(50f, 3_000)
        assertEquals(250f, s.distance(4_000), eps)
        s.setRunning(false, 4_000)
        assertEquals(250f, s.distance(99_000), eps)
        s.rescale(2f, 99_000)
        assertEquals(500f, s.distance(99_000), eps)
        s.setRunning(true, 100_000)
        s.reset(100_500)
        assertEquals(50f, s.distance(101_500), eps)
    }

    // ---- GIF ----

    @Test
    fun gifTimeline_loopsByDelay_withBrowserMinimums() {
        val t = GifTimeline(intArrayOf(100, 0, 50, 15))
        // 0 → 100 ms default, 15 → clamped 20 ms: ends 100, 200, 250, 270
        assertEquals(270L, t.loopMs)
        assertEquals(0, t.frameAt(0))
        assertEquals(0, t.frameAt(99))
        assertEquals(1, t.frameAt(100))
        assertEquals(2, t.frameAt(249))
        assertEquals(3, t.frameAt(250))
        assertEquals(0, t.frameAt(270))
        assertEquals(1, t.frameAt(270 + 150))
        assertEquals(0, GifTimeline(intArrayOf(40)).frameAt(12_345))
    }
}
