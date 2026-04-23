# PROJECT.md — flutter_rtmp_broadcaster

## Project Goal

Build a Flutter plugin package that accepts RTMP credentials, sponsor images, and a
dynamically-updating scoreband PNG from a Flutter app, composites them onto a live
camera feed in native code (using each platform library's built-in overlay primitive),
and broadcasts the result via RTMP.

---

## Current State (2026-04-23)

**M1–M4 code-complete; physical device verification deferred.** Three cross-channel/API bugs fixed: (1) `handleConfigure` now reads `rtmpEndpoint` (Dart sends combined key) instead of separate `rtmpUrl`/`rtmpKey`; (2) `SponsorConfig.fromMap` now reads `width`/`height` matching `OverlayPosition.toMap()`; (3) `CameraStreamManager.configure` was calling `prepareVideo(w, h, fps, bitrate)` — RootEncoder's signature is `prepareVideo(w, h, bitrate, fps, …)`, so bitrate was being set to 30 bps and fps to 2_500_000. Symptom: RTMP connected, preview worked, YouTube reported "Stream status: Good" with ~141 kbps (audio-only) and no video. Fixed by swapping arg order, and `prepare` failures now throw instead of silently logging. Example app rewritten with full Android test UI: RTMP URL/key form → configure → camera preview → start/stop stream → status display → live scoreband capture-and-push loop.

Next: physical device test of example app on Android, then M5 — iOS camera + preview (HaishinKit MediaMixer).

---

## Milestone Overview

```
M1 — Package Scaffold & Dart API
M2 — Android: Camera + Preview (GenericStream)
M3 — Android: Overlay Compositing (ImageObjectFilterRender)
M4 — Android: RTMP Broadcast
M5 — iOS: Camera + Preview (HaishinKit MediaMixer)
M6 — iOS: Overlay Compositing (HaishinKit ScreenObject)
M7 — iOS: RTMP Broadcast (StreamSession)
M8 — Scoreband Update Verification & Resource Audits
M9 — Example App
M10 — Polish, Error Handling & Documentation
```

---

## M1 — Package Scaffold & Dart API

**Goal:** The package compiles as a Flutter plugin, has correct structure, and the full
Dart public API exists (even if it does nothing yet).

### M1.1 — Re-scaffold as Plugin ✅
- [x] Scaffolded to /tmp, migrated plugin structure in place
- [x] pubspec.yaml: android+ios only, sdk >=3.10.0 <4.0.0, flutter >=3.38.0
- [x] android/build.gradle: minSdk=21, namespace=com.flutterrtmp.broadcaster, JitPack+RootEncoder added
- [x] ios/flutter_rtmp_broadcaster.podspec: deployment_target=14.0, HaishinKit ~> 2.2 (CocoaPods chosen)
- [x] example/ directory with minimal app harness

### M1.2 — Define Dart Models ✅
- [x] lib/src/models/overlay_position.dart
- [x] lib/src/models/sponsor_overlay.dart
- [x] lib/src/models/rtmp_status.dart
- [x] lib/src/models/rtmp_broadcaster_exception.dart

### M1.3 — Define Channel Bridges ✅
- [x] lib/src/channels/method_channel_bridge.dart
- [x] lib/src/channels/event_channel_bridge.dart

### M1.4 — Implement RtmpBroadcastController ✅
- [x] lib/src/rtmp_broadcast_controller.dart — configure, updateScoreband, start/stop, switchCamera, setAudioMuted, dispose

### M1.5 — Implement RtmpBroadcastWidget ✅
- [x] lib/src/rtmp_broadcast_widget.dart — AndroidView / UiKitView, viewType flutter_rtmp_broadcaster/camera_preview

### M1.6 — Barrel Export ✅
- [x] lib/flutter_rtmp_broadcaster.dart

### M1.7 — Example App Bootstrap ✅
- [x] permission_handler added to example/pubspec.yaml
- [x] example/lib/main.dart: requests camera+mic, mounts RtmpBroadcastWidget on grant
- [ ] NSCameraUsageDescription + NSMicrophoneUsageDescription in example/ios/Runner/Info.plist (pending — done in M5 when iOS work begins)
- [x] CAMERA, RECORD_AUDIO, INTERNET in example/android/app/src/main/AndroidManifest.xml (confirmed in M2.1)

### M1.8 — Unit Tests ✅
- [x] test/models/overlay_position_test.dart
- [x] test/models/rtmp_status_test.dart
- [x] test/rtmp_broadcast_controller_test.dart
- [x] flutter test: 12/12 passed; flutter analyze: 0 issues

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
- [x] Accepts `onConnectedCallback` + `onDisconnectedCallback` lambdas so `CameraStreamManager` can react without coupling

### M4.2 — Wire ConnectChecker into GenericStream ✅
- [x] `CameraStreamManager` now holds `RtmpConnectChecker` (replaces M2/M3 no-op)
- [x] `RtmpConnectChecker` created with lambdas: `onConnected → reconnectAttempt = 0`, `onDisconnected → scheduleReconnect()`
- [x] `setSink(EventChannel.EventSink?)` on `CameraStreamManager` delegates to checker

### M4.3 — Handle startStream MethodChannel Call ✅
- [x] `handleStartStream`: guards `NOT_CONFIGURED` + `ALREADY_STREAMING`, calls `manager.startStream()`
- [x] `CameraStreamManager.startStream()`: resets `intentionalStop=false`, `reconnectAttempt=0`, calls `genericStream.startStream(rtmpEndpoint)`

### M4.4 — Handle stopStream MethodChannel Call ✅
- [x] `handleStopStream`: calls `manager.stopStream()`
- [x] `CameraStreamManager.stopStream()`: sets `intentionalStop=true`, `cancelReconnect()`, `genericStream.stopStream()`

### M4.5 — EventChannel Setup ✅
- [x] Plugin stores `eventSink`; `onListen` → `cameraStreamManager?.setSink(events)`, `onCancel` → `setSink(null)`
- [x] `handleConfigure` forwards existing sink to newly-created manager so late-listen and early-listen both work

### M4.6 — Android End-to-End Test
- [ ] Deferred — test together with M2 preview verification on physical device

### M4.7 — Auto-Reconnect (Android) ✅
- [x] `scheduleReconnect()` in `CameraStreamManager`: gated by `intentionalStop`; increments `reconnectAttempt`, fires `{ type: reconnecting, attempt: N }`, posts `startStream` via `Handler.postDelayed(3000ms)`
- [x] After attempt 3 fails: fires `{ type: error, code: MAX_RECONNECT_EXCEEDED }`, resets counter
- [x] `stopStream()` calls `cancelReconnect()` → removes pending `Runnable` from handler queue
- [ ] Verify on device with server taken offline mid-stream

### M4.8 — YouTube-Compliant Encoder Config, Orientation & Camera Pickers

**Goal:** Let the user pick resolution (720p/1080p), orientation (portrait/landscape), and initial camera (front/back) **before going live**; apply YouTube-compliant encoder settings (H.264, 2 s keyframe, CBR, BT.709, 30 fps, true portrait dims when vertical); expose camera-flip + mute **during live**. Resolution and orientation are locked once streaming starts (YouTube drops the session on mid-stream dim changes). Plan: `~/.claude/plans/claude-plugin-marketplace-add-snazzy-lecun.md`.

**Mid-live behavior matrix**

| Action | Live-safe? | Notes |
|---|---|---|
| Switch camera (front/back) | Yes | `Camera2Source.switchCamera()` is hot-swap safe |
| Toggle mute | Yes | `MicrophoneSource.mute()/unMute()` |
| Change resolution | No | `prepareVideo` requires stream stopped; disabled in UI |
| Change orientation | No | Same |
| Change bitrate | Out of scope | `setVideoBitrateOnFly(int)` exists, defer |

#### M4.8.1 — Create `StreamConfig` model (Dart)
- [ ] New file `lib/src/models/stream_config.dart` with `VideoResolution {hd720, fhd1080}`, `VideoOrientation {portrait, landscape}`, `CameraFacing {front, back}` enums
- [ ] `StreamConfig` class with fields: `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [ ] Factory constructors: `youtube720Landscape` (1280×720 @ 2.5 Mbps), `youtube1080Landscape` (1920×1080 @ 4.5 Mbps), `youtube720Portrait` (720×1280 @ 2.5 Mbps), `youtube1080Portrait` (1080×1920 @ 4.5 Mbps) — all 30 fps, 2 s keyframe
- [ ] `toMap()` serializer
- [ ] Re-export `StreamConfig` + enums from `lib/flutter_rtmp_broadcaster.dart`

#### M4.8.2 — Refactor `RtmpBroadcastController.configure`
- [ ] Delete local `CameraFacing` / `VideoOrientation` enum declarations (now in `stream_config.dart`)
- [ ] `configure()` signature: `{required String rtmpUrl, required String rtmpKey, required List<SponsorOverlay> sponsors, required StreamConfig config}`
- [ ] Build MethodChannel payload by spreading `config.toMap()` alongside `rtmpEndpoint` + `sponsors`
- [ ] Store `_config`, expose `StreamConfig get config`

#### M4.8.3 — Android plugin parses new configure keys
- [ ] `FlutterRtmpBroadcasterPlugin.handleConfigure` parses `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [ ] Forwards them to `manager.configure(...)` with new signature

#### M4.8.4 — `CameraStreamManager` accepts dynamic encoder config
- [ ] Remove `STREAM_WIDTH` / `STREAM_HEIGHT` / `STREAM_FPS` / `VIDEO_BITRATE` constants
- [ ] Change `overlayFilterManager` from `val` to `var` (rebuilt at configure time with real encoded dims)
- [ ] New `configure(rtmpEndpoint, sponsors, width, height, fps, videoBitrate, keyframeIntervalSeconds, orientation, initialFacing)` signature
- [ ] Compute `(encW, encH)` so **portrait is truly W<H** (no rotation swap)
- [ ] Call `genericStream.prepareVideo(encW, encH, videoBitrate, fps, keyframeIntervalSeconds, 0 /* rotation */)`

#### M4.8.5 — Kill rotation-swap, encode true portrait frames
- [ ] Replace `setOrientation(CameraHelper.getCameraOrientation(context))` (line 70) with `setOrientation(0)`
- [ ] Remove the `setOrientation(getSensorOrientation(...))` call inside `switchCamera()` (line 106) so a mid-stream flip does not re-introduce the swap
- [ ] Rebuild `overlayFilterManager = OverlayFilterManager(encW, encH)` before `initLayers`

#### M4.8.6 — BT.709 color space + honor `initialFacing`
- [ ] After `prepareVideo` succeeds, call `genericStream.getVideoEncoder()?.forceBt709Color(true)`
- [ ] After `initLayers`, call `switchCamera(initialFacing)` so user's pre-live camera choice applies before the first encoded frame

#### M4.8.7 — Example app pre-live pickers
- [ ] Add state in `_StreamPageState`: `_res`, `_orient`, `_initialFacing` (plus `_currentFacing`, `_muted`)
- [ ] Three `SegmentedButton` rows in pre-configure block: Resolution (720p/1080p), Orientation (Landscape/Portrait), Initial Camera (Back/Front)
- [ ] Pickers disabled while `configured == true`
- [ ] `_configure()` builds `StreamConfig` by switching on `(_res, _orient)` → one of the four factory constructors

#### M4.8.8 — Example app live controls
- [ ] Post-configure row: `[Flip camera] [Mute/Unmute] [Start/Stop]`
- [ ] Flip toggles `_currentFacing` and calls `controller.switchCamera(...)`
- [ ] Mute calls `controller.setAudioMuted(_muted = !_muted)`
- [ ] Confirm no `disconnected` event on `statusStream` during flip

#### M4.8.9 — Dart unit tests
- [ ] New `test/stream_config_test.dart`: factory outputs match YouTube-preset dims/bitrate
- [ ] Update controller test with `MockMethodCallHandler` to verify `configure` sends expected payload `Map`

#### M4.8.10 — Manual smoke test against YouTube Studio
- [ ] 720p landscape back camera → YouTube Live Control Room reports `1280×720 @ 30 fps`, `~2500 kbps`, `Keyframe 2.0 s`, `H.264`, no B-frame warning
- [ ] 1080p landscape → `1920×1080 @ ~4500 kbps`
- [ ] 720p portrait → YouTube mobile player shows full-screen vertical (not letterboxed rotated-landscape)
- [ ] 1080p portrait → same check at higher res
- [ ] Flip camera mid-stream → no `disconnected` event
- [ ] Mute mid-stream → audio drops, video unaffected
- [ ] Resolution/orientation pickers greyed out while streaming
- [ ] Scoreband still overlays correctly across all four presets

---

## M5 — iOS: Camera + Preview

**Goal:** Real camera feed visible in Flutter app via PlatformView on iOS, wired through
HaishinKit `MediaMixer` so the same pipeline can later publish.

### M5.1 — Add HaishinKit
- [ ] Using the distribution chosen in M1.1: add `s.dependency 'HaishinKit', '~> 2.2'` to `ios/flutter_rtmp_broadcaster.podspec` (or declare in `Package.swift` if SPM)
- [ ] Run `cd example/ios && pod install` (or SPM resolve) and verify `import HaishinKit` compiles
- [ ] Confirm the iOS deployment target set in M1.1 satisfies HaishinKit 2.2.5 (bump to 14.0 / 15.0 if the library demands it)

### M5.2 — CameraStreamManager (HaishinKit MediaMixer wrapper)
- [ ] Create `camera/CameraStreamManager.swift`
- [ ] Properties:
  - `let mixer = MediaMixer(captureSessionMode: .manual, multiTrackAudioMixingEnabled: true)`
  - `var previewLayer: AVCaptureVideoPreviewLayer?`
  - `var currentLensFacing: AVCaptureDevice.Position = .back`
- [ ] `func prepare() async throws`
  - `try await mixer.setVideoMixerSettings(VideoMixerSettings(mode: .offscreen))` (or default; verify in impl)
  - Configure default device: `try await mixer.attachVideo(AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back))`
  - Configure default audio: `try await mixer.attachAudio(AVCaptureDevice.default(for: .audio))`
  - `try await mixer.startRunning()`
- [ ] `func bindPreview(layerContainer: UIView)`
  - Create `AVCaptureVideoPreviewLayer(session: mixer.session)` (exact accessor verified at impl time; HaishinKit exposes the capture session for this purpose)
  - Add as sublayer; set `videoGravity = .resizeAspectFill`
- [ ] `func unbindPreview()` — remove preview layer
- [ ] `func switchCamera(facing: String) async throws` — detach current video, attach `AVCaptureDevice` matching `facing == "front" ? .front : .back` (honor explicit Dart-side target, no toggle)
- [ ] `func stop() async` — detach inputs, `mixer.stopRunning()`
- [ ] Lock video orientation to landscape via `AVCaptureConnection.videoOrientation` (or HaishinKit equivalent) so the encoded frame matches 1280×720 landscape

### M5.3 — CameraPreviewFactory & FlutterPlatformView
- [ ] Create `CameraPreviewFactory.swift` implementing `NSObject, FlutterPlatformViewFactory`
- [ ] Create `CameraPreviewView.swift` implementing `NSObject, FlutterPlatformView`
  - `view()` returns a `UIView` that hosts an `AVCaptureVideoPreviewLayer`
  - On attach: calls `CameraStreamManager.bindPreview(layerContainer: view)` so the manager installs the preview layer from the `MediaMixer`'s session
  - On detach: `CameraStreamManager.unbindPreview()`

### M5.4 — Plugin Registration (iOS)
- [ ] Open `FlutterRtmpBroadcasterPlugin.swift`
- [ ] Implement `FlutterPlugin.register`
- [ ] Register MethodChannel + EventChannel
- [ ] Register the `CameraPreviewFactory` (created in M5.3) for viewType `'flutter_rtmp_broadcaster/camera_preview'`
- [ ] Verify preview visible in the example app before continuing (requires M1.7 permissions bootstrap in place)

---

## M6 — iOS: Overlay Compositing

**Goal:** Sponsor images and scoreband composited onto frames using HaishinKit's built-in
`ScreenObject` (watermark primitive). Replaces the older manual CoreImage pipeline — no
`CIContext.render`, no `CVPixelBufferPool`.

### M6.1 — OverlayCompositor
- [ ] Create `overlay/OverlayCompositor.swift`
- [ ] Properties:
  - `weak var mixer: MediaMixer?`
  - `var sponsorObjects: [ScreenObject] = []`
  - `var scorebandObject: ScreenObject?`
  - `let streamSize = CGSize(width: 1280, height: 720)`
- [ ] `func configureSponsors(_ sponsors: [SponsorConfig]) async throws`
  - For each sponsor: `UIImage(data:)` → `CGImage`
  - Create `ScreenObject`, set `contents = cgImage`, set frame (normalized → pixel) using `streamSize`
  - `try await mixer?.screen.addChild(screenObject)` (exact method name verified at impl time)
- [ ] `func updateScoreband(_ pngData: Data) async throws`
  - `UIImage(data:)` → `CGImage`
  - If `scorebandObject == nil`: create and add it (top z-order)
  - `scorebandObject?.contents = cgImage`
- [ ] `func updateSponsors(_ sponsors: [SponsorConfig]) async throws` — remove existing sponsor ScreenObjects, rebuild
- [ ] `func release() async` — remove all ScreenObjects from mixer

### M6.2 — Wire Compositor into CameraStreamManager
- [ ] `CameraStreamManager` holds an `OverlayCompositor` referencing its `MediaMixer`
- [ ] On `configure()`: after `prepare()`, call `compositor.configureSponsors(sponsors)`
- [ ] `func updateScoreband(_ bytes: Data)` → forwards to compositor

### M6.3 — Verify Layering & Positioning
- [ ] Confirm z-order: Camera frame → Sponsor_0 … Sponsor_N → Scoreband
- [ ] Confirm normalized-to-pixel math uses `streamSize` (not preview layer size)
- [ ] Verify with a debug build that overlays appear in the encoded output, not just preview

---

## M7 — iOS: RTMP Broadcast

**Goal:** Full RTMP stream working on iOS with composited overlays via `StreamSession`.

### M7.1 — RtmpSession (iOS)
- [ ] Create `rtmp/RtmpSession.swift`
- [ ] Properties:
  - `var session: (any StreamSession)?`
  - `weak var mixer: MediaMixer?`
  - `var eventSink: FlutterEventSink?`
- [ ] `func configure(rtmpEndpoint: String) async throws`
  - `session = try await StreamSessionBuilderFactory.shared.make(URL(string: rtmpEndpoint)!).build()`
  - Apply video settings (1280×720, 2500kbps, H.264 baseline/main) and audio settings (128kbps AAC) via the session's settings API (exact field names verified at impl time)
  - Subscribe to session status events (RTMP connect/disconnect/error) and forward to EventChannel
- [ ] `func startStream() async throws`
  - Attach `mixer` output to `session` (exact wiring: `await session.attachStream(mixer)` or equivalent — verify at impl time)
  - `try await session.connect()` / `session.publish()` (API shape per `StreamSessionBuilderFactory` build output)
- [ ] `func stopStream() async`
  - `await session.close()` or equivalent
  - Clear session reference

### M7.2 — Frame Flow (no manual work required)
- [ ] Verify: `MediaMixer` already composites `ScreenObject` overlays and pushes the final frames into the attached `StreamSession`. We do **not** wrap `CMSampleBuffer`s manually.
- [ ] Audio: same — `MediaMixer` handles audio routing into the stream session.

### M7.3 — EventChannel on iOS
- [ ] Create `EventSink` wrapper in plugin
- [ ] `RtmpSession` holds a reference to the event sender closure
- [ ] On session status events: call closure with `[String: Any]` map
- [ ] Plugin forwards to `FlutterEventSink` on main thread

### M7.4 — iOS End-to-End Test
- [ ] Same checklist as M4.6 but on iOS device
- [ ] Verify `ScreenObject` overlays present in the encoded output
- [ ] Verify `StreamSession` status events trigger EventChannel

### M7.5 — Auto-Reconnect (iOS)
- [ ] On unintentional disconnect (not via `stopStream()`): wait 3s, rebuild/reopen the `StreamSession` (or call its re-publish API — verify at impl time), max 3 attempts
- [ ] Fire `reconnecting { attempt: N }` before each retry
- [ ] After 3 failed attempts: fire `error { code: MAX_RECONNECT_EXCEEDED, message: ... }`
- [ ] `stopStream()` from Dart must cancel any in-flight `Task` handling the retry
- [ ] Event-payload shape must match Android (M4.7) so Dart listeners are platform-agnostic

---

## M8 — Scoreband Update Verification & Resource Audits

**Goal:** Full loop verified: score changes in app → PNG pushed → visible in stream < 500ms.

### M8.1 — Verify updateOverlay MethodChannel on Android
- [ ] Call `controller.updateScoreband(pngBytes)` from Dart
- [ ] Confirm bytes arrive in `OverlayFilterManager.updateScoreband()`
- [ ] Confirm `ImageObjectFilterRender.setImage()` is called
- [ ] Time the round-trip: Dart call → next encoded frame, assert < 200ms

### M8.2 — Verify updateOverlay MethodChannel on iOS
- [ ] Call `controller.updateScoreband(pngBytes)` from Dart
- [ ] Confirm bytes arrive in `OverlayCompositor.updateScoreband()`
- [ ] Confirm `scorebandObject.contents` is updated
- [ ] Confirm next encoded frame picks up the new image

### M8.3 — Thread Safety Audit
- [ ] Android: `ImageObjectFilterRender.setImage()` is GL-thread-safe internally — verify with RootEncoder wiki/source that no manual locking is needed in `OverlayFilterManager`
- [ ] iOS: all `MediaMixer` interactions are async and actor-isolated in HaishinKit 2.x — verify `updateScoreband` dispatch goes through `await` correctly

### M8.4 — Memory Audit
- [ ] Android: confirm the old `Bitmap` is released after `setImage()` replaces it (verify with RootEncoder source — `setImage` may take ownership and recycle the prior bitmap; otherwise we recycle manually)
- [ ] iOS: confirm the prior `CGImage` is released after `contents = newCgImage`
- [ ] Run with Instruments (iOS) and Android Profiler: check for memory growth over 60s

---

## M9 — Example App

**Goal:** The plugin's `example/` app demonstrates all features.

### M9.1 — Example App Structure
- [ ] `example/lib/main.dart` — entry point
- [ ] `example/lib/screens/stream_screen.dart` — main streaming UI
- [ ] `example/lib/widgets/score_band_widget.dart` — demo scoreband UI
- [ ] `example/lib/services/mock_score_service.dart` — emits fake score updates every 2.5s

### M9.2 — Permissions UX Hardening (initial bootstrap already done in M1.7)
- [ ] On denial: show a user-friendly explanation dialog stating why camera + microphone are needed
- [ ] On "permanently denied" (iOS / Android 11+): provide an "Open Settings" action via `openAppSettings()`
- [ ] Confirm `controller.configure()` is gated on granted permissions (wired in M1.7 — re-verify the guard still holds after full UI is in place)
- [ ] Handle mid-stream permission revocation (user revokes camera via Settings while streaming → graceful `stopStream()` + `error` event)

### M9.3 — Stream Screen Implementation
- [ ] Text fields for RTMP URL and RTMP Key
- [ ] `RtmpBroadcastWidget` filling 80% of screen
- [ ] Start/Stop stream button
- [ ] Status indicator (connected/disconnected/error) fed by `controller.statusStream`
- [ ] Bitrate display updated from `statusStream`
- [ ] Invisible `RepaintBoundary` wrapping `ScoreBandWidget` (off-screen capture)

### M9.4 — ScoreBandWidget (Demo)
- [ ] Visual: team names, score (wickets/runs), overs
- [ ] Sized: 800×120 logical pixels at 2.0 pixel ratio
- [ ] Updates whenever `MockScoreService` emits
- [ ] After each update: auto-captures and calls `controller.updateScoreband()`

### M9.5 — Sponsor Image Demo
- [ ] Add two demo PNG assets to `example/assets/`
- [ ] Load them at app start: `rootBundle.load('assets/sponsor_a.png')`
- [ ] Pass to `controller.configure()` with different normalized positions

### M9.6 — Example App End-to-End
- [ ] Full flow works on Android physical device
- [ ] Full flow works on iOS physical device
- [ ] Scoreband visibly updates in the RTMP stream output every 2–3 seconds

---

## M10 — Polish, Error Handling & Documentation

**Goal:** Package is ready for use by another developer without guidance.

### M10.1 — Error Handling
- [ ] Dart: all `invokeMethod` calls wrapped in try-catch, rethrow as `RtmpBroadcasterException`
- [ ] Android: all MethodChannel handlers in try-catch, call `result.error()` on failure
- [ ] iOS: all MethodChannel handlers in do-catch, call `result(FlutterError(...))` on failure
- [ ] Handle: camera already in use by another app
- [ ] Handle: RTMP connection timeout (> 10s without `connected` event)
- [ ] Handle: `configure()` called before permissions granted

### M10.2 — Auto-Reconnect Documentation (behavior implemented in M4.7 + M7.5)
- [ ] Document the reconnect state machine in README: attempt count (3), delay (3s), events fired (`reconnecting`, `MAX_RECONNECT_EXCEEDED`), and `stopStream()` cancellation semantics
- [ ] Confirm Android (M4.7) and iOS (M7.5) emit matching EventChannel payloads so Dart listeners remain platform-agnostic

### M10.3 — README.md
- [ ] Installation instructions (pubspec.yaml dependency)
- [ ] Android and iOS setup steps (permissions, podfile, jitpack)
- [ ] Minimal usage code example (configure + widget + updateScoreband)
- [ ] Full API reference table
- [ ] Scoreband integration pattern (the `endOfFrame` → `toImage()` → push pattern)

### M10.4 — API Documentation
- [ ] All public Dart classes and methods have `///` doc comments
- [ ] All public model fields documented
- [ ] `dart doc` generates without warnings

### M10.5 — CHANGELOG.md
- [ ] `0.1.0` entry with full feature list

### M10.6 — Final Checklist
- [ ] `flutter analyze` passes with zero issues
- [ ] `flutter test` passes
- [ ] Example app builds on Android without warnings
- [ ] Example app builds on iOS without warnings
- [ ] Package passes `flutter pub publish --dry-run`

---

## Dependency Summary

### Dart (`pubspec.yaml`)
```yaml
name: flutter_rtmp_broadcaster
environment:
  sdk: ">=3.10.0 <4.0.0"
  flutter: ">=3.38.0"

dependencies:
  flutter:
    sdk: flutter

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^6.0.0

flutter:
  plugin:
    platforms:
      android:
        package: com.flutterrtmp.broadcaster
        pluginClass: FlutterRtmpBroadcasterPlugin
      ios:
        pluginClass: FlutterRtmpBroadcasterPlugin
```

### Android (`android/build.gradle`)
```
repositories { maven { url 'https://jitpack.io' } }
dependencies {
  implementation 'com.github.pedroSG94.RootEncoder:library:2.7.2'
}
```

### iOS (`ios/flutter_rtmp_broadcaster.podspec`)
```ruby
s.dependency 'HaishinKit', '~> 2.2'
s.ios.deployment_target = '14.0'  # verify/raise to match HaishinKit 2.2.5 requirement
```

---

## Key Decisions Log (Do Not Change Without Review)

| Decision | Reason |
|---|---|
| Scoreband is push-based, not polled | Score changes are event-driven; polling wastes CPU and bridge bandwidth |
| Sponsors sent once at configure() | They don't change; batching them in configure avoids repeated channel calls |
| Package owns camera 100% | Prevents camera session conflicts with Flutter `camera` package |
| Normalized positions (0.0–1.0) in Dart | Resolution-agnostic; native converts to pixels using stream dimensions |
| Default resolution 1280×720 | Balanced quality and encoding cost for live sports broadcast |
| PNG format for scoreband bytes | Simple, lossless, universally decodable; at 0.3–0.5fps the cost is negligible |
| No Timer.periodic in package | Push model is simpler, lower latency, and avoids capturing unchanged frames |
| MethodChannel for overlay bytes | Simple and sufficient at 0.3fps; upgrade to BinaryCodec only if profiling shows need |
| Android: `GenericStream` (not `RtmpCamera2`) | `RtmpCamera2` is superseded in RootEncoder 2.5+ by the `StreamBase` pattern; `GenericStream` is the forward-compatible entry point and supports runtime preview attach/detach (essential for PlatformView lifecycle) |
| Android: plain `TextureView` (not `OpenGlView`) | `GenericStream` attaches preview to any `TextureView`/`SurfaceView` and handles the GL encoder surface internally; `TextureView` composes cleanly inside a `FlutterPlatformView` |
| iOS: HaishinKit `MediaMixer` + `StreamSession` | 2.x API is async/await and centralizes capture + mixing + publishing. `RTMPConnection` + `RTMPStream` direct usage is legacy |
| iOS: `ScreenObject` for overlays (not manual CoreImage) | HaishinKit 2.x ships a built-in watermark/overlay primitive; rolling our own `CIContext.render` pipeline adds complexity, per-frame allocation risk, and maintenance cost for zero benefit |
