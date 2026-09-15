---
name: release-check
description: Pre-release / pre-merge verification for flutter_rtmp_broadcaster — analyze, tests, example builds (debug + release with R8), pub publish dry-run, CHANGELOG and version bump, doc drift. Use when the user says "release", "publish", "cut a version", "ready to merge?", "pre-release check", or before tagging.
---

# Release check

Run from the repo root. Report each step as pass/fail with the output. Don't skip steps silently.

## 1. Static
```sh
flutter pub get
flutter analyze
flutter test
(cd example && flutter analyze && flutter test)
(cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest)   # Kotlin JVM tests
```

## 2. Builds
```sh
cd example
flutter build apk --debug
flutter build appbundle --release     # R8 on: the overlay + ZoomableUvcCamera keeps in consumer-rules.pro must hold
flutter build ios --no-codesign        # only once iOS is implemented; note stub status otherwise
```
Release build is mandatory: overlays have silently vanished under R8 before.

## 3. Device smoke (ask the user if no device is attached)
Stream to a test RTMP endpoint and check:
- Preview works.
- Sponsors and scoreband are visible **in the output stream**.
- Overlay Studio: a scenario of each kind (image/GIF/text/ticker/carousel, animations, duration) shows in the output stream.
- Camera flip, mute and pinch zoom work live; zoom survives background → foreground.
- Background → foreground recovers the preview and all overlay layers.
- Kill network → `reconnecting` ×N → recovers or `MAX_RECONNECT_EXCEEDED`.
- Milestone device checklists in `docs/plans/` are ticked (ADR 0020).

Use a release build for this.

## 4. Package hygiene
```sh
flutter pub publish --dry-run
```
- No `.claude/`, `CLAUDE.md`, `docs/`, or build artifacts in the file list (`.pubignore`; `docs/` is excluded because pub expects `doc/`).
- `pubspec.yaml` version == `ios/flutter_rtmp_broadcaster.podspec` `s.version`; bump both.
- `CHANGELOG.md`: move `## Unreleased` → `## x.y.z — YYYY-MM-DD`.

## 5. Docs
- Run skill `sync-docs` checks (links + channel drift).
- README Platform Support and Known Limitations match reality.
- `docs/plans/roadmap.md` Current State date refreshed.

## 6. Tag (only when the user explicitly asks)
`git tag vX.Y.Z`. Push only with explicit approval.
