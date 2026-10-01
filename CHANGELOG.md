# Changelog

## Unreleased (0.1.0)

Android implementation; iOS is not implemented yet.

Fixed (2026-09-30, not device-verified):
- Android USB audio: with a USB audio input selected, the stream could carry the phone microphone with no warning. `UsbAudioSource` now resolves the USB input again right before recording (id → product name → first USB input), checks the actual route, re-routes on change, and sends new warnings `USB_AUDIO_DEVICE_NOT_FOUND` / `USB_AUDIO_NOT_ROUTED`. `configure()` after `initPreview()` now applies a changed audio input. ADR 0022.
- Android USB audio crashed on Android 5.x (API 21/22): the read loop called API 23 `AudioRecord.bufferSizeInFrames`.

Fixed (2026-10-01, not device-verified):
- Android USB audio: with an MS2109-based USB audio input (MT-VIKI switcher) YouTube received nothing, while USB video + phone mic worked. USB audio is now watched while streaming: if it stops delivering sound it is restarted once (warning `USB_AUDIO_STALLED`), and if it stalls again within 30 s the stream switches to the phone microphone (warning `USB_AUDIO_FALLBACK_PHONE_MIC`) instead of going blank. Read chunks are capped at 4096 bytes (the AAC encoder silently cut larger ones). New 5 s diagnostics lines `pcm:` and `stream:` and a `mics[…]:` line with the physical microphone in use. ADR 0023.
- Example: Go Live screen shows the audio source (chip + red banner on fallback) and has a Diagnostics button.
- Android: the app crashed natively when a USB camera was closed a few ms after its preview started (camera screen bound and unbound the preview back to back). The UVC close now waits until the preview is 500 ms old. Diagnostics now log why previous app processes died (`EXIT` lines, Android 11+).
- Diagnostics log leaked the RTMP stream key (`startStream` line printed the full endpoint). Endpoints are now redacted to `scheme://host/app/***`.
- Android USB permission: `requestUsbPermission` returned `false` without a system dialog on Android 12+ for any device not already granted (libuvc 3.2.0 `USBMonitor.register()` fails on targetSdk 31+). The plugin now runs its own permission request and detach receiver; `usbDetached` events are delivered again. ADR 0021.

Fixed (device test 2026-09-15):
- `statusStream` with more than one listener: each extra listener used to take over the native event channel, so earlier listeners silently stopped receiving events. All listeners now share one stream.
- The configured `videoBitrate` was ignored after `initPreview` (encoder stuck at 2.5 Mbps). `initPreview` now prepares with `StreamConfig.videoBitrate`; a different bitrate in `configure` is applied at `startStream`; orientation re-prepare keeps the configured bitrate/fps/keyframe. New warning `STREAM_CONFIG_MISMATCH`.
- **Preset bitrates now follow YouTube's H.264 recommendations:** 720p 4 Mbps (was 2.5), 1080p 10 Mbps (was 4.5).

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
- Dynamic overlays (Android, M11, device-verified 2026-09-15):
  - `addOverlay` / `updateOverlay` / `hideOverlay` / `showOverlay` / `removeOverlay` / `clearOverlays` with `DynamicOverlay`, `ImageContent`, `OverlayPlacement`, `OverlayLength` (percent or px).
  - One z-ordered layer stack for sponsors, scoreband and dynamic overlays; new optional `weight` on `SponsorPlacement` (default 10) and `updateScoreband` (default 50).
  - Events `overlayShown` / `overlayHidden` / `overlayRemoved` and `RtmpStatus.overlayId`. **Breaking for exhaustive `switch` statements on `RtmpStatusType`: add the new cases.**
  - After a preview rebind, sponsors are now restored below the scoreband (previously they could end up above it).
  - Example app: Overlay Studio (mock-data scenarios, builder, active list, event log, stream HUD) and sponsor/scoreband weight controls.
  - Wrapped text: `TextOverlayStyle.maxLines` / `align` (`TextOverlayAlign`). `copyWith` on `OverlayPlacement`, `TextOverlayStyle`, `TextContent`, `TickerContent`.
  - Content types `GifContent`, `TextContent` (+ `TextOverlayStyle`, custom TTF), `TickerContent` (speed or cycle duration, loop or one pass, auto RTL). Errors `OVERLAY_GIF_TOO_LARGE`, `OVERLAY_FONT_INVALID`.
  - Enter/exit animations `OverlayAnimation` (slide / pop / curtain, 4 easings); `removeOverlay(animate:)`, `clearOverlays(animate:)`. `overlayRemoved` reason `completed`.
  - `DynamicOverlay.duration`: live-time-only auto-expiry (paused before go-live, during reconnects, after `stopStream`, while hidden; survives stop/start). `updateOverlay(duration: OverlayDurationUpdate…, restartTimer:)`. `overlayRemoved` reason `expired`.
- Sponsor carousel (Android, M12, device-verified 2026-09-15): `CarouselContent` / `CarouselItem` / `CarouselTransition` — images or GIFs rotating in one overlay slot with a global or per-item interval and `cut` / `crossfade` / `push` transitions. New error `OVERLAY_CAROUSEL_TOO_LARGE`. `OverlayContent` gained a subclass: exhaustive `switch` statements on it need a new case. Example: Overlay Studio carousel scenarios and builder.
- Camera zoom (Android, M13, device-verified on phone cameras 2026-09-15; UVC camera zoom not yet): `getZoom()` / `setZoom(level)` → `ZoomInfo`, phone cameras (Camera2) and UVC cameras with a zoom control. Zoom is kept across preview rebinds, orientation changes and reconnects and resets on `switchCamera`. Event `zoomChanged` (`RtmpStatus.zoom`) — **breaking for exhaustive `switch` on `RtmpStatusType`**. Errors `ZOOM_INVALID`, `ZOOM_NOT_READY`, `ZOOM_UNSUPPORTED`, `ZOOM_OPERATION_FAILED`; warning `ZOOM_REAPPLY_FAILED`. Example: pinch on preview, zoom slider and presets.
- Fixed: a dynamic overlay hidden before its first frame kept the per-frame overlay driver running while hidden.
- Fixed: `setAppOrientation` with a real portrait↔landscape flip switched a front camera to the back camera.
- Docs restructured for agents:
  - `docs/` holds specs, architecture, ADRs, plans, and reference.
  - `.claude/` holds path-scoped rules, skills, and shared settings.
- Docs (2026-09-15): README rewritten as a full guide and API reference (every method, model, event, error and warning code). ADRs 0018 (percent or px lengths), 0019 (preset bitrates), 0020 (working agreements). Rules and skills updated for dynamic overlays, carousel and zoom.
- Docs (2026-09-16): README section "Push a Flutter widget as an overlay" — the `RepaintBoundary` → PNG → `ImageContent` recipe, and `updateOverlay(content:)` as the dynamic-overlay equivalent of `updateScoreband`, with the id, limit and cost caveats. No API change; this already worked. Example: Overlay Studio "Widget capture" scenarios capture the real `ScoreBandView` and drive a dynamic overlay with it, plus a shared `captureBoundaryPng` helper.
