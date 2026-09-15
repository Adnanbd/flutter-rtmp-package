---
name: ios-port
description: Workflow for implementing the iOS side of flutter_rtmp_broadcaster (milestones M5–M7) with HaishinKit MediaMixer + StreamSession + ScreenObject, at parity with the Android channel contract. Use when the user asks to start/continue iOS work, implement camera preview/overlays/RTMP/reconnect on iOS, or edits ios/Classes.
---

# iOS port (M5 → M6 → M7)

Read first:
- `docs/architecture/ios.md` (target design)
- `docs/plans/ios.md` (checkboxes)
- `docs/specs/channel-contract.md` (parity contract)
- `.claude/rules/ios.md`

## 0. Ground truth before coding
- The current plugin is a stub on the wrong channel name. Replace it; don't extend it.
- Check the HaishinKit version actually resolved (`example/ios/Podfile.lock`). Look up real 2.x API names in
  HaishinKit docs/source (`docs.haishinkit.com`, GitHub `HaishinKit.swift`). Plan docs say "verify at impl time"
  for several calls, so do verify.
- Confirm the deployment target meets HaishinKit's minimum. Bump the podspec if needed.

## 1. M5 — Camera + preview
1. `FlutterRtmpBroadcasterPlugin.swift`: register `flutter_rtmp_broadcaster/control`, `flutter_rtmp_broadcaster/status` (FlutterStreamHandler), and the `flutter_rtmp_broadcaster/camera_preview` factory.
2. `camera/CameraStreamManager.swift`: `MediaMixer` lifecycle; `initPreview` / `configure` args from `StreamConfig.toMap()`.
3. `camera/CameraPreviewFactory.swift` + `CameraPreviewView.swift`: `AVCaptureVideoPreviewLayer` on the mixer session. Emit `previewBound` / `previewUnbound` like Android.
4. `switchCamera`, `setAudioMute`.
5. Example `Info.plist`: `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`.
6. Device check: preview visible, flip works, background/foreground recovers.

## 2. M6 — Overlays
1. **Spike first.** Put one `ScreenObject` at a known frame and record in `docs/specs/overlay-compositing.md`:
   - coordinate space
   - units
   - orientation behavior in portrait and landscape
2. `overlay/OverlayCompositor.swift`: sponsors from `SponsorPlacement` rules (BoxFit.contain + edge anchors, same as Android), scoreband `width/x/y`.
3. Use configured stream dims. Z-order: sponsors, then scoreband on top.
4. Same error/warning codes as Android (`OVERLAY_DECODE_FAILED`, `SPONSOR_DECODE_FAILED`, …).

## 3. M7 — RTMP
1. `StreamSession` from `rtmpEndpoint`; video settings from `StreamConfig` (bitrate, fps, keyframe); AAC 128 kbps.
2. `rtmp/RtmpSessionObserver.swift`: session state → `connected` / `disconnected{reason}` / `bitrate{kbps}` / `error{code,message}` on the main thread.
3. Reconnect: 3 × 3 s, `reconnecting{attempt}`, `MAX_RECONNECT_EXCEEDED`, cancel on `stopStream`.
4. Android-only methods (USB, diagnostics): return `[]` / `false` / `""` and mark iOS status in the spec.

## 4. After each milestone
- Tick boxes in `docs/plans/ios.md`; flip ❌ → ✅ per method in `docs/specs/channel-contract.md`.
- Replace "TARGET DESIGN" wording in `docs/architecture/ios.md` with what was built.
- Update the README Platform Support table and Known Limitations.
- New design choices → ADR in `docs/decisions/`.
- Run skill `sync-docs`.

## Parity test
Run the example app on Android and iOS with the same config. The `statusStream` event sequence and
the overlay placement in the output stream should match.
