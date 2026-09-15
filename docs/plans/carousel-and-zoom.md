# M12 Sponsor Carousel + M13 Camera Zoom (Android) — Tracking

> Contracts: [../specs/dynamic-overlays.md §12](../specs/dynamic-overlays.md#12-carousel-content) · [../specs/camera-zoom.md](../specs/camera-zoom.md)
> Decisions: [ADR 0016](../decisions/0016-carousel-dynamic-content.md), [ADR 0017](../decisions/0017-source-native-zoom.md)
> Branch: `feature/dynamic-overlays` (same branch as M11, user decision 2026-09-15) · Builds on [dynamic-overlays.md](dynamic-overlays.md) (M11)

## ▶ Resume here

| | |
|---|---|
| **Status** | **C0–C5 + Z1–Z5 done; device checks passed 2026-09-15 (user) except UVC camera zoom** (no USB camera tested; not a merge blocker, user 2026-09-15). `sync-docs` + `release-check` passed 2026-09-15. Code uncommitted — user commits manually. Kotlin 126 + Dart 50 + example 3 tests pass. |
| **Next step** | User commits, then merge to `main` on user approval. UVC camera zoom check (section E) after merge, when a USB camera is available. |
| **Blockers** | none |
| **Last updated** | 2026-09-15 |

**Resume protocol for agents:**
1. Read this box.
2. Read both specs.
3. `git status` (work is uncommitted; the user commits manually).
4. Continue at **Next step**.
5. Update this box and tick boxes as work lands.

---

## Decisions (agreed with user 2026-09-15)

| Topic | Decision |
|---|---|
| Platform | Android only (iOS plugin is a stub) |
| Branch | stay on `feature/dynamic-overlays`; device checks batched with M11 at the end |
| Carousel API | new dynamic overlay content type `CarouselContent` (reuses id, placement, weight, duration, enter/exit, hide/show/update/remove) |
| Transitions | `cut`, `crossfade`, `push` (in-slot, edge-selectable); duration + easing tweakable; global for the carousel |
| Carousel clock | wall clock since add / content update (like GIF) — rotates in preview, off-air and while hidden |
| Items | image + GIF only, 1–20 |
| Timing | global `interval` + optional per-item `interval` override |
| Item event | none |
| Zoom API | `getZoom()` + `setZoom(level)` returning `ZoomInfo`; host app does gestures |
| Zoom sources | Camera2 (phone) + UVC hardware zoom when the camera supports it |
| Zoom persistence | kept (clamped) across orientation flip / configure / preview rebind / reconnect; reset to 1.0 on `switchCamera` (`zoomChanged` event) |
| Example | Overlay Studio carousel scenarios + builder; pinch on preview + zoom slider/presets |
| UVC zoom check | not a merge blocker; run after merge when a USB camera is available (spec §8) |

Enforced in: contract [specs/dynamic-overlays.md §12](../specs/dynamic-overlays.md#12-carousel-content), [specs/camera-zoom.md](../specs/camera-zoom.md) ·
design ADR [0016](../decisions/0016-carousel-dynamic-content.md), [0017](../decisions/0017-source-native-zoom.md) ·
process ADR [0020](../decisions/0020-working-agreements-m11-m13.md) (Android only, shared branch, batched device checks) ·
`.claude/rules/android.md`, `example-app.md`.

---

## C0 — Contract & tracking docs
- [x] this tracking file
- [x] spec §12 carousel in `docs/specs/dynamic-overlays.md`
- [x] `docs/specs/camera-zoom.md`
- [x] ADR 0016 carousel as dynamic content · ADR 0017 source-native zoom
- [x] roadmap M12/M13, `docs/README.md`, CLAUDE.md active-work pointer

## C1 — Carousel timeline (pure Kotlin)
- [x] `overlay/CarouselTimeline.kt`: per-item intervals, transition window at slot end, loop, item clocks, next change
- [x] `CarouselTimelineTest`

## C2 — Carousel visual, decoder, parser
- [x] `CarouselVisual` (cut / crossfade / push, items contain-fit centered in slot)
- [x] decoder: items via image/GIF paths, aggregate memory cap
- [x] parser `type: carousel` + validation; parser tests
- [x] geometry: slot fills the placement box when width + height are given

## C3 — Controller scheduling
- [x] `DecodedContent.carousel` hook; frames only during transitions (or GIF items); one scheduler wake at next change
- [x] content epoch reset on carousel content update
- [x] controller tests (fake clock/scheduler)

## C4 — Dart API
- [x] `CarouselContent`, `CarouselItem`, `CarouselTransition` + validation + `toMap`; exports; tests

## C5 — Example
- [x] Overlay Studio scenarios: sponsor carousel (crossfade), push carousel, per-item override
- [x] Build tab carousel editor

## Z1 — ZoomController (pure)
- [x] `camera/ZoomController.kt` + `ZoomTarget`; clamp, verify + retry re-apply, give-up warning
- [x] `ZoomControllerTest`

## Z2 — Camera2 wiring
- [x] `Camera2ZoomTarget`; re-apply after initPreview / configure / orientation reinit / rebind / stream recovery; reset on switchCamera

## Z3 — UVC zoom
- [x] `UVCCamera` subclass exposing zoom range/support; `UvcZoomTarget`; ratio mapping

## Z4 — Channel + Dart
- [x] `getZoom` / `setZoom` handlers; `ZoomInfo`; `RtmpStatusType.zoomChanged`; error codes; tests; channel drift script

## Z5 — Example
- [x] pinch on preview, zoom slider + 1×/2×/5× chips, HUD readout

## E — End checks (batched with M11 device checks)
**Carousel (physical phone, output stream)** — passed 2026-09-15 (user)
- [x] crossfade, push from each edge, cut; per-item override timing
- [x] GIF item animates; enter/exit + hide/show on the carousel; update restarts at item 0
- [x] no frame driving between transitions (logcat), 720p/1080p, portrait/landscape, background → foreground

**Zoom**
- [x] pinch + slider smooth on stream; overlays not zoomed
- [x] zoom kept after background → foreground, stop/start, reconnect; reset on camera switch (event); front camera range
- [x] API < 30 device
- [ ] UVC camera with and without zoom control (no USB camera tested yet; not a merge blocker, user 2026-09-15)

**Finish**
- [x] `sync-docs`, `release-check` (2026-09-15)
- [ ] merge only on user approval

---

## Log
- 2026-09-15 — `sync-docs` + `release-check` for M11–M13: analyze clean (package), Dart 50 + example 3 + Kotlin 126 tests pass, channel drift check clean (23 methods), debug APK + release AAB build, R8 mapping keeps `overlay.**` and `ZoomableUvcCamera`. Fixed: `PROMPT.md` leaked into the pub package → added to `.pubignore`. User decision: UVC camera zoom check is not a merge blocker.
- 2026-09-15 — Device checks by user on a physical phone: all carousel and phone-camera zoom checks passed. Not tested: UVC (USB) camera zoom. Specs, ADR 0016/0017 status, README and CHANGELOG updated to "verified" except UVC zoom.
- 2026-09-15 — C0 + Z1–Z5 landed (uncommitted): `ZoomController` / `ZoomTargets` / `ZoomableUvcCamera`, `getZoom`/`setZoom` + `ZOOM_*` codes + `zoomChanged`, Dart `ZoomInfo`, example `ZoomControl` + pinch; specs, ADR 0016/0017, README, CHANGELOG synced. Audit after an interrupted session: Kotlin 126 + Dart 50 + example 3 tests pass, `flutter analyze lib test` clean, drift check passes. This tracking file was the only thing left stale.
- 2026-09-15 — C1–C5 (uncommitted). Findings / decisions:
  - Slot intrinsic size = tallest item height × widest item aspect; `fillBox` makes a width + height placement use the whole box (`OverlayGeometry.placementRect(fillBox)`).
  - Item clock starts when the item begins to appear (transition into it), so GIF items never jump at slot start.
  - Aggregate decoded-memory cap uses a **new code `OVERLAY_CAROUSEL_TOO_LARGE`** (plan said `OVERLAY_GIF_TOO_LARGE`; image-only carousels made that name misleading). Decode failure message names the item index (no extra key).
  - Static carousels arm one scheduler wake per transition/cut; frames run only during transitions, plus one final frame (`carouselWasActive`).
  - **M11 bug fixed:** a hidden overlay whose `dirty` flag was still set (e.g. add then hide before the first frame) kept Choreographer frames running forever. `updateFrameRequest` now ignores hidden entries.
  - Example: *Error codes* scenario now has 10 lines (carousel decode + 21 items).
- 2026-09-15 — Plan agreed. Findings (RootEncoder 2.7.2 bytecode): `Camera2ApiManager.setZoom` silently returns before the capture session exists; `closeCamera` resets `zoomLevel` and a new session never re-applies it → package re-applies and verifies. UVC lib (libuvc 3.2.0) `UVCCamera.setZoom(int)` takes percent of the hardware range; `mZoomMin/mZoomMax` are protected.
