# 0023 — Android: USB audio stall watchdog, one restart, then phone-mic fallback

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
On 2026-10-01 a Xiaomi 24129PN74G streamed from an MT-VIKI HDMI switcher (UAC chip shown as `USB-Audio - MS2109`).
With USB video + USB audio, YouTube showed nothing (no video, no audio) although RTMP connected and bitrate events kept
coming; USB video + phone mic worked. DiagLogger showed the USB input routed correctly and one first PCM frame, then
nothing until stop. MS2109 devices are known to describe themselves as 96 kHz mono while sending 48 kHz stereo.
The user can only collect DiagLogger output in the field (the phone's USB port is taken by the switcher, so no adb).

RootEncoder 2.7.2 (bytecode): `StreamBase.changeAudioSource` supports a swap while streaming (old source stopped and
released, new one started with the same callback). `AudioEncoder` uses `max-input-size` 8192 and
`BaseEncoder.processInput` silently truncates larger frames. `GenericStreamClient` exposes sent/dropped frame counters.

## Decision
- `UsbAudioSource` watches its own PCM flow with the pure `AudioStallDetector` (no data ≥ 1.5 s or < 50 % real-time
  over 3 s, after a 1 s grace) and reports once per recording session.
- `CameraStreamManager` owns the policy (user decision 2026-10-01): restart USB capture once (`USB_AUDIO_STALLED`);
  a second stall within 30 s, or a failed restart, swaps to `MicrophoneSource` forced to the built-in mic
  (`USB_AUDIO_FALLBACK_PHONE_MIC`). A live stream with phone audio beats a blank one. The fallback holds until the next
  `initPreview`/`configure`; no automatic switch back (it could flap).
- Host apps must surface the fallback clearly; the example shows a chip and a persistent banner.
- Read chunks capped at 4096 bytes. Periodic DiagLogger summaries (`pcm:`, `stream:`, `mics[…]:`) make the next field
  log decisive without adb.

## Consequences
- A failing USB input no longer blanks the stream; the user is told the audio changed.
- Up to ~4.5 s of missing audio before the restart, and again before the fallback.
- Root cause on the MS2109 is still open; the new logs (bytes/s vs expected, physical mic, sent audio frames) are meant
  to settle it. Not device-verified as of 2026-10-01.
