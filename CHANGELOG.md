# Changelog

## Unreleased (0.1.0)

Android implementation; iOS is not implemented yet.

- `RtmpBroadcastController`:
  - `initPreview`, `configure`, `startStream`/`stopStream`, `updateScoreband`
  - `switchCamera`, `setAudioMuted`, `setAppOrientation`, `rebindPreview`
- `StreamConfig` with YouTube presets (720p/1080p × portrait/landscape). Default `youtube720Portrait`.
- Native GPU compositing of sponsor overlays and a live scoreband (RootEncoder `GenericStream` + `ImageObjectFilterRender`).
- `SponsorPlacement`: edge anchors + BoxFit.contain sizing. `OverlayPosition` is deprecated.
- Scoreband position/size: `updateScoreband(width, x, y)`.
- Auto-reconnect: 3 attempts × 3 s via `StreamClient.reTry`.
- Adaptive video bitrate under congestion.
- USB sources: UVC camera and USB audio input.
- `statusStream` events: `connected`, `disconnected`, `bitrate`, `reconnecting`, `error`, `warning`, `usbDetached`.
- `previewBound` notifier.
- On-device diagnostics log: `exportDiagnostics` / `clearDiagnostics`.
- R8 consumer rules so overlays survive release builds.
- Docs restructured for agents:
  - `docs/` holds specs, architecture, ADRs, plans, and reference.
  - `.claude/` holds path-scoped rules, skills, and shared settings.
