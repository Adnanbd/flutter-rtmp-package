# Architecture Decision Records

Why things are the way they are. **Append-only**: never rewrite an accepted ADR — add a new one that
supersedes it and set the old one's status to `Superseded by NNNN`.

Template: copy [0000-template.md](0000-template.md), take the next number.

| # | Decision | Status |
|---|---|---|
| [0001](0001-package-owns-camera.md) | Package owns the camera; single session end-to-end | Accepted |
| [0002](0002-push-model-scoreband.md) | Scoreband is push-based PNG, no timers | Accepted |
| [0003](0003-sponsors-sent-at-configure.md) | Sponsors sent once at configure, cached natively | Accepted |
| [0004](0004-percentage-overlay-coordinates.md) | Overlay coords are resolution-agnostic percentages | Accepted |
| [0005](0005-sponsor-placement-edge-anchors.md) | Sponsor placement = edge anchors + BoxFit.contain | Accepted |
| [0006](0006-methodchannel-for-overlay-bytes.md) | StandardMethodCodec for overlay bytes | Accepted |
| [0007](0007-default-720p-portrait.md) | Default config 720×1280 portrait, YouTube presets | Accepted |
| [0008](0008-android-genericstream.md) | Android: RootEncoder `GenericStream`, not `RtmpCamera2` | Accepted |
| [0009](0009-android-textureview-preview.md) | Android: plain `TextureView` preview | Accepted |
| [0010](0010-android-lazy-scoreband-filter.md) | Android: lazy scoreband filter, setImage before addFilter | Accepted |
| [0011](0011-android-reconnect-via-retry.md) | Android: reconnect via `StreamClient.reTry`, never `startStream` | Accepted |
| [0012](0012-ios-mediamixer-streamsession.md) | iOS: HaishinKit `MediaMixer` + `StreamSession` via CocoaPods | Accepted |
| [0013](0013-ios-screenobject-overlays.md) | iOS: `ScreenObject` overlays, no manual CoreImage | Accepted |
