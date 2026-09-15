import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'channels/event_channel_bridge.dart';
import 'channels/method_channel_bridge.dart';
import 'models/dynamic_overlay.dart';
import 'models/rtmp_broadcaster_exception.dart';
import 'models/rtmp_status.dart';
import 'models/sponsor_overlay.dart';
import 'models/stream_config.dart';
import 'models/usb_device_info.dart';
import 'models/zoom_info.dart';

class RtmpBroadcastController {
  RtmpBroadcastController()
      : _method = MethodChannelBridge(),
        _event = EventChannelBridge();

  final MethodChannelBridge _method;
  final EventChannelBridge _event;

  final previewBound = ValueNotifier<bool>(false);

  StreamConfig? _config;
  StreamConfig get config => _config!;

  /// Status events, minus the preview bind/unbind pair — those are folded into
  /// [previewBound] instead of being pushed at every listener.
  ///
  /// [RtmpStatusType.previewUnbound] is what keeps [previewBound] honest across a
  /// background/foreground cycle: the Android surface is torn down while the app
  /// is away, and without this event the flag would still read `true` on return,
  /// with nothing behind it.
  Stream<RtmpStatus> get statusStream => _event.statusStream.where((s) {
        if (s.type == RtmpStatusType.previewBound) {
          previewBound.value = true;
          return false;
        }
        if (s.type == RtmpStatusType.previewUnbound) {
          previewBound.value = false;
          return false;
        }
        return true;
      });

  Future<void> initPreview({StreamConfig? config}) async {
    previewBound.value = false;
    final cfg = config ?? StreamConfig.defaultConfig;
    try {
      await _method.initPreview({
        ...cfg.toMap(),
      });
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> configure({
    required String rtmpUrl,
    required String rtmpKey,
    required List<SponsorOverlay> sponsors,
    required StreamConfig config,
  }) async {
    if (rtmpUrl.isEmpty) {
      throw const RtmpBroadcasterException('INVALID_URL', 'rtmpUrl must not be empty');
    }
    if (rtmpKey.isEmpty) {
      throw const RtmpBroadcasterException('INVALID_KEY', 'rtmpKey must not be empty');
    }
    try {
      await _method.configure({
        'rtmpEndpoint': '$rtmpUrl/$rtmpKey',
        'sponsors': sponsors.map((s) => s.toMap()).toList(),
        ...config.toMap(),
      });
      _config = config;
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  /// Push a new scoreband image. [weight] is its z-order (0 back – 100 front,
  /// default 50); see [DynamicOverlay] for ordering rules.
  Future<void> updateScoreband(
    Uint8List pngBytes, {
    int width = 90,
    int x = 50,
    int y = 100,
    int weight = 50,
  }) async {
    validateOverlayWeight(weight);
    try {
      await _method.updateOverlay('scoreband', pngBytes,
          width: width, x: x, y: y, weight: weight);
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> startStream() async {
    try {
      await _method.startStream();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> stopStream() async {
    try {
      await _method.stopStream();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> switchCamera({required CameraFacing facing}) async {
    try {
      await _method.switchCamera(
          facing == CameraFacing.front ? 'front' : 'back');
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  /// Current camera zoom. Android only.
  ///
  /// Throws `ZOOM_NOT_READY` before `initPreview`/`configure`, or while the
  /// camera is still opening after [previewBound] — retry shortly.
  Future<ZoomInfo> getZoom() async {
    try {
      return ZoomInfo.fromMap(await _method.getZoom());
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  /// Zoom the camera to [level] (ratio, e.g. 2.0 = 2×), clamped to
  /// [ZoomInfo.min]–[ZoomInfo.max]. Returns the applied state. Android only.
  ///
  /// The zoom is kept across preview rebinds, orientation changes and
  /// reconnects, and resets to 1.0 on [switchCamera]; both are reported with
  /// [RtmpStatusType.zoomChanged]. Safe to call on every pinch update.
  ///
  /// Throws `ZOOM_INVALID` (not a finite number > 0), `ZOOM_NOT_READY`, or
  /// `ZOOM_UNSUPPORTED` (camera has no zoom control and [level] is not 1.0).
  Future<ZoomInfo> setZoom(double level) async {
    if (level.isNaN || level.isInfinite || level <= 0) {
      throw RtmpBroadcasterException('ZOOM_INVALID', 'zoom level must be a finite number > 0 (got $level)');
    }
    try {
      return ZoomInfo.fromMap(await _method.setZoom(level));
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  /// Re-attach the GL preview to the platform view currently on screen.
  ///
  /// For recovering a preview that went stale while the app was backgrounded,
  /// without the cost of `initPreview` + `configure` — the encoder, the overlay
  /// filters and any running RTMP session are left alone. [previewBound] goes
  /// true again through the usual event once the native side rebinds.
  ///
  /// Throws `NO_PREVIEW_VIEW` when no preview is mounted and
  /// `SURFACE_UNAVAILABLE` when its SurfaceTexture is not ready yet; both mean
  /// "wait for the view, or fall back to a full re-init".
  Future<void> rebindPreview() async {
    try {
      await _method.rebindPreview();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> setAudioMuted(bool muted) async {
    try {
      await _method.setAudioMute(muted);
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> updateSponsors(List<SponsorOverlay> sponsors) async {
    try {
      await _method.updateSponsors(
          sponsors.map((s) => s.toMap()).toList());
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<void> setAppOrientation(VideoOrientation orientation) async {
    try {
      await _method.setAppOrientation(orientation.name);
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<List<UsbDeviceInfo>> listUsbVideoDevices() async {
    try {
      final raw = await _method.listUsbVideoDevices();
      return raw.map(UsbDeviceInfo.fromMap).toList();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<List<UsbAudioDeviceInfo>> listUsbAudioDevices() async {
    try {
      final raw = await _method.listUsbAudioDevices();
      return raw.map(UsbAudioDeviceInfo.fromMap).toList();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<bool> requestUsbPermission(int deviceId) async {
    try {
      return await _method.requestUsbPermission(deviceId);
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  Future<String> exportDiagnostics() async {
    try {
      return await _method.exportDiagnostics();
    } on PlatformException catch (e) {
      return 'Error reading diagnostics: ${e.message}';
    }
  }

  Future<void> clearDiagnostics() => _method.clearDiagnostics();

  /// Add a dynamic overlay (Android). Available after [initPreview] or
  /// [configure]. Emits [RtmpStatusType.overlayShown].
  ///
  /// Throws [RtmpBroadcasterException]: `OVERLAY_ID_EXISTS`,
  /// `OVERLAY_ID_RESERVED`, `OVERLAY_LIMIT_REACHED` (16), `OVERLAY_DECODE_FAILED`,
  /// `OVERLAY_GIF_TOO_LARGE`, `OVERLAY_FONT_INVALID`,
  /// `OVERLAY_INVALID_CONTENT`, `OVERLAY_INVALID_PLACEMENT`,
  /// `OVERLAY_NOT_INITIALIZED`.
  Future<void> addOverlay(DynamicOverlay overlay) async {
    overlay.validate();
    await _guard(() => _method.overlayAdd(overlay.toMap()));
  }

  /// Change an overlay in place. Omitted arguments stay unchanged; changing
  /// [weight] re-orders the layer immediately. Throws `OVERLAY_NOT_FOUND` for
  /// unknown ids.
  ///
  /// The live-time timer keeps running across updates. [duration] sets a new
  /// total (see [OverlayDurationUpdate]); [restartTimer] counts from zero again.
  Future<void> updateOverlay(
    String id, {
    OverlayContent? content,
    OverlayPlacement? placement,
    int? weight,
    OverlayDurationUpdate? duration,
    bool restartTimer = false,
  }) async {
    validateOverlayId(id);
    content?.validate();
    placement?.validate();
    if (weight != null) validateOverlayWeight(weight);
    duration?.validate();
    await _guard(() => _method.overlayUpdate({
          'id': id,
          if (content != null) 'content': content.toMap(),
          if (placement != null) 'placement': placement.toMap(),
          'weight': ?weight,
          'duration': ?duration?.toMap(),
          if (restartTimer) 'restartTimer': true,
        }));
  }

  /// Hide an overlay but keep it for [showOverlay], playing its exit
  /// animation. Pauses its duration timer and ticker. Emits
  /// [RtmpStatusType.overlayHidden]. No-op if already hidden or hiding.
  Future<void> hideOverlay(String id) async {
    validateOverlayId(id);
    await _guard(() => _method.overlayHide(id));
  }

  /// Show a hidden overlay again, playing its enter animation. Emits
  /// [RtmpStatusType.overlayShown]. No-op if already visible or entering.
  Future<void> showOverlay(String id) async {
    validateOverlayId(id);
    await _guard(() => _method.overlayShow(id));
  }

  /// Remove an overlay and free its resources, playing its exit animation
  /// unless [animate] is false. Emits [RtmpStatusType.overlayRemoved] with
  /// reason `removed` when gone. The id is free for [addOverlay] immediately.
  Future<void> removeOverlay(String id, {bool animate = true}) async {
    validateOverlayId(id);
    await _guard(() => _method.overlayRemove(id, animate));
  }

  /// Remove all dynamic overlays (sponsors and scoreband are untouched). Emits
  /// [RtmpStatusType.overlayRemoved] with reason `cleared` for each. Instant
  /// unless [animate] is true.
  Future<void> clearOverlays({bool animate = false}) =>
      _guard(() => _method.overlayClear(animate));

  Future<void> _guard(Future<void> Function() call) async {
    try {
      await call();
    } on PlatformException catch (e) {
      throw RtmpBroadcasterException(e.code, e.message ?? '');
    }
  }

  void dispose() {
    previewBound.dispose();
  }
}
