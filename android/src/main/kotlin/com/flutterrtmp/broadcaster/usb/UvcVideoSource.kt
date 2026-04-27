package com.flutterrtmp.broadcaster.usb

import android.graphics.SurfaceTexture
import android.util.Log
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.UVCCamera

class UvcVideoSource(
    private val registry: UsbDeviceRegistry,
    private val deviceId: Int
) : VideoSource() {

    companion object {
        private const val TAG = "UvcVideoSource"
    }

    private var uvcCamera: UVCCamera? = null
    private var running = false

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        return try {
            val ctrlBlock = registry.openDevice(deviceId)
            if (ctrlBlock == null) {
                Log.e(TAG, "create: openDevice($deviceId) returned null")
                return false
            }
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            trySetPreviewSize(camera, width, height)
            uvcCamera = camera
            Log.d(TAG, "create: UVC device opened — ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "create failed: $e")
            false
        }
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        val camera = uvcCamera ?: run {
            Log.e(TAG, "start: uvcCamera is null")
            return
        }
        try {
            camera.setPreviewTexture(surfaceTexture)
            camera.startPreview()
            running = true
            Log.d(TAG, "start: UVC preview started")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: $e")
        }
    }

    override fun stop() {
        try {
            uvcCamera?.stopPreview()
        } catch (_: Exception) {}
        running = false
        Log.d(TAG, "stop")
    }

    override fun release() {
        try {
            uvcCamera?.close()
            uvcCamera?.destroy()
        } catch (_: Exception) {}
        uvcCamera = null
        running = false
        Log.d(TAG, "release")
    }

    override fun isRunning(): Boolean = running

    private fun trySetPreviewSize(camera: UVCCamera, width: Int, height: Int) {
        val formats = listOf(
            UVCCamera.FRAME_FORMAT_MJPEG,
            UVCCamera.FRAME_FORMAT_YUYV
        )
        for (fmt in formats) {
            try {
                camera.setPreviewSize(width, height, fmt)
                Log.d(TAG, "trySetPreviewSize: ${width}x${height} fmt=$fmt OK")
                return
            } catch (e: Exception) {
                Log.w(TAG, "trySetPreviewSize: fmt=$fmt failed — $e")
            }
        }
        // Fall back to default (let driver pick)
        try { camera.setPreviewSize(width, height) } catch (e: Exception) {
            Log.w(TAG, "trySetPreviewSize: default fallback failed — $e")
        }
    }
}
