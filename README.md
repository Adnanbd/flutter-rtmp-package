# flutter_rtmp_broadcaster

A Flutter plugin for live RTMP broadcasting with **native GPU-composited overlays** — sponsor images and a real-time scoreband — baked directly into the encoded video stream.

## Platform Support

| Android | iOS |
|---------|-----|
| ✅ Full | 🚧 Not implemented (scaffold stub) |

- **Min Android SDK:** 21
- **Min iOS:** 14.0

---

## Features

- Native camera ownership — no dependency on the `camera` package
- Hardware-accelerated overlay compositing via OpenGL ES (Android)
- Static sponsor image overlays (PNG/JPG, up to 3, positioned freely)
- Dynamic scoreband overlay — push updated PNG bytes at any time during a live stream
- Portrait and landscape streaming
- Front/back camera switching
- Audio mute/unmute
- Auto-reconnect (3 attempts, 3-second delay)
- Adaptive video bitrate — drops automatically when the uplink congests (Android)
- Status stream: connected, disconnected, bitrate, error, reconnecting events

---

## Installation

```yaml
dependencies:
  flutter_rtmp_broadcaster:
    path: ../flutter_rtmp_broadcaster   # or pub.dev version when published
```

---

## Android Setup

### 1. `android/app/build.gradle`

Ensure `minSdkVersion` is at least 21:

```groovy
android {
    defaultConfig {
        minSdkVersion 21
    }
}
```

### 2. `android/app/src/main/AndroidManifest.xml`

Add these permissions inside `<manifest>`, before `<application>`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- Required on Android 13+ if you let users pick sponsor images from gallery -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
```

### 3. Request Permissions at Runtime

The plugin does **not** request permissions itself. You must request them before calling `initPreview()`. Using [`permission_handler`](https://pub.dev/packages/permission_handler):

```dart
import 'package:permission_handler/permission_handler.dart';

Future<void> requestPermissions() async {
  await [Permission.camera, Permission.microphone].request();
}
```

### 4. JitPack Repository

The plugin depends on [RootEncoder](https://github.com/pedroSG94/RootEncoder) via JitPack. **You must add JitPack to your consumer app — Flutter does not propagate the plugin's own repository declarations.**

#### Newer Flutter projects (Gradle 7+, `settings.gradle.kts` with `dependencyResolutionManagement`)

Edit `android/settings.gradle.kts`:

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

#### Older projects (`android/build.gradle` with `allprojects`)

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

If JitPack is missing, the build fails with `Could not resolve com.github.pedroSG94.RootEncoder:library:2.7.2`.

### 5. ProGuard / R8 (Release / AAB Builds)

The plugin ships [`android/consumer-rules.pro`](android/consumer-rules.pro), which Gradle automatically merges into the consumer app's R8 config via `consumerProguardFiles`. **No manual ProGuard rules are required in the consumer app.**

If you have a custom `proguard-rules.pro` in the consumer app and overlays disappear in release builds (sponsor images and scoreband missing while stream still publishes), confirm your rules don't strip RootEncoder. Belt-and-suspenders rules to add to `android/app/proguard-rules.pro`:

```proguard
-keep class com.pedro.** { *; }
-keep interface com.pedro.** { *; }
-dontwarn com.pedro.**
```

Symptom of missing rules: stream connects, encoder runs, but the GPU overlay pipeline silently no-ops because R8 mangles `ImageObjectFilterRender` internals.

---

## iOS Setup

> **Note:** iOS RTMP streaming is not yet implemented. The plugin registers a camera preview view but does not broadcast. The setup below prepares for future implementation.

### `ios/Runner/Info.plist`

Add usage descriptions:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera is needed for live streaming.</string>
<key>NSMicrophoneUsageDescription</key>
<string>Microphone is needed for live streaming audio.</string>
<!-- If you let users pick sponsor images from the gallery -->
<key>NSPhotoLibraryUsageDescription</key>
<string>Select sponsor images to overlay on your stream.</string>
```

---

## Quick Start

```dart
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

class _MyStreamScreenState extends State<MyStreamScreen> {
  late final RtmpBroadcastController _controller;
  StreamSubscription<RtmpStatus>? _statusSub;
  bool _streaming = false;

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    _controller = RtmpBroadcastController();

    // 1. Init camera preview
    await _controller.initPreview(config: StreamConfig.youtube720Portrait);

    // 2. Configure RTMP endpoint + sponsors
    await _controller.configure(
      rtmpUrl: 'rtmp://a.rtmp.youtube.com/live2',
      rtmpKey: 'your-stream-key',
      config: StreamConfig.youtube720Portrait,
      sponsors: [
        SponsorOverlay(
          bytes: sponsorImageBytes,   // Uint8List — PNG or JPG
          placement: SponsorPlacement(left: 2, top: 2, width: 22, height: 8),
        ),
      ],
    );

    // 3. Listen to stream status
    _statusSub = _controller.statusStream.listen((status) {
      switch (status.type) {
        case RtmpStatusType.connected:
          setState(() => _streaming = true);
        case RtmpStatusType.disconnected:
          setState(() => _streaming = false);
        case RtmpStatusType.error:
          debugPrint('Error: ${status.errorCode} — ${status.errorMessage}');
        case RtmpStatusType.bitrate:
          debugPrint('Bitrate: ${status.kbps} kbps');
        case RtmpStatusType.reconnecting:
          debugPrint('Reconnecting: attempt ${status.reconnectAttempt}');
        case RtmpStatusType.warning:
          debugPrint('Warning: ${status.errorCode} — ${status.errorMessage}');
        default:
          break;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Camera preview fills the screen
          const Positioned.fill(child: RtmpBroadcastWidget()),

          // Your UI on top
          Positioned(
            bottom: 32,
            left: 0, right: 0,
            child: Center(
              child: ElevatedButton(
                onPressed: _streaming
                    ? () => _controller.stopStream()
                    : () => _controller.startStream(),
                child: Text(_streaming ? 'Stop' : 'Go Live'),
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
    _controller.dispose();
    super.dispose();
  }
}
```

---

## API Reference

### `RtmpBroadcastController`

| Method | Description |
|--------|-------------|
| `initPreview({StreamConfig? config})` | Initialize camera preview. Must be called before showing `RtmpBroadcastWidget`. |
| `configure({rtmpUrl, rtmpKey, sponsors, config})` | Set RTMP endpoint, sponsor overlays, and video config. |
| `startStream()` | Begin broadcasting. Requires prior `configure()` call. |
| `stopStream()` | Stop broadcasting. |
| `updateScoreband(Uint8List pngBytes, {int width = 90, int x = 50, int y = 100})` | Push a new scoreband PNG. `width` 1–100 (% of stream width), `x`/`y` 0–100 (0 = left/top edge, 100 = right/bottom edge). Defaults = bottom-center, 90% wide. Safe during a live stream. |
| `switchCamera({CameraFacing facing})` | Switch between `CameraFacing.front` and `CameraFacing.back`. |
| `setAudioMuted(bool muted)` | Mute or unmute microphone. |
| `setAppOrientation(VideoOrientation orientation)` | Change stream orientation. Reinitializes the encoder pipeline. |
| `updateSponsors(List<SponsorOverlay>)` | _(Not yet implemented on Android)_ Update sponsor images mid-stream. |
| `rebindPreview()` | Re-attach the preview to the on-screen view after background/foreground without re-initializing. Throws `NO_PREVIEW_VIEW` / `SURFACE_UNAVAILABLE` when there is nothing to bind yet. |
| `listUsbVideoDevices()` | _(Android)_ List attached UVC cameras → `List<UsbDeviceInfo>`. |
| `listUsbAudioDevices()` | _(Android 6+)_ List USB audio inputs → `List<UsbAudioDeviceInfo>`. |
| `requestUsbPermission(int deviceId)` | _(Android)_ Show the system USB permission dialog → `bool` granted. |
| `exportDiagnostics()` | _(Android)_ Return the on-device diagnostics log as text. Never throws. |
| `clearDiagnostics()` | _(Android)_ Delete the diagnostics log. |
| `dispose()` | Release resources. |

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `statusStream` | `Stream<RtmpStatus>` | Broadcast stream of RTMP status events (preview bind/unbind events are folded into `previewBound`). |
| `previewBound` | `ValueNotifier<bool>` | `true` while the native preview is attached to a surface. Goes `false` when the app is backgrounded or the view is disposed. |
| `config` | `StreamConfig` | Config passed to the last successful `configure()`. Throws before that. |

---

### `RtmpBroadcastWidget`

The camera preview. Place it wherever you want the camera feed visible.

```dart
const RtmpBroadcastWidget()
```

- Renders as a native platform view (Android: `TextureView`; iOS: `UIView`)
- No overlays are visible in Flutter — compositing is entirely native
- Only one instance per app is supported at a time

---

### `StreamConfig`

| Preset | Resolution | FPS | Video Bitrate | Orientation |
|--------|-----------|-----|---------------|-------------|
| `youtube720Portrait` _(default)_ | 720×1280 | 30 | 2.5 Mbps | Portrait |
| `youtube1080Portrait` | 1080×1920 | 30 | 4.5 Mbps | Portrait |
| `youtube720Landscape` | 1280×720 | 30 | 2.5 Mbps | Landscape |
| `youtube1080Landscape` | 1920×1080 | 30 | 4.5 Mbps | Landscape |

**Audio:** Always AAC, 44.1 kHz, 128 kbps.

Custom config:

```dart
const config = StreamConfig(
  width: 720,
  height: 1280,
  fps: 30,
  videoBitrate: 2_500_000,
  keyframeIntervalSeconds: 2,
  orientation: VideoOrientation.portrait,
  initialFacing: CameraFacing.back,
  // Optional external sources (Android):
  // videoInput: VideoInput.usb, usbVideoDeviceId: device.deviceId,
  // audioInput: AudioInput.usb, usbAudioDeviceId: mic.deviceId,
);
```

---

### `SponsorOverlay`

Static image composited onto the video frame at configure time.

```dart
SponsorOverlay(
  bytes: Uint8List,                 // PNG or JPG image bytes (HEIC not supported)
  placement: SponsorPlacement(
    left: 2,                        // image LEFT edge at 2% from frame left
    top: 2,                         // image TOP edge at 2% from frame top
    width: 22,                      // max box width  (% of stream width)
    height: 8,                      // max box height (% of stream height)
  ),
)
```

**`SponsorPlacement` rules** (all values are integers 0–100, percent of the final **post-rotation** frame):
- Horizontal: only `left` → left edge pinned; only `right` → right edge pinned `right%` from the frame's right; both or neither → centered.
- Vertical: same with `top` / `bottom`.
- Size: the image is scaled aspect-preserving to fit inside `width × height` (like `BoxFit.contain`).

**Recommended positions for three sponsors (top row):**

```dart
SponsorPlacement(left: 2,  top: 2, width: 22, height: 8)   // Left
SponsorPlacement(          top: 2, width: 22, height: 8)   // Center
SponsorPlacement(right: 2, top: 2, width: 22, height: 8)   // Right
```

`position: OverlayPosition(x, y, width, height)` (normalized 0.0–1.0) still works but is **deprecated**.

---

### `RtmpStatus`

| Field | Type | Present when |
|-------|------|-------------|
| `type` | `RtmpStatusType` | Always |
| `kbps` | `int?` | `bitrate` events |
| `reason` | `String?` | `disconnected` events |
| `errorCode` | `String?` | `error` and `warning` events |
| `errorMessage` | `String?` | `error` and `warning` events |
| `reconnectAttempt` | `int?` | `reconnecting` events |

#### `RtmpStatusType`

| Value | Meaning |
|-------|---------|
| `connected` | RTMP handshake complete, stream is live |
| `disconnected` | Stream stopped (normal or server-initiated) |
| `error` | Unrecoverable error — check `errorCode` |
| `bitrate` | Periodic bitrate report |
| `reconnecting` | Auto-reconnect attempt in progress |
| `warning` | Non-fatal issue — check `errorCode` (e.g. `SPONSOR_DECODE_FAILED`, `OVERLAY_FILTERS_LOST`, `NO_OVERLAYS_AT_STREAM_START`) |
| `usbDetached` | A USB device was unplugged (Android) |
| `previewBound` / `previewUnbound` | Internal; consumed by `controller.previewBound`, never emitted on `statusStream` |

---

### `RtmpBroadcasterException`

Thrown by any controller method when the native call fails (codes below). Some failures are also emitted as `error` events on `statusStream`.

```dart
try {
  await controller.configure(...);
} on RtmpBroadcasterException catch (e) {
  print('${e.code}: ${e.message}');
}
```

| Error Code | Cause |
|------------|-------|
| `NO_CONTEXT` | Plugin context unavailable |
| `NO_ACTIVITY` | Activity reference lost |
| `INIT_PREVIEW_ERROR` | Camera or audio prepare failed |
| `CONFIGURE_ERROR` | Stream encoder configuration failed |
| `NOT_CONFIGURED` | `startStream()` called before `configure()` |
| `ALREADY_STREAMING` | `startStream()` called while already streaming |
| `STREAM_ERROR` | Generic streaming failure |
| `INVALID_URL` | Empty RTMP URL |
| `INVALID_KEY` | Empty stream key |
| `INVALID_ARGS` | Required argument missing |
| `PREVIEW_NOT_READY` / `PREVIEW_NOT_BOUND` | `startStream()` before the pipeline is prepared / the preview surface is bound |
| `NO_MANAGER` / `NO_PREVIEW_VIEW` / `SURFACE_UNAVAILABLE` / `REBIND_PREVIEW_ERROR` | `rebindPreview()` failures |
| `OVERLAY_NOT_INITIALIZED` | `updateScoreband()` before `initPreview()`/`configure()` |
| `OVERLAY_DECODE_FAILED` | Scoreband bytes are not a decodable image |
| `UNKNOWN_LAYER` | Unsupported overlay layer id |
| `USB_DEVICE_GONE` / `USB_PERMISSION_REVOKED` | USB camera unplugged or permission lost before `startStream()` |
| `PREVIEW_BIND_FAILED` / `STREAM_START_THREW` | Native preview/stream start threw (event only) |
| `AUTH_ERROR` | RTMP server rejected authentication (event only) |
| `MAX_RECONNECT_EXCEEDED` | All 3 auto-reconnect attempts failed (event only) |

Full contract: [docs/specs/channel-contract.md](docs/specs/channel-contract.md).

---

## Scoreband Integration

The scoreband is a full-width dynamic overlay anchored to the bottom of the frame. Push PNG bytes whenever score data changes:

```dart
import 'dart:ui' as ui;
import 'package:flutter/rendering.dart';

final _scoreBandKey = GlobalKey();

// Render the widget off-screen (invisible to user, capturable by RepaintBoundary)
Positioned(
  bottom: -10000,   // off-screen
  left: 16, right: 16,
  child: RepaintBoundary(
    key: _scoreBandKey,
    child: MyScoreBandWidget(score: _score),
  ),
)

// Push an updated image to the native layer
Future<void> _pushScoreband() async {
  final ctx = _scoreBandKey.currentContext;
  if (ctx == null) return;

  final boundary = ctx.findRenderObject() as RenderRepaintBoundary;
  if (boundary.debugNeedsPaint) {
    await WidgetsBinding.instance.endOfFrame;
  }

  final image = await boundary.toImage(pixelRatio: 2.0);
  final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
  if (byteData == null) return;

  await controller.updateScoreband(byteData.buffer.asUint8List());
}
```

Call `_pushScoreband()` after every score update. The native layer swaps the overlay texture in real-time without interrupting the stream.

**Note:** If the scoreband widget uses `ConsumerWidget` (Riverpod) or other inherited widget dependencies, ensure your app is wrapped in `ProviderScope` (or the appropriate inherited widget ancestor) in `main.dart`.

---

## Orientation Handling

### Portrait (default)

```dart
await controller.configure(config: StreamConfig.youtube720Portrait, ...);
await controller.setAppOrientation(VideoOrientation.portrait);
```

Device stays upright. Stream output: 720×1280.

### Landscape

```dart
await controller.configure(config: StreamConfig.youtube720Landscape, ...);
await controller.setAppOrientation(VideoOrientation.landscape);
```

`setAppOrientation(landscape)` locks the UI to landscape orientation. The user **must physically rotate the device** — sensor auto-rotate must be off. Stream output: 1280×720.

### Restore portrait on back navigation

Call this before `Navigator.pop()` from the stream screen:

```dart
await SystemChrome.setPreferredOrientations([
  DeviceOrientation.portraitUp,
  DeviceOrientation.portraitDown,
]);
```

---

## Auto-Reconnect

If the RTMP connection drops unexpectedly (network hiccup, server timeout), the plugin retries up to **3 times** with a **3-second delay** between each attempt.

- Each attempt fires `RtmpStatusType.reconnecting` with `reconnectAttempt` set to the attempt number (1–3)
- If all attempts fail, `RtmpStatusType.error` fires with `errorCode: "MAX_RECONNECT_EXCEEDED"`
- Auto-reconnect is **disabled** when you call `stopStream()` explicitly

On Android the retry is performed by RootEncoder's own stream client (`StreamBaseClient.reTry`), so the connection is re-established in place — the encoder, preview, and overlay filters are never torn down.

---

## Adaptive Bitrate (Android)

The video bitrate you pass to `configure()` is a **ceiling**, not a fixed rate. While
streaming, the plugin watches the RTMP sender cache; when the uplink cannot keep up it
steps the video bitrate down (`setVideoBitrateOnFly`) and raises it again as headroom
returns. This prevents the failure mode where the send cache saturates, frames are
discarded, and the server eventually closes the connection.

Practical consequences:

- `RtmpStatusType.bitrate` events will vary during a stream — that is expected, not an error
- Audio bitrate is unaffected (128 kbps AAC)
- The rate never exceeds the `videoBitrate` you configured

---

## Architecture Notes

### Android

- **Encoder:** [RootEncoder 2.7.2](https://github.com/pedroSG94/RootEncoder) via `GenericStream` (the `RtmpCamera2` API is deprecated and not used)
- **Preview:** Plain `TextureView` in a `FlutterPlatformView`. `GenericStream.startPreview(textureView)` attaches the encoder's preview output — no separate `CameraController` or second camera session is opened
- **Compositing:** `ImageObjectFilterRender` (OpenGL ES, GPU-accelerated) — one filter instance per layer, registered via `genericStream.getGlInterface().addFilter()`
- **Layer order (bottom → top):** Camera frame → Sponsor_0 → Sponsor_1 → Sponsor_2 → Scoreband
- **External sources:** UVC cameras and USB audio via `libuvc` (`VideoInput.usb` / `AudioInput.usb`)
- **Diagnostics:** rotating on-device log, readable with `exportDiagnostics()`

Deeper docs: [architecture](docs/architecture/overview.md) · [specs](docs/specs/) · [design decisions](docs/decisions/README.md).

### iOS

- HaishinKit 2.x is declared as a pod dependency for future use ([target design](docs/architecture/ios.md))
- Current implementation: `flutter create` stub — the plugin channels and preview view are not registered yet

---

## Known Limitations

| Limitation | Detail |
|-----------|--------|
| iOS | Not implemented — plugin is a scaffold stub |
| USB sources, diagnostics, adaptive bitrate | Android only |
| `updateSponsors()` mid-stream | Not yet implemented on Android — sponsor changes require stopping and reconfiguring |
| Simulator | Android emulator has no camera; physical device required |
| Multiple widgets | Only one `RtmpBroadcastWidget` per running app supported |

---

## Example App

The `example/` directory contains a complete demo with:

- Permission gating screen
- RTMP URL + stream key input
- Resolution (720p / 1080p) and orientation (portrait / landscape) selection
- Sponsor image upload from gallery (up to 3, with Left / Middle / Right position selector)
- Camera controls: flip camera, go live / stop, mute / unmute
- Live scoreband overlay (updates every 3 seconds with mock cricket scorecard data)

```sh
cd example
flutter pub get
flutter run
```

---

## License

MIT — see [LICENSE](LICENSE).
