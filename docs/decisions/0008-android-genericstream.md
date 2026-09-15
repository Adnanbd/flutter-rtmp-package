# 0008 — Android: RootEncoder `GenericStream`, not `RtmpCamera2`

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
`RtmpCamera2` is superseded in RootEncoder 2.5+ by the `StreamBase` pattern.

## Decision
Use `GenericStream(context, connectChecker)` (RootEncoder 2.7.2). Overlays register on
`genericStream.getGlInterface().addFilter(...)`. Sources swap via `changeVideoSource` / `changeAudioSource`
(enables UVC + USB audio).

## Consequences
- Never introduce `RtmpCamera2`, `OpenGlView`, or filters attached to a view.
