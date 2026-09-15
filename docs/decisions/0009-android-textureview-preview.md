# 0009 — Android: plain `TextureView` preview

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
`GenericStream` can attach preview to any `TextureView`/`SurfaceView` and manages the GL encoder surface internally.

## Decision
`CameraPreviewView` hosts a plain `TextureView`; bind on `onSurfaceTextureAvailable`, unbind on destroy/dispose.

## Consequences
- Preview lifecycle is tied to the SurfaceTexture; background/foreground requires rebind (`previewBound`/`previewUnbound` events, `rebindPreview`).
