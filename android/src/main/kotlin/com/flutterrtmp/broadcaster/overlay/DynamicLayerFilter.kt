package com.flutterrtmp.broadcaster.overlay

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.pedro.encoder.input.gl.TextureLoader
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.encoder.utils.gl.StreamObjectBase

/**
 * Object filter for dynamic overlays (ADR 0015).
 *
 * - Uploads bitmaps **without recycling** them, so a [LayerRenderer] can reuse its bitmaps every frame.
 * - Frames are handed over with [publish]: the GL thread takes the newest bitmap at the start of [drawFilter]
 *   and uploads it in the same call, so a frame published between upload and `shouldLoad = false` is never lost.
 * - Uses its own stream object instead of `setImage`, whose `ImageStreamObject.load` logs on every call.
 */
class DynamicLayerFilter : ImageObjectFilterRender() {

    private val lock = Any()
    private var pending: Bitmap? = null
    private var uploading: Bitmap? = null
    private val frameObject = FrameStreamObject()

    init {
        textureLoader = KeepBitmapTextureLoader()
        streamObject = frameObject
    }

    /** Main thread: show [bitmap] from the next GL frame on. Replaces any not-yet-uploaded bitmap. */
    fun publish(bitmap: Bitmap) {
        synchronized(lock) { pending = bitmap }
    }

    /** True while the GL thread may still read [bitmap] — the renderer must not draw into it. */
    fun isBusy(bitmap: Bitmap): Boolean = synchronized(lock) { bitmap === pending || bitmap === uploading }

    override fun drawFilter() {
        val next = synchronized(lock) {
            val b = pending
            pending = null
            uploading = b
            b
        }
        try {
            if (next != null) {
                // On the GL thread: uploaded by super.drawFilter right below.
                frameObject.bitmap = next
                shouldLoad = true
            }
            super.drawFilter()
        } finally {
            if (next != null) synchronized(lock) { uploading = null }
        }
    }

    /** Holds the frame being uploaded. Never recycles: bitmaps belong to the LayerRenderer pool. */
    private class FrameStreamObject : StreamObjectBase() {
        var bitmap: Bitmap? = null

        override fun getWidth(): Int = bitmap?.width ?: 0
        override fun getHeight(): Int = bitmap?.height ?: 0
        override fun updateFrame(): Int = 0
        override fun recycle() {
            bitmap = null
        }
        override fun getNumFrames(): Int = 1
        override fun getBitmaps(): Array<Bitmap?> = arrayOf(bitmap)
    }

    /** Same as RootEncoder's TextureLoader.load, minus `bitmap.recycle()`. */
    private class KeepBitmapTextureLoader : TextureLoader() {
        override fun load(bitmaps: Array<Bitmap?>): IntArray {
            val ids = IntArray(bitmaps.size)
            GlUtil.createTextures(bitmaps.size, ids, 0)
            for (i in bitmaps.indices) {
                val b = bitmaps[i] ?: continue
                if (b.isRecycled) continue
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[i])
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            }
            return ids
        }
    }
}
