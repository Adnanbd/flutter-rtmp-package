# M11 — Dynamic Overlays (Android) — Tracking

> Contract: [../specs/dynamic-overlays.md](../specs/dynamic-overlays.md) · Decisions: [ADR 0014](../decisions/0014-layer-stack-weights.md)
> Branch: `feature/dynamic-overlays` · Original request: `PROMPT.md` (untracked, repo root)

## ▶ Resume here

| | |
|---|---|
| **Status** | P0, P2 done. `OverlayGeometry` extracted; legacy math proven identical by exact-float oracle tests |
| **Next step** | P3 LayerStack (pure logic + GL wrapper, testable without device). P1 spikes still pending: need a **physical** Android device (only an emulator was attached on 2026-09-15). Run them before P6–P8. |
| **Blockers** | none |
| **Last updated** | 2026-09-15 |

**Resume protocol for agents:**
1. Read this box.
2. Read the spec.
3. `git log --oneline main..feature/dynamic-overlays`.
4. Continue at **Next step**.
5. Update this box and tick boxes in the same commit as the work.

---

## Decisions (agreed with user 2026-09-15)

| Topic | Decision |
|---|---|
| Platform | Android only |
| px unit | encoder output px, mixable with percent per field |
| Duration | counts only while RTMP connected and overlay VISIBLE; survives stop/start |
| Update | instant swap, timer kept; `restartTimer` flag |
| Hide / remove | both; hide pauses timer + ticker |
| Animations | slide, pop, curtain (center-out reveal); no fade; type + durationMs + easing + edge |
| Content | image, GIF, single-line text with background + TTF, ticker |
| Ticker | text flattened to one line; speed px/s or cycle duration; loop or once (once → `completed`); auto RTL; duration expiry finishes the pass; scroll only while live |
| Weights | sponsors 10, scoreband 50, dynamic 50; tie: sponsor < scoreband < dynamic, then later on top |
| IDs | app-provided; duplicate → error; `scoreband`, `sponsor_*` reserved |
| Limits | 16 overlays; GIF ≤150 frames and ≤64 MB decoded; oversize images contained + warning |
| Legacy | only optional `weight` added |

---

## P0 — Contract & tracking docs
- [x] `docs/specs/dynamic-overlays.md`
- [x] this tracking file
- [x] ADR 0014 layer stack + weights
- [x] roadmap M11 entry, docs index, CLAUDE.md pointer

## P1 — Device spikes (throwaway code on `spike/*` branches; results → spec §11 + ADRs)
- [ ] S1 `addFilter(index)` ordering with queued ops, rapid add/remove, rebuild after background/foreground
- [ ] S2 curtain: CPU mask + `setImage` per frame (1280×200 @ 30 fps) — frame drops / GC? else custom clip shader → ADR 0015
- [ ] S3 GIF memory + frame advance; `setPosition`/`setScale` per-frame smoothness at 1080p
- [ ] S4 ticker: CPU window vs UV-offset shader at 120 px/s, 1280×90 band; Choreographer vs 30 fps encoder judder; long-paragraph tiling (GL max texture size) → ADR 0016

## P2 — Geometry extraction (no behavior change)
- [x] `overlay/OverlayGeometry.kt` pure Kotlin: contain + anchors + pre-rotation transform + `Length` (percent/px) + `placementRect` for dynamic overlays
- [x] JVM tests (`android/src/test/kotlin/.../overlay/OverlayGeometryTest.kt`, 14 tests): verbatim legacy formulas as oracle, exact float equality over a grid of frames × orientations × bitmaps × params; spec worked example; px/percent/contain/anchor/downscale cases
- [x] `OverlayFilterManager` uses it (`applySponsorPosition`, `updateScoreband`)
- [ ] device visual check sponsor + scoreband unchanged — folded into P3 device check (math already proven identical)
- [x] removed stale `flutter create` Kotlin test that broke `testDebugUnitTest` compilation

## P3 — LayerStack + legacy weights
- [ ] `overlay/LayerStack.kt` keyed `(weight, classRank, seq)`; `insert` / `remove` / `reorder` / `rebuild`
- [ ] GL interface wrapper for testability; ordering unit tests
- [ ] sponsors + scoreband migrated; `rebuild` replaces `reapplyOverlaysIfNeeded` and the recovery paths
- [ ] Dart: `SponsorPlacement.weight`, `updateScoreband(weight:)`; payload tests
- [ ] Kotlin: parse `weight` with defaults
- [ ] device: default order identical to today

## P4 — Dynamic image overlays (instant, no timer)
- [ ] Dart models: `DynamicOverlay`, `OverlayContent` (image only wired), `OverlayPlacement`, `OverlayLength`, `OverlayAnimation`, `OverlayDurationUpdate`; barrel export; `toMap` tests
- [ ] Dart validation + controller methods + bridge; `RtmpStatusType` overlay events + `RtmpStatus.overlayId`
- [ ] Kotlin: `DynamicOverlayController` (state machine without animation), plugin handlers, errors, limits, events
- [ ] survives orientation flip, `configure` re-prepare, background/foreground
- [ ] example app minimal hook
- [ ] channel drift script passes

## P5 — Live-only duration timer
- [ ] `OverlayTimer` with injectable clock; unit tests: pre-live freeze, reconnect pause, stop/start resume, hide pause, `restartTimer`, duration update
- [ ] live state wired from `RtmpConnectChecker` / `stopStream`
- [ ] expiry → `overlayRemoved{expired}`

## P6 — Text content
- [ ] `content/TextBitmapRenderer.kt`: Paint, background, padding, TTF via temp file
- [ ] in-place text/style update; `OVERLAY_FONT_INVALID`
- [ ] device: English + Bangla + Arabic rendering

## P7 — GIF content
- [ ] `content/GifLayer.kt`: caps, `OVERLAY_GIF_TOO_LARGE`, bytes kept for rebuild
- [ ] device: memory profile, loop correctness after rebuild

## P7b — Ticker content
- [ ] `content/TickerLayer.kt` per S4 result
- [ ] speed / cycleDuration, loop + loopGap, play-once → `completed`, finish-pass-on-expiry
- [ ] auto/rtl/ltr direction via `Bidi`
- [ ] text update resets offset; style-only keeps offset
- [ ] pure-Kotlin tests: offset math, pass length, direction, completion/expiry
- [ ] device: English / Bangla / Arabic paragraphs, loop vs once, 720p/1080p, portrait/landscape, freezes while not live

## P8 — Animations
- [ ] `OverlayAnimator`: slide / pop / curtain × easing; enter + exit
- [ ] interruption rules (spec §4)
- [ ] curtain fallback warning where unsupported
- [ ] device frame-time check

## P9 — Example app playground
- [ ] screen: add image / GIF / text / ticker; weight slider; duration; animation pickers; list with hide / show / remove / update

## P10 — Docs sync + release check
- [ ] spec DRAFT banner removed; `channel-contract.md`, `dart-api.md`, `overlay-compositing.md` updated
- [ ] README API + error tables, CHANGELOG
- [ ] roadmap M11 ticked; `sync-docs` + `release-check` skills
- [ ] merge to main **only on user approval**

---

## Log
- 2026-09-15 — P2: geometry extraction. Run Kotlin tests: `cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest` (first run needs network for mockito). `placementRect` downscales any oversize result (not only intrinsic) — spec §2 updated.
- 2026-09-15 — P0: contract, tracking, ADR 0014 written after 5 rounds of requirement Q&A.
