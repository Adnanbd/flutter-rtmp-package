package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.generic.GenericStream

class OverlayFilterManager(
    // Encoder (post-rotation) dimensions. Portrait: 720x1280. Landscape: 1280x720.
    private val streamWidth: Int,
    private val streamHeight: Int,
    // True when stream rotation is applied (setStreamRotation(270)).
    // Filters render in PRE-rotation (camera-native landscape) coordinate space,
    // so portrait needs counter-rotated bitmap and swapped scale/position.
    private val isPortrait: Boolean
) {

    companion object {
        private const val TAG = "OverlayFilterManager"
    }

    private val frame = OverlayGeometry.FrameSize(streamWidth, streamHeight)
    private val sponsorFilters = mutableListOf<ImageObjectFilterRender>()
    private var scorebandFilter: ImageObjectFilterRender? = null
    private var streamRef: GenericStream? = null

    data class OverlayOpResult(val input: Int, val added: Int, val decodeFails: Int)

    fun initLayers(
        stream: GenericStream,
        sponsorList: List<SponsorConfig>
    ): OverlayOpResult {
        streamRef = stream
        sponsorFilters.clear()
        scorebandFilter = null

        var decodeFails = 0
        for ((idx, sponsor) in sponsorList.withIndex()) {
            val bitmap = BitmapFactory.decodeByteArray(sponsor.bytes, 0, sponsor.bytes.size)
            if (bitmap == null) {
                Log.e(TAG, "initLayers: decode FAIL idx=$idx bytes=${sponsor.bytes.size}")
                decodeFails++
                continue
            }
            val filter = ImageObjectFilterRender()
            filter.setImage(orientBitmap(bitmap))
            applySponsorPosition(filter, bitmap, sponsor)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
            Log.d(TAG, "initLayers[add idx=$idx]: bmp=${bitmap.width}x${bitmap.height}, " +
                "L=${sponsor.left} R=${sponsor.right} T=${sponsor.top} B=${sponsor.bottom} w=${sponsor.width} h=${sponsor.height}, " +
                "glFilters=${stream.getGlInterface().filtersCount()}")
        }

        Log.d(TAG, "initLayers: input=${sponsorList.size}, added=${sponsorFilters.size}, decodeFails=$decodeFails, " +
            "scoreband=lazy, encDims=${streamWidth}x${streamHeight}, isPortrait=$isPortrait")
        return OverlayOpResult(sponsorList.size, sponsorFilters.size, decodeFails)
    }

    fun updateScoreband(pngBytes: ByteArray, widthParam: Float, xParam: Float, yParam: Float) {
        val stream = streamRef
        if (stream == null) {
            Log.e(TAG, "updateScoreband: streamRef NULL — initLayers not called")
            throw IllegalStateException("OVERLAY_NOT_INITIALIZED: updateScoreband called before initLayers — call configure() first")
        }
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode scoreband PNG — update skipped (bytes=${pngBytes.size})")
            throw IllegalArgumentException("OVERLAY_DECODE_FAILED: scoreband PNG decode returned null (bytes=${pngBytes.size})")
        }

        // Placement math lives in OverlayGeometry (post-rotation rect → pre-rotation filter transform).
        val finalBitmap = orientBitmap(bitmap)
        val rect = OverlayGeometry.scorebandRect(
            widthParam, xParam, yParam,
            bitmap.width.toFloat() / bitmap.height.toFloat(),
            frame
        )
        val t = OverlayGeometry.toFilter(rect, isPortrait)
        val scaleX = t.scaleX
        val scaleY = t.scaleY
        val posX = t.posX
        val posY = t.posY
        val widthPct = rect.w
        val xPct = xParam.coerceIn(0f, 100f)
        val yPct = yParam.coerceIn(0f, 100f)

        val existing = scorebandFilter
        if (existing == null) {
            val filter = ImageObjectFilterRender()
            filter.setImage(finalBitmap)
            filter.setScale(scaleX, scaleY)
            filter.setPosition(posX, posY)
            stream.getGlInterface().addFilter(filter)
            scorebandFilter = filter
            Log.d(TAG, "updateScoreband[create]: bmp=${finalBitmap.width}x${finalBitmap.height}, scale=($scaleX,$scaleY)%, pos=($posX,$posY)%, dartW=$widthPct dartX=$xPct dartY=$yPct, isPortrait=$isPortrait, glFilters=${stream.getGlInterface().filtersCount()}")
        } else {
            existing.setImage(finalBitmap)
            existing.setScale(scaleX, scaleY)
            existing.setPosition(posX, posY)
            Log.d(TAG, "updateScoreband[update]: bmp=${finalBitmap.width}x${finalBitmap.height}, scale=($scaleX,$scaleY)%, pos=($posX,$posY)%, dartW=$widthPct dartX=$xPct dartY=$yPct")
        }
    }

    fun updateSponsors(stream: GenericStream, sponsorList: List<SponsorConfig>): OverlayOpResult {
        for (filter in sponsorFilters) {
            stream.getGlInterface().removeFilter(filter)
        }
        sponsorFilters.clear()

        var decodeFails = 0
        for ((idx, sponsor) in sponsorList.withIndex()) {
            val bitmap = BitmapFactory.decodeByteArray(sponsor.bytes, 0, sponsor.bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "updateSponsors: decode FAIL idx=$idx bytes=${sponsor.bytes.size}")
                decodeFails++
                continue
            }
            val filter = ImageObjectFilterRender()
            filter.setImage(orientBitmap(bitmap))
            applySponsorPosition(filter, bitmap, sponsor)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
            Log.d(TAG, "updateSponsors[add idx=$idx]: bmp=${bitmap.width}x${bitmap.height}, " +
                "L=${sponsor.left} R=${sponsor.right} T=${sponsor.top} B=${sponsor.bottom} w=${sponsor.width} h=${sponsor.height}, " +
                "glFilters=${stream.getGlInterface().filtersCount()}")
        }
        Log.d(TAG, "updateSponsors: input=${sponsorList.size}, added=${sponsorFilters.size}, decodeFails=$decodeFails")
        return OverlayOpResult(sponsorList.size, sponsorFilters.size, decodeFails)
    }

    fun release(stream: GenericStream) {
        stream.getGlInterface().clearFilters()
        sponsorFilters.clear()
        scorebandFilter = null
        streamRef = null
    }

    // Counter-rotates bitmap 90° CW so it appears upright after the frame's 90° CCW rotation.
    private fun orientBitmap(src: Bitmap): Bitmap {
        if (!isPortrait) return src
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // Sponsor placement (post-rotation 0-100% frame): BoxFit.contain + edge anchors, via OverlayGeometry.
    private fun applySponsorPosition(
        filter: ImageObjectFilterRender,
        bitmap: Bitmap,
        cfg: SponsorConfig
    ) {
        val rect = OverlayGeometry.sponsorRect(
            cfg.left, cfg.right, cfg.top, cfg.bottom, cfg.width, cfg.height,
            bitmap.width.toFloat() / bitmap.height.toFloat(),
            frame
        )
        val t = OverlayGeometry.toFilter(rect, isPortrait)
        filter.setScale(t.scaleX, t.scaleY)
        filter.setPosition(t.posX, t.posY)
    }
}
