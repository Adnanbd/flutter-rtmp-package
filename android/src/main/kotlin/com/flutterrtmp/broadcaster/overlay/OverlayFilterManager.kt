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
            val correctedH = aspectCorrectHeight(bitmap, sponsor.width)
            val filter = ImageObjectFilterRender()
            filter.setImage(orientBitmap(bitmap))
            applySponsorPosition(filter, sponsor.x, sponsor.y, sponsor.width, correctedH)
            stream.getGlInterface().addFilter(filter)
            sponsorFilters.add(filter)
        }

        Log.d(TAG, "initLayers: sponsors=${sponsorFilters.size}, scoreband=lazy, encDims=${streamWidth}x${streamHeight}, isPortrait=$isPortrait")
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

        // Compute target placement in POST-rotation (stream output) frame.
        // Width 90%, height proportional, anchored 4% from bottom.
        val widthPct = 90f
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val postScaleX = widthPct
        val postScaleY = (widthPct / 100f) * (streamWidth.toFloat() / streamHeight.toFloat()) / bitmapAspect * 100f
        val bottomMarginPct = 4f
        val postPosX = (100f - postScaleX) / 2f
        val postPosY = 100f - postScaleY - bottomMarginPct

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
            Log.d(TAG, "updateScoreband[create]: bmp=${finalBitmap.width}x${finalBitmap.height}, scale=($scaleX,$scaleY)%, pos=($posX,$posY)%, isPortrait=$isPortrait, glFilters=${stream.getGlInterface().filtersCount()}")
        } else {
            existing.setImage(finalBitmap)
            existing.setScale(scaleX, scaleY)
            existing.setPosition(posX, posY)
            Log.d(TAG, "updateScoreband[update]: bmp=${finalBitmap.width}x${finalBitmap.height}, scale=($scaleX,$scaleY)%, pos=($posX,$posY)%")
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
            val correctedH = aspectCorrectHeight(bitmap, sponsor.width)
            val filter = ImageObjectFilterRender()
            filter.setImage(orientBitmap(bitmap))
            applySponsorPosition(filter, sponsor.x, sponsor.y, sponsor.width, correctedH)
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

    // Compute aspect-ratio-correct normalized height in post-rotation space.
    // Uses ORIGINAL (pre-orientBitmap) bitmap dimensions so the aspect ratio
    // reflects how the image should appear in the final stream output frame.
    private fun aspectCorrectHeight(bitmap: Bitmap, normalizedWidth: Float): Float {
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val pixelW = normalizedWidth * streamWidth
        val pixelH = pixelW / aspect
        return (pixelH / streamHeight).coerceAtMost(1f)
    }

    // Counter-rotates bitmap 90° CW so it appears upright after the frame's 90° CCW rotation.
    private fun orientBitmap(src: Bitmap): Bitmap {
        if (!isPortrait) return src
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // Sponsor position: normalized (0-1) top-left rect in POST-rotation frame
    // → 0-100% PRE-rotation coords. Same swap rule as scoreband.
    private fun applySponsorPosition(
        filter: ImageObjectFilterRender,
        x: Float, y: Float, w: Float, h: Float
    ) {
        val postPosX = x * 100f
        val postPosY = y * 100f
        val postScaleX = w * 100f
        val postScaleY = h * 100f
        if (isPortrait) {
            filter.setScale(postScaleY, postScaleX)
            filter.setPosition(100f - postPosY - postScaleY, postPosX)
        } else {
            filter.setScale(postScaleX, postScaleY)
            filter.setPosition(postPosX, postPosY)
        }
    }
}
