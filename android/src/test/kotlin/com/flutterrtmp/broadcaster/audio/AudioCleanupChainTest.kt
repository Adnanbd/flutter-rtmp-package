package com.flutterrtmp.broadcaster.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioCleanupChainTest {
    private fun chain(
        mode: AudioCleanupMode,
        fake: FakeDenoiser? = FakeDenoiser(),
        rate: Int = SR,
        clock: () -> Long = System::nanoTime,
        overload: MutableList<String> = mutableListOf(),
        unavailable: MutableList<String> = mutableListOf()
    ) = AudioCleanupChain(rate, 2, fake?.let { f -> { f } }, clock, { overload += it }, { unavailable += it })
        .also { it.mode = mode }

    /** Feeds in 4096-byte chunks like UsbAudioSource. */
    private fun feed(c: AudioCleanupChain, pcm: ByteArray) {
        var off = 0
        while (off < pcm.size) {
            val n = minOf(4096, pcm.size - off)
            val chunk = pcm.copyOfRange(off, off + n)
            c.process(chunk, n)
            chunk.copyInto(pcm, off)
            off += n
        }
    }

    @Test
    fun `off leaves audio untouched`() {
        val pcm = toStereoPcm(mix(sine(50.0, -40.0, 0.5), sine(1000.0, -20.0, 0.5)))
        val before = pcm.copyOf()
        feed(chain(AudioCleanupMode.OFF), pcm)
        assertContentEquals(before, pcm)
    }

    @Test
    fun `basic removes hum under speech and keeps the voice`() {
        val pcm = toStereoPcm(mix(sine(50.0, -35.0, 4.0), sine(1000.0, -26.0, 4.0)))
        feed(chain(AudioCleanupMode.BASIC), pcm)
        val out = leftChannel(pcm)
        val hum = HumProbe(SR)
        out.copyOfRange(SR * 2, SR * 4).forEach { hum.next(it, true) }
        val line = hum.summarize()!!
        val hum50 = Regex("hum50=(-?[0-9.]+)").find(line)!!.groupValues[1].toDouble()
        val total = Regex("noise=(-?[0-9.]+)").find(line)!!.groupValues[1].toDouble()
        // Voice (1 kHz) stays strong after AGC; 50 Hz hum ends up ≥ 40 dB below it.
        assertTrue(total > -30.0, line)
        assertTrue(total - hum50 > 40.0, line)
    }

    @Test
    fun `basic pulls a hiss-only signal down`() {
        val pcm = toStereoPcm(whiteNoise(-62.0, 3.0))
        feed(chain(AudioCleanupMode.BASIC), pcm)
        val out = leftChannel(pcm)
        assertTrue(rmsDb(out, SR, SR * 3) < -75.0, "out ${rmsDb(out, SR, SR * 3)}")
    }

    @Test
    fun `voice runs the denoiser per channel with one frame of latency`() {
        val fake = FakeDenoiser(vad = 0.9f)
        val c = chain(AudioCleanupMode.VOICE, fake)
        val pcm = toStereoPcm(sine(1000.0, -20.0, 1.0))
        feed(c, pcm)
        assertEquals(AudioCleanupMode.VOICE, c.effectiveMode)
        assertEquals(2 * SR / 480, fake.frames) // 2 channels × 100 frames
        val out = leftChannel(pcm)
        assertTrue(out.copyOfRange(0, 480).all { it == 0f }, "first 10 ms is the RNNoise delay")
    }

    @Test
    fun `output length always equals input length`() {
        val c = chain(AudioCleanupMode.VOICE)
        for (n in listOf(4, 100, 4096, 3844, 8192)) {
            val buf = ByteArray(n)
            c.process(buf, n)
            assertEquals(n, buf.size)
        }
    }

    @Test
    fun `voice without RNNoise falls back to basic and reports once`() {
        val unavailable = mutableListOf<String>()
        val c = chain(AudioCleanupMode.VOICE, fake = null, unavailable = unavailable)
        c.process(ByteArray(4096)); c.process(ByteArray(4096))
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `voice at 44_1 kHz falls back to basic`() {
        val unavailable = mutableListOf<String>()
        val c = chain(AudioCleanupMode.VOICE, rate = 44_100, unavailable = unavailable)
        c.process(ByteArray(4096))
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
        assertTrue(unavailable.single().contains("48 kHz"))
    }

    @Test
    fun `mode switch closes the old denoiser and fades in`() {
        val fake = FakeDenoiser()
        val c = chain(AudioCleanupMode.VOICE, fake)
        c.process(toStereoPcm(sine(1000.0, -20.0, 0.1)))
        c.mode = AudioCleanupMode.BASIC
        val pcm = toStereoPcm(sine(1000.0, -10.0, 0.1))
        c.process(pcm)
        assertTrue(fake.closed)
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
        assertTrue(kotlin.math.abs(leftChannel(pcm)[1]) < 200f, "starts faded")
    }

    @Test
    fun `cpu guard drops voice to basic when too slow`() {
        var now = 0L
        val overload = mutableListOf<String>()
        // Every process() call "takes" 1 s of CPU for ~21 ms of audio.
        val c = chain(AudioCleanupMode.VOICE, clock = { now.also { now += 500_000_000L } }, overload = overload)
        repeat(300) { c.process(ByteArray(4096)) }
        assertEquals(1, overload.size)
        assertEquals(AudioCleanupMode.BASIC, c.mode)
    }

    @Test
    fun `summary reports levels and resets`() {
        val c = chain(AudioCleanupMode.BASIC)
        c.process(toStereoPcm(whiteNoise(-60.0, 0.5)))
        val line = c.summarize(500)
        assertTrue(line.startsWith("mode=basic effective=basic in_rms="), line)
        assertTrue(c.summarize(500).contains("in_rms=-120dBFS"))
    }

    private class ThrowingDenoiser : Denoiser {
        override val frameSize = 480
        override fun process(frame: FloatArray): Float = throw IllegalStateException("native boom")
        override fun close() {}
    }

    @Test
    fun `denoiser error restores the raw chunk, disables cleanup and reports once`() {
        val failures = mutableListOf<Throwable>()
        val c = AudioCleanupChain(SR, 2, { ThrowingDenoiser() }, onFailure = { failures += it })
        c.mode = AudioCleanupMode.VOICE
        val pcm = toStereoPcm(sine(1000.0, -20.0, 0.1))
        val raw = pcm.copyOf()
        c.process(pcm) // must not throw
        assertContentEquals(raw, pcm)
        assertTrue(c.failed)
        val next = toStereoPcm(sine(500.0, -20.0, 0.1))
        val nextRaw = next.copyOf()
        c.process(next)
        assertContentEquals(nextRaw, next, "passes audio through while failed")
        assertEquals(1, failures.size)
    }

    @Test
    fun `resetFailure lets a new mode run again`() {
        val c = AudioCleanupChain(SR, 2, { ThrowingDenoiser() })
        c.mode = AudioCleanupMode.VOICE
        c.process(toStereoPcm(sine(1000.0, -20.0, 0.1)))
        assertTrue(c.failed)
        c.resetFailure()
        c.mode = AudioCleanupMode.BASIC
        c.process(toStereoPcm(sine(1000.0, -20.0, 0.1)))
        assertTrue(!c.failed)
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
    }

    @Test
    fun `denoiser that cannot be created falls back to basic`() {
        val unavailable = mutableListOf<String>()
        val c = AudioCleanupChain(SR, 2, { throw UnsatisfiedLinkError("no symbol") }, onUnavailable = { unavailable += it })
        c.mode = AudioCleanupMode.VOICE
        c.process(toStereoPcm(sine(1000.0, -20.0, 0.1)))
        assertTrue(!c.failed)
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `length beyond the buffer is clamped, odd lengths are safe`() {
        val c = chain(AudioCleanupMode.VOICE)
        c.process(ByteArray(10), 4096)
        c.process(ByteArray(7), 7)
        c.process(ByteArray(0), 0)
        assertTrue(!c.failed)
    }

    @Test
    fun `sample rate fallback to 44_1 kHz runs voice as basic, back to 48 kHz restores voice`() {
        val unavailable = mutableListOf<String>()
        val c = chain(AudioCleanupMode.VOICE, unavailable = unavailable)
        c.updateSampleRate(44_100)
        c.process(ByteArray(4096))
        assertEquals(AudioCleanupMode.BASIC, c.effectiveMode)
        c.updateSampleRate(48_000)
        c.process(ByteArray(4096))
        assertEquals(AudioCleanupMode.VOICE, c.effectiveMode)
        assertEquals(1, unavailable.size)
    }

    @Test
    fun `off keeps the buffer identical even for odd chunk sizes`() {
        val c = chain(AudioCleanupMode.OFF)
        val pcm = toStereoPcm(whiteNoise(-30.0, 0.05)).copyOf(1001)
        val raw = pcm.copyOf()
        c.process(pcm, 1001)
        assertContentEquals(raw, pcm)
    }
}
