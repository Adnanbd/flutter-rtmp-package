package com.flutterrtmp.broadcaster.camera

import android.content.Context
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.TextureView
import com.flutterrtmp.broadcaster.diag.DiagLogger
import com.flutterrtmp.broadcaster.overlay.OverlayFilterManager
import com.flutterrtmp.broadcaster.overlay.SponsorConfig
import com.flutterrtmp.broadcaster.rtmp.RtmpConnectChecker
import com.flutterrtmp.broadcaster.usb.UsbAudioSource
import com.flutterrtmp.broadcaster.usb.UsbDeviceRegistry
import com.flutterrtmp.broadcaster.usb.UvcVideoSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter
import io.flutter.plugin.common.EventChannel

class CameraStreamManager(
    private val context: Context,
    private val activity: android.app.Activity,
    val usbDeviceRegistry: UsbDeviceRegistry? = null
) {

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
        onDisconnectedCallback = { reason -> scheduleReconnect(reason) },
        onNewBitrateCallback = { bitrate -> onNewBitrate(bitrate) }
    )

    val genericStream: GenericStream by lazy { GenericStream(context, connectChecker) }
    private var overlayFilterManager: OverlayFilterManager? = null

    // Drops video bitrate when the RTMP sender cache backs up, instead of letting
    // RootEncoder discard frames until the server closes the socket.
    private val bitrateAdapter = BitrateAdapter { bitrate ->
        Log.d(TAG, "bitrateAdapter: video bitrate -> $bitrate")
        genericStream.setVideoBitrateOnFly(bitrate)
    }

    private var encWidth = 0
    private var encHeight = 0
    private var currentIsPortrait: Boolean = true
    private var lastScorebandBytes: ByteArray? = null
    private var lastScorebandWidth: Float = 90f
    private var lastScorebandX: Float = 50f
    private var lastScorebandY: Float = 100f
    private var lastSponsors: List<SponsorConfig> = emptyList()
    var rtmpEndpoint: String = ""
        private set
    private var isPreviewReady = false
    private var isConfigured = false
    private var intentionalStop = false
    private var reconnectAttempt = 0

    val isStreaming: Boolean get() = isConfigured && genericStream.isStreaming
    val previewReady: Boolean get() = isPreviewReady
    val videoSourceClassName: String get() = genericStream.videoSource?.javaClass?.simpleName ?: "null"

    fun setSink(sink: EventChannel.EventSink?) {
        connectChecker.sink = sink
    }

    fun initPreviewOnly(
        width: Int,
        height: Int,
        fps: Int,
        orientation: String,
        initialFacing: String,
        videoInput: String = "device",
        usbVideoDeviceId: Int? = null,
        audioInput: String = "mic",
        usbAudioDeviceId: Int? = null
    ) {
        if (isPreviewReady) return

        encWidth = width
        encHeight = height

        val videoOk = genericStream.prepareVideo(width, height, DEFAULT_BITRATE, fps, DEFAULT_KEYFRAME, 0)
        val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        if (!videoOk || !audioOk) {
            Log.e(TAG, "initPreviewOnly prepare failed: video=$videoOk audio=$audioOk")
            throw IllegalStateException("Preview prepare failed (video=$videoOk, audio=$audioOk)")
        }
        applyStreamClientDefaults(DEFAULT_BITRATE)

if (videoInput == "usb" && usbVideoDeviceId != null && usbDeviceRegistry != null) {
            try {
                usbDeviceRegistry.invalidateDevice(usbVideoDeviceId)
                val uvcSource = UvcVideoSource(usbDeviceRegistry, usbVideoDeviceId)
                genericStream.changeVideoSource(uvcSource)
                Log.d(TAG, "initPreviewOnly: switched to UVC source device=$usbVideoDeviceId")
            } catch (e: Exception) {
                DiagLogger.logError("USB_SETUP_FAILED", "initPreviewOnly device=$usbVideoDeviceId", e)
                throw IllegalStateException("USB camera setup failed: ${e.message}")
            }
        }

        if (audioInput == "usb") {
            val usbAudio = UsbAudioSource(context, usbAudioDeviceId)
            genericStream.changeAudioSource(usbAudio)
            Log.d(TAG, "initPreviewOnly: switched to USB audio device=$usbAudioDeviceId")
        }

        configureGlForOrientation(orientation)

        overlayFilterManager = OverlayFilterManager(width, height, orientation == "portrait")
        overlayFilterManager?.initLayers(genericStream, emptyList())

        if (videoInput != "usb") switchCamera(initialFacing)

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
        initialFacing: String,
        videoInput: String = "device",
        usbVideoDeviceId: Int? = null,
        audioInput: String = "mic",
        usbAudioDeviceId: Int? = null
    ) {
        this.rtmpEndpoint = rtmpEndpoint
        this.lastSponsors = sponsors

        if (!isPreviewReady || width != encWidth || height != encHeight) {
            genericStream.release()
            isPreviewReady = false

            val videoOk = genericStream.prepareVideo(width, height, videoBitrate, fps, keyframeIntervalSeconds, 0)
            val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
            if (!videoOk || !audioOk) {
                Log.e(TAG, "configure prepare failed: video=$videoOk audio=$audioOk")
                throw IllegalStateException("Configure failed (video=$videoOk, audio=$audioOk)")
            }
            applyStreamClientDefaults(videoBitrate)

            if (videoInput == "usb" && usbVideoDeviceId != null && usbDeviceRegistry != null) {
                try {
                    usbDeviceRegistry.invalidateDevice(usbVideoDeviceId)
                    val uvcSource = UvcVideoSource(usbDeviceRegistry, usbVideoDeviceId)
                    genericStream.changeVideoSource(uvcSource)
                    Log.d(TAG, "configure: switched to UVC source device=$usbVideoDeviceId")
                } catch (e: Exception) {
                    DiagLogger.logError("USB_SETUP_FAILED", "configure device=$usbVideoDeviceId", e)
                    throw IllegalStateException("USB camera setup failed: ${e.message}")
                }
            }

            if (audioInput == "usb") {
                val usbAudio = UsbAudioSource(context, usbAudioDeviceId)
                genericStream.changeAudioSource(usbAudio)
                Log.d(TAG, "configure: switched to USB audio device=$usbAudioDeviceId")
            }

            this.encWidth = width
            this.encHeight = height

            configureGlForOrientation(orientation)

            overlayFilterManager = OverlayFilterManager(width, height, orientation == "portrait")
            overlayFilterManager?.initLayers(genericStream, sponsors)?.also {
                checkSponsorResult(it, "configure[fresh]")
            }

            lastScorebandBytes?.let { overlayFilterManager?.updateScoreband(it, lastScorebandWidth, lastScorebandX, lastScorebandY) }

            if (videoInput != "usb") switchCamera(initialFacing)

            isPreviewReady = true
        } else {
            // Same dims — refresh sponsors only; scoreband filter already exists from initPreview.
            overlayFilterManager?.updateSponsors(genericStream, sponsors)?.also {
                checkSponsorResult(it, "configure[reuse]")
            }
            if (videoInput != "usb") switchCamera(initialFacing)
        }

        isConfigured = true
    }

    fun bindPreview(textureView: TextureView) {
        DiagLogger.log(TAG, "bindPreview: isPreviewReady=$isPreviewReady isOnPreview=${genericStream.isOnPreview} src=$videoSourceClassName")
        if (!isPreviewReady) return
        try {
            // Idempotent: StreamBase.startPreview throws "Preview already started"
            // when it is already on preview. A resume can land here with a stale
            // GL attachment (the app was backgrounded, the surface replaced), so
            // drop the old one instead of turning the rebind into an error.
            if (genericStream.isOnPreview) {
                DiagLogger.log(TAG, "bindPreview: already on preview — stopping first to rebind")
                genericStream.stopPreview()
            }
            genericStream.startPreview(textureView)
            reapplyOverlaysIfNeeded("bindPreview")
            DiagLogger.log(TAG, "bindPreview: preview bound, sent previewBound event isOnPreview=${genericStream.isOnPreview}")
            connectChecker.sendEvent(mapOf("type" to "previewBound"))
        } catch (t: Throwable) {
            DiagLogger.logError("PREVIEW_BIND_FAILED", "src=$videoSourceClassName", t)
            emitErr("PREVIEW_BIND_FAILED", t.message ?: "startPreview threw")
        }
    }

    /**
     * Force the preview back onto [textureView].
     *
     * Reachable from Dart (`rebindPreview` method call) so a screen returning from
     * background can recover a stale preview without tearing the whole pipeline
     * down with `initPreview` + `configure`.
     *
     * Delegates the second half to [bindPreview] on purpose: that is the only path
     * that re-applies overlays *and* emits `previewBound`, which is how Dart learns
     * the preview is live again.
     */
    fun rebindPreview(textureView: TextureView) {
        DiagLogger.log(TAG, "rebindPreview: isPreviewReady=$isPreviewReady isOnPreview=${genericStream.isOnPreview}")
        unbindPreview()
        bindPreview(textureView)
    }

    // Re-apply sponsor + scoreband overlays. Idempotent — `updateSponsors` and
    // `updateScoreband` clear/replace existing filters. Used after pipeline
    // transitions (startPreview, startStream) where RootEncoder GL drops filters
    // attached pre-transition.
    private fun reapplyOverlaysIfNeeded(where: String) {
        val mgr = overlayFilterManager ?: return
        if (lastSponsors.isEmpty() && lastScorebandBytes == null) return
        Log.d(TAG, "reapplyOverlays[$where]: sponsors=${lastSponsors.size}, scorebandPushed=${lastScorebandBytes != null}, filtersBefore=${genericStream.getGlInterface().filtersCount()}")
        if (lastSponsors.isNotEmpty()) {
            mgr.updateSponsors(genericStream, lastSponsors).also {
                checkSponsorResult(it, "reapply[$where]")
            }
        }
        lastScorebandBytes?.let { mgr.updateScoreband(it, lastScorebandWidth, lastScorebandX, lastScorebandY) }
    }

    /**
     * Release the GL preview and tell Dart about it.
     *
     * The event is what `RtmpBroadcastController.previewBound` needs to go back to
     * false: it is only ever raised by the `previewBound` event and lowered inside
     * `initPreview()`, so without this a background/foreground cycle leaves Dart
     * believing a preview is bound when the surface underneath is gone.
     *
     * Emitted unconditionally — the point is the Dart-side flag, and a preview that
     * was already off is still not bound.
     */
    fun unbindPreview() {
        val wasOnPreview = genericStream.isOnPreview
        DiagLogger.log(TAG, "unbindPreview: isOnPreview=$wasOnPreview src=$videoSourceClassName")
        if (wasOnPreview) genericStream.stopPreview()
        connectChecker.sendEvent(mapOf("type" to "previewUnbound"))
    }

    private fun configureGlForOrientation(orientation: String) {
        currentIsPortrait = orientation == "portrait"
        val isUvc = genericStream.videoSource is UvcVideoSource
        if (isUvc) {
            configureGlForUvc(orientation)
        } else {
            configureGlForDeviceCamera(orientation)
        }
    }

    // Phone (Camera2Source) — values per CLAUDE.md "Orientation Handling".
    // Do NOT change these without updating CLAUDE.md.
    private fun configureGlForDeviceCamera(orientation: String) {
        val gl = genericStream.getGlInterface()
        val isPortrait = orientation == "portrait"

        val streamRot = if (isPortrait) 270 else 0
        val previewRot = if (isPortrait) 270 else 0
        val orient = if (isPortrait) 90 else 270
        DiagLogger.log(TAG, "configureGlForDeviceCamera: orientation=$orientation isPortrait=$isPortrait " +
            "streamRot=$streamRot previewRot=$previewRot orient=$orient " +
            "enc=${encWidth}x${encHeight} src=${videoSourceClassName}")

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

    // UVC / external camera. Native UVC frames arrive in the camera's own
    // landscape orientation (typical for HDMI capture / webcams), unlike the
    // phone's portrait sensor frames. Reusing the phone-camera rotation values
    // produces a white/blank preview in portrait and an off-axis frame in
    // landscape. Tweak the four constants below if a specific UVC device needs
    // a different rotation — phone-camera path is unaffected.
    private fun configureGlForUvc(orientation: String) {
        val gl = genericStream.getGlInterface()
        val isPortrait = orientation == "portrait"

        // UVC-specific rotation knobs. Adjust here when testing new devices.
        val streamRot = if (isPortrait) 90 else 0
        val previewRot = if (isPortrait) 90 else 0
        val orient = if (isPortrait) 0 else 0

        DiagLogger.log(TAG, "configureGlForUvc: orientation=$orientation isPortrait=$isPortrait " +
            "streamRot=$streamRot previewRot=$previewRot orient=$orient " +
            "enc=${encWidth}x${encHeight} src=${videoSourceClassName}")

        gl.autoHandleOrientation = false
        gl.setStreamIsPortrait(isPortrait)
        gl.setPreviewIsPortrait(isPortrait)
        gl.setStreamRotation(streamRot)
        gl.setPreviewRotation(previewRot)
        genericStream.setOrientation(orient)
    }

    fun startStream() {
        intentionalStop = false
        reconnectAttempt = 0

        if (!isPreviewReady) {
            fail("PREVIEW_NOT_READY", "initPreview/configure not complete")
        }
        if (!genericStream.isOnPreview) {
            fail("PREVIEW_NOT_BOUND", "SurfaceTexture not bound — wait for preview before streaming")
        }

        val src = genericStream.videoSource
        if (src is UvcVideoSource && usbDeviceRegistry != null) {
            if (!usbDeviceRegistry.hasDevice(src.deviceId)) {
                fail("USB_DEVICE_GONE", "deviceId=${src.deviceId} no longer attached")
            }
            if (!usbDeviceRegistry.hasPermission(src.deviceId)) {
                fail("USB_PERMISSION_REVOKED", "deviceId=${src.deviceId}")
            }
        }

        val filtersBefore = genericStream.getGlInterface().filtersCount()
        val gl = genericStream.getGlInterface()
        DiagLogger.log(TAG, "startStream: videoSrc=${src?.javaClass?.simpleName} " +
            "filters=$filtersBefore enc=${encWidth}x${encHeight} ep=$rtmpEndpoint " +
            "isOnPreview=${genericStream.isOnPreview} isStreaming=${genericStream.isStreaming}")

        if (filtersBefore == 0) {
            if (lastSponsors.isEmpty() && lastScorebandBytes == null) {
                emitWarn(
                    "NO_OVERLAYS_AT_STREAM_START",
                    "No overlays registered. Stream will publish camera-only video. " +
                        "Pass non-empty sponsors to configure() and/or call updateScoreband(bytes) after RtmpStatusType.connected.",
                    mapOf("sponsorCount" to lastSponsors.size, "scorebandPushed" to false)
                )
            } else {
                // Filters added earlier but RootEncoder GL pipeline lost them
                // (observed at 1920x1080 after configureGlForOrientation rotation knobs).
                // Re-apply BEFORE startStream — filter add must precede startStream per CLAUDE.md.
                emitWarn(
                    "OVERLAY_FILTERS_LOST",
                    "Filters added during configure() were dropped by GL pipeline before startStream — re-applying. " +
                        "lastSponsors=${lastSponsors.size}, scorebandPushed=${lastScorebandBytes != null}.",
                    mapOf("sponsorCount" to lastSponsors.size, "scorebandPushed" to (lastScorebandBytes != null))
                )
                if (lastSponsors.isNotEmpty()) {
                    overlayFilterManager?.updateSponsors(genericStream, lastSponsors)?.also {
                        checkSponsorResult(it, "startStream-recovery")
                    }
                }
                lastScorebandBytes?.let { overlayFilterManager?.updateScoreband(it, lastScorebandWidth, lastScorebandX, lastScorebandY) }
                Log.d(TAG, "startStream: post-recovery filters=${genericStream.getGlInterface().filtersCount()}")
            }
        }
        // setReTries also resets the library's internal retry counter for this session.
        genericStream.getStreamClient().setReTries(MAX_RECONNECT_ATTEMPTS)
        bitrateAdapter.reset()

        try {
            genericStream.startStream(rtmpEndpoint)
        } catch (t: Throwable) {
            DiagLogger.logError("STREAM_START_THREW", t.message ?: "", t)
            emitErr("STREAM_START_THREW", t.message ?: "Unknown")
            throw t
        }
    }

    private fun fail(code: String, msg: String): Nothing {
        DiagLogger.logError(code, msg)
        emitErr(code, msg)
        throw IllegalStateException("$code: $msg")
    }

    private fun emitErr(code: String, message: String) =
        connectChecker.sendEvent(mapOf("type" to "error", "code" to code, "message" to message))

    private fun emitWarn(code: String, message: String, extra: Map<String, Any?> = emptyMap()) {
        Log.w(TAG, "WARN $code: $message ${if (extra.isNotEmpty()) extra else ""}")
        val payload = mutableMapOf<String, Any?>("type" to "warning", "code" to code, "message" to message)
        payload.putAll(extra)
        connectChecker.sendEvent(payload)
    }

    private fun checkSponsorResult(result: com.flutterrtmp.broadcaster.overlay.OverlayFilterManager.OverlayOpResult, where: String) {
        if (result.input > 0 && result.added < result.input) {
            emitWarn(
                "SPONSOR_DECODE_FAILED",
                "$where: ${result.decodeFails}/${result.input} sponsor image(s) failed to decode (HEIC or corrupted bytes?). " +
                    "Stream will publish with ${result.added} sponsor(s) instead of ${result.input}.",
                mapOf("input" to result.input, "added" to result.added, "decodeFails" to result.decodeFails)
            )
        }
    }

    fun stopStream() {
        intentionalStop = true
        reconnectAttempt = 0
        // stopStream() also cancels any in-flight reTry() inside the stream client.
        genericStream.stopStream()
    }

    fun updateScoreband(bytes: ByteArray, width: Float, x: Float, y: Float) {
        lastScorebandBytes = bytes
        lastScorebandWidth = width
        lastScorebandX = x
        lastScorebandY = y
        val filters = genericStream.getGlInterface().filtersCount()
        Log.d(TAG, "updateScoreband: bytes=${bytes.size}, w=$width x=$x y=$y, filtersCount=$filters, streaming=${genericStream.isStreaming}, onPreview=${genericStream.isOnPreview}")
        val mgr = overlayFilterManager
        if (mgr == null) {
            val msg = "OVERLAY_NOT_INITIALIZED: updateScoreband called before configure()"
            DiagLogger.logError("OVERLAY_NOT_INITIALIZED", msg)
            emitErr("OVERLAY_NOT_INITIALIZED", msg)
            throw IllegalStateException(msg)
        }
        try {
            mgr.updateScoreband(bytes, width, x, y)
        } catch (t: Throwable) {
            val code = (t.message?.substringBefore(':') ?: "OVERLAY_UPDATE_FAILED").trim()
            DiagLogger.logError(code, t.message ?: "updateScoreband failed", t)
            emitErr(code, t.message ?: "updateScoreband failed")
            throw t
        }
    }

    fun switchCamera(facing: String) {
        val source = genericStream.videoSource as? Camera2Source ?: return  // no-op for UVC/non-Camera2 sources
        val desiredFront = facing == "front"
        val currentFront = source.getCameraFacing() == CameraHelper.Facing.FRONT
        if (desiredFront != currentFront) {
            source.switchCamera()
        }
    }

    fun setAudioMuted(muted: Boolean) {
        when (val source = genericStream.audioSource) {
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
            is UsbAudioSource -> if (muted) source.mute() else source.unMute()
        }
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

        // No actual flip — just refresh GL knobs. Avoids tearing down a working
        // pipeline (esp. UVC, where rapid release+reopen hits nativeConnect=-99).
        if (isPortrait == currentIsPortrait && encWidth != 0 && encHeight != 0) {
            configureGlForOrientation(orientation)
            return
        }

        // Real flip: swap configured dims to preserve the user's chosen resolution
        // (720p stays 720p, 1080p stays 1080p). Hardcoding 1280×720 would clobber 1080p.
        val newWidth = if (encWidth != 0 && encHeight != 0) encHeight else if (isPortrait) 720 else 1280
        val newHeight = if (encWidth != 0 && encHeight != 0) encWidth else if (isPortrait) 1280 else 720

        val currentSource = genericStream.videoSource
        val videoInput = if (currentSource is UvcVideoSource) "usb" else "device"
        val usbVideoDeviceId = (currentSource as? UvcVideoSource)?.deviceId
        val facing = when (currentSource) {
            is com.pedro.encoder.input.sources.video.Camera2Source -> currentSource.getCameraFacing().name
            else -> "back"
        }

        if (newWidth != encWidth || newHeight != encHeight) {
            genericStream.release()
            isPreviewReady = false

            val videoOk = genericStream.prepareVideo(newWidth, newHeight, DEFAULT_BITRATE, 30, DEFAULT_KEYFRAME, 0)
            val audioOk = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
            if (!videoOk || !audioOk) {
                Log.e(TAG, "reinitialize prepare failed: video=$videoOk audio=$audioOk")
                return
            }
            applyStreamClientDefaults(DEFAULT_BITRATE)

            encWidth = newWidth
            encHeight = newHeight

            configureGlForOrientation(orientation)

            overlayFilterManager = OverlayFilterManager(newWidth, newHeight, isPortrait)
            overlayFilterManager?.initLayers(genericStream, lastSponsors)?.also {
                checkSponsorResult(it, "reinitForOrientation")
            }

            lastScorebandBytes?.let { overlayFilterManager?.updateScoreband(it, lastScorebandWidth, lastScorebandX, lastScorebandY) }

            if (videoInput == "usb" && usbVideoDeviceId != null && usbDeviceRegistry != null) {
                try {
                    usbDeviceRegistry.invalidateDevice(usbVideoDeviceId)
                    val uvcSource = UvcVideoSource(usbDeviceRegistry, usbVideoDeviceId)
                    genericStream.changeVideoSource(uvcSource)
                    Log.d(TAG, "reinitialize: switched to UVC source device=$usbVideoDeviceId")
                } catch (e: Exception) {
                    DiagLogger.logError("USB_SETUP_FAILED", "reinitialize device=$usbVideoDeviceId", e)
                    throw IllegalStateException("USB camera setup failed: ${e.message}")
                }
            } else {
                switchCamera(facing)
            }

            isPreviewReady = true
        } else {
            configureGlForOrientation(orientation)
        }
    }

    fun release() {
        intentionalStop = true
        reconnectAttempt = 0
        if (isPreviewReady) overlayFilterManager?.release(genericStream)
        if (genericStream.isOnPreview) genericStream.stopPreview()
        if (genericStream.isStreaming) genericStream.stopStream()
        genericStream.release()
        overlayFilterManager = null
        isPreviewReady = false
        isConfigured = false
    }

    /**
     * Reconnect through RootEncoder's own retry path.
     *
     * Do NOT call genericStream.startStream() here: the stream is still marked started
     * after a socket drop, so StreamBase.startStream throws
     * "Stream already started, stopStream before startStream again". reTry() reconnects
     * the client in place on its own thread and never hits that guard.
     */
    private fun scheduleReconnect(reason: String) {
        if (intentionalStop) return

        val retrying = try {
            genericStream.getStreamClient().reTry(RECONNECT_DELAY_MS, reason)
        } catch (t: Throwable) {
            DiagLogger.logError("RECONNECT_THREW", t.message ?: "reTry threw", t)
            false
        }

        if (retrying) {
            reconnectAttempt++
            connectChecker.sendEvent(mapOf("type" to "reconnecting", "attempt" to reconnectAttempt))
            DiagLogger.log(TAG, "scheduleReconnect: attempt=$reconnectAttempt reason=$reason")
        } else {
            DiagLogger.log(TAG, "scheduleReconnect: retries exhausted after $reconnectAttempt reason=$reason")
            emitErr("MAX_RECONNECT_EXCEEDED", "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
            reconnectAttempt = 0
            // Leave StreamBase in a startable state so a later startStream() works.
            runCatching { genericStream.stopStream() }
        }
    }

    private fun applyStreamClientDefaults(videoBitrate: Int) {
        bitrateAdapter.setMaxBitrate(videoBitrate)
        bitrateAdapter.reset()
        genericStream.getStreamClient().setReTries(MAX_RECONNECT_ATTEMPTS)
    }

    private fun onNewBitrate(bitrate: Long) {
        val congested = try {
            genericStream.getStreamClient().hasCongestion()
        } catch (t: Throwable) {
            false
        }
        bitrateAdapter.adaptBitrate(bitrate, congested)
    }
}
