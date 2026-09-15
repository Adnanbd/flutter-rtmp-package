# Architecture — Android

Package `com.flutterrtmp.broadcaster`, Kotlin only. minSdk 21, compileSdk 36, JVM 17, AGP 8.11, Kotlin 2.2.

## Dependencies (`android/build.gradle`)
- `com.github.pedroSG94.RootEncoder:library:2.7.2` (JitPack) — `GenericStream`, GL filters, RTMP client, `BitrateAdapter`.
- `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.0` — UVC camera + `USBMonitor`.
- `consumer-rules.pro` shipped to host apps (R8 keeps; see [overlay spec](../specs/overlay-compositing.md#r8--proguard)).

## Class map

```
kotlin/com/flutterrtmp/broadcaster/
├── FlutterRtmpBroadcasterPlugin.kt   FlutterPlugin + MethodCallHandler + ActivityAware.
│                                     Owns channels, UsbDeviceRegistry, current CameraStreamManager, current previewView.
├── camera/
│   ├── CameraStreamManager.kt        Pipeline owner: GenericStream, GL orientation, overlays cache,
│   │                                 reconnect, bitrate adapter, preview bind/unbind.
│   ├── ZoomController.kt             Pure: requested zoom, wait-ready/verify/re-apply loop, zoomChanged; UvcZoomMath.
│   ├── ZoomTargets.kt                Camera2ZoomTarget (RootEncoder Camera2Source), UvcZoomTarget (ADR 0017).
│   ├── CameraPreviewFactory.kt       PlatformViewFactory; reports created/disposed views to plugin.
│   └── CameraPreviewView.kt          TextureView; surface available → bindPreview, destroyed/dispose → unbindPreview.
├── overlay/
│   ├── OverlayFilterManager.kt       One ImageObjectFilterRender per layer; DynamicLayerHost for one pipeline.
│   ├── OverlayGeometry.kt            Pure math: contain, anchors, percent/px, pre-rotation transform.
│   ├── LayerStack.kt / GlFilterSink.kt  Z-ordered stack (weight, class, seq) → GL filter indices.
│   ├── DynamicOverlayController.kt   Dynamic overlay lifecycle (enter/exit, interruptions), expiry, ticker passes;
│   │                                 outlives pipelines. Pure Kotlin.
│   ├── DynamicOverlayModels.kt       overlay* channel args parser, content/animation models, OverlayException.
│   ├── OverlayTimer.kt               Live-time accumulator per overlay.
│   ├── OverlayAnimationMath.kt / TickerMath.kt / GifTimeline.kt / CarouselTimeline.kt   Pure motion math (JVM-tested).
│   ├── OverlayContentDecoder.kt      Image/GIF decode, text render, TTF fonts, ticker metrics → OverlayVisual.
│   ├── OverlayVisuals.kt             BitmapVisual, GifVisual, WrappedTextVisual, CarouselVisual, TickerVisual (Canvas drawing).
│   ├── LayerRenderer.kt              Per-layer bitmap pool; draws a frame and publishes it (ADR 0015).
│   ├── DynamicLayerFilter.kt         ImageObjectFilterRender subclass: non-recycling upload, GL-thread handoff.
│   ├── ChoreographerFrameDriver.kt   Per-vsync onFrame while animating, throttled to encoder fps.
│   └── SponsorConfig.kt              Channel map → data class.
├── rtmp/RtmpConnectChecker.kt        ConnectChecker → EventChannel (main thread, 32-event buffer).
├── usb/                              UsbDeviceRegistry, UvcVideoSource (+ ZoomableUvcCamera), UsbAudioSource.
└── diag/DiagLogger.kt                File log + uncaught handler.
```

## Pipeline call order (critical)

```
prepareVideo(w, h, bitrate, fps, keyframe, 0)   ← arg order: bitrate BEFORE fps
prepareAudio(44100, stereo=true, 128_000)
applyStreamClientDefaults                        ← BitrateAdapter max + setReTries(3)
[changeVideoSource(UVC) / changeAudioSource(USB)]
configureGlForOrientation
OverlayFilterManager.initLayers → addFilter per sponsor
switchCamera(initialFacing)                      ← device camera only
startPreview(textureView)                        ← on SurfaceTexture available
startStream(rtmpEndpoint)                        ← filters must exist first
```

Bug history: swapping `bitrate`/`fps` in `prepareVideo` gave 30 bps video → YouTube showed audio-only.

## Lifecycle state (in `CameraStreamManager`)

| Flag | Meaning |
|---|---|
| `isPreviewReady` | encoder prepared + GL configured (set by `initPreviewOnly` / `configure`) |
| `isConfigured` | `configure` succeeded (endpoint known) |
| `genericStream.isOnPreview` | preview surface bound |
| `intentionalStop` | suppresses auto-reconnect |
| `lastSponsors`, `lastScoreband*` | sponsor/scoreband cache, seeded into every new `OverlayFilterManager` via `newOverlayFilterManager` |
| `dynamicOverlays` (`DynamicOverlayController`) | dynamic overlays; kept across rebind, orientation flip and `configure` re-prepare, lost with this manager (new `initPreview`) |
| `zoom` (`ZoomController`) | requested zoom level; re-applied and verified after every camera open (`bindPreview`) |

Kotlin JVM tests (pure classes: geometry, layer stack, timers, tickers, carousel, zoom, parser, controller):
`cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest`.

`handleConfigure` reuses the manager when `previewReady`; otherwise releases and creates fresh.
`configure` re-prepares only when dims changed.

## Threading
- Method calls arrive on the platform (main) thread.
- `RtmpConnectChecker` callbacks come from RootEncoder threads; events posted to main via `Handler`.
- `ImageObjectFilterRender.setImage` is safe from main (RootEncoder dispatches to GL thread) — formal audit pending (roadmap M8.3).

## Preview recovery
Background/foreground destroys the SurfaceTexture → `unbindPreview` → `previewUnbound`.
On return: new surface → `bindPreview` (idempotent, stops stale preview first, re-applies overlays) → `previewBound`.
Manual: `controller.rebindPreview()` → `unbindPreview` + `bindPreview` on the current view.

Specs: [channel-contract](../specs/channel-contract.md) · [overlay](../specs/overlay-compositing.md) ·
[orientation](../specs/orientation.md) · [reconnect](../specs/reconnect-and-bitrate.md) ·
[usb](../specs/usb-sources.md) · [diagnostics](../specs/diagnostics.md).
