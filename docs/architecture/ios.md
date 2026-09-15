# Architecture — iOS (TARGET DESIGN — NOT IMPLEMENTED)

> **Current state:** `ios/Classes/FlutterRtmpBroadcasterPlugin.swift` is the unmodified `flutter create`
> stub. It registers channel `flutter_rtmp_broadcaster` (wrong name) and handles only
> `getPlatformVersion`. No preview factory, no EventChannel. Everything below is the plan (M5–M7 in
> [../plans/ios.md](../plans/ios.md)). Use the `ios-port` skill.

Swift only. Deployment target 14.0 (verify against HaishinKit 2.2.5). CocoaPods chosen:
`s.dependency 'HaishinKit', '~> 2.2'` in `ios/flutter_rtmp_broadcaster.podspec`.

## Planned stack
- **Pipeline:** `MediaMixer(captureSessionMode: .manual, multiTrackAudioMixingEnabled: true)` owns `AVCaptureSession`.
- **Publish:** `StreamSession` via `StreamSessionBuilderFactory.shared.make(url).build()`; mixer output attached. async/await.
- **Camera:** `mixer.attachVideo(AVCaptureDevice)`; **audio:** `mixer.attachAudio(AVCaptureDevice)`.
- **Preview:** `AVCaptureVideoPreviewLayer` on the mixer's session, inside a `UIView` → `FlutterPlatformView`.
- **Overlays:** HaishinKit `ScreenObject` per layer. Sponsors created in `configure`; scoreband `contents` (CGImage) swapped on update.
- **Forbidden:** manual `CIContext.render` pipeline, managing `CVPixelBufferPool`, legacy `RTMPConnection`/`RTMPStream` direct use.

```
AVCaptureSession → MediaMixer (composites ScreenObjects) → StreamSession → RTMP server
```

## Planned files

```
ios/Classes/
├── FlutterRtmpBroadcasterPlugin.swift   register control + status channels, camera_preview factory
├── camera/CameraStreamManager.swift     MediaMixer + StreamSession owner
├── camera/CameraPreviewFactory.swift
├── camera/CameraPreviewView.swift
├── overlay/OverlayCompositor.swift      ScreenObject per layer
└── rtmp/RtmpSessionObserver.swift       session events → EventChannel (main thread)
```

## Parity requirements
Must match [channel-contract.md](../specs/channel-contract.md) byte-for-byte on method names, argument
keys, event `type` strings, payload keys, and error codes, so Dart stays platform-agnostic. Android-only
methods (USB, diagnostics) should return empty list / `false` / placeholder string, not `notImplemented`,
or be documented as Android-only.

Open questions (coordinate space, units, image orientation, orientation API): see
[overlay spec](../specs/overlay-compositing.md#open-questions-for-ios-haishinkit-screenobject) and
[orientation spec](../specs/orientation.md#ios-not-implemented--research-for-m5).
