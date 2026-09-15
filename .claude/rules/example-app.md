---
paths:
  - "example/**"
---

# Example app rules

The example is the integration test bed and reference for host apps (Part A).

- Request camera and mic permissions with `permission_handler` **before** `initPreview`/`configure` (`screens/permission_gate_screen.dart`).
- Never use the `camera` package. `RtmpBroadcastWidget` is the only preview.
- Scoreband capture pattern:
  1. `setState`, then await `WidgetsBinding.instance.endOfFrame` if `debugNeedsPaint`.
  2. `RenderRepaintBoundary.toImage(pixelRatio: 2.0)` → PNG → `controller.updateScoreband`.
- The capture widget may be off-screen but must have non-zero opacity. Push only when score data changes.
- Resolution and orientation pickers are disabled once configured or live. Camera flip and mute stay enabled.
- Sponsors: build `SponsorOverlay(placement: SponsorPlacement(...))`. Don't use the deprecated `OverlayPosition`.
- Restore portrait with `SystemChrome.setPreferredOrientations` when leaving the stream screen.
- Only use platform features a real host app could use. No reaching into plugin internals.
- A new plugin capability should get a minimal UI hook here so it can be tested on device.
