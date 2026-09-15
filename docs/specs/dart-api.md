# Spec — Dart Public API

**Source of truth:** `lib/flutter_rtmp_broadcaster.dart` (barrel) and `lib/src/`.
User-facing examples live in the root `README.md`; this file is the precise contract for agents.

Barrel exports: `RtmpBroadcastController`, `RtmpBroadcastWidget`, and all of `lib/src/models/`
(`OverlayPosition` [deprecated], `RtmpBroadcasterException`, `RtmpStatus`/`RtmpStatusType`,
`SponsorOverlay`, `SponsorPlacement`, `StreamConfig` + enums, `UsbDeviceInfo`, `UsbAudioDeviceInfo`).
Channel bridges are **not** exported.

## Lifecycle (expected call order)

```
initPreview(config)          → native pipeline prepared (no RTMP)
RtmpBroadcastWidget mounted  → SurfaceTexture ready → previewBound = true
configure(url, key, sponsors, config)
updateScoreband(png)         → any time after configure; repeat on score change
startStream()                → connected / bitrate / reconnecting events
stopStream()
dispose()
```

`configure` without prior `initPreview` also works (creates a fresh manager). If `config`
dimensions differ from `initPreview`'s, native releases and re-prepares the encoder.

## `RtmpBroadcastController`

| Member | Channel method | Notes |
|---|---|---|
| `ValueNotifier<bool> previewBound` | — | driven by `previewBound`/`previewUnbound` events; reset to false in `initPreview` |
| `StreamConfig get config` | — | throws (null check) until `configure` succeeds |
| `Stream<RtmpStatus> get statusStream` | EventChannel | filters out preview bind events; ⚠ each getter call creates a new filtered stream |
| `initPreview({StreamConfig? config})` | `initPreview` | default `StreamConfig.defaultConfig` |
| `configure({rtmpUrl, rtmpKey, sponsors, config})` | `configure` | sends `rtmpEndpoint = "$rtmpUrl/$rtmpKey"` |
| `updateScoreband(Uint8List png, {int width = 90, int x = 50, int y = 100})` | `updateOverlay` | layerId fixed to `scoreband` |
| `startStream()` / `stopStream()` | same | `stopStream` disables auto-reconnect |
| `switchCamera({required CameraFacing facing})` | `switchCamera` | live-safe |
| `rebindPreview()` | `rebindPreview` | cheap recovery after background/foreground |
| `setAudioMuted(bool)` | `setAudioMute` | note name mismatch Dart vs channel |
| `updateSponsors(List<SponsorOverlay>)` | `updateSponsors` | ⛔ Android returns notImplemented → throws |
| `setAppOrientation(VideoOrientation)` | `setAppOrientation` | locks activity orientation and re-prepares if dims flip |
| `listUsbVideoDevices()` / `listUsbAudioDevices()` | same | uses `invokeListMethod` |
| `requestUsbPermission(int deviceId) → bool` | same | |
| `exportDiagnostics() → String` | same | never throws; returns error text instead |
| `clearDiagnostics()` | same | not wrapped in try/catch |
| `dispose()` | — | disposes `previewBound` only; does not stop stream |

All methods except `exportDiagnostics`/`clearDiagnostics` convert `PlatformException` →
`RtmpBroadcasterException(code, message)`. Codes: [channel-contract.md](channel-contract.md#error-codes).

## `RtmpBroadcastWidget({RtmpBroadcastController? controller})`

`AndroidView` / `UiKitView` with viewType `flutter_rtmp_broadcaster/camera_preview`, keyed by
`MediaQuery.orientation` (forces a new platform view on rotation). `controller` is currently unused.
No UI chrome allowed — transparent preview only.

## Models

### `StreamConfig`
Fields: `width, height, fps, videoBitrate (bps), keyframeIntervalSeconds, orientation, initialFacing,
videoInput = device, audioInput = mic, usbVideoDeviceId?, usbAudioDeviceId?`.

| Preset | Dims | Bitrate |
|---|---|---|
| `youtube720Portrait` (= `defaultConfig`) | 720×1280 | 2.5 Mbps |
| `youtube1080Portrait` | 1080×1920 | 4.5 Mbps |
| `youtube720Landscape` | 1280×720 | 2.5 Mbps |
| `youtube1080Landscape` | 1920×1080 | 4.5 Mbps |

All presets: 30 fps, 2 s keyframe, back camera. Enums: `VideoResolution {hd720, fhd1080}` (unused by
StreamConfig), `VideoOrientation`, `CameraFacing`, `VideoInput {device, usb}`, `AudioInput {mic, usb}`.

### `SponsorOverlay({required Uint8List bytes, SponsorPlacement? placement, @Deprecated OverlayPosition? position})`
Assert: one of `placement`/`position`. Legacy `position` (0.0–1.0) is converted to a placement
(`left = x*100`, `top = y*100`, `width = w*100`, `height = 100`).

### `SponsorPlacement({int? left, right, top, bottom, required int width, required int height})`
Integers 0–100, percent of post-rotation stream frame. Rules: [overlay-compositing.md](overlay-compositing.md#sponsor-placement).

### `RtmpStatus`
`type, kbps?, reason?, errorCode? (map 'code'), errorMessage? (map 'message'), reconnectAttempt? (map 'attempt')`.
`RtmpStatusType`: `connected, disconnected, error, warning, bitrate, reconnecting, previewBound, previewUnbound, usbDetached`.

### `RtmpBroadcasterException(code, message)`

### `UsbDeviceInfo` / `UsbAudioDeviceInfo`
See [channel-contract.md](channel-contract.md#uvc-device-map-listusbvideodevices).

## Tests
`test/rtmp_broadcast_controller_test.dart` mocks the MethodChannel and asserts the payloads.
`test/models/`. Any payload change → update these tests.
