# 0016 — Sponsor carousel as dynamic overlay content

- **Status:** Accepted (device-verified 2026-09-15)
- **Date:** 2026-09-15

## Context
Host apps want several sponsor images (or GIFs) rotating in one slot with a configurable interval and transition.
Options:
1. Extend legacy `configure(sponsors)` — but sponsors are static by invariant 3 and have no animation or lifecycle.
2. Let the app rotate by calling `updateOverlay` on a timer — polling from Dart, transitions impossible (`update` is an instant swap), timing jitter from the channel.
3. A new content type for the M11 dynamic overlay system, rotated natively.

## Decision
Option 3: `CarouselContent(items, interval, transition)` is one more `OverlayContent`.
- Reuses everything a dynamic overlay has: id, placement (percent/px), weight, live-time duration, enter/exit
  animations, hide/show/update/remove, events, limits.
- Items are image or GIF. Transitions `cut` / `crossfade` / `push` are drawn on the CPU into the layer bitmap
  (ADR 0015): crossfade = per-item `saveLayerAlpha`, push = translate, clipped by the layer bitmap itself.
- Clock = wall clock since add or content update (same as GIF), so it rotates in preview and off-air.
- `CarouselTimeline` (pure Kotlin) decides item, transition progress and the next change. Static carousels need no
  per-frame work between transitions: the controller arms one scheduler wake per change and runs frames only while a
  transition plays (plus one final frame). Carousels with a GIF item are frame-driven like GIFs.

## Consequences
- No new channel methods; one new content `type` and one new error code (`OVERLAY_CAROUSEL_TOO_LARGE`, 64 MB aggregate).
- Slot geometry needed a `fillBox` mode in `OverlayGeometry.placementRect` (width + height = the whole box, items contained inside).
- A transition uploads one texture per frame for its duration (same cost class as the curtain animation).
- No per-item event: apps that need sponsor airtime reporting must derive it from the timeline they configured.
