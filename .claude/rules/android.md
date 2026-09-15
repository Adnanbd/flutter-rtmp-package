---
paths:
  - "android/**"
---

# Android native rules

Architecture: `docs/architecture/android.md`. Specs: `docs/specs/overlay-compositing.md`, `docs/specs/orientation.md`, `docs/specs/reconnect-and-bitrate.md`.

- Kotlin only. Package `com.flutterrtmp.broadcaster`.
- Use `GenericStream` only. Never use `RtmpCamera2` or `OpenGlView`. Register filters via `genericStream.getGlInterface().addFilter(...)`.
- `prepareVideo(width, height, bitrate, fps, keyframe, rotation)`: bitrate comes **before** fps.
- Call order: prepareVideo/Audio → `applyStreamClientDefaults` → source swaps → `configureGlForOrientation` → overlay filters → `startPreview` → `startStream`.
- Filter order: `setImage → setScale → setPosition → addFilter`. Scoreband filter is created lazily. No placeholder bitmaps.
- Filters render **pre-rotation**, in 0–100 % units. Portrait needs the bitmap rotation plus coordinate swap.
- Pipeline transitions can drop filters. Keep `lastSponsors`/`lastScoreband*` in sync and re-apply.
- Never call `startStream()` to reconnect. Use `getStreamClient().reTry(...)`.
- GL orientation values are fixed by `docs/specs/orientation.md`. Change the spec in the same commit.
- Every failure: `DiagLogger.logError(CODE, …)` plus `result.error(CODE, …)` and/or an `error` event with the same CODE. No silent `Log.w`.
- New error/warning code → add to `docs/specs/channel-contract.md` and the README error table.
- Events go through `RtmpConnectChecker.sendEvent` (main thread, buffered). Don't call `eventSink` directly from managers.
- New reflection/JNI-reached class → check `android/consumer-rules.pro`. Overlays break silently under R8.
- Before touching channel setup, confirm `FlutterPluginBinding` / activity binding is available (`context`, `activity` null checks).
- Never log the RTMP stream key.
