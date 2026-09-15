# 0001 — Package owns the camera; single session end-to-end

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
Compositing overlays into the encoded stream requires native access to camera frames. A Flutter
`camera` package session would compete for the same device.

## Decision
The package owns the camera 100%. Preview is an output of the same native pipeline the encoder uses
(Android: `GenericStream.startPreview(textureView)`; iOS: `AVCaptureVideoPreviewLayer` on `MediaMixer`'s session).
No second capture session is ever opened.

## Consequences
- Host apps must not use `camera` / `CameraController` alongside this package.
- `RtmpBroadcastWidget` shows only the camera preview; overlays are never visible in Flutter.
