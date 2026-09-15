# 0019 — Preset bitrates follow YouTube's H.264 recommendations; the configured bitrate is honored

- **Status:** Accepted (supersedes the bitrate values of 0007)
- **Date:** 2026-09-15

## Context
The user's device test on 2026-09-15 showed YouTube Studio warning "bitrate lower than recommended". Two causes:
- The presets used 2.5 Mbps (720p) and 4.5 Mbps (1080p), below YouTube's recommendation
  (YouTube Help "Choose live encoder settings", checked 2026-09-15: 720p30 4 Mbps, 1080p30 10 Mbps).
- `initPreview` prepared the encoder with a fixed bitrate. `prepareVideo` throws while previewing, so the bitrate
  passed later to `configure` was silently ignored and the stream stayed at 2.5 Mbps.

## Decision
- Presets: `youtube720Portrait`/`youtube720Landscape` 4 Mbps, `youtube1080Portrait`/`youtube1080Landscape` 10 Mbps;
  30 fps, 2 s keyframe (unchanged).
- `initPreview` prepares the encoder with `StreamConfig.videoBitrate`.
- A different bitrate in `configure` (same dims) is kept as pending and applied with `setVideoBitrateOnFly` right after
  every `startStream`.
- A different fps or keyframe interval in `configure` can't be applied while previewing: the `initPreview` values are
  kept and warning `STREAM_CONFIG_MISMATCH` is emitted.
- An orientation re-prepare keeps the configured bitrate, fps and keyframe.
- The configured bitrate stays a **ceiling** for adaptive bitrate.
- The example app gets a bitrate picker (default = the preset's bitrate).

## Consequences
- The presets need more uplink. Adaptive bitrate still steps down under congestion.
- Apps should pass the same `StreamConfig` to `initPreview` and `configure`.
- Rules: `docs/specs/reconnect-and-bitrate.md`, `docs/specs/channel-contract.md` (`StreamConfig.toMap()` keys).
