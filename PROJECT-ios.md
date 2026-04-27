# PROJECT-ios.md — flutter_rtmp_broadcaster (iOS)

> For shared milestones (M1, M8–M10), dependency summary, and key decisions see `PROJECT.md`.
> For Android milestones (M2–M4) and Android technical notes see `PROJECT-android.md`.

---

## Native Stack

- **RTMP + capture:** HaishinKit `2.2.5` (CocoaPods: `s.dependency 'HaishinKit', '~> 2.2'`).
- **Pipeline:** `MediaMixer(captureSessionMode: .manual, multiTrackAudioMixingEnabled: true)` owns `AVCaptureSession`. Publishing goes through a `StreamSession` built via `StreamSessionBuilderFactory.shared.make(url).build()`; the mixer's output is attached to the session. APIs are async/await.
- **Camera:** `mixer.attachVideo(AVCaptureDevice)` — HaishinKit selects the correct `AVCaptureDeviceInput` and wires it to the `AVCaptureVideoDataOutput` internally.
- **Preview:** `AVCaptureVideoPreviewLayer` attached to the `MediaMixer`'s capture session, hosted in a `UIView` → `FlutterPlatformView`.
- **Compositing:** HaishinKit's built-in `ScreenObject` (watermark/overlay primitive) — one per layer.
- **Audio:** `mixer.attachAudio(AVCaptureDevice)` → routed into the stream session automatically.

**Per-frame flow (library-internal, not our code):**
```
AVCaptureSession → MediaMixer (compositing ScreenObjects) → StreamSession → RTMP server
```

### Dependency (`ios/flutter_rtmp_broadcaster.podspec`)
```ruby
s.dependency 'HaishinKit', '~> 2.2'
s.ios.deployment_target = '14.0'  # verify/raise to match HaishinKit 2.2.5 requirement
```

---

## Orientation Handling (iOS)

**Core principle:** Camera always captures in portrait (UI stays portrait). For landscape streaming, different output dimensions (1280×720) and rotation applied at encoder level.

**User flow:**
- User selects "Landscape" in dropdown
- Physical phone rotation required (sensor auto-rotate OFF)
- Camera preview stays portrait (no change)
- Stream output: 1280×720 dimension, no rotation

**HaishinKit equivalent settings to research:**
- `StreamSession` / `MediaMixer` orientation API
- Rotation equivalent to `setOrientation(270)` for landscape
- Whether reconfiguration needed on orientation change

**Testing checklist:**
- [ ] Portrait video fills screen
- [ ] Landscape video fills screen
- [ ] No unwanted rotation in landscape
- [ ] Dimension correct in both modes

---

## M5 — iOS: Camera + Preview

**Goal:** Real camera feed visible in Flutter app via PlatformView on iOS, wired through
HaishinKit `MediaMixer` so the same pipeline can later publish.

### M5.1 — Add HaishinKit
- [ ] Add `s.dependency 'HaishinKit', '~> 2.2'` to `ios/flutter_rtmp_broadcaster.podspec`
- [ ] Run `cd example/ios && pod install` and verify `import HaishinKit` compiles
- [ ] Confirm iOS deployment target satisfies HaishinKit 2.2.5 (bump to 14.0 / 15.0 if needed)

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
  - Create `AVCaptureVideoPreviewLayer(session: mixer.session)` (exact accessor verified at impl time)
  - Add as sublayer; set `videoGravity = .resizeAspectFill`
- [ ] `func unbindPreview()` — remove preview layer
- [ ] `func switchCamera(facing: String) async throws` — detach current video, attach `AVCaptureDevice` matching `facing == "front" ? .front : .back`
- [ ] `func stop() async` — detach inputs, `mixer.stopRunning()`
- [ ] Lock video orientation to landscape via `AVCaptureConnection.videoOrientation` (or HaishinKit equivalent) so encoded frame matches 1280×720 landscape

### M5.3 — CameraPreviewFactory & FlutterPlatformView
- [ ] Create `CameraPreviewFactory.swift` implementing `NSObject, FlutterPlatformViewFactory`
- [ ] Create `CameraPreviewView.swift` implementing `NSObject, FlutterPlatformView`
  - `view()` returns a `UIView` that hosts an `AVCaptureVideoPreviewLayer`
  - On attach: calls `CameraStreamManager.bindPreview(layerContainer: view)`
  - On detach: `CameraStreamManager.unbindPreview()`

### M5.4 — Plugin Registration (iOS)
- [ ] Open `FlutterRtmpBroadcasterPlugin.swift`
- [ ] Implement `FlutterPlugin.register`
- [ ] Register MethodChannel + EventChannel
- [ ] Register the `CameraPreviewFactory` for viewType `'flutter_rtmp_broadcaster/camera_preview'`
- [ ] Verify preview visible in the example app (requires M1.7 permissions bootstrap — `NSCameraUsageDescription` + `NSMicrophoneUsageDescription` in `example/ios/Runner/Info.plist`)

---

## M6 — iOS: Overlay Compositing

**Goal:** Sponsor images and scoreband composited onto frames using HaishinKit's built-in
`ScreenObject` (watermark primitive). No `CIContext.render`, no `CVPixelBufferPool`.

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
  - Apply video settings (1280×720, 2500kbps, H.264 baseline/main) and audio settings (128kbps AAC)
  - Subscribe to session status events and forward to EventChannel
- [ ] `func startStream() async throws`
  - Attach `mixer` output to `session` (exact wiring verified at impl time)
  - `try await session.connect()` / `session.publish()`
- [ ] `func stopStream() async`
  - `await session.close()` or equivalent
  - Clear session reference

### M7.2 — Frame Flow (no manual work required)
- [ ] Verify: `MediaMixer` already composites `ScreenObject` overlays and pushes final frames into the attached `StreamSession`. We do **not** wrap `CMSampleBuffer`s manually.
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
