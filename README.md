# flutter_rtmp_broadcaster

A Flutter plugin for live RTMP broadcasting with **native, GPU-composited overlays** baked into the encoded video:
static sponsor logos, a live scoreband, and app-controlled dynamic overlays (images, animated GIFs, text, scrolling
tickers, rotating sponsor carousels) with layering, timers and animations.

The package owns the camera, compositing, encoding and RTMP push. Your app owns the UI, the gestures and the content
it sends. Nothing is drawn in Flutter: what viewers see is composited natively, so it looks the same in the preview
and in the stream.

---

## Contents

- [Platform support](#platform-support)
- [Installation](#installation)
- [Android setup](#android-setup)
- [iOS setup](#ios-setup)
- [Core concepts](#core-concepts)
- [Quick start](#quick-start)
- Guides
  - [Stream configuration](#stream-configuration)
  - [Status events](#status-events)
  - [Error handling](#error-handling)
  - [Camera: switch, mute, zoom](#camera-switch-mute-zoom)
  - [Orientation](#orientation)
  - [App lifecycle and preview recovery](#app-lifecycle-and-preview-recovery)
  - [Sponsors](#sponsors)
  - [Scoreband](#scoreband)
  - [Dynamic overlays](#dynamic-overlays)
    - [Push a Flutter widget as an overlay](#push-a-flutter-widget-as-an-overlay)
  - [Carousel](#carousel)
  - [USB camera and microphone](#usb-camera-and-microphone)
  - [Auto-reconnect and adaptive bitrate](#auto-reconnect-and-adaptive-bitrate)
  - [Diagnostics](#diagnostics)
- [API reference](#api-reference)
- [Error codes](#error-codes) · [Warning codes](#warning-codes)
- [Limits](#limits) · [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Example app](#example-app)
- [Architecture notes](#architecture-notes)
- [For contributors and AI agents](#for-contributors-and-ai-agents)

---

## Platform support

| | Android | iOS |
|---|---|---|
| Status | ✅ Implemented | 🚧 Not implemented (scaffold stub) |
| Minimum | SDK 21 | iOS 14.0 |

| Feature | Android | Device-verified |
|---|---|---|
| Camera preview, RTMP streaming, front/back switch, mute | ✅ | ✅ |
| Sponsor images and live scoreband | ✅ | ✅ |
| Portrait and landscape streaming (720p / 1080p) | ✅ | ✅ |
| Auto-reconnect, adaptive video bitrate | ✅ | ✅ |
| USB (UVC) camera and USB microphone | ✅ | ✅ |
| On-device diagnostics log | ✅ | ✅ |
| YouTube-recommended preset bitrates (4 / 10 Mbps) | ✅ | ✅ 2026-09-15 |
| Layer weights for sponsors, scoreband and dynamic overlays | ✅ | ✅ 2026-09-15 |
| Dynamic overlays: image, GIF, text, ticker, animations, live-time duration | ✅ | ✅ 2026-09-15 |
| Sponsor carousel | ✅ | ✅ 2026-09-15 |
| Camera zoom | ✅ | ✅ phone cameras 2026-09-15 · ⏳ USB (UVC) cameras pending |

Every feature in this README is **Android only** until iOS is implemented.

Requirements: Dart `>=3.10.0 <4.0.0`, Flutter `>=3.38.0`. A physical device is needed (the Android emulator has no usable camera).

---

## Installation

```yaml
dependencies:
  flutter_rtmp_broadcaster:
    path: ../flutter_rtmp_broadcaster   # or a git / pub.dev source once published
```

```dart
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
```

---

## Android setup

### 1. Minimum SDK

`android/app/build.gradle(.kts)`:

```groovy
android {
    defaultConfig {
        minSdkVersion 21
    }
}
```

### 2. Manifest

`android/app/src/main/AndroidManifest.xml`, inside `<manifest>` before `<application>`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- Only if users pick overlay images from the gallery (Android 13+) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<!-- Only for USB cameras / microphones -->
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

For USB cameras you can also add a `USB_DEVICE_ATTACHED` intent filter with a device filter XML; see
`example/android/app/src/main/AndroidManifest.xml`.

### 3. Runtime permissions

The plugin does **not** request permissions. Request camera and microphone before `initPreview()`, for example with
[`permission_handler`](https://pub.dev/packages/permission_handler):

```dart
import 'package:permission_handler/permission_handler.dart';

Future<bool> requestStreamPermissions() async {
  final statuses = await [Permission.camera, Permission.microphone].request();
  return statuses.values.every((s) => s.isGranted);
}
```

### 4. JitPack repository

The plugin depends on [RootEncoder](https://github.com/pedroSG94/RootEncoder) and a UVC library via JitPack.
**Add JitPack to your app** — Flutter does not pass a plugin's repositories on to the app.

Newer projects (`android/settings.gradle.kts` with `dependencyResolutionManagement`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Older projects (`android/build.gradle` with `allprojects`):

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Without JitPack the build fails with `Could not resolve com.github.pedroSG94.RootEncoder:library:2.7.2`.

### 5. R8 / ProGuard (release builds)

The plugin ships [`android/consumer-rules.pro`](android/consumer-rules.pro), which Gradle merges into your app's R8
config automatically. **No manual rules are needed.**

If overlays disappear only in release builds (the stream still publishes), check that your own rules don't strip
RootEncoder. Extra rules you can add to `android/app/proguard-rules.pro`:

```proguard
-keep class com.pedro.** { *; }
-keep interface com.pedro.** { *; }
-dontwarn com.pedro.**
```

---

## iOS setup

> iOS streaming is **not implemented yet**: the plugin is a scaffold and does not register its channels. Calls fail on
> iOS. The setup below prepares an app for the future implementation.

`ios/Runner/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera is needed for live streaming.</string>
<key>NSMicrophoneUsageDescription</key>
<string>Microphone is needed for live streaming audio.</string>
<!-- Only if users pick overlay images from the gallery -->
<key>NSPhotoLibraryUsageDescription</key>
<string>Select images to overlay on your stream.</string>
```

---

## Core concepts

### Controller and widget

- `RtmpBroadcastController` is the whole API: camera, stream, overlays, zoom, USB, diagnostics, events.
- `RtmpBroadcastWidget` is the camera preview: a bare native platform view. It has no buttons, no gestures and shows
  no overlays in Flutter. Put your own UI on top of it with a `Stack`.
- Use **one** `RtmpBroadcastWidget` at a time.

### Lifecycle

```
permissions granted
  └─ initPreview(config)            encoder + camera prepared, no RTMP yet
      └─ RtmpBroadcastWidget shown  surface ready → previewBound = true
          └─ configure(url, key, sponsors, config)
              ├─ updateScoreband / addOverlay / setZoom …   any time from here (overlays and zoom also work after initPreview)
              └─ startStream()      connected · bitrate · reconnecting events
                  └─ stopStream()   auto-reconnect off; overlay timers pause
                      └─ dispose()  releases the controller's notifier only
```

- `configure()` without a prior `initPreview()` also works; it prepares a fresh pipeline.
- Pass the **same** `StreamConfig` to `initPreview()` and `configure()`.
- `dispose()` does **not** stop the stream. Call `stopStream()` first.
- A new `initPreview()` drops all dynamic overlays. Preview rebinds, orientation changes and `configure()` keep them.

### Coordinates

All overlay geometry is relative to the **final stream frame as viewers see it** (after rotation), never to the phone
screen or the preview widget.

| Layer | Units |
|---|---|
| Sponsors, scoreband | integer percent 0–100 of the frame |
| Dynamic overlays | per field: `OverlayLength.percent(0–100)` or `OverlayLength.px(v)` = pixels of the encoded stream (e.g. of 720×1280) |

Text sizes (`fontSizePx`, `paddingPx`) and ticker speed are encoder pixels too. A px layout looks smaller at 1080p
than at 720p; use percent if you switch presets.

### Layering

Every overlay layer has a `weight` from 0 (back) to 100 (front):

| Layer | Default weight |
|---|---|
| Sponsors | 10 |
| Scoreband | 50 |
| Dynamic overlays | 50 |

On equal weight: sponsors < scoreband < dynamic overlays, and a dynamic overlay added later is on top. Changing a
weight re-orders immediately.

### Live time

A dynamic overlay's `duration` counts **only while the stream is live (RTMP connected) and the overlay is shown**.
Tickers scroll only while live too, so viewers always see a message from its start. GIFs, carousels and animations run
on a normal clock, live or not.

### Events

Everything the native side reports arrives on `controller.statusStream` as `RtmpStatus`. Preview attach/detach is
folded into `controller.previewBound` (a `ValueNotifier<bool>`), which updates only while something listens to
`statusStream` — always keep one listener.

---

## Quick start

A complete streaming screen: permissions, preview, configure, go live, status, cleanup.

```dart
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:permission_handler/permission_handler.dart';

class StreamScreen extends StatefulWidget {
  const StreamScreen({super.key});

  @override
  State<StreamScreen> createState() => _StreamScreenState();
}

class _StreamScreenState extends State<StreamScreen> {
  static const _config = StreamConfig.youtube720Portrait;

  final _controller = RtmpBroadcastController();
  StreamSubscription<RtmpStatus>? _statusSub;
  bool _ready = false;
  bool _live = false;
  String _status = 'Starting…';

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    final statuses = await [Permission.camera, Permission.microphone].request();
    if (!statuses.values.every((s) => s.isGranted)) {
      setState(() => _status = 'Camera and microphone permission required');
      return;
    }

    // Listen first: statusStream also drives controller.previewBound.
    _statusSub = _controller.statusStream.listen(_onStatus);

    try {
      await _controller.initPreview(config: _config);
      setState(() => _ready = true); // mount RtmpBroadcastWidget now

      await _controller.configure(
        rtmpUrl: 'rtmp://a.rtmp.youtube.com/live2',
        rtmpKey: 'your-stream-key', // never log or commit this
        config: _config,
        sponsors: const [], // see "Sponsors"
      );
      setState(() => _status = 'Ready');
    } on RtmpBroadcasterException catch (e) {
      setState(() => _status = 'Setup failed: ${e.code} ${e.message}');
    }
  }

  void _onStatus(RtmpStatus s) {
    switch (s.type) {
      case RtmpStatusType.connected:
        setState(() {
          _live = true;
          _status = 'LIVE';
        });
      case RtmpStatusType.disconnected:
        setState(() {
          _live = false;
          _status = 'Disconnected: ${s.reason ?? ''}';
        });
      case RtmpStatusType.reconnecting:
        setState(() => _status = 'Reconnecting (${s.reconnectAttempt}/3)');
      case RtmpStatusType.error:
        setState(() => _status = 'Error ${s.errorCode}: ${s.errorMessage}');
      case RtmpStatusType.bitrate:
        debugPrint('bitrate ${s.kbps} kbps');
      default:
        debugPrint('status ${s.type.name} ${s.errorCode ?? s.overlayId ?? ''}');
    }
  }

  Future<void> _toggleLive() async {
    try {
      if (_live) {
        await _controller.stopStream();
        setState(() {
          _live = false;
          _status = 'Stopped';
        });
      } else {
        await _controller.startStream();
      }
    } on RtmpBroadcasterException catch (e) {
      setState(() => _status = '${e.code}: ${e.message}');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          if (_ready) const Positioned.fill(child: RtmpBroadcastWidget()),
          Positioned(
            top: 48,
            left: 16,
            child: Text(_status, style: const TextStyle(color: Colors.white)),
          ),
          Positioned(
            bottom: 32,
            left: 0,
            right: 0,
            child: Center(
              child: ElevatedButton(
                onPressed: _ready ? _toggleLive : null,
                child: Text(_live ? 'Stop' : 'Go Live'),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _statusSub?.cancel();
    if (_live) _controller.stopStream();
    _controller.dispose();
    super.dispose();
  }
}
```

---

## Stream configuration

### Presets

| Preset | Resolution | FPS | Video bitrate | Keyframe | Orientation |
|---|---|---|---|---|---|
| `StreamConfig.youtube720Portrait` _(= `defaultConfig`)_ | 720×1280 | 30 | 4 Mbps | 2 s | portrait |
| `StreamConfig.youtube1080Portrait` | 1080×1920 | 30 | 10 Mbps | 2 s | portrait |
| `StreamConfig.youtube720Landscape` | 1280×720 | 30 | 4 Mbps | 2 s | landscape |
| `StreamConfig.youtube1080Landscape` | 1920×1080 | 30 | 10 Mbps | 2 s | landscape |

Bitrates follow YouTube's recommended H.264 settings. All presets start with the back camera. Audio is always AAC,
44.1 kHz stereo, 128 kbps.

### Custom config

```dart
const config = StreamConfig(
  width: 720,
  height: 1280,
  fps: 30,
  videoBitrate: 3_000_000, // bits per second; a ceiling for adaptive bitrate
  keyframeIntervalSeconds: 2,
  orientation: VideoOrientation.portrait,
  initialFacing: CameraFacing.front,
  // Optional USB sources (Android), see "USB camera and microphone":
  // videoInput: VideoInput.usb, usbVideoDeviceId: 1003,
  // audioInput: AudioInput.usb, usbAudioDeviceId: 42,
);
```

Rules:
- Pass the same config to `initPreview()` and `configure()`.
- A different `videoBitrate` in `configure()` (same size) is applied when the stream starts.
- A different `fps` or `keyframeIntervalSeconds` in `configure()` can't be applied while previewing: the
  `initPreview()` values are kept and a `STREAM_CONFIG_MISMATCH` warning is sent.
- A different width/height in `configure()` re-prepares the encoder.
- Choose resolution and orientation **before** going live. RTMP servers such as YouTube drop the session when the
  frame size changes mid-stream.

---

## Status events

```dart
final sub = controller.statusStream.listen((RtmpStatus s) {
  switch (s.type) {
    case RtmpStatusType.connected:
      // RTMP handshake done, you are live
      break;
    case RtmpStatusType.disconnected:
      // s.reason: why (server close, network, stop)
      break;
    case RtmpStatusType.reconnecting:
      // s.reconnectAttempt: 1…3
      break;
    case RtmpStatusType.bitrate:
      // s.kbps: current video bitrate
      break;
    case RtmpStatusType.error:
    case RtmpStatusType.warning:
      // s.errorCode, s.errorMessage; overlay warnings also set s.overlayId
      break;
    case RtmpStatusType.usbDetached:
      // a USB device was unplugged
      break;
    case RtmpStatusType.overlayShown:
    case RtmpStatusType.overlayHidden:
      // s.overlayId: enter / exit animation finished
      break;
    case RtmpStatusType.overlayRemoved:
      // s.overlayId, s.reason: removed | cleared | expired | completed
      break;
    case RtmpStatusType.zoomChanged:
      // s.zoom (ZoomInfo), s.reason: reapplied | clamped | reset | cameraSwitched
      break;
    case RtmpStatusType.previewBound:
    case RtmpStatusType.previewUnbound:
      // never delivered on statusStream; read controller.previewBound instead
      break;
  }
});
```

- `statusStream` is shared: any number of listeners, on any number of controllers, all receive every event.
- Events sent before the first listener are buffered (up to 32).
- New `RtmpStatusType` values are added as features grow. An exhaustive `switch` without `default` then needs the
  new cases (a compile error tells you).

---

## Error handling

Every controller method throws `RtmpBroadcasterException` with a stable `code` (see [Error codes](#error-codes)).
Asynchronous failures (connection lost, auth rejected, overlay filters rebuilt, …) arrive as `error` / `warning`
events instead.

```dart
try {
  await controller.startStream();
} on RtmpBroadcasterException catch (e) {
  switch (e.code) {
    case 'NOT_CONFIGURED':
      // call configure() first
      break;
    case 'PREVIEW_NOT_BOUND':
      // the preview widget isn't on screen yet; wait for controller.previewBound
      break;
    default:
      debugPrint('startStream failed: ${e.code} ${e.message}');
  }
}
```

Dynamic overlay and zoom arguments are validated in Dart **before** anything is sent, with the same codes the native
side uses, so mistakes fail fast.

Exceptions to the rule: `exportDiagnostics()` never throws (it returns error text), and `updateSponsors()` is not
implemented (see [Known limitations](#known-limitations)).

---

## Camera: switch, mute, zoom

```dart
await controller.switchCamera(facing: CameraFacing.front); // live-safe; resets zoom to 1.0
await controller.setAudioMuted(true);                      // live-safe
```

`switchCamera` does nothing for a USB camera.

### Zoom

Zoom happens inside the camera, so the preview and the stream zoom together and **overlays keep their size and
position**.

```dart
final ZoomInfo zoom = await controller.getZoom();
// ZoomInfo(supported: true, min: 0.6, max: 10.0, current: 1.0, source: ZoomSource.camera2)

final ZoomInfo applied = await controller.setZoom(2.0); // ratio; clamped to min…max
```

- Phone cameras: real zoom ratios. `min` can be below 1.0 on phones with an ultra-wide lens (Android 11+).
- USB cameras: only when the camera has a hardware zoom control, otherwise `supported` is `false` and only
  `setZoom(1.0)` is allowed.
- The camera opens a moment after `previewBound` becomes true. Until then zoom calls throw `ZOOM_NOT_READY`: retry
  shortly.
- The package keeps your zoom across preview rebinds (background → foreground), orientation changes, stop/start and
  reconnects, and resets it to 1.0 on `switchCamera`. Each such change arrives as `zoomChanged` with `RtmpStatus.zoom`.

Pinch and slider (the widget has no gestures; your app adds them):

```dart
class ZoomablePreview extends StatefulWidget {
  const ZoomablePreview({super.key, required this.controller});

  final RtmpBroadcastController controller;

  @override
  State<ZoomablePreview> createState() => _ZoomablePreviewState();
}

class _ZoomablePreviewState extends State<ZoomablePreview> {
  ZoomInfo? _zoom;
  double _pinchBase = 1.0;
  StreamSubscription<RtmpStatus>? _sub;
  bool _inFlight = false;
  double? _pending;

  @override
  void initState() {
    super.initState();
    _sub = widget.controller.statusStream.listen((s) {
      if (s.type == RtmpStatusType.zoomChanged && s.zoom != null) {
        setState(() => _zoom = s.zoom);
      }
    });
    widget.controller.previewBound.addListener(_loadZoom);
    _loadZoom();
  }

  Future<void> _loadZoom() async {
    if (!widget.controller.previewBound.value) return;
    for (var attempt = 0; attempt < 10; attempt++) {
      try {
        final info = await widget.controller.getZoom();
        if (mounted) setState(() => _zoom = info);
        return;
      } on RtmpBroadcasterException catch (e) {
        if (e.code != 'ZOOM_NOT_READY') return;
        await Future<void>.delayed(const Duration(milliseconds: 200));
      }
    }
  }

  // Pinch updates arrive faster than the camera applies them: keep one call in flight.
  Future<void> _setZoom(double level) async {
    _pending = level;
    if (_inFlight) return;
    _inFlight = true;
    try {
      while (_pending != null) {
        final next = _pending!;
        _pending = null;
        final info = await widget.controller.setZoom(next);
        if (mounted) setState(() => _zoom = info);
      }
    } on RtmpBroadcasterException catch (e) {
      debugPrint('zoom: ${e.code}');
    } finally {
      _inFlight = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final zoom = _zoom;
    return Stack(
      children: [
        Positioned.fill(
          child: GestureDetector(
            onScaleStart: (_) => _pinchBase = zoom?.current ?? 1.0,
            onScaleUpdate: (d) {
              if (d.pointerCount >= 2) _setZoom(_pinchBase * d.scale);
            },
            child: const RtmpBroadcastWidget(),
          ),
        ),
        if (zoom != null && zoom.supported)
          Positioned(
            bottom: 96,
            left: 24,
            right: 24,
            child: Slider(
              min: zoom.min,
              max: zoom.max,
              value: zoom.current.clamp(zoom.min, zoom.max),
              label: '${zoom.current.toStringAsFixed(1)}×',
              onChanged: _setZoom,
            ),
          ),
      ],
    );
  }

  @override
  void dispose() {
    widget.controller.previewBound.removeListener(_loadZoom);
    _sub?.cancel();
    super.dispose();
  }
}
```

---

## Orientation

Portrait is the default. Pick the orientation before going live.

```dart
// Portrait: device upright, stream 720×1280
await controller.configure(
  rtmpUrl: url, rtmpKey: key, sponsors: sponsors, config: StreamConfig.youtube720Portrait);
await controller.setAppOrientation(VideoOrientation.portrait);

// Landscape: stream 1280×720
await controller.configure(
  rtmpUrl: url, rtmpKey: key, sponsors: sponsors, config: StreamConfig.youtube720Landscape);
await controller.setAppOrientation(VideoOrientation.landscape);
```

- `setAppOrientation` locks the activity to that orientation and re-prepares the encoder if the frame flips. The user
  must physically rotate the device (sensor auto-rotate is off).
- Overlays, the scoreband, sponsors and zoom are rebuilt for the new frame automatically. px lengths keep their value
  and are clamped to the new frame.
- `RtmpBroadcastWidget` rebuilds its native view on rotation (`previewBound` goes false, then true).

Restore portrait when leaving the stream screen:

```dart
await SystemChrome.setPreferredOrientations([
  DeviceOrientation.portraitUp,
  DeviceOrientation.portraitDown,
]);
```

---

## App lifecycle and preview recovery

When the app goes to the background, Android destroys the preview surface (`previewBound` → false). When the app comes
back, the package re-attaches the preview automatically and rebuilds every overlay layer (`previewBound` → true). The
encoder, overlays and a running stream are not restarted.

If the preview is still not bound shortly after resuming, force a cheap rebind:

```dart
class _StreamLifecycle with WidgetsBindingObserver {
  _StreamLifecycle(this.controller);

  final RtmpBroadcastController controller;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    Future<void>.delayed(const Duration(milliseconds: 500), () async {
      if (controller.previewBound.value) return;
      try {
        await controller.rebindPreview();
      } on RtmpBroadcasterException catch (e) {
        // NO_PREVIEW_VIEW / SURFACE_UNAVAILABLE: the view isn't ready yet; retry later
        // or fall back to initPreview() + configure().
        debugPrint('rebindPreview: ${e.code}');
      }
    });
  }
}

// WidgetsBinding.instance.addObserver(_StreamLifecycle(controller));
```

---

## Sponsors

Static sponsor images are sent once with `configure()` and composited for the whole session.

```dart
await controller.configure(
  rtmpUrl: url,
  rtmpKey: key,
  config: StreamConfig.youtube720Portrait,
  sponsors: [
    SponsorOverlay(
      bytes: leftLogoPng, // PNG or JPG bytes (HEIC is not supported)
      placement: const SponsorPlacement(left: 2, top: 2, width: 22, height: 8),
    ),
    SponsorOverlay(
      bytes: centerLogoPng,
      placement: const SponsorPlacement(top: 2, width: 22, height: 8), // centered horizontally
    ),
    SponsorOverlay(
      bytes: rightLogoPng,
      placement: const SponsorPlacement(right: 2, top: 2, width: 22, height: 8, weight: 12),
    ),
  ],
);
```

**`SponsorPlacement` rules** (integers 0–100, percent of the stream frame):
- Size: the image is scaled, keeping its aspect ratio, to fit inside `width × height` (like `BoxFit.contain`).
- Horizontal: only `left` → left edge `left`% from the frame's left; only `right` → right edge `right`% from the
  frame's right; both or neither → centered. Vertical works the same with `top` / `bottom`.
- `weight` 0–100, default 10 (see [Layering](#layering)).

A sponsor image that can't be decoded is skipped with a `SPONSOR_DECODE_FAILED` warning.

Sponsors can't be changed after `configure()` (`updateSponsors` is not implemented). For sponsors that change or
rotate during the stream, use a [dynamic overlay](#dynamic-overlays) or a [carousel](#carousel).

`SponsorOverlay(position: OverlayPosition(x, y, width, height))` with normalized 0.0–1.0 values still works but is
**deprecated**.

---

## Scoreband

The scoreband is one image you push whenever the score changes. It is drawn bottom-center, 90 % wide by default.

```dart
await controller.updateScoreband(
  pngBytes,
  width: 90,  // % of stream width, 1–100; height follows the image aspect
  x: 50,      // 0 = left edge, 50 = centered, 100 = right edge
  y: 100,     // 0 = top edge, 100 = bottom edge
  weight: 50, // layer order, 0–100
);
```

Render a Flutter widget to PNG and push it (the widget can be off-screen, but must not have opacity 0):

```dart
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';

final _scoreBandKey = GlobalKey();

// In build(): an off-screen, capturable scoreband widget.
Widget buildHiddenScoreband(Widget scoreband) => Positioned(
      left: 0,
      top: -10000, // off-screen
      child: RepaintBoundary(key: _scoreBandKey, child: scoreband),
    );

// After every score change (after setState):
Future<void> pushScoreband(RtmpBroadcastController controller) async {
  final boundary = _scoreBandKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
  if (boundary == null) return;
  if (boundary.debugNeedsPaint) {
    await WidgetsBinding.instance.endOfFrame;
  }
  final image = await boundary.toImage(pixelRatio: 2.0);
  final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
  if (byteData == null) return;
  await controller.updateScoreband(byteData.buffer.asUint8List());
}
```

Push only when the data changes; the native side swaps the texture without interrupting the stream. If your scoreband
widget reads inherited state (Riverpod, Provider, Theme), render it under the same ancestors.

The same captured bytes work as a dynamic overlay, which adds animations, a live-time duration, free placement and
hide/show — see [Push a Flutter widget as an overlay](#push-a-flutter-widget-as-an-overlay).

---

## Dynamic overlays

App-controlled layers you can add, change, hide, show and remove by id at any time, before or during a stream. They
appear in the preview and the stream like sponsors and the scoreband. Available after `initPreview()` or `configure()`.

### Add, update, hide, show, remove

```dart
Future<void> dynamicOverlayBasics(RtmpBroadcastController controller, Uint8List bannerPng, Uint8List newPng) async {
  await controller.addOverlay(DynamicOverlay(
    id: 'goal-banner',                       // your id: 1–64 chars, not 'scoreband', not 'sponsor_…'
    content: ImageContent(bannerPng),        // PNG, JPG or static WebP
    placement: const OverlayPlacement(
      right: OverlayLength.px(24),           // right edge 24 stream px from the frame's right
      top: OverlayLength.percent(5),         // top edge at 5 % of the frame height
      width: OverlayLength.percent(30),      // 30 % of the frame width; height from the image aspect
    ),
    weight: 60,                              // in front of the scoreband (50)
    duration: const Duration(seconds: 15),   // removed after 15 s of live time; null = stays
    enter: const OverlayAnimation.slide(edge: OverlayEdge.right),
    exit: const OverlayAnimation.pop(durationMs: 250),
  ));

  // Change anything in place. Omitted arguments stay as they are; nothing re-animates.
  await controller.updateOverlay('goal-banner', content: ImageContent(newPng));
  await controller.updateOverlay('goal-banner', weight: 100); // bring to front
  await controller.updateOverlay('goal-banner',
      placement: const OverlayPlacement(left: OverlayLength.percent(2), bottom: OverlayLength.percent(12)));

  await controller.hideOverlay('goal-banner'); // plays exit, keeps it, pauses its timer
  await controller.showOverlay('goal-banner'); // plays enter, resumes

  await controller.removeOverlay('goal-banner');                 // plays exit, then removed
  await controller.removeOverlay('goal-banner', animate: false); // (or) instant
  await controller.clearOverlays();                              // all dynamic overlays, instant by default
}
```

`clearOverlays()` never touches sponsors or the scoreband. A removed id can be added again right away.

### Content types

```dart
Future<void> contentTypes(RtmpBroadcastController controller, Uint8List gifBytes, Uint8List fontBytes) async {
  // Image from an asset
  final logo = (await rootBundle.load('assets/logo.png')).buffer.asUint8List();
  await controller.addOverlay(DynamicOverlay(id: 'logo', content: ImageContent(logo)));

  // Animated GIF: loops by its own frame delays (≤ 150 frames, ≤ 64 MB decoded)
  await controller.addOverlay(DynamicOverlay(id: 'spinner', content: GifContent(gifBytes)));

  // Single-line text badge: scaled to fit the placement
  await controller.addOverlay(DynamicOverlay(
    id: 'badge',
    content: const TextContent(
      'GOAL!',
      style: TextOverlayStyle(
        fontSizePx: 48,                   // stream px
        color: Color(0xFFFFFFFF),
        background: Color(0xCC1565C0),     // ARGB; null = transparent
        paddingPx: 12,
      ),
    ),
    placement: const OverlayPlacement(top: OverlayLength.percent(10)),
  ));

  // Wrapped paragraph with a custom font (TTF/OTF bytes)
  await controller.addOverlay(DynamicOverlay(
    id: 'lower-third',
    content: TextContent(
      'Player of the match\nScored 112 off 64 balls, 9 fours and 6 sixes',
      style: TextOverlayStyle(
        fontSizePx: 30,
        background: const Color(0xB3000000),
        maxLines: 3,                      // > 1 wraps to the placement width, ends with … when longer
        align: TextOverlayAlign.center,
        fontTtf: fontBytes,
      ),
    ),
    placement: const OverlayPlacement(
      left: OverlayLength.percent(5),
      bottom: OverlayLength.percent(15),
      width: OverlayLength.percent(90),
    ),
  ));

  // News ticker: one line scrolling through a full-width band
  await controller.addOverlay(DynamicOverlay(
    id: 'ticker',
    content: const TickerContent(
      'Breaking: rain delay expected at 15:30 · Next match starts 18:00 · Follow for live updates',
      speedPxPerSec: 140,                 // or cycleDuration: Duration(seconds: 12), not both
      loop: true,                         // false = one pass, then removed with reason 'completed'
      loopGap: OverlayLength.percent(20), // gap between passes, % of the band width
      direction: TickerDirection.auto,    // Arabic / Hebrew / Urdu scroll left→right automatically
    ),
    placement: const OverlayPlacement(bottom: OverlayLength.px(0)),
    weight: 70,
  ));
}
```

| Content | Notes |
|---|---|
| `ImageContent(bytes)` | PNG, JPG, static WebP. Images above 2048 px are subsampled on decode. The bytes can come from an asset, the network, a `Canvas`, or a captured Flutter widget — see [below](#push-a-flutter-widget-as-an-overlay). |
| `GifContent(bytes)` | Loops forever by frame delays (delays ≤ 10 ms play at 100 ms, minimum 20 ms). Runs live or not. |
| `TextContent(text, {style})` | `maxLines: 1` (default): line breaks become spaces and the line is scaled to fit — long text gets tiny. `maxLines > 1`: wraps to the placement width (default the frame width), keeps `\n`, ellipsis after the last line. Android shapes complex scripts (Bangla, Arabic). |
| `TickerContent(text, {style, speedPxPerSec, cycleDuration, loop, loopGap, direction})` | Band = placement `width` (default 100 %) × one line (`fontSizePx` + 2 × `paddingPx`); `height` is ignored. Default speed 120 px/s, default gap 33 % of the band, default style white on 70 % black. Scrolls only while live and shown. Changing the text restarts the scroll; changing only style or speed keeps the position. When a `duration` runs out, the current pass finishes first. |
| `CarouselContent(items, …)` | See [Carousel](#carousel). |

`OverlayPlacement`, `TextOverlayStyle`, `TextContent` and `TickerContent` have `copyWith` for quick updates:

```dart
const style = TextOverlayStyle(fontSizePx: 36, background: Color(0xCC000000));
await controller.updateOverlay('badge', content: TextContent('GOAL! 2–1', style: style.copyWith(fontSizePx: 44)));
```

### Push a Flutter widget as an overlay

`ImageContent` takes the same PNG bytes as [`updateScoreband`](#scoreband), so anything you can render as a Flutter
widget can be an overlay: wrap it in a `RepaintBoundary`, capture it, and push the bytes. Use this when you want a
widget-designed layer *and* the things the scoreband API doesn't offer — enter/exit animations, a live-time
`duration`, arbitrary placement, `weight`, and `hideOverlay` / `showOverlay`.

```dart
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';

/// Renders a mounted RepaintBoundary to PNG; null when it isn't on screen yet.
Future<Uint8List?> captureBoundaryPng(GlobalKey key, {double pixelRatio = 2.0}) async {
  final boundary = key.currentContext?.findRenderObject() as RenderRepaintBoundary?;
  if (boundary == null) return null;
  if (boundary.debugNeedsPaint) {
    await WidgetsBinding.instance.endOfFrame;
  }
  final image = await boundary.toImage(pixelRatio: pixelRatio);
  try {
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    return byteData?.buffer.asUint8List();
  } finally {
    image.dispose();
  }
}

final _bandKey = GlobalKey();

// In build(): off-screen but painted, so it can be captured.
Widget buildHiddenBand(Widget band) =>
    Positioned(left: 0, top: -10000, child: RepaintBoundary(key: _bandKey, child: band));

Future<void> showBand(RtmpBroadcastController controller) async {
  final png = await captureBoundaryPng(_bandKey);
  if (png == null) return;
  await controller.addOverlay(DynamicOverlay(
    id: 'match-band',                        // 'scoreband' and 'sponsor_*' are reserved
    content: ImageContent(png),
    placement: const OverlayPlacement(
      bottom: OverlayLength.percent(6),
      width: OverlayLength.percent(90),
    ),
    weight: 55,
    enter: const OverlayAnimation.slide(edge: OverlayEdge.bottom),
    exit: const OverlayAnimation.slide(edge: OverlayEdge.bottom),
  ));
}

// After every data change: re-render the widget, re-capture, swap the image.
Future<void> refreshBand(RtmpBroadcastController controller) async {
  final png = await captureBoundaryPng(_bandKey);
  if (png == null) return;
  await controller.updateOverlay('match-band', content: ImageContent(png));
}
```

`updateOverlay(content:)` swaps the texture on the live layer — the overlay is not removed and re-added, and the
enter animation does not replay (only a changed `weight` re-orders the stack).

Things to get right:

- **Push, don't poll.** Call `refreshBand` when your data changes. The package runs no timers of its own.
- **The widget must be mounted and painted.** Off-screen (`top: -10000`) is fine; `Opacity(opacity: 0)` is not,
  because it paints nothing. `setState` first, then capture on the next frame.
- **Ids.** `scoreband` and `sponsor_*` throw `OVERLAY_ID_RESERVED`, so a captured band needs its own id; it can run
  alongside the real scoreband at a different `weight`.
- **Budget.** At most 16 dynamic overlays, visible plus hidden (`OVERLAY_LIMIT_REACHED`).
- **Cost.** Each update decodes the PNG once; images above 2048 px are subsampled on decode. `pixelRatio` only buys
  sharpness, since the layer is scaled to its placement on the stream.
- **Inherited state.** If the widget reads Riverpod/Provider/Theme, render it under the same ancestors.

### Placement

| Field | Meaning |
|---|---|
| `width`, `height` | Size box. Both → fit inside, keeping aspect. One → the other follows the content's aspect. Neither → the content's own pixel size. |
| `left` / `right` | Only `left` → pins the left edge; only `right` → pins the right edge; both or neither → centered. |
| `top` / `bottom` | Same, vertically. |

- Each field is `OverlayLength.percent(v)` (0–100 of the frame width for left/right/width, height for top/bottom/height)
  or `OverlayLength.px(v)` (stream pixels, ≥ 0).
- The overlay always stays inside the frame. Anything larger than the frame is scaled down, with an
  `OVERLAY_DOWNSCALED` warning (`RtmpStatus.overlayId` set).
- `const OverlayPlacement()` = centered at the content's own size.

Cookbook:

```dart
const topRightCorner = OverlayPlacement(
  right: OverlayLength.px(24), top: OverlayLength.px(24), width: OverlayLength.percent(20));
const bottomLeftCorner = OverlayPlacement(
  left: OverlayLength.percent(3), bottom: OverlayLength.percent(3), height: OverlayLength.percent(8));
const lowerThird = OverlayPlacement(
  left: OverlayLength.percent(5), bottom: OverlayLength.percent(14), width: OverlayLength.percent(90));
const fullWidthTopBand = OverlayPlacement(top: OverlayLength.px(0), width: OverlayLength.percent(100));
const centeredHalfWidth = OverlayPlacement(width: OverlayLength.percent(50));
```

### Animations

```dart
const enter = OverlayAnimation.slide(edge: OverlayEdge.left, durationMs: 400, easing: OverlayEasing.easeOut);
const exit = OverlayAnimation.curtain(durationMs: 300, easing: OverlayEasing.easeIn);
const popIn = OverlayAnimation.pop();
const instant = OverlayAnimation.none;
```

| Type | Enter | Exit |
|---|---|---|
| `slide` | moves in from `edge` | moves out to `edge` |
| `pop` | scales up from the center | scales down to the center |
| `curtain` | reveals from the center outward | closes to the center |
| `none` | instant | instant |

- Easing: `linear`, `easeIn`, `easeOut` (default), `easeInOut`. Duration 0–5000 ms (default 400; 0 = instant).
- `enter` plays on `addOverlay` and `showOverlay`; `exit` on `hideOverlay`, `removeOverlay`, expiry and a finished
  one-pass ticker. Animations play live or not.
- Events fire when the animation finishes: `overlayShown`, `overlayHidden`, `overlayRemoved`.

Interruptions:

| Case | Result |
|---|---|
| hide/remove while entering | same animation type → reverses from where it is; different type → jumps to shown, then exits |
| show while hiding | same rule in reverse |
| remove while hiding | the exit finishes, then removed |
| hide while hidden, show while shown | no-op |
| update while animating | applied at once; the animation continues with the new content/placement |
| any call on an overlay that is exiting for removal | `OVERLAY_NOT_FOUND` (it's already gone for the API) |
| add with the id of an overlay that is exiting for removal | the old one finishes instantly, the new one is added |

### Duration

```dart
await controller.addOverlay(DynamicOverlay(
  id: 'promo', content: ImageContent(promoPng), duration: const Duration(seconds: 30)));

await controller.updateOverlay('promo', duration: const OverlayDurationUpdate.of(Duration(seconds: 60))); // new total
await controller.updateOverlay('promo', restartTimer: true);                                           // count from 0
await controller.updateOverlay('promo', duration: const OverlayDurationUpdate.infinite());             // never expire
```

- Counts only while the stream is **connected and the overlay is shown**: not before `startStream()`, not during
  reconnects, not after `stopStream()`, not while hidden or animating. The remaining time carries over to the next
  `startStream()` and across preview rebinds.
- When it runs out: exit animation, then `overlayRemoved` with reason `expired`.
- A new total keeps the time already counted, so a shorter total can expire the overlay immediately. A paused overlay
  never expires; it expires the moment it runs again.
- `OverlayDurationUpdate.keep()` (same as omitting `duration`) leaves it unchanged.

### Events

| Event | `RtmpStatus` fields |
|---|---|
| `overlayShown` | `overlayId` |
| `overlayHidden` | `overlayId` |
| `overlayRemoved` | `overlayId`, `reason`: `removed` · `cleared` · `expired` · `completed` (one-pass ticker) |
| `warning` `OVERLAY_DOWNSCALED` | `overlayId`, `errorMessage` |

---

## Carousel

A carousel shows images or GIFs one after another in the same slot, e.g. rotating sponsor logos. It is a normal
dynamic overlay, so id, placement, weight, duration, animations and hide/show/update/remove all apply to the whole
slot.

```dart
Future<void> sponsorCarousel(
  RtmpBroadcastController controller,
  Uint8List titleSponsorPng,
  Uint8List sponsorBPng,
  Uint8List sponsorCGif,
) async {
  await controller.addOverlay(DynamicOverlay(
    id: 'sponsors',
    content: CarouselContent(
      [
        CarouselItem(ImageContent(titleSponsorPng), interval: const Duration(seconds: 10)), // own interval
        CarouselItem(ImageContent(sponsorBPng)),
        CarouselItem(GifContent(sponsorCGif)),
      ],
      interval: const Duration(seconds: 5), // default time per item, ≥ 500 ms
      transition: const CarouselTransition.push(edge: OverlayEdge.bottom, durationMs: 400),
      // or CarouselTransition.crossfade(durationMs: 600) / CarouselTransition.cut()
    ),
    placement: const OverlayPlacement(
      right: OverlayLength.px(24),
      top: OverlayLength.px(24),
      width: OverlayLength.percent(22),
      height: OverlayLength.percent(10), // the slot; every item fits inside it
    ),
    enter: const OverlayAnimation.slide(edge: OverlayEdge.right),
    weight: 20,
  ));
}
```

| Transition | Look |
|---|---|
| `CarouselTransition.cut()` | instant swap |
| `CarouselTransition.crossfade({durationMs = 500, easing = easeInOut})` | current fades out while the next fades in |
| `CarouselTransition.push({edge = right, durationMs = 500, easing = easeInOut})` | next item pushes the current one out, entering from `edge` |

- Each item stays for its own `interval` or the carousel's `interval`. The transition takes the **last** part of that
  time, so `durationMs` must be shorter than every interval.
- It loops forever; give the overlay a `duration` to stop it.
- The rotation runs on a normal clock from `addOverlay`: also before going live and while hidden.
  `updateOverlay(content: CarouselContent(…))` starts again at the first item; placement, weight and duration updates
  keep the position.
- Slot size: with both `width` and `height`, the slot is exactly that box. Otherwise it is as tall as the tallest item
  and as wide as the widest item's aspect. Every item is fitted and centered, so the slot never changes size.
- 1–20 items, image or GIF only, ≤ 64 MB decoded in total (`OVERLAY_CAROUSEL_TOO_LARGE`).
- There is no per-item event.

---

## USB camera and microphone

UVC cameras and USB audio inputs can replace the phone camera and microphone (Android; USB audio needs Android 6+).

```dart
Future<void> startWithUsbSources(RtmpBroadcastController controller) async {
  final cameras = await controller.listUsbVideoDevices(); // List<UsbDeviceInfo>
  if (cameras.isEmpty) return;
  final camera = cameras.first;
  if (!camera.hasPermission) {
    final granted = await controller.requestUsbPermission(camera.deviceId); // system dialog
    if (!granted) return;
  }

  final mics = await controller.listUsbAudioDevices(); // List<UsbAudioDeviceInfo>

  final config = StreamConfig(
    width: 1280,
    height: 720,
    fps: 30,
    videoBitrate: 4_000_000,
    keyframeIntervalSeconds: 2,
    orientation: VideoOrientation.landscape,
    initialFacing: CameraFacing.back,
    videoInput: VideoInput.usb,
    usbVideoDeviceId: camera.deviceId,
    audioInput: mics.isEmpty ? AudioInput.mic : AudioInput.usb,
    usbAudioDeviceId: mics.isEmpty ? null : mics.first.deviceId,
  );

  await controller.initPreview(config: config);
  // … show RtmpBroadcastWidget, then configure() with the same config
}
```

- Pass the same USB fields in the `StreamConfig` for both `initPreview()` and `configure()`.
- Unplugging sends `usbDetached`. `startStream()` fails with `USB_DEVICE_GONE` / `USB_PERMISSION_REVOKED` if the camera
  is gone or permission was lost.
- `switchCamera` does nothing for USB cameras. Zoom works if the camera has a zoom control.
- If the USB microphone isn't found at start, the default microphone is used.

---

## Auto-reconnect and adaptive bitrate

**Auto-reconnect:** if the connection drops unexpectedly, the plugin retries **3 times, 3 seconds apart**.
- Each attempt sends `reconnecting` with `reconnectAttempt` 1–3.
- Success sends `connected` again. The encoder, preview and overlays keep running throughout.
- After the third failure: `error` with `MAX_RECONNECT_EXCEEDED`, and the stream is stopped so `startStream()` works again.
- `stopStream()` turns auto-reconnect off.
- Dynamic overlay timers and tickers pause while reconnecting.

**Adaptive bitrate:** the configured `videoBitrate` is a **ceiling**. When the uplink can't keep up, the plugin lowers
the video bitrate and raises it again when there is room, instead of letting frames pile up until the server closes
the connection. `bitrate` events therefore vary during a stream. Audio stays at 128 kbps.

---

## Diagnostics

A rotating on-device log (about 512 KB) for debugging release builds in the field, where logcat isn't available.

```dart
final String log = await controller.exportDiagnostics(); // never throws; share or upload it
await controller.clearDiagnostics();
```

Failures are logged with their error code (`ERROR/<CODE>`). The stream key is never logged.

---

## API reference

### `RtmpBroadcastController`

| Method | Returns | Description | Throws |
|---|---|---|---|
| `RtmpBroadcastController()` | — | Creates a controller. Controllers share one native pipeline and one event stream. | — |
| `initPreview({StreamConfig? config})` | `Future<void>` | Prepares camera, encoder and audio for preview (default `StreamConfig.defaultConfig`). Resets `previewBound` to false. A new call drops dynamic overlays. | `NO_CONTEXT`, `NO_ACTIVITY`, `INIT_PREVIEW_ERROR` |
| `configure({required String rtmpUrl, required String rtmpKey, required List<SponsorOverlay> sponsors, required StreamConfig config})` | `Future<void>` | Sets the RTMP endpoint (`"$rtmpUrl/$rtmpKey"`), sponsors and config. Re-prepares only if the frame size changed. | `INVALID_URL`, `INVALID_KEY`, `NO_CONTEXT`, `NO_ACTIVITY`, `INVALID_ARGS`, `CONFIGURE_ERROR` |
| `startStream()` | `Future<void>` | Connects and starts broadcasting. | `NOT_CONFIGURED`, `ALREADY_STREAMING`, `STREAM_ERROR`, `PREVIEW_NOT_READY`, `PREVIEW_NOT_BOUND`, `USB_DEVICE_GONE`, `USB_PERMISSION_REVOKED` |
| `stopStream()` | `Future<void>` | Stops broadcasting and disables auto-reconnect. Preview keeps running. | — |
| `updateScoreband(Uint8List pngBytes, {int width = 90, int x = 50, int y = 100, int weight = 50})` | `Future<void>` | Shows or replaces the scoreband image. Live-safe. | `OVERLAY_INVALID_PLACEMENT`, `NOT_CONFIGURED`, `INVALID_ARGS`, `OVERLAY_NOT_INITIALIZED`, `OVERLAY_DECODE_FAILED` |
| `updateSponsors(List<SponsorOverlay> sponsors)` | `Future<void>` | ⛔ Not implemented on Android (fails with `MissingPluginException`). | — |
| `switchCamera({required CameraFacing facing})` | `Future<void>` | Front/back camera. Live-safe. Resets zoom to 1.0. No-op for USB cameras. | — |
| `getZoom()` | `Future<ZoomInfo>` | Current zoom state. | `ZOOM_NOT_READY`, `ZOOM_OPERATION_FAILED` |
| `setZoom(double level)` | `Future<ZoomInfo>` | Zooms to a ratio, clamped to `min`–`max`; returns the applied state. Safe on every pinch update. | `ZOOM_INVALID`, `ZOOM_NOT_READY`, `ZOOM_UNSUPPORTED`, `ZOOM_OPERATION_FAILED` |
| `setAudioMuted(bool muted)` | `Future<void>` | Mutes/unmutes the microphone. Live-safe. | — |
| `setAppOrientation(VideoOrientation orientation)` | `Future<void>` | Locks the app orientation and re-prepares the encoder if the frame flips. Not while live. | — |
| `rebindPreview()` | `Future<void>` | Re-attaches the preview to the on-screen view without re-initializing. | `NO_MANAGER`, `NO_PREVIEW_VIEW`, `SURFACE_UNAVAILABLE`, `REBIND_PREVIEW_ERROR` |
| `addOverlay(DynamicOverlay overlay)` | `Future<void>` | Adds a dynamic overlay and plays `enter`. | `OVERLAY_ID_EXISTS`, `OVERLAY_ID_RESERVED`, `OVERLAY_LIMIT_REACHED`, `OVERLAY_INVALID_CONTENT`, `OVERLAY_INVALID_PLACEMENT`, `OVERLAY_DECODE_FAILED`, `OVERLAY_GIF_TOO_LARGE`, `OVERLAY_CAROUSEL_TOO_LARGE`, `OVERLAY_FONT_INVALID`, `OVERLAY_NOT_INITIALIZED`, `OVERLAY_OPERATION_FAILED` |
| `updateOverlay(String id, {OverlayContent? content, OverlayPlacement? placement, int? weight, OverlayDurationUpdate? duration, bool restartTimer = false})` | `Future<void>` | Changes an overlay in place; omitted arguments stay unchanged; the timer keeps running unless `restartTimer`. | `OVERLAY_NOT_FOUND`, `OVERLAY_ID_RESERVED`, content/placement codes as in `addOverlay` |
| `hideOverlay(String id)` | `Future<void>` | Plays `exit` and keeps the overlay; pauses its timer and ticker. No-op if hidden. | `OVERLAY_NOT_FOUND`, `OVERLAY_ID_RESERVED`, `OVERLAY_NOT_INITIALIZED` |
| `showOverlay(String id)` | `Future<void>` | Plays `enter` and resumes. No-op if shown. | `OVERLAY_NOT_FOUND`, `OVERLAY_ID_RESERVED`, `OVERLAY_NOT_INITIALIZED` |
| `removeOverlay(String id, {bool animate = true})` | `Future<void>` | Removes an overlay, with `exit` unless `animate: false`. | `OVERLAY_NOT_FOUND`, `OVERLAY_ID_RESERVED`, `OVERLAY_NOT_INITIALIZED` |
| `clearOverlays({bool animate = false})` | `Future<void>` | Removes all dynamic overlays (reason `cleared`). Sponsors and scoreband stay. | `OVERLAY_NOT_INITIALIZED` |
| `listUsbVideoDevices()` | `Future<List<UsbDeviceInfo>>` | Attached UVC cameras. | — |
| `listUsbAudioDevices()` | `Future<List<UsbAudioDeviceInfo>>` | USB audio inputs (empty below Android 6). | — |
| `requestUsbPermission(int deviceId)` | `Future<bool>` | Shows the system USB permission dialog; `true` if granted. | `INVALID_ARGS` |
| `exportDiagnostics()` | `Future<String>` | Diagnostics log text. Never throws. | — |
| `clearDiagnostics()` | `Future<void>` | Deletes the diagnostics log. | — |
| `dispose()` | `void` | Disposes `previewBound`. Does not stop the stream or release the camera. | — |

Every method that talks to native code can also throw `NO_CONTEXT` if the plugin isn't attached.

| Property | Type | Description |
|---|---|---|
| `statusStream` | `Stream<RtmpStatus>` | All events except preview bind/unbind. Shared: every listener gets every event. |
| `previewBound` | `ValueNotifier<bool>` | `true` while the preview is attached to a surface. Updated from `statusStream` events — keep a listener. |
| `config` | `StreamConfig` | The config of the last successful `configure()`. Throws before that. |

### `RtmpBroadcastWidget`

```dart
const RtmpBroadcastWidget({Key? key, RtmpBroadcastController? controller})
```

The native camera preview (Android `TextureView`). Size it like any widget; the camera image fills it. No UI chrome,
no gestures, no overlays in Flutter. It rebuilds its native view when the device orientation changes. `controller` is
currently unused. One instance at a time.

### `StreamConfig`

| Field | Type | Default | Description |
|---|---|---|---|
| `width`, `height` | `int` | required | Encoded frame size (post-rotation) |
| `fps` | `int` | required | Frames per second |
| `videoBitrate` | `int` | required | Bits per second; ceiling for adaptive bitrate |
| `keyframeIntervalSeconds` | `int` | required | Keyframe (GOP) interval |
| `orientation` | `VideoOrientation` | required | `portrait` · `landscape` |
| `initialFacing` | `CameraFacing` | required | `front` · `back` |
| `videoInput` | `VideoInput` | `device` | `device` · `usb` |
| `audioInput` | `AudioInput` | `mic` | `mic` · `usb` |
| `usbVideoDeviceId` | `int?` | `null` | from `UsbDeviceInfo.deviceId` |
| `usbAudioDeviceId` | `int?` | `null` | from `UsbAudioDeviceInfo.deviceId` |

Static presets: `youtube720Portrait`, `youtube1080Portrait`, `youtube720Landscape`, `youtube1080Landscape`,
`defaultConfig` (= `youtube720Portrait`). Method: `toMap()`.
`enum VideoResolution { hd720, fhd1080 }` is exported for app UIs; `StreamConfig` doesn't use it.

### `SponsorOverlay` and `SponsorPlacement`

```dart
const SponsorOverlay({required Uint8List bytes, SponsorPlacement? placement, @Deprecated OverlayPosition? position})
const SponsorPlacement({int? left, int? right, int? top, int? bottom, required int width, required int height, int weight = 10})
@Deprecated const OverlayPosition({required double x, required double y, required double width, required double height}) // 0.0–1.0
```

One of `placement` / `position` is required. Placement rules: [Sponsors](#sponsors).

### `DynamicOverlay`

```dart
const DynamicOverlay({
  required String id,              // 1–64 chars; not 'scoreband'; not starting with 'sponsor_'
  required OverlayContent content,
  OverlayPlacement placement = const OverlayPlacement(),
  int weight = 50,                 // 0–100
  Duration? duration,              // live time; null = infinite; ≥ 1 ms
  OverlayAnimation enter = OverlayAnimation.none,
  OverlayAnimation exit = OverlayAnimation.none,
})
```

Constant `DynamicOverlay.maxIdLength` (64). Methods `toMap()`, `validate()`.

### Overlay content

`sealed class OverlayContent` — subclasses:

| Class | Constructor |
|---|---|
| `ImageContent` | `const ImageContent(Uint8List bytes)` |
| `GifContent` | `const GifContent(Uint8List bytes)` |
| `TextContent` | `const TextContent(String text, {TextOverlayStyle style = const TextOverlayStyle()})` · `copyWith({text, style})` |
| `TickerContent` | `const TickerContent(String text, {TextOverlayStyle style = TickerContent.defaultStyle, double? speedPxPerSec, Duration? cycleDuration, bool loop = true, OverlayLength? loopGap, TickerDirection direction = TickerDirection.auto})` · `copyWith({text, style})` |
| `CarouselContent` | `const CarouselContent(List<CarouselItem> items, {Duration interval = const Duration(seconds: 5), CarouselTransition transition = const CarouselTransition()})` |

`TextOverlayStyle`:

| Field | Type | Default | Notes |
|---|---|---|---|
| `fontSizePx` | `double` | 32 | stream px, > 0 |
| `color` | `Color` | white | |
| `background` | `Color?` | `null` | filled behind text and padding |
| `paddingPx` | `double` | 8 | ≥ 0 |
| `fontTtf` | `Uint8List?` | `null` | TTF/OTF bytes; `null` = system font |
| `maxLines` | `int` | 1 | `TextContent` only; > 1 wraps |
| `align` | `TextOverlayAlign` | `start` | `start` · `center` · `end`; wrapped lines |

`TickerContent.defaultStyle` = white text on `Color(0xB3000000)`. `copyWith` on `TextOverlayStyle` can't clear
`background` / `fontTtf` to `null`.

`enum TickerDirection { auto, rtl, ltr }` — `rtl`: text moves right→left; `ltr`: left→right; `auto`: from the text.

Carousel types:

```dart
const CarouselItem(OverlayContent content /* ImageContent or GifContent */, {Duration? interval /* ≥ 500 ms */})
const CarouselTransition({CarouselTransitionType type = CarouselTransitionType.crossfade, int durationMs = 500,
    OverlayEasing easing = OverlayEasing.easeInOut, OverlayEdge edge = OverlayEdge.right})
const CarouselTransition.cut()
const CarouselTransition.crossfade({int durationMs = 500, OverlayEasing easing = OverlayEasing.easeInOut})
const CarouselTransition.push({OverlayEdge edge = OverlayEdge.right, int durationMs = 500, OverlayEasing easing = OverlayEasing.easeInOut})
enum CarouselTransitionType { cut, crossfade, push }
```

Constants `CarouselContent.maxItems` (20), `CarouselContent.minInterval` (500 ms).

### Placement types

```dart
const OverlayPlacement({OverlayLength? left, OverlayLength? right, OverlayLength? top, OverlayLength? bottom,
    OverlayLength? width, OverlayLength? height})   // copyWith(...), toMap(), validate()

sealed class OverlayLength
  const OverlayLength.percent(num value)  // → PercentLength, 0–100
  const OverlayLength.px(num value)       // → PxLength, stream pixels ≥ 0
```

### Animation types

```dart
const OverlayAnimation({OverlayAnimationType type = OverlayAnimationType.none, int durationMs = 400,
    OverlayEasing easing = OverlayEasing.easeOut, OverlayEdge edge = OverlayEdge.bottom})
const OverlayAnimation.slide({OverlayEdge edge = OverlayEdge.bottom, int durationMs = 400, OverlayEasing easing = OverlayEasing.easeOut})
const OverlayAnimation.pop({int durationMs = 400, OverlayEasing easing = OverlayEasing.easeOut})
const OverlayAnimation.curtain({int durationMs = 400, OverlayEasing easing = OverlayEasing.easeOut})
static const OverlayAnimation.none; static const int maxDurationMs = 5000;

enum OverlayAnimationType { none, slide, pop, curtain }
enum OverlayEasing { linear, easeIn, easeOut, easeInOut }   // cubic curves
enum OverlayEdge { left, right, top, bottom }
```

### `OverlayDurationUpdate`

```dart
const OverlayDurationUpdate.keep()           // unchanged (same as omitting duration)
const OverlayDurationUpdate.infinite()       // never expire
const OverlayDurationUpdate.of(Duration d)   // new total live duration, time already counted is kept
```

### Validation helpers

Top-level functions used by the controller, exported for app-side form validation. Each throws
`RtmpBroadcasterException`:

| Function | Throws |
|---|---|
| `validateOverlayId(String id)` | `OVERLAY_ID_RESERVED` (empty, > 64 chars, `scoreband`, `sponsor_…`) |
| `validateOverlayWeight(int weight)` | `OVERLAY_INVALID_PLACEMENT` (outside 0–100) |
| `validateOverlayDuration(Duration d)` | `OVERLAY_INVALID_CONTENT` (under 1 ms) |

`DynamicOverlay.validate()`, `OverlayContent.validate()`, `OverlayPlacement.validate()`, `TextOverlayStyle.validate()`
and `OverlayAnimation.validate(String field)` check a whole object the same way.

### `ZoomInfo`

| Field | Type | Description |
|---|---|---|
| `supported` | `bool` | `false` when the camera has no zoom control; then `min = max = current = 1.0` |
| `min`, `max` | `double` | Zoom ratio range (`min` < 1.0 possible on multi-lens phones) |
| `current` | `double` | Applied ratio |
| `source` | `ZoomSource` | `camera2` (phone camera, true ratios) · `uvc` (USB camera; ratios derived from the hardware range) |

`ZoomInfo.fromMap(map)`, value equality.

### `RtmpStatus`

| Field | Type | Set on |
|---|---|---|
| `type` | `RtmpStatusType` | always |
| `kbps` | `int?` | `bitrate` |
| `reason` | `String?` | `disconnected`; `overlayRemoved` (`removed` · `cleared` · `expired` · `completed`); `zoomChanged` (`reapplied` · `clamped` · `reset` · `cameraSwitched`) |
| `errorCode` | `String?` | `error`, `warning` |
| `errorMessage` | `String?` | `error`, `warning` |
| `reconnectAttempt` | `int?` | `reconnecting` (1–3) |
| `overlayId` | `String?` | `overlayShown`, `overlayHidden`, `overlayRemoved`, overlay warnings |
| `zoom` | `ZoomInfo?` | `zoomChanged` |

`RtmpStatus.fromMap(map)`. Unknown native event types become `error`.

### `RtmpStatusType`

| Value | Meaning |
|---|---|
| `connected` | RTMP handshake complete; you are live |
| `disconnected` | Connection closed (normal or not); auto-reconnect may follow |
| `error` | Failure with `errorCode` (e.g. `AUTH_ERROR`, `MAX_RECONNECT_EXCEEDED`) |
| `warning` | Non-fatal issue with `errorCode` (see [Warning codes](#warning-codes)) |
| `bitrate` | Current video bitrate in `kbps` |
| `reconnecting` | Auto-reconnect attempt `reconnectAttempt` |
| `usbDetached` | A USB device was unplugged |
| `overlayShown` | Dynamic overlay visible (enter finished) |
| `overlayHidden` | Dynamic overlay hidden (exit finished) |
| `overlayRemoved` | Dynamic overlay removed, with `reason` |
| `zoomChanged` | Zoom changed without `setZoom` (re-applied, clamped, reset, camera switched) |
| `previewBound` / `previewUnbound` | Internal; folded into `controller.previewBound`, never on `statusStream` |

### `UsbDeviceInfo` / `UsbAudioDeviceInfo`

| `UsbDeviceInfo` | Type | | `UsbAudioDeviceInfo` | Type |
|---|---|---|---|---|
| `deviceId` | `int` | | `deviceId` | `int` |
| `vendorId` | `int` | | `productName` | `String` |
| `productId` | `int` | | `type` | `int` (Android `AudioDeviceInfo` type) |
| `productName` | `String` | | | |
| `manufacturerName` | `String` | | | |
| `hasPermission` | `bool` | | | |

### `RtmpBroadcasterException`

```dart
const RtmpBroadcasterException(String code, String message)
```

Implements `Exception`. `code` is one of the [error codes](#error-codes).

---

## Error codes

Thrown as `RtmpBroadcasterException.code` by controller methods, or sent as `error` events (marked *event*).

**Setup and streaming**

| Code | Cause |
|---|---|
| `NO_CONTEXT` / `NO_ACTIVITY` | Plugin not attached to the Flutter engine / activity |
| `INVALID_URL` / `INVALID_KEY` | Empty `rtmpUrl` / `rtmpKey` in `configure()` |
| `INVALID_ARGS` | A required argument is missing |
| `INIT_PREVIEW_ERROR` | Camera, audio or USB source setup failed in `initPreview()` |
| `CONFIGURE_ERROR` | Encoder or source setup failed in `configure()` |
| `NOT_CONFIGURED` | `startStream()` before `configure()`, or `updateScoreband()` before `initPreview()`/`configure()` |
| `ALREADY_STREAMING` | `startStream()` while streaming |
| `STREAM_ERROR` | `startStream()` failed |
| `PREVIEW_NOT_READY` | `startStream()` before the pipeline is prepared (also an event) |
| `PREVIEW_NOT_BOUND` | `startStream()` before the preview surface is attached (also an event) |
| `USB_DEVICE_GONE` / `USB_PERMISSION_REVOKED` | USB camera unplugged / permission lost before `startStream()` (also events) |
| `STREAM_START_THREW` | *event* — the encoder threw while starting the stream |
| `PREVIEW_BIND_FAILED` | *event* — attaching the preview threw |
| `AUTH_ERROR` | *event* — the RTMP server rejected the stream key |
| `MAX_RECONNECT_EXCEEDED` | *event* — 3 reconnect attempts failed; stream stopped |
| `NO_MANAGER` / `NO_PREVIEW_VIEW` / `SURFACE_UNAVAILABLE` / `REBIND_PREVIEW_ERROR` | `rebindPreview()`: nothing initialized / no preview widget / surface not ready / rebind threw |

**Scoreband**

| Code | Cause |
|---|---|
| `OVERLAY_NOT_INITIALIZED` | Scoreband or overlay call before the pipeline exists (also an event) |
| `OVERLAY_DECODE_FAILED` | Scoreband, image or GIF bytes can't be decoded (also an event) |
| `UNKNOWN_LAYER` | Internal: unsupported scoreband layer id |

**Dynamic overlays and carousel**

| Code | Cause |
|---|---|
| `OVERLAY_ID_EXISTS` | `addOverlay()` with an id already in use (hidden overlays count) |
| `OVERLAY_NOT_FOUND` | Unknown id, or the overlay is already exiting for removal |
| `OVERLAY_ID_RESERVED` | Id empty, longer than 64, `scoreband`, or starts with `sponsor_` |
| `OVERLAY_LIMIT_REACHED` | More than 16 dynamic overlays (hidden ones count) |
| `OVERLAY_INVALID_CONTENT` | Empty bytes or text; font size ≤ 0; padding < 0; `maxLines` < 1; ticker speed and cycle both set, or ≤ 0; duration < 1 ms; animation or transition outside 0–5000 ms; carousel with 0 or > 20 items, a non-image/GIF item, an interval < 500 ms, or a transition not shorter than every interval |
| `OVERLAY_INVALID_PLACEMENT` | Negative length, percent > 100, weight outside 0–100 |
| `OVERLAY_GIF_TOO_LARGE` | GIF over 150 frames or 64 MB decoded |
| `OVERLAY_CAROUSEL_TOO_LARGE` | Carousel items over 64 MB decoded in total |
| `OVERLAY_FONT_INVALID` | `fontTtf` is empty or not a loadable TrueType/OpenType font |
| `OVERLAY_OPERATION_FAILED` | Unexpected native failure in an overlay call (see `exportDiagnostics()`) |

**Zoom**

| Code | Cause |
|---|---|
| `ZOOM_INVALID` | `setZoom()` level is NaN, infinite, ≤ 0, or missing |
| `ZOOM_NOT_READY` | Before `initPreview()`/`configure()`, or the camera is still opening — retry shortly |
| `ZOOM_UNSUPPORTED` | `setZoom(level ≠ 1.0)` on a camera without zoom control |
| `ZOOM_OPERATION_FAILED` | Unexpected native failure in a zoom call |

## Warning codes

Sent as `warning` events (`RtmpStatus.errorCode`). The stream keeps running.

| Code | Cause |
|---|---|
| `NO_OVERLAYS_AT_STREAM_START` | `startStream()` with no sponsors and no scoreband |
| `OVERLAY_FILTERS_LOST` | The GPU pipeline dropped overlay layers before the stream started; they were rebuilt |
| `SPONSOR_DECODE_FAILED` | At least one sponsor image couldn't be decoded and was skipped |
| `OVERLAY_DOWNSCALED` | A dynamic overlay was larger than the frame and was scaled down (`overlayId` set) |
| `STREAM_CONFIG_MISMATCH` | `configure()` fps/keyframe differ from `initPreview()`; the `initPreview()` values are kept |
| `ZOOM_REAPPLY_FAILED` | The kept zoom couldn't be applied again within 3 s after the camera reopened; it is retried on the next open |

Full wire contract: [docs/specs/channel-contract.md](docs/specs/channel-contract.md).

---

## Limits

| Limit | Value |
|---|---|
| Dynamic overlays | 16 at a time (hidden ones count) |
| Overlay id | 1–64 characters; `scoreband` and `sponsor_*` reserved |
| Weight | 0–100 |
| Percent lengths | 0–100 |
| Animation / carousel transition duration | 0–5000 ms |
| Overlay `duration` | ≥ 1 ms, or `null` |
| GIF | ≤ 150 frames, ≤ 64 MB decoded (width × height × 4 × frames) |
| Carousel | 1–20 image/GIF items, interval ≥ 500 ms, ≤ 64 MB decoded in total |
| Image decode | larger than 2048 px is subsampled |
| Text bitmap | larger than 4096 px is scaled down |
| Reconnect | 3 attempts, 3 s apart |
| Buffered events before the first listener | 32 |
| Preview widgets | 1 |

---

## Known limitations

| Limitation | Detail |
|---|---|
| iOS | Not implemented; the plugin is a scaffold. |
| Not yet device-verified | Zoom on USB (UVC) cameras. Everything else was verified on a phone on 2026-09-15. |
| `updateSponsors()` | Not implemented on Android. Sponsors are fixed after `configure()`; use dynamic overlays for changing logos. |
| Resolution / orientation while live | Choose before going live; servers drop the session on frame-size changes. |
| `usbDetached` | The unplugged `deviceId` is not exposed on `RtmpStatus`. |
| Warning extras | Extra warning fields (e.g. `requested` zoom, sponsor decode counts) are not exposed on `RtmpStatus`. |
| `RtmpBroadcastWidget.controller` | Accepted but unused. |
| `dispose()` | Doesn't stop the stream or release the camera; call `stopStream()` first. |
| Zoom sources | Phone cameras (Camera2) and UVC cameras with a zoom control only. UVC ratios are nominal (1–4×) when the camera reports a minimum of 0. |
| Emulator | No usable camera; use a physical device. |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not resolve com.github.pedroSG94.RootEncoder…` | Add JitPack to your app's repositories ([Android setup](#4-jitpack-repository)). |
| Overlays missing only in release builds, stream still works | R8 stripped RootEncoder: check custom ProGuard rules ([R8](#5-r8--proguard-release-builds)). |
| Black preview after returning to the app | Wait for `previewBound`; call `rebindPreview()` if it stays false ([App lifecycle](#app-lifecycle-and-preview-recovery)). |
| `previewBound` never becomes true | Nobody listens to `statusStream`; add a listener before `initPreview()`. |
| `PREVIEW_NOT_BOUND` on `startStream()` | The preview widget isn't on screen yet; show `RtmpBroadcastWidget` and wait for `previewBound`. |
| YouTube warns the bitrate is too low | Use a preset (4 / 10 Mbps) and pass the same `StreamConfig` to `initPreview()` and `configure()`; check `bitrate` events and your uplink. |
| Compile error "the type RtmpStatusType is not exhaustively matched" after an upgrade | New event types were added; add the cases or a `default`. |
| Compile error on a `switch` over `OverlayContent` | A new content type (e.g. `CarouselContent`) was added; add the case. |
| `ZOOM_NOT_READY` right after the preview appears | The camera opens asynchronously; retry after ~200 ms. |
| Long text overlay is tiny | Single-line text is scaled to fit; set `TextOverlayStyle(maxLines: 3)` and a placement `width`. |
| Ticker doesn't move | Tickers scroll only while live and shown. |
| Overlay doesn't disappear after its `duration` | Duration counts live and shown time only. |
| `OVERLAY_ID_EXISTS` for an id you just removed with animation | Wait for `overlayRemoved`, or remove with `animate: false` (re-adding an id that is still exiting also works). |
| Squares/tofu instead of Bangla, Arabic, … text | Pass a `fontTtf` that covers the script. |
| GIF from the gallery doesn't animate | Don't resize or recompress it when picking (e.g. `image_picker` without `maxWidth` / `imageQuality`). |
| Something else | `exportDiagnostics()` and look for `ERROR/<CODE>`. |

---

## Example app

`example/` is a complete app and the device test bed:

- Permission gate screen.
- Config screen: RTMP URL and key, resolution, orientation, bitrate (preset or custom), sponsor images from the gallery
  with position and layer weight, scoreband weight, diagnostics export.
- Go Live screen: camera preview, go live / stop, camera flip, mute, pinch zoom with a zoom slider and 1× / 2× / 5×
  presets, and a live scoreband with mock cricket data.
- **Overlay Studio** (layers button, top-right; works before and during a stream):
  - *Scenarios* with mock match data: wicket banner, boundary GIF, sponsor break, lower third, live score text,
    English / Bangla / Arabic tickers, sponsor carousels (crossfade, push, per-item interval), interrupt tests, layer
    order, 16-overlay limit, error codes, auto demo.
  - *Widget capture* scenarios: the Go Live screen's real scoreband widget captured to PNG and pushed as a dynamic
    overlay, then re-captured and swapped in place — see
    [Push a Flutter widget as an overlay](#push-a-flutter-widget-as-an-overlay).
  - *Build*: any overlay from every API option (image / GIF from samples or the gallery, text, ticker, carousel,
    placement in % or px, weight, duration, animations).
  - *Active*: re-weight, hide/show, change duration, replace content, move or remove each overlay; scoreband weight.
  - *Log*: overlay events, warnings and errors with timestamps.
  - HUD (top-left): OFFLINE / LIVE time / RECONNECTING, bitrate, overlay count, last event.

```sh
cd example
flutter pub get
flutter run   # physical Android device
```

---

## Architecture notes

- **Encoder:** [RootEncoder 2.7.2](https://github.com/pedroSG94/RootEncoder) `GenericStream` (not the deprecated `RtmpCamera2`).
- **Preview:** a `TextureView` fed by the encoder pipeline. There is no second camera session.
- **Layers (back → front):** camera frame, then every overlay ordered by weight. With default weights: sponsors (10) →
  scoreband (50) → dynamic overlays (50, later on top).
- **Sponsors and scoreband:** RootEncoder `ImageObjectFilterRender` filters.
- **Dynamic overlays:** drawn with Android `Canvas` into per-layer bitmaps and uploaded to their own GL filter. Frames
  are only produced while something moves (animation, ticker, GIF, carousel transition), at the stream's frame rate.
- **Zoom:** applied in the camera source (Camera2 zoom ratio / UVC hardware zoom) before GPU compositing, and
  re-applied after every camera reopen.
- **USB sources:** UVC cameras via libuvc, USB audio via `AudioRecord`.
- **Diagnostics:** rotating on-device log file.

---

## For contributors and AI agents

- Start with [CLAUDE.md](CLAUDE.md) (invariants, working agreements, commands) and [docs/README.md](docs/README.md)
  (docs index and "read by task" table).
- Contracts: [docs/specs/](docs/specs/) · design decisions: [docs/decisions/](docs/decisions/README.md) · progress:
  [docs/plans/](docs/plans/) (each active milestone has a "Resume here" box).
- Tests:

```sh
flutter analyze lib test && flutter test
(cd example && flutter test)
(cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest)   # Kotlin unit tests
```

- API, event or error code changes update this README, `docs/specs/channel-contract.md` and `CHANGELOG.md` in the
  same change.

---

## License

MIT — see [LICENSE](LICENSE).
