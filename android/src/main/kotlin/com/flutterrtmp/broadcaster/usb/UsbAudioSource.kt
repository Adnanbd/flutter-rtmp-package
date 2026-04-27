package com.flutterrtmp.broadcaster.usb

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource

class UsbAudioSource(
    private val context: Context,
    private val usbAudioDeviceId: Int?
) : AudioSource() {

    companion object {
        private const val TAG = "UsbAudioSource"
    }

    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var muted = false

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
                Log.e(TAG, "create: AudioRecord not initialized")
                record.release()
                return false
            }
            audioRecord = record
            Log.d(TAG, "create: sampleRate=$sampleRate stereo=$isStereo usbDevice=$usbAudioDeviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "create failed: $e")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, channelConfig: Int, bufferSize: Int): AudioRecord {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && usbAudioDeviceId != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val usbDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == usbAudioDeviceId }
            if (usbDevice != null) {
                record.preferredDevice = usbDevice
                Log.d(TAG, "createAudioRecord: preferred device set to ${usbDevice.productName}")
            } else {
                Log.w(TAG, "createAudioRecord: USB audio device $usbAudioDeviceId not found, using default")
            }
        }
        return record
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val record = audioRecord ?: return
        record.startRecording()
        running = true

        val bufferSize = record.bufferSizeInFrames * 2
        readThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val frame = if (muted) {
                        Frame(ByteArray(read), 0, read, System.nanoTime() / 1000)
                    } else {
                        Frame(buffer.copyOf(read), 0, read, System.nanoTime() / 1000)
                    }
                    getMicrophoneData.inputPCMData(frame)
                }
            }
        }, "UsbAudioSource").also { it.isDaemon = true }
        readThread?.start()
        Log.d(TAG, "start: recording started")
    }

    override fun stop() {
        running = false
        readThread?.interrupt()
        readThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        Log.d(TAG, "stop")
    }

    override fun release() {
        stop()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "release")
    }

    override fun isRunning(): Boolean = running

    fun mute() { muted = true }
    fun unMute() { muted = false }
    fun isMuted(): Boolean = muted
}
