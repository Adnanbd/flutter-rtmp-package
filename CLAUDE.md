# CLAUDE.md — flutter_rtmp_broadcaster

Flutter plugin (Dart + Kotlin + Swift). It owns the device camera, composites static sponsor images and a
push-updated scoreband PNG onto live video **natively**, and broadcasts over RTMP.
The host app (Part A, not this repo) owns the UI and scoreband rendering. This package (Part B) owns camera → compositing → encoding → RTMP.

| | |
|---|---|
| Platforms | Android ✅ implemented (RootEncoder 2.7.2 `GenericStream`) · iOS ❌ stub (planned HaishinKit 2.x) |
| SDKs | Dart `>=3.10.0 <4.0.0`, Flutter `>=3.38.0`, Android minSdk 21, iOS 14.0 |
| Channels | `flutter_rtmp_broadcaster/control` (method) · `/status` (event) · `/camera_preview` (view) |

## Invariants (never break)

1. **Package owns the camera.** One native capture session end-to-end; preview is an output of the encoder pipeline. The host app never uses the `camera` package.
2. **Push, don't poll.** No `Timer.periodic` in the package. The app calls `updateScoreband(png)` when score data changes.
3. **Sponsors are static.** Sent in `configure()`, cached natively, re-applied after pipeline transitions.
4. **One transparent widget.** `RtmpBroadcastWidget` is a bare platform view: no UI chrome, overlays never visible in Flutter.
5. **Resolution-agnostic geometry.** Dart overlay coords are percent of the post-rotation stream frame; native converts with encoder dims.
6. **Wire contract parity.** Dart bridge, Kotlin plugin, Swift plugin, and `docs/specs/channel-contract.md` change together.
7. **Loud failures.** Native errors become `RtmpBroadcasterException` (method) and/or `error`/`warning` events with a stable code. Nothing fails silently.
8. **Android:** `GenericStream` only (never `RtmpCamera2`). Filters via `getGlInterface().addFilter`. Reconnect via `reTry`, never `startStream`.
9. **iOS:** `MediaMixer` + `StreamSession` + `ScreenObject`. No manual `CIContext.render` / `CVPixelBufferPool`.
10. Kotlin for Android, Swift for iOS. No `dart:mirrors`. Dart uses `invokeMethod`/`invokeListMethod`, never `invokeMapMethod`.

Path-specific rules load automatically from `.claude/rules/` when you touch `lib/`, `android/`, `ios/`, or `example/`.

## Where to look

| Need | File |
|---|---|
| Doc index and "read by task" table | `docs/README.md` |
| Big picture and component map | `docs/architecture/overview.md` |
| Method/event/error contract | `docs/specs/channel-contract.md` |
| Public Dart API | `docs/specs/dart-api.md` |
| Overlay math and GL gotchas | `docs/specs/overlay-compositing.md` |
| Portrait/landscape GL values | `docs/specs/orientation.md` |
| Reconnect, adaptive bitrate | `docs/specs/reconnect-and-bitrate.md` |
| USB camera/mic | `docs/specs/usb-sources.md` |
| Field diagnostics log | `docs/specs/diagnostics.md` |
| Android class map and call order | `docs/architecture/android.md` |
| iOS target design | `docs/architecture/ios.md` |
| Why a choice was made | `docs/decisions/` (ADRs) |
| Progress and next steps | `docs/plans/roadmap.md`, `android.md`, `ios.md` |
| **Active work: dynamic overlays (M11)** | `docs/plans/dynamic-overlays.md` — read its "Resume here" box first |
| Package user docs | `README.md` |

## Skills (`.claude/skills/`)

| Skill | Use for |
|---|---|
| `add-channel-method` | any new or changed method, arg, event, or error code |
| `debug-overlay` | overlay missing, misplaced, rotated, or gone in release |
| `orientation-change` | rotation, dims, portrait/landscape behavior |
| `ios-port` | M5–M7 iOS implementation |
| `sync-docs` | **end of every change** |
| `release-check` | pre-merge / pre-publish verification |

## Commands

```sh
flutter pub get
flutter analyze && flutter test                  # package
cd example && flutter run                         # physical device required (camera)
cd example && flutter build appbundle --release   # verifies R8 keeps for overlays
adb logcat -s CameraStreamManager OverlayFilterManager DiagLogger
```

## Definition of done

- `flutter analyze` clean, `flutter test` passing.
- Native changes checked on a physical device, in the **output stream** (not just preview), when behavior is visual.
- Docs synced via the `sync-docs` skill:
  - specs match code
  - plan checkboxes ticked and Current State date refreshed
  - README updated for any API, setup, error code, orientation, or limitation change
  - ADR added for any design decision
  - CHANGELOG `Unreleased` entry added
- Use absolute dates in docs. Never commit the RTMP stream key or log it.
