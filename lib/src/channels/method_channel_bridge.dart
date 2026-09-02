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

  Future<void> updateOverlay(
    String layerId,
    Uint8List bytes, {
    required int width,
    required int x,
    required int y,
  }) =>
      _channel.invokeMethod('updateOverlay', {
        'layerId': layerId,
        'bytes': bytes,
        'width': width,
        'x': x,
        'y': y,
      });

  Future<void> updateSponsors(List<Map<String, dynamic>> sponsors) =>
      _channel.invokeMethod('updateSponsors', {'sponsors': sponsors});

  Future<void> switchCamera(String facing) =>
      _channel.invokeMethod('switchCamera', {'facing': facing});

  Future<void> rebindPreview() => _channel.invokeMethod('rebindPreview');

  Future<void> setAudioMute(bool muted) =>
      _channel.invokeMethod('setAudioMute', {'muted': muted});

  Future<void> setAppOrientation(String orientation) =>
      _channel.invokeMethod('setAppOrientation', {'orientation': orientation});

  Future<List<Map<Object?, Object?>>> listUsbVideoDevices() async {
    final raw = await _channel.invokeListMethod<Map<Object?, Object?>>('listUsbVideoDevices');
    return raw ?? [];
  }

  Future<List<Map<Object?, Object?>>> listUsbAudioDevices() async {
    final raw = await _channel.invokeListMethod<Map<Object?, Object?>>('listUsbAudioDevices');
    return raw ?? [];
  }

  Future<bool> requestUsbPermission(int deviceId) async {
    final result = await _channel.invokeMethod<bool>(
        'requestUsbPermission', {'deviceId': deviceId});
    return result ?? false;
  }

  Future<String> exportDiagnostics() async =>
      (await _channel.invokeMethod<String>('exportDiagnostics')) ?? '';

  Future<void> clearDiagnostics() => _channel.invokeMethod('clearDiagnostics');
}
