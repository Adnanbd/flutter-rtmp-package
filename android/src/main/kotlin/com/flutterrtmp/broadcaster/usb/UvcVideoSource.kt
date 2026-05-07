package com.flutterrtmp.broadcaster.usb

import android.graphics.SurfaceTexture
import android.util.Log
import com.flutterrtmp.broadcaster.diag.DiagLogger
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

class UvcVideoSource(
    private val registry: UsbDeviceRegistry,
    val deviceId: Int
) : VideoSource() {

    companion object {
        private const val TAG = "UvcVideoSource"
    }

    private var ctrlBlock: USBMonitor.UsbControlBlock? = null
    private var uvcCamera: UVCCamera? = null
    private var running = false
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    private var lastFps: Int = 0
    private var lastRotation: Int = 0

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        lastWidth = width
        lastHeight = height
        lastFps = fps
        lastRotation = rotation
        DiagLogger.log(TAG, "create: deviceId=$deviceId ${width}x${height} fps=$fps rotation=$rotation alreadyOpen=${uvcCamera != null}")
        if (uvcCamera != null) {
            DiagLogger.log(TAG, "create: already open — skipping re-open (deviceId=$deviceId)")
            return true
        }
        return try {
            ctrlBlock = registry.openDevice(deviceId)
                ?: throw IllegalStateException("openDevice($deviceId) returned null")
            val camera = UVCCamera()
            camera.open(ctrlBlock!!)
            val sizeOk = trySetPreviewSize(camera, width, height)
            uvcCamera = camera
            DiagLogger.log(TAG, "create: OK deviceId=$deviceId ${width}x${height} sizeOk=$sizeOk")
            true
        } catch (e: Exception) {
            DiagLogger.logError("UVC_CREATE", "deviceId=$deviceId ${width}x${height}", e)
            throw e
        }
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        if (uvcCamera == null) {
            DiagLogger.log(TAG, "start: uvcCamera was null — lazy re-create deviceId=$deviceId ${lastWidth}x${lastHeight}")
            if (lastWidth == 0 || lastHeight == 0) {
                val msg = "UVC camera not opened and no cached size — create() never ran"
                DiagLogger.logError("UVC_START", "deviceId=$deviceId uvcCamera=null lastSize=0")
                throw IllegalStateException(msg)
            }
            try {
                create(lastWidth, lastHeight, lastFps, lastRotation)
            } catch (e: Exception) {
                DiagLogger.logError("UVC_LAZY_REOPEN_FAILED", "deviceId=$deviceId", e)
                throw e
            }
        }
        val camera = uvcCamera ?: run {
            val msg = "UVC camera still null after re-create"
            DiagLogger.logError("UVC_START", "deviceId=$deviceId uvcCamera=null after re-create")
            throw IllegalStateException(msg)
        }
        DiagLogger.log(TAG, "start: deviceId=$deviceId surface=${surfaceTexture.hashCode()} running=$running")
        try {
            camera.setPreviewTexture(surfaceTexture)
            camera.startPreview()
            running = true
            DiagLogger.log(TAG, "start: OK UVC preview started deviceId=$deviceId")
        } catch (e: Exception) {
            DiagLogger.logError("UVC_START", "deviceId=$deviceId", e)
            throw e
        }
    }

    override fun stop() {
        // Fully close the camera here. This lets the next create() open a fresh
        // UVCCamera() instance, which avoids the stale-pthread-handle SIGABRT that
        // occurs when startPreview() calls prepare_preview() on a camera whose
        // capture thread was already joined by a prior stopPreview() call without
        // the handle being zeroed by the library.
        val cam = uvcCamera ?: run {
            running = false
            DiagLogger.log(TAG, "stop: no-op (camera already null, deviceId=$deviceId)")
            return
        }
        uvcCamera = null
        running = false
        DiagLogger.log(TAG, "stop: closing UVC camera (deviceId=$deviceId)")
        try { cam.stopPreview() } catch (_: Exception) {}
        try { cam.close() } catch (_: Exception) {}
        try { cam.destroy() } catch (_: Exception) {}
        // Clear stale ctrlBlock from registry cache so create() gets a fresh one
        ctrlBlock?.let { registry.invalidateDevice(deviceId) }
        ctrlBlock = null
    }

    override fun release() {
        val cam = uvcCamera ?: run {
            DiagLogger.log(TAG, "release: already closed (deviceId=$deviceId)")
            ctrlBlock?.let { registry.invalidateDevice(deviceId) }
            ctrlBlock = null
            running = false
            return
        }
        uvcCamera = null
        running = false
        DiagLogger.log(TAG, "release: closing UVC camera (deviceId=$deviceId)")
        try { cam.stopPreview() } catch (_: Exception) {}
        try { cam.close() } catch (_: Exception) {}
        try { cam.destroy() } catch (_: Exception) {}
        ctrlBlock?.let { registry.invalidateDevice(deviceId) }
        ctrlBlock = null
    }

    override fun isRunning(): Boolean = running

    private fun trySetPreviewSize(camera: UVCCamera, width: Int, height: Int): Boolean {
        val formats = listOf(
            UVCCamera.FRAME_FORMAT_MJPEG to "MJPEG",
            UVCCamera.FRAME_FORMAT_YUYV to "YUYV"
        )
        for ((fmt, fmtName) in formats) {
            try {
                camera.setPreviewSize(width, height, fmt)
                DiagLogger.log(TAG, "trySetPreviewSize: OK ${width}x${height} format=$fmtName")
                return true
            } catch (e: Exception) {
                DiagLogger.log(TAG, "trySetPreviewSize: $fmtName failed — $e")
            }
        }
        // Fall back to default (let driver pick)
        return try {
            camera.setPreviewSize(width, height)
            DiagLogger.log(TAG, "trySetPreviewSize: OK ${width}x${height} format=DEFAULT")
            true
        } catch (e: Exception) {
            DiagLogger.log(TAG, "trySetPreviewSize: DEFAULT fallback also failed — $e")
            false
        }
    }
}
