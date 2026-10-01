package com.flutterrtmp.broadcaster.usb

import kotlin.test.Test
import kotlin.test.assertEquals

class UvcTimingTest {
    @Test
    fun `close right after start waits the remainder`() {
        assertEquals(483, UvcTiming.waitBeforeCloseMs(startedAtMs = 1_000, nowMs = 1_017))
    }

    @Test
    fun `old preview closes immediately`() {
        assertEquals(0, UvcTiming.waitBeforeCloseMs(startedAtMs = 1_000, nowMs = 1_500))
        assertEquals(0, UvcTiming.waitBeforeCloseMs(startedAtMs = 1_000, nowMs = 9_000))
    }

    @Test
    fun `never started does not wait`() {
        assertEquals(0, UvcTiming.waitBeforeCloseMs(startedAtMs = 0, nowMs = 10))
    }

    @Test
    fun `clock going backwards does not wait`() {
        assertEquals(0, UvcTiming.waitBeforeCloseMs(startedAtMs = 2_000, nowMs = 1_000))
    }
}
