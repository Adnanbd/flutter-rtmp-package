# 0011 — Android: reconnect via `StreamClient.reTry`, never `startStream`

- **Status:** Accepted
- **Date:** 2026-07-30

## Context
Field crash: reconnect posted `genericStream.startStream()` 3 s after disconnect. RootEncoder keeps
`StreamBase.isStreaming = true` after a socket drop, so it threw `IllegalStateException: Stream already
started` on the main thread. Same report showed frames discarded for ~2 min before `Broken pipe`.

## Decision
- Reconnect with `getStreamClient().setReTries(3)` + `reTry(3000, reason)`. On exhaustion emit
  `MAX_RECONNECT_EXCEEDED` and `stopStream()`.
- Add `BitrateAdapter` so configured bitrate is a ceiling that steps down under congestion.

## Consequences
- Encoder, preview, overlays survive reconnects untouched.
