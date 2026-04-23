package com.flutterrtmp.broadcaster.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

class CameraStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreamManager"
        private const val STREAM_WIDTH = 720
        private const val STREAM_HEIGHT = 1280
        private const val STREAM_FPS = 30
        private const val VIDEO_BITRATE = 2_500_000
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
    private val overlayFilterManager = OverlayFilterManager(STREAM_WIDTH, STREAM_HEIGHT)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    var rtmpEndpoint: String = ""
        private set
    private var isPrepared = false
    private var intentionalStop = false
    private var reconnectAttempt = 0

    val isStreaming: Boolean get() = isPrepared && genericStream.isStreaming

    fun setSink(sink: EventChannel.EventSink?) {
        connectChecker.sink = sink
    }

    fun configure(rtmpEndpoint: String, sponsors: List<SponsorConfig>) {
        if (isPrepared) return
        this.rtmpEndpoint = rtmpEndpoint

        // RootEncoder signature: prepareVideo(width, height, bitrate, fps, ...).
        // Passing fps in the bitrate slot silently collapses the encoder to ~30 bps.
        val videoOk = genericStream.prepareVideo(STREAM_WIDTH, STREAM_HEIGHT, VIDEO_BITRATE, STREAM_FPS)
        val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        if (!videoOk || !audioOk) {
            Log.e(TAG, "prepare failed: video=$videoOk audio=$audioOk")
            throw IllegalStateException("Encoder prepare failed (video=$videoOk, audio=$audioOk)")
        }

        // Correct for camera sensor orientation so the encoded portrait frames
        // are upright in the 720×1280 encoder (back camera sensor is typically 90°).
        genericStream.setOrientation(CameraHelper.getCameraOrientation(context))

        overlayFilterManager.initLayers(genericStream, sponsors)
        isPrepared = true
    }

    fun bindPreview(textureView: TextureView) {
        if (isPrepared) genericStream.startPreview(textureView)
    }

    fun unbindPreview() {
        if (genericStream.isOnPreview) genericStream.stopPreview()
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
        overlayFilterManager.updateScoreband(bytes)
    }

    fun switchCamera(facing: String) {
        val desiredFront = facing == "front"
        val source = genericStream.videoSource as? Camera2Source ?: return
        val currentFront = source.getCameraFacing() == CameraHelper.Facing.FRONT
        if (desiredFront != currentFront) {
            source.switchCamera()
            genericStream.setOrientation(getSensorOrientation(desiredFront))
        }
    }

    private fun getSensorOrientation(front: Boolean): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lensFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                         else CameraCharacteristics.LENS_FACING_BACK
        return try {
            manager.cameraIdList.firstNotNullOfOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing)
                    chars.get(CameraCharacteristics.SENSOR_ORIENTATION)
                else null
            } ?: 90
        } catch (e: Exception) { 90 }
    }

    fun setAudioMuted(muted: Boolean) {
        val source = genericStream.audioSource as? MicrophoneSource ?: return
        if (muted) source.mute() else source.unMute()
    }

    fun release() {
        intentionalStop = true
        cancelReconnect()
        if (isPrepared) overlayFilterManager.release(genericStream)
        if (genericStream.isOnPreview) genericStream.stopPreview()
        if (genericStream.isStreaming) genericStream.stopStream()
        genericStream.release()
        isPrepared = false
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
