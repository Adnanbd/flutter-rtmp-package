# Spec — USB Video (UVC) & Audio (UAC) Sources — Android only

**Source of truth:** `android/.../usb/UsbDeviceRegistry.kt`, `UvcVideoSource.kt`, `UsbAudioSource.kt`;
wiring in `CameraStreamManager.kt` (`initPreviewOnly`, `configure`, `reinitializeForOrientation`, `startStream`).

Dependency: `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.0` (`com.serenegiant.usb.*`).

## Dart flow

```dart
final cams = await controller.listUsbVideoDevices();      // UsbDeviceInfo, filtered to UVC
if (!cams.first.hasPermission) {
  final ok = await controller.requestUsbPermission(cams.first.deviceId);  // system dialog
}
final mics = await controller.listUsbAudioDevices();      // UsbAudioDeviceInfo (API 23+)
await controller.initPreview(config: StreamConfig(
  ..., videoInput: VideoInput.usb, usbVideoDeviceId: cams.first.deviceId,
       audioInput: AudioInput.usb, usbAudioDeviceId: mics.first.deviceId));
```

Pass the same USB fields again in `configure`'s `StreamConfig`.

## `UsbDeviceRegistry` (created in `onAttachedToEngine`, one per engine)
- Owns its permission flow (ADR 0021): a receiver for `<package>.flutter_rtmp_broadcaster.USB_PERMISSION` and
  `ACTION_USB_DEVICE_DETACHED` (`RECEIVER_NOT_EXPORTED` on API 33+), registered on attach, unregistered on detach.
  `requestPermission` uses `UsbManager.requestPermission` with an explicit (`setPackage`) `PendingIntent`,
  `FLAG_MUTABLE` on API 31+.
- Keeps a `USBMonitor` only for `openDevice()`. Never calls `USBMonitor.register()`: libuvc 3.2.0 builds its
  PendingIntent with flags 0, which throws on targetSdk 31+ and made every request return `false` without a dialog.
- UVC detection: `deviceClass` 14 (video) or 239 (misc/IAD), or any interface class 14.
- UAC listing: `AudioManager.GET_DEVICES_INPUTS` with `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`; empty below API 23.
- `requestPermission(deviceId, cb)`: immediate `true` if already granted, `false` if not found; else stores
  a pending callback resolved by the permission broadcast (granted or not) or detach (`false`). A newer request for
  the same device resolves the older one `false`. Failures log `USB_REGISTER_FAILED` /
  `USB_PERMISSION_REQUEST_FAILED` (DiagLogger only).
- USB devices with audio input (switchers, capture cards) also need `RECORD_AUDIO` granted before the request, or
  Android denies it. Host apps should request camera + mic first.
- Caches `UsbControlBlock` per device; `invalidateDevice` closes and evicts it (done before every new `UvcVideoSource`).
- Detach → event `{type: usbDetached, deviceId}`.

## `UvcVideoSource : VideoSource`
- `create` opens `UVCCamera` via registry control block; preview size tries MJPEG → YUYV → driver default.
- `stop()` **fully closes and destroys** the camera. Reason: reusing a camera after `stopPreview` hits a
  stale pthread handle → SIGABRT in `prepare_preview`. `start()` lazily re-creates using cached size.
- GL rotation differs from phone camera — see [orientation.md](orientation.md#gl-settings--uvc--usb-camera-uvcvideosource).
- `switchCamera` is a no-op for UVC.
- Minimum preview lifetime: `stop()`/`release()` wait until `startPreview()` is ≥ 500 ms old (`UvcTiming`, max 500 ms
  on the main thread). Closing 17 ms after start crashed natively in libuvc (Xiaomi 24129PN74G + MS2109 switcher,
  2026-10-01) when the host's platform view bound and unbound back to back. Not device-verified as of 2026-10-01.
- Zoom: `ZoomableUvcCamera` (subclass exposing libuvc's `mZoomMin`/`mZoomMax`); `zoomLimits()`, `setZoomPercent()`,
  `zoomPercent()` feed `UvcZoomTarget`. Cameras without `CT_ZOOM_ABSOLUTE` report `supported: false`.
  See [camera-zoom.md](camera-zoom.md).

## `UsbAudioSource : AudioSource`
ADR 0022. Composite devices (HDMI switchers, capture cards, webcams) can re-enumerate their USB audio input when the
camera opens, so the `AudioDeviceInfo` id picked by the app may be stale when audio starts.
- `AudioRecord(MIC)` with `preferredDevice` = USB input chosen by pure `UsbAudioRouting.select`: same id → same
  product name → first USB input (`TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`). `usbAudioDeviceId` null = first USB input.
- Resolved at `create()` and **again at `start()`**, right before `startRecording()` (camera open by then).
- Route checked after the first PCM frame (`routedDevice`) and on every `AudioRouting` change (API 24+). Not USB →
  re-resolve + set `preferredDevice` again, and warning `USB_AUDIO_NOT_ROUTED` once per bad-route episode.
- `AudioDeviceCallback` while recording: a USB input added (re-enumeration) → preferred device re-applied.
- No USB input (or API < 23) → records from the default mic and sends warning `USB_AUDIO_DEVICE_NOT_FOUND`. Never silent.
- `CameraStreamManager.installAudioSource` is used by `initPreviewOnly`, `configure` (fresh **and** reuse) and keeps the
  current source when it already matches; `"mic"` after USB swaps back to `MicrophoneSource`.
- Every step logs to `DiagLogger` (tag `UsbAudioSource`): available inputs, chosen device + match kind, routed device.
- PCM 16-bit read loop on a daemon thread; mute sends zeroed buffers; negative `read` → `USB_AUDIO_READ_FAILED` log;
  `read == 0` sleeps 5 ms. Read chunk ≤ 4096 bytes, a multiple of 4: RootEncoder's `AudioEncoder` has
  `max-input-size` 8192 and `BaseEncoder.processInput` silently cuts anything above the codec buffer.
- Stall watchdog (ADR 0023, pure `AudioStallDetector`, main thread every 500 ms while recording): after a 1 s grace,
  stalled = no PCM for ≥ 1.5 s or < 50 % of real-time bytes over 3 s. `CameraStreamManager.onUsbAudioStall`:
  first stall → `USB_AUDIO_STALLED` + `UsbAudioSource.restartCapture()` (new `AudioRecord`, device re-resolved, same
  encoder callback); another stall within 30 s or a failed restart → `MicrophoneSource` with `preferredDevice` =
  built-in mic (Android may route the default input to the attached USB device), mute state kept,
  `USB_AUDIO_FALLBACK_PHONE_MIC`. Fallback lasts until the next `initPreview`/`configure`.
- Diagnostics while recording: `new: caps …` (USB input sample rates / channel counts / encodings), `pcm:` every 5 s
  (`PcmStats`: bytes/s vs expected, reads, empty reads, max gap, peak/RMS dBFS, zero %), `mics[…]:` at first frame and
  every 30 s (`AudioRecord.activeMicrophones` API 28 = physical mic and location; `activeRecordingConfigurations`
  API 24, `silenced` API 29).

## Guards at `startStream`
`USB_DEVICE_GONE` if the device detached; `USB_PERMISSION_REVOKED` if permission lost.
Setup failures throw `USB camera setup failed: …` → `INIT_PREVIEW_ERROR` / `CONFIGURE_ERROR`.

## Host app requirements
`<uses-feature android:name="android.hardware.usb.host"/>`, plus optionally a `USB_DEVICE_ATTACHED`
intent filter with `@xml/usb_device_filter` (see `example/android/app/src/main/AndroidManifest.xml`).

## Known issues
- Rapid release + reopen can fail with `nativeConnect=-99`; `reinitializeForOrientation` avoids re-prepare when orientation is unchanged.
- `usbDetached` `deviceId` is not surfaced on `RtmpStatus`.
- Permission flow fix (ADR 0021) not device-verified as of 2026-09-30.
- USB audio routing fix (ADR 0022) not device-verified as of 2026-09-30.
- MS2109-based capture (MT-VIKI switcher, shown as `USB-Audio - MS2109` on a Xiaomi 24129PN74G): with USB audio
  selected YouTube showed nothing on 2026-10-01 while USB video + phone mic worked. Stall watchdog + fallback (ADR 0023)
  not device-verified as of 2026-10-01.
