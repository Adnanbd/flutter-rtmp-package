package com.flutterrtmp.broadcaster.camera

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class CameraPreviewFactory(
    private val streamManagerProvider: () -> CameraStreamManager?,
    // Reports the live preview view back to the plugin, so a `rebindPreview` call
    // from Dart has a TextureView to bind onto. Without it the plugin holds no
    // handle at all and the only recovery from a stale preview is switchCamera.
    private val onViewCreated: (CameraPreviewView) -> Unit = {},
    private val onViewDisposed: (CameraPreviewView) -> Unit = {}
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return CameraPreviewView(context, streamManagerProvider, onViewDisposed).also(onViewCreated)
    }
}
