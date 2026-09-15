package com.flutterrtmp.broadcaster.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class CarouselTimelineTest {

    // Slots: A 0–5000, B 5000–15000 (override 10 s), C 15000–20000; transition 500 ms at the end of each slot.
    private val timeline = CarouselTimeline(longArrayOf(5_000, 10_000, 5_000), 500)

    @Test
    fun holdsEachItemForItsInterval() {
        assertEquals(20_000L, timeline.cycleMs)
        assertEquals(0, timeline.at(0).index)
        assertEquals(0, timeline.at(4_499).index)
        assertEquals(1, timeline.at(5_000).index)
        assertEquals(1, timeline.at(14_499).index)
        assertEquals(2, timeline.at(15_000).index)
        assertNull(timeline.at(4_499).progress)
    }

    @Test
    fun transitionTakesTheEndOfTheSlot() {
        val s = timeline.at(4_750)
        assertEquals(0, s.index)
        assertEquals(1, s.next)
        assertEquals(0.5f, s.progress)
        assertEquals(250L, s.nextItemTimeMs)
        assertTrue(timeline.inTransition(4_500))
        assertFalse(timeline.inTransition(5_000))
    }

    @Test
    fun loopsBackToFirstItem() {
        val s = timeline.at(19_900)
        assertEquals(2, s.index)
        assertEquals(0, s.next)
        assertEquals(0, timeline.at(20_000).index)
        assertEquals(1, timeline.at(45_000).index)
    }

    @Test
    fun itemClockIsContinuousFromTransitionIntoSlot() {
        // B starts appearing at 4500 (clock 0), its slot starts at 5000 (clock 500).
        assertEquals(499L, timeline.at(4_999).nextItemTimeMs)
        assertEquals(500L, timeline.at(5_000).itemTimeMs)
        assertEquals(1_500L, timeline.at(6_000).itemTimeMs)
    }

    @Test
    fun msUntilNextChange_pointsAtTransitionStart() {
        assertEquals(4_500L, timeline.msUntilNextChange(0))
        assertEquals(0L, timeline.msUntilNextChange(4_600))
        assertEquals(9_500L, timeline.msUntilNextChange(5_000))
        assertEquals(4_500L, timeline.msUntilNextChange(20_000))
    }

    @Test
    fun cut_changesAtSlotBoundaryWithoutTransition() {
        val cut = CarouselTimeline(longArrayOf(1_000, 2_000), 0)
        assertNull(cut.at(999).progress)
        assertEquals(1, cut.at(1_000).index)
        assertEquals(1_000L, cut.msUntilNextChange(0))
        assertEquals(1L, cut.msUntilNextChange(2_999))
        assertEquals(0L, cut.at(1_000).itemTimeMs)
    }

    @Test
    fun singleItem_isStatic() {
        val one = CarouselTimeline(longArrayOf(5_000), 500)
        assertEquals(0L, one.transitionMs)
        assertEquals(0, one.at(123_456).index)
        assertEquals(123_456L, one.at(123_456).itemTimeMs)
        assertNull(one.msUntilNextChange(0))
        assertFalse(one.inTransition(4_900))
    }

    @Test
    fun rejectsTransitionNotShorterThanInterval() {
        assertFailsWith<IllegalArgumentException> { CarouselTimeline(longArrayOf(1_000, 500), 500) }
        assertFailsWith<IllegalArgumentException> { CarouselTimeline(longArrayOf(), 0) }
    }
}
