# 0003 — Sponsors sent once at configure, cached natively

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
Sponsor images don't change during a broadcast. Re-sending bytes is wasteful.

## Decision
Sponsors are passed in `configure()` and cached natively (Android `lastSponsors` + one
`ImageObjectFilterRender` each). Replaced only via `updateSponsors()` (not yet implemented natively).
The native side re-applies cached sponsors after pipeline transitions that drop GL filters.

## Consequences
- Native code must keep the cache in sync with every re-prepare path (`configure`, `reinitializeForOrientation`, `bindPreview`, `startStream`).
