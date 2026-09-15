# 0015 — CPU-composed dynamic layers on a non-recycling object filter

- **Status:** Accepted (device-verified 2026-09-15; replaced spikes S2–S4 as the starting design)
- **Date:** 2026-09-15

## Context
Dynamic overlays need text, GIF, a scrolling ticker and slide/pop/curtain animations (M11 P6–P8).
Device spikes S2–S4 were planned to choose between CPU and shader approaches, but the user asked to batch all
device testing at the end of M11, so a design had to be chosen from the RootEncoder 2.7.2 sources alone:

- `ImageObjectFilterRender.setImage` only stores the bitmap and sets `shouldLoad`; the GL thread uploads it in
  `drawFilter` via `TextureLoader.load`, which **recycles** the bitmap. Uploading per frame is supported
  (`releaseTexture` + reload) but would allocate a new bitmap every frame.
- `setImage` from the main thread races `drawFilter` on the GL thread: an update landing between the upload and
  `shouldLoad = false` is lost. Harmless for the scoreband, fatal for the last frame of an animation.
- The object fragment shader draws the texture only inside the sprite rect; there is no clip or UV offset.
- `BaseObjectFilterRender.textureLoader` is a protected, non-final field; `release()` never recycles bitmaps.

## Decision
- Dynamic overlays use their own filter, `DynamicLayerFilter : ImageObjectFilterRender`:
  - its `textureLoader` is replaced by one that uploads **without recycling**;
  - frames are handed over through a lock: the main thread publishes a bitmap, the GL thread takes it at the
    start of `drawFilter` and calls `setImage` itself, so no update is lost.
- Each dynamic layer renders into a small pool of up to 3 bitmaps owned by the host. A bitmap is reused only when
  it is neither pending nor being uploaded. Size = the layer's display rect in encoder px (post-rotation, swapped in
  portrait), so memory follows what viewers see, not the source resolution.
- All content is drawn with `Canvas`: image and text bitmaps scaled, GIF frames by timeline, ticker text at a scroll
  offset with repeated copies. Portrait orientation is a canvas matrix, not a rotated copy.
- Animations: slide and pop change `setPosition`/`setScale` per frame (no redraw). Curtain changes the rect width and
  redraws with a clip, so every content type supports it.
- Frames are driven by a `Choreographer` callback on the main thread, only while something animates, throttled to
  the encoder fps. Static layers redraw only when their state changes.
- Sponsors and the scoreband keep the existing `ImageObjectFilterRender` path (no behavior change).

## Consequences
- No shader code to maintain; works on any GLES2 device RootEncoder supports.
- Per-frame texture uploads for tickers, GIFs and curtain animations. Cost scales with layer px (e.g. a 1280×90 band
  is ~0.5 MB per upload). Check frame time on device (plan P7b/P8 device items); fallback is a UV-offset shader.
- `OVERLAY_ANIMATION_UNSUPPORTED` is not emitted by this design; the code stays reserved.
- Relies on RootEncoder internals (`textureLoader` field, `drawFilter` order). Re-check on RootEncoder upgrades.
