# 0007 — Default config 720×1280 portrait, YouTube presets

- **Status:** Accepted (supersedes original 1280×720 landscape default); bitrate values superseded by 0019
- **Date:** 2026-04-25

## Context
Primary use is phone-held vertical streaming to YouTube, which drops sessions on mid-stream dimension changes.

## Decision
`StreamConfig.defaultConfig = youtube720Portrait`. Four presets (720p/1080p × portrait/landscape), 30 fps,
2 s keyframe, 2.5 / 4.5 Mbps. Resolution and orientation are chosen before going live and locked while streaming.

## Consequences
- Camera flip and mute are live-safe; resolution/orientation changes are not.
