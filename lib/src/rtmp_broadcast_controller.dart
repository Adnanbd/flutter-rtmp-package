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

  Stream<RtmpStatus> get statusStream => _event.statusStream.where((s) {
        if (s.type == RtmpStatusType.previewBound) {
          previewBound.value = true;
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
