# Spec — Camera Zoom (Android only)

> **Status: implemented; device-verified on phone cameras 2026-09-15. UVC camera zoom not yet verified (§8).** Contract agreed 2026-09-15.
> Progress: [../plans/carousel-and-zoom.md](../plans/carousel-and-zoom.md) · Decision: [ADR 0017](../decisions/0017-source-native-zoom.md)

The package owns the camera, so zoom is a package feature. Zoom happens **in the camera source**, before the GL
pipeline: preview and stream zoom together, overlays are never zoomed. iOS: not implemented (stub plugin).

## 1. Dart API

| Method | Channel | Returns | Notes |
|---|---|---|---|
| `Future<ZoomInfo> getZoom()` | `getZoom` | `ZoomInfo` | |
| `Future<ZoomInfo> setZoom(double level)` | `setZoom {level}` | applied `ZoomInfo` | clamped to `[min, max]`; safe to call on every pinch update |

```dart
class ZoomInfo {
  final bool supported;   // false: camera has no zoom control; min = max = current = 1.0
  final double min, max;  // zoom ratios; min can be < 1.0 on multi-lens phones (API 30+)
  final double current;
  final ZoomSource source; // camera2 | uvc
}
```

Event `RtmpStatusType.zoomChanged` (`RtmpStatus.zoom`, `RtmpStatus.reason`) is emitted only when the zoom changes
**without** a `setZoom` call:

| `reason` | When |
|---|---|
| `reapplied` | the camera re-opened (preview bind, background → foreground) and the kept zoom was applied again |
| `clamped` | the kept zoom was outside the re-opened camera's range and was clamped |
| `reset` | the source has no zoom control; the kept zoom went back to 1.0 |
| `cameraSwitched` | `switchCamera` changed the facing; zoom is 1.0, payload carries the new camera's range |

Gestures (pinch, slider) belong to the host app; `RtmpBroadcastWidget` stays a bare platform view.

## 2. Persistence

- The package keeps the requested zoom (after clamping).
- Kept across preview rebind, background → foreground, orientation flip, `configure` re-prepare, `stopStream`/`startStream`, reconnects.
- Reset to 1.0 on `switchCamera` when the facing actually changes.
- Lost on `release` (controller dispose).

## 3. Readiness and re-apply

RootEncoder 2.7.2 (`Camera2ApiManager`):
- `setZoom` silently returns until the capture request builder exists (the camera opens asynchronously after `startPreview`).
- `closeCamera` resets zoom to 1.0; a new session does not re-apply it.

So the package:
1. On `bindPreview` (every camera open) with a kept zoom ≠ 1.0: waits until the source reports a zoom range, applies,
   reads back with `getZoom()`, retries every 100 ms for up to 3 s. Success → `zoomChanged{reapplied|clamped}`.
   Timeout → warning `ZOOM_REAPPLY_FAILED` (the zoom stays requested for the next open).
2. `setZoom` while the range is known but the session is still being built: returns the state read back (1.0) and
   keeps applying in the background (same loop, emits `zoomChanged{reapplied}` when it lands).
3. `getZoom` / `setZoom` before any camera is open: `ZOOM_NOT_READY`. A Camera2 range of 1.0–1.0 counts as not ready.

## 4. Units

| Source | Values |
|---|---|
| Camera2 | true zoom ratio. API ≥ 30 and not LEGACY: `CONTROL_ZOOM_RATIO` (range from `CONTROL_ZOOM_RATIO_RANGE`); else `SCALER_CROP_REGION` from `1.0` to `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`. |
| UVC | hardware `CT_ZOOM_ABSOLUTE` range `[rawMin, rawMax]` (libuvc `UVCCamera`). Ratio = `raw / rawMin` when `rawMin > 0` (focal-length relative, per UVC spec); otherwise nominal `1.0 + 3.0 × (raw − rawMin) / (rawMax − rawMin)` (1–4×, not optical). libuvc sets zoom in 1 % steps of the hardware range. `rawMax ≤ rawMin` → `supported: false`. |

## 5. Wire format

- `getZoom` → no args.
- `setZoom` → `{level: double}`.
- Both return `{supported: bool, min: double, max: double, current: double, source: 'camera2'|'uvc'}`.
- Event: `{type: 'zoomChanged', reason: String, zoom: {…same map…}}`.

## 6. Errors & warnings

| Code | Kind | Cause |
|---|---|---|
| `ZOOM_INVALID` | method (+ Dart) | level NaN, infinite or ≤ 0; missing `level` |
| `ZOOM_NOT_READY` | method | no manager (before `initPreview`/`configure`), or camera still opening |
| `ZOOM_UNSUPPORTED` | method | `setZoom(level ≠ 1.0)` on a camera without zoom control |
| `ZOOM_OPERATION_FAILED` | method | unexpected native exception |
| `ZOOM_REAPPLY_FAILED` | warning | kept zoom not re-applied within 3 s after a camera open; extra key `requested` |

## 7. Implementation (Android)

- `camera/ZoomController.kt` — pure: requested level, readiness loop, events. `ZoomTarget` per source, looked up on every call.
- `camera/ZoomTargets.kt` — `Camera2ZoomTarget` (RootEncoder `Camera2Source`), `UvcZoomTarget` (`UvcVideoSource`).
- `usb/ZoomableUvcCamera.kt` — exposes libuvc's protected `mZoomMin`/`mZoomMax` (refreshed by `getZoom()`); kept by R8 rule.
- `CameraStreamManager`: `zoom.reapply` after `startPreview` in `bindPreview`; `zoom.onCameraSwitched` in `switchCamera`; `zoom.release` in `release`.

## 8. Device results

Device check 2026-09-15 (user, physical Android phone), passed: pinch and slider smooth on the stream, overlays not
zoomed; zoom kept after background → foreground, stop/start and reconnect; reset on camera switch with `zoomChanged`;
front camera range; API < 30 device.

**Not yet tested:** UVC camera with and without a zoom control (plan, section E). Not a merge blocker (user,
2026-09-15); checked after merge when a USB camera is available.
