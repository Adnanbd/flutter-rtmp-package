# Spec — Dart Public API

**Source of truth:** `lib/flutter_rtmp_broadcaster.dart` (barrel) and `lib/src/`.
User-facing examples live in the root `README.md`; this file is the precise contract for agents.

Barrel exports: `RtmpBroadcastController`, `RtmpBroadcastWidget`, and every file in `lib/src/models/`:
`dynamic_overlay.dart` (all dynamic overlay, content, placement, animation and carousel types, plus the top-level
validators `validateOverlayId`, `validateOverlayWeight`, `validateOverlayDuration`), `OverlayPosition` [deprecated],
`RtmpBroadcasterException`, `RtmpStatus`/`RtmpStatusType`, `SponsorOverlay`, `SponsorPlacement`, `StreamConfig` + enums,
`UsbDeviceInfo`, `UsbAudioDeviceInfo`, `ZoomInfo`/`ZoomSource`. Channel bridges are **not** exported.

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
| `Stream<RtmpStatus> get statusStream` | EventChannel | filters out preview bind events; each getter call returns a new filtered view of **one process-wide broadcast stream**, so any number of listeners (and controllers) all receive every event |
| `initPreview({StreamConfig? config})` | `initPreview` | default `StreamConfig.defaultConfig` |
| `configure({rtmpUrl, rtmpKey, sponsors, config})` | `configure` | sends `rtmpEndpoint = "$rtmpUrl/$rtmpKey"` |
| `updateScoreband(Uint8List png, {int width = 90, int x = 50, int y = 100, int weight = 50})` | `updateOverlay` | layerId fixed to `scoreband` |
| `startStream()` / `stopStream()` | same | `stopStream` disables auto-reconnect |
| `switchCamera({required CameraFacing facing})` | `switchCamera` | live-safe; resets zoom to 1.0 (`zoomChanged{cameraSwitched}`) |
| `getZoom() → ZoomInfo` | `getZoom` | Android only; `ZOOM_NOT_READY` until the camera is open |
| `setZoom(double level) → ZoomInfo` | `setZoom` | Android only; clamped; validates in Dart (`ZOOM_INVALID`); rules [camera-zoom.md](camera-zoom.md) |
| `rebindPreview()` | `rebindPreview` | cheap recovery after background/foreground |
| `setAudioMuted(bool)` | `setAudioMute` | note name mismatch Dart vs channel |
| `updateSponsors(List<SponsorOverlay>)` | `updateSponsors` | ⛔ Android returns notImplemented → throws |
| `setAppOrientation(VideoOrientation)` | `setAppOrientation` | locks activity orientation and re-prepares if dims flip |
| `listUsbVideoDevices()` / `listUsbAudioDevices()` | same | uses `invokeListMethod` |
| `requestUsbPermission(int deviceId) → bool` | same | |
| `exportDiagnostics() → String` | same | never throws; returns error text instead |
| `clearDiagnostics()` | same | not wrapped in try/catch |
| `addOverlay(DynamicOverlay)` | `overlayAdd` | validates in Dart first (same codes as native) |
| `updateOverlay(id, {content, placement, weight, OverlayDurationUpdate? duration, bool restartTimer = false})` | `overlayUpdate` | omitted args unchanged; timer kept unless `restartTimer` |
| `hideOverlay(id)` / `showOverlay(id)` | `overlayHide` / `overlayShow` | play exit/enter; idempotent |
| `removeOverlay(id, {bool animate = true})` | `overlayRemove` | exit animation unless `animate: false` |
| `clearOverlays({bool animate = false})` | `overlayClear` | dynamic overlays only |
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
| `youtube720Portrait` (= `defaultConfig`) | 720×1280 | 4 Mbps |
| `youtube1080Portrait` | 1080×1920 | 10 Mbps |
| `youtube720Landscape` | 1280×720 | 4 Mbps |
| `youtube1080Landscape` | 1920×1080 | 10 Mbps |

All presets: 30 fps, 2 s keyframe, back camera. Enums: `VideoResolution {hd720, fhd1080}` (unused by
StreamConfig), `VideoOrientation`, `CameraFacing`, `VideoInput {device, usb}`, `AudioInput {mic, usb}`.

### `SponsorOverlay({required Uint8List bytes, SponsorPlacement? placement, @Deprecated OverlayPosition? position})`
Assert: one of `placement`/`position`. Legacy `position` (0.0–1.0) is converted to a placement
(`left = x*100`, `top = y*100`, `width = w*100`, `height = 100`).

### Dynamic overlays (`models/dynamic_overlay.dart`)
`DynamicOverlay({required String id, required OverlayContent content, OverlayPlacement placement, int weight = 50, Duration? duration, OverlayAnimation enter = none, OverlayAnimation exit = none})`
(`duration` = live time only, `null` infinite), `OverlayDurationUpdate.keep()` / `.infinite()` / `.of(Duration)`.
Sealed `OverlayContent` → `ImageContent(Uint8List)`, `GifContent(Uint8List)`, `TextContent(String, {TextOverlayStyle style})`,
`TickerContent(String, {style = TickerContent.defaultStyle, speedPxPerSec, cycleDuration, loop = true, loopGap, TickerDirection direction = auto})`,
`CarouselContent(List<CarouselItem> items, {Duration interval = 5 s, CarouselTransition transition = crossfade 500 ms})`
with `CarouselItem(OverlayContent image-or-gif, {Duration? interval})` and `CarouselTransition({type = crossfade, durationMs = 500, easing = easeInOut, edge = right})` + `.cut()/.crossfade()/.push(edge:)`, enum `CarouselTransitionType {cut, crossfade, push}` ([§12](dynamic-overlays.md#12-carousel-content)).
`TextOverlayStyle({fontSizePx = 32, Color color = white, Color? background, paddingPx = 8, Uint8List? fontTtf, int maxLines = 1, TextOverlayAlign align = start})`
(colors sent as ARGB32; `maxLines > 1` wraps `TextContent` to the placement width; enum `TextOverlayAlign {start, center, end}`).
`copyWith` on `OverlayPlacement`, `TextOverlayStyle`, `TextContent`, `TickerContent` (can't clear a field to null).
`OverlayLength.percent`/`.px` are `PercentLength`/`PxLength`. Constants: `DynamicOverlay.maxIdLength = 64`,
`OverlayAnimation.maxDurationMs = 5000`, `CarouselContent.maxItems = 20`, `CarouselContent.minInterval = 500 ms`,
`TickerContent.defaultStyle` (white on 70 % black). Top-level validators `validateOverlayId`, `validateOverlayWeight`,
`validateOverlayDuration` throw the native codes.
`OverlayAnimation({type, durationMs = 400, easing = easeOut, edge = bottom})` + `.slide/.pop/.curtain`; enums
`OverlayAnimationType`, `OverlayEasing`, `OverlayEdge`, `TickerDirection`.
`OverlayPlacement({left, right, top, bottom, width, height})` of sealed `OverlayLength` → `.percent(num)` / `.px(num)`.
Full contract: [dynamic-overlays.md](dynamic-overlays.md).

### `SponsorPlacement({int? left, right, top, bottom, required int width, required int height, int weight = 10})`
Integers 0–100, percent of post-rotation stream frame. Rules: [overlay-compositing.md](overlay-compositing.md#sponsor-placement).

### `RtmpStatus`
`type, kbps?, reason?, errorCode? (map 'code'), errorMessage? (map 'message'), reconnectAttempt? (map 'attempt')`.
`RtmpStatusType`: `connected, disconnected, error, warning, bitrate, reconnecting, previewBound, previewUnbound, usbDetached, overlayShown, overlayHidden, overlayRemoved, zoomChanged`. `overlayId` (map `id`) set on overlay events and overlay warnings.
`zoom` (map `zoom` → `ZoomInfo`) set on `zoomChanged`.

### `ZoomInfo({supported, min, max, current, ZoomSource source})`
Zoom ratios; `ZoomSource {camera2, uvc}`. `ZoomInfo.fromMap`. Rules: [camera-zoom.md](camera-zoom.md).

### `RtmpBroadcasterException(code, message)`

### `UsbDeviceInfo` / `UsbAudioDeviceInfo`
See [channel-contract.md](channel-contract.md#uvc-device-map-listusbvideodevices).

## Tests
`test/rtmp_broadcast_controller_test.dart` mocks the MethodChannel and asserts the payloads.
`test/models/`. Any payload change → update these tests.
