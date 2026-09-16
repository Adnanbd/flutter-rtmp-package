---
paths:
  - "example/**"
---

# Example app rules

The example is the integration test bed and reference for host apps (Part A).

- Request camera and mic permissions with `permission_handler` **before** `initPreview`/`configure` (`screens/permission_gate_screen.dart`).
- Never use the `camera` package. `RtmpBroadcastWidget` is the only preview.
- Widget → PNG capture goes through `captureBoundaryPng` (`lib/overlay_studio/widget_capture.dart`): `setState`, await `WidgetsBinding.instance.endOfFrame` if `debugNeedsPaint`, then `RenderRepaintBoundary.toImage` → PNG. Don't hand-roll it again.
- The same bytes feed `controller.updateScoreband` **or** `ImageContent` on a dynamic overlay (`updateOverlay(content:)` swaps the texture in place). Scenarios reach the on-screen widget only through `OverlayStudio.captureBand`, which the Go Live screen sets.
- The capture widget may be off-screen but must have non-zero opacity. Push only when the data changes.
- Resolution, orientation and bitrate pickers are disabled once configured or live. Camera flip, mute and zoom stay enabled. Orientation is fixed after configure, so "orientation flip with overlays" can't be tested from the UI (unit tests cover it).
- Pass the same `StreamConfig` to `initPreview` and `configure`. Bitrate picker `null` = the preset's bitrate (ADR 0019).
- Sponsors: build `SponsorOverlay(placement: SponsorPlacement(...))`. Don't use the deprecated `OverlayPosition`. The config screen has per-sponsor layer weight and scoreband weight.
- Restore portrait with `SystemChrome.setPreferredOrientations` when leaving the stream screen.
- Only use platform features a real host app could use. No reaching into plugin internals.
- Every new plugin capability gets a UI hook usable **before and during** a stream, with mock data (ADR 0020):
  - overlay features → Overlay Studio (`lib/overlay_studio/`): a scenario in `studio_scenarios.dart` (mock match data in `mock_match.dart`), every option in the Build tab, live controls in the Active tab, events and errors in the Log tab;
  - camera features → Go Live screen (`screens/camera_screen.dart`, e.g. `widgets/zoom_control.dart`).
- Update `example/test/overlay_studio_test.dart` when Studio state or tabs change.
- Gestures belong to the app: pinch zoom wraps `RtmpBroadcastWidget` in a `GestureDetector`, and `ZoomModel` coalesces rapid `setZoom` calls to one in flight.
- Gallery picks use `image_picker` **without** `maxWidth`/`maxHeight`/`imageQuality`, so GIF bytes stay animated.
- `switch` on `RtmpStatusType` exhaustively; add cases when the package adds values.
