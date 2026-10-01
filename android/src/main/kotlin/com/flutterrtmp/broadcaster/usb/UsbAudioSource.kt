package com.flutterrtmp.broadcaster.usb

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.flutterrtmp.broadcaster.audio.AudioCleanupChain
import com.flutterrtmp.broadcaster.diag.DiagLogger
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource

/**
 * Records from a USB audio input (docs/specs/usb-sources.md, ADR 0022).
 *
 * `preferredDevice` is only a hint and the USB input can re-enumerate when a composite UVC+UAC
 * device opens its camera, so this source resolves the input again right before recording,
 * checks where Android actually routed it, re-routes on changes, and reports every fallback to
 * the built-in mic through [onIssue] (→ `warning` event) instead of failing silently.
 *
 * While recording it also watches the PCM flow (ADR 0023): a 5 s `pcm:` summary and a 30 s `mics:` line go to
 * DiagLogger, and [onStall] fires (main thread, once per recording session) when the input stops delivering.
 * The owner decides what to do: [restartCapture] or swap to the phone mic.
 */
class UsbAudioSource(
    private val context: Context,
    usbAudioDeviceId: Int?,
    private val onIssue: (code: String, message: String, details: Map<String, Any?>) -> Unit = { _, _, _ -> },
    private val onStall: (source: UsbAudioSource, reason: String) -> Unit = { _, _ -> },
    /** Hum/noise cleanup run on each read, after the raw `pcm:` stats (docs/specs/audio-cleanup.md). */
    private val cleanup: AudioCleanupChain? = null
) : AudioSource() {

    companion object {
        private const val TAG = "UsbAudioSource"
        const val CODE_NOT_FOUND = "USB_AUDIO_DEVICE_NOT_FOUND"
        const val CODE_NOT_ROUTED = "USB_AUDIO_NOT_ROUTED"
        /** AudioEncoder's max-input-size is 8192 and BaseEncoder cuts anything above the codec buffer. */
        private const val MAX_READ_BYTES = 4096
        private const val WATCH_INTERVAL_MS = 500L
        private const val PCM_LOG_INTERVAL_MS = 5_000L
        private const val MICS_LOG_INTERVAL_MS = 30_000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Requested input. Updated when the device re-enumerates under a new id. */
    private var wantedId: Int? = usbAudioDeviceId
    private var wantedName: String? = null

    private var audioRecord: AudioRecord? = null
    /** Read chunk in bytes, from create() (AudioRecord.bufferSizeInFrames is API 23). */
    private var readBufferBytes = 0
    private var readThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var muted = false
    /** One NOT_ROUTED warning per bad-route episode; cleared when the route is back on USB. */
    private var notRoutedWarned = false
    private var notFoundWarned = false
    private var routingListener: Any? = null
    private var deviceCallback: AudioDeviceCallback? = null

    /** create() params, kept for [restartCapture]. */
    private var createParams: IntArray? = null
    private var micData: GetMicrophoneData? = null
    /** Bumped per start(); an old read thread exits when it no longer matches. */
    @Volatile private var session = 0
    private val stats = PcmStats()
    private var stallDetector: AudioStallDetector? = null
    private var stallReported = false
    private var lastPcmLogAt = 0L
    private var lastMicsLogAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (t: Throwable) {
                DiagLogger.logError("USB_AUDIO_STALLED", "watchdog tick failed: ${t.message}", t)
                if (running) mainHandler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }

        private fun tick() {
            val now = SystemClock.elapsedRealtime()
            val expected = expectedBytesPerSec()
            if (now - lastPcmLogAt >= PCM_LOG_INTERVAL_MS) {
                DiagLogger.log(TAG, "pcm: ${stats.summarize(now - lastPcmLogAt, expected)}")
                lastPcmLogAt = now
            }
            if (now - lastMicsLogAt >= MICS_LOG_INTERVAL_MS) logActiveMics("periodic")
            if (!stallReported) {
                stallDetector?.check()?.let { reason ->
                    stallReported = true
                    DiagLogger.logError("USB_AUDIO_STALLED", "$reason routed=${audioRecord?.let { routedForLog(it) }}")
                    // The owner restarts (start() re-arms this watchdog) or swaps the source (stop() ends it).
                    onStall(this@UsbAudioSource, reason)
                    return
                }
            }
            if (running) mainHandler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            wantedName = listInputs().firstOrNull { it.id == usbAudioDeviceId }?.productName
        }
        DiagLogger.log(TAG, "new: requestedId=$usbAudioDeviceId name=$wantedName inputs=${inputsForLog()}")
        if (usbAudioDeviceId == null) {
            DiagLogger.log(TAG, "new: no usbAudioDeviceId given — will use the first USB input")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { UsbAudioRouting.isUsbType(it.type) }
                .forEach {
                    DiagLogger.log(TAG, "new: caps ${it.toInfo()} sampleRates=${it.sampleRates.contentToString()} " +
                        "channelCounts=${it.channelCounts.contentToString()} encodings=${it.encodings.contentToString()}")
                }
        }
    }

    val currentDeviceId: Int? get() = wantedId

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        val channelConfig = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT) * 2

        return try {
            val record = createAudioRecord(sampleRate, channelConfig, bufferSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                DiagLogger.logError("USB_AUDIO_INIT_FAILED", "AudioRecord not initialized sampleRate=$sampleRate stereo=$isStereo")
                record.release()
                return false
            }
            audioRecord = record
            // Multiple of one stereo 16-bit frame and below the AAC encoder's input limit.
            readBufferBytes = (minOf(bufferSize, MAX_READ_BYTES) / 4).coerceAtLeast(1) * 4
            createParams = intArrayOf(sampleRate, if (isStereo) 1 else 0, if (echoCanceler) 1 else 0, if (noiseSuppressor) 1 else 0)
            DiagLogger.log(TAG, "create: sampleRate=$sampleRate stereo=$isStereo wantedId=$wantedId " +
                "minBuffer=$bufferSize readChunk=$readBufferBytes")
            applyPreferredDevice("create")
            true
        } catch (e: Exception) {
            DiagLogger.logError("USB_AUDIO_INIT_FAILED", "create failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, channelConfig: Int, bufferSize: Int): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val record = audioRecord ?: run {
            DiagLogger.logError("USB_AUDIO_INIT_FAILED", "start: no AudioRecord (create failed or not called)")
            return
        }
        micData = getMicrophoneData
        notRoutedWarned = false
        notFoundWarned = false
        stallReported = false
        // The camera of a composite device is open by now; its audio input may have a new id.
        applyPreferredDevice("start")
        registerRoutingWatchers(record)
        record.startRecording()
        running = true
        val mySession = ++session
        DiagLogger.log(TAG, "start: recording state=${record.recordingState} routed=${routedForLog(record)}")
        val sleepOffsetMs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1_000_000
        DiagLogger.log(TAG, "clock: frames use elapsedRealtimeNanos; boottime-monotonic=${sleepOffsetMs} ms")

        val detector = AudioStallDetector({ SystemClock.elapsedRealtime() }, expectedBytesPerSec())
        stallDetector = detector
        detector.start()
        stats.summarize(0, 0) // reset
        lastPcmLogAt = SystemClock.elapsedRealtime()
        lastMicsLogAt = lastPcmLogAt
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCH_INTERVAL_MS)

        readThread = Thread({
            // Any throwable ends this session's loop instead of killing the app; the stall watchdog then restarts
            // USB capture or falls back to the phone mic (ADR 0023).
            try {
                val buffer = ByteArray(readBufferBytes)
                var firstFrame = true
                while (running && mySession == session) {
                    // Same clock and moment as RootEncoder's MicrophoneManager: the encoders start at
                    // elapsedRealtimeNanos (CLOCK_BOOTTIME). System.nanoTime() lags it by all deep sleep since boot,
                    // which made every USB audio PTS clamp to 0 and YouTube unable to play the stream (ADR 0024).
                    val frameTimeUs = frameClockUs()
                    val read = record.read(buffer, 0, buffer.size)
                    if (mySession != session) break
                    stats.onRead(buffer, read, SystemClock.elapsedRealtime())
                    if (read > 0) {
                        detector.onBytes(read)
                        if (firstFrame) {
                            firstFrame = false
                            // routedDevice is reliable once audio flows.
                            mainHandler.post { verifyRoute("firstFrame"); logActiveMics("firstFrame") }
                        }
                        if (!muted) cleanup?.process(buffer, read)
                        val frame = if (muted) {
                            Frame(ByteArray(read), 0, read, frameTimeUs)
                        } else {
                            Frame(buffer.copyOf(read), 0, read, frameTimeUs)
                        }
                        getMicrophoneData.inputPCMData(frame)
                    } else if (read < 0 && running) {
                        DiagLogger.logError("USB_AUDIO_READ_FAILED", "AudioRecord.read returned $read")
                        break
                    } else if (read == 0) {
                        try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                    }
                }
            } catch (t: Throwable) {
                DiagLogger.logError("USB_AUDIO_READ_FAILED", "read loop stopped: ${t.message}", t)
            }
        }, "UsbAudioSource").also { it.isDaemon = true }
        readThread?.start()
    }

    override fun stop() {
        running = false
        session++
        mainHandler.removeCallbacks(watchdog)
        stallDetector?.stop()
        readThread?.interrupt()
        readThread = null
        unregisterRoutingWatchers()
        try { audioRecord?.stop() } catch (_: Exception) {}
        DiagLogger.log(TAG, "stop")
    }

    override fun release() {
        stop()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        DiagLogger.log(TAG, "release")
    }

    override fun isRunning(): Boolean = running

    /**
     * Re-opens the USB input: new AudioRecord, device resolved again, recording restarted with the same
     * encoder callback. Returns false when it couldn't (the owner then falls back to the phone mic).
     */
    fun restartCapture(): Boolean {
        val data = micData
        val p = createParams
        if (data == null || p == null) {
            DiagLogger.logError("USB_AUDIO_STALLED", "restartCapture: never started")
            return false
        }
        DiagLogger.log(TAG, "restartCapture: reopening USB input wantedId=$wantedId")
        stop()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        if (!create(p[0], p[1] == 1, p[2] == 1, p[3] == 1)) return false
        start(data)
        return running
    }

    /** Frame timestamp in µs on RootEncoder's encoder clock (`TimeUtils.getCurrentTimeMicro`). */
    private fun frameClockUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000

    private fun expectedBytesPerSec(): Int {
        val p = createParams ?: return 0
        return p[0] * (if (p[1] == 1) 2 else 1) * 2
    }

    /** Physical mics in use (API 28) and the system's view of active recordings (API 24). */
    private fun logActiveMics(where: String) {
        lastMicsLogAt = SystemClock.elapsedRealtime()
        val record = audioRecord ?: return
        try {
            val mics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                record.activeMicrophones.joinToString(prefix = "[", postfix = "]") {
                    "${it.description}(type=${UsbAudioRouting.typeName(it.type)} location=${micLocation(it.location)} addr=${it.address})"
                }
            } else {
                "n/a(api<28)"
            }
            val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioManager.activeRecordingConfigurations.joinToString(prefix = "[", postfix = "]") { c ->
                    val dev = c.audioDevice?.toInfo()?.toString() ?: "unknown"
                    val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " silenced=${c.isClientSilenced}" else ""
                    "source=${c.clientAudioSource} device=$dev rate=${c.format.sampleRate}$silenced"
                }
            } else {
                "n/a(api<24)"
            }
            DiagLogger.log(TAG, "mics[$where]: active=$mics recordings=$configs")
        } catch (e: Exception) {
            DiagLogger.log(TAG, "mics[$where]: query failed ${e.message}")
        }
    }

    private fun micLocation(location: Int): String = when (location) {
        1 -> "MAINBODY"
        2 -> "MAINBODY_MOVABLE"
        3 -> "PERIPHERAL"
        else -> "UNKNOWN($location)"
    }

    fun mute() { muted = true }
    fun unMute() { muted = false }
    fun isMuted(): Boolean = muted

    // --- routing ---

    /** Resolves the USB input and sets it as preferred. Returns false when no USB input exists. */
    private fun applyPreferredDevice(where: String): Boolean {
        val record = audioRecord ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            reportNotFound(where, "USB audio routing needs Android 6.0 (API 23); recording from the default mic")
            return false
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val selection = UsbAudioRouting.select(devices.map { it.toInfo() }, wantedId, wantedName)
        if (selection == null) {
            reportNotFound(where, "no USB audio input attached (requested id=$wantedId name=$wantedName); recording from the phone mic")
            return false
        }
        val device = devices.first { it.id == selection.input.id }
        val ok = record.setPreferredDevice(device)
        if (selection.match != UsbAudioRouting.Match.ID) {
            DiagLogger.log(TAG, "$where: requested id=$wantedId not present, matched by ${selection.match} → ${selection.input}")
        }
        wantedId = selection.input.id
        wantedName = selection.input.productName
        DiagLogger.log(TAG, "$where: preferredDevice=${selection.input} match=${selection.match} accepted=$ok")
        return true
    }

    /** Warns once per start when the route is not USB; tries one re-route first. */
    private fun verifyRoute(where: String) {
        val record = audioRecord ?: return
        if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val routed = record.routedDevice
        DiagLogger.log(TAG, "$where: routed=${routedForLog(record)}")
        if (routed == null) return // not decided yet; the routing listener fires when it is
        if (UsbAudioRouting.isUsbType(routed.type)) {
            if (notRoutedWarned) DiagLogger.log(TAG, "$where: route back on USB")
            notRoutedWarned = false
            return
        }
        applyPreferredDevice("$where-reroute")
        if (!notRoutedWarned) {
            notRoutedWarned = true
            val msg = "Audio is recording from ${routed.productName} (${UsbAudioRouting.typeName(routed.type)}) " +
                "instead of the USB input; re-route requested"
            DiagLogger.logError(CODE_NOT_ROUTED, "$where: $msg inputs=${inputsForLog()}")
            onIssue(CODE_NOT_ROUTED, msg, mapOf(
                "routedDevice" to routed.productName.toString(),
                "routedType" to UsbAudioRouting.typeName(routed.type),
                "requestedDeviceId" to wantedId
            ))
        }
    }

    private fun reportNotFound(where: String, msg: String) {
        DiagLogger.logError(CODE_NOT_FOUND, "$where: $msg inputs=${inputsForLog()}")
        // create() runs at initPreview and start() again at startStream: warn once per recording session.
        if (notFoundWarned) return
        notFoundWarned = true
        onIssue(CODE_NOT_FOUND, msg, mapOf("requestedDeviceId" to wantedId))
    }

    private fun registerRoutingWatchers(record: AudioRecord) {
        unregisterRoutingWatchers()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val listener = AudioRouting.OnRoutingChangedListener { verifyRoute("routingChanged") }
            record.addOnRoutingChangedListener(listener, mainHandler)
            routingListener = listener
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    val usbIn = added.filter { it.isSource && UsbAudioRouting.isUsbType(it.type) }
                    if (usbIn.isEmpty() || !running) return
                    DiagLogger.log(TAG, "usb input added: ${usbIn.map { it.toInfo() }} — re-applying preferred device")
                    applyPreferredDevice("deviceAdded")
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    val usbIn = removed.filter { it.isSource && UsbAudioRouting.isUsbType(it.type) }
                    if (usbIn.isEmpty()) return
                    DiagLogger.log(TAG, "usb input removed: ${usbIn.map { it.toInfo() }} running=$running")
                }
            }
            // Registering replays currently-present devices to onAudioDevicesAdded; harmless re-apply.
            audioManager.registerAudioDeviceCallback(callback, mainHandler)
            deviceCallback = callback
        }
    }

    private fun unregisterRoutingWatchers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (routingListener as? AudioRouting.OnRoutingChangedListener)?.let {
                audioRecord?.removeOnRoutingChangedListener(it)
            }
        }
        routingListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        }
        deviceCallback = null
    }

    // --- logging helpers ---

    private fun listInputs(): List<AudioInputInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toInfo() }
        } else {
            emptyList()
        }

    private fun inputsForLog(): String = listInputs().joinToString(prefix = "[", postfix = "]")

    private fun routedForLog(record: AudioRecord): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            record.routedDevice?.toInfo()?.toString() ?: "unknown"
        } else {
            "n/a(api<23)"
        }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun AudioDeviceInfo.toInfo() = AudioInputInfo(id, type, productName?.toString() ?: "")
}
