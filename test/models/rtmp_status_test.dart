import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

void main() {
  group('RtmpStatus.fromMap', () {
    test('parses connected event', () {
      final s = RtmpStatus.fromMap({'type': 'connected'});
      expect(s.type, RtmpStatusType.connected);
    });

    test('parses disconnected event with reason', () {
      final s = RtmpStatus.fromMap({'type': 'disconnected', 'reason': 'timeout'});
      expect(s.type, RtmpStatusType.disconnected);
      expect(s.reason, 'timeout');
    });

    test('parses error event with code and message', () {
      final s = RtmpStatus.fromMap({
        'type': 'error',
        'code': 'MAX_RECONNECT_EXCEEDED',
        'message': 'gave up after 3 attempts',
      });
      expect(s.type, RtmpStatusType.error);
      expect(s.errorCode, 'MAX_RECONNECT_EXCEEDED');
      expect(s.errorMessage, 'gave up after 3 attempts');
    });

    test('parses bitrate event', () {
      final s = RtmpStatus.fromMap({'type': 'bitrate', 'kbps': 2500});
      expect(s.type, RtmpStatusType.bitrate);
      expect(s.kbps, 2500);
    });

    test('parses reconnecting event with attempt', () {
      final s = RtmpStatus.fromMap({'type': 'reconnecting', 'attempt': 2});
      expect(s.type, RtmpStatusType.reconnecting);
      expect(s.reconnectAttempt, 2);
    });

    test('unknown type falls back to error', () {
      final s = RtmpStatus.fromMap({'type': 'unknown_event'});
      expect(s.type, RtmpStatusType.error);
    });
  });
}
