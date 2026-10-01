package com.flutterrtmp.broadcaster.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmStatsTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> out[i * 2] = (s and 0xFF).toByte(); out[i * 2 + 1] = (s shr 8).toByte() }
        return out
    }

    @Test
    fun `digital silence reports zero percent 100 and floor dbfs`() {
        val s = PcmStats()
        val buf = pcm(0, 0, 0, 0)
        s.onRead(buf, buf.size, 0)
        val line = s.summarize(1_000, 176_400)
        assertTrue("zero%=100" in line, line)
        assertTrue("peak=-120dBFS" in line, line)
    }

    @Test
    fun `full scale negative sample is 0 dbfs peak`() {
        val s = PcmStats()
        val buf = pcm(-32768, 0)
        s.onRead(buf, buf.size, 0)
        assertTrue("peak=0.0dBFS" in s.summarize(1_000, 176_400))
    }

    @Test
    fun `bytes per second gaps and empty reads`() {
        val s = PcmStats()
        val buf = pcm(1000, -1000)
        s.onRead(buf, buf.size, 0)
        s.onRead(buf, 0, 700)
        s.onRead(buf, buf.size, 750)
        val line = s.summarize(2_000, 176_400)
        assertTrue("bytes/s=4 " in line, line)
        assertTrue("reads=2 emptyReads=1" in line, line)
        assertTrue("maxGapMs=700" in line, line)
    }

    @Test
    fun `summary resets the window`() {
        val s = PcmStats()
        val buf = pcm(500)
        s.onRead(buf, buf.size, 0)
        s.summarize(1_000, 1)
        assertTrue("reads=0" in s.summarize(1_000, 1))
    }

    @Test
    fun `dbfs formatting`() {
        assertEquals("-6.0", PcmStats.dbfs(16384.0))
        assertEquals("-120", PcmStats.dbfs(0.0))
    }
}
