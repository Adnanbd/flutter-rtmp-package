# 0013 — iOS: `ScreenObject` overlays, no manual CoreImage

- **Status:** Accepted (not yet implemented)
- **Date:** 2026-04-22

## Context
A hand-rolled `CIContext.render` + `CVPixelBufferPool` pipeline adds complexity and per-frame allocation risk.

## Decision
One HaishinKit `ScreenObject` per layer inside `MediaMixer`. Scoreband updates swap the object's image.

## Consequences
- Coordinate space / units / orientation behavior must be verified empirically (open questions in overlay spec).
