# 0024 — Android: custom audio/video sources stamp frames with RootEncoder's clock

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
2026-10-01, Xiaomi 24129PN74G + MT-VIKI (MS2109), USB video + USB audio: YouTube recorded the duration but showed no
frames. DiagLogger showed healthy input (`pcm: bytes/s≈176400`) and both tracks leaving the phone (`stream:` 30 video and
43 audio frames per second). USB video + phone mic worked.

RootEncoder 2.7.2 (bytecode): `StreamBase.startStream` starts the encoders with `TimeUtils.getCurrentTimeMicro()` =
`SystemClock.elapsedRealtimeNanos()/1000` (CLOCK_BOOTTIME). `AudioEncoder.calculatePts` = `max(0, frame.timeStamp −
start)`. Its `MicrophoneManager` stamps frames with the same clock. Our `UsbAudioSource` used `System.nanoTime()/1000`
(CLOCK_MONOTONIC), which stops in deep sleep, so on a phone that had slept every USB audio PTS was 0 and YouTube could
not sync audio with video.

## Decision
Every custom source stamps frames with `SystemClock.elapsedRealtimeNanos()/1000`, taken at the same moment as
RootEncoder's own sources (audio: just before `AudioRecord.read`). Recorded in `.claude/rules/android.md`.

## Consequences
- USB audio and video share one timeline on every phone, regardless of how long it slept.
- `UsbAudioSource` logs the boottime−monotonic offset at start, which shows how far off the old clock was.
- Not device-verified as of 2026-10-01.
