# Spec — Platform Channel Contract

The wire contract between Dart (`lib/src/channels/`) and native plugins. **Source of truth:**
`lib/src/channels/method_channel_bridge.dart` (Dart side) and
`android/src/main/kotlin/com/flutterrtmp/broadcaster/FlutterRtmpBroadcasterPlugin.kt` (`onMethodCall`).

Any change here must land on **both** sides in one change, plus this file. Use the
`add-channel-method` skill.

| Channel | Name |
|---|---|
| MethodChannel | `flutter_rtmp_broadcaster/control` |
| EventChannel | `flutter_rtmp_broadcaster/status` |
| PlatformView viewType | `flutter_rtmp_broadcaster/camera_preview` |

Status legend: ✅ implemented · ⛔ returns `notImplemented` · ❌ not implemented (iOS plugin is a scaffold stub and does not register these channels yet).

---

## MethodChannel methods

| Method | Arguments | Returns | Android | iOS |
|---|---|---|---|---|
| `initPreview` | `StreamConfig.toMap()` (see below) | `null` | ✅ | ❌ |
| `configure` | `rtmpEndpoint: String`, `sponsors: List<Map>`, + `StreamConfig.toMap()` | `null` | ✅ | ❌ |
| `startStream` | — | `null` | ✅ | ❌ |
| `stopStream` | — | `null` | ✅ | ❌ |
| `updateOverlay` | `layerId: 'scoreband'`, `bytes: Uint8List`, `width: int`, `x: int`, `y: int` | `null` | ✅ | ❌ |
| `updateSponsors` | `sponsors: List<Map>` | — | ⛔ | ❌ |
| `switchCamera` | `facing: 'front' \| 'back'` | `null` | ✅ (no-op for UVC) | ❌ |
| `rebindPreview` | — | `null` | ✅ | ❌ |
| `setAudioMute` | `muted: bool` | `null` | ✅ | ❌ |
| `setAppOrientation` | `orientation: 'portrait' \| 'landscape'` | `null` | ✅ | ❌ |
| `listUsbVideoDevices` | — | `List<Map>` (UVC) | ✅ | ❌ |
| `listUsbAudioDevices` | — | `List<Map>` (UAC) | ✅ | ❌ |
| `requestUsbPermission` | `deviceId: int` | `bool` granted | ✅ | ❌ |
| `exportDiagnostics` | — | `String` log text | ✅ | ❌ |
| `clearDiagnostics` | — | `null` | ✅ | ❌ |

### `StreamConfig.toMap()` keys (sent by `initPreview` and `configure`)

| Key | Type | Native default if absent |
|---|---|---|
| `width`, `height` | int | 1280, 720 |
| `fps` | int | 30 |
| `videoBitrate` | int (bps) | 2 500 000 (`configure` only; `initPreview` always uses 2.5 Mbps) |
| `keyframeIntervalSeconds` | int | 2 |
| `orientation` | `'portrait' \| 'landscape'` | `initPreview`: portrait · `configure`: landscape |
| `initialFacing` | `'front' \| 'back'` | back |
| `videoInput` | `'device' \| 'usb'` | device |
| `audioInput` | `'mic' \| 'usb'` | mic |
| `usbVideoDeviceId`, `usbAudioDeviceId` | int? | omitted when null |

### Sponsor map (`SponsorOverlay.toMap()` → Kotlin `SponsorConfig.fromMap`)

| Key | Type | Notes |
|---|---|---|
| `bytes` | Uint8List | PNG/JPG. HEIC fails decode → `SPONSOR_DECODE_FAILED` warning |
| `left`, `right`, `top`, `bottom` | int? 0–100 | edge anchors, omitted when null |
| `width`, `height` | int 1–100 | BoxFit.contain bounding box, % of stream dims |

Semantics: [overlay-compositing.md](overlay-compositing.md).

### Scoreband args (`updateOverlay`)

`width` 1–100 (% of stream width), `x`/`y` 0–100 (0 = left/top edge, 100 = right/bottom edge).
Dart defaults `width=90, x=50, y=100` → bottom-center, 90% wide. Height derives from PNG aspect.

### UVC device map (`listUsbVideoDevices`)

`deviceId:int, vendorId:int, productId:int, productName:String, manufacturerName:String, hasPermission:bool`
→ Dart `UsbDeviceInfo`.

### UAC device map (`listUsbAudioDevices`)

`deviceId:int` (AudioDeviceInfo id), `productName:String`, `type:int` → Dart `UsbAudioDeviceInfo`.

---

## EventChannel events

Every event is a `Map` with `type`. Dart parses via `RtmpStatus.fromMap`; **unknown `type` maps to
`RtmpStatusType.error`** — so every new native event type needs a matching enum value.

Events emitted before Dart listens are buffered (max 32) in `RtmpConnectChecker` and flushed on `onListen`.

| `type` | Payload keys | Emitted by (Android) | Dart field |
|---|---|---|---|
| `connected` | — | `RtmpConnectChecker.onConnectionSuccess` | — |
| `disconnected` | `reason` | `onConnectionFailed`, `onDisconnect` | `reason` |
| `error` | `code`, `message` | many, see error codes | `errorCode`, `errorMessage` |
| `warning` | `code`, `message`, + extra keys | `CameraStreamManager.emitWarn` | `errorCode`, `errorMessage` |
| `bitrate` | `kbps` | `onNewBitrate` | `kbps` |
| `reconnecting` | `attempt` (1-based) | `scheduleReconnect` | `reconnectAttempt` |
| `previewBound` | — | `bindPreview` success | swallowed → `controller.previewBound = true` |
| `previewUnbound` | — | `unbindPreview` (always) | swallowed → `controller.previewBound = false` |
| `usbDetached` | `deviceId` | `UsbDeviceRegistry` detach callback | ⚠ `deviceId` not parsed into `RtmpStatus` |

---

## Error codes

Two delivery paths: **MethodChannel** `PlatformException` → rethrown as `RtmpBroadcasterException(code, message)`
by the controller; **EventChannel** `error` event on `statusStream`. Some codes use both.

| Code | Path | Cause |
|---|---|---|
| `INVALID_URL` / `INVALID_KEY` | Dart throw | empty `rtmpUrl` / `rtmpKey` in `configure` |
| `INVALID_ARGS` | method | missing required arg (`rtmpEndpoint`, `layerId`, `bytes`, `deviceId`) |
| `NO_CONTEXT` / `NO_ACTIVITY` | method | plugin not attached to engine / activity |
| `NO_MANAGER` | method | `rebindPreview` before `initPreview`/`configure` |
| `NO_PREVIEW_VIEW` | method | `rebindPreview` with no platform view mounted |
| `SURFACE_UNAVAILABLE` | method | `rebindPreview` before SurfaceTexture ready |
| `REBIND_PREVIEW_ERROR` | method | rebind threw |
| `INIT_PREVIEW_ERROR` | method | `prepareVideo`/`prepareAudio` or USB setup failed |
| `CONFIGURE_ERROR` | method | same, during `configure` |
| `NOT_CONFIGURED` | method | `startStream`/`updateOverlay` before `configure` |
| `ALREADY_STREAMING` | method | `startStream` while streaming |
| `STREAM_ERROR` | method | `startStream` threw (wraps codes below) |
| `PREVIEW_NOT_READY` | event + method | `startStream` before pipeline prepared |
| `PREVIEW_NOT_BOUND` | event + method | `startStream` before preview surface bound |
| `USB_DEVICE_GONE` / `USB_PERMISSION_REVOKED` | event + method | UVC source invalid at `startStream` |
| `STREAM_START_THREW` | event | `GenericStream.startStream` threw |
| `PREVIEW_BIND_FAILED` | event | `startPreview` threw |
| `OVERLAY_NOT_INITIALIZED` | event + method | `updateScoreband` before overlay manager exists |
| `OVERLAY_DECODE_FAILED` | event + method | scoreband PNG decode returned null |
| `UNKNOWN_LAYER` | method | `updateOverlay` with layerId ≠ `scoreband` |
| `AUTH_ERROR` | event | RTMP auth rejected |
| `MAX_RECONNECT_EXCEEDED` | event | 3 reconnect attempts failed |

## Warning codes (`type: warning`)

| Code | Cause |
|---|---|
| `NO_OVERLAYS_AT_STREAM_START` | no sponsors and no scoreband at `startStream` |
| `OVERLAY_FILTERS_LOST` | GL pipeline dropped filters before `startStream`; plugin re-applied them |
| `SPONSOR_DECODE_FAILED` | ≥1 sponsor image failed to decode; extra keys `input`, `added`, `decodeFails` |
