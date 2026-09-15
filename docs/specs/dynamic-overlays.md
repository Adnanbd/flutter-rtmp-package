# Spec — Dynamic Overlays (Android only)

> **Status: DRAFT — not implemented.** Contract agreed 2026-09-15. Progress: [../plans/dynamic-overlays.md](../plans/dynamic-overlays.md).
> Sections marked **[spike]** are filled in after P1 device spikes.

Adds app-controlled overlays on top of the existing sponsor/scoreband system:
- any number, any position/size (percent or encoder px)
- 0–100 z-weight
- optional duration that counts only while live
- hide/show/remove/update by id
- enter/exit animations
- image, GIF, static text, and scrolling ticker content

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
| `Future<void> removeOverlay(String id, {bool animate = true})` | `overlayRemove` | frees resources; event `overlayRemoved{reason: removed}` |
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
  final Duration? duration;        // null = infinite; must be > 0
  final OverlayAnimation enter;    // default OverlayAnimation.none
  final OverlayAnimation exit;     // default OverlayAnimation.none
}

sealed class OverlayContent
  ImageContent(Uint8List bytes)                       // PNG / JPG / WebP (static)
  GifContent(Uint8List bytes)                         // animated GIF, loops by frame delay
  TextContent(String text, {TextOverlayStyle style})  // single line, no wrap
  TickerContent(String text, {TextOverlayStyle style,
      double? speedPxPerSec, Duration? cycleDuration, // at most one; default speed 120 px/s
      bool loop = true,
      OverlayLength? loopGap,                         // default percent(33) of band width
      TickerDirection direction = TickerDirection.auto})

class TextOverlayStyle {
  final double fontSizePx;          // encoder px, default 32
  final Color color;                // default white
  final Color? background;          // TextContent: default null; TickerContent: default 0xB3000000 (70% black)
  final double paddingPx;           // default 8
  final Uint8List? fontTtf;         // optional TTF/OTF bytes; default system font
}

enum TickerDirection { auto, rtl, ltr }   // rtl = text moves right→left (default for LTR scripts)

sealed class OverlayLength { percent(num v) /*0–100*/; px(num v) /*encoder px, ≥0*/ }

class OverlayPlacement {
  final OverlayLength? left, right, top, bottom;   // anchors
  final OverlayLength? width, height;              // max box
}

class OverlayAnimation {
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

## 5. Duration timer (live-only)

- `liveElapsed` accumulates only while **RTMP is connected** AND state == **VISIBLE**.
- It does not count during enter/exit animations, while hidden, before go-live, during reconnects, or after `stopStream`.
- Connected = between `connected` and the next `disconnected` / `stopStream`.
- `liveElapsed ≥ duration` → exit animation → `overlayRemoved{reason: expired}`.
- Remaining time survives `stopStream` / `startStream` and pipeline rebuilds. It is lost only on `removeOverlay`, `clearOverlays`, or plugin release.
- `updateOverlay(duration: .of(d))` sets a new total and keeps `liveElapsed` (it may expire immediately). `restartTimer: true` resets `liveElapsed` to 0. `.infinite()` disables expiry.

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
- Rendering approach **[spike S4]**.

## 7. Animations

All animations are driven natively on the main thread per display frame. They play regardless of live state.

| Type | Enter (progress 0→1) | Exit (1→0) |
|---|---|---|
| `slide` | position moves from fully outside `edge` to final position | final → outside `edge` |
| `pop` | scale 0→1 around the overlay center | 1→0 around center |
| `curtain` | horizontal reveal from center outward (clip width 0→100%) | clip closes to center |
| `none` | instant | instant |

Easing curves: `linear`, `easeIn` (cubic), `easeOut` (cubic), `easeInOut` (cubic).

Curtain implementation **[spike S2]**. If unsupported for a content type (candidate: GIF) → warning
`OVERLAY_ANIMATION_UNSUPPORTED` and fall back to `pop`.

## 8. Wire format

### `overlayAdd`
```
{ id: String, weight: int, durationMs: int?,
  content: Content, placement: Placement,
  enter: Animation, exit: Animation }
```
**Content:**
```
{ type: 'image'|'gif', bytes: Uint8List }
{ type: 'text', text: String, style: Style }
{ type: 'ticker', text: String, style: Style, speedPxPerSec: double?, cycleDurationMs: int?,
  loop: bool, loopGap: Length?, direction: 'auto'|'rtl'|'ltr' }
```
**Style:** `{ fontSizePx: double, color: int (ARGB32), background: int?, paddingPx: double, fontTtf: Uint8List? }`

**Length:** `{ unit: 'percent'|'px', value: double }`

**Placement:** `{ left: Length?, right: Length?, top: Length?, bottom: Length?, width: Length?, height: Length? }` (null keys omitted)

**Animation:** `{ type: 'none'|'slide'|'pop'|'curtain', durationMs: int, easing: 'linear'|'easeIn'|'easeOut'|'easeInOut', edge: 'left'|'right'|'top'|'bottom' }`

### `overlayUpdate`
```
{ id: String, restartTimer: bool,
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
| `OVERLAY_FONT_INVALID` | error | `fontTtf` bytes not loadable |
| `OVERLAY_DOWNSCALED` | warning | intrinsic size larger than frame, contained |
| `OVERLAY_ANIMATION_UNSUPPORTED` | warning | animation not supported for content type; fallback used |

## 10. Limits

| Limit | Value |
|---|---|
| Dynamic overlays | 16 (visible + hidden) |
| GIF | ≤ 150 frames, ≤ 64 MB decoded |
| Animation duration | 0–5000 ms |
| Id length | 1–64 |

## 11. Spike results

- S1 layer ordering: **[spike]**
- S2 curtain: **[spike]**
- S3 GIF + transform smoothness: **[spike]**
- S4 ticker rendering: **[spike]**
