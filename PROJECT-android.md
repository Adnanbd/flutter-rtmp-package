# PROJECT-android.md — flutter_rtmp_broadcaster (Android)

> For shared milestones (M1, M8–M10), dependency summary, and key decisions see `PROJECT.md`.
> For iOS milestones (M5–M7) see `PROJECT-ios.md`.

---

## Current State (2026-04-23)

**M1–M4 code-complete; physical device verification deferred.** Three cross-channel/API bugs fixed: (1) `handleConfigure` now reads `rtmpEndpoint` (Dart sends combined key) instead of separate `rtmpUrl`/`rtmpKey`; (2) `SponsorConfig.fromMap` now reads `width`/`height` matching `OverlayPosition.toMap()`; (3) `CameraStreamManager.configure` was calling `prepareVideo(w, h, fps, bitrate)` — RootEncoder's signature is `prepareVideo(w, h, bitrate, fps, …)`, so bitrate was being set to 30 bps and fps to 2_500_000. Symptom: RTMP connected, preview worked, YouTube reported "Stream status: Good" with ~141 kbps (audio-only) and no video. Fixed by swapping arg order, and `prepare` failures now throw instead of silently logging. Example app rewritten with full Android test UI: RTMP URL/key form → configure → camera preview → start/stop stream → status display → live scoreband capture-and-push loop.

Next: physical device test of example app on Android, then M5 — iOS camera + preview (HaishinKit MediaMixer).

---

## Native Stack

- **RTMP:** RootEncoder `2.7.2` — artifact `com.github.pedroSG94.RootEncoder:library:2.7.2` (JitPack).
- **Streaming pipeline:** `GenericStream(context, connectChecker)`. `RtmpCamera2` is deprecated — must NOT be used.
- **Preview:** `TextureView` owned by `FlutterPlatformView`; attached via `genericStream.startPreview(textureView)`.
- **Compositing:** `ImageObjectFilterRender` (bundled in RootEncoder, OpenGL ES GPU) — one instance per layer.
- **Audio:** AAC via built-in microphone source + encoder (`prepareAudio()`).
- **Config call order (critical):** `prepareVideo(...)` + `prepareAudio(...)` → `getGlInterface().addFilter(...)` for each layer → `startPreview(textureView)` → on start: `startStream(endpoint)`.

**Render layer order (bottom → top):**
```
Camera frame → Sponsor_0 → Sponsor_1 → … → Scoreband
```

### Dependency (`android/build.gradle`)
```
repositories { maven { url 'https://jitpack.io' } }
dependencies {
  implementation 'com.github.pedroSG94.RootEncoder:library:2.7.2'
}
```

---

## M2 — Android: Camera + Preview

**Goal:** Real camera feed visible in Flutter app via PlatformView, wired through
`GenericStream` so the same pipeline can later be extended to encode + stream. No
RTMP connection yet.

### M2.1 — Add RootEncoder Dependency ✅
- [x] JitPack in `allprojects.repositories` in `android/build.gradle`
- [x] `implementation 'com.github.pedroSG94.RootEncoder:library:2.7.2'` at root `dependencies` level
- [x] `minSdk = 21`, `namespace = com.flutterrtmp.broadcaster`
- [x] Example AndroidManifest: `CAMERA`, `RECORD_AUDIO`, `INTERNET` permissions added
- [ ] GenericStream import verified at compile time (confirmed in M2.4 build)

### M2.2 — CameraPreviewFactory & PlatformView ✅
- [x] `camera/CameraPreviewFactory.kt` — PlatformViewFactory, takes lazy `CameraStreamManager` provider
- [x] `camera/CameraPreviewView.kt` — PlatformView wrapping `TextureView`; calls `bindPreview` on init, `unbindPreview` on dispose

### M2.3 — Plugin Registration ✅
- [x] Rewrote `FlutterRtmpBroadcasterPlugin.kt`
- [x] Implements `FlutterPlugin`, `MethodCallHandler`, `ActivityAware`
- [x] `onAttachedToEngine`: registers MethodChannel (`flutter_rtmp_broadcaster/control`) + EventChannel (`flutter_rtmp_broadcaster/status`)
- [x] `onAttachedToActivity`: stores activity reference
- [x] Registers `CameraPreviewFactory` for viewType `'flutter_rtmp_broadcaster/camera_preview'`

### M2.4 — CameraStreamManager (GenericStream wrapper) ✅
- [x] Created `camera/CameraStreamManager.kt`
- [x] Fields: `GenericStream` (lazy-init), `Context`, `ConnectChecker` (no-op inline; replaced by `RtmpConnectChecker` in M4)
- [x] `prepare()` — `prepareVideo(1280, 720, 2_500_000, 30)` (width, height, bitrate, fps) + `prepareAudio(44100, true, 128_000)`
- [x] `bindPreview(textureView)` — `genericStream.startPreview(textureView)` if prepared
- [x] `unbindPreview()` — `genericStream.stopPreview()` guarded by `isOnPreview`
- [x] `switchCamera(facing)` — `genericStream.changeVideoSource(Camera2Source(context, isFront: Boolean))` — **API verify at M2.4 compile**: `Camera2Source(Context, Boolean)` constructor signature and `isOnPreview`/`isStreaming` property names may differ in RootEncoder 2.7.2
- [x] `release()` — stops preview + stream if running, calls `genericStream.release()`
- [ ] Verify camera preview is visible in the example app (requires build + physical device)

### M2.5 — MethodChannel Wiring (Android side, partial) ✅
- [x] `configure`: parses `rtmpUrl` + `rtmpKey`, instantiates `CameraStreamManager`, calls `prepare()`; stores combined endpoint
- [x] `switchCamera`: delegates to `cameraStreamManager.switchCamera(facing)`
- [x] `setAudioMute`: delegates to `cameraStreamManager.setAudioMuted(muted)`
- [x] All unimplemented methods (`startStream`, `stopStream`, `updateOverlay`, `updateSponsors`) return `result.notImplemented()`

---

## M3 — Android: Overlay Compositing

**Goal:** Static sponsor images and a placeholder scoreband rendered on top of camera
frames, using `ImageObjectFilterRender` registered on `GenericStream`'s GL interface.

### M3.1 — OverlayFilterManager ✅
- [x] Created `overlay/OverlayFilterManager.kt`
- [x] `initLayers(stream, sponsorList, streamWidth, streamHeight)` — decodes each sponsor to Bitmap, creates `ImageObjectFilterRender`, sets image + position/scale, adds via `stream.getGlInterface().addFilter()` in render order (sponsors then scoreband). Scoreband starts with transparent 1×1 placeholder.
- [x] `updateScoreband(pngBytes)` — decodes PNG → Bitmap → `scorebandFilter.setImage()` (GL-thread dispatch handled by RootEncoder internally)
- [x] `updateSponsors(stream, sponsorList, ...)` — removes existing sponsor filters, rebuilds and re-adds
- [x] `release(stream)` — `stream.getGlInterface().clearFilters()`
- **API verify at compile:** `ImageObjectFilterRender.setScale(Float, Float)`, `setPosition(Float, Float)`, `GlInterface.addFilter/removeFilter/clearFilters` names in RootEncoder 2.7.2. NDC center conversion: `ndcX = (x + w/2)*2 - 1`, `ndcY = 1 - (y + h/2)*2`.

### M3.2 — SponsorConfig Data Class ✅
- [x] Created `overlay/SponsorConfig.kt` with `bytes`, `x`, `y`, `width`, `height` (all normalized)
- [x] `fromMap(Map<String, Any>)` companion — bytes as `ByteArray`, coords as `Double → Float`

### M3.3 — Wire Overlay into CameraStreamManager ✅
- [x] `CameraStreamManager` holds `OverlayFilterManager`
- [x] `prepare()` renamed to `configure(rtmpEndpoint, sponsors)` — calls `prepareVideo/Audio` then `overlayFilterManager.initLayers()` before preview
- [x] `updateScoreband(bytes: ByteArray)` delegates to overlay manager

### M3.4 — Handle configure MethodChannel Call ✅
- [x] `handleConfigure` parses `sponsors: List<Map<String, Any>>` → `List<SponsorConfig>`
- [x] Creates `CameraStreamManager(ctx)`, calls `.configure("$rtmpUrl/$rtmpKey", sponsors)`

### M3.5 — Handle updateOverlay MethodChannel Call ✅
- [x] `handleUpdateOverlay` parses `layerId` + `bytes`
- [x] `layerId == "scoreband"` → `cameraStreamManager.updateScoreband(bytes)`
- [x] Unknown `layerId` → `result.error("UNKNOWN_LAYER", ...)`

### M3.6 — Confirm Overlay Visibility Path
- [ ] Deferred to M4.6 end-to-end test — overlays verified in streamed output on device

---

## M4 — Android: RTMP Broadcast

**Goal:** Full RTMP stream working on Android with composited overlays.

### M4.1 — ConnectChecker Implementation ✅
- [x] Created `rtmp/RtmpConnectChecker.kt`
- [x] Implements `com.pedro.common.ConnectChecker`
- [x] All callbacks post events to main thread via `Handler(Looper.getMainLooper())`
  - `onConnectionSuccess` → `{ type: connected }`
  - `onConnectionFailed(reason)` → `{ type: disconnected, reason }`
  - `onDisconnect` → `{ type: disconnected, reason: "Server closed connection" }`
  - `onNewBitrate(bitrate)` → `{ type: bitrate, kbps: bitrate/1000 }`
  - `onAuthError` → `{ type: error, code: AUTH_ERROR, message: ... }`
- [x] Accepts `onConnectedCallback`, `onDisconnectedCallback(reason)` + `onNewBitrateCallback(bitrate)` lambdas so `CameraStreamManager` can react without coupling

### M4.2 — Wire ConnectChecker into GenericStream ✅
- [x] `CameraStreamManager` now holds `RtmpConnectChecker` (replaces M2/M3 no-op)
- [x] `RtmpConnectChecker` created with lambdas: `onConnected → reconnectAttempt = 0`, `onDisconnected(reason) → scheduleReconnect(reason)`, `onNewBitrate(bitrate) → bitrateAdapter.adaptBitrate(...)`
- [x] `setSink(EventChannel.EventSink?)` on `CameraStreamManager` delegates to checker

### M4.3 — Handle startStream MethodChannel Call ✅
- [x] `handleStartStream`: guards `NOT_CONFIGURED` + `ALREADY_STREAMING`, calls `manager.startStream()`
- [x] `CameraStreamManager.startStream()`: resets `intentionalStop=false`, `reconnectAttempt=0`, calls `genericStream.startStream(rtmpEndpoint)`

### M4.4 — Handle stopStream MethodChannel Call ✅
- [x] `handleStopStream`: calls `manager.stopStream()`
- [x] `CameraStreamManager.stopStream()`: sets `intentionalStop=true`, resets `reconnectAttempt`, `genericStream.stopStream()` (also cancels an in-flight `reTry`)

### M4.5 — EventChannel Setup ✅
- [x] Plugin stores `eventSink`; `onListen` → `cameraStreamManager?.setSink(events)`, `onCancel` → `setSink(null)`
- [x] `handleConfigure` forwards existing sink to newly-created manager so late-listen and early-listen both work

### M4.6 — Android End-to-End Test
- [ ] Deferred — test together with M2 preview verification on physical device

### M4.7 — Auto-Reconnect (Android) ✅
- [x] `scheduleReconnect(reason)` in `CameraStreamManager`: gated by `intentionalStop`; increments `reconnectAttempt`, fires `{ type: reconnecting, attempt: N }`, delegates the retry to `genericStream.getStreamClient().reTry(3000ms, reason)`
- [x] Retry budget set via `getStreamClient().setReTries(3)` after each `prepareVideo` and again in `startStream()` (resets the library counter per session)
- [x] When `reTry()` returns false (budget exhausted): fires `{ type: error, code: MAX_RECONNECT_EXCEEDED }`, resets counter, calls `stopStream()` to leave `StreamBase` startable
- [x] `stopStream()` / `release()` set `intentionalStop` and call `genericStream.stopStream()`, which cancels any in-flight retry
- [ ] Verify on device with server taken offline mid-stream

**Crash fixed (field report, 2026-07-30):** the original implementation posted `genericStream.startStream(endpoint)` on a `Handler` 3s after disconnect. RootEncoder does not clear `StreamBase.isStreaming` when the socket drops, so the retry threw `IllegalStateException: Stream already started, stopStream before startStream again` on the main thread and killed the app. `StreamBaseClient.reTry()` reconnects the client in place on its own thread and never hits that guard — never call `startStream()` to reconnect.

### M4.7b — Adaptive Bitrate (Android) ✅
- [x] `BitrateAdapter` (`com.pedro.library.util`) in `CameraStreamManager`, listener → `genericStream.setVideoBitrateOnFly(bitrate)`
- [x] `setMaxBitrate(videoBitrate)` + `reset()` applied in `applyStreamClientDefaults()` after every `prepareVideo`; `reset()` again on `startStream()`
- [x] `RtmpConnectChecker.onNewBitrate` forwards to `bitrateAdapter.adaptBitrate(bitrate, getStreamClient().hasCongestion())`
- Rationale: same field report showed ~2 min of `RtmpSender: Video/Audio frame discarded` before `IOException: Broken pipe` — uplink fell below the fixed 2 500 000 bps and the sender cache saturated. Bitrate now steps down under congestion instead of dropping frames until the server closes the socket.

### M4.8 — YouTube-Compliant Encoder Config, Orientation & Camera Pickers

**Goal:** Let the user pick resolution (720p/1080p), orientation (portrait/landscape), and initial camera (front/back) **before going live**; apply YouTube-compliant encoder settings (H.264, 2 s keyframe, CBR, BT.709, 30 fps, true portrait dims when vertical); expose camera-flip + mute **during live**. Resolution and orientation are locked once streaming starts (YouTube drops the session on mid-stream dim changes).

**Mid-live behavior matrix**

| Action | Live-safe? | Notes |
|---|---|---|
| Switch camera (front/back) | Yes | `Camera2Source.switchCamera()` is hot-swap safe |
| Toggle mute | Yes | `MicrophoneSource.mute()/unMute()` |
| Change resolution | No | `prepareVideo` requires stream stopped; disabled in UI |
| Change orientation | No | Same |
| Change bitrate | Out of scope | `setVideoBitrateOnFly(int)` exists, defer |

#### M4.8.1 — Create `StreamConfig` model (Dart) ✅
- [x] New file `lib/src/models/stream_config.dart` with `VideoResolution {hd720, fhd1080}`, `VideoOrientation {portrait, landscape}`, `CameraFacing {front, back}` enums
- [x] `StreamConfig` class with fields: `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [x] Factory constructors: `youtube720Landscape` (1280×720 @ 2.5 Mbps), `youtube1080Landscape` (1920×1080 @ 4.5 Mbps), `youtube720Portrait` (720×1280 @ 2.5 Mbps), `youtube1080Portrait` (1080×1920 @ 4.5 Mbps) — all 30 fps, 2 s keyframe
- [x] `toMap()` serializer
- [x] Re-export `StreamConfig` + enums from `lib/flutter_rtmp_broadcaster.dart`

#### M4.8.2 — Refactor `RtmpBroadcastController.configure` ✅
- [x] `configure()` signature: `{required String rtmpUrl, required String rtmpKey, required List<SponsorOverlay> sponsors, required StreamConfig config}`
- [x] Build MethodChannel payload by spreading `config.toMap()` alongside `rtmpEndpoint` + `sponsors`
- [x] Store `_config`, expose `StreamConfig get config`

#### M4.8.3 — Android plugin parses new configure keys ✅
- [x] `FlutterRtmpBroadcasterPlugin.handleConfigure` parses `width`, `height`, `fps`, `videoBitrate`, `keyframeIntervalSeconds`, `orientation`, `initialFacing`
- [x] Forwards them to `manager.configure(...)` with new signature

#### M4.8.4 — `CameraStreamManager` accepts dynamic encoder config ✅
- [x] Remove `STREAM_WIDTH` / `STREAM_HEIGHT` / `STREAM_FPS` / `VIDEO_BITRATE` constants
- [x] Change `overlayFilterManager` from `val` to `var` (rebuilt at configure time with real encoded dims)
- [x] New `configure(rtmpEndpoint, sponsors, width, height, fps, videoBitrate, keyframeIntervalSeconds, orientation, initialFacing)` signature
- [x] Call `genericStream.prepareVideo(encW, encH, videoBitrate, fps, keyframeIntervalSeconds, 0 /* rotation */)`

#### M4.8.5 — Kill rotation-swap, encode true portrait frames ✅
- [x] Replace `setOrientation(CameraHelper.getCameraOrientation(context))` with `setOrientation(0)`
- [x] Remove the orientation swap in `switchCamera()` so mid-stream flip does not re-introduce the swap

#### M4.8.6 — BT.709 color space + honor `initialFacing` ✅
- [x] After `prepareVideo` succeeds, call `genericStream.getVideoEncoder()?.forceBt709Color(true)`
- [x] After `initLayers`, call `switchCamera(initialFacing)` so user's pre-live camera choice applies before the first encoded frame

**Note:** BT.709 forcing removed temporarily due to RootEncoder 2.7.2 API variance; can be added back if the exact method signature is verified against source.

#### M4.8.7 — Example app pre-live pickers ✅
- [x] Add state in `_StreamPageState`: `_selectedRes`, `_selectedOrient`, `_initialFacing`
- [x] Three `DropdownButton` rows in pre-configure block: Resolution (720p/1080p), Orientation (Landscape/Portrait), Initial Camera (Back/Front)
- [x] Pickers disabled while `configured == true`
- [x] `_configure()` builds `StreamConfig` by switching on `(_selectedRes, _selectedOrient)` → one of the four factory constructors

#### M4.8.8 — Example app live controls ✅
- [x] Post-configure row: `[Flip camera] [Mute] [Start/Stop]`
- [x] Flip toggles `_initialFacing` and calls `controller.switchCamera(...)`
- [x] Mute calls `controller.setAudioMuted(...)`

---

## Android Scoreband Filter — Technical Notes (M3 follow-up, 2026-04-25)

Scoreband overlay rendering on Android is **working in both portrait and landscape**: visible bottom-center, ~90% stream width, real-time updates every 3 s, mirrored to YouTube via RTMP. The findings below are non-obvious, were established empirically against `pedroSG94/RootEncoder@2.7.2`, and are required reading before touching `OverlayFilterManager.kt`, adding new resolutions, upgrading RootEncoder, or porting to iOS.

### Filter coordinate space (PRE-rotation)

`ImageObjectFilterRender` renders into the **camera-native (landscape) frame** — filters are applied **before** `setStreamRotation` rotates the composite for the encoder. So `setScale` / `setPosition` on the filter operate in landscape pixel space regardless of the configured stream orientation.

```
camera frame (landscape) → [filters apply here] → setStreamRotation → encoder/preview
```

If you're seeing an overlay land in the wrong corner, "rotated 90°", or "shrunk and tilted", the cause is almost always: position/scale computed in post-rotation coords without the inverse transform.

### `Sprite.scale(x, y)` / `translate(x, y)` units

Both are **0–100% of the frame**, not NDC, not normalized 0–1.

```kotlin
filter.setScale(90f, 6.69f)       // 90% of frame width × 6.69% of frame height
filter.setPosition(5f, 89.31f)    // top-left at 5% from left, 89.31% from top
```

Sprite origin: (0, 0) = top-left, (100, 100) = bottom-right.

### Stream rotation values (only two used)

| `setStreamRotation` arg | Visual effect | When |
|---|---|---|
| `0` | none — pre frame == post frame (1280×720) | Landscape mode (`setStreamIsPortrait(false)`) |
| `270` | rotate **90° CCW** — pre 1280×720 → post 720×1280 | Portrait mode (`setStreamIsPortrait(true)`) |

Direction (CCW) was determined empirically; the integer `270` alone is ambiguous between CW/CCW conventions.

### Portrait bitmap + position transform

For portrait, the captured PNG must be **pre-rotated +90° CW** before `setImage()` so the frame's 90° CCW rotation cancels back to upright. Scale and position must be transformed from desired post-rotation coords to pre-rotation coords:

| Pre-rotation value | Formula |
|---|---|
| `pre.scaleX` | `post.scaleY` |
| `pre.scaleY` | `post.scaleX` |
| `pre.posX` | `100 − post.posY − post.scaleY` |
| `pre.posY` | `post.posX` |

Worked example — 1408×186 PNG, target bottom-center on 720×1280 portrait stream:
- Post: `scale=(90, 6.69)`, `pos=(5, 89.31)`
- Pre:  `scale=(6.69, 90)`, `pos=(4.0, 5)` → vertical strip on right edge of landscape pre frame, becomes bottom horizontal bar after 90° CCW rotation.

Landscape mode skips both the bitmap rotation and the swap — pre == post.

### Filter lifecycle: `addFilter` vs `setImage` order matters

A filter added via `glInterface.addFilter(filter)` **before** `setImage()` may end up with an unbound GL texture; subsequent `setImage()` calls do not always re-bind. Canonical order, matching the official RootEncoder sample:

```kotlin
val f = ImageObjectFilterRender()
f.setImage(bitmap)
f.setScale(...)
f.setPosition(...)
glInterface.addFilter(f)
```

We use this for sponsors, and we lazy-create the scoreband filter on first `updateScoreband()` for the same reason. Don't preallocate filters with placeholder/empty bitmaps.

### Capture-side timing (Flutter)

`RepaintBoundary.toImage(pixelRatio: 2.0)` works fine even with the widget positioned off-screen (`bottom: -10000`) as long as:

- `Opacity` is non-zero at capture time (set to `1.0` while streaming so capture sees real pixels).
- `boundary.debugNeedsPaint` is checked and `WidgetsBinding.instance.endOfFrame` is awaited if needed.

Already implemented in `example/lib/main.dart:_pushScoreband`. Do not regress.

### Files of record

- `android/src/main/kotlin/com/flutterrtmp/broadcaster/overlay/OverlayFilterManager.kt` — `updateScoreband`, `orientBitmap`, `applySponsorPosition` carry the implementation.
- `android/src/main/kotlin/com/flutterrtmp/broadcaster/camera/CameraStreamManager.kt` — `configureGlForOrientation` sets `setStreamRotation` / `setStreamIsPortrait`; the `isPortrait` flag flows from here into `OverlayFilterManager`'s constructor.

### R8 / ProGuard — overlays vanish in release (2026-05-07)

Symptom: stream connects in release/AAB builds, sponsors and scoreband both fail to render together. Root cause: R8 mangles RootEncoder internals (`com.pedro.encoder.input.gl.render.filters.object.ImageObjectFilterRender` and GL-thread dispatch), so every `glInterface.addFilter()` silently no-ops. Fix lives in `android/consumer-rules.pro` — keeps `com.pedro.**` plus the plugin's own `rtmp` and `overlay` packages. Rules propagate to consumer apps via `consumerProguardFiles 'consumer-rules.pro'` in `android/build.gradle`. Do not narrow these keeps without testing a real release/AAB build with overlays.

### Loud overlay failures (2026-05-07)

`OverlayFilterManager.updateScoreband()` now throws (`OVERLAY_NOT_INITIALIZED` / `OVERLAY_DECODE_FAILED`) instead of returning silently. `CameraStreamManager.updateScoreband()` catches, emits an `error` event on the EventChannel, and rethrows so the plugin's MethodChannel handler returns a `PlatformException` to Dart. Sponsor decode failures in `initLayers()` and `updateSponsors()` upgraded from `Log.w` to `Log.e` with byte size. Silent overlay failures are now impossible to miss in release builds where `Log.w` may be stripped.

### For iOS port (M5–M6)

Re-verify each finding against HaishinKit `ScreenObject`; do not assume Android answers carry over:

- Does `ScreenObject` render in pre- or post-rotation space inside `MediaMixer`?
- What are the units of `ScreenObject.frame` / position — pixels, normalized 0–1, or percentage?
- Does HaishinKit handle bitmap orientation automatically based on `videoOrientation`, or does the captured PNG need explicit pre-rotation as on Android?
