package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Draws one dynamic layer into a small bitmap pool and publishes frames to its [DynamicLayerFilter] (ADR 0015).
 * One instance per layer per pipeline (OverlayFilterManager). Main thread only.
 */
class LayerRenderer(private val isPortrait: Boolean) {
    companion object {
        private const val MAX_POOL = 3
    }

    private class Slot(val bitmap: Bitmap, val canvas: Canvas)

    private val pool = ArrayList<Slot>(MAX_POOL)
    private var lastKey: Any? = null

    /** Force a redraw on the next [render] (new filter, new content). */
    fun invalidate() {
        lastKey = null
    }

    /** Drop bitmaps (layer hidden or removed). A filter may still be uploading one; it is not recycled. */
    fun dropPool() {
        pool.clear()
        lastKey = null
    }

    /**
     * Render [visual] at post-rotation size `w × h` px with the centered [reveal] fraction of its width
     * visible, unless nothing changed since the last published frame.
     * @return false when no pool bitmap was free (GL thread behind); call again next frame.
     */
    fun render(
        filter: DynamicLayerFilter,
        visual: OverlayVisual,
        frame: LayerFrame,
        w: Int,
        h: Int,
        reveal: Float,
        layout: TickerMath.Layout?
    ): Boolean {
        val key = listOf(visual, w, h, reveal, visual.frameKey(frame), filter)
        if (key == lastKey) return true

        val bw = if (isPortrait) h else w
        val bh = if (isPortrait) w else h
        if (pool.isNotEmpty() && (pool[0].bitmap.width != bw || pool[0].bitmap.height != bh)) pool.clear()
        val slot = pool.firstOrNull { !filter.isBusy(it.bitmap) }
            ?: if (pool.size < MAX_POOL) {
                val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                Slot(b, Canvas(b)).also { pool.add(it) }
            } else {
                return false   // GL thread is behind; retry next frame (lastKey unchanged)
            }

        slot.bitmap.eraseColor(0)
        val c = slot.canvas
        c.save()
        if (isPortrait) {
            // Same as OverlayFilterManager.orientBitmap: rotate 90° CW so it is upright after the stream rotation.
            c.translate(h.toFloat(), 0f)
            c.rotate(90f)
        }
        if (reveal < 1f) {
            val visibleW = w * reveal.coerceIn(0f, 1f)
            val left = (w - visibleW) / 2f
            c.clipRect(left, 0f, left + visibleW, h.toFloat())
        }
        if (reveal > 0f) visual.draw(c, w, h, frame, layout)
        c.restore()

        filter.publish(slot.bitmap)
        lastKey = key
        return true
    }
}
