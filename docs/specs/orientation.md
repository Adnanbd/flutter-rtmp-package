# Spec — Orientation (Portrait vs Landscape)

**Source of truth (Android):** `CameraStreamManager.kt` → `configureGlForOrientation`,
`configureGlForDeviceCamera`, `configureGlForUvc`, `setAppOrientation`, `reinitializeForOrientation`.
Any change to the GL values below must update this file (code comments point here).

## Model

- App UI is portrait by default. Landscape streaming requires the user to **physically rotate** the
  device; sensor auto-rotate is off.
- Phone camera sensor frames are handled as portrait capture; the encoder dims decide output orientation.
- Orientation and resolution are **locked once live** — YouTube drops the session on mid-stream dim changes.
- `RtmpBroadcastWidget` is keyed on `MediaQuery.orientation`, so rotation recreates the platform view
  (→ `previewUnbound` then `previewBound`).

## Encoder dims

| Mode | 720p | 1080p |
|---|---|---|
| Portrait | 720×1280 | 1080×1920 |
| Landscape | 1280×720 | 1920×1080 |

## GL settings — phone camera (`Camera2Source`)

Always `autoHandleOrientation = false`.

| Setting | Portrait | Landscape |
|---|---|---|
| `setStreamIsPortrait` / `setPreviewIsPortrait` | `true` | `false` |
| `setStreamRotation` / `setPreviewRotation` | `270` | `0` |
| `genericStream.setOrientation` | `90` | `270` |

Key insight: landscape uses GL rotation `0` with `setOrientation(270)` so the capture is encoded at
1280×720 without an unwanted rotation. `270` stream rotation = 90° CCW (determined empirically).

## GL settings — UVC / USB camera (`UvcVideoSource`)

UVC frames arrive in the device's native landscape. Tunable constants in `configureGlForUvc`:

| Setting | Portrait | Landscape |
|---|---|---|
| stream/preview rotation | `90` | `0` |
| `setOrientation` | `0` | `0` |

Reusing phone values on UVC gives a white preview (portrait) or off-axis frame (landscape).

## `setAppOrientation(orientation)`

1. Sets `activity.requestedOrientation` (portrait / landscape lock).
2. `reinitializeForOrientation`:
   - Same orientation as current → only re-apply GL settings (avoids UVC reopen `nativeConnect=-99`).
   - Real flip → swap `encWidth`/`encHeight` (keeps 720p vs 1080p), `release` + `prepareVideo` with the configured bitrate, fps and keyframe
     + `prepareAudio`, GL settings, rebuild `OverlayFilterManager` with cached sponsors + scoreband,
     restore camera facing or UVC source.

Restoring portrait on back-navigation is the app's job (`SystemChrome.setPreferredOrientations`).

## Overlay impact
Overlays render pre-rotation; portrait needs the bitmap + coordinate transform in
[overlay-compositing.md](overlay-compositing.md#portrait-transform-isportrait--true-stream-rotation-270--90-ccw).

## iOS (not implemented — research for M5)
- HaishinKit `MediaMixer` / `StreamSession` orientation API; equivalent of `setOrientation(270)`.
- Whether an orientation change needs reconfiguration.
- Checklist: portrait fills screen · landscape fills screen · no unwanted rotation · dims correct both modes.
