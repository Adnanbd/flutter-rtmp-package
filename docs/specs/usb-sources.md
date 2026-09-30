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
- Zoom: `ZoomableUvcCamera` (subclass exposing libuvc's `mZoomMin`/`mZoomMax`); `zoomLimits()`, `setZoomPercent()`,
  `zoomPercent()` feed `UvcZoomTarget`. Cameras without `CT_ZOOM_ABSOLUTE` report `supported: false`.
  See [camera-zoom.md](camera-zoom.md).

## `UsbAudioSource : AudioSource`
- `AudioRecord(MIC)` with `preferredDevice` = matching USB input (API 23+); falls back to default mic if not found.
- PCM 16-bit read loop on a daemon thread; mute sends zeroed buffers.

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
