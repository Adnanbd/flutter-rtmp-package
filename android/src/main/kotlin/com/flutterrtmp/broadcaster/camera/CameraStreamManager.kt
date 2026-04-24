package com.flutterrtmp.broadcaster.camera

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.flutterrtmp.broadcaster.overlay.OverlayFilterManager
import com.flutterrtmp.broadcaster.overlay.SponsorConfig
import com.flutterrtmp.broadcaster.rtmp.RtmpConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.generic.GenericStream
import io.flutter.plugin.common.EventChannel

class CameraStreamManager(private val context: Context, private val activity: android.app.Activity) {

    companion object {
        private const val TAG = "CameraStreamManager"
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
        private const val DEFAULT_FPS = 30
        private const val DEFAULT_BITRATE = 2_500_000
        private const val DEFAULT_KEYFRAME = 2
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val connectChecker = RtmpConnectChecker(
        onConnectedCallback = { reconnectAttempt = 0 },
        onDisconnectedCallback = { scheduleReconnect() }
    )

    val genericStream: GenericStream by lazy { GenericStream(context, connectChecker) }
    private var overlayFilterManager: OverlayFilterManager? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private var encWidth = 0
    private var encHeight = 0
    var rtmpEndpoint: String = ""
        private set
    private var isPreviewReady = false
    private var isConfigured = false
    private var intentionalStop = false
    private var reconnectAttempt = 0

    val isStreaming: Boolean get() = isConfigured && genericStream.isStreaming

    fun setSink(sink: EventChannel.EventSink?) {
        connectChecker.sink = sink
    }

    fun initPreviewOnly(width: Int, height: Int, fps: Int, orientation: String, initialFacing: String) {
        if (isPreviewReady) return

        encWidth = width
        encHeight = height

        val videoOk = genericStream.prepareVideo(width, height, DEFAULT_BITRATE, fps, DEFAULT_KEYFRAME, 0)
        val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        if (!videoOk || !audioOk) {
            Log.e(TAG, "initPreviewOnly prepare failed: video=$videoOk audio=$audioOk")
            throw IllegalStateException("Preview prepare failed (video=$videoOk, audio=$audioOk)")
        }

        configureGlForOrientation(orientation)

        overlayFilterManager = OverlayFilterManager(width, height)
        overlayFilterManager?.initLayers(genericStream, emptyList())

        switchCamera(initialFacing)

        isPreviewReady = true
    }

    @Suppress("UNCHECKED_CAST")
    fun configure(
        rtmpEndpoint: String,
        sponsors: List<SponsorConfig>,
        width: Int,
        height: Int,
        fps: Int,
        videoBitrate: Int,
        keyframeIntervalSeconds: Int,
        orientation: String,
        initialFacing: String
    ) {
        this.rtmpEndpoint = rtmpEndpoint
        this.encWidth = width
        this.encHeight = height

        if (!isPreviewReady || width != encWidth || height != encHeight) {
            genericStream.release()
            isPreviewReady = false

            val videoOk = genericStream.prepareVideo(width, height, videoBitrate, fps, keyframeIntervalSeconds, 0)
            val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
            if (!videoOk || !audioOk) {
                Log.e(TAG, "configure prepare failed: video=$videoOk audio=$audioOk")
                throw IllegalStateException("Configure failed (video=$videoOk, audio=$audioOk)")
            }

            configureGlForOrientation(orientation)

            overlayFilterManager = OverlayFilterManager(width, height)
            overlayFilterManager?.initLayers(genericStream, sponsors)

            switchCamera(initialFacing)

            isPreviewReady = true
        } else {
            overlayFilterManager?.initLayers(genericStream, sponsors)
            switchCamera(initialFacing)
        }

        isConfigured = true
    }

    fun bindPreview(textureView: TextureView) {
        if (isPreviewReady) genericStream.startPreview(textureView)
    }

    fun rebindPreview(textureView: TextureView) {
        unbindPreview()
        if (isPreviewReady) genericStream.startPreview(textureView)
    }

    fun unbindPreview() {
        if (genericStream.isOnPreview) genericStream.stopPreview()
    }

    private fun configureGlForOrientation(orientation: String) {
        val gl = genericStream.getGlInterface()
        val isPortrait = orientation == "portrait"

        gl.autoHandleOrientation = false
        gl.setStreamIsPortrait(isPortrait)
        gl.setPreviewIsPortrait(isPortrait)

        if (isPortrait) {
            gl.setStreamRotation(270)
            gl.setPreviewRotation(270)
            genericStream.setOrientation(90)
        } else {
            gl.setStreamRotation(0)
            gl.setPreviewRotation(0)
            genericStream.setOrientation(270)
        }
    }

    fun startStream() {
        intentionalStop = false
        reconnectAttempt = 0
        genericStream.startStream(rtmpEndpoint)
    }

    fun stopStream() {
        intentionalStop = true
        cancelReconnect()
        genericStream.stopStream()
    }

    fun updateScoreband(bytes: ByteArray) {
        overlayFilterManager?.updateScoreband(bytes)
    }

    fun switchCamera(facing: String) {
        val desiredFront = facing == "front"
        val source = genericStream.videoSource as? Camera2Source ?: return
        val currentFront = source.getCameraFacing() == CameraHelper.Facing.FRONT
        if (desiredFront != currentFront) {
            source.switchCamera()
        }
    }

    fun setAudioMuted(muted: Boolean) {
        val source = genericStream.audioSource as? MicrophoneSource ?: return
        if (muted) source.mute() else source.unMute()
    }

    fun setAppOrientation(orientation: String) {
        val orient = when (orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            activity?.requestedOrientation = orient
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set orientation: $e")
        }

        reinitializeForOrientation(orientation)
    }

    private fun reinitializeForOrientation(orientation: String) {
        val isPortrait = orientation == "portrait"
        val newWidth = if (isPortrait) 720 else 1280
        val newHeight = if (isPortrait) 1280 else 720
        val facing = if (genericStream.videoSource is com.pedro.encoder.input.sources.video.Camera2Source) {
            (genericStream.videoSource as com.pedro.encoder.input.sources.video.Camera2Source).getCameraFacing().name
        } else "back"

        if (newWidth != encWidth || newHeight != encHeight) {
            genericStream.release()
            isPreviewReady = false

            val videoOk = genericStream.prepareVideo(newWidth, newHeight, DEFAULT_BITRATE, 30, DEFAULT_KEYFRAME, 0)
            val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
            if (!videoOk || !audioOk) {
                Log.e(TAG, "reinitialize prepare failed: video=$videoOk audio=$audioOk")
                return
            }

            encWidth = newWidth
            encHeight = newHeight

            configureGlForOrientation(orientation)

            overlayFilterManager = OverlayFilterManager(newWidth, newHeight)
            overlayFilterManager?.initLayers(genericStream, emptyList())

            switchCamera(facing)

            isPreviewReady = true
        } else {
            configureGlForOrientation(orientation)
        }
    }

    fun release() {
        intentionalStop = true
        cancelReconnect()
        if (isPreviewReady) overlayFilterManager?.release(genericStream)
        if (genericStream.isOnPreview) genericStream.stopPreview()
        if (genericStream.isStreaming) genericStream.stopStream()
        genericStream.release()
        overlayFilterManager = null
        isPreviewReady = false
        isConfigured = false
    }

    private fun scheduleReconnect() {
        if (intentionalStop) return

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            connectChecker.sendEvent(mapOf(
                "type" to "error",
                "code" to "MAX_RECONNECT_EXCEEDED",
                "message" to "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts"
            ))
            reconnectAttempt = 0
            return
        }

        reconnectAttempt++
        connectChecker.sendEvent(mapOf("type" to "reconnecting", "attempt" to reconnectAttempt))

        val runnable = Runnable {
            if (!intentionalStop) {
                genericStream.startStream(rtmpEndpoint)
            }
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }
}
