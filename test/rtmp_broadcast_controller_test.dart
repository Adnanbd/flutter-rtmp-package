import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;

  setUp(() {
    calls = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('flutter_rtmp_broadcaster/control'),
      (call) async {
        calls.add(call);
        return null;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('flutter_rtmp_broadcaster/control'),
      null,
    );
  });

  group('RtmpBroadcastController.configure', () {
    test('combines url and key with slash', () async {
      final ctrl = RtmpBroadcastController();
      await ctrl.configure(
        rtmpUrl: 'rtmp://live.example.com/app',
        rtmpKey: 'my-key',
        sponsors: [],
        config: StreamConfig.youtube720Landscape,
      );
      expect(calls.length, 1);
      expect(calls.first.method, 'configure');
      final args = calls.first.arguments as Map;
      expect(args['rtmpEndpoint'], 'rtmp://live.example.com/app/my-key');
    });

    test('throws on empty url', () async {
      final ctrl = RtmpBroadcastController();
      expect(
        () => ctrl.configure(rtmpUrl: '', rtmpKey: 'key', sponsors: [], config: StreamConfig.youtube720Landscape),
        throwsA(isA<RtmpBroadcasterException>()),
      );
    });

    test('throws on empty key', () async {
      final ctrl = RtmpBroadcastController();
      expect(
        () => ctrl.configure(
            rtmpUrl: 'rtmp://live.example.com/app', rtmpKey: '', sponsors: [], config: StreamConfig.youtube720Landscape),
        throwsA(isA<RtmpBroadcasterException>()),
      );
    });

    test('sends sponsors in correct map shape', () async {
      final ctrl = RtmpBroadcastController();
      final bytes = Uint8List.fromList([0, 1, 2, 3]);
      await ctrl.configure(
        rtmpUrl: 'rtmp://host/app',
        rtmpKey: 'key',
        sponsors: [
          SponsorOverlay(
            bytes: bytes,
            position: const OverlayPosition(x: 0.1, y: 0.2, width: 0.3, height: 0.05),
          ),
        ],
        config: StreamConfig.youtube720Landscape,
      );
      final args = calls.first.arguments as Map;
      final sponsors = args['sponsors'] as List;
      expect(sponsors.length, 1);
      final s = sponsors.first as Map;
      expect(s['bytes'], bytes);
      expect(s['x'], 0.1);
      expect(s['y'], 0.2);
      expect(s['width'], 0.3);
      expect(s['height'], 0.05);
    });
  });
}
