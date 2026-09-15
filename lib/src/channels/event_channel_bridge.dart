import 'package:flutter/services.dart';

import '../models/rtmp_status.dart';

class EventChannelBridge {
  static const channelName = 'flutter_rtmp_broadcaster/status';

  /// One stream for the whole process. `EventChannel.receiveBroadcastStream`
  /// installs a single platform message handler per channel name when it is
  /// listened to, so a second `receiveBroadcastStream()` would silently steal
  /// events from the first. Sharing one broadcast stream lets any number of
  /// listeners (and controllers) receive every event.
  static final Stream<RtmpStatus> _shared = const EventChannel(channelName)
      .receiveBroadcastStream()
      .map((event) => RtmpStatus.fromMap(event as Map));

  Stream<RtmpStatus> get statusStream => _shared;
}
