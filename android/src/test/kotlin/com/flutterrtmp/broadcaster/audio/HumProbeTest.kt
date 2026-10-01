package com.flutterrtmp.broadcaster.audio

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HumProbeTest {
    private fun value(line: String, key: String) = Regex("$key=(-?[0-9.]+)").find(line)!!.groupValues[1].toDouble()

    @Test
    fun `hum dominated noise reports the hum RMS level and a high hum share`() {
        val p = HumProbe(SR)
        mix(sine(50.0, -40.0, 1.0), whiteNoise(-70.0, 1.0)).forEach { p.next(it, true) }
        val line = assertNotNull(p.summarize())
        assertTrue(value(line, "hum50") in -44.5..-41.5, line) // -40 dBFS peak sine = -43 dBFS RMS
        assertTrue(value(line, "humShare") > 90, line)
    }

    @Test
    fun `white noise reports low hum share`() {
        val p = HumProbe(SR)
        whiteNoise(-50.0, 1.0).forEach { p.next(it, true) }
        val line = assertNotNull(p.summarize())
        assertTrue(value(line, "noise") in -51.0..-49.0, line)
        assertTrue(value(line, "humShare") < 10, line)
    }

    @Test
    fun `blocks with speech are ignored`() {
        val p = HumProbe(SR)
        sine(50.0, -40.0, 1.0).forEach { p.next(it, false) }
        assertNull(p.summarize())
    }
}
