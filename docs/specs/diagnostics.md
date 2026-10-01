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
- Never log the RTMP stream key or the full `rtmpEndpoint` (it contains the key). Log `EndpointRedactor.redact(endpoint)`
  (`scheme://host/app/***`) instead. The `startStream` line leaked it until 2026-09-30.
- `emitWarn` in `CameraStreamManager` also writes the warning to the file.
- On plugin attach (API 30+) `DiagLogger` logs why earlier processes of the app died (`ActivityManager.getHistoricalProcessExitReasons`),
  newest 5 not yet logged: `EXIT | at=… reason=CRASH_NATIVE status=<signal> importance=… description=…`. Marker file
  `rtmp_diag_exit.ts`. Native crashes leave no other line, so this is how they show up in an export.
- Periodic summaries are the only allowed hot-path logging: `UsbAudioSource` `pcm:` (5 s) and `mics[periodic]:` (30 s)
  while USB audio records; `CameraStreamManager` `stream:` (5 s) while streaming: `sentVideo`, `sentAudio`,
  `droppedVideo`, `droppedAudio`, `bytesSent`, `cache`, `audioSrc` from `GenericStreamClient`. These show what
  actually leaves the phone.

## Triage recipe
1. Reproduce, then `exportDiagnostics()` (or `adb shell run-as <pkg> cat files/rtmp_diag.log`).
2. Search for `ERROR/`, then read the `CameraStreamManager` lines before it: `isPreviewReady`, `isOnPreview`, `filters=`, `src=`.
3. Match codes against [channel-contract.md](channel-contract.md#error-codes).
