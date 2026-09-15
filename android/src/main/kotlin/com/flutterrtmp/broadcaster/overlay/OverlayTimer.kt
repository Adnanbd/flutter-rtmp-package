package com.flutterrtmp.broadcaster.overlay

/**
 * Live-only duration timer for one dynamic overlay (docs/specs/dynamic-overlays.md §5).
 *
 * Pure bookkeeping: the caller passes `now` (monotonic ms) and decides when the timer runs
 * (RTMP connected AND overlay VISIBLE). Elapsed time is kept even when [durationMs] is null so a
 * later `duration: .of(d)` update counts time already shown.
 */
class OverlayTimer(var durationMs: Long?) {
    private var accumulatedMs = 0L
    private var runningSinceMs: Long? = null

    val isRunning: Boolean get() = runningSinceMs != null

    fun elapsedMs(now: Long): Long = accumulatedMs + (runningSinceMs?.let { now - it } ?: 0L)

    fun setRunning(running: Boolean, now: Long) {
        if (running == isRunning) return
        if (running) {
            runningSinceMs = now
        } else {
            accumulatedMs += now - runningSinceMs!!
            runningSinceMs = null
        }
    }

    /** `restartTimer: true` — elapsed back to 0, running state unchanged. */
    fun restart(now: Long) {
        accumulatedMs = 0L
        if (isRunning) runningSinceMs = now
    }

    /** Null when the overlay never expires; ≤ 0 when it is due. */
    fun remainingMs(now: Long): Long? = durationMs?.let { it - elapsedMs(now) }
}
