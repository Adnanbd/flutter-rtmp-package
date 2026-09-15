---
name: add-channel-method
description: End-to-end checklist for adding or changing a MethodChannel method, argument, return value, EventChannel event type, or error code in flutter_rtmp_broadcaster. Use when the user asks to "add a method", "expose X to Dart", "add an event", "new error code", "change the payload", or touches method_channel_bridge.dart / FlutterRtmpBroadcasterPlugin.kt onMethodCall.
---

# Add / change a channel method or event

The wire contract is `docs/specs/channel-contract.md`. Dart and native must never drift. Do every step
in one change.

## 1. Design first
- Read `docs/specs/channel-contract.md` and `docs/specs/dart-api.md`.
- Pick a camelCase method name and arg keys. Reuse existing key names (`facing`, `deviceId`, `orientation`) where the meaning matches.
- Decide the return type, error codes, and whether it's live-safe (while streaming).
- Android-only feature? Decide now what iOS returns (empty/false/placeholder vs `notImplemented`) and record it.

## 2. Dart
1. `lib/src/channels/method_channel_bridge.dart` — add the bridge method (`invokeMethod`, or `invokeListMethod` for lists).
2. `lib/src/rtmp_broadcast_controller.dart` — public method with `///` doc comment, `try` / `on PlatformException catch (e) { throw RtmpBroadcasterException(e.code, e.message ?? ''); }`.
3. New model → `lib/src/models/`, export in `lib/flutter_rtmp_broadcaster.dart`.
4. New event → add value to `RtmpStatusType`. Add fields to `RtmpStatus.fromMap` if the payload has new keys.

## 3. Android
1. `FlutterRtmpBroadcasterPlugin.kt` `onMethodCall` → `"name" -> handleName(call, result)`.
2. Handler: validate args (`INVALID_ARGS`), null-check `cameraStreamManager` (`NOT_CONFIGURED` / `NO_MANAGER`), `try/catch` → `DiagLogger.logError(CODE, …)` + `result.error(CODE, …)`.
3. Put the logic in the owning manager (`CameraStreamManager`, `OverlayFilterManager`, `UsbDeviceRegistry`), not the plugin.
4. Events: `connectChecker.sendEvent(mapOf("type" to "...", ...))`. Never call `eventSink` directly from managers.

## 4. iOS
- If iOS is implemented: mirror the handler in `ios/Classes/FlutterRtmpBroadcasterPlugin.swift` with identical keys.
- If still a stub: mark the method ❌ in the spec. Add an item under the right milestone in `docs/plans/ios.md`.

## 5. Tests
- `test/rtmp_broadcast_controller_test.dart` (or the feature file, e.g. `test/dynamic_overlay_test.dart`, `test/zoom_test.dart`): assert `calls.last.method` and `arguments` map, and Dart-side validation codes.
- Model parsing → `test/models/`.
- Native logic belongs in a pure-Kotlin class (clock/scheduler injected) with a JVM test in `android/src/test/kotlin/…`.
- Run `flutter analyze lib test`, `flutter test`, and `cd example/android && ./gradlew :flutter_rtmp_broadcaster:testDebugUnitTest`.

## 6. Docs (same change)
- `docs/specs/channel-contract.md`: method row with Android/iOS status, arg keys, events, and error codes.
- `docs/specs/dart-api.md`: controller table. Feature spec too (`dynamic-overlays.md`, `camera-zoom.md`) when it applies.
- `README.md`: controller method table, guide section with an example, `RtmpStatus`/`RtmpStatusType` tables for events, error/warning tables.
- Example app hook, usable before and during a stream (ADR 0020): overlay features in `example/lib/overlay_studio/`, camera features on the Go Live screen.
- `CHANGELOG.md`: note breaking changes for exhaustive `switch` (new `RtmpStatusType` values).
- Then run skill `sync-docs`.

## Verify drift
```sh
grep -oE '"[a-zA-Z]+" ->' android/src/main/kotlin/com/flutterrtmp/broadcaster/FlutterRtmpBroadcasterPlugin.kt \
  | tr -d '"> -' | grep -v '^scoreband$' | sort > /tmp/k
tr '\n' ' ' < lib/src/channels/method_channel_bridge.dart \
  | grep -oE "invoke[A-Za-z]*Method[^(]*\( *'[a-zA-Z]+'" | grep -oE "'[a-zA-Z]+'" | tr -d "'" | sort > /tmp/d
diff /tmp/k /tmp/d && for m in $(cat /tmp/k); do grep -q "\`$m\`" docs/specs/channel-contract.md || echo "spec missing: $m"; done
```
Every name must also appear in `docs/specs/channel-contract.md`.
