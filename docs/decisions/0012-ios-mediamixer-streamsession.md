# 0012 — iOS: HaishinKit `MediaMixer` + `StreamSession` via CocoaPods

- **Status:** Accepted (not yet implemented)
- **Date:** 2026-04-22

## Context
HaishinKit 2.x centralizes capture, mixing, and publishing with async/await. Direct
`RTMPConnection` + `RTMPStream` use is legacy.

## Decision
`MediaMixer` owns `AVCaptureSession`; publish through `StreamSession` built by
`StreamSessionBuilderFactory`. Dependency via CocoaPods (`HaishinKit ~> 2.2`), not SPM.

## Consequences
- Deployment target must satisfy HaishinKit 2.2.5 (currently 14.0; verify in M5.1).
