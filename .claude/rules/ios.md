---
paths:
  - "ios/**"
---

# iOS native rules

Status: **not implemented**. The plugin is still the `flutter create` stub. Target design: `docs/architecture/ios.md`. Milestones: `docs/plans/ios.md`. Workflow: skill `ios-port`.

- Swift only. No Objective-C.
- HaishinKit 2.x via CocoaPods (`ios/flutter_rtmp_broadcaster.podspec`). Use `MediaMixer` + `StreamSession`, async/await.
- Don't use legacy `RTMPConnection`/`RTMPStream` directly.
- Overlays: one HaishinKit `ScreenObject` per layer. Never write a manual `CIContext.render` pipeline or manage a `CVPixelBufferPool`.
- One capture session: `MediaMixer` owns `AVCaptureSession`. Preview is an `AVCaptureVideoPreviewLayer` on that session. Never open a second session.
- Channel names: `flutter_rtmp_broadcaster/control`, `flutter_rtmp_broadcaster/status`. ViewType: `flutter_rtmp_broadcaster/camera_preview`. The stub's `flutter_rtmp_broadcaster` channel is wrong.
- Method names, arg keys, event `type`s, payload keys, and error codes must match `docs/specs/channel-contract.md` exactly.
- Send `FlutterEventSink` calls on the main thread.
- Overlay math uses the configured stream dims, not preview layer size and not hardcoded 1280×720.
- Don't assume Android overlay findings carry over (pre-rotation space, % units, bitmap rotation). Verify on device and record results in the specs.
- Reconnect: 3 attempts × 3 s, `reconnecting {attempt}` events, `MAX_RECONNECT_EXCEEDED`. `stopStream` cancels the retry `Task`.
