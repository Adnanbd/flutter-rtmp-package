import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'rtmp_broadcast_controller.dart';

class RtmpBroadcastWidget extends StatefulWidget {
  const RtmpBroadcastWidget({super.key, this.controller});

  final RtmpBroadcastController? controller;

  @override
  State<RtmpBroadcastWidget> createState() => _RtmpBroadcastWidgetState();
}

class _RtmpBroadcastWidgetState extends State<RtmpBroadcastWidget> {
  static const _viewType = 'flutter_rtmp_broadcaster/camera_preview';

  @override
  Widget build(BuildContext context) {
    final orientation = MediaQuery.of(context).orientation;
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return AndroidView(
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParamsCodec: const StandardMessageCodec(),
          key: ValueKey('camera_$orientation'),
        );
      case TargetPlatform.iOS:
        return UiKitView(
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParamsCodec: const StandardMessageCodec(),
          key: ValueKey('camera_$orientation'),
        );
      default:
        return const SizedBox.shrink();
    }
  }
}
