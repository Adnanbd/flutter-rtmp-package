import 'package:flutter/services.dart';

class MethodChannelBridge {
  static const channelName = 'flutter_rtmp_broadcaster/control';

  final MethodChannel _channel = const MethodChannel(channelName);

  Future<void> initPreview(Map<String, dynamic> args) =>
      _channel.invokeMethod('initPreview', args);

  Future<void> configure(Map<String, dynamic> args) =>
      _channel.invokeMethod('configure', args);

  Future<void> startStream() => _channel.invokeMethod('startStream');

  Future<void> stopStream() => _channel.invokeMethod('stopStream');

  Future<void> updateOverlay(String layerId, Uint8List bytes) =>
      _channel.invokeMethod('updateOverlay', {'layerId': layerId, 'bytes': bytes});

  Future<void> updateSponsors(List<Map<String, dynamic>> sponsors) =>
      _channel.invokeMethod('updateSponsors', {'sponsors': sponsors});

  Future<void> switchCamera(String facing) =>
      _channel.invokeMethod('switchCamera', {'facing': facing});

  Future<void> setAudioMute(bool muted) =>
      _channel.invokeMethod('setAudioMute', {'muted': muted});

  Future<void> setAppOrientation(String orientation) =>
      _channel.invokeMethod('setAppOrientation', {'orientation': orientation});
}
