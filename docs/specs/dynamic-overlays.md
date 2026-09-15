# Spec — Dynamic Overlays (Android only)

> **Status: implemented on Android and device-verified 2026-09-15** (§11). Contract agreed 2026-09-15. Progress: [../plans/dynamic-overlays.md](../plans/dynamic-overlays.md).
> Rendering design: [ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md).

Adds app-controlled overlays on top of the existing sponsor/scoreband system:
- any number, any position/size (percent or encoder px)
- 0–100 z-weight
- optional duration that counts only while live
- hide/show/remove/update by id
- enter/exit animations
- image, GIF, static text, and scrolling ticker content
- carousel: images/GIFs rotating in one slot (§12, M12)

**Legacy compatibility:** `configure(sponsors)` and `updateScoreband` keep their exact behavior. The only addition is an optional `weight` (sponsors 10, scoreband 50).

iOS: not planned. The iOS plugin is a stub; see [../architecture/ios.md](../architecture/ios.md).

---

## 1. Dart API

### Controller

| Method | Channel | Notes |
|---|---|---|
| `Future<void> addOverlay(DynamicOverlay overlay)` | `overlayAdd` | plays `enter`; id must be new |
| `Future<void> updateOverlay(String id, {OverlayContent? content, OverlayPlacement? placement, int? weight, OverlayDurationUpdate? duration, bool restartTimer = false})` | `overlayUpdate` | instant swap; timer kept unless `restartTimer` |
| `Future<void> hideOverlay(String id)` | `overlayHide` | plays `exit`, keeps overlay, pauses timer + ticker |
| `Future<void> showOverlay(String id)` | `overlayShow` | plays `enter`, resumes |
| `Future<void> removeOverlay(String id, {bool animate = true})` | `overlayRemove` | plays `exit` unless `animate: false`; event `overlayRemoved{reason: removed}` when gone |
| `Future<void> clearOverlays({bool animate = false})` | `overlayClear` | removes all dynamic overlays (never sponsors/scoreband); reason `cleared` |

Legacy additions:
- `SponsorPlacement(..., int weight = 10)`
- `updateScoreband(bytes, {width, x, y, int weight = 50})`

`OverlayDurationUpdate`: `.keep()` (default when `duration` is omitted), `.infinite()`, `.of(Duration d)`.

### Models (`lib/src/models/dynamic_overlay.dart`)

```dart
class DynamicOverlay {
  final String id;                 // 1–64 chars; not 'scoreband', not starting with 'sponsor_'
  final OverlayContent content;
  final OverlayPlacement placement;
  final int weight;                // 0–100, default 50; 100 = front-most
  final Duration? duration;        // null = infinite; must be ≥ 1 ms
  final OverlayAnimation enter;    // default OverlayAnimation.none
  final OverlayAnimation exit;     // default OverlayAnimation.none
}

sealed class OverlayContent
  ImageContent(Uint8List bytes)                       // PNG / JPG / WebP (static)
  GifContent(Uint8List bytes)                         // animated GIF, loops by frame delay
  TextContent(String text, {TextOverlayStyle style})  // maxLines 1: one line scaled to fit; > 1: wraps (§2)
  TickerContent(String text, {TextOverlayStyle style,
      double? speedPxPerSec, Duration? cycleDuration, // at most one; default speed 120 px/s
      bool loop = true,
      OverlayLength? loopGap,                         // default percent(33) of band width
      TickerDirection direction = TickerDirection.auto})
  CarouselContent(List<CarouselItem> items,           // §12
      {Duration interval = 5 s, CarouselTransition transition = crossfade 500 ms})

class TextOverlayStyle {
  final double fontSizePx;          // encoder px, default 32
  final Color color;                // default white
  final Color? background;          // default null; TickerContent.defaultStyle has 0xB3000000 (70% black)
  final double paddingPx;           // default 8
  final Uint8List? fontTtf;         // optional TTF/OTF bytes; default system font
  final int maxLines;               // TextContent only; default 1 = one scaled line; > 1 = wrap (see §2)
  final TextOverlayAlign align;     // start (default) | center | end; wrapped lines only
}
// copyWith on OverlayPlacement, TextOverlayStyle, TextContent, TickerContent (can't clear a field to null)

enum TickerDirection { auto, rtl, ltr }   // rtl = text moves right→left (default for LTR scripts)

sealed class OverlayLength { percent(num v) /*0–100*/; px(num v) /*encoder px, ≥0*/ }

class OverlayPlacement {
  final OverlayLength? left, right, top, bottom;   // anchors
  final OverlayLength? width, height;              // max box
}

class OverlayAnimation {                // also const .slide({edge}), .pop(), .curtain()
  final OverlayAnimationType type;   // none | slide | pop | curtain
  final int durationMs;              // default 400, 0–5000
  final OverlayEasing easing;        // linear | easeIn | easeOut (default) | easeInOut
  final OverlayEdge edge;            // slide only: left | right | top | bottom (default)
  static const none = OverlayAnimation(type: OverlayAnimationType.none);
}
```

## 2. Geometry

All geometry is in the **post-rotation stream frame** (what viewers see). Native converts to the pre-rotation
filter space exactly like legacy overlays (see [overlay-compositing.md](overlay-compositing.md)).

- `percent(v)`: v % of frame width (for left/right/width) or height (for top/bottom/height).
- `px(v)`: encoder output pixels of the **current** encoder dims. After an orientation flip, px keep their value against the new dims, clamped to the frame.

**Size:**

| Given | Result |
|---|---|
| `width` + `height` | BoxFit.contain inside that box |
| only `width` | height from content aspect |
| only `height` | width from content aspect |
| neither | intrinsic content size in px (bitmap px = encoder px) |

Any resulting size larger than the frame (any of the rows above) is contained in the frame, with warning `OVERLAY_DOWNSCALED`.

**Position, per axis** (same rules as `SponsorPlacement`):

| Given | Result |
|---|---|
| only `left` | left edge pinned |
| only `right` | right edge pinned |
| both or neither | centered |

Result clamped to `[0, frame − size]`.

**Ticker:** `width` = band width (default `percent(100)`). Band height = `fontSizePx` line height + 2 × `paddingPx`. `height` ignored.

**Wrapped text** (`TextContent` with `style.maxLines > 1`): wraps to the placement `width` (default the frame width, clamped to it),
ellipsized with `…` after `maxLines`. The measured size is then placed with the rules above, so a `height` still contains it.
Single-line text (`maxLines: 1`) is scaled to fit, so long single-line text becomes tiny.

## 3. Layering

Every GL overlay layer (sponsor, scoreband, dynamic) lives in one ordered stack. Sort key, back → front:

```
(weight ascending, classRank ascending, seq ascending)
classRank: sponsor = 0, scoreband = 1, dynamic = 2
seq: monotonically increasing insertion counter (per class)
```

- Default weights reproduce today's order: sponsors (10) < scoreband (50) < dynamic at 50, with later-added dynamic overlays on top.
- `updateOverlay(weight:)` reorders immediately. Seq is unchanged, so it keeps its place among equal keys.
- Hidden overlays are removed from GL but keep their key; `show` reinserts at the correct index.
- Pipeline transitions (preview rebind, orientation flip, `configure` re-prepare) rebuild the full stack in order; in-flight animations snap to their end state.

## 4. Lifecycle state machine

```
          add / show                      enter done
 (none) ───────────────▶ ENTERING ───────────────────▶ VISIBLE
                            │ ▲                          │
                   hide/rm  │ │ show                     │ hide / remove / expire / ticker complete
                            ▼ │                          ▼
                          EXITING ◀──────────────────────┘
                            │ exit done
              ┌─────────────┴──────────────┐
         (hide) HIDDEN              (remove/expire/clear/complete) REMOVED
```

**Events** (on `statusStream`, `RtmpStatus.overlayId` set):

| Event | When |
|---|---|
| `overlayShown {id}` | enter animation finished (immediately if `none`) |
| `overlayHidden {id}` | exit finished after `hideOverlay` |
| `overlayRemoved {id, reason}` | exit finished (or immediately if `animate: false` / `none`). `reason` ∈ `removed \| expired \| cleared \| completed` |

**Interruptions:**

| Case | Behavior |
|---|---|
| `hide`/`remove` during ENTERING | if exit type == enter type, reverse from current progress; else snap to VISIBLE and start exit |
| `show` during EXITING-for-hide | mirror rule: reverse or snap then enter |
| `remove` during EXITING-for-hide | let exit finish, then REMOVED (reason `removed`) |
| `hide` on HIDDEN, `show` on VISIBLE/ENTERING | no-op, success |
| `update` during any state | content/placement/weight applied immediately; running animation continues against new geometry |
| any call on an overlay EXITING-for-removal | it is already gone for the API: update/hide/show/remove → `OVERLAY_NOT_FOUND` |
| `add` with the id of an overlay EXITING-for-removal | the old one finishes instantly (`overlayRemoved` with its reason), then the new one is added |
| `remove` on HIDDEN | instant (nothing to animate) |
| snap to VISIBLE / HIDDEN / REMOVED (interruption or pipeline transition) | emits the event that state normally emits |

## 5. Duration timer (live-only)

- `liveElapsed` accumulates only while **RTMP is connected** AND state == **VISIBLE**.
- It does not count during enter/exit animations, while hidden, before go-live, during reconnects, or after `stopStream`.
- Connected = between `connected` and the next `disconnected` / `stopStream`.
- `liveElapsed ≥ duration` while the timer is running → exit animation → `overlayRemoved{reason: expired}`.
  A paused overlay (hidden, or not live) never expires, even past its duration; it expires the moment it runs again.
- Native: `OverlayTimer` per overlay; `DynamicOverlayController` arms one main-thread check for the soonest running expiry. Live state is set from `RtmpConnectChecker` connect/disconnect (posted to main; ignored after `stopStream`), `stopStream` and `release`.
- Remaining time survives `stopStream` / `startStream` and pipeline rebuilds. It is lost only on `removeOverlay`, `clearOverlays`, or plugin release.
- `updateOverlay(duration: .of(d))` sets a new total and keeps `liveElapsed` (a running overlay may expire immediately). `restartTimer: true` resets `liveElapsed` to 0. `.infinite()` disables expiry.

## 6. Ticker

- Text: all line breaks and runs of whitespace collapsed to single spaces → one continuous line.
- Direction `auto`: `java.text.Bidi` base direction of the text. RTL scripts (Arabic, Hebrew, Urdu) move left→right; everything else right→left.
- **Pass** = the text's leading edge enters at the band's entry side until its trailing edge leaves the exit side.
  Pass length = `textWidthPx + bandWidthPx`.
- Speed: `speedPxPerSec`, or derived from `cycleDuration` as `passLength / cycleDuration`.
- `loop: true` → next pass starts `loopGap` after the previous trailing edge (continuous, no reset jump).
- `loop: false` → after one pass: exit animation → `overlayRemoved{reason: completed}`.
- Loop + `duration` expiry → finish the current pass, then exit → `overlayRemoved{reason: expired}`.
- Scroll offset advances **only while live AND VISIBLE**; otherwise frozen. The first pass never starts before go-live, so viewers see the start of the message.
- `updateOverlay(content: TickerContent)`:
  - text changed → offset resets to band entry edge
  - style/speed-only change → offset kept (proportionally rescaled if text width changed)
- Rendering: every frame the band is redrawn on the CPU (background + one `drawText` per visible copy) and uploaded
  ([ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md)); Android shapes Bangla/Arabic text. Frames are requested
  only while the ticker is scrolling or animating.

## 7. Animations

All animations are driven natively on the main thread per display frame. They play regardless of live state.

| Type | Enter (progress 0→1) | Exit (1→0) |
|---|---|---|
| `slide` | position moves from fully outside `edge` to final position | final → outside `edge` |
| `pop` | scale 0→1 around the overlay center | 1→0 around center |
| `curtain` | horizontal reveal from center outward (clip width 0→100%) | clip closes to center |
| `none` | instant | instant |

Easing curves: `linear`, `easeIn` (cubic), `easeOut` (cubic), `easeInOut` (cubic).

Implementation ([ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md)): `slide` and `pop` move/scale the GL filter;
`curtain` keeps the rect and redraws the content clipped to the centered visible fraction. Every content type supports
every animation, so `OVERLAY_ANIMATION_UNSUPPORTED` is reserved and never emitted. A `durationMs` of 0 is instant.
Frames run on `Choreographer`, throttled to the encoder fps.

## 8. Wire format

### `overlayAdd`
```
{ id: String, weight: int, durationMs: int?,   // key omitted = infinite; must be > 0
  content: Content, placement: Placement,
  enter: Animation, exit: Animation }
```
**Content:**
```
{ type: 'image'|'gif', bytes: Uint8List }
{ type: 'text', text: String, style: Style }
{ type: 'ticker', text: String, style: Style, speedPxPerSec: double?, cycleDurationMs: int?,
  loop: bool, loopGap: Length?, direction: 'auto'|'rtl'|'ltr' }
{ type: 'carousel', intervalMs: int,
  items: [ { content: { type: 'image'|'gif', bytes }, intervalMs: int? } ],
  transition: { type: 'cut'|'crossfade'|'push', durationMs: int, easing: String, edge: String } }
```
**Style:** `{ fontSizePx: double, color: int (ARGB32), background: int?, paddingPx: double, fontTtf: Uint8List?, maxLines: int?, align: 'start'|'center'|'end'? }`
(null/default keys omitted; native defaults when absent: 32, white, no background, 8, system font, 1, start).
`text` is flattened natively too, except text content with `maxLines > 1`, which keeps `\n` breaks (other whitespace runs collapse).

**Length:** `{ unit: 'percent'|'px', value: double }`

**Placement:** `{ left: Length?, right: Length?, top: Length?, bottom: Length?, width: Length?, height: Length? }` (null keys omitted)

**Animation:** `{ type: 'none'|'slide'|'pop'|'curtain', durationMs: int, easing: 'linear'|'easeIn'|'easeOut'|'easeInOut', edge: 'left'|'right'|'top'|'bottom' }`

### `overlayUpdate`
```
{ id: String, restartTimer: bool,   // key omitted = false
  content: Content?, placement: Placement?, weight: int?,   // key omitted = unchanged
  duration: { ms: int? }? }                                 // key omitted = keep; ms null = infinite
```

### Others
- `overlayHide {id}`, `overlayShow {id}`
- `overlayRemove {id, animate: bool}`
- `overlayClear {animate: bool}`
- All return `null`.

### Legacy additions
- Sponsor map gains `weight: int` (default 10 when absent).
- `updateOverlay` (scoreband) gains `weight: int` (default 50 when absent).

## 9. Errors & warnings

Validation runs in Dart first (throws `RtmpBroadcasterException`) and is repeated natively.

| Code | Kind | Cause |
|---|---|---|
| `OVERLAY_NOT_INITIALIZED` | error | any overlay call before `initPreview`/`configure` |
| `OVERLAY_ID_EXISTS` | error | `addOverlay` with an id already present (including hidden) |
| `OVERLAY_NOT_FOUND` | error | update/hide/show/remove unknown id |
| `OVERLAY_ID_RESERVED` | error | id empty, > 64 chars, `scoreband`, or `sponsor_*` |
| `OVERLAY_LIMIT_REACHED` | error | > 16 dynamic overlays (visible + hidden) |
| `OVERLAY_INVALID_PLACEMENT` | error | negative length, percent > 100, weight outside 0–100 |
| `OVERLAY_INVALID_CONTENT` | error | empty text, both `speedPxPerSec` and `cycleDuration`, non-positive speed/duration/font size, animation duration out of range |
| `OVERLAY_DECODE_FAILED` | error | image/GIF bytes undecodable (existing code) |
| `OVERLAY_GIF_TOO_LARGE` | error | GIF > 150 frames or > 64 MB decoded (w × h × 4 × frames) |
| `OVERLAY_CAROUSEL_TOO_LARGE` | error | carousel items > 64 MB decoded in total |
| `OVERLAY_FONT_INVALID` | error | `fontTtf` bytes not loadable |
| `OVERLAY_DOWNSCALED` | warning | intrinsic size larger than frame, contained |
| `OVERLAY_ANIMATION_UNSUPPORTED` | warning | reserved; not emitted (all content types support all animations, ADR 0015) |
| `OVERLAY_OPERATION_FAILED` | error | unexpected native exception during an overlay call (see diagnostics log) |

## 10. Limits

| Limit | Value |
|---|---|
| Dynamic overlays | 16 (visible + hidden) |
| GIF | ≤ 150 frames, ≤ 64 MB decoded |
| Animation duration | 0–5000 ms |
| Carousel | 1–20 items, interval ≥ 500 ms, ≤ 64 MB decoded in total |
| Id length | 1–64 |

## 11. Rendering notes and device results

- Design chosen without device spikes (device testing batched at the end of M11): [ADR 0015](../decisions/0015-cpu-composed-dynamic-layers.md).
- Dynamic layers render at their display size in encoder px; images above 2048 px are subsampled on decode; text bitmaps above 4096 px are scaled down.
- GIF frame delays ≤ 10 ms play at 100 ms; the minimum delay is 20 ms.
- `fontTtf` is written to `cacheDir/overlay_fonts/<sha1>.ttf` and loaded with `Typeface.createFromFile`.
- Device check 2026-09-15 (user, physical Android phone, output stream), all passed: layer ordering with queued filter
  ops, sponsor/scoreband placement unchanged, frame time (auto demo live, no dropped encoder frames), GIF memory and speed,
  ticker smoothness at 720p/1080p, text shaping (English, Bangla, Arabic), animations and interruptions, live-time
  duration across reconnect and stop/start, carousel transitions, release build (R8). Orientation flip / re-`configure`
  with overlays is not reachable from the example UI and is covered by unit tests only.

## 12. Carousel content

> M12, 2026-09-15. Decision: [ADR 0016](../decisions/0016-carousel-dynamic-content.md). Progress: [../plans/carousel-and-zoom.md](../plans/carousel-and-zoom.md).

Images and GIFs shown one after another in the same slot (rotating sponsor logos). A carousel is a normal dynamic
overlay: id, placement, weight, `duration`, enter/exit animations, hide/show/update/remove all apply to the whole slot.

```dart
class CarouselContent extends OverlayContent {
  final List<CarouselItem> items;       // 1–20
  final Duration interval;              // default 5 s, ≥ 500 ms
  final CarouselTransition transition;  // default crossfade 500 ms easeInOut
}
class CarouselItem { final OverlayContent content; /* ImageContent | GifContent */ final Duration? interval; }
class CarouselTransition {              // also const .cut(), .crossfade(), .push(edge:)
  final CarouselTransitionType type;    // cut | crossfade | push
  final int durationMs;                 // 0–5000; ignored by cut
  final OverlayEasing easing;           // default easeInOut
  final OverlayEdge edge;               // push only: side the next item enters from; default right
}
```

**Timeline**
- Item `i` owns a slot of `interval_i` (its own `interval`, else `CarouselContent.interval`). Cycle = Σ slots; loops forever.
- The transition to item `i+1` takes the **last** `durationMs` of slot `i`. `durationMs` must be shorter than every
  slot (validated when more than one item and not `cut`).
- One item: static, no transition.
- Clock: wall clock since `addOverlay`, or since an `updateOverlay(content:)` with carousel content (restarts at item 0).
  Runs before go-live, off-air and while hidden (like GIF). Placement/weight/duration updates keep the position.
- Each item has its own clock starting when it begins to appear (the transition into it), so GIF items animate during the
  transition and never jump at their slot start.

**Transitions**

| Type | Drawing |
|---|---|
| `cut` | swap at the slot end |
| `crossfade` | outgoing alpha 1 → 0, incoming 0 → 1, eased |
| `push` | incoming enters from `edge`, outgoing leaves toward the opposite side, both moving one slot length, clipped to the slot |

**Slot geometry**
- `width` **and** `height` given: the slot is exactly that box (not contained to an aspect).
- Otherwise: intrinsic slot = tallest item height × widest item aspect, then sized/positioned with the §2 rules.
- Every item is contain-fit and centered in the slot, so the slot never changes size while rotating.

**Rendering** (ADR 0015/0016): one `CarouselVisual` per layer. Static carousels redraw only at cuts and during
transitions (one scheduler wake per change); carousels with a GIF item redraw per frame like GIFs.

**Errors:** `OVERLAY_INVALID_CONTENT` (0 or > 20 items, item not image/GIF, interval < 500 ms, transition too long or out
of range), `OVERLAY_DECODE_FAILED` (message names the item index), `OVERLAY_CAROUSEL_TOO_LARGE`, `OVERLAY_GIF_TOO_LARGE`
(single GIF item over its limits).

**Wire** (content map in `overlayAdd` / `overlayUpdate`): see §8.

