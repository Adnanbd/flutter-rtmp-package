package com.flutterrtmp.broadcaster.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OverlayTimerTest {

    @Test
    fun accumulatesOnlyWhileRunning() {
        val t = OverlayTimer(10_000)
        assertEquals(0L, t.elapsedMs(500))
        t.setRunning(true, 1_000)
        assertEquals(2_000L, t.elapsedMs(3_000))
        t.setRunning(false, 3_000)
        assertEquals(2_000L, t.elapsedMs(50_000))
        t.setRunning(true, 50_000)
        assertEquals(3_000L, t.elapsedMs(51_000))
        assertEquals(7_000L, t.remainingMs(51_000))
    }

    @Test
    fun setRunning_isIdempotent() {
        val t = OverlayTimer(null)
        t.setRunning(true, 0)
        t.setRunning(true, 900)     // must not reset the start
        assertEquals(1_000L, t.elapsedMs(1_000))
        t.setRunning(false, 1_000)
        t.setRunning(false, 5_000)  // must not add again
        assertEquals(1_000L, t.elapsedMs(9_000))
        assertFalse(t.isRunning)
    }

    @Test
    fun infinite_hasNoRemaining_butKeepsElapsed() {
        val t = OverlayTimer(null)
        t.setRunning(true, 0)
        assertNull(t.remainingMs(4_000))
        t.durationMs = 3_000
        assertEquals(-1_000L, t.remainingMs(4_000))
    }

    @Test
    fun restart_zeroesElapsed_keepsRunningState() {
        val t = OverlayTimer(5_000)
        t.setRunning(true, 0)
        t.restart(4_000)
        assertTrue(t.isRunning)
        assertEquals(1_000L, t.elapsedMs(5_000))

        val paused = OverlayTimer(5_000)
        paused.setRunning(true, 0)
        paused.setRunning(false, 2_000)
        paused.restart(3_000)
        assertFalse(paused.isRunning)
        assertEquals(0L, paused.elapsedMs(10_000))
    }
}
