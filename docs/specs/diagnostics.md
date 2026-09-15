# Spec — Diagnostics Log (Android only)

**Source of truth:** `android/.../diag/DiagLogger.kt`.

Purpose: field debugging on release builds where logcat is unavailable or `Log.d`/`Log.w` is stripped.

## Behavior
- Singleton `object DiagLogger`, initialized in `onAttachedToEngine`; also installs a chaining uncaught-exception handler (logs `ERROR/UNCAUGHT`).
- File: `<app filesDir>/rtmp_diag.log`, rotated to `rtmp_diag.log.1` above 256 KB (max ~512 KB on disk).
- Line format: `yyyy-MM-ddTHH:mm:ss.SSS | <thread> | <tag> | <message>` + stack trace if any.
- `log(tag, msg)` → `Log.d` + file. `logError(code, msg, t?)` → `Log.e` + file with tag `ERROR/<code>`.
- Thread-safe writes (`synchronized`).

## Dart API
- `controller.exportDiagnostics(): Future<String>` — rotated file + current file concatenated;
  `(log is empty)` / `(DiagLogger not initialized)` placeholders. Never throws.
- `controller.clearDiagnostics()` — deletes both files.
- Example app: "export diagnostics" in `example/lib/screens/rtmp_config_screen.dart`.

## Conventions for new native code
- Use `DiagLogger.logError(CODE, …)` on every failure path that also emits an `error` event or returns
  `result.error(CODE, …)`, with the same `CODE`.
- Use `DiagLogger.log` for pipeline state transitions (preview bind/unbind, source changes, startStream state).
- Keep hot paths (per-frame, per-bitrate callback) on plain `Log.d`, not the file.
- Never log the RTMP stream key. ⚠ `CameraStreamManager.startStream` currently logs `ep=$rtmpEndpoint`, which includes the key.

## Triage recipe
1. Reproduce, then `exportDiagnostics()` (or `adb shell run-as <pkg> cat files/rtmp_diag.log`).
2. Search for `ERROR/`, then read the `CameraStreamManager` lines before it: `isPreviewReady`, `isOnPreview`, `filters=`, `src=`.
3. Match codes against [channel-contract.md](channel-contract.md#error-codes).
