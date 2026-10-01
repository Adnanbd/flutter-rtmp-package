package com.flutterrtmp.broadcaster.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Live mic cleanup (docs/specs/audio-cleanup.md, ADR 0025), the usual streamer chain:
 * high-pass 80 Hz → 50/100/150 Hz hum notches → RNNoise ([AudioCleanupMode.VOICE]) → gate → AGC → limiter.
 *
 * Works in place on interleaved 16-bit PCM, any chunk size; output length = input length. Per-channel filters and
 * denoisers; gate, AGC and limiter are linked across channels. [VOICE] adds a fixed 10 ms delay (one RNNoise frame).
 * Mode changes apply at the next chunk with fresh state and a 20 ms fade-in. Pure Kotlin except the injected denoiser.
 *
 * Never throws: [process] restores the untouched input on any error, disables cleanup until [resetFailure] and
 * reports through [onFailure]. It runs on audio threads (ours and RootEncoder's), where an uncaught throwable would
 * kill the app.
 *
 * @param denoiserFactory null when RNNoise isn't usable; VOICE then runs as BASIC and [onUnavailable] fires once.
 * @param nanoClock monotonic clock used only to measure processing time (CPU guard).
 */
class AudioCleanupChain(
    sampleRate: Int,
    private val channels: Int,
    private val denoiserFactory: (() -> Denoiser)?,
    private val nanoClock: () -> Long = System::nanoTime,
    private val onOverload: (String) -> Unit = {},
    private val onUnavailable: (String) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val onClipping: (String) -> Unit = {}
) {
    /** Set after an unexpected error; the chain then passes audio through untouched. */
    @Volatile var failed = false
        private set

    @Volatile var mode: AudioCleanupMode = AudioCleanupMode.OFF

    /** The stream's audio rate; changes via [updateSampleRate] (e.g. a device that can't do 48 kHz). */
    private var sampleRate: Int = sampleRate

    private var applied: AudioCleanupMode? = null
    private var effective: AudioCleanupMode = AudioCleanupMode.OFF
    private var highPass = emptyArray<Biquad>()
    private var notches = emptyArray<Array<Biquad>>()
    private var denoisers: Array<FrameDenoiser>? = null
    private var gate = NoiseGate(sampleRate)
    private var agc = Agc(sampleRate)
    private var limiter = Limiter(sampleRate)
    private var fade = 1f
    private var fadeStep = 1f / (sampleRate * 0.02f)
    private var probe = HumProbe(sampleRate)
    private var unavailableReported = false
    private val clip = Array(channels) { ClipDetector() }
    private var lastClipWarnNanos: Long? = null

    // Window stats, reset by summarize().
    private var inSumSq = 0.0
    private var outSumSq = 0.0
    private var frames = 0L
    private var gateOpenFrames = 0L
    private var vadSum = 0.0
    private var vadFrames = 0L
    private var cpuNanos = 0L

    // CPU guard window.
    private var guardCpuNanos = 0L
    private var guardFrames = 0L

    val effectiveMode: AudioCleanupMode @Synchronized get() = effective

    /** Cleans [length] bytes of [pcm] in place. Never throws (see class doc). */
    @Synchronized
    fun process(pcm: ByteArray, length: Int = pcm.size) {
        if (failed) return
        val len = length.coerceIn(0, pcm.size)
        // OFF only reads the buffer, so no backup is needed (keeps the default path cheap).
        val original = if (mode == AudioCleanupMode.OFF) null else pcm.copyOf(len)
        try {
            processUnsafe(pcm, len)
        } catch (t: Throwable) {
            original?.let { System.arraycopy(it, 0, pcm, 0, len) }
            failed = true
            try { denoisers?.forEach { it.close() } } catch (_: Throwable) {}
            denoisers = null
            applied = null
            try { onFailure(t) } catch (_: Throwable) {}
        }
    }

    /** Rebuilds every stage for a new stream audio rate; VOICE needs 48 kHz, else it runs as BASIC. */
    @Synchronized
    fun updateSampleRate(rate: Int) {
        if (rate == sampleRate || rate <= 0) return
        sampleRate = rate
        fadeStep = 1f / (rate * 0.02f)
        probe = HumProbe(rate)
        runCatching { denoisers?.forEach { it.close() } }
        denoisers = null
        applied = null
        unavailableReported = false
    }

    /** Lets a new [mode] request try again after a failure. */
    @Synchronized
    fun resetFailure() {
        failed = false
    }

    private fun processUnsafe(pcm: ByteArray, length: Int) {
        val started = nanoClock()
        if (applied != mode) apply(mode)
        val frameBytes = 2 * channels
        val n = length / frameBytes
        val tmp = FloatArray(channels)
        val off = effective == AudioCleanupMode.OFF
        for (f in 0 until n) {
            val base = f * frameBytes
            var inPeak = 0f
            for (c in 0 until channels) {
                val i = base + 2 * c
                val x = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toFloat()
                inSumSq += x.toDouble() * x
                if (c == 0) probe.next(x, !gate.isOpen)
                clip[c].next(x)
                if (abs(x) > inPeak) inPeak = abs(x)
                tmp[c] = if (off) x else stagesBeforeDynamics(c, x)
            }
            if (off) {
                gate.next(inPeak, null) // keeps the hum probe's "quiet" flag meaningful while off
                outSumSq += sqSum(tmp)
                frames++
                continue
            }
            var level = 0f
            for (c in 0 until channels) if (abs(tmp[c]) > level) level = abs(tmp[c])
            val vad = denoisers?.maxOf { it.vad }
            if (vad != null) { vadSum += vad; vadFrames++ }
            val g = gate.next(level, vad)
            if (gate.isOpen) gateOpenFrames++
            val a = agc.next(level, gate.isOpen)
            var peak = 0f
            for (c in 0 until channels) {
                tmp[c] *= g * a
                if (abs(tmp[c]) > peak) peak = abs(tmp[c])
            }
            val l = limiter.next(peak)
            if (fade < 1f) fade = (fade + fadeStep).coerceAtMost(1f)
            for (c in 0 until channels) {
                val y = (tmp[c] * l * fade).coerceIn(-32768f, 32767f)
                tmp[c] = y
                val s = y.toInt()
                val i = base + 2 * c
                pcm[i] = (s and 0xFF).toByte()
                pcm[i + 1] = (s shr 8).toByte()
            }
            outSumSq += sqSum(tmp)
            frames++
        }
        val spent = nanoClock() - started
        cpuNanos += spent
        guard(spent, n)
    }

    private fun stagesBeforeDynamics(c: Int, x: Float): Float {
        var y = highPass[c].process(x)
        for (notch in notches[c]) y = notch.process(y)
        denoisers?.let { y = it[c].push(y) }
        return y
    }

    private fun apply(requested: AudioCleanupMode) {
        denoisers?.forEach { it.close() }
        denoisers = null
        var target = requested
        if (target == AudioCleanupMode.VOICE) {
            val factory = denoiserFactory
            val reason = when {
                sampleRate != 48_000 -> "RNNoise needs 48 kHz, stream is $sampleRate Hz"
                factory == null -> "RNNoise library not available on this device"
                else -> null
            }
            val created = if (reason == null) createDenoisers(factory!!) else null
            if (created != null) {
                denoisers = created
            } else {
                target = AudioCleanupMode.BASIC
                if (!unavailableReported) {
                    unavailableReported = true
                    onUnavailable(reason ?: "RNNoise could not be started")
                }
            }
        }
        highPass = Array(channels) { Biquad.highPass(sampleRate, 80.0) }
        notches = Array(channels) { HUM_HZ.map { Biquad.notch(sampleRate, it, 30.0) }.toTypedArray() }
        gate = NoiseGate(sampleRate)
        agc = Agc(sampleRate)
        limiter = Limiter(sampleRate)
        fade = 0f
        guardCpuNanos = 0; guardFrames = 0
        effective = target
        applied = requested
    }

    /** All channels or nothing; a native create failure falls back to BASIC instead of failing the chain. */
    private fun createDenoisers(factory: () -> Denoiser): Array<FrameDenoiser>? {
        val made = ArrayList<FrameDenoiser>(channels)
        return try {
            repeat(channels) { made += FrameDenoiser(factory()) }
            made.toTypedArray()
        } catch (t: Throwable) {
            made.forEach { runCatching { it.close() } }
            null
        }
    }

    private fun guard(spentNanos: Long, n: Int) {
        if (effective != AudioCleanupMode.VOICE) return
        guardCpuNanos += spentNanos
        guardFrames += n
        if (guardFrames < sampleRate * 5L) return
        val audioNanos = guardFrames * 1_000_000_000L / sampleRate
        val ratio = guardCpuNanos.toDouble() / audioNanos
        guardCpuNanos = 0; guardFrames = 0
        if (ratio > 0.5) {
            mode = AudioCleanupMode.BASIC
            onOverload("cleanup used ${(ratio * 100).toInt()}% of real time; switched voice → basic")
        }
    }

    /** `cleanup:` + `noise:` diagnostics for the window since the last call, then reset. */
    @Synchronized
    fun summarize(windowMs: Long): String {
        val inRms = if (frames > 0) sqrt(inSumSq / (frames * channels)) else 0.0
        val outRms = if (frames > 0) sqrt(outSumSq / (frames * channels)) else 0.0
        val audioMs = frames * 1000 / sampleRate
        val cpuPct = if (audioMs > 0) cpuNanos / 1_000_000.0 / audioMs * 100 else 0.0
        val line = "mode=${mode.wire} effective=${effective.wire} in_rms=${dbfs(inRms)}dBFS out_rms=${dbfs(outRms)}dBFS " +
            "gate_open=${if (frames > 0) gateOpenFrames * 100 / frames else 0}% " +
            "vad=${if (vadFrames > 0) "%.2f".format(java.util.Locale.US, vadSum / vadFrames) else "n/a"} " +
            "gain=${"%.1f".format(java.util.Locale.US, 20 * log10(agc.gain.toDouble()))}dB " +
            "cpu=${"%.1f".format(java.util.Locale.US, cpuPct)}% window=${windowMs}ms " +
            clipSummary() +
            (probe.summarize()?.let { " | noise: $it" } ?: "")
        inSumSq = 0.0; outSumSq = 0.0; frames = 0; gateOpenFrames = 0; vadSum = 0.0; vadFrames = 0; cpuNanos = 0
        return line
    }

    /** `clip=…% ceiling=…dBFS`; fires [onClipping] at most once per [CLIP_WARN_INTERVAL_NANOS] while clipping. */
    private fun clipSummary(): String {
        val r = ClipDetector.merge(clip.map { it.summarize() })
        val ceiling = "%.1f".format(java.util.Locale.US, r.ceilingDbfs)
        if (r.clipping) {
            val now = nanoClock()
            val last = lastClipWarnNanos
            if (last == null || now - last >= CLIP_WARN_INTERVAL_NANOS) {
                lastClipWarnNanos = now
                try {
                    onClipping("Mic input is clipping at $ceiling dBFS before it reaches the phone (crackle on loud " +
                        "speech); lower the mixer or source output level")
                } catch (_: Throwable) {}
            }
        }
        return "clip=${"%.2f".format(java.util.Locale.US, r.ratio * 100)}% ceiling=${ceiling}dBFS" +
            (if (r.clipping) " CLIPPING" else "")
    }

    @Synchronized
    fun release() {
        denoisers?.forEach { runCatching { it.close() } }
        denoisers = null
        applied = null
    }

    private fun sqSum(v: FloatArray): Double { var s = 0.0; for (x in v) s += x.toDouble() * x; return s }

    private fun dbfs(rms: Double): String =
        if (rms <= 0) "-120" else "%.1f".format(java.util.Locale.US, 20 * log10(rms / FULL_SCALE))

    companion object {
        /** Mains hum in Bangladesh/most of the world is 50 Hz; the notches also catch its first two harmonics. */
        val HUM_HZ = listOf(50.0, 100.0, 150.0)
        const val CLIP_WARN_INTERVAL_NANOS = 30_000_000_000L
    }
}
