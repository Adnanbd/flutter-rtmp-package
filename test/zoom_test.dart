import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('flutter_rtmp_broadcaster/control');
  late List<MethodCall> calls;
  PlatformException? nextError;

  const state = {'supported': true, 'min': 1.0, 'max': 8.0, 'current': 2.5, 'source': 'camera2'};

  setUp(() {
    calls = [];
    nextError = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (nextError != null) throw nextError!;
      return state;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  Matcher throwsCode(String code) =>
      throwsA(isA<RtmpBroadcasterException>().having((e) => e.code, 'code', code));

  test('setZoom sends level and returns applied state', () async {
    final info = await RtmpBroadcastController().setZoom(2.5);
    expect(calls.single.method, 'setZoom');
    expect(calls.single.arguments, {'level': 2.5});
    expect(info.current, 2.5);
    expect(info.max, 8.0);
  });

  test('getZoom has no arguments', () async {
    final info = await RtmpBroadcastController().getZoom();
    expect(calls.single.method, 'getZoom');
    expect(calls.single.arguments, isNull);
    expect(info.supported, isTrue);
  });

  test('setZoom validates in Dart', () {
    final c = RtmpBroadcastController();
    expect(c.setZoom(0), throwsCode('ZOOM_INVALID'));
    expect(c.setZoom(double.nan), throwsCode('ZOOM_INVALID'));
    expect(c.setZoom(double.infinity), throwsCode('ZOOM_INVALID'));
    expect(calls, isEmpty);
  });

  test('native errors become RtmpBroadcasterException', () async {
    nextError = PlatformException(code: 'ZOOM_UNSUPPORTED', message: 'no zoom');
    await expectLater(RtmpBroadcastController().setZoom(2), throwsCode('ZOOM_UNSUPPORTED'));
    nextError = PlatformException(code: 'ZOOM_NOT_READY');
    await expectLater(RtmpBroadcastController().getZoom(), throwsCode('ZOOM_NOT_READY'));
  });
}
