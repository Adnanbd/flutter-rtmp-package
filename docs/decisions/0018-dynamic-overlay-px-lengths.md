# 0018 — Percent or encoder-px lengths for dynamic overlays

- **Status:** Accepted (refines 0004; device-verified 2026-09-15)
- **Date:** 2026-09-15

## Context
The M11 request asked for overlays of any size and position: "along with today's fixed percentages, can be set
pixel". ADR 0004 kept pixels out of Dart because stream resolution varies per preset. Host apps that design a badge
for a known stream size (e.g. a 200 px logo on 720p) still want exact pixels.

## Decision
- Dynamic overlays (`OverlayPlacement`) take an `OverlayLength` per field: `OverlayLength.percent(v)` (0–100 % of the
  frame width or height) or `OverlayLength.px(v)` (encoder output pixels, ≥ 0). Units can be mixed per field.
- Everything stays relative to the **post-rotation** stream frame (what viewers see); native converts to the
  pre-rotation filter space as before.
- px are measured against the **current** encoder dims. After an orientation flip or re-`configure` they keep their
  value and are clamped to the new frame.
- Text sizes (`fontSizePx`, `paddingPx`), ticker speed (`speedPxPerSec`) and `loopGap.px` are encoder px too.
- Legacy sponsors (`SponsorPlacement`) and the scoreband (`width/x/y`) keep integer percent. Their API does not change.
- Anything larger than the frame is contained in it, with warning `OVERLAY_DOWNSCALED`.

## Consequences
- A px layout looks smaller at 1080p than at 720p; apps that switch presets should use percent or scale their px.
- Native geometry lives in one pure, JVM-tested place (`OverlayGeometry.Length`, `placementRect`).
- Rules: `docs/specs/dynamic-overlays.md` §2.
