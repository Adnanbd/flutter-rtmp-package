# Spec — Auto-Reconnect & Adaptive Bitrate

**Source of truth (Android):** `CameraStreamManager.kt` (`scheduleReconnect`, `applyStreamClientDefaults`,
`onNewBitrate`, `startStream`, `stopStream`), `rtmp/RtmpConnectChecker.kt`.

## Auto-reconnect contract (both platforms must match)

| Rule | Value |
|---|---|
| Max attempts | 3 (`MAX_RECONNECT_ATTEMPTS`) |
| Delay | 3 s (`RECONNECT_DELAY_MS`) |
| Per attempt | event `{type: reconnecting, attempt: N}` (1-based) |
| After exhaustion | event `{type: error, code: MAX_RECONNECT_EXCEEDED}`, stream stopped so `startStream()` works again |
| On success | `connected` event, attempt counter reset |
| `stopStream()` / `release()` | sets `intentionalStop`, cancels any in-flight retry, no reconnect |

Trigger: `onConnectionFailed(reason)` or `onDisconnect()` → `disconnected` event → `scheduleReconnect`.

## Android implementation

- Retry budget: `getStreamClient().setReTries(3)` after every `prepareVideo` and again in `startStream()`
  (resets the library counter each session).
- Retry: `getStreamClient().reTry(3000, reason)`. Returns `false` when budget exhausted.
- **Never call `genericStream.startStream()` to reconnect.** After a socket drop `StreamBase.isStreaming`
  stays true, so it throws `IllegalStateException: Stream already started` on the main thread and
  crashes the app (field report 2026-07-30). `reTry` reconnects in place on its own thread.
- Encoder, preview, and overlay filters are untouched during reconnect.

## Adaptive bitrate (Android only)

- `com.pedro.library.util.BitrateAdapter` → listener calls `genericStream.setVideoBitrateOnFly(bitrate)`.
- `setMaxBitrate(videoBitrate)` + `reset()` in `applyStreamClientDefaults`; `reset()` again on `startStream`.
- `RtmpConnectChecker.onNewBitrate(bps)` → emits `{type: bitrate, kbps}` → `bitrateAdapter.adaptBitrate(bps, hasCongestion())`.
- Configured `videoBitrate` is a **ceiling**. `bitrate` events vary during a stream by design.
- Audio fixed at 128 kbps AAC, 44.1 kHz stereo.
- Rationale: field report showed ~2 min of `RtmpSender: Video/Audio frame discarded` then `Broken pipe`
  when uplink fell below a fixed 2.5 Mbps.

## iOS (not implemented — M7.5)
Rebuild/reopen `StreamSession` or use its republish API; cancel the retry `Task` on `stopStream()`;
payloads identical to Android.
