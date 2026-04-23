import 'package:flutter/services.dart';

import '../models/rtmp_status.dart';

class EventChannelBridge {
  static const channelName = 'flutter_rtmp_broadcaster/status';

  final EventChannel _channel = const EventChannel(channelName);

  Stream<RtmpStatus> get statusStream => _channel
      .receiveBroadcastStream()
      .map((event) => RtmpStatus.fromMap(event as Map));
}
