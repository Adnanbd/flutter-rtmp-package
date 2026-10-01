// JNI bridge for RNNoise (docs/specs/audio-cleanup.md). One DenoiseState per audio channel.
// Frames are 480 floats at 48 kHz in int16 scale (-32768..32767), as rnnoise_process_frame expects.
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#define FRAME_SIZE 480

JNIEXPORT jlong JNICALL
Java_com_flutterrtmp_broadcaster_audio_RnnoiseNative_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_flutterrtmp_broadcaster_audio_RnnoiseNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle != 0) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_com_flutterrtmp_broadcaster_audio_RnnoiseNative_nativeFrameSize(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return rnnoise_get_frame_size();
}

/** Denoises one frame in place; returns the voice-activity probability (0..1), or -1 on bad input. */
JNIEXPORT jfloat JNICALL
Java_com_flutterrtmp_broadcaster_audio_RnnoiseNative_nativeProcess(JNIEnv *env, jclass clazz, jlong handle,
                                                                   jfloatArray frame) {
    (void) clazz;
    if (handle == 0 || frame == NULL || (*env)->GetArrayLength(env, frame) < FRAME_SIZE) return -1.0f;
    float buf[FRAME_SIZE];
    (*env)->GetFloatArrayRegion(env, frame, 0, FRAME_SIZE, buf);
    float vad = rnnoise_process_frame((DenoiseState *) (intptr_t) handle, buf, buf);
    (*env)->SetFloatArrayRegion(env, frame, 0, FRAME_SIZE, buf);
    return vad;
}
