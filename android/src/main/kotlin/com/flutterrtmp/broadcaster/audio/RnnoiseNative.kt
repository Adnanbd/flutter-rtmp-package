package com.flutterrtmp.broadcaster.audio

import com.flutterrtmp.broadcaster.diag.DiagLogger

/** JNI binding to `librnnoise_jni.so` (android/src/main/cpp). Kept by consumer-rules.pro. */
object RnnoiseNative {
    /** False when the native library couldn't load (e.g. an ABI the app didn't ship). */
    val available: Boolean = try {
        System.loadLibrary("rnnoise_jni")
        true
    } catch (t: Throwable) {
        DiagLogger.logError("AUDIO_CLEANUP_UNAVAILABLE", "loadLibrary(rnnoise_jni) failed: ${t.message}")
        false
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeFrameSize(): Int
    @JvmStatic external fun nativeProcess(handle: Long, frame: FloatArray): Float
}

/** RNNoise denoiser, one native state per channel. 48 kHz, 480-sample frames. */
class RnnoiseDenoiser : Denoiser {
    private var handle = RnnoiseNative.nativeCreate()
    override val frameSize: Int = RnnoiseNative.nativeFrameSize()

    init {
        // AudioCleanupChain catches this and runs BASIC instead.
        if (handle == 0L || frameSize != 480) {
            close()
            throw IllegalStateException("RNNoise init failed (handle=$handle frameSize=$frameSize)")
        }
    }

    override fun process(frame: FloatArray): Float {
        if (handle == 0L) return 0f
        return RnnoiseNative.nativeProcess(handle, frame).coerceAtLeast(0f)
    }

    override fun close() {
        if (handle != 0L) RnnoiseNative.nativeDestroy(handle)
        handle = 0L
    }
}
