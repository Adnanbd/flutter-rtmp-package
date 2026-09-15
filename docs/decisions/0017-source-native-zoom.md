# 0017 — Camera zoom in the source, with verify and re-apply

- **Status:** Accepted (device-verified on phone cameras 2026-09-15; UVC camera zoom not yet verified)
- **Date:** 2026-09-15

## Context
Zoom is the most requested camera feature. The package owns the camera (invariant 1), so it must provide it.
Options:
1. GL crop/scale filter in front of the overlay filters — works for every source, but upscales the encoder-sized
   camera texture (soft image), and needs a pinned slot at index 0 of the layer stack.
2. Zoom in the camera source: Camera2 `CONTROL_ZOOM_RATIO` / `SCALER_CROP_REGION` through RootEncoder, UVC
   `CT_ZOOM_ABSOLUTE` through libuvc. The sensor crops at full resolution before scaling, so quality is better, and
   overlays are unaffected because zoom happens before GL.

RootEncoder 2.7.2 bytecode shows two traps: `Camera2ApiManager.setZoom` silently returns until the capture request
builder exists (async camera open), and `closeCamera` resets zoom to 1.0 without a later re-apply.

## Decision
Option 2.
- `ZoomController` (pure Kotlin) owns the requested level and is the only writer. It looks up a `ZoomTarget` for the
  current video source on every call (`Camera2ZoomTarget`, `UvcZoomTarget`).
- After every camera open (`bindPreview`) it waits for the source's zoom range, applies, reads back, and retries every
  100 ms for 3 s; failure is a loud `ZOOM_REAPPLY_FAILED` warning.
- `switchCamera` resets to 1.0 (ranges differ per camera) and announces the new range with `zoomChanged`.
- libuvc's zoom limits are protected fields: a tiny `UVCCamera` subclass exposes them (kept by R8).
- Host apps do gestures; the widget stays bare.

## Consequences
- No zoom for sources other than Camera2 and UVC (none exist today).
- UVC ratios are nominal when the hardware minimum is 0; UVC steps are 1 % of the hardware range.
- A Camera2 device whose maximum zoom is exactly 1.0 would report `ZOOM_NOT_READY` forever (not seen in practice).
- Relies on RootEncoder internals (`getZoom` reflecting a successful apply). Re-check on RootEncoder upgrades.
