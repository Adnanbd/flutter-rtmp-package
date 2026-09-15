---
paths:
  - "lib/**"
  - "test/**"
---

# Dart package rules

Contract: `docs/specs/dart-api.md`, `docs/specs/channel-contract.md`, `docs/specs/dynamic-overlays.md`, `docs/specs/camera-zoom.md`.

- Channel calls go only through `lib/src/channels/method_channel_bridge.dart`. Use `invokeMethod` (or `invokeListMethod` for list results). Never use `invokeMapMethod`.
- Every public controller method catches `PlatformException` and rethrows `RtmpBroadcasterException(e.code, e.message ?? '')` (`_guard`). Only `exportDiagnostics` returns error text instead of throwing.
- Method names, argument keys, and event `type` strings must match the Kotlin plugin exactly. Change both sides together (skill `add-channel-method`).
- New native event `type` → add an `RtmpStatusType` value; new payload key → `RtmpStatus` field. Unknown types silently become `error`.
- A new `RtmpStatusType` value or a new sealed `OverlayContent` subclass breaks exhaustive `switch` in host apps: say so in the CHANGELOG.
- `statusStream` is **one process-wide broadcast**: the event bridge listens to native once and every listener on every controller gets every event (`test/event_channel_bridge_test.dart`). Never add a second `receiveBroadcastStream` listen.
- Geometry is relative to the **post-rotation stream frame**, never screen or preview pixels. Sponsors and the scoreband use integer percent 0–100. Dynamic overlays use `OverlayLength.percent` (0–100) or `OverlayLength.px` (encoder output px), per field (ADR 0018).
- Dart validates dynamic overlay and zoom input **before** the channel call and throws the same codes as native (`OVERLAY_*`, `ZOOM_INVALID`). Change both validators together.
- Overlay ids are 1–64 chars; `scoreband` and ids starting with `sponsor_` are reserved. Weights are 0–100 (defaults: sponsor 10, scoreband 50, dynamic 50).
- Wire maps omit null and default keys (`'key': ?value`); native applies the defaults documented in spec §8. Keep them in sync.
- No `Timer.periodic` or polling in the package. The scoreband is push-only; durations, tickers, GIFs, carousels, animations and zoom re-apply run natively.
- `RtmpBroadcastWidget` stays a bare platform view: no UI chrome, no gestures, no overlays.
- Never use `dart:mirrors`.
- New public model → export from `lib/flutter_rtmp_broadcaster.dart`, add `///` docs, add tests.
- Tests: payloads `test/rtmp_broadcast_controller_test.dart`; dynamic overlays + carousel `test/dynamic_overlay_test.dart`; zoom `test/zoom_test.dart`, `test/models/zoom_info_test.dart`; model parsing `test/models/`.
- Deprecate, don't delete, public API (see `OverlayPosition` → `SponsorPlacement`).
- Public API change → README API reference in the same change (skill `sync-docs`).
- Verify with `flutter analyze lib test` and `flutter test`.
