import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const name = 'flutter_rtmp_broadcaster/status';
  const codec = StandardMethodCodec();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  test('every listener on every controller receives each event; native listens once', () async {
    var listenCalls = 0;
    messenger.setMockMessageHandler(name, (message) async {
      final call = codec.decodeMethodCall(message);
      if (call.method == 'listen') listenCalls++;
      return codec.encodeSuccessEnvelope(null);
    });

    final a = <RtmpStatusType>[];
    final b = <RtmpStatusType>[];
    final c = <RtmpStatusType>[];
    final subA = RtmpBroadcastController().statusStream.listen((s) => a.add(s.type));
    final subB = RtmpBroadcastController().statusStream.listen((s) => b.add(s.type));
    final controller = RtmpBroadcastController();
    final subC = controller.statusStream.listen((s) => c.add(s.type));
    await pumpEventQueue();

    await messenger.handlePlatformMessage(name, codec.encodeSuccessEnvelope({'type': 'connected'}), (_) {});
    await subB.cancel();
    await messenger.handlePlatformMessage(
        name, codec.encodeSuccessEnvelope({'type': 'overlayRemoved', 'id': 'x', 'reason': 'cleared'}), (_) {});
    await pumpEventQueue();

    expect(listenCalls, 1);
    expect(a, [RtmpStatusType.connected, RtmpStatusType.overlayRemoved]);
    expect(b, [RtmpStatusType.connected]);
    expect(c, [RtmpStatusType.connected, RtmpStatusType.overlayRemoved]);

    await subA.cancel();
    await subC.cancel();
    messenger.setMockMessageHandler(name, null);
  });
}
