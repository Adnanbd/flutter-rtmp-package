package com.flutterrtmp.broadcaster.camera

import com.flutterrtmp.broadcaster.usb.UvcVideoSource
import com.pedro.encoder.input.sources.video.Camera2Source

/**
 * Phone camera zoom through RootEncoder. `CONTROL_ZOOM_RATIO` on API 30+ (non-LEGACY), crop region below.
 * Before the capture session exists `getZoomRange()` is 1..1 and `setZoom` is a silent no-op, so a trivial range
 * reads as NotReady (Camera2 devices always offer digital zoom).
 */
class Camera2ZoomTarget(private val camera: Camera2Source) : ZoomTarget {
    override val source = ZoomSource.CAMERA2

    override fun availability(): ZoomTarget.Availability {
        if (!camera.isRunning()) return ZoomTarget.Availability.NotReady
        val range = try {
            camera.getZoomRange()
        } catch (t: Throwable) {
            return ZoomTarget.Availability.NotReady
        }
        return if (range.upper > range.lower) ZoomTarget.Availability.Ready(range.lower, range.upper)
        else ZoomTarget.Availability.NotReady
    }

    override fun apply(level: Float) = camera.setZoom(level)

    override fun current(): Float = camera.getZoom()
}

/** UVC hardware zoom (`CT_ZOOM_ABSOLUTE`), mapped to a ratio by [UvcZoomMath]. */
class UvcZoomTarget(private val uvc: UvcVideoSource) : ZoomTarget {
    override val source = ZoomSource.UVC

    override fun availability(): ZoomTarget.Availability {
        if (!uvc.isRunning()) return ZoomTarget.Availability.NotReady
        val (min, max) = uvc.zoomLimits() ?: return ZoomTarget.Availability.NotReady
        if (max <= min) return ZoomTarget.Availability.Unsupported
        val (lo, hi) = UvcZoomMath.ratioRange(min, max)
        return ZoomTarget.Availability.Ready(lo, hi)
    }

    override fun apply(level: Float) {
        val (min, max) = uvc.zoomLimits() ?: return
        uvc.setZoomPercent(UvcZoomMath.percentOfRatio(level, min, max))
    }

    override fun current(): Float {
        val (min, max) = uvc.zoomLimits() ?: return 1f
        return UvcZoomMath.ratioOfPercent(uvc.zoomPercent() ?: 0, min, max)
    }

    /** One percent step of the hardware range, plus rounding slack. */
    override val tolerance: Float
        get() {
            val (min, max) = uvc.zoomLimits() ?: return 0.01f
            val (lo, hi) = UvcZoomMath.ratioRange(min, max)
            return (hi - lo) / 100f + 0.01f
        }
}
