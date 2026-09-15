# YouTube Live Latency — Why scoreband updates take 18–20s to appear

When you push a new scoreband PNG via `controller.updateScoreband()`, the new graphic appears on the YouTube viewer's screen ~18–20 seconds later. **This is normal.** YouTube's live pipeline dominates the latency budget, not the plugin.

---

## End-to-End Latency Breakdown

| Stage | Latency | Where |
|---|---|---|
| Dart capture + PNG encode (`RepaintBoundary.toImage` → `toByteData`) | ~50–150ms | App |
| Method channel hop + native composite + H.264 encode | ~30–100ms | Plugin |
| RTMP push → YouTube ingest | ~1–3s | Network |
| **YouTube transcode (ABR ladders: 1080p / 720p / 480p / …)** | **5–10s** | YouTube |
| **YouTube CDN edge distribution** | **2–5s** | YouTube |
| **Viewer player buffer** | **3–15s** | Viewer |

**Sum: 11–33s typical.** 18–20s falls inside YouTube **"Normal Latency"** mode (the default).

---

## Reduce Latency

In **YouTube Studio → Stream → Settings → "Stream latency"**:

| Mode | Glass-to-glass | Trade-off |
|---|---|---|
| Normal Latency (default) | ~30s | Most stable; full DVR / captions |
| **Low Latency** | ~10–15s | Recommended for live scoring |
| **Ultra Low Latency** | ~2–5s | Smallest viewer buffer; disables some features (DVR, captions); less robust on poor networks |

Switch to **Low** or **Ultra Low** before going live.

---

## Plugin-Side Knobs (Minor Impact)

These shave at most a second or two — YouTube is still the bottleneck.

- **Keyframe interval** — `StreamConfig.keyframeIntervalSeconds` (default `2`). Lower = faster keyframe alignment for the player → less initial buffer. Don't go below `1` (kills quality, increases bitrate).
- **Bitrate** — Higher bitrate → faster decode-start at viewer, more upload pressure on uplink.
- **FPS** — Default `30`. Raising to `60` doesn't reduce latency (frame is delivered just as quickly).
- **Resolution** — 720p uploads faster than 1080p; if uplink is constrained, drop resolution to avoid buffering on the RTMP push side.

`controller.updateScoreband()` itself returns in **<50ms** (method-channel hop + `BitmapFactory.decodeByteArray` + GPU texture upload to the existing `ImageObjectFilterRender`). Negligible compared to the YouTube pipeline.

---

## Verifying the Bottleneck

1. Switch YouTube latency to **Ultra Low**.
2. Push the same scoreband on a known timestamp.
3. Open the YouTube watch URL in another window. Watch the scoreband appear ~3–5s later.

If it appears in 3–5s on Ultra Low and 18–20s on Normal → confirms YouTube pipeline dominates. Plugin is already as fast as it can be.

If even Ultra Low is >10s → check uplink upload bandwidth (`speedtest.net` while streaming) and RTMP server region. Pick a YouTube ingest URL closer to your geographic region.

---

## Why You Cannot Beat ~2s

Two unavoidable floors:

1. **Encoder GOP** — H.264 viewer can only start decoding from a keyframe. With a 2s keyframe interval, average wait = ~1s.
2. **Network buffer** — Even Ultra Low mode keeps a small player-side buffer to absorb network jitter. ~1–2s.

For sub-second latency you need WebRTC (different protocol entirely, not RTMP). YouTube does not support WebRTC ingest. Use Twitch with low-latency mode or a custom WebRTC SFU if sub-second is a hard requirement.

---

## Push Strategy

Don't try to "catch up" by pushing scoreband faster than data changes. Plugin re-encodes on every `updateScoreband()` call. Push **only when score data actually changes** (every 2–3s typical for cricket / ball-by-ball updates). Higher push rate burns CPU and adds nothing — viewer still sees same delayed feed.
