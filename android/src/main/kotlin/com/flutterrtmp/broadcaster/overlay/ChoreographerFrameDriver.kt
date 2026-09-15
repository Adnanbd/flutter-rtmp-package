package com.flutterrtmp.broadcaster.overlay

import android.view.Choreographer

/**
 * Calls [onFrame] once per display frame while active, throttled to [fps] (the encoder can't show more).
 * Main thread only.
 */
class ChoreographerFrameDriver(
    private val fps: () -> Int,
    private val onFrame: () -> Unit
) : FrameRequester, Choreographer.FrameCallback {

    private var active = false
    private var posted = false
    private var lastFrameMs = 0L

    override fun setActive(active: Boolean) {
        this.active = active
        if (active) post() else if (posted) {
            Choreographer.getInstance().removeFrameCallback(this)
            posted = false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!active) return
        val nowMs = frameTimeNanos / 1_000_000
        val intervalMs = 1000L / fps().coerceIn(1, 120)
        // A few ms of slack so 60 Hz vsync lands on every other frame for 30 fps, not every third.
        if (nowMs - lastFrameMs >= intervalMs - 4) {
            lastFrameMs = nowMs
            onFrame()
        }
        if (active) post()
    }

    private fun post() {
        if (posted) return
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }
}
