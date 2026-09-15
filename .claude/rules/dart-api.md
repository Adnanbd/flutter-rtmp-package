---
paths:
  - "lib/**"
  - "test/**"
---

# Dart package rules

Contract: `docs/specs/dart-api.md`, `docs/specs/channel-contract.md`.

- Channel calls go only through `lib/src/channels/method_channel_bridge.dart`. Use `invokeMethod` (or `invokeListMethod` for list results). Never use `invokeMapMethod`.
- Every public controller method catches `PlatformException` and rethrows `RtmpBroadcasterException(e.code, e.message ?? '')`.
- Method names, argument keys, and event `type` strings must match the Kotlin plugin exactly. Change both sides together (skill `add-channel-method`).
- New native event `type` → add an `RtmpStatusType` value. Unknown types silently become `error`.
- Overlay geometry in Dart is percent/normalized of the **post-rotation stream frame**. Never pass pixels.
- No `Timer.periodic` or polling in the package. The scoreband is push-only.
- `RtmpBroadcastWidget` stays a bare platform view: no UI chrome, no overlays.
- Never use `dart:mirrors`.
- New public model → export from `lib/flutter_rtmp_broadcaster.dart`, add `///` docs, add a test in `test/models/`.
- Payload change → update `test/rtmp_broadcast_controller_test.dart`.
- Deprecate, don't delete, public API (see `OverlayPosition` → `SponsorPlacement`).
- Verify with `flutter analyze` and `flutter test`.
