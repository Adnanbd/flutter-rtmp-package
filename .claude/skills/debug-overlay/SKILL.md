---
name: debug-overlay
description: Diagnose and fix sponsor or scoreband overlay problems on the RTMP stream — overlay missing, in the wrong corner, rotated 90°, squashed or stretched, visible in preview but not stream, vanishing in release/AAB builds, disappearing after resume/rotation/1080p, or SPONSOR_DECODE_FAILED / OVERLAY_* codes. Use when the user reports anything wrong with overlays, sponsors, logos, or the scoreband.
---

# Debug overlays

Background: `docs/specs/overlay-compositing.md` (read fully first). Code: `android/.../overlay/OverlayFilterManager.kt`, `CameraStreamManager.kt`.

## 1. Collect evidence
- Platform. iOS has no overlays yet, so stop there.
- Build type (debug / release / AAB).
- `StreamConfig` (dims, orientation), `videoInput` (device / usb).
- Events from `statusStream`: `warning` and `error` codes.
- `controller.exportDiagnostics()` or `adb logcat -s OverlayFilterManager CameraStreamManager DiagLogger`.
  Key lines: `initLayers[add idx=…]`, `updateScoreband[create|update]`, `startStream: … filters=N`, `reapplyOverlays[…]`.

## 2. Match symptom → cause

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Nothing renders in **release** only | R8 stripped RootEncoder GL | `android/consumer-rules.pro` keeps `com.pedro.**`; test a real release build |
| `NO_OVERLAYS_AT_STREAM_START` | app never passed sponsors or pushed a scoreband | app-side |
| `OVERLAY_FILTERS_LOST` | GL dropped filters on transition (seen at 1080p) | auto re-applied; if still missing, check `lastSponsors`/`lastScoreband*` are set on that code path |
| Missing after background/rotation | preview rebind didn't re-apply | `bindPreview` → `reapplyOverlaysIfNeeded`; `previewBound` event arrived? |
| `SPONSOR_DECODE_FAILED` | HEIC / corrupt bytes | convert to PNG/JPG in app |
| `OVERLAY_DECODE_FAILED` | scoreband bytes not a PNG | `toByteData(format: png)` |
| `OVERLAY_NOT_INITIALIZED` | `updateScoreband` before `configure`/`initPreview` | call order |
| Wrong corner / rotated 90° | post-rotation math without pre-rotation transform, or `isPortrait` mismatch | `OverlayFilterManager(width, height, isPortrait)` must match `configureGlForOrientation` |
| Squashed / stretched | aspect uses wrong dims (preview vs encoder) or swapped scale axes | `streamWidth/Height` = encoder dims |
| Visible once, stale after `setImage` | filter added before `setImage` | order `setImage → setScale → setPosition → addFilter` |
| Scoreband capture blank | widget opacity 0 or not painted | await `endOfFrame`; opacity > 0 |
| Wrong only on UVC | UVC GL rotation constants | `configureGlForUvc`; see `docs/specs/orientation.md` |

## 3. Verify math by hand
Use the formulas in the spec with the real bitmap size and stream dims. The worked example there is
1408×186 on 720×1280 → pre `scale=(6.69, 90) pos=(4.0, 5)`. Compare against the logged `scale=` / `pos=`.

## 4. Fix rules
- Keep failures loud: throw + `error` event, or `warning`.
- Any new re-prepare path must rebuild `OverlayFilterManager` with the cached sponsors and scoreband.
- After fixing, record the non-obvious finding in `docs/specs/overlay-compositing.md` (skill `sync-docs`).
- Device check: preview **and** the actual RTMP output (YouTube / VLC). Preview alone is not proof.
