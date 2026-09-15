# 0004 — Overlay coords are resolution-agnostic percentages

- **Status:** Accepted (refined by 0005)
- **Date:** 2026-04-22, refined 2026-05

## Context
Stream resolution varies (720p/1080p × portrait/landscape). Pixel coordinates in Dart would break per preset.

## Decision
Dart expresses overlay geometry relative to the **post-rotation** stream frame. Originally normalized
doubles 0.0–1.0 (`OverlayPosition`, now deprecated); current API uses integer percent 0–100
(`SponsorPlacement`, scoreband `width/x/y`). Native converts using encoder dims, never preview size.

## Consequences
- Native must apply the pre-rotation transform on Android (see `docs/specs/overlay-compositing.md`).
