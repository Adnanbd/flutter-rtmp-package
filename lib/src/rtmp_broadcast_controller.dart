import 'package:flutter/services.dart';

import 'channels/event_channel_bridge.dart';
import 'channels/method_channel_bridge.dart';
import 'models/rtmp_broadcaster_exception.dart';
import 'models/rtmp_status.dart';
import 'models/sponsor_overlay.dart';
import 'models/stream_config.dart';

class RtmpBroadcastController {
  RtmpBroadcastController()
      : _method = MethodChannelBridge(),
        _event = EventChannelBridge();

  final MethodChannelBridge _method;
  final EventChannelBridge _event;

  StreamConfig? _config;
  StreamConfig get config => _config!;

  Stream<RtmpStatus> get statusStream => _event.statusStream;

  Future<void> initPreview({StreamConfig? config}) async {
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

  Future<void> updateScoreband(Uint8List pngBytes) async {
    try {
      await _method.updateOverlay('scoreband', pngBytes);
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

  void dispose() {}
}
