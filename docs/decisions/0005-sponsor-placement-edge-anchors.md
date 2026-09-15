# 0005 — Sponsor placement = edge anchors + BoxFit.contain

- **Status:** Accepted
- **Date:** 2026-05-14

## Context
`OverlayPosition(x, y, width, height)` couldn't express "right-aligned" or "centered" without the app
knowing the image's rendered size, and height was ignored by aspect-fit.

## Decision
`SponsorPlacement(left?, right?, top?, bottom?, width, height)`, ints 0–100. Per axis: a single anchor
pins that edge; both or neither → centered. `width`/`height` are a max box; image scaled with
BoxFit.contain. `OverlayPosition` kept as deprecated and converted in `SponsorOverlay.toMap()`.

## Consequences
- Wire keys changed from `x,y` to `left,right,top,bottom` — `SponsorConfig.fromMap` must match.
