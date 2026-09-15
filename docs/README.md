# Docs Index

Start with the root [CLAUDE.md](../CLAUDE.md) (agent boot) or [README.md](../README.md) (package users).

| Folder | Holds | Edit when | Rule |
|---|---|---|---|
| [specs/](specs/) | Contracts: what MUST be true now | Behavior, API, or wire format changes | Update in the **same change** as the code |
| [architecture/](architecture/) | How it's built: class maps, pipelines, threading | Files/classes added, moved, or rewired | Describe, don't prescribe |
| [decisions/](decisions/README.md) | ADRs: why | A design choice is made or reversed | Append-only; supersede, never rewrite |
| [plans/](plans/) | Milestones and checkboxes | A task starts or finishes | Progress only, no specs |
| [reference/](reference/) | External knowledge (YouTube rules, latency) and historical notes | Rarely | Not a contract |

## Read by task

| Task | Read |
|---|---|
| Add or change a channel method or event | [specs/channel-contract.md](specs/channel-contract.md), [specs/dart-api.md](specs/dart-api.md) → skill `add-channel-method` |
| Overlay wrong position, missing, or rotated | [specs/overlay-compositing.md](specs/overlay-compositing.md) → skill `debug-overlay` |
| Portrait/landscape or GL rotation | [specs/orientation.md](specs/orientation.md) → skill `orientation-change` |
| Reconnect or bitrate behavior | [specs/reconnect-and-bitrate.md](specs/reconnect-and-bitrate.md) |
| USB camera or mic | [specs/usb-sources.md](specs/usb-sources.md) |
| Field bug from a release build | [specs/diagnostics.md](specs/diagnostics.md) |
| Start the iOS implementation | [architecture/ios.md](architecture/ios.md), [plans/ios.md](plans/ios.md) → skill `ios-port` |
| Encoder settings for YouTube | [reference/youtube-rtmp.md](reference/youtube-rtmp.md), [reference/youtube-latency.md](reference/youtube-latency.md) |
| Finish any change | skill `sync-docs` |
| Release | skill `release-check` |

## Map

```
docs/
├── specs/          channel-contract · dart-api · overlay-compositing · orientation
│                   reconnect-and-bitrate · usb-sources · diagnostics
├── architecture/   overview · android · ios (target design)
├── decisions/      0001–0013 ADRs + template
├── plans/          roadmap (M1, M8–M10) · android (M2–M4) · ios (M5–M7)
└── reference/      youtube-rtmp · youtube-latency · sponsor-placement-request
```
