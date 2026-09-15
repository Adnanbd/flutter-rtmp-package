package com.flutterrtmp.broadcaster.overlay

import kotlin.math.roundToInt

/**
 * Pure overlay geometry — no Android or GL dependencies, so it is unit-testable on the JVM.
 *
 * Two coordinate spaces, both in 0–100 % of the frame with origin top-left:
 * - **post-rotation**: the frame viewers see (encoder output dims). Everything the app sends is here.
 * - **pre-rotation**: where `ImageObjectFilterRender` actually draws, before `setStreamRotation(270)`
 *   rotates portrait frames 90° CCW. Landscape: pre == post.
 *
 * See docs/specs/overlay-compositing.md and docs/specs/dynamic-overlays.md §2.
 */
object OverlayGeometry {

    /** Encoder output size (post-rotation). */
    data class FrameSize(val width: Int, val height: Int) {
        val aspect: Float get() = width.toFloat() / height.toFloat()
    }

    /** Rect in post-rotation frame percent. */
    data class PostRect(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Values to pass to `setScale` / `setPosition` (pre-rotation percent). */
    data class FilterTransform(val scaleX: Float, val scaleY: Float, val posX: Float, val posY: Float)

    /** A length along one frame axis: percent of that axis, or encoder pixels. */
    sealed class Length {
        abstract fun toPercent(axisPx: Int): Float

        data class Percent(val value: Float) : Length() {
            override fun toPercent(axisPx: Int): Float = value
        }

        data class Px(val value: Float) : Length() {
            override fun toPercent(axisPx: Int): Float = value / axisPx.toFloat() * 100f
        }
    }

    data class Placement(
        val left: Length? = null,
        val right: Length? = null,
        val top: Length? = null,
        val bottom: Length? = null,
        val width: Length? = null,
        val height: Length? = null
    )

    data class PlacementResult(val rect: PostRect, val downscaled: Boolean)

    /**
     * Post-rotation rect → filter transform. Portrait: frame rotates 90° CCW, so
     * pre.scale = (post.h, post.w), pre.pos = (100 − post.y − post.h, post.x).
     */
    fun toFilter(rect: PostRect, isPortrait: Boolean): FilterTransform =
        if (isPortrait) {
            FilterTransform(
                scaleX = rect.h,
                scaleY = rect.w,
                posX = 100f - rect.y - rect.h,
                posY = rect.x
            )
        } else {
            FilterTransform(scaleX = rect.w, scaleY = rect.h, posX = rect.x, posY = rect.y)
        }

    /**
     * BoxFit.contain in frame-percent space: largest size with the content's aspect that fits
     * inside `boxW × boxH` (percent). Returns (w, h).
     */
    fun contain(boxW: Float, boxH: Float, contentAspect: Float, frameAspect: Float): Pair<Float, Float> {
        val hForW = boxW * frameAspect / contentAspect
        return if (hForW <= boxH) {
            boxW to hForW
        } else {
            (boxH * contentAspect / frameAspect) to boxH
        }
    }

    /**
     * Edge-anchor rule on one axis: only `start` → pinned to start edge; only `end` → pinned to
     * end edge; both or neither → centered. Clamped to `[0, 100 − size]`.
     */
    fun anchor(start: Float?, end: Float?, size: Float): Float {
        val maxPos = (100f - size).coerceAtLeast(0f)
        return when {
            start != null && end == null -> start.coerceIn(0f, maxPos)
            end != null && start == null -> (100f - size - end).coerceIn(0f, maxPos)
            else -> maxPos / 2f
        }
    }

    /** Legacy sponsor placement (`SponsorPlacement`, int percent anchors + contain box). */
    fun sponsorRect(
        left: Int?, right: Int?, top: Int?, bottom: Int?,
        width: Int, height: Int,
        contentAspect: Float, frame: FrameSize
    ): PostRect {
        val (w, h) = contain(
            width.coerceIn(1, 100).toFloat(),
            height.coerceIn(1, 100).toFloat(),
            contentAspect,
            frame.aspect
        )
        return PostRect(
            x = anchor(left?.toFloat(), right?.toFloat(), w),
            y = anchor(top?.toFloat(), bottom?.toFloat(), h),
            w = w,
            h = h
        )
    }

    /**
     * Legacy scoreband placement: width 1–100 % of frame width, height from content aspect,
     * x/y 0–100 slide the rect between the frame edges (0 = left/top edge, 100 = right/bottom edge).
     * Intentionally unclamped vertically — matches pre-refactor behavior.
     */
    fun scorebandRect(width: Float, x: Float, y: Float, contentAspect: Float, frame: FrameSize): PostRect {
        val w = width.coerceIn(1f, 100f)
        val xPct = x.coerceIn(0f, 100f)
        val yPct = y.coerceIn(0f, 100f)
        val h = (w / 100f) * frame.aspect / contentAspect * 100f
        return PostRect(
            x = (xPct / 100f) * (100f - w),
            y = (yPct / 100f) * (100f - h),
            w = w,
            h = h
        )
    }

    /**
     * Wrapped text (`maxLines > 1`): wrap to the placement width (default the frame width), then place the measured
     * size with the normal rules — so a placement `height` still contains it and oversize is downscaled.
     * [measure] returns the content size in px for a wrap width in px.
     */
    fun wrappedPlacementRect(p: Placement, frame: FrameSize, measure: (widthPx: Int) -> Pair<Int, Int>): PlacementResult {
        val wrapPercent = (p.width?.toPercent(frame.width) ?: 100f).coerceIn(0f, 100f)
        val wrapPx = (wrapPercent / 100f * frame.width).roundToInt().coerceIn(1, frame.width)
        val (w, h) = measure(wrapPx)
        return placementRect(p.copy(width = Length.Px(w.toFloat())), w.coerceAtLeast(1), h.coerceAtLeast(1), frame)
    }

    /**
     * Dynamic overlay placement (docs/specs/dynamic-overlays.md §2).
     * Size: width+height → contain; one → aspect-derived; neither → intrinsic px.
     * Any result larger than the frame is contained in the frame and flagged `downscaled`.
     * [fillBox] (carousel slot, spec §12): width+height → the box itself, not contained.
     */
    fun placementRect(
        p: Placement,
        contentWidthPx: Int,
        contentHeightPx: Int,
        frame: FrameSize,
        fillBox: Boolean = false
    ): PlacementResult {
        var contentAspect = contentWidthPx.toFloat() / contentHeightPx.toFloat()
        val boxW = p.width?.toPercent(frame.width)
        val boxH = p.height?.toPercent(frame.height)

        var (w, h) = when {
            fillBox && boxW != null && boxH != null -> {
                if (boxW > 0f && boxH > 0f) contentAspect = boxW / boxH * frame.aspect
                boxW to boxH
            }
            boxW != null && boxH != null -> contain(boxW, boxH, contentAspect, frame.aspect)
            boxW != null -> boxW to boxW * frame.aspect / contentAspect
            boxH != null -> (boxH * contentAspect / frame.aspect) to boxH
            else -> (contentWidthPx.toFloat() / frame.width * 100f) to (contentHeightPx.toFloat() / frame.height * 100f)
        }

        val downscaled = w > 100f || h > 100f
        if (downscaled) {
            val fit = contain(100f, 100f, contentAspect, frame.aspect)
            w = fit.first
            h = fit.second
        }

        return PlacementResult(
            rect = PostRect(
                x = anchor(p.left?.toPercent(frame.width), p.right?.toPercent(frame.width), w),
                y = anchor(p.top?.toPercent(frame.height), p.bottom?.toPercent(frame.height), h),
                w = w,
                h = h
            ),
            downscaled = downscaled
        )
    }
}
