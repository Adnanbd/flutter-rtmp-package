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

        // Compute target placement in POST-rotation (stream output) frame.
        // Dart supplies width (1-100), x (0-100: 0=left, 100=right), y (0-100: 0=top, 100=bottom).
        // Height stays aspect-derived from bitmap.
        val widthPct = widthParam.coerceIn(1f, 100f)
        val xPct = xParam.coerceIn(0f, 100f)
        val yPct = yParam.coerceIn(0f, 100f)
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val postScaleX = widthPct
        val postScaleY = (widthPct / 100f) * (streamWidth.toFloat() / streamHeight.toFloat()) / bitmapAspect * 100f
        val postPosX = (xPct / 100f) * (100f - postScaleX)
        val postPosY = (yPct / 100f) * (100f - postScaleY)

        // Convert to PRE-rotation coords (where filter actually renders).
        val finalBitmap = orientBitmap(bitmap)
        val scaleX: Float
        val scaleY: Float
        val posX: Float
        val posY: Float
        if (isPortrait) {
            // Frame rotates 90° CCW. Pre→Post: (xPre,yPre) → (yPre, Wpre-xPre).
            // Pre-rect top-left (post-rotation top-left maps to pre x = Wpre-(postY+postScaleY)).
            scaleX = postScaleY
            scaleY = postScaleX
            posX = 100f - postPosY - postScaleY
            posY = postPosX
        } else {
            scaleX = postScaleX
            scaleY = postScaleY
            posX = postPosX
            posY = postPosY
        }

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

    // Sponsor placement (post-rotation 0-100% frame). BoxFit.contain sizing + edge anchor rules.
    // Pre/post rotation swap matches scoreband.
    private fun applySponsorPosition(
        filter: ImageObjectFilterRender,
        bitmap: Bitmap,
        cfg: SponsorConfig
    ) {
        val widthPct = cfg.width.coerceIn(1, 100).toFloat()
        val heightPct = cfg.height.coerceIn(1, 100).toFloat()
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val frameAspect = streamWidth.toFloat() / streamHeight.toFloat()

        // BoxFit.contain in frame-% space.
        val hForW = widthPct * frameAspect / bitmapAspect
        val finalW: Float
        val finalH: Float
        if (hForW <= heightPct) {
            finalW = widthPct
            finalH = hForW
        } else {
            finalH = heightPct
            finalW = heightPct * bitmapAspect / frameAspect
        }

        // Edge-anchor rules per axis (both-given or neither → center).
        val maxPosX = (100f - finalW).coerceAtLeast(0f)
        val maxPosY = (100f - finalH).coerceAtLeast(0f)
        val postPosX = when {
            cfg.left != null && cfg.right == null -> cfg.left.toFloat().coerceIn(0f, maxPosX)
            cfg.right != null && cfg.left == null -> (100f - finalW - cfg.right.toFloat()).coerceIn(0f, maxPosX)
            else -> maxPosX / 2f
        }
        val postPosY = when {
            cfg.top != null && cfg.bottom == null -> cfg.top.toFloat().coerceIn(0f, maxPosY)
            cfg.bottom != null && cfg.top == null -> (100f - finalH - cfg.bottom.toFloat()).coerceIn(0f, maxPosY)
            else -> maxPosY / 2f
        }

        if (isPortrait) {
            filter.setScale(finalH, finalW)
            filter.setPosition(100f - postPosY - finalH, postPosX)
        } else {
            filter.setScale(finalW, finalH)
            filter.setPosition(postPosX, postPosY)
        }
    }
}
