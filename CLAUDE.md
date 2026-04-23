# CLAUDE.md — flutter_rtmp_broadcaster

## What This Project Is

A Flutter plugin package that:
- Owns the device camera (Android + iOS) natively
- Composites static sponsor images + a dynamically-updating scoreband PNG onto live video frames using each platform's built-in overlay/mix API
- Broadcasts the composited stream via RTMP

**Part A** (Flutter app, not this repo) handles: UI, scoreband widget rendering, score data feeds.
**Part B** (this package) handles: camera, frame compositing, encoding, RTMP push.

---

## Package Identity

| Field | Value |
|---|---|
| Package name | `flutter_rtmp_broadcaster` |
| Type | Flutter Plugin (Dart + Kotlin + Swift) |
| Supported platforms | android, ios only |
| Min Android SDK | 21 |
| Min iOS | 14.0 (HaishinKit 2.x baseline — verify in M1) |
| Dart SDK | `>=3.10.0 <4.0.0` |
| Flutter SDK | `>=3.38.0` (stable) |

---

## Repository Structure

```
flutter_rtmp_broadcaster/
├── lib/
│   ├── flutter_rtmp_broadcaster.dart       # Barrel export
│   └── src/
│       ├── rtmp_broadcast_controller.dart
│       ├── rtmp_broadcast_widget.dart
│       ├── models/
│       │   ├── sponsor_overlay.dart
│       │   ├── overlay_position.dart
│       │   ├── rtmp_status.dart
│       │   └── rtmp_broadcaster_exception.dart
│       └── channels/
│           ├── method_channel_bridge.dart
│           └── event_channel_bridge.dart
├── android/src/main/kotlin/com/flutterrtmp/broadcaster/
│   ├── FlutterRtmpBroadcasterPlugin.kt
│   ├── camera/CameraStreamManager.kt        # wraps GenericStream
│   ├── overlay/OverlayFilterManager.kt      # ImageObjectFilterRender per layer
│   └── rtmp/RtmpConnectChecker.kt           # ConnectChecker impl → EventChannel
├── ios/Classes/
│   ├── FlutterRtmpBroadcasterPlugin.swift
│   ├── camera/CameraStreamManager.swift     # wraps MediaMixer + StreamSession
│   ├── overlay/OverlayCompositor.swift      # HaishinKit ScreenObject per layer
│   └── rtmp/RtmpSessionObserver.swift       # StreamSession events → EventChannel
└── example/lib/main.dart
```

---

## Core Architectural Rules

1. **Camera Ownership** — The package owns the camera 100%. The app must NOT use the `camera` package or `CameraController`. Preview is exposed via PlatformView.
2. **Push Model, No Polling** — No `Timer.periodic`. The app pushes scoreband PNG bytes to `controller.updateScoreband()` only when score data changes (~every 2–3s).
3. **Sponsors are Static** — Sent once in `configure()`, cached natively as overlay layers (Android: `Bitmap` in `ImageObjectFilterRender`; iOS: `CGImage` set on a `ScreenObject`). Not re-sent unless `updateSponsors()` is explicitly called.
4. **One Widget** — `RtmpBroadcastWidget` is the only visual the package exposes. All compositing is native — Flutter sees only the camera preview.
5. **RTMP URL Assembly** — App provides `rtmpUrl` + `rtmpKey` separately. Package combines as `"$rtmpUrl/$rtmpKey"` internally.
6. **One Camera Session End-to-End** — Each platform library owns the single camera pipeline; we do not open a second session for preview. On Android, `GenericStream.startPreview(textureView)` attaches the existing encoder pipeline's preview output. On iOS, `MediaMixer` owns `AVCaptureSession`; we attach its video output to both an `AVCaptureVideoPreviewLayer` (preview) and the `StreamSession` (encoder).

---

## Channel Contracts

### MethodChannel: `'flutter_rtmp_broadcaster/control'`

| Method | Key Arguments |
|---|---|
| `configure` | `rtmpUrl, rtmpKey, sponsors: [{bytes, x, y, w, h}]` |
| `startStream` | — |
| `stopStream` | — |
| `updateOverlay` | `layerId: 'scoreband', bytes: Uint8List` |
| `updateSponsors` | `sponsors: [{bytes, x, y, w, h}]` |
| `switchCamera` | `facing: 'front' \| 'back'` |
| `setAudioMute` | `muted: bool` |

### EventChannel: `'flutter_rtmp_broadcaster/status'`

| Event | Payload |
|---|---|
| `connected` | `{}` |
| `disconnected` | `{ reason }` |
| `error` | `{ code, message }` |
| `bitrate` | `{ kbps }` |
| `reconnecting` | `{ attempt }` |

---

## Android Native Stack

- **RTMP:** RootEncoder `2.7.2` — artifact `com.github.pedroSG94.RootEncoder:library:2.7.2` (JitPack).
- **Streaming pipeline:** `GenericStream(context, connectChecker)` (StreamBase pattern). `RtmpCamera2` is deprecated and must NOT be used — `GenericStream` is the forward-compatible entry point that handles camera source, encoder, and RTMP sink.
- **Preview:** `TextureView` owned by a `FlutterPlatformView`; attached to the stream via `genericStream.startPreview(textureView)` and detached via `stopPreview()`.
- **Compositing:** `ImageObjectFilterRender` (bundled in RootEncoder, OpenGL ES GPU) — one instance per layer. Registered on the stream via `genericStream.getGlInterface().addFilter(filter)`. `setImage(Bitmap)` is thread-safe for live swaps.
- **Audio:** AAC via the built-in microphone source + encoder (`prepareAudio()`).
- **Config call order (critical):** `prepareVideo(...)` + `prepareAudio(...)` → `getGlInterface().addFilter(...)` for each layer → `startPreview(textureView)` → later, on start, `startStream(endpoint)`.

**Render layer order (bottom → top):**
```
Camera frame → Sponsor_0 → Sponsor_1 → … → Scoreband
```
Filters are added to `genericStream.getGlInterface()` in the order above; `ImageObjectFilterRender.setImage(Bitmap)` updates a layer's bitmap on a live stream.

---

## iOS Native Stack

- **RTMP + capture:** HaishinKit `2.2.5` (SPM; CocoaPods is also supported via the package podspec — pick one in M1).
- **Pipeline:** `MediaMixer(captureSessionMode: .manual, multiTrackAudioMixingEnabled: true)` owns `AVCaptureSession`. Publishing goes through a `StreamSession` built via `StreamSessionBuilderFactory.shared.make(url).build()`; the mixer's output is attached to the session. APIs are async/await.
- **Camera:** `mixer.attachVideo(AVCaptureDevice)` — HaishinKit selects the correct `AVCaptureDeviceInput` and wires it to the `AVCaptureVideoDataOutput` internally.
- **Preview:** `AVCaptureVideoPreviewLayer` attached to the `MediaMixer`'s capture session, hosted in a `UIView` → `FlutterPlatformView`.
- **Compositing:** HaishinKit's built-in `ScreenObject` (watermark/overlay primitive) — one per layer. Sponsor `ScreenObject`s created once in `configure()`. Scoreband `ScreenObject` has its `contents` (CGImage) swapped when `updateScoreband()` fires. No manual `CIContext.render` pipeline is needed.
- **Audio:** `mixer.attachAudio(AVCaptureDevice)` → routed into the stream session automatically.

**Per-frame flow (library-internal, not our code):**
```
AVCaptureSession → MediaMixer (compositing ScreenObjects) → StreamSession → RTMP server
```

Our responsibility is to (1) configure the mixer + session, (2) add ScreenObjects, (3) swap scoreband image on update, (4) handle session events.

---

## Dart Public API

```dart
final controller = RtmpBroadcastController();

await controller.configure(
  rtmpUrl: 'rtmp://live.example.com/app',
  rtmpKey: 'stream-key',
  sponsors: [
    SponsorOverlay(
      bytes: imageBytes,
      position: OverlayPosition(x: 0.0, y: 0.0, width: 0.2, height: 0.1), // normalized 0.0–1.0
    ),
  ],
);

await controller.updateScoreband(pngBytes);  // call when score data changes
await controller.startStream();
await controller.stopStream();
controller.statusStream.listen((status) { ... });
await controller.switchCamera(facing: CameraFacing.front);
await controller.setAudioMuted(true);
controller.dispose();

// Widget — camera preview only, no overlays visible in Flutter
RtmpBroadcastWidget(controller: controller)
```

---

## Scoreband Integration Pattern (for app developer)

```dart
Future<void> onScoreUpdate(ScoreData data) async {
  setState(() => _currentScore = data);               // rebuild widget
  await WidgetsBinding.instance.endOfFrame;           // wait for repaint
  final boundary = _key.currentContext!.findRenderObject() as RenderRepaintBoundary;
  final image = await boundary.toImage(pixelRatio: 2.0);
  final bytes = (await image.toByteData(format: ImageByteFormat.png))!.buffer.asUint8List();
  await controller.updateScoreband(bytes);            // push to package
}
```

---

## Permissions

**Android** (`AndroidManifest.xml`): `CAMERA`, `RECORD_AUDIO`, `INTERNET`
**iOS** (`Info.plist`): `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`

Package must request permissions at runtime before `configure()`. Use `permission_handler` in the example app.

---

## Defaults & Conventions

- Stream resolution: **1280×720 @ 30fps, 2500kbps video, 128kbps AAC**
- Overlay positions: **normalized 0.0–1.0** in Dart, converted to absolute pixels in native using stream dimensions (1280×720)
- Errors thrown as `RtmpBroadcasterException(code, message)` from Dart; forwarded via EventChannel from native
- Auto-reconnect: 3 attempts with 3s delay, fires `reconnecting` event each try, `MAX_RECONNECT_EXCEEDED` error after all fail

---

## Claude Code Behavior Notes

- **Always update `PROJECT.md`** after adding, modifying, or completing any feature, task, or subtask. Mark checkboxes, update progress, add new entries. This file serves as the authoritative context tracker for all development work.
- Write **Kotlin** for Android (not Java), **Swift** for iOS (not Objective-C).
- Never use `dart:mirrors`.
- All `MethodChannel` calls from Dart use `invokeMethod` — never `invokeMapMethod` directly.
- Overlay positions are always normalized in Dart; native converts using stream dimensions (1280×720).
- Do not add UI chrome to `RtmpBroadcastWidget` — it is a transparent camera preview only.
- When editing native code, confirm `FlutterPluginBinding` / registrar is properly passed before touching channel setup.
- **Android:** Use `GenericStream`, never `RtmpCamera2`. Register overlay filters via `genericStream.getGlInterface().addFilter(...)`, not against the preview view.
- **iOS:** Use `MediaMixer` + `StreamSession` (async/await). Do not manually run a CoreImage `CIContext.render` pipeline or manage `CVPixelBufferPool` — HaishinKit `ScreenObject` handles compositing.
