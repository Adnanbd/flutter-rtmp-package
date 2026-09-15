# 0002 — Scoreband is push-based PNG, no timers

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
Score data changes are event-driven (~every 2–3 s). Polling wastes CPU and bridge bandwidth and
captures unchanged frames.

## Decision
The app renders the scoreband widget, captures it with `RepaintBoundary.toImage` to PNG, and calls
`controller.updateScoreband(bytes)` only when data changes. The package contains no `Timer.periodic`.
PNG chosen: lossless, universally decodable, cost negligible at ~0.3–0.5 fps.

## Consequences
- Latency in the viewer is dominated by the platform (YouTube ~18–20 s), not the push; see `docs/reference/youtube-latency.md`.
