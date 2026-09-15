# M11 — Dynamic Overlays (Android) — Tracking

> Contract: [../specs/dynamic-overlays.md](../specs/dynamic-overlays.md) · Decisions: [ADR 0014](../decisions/0014-layer-stack-weights.md), [ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md)
> Branch: `feature/dynamic-overlays` · Original request: `PROMPT.md` (untracked, repo root)

## ▶ Resume here

| | |
|---|---|
| **Status** | **P2–P9 done and device-verified 2026-09-15** (user, physical Android phone, output stream): every End-of-M11 check passed. Only exception: orientation flip / re-`configure` with overlays, which the example UI can't reach (unit tests cover it). **P10 done: merged to `main` 2026-09-15** with M12/M13 (user approval; not published to pub.dev). |
| **Next step** | none — M11 complete. Follow-ups tracked in [carousel-and-zoom.md](carousel-and-zoom.md) (UVC zoom) and [ios.md](ios.md) (iOS parity). |
| **Blockers** | none |
| **Last updated** | 2026-09-15 |

**Resume protocol for agents:**
1. Read this box.
2. Read the spec.
3. `git status` (work is uncommitted; the user commits manually).
4. Continue at **Next step**.
5. Update this box and tick boxes in the same change as the work.

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

Enforced in: contract [specs/dynamic-overlays.md](../specs/dynamic-overlays.md) · design ADR [0014](../decisions/0014-layer-stack-weights.md),
[0015](../decisions/0015-cpu-composed-dynamic-layers.md), [0018](../decisions/0018-dynamic-overlay-px-lengths.md) (px) ·
process ADR [0020](../decisions/0020-working-agreements-m11-m13.md) (Android only, batched device checks, manual commits, Overlay Studio) ·
bitrate fix ADR [0019](../decisions/0019-youtube-preset-bitrates.md) · `.claude/rules/dart-api.md`, `android.md`, `example-app.md`.

---

## P0 — Contract & tracking docs
- [x] `docs/specs/dynamic-overlays.md`
- [x] this tracking file
- [x] ADR 0014 layer stack + weights
- [x] roadmap M11 entry, docs index, CLAUDE.md pointer

## P1 — Device spikes — superseded 2026-09-15
No spikes: the user batched all device testing at the end of M11. Design chosen from RootEncoder 2.7.2 sources →
[ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md). The spike questions (S1 ordering, S2 curtain cost, S3 GIF
memory/smoothness, S4 ticker judder) became items in **End-of-M11 device checks** (all passed 2026-09-15).

## P2 — Geometry extraction (no behavior change)
- [x] `overlay/OverlayGeometry.kt` pure Kotlin: contain + anchors + pre-rotation transform + `Length` (percent/px) + `placementRect` for dynamic overlays
- [x] JVM tests (`android/src/test/kotlin/.../overlay/OverlayGeometryTest.kt`, 14 tests): verbatim legacy formulas as oracle, exact float equality over a grid of frames × orientations × bitmaps × params; spec worked example; px/percent/contain/anchor/downscale cases
- [x] `OverlayFilterManager` uses it (`applySponsorPosition`, `updateScoreband`)
- [x] device visual check sponsor + scoreband unchanged (2026-09-15)
- [x] removed stale `flutter create` Kotlin test that broke `testDebugUnitTest` compilation

## P3 — LayerStack + legacy weights
- [x] `overlay/LayerStack.kt` keyed `(weight, classRank, seq)`; `put` / `attach` / `detach` / `remove` / `setWeight` / `replaceFilter` / `rebuild`
- [x] `FilterSink` interface + `GlFilterSink`; `LayerStackTest` (16 tests, fake sink simulating index ops)
- [x] sponsors + scoreband migrated (`OverlayFilterManager` rewritten); `reapplyOverlaysIfNeeded` and startStream recovery now call `rebuild`; all re-prepare paths go through `CameraStreamManager.newOverlayFilterManager`
- [x] Dart: `SponsorPlacement.weight` (default 10), `updateScoreband(weight:)` (default 50); payload tests
- [x] Kotlin: `SponsorConfig.weight`, scoreband `weight` parsed with defaults, clamped 0–100
- [x] device: default order identical to today; sponsors+scoreband placement unchanged (2026-09-15)

## P4 — Dynamic image overlays (instant, no timer)
- [x] Dart models (`lib/src/models/dynamic_overlay.dart`): `DynamicOverlay`, sealed `OverlayContent` → `ImageContent`, `OverlayPlacement`, sealed `OverlayLength` (`percent`/`px`); validation mirrors native codes. `OverlayAnimation`, `duration`, `OverlayDurationUpdate` deliberately **not** added yet — they arrive with P5/P8 as additive optional params
- [x] controller `addOverlay` / `updateOverlay` / `hideOverlay` / `showOverlay` / `removeOverlay` / `clearOverlays` + bridge; `RtmpStatusType.overlayShown/overlayHidden/overlayRemoved`, `RtmpStatus.overlayId`; `test/dynamic_overlay_test.dart` (10 tests)
- [x] Kotlin: `DynamicOverlayModels.kt` (`OverlayException`, parser), `DynamicOverlayController.kt` (instant VISIBLE/HIDDEN), plugin `overlay*` handlers; errors, 16-overlay limit, events, `OVERLAY_DOWNSCALED` warning; `DynamicOverlayControllerTest` (13), `DynamicOverlayParserTest` (6)
- [x] survives orientation flip / `configure` re-prepare / preview rebind **in code** (controller seeds every new `OverlayFilterManager`; `rebuild` on rebind/recovery)
- [x] device: preview rebind verified on a phone (2026-09-15); orientation flip / re-`configure` not reachable from the example UI (unit tests only)
- [x] example app minimal hook: `example/lib/widgets/overlay_debug_menu.dart` (layers icon, top-right of camera screen)
- [x] channel drift script passes

## P5 — Live-only duration timer
- [x] `overlay/OverlayTimer.kt` (pure, `now` passed in) + `OverlayScheduler` injected into `DynamicOverlayController` (clock + scheduler); `OverlayTimerTest` (4), controller timer tests (10): pre-live freeze, reconnect pause, stop/start resume, hide pause, `restartTimer`, duration update (keep elapsed, immediate expiry), infinite, paused-past-duration, remove/clear cancel, multi-overlay order, host swap
- [x] live state wired: `RtmpConnectChecker` connect/disconnect callbacks → `mainHandler.post` → `setLive` (connect ignored after `stopStream`); `stopStream` + `release` → `setLive(false)`
- [x] expiry → `overlayRemoved{expired}` (instant until P8)
- [x] Dart: `DynamicOverlay.duration`, `OverlayDurationUpdate.keep/infinite/of`, `updateOverlay(duration:, restartTimer:)`; parser `durationMs` / `duration{ms}` / `restartTimer`; 4 Dart tests, 2 parser tests
- [x] example menu: 10 s badge, hide/show, restart timer, → infinite; snackbar on `expired`
- [x] device: 10 s badge frozen before go-live, expires after 10 s on air, pauses across a forced reconnect (toggle network) and stop/start, pauses while hidden (2026-09-15)

## P6 — Text content
- [x] `OverlayContentDecoder.renderText`: Paint, background, padding, text flattened to one line, bitmap capped at 4096 px
- [x] TTF via content-addressed cache file (`cacheDir/overlay_fonts/<sha1>.ttf`), magic-byte check → `OVERLAY_FONT_INVALID`
- [x] Dart `TextContent`, `TextOverlayStyle` (ARGB32 colors); parser + Dart tests

## P7 — GIF content
- [x] `OverlayContentDecoder.decodeGif` with RootEncoder `GifDecoder`: frames copied, caps → `OVERLAY_GIF_TOO_LARGE`; frames kept in the visual so rebuilds don't re-decode
- [x] `GifTimeline` (browser delay minimums) + `GifVisual`; GIF clock = time since add, runs live or not
- [x] Dart `GifContent`; example asset `assets/overlays/spinner.gif` (generated, 12 frames)

## P7b — Ticker content
- [x] `TickerMath` (layout, copies, pass/period, finish) + `ScrollProgress`; `TickerVisual` draws copies per frame (ADR 0015)
- [x] speed / cycleDuration, loop + loopGap (percent of band), play-once → `completed`, finish-pass-on-expiry
- [x] auto/rtl/ltr direction via `java.text.Bidi`
- [x] text update resets offset; style-only rescales by pass length
- [x] pure-Kotlin tests: layout, copies, directions, scroll progress; controller: live freeze, hide pause, once, loop expiry, cancel expiry, rescale

## P8 — Animations
- [x] `OverlayAnimationMath`: slide / pop / curtain × 4 cubic easings; enter + exit
- [x] controller lifecycle ENTERING/VISIBLE/EXITING/HIDDEN + interruption rules (spec §4), `snapAnimations` on pipeline transitions, `animate` flags on remove/clear
- [x] `DynamicLayerFilter` (non-recycling upload, GL-thread handoff, no per-frame logging), `LayerRenderer` pool, `ChoreographerFrameDriver` throttled to encoder fps
- [x] curtain works for every content type → `OVERLAY_ANIMATION_UNSUPPORTED` reserved, never emitted
- [x] controller tests: enter/exit events, reversal vs snap, remove during hide, re-add while exiting, clear, snap, render retry, GIF frame requests

## P9 — Example app: Overlay Studio
- [x] `example/lib/overlay_studio/`: **Overlay Studio** sheet on the Go Live screen (usable before and during a stream)
  - *Scenarios* (mock cricket match, `mock_match.dart`): wicket, boundary GIF+badge, sponsor break, lower third, live score text update, EN loop / BN one-pass / AR 15 s tickers, restyle, interrupt reverse/snap, layer order, fill to 16, error codes, auto demo
  - *Build*: every API option (content, text presets, font, ticker speed/cycle/loop/gap/direction, position, % / px, weight, duration, enter/exit/edge/easing/ms, custom id)
  - *Active*: scoreband weight (live), per overlay weight, duration chips + restart, hide/show, replace content, move, remove animated / instant
  - *Log*: overlay events, warnings, errors, API failures with timestamps
- [x] `StreamHud`: OFFLINE / LIVE mm:ss / RECONNECTING, overlay + hidden count, last event
- [x] config screen: per-sponsor layer weight, scoreband weight
- [x] `example/test/overlay_studio_test.dart`: studio state from mocked events (incl. re-add while exiting), all tabs render
- [x] replaced `widgets/overlay_debug_menu.dart` + `overlay_playground_sheet.dart`

## End-of-M11 device checks — passed 2026-09-15 (user; physical Android phone, output stream)
Example app: config screen → **Go Live** screen → **layers button** (top-right) opens the **Overlay Studio**
(tabs *Scenarios · Build · Active · Log*). The **HUD** (top-left) shows OFFLINE / LIVE mm:ss / RECONNECTING, overlay
count and the last event. Every check works before and during a stream. Results are recorded in spec §11.

**Bitrate (fix 2026-09-15)**
- [x] HUD shows kbps near the preset (720p ≈ 4000, 1080p ≈ 10000) on a good uplink; YouTube Studio no longer warns "lower than recommended"; config screen bitrate picker changes it

**Legacy + layering (P2/P3)**
- [x] sponsors + scoreband look exactly as before (placement and order), portrait and landscape, 720p and 1080p
- [x] config screen: sponsor **Layer weight** and **Scoreband weight** sliders change stacking; Active tab scoreband slider re-orders live
- [x] Scenarios → *Layer order*: w5 behind sponsors, w50/w95 stacked; after 4 s red jumps to front
- [x] Scenarios → *Fill to limit (16)* then *Clear (instant)*: order stays stable; 17th → `OVERLAY_LIMIT_REACHED` in Log
- [x] background → foreground (preview rebind) keeps all layers, order, hidden state, running tickers/GIFs

**Image overlays + placement (P4)**
- [x] Build tab: each position, % vs px width, width 100 % + tall image → `OVERLAY_DOWNSCALED` warning in Log
- [x] Build tab → image → **Gallery**: own PNG/JPG (large photo too, e.g. 12 MP) shows correctly; Active card **Gallery…** replaces it in place
- [x] Active tab: *Move to bottom-left*, weight slider, *Replace content* apply instantly
- [ ] orientation flip / re-`configure` with overlays: **not reachable from the example UI** (orientation is fixed once configured, per example rules) — covered by unit tests only; not a merge blocker

**Duration (P5)**
- [x] Scenarios → *Wicket!* (6 s) before Go Live: stays up (HUD OFFLINE); after Go Live disappears 6 s later (`expired`)
- [x] during a forced reconnect (airplane mode briefly; HUD RECONNECTING) the timer pauses; Stop → Go Live resumes remaining time
- [x] Active tab: hide pauses the timer; *Restart*, `5s/15s/60s/∞` chips behave as named

**Text + fonts (P6)**
- [x] Build tab → text: English / বাংলা / العربية render correctly (shaping, no tofu); custom font toggle; background + font size
- [x] Build tab → text → *Long* (max lines 6): wraps, readable, ends with `…`; align start/center/end; Active *Wrap 4 lines* / *Single line* toggle
- [x] Active card quick updates: *Text…*, *A− / A+*, *Background*, *Size − / +*, *Move ▾* change the overlay in place without re-animating
- [x] Active list follows reality: shown → green, hidden → grey, *Clear* empties it (regression 2026-09-15)
- [x] Scenarios → *Next ball (live score)* repeatedly: text swaps in place without re-animating

**GIF (P7)**
- [x] Scenarios → *Boundary 4 / 6*: spinner animates at the right speed with the badge; keeps animating after background/foreground
- [x] Build tab → GIF → **Gallery**: own animated GIFs (transparent, many frames, large) animate at the right speed; a non-GIF picked as GIF → `OVERLAY_DECODE_FAILED` in Log
- [x] memory: Build tab → GIF × 5, Android Studio profiler; Scenarios → *Error codes* shows ✓ for `OVERLAY_GIF_TOO_LARGE`

**Ticker (P7b)**
- [x] Scenarios → *News ticker (loop)*: frozen until live, smooth at 720p/1080p, pauses when hidden; tap again → next headline restarts
- [x] Scenarios → *Restyle news ticker*: scroll position kept (no jump back to start)
- [x] Scenarios → *বাংলা ticker — one pass* → removed `completed`; *Arabic ticker — 15 s* scrolls left→right, finishes its pass, then `expired`
- [x] Build tab → ticker with *Long* text + cycle duration: text visible and scrolling, no frame stalls

**Animations (P8)**
- [x] Build tab: slide from each edge, pop, curtain × easings × anim ms, on image / text / GIF / ticker
- [x] Scenarios → *Interrupt: reverse* and *Interrupt: snap*: behavior matches each description; Log event order matches spec §4
- [x] Scenarios → *Auto demo* on while live for a few minutes: no stutter, no dropped encoder frames (logcat), no leaked overlays
- [x] Scenarios → *Error codes*: all lines ✓
- [x] release build: `flutter build apk --release` (or appbundle), install, repeat *Sponsor break* + *News ticker* (R8)

## P10 — Docs sync + release check
- [x] spec DRAFT banner removed after device checks (`dynamic-overlays.md`, `camera-zoom.md`); README, CHANGELOG, ADR statuses updated 2026-09-15
- [x] `overlay-compositing.md`: pointer to ADR 0015 for dynamic layers
- [x] roadmap M11 ticked; `sync-docs` + `release-check` skills (2026-09-15)
- [x] merge to main **only on user approval** — merged 2026-09-15

---

## Log
- 2026-09-15 — Device checks by user: every End-of-M11 check passed (bitrate, legacy + layering, placement, duration, text + fonts, GIF, ticker incl. *Long*, animations, release build). Orientation flip with overlays stays unit-test-only (not reachable from the example UI). Spec banners, ADR 0014/0015/0018 statuses, README, CHANGELOG, CLAUDE.md updated.
- 2026-09-15 — Example: gallery picker for image and GIF content (Build tab source card with preview; Active card *Gallery…* replace). Uses existing `image_picker` without resize/quality args so GIF bytes stay animated.
- 2026-09-15 — Device test by user: overall good. Fixed: (1) Studio Active list stale — package `statusStream` let the last listener take over the EventChannel; now one shared stream (test `test/event_channel_bridge_test.dart`). (2) Long single-line text ~1 px tall → new `maxLines`/`align` wrapping. (3) YouTube "bitrate lower than recommended" — configured bitrate ignored after `initPreview`; presets raised to YouTube H.264 values (720p 4 Mbps, 1080p 10 Mbps). (4) Studio Active cards: quick update chips (text, font ±, background, wrap, size ±, move, mock content). Example: bitrate picker, HUD kbps. Ticker "Long" still untested.
- 2026-09-15 — P9 reworked into Overlay Studio at the user's request ("wire all new features to UI, testable during stream, mock data"). Orientation flip / re-configure with overlays is not reachable from the example UI (orientation fixed after configure) — noted in the device checklist.
- 2026-09-15 — P6–P9 (uncommitted). Findings:
  - RootEncoder 2.7.2 internals read from the AAR (javap): `setImage` → `ImageStreamObject.load` logs `Log.i` every call and races `drawFilter`; `TextureLoader` recycles; `textureLoader`/`streamObject`/`shouldLoad` are protected and writable → ADR 0015 design.
  - Object fragment shader only draws inside the sprite rect (no clip/UV) → curtain and ticker redraw on the CPU.
  - `GifDecoder` status constants are package-private; `STATUS_OK = 0`, `STATUS_PARTIAL_DECODE = 3` used as literals.
  - `Math.floorMod` is API 24+ (minSdk 21) → Kotlin `Long.mod`.
  - `flutter build apk` ran the Flutter migrator: `example/android/gradle.properties` gained `android.builtInKotlin=false` / `android.newDsl=false`, and `example/pubspec.lock` was refreshed. Not hand edits — review before committing.
- 2026-09-15 — P5 (uncommitted). Findings:
  - `connectChecker` ↔ `dynamicOverlays` lambdas reference each other → Kotlin "recursive problem" type inference error; fixed with explicit property types.
  - Decision (spec §5 clarified): a paused timer never expires even if a `duration` update puts it past its total; it expires the moment it runs again (show / go-live).
  - Wire: `restartTimer` sent only when true; `durationMs` omitted = infinite.
- 2026-09-15 — P3+P4 (uncommitted). Findings:
  - RootEncoder `TextureLoader.load` **recycles the Bitmap** after upload → factories cache encoded bytes and decode a fresh bitmap per filter build.
  - `GlStreamInterface` filter ops go through one FIFO `BlockingQueue` drained on the GL thread → sequential index math is valid (confirmed on device 2026-09-15).
  - Behavior change: after preview rebind, sponsors used to be re-appended **above** the scoreband (old `updateSponsors` appended). Now `rebuild` restores the documented order sponsors < scoreband.
  - Adding `RtmpStatusType` values breaks exhaustive `switch` statements in apps (the example needed new cases) → CHANGELOG notes it.
  - New error code `OVERLAY_OPERATION_FAILED` for unexpected native exceptions in overlay calls.
  - Example `flutter build apk --debug` OK. Emulator smoke test not done (emulator disconnected).
- 2026-09-15 — P2: geometry extraction. Run Kotlin tests: `cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest` (first run needs network for mockito). `placementRect` downscales any oversize result (not only intrinsic) — spec §2 updated.
- 2026-09-15 — P0: contract, tracking, ADR 0014 written after 5 rounds of requirement Q&A.
