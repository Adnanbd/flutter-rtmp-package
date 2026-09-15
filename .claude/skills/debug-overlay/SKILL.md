---
name: debug-overlay
description: Diagnose and fix sponsor, scoreband or dynamic overlay problems on the RTMP stream — overlay missing, in the wrong corner, rotated 90°, squashed or stretched, wrong stacking order, not animating, not expiring, ticker not scrolling, GIF or carousel stuck, visible in preview but not stream, vanishing in release/AAB builds, disappearing after resume/rotation/1080p, or SPONSOR_DECODE_FAILED / OVERLAY_* codes. Use when the user reports anything wrong with overlays, sponsors, logos, text, tickers, GIFs, carousels, or the scoreband.
---

# Debug overlays

Background: `docs/specs/overlay-compositing.md` (sponsors, scoreband, GL gotchas) and `docs/specs/dynamic-overlays.md`
(dynamic overlays, carousel). Read the relevant one fully first.
Code: `android/.../overlay/` (`OverlayFilterManager`, `LayerStack`, `DynamicOverlayController`, `DynamicLayerFilter`,
`OverlayVisuals`, `OverlayGeometry`), `camera/CameraStreamManager.kt`.

## 1. Collect evidence
- Platform. iOS has no overlays yet, so stop there.
- Build type (debug / release / AAB).
- `StreamConfig` (dims, orientation), `videoInput` (device / usb).
- Dynamic overlays: the exact calls and args (id, content type, placement units, weight, duration, enter/exit), live or offline, hidden or shown.
- Events from `statusStream`: `warning` / `error` codes, `overlayShown` / `overlayHidden` / `overlayRemoved` + `reason`. In the example: Overlay Studio **Log** tab and HUD.
- `controller.exportDiagnostics()` or `adb logcat -s OverlayFilterManager CameraStreamManager DiagLogger`.
  Key lines: `initLayers[add idx=…]`, `updateScoreband[create|update]`, `startStream: … filters=N`, `reapplyOverlays[…]`.

## 2. Sponsors and scoreband: symptom → cause

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Nothing renders in **release** only | R8 stripped RootEncoder GL | `android/consumer-rules.pro` keeps `com.pedro.**`, `.overlay.**`; test a real release build |
| `NO_OVERLAYS_AT_STREAM_START` | app never passed sponsors or pushed a scoreband | app-side |
| `OVERLAY_FILTERS_LOST` | GL dropped filters on transition (seen at 1080p) | auto rebuilt; if still missing, check that path builds via `newOverlayFilterManager` and calls `rebuild` |
| Missing after background/rotation | preview rebind didn't rebuild | `bindPreview` → `reapplyOverlaysIfNeeded` → `rebuild`; `previewBound` event arrived? |
| `SPONSOR_DECODE_FAILED` | HEIC / corrupt bytes | convert to PNG/JPG in app |
| `OVERLAY_DECODE_FAILED` | scoreband bytes not a PNG | `toByteData(format: png)` |
| `OVERLAY_NOT_INITIALIZED` | `updateScoreband` before `configure`/`initPreview` | call order |
| Wrong corner / rotated 90° | post-rotation math without pre-rotation transform, or `isPortrait` mismatch | `OverlayFilterManager(width, height, isPortrait)` must match `configureGlForOrientation` |
| Squashed / stretched | aspect uses wrong dims (preview vs encoder) or swapped scale axes | `streamWidth/Height` = encoder dims |
| Visible once, stale after `setImage` | filter added before `setImage` | order `setImage → setScale → setPosition → insert` |
| Sponsor above scoreband (or other wrong order) | weights / direct `addFilter` | defaults sponsor 10 < scoreband 50; all inserts via `LayerStack` |
| Scoreband capture blank | widget opacity 0 or not painted | await `endOfFrame`; opacity > 0 |
| Wrong only on UVC | UVC GL rotation constants | `configureGlForUvc`; see `docs/specs/orientation.md` |

## 3. Dynamic overlays: behavior that is by design

| Looks like a bug | Why | Spec |
|---|---|---|
| `duration` doesn't count before Go Live, during reconnects, after `stopStream`, while hidden | live time only | §5 |
| A paused overlay past its duration stays | it expires the moment it runs again | §5 |
| Ticker frozen before Go Live or while hidden | scrolls only while live and shown | §6 |
| Ticker still visible after its duration | finishes the current pass first | §6 |
| GIF / carousel keeps moving offline or hidden | wall clock since add / content update | §12 |
| `updateOverlay` doesn't re-animate | updates are instant swaps | §4 |
| Long single-line text is tiny | `maxLines: 1` scales to fit → set `maxLines > 1` | §2 |
| `OVERLAY_NOT_FOUND` for an overlay still on screen | it is exiting for removal | §4 |
| All dynamic overlays gone | a new `initPreview` drops them (rebind, orientation flip, `configure` keep them) | README Limits |

## 4. Dynamic overlays: real problems

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Wrong stacking | weight / class rank / seq, or a direct `addFilter` | `LayerStack` only; `LayerStackTest` |
| Frames keep running with nothing moving (CPU, battery) | a hidden or static entry still requests frames | `DynamicOverlayController.updateFrameRequest` (a dirty hidden entry caused this once, 2026-09-15) |
| Last animation frame lost / flicker | bitmap handoff race | `DynamicLayerFilter` publish/take; pool reuses a bitmap only when neither pending nor uploading (ADR 0015) |
| Missing in release only | R8 | `consumer-rules.pro` keeps `com.flutterrtmp.broadcaster.overlay.**` |
| `OVERLAY_DOWNSCALED` | content larger than the frame | placement `width`/`height`, px vs percent |
| `OVERLAY_DECODE_FAILED` / `OVERLAY_GIF_TOO_LARGE` / `OVERLAY_CAROUSEL_TOO_LARGE` / `OVERLAY_FONT_INVALID` | bytes or limits | spec §9, §10, §12 |
| Misplaced after an orientation flip with px | px keep their value against the new dims, clamped | ADR 0018 |
| Stutter / dropped encoder frames | per-frame uploads (ticker, GIF, curtain, carousel transition) scale with layer px | reduce layer size; fallback is a UV-offset shader (ADR 0015) |
| Tofu / broken Bangla or Arabic | font without the script | system font or a `fontTtf` that covers it |

## 5. Verify math by hand
Use the formulas in `overlay-compositing.md` with the real bitmap size and stream dims. The worked example there is
1408×186 on 720×1280 → pre `scale=(6.69, 90) pos=(4.0, 5)`. Compare against the logged `scale=` / `pos=`.
Dynamic placement: reproduce in `OverlayGeometryTest` (`placementRect`, percent/px, `fillBox` for carousels).

## 6. Fix rules
- Keep failures loud: throw + `error` event, or `warning`.
- Any new re-prepare path goes through `newOverlayFilterManager` and `rebuild`.
- Pure logic change → JVM test with the fake clock/scheduler in `android/src/test/kotlin/`.
- Record the non-obvious finding in the spec (dynamic overlays: §11 device results) — skill `sync-docs`.
- Device check: preview **and** the actual RTMP output (YouTube / VLC). Preview alone is not proof.
