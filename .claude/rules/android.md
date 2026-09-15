---
paths:
  - "android/**"
---

# Android native rules

Architecture: `docs/architecture/android.md`. Specs: `docs/specs/overlay-compositing.md`, `dynamic-overlays.md`, `camera-zoom.md`, `orientation.md`, `reconnect-and-bitrate.md`.

- Kotlin only. Package `com.flutterrtmp.broadcaster`. minSdk 21: check API levels (e.g. `Math.floorMod` is API 24 → use Kotlin `mod`).
- Use `GenericStream` only. Never use `RtmpCamera2` or `OpenGlView`.
- `prepareVideo(width, height, bitrate, fps, keyframe, rotation)`: bitrate comes **before** fps. Prepare with the configured bitrate, fps and keyframe (ADR 0019).
- Call order: prepareVideo/Audio → `applyStreamClientDefaults` → source swaps → `configureGlForOrientation` → overlay layers → `startPreview` → `startStream`.
- **All GL filter add/remove goes through `LayerStack`** (sorted by weight, class sponsor < scoreband < dynamic, seq; ADR 0014). Never call `getGlInterface().addFilter` directly.
- Sponsor/scoreband filters: `setImage → setScale → setPosition` before insert; scoreband created lazily; no placeholder bitmaps. RootEncoder `TextureLoader` **recycles** the bitmap on upload → cache encoded bytes and decode a fresh bitmap per filter.
- Dynamic layers use `DynamicLayerFilter` (non-recycling upload, GL-thread handoff) and draw with `Canvas` on the CPU (ADR 0015). No per-frame logging.
- Frames run on `ChoreographerFrameDriver` only while something animates, scrolls or plays, throttled to the encoder fps. Static carousels arm one scheduler wake per change. Hidden entries never request frames (`DynamicOverlayController.updateFrameRequest`).
- Filters render **pre-rotation**, in 0–100 % units. Portrait needs the bitmap rotation plus coordinate swap. Geometry (percent/px, contain, anchors) lives in pure `OverlayGeometry`.
- Every re-prepare path builds its manager via `CameraStreamManager.newOverlayFilterManager` (seeded with sponsors, scoreband and `dynamicOverlays`). Preview rebind and `startStream` recovery call `rebuild`. Pipeline transitions snap running animations.
- Timing and lifecycle logic is pure Kotlin with an injected clock and scheduler (`DynamicOverlayController`, `OverlayTimer`, `TickerMath`, `GifTimeline`, `CarouselTimeline`, `ZoomController`) and has JVM tests in `android/src/test/kotlin/`. Run `cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest`.
- Overlay duration counts only while live and VISIBLE: `RtmpConnectChecker` connect/disconnect → `setLive` posted to main, ignored after `stopStream`.
- Zoom: `ZoomController` is the only writer (ADR 0017). RootEncoder `Camera2ApiManager.setZoom` silently no-ops until the capture session exists, and `closeCamera` resets zoom → re-apply and read back after every camera open (`bindPreview`), retry every 100 ms for 3 s, else warning `ZOOM_REAPPLY_FAILED`. Reset to 1.0 on a real `switchCamera`.
- The design relies on RootEncoder 2.7.2 and libuvc 3.2.0 internals (`textureLoader` field, `drawFilter` order, `Camera2ApiManager` zoom, `UVCCamera.mZoomMin/mZoomMax`). Re-check ADR 0015 and 0017 on any upgrade.
- Never call `startStream()` to reconnect. Use `getStreamClient().reTry(...)`.
- GL orientation values are fixed by `docs/specs/orientation.md`. Change the spec in the same commit.
- Every failure: `DiagLogger.logError(CODE, …)` plus `result.error(CODE, …)` and/or an `error` event with the same CODE. No silent `Log.w`. Unexpected exceptions in overlay/zoom calls → `OVERLAY_OPERATION_FAILED` / `ZOOM_OPERATION_FAILED`.
- New error/warning code → `docs/specs/channel-contract.md`, the feature spec, and the README error/warning tables.
- Events go through `RtmpConnectChecker.sendEvent` (main thread, buffered). Don't call `eventSink` directly from managers.
- New reflection/JNI-reached class or library subclass → check `android/consumer-rules.pro` (keeps `.overlay.**`, `ZoomableUvcCamera`). Overlays break silently under R8.
- Before touching channel setup, confirm `FlutterPluginBinding` / activity binding is available (`context`, `activity` null checks).
- Never log the RTMP stream key or the full endpoint.
