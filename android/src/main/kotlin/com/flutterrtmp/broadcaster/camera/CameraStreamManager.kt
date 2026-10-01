package com.flutterrtmp.broadcaster.camera

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import com.flutterrtmp.broadcaster.audio.AudioCleanupChain
import com.flutterrtmp.broadcaster.audio.AudioCleanupMode
import com.flutterrtmp.broadcaster.audio.CleanupAudioEffect
import com.flutterrtmp.broadcaster.audio.RnnoiseDenoiser
import com.flutterrtmp.broadcaster.audio.RnnoiseNative
import com.flutterrtmp.broadcaster.diag.DiagLogger
import com.flutterrtmp.broadcaster.diag.EndpointRedactor
import com.flutterrtmp.broadcaster.overlay.ChoreographerFrameDriver
import com.flutterrtmp.broadcaster.overlay.DynamicOverlayController
import com.flutterrtmp.broadcaster.overlay.OverlayContentDecoder
import com.flutterrtmp.broadcaster.overlay.OverlayFilterManager
import com.flutterrtmp.broadcaster.overlay.OverlayScheduler
import com.flutterrtmp.broadcaster.overlay.OverlayVisual
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
        private const val DEFAULT_BITRATE = 4_000_000
        private const val DEFAULT_KEYFRAME = 2
        /** 48 kHz: the MS2109/most USB audio is natively 48 kHz and RNNoise requires it (ADR 0025). */
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
        private const val FALLBACK_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 3000L
        private const val USB_RESTART_WINDOW_MS = 30_000L
        private const val STREAM_STATS_INTERVAL_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ConnectChecker callbacks arrive on the stream client's thread; overlay timers are main-thread only.
    private val connectChecker: RtmpConnectChecker = RtmpConnectChecker(
        onConnectedCallback = {
            reconnectAttempt = 0
            mainHandler.post { if (!intentionalStop) dynamicOverlays.setLive(true) }
        },
        onDisconnectedCallback = { reason ->
            mainHandler.post { dynamicOverlays.setLive(false) }
            scheduleReconnect(reason)
        },
        onNewBitrateCallback = { bitrate -> onNewBitrate(bitrate) }
    )

    val genericStream: GenericStream by lazy { GenericStream(context, connectChecker) }
    private var overlayFilterManager: OverlayFilterManager? = null

    private val overlayDecoder = OverlayContentDecoder(context.cacheDir)

    private val mainScheduler = OverlayScheduler { delayMs, task ->
        val r = Runnable(task)
        mainHandler.postDelayed(r, delayMs)
        ({ mainHandler.removeCallbacks(r) })
    }

    /**
     * App-controlled overlays (docs/specs/dynamic-overlays.md). Outlives each OverlayFilterManager:
     * every new manager is seeded from it via [newOverlayFilterManager].
     */
    val dynamicOverlays: DynamicOverlayController<OverlayVisual> = DynamicOverlayController(
        decode = { content -> overlayDecoder.decode(content) },
        emit = { event -> connectChecker.sendEvent(event) },
        clock = { SystemClock.elapsedRealtime() },
        scheduler = mainScheduler,
        frames = ChoreographerFrameDriver(fps = { currentFps }) { dynamicOverlays.onFrame() }
    )

    /** Camera zoom (docs/specs/camera-zoom.md). Keeps the requested level across camera re-opens. */
    val zoom = ZoomController(
        target = {
            when (val src = genericStream.videoSource) {
                is Camera2Source -> Camera2ZoomTarget(src)
                is UvcVideoSource -> UvcZoomTarget(src)
                else -> null
            }
        },
        emit = { event -> connectChecker.sendEvent(event) },
        clock = { SystemClock.elapsedRealtime() },
        scheduler = mainScheduler,
        log = { DiagLogger.log(TAG, "zoom: $it") }
    )

    // Drops video bitrate when the RTMP sender cache backs up, instead of letting
    // RootEncoder discard frames until the server closes the socket.
    private val bitrateAdapter = BitrateAdapter { bitrate ->
        Log.d(TAG, "bitrateAdapter: video bitrate -> $bitrate")
        genericStream.setVideoBitrateOnFly(bitrate)
    }

    private var encWidth = 0
    private var encHeight = 0
    private var currentFps = DEFAULT_FPS
    /** Values the encoder was prepared with (prepareVideo can't run again while previewing). */
    private var preparedBitrate = DEFAULT_BITRATE
    private var preparedKeyframe = DEFAULT_KEYFRAME
    /** configure() asked for a different bitrate than prepared: applied on the running encoder at every startStream. */
    private var pendingBitrate: Int? = null
    private var currentIsPortrait: Boolean = true
    private var lastScorebandBytes: ByteArray? = null
    private var lastScorebandWidth: Float = 90f
    private var lastScorebandX: Float = 50f
    private var lastScorebandY: Float = 100f
    private var lastScorebandWeight: Int = OverlayFilterManager.DEFAULT_SCOREBAND_WEIGHT
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
        videoBitrate: Int,
        keyframeIntervalSeconds: Int,
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

        currentFps = fps
        preparedBitrate = videoBitrate
        preparedKeyframe = keyframeIntervalSeconds
        pendingBitrate = null
        val videoOk = genericStream.prepareVideo(width, height, videoBitrate, fps, keyframeIntervalSeconds, 0)
        val audioOk = prepareAudioWithFallback()
        if (!videoOk || !audioOk) {
            Log.e(TAG, "initPreviewOnly prepare failed: video=$videoOk audio=$audioOk")
            throw IllegalStateException("Preview prepare failed (video=$videoOk, audio=$audioOk)")
        }
        applyStreamClientDefaults(videoBitrate)

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

        installAudioSource(audioInput, usbAudioDeviceId, "initPreviewOnly")

        configureGlForOrientation(orientation)

        newOverlayFilterManager(width, height, orientation == "portrait", emptyList(), "initPreview")

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

            currentFps = fps
            preparedBitrate = videoBitrate
            preparedKeyframe = keyframeIntervalSeconds
            pendingBitrate = null
            val videoOk = genericStream.prepareVideo(width, height, videoBitrate, fps, keyframeIntervalSeconds, 0)
            val audioOk = prepareAudioWithFallback()
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

            installAudioSource(audioInput, usbAudioDeviceId, "configure[fresh]")

            this.encWidth = width
            this.encHeight = height

            configureGlForOrientation(orientation)

            newOverlayFilterManager(width, height, orientation == "portrait", sponsors, "configure[fresh]")

            if (videoInput != "usb") switchCamera(initialFacing)

            isPreviewReady = true
        } else {
            // Same dims — the encoder stays prepared from initPreview (prepareVideo throws while previewing).
            if (videoBitrate != preparedBitrate) {
                pendingBitrate = videoBitrate
                applyStreamClientDefaults(videoBitrate)
                DiagLogger.log(TAG, "configure[reuse]: bitrate $preparedBitrate -> $videoBitrate applied at startStream")
            } else {
                pendingBitrate = null
            }
            if (fps != currentFps || keyframeIntervalSeconds != preparedKeyframe) {
                emitWarn(
                    "STREAM_CONFIG_MISMATCH",
                    "configure() fps/keyframe differ from initPreview() and can't change while previewing; " +
                        "pass the same StreamConfig to both. Using fps=$currentFps keyframe=${preparedKeyframe}s.",
                    mapOf(
                        "preparedFps" to currentFps, "requestedFps" to fps,
                        "preparedKeyframe" to preparedKeyframe, "requestedKeyframe" to keyframeIntervalSeconds
                    )
                )
            }
            // initPreview may have been called with a different audio input.
            installAudioSource(audioInput, usbAudioDeviceId, "configure[reuse]")
            // Refresh sponsors; scoreband filter already exists from initPreview.
            overlayFilterManager?.updateSponsors(genericStream, sponsors)?.also {
                checkSponsorResult(it, "configure[reuse]")
            }
            if (videoInput != "usb") switchCamera(initialFacing)
        }

        isConfigured = true
    }

    /**
     * Puts the requested audio input on [genericStream] (docs/specs/usb-sources.md). Idempotent: keeps the
     * current source when it already matches, so `configure` after `initPreview` doesn't re-open the mic.
     * Must run after prepareAudio (changeAudioSource creates the new source with the prepared params).
     */
    private fun installAudioSource(audioInput: String, usbAudioDeviceId: Int?, where: String) {
        val current = genericStream.audioSource
        when (audioInput) {
            "usb" -> {
                if (current is UsbAudioSource && (usbAudioDeviceId == null || current.currentDeviceId == usbAudioDeviceId)) {
                    DiagLogger.log(TAG, "$where: audio already USB (${audioSourceForLog()})")
                    return
                }
                val usbAudio = UsbAudioSource(
                    context, usbAudioDeviceId,
                    onIssue = { code, message, details -> mainHandler.post { emitWarn(code, message, details) } },
                    onStall = { src, reason -> onUsbAudioStall(src, reason) },
                    cleanup = audioCleanup
                )
                usbRestartAt = 0L
                genericStream.changeAudioSource(usbAudio)
                DiagLogger.log(TAG, "$where: audio -> USB requestedId=$usbAudioDeviceId")
            }
            else -> {
                if (current !is UsbAudioSource) {
                    (current as? MicrophoneSource)?.let { attachCleanup(it) }
                    DiagLogger.log(TAG, "$where: audio=${audioSourceForLog()} (requested $audioInput)")
                    return
                }
                genericStream.changeAudioSource(MicrophoneSource().also { attachCleanup(it) })
                DiagLogger.log(TAG, "$where: audio USB -> phone mic (requested $audioInput)")
            }
        }
    }

    /**
     * Mic cleanup shared by every audio source this manager installs (docs/specs/audio-cleanup.md, ADR 0025):
     * run directly by [UsbAudioSource], and as RootEncoder's `CustomAudioEffect` on the phone mic.
     */
    private val audioCleanup = AudioCleanupChain(
        sampleRate = AUDIO_SAMPLE_RATE,
        channels = AUDIO_CHANNELS,
        denoiserFactory = if (runCatching { RnnoiseNative.available }.getOrDefault(false)) ({ RnnoiseDenoiser() }) else null,
        onOverload = { msg -> mainHandler.post { emitWarn("AUDIO_CLEANUP_OVERLOAD", msg) } },
        onUnavailable = { msg -> mainHandler.post { emitWarn("AUDIO_CLEANUP_UNAVAILABLE", "$msg; using basic cleanup") } },
        onFailure = { t ->
            DiagLogger.logError("AUDIO_CLEANUP_FAILED", "cleanup disabled, raw audio continues", t)
            mainHandler.post {
                emitWarn("AUDIO_CLEANUP_FAILED", "Mic cleanup hit an error and was turned off; the stream continues with raw audio. ${t.message ?: ""}")
            }
        }
    )

    /**
     * Prepares 48 kHz stereo audio; a device that can't record 48 kHz gets 44.1 kHz (the previous default) so the
     * stream still starts. Cleanup follows the rate (VOICE needs 48 kHz → BASIC + AUDIO_CLEANUP_UNAVAILABLE).
     */
    private fun prepareAudioWithFallback(): Boolean {
        val ok48 = try {
            genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        } catch (t: Throwable) {
            DiagLogger.log(TAG, "prepareAudio(48000) threw: ${t.message}")
            false
        }
        if (ok48) {
            audioCleanup.updateSampleRate(AUDIO_SAMPLE_RATE)
            return true
        }
        DiagLogger.log(TAG, "prepareAudio: 48000 Hz not supported, falling back to $FALLBACK_SAMPLE_RATE Hz")
        val ok = genericStream.prepareAudio(FALLBACK_SAMPLE_RATE, true, AUDIO_BITRATE)
        if (ok) audioCleanup.updateSampleRate(FALLBACK_SAMPLE_RATE)
        return ok
    }

    /** Live-safe: the chain picks the new mode at its next audio chunk. */
    fun setAudioCleanup(mode: AudioCleanupMode) {
        if (audioCleanup.mode != mode) DiagLogger.log(TAG, "audioCleanup: ${audioCleanup.mode.wire} -> ${mode.wire}")
        audioCleanup.resetFailure()
        audioCleanup.mode = mode
    }

    private fun attachCleanup(mic: MicrophoneSource) {
        try {
            mic.setAudioEffect(CleanupAudioEffect(audioCleanup))
        } catch (t: Throwable) {
            DiagLogger.logError("AUDIO_CLEANUP_FAILED", "setAudioEffect on phone mic failed; raw audio", t)
        }
    }

    /** Last USB capture restart (elapsedRealtime), 0 = none in this session (ADR 0023). */
    private var usbRestartAt = 0L

    /**
     * USB audio stopped delivering PCM (main thread). A stalled input leaves YouTube with no playable stream,
     * so: restart USB capture once; a second stall within 30 s (or a failed restart) swaps to the phone mic.
     */
    private fun onUsbAudioStall(src: UsbAudioSource, reason: String) {
        if (genericStream.audioSource !== src) return
        val now = SystemClock.elapsedRealtime()
        if (usbRestartAt == 0L || now - usbRestartAt > USB_RESTART_WINDOW_MS) {
            usbRestartAt = now
            emitWarn(
                "USB_AUDIO_STALLED",
                "USB audio stopped delivering sound ($reason). Restarting USB audio.",
                mapOf("reason" to reason)
            )
            val ok = try { src.restartCapture() } catch (t: Throwable) {
                DiagLogger.logError("USB_AUDIO_STALLED", "restartCapture threw", t)
                false
            }
            DiagLogger.log(TAG, "usbAudioStall: restart ok=$ok")
            if (!ok) fallbackToPhoneMic(src, "restart failed after: $reason")
        } else {
            fallbackToPhoneMic(src, "stalled again after restart: $reason")
        }
    }

    private fun fallbackToPhoneMic(src: UsbAudioSource, reason: String) {
        val mic = MicrophoneSource().also { attachCleanup(it) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Android may route the default input to an attached USB device; ask for the built-in mic explicitly.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { mic.setPreferredDevice(it) }
        }
        val wasMuted = src.isMuted()
        try {
            genericStream.changeAudioSource(mic)
        } catch (t: Throwable) {
            DiagLogger.logError("USB_AUDIO_FALLBACK_PHONE_MIC", "changeAudioSource(phone mic) threw", t)
            emitErr("USB_AUDIO_FALLBACK_PHONE_MIC", "USB audio failed and the phone mic could not be started: ${t.message}")
            return
        }
        if (wasMuted) mic.mute()
        DiagLogger.logError("USB_AUDIO_FALLBACK_PHONE_MIC", "$reason → now streaming phone mic")
        emitWarn(
            "USB_AUDIO_FALLBACK_PHONE_MIC",
            "USB audio stopped. Now streaming the phone microphone. Check the USB audio device, then restart the stream to try USB again.",
            mapOf("reason" to reason)
        )
    }

    // What actually leaves the phone, every 5 s while streaming (docs/specs/diagnostics.md).
    private val streamStatsLogger = object : Runnable {
        override fun run() {
            if (!genericStream.isStreaming) return
            try {
                val c = genericStream.getStreamClient()
                DiagLogger.log(TAG, "cleanup: ${audioCleanup.summarize(STREAM_STATS_INTERVAL_MS)}")
                DiagLogger.log(TAG, "stream: sentVideo=${c.getSentVideoFrames()} sentAudio=${c.getSentAudioFrames()} " +
                    "droppedVideo=${c.getDroppedVideoFrames()} droppedAudio=${c.getDroppedAudioFrames()} " +
                    "bytesSent=${c.getBytesSend()} cache=${c.getItemsInCache()} audioSrc=${audioSourceForLog()}")
            } catch (t: Throwable) {
                DiagLogger.log(TAG, "stream: stats failed ${t.message}")
            }
            mainHandler.postDelayed(this, STREAM_STATS_INTERVAL_MS)
        }
    }

    private fun audioSourceForLog(): String = when (val src = genericStream.audioSource) {
        is UsbAudioSource -> "USB(id=${src.currentDeviceId})"
        is MicrophoneSource -> "phoneMic"
        else -> src.javaClass.simpleName
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
            zoom.reapply("bindPreview")   // the camera re-opened; RootEncoder reset its zoom to 1.0
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

    // Re-add every overlay layer (sponsors, scoreband, visible dynamic overlays) in z-order with fresh
    // filters. Used after pipeline transitions (startPreview, startStream) where RootEncoder GL drops
    // filters attached pre-transition. See LayerStack.rebuild.
    private fun reapplyOverlaysIfNeeded(where: String) {
        val mgr = overlayFilterManager ?: return
        if (!mgr.hasLayers) return
        Log.d(TAG, "reapplyOverlays[$where]: sponsors=${lastSponsors.size}, scorebandPushed=${lastScorebandBytes != null}, " +
            "dynamic=${dynamicOverlays.visibleCount}/${dynamicOverlays.size}, filtersBefore=${genericStream.getGlInterface().filtersCount()}")
        mgr.rebuild(where)
    }

    /**
     * Build a fresh overlay manager for the current encoder dims: sponsors + cached scoreband + dynamic
     * overlays, then hand it to [dynamicOverlays]. Every re-prepare path goes through here so no layer is lost.
     */
    private fun newOverlayFilterManager(width: Int, height: Int, isPortrait: Boolean, sponsors: List<SponsorConfig>, where: String) {
        dynamicOverlays.attachHost(null)
        dynamicOverlays.snapAnimations()   // spec §3: in-flight animations jump to their end state
        val mgr = OverlayFilterManager(width, height, isPortrait)
        mgr.initLayers(genericStream, sponsors, dynamicOverlays.layers()).also {
            checkSponsorResult(it, where)
        }
        lastScorebandBytes?.let { mgr.updateScoreband(it, lastScorebandWidth, lastScorebandX, lastScorebandY, lastScorebandWeight) }
        overlayFilterManager = mgr
        dynamicOverlays.attachHost(mgr)
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

    // Phone (Camera2Source) — values per docs/specs/orientation.md.
    // Do NOT change these without updating that spec.
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
            "filters=$filtersBefore enc=${encWidth}x${encHeight} ep=${EndpointRedactor.redact(rtmpEndpoint)} " +
            "isOnPreview=${genericStream.isOnPreview} isStreaming=${genericStream.isStreaming} " +
            "audioSrc=${audioSourceForLog()}")

        if (filtersBefore == 0) {
            if (overlayFilterManager?.hasLayers != true) {
                emitWarn(
                    "NO_OVERLAYS_AT_STREAM_START",
                    "No overlays registered. Stream will publish camera-only video. " +
                        "Pass non-empty sponsors to configure() and/or call updateScoreband(bytes) after RtmpStatusType.connected.",
                    mapOf("sponsorCount" to lastSponsors.size, "scorebandPushed" to false)
                )
            } else {
                // Filters added earlier but RootEncoder GL pipeline lost them
                // (observed at 1920x1080 after configureGlForOrientation rotation knobs).
                // Re-apply BEFORE startStream — filter add must precede startStream (docs/specs/overlay-compositing.md).
                emitWarn(
                    "OVERLAY_FILTERS_LOST",
                    "Filters added during configure() were dropped by GL pipeline before startStream — re-applying. " +
                        "lastSponsors=${lastSponsors.size}, scorebandPushed=${lastScorebandBytes != null}, dynamic=${dynamicOverlays.visibleCount}.",
                    mapOf(
                        "sponsorCount" to lastSponsors.size,
                        "scorebandPushed" to (lastScorebandBytes != null),
                        "dynamicCount" to dynamicOverlays.visibleCount
                    )
                )
                overlayFilterManager?.rebuild("startStream-recovery")
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
        mainHandler.removeCallbacks(streamStatsLogger)
        mainHandler.postDelayed(streamStatsLogger, STREAM_STATS_INTERVAL_MS)
        // Encoders run from here; a bitrate the prepared encoder doesn't have yet is applied on the fly.
        pendingBitrate?.let {
            genericStream.setVideoBitrateOnFly(it)
            DiagLogger.log(TAG, "startStream: video bitrate on fly -> $it (prepared $preparedBitrate)")
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
        DiagLogger.log(TAG, "WARN $code: $message ${if (extra.isNotEmpty()) extra else ""}")
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
        dynamicOverlays.setLive(false)
        mainHandler.removeCallbacks(streamStatsLogger)
        // stopStream() also cancels any in-flight reTry() inside the stream client.
        genericStream.stopStream()
    }

    fun updateScoreband(bytes: ByteArray, width: Float, x: Float, y: Float, weight: Int = OverlayFilterManager.DEFAULT_SCOREBAND_WEIGHT) {
        lastScorebandBytes = bytes
        lastScorebandWidth = width
        lastScorebandX = x
        lastScorebandY = y
        lastScorebandWeight = weight
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
            mgr.updateScoreband(bytes, width, x, y, weight)
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
            zoom.onCameraSwitched()
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
            // switchCamera expects "front"/"back"; Facing.name is upper case.
            is com.pedro.encoder.input.sources.video.Camera2Source -> currentSource.getCameraFacing().name.lowercase()
            else -> "back"
        }

        if (newWidth != encWidth || newHeight != encHeight) {
            genericStream.release()
            isPreviewReady = false

            currentFps = 30
            val bitrate = pendingBitrate ?: preparedBitrate
            val videoOk = genericStream.prepareVideo(newWidth, newHeight, bitrate, currentFps, preparedKeyframe, 0)
            val audioOk = prepareAudioWithFallback()
            if (!videoOk || !audioOk) {
                Log.e(TAG, "reinitialize prepare failed: video=$videoOk audio=$audioOk")
                return
            }
            preparedBitrate = bitrate
            pendingBitrate = null
            applyStreamClientDefaults(bitrate)
            // release() + prepareAudio() re-creates the current audio source in place (USB stays USB).
            DiagLogger.log(TAG, "reinitialize: audioSrc=${audioSourceForLog()}")

            encWidth = newWidth
            encHeight = newHeight

            configureGlForOrientation(orientation)

            newOverlayFilterManager(newWidth, newHeight, isPortrait, lastSponsors, "reinitForOrientation")

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
        dynamicOverlays.setLive(false)
        dynamicOverlays.attachHost(null)
        mainHandler.removeCallbacks(streamStatsLogger)
        try { audioCleanup.release() } catch (t: Throwable) { DiagLogger.log(TAG, "audioCleanup.release failed", t) }
        zoom.release()
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
