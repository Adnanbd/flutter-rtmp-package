package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.OverlayGeometry.PostRect

/**
 * Enter/exit animation math (docs/specs/dynamic-overlays.md §7). Pure Kotlin.
 *
 * `visible` is the eased fraction of the overlay that is shown: 0 = fully out, 1 = final state.
 */
object OverlayAnimationMath {

    /** Where to draw and how much of the content to reveal (curtain). */
    data class Frame(val rect: PostRect, val reveal: Float)

    fun ease(easing: Easing, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            Easing.LINEAR -> x
            Easing.EASE_IN -> x * x * x
            Easing.EASE_OUT -> 1f - (1f - x).let { it * it * it }
            Easing.EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
        }
    }

    /**
     * Eased visible fraction for linear [progress] (0 = out, 1 = in).
     * Enter runs 0→1 with the easing; exit runs 1→0, so the easing applies to the elapsed exit time.
     */
    fun visible(spec: AnimationSpec, progress: Float, exiting: Boolean): Float =
        if (exiting) 1f - ease(spec.easing, 1f - progress) else ease(spec.easing, progress)

    /** Apply [type]/[edge] at [visible] to the overlay's final [rect] (post-rotation percent). */
    fun frame(rect: PostRect, type: AnimationType, edge: Edge, visible: Float): Frame {
        val v = visible.coerceIn(0f, 1f)
        return when (type) {
            AnimationType.NONE -> Frame(rect, 1f)
            AnimationType.SLIDE -> {
                val (outX, outY) = when (edge) {
                    Edge.LEFT -> -rect.w to rect.y
                    Edge.RIGHT -> 100f to rect.y
                    Edge.TOP -> rect.x to -rect.h
                    Edge.BOTTOM -> rect.x to 100f
                }
                Frame(rect.copy(x = lerp(outX, rect.x, v), y = lerp(outY, rect.y, v)), 1f)
            }
            AnimationType.POP -> {
                val w = rect.w * v
                val h = rect.h * v
                Frame(PostRect(rect.x + (rect.w - w) / 2f, rect.y + (rect.h - h) / 2f, w, h), 1f)
            }
            // Rect stays put; the renderer clips the content to the centered `reveal` fraction of its width.
            AnimationType.CURTAIN -> Frame(rect, v)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
