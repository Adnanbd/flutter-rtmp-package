import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

void main() {
  group('OverlayPosition.toMap', () {
    test('returns correct keys and values', () {
      const pos = OverlayPosition(x: 0.1, y: 0.2, width: 0.3, height: 0.4);
      final map = pos.toMap();
      expect(map['x'], 0.1);
      expect(map['y'], 0.2);
      expect(map['width'], 0.3);
      expect(map['height'], 0.4);
    });

    test('accepts full-frame normalized values', () {
      const pos = OverlayPosition(x: 0.0, y: 0.0, width: 1.0, height: 1.0);
      final map = pos.toMap();
      expect(map.values.every((v) => v >= 0.0 && v <= 1.0), isTrue);
    });
  });
}
