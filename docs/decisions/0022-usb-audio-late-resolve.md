# 0022 — Android: USB audio resolves late, verifies its route, warns on fallback

- **Status:** Accepted
- **Date:** 2026-09-30

## Context
A host app streamed from a USB HDMI switcher (MT-VIKI MT-L4UHD, UVC + UAC over one USB-C) with USB video and USB
audio selected. Video came from the switcher, audio from the phone microphone, with no error or warning.
`UsbAudioSource` resolved the `AudioDeviceInfo` id once, at `create()` during `initPreview`, before libuvc opened the
camera. It then treated `preferredDevice` as a guarantee and fell back to the default mic with only a `Log.w`
(not in the diagnostics export). The host diagnostics showed the UVC camera opening, closing and reopening within
0.7 s during preview bind; on a composite device each open can re-enumerate the audio input under a new id.
The exact trigger on this device was not confirmed (no logcat available).

## Decision
- Select the USB input with pure `UsbAudioRouting.select`: id → product name → first USB input. A null id means
  "first USB input", not "default mic".
- Resolve at `create()` and again at `start()` right before `startRecording()`.
- Verify `routedDevice` after the first PCM frame and on every routing change; re-apply `preferredDevice` when a USB
  input is added (`AudioDeviceCallback`) or the route leaves USB.
- Every fallback is loud: warnings `USB_AUDIO_DEVICE_NOT_FOUND`, `USB_AUDIO_NOT_ROUTED`, and `DiagLogger` lines.
  The stream keeps recording from the default mic rather than going silent.
- `installAudioSource` runs in `configure[reuse]` too, so a changed audio input after `initPreview` applies.

## Consequences
- Wrong-mic streams become visible to the host app and in `exportDiagnostics()`.
- If audio policy refuses USB for `MediaRecorder.AudioSource.MIC` on some phones, the warning fires but the route may
  stay wrong; next step would be trying another audio source (`UNPROCESSED`, `CAMCORDER`) or reading UAC via libusb.
- Not device-verified as of 2026-09-30.
