package com.flutterrtmp.broadcaster.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.flutter.plugin.platform.PlatformView

class CameraPreviewView(
    context: Context,
    private val streamManagerProvider: () -> CameraStreamManager?,
    // Lets the plugin drop its handle when this view goes away, so a later
    // `rebindPreview` never binds onto a disposed TextureView.
    private val onDisposed: (CameraPreviewView) -> Unit = {}
) : PlatformView, TextureView.SurfaceTextureListener {

    val textureView = TextureView(context).also {
        it.surfaceTextureListener = this
        it.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // SurfaceTexture is not available at construction time; wait for the callback.
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        streamManagerProvider()?.bindPreview(textureView)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        streamManagerProvider()?.unbindPreview()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun getView(): View = textureView

    override fun dispose() {
        streamManagerProvider()?.unbindPreview()
        onDisposed(this)
    }
}
