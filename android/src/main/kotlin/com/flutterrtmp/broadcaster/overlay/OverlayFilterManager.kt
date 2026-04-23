package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
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

    // Initializes all overlay layers in render order: sponsor_0…N → scoreband.
    // Must be called after prepareVideo/prepareAudio and before startPreview.
    fun initLayers(
        stream: GenericStream,
        sponsorList: List<SponsorConfig>
    ) {
        sponsorFilters.clear()
        scorebandFilter = null

        for (sponsor in sponsorList) {
            val bitmap = BitmapFactory.decodeByteArray(sponsor.bytes, 0, sponsor.bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode sponsor bitmap — layer skipped")
                continue
            }
            val filter = ImageObjectFilterRender()
            applyPosition(filter, sponsor.x, sponsor.y, sponsor.width, sponsor.height)
            filter.setImage(bitmap)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
        }

        // Scoreband layer: invisible placeholder until first updateScoreband call.
        val sbFilter = ImageObjectFilterRender()
        sbFilter.setAlpha(0f)
        stream.getGlInterface().addFilter(sbFilter)
        scorebandFilter = sbFilter
    }

    // Thread-safe: RootEncoder dispatches setImage to the GL thread internally.
    fun updateScoreband(pngBytes: ByteArray) {
        val filter = scorebandFilter ?: return
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        if (bitmap == null) {
            Log.w(TAG, "Failed to decode scoreband PNG — update skipped")
            return
        }

        // Scale: full stream width; height preserves aspect ratio, capped at stream height.
        // setScale takes percentage (0-100), where 100 = 100% of the frame dimension.
        val pixelH = (streamWidth.toFloat() * bitmap.height.toFloat() / bitmap.width.toFloat())
            .coerceAtMost(streamHeight.toFloat())
        val pctH = pixelH / streamHeight.toFloat() * 100f

        filter.setScale(100f, pctH)

        // Position: center horizontally (ndcX=0), bottom-aligned.
        // NDC y of center = -1 + pctH/100 (y=-1 is bottom edge in RootEncoder).
        val ndcY = -1f + pctH / 100f
        filter.setPosition(0f, ndcY)

        filter.setAlpha(1f)
        filter.setImage(bitmap)
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
            applyPosition(filter, sponsor.x, sponsor.y, sponsor.width, sponsor.height)
            filter.setImage(bitmap)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
        }
    }

    fun release(stream: GenericStream) {
        stream.getGlInterface().clearFilters()
        sponsorFilters.clear()
        scorebandFilter = null
    }

    // Converts normalized top-left (x, y, w, h in 0-1) to RootEncoder's percentage scale
    // and NDC center position (y-up, origin = center of frame).
    private fun applyPosition(
        filter: ImageObjectFilterRender,
        x: Float, y: Float, w: Float, h: Float
    ) {
        // setScale takes percentage (0-100), where 100 = 100% of the frame dimension.
        filter.setScale(w * 100f, h * 100f)

        // Convert normalized top-left to NDC center: x∈[-1,1], y∈[-1,1] (y-up).
        val ndcX = (x + w / 2f) * 2f - 1f
        val ndcY = 1f - (y + h / 2f) * 2f
        filter.setPosition(ndcX, ndcY)
    }
}
