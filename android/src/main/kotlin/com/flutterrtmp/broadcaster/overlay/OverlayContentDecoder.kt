package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import com.pedro.encoder.utils.gl.gif.GifDecoder
import java.io.File
import java.security.MessageDigest
import java.text.Bidi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turns dynamic overlay content configs into drawable [OverlayVisual]s (docs/specs/dynamic-overlays.md §1, §6, §9–§10).
 * Returns null for undecodable image/GIF bytes (→ `OVERLAY_DECODE_FAILED`); throws [OverlayException] for
 * `OVERLAY_GIF_TOO_LARGE` and `OVERLAY_FONT_INVALID`. Main thread only.
 */
class OverlayContentDecoder(private val cacheDir: File) {
    companion object {
        const val MAX_GIF_FRAMES = 150
        const val MAX_GIF_DECODED_BYTES = 64L * 1024 * 1024
        const val MAX_CAROUSEL_DECODED_BYTES = 64L * 1024 * 1024
        /** Source bitmaps are subsampled above this; the stream frame is at most 1920 px. */
        private const val MAX_SOURCE_PX = 2048
        /** Pre-rendered text bitmaps are scaled down above this. */
        private const val MAX_TEXT_BITMAP_PX = 4096
        /** Nominal wrap width for the intrinsic size of wrapped text; the host re-measures at the placement width. */
        private const val DEFAULT_WRAP_WIDTH_PX = 1280
        private const val GIF_STATUS_OK = 0
        private const val GIF_STATUS_PARTIAL = 3
    }

    private val fonts = HashMap<String, Typeface>()

    fun decode(content: OverlayContentConfig): DecodedContent<OverlayVisual>? = when (content) {
        is OverlayContentConfig.Image -> decodeImage(content.bytes)
        is OverlayContentConfig.Gif -> decodeGif(content.bytes)
        is OverlayContentConfig.Text -> renderText(content)
        is OverlayContentConfig.Ticker -> measureTicker(content)
        is OverlayContentConfig.Carousel -> decodeCarousel(content)
    }

    /** Decoded bitmap bytes of the last [decodeImage] / [decodeGif] call (carousel memory cap). */
    private var lastDecodedBytes = 0L

    private fun decodeCarousel(content: OverlayContentConfig.Carousel): DecodedContent<OverlayVisual> {
        val items = ArrayList<CarouselVisual.Item>(content.items.size)
        var totalBytes = 0L
        var animated = false
        for ((i, item) in content.items.withIndex()) {
            val decoded = when (val c = item.content) {
                is OverlayContentConfig.Image -> decodeImage(c.bytes)
                is OverlayContentConfig.Gif -> decodeGif(c.bytes)
                else -> null
            } ?: throw OverlayException("OVERLAY_DECODE_FAILED", "carousel item $i could not be decoded")
            totalBytes += lastDecodedBytes
            if (totalBytes > MAX_CAROUSEL_DECODED_BYTES) {
                throw OverlayException(
                    "OVERLAY_CAROUSEL_TOO_LARGE",
                    "carousel items decode to more than ${MAX_CAROUSEL_DECODED_BYTES / (1024 * 1024)} MB (at item $i)"
                )
            }
            animated = animated || decoded.animated
            items.add(CarouselVisual.Item(decoded.handle, decoded.width, decoded.height))
        }
        val timeline = CarouselTimeline(content.effectiveIntervalsMs(), content.transition.effectiveMs)
        // Intrinsic slot: tallest item height, widest item aspect (spec §12).
        val slotH = items.maxOf { it.height }
        val maxAspect = items.maxOf { it.width.toFloat() / it.height.toFloat() }
        val slotW = max(1, ceil(maxAspect * slotH).toInt())
        return DecodedContent(
            CarouselVisual(items, timeline, content.transition),
            slotW,
            slotH,
            animated = animated,
            carousel = timeline,
            fillBox = true
        )
    }

    private fun decodeImage(bytes: ByteArray): DecodedContent<OverlayVisual>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SOURCE_PX || bounds.outHeight / sample > MAX_SOURCE_PX) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        lastDecodedBytes = bitmap.allocationByteCount.toLong()
        // Intrinsic size stays the source px, so "neither width nor height" sizing matches the image file.
        return DecodedContent(BitmapVisual(bitmap), bounds.outWidth, bounds.outHeight)
    }

    private fun decodeGif(bytes: ByteArray): DecodedContent<OverlayVisual>? {
        val decoder = GifDecoder()
        val status = decoder.read(bytes)
        val count = decoder.frameCount
        if ((status != GIF_STATUS_OK && status != GIF_STATUS_PARTIAL) || count <= 0) return null
        val w = decoder.width
        val h = decoder.height
        if (w <= 0 || h <= 0) return null
        val decodedBytes = w.toLong() * h * 4 * count
        if (count > MAX_GIF_FRAMES || decodedBytes > MAX_GIF_DECODED_BYTES) {
            throw OverlayException(
                "OVERLAY_GIF_TOO_LARGE",
                "GIF has $count frames at ${w}x$h (${decodedBytes / (1024 * 1024)} MB decoded); " +
                    "max $MAX_GIF_FRAMES frames and ${MAX_GIF_DECODED_BYTES / (1024 * 1024)} MB"
            )
        }
        val frames = ArrayList<Bitmap>(count)
        val delays = IntArray(count)
        for (i in 0 until count) {
            decoder.advance()
            // The decoder may reuse its bitmap between frames; keep a copy.
            val frame = decoder.nextFrame ?: return null
            frames.add(frame.copy(Bitmap.Config.ARGB_8888, false))
            delays[i] = decoder.getDelay(i)
        }
        decoder.clear()
        lastDecodedBytes = decodedBytes
        return DecodedContent(GifVisual(frames.toTypedArray(), GifTimeline(delays)), w, h, animated = count > 1)
    }

    private fun renderText(content: OverlayContentConfig.Text): DecodedContent<OverlayVisual> {
        val style = content.style
        if (style.maxLines > 1) return wrappedText(content)
        val paint = textPaint(style)
        val fm = paint.fontMetrics
        val pad = style.paddingPx
        val w = max(1, ceil(paint.measureText(content.text) + 2 * pad).toInt())
        val h = max(1, ceil(fm.descent - fm.ascent + 2 * pad).toInt())
        val scale = min(1f, MAX_TEXT_BITMAP_PX.toFloat() / max(w, h))
        val bitmap = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(scale, scale)
            style.background?.let { drawColor(it) }
            drawText(content.text, pad, pad - fm.ascent, paint)
        }
        return DecodedContent(BitmapVisual(bitmap), w, h)
    }

    private fun wrappedText(content: OverlayContentConfig.Text): DecodedContent<OverlayVisual> {
        val style = content.style
        val paint = TextPaint(textPaint(style))
        val alignment = when (style.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val visual = WrappedTextVisual(content.text, paint, style.background, style.paddingPx, style.maxLines, alignment)
        val (w, h) = visual.measure(DEFAULT_WRAP_WIDTH_PX)
        return DecodedContent(visual, w, h, wrap = visual::measure)
    }

    private fun measureTicker(content: OverlayContentConfig.Ticker): DecodedContent<OverlayVisual> {
        val style = content.style
        val paint = textPaint(style)
        val fm = paint.fontMetrics
        val textW = max(1f, paint.measureText(content.text))
        val bandH = max(1, ceil(fm.descent - fm.ascent + 2 * style.paddingPx).toInt())
        val movesLeft = when (content.direction) {
            // RTL scripts scroll left→right; everything else right→left (spec §6).
            TickerDirection.AUTO -> Bidi(content.text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()
            TickerDirection.RTL -> true
            TickerDirection.LTR -> false
        }
        val metrics = TickerMetrics(
            text = content.text,
            textWidthPx = textW,
            bandHeightPx = bandH,
            speedPxPerSec = content.speedPxPerSec,
            cycleDurationMs = content.cycleDurationMs,
            loop = content.loop,
            loopGap = content.loopGap,
            movesLeft = movesLeft
        )
        val visual = TickerVisual(content.text, paint, style.background, metrics, style.paddingPx, -fm.ascent)
        return DecodedContent(visual, ceil(textW).toInt(), bandH, ticker = metrics)
    }

    private fun textPaint(style: TextStyleConfig) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = style.fontSizePx
        color = style.color
        typeface = typeface(style.fontTtf)
    }

    /** TTF/OTF bytes → Typeface via a content-addressed cache file (Typeface has no byte-array API before 26). */
    private fun typeface(ttf: ByteArray?): Typeface {
        if (ttf == null) return Typeface.DEFAULT
        val key = MessageDigest.getInstance("SHA-1").digest(ttf).joinToString("") { "%02x".format(it) }
        fonts[key]?.let { return it }
        if (!looksLikeFont(ttf)) throw fontInvalid("not a TrueType/OpenType font")
        val dir = File(cacheDir, "overlay_fonts").apply { mkdirs() }
        val file = File(dir, "$key.ttf")
        if (!file.exists()) file.writeBytes(ttf)
        val tf = try {
            Typeface.createFromFile(file)
        } catch (t: Throwable) {
            null
        }
        // Newer Android versions return the default typeface instead of throwing on a bad file.
        if (tf == null || tf == Typeface.DEFAULT) {
            file.delete()
            throw fontInvalid("Typeface could not be created")
        }
        fonts[key] = tf
        return tf
    }

    private fun looksLikeFont(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        return tag == "   " || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    private fun fontInvalid(why: String) = OverlayException("OVERLAY_FONT_INVALID", "fontTtf is not loadable: $why")
}
