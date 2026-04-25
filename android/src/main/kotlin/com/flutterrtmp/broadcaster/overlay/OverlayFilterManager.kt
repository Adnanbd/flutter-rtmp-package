package com.flutterrtmp.broadcaster.overlay

import android.graphics.BitmapFactory
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.generic.GenericStream

class OverlayFilterManager(
    private val streamWidth: Int,
    private val streamHeight: Int
) {

    companion object {
        private const val TAG = "OverlayFilterManager"
    }

    private val sponsorFilters = mutableListOf<ImageObjectFilterRender>()
    private var scorebandFilter: ImageObjectFilterRender? = null
    private var streamRef: GenericStream? = null

    // Initializes sponsor layers. Scoreband filter is created lazily on first
    // updateScoreband() call (canonical RootEncoder pattern: configure filter
    // fully — setImage + setScale + setPosition — BEFORE addFilter).
    fun initLayers(
        stream: GenericStream,
        sponsorList: List<SponsorConfig>
    ) {
        streamRef = stream
        sponsorFilters.clear()
        scorebandFilter = null

        for (sponsor in sponsorList) {
            val bitmap = BitmapFactory.decodeByteArray(sponsor.bytes, 0, sponsor.bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode sponsor bitmap — layer skipped")
                continue
            }
            val filter = ImageObjectFilterRender()
            filter.setImage(bitmap)
            applyPosition(filter, sponsor.x, sponsor.y, sponsor.width, sponsor.height)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
        }

        Log.d(TAG, "initLayers: sponsors=${sponsorFilters.size}, scoreband=lazy, streamDims=${streamWidth}x${streamHeight}")
    }

    fun updateScoreband(pngBytes: ByteArray) {
        val stream = streamRef
        if (stream == null) {
            Log.w(TAG, "updateScoreband: streamRef NULL — initLayers not called")
            return
        }
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        if (bitmap == null) {
            Log.w(TAG, "Failed to decode scoreband PNG — update skipped (bytes=${pngBytes.size})")
            return
        }

        val centreAlpha = android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))

        // RootEncoder Sprite: scale and position both 0-100% of stream frame.
        // (0,0) = top-left, (100,100) = bottom-right.
        val widthPct = 90f
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val heightPct = (widthPct / 100f) * (streamWidth.toFloat() / streamHeight.toFloat()) / bitmapAspect * 100f
        val bottomMarginPct = 4f
        val posX = (100f - widthPct) / 2f
        val posY = 100f - heightPct - bottomMarginPct

        val existing = scorebandFilter
        if (existing == null) {
            // First call: create filter fully configured, THEN add to GL.
            val filter = ImageObjectFilterRender()
            filter.setImage(bitmap)
            filter.setScale(widthPct, heightPct)
            filter.setPosition(posX, posY)
            stream.getGlInterface().addFilter(filter)
            scorebandFilter = filter
            Log.d(TAG, "updateScoreband[create]: bmp=${bitmap.width}x${bitmap.height}, alpha=$centreAlpha, scale=${widthPct}x${heightPct}%, pos=($posX,$posY)%, glFilters=${stream.getGlInterface().filtersCount()}")
        } else {
            existing.setImage(bitmap)
            existing.setScale(widthPct, heightPct)
            existing.setPosition(posX, posY)
            Log.d(TAG, "updateScoreband[update]: bmp=${bitmap.width}x${bitmap.height}, alpha=$centreAlpha, scale=${widthPct}x${heightPct}%, pos=($posX,$posY)%")
        }
    }

    fun updateSponsors(stream: GenericStream, sponsorList: List<SponsorConfig>) {
        for (filter in sponsorFilters) {
            stream.getGlInterface().removeFilter(filter)
        }
        sponsorFilters.clear()

        for (sponsor in sponsorList) {
            val bitmap = BitmapFactory.decodeByteArray(sponsor.bytes, 0, sponsor.bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode sponsor bitmap — layer skipped")
                continue
            }
            val filter = ImageObjectFilterRender()
            filter.setImage(bitmap)
            applyPosition(filter, sponsor.x, sponsor.y, sponsor.width, sponsor.height)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
        }
    }

    fun release(stream: GenericStream) {
        stream.getGlInterface().clearFilters()
        sponsorFilters.clear()
        scorebandFilter = null
        streamRef = null
    }

    // Normalized 0-1 top-left rect → RootEncoder's 0-100% screen coords.
    // Sprite: (0,0) = top-left, (100,100) = bottom-right.
    private fun applyPosition(
        filter: ImageObjectFilterRender,
        x: Float, y: Float, w: Float, h: Float
    ) {
        filter.setScale(w * 100f, h * 100f)
        filter.setPosition(x * 100f, y * 100f)
    }
}
