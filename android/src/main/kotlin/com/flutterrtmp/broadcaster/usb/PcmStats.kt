package com.flutterrtmp.broadcaster.usb

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Rolling PCM 16-bit little-endian statistics for one log window (docs/specs/diagnostics.md).
 * Fed from the audio read thread, summarised and reset from the main thread, so every call is synchronized.
 */
class PcmStats {
    private var bytes = 0L
    private var reads = 0
    private var emptyReads = 0
    private var maxGapMs = 0L
    private var lastReadAtMs = -1L
    private var peak = 0
    private var sumSquares = 0.0
    private var samples = 0L
    private var zeroSamples = 0L

    @Synchronized
    fun onRead(buffer: ByteArray, length: Int, nowMs: Long) {
        if (lastReadAtMs >= 0) maxGapMs = maxOf(maxGapMs, nowMs - lastReadAtMs)
        lastReadAtMs = nowMs
        if (length <= 0) {
            emptyReads++
            return
        }
        reads++
        bytes += length
        var i = 0
        while (i + 1 < length) {
            val s = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            if (s == 0) zeroSamples++
            sumSquares += s.toDouble() * s
            samples++
            i += 2
        }
    }

    /** One-line summary of the window, then reset. [windowMs] is the time since the last summary. */
    @Synchronized
    fun summarize(windowMs: Long, expectedBytesPerSec: Int): String {
        val bps = if (windowMs > 0) bytes * 1000 / windowMs else 0
        val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
        val zeroPct = if (samples > 0) zeroSamples * 100 / samples else 100
        val line = "bytes/s=$bps (expected $expectedBytesPerSec) reads=$reads emptyReads=$emptyReads " +
            "maxGapMs=$maxGapMs peak=${dbfs(peak.toDouble())}dBFS rms=${dbfs(rms)}dBFS zero%=$zeroPct"
        bytes = 0; reads = 0; emptyReads = 0; maxGapMs = 0; peak = 0; sumSquares = 0.0; samples = 0; zeroSamples = 0
        return line
    }

    companion object {
        /** Full scale = 32768; digital silence reports -inf as "-120". */
        fun dbfs(level: Double): String =
            if (level <= 0.0) "-120" else "%.1f".format(java.util.Locale.US, 20 * log10(level / 32768.0))
    }
}
