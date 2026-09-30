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
import androidx.annotation.RequiresApi
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
 */
class UsbAudioSource(
    private val context: Context,
    usbAudioDeviceId: Int?,
    private val onIssue: (code: String, message: String, details: Map<String, Any?>) -> Unit = { _, _, _ -> }
) : AudioSource() {

    companion object {
        private const val TAG = "UsbAudioSource"
        const val CODE_NOT_FOUND = "USB_AUDIO_DEVICE_NOT_FOUND"
        const val CODE_NOT_ROUTED = "USB_AUDIO_NOT_ROUTED"
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

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            wantedName = listInputs().firstOrNull { it.id == usbAudioDeviceId }?.productName
        }
        DiagLogger.log(TAG, "new: requestedId=$usbAudioDeviceId name=$wantedName inputs=${inputsForLog()}")
        if (usbAudioDeviceId == null) {
            DiagLogger.log(TAG, "new: no usbAudioDeviceId given — will use the first USB input")
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
            readBufferBytes = bufferSize
            DiagLogger.log(TAG, "create: sampleRate=$sampleRate stereo=$isStereo wantedId=$wantedId")
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
        notRoutedWarned = false
        notFoundWarned = false
        // The camera of a composite device is open by now; its audio input may have a new id.
        applyPreferredDevice("start")
        registerRoutingWatchers(record)
        record.startRecording()
        running = true
        DiagLogger.log(TAG, "start: recording state=${record.recordingState} routed=${routedForLog(record)}")

        readThread = Thread({
            val buffer = ByteArray(readBufferBytes)
            var firstFrame = true
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (firstFrame) {
                        firstFrame = false
                        // routedDevice is reliable once audio flows.
                        mainHandler.post { verifyRoute("firstFrame") }
                    }
                    val frame = if (muted) {
                        Frame(ByteArray(read), 0, read, System.nanoTime() / 1000)
                    } else {
                        Frame(buffer.copyOf(read), 0, read, System.nanoTime() / 1000)
                    }
                    getMicrophoneData.inputPCMData(frame)
                } else if (read < 0 && running) {
                    DiagLogger.logError("USB_AUDIO_READ_FAILED", "AudioRecord.read returned $read")
                    break
                }
            }
        }, "UsbAudioSource").also { it.isDaemon = true }
        readThread?.start()
    }

    override fun stop() {
        running = false
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
