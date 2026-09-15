---
name: orientation-change
description: Guide for changing portrait/landscape streaming behavior, GL rotation values, encoder dimensions, setAppOrientation, or reinitializeForOrientation — and for porting orientation to iOS. Use when the user reports sideways/rotated/letterboxed video, black or white preview after rotating, wrong output dimensions, or asks to support a new orientation, resolution, or camera type.
---

# Orientation changes

Contract: `docs/specs/orientation.md` — read it fully first. The GL values there were found empirically.
Don't "clean them up" without device proof.

## 1. Classify
- Phone camera (`Camera2Source`) → `configureGlForDeviceCamera`.
- UVC camera → `configureGlForUvc` (separate constants; phone path must stay untouched).
- Encoder dims wrong → `StreamConfig` preset, or `reinitializeForOrientation` dim swap.
- Preview-only glitch after rotation → widget is keyed on `MediaQuery.orientation`; check the `previewUnbound`/`previewBound` sequence.

## 2. Change
1. Edit only the one function for the camera type.
2. If overlays are affected, the `isPortrait` passed to `OverlayFilterManager` must match. See `docs/specs/overlay-compositing.md`.
3. `reinitializeForOrientation` must preserve: resolution class (720p/1080p), camera facing or UVC device, cached sponsors + scoreband.
4. Don't allow orientation or resolution changes while streaming (YouTube drops the session).

## 3. Device test matrix (both must pass before done)

| Case | Preview upright | Stream upright | Output dims | Overlays placed |
|---|---|---|---|---|
| Portrait 720p, back cam | | | 720×1280 | |
| Portrait 720p, front cam | | | 720×1280 | |
| Landscape 720p (device physically rotated) | | | 1280×720 | |
| Portrait ↔ landscape flip before live | | | swapped | |
| 1080p portrait + landscape | | | 1080×1920 / 1920×1080 | |
| UVC portrait + landscape (if USB touched) | | | | |

Check the stream in a real player (YouTube Studio preview or `ffprobe rtmp://…`) for dims.

## 4. Docs
Update the GL tables in `docs/specs/orientation.md` in the same commit (the code comment points there).
New empirical findings go in the spec. Run skill `sync-docs`.

## iOS port
Answer the open questions in `docs/specs/orientation.md#ios` on device first. Record HaishinKit
API names and values in the spec, then implement.
