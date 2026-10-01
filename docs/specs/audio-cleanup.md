# Spec — Mic audio cleanup (hum / static / hiss) — Android only

**Source of truth:** `android/.../audio/` (`AudioCleanupChain`, `Biquad`, `Dynamics.kt`, `HumProbe`, `Denoiser`,
`RnnoiseNative`, `CleanupAudioEffect`), `android/src/main/cpp/` (vendored RNNoise 0.2 + `rnnoise_jni.c`).
Decision: [ADR 0025](../decisions/0025-audio-cleanup.md). Not device-verified as of 2026-10-01.

## API
- `StreamConfig.audioCleanup: AudioCleanup` (`off` default · `basic` · `voice`), wire key `audioCleanup` on
  `initPreview` / `configure` (omitted when `off`).
- `controller.setAudioCleanup(AudioCleanup)` → `setAudioCleanup {mode}`. Live-safe; applies at the next audio chunk.
- Never crashes the app: `AudioCleanupChain.process` catches every throwable, restores the untouched chunk, turns
  cleanup off and sends `AUDIO_CLEANUP_FAILED` (a later `setAudioCleanup` retries). RNNoise that can't be created → `basic`.
  The USB read loop and its watchdog are wrapped too. Native crashes inside RNNoise itself can't be caught.
- Audio rate: 48 kHz; a device whose `prepareAudio(48000)` fails gets 44.1 kHz (stream still starts; `voice` → `basic`).
- Warnings: `AUDIO_CLEANUP_UNAVAILABLE` (RNNoise library didn't load, or stream not 48 kHz → `basic`),
  `AUDIO_CLEANUP_OVERLOAD` (`voice` > 50 % of real time over 5 s → `basic`).

## Where it runs
- One `AudioCleanupChain` per `CameraStreamManager`, 48 kHz stereo 16-bit (the stream's audio is prepared at 48 kHz).
- USB mic: `UsbAudioSource` calls `chain.process(buffer, read)` after the raw `pcm:` stats, before building the `Frame`.
- Phone mic: `MicrophoneSource.setAudioEffect(CleanupAudioEffect(chain))` on the default source, on a USB→mic switch and
  on the USB-stall fallback. RootEncoder calls it on every read.
- Muted audio is zeros and skips the chain.

## Chain (per sample frame; filters and RNNoise per channel; gate/AGC/limiter linked)
1. High-pass 80 Hz, 2nd-order Butterworth (rumble, DC, most of the hum fundamental).
2. Notches at 50, 100, 150 Hz, Q 30 (mains hum and harmonics; Bangladesh mains is 50 Hz).
3. `voice` only: RNNoise. `FrameDenoiser` buffers 480 samples (10 ms at 48 kHz), so output length = input length with a
   fixed 10 ms delay. Floats in int16 scale. Returns a voice-activity probability per frame.
4. Noise gate / expander: peak envelope (1 ms attack, 50 ms release) vs −50 dBFS, or VAD ≥ 0.5 → open; 200 ms hold;
   closed gain −20 dB (not full mute); 10 ms opening, 150 ms closing.
5. AGC toward −20 dBFS RMS, range −6 … +24 dB, 50 ms down / 1 s up, measured only while the gate is open.
6. Peak limiter at −1 dBFS, instant attack, 50 ms release.
- `off` returns the input byte-for-byte. A mode change rebuilds state (closes RNNoise states) and fades in over 20 ms.

## Diagnostics (every 5 s while streaming, `CameraStreamManager` tag)
`cleanup: mode=… effective=… in_rms=…dBFS out_rms=…dBFS gate_open=…% vad=… gain=…dB cpu=…% window=…ms | noise: hum50=…dBFS hum100=… hum150=… noise=…dBFS humShare=…% blocks=…`
- `noise:` comes from `HumProbe`: Goertzel at 50/100/150 Hz over 100 ms blocks of the **input** (left channel) that were
  entirely gate-closed (no speech). High `humShare` = earthing hum; low = broadband hiss/static. Absent when no quiet block.

## Native build
- `android/build.gradle` → `externalNativeBuild.cmake` (`src/main/cpp/CMakeLists.txt`, CMake 3.22.1, app's NDK).
  All ABIs; 16 KB page alignment. `librnnoise_jni.so` ≈ 1.4 MB per ABI (bundled model).
- RNNoise 0.2 release tarball is missing `os_support.h` for the NEON path; `rnnoise/src/os_support.h` is a shim.
- `consumer-rules.pro` keeps `RnnoiseNative` (JNI names).

## Tests
`android/src/test/kotlin/.../audio/`: `FiltersTest` (high-pass, notch, limiter, gate, AGC), `HumProbeTest`,
`AudioCleanupChainTest` (off is bit-exact, hum removed under speech, hiss pulled down, RNNoise latency, length,
unavailable/44.1 kHz fallback, mode switch, CPU guard, summary). RNNoise itself is not run on the JVM (fake denoiser).
