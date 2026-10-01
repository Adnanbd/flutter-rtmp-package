# 0025 — Android: mic cleanup chain with RNNoise, 48 kHz audio

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
After USB audio worked (ADR 0024), the public test stream carried a constant static / "earthing" noise on the mixer mic
(MT-VIKI, MS2109). The same mic in another recording app sounded crisp. The package sent raw PCM. Bangladesh mains is
50 Hz, so ground-loop hum sits at 50/100/150 Hz, often with broadband hiss.

The standard live-streaming chain (OBS / StreamYard guidance) is high-pass → noise suppression (RNNoise) → gate →
compressor/limiter. RNNoise (xiph, BSD-3) works on 480-sample frames at 48 kHz in real time. Android's `NoiseSuppressor`
is tuned for calls and often unavailable for USB inputs.

## Decision (user, 2026-10-01)
- Full chain with RNNoise; modes `off` / `basic` / `voice`, set in `StreamConfig` and live via `setAudioCleanup`.
- Applies to the USB and the phone mic.
- Stream audio prepared at 48 kHz (was 44.1 kHz): RNNoise needs it and the MS2109 is natively 48 kHz.
- Package default `off` so existing apps don't change; the example and host app choose `voice`.
- RNNoise vendored from the 0.2 release tarball and built with CMake; a 1-line-per-macro `os_support.h` shim fills a file
  missing upstream.

## Consequences
- `voice` adds 10 ms audio delay and ≈ 1.4 MB per ABI (`librnnoise_jni.so`, mostly model weights).
- The package now has a native build step (NDK + CMake) for all host apps.
- Overload / unavailable cases fall back to `basic` with a warning instead of failing.
- `cleanup:` / `noise:` diagnostics show whether the noise was hum or hiss and what the chain did.
- Not device-verified as of 2026-10-01.
