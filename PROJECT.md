# PROJECT.md — flutter_rtmp_broadcaster

> Platform-specific milestones: [`PROJECT-android.md`](PROJECT-android.md) (M2–M4) | [`PROJECT-ios.md`](PROJECT-ios.md) (M5–M7)

## Project Goal

Build a Flutter plugin package that accepts RTMP credentials, sponsor images, and a
dynamically-updating scoreband PNG from a Flutter app, composites them onto a live
camera feed in native code (using each platform library's built-in overlay primitive),
and broadcasts the result via RTMP.

---

## Current State (2026-04-23)

**M1–M4 code-complete; physical device verification deferred.** Three cross-channel/API bugs fixed: (1) `handleConfigure` now reads `rtmpEndpoint` (Dart sends combined key) instead of separate `rtmpUrl`/`rtmpKey`; (2) `SponsorConfig.fromMap` now reads `width`/`height` matching `OverlayPosition.toMap()`; (3) `CameraStreamManager.configure` was calling `prepareVideo(w, h, fps, bitrate)` — RootEncoder's signature is `prepareVideo(w, h, bitrate, fps, …)`, so bitrate was being set to 30 bps and fps to 2_500_000. Fixed by swapping arg order, and `prepare` failures now throw instead of silently logging. Example app rewritten with full Android test UI.

Next: physical device test on Android, then M5 — iOS camera + preview (HaishinKit MediaMixer).

---

## Milestone Overview

```
M1  — Package Scaffold & Dart API          [DONE]
M2  — Android: Camera + Preview            [DONE] → PROJECT-android.md
M3  — Android: Overlay Compositing         [DONE] → PROJECT-android.md
M4  — Android: RTMP Broadcast              [DONE] → PROJECT-android.md
M5  — iOS: Camera + Preview                [ ]    → PROJECT-ios.md
M6  — iOS: Overlay Compositing             [ ]    → PROJECT-ios.md
M7  — iOS: RTMP Broadcast                  [ ]    → PROJECT-ios.md
M8  — Scoreband Update Verification        [ ]
M9  — Example App                          [ ]
M10 — Polish, Error Handling & Docs        [ ]
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
- [ ] Android: confirm the old `Bitmap` is released after `setImage()` replaces it
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
- [ ] On denial: show a user-friendly explanation dialog
- [ ] On "permanently denied": provide an "Open Settings" action via `openAppSettings()`
- [ ] Confirm `controller.configure()` is gated on granted permissions
- [ ] Handle mid-stream permission revocation (graceful `stopStream()` + `error` event)

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

### M9.5 — Sponsor Image Upload (Config Screen) ✅
- [x] `image_picker: ^1.1.2` added to `example/pubspec.yaml`
- [x] `READ_MEDIA_IMAGES` permission added to `example/android/app/src/main/AndroidManifest.xml`
- [x] `NSPhotoLibraryUsageDescription` added to `example/ios/Runner/Info.plist`
- [x] `rtmp_config_screen.dart` rewritten with dynamic sponsor UI: up to 3 image slots, gallery picker, per-slot Left/Middle/Right position toggle, remove button, thumbnail preview
- [x] `_buildSponsors()` converts selected images to `SponsorOverlay` with normalized positions (y=0.02, h=0.08; x=0.02/0.39/0.76 for L/M/R)
- [x] `configure()` now passes real sponsor list instead of hardcoded `[]`

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
| Default resolution 720×1280 (portrait) | `StreamConfig.defaultConfig = youtube720Portrait`; four presets available (720p/1080p × portrait/landscape), user selects on config screen before going live |
| PNG format for scoreband bytes | Simple, lossless, universally decodable; at 0.3–0.5fps the cost is negligible |
| No Timer.periodic in package | Push model is simpler, lower latency, and avoids capturing unchanged frames |
| MethodChannel for overlay bytes | Simple and sufficient at 0.3fps; upgrade to BinaryCodec only if profiling shows need |
| Android: `GenericStream` (not `RtmpCamera2`) | `RtmpCamera2` is superseded in RootEncoder 2.5+ by the `StreamBase` pattern; `GenericStream` is the forward-compatible entry point |
| Android: plain `TextureView` (not `OpenGlView`) | `GenericStream` attaches preview to any `TextureView`/`SurfaceView` and handles the GL encoder surface internally |
| iOS: HaishinKit `MediaMixer` + `StreamSession` | 2.x API is async/await and centralizes capture + mixing + publishing. `RTMPConnection` + `RTMPStream` direct usage is legacy |
| iOS: `ScreenObject` for overlays (not manual CoreImage) | HaishinKit 2.x ships a built-in watermark/overlay primitive; rolling our own `CIContext.render` pipeline adds complexity and per-frame allocation risk |
