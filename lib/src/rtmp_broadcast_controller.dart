import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'channels/event_channel_bridge.dart';
import 'channels/method_channel_bridge.dart';
import 'models/rtmp_broadcaster_exception.dart';
import 'models/rtmp_status.dart';
import 'models/sponsor_overlay.dart';
import 'models/stream_config.dart';
import 'models/usb_device_info.dart';

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

  Future<void> updateScoreband(
    Uint8List pngBytes, {
    int width = 90,
    int x = 50,
    int y = 100,
  }) async {
    try {
      await _method.updateOverlay('scoreband', pngBytes, width: width, x: x, y: y);
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

  void dispose() {
    previewBound.dispose();
  }
}
