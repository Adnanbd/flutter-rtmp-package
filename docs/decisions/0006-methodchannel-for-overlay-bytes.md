# 0006 — StandardMethodCodec for overlay bytes

- **Status:** Accepted
- **Date:** 2026-04-22

## Context
Scoreband PNGs (tens to hundreds of KB) cross the bridge every 2–3 s.

## Decision
Send bytes as `Uint8List` through the normal `MethodChannel` (`updateOverlay`). Upgrade to a
`BasicMessageChannel` with `BinaryCodec` only if profiling shows bridge cost matters.

## Consequences
- Dart calls use `invokeMethod` (list-returning USB calls use `invokeListMethod`); never `invokeMapMethod`.
