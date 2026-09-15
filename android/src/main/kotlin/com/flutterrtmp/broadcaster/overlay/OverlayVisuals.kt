package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drawable dynamic overlay content (ADR 0015). Survives pipeline rebuilds; drawn by [LayerRenderer].
 * Main thread only.
 */
interface OverlayVisual {
    /** Changes whenever [draw] output for [frame] would change. */
    fun frameKey(frame: LayerFrame): Any

    /**
     * Draw into the `w × h` px rect at the canvas origin (post-rotation orientation).
     * [layout] is the ticker layout for the current frame (null for other content).
     */
    fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?)
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

/** Static bitmap (image or pre-rendered text), scaled to the display rect. */
class BitmapVisual(private val bitmap: Bitmap) : OverlayVisual {
    private val dst = RectF()

    override fun frameKey(frame: LayerFrame): Any = 0

    override fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }
}

/** Looping GIF: pre-decoded frames, frame chosen from the overlay's content clock. */
class GifVisual(private val frames: Array<Bitmap>, private val timeline: GifTimeline) : OverlayVisual {
    private val dst = RectF()

    override fun frameKey(frame: LayerFrame): Any = timeline.frameAt(frame.contentTimeMs)

    override fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(frames[timeline.frameAt(frame.contentTimeMs)], null, dst, bitmapPaint)
    }
}

/**
 * Multi-line text wrapped to a width (`maxLines > 1`). The host calls [measure] with the wrap width, then [draw]
 * with the display size; the last measured layout is drawn scaled to it.
 */
class WrappedTextVisual(
    private val text: String,
    private val paint: TextPaint,
    private val background: Int?,
    private val paddingPx: Float,
    private val maxLines: Int,
    private val alignment: Layout.Alignment
) : OverlayVisual {
    private var layoutWidthPx = -1
    private var layout: StaticLayout? = null
    private var measured = 1 to 1

    /** Size in px (padding included) when wrapped to [widthPx]. */
    fun measure(widthPx: Int): Pair<Int, Int> {
        if (widthPx != layoutWidthPx) {
            val inner = max(1, (widthPx - 2 * paddingPx).toInt())
            val l = buildLayout(inner)
            layout = l
            layoutWidthPx = widthPx
            measured = widthPx to max(1, ceil(l.height + 2 * paddingPx).toInt())
        }
        return measured
    }

    override fun frameKey(frame: LayerFrame): Any = layoutWidthPx

    override fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?) {
        val l = this.layout ?: return
        background?.let { canvas.drawColor(it) }
        canvas.save()
        canvas.scale(w / measured.first.toFloat(), h / measured.second.toFloat())
        canvas.translate(paddingPx, paddingPx)
        l.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(innerWidth: Int): StaticLayout {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, innerWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        // API 21–22: no maxLines; cut at the last allowed line and append an ellipsis until it fits.
        @Suppress("DEPRECATION")
        fun layoutOf(t: CharSequence) = StaticLayout(t, paint, innerWidth, alignment, 1f, 0f, false)
        var l = layoutOf(text)
        if (l.lineCount <= maxLines) return l
        var end = l.getLineEnd(maxLines - 1)
        while (end > 0) {
            end--
            l = layoutOf(text.substring(0, end).trimEnd() + "…")
            if (l.lineCount <= maxLines) return l
        }
        return l
    }
}

/**
 * Rotating image/GIF items in one slot (docs/specs/dynamic-overlays.md §12). Each item is contain-fit and centered
 * in the slot; the slot is the layer rect, so push transitions are clipped by the layer bitmap itself.
 */
class CarouselVisual(
    private val items: List<Item>,
    private val timeline: CarouselTimeline,
    private val transition: CarouselTransition
) : OverlayVisual {

    /** One item's visual and intrinsic size in px. */
    class Item(val visual: OverlayVisual, val width: Int, val height: Int)

    override fun frameKey(frame: LayerFrame): Any {
        val s = timeline.at(frame.contentTimeMs)
        val next = s.next
        return listOf(
            s.index,
            items[s.index].visual.frameKey(frame.copy(contentTimeMs = s.itemTimeMs)),
            next,
            next?.let { items[it].visual.frameKey(frame.copy(contentTimeMs = s.nextItemTimeMs)) },
            s.progress?.let { (it * 1000f).toInt() }
        )
    }

    override fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?) {
        val s = timeline.at(frame.contentTimeMs)
        val next = s.next
        val progress = s.progress
        if (next == null || progress == null) {
            drawItem(canvas, w, h, frame, s.index, s.itemTimeMs, 0f, 0f, 255)
            return
        }
        val e = OverlayAnimationMath.ease(transition.easing, progress)
        when (transition.type) {
            CarouselTransitionType.CUT -> drawItem(canvas, w, h, frame, s.index, s.itemTimeMs, 0f, 0f, 255)
            CarouselTransitionType.CROSSFADE -> {
                drawItem(canvas, w, h, frame, s.index, s.itemTimeMs, 0f, 0f, ((1f - e) * 255f).roundToInt())
                drawItem(canvas, w, h, frame, next, s.nextItemTimeMs, 0f, 0f, (e * 255f).roundToInt())
            }
            CarouselTransitionType.PUSH -> {
                // Unit vector from the slot toward the side the incoming item enters from.
                val (ux, uy) = when (transition.edge) {
                    Edge.LEFT -> -1f to 0f
                    Edge.RIGHT -> 1f to 0f
                    Edge.TOP -> 0f to -1f
                    Edge.BOTTOM -> 0f to 1f
                }
                val fw = w.toFloat()
                val fh = h.toFloat()
                drawItem(canvas, w, h, frame, s.index, s.itemTimeMs, -ux * fw * e, -uy * fh * e, 255)
                drawItem(canvas, w, h, frame, next, s.nextItemTimeMs, ux * fw * (1f - e), uy * fh * (1f - e), 255)
            }
        }
    }

    private fun drawItem(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, index: Int, itemTimeMs: Long, dx: Float, dy: Float, alpha: Int) {
        if (alpha <= 0) return
        val item = items[index]
        val scale = min(w / item.width.toFloat(), h / item.height.toFloat())
        val dw = max(1, (item.width * scale).roundToInt())
        val dh = max(1, (item.height * scale).roundToInt())
        val count = canvas.save()
        canvas.translate(dx + (w - dw) / 2f, dy + (h - dh) / 2f)
        if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, dw.toFloat(), dh.toFloat(), alpha)
        item.visual.draw(canvas, dw, dh, frame.copy(contentTimeMs = itemTimeMs), null)
        canvas.restoreToCount(count)
    }
}

/** Scrolling one-line text over an optional background band (docs/specs/dynamic-overlays.md §6). */
class TickerVisual(
    private val text: String,
    private val paint: Paint,
    private val background: Int?,
    private val metrics: TickerMetrics,
    private val paddingPx: Float,
    /** −ascent of the font, so the baseline is `padding + ascentPx`. */
    private val ascentPx: Float
) : OverlayVisual {

    override fun frameKey(frame: LayerFrame): Any =
        Pair((frame.tickerDistancePx * 2f).toInt(), frame.tickerLastCopy)

    override fun draw(canvas: Canvas, w: Int, h: Int, frame: LayerFrame, layout: TickerMath.Layout?) {
        val l = layout ?: return
        background?.let { canvas.drawColor(it) }
        // Band px → render px (they differ only when the band was downscaled to fit).
        val sx = w / l.bandWidthPx
        val sy = h / metrics.bandHeightPx.toFloat()
        canvas.save()
        canvas.scale(sx, sy)
        val baseline = paddingPx + ascentPx
        val d = frame.tickerDistancePx
        for (k in TickerMath.visibleCopies(d, l, frame.tickerLastCopy)) {
            canvas.drawText(text, TickerMath.copyX(k, d, l, metrics.movesLeft), baseline, paint)
        }
        canvas.restore()
    }
}
