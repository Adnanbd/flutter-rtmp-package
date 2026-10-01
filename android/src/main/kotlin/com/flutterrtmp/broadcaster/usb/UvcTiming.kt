package com.flutterrtmp.broadcaster.usb

/**
 * libuvc starts its capture thread asynchronously after `startPreview()`. Closing the camera within a few ms
 * of that killed the process with a native crash (Xiaomi 24129PN74G + MS2109 switcher, 2026-10-01: close 17 ms after
 * start). [UvcVideoSource] waits until the preview has lived [MIN_PREVIEW_LIFETIME_MS] before closing it.
 */
object UvcTiming {
    const val MIN_PREVIEW_LIFETIME_MS = 500L

    /** How long to wait before closing; 0 when the preview never started or is old enough. */
    fun waitBeforeCloseMs(startedAtMs: Long, nowMs: Long, minLifetimeMs: Long = MIN_PREVIEW_LIFETIME_MS): Long {
        if (startedAtMs <= 0) return 0
        val age = nowMs - startedAtMs
        return if (age in 0 until minLifetimeMs) minLifetimeMs - age else 0
    }
}
