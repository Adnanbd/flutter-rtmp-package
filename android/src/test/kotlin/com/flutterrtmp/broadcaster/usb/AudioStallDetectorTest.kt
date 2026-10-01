package com.flutterrtmp.broadcaster.usb

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioStallDetectorTest {
    private var now = 0L
    private val bps = 176_400 // 44.1 kHz stereo 16-bit
    private fun detector() = AudioStallDetector({ now }, bps)

    /** Feeds real-time PCM in 20 ms chunks for [ms]. */
    private fun feed(d: AudioStallDetector, ms: Long, ratio: Double = 1.0) {
        val chunk = (bps * 0.02 * ratio).toInt()
        repeat((ms / 20).toInt()) { now += 20; d.onBytes(chunk) }
    }

    @Test
    fun `healthy real-time input never stalls`() {
        val d = detector().also { it.start() }
        repeat(10) { feed(d, 1_000); assertNull(d.check()) }
    }

    @Test
    fun `grace period ignores slow start`() {
        val d = detector().also { it.start() }
        now += 900
        assertNull(d.check())
    }

    @Test
    fun `first frame then silence stalls`() {
        val d = detector().also { it.start() }
        now += 100; d.onBytes(4096)
        now += 1_600
        val reason = d.check()
        assertNotNull(reason)
        assertTrue(reason.startsWith("no PCM"))
    }

    @Test
    fun `nothing ever arrives stalls after no-data limit`() {
        val d = detector().also { it.start() }
        now += 1_500
        assertNotNull(d.check())
    }

    @Test
    fun `trickle below half real-time stalls`() {
        val d = detector().also { it.start() }
        feed(d, 4_500, ratio = 0.3)
        val reason = d.check()
        assertNotNull(reason)
        assertTrue(reason.startsWith("only"))
    }

    @Test
    fun `stopped detector is quiet and restart resets`() {
        val d = detector().also { it.start() }
        now += 5_000
        d.stop()
        assertNull(d.check())
        d.start()
        assertNull(d.check())
        feed(d, 2_000)
        assertNull(d.check())
    }
}
