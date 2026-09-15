package com.flutterrtmp.broadcaster.camera

import com.flutterrtmp.broadcaster.overlay.OverlayScheduler
import kotlin.math.abs
import kotlin.math.roundToInt

/** Zoom API failure with a stable channel error code (docs/specs/camera-zoom.md). */
class ZoomException(val code: String, message: String) : RuntimeException(message)

enum class ZoomSource(val wire: String) { CAMERA2("camera2"), UVC("uvc") }

/** `ZoomInfo` on the wire. [current] is a zoom ratio; unsupported sources report 1.0 for all three values. */
data class ZoomInfo(
    val supported: Boolean,
    val min: Float,
    val max: Float,
    val current: Float,
    val source: ZoomSource
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "supported" to supported,
        "min" to min.toDouble(),
        "max" to max.toDouble(),
        "current" to current.toDouble(),
        "source" to source.wire
    )
}

/** One camera source that can zoom. Implementations talk to RootEncoder / libuvc. Main thread only. */
interface ZoomTarget {
    val source: ZoomSource

    sealed class Availability {
        /** Session open: zoom can be applied now within [min]..[max]. */
        data class Ready(val min: Float, val max: Float) : Availability()
        /** Camera not open yet (async open) — try again shortly. */
        object NotReady : Availability()
        /** This camera has no zoom control. */
        object Unsupported : Availability()
    }

    fun availability(): Availability

    /** Apply [level] (already clamped). May silently not take effect; read back with [current]. */
    fun apply(level: Float)

    fun current(): Float

    /** How far [current] may be from an applied level and still count as applied (UVC steps are 1 % of range). */
    val tolerance: Float get() = 0.01f
}

/**
 * Keeps the requested zoom for a CameraStreamManager and re-applies it across camera re-opens
 * (docs/specs/camera-zoom.md, ADR 0017).
 *
 * RootEncoder's `Camera2ApiManager.setZoom` silently returns until the capture session exists and
 * `closeCamera` resets zoom to 1.0, so every (re)start waits until the target is ready, applies,
 * reads back, and retries every [RETRY_MS] for up to [REAPPLY_TIMEOUT_MS].
 *
 * Pure Kotlin; [target] is looked up on every call because the video source changes.
 */
class ZoomController(
    private val target: () -> ZoomTarget?,
    private val emit: (Map<String, Any?>) -> Unit,
    private val clock: () -> Long,
    private val scheduler: OverlayScheduler,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val RETRY_MS = 100L
        const val REAPPLY_TIMEOUT_MS = 3_000L
        private const val EPSILON = 0.01f
    }

    /** Zoom the app asked for (clamped). Survives pipeline rebuilds; reset by [onCameraSwitched]. */
    var requested: Float = 1f
        private set

    private var cancelRetry: (() -> Unit)? = null

    /** Current state. Throws `ZOOM_NOT_READY` before a source exists or while its camera is opening. */
    fun info(): ZoomInfo {
        val t = target() ?: throw notReady()
        return when (val a = t.availability()) {
            is ZoomTarget.Availability.Ready -> ZoomInfo(true, a.min, a.max, t.current(), t.source)
            ZoomTarget.Availability.Unsupported -> ZoomInfo(false, 1f, 1f, 1f, t.source)
            ZoomTarget.Availability.NotReady -> throw notReady()
        }
    }

    /** `setZoom`: clamp, apply, read back. A pending re-apply is superseded. */
    fun set(level: Float): ZoomInfo {
        if (level.isNaN() || level.isInfinite() || level <= 0f) {
            throw ZoomException("ZOOM_INVALID", "zoom level must be a finite number > 0 (got $level)")
        }
        val t = target() ?: throw notReady()
        cancelPending()
        return when (val a = t.availability()) {
            is ZoomTarget.Availability.Ready -> {
                val clamped = level.coerceIn(a.min, a.max)
                t.apply(clamped)
                requested = clamped
                val current = t.current()
                // Range known but the capture session is still being built: keep trying in the background.
                if (abs(current - clamped) > t.tolerance) {
                    attempt(deadline = clock() + REAPPLY_TIMEOUT_MS, reason = "reapplied", announceOnly = false)
                }
                ZoomInfo(true, a.min, a.max, current, t.source)
            }
            ZoomTarget.Availability.Unsupported -> {
                if (abs(level - 1f) > EPSILON) {
                    throw ZoomException("ZOOM_UNSUPPORTED", "this camera (${t.source.wire}) has no zoom control")
                }
                requested = 1f
                ZoomInfo(false, 1f, 1f, 1f, t.source)
            }
            ZoomTarget.Availability.NotReady -> throw notReady()
        }
    }

    /** The camera (re)opened (preview bound). Re-applies a non-1.0 zoom once the session is ready. */
    fun reapply(where: String) {
        cancelPending()
        if (abs(requested - 1f) <= EPSILON) return
        log("reapply[$where]: requested=$requested")
        attempt(deadline = clock() + REAPPLY_TIMEOUT_MS, reason = "reapplied", announceOnly = false)
    }

    /** The facing changed: zoom resets to 1.0; `zoomChanged{cameraSwitched}` goes out once the new range is known. */
    fun onCameraSwitched() {
        cancelPending()
        requested = 1f
        attempt(deadline = clock() + REAPPLY_TIMEOUT_MS, reason = "cameraSwitched", announceOnly = true)
    }

    fun release() = cancelPending()

    private fun attempt(deadline: Long, reason: String, announceOnly: Boolean) {
        cancelRetry = null
        val t = target()
        val a = t?.availability()
        when {
            t == null || a == null || a == ZoomTarget.Availability.NotReady -> retryOrGiveUp(deadline, reason, announceOnly, "camera not ready")
            a == ZoomTarget.Availability.Unsupported -> {
                val changed = abs(requested - 1f) > EPSILON
                requested = 1f
                emitChanged(if (announceOnly) reason else "reset", ZoomInfo(false, 1f, 1f, 1f, t.source), changed || announceOnly)
            }
            a is ZoomTarget.Availability.Ready -> {
                if (announceOnly) {
                    emitChanged(reason, ZoomInfo(true, a.min, a.max, t.current(), t.source), true)
                    return
                }
                val clamped = requested.coerceIn(a.min, a.max)
                t.apply(clamped)
                val current = t.current()
                if (abs(current - clamped) > t.tolerance) {
                    retryOrGiveUp(deadline, reason, announceOnly, "read back $current, wanted $clamped")
                    return
                }
                val wasClamped = abs(clamped - requested) > EPSILON
                requested = clamped
                emitChanged(if (wasClamped) "clamped" else reason, ZoomInfo(true, a.min, a.max, current, t.source), true)
            }
        }
    }

    private fun retryOrGiveUp(deadline: Long, reason: String, announceOnly: Boolean, why: String) {
        if (clock() + RETRY_MS <= deadline) {
            cancelRetry = scheduler.schedule(RETRY_MS) { attempt(deadline, reason, announceOnly) }
            return
        }
        if (announceOnly) {
            log("zoom range still unknown after camera switch ($why)")
            return
        }
        log("reapply gave up: $why")
        emit(
            mapOf(
                "type" to "warning",
                "code" to "ZOOM_REAPPLY_FAILED",
                "message" to "zoom $requested could not be re-applied within ${REAPPLY_TIMEOUT_MS} ms ($why)",
                "requested" to requested.toDouble()
            )
        )
    }

    private fun emitChanged(reason: String, info: ZoomInfo, send: Boolean) {
        if (!send) return
        emit(mapOf("type" to "zoomChanged", "reason" to reason, "zoom" to info.toMap()))
    }

    private fun cancelPending() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    private fun notReady() = ZoomException("ZOOM_NOT_READY", "camera is not open yet; call after previewBound")
}

/**
 * UVC `CT_ZOOM_ABSOLUTE` ↔ zoom ratio (docs/specs/camera-zoom.md §Units). libuvc's `UVCCamera.setZoom/getZoom` use
 * percent (0–100) of the hardware range. Pure Kotlin.
 */
object UvcZoomMath {
    /** Nominal max ratio when the hardware minimum is 0 (no focal-length meaning). */
    const val NOMINAL_MAX = 4f

    fun ratioRange(rawMin: Int, rawMax: Int): Pair<Float, Float> = ratioOfRaw(rawMin, rawMin, rawMax) to ratioOfRaw(rawMax, rawMin, rawMax)

    fun ratioOfRaw(raw: Int, rawMin: Int, rawMax: Int): Float =
        if (rawMin > 0) raw.toFloat() / rawMin
        else 1f + (NOMINAL_MAX - 1f) * (raw - rawMin).toFloat() / (rawMax - rawMin).coerceAtLeast(1)

    fun rawOfRatio(ratio: Float, rawMin: Int, rawMax: Int): Int {
        val raw = if (rawMin > 0) ratio * rawMin else rawMin + (ratio - 1f) / (NOMINAL_MAX - 1f) * (rawMax - rawMin)
        return raw.roundToInt().coerceIn(rawMin, rawMax)
    }

    fun percentOfRatio(ratio: Float, rawMin: Int, rawMax: Int): Int {
        val span = (rawMax - rawMin).coerceAtLeast(1)
        return ((rawOfRatio(ratio, rawMin, rawMax) - rawMin) * 100f / span).roundToInt().coerceIn(0, 100)
    }

    fun ratioOfPercent(percent: Int, rawMin: Int, rawMax: Int): Float {
        val raw = rawMin + percent.coerceIn(0, 100) / 100f * (rawMax - rawMin)
        return ratioOfRaw(raw.roundToInt(), rawMin, rawMax)
    }
}
