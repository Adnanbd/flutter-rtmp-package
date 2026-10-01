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

    test('sends sponsors in correct map shape (new placement API)', () async {
      final ctrl = RtmpBroadcastController();
      final bytes = Uint8List.fromList([0, 1, 2, 3]);
      await ctrl.configure(
        rtmpUrl: 'rtmp://host/app',
        rtmpKey: 'key',
        sponsors: [
          SponsorOverlay(
            bytes: bytes,
            placement: const SponsorPlacement(left: 10, top: 20, width: 30, height: 50),
          ),
        ],
        config: StreamConfig.youtube720Landscape,
      );
      final args = calls.first.arguments as Map;
      final sponsors = args['sponsors'] as List;
      expect(sponsors.length, 1);
      final s = sponsors.first as Map;
      expect(s['bytes'], bytes);
      expect(s['left'], 10);
      expect(s['top'], 20);
      expect(s['width'], 30);
      expect(s['height'], 50);
      expect(s.containsKey('right'), false);
      expect(s.containsKey('bottom'), false);
    });

    test('legacy OverlayPosition translates to new wire format', () async {
      final ctrl = RtmpBroadcastController();
      final bytes = Uint8List.fromList([0, 1, 2, 3]);
      await ctrl.configure(
        rtmpUrl: 'rtmp://host/app',
        rtmpKey: 'key',
        sponsors: [
          // ignore: deprecated_member_use_from_same_package
          SponsorOverlay(
            bytes: bytes,
            // ignore: deprecated_member_use_from_same_package
            position: const OverlayPosition(x: 0.1, y: 0.2, width: 0.3, height: 0.05),
          ),
        ],
        config: StreamConfig.youtube720Landscape,
      );
      final args = calls.first.arguments as Map;
      final s = (args['sponsors'] as List).first as Map;
      expect(s['left'], 10);
      expect(s['top'], 20);
      expect(s['width'], 30);
      expect(s['height'], 100);
    });
  });

  group('audio cleanup', () {
    test('StreamConfig omits audioCleanup when off', () {
      expect(StreamConfig.youtube720Landscape.toMap().containsKey('audioCleanup'), isFalse);
    });

    test('initPreview sends audioCleanup', () async {
      final ctrl = RtmpBroadcastController();
      const cfg = StreamConfig(
        width: 1920,
        height: 1080,
        fps: 30,
        videoBitrate: 10000000,
        keyframeIntervalSeconds: 2,
        orientation: VideoOrientation.landscape,
        initialFacing: CameraFacing.back,
        audioInput: AudioInput.usb,
        audioCleanup: AudioCleanup.voice,
      );
      await ctrl.initPreview(config: cfg);
      expect(calls.last.method, 'initPreview');
      expect((calls.last.arguments as Map)['audioCleanup'], 'voice');
    });

    test('setAudioCleanup sends the mode', () async {
      final ctrl = RtmpBroadcastController();
      await ctrl.setAudioCleanup(AudioCleanup.basic);
      expect(calls.last.method, 'setAudioCleanup');
      expect(calls.last.arguments, {'mode': 'basic'});
    });

    test('setAudioCleanup maps native errors', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('flutter_rtmp_broadcaster/control'),
        (call) async => throw PlatformException(code: 'NOT_CONFIGURED', message: 'no manager'),
      );
      final ctrl = RtmpBroadcastController();
      expect(
        () => ctrl.setAudioCleanup(AudioCleanup.voice),
        throwsA(isA<RtmpBroadcasterException>().having((e) => e.code, 'code', 'NOT_CONFIGURED')),
      );
    });
  });
}
