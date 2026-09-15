import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ZoomInfo.fromMap parses ints and doubles', () {
    final info = ZoomInfo.fromMap({'supported': true, 'min': 0.5, 'max': 10, 'current': 2.0, 'source': 'camera2'});
    expect(info, const ZoomInfo(supported: true, min: 0.5, max: 10, current: 2, source: ZoomSource.camera2));
  });

  test('unsupported UVC camera', () {
    final info = ZoomInfo.fromMap({'supported': false, 'min': 1.0, 'max': 1.0, 'current': 1.0, 'source': 'uvc'});
    expect(info.supported, isFalse);
    expect(info.source, ZoomSource.uvc);
  });

  test('zoomChanged event carries reason and zoom', () {
    final status = RtmpStatus.fromMap({
      'type': 'zoomChanged',
      'reason': 'cameraSwitched',
      'zoom': {'supported': true, 'min': 1.0, 'max': 4.0, 'current': 1.0, 'source': 'camera2'},
    });
    expect(status.type, RtmpStatusType.zoomChanged);
    expect(status.reason, 'cameraSwitched');
    expect(status.zoom?.max, 4.0);
    expect(RtmpStatus.fromMap({'type': 'connected'}).zoom, isNull);
  });
}
