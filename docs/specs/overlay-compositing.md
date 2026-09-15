# Spec — Overlay Compositing

**Source of truth (Android):** `android/.../overlay/OverlayFilterManager.kt`, `CameraStreamManager.kt`
(`reapplyOverlaysIfNeeded`, `startStream` recovery). iOS: not implemented — see
[../plans/ios.md](../plans/ios.md) M6.

## Invariants

1. All compositing is native. Flutter never sees overlays; only the encoded stream (and native preview) does.
2. Layer order comes from one `LayerStack` sorted by `(weight, classRank sponsor<scoreband<dynamic, seq)` (ADR 0014). Default weights (sponsors 10, scoreband 50, dynamic 50) give `camera → sponsor_0 … sponsor_N → scoreband → dynamic overlays`. All filter add/remove goes through `LayerStack` — never call `addFilter` directly.
3. Sponsors are static: sent in `configure()`, cached natively, re-applied after pipeline transitions.
4. Scoreband is push-only: the app calls `updateScoreband(png)` when data changes (~2–3 s). No timers in the package.
5. Dart geometry is relative to the post-rotation stream frame: integer percent for sponsors and the scoreband; percent or encoder px per field for dynamic overlays ([ADR 0018](../decisions/0018-dynamic-overlay-px-lengths.md)). Native converts using **encoder (post-rotation) dims**, never preview size.
6. Overlay failures must be loud: throw + `error` event, or `warning` event. Never silently skip.

## Android implementation notes (RootEncoder 2.7.2, verified empirically)

### Filter coordinate space is PRE-rotation
`ImageObjectFilterRender` draws on the camera-native frame **before** `setStreamRotation` rotates
it for the encoder:

```
camera frame (landscape) → [filters here] → setStreamRotation → encoder / preview
```

Overlay in the wrong corner, rotated 90°, or squashed ⇒ post-rotation math without the inverse transform.

### Units
`setScale(x, y)` and `setPosition(x, y)` are **0–100 % of the frame**, origin top-left. Not NDC, not 0–1.

### Portrait transform (`isPortrait = true`, stream rotation 270 = 90° CCW)
- Bitmap pre-rotated +90° CW (`orientBitmap`) before `setImage`.
- Post → pre mapping:

| Pre value | Formula |
|---|---|
| `pre.scaleX` | `post.scaleY` |
| `pre.scaleY` | `post.scaleX` |
| `pre.posX` | `100 − post.posY − post.scaleY` |
| `pre.posY` | `post.posX` |

Landscape: no bitmap rotation, pre == post.

Worked example: 1408×186 PNG, bottom-center on 720×1280 → post `scale=(90, 6.69) pos=(5, 89.31)` →
pre `scale=(6.69, 90) pos=(4.0, 5)`.

### Bitmaps are recycled on upload
RootEncoder's `TextureLoader.load` calls `Bitmap.recycle()` after uploading the texture. A bitmap passed to
`setImage` is dead afterwards. `OverlayFilterManager` therefore caches **encoded bytes** and each filter factory
decodes a fresh bitmap. Never cache and re-use a bitmap across `setImage` calls.

### Filter lifecycle — order matters
Always `setImage → setScale → setPosition → addFilter`. Adding before `setImage` can leave an unbound
texture that later `setImage` calls don't fix. Hence: **scoreband filter is lazily created on first
`updateScoreband`**; never preallocate placeholder bitmaps.

### Filters get dropped by pipeline transitions
RootEncoder's GL can lose filters across `startPreview` / resolution changes (observed at 1920×1080).
Mitigations:
- `CameraStreamManager` caches `lastSponsors` + `lastScoreband*`; `DynamicOverlayController` keeps dynamic overlays
  (rendered through `DynamicLayerFilter`, not `ImageObjectFilterRender.setImage`: [ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md), device-verified 2026-09-15).
- Every re-prepare builds a new `OverlayFilterManager` via `newOverlayFilterManager`, seeded with all three.
- `bindPreview` → `reapplyOverlaysIfNeeded` → `LayerStack.rebuild()` (clear + re-add all visible layers in order, fresh filters).
- `startStream` with `filtersCount() == 0` → emits `OVERLAY_FILTERS_LOST` warning and rebuilds before
  `genericStream.startStream`. Filters must exist before `startStream`.

### R8 / ProGuard
Release builds strip RootEncoder GL internals → every `addFilter` silently no-ops. `android/consumer-rules.pro`
keeps `com.pedro.**`, `com.flutterrtmp.broadcaster.rtmp.**`, `.overlay.**`, `com.serenegiant.**`. Do not
narrow without testing a real release/AAB build with overlays.

## Scoreband placement

Args from Dart: `width` 1–100, `x` 0–100, `y` 0–100 (all clamped).

```
scaleX = width
scaleY = (width/100) * (streamW/streamH) / bitmapAspect * 100    // aspect-derived height
posX   = (x/100) * (100 − scaleX)                                // x=0 left edge, 100 right edge, 50 centered
posY   = (y/100) * (100 − scaleY)
```
Then portrait transform above. Defaults `width=90, x=50, y=100` = bottom-center, 90 % wide.

## Sponsor placement

`SponsorPlacement` ints 0–100, percent of post-rotation stream frame.

**Size (BoxFit.contain):** try `finalW = width`, `finalH = width * frameAspect / bitmapAspect`.
If `finalH > height`: `finalH = height`, `finalW = height * bitmapAspect / frameAspect`.

**Position, per axis independently:**

| Given | Result |
|---|---|
| only `left` | image left edge at `left` % |
| only `right` | image right edge at `right` % from frame right |
| both or neither | centered |

Same for `top`/`bottom`. Positions clamped to `[0, 100 − size]`. Then portrait transform.

## Errors / warnings
`OVERLAY_NOT_INITIALIZED`, `OVERLAY_DECODE_FAILED`, `UNKNOWN_LAYER`, `SPONSOR_DECODE_FAILED`,
`OVERLAY_FILTERS_LOST`, `NO_OVERLAYS_AT_STREAM_START` — see [channel-contract.md](channel-contract.md#error-codes).

## Scoreband capture (app side)
`RepaintBoundary.toImage(pixelRatio: 2.0)` works off-screen (`bottom: -10000`) if opacity is non-zero at
capture time and `endOfFrame` is awaited when `debugNeedsPaint`. See `example/lib/score.band/` and README
"Scoreband Integration".

## Open questions for iOS (HaishinKit `ScreenObject`)
- Pre- or post-rotation space inside `MediaMixer`?
- Units of `ScreenObject` frame — pixels, 0–1, or %?
- Does HaishinKit auto-orient images, or do PNGs need pre-rotation like Android?
