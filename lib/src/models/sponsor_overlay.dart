import 'dart:typed_data';

import 'overlay_position.dart';

/// A static sponsor image with its normalized position/size on the stream frame.
class SponsorOverlay {
  const SponsorOverlay({
    required this.bytes,
    required this.position,
  });

  final Uint8List bytes;
  final OverlayPosition position;

  Map<String, dynamic> toMap() => {
        'bytes': bytes,
        ...position.toMap(),
      };
}
