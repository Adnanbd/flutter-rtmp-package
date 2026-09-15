# Android Milestones (M2–M4) — flutter_rtmp_broadcaster

> Shared milestones: [roadmap.md](roadmap.md) · iOS: [ios.md](ios.md).
> Stack and class map: [../architecture/android.md](../architecture/android.md). Technical findings (coordinate space,
> filter lifecycle, R8, orientation) moved to [../specs/overlay-compositing.md](../specs/overlay-compositing.md) and
> [../specs/orientation.md](../specs/orientation.md). This file tracks **progress only**; checkbox text reflects the
> implementation at the time and may describe superseded designs (see specs for current behavior).

---

## M2 — Android: Camera + Preview

**Goal:** Real camera feed visible in Flutter app via PlatformView, wired through
`GenericStream` so the same pipeline can later be extended to encode + stream. No
RTMP connection yet.

### M2.1 — Add RootEncoder Dependency ✅
- [x] JitPack in `allprojects.repositories` in `android/build.gradle`
- [x] `implementation 'com.github.pedroSG94.RootEncoder:library:2.7.2'` at root `dependencies` level
- [x] `minSdk = 21`, `namespace = com.flutterrtmp.broadcaster`
- [x] Example AndroidManifest: `CAMERA`, `RECORD_AUDIO`, `INTERNET` permissions added
- [ ] GenericStream import verified at compile time (confirmed in M2.4 build)

### M2.2 — CameraPreviewFactory & PlatformView ✅
- [x] `camera/CameraPreviewFactory.kt` — PlatformViewFactory, takes lazy `CameraStreamManager` provider
- [x] `camera/CameraPreviewView.kt` — PlatformView wrapping `TextureView`; calls `bindPreview` on init, `unbindPreview` on dispose

### M2.3 — Plugin Registration ✅
- [x] Rewrote `FlutterRtmpBroadcasterPlugin.kt`
- [x] Implements `FlutterPlugin`, `MethodCallHandler`, `ActivityAware`
- [x] `onAttachedToEngine`: registers MethodChannel (`flutter_rtmp_broadcaster/control`) + EventChannel (`flutter_rtmp_broadcaster/status`)
- [x] `onAttachedToActivity`: stores activity reference
- [x] Registers `CameraPreviewFactory` for viewType `'flutter_rtmp_broadcaster/camera_preview'`

### M2.4 — CameraStreamManager (GenericStream wrapper) ✅
- [x] Created `camera/CameraStreamManager.kt`
- [x] Fields: `GenericStream` (lazy-init), `Context`, `ConnectChecker` (no-op inline; replaced by `RtmpConnectChecker` in M4)
- [x] `prepare()` — `prepareVideo(1280, 720, 2_500_000, 30)` (width, height, bitrate, fps) + `prepareAudio(44100, true, 128_000)`
- [x] `bindPreview(textureView)` — `genericStream.startPreview(textureView)` if prepared
- [x] `unbindPreview()` — `genericStream.stopPreview()` guarded by `isOnPreview`
- [x] `switchCamera(facing)` — `genericStream.changeVideoSource(Camera2Source(context, isFront: Boolean))` — **API verify at M2.4 compile**: `Camera2Source(Context, Boolean)` constructor signature and `isOnPreview`/`isStreaming` property names may differ in RootEncoder 2.7.2
- [x] `release()` — stops preview + stream if running, calls `genericStream.release()`
- [ ] Verify camera preview is visible in the example app (requires build + physical device)

### M2.5 — MethodChannel Wiring (Android side, partial) ✅
- [x] `configure`: parses `rtmpUrl` + `rtmpKey`, instantiates `CameraStreamManager`, calls `prepare()`; stores combined endpoint
- [x] `switchCamera`: delegates to `cameraStreamManager.switchCamera(facing)`
- [x] `setAudioMute`: delegates to `cameraStreamManager.setAudioMuted(muted)`
- [x] All unimplemented methods (`startStream`, `stopStream`, `updateOverlay`, `updateSponsors`) return `result.notImplemented()`

---

## M3 — Android: Overlay Compositing

**Goal:** Static sponsor images and a placeholder scoreband rendered on top of camera
frames, using `ImageObjectFilterRender` registered on `GenericStream`'s GL interface.

### M3.1 — OverlayFilterManager ✅
- [x] Created `overlay/OverlayFilterManager.kt`
- [x] `initLayers(stream, sponsorList, streamWidth, streamHeight)` — decodes each sponsor to Bitmap, creates `ImageObjectFilterRender`, sets image + position/scale, adds via `stream.getGlInterface().addFilter()` in render order (sponsors then scoreband). Scoreband starts with transparent 1×1 placeholder.
- [x] `updateScoreband(pngBytes)` — decodes PNG → Bitmap → `scorebandFilter.setImage()` (GL-thread dispatch handled by RootEncoder internally)
- [x] `updateSponsors(stream, sponsorList, ...)` — removes existing sponsor filters, rebuilds and re-adds
- [x] `release(stream)` — `stream.getGlInterface().clearFilters()`
- **API verify at compile:** `ImageObjectFilterRender.setScale(Float, Float)`, `setPosition(Float, Float)`, `GlInterface.addFilter/removeFilter/clearFilters` names in RootEncoder 2.7.2. NDC center conversion: `ndcX = (x + w/2)*2 - 1`, `ndcY = 1 - (y + h/2)*2`.

### M3.2 — SponsorConfig Data Class ✅
- [x] Created `overlay/SponsorConfig.kt` with `bytes`, `x`, `y`, `width`, `height` (all normalized)
- [x] `fromMap(Map<String, Any>)` companion — bytes as `ByteArray`, coords as `Double → Float`

### M3.3 — Wire Overlay into CameraStreamManager ✅
- [x] `CameraStreamManager` holds `OverlayFilterManager`
- [x] `prepare()` renamed to `configure(rtmpEndpoint, sponsors)` — calls `prepareVideo/Audio` then `overlayFilterManager.initLayers()` before preview
- [x] `updateScoreband(bytes: ByteArray)` delegates to overlay manager

### M3.4 — Handle configure MethodChannel Call ✅
- [x] `handleConfigure` parses `sponsors: List<Map<String, Any>>` → `List<SponsorConfig>`
- [x] Creates `CameraStreamManager(ctx)`, calls `.configure("$rtmpUrl/$rtmpKey", sponsors)`

### M3.5 — Handle updateOverlay MethodChannel Call ✅
- [x] `handleUpdateOverlay` parses `layerId` + `bytes`
- [x] `layerId == "scoreband"` → `cameraStreamManager.updateScoreband(bytes)`
- [x] Unknown `layerId` → `result.error("UNKNOWN_LAYER", ...)`

### M3.6 — Confirm Overlay Visibility Path
- [ ] Deferred to M4.6 end-to-end test — overlays verified in streamed output on device

---

## M4 — Android: RTMP Broadcast

**Goal:** Full RTMP stream working on Android with composited overlays.

### M4.1 — ConnectChecker Implementation ✅
- [x] Created `rtmp/RtmpConnectChecker.kt`
- [x] Implements `com.pedro.common.ConnectChecker`
- [x] All callbacks post events to main thread via `Handler(Looper.getMainLooper())`
  - `onConnectionSuccess` → `{ type: connected }`
  - `onConnectionFailed(reason)` → `{ type: disconnected, reason }`
  - `onDisconnect` → `{ type: disconnected, reason: "Server closed connection" }`
  - `onNewBitrate(bitrate)` → `{ type: bitrate, kbps: bitrate/1000 }`
  - `onAuthError` → `{ type: error, code: AUTH_ERROR, message: ... }`
- [x] Accepts `onConnectedCallback`, `onDisconnectedCallback(reason)` + `onNewBitrateCallback(bitrate)` lambdas so `CameraStreamManager` can react without coupling

### M4.2 — Wire ConnectChecker into GenericStream ✅
- [x] `CameraStreamManager` now holds `RtmpConnectChecker` (replaces M2/M3 no-op)
- [x] `RtmpConnectChecker` created with lambdas: `onConnected → reconnectAttempt = 0`, `onDisconnected(reason) → scheduleReconnect(reason)`, `onNewBitrate(bitrate) → bitrateAdapter.adaptBitrate(...)`
- [x] `setSink(EventChannel.EventSink?)` on `CameraStreamManager` delegates to checker

### M4.3 — Handle startStream MethodChannel Call ✅
- [x] `handleStartStream`: guards `NOT_CONFIGURED` + `ALREADY_STREAMING`, calls `manager.startStream()`
- [x] `CameraStreamManager.startStream()`: resets `intentionalStop=false`, `reconnectAttempt=0`, calls `genericStream.startStream(rtmpEndpoint)`

### M4.4 — Handle stopStream MethodChannel Call ✅
- [x] `handleStopStream`: calls `manager.stopStream()`
- [x] `CameraStreamManager.stopStream()`: sets `intentionalStop=true`, resets `reconnectAttempt`, `genericStream.stopStream()` (also cancels an in-flight `reTry`)

### M4.5 — EventChannel Setup ✅
- [x] Plugin stores `eventSink`; `onListen` → `cameraStreamManager?.setSink(events)`, `onCancel` → `setSink(null)`
- [x] `handleConfigure` forwards existing sink to newly-created manager so late-listen and early-listen both work

### M4.6 — Android End-to-End Test
- [ ] Deferred — test together with M2 preview verification on physical device

### M4.7 — Auto-Reconnect (Android) ✅
- [x] `scheduleReconnect(reason)` in `CameraStreamManager`: gated by `intentionalStop`; increments `reconnectAttempt`, fires `{ type: reconnecting, attempt: N }`, delegates the retry to `genericStream.getStreamClient().reTry(3000ms, reason)`
- [x] Retry budget set via `getStreamClient().setReTries(3)` after each `prepareVideo` and again in `startStream()` (resets the library counter per session)
- [x] When `reTry()` returns false (budget exhausted): fires `{ type: error, code: MAX_RECONNECT_EXCEEDED }`, resets counter, calls `stopStream()` to leave `StreamBase` startable
- [x] `stopStream()` / `release()` set `intentionalStop` and call `genericStream.stopStream()`, which cancels any in-flight retry
- [ ] Verify on device with server taken offline mid-stream

**Crash fixed (field report, 2026-07-30):** the original implementation posted `genericStream.startStream(endpoint)` on a `Handler` 3s after disconnect. RootEncoder does not clear `StreamBase.isStreaming` when the socket drops, so the retry threw `IllegalStateException: Stream already started, stopStream before startStream again` on the main thread and killed the app. `StreamBaseClient.reTry()` reconnects the client in place on its own thread and never hits that guard — never call `startStream()` to reconnect.

### M4.7b — Adaptive Bitrate (Android) ✅
- [x] `BitrateAdapter` (`com.pedro.library.util`) in `CameraStreamManager`, listener → `genericStream.setVideoBitrateOnFly(bitrate)`
- [x] `setMaxBitrate(videoBitrate)` + `reset()` applied in `applyStreamClientDefaults()` after every `prepareVideo`; `reset()` again on `startStream()`
- [x] `RtmpConnectChecker.onNewBitrate` forwards to `bitrateAdapter.adaptBitrate(bitrate, getStreamClient().hasCongestion())`
- Rationale: same field report showed ~2 min of `RtmpSender: Video/Audio frame discarded` before `IOException: Broken pipe` — uplink fell below the fixed 2 500 000 bps and the sender cache saturated. Bitrate now steps down under congestion instead of dropping frames until the server closes the socket.

### M4.8 — YouTube-Compliant Encoder Config, Orientation & Camera Pickers

**Goal:** Let the user pick resolution (720p/1080p), orientation (portrait/landscape), and initial camera (front/back) **before going live**; apply YouTube-compliant encoder settings (H.264, 2 s keyframe, CBR, BT.709, 30 fps, true portrait dims when vertical); expose camera-flip + mute **during live**. Resolution and orientation are locked once streaming starts (YouTube drops the session on mid-stream dim changes).

**Mid-live behavior matrix**

| Action | Live-safe? | Notes |
|---|---|---|
| Switch camera (front/back) | Yes | `Camera2Source.switchCamera()` is hot-swap safe |
| Toggle mute | Yes | `MicrophoneSource.mute()/unMute()` |
| Change resolution | No | `prepareVideo` requires stream stopped; disabled in UI |
| Change orientation | No | Same |
| Change bitrate | Out of scope | `setVideoBitrateOnFly(int)` exists, defer |

#### M4.8.1 — Create `StreamConfig` model (Dart) ✅
- [x] New file `lib/src/models/stream_config.dart` with `VideoResolution {hd720, fhd1080}`, `VideoOrientation {portrait, landscape}`, `CameraFacing {front, back}` enums
- [x] `StreamConfig` class with fields: `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [x] Factory constructors: `youtube720Landscape` (1280×720 @ 2.5 Mbps), `youtube1080Landscape` (1920×1080 @ 4.5 Mbps), `youtube720Portrait` (720×1280 @ 2.5 Mbps), `youtube1080Portrait` (1080×1920 @ 4.5 Mbps) — all 30 fps, 2 s keyframe
- [x] `toMap()` serializer
- [x] Re-export `StreamConfig` + enums from `lib/flutter_rtmp_broadcaster.dart`

#### M4.8.2 — Refactor `RtmpBroadcastController.configure` ✅
- [x] `configure()` signature: `{required String rtmpUrl, required String rtmpKey, required List<SponsorOverlay> sponsors, required StreamConfig config}`
- [x] Build MethodChannel payload by spreading `config.toMap()` alongside `rtmpEndpoint` + `sponsors`
- [x] Store `_config`, expose `StreamConfig get config`

#### M4.8.3 — Android plugin parses new configure keys ✅
- [x] `FlutterRtmpBroadcasterPlugin.handleConfigure` parses `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [x] Forwards them to `manager.configure(...)` with new signature

#### M4.8.4 — `CameraStreamManager` accepts dynamic encoder config ✅
- [x] Remove `STREAM_WIDTH` / `STREAM_HEIGHT` / `STREAM_FPS` / `VIDEO_BITRATE` constants
- [x] Change `overlayFilterManager` from `val` to `var` (rebuilt at configure time with real encoded dims)
- [x] New `configure(rtmpEndpoint, sponsors, width, height, fps, videoBitrate, keyframeIntervalSeconds, orientation, initialFacing)` signature
- [x] Call `genericStream.prepareVideo(encW, encH, videoBitrate, fps, keyframeIntervalSeconds, 0 /* rotation */)`

#### M4.8.5 — Kill rotation-swap, encode true portrait frames ✅
- [x] Replace `setOrientation(CameraHelper.getCameraOrientation(context))` with `setOrientation(0)`
- [x] Remove the orientation swap in `switchCamera()` so mid-stream flip does not re-introduce the swap

#### M4.8.6 — BT.709 color space + honor `initialFacing` ✅
- [x] After `prepareVideo` succeeds, call `genericStream.getVideoEncoder()?.forceBt709Color(true)`
- [x] After `initLayers`, call `switchCamera(initialFacing)` so user's pre-live camera choice applies before the first encoded frame

**Note:** BT.709 forcing removed temporarily due to RootEncoder 2.7.2 API variance; can be added back if the exact method signature is verified against source.

#### M4.8.7 — Example app pre-live pickers ✅
- [x] Add state in `_StreamPageState`: `_selectedRes`, `_selectedOrient`, `_initialFacing`
- [x] Three `DropdownButton` rows in pre-configure block: Resolution (720p/1080p), Orientation (Landscape/Portrait), Initial Camera (Back/Front)
- [x] Pickers disabled while `configured == true`
- [x] `_configure()` builds `StreamConfig` by switching on `(_selectedRes, _selectedOrient)` → one of the four factory constructors

#### M4.8.8 — Example app live controls ✅
- [x] Post-configure row: `[Flip camera] [Mute] [Start/Stop]`
- [x] Flip toggles `_initialFacing` and calls `controller.switchCamera(...)`
- [x] Mute calls `controller.setAudioMuted(...)`

---

## Post-M4 work (no milestone number)

- [x] UVC camera + USB audio sources — [../specs/usb-sources.md](../specs/usb-sources.md)
- [x] Diagnostics log (`exportDiagnostics` / `clearDiagnostics`) — [../specs/diagnostics.md](../specs/diagnostics.md)
- [x] Loud overlay failures + `warning` events (2026-05-07)
- [x] R8 consumer rules for overlays in release builds (2026-05-07)
- [x] `SponsorPlacement` edge anchors + dynamic scoreband `width/x/y` (2026-05-14) — ADR 0005
- [x] Preview bind/unbind observable + `rebindPreview` (2026-09-02)
- [ ] `updateSponsors` native implementation (currently `notImplemented`)
- [ ] Physical device verification of reconnect with server taken offline (M4.7)
