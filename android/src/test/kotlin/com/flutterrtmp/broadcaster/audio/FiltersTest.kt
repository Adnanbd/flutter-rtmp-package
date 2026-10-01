package com.flutterrtmp.broadcaster.audio

import kotlin.test.Test
import kotlin.test.assertTrue

class FiltersTest {
    private fun run(f: Biquad, x: FloatArray) = FloatArray(x.size) { f.process(x[it]) }

    @Test
    fun `high-pass keeps speech and removes rumble`() {
        val skip = SR / 2 // settle
        val speech = run(Biquad.highPass(SR, 80.0), sine(1000.0, -20.0, 2.0))
        val rumble = run(Biquad.highPass(SR, 80.0), sine(20.0, -20.0, 2.0))
        // A -20 dBFS-peak sine has -23 dBFS RMS.
        assertTrue(rmsDb(speech, skip) > -23.5, "1 kHz ${rmsDb(speech, skip)}")
        assertTrue(rmsDb(rumble, skip) < -42.0, "20 Hz ${rmsDb(rumble, skip)}")
    }

    @Test
    fun `hum notch removes 50 Hz and leaves 1 kHz`() {
        val skip = SR
        val hum = run(Biquad.notch(SR, 50.0, 30.0), sine(50.0, -30.0, 3.0))
        val voice = run(Biquad.notch(SR, 50.0, 30.0), sine(1000.0, -30.0, 3.0))
        assertTrue(rmsDb(hum, skip) < -60.0, "50 Hz ${rmsDb(hum, skip)}")
        assertTrue(rmsDb(voice, skip) > -34.0, "1 kHz ${rmsDb(voice, skip)}") // -30 dBFS peak = -33 dBFS RMS
    }

    @Test
    fun `limiter never lets a peak past -1 dBFS`() {
        val lim = Limiter(SR)
        val ceiling = 32768f * dbToLinear(-1.0) + 1f
        for (i in 0 until SR) {
            val peak = if (i % 100 == 0) 32767f else 1000f
            assertTrue(peak * lim.next(peak) <= ceiling)
        }
    }

    @Test
    fun `gate closes on quiet noise and opens on speech level`() {
        val gate = NoiseGate(SR)
        var g = 0f
        repeat(SR) { g = gate.next(32768f * dbToLinear(-62.0), null) }
        assertTrue(!gate.isOpen && g < 0.2f, "closed gain $g")
        repeat(SR / 10) { g = gate.next(32768f * dbToLinear(-20.0), null) }
        assertTrue(gate.isOpen && g > 0.9f, "open gain $g")
    }

    @Test
    fun `gate opens on voice activity even when quiet`() {
        val gate = NoiseGate(SR)
        repeat(SR / 10) { gate.next(32768f * dbToLinear(-70.0), 0.9f) }
        assertTrue(gate.isOpen)
    }

    @Test
    fun `agc lifts quiet speech toward target within its range`() {
        val agc = Agc(SR)
        val level = 32768f * dbToLinear(-40.0)
        repeat(SR * 10) { agc.next(level, true) }
        val gainDb = 20 * kotlin.math.log10(agc.gain.toDouble())
        assertTrue(gainDb in 15.0..24.01, "gain $gainDb")
    }

    @Test
    fun `agc is frozen while not measuring`() {
        val agc = Agc(SR)
        repeat(SR * 5) { agc.next(100f, false) }
        assertTrue(agc.gain == 1f)
    }
}
