package com.flutterrtmp.broadcaster.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipDetectorTest {
    /** Speech-like: 200 Hz tone with a syllable envelope that varies, so only one cycle hits the window peak. */
    private fun speech(seconds: Double, peakDbfs: Double): FloatArray {
        val amp = 32768.0 * Math.pow(10.0, peakDbfs / 20.0)
        return FloatArray((SR * seconds).toInt()) { i ->
            val t = i.toDouble() / SR
            val env = 0.55 + 0.45 * sin(2 * PI * 3.1 * t) * sin(2 * PI * 0.37 * t + 0.4)
            (amp * env * sin(2 * PI * 200 * t)).toFloat()
        }
    }

    private fun clipAt(x: FloatArray, dbfs: Double): FloatArray {
        val c = (32768.0 * Math.pow(10.0, dbfs / 20.0)).toFloat()
        return FloatArray(x.size) { x[it].coerceIn(-c, c) }
    }

    private fun run(x: FloatArray): ClipDetector.Result = ClipDetector().also { d -> x.forEach { d.next(it) } }.summarize()

    @Test
    fun `clean speech is not clipping`() {
        val r = run(speech(5.0, -6.0))
        assertFalse(r.clipping, r.toString())
    }

    @Test
    fun `speech flattened at -9_5 dBFS is clipping with that ceiling`() {
        val r = run(clipAt(speech(5.0, -3.0), -9.5))
        assertTrue(r.clipping, r.toString())
        assertTrue(r.ceilingDbfs in -9.7..-9.3, r.toString())
    }

    @Test
    fun `quiet signals never count as clipping`() {
        val r = run(clipAt(speech(5.0, -20.0), -30.0))
        assertFalse(r.clipping, r.toString())
    }

    @Test
    fun `summarize resets the window`() {
        val d = ClipDetector()
        clipAt(speech(2.0, -3.0), -9.5).forEach { d.next(it) }
        assertTrue(d.summarize().clipping)
        speech(2.0, -12.0).forEach { d.next(it) }
        assertFalse(d.summarize().clipping)
    }
}
