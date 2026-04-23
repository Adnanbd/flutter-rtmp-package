## YouTube Live RTMP Rules for Android Streaming App

---

### 📷 Camera Resolutions You Can Use

| Resolution | Dimensions | Aspect Ratio | Notes |
|---|---|---|---|
| 4K UHD | 3840×2160 | 16:9 | Requires powerful device + high bitrate |
| 1080p FHD | 1920×1080 | 16:9 | Most recommended |
| 720p HD | 1280×720 | 16:9 | Good balance |
| 480p | 854×480 | 16:9 | Low-end devices |
| 360p | 640×360 | 16:9 | Minimum usable |

> ⚠️ Android cameras give raw frames — you must **encode them yourself** before sending to RTMP.

---

### 🌄 Landscape Video Rules (Standard)

| Property | Rule |
|---|---|
| Dimensions | Width > Height (e.g. 1920×1080) |
| Aspect Ratio | **16:9** preferred |
| Codec | H.264 (AVC) |
| Keyframe Interval | Every **2 seconds** |
| Bitrate | 3–9 Mbps for 1080p |
| FPS | 24, 25, 30, 48, 50, 60 |
| Color Space | BT.709 |
| Chroma | 4:2:0 |
| Pixel Ratio | 1:1 (square pixels) |

✅ YouTube is **natively designed for landscape** — no issues, full player support.

---

### 📱 Vertical Video Rules (Portrait)

| Property | Rule |
|---|---|
| Dimensions | Height > Width (e.g. 1080×1920) |
| Aspect Ratio | **9:16** preferred |
| Codec | H.264 (AVC) |
| Keyframe Interval | Every **2 seconds** |
| Bitrate | Same as landscape equivalent |
| FPS | 24, 25, 30, 48, 50, 60 |

#### ⚠️ Vertical-Specific Gotchas

- YouTube **does support vertical (9:16)** streams natively now, but behavior depends on the viewer's device
- On **desktop/TV**, vertical streams show with **black bars** on both sides
- On **mobile**, YouTube app may display it **full screen vertically**
- You must send the correct **SAR (Sample Aspect Ratio)** metadata so YouTube knows it's intentional vertical
- Some encoders rotate the frame instead of truly encoding vertical — make sure your **width < height** in actual encoded frame, not just metadata
- Avoid sending a **rotated landscape frame with rotation metadata** — encode it truly as portrait dimensions

---

### 🎬 Video Encoding Rules (Android Specific)

| Property | Rule |
|---|---|
| Encoder | `MediaCodec` with `video/avc` (H.264) |
| Profile | `AVCProfileHigh` or `AVCProfileBaseline` |
| Level | `AVCLevel41` or `AVCLevel42` |
| I-Frame Interval | `2` seconds (set in `MediaFormat`) |
| Bitrate Mode | `BITRATE_MODE_CBR` (Constant Bitrate) |
| Color Format | `COLOR_FormatSurface` (camera surface input) |
| B-Frames | Set to `0` — disable B-frames for live streaming |

#### Android `MediaFormat` Key Settings
```java
mediaFormat.setInteger(MediaFormat.KEY_WIDTH, 1920);
mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, 1080);
mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000); // 4 Mbps
mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // Keyframe every 2s
mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
```

---

### 📡 RTMP Stream Rules

| Property | Value |
|---|---|
| Protocol | RTMP (not RTMPS unless you handle TLS) |
| RTMP URL | `rtmp://a.rtmp.youtube.com/live2` |
| Stream Key | From YouTube Studio |
| Recommended Library | **RootEncoder**, **librtmp**, or **HaishinKit** port |
| Chunk Size | 4096 bytes (default) |
| Audio + Video muxed | Yes — both in same RTMP connection |

---

### 📊 Recommended Presets for Android

| Target | Resolution | FPS | Video Bitrate | Total Bitrate |
|---|---|---|---|---|
| Low-end device | 720×1280 or 1280×720 | 24–30 | 1.5–2.5 Mbps | ~2 Mbps |
| Mid-range device | 1080×1920 or 1920×1080 | 30 | 3–5 Mbps | ~4.5 Mbps |
| Flagship device | 1920×1080 or higher | 60 | 6–9 Mbps | ~8 Mbps |

---

### ✅ Quick Checklist for Your App

- [ ] Keyframe every **2 seconds** (most critical rule)
- [ ] Use **CBR** bitrate mode
- [ ] Disable **B-frames**
- [ ] Encode truly as portrait dimensions if streaming vertical (don't just rotate)
- [ ] H.264 codec only
- [ ] Square pixels (1:1 SAR)
- [ ] Handle camera **orientation sensor** to switch encoding resolution dynamically
- [ ] Test on both **landscape and portrait** orientations before release