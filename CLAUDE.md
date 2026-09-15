# CLAUDE.md — flutter_rtmp_broadcaster

Flutter plugin (Dart + Kotlin + Swift). It owns the device camera (including zoom), composites overlays onto live
video **natively** — static sponsor images, a push-updated scoreband PNG, and app-controlled dynamic overlays (image,
GIF, text, ticker, carousel) — and broadcasts over RTMP.
The host app (Part A, not this repo) owns the UI, gestures and scoreband rendering. This package (Part B) owns camera → compositing → encoding → RTMP.

| | |
|---|---|
| Platforms | Android ✅ implemented (RootEncoder 2.7.2 `GenericStream`); dynamic overlays, carousel and zoom device-verified 2026-09-15 (UVC camera zoom not yet) · iOS ❌ stub (planned HaishinKit 2.x) |
| SDKs | Dart `>=3.10.0 <4.0.0`, Flutter `>=3.38.0`, Android minSdk 21, iOS 14.0 |
| Channels | `flutter_rtmp_broadcaster/control` (method) · `/status` (event) · `/camera_preview` (view) |

## Invariants (never break)

1. **Package owns the camera.** One native capture session end-to-end; preview is an output of the encoder pipeline. The host app never uses the `camera` package.
2. **Push, don't poll.** No `Timer.periodic` or polling in Dart. The app calls `updateScoreband(png)` / `updateOverlay` when data changes. Durations, tickers, GIFs, carousels, animations and zoom re-apply are driven natively.
3. **Sponsors are static.** Sent in `configure()`, cached natively, re-applied after pipeline transitions. Rotating sponsors = a carousel dynamic overlay.
4. **One transparent widget.** `RtmpBroadcastWidget` is a bare platform view: no UI chrome, no gestures, overlays never visible in Flutter.
5. **Resolution-agnostic geometry.** Relative to the post-rotation stream frame: integer percent for sponsors and scoreband; percent or encoder px per field for dynamic overlays (ADR 0018). Native converts with encoder dims.
6. **One layer stack.** Every overlay layer lives in `LayerStack`, ordered by weight 0–100 (defaults sponsor 10, scoreband 50, dynamic 50; ties: sponsor < scoreband < dynamic, then later on top). Ids `scoreband` and `sponsor_*` are reserved.
7. **Wire contract parity.** Dart bridge, Kotlin plugin, Swift plugin, and `docs/specs/channel-contract.md` change together.
8. **Loud failures.** Native errors become `RtmpBroadcasterException` (method) and/or `error`/`warning` events with a stable code. Nothing fails silently.
9. **Android:** `GenericStream` only (never `RtmpCamera2`). Filters only through `LayerStack`. Reconnect via `reTry`, never `startStream`. Zoom happens in the camera source and only `ZoomController` writes it (ADR 0017).
10. **iOS:** `MediaMixer` + `StreamSession` + `ScreenObject`. No manual `CIContext.render` / `CVPixelBufferPool`.
11. Kotlin for Android, Swift for iOS. No `dart:mirrors`. Dart uses `invokeMethod`/`invokeListMethod`, never `invokeMapMethod`.

Path-specific rules load automatically from `.claude/rules/` when you touch `lib/`, `android/`, `ios/`, or `example/`.

## Working agreements (ADR 0020)

- Big features: ask questions until requirements are fully clear; suggest, don't assume. Record every agreed decision where it is enforced (spec, ADR, or `.claude/rules`) and in the plan's decision table.
- Track work in the milestone plan: keep its "Resume here" box and checkboxes current as work lands.
- Dynamic overlays, carousel and zoom (M11–M13) are Android only; iOS parity backlog in `docs/plans/ios.md`.
- No device spikes. Device checks are batched at the end of a milestone; until they pass, docs say "not device-verified".
- M11–M13 merged to `main` 2026-09-15 (not published to pub.dev). **The user commits manually**: don't commit, push, tag, merge or publish unless asked. Merge only on approval.
- Every new capability gets an example hook usable before and during a stream, with mock data (Overlay Studio / Go Live screen).

## Where to look

| Need | File |
|---|---|
| Doc index and "read by task" table | `docs/README.md` |
| Big picture and component map | `docs/architecture/overview.md` |
| Method/event/error contract | `docs/specs/channel-contract.md` |
| Public Dart API | `docs/specs/dart-api.md` |
| Sponsor/scoreband math and GL gotchas | `docs/specs/overlay-compositing.md` |
| Dynamic overlays + carousel | `docs/specs/dynamic-overlays.md` |
| Camera zoom | `docs/specs/camera-zoom.md` |
| Portrait/landscape GL values | `docs/specs/orientation.md` |
| Reconnect, adaptive bitrate | `docs/specs/reconnect-and-bitrate.md` |
| USB camera/mic | `docs/specs/usb-sources.md` |
| Field diagnostics log | `docs/specs/diagnostics.md` |
| Android class map and call order | `docs/architecture/android.md` |
| iOS target design | `docs/architecture/ios.md` |
| Why a choice was made | `docs/decisions/` (ADRs) |
| Progress and next steps | `docs/plans/roadmap.md`, `android.md`, `ios.md` |
| **Active work: dynamic overlays (M11)** | `docs/plans/dynamic-overlays.md` — read its "Resume here" box first |
| **Active work: carousel + zoom (M12/M13)** | `docs/plans/carousel-and-zoom.md` — read its "Resume here" box first |
| Package user docs (full guide + API reference) | `README.md` |

## Skills (`.claude/skills/`)

| Skill | Use for |
|---|---|
| `add-channel-method` | any new or changed method, arg, event, or error code |
| `debug-overlay` | sponsor, scoreband or dynamic overlay missing, misplaced, mis-ordered, not animating, or gone in release |
| `orientation-change` | rotation, dims, portrait/landscape behavior |
| `ios-port` | M5–M7 iOS implementation |
| `sync-docs` | **end of every change** |
| `release-check` | pre-merge / pre-publish verification |

## Commands

```sh
flutter pub get
flutter analyze lib test && flutter test                                        # package
(cd example && flutter test)                                                    # example (analyze has known old warnings)
(cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest)   # Kotlin JVM tests
cd example && flutter run                                                       # physical device required (camera)
cd example && flutter build appbundle --release                                 # verifies R8 keeps for overlays
adb logcat -s CameraStreamManager OverlayFilterManager DiagLogger UvcVideoSource
```

## Definition of done

- `flutter analyze lib test` clean; `flutter test`, example tests and Kotlin unit tests passing.
- Native changes checked on a physical device, in the **output stream** (not just preview), when behavior is visual — batched at the end of the milestone (ADR 0020).
- Docs synced via the `sync-docs` skill:
  - specs match code
  - user decisions recorded where they are enforced (spec / ADR / rule)
  - plan checkboxes ticked, "Resume here" box and Current State date refreshed
  - README updated for any API, setup, error code, orientation, limit, or limitation change
  - ADR added for any design decision
  - CHANGELOG `Unreleased` entry added
- Use absolute dates in docs. Never commit the RTMP stream key or log it.
