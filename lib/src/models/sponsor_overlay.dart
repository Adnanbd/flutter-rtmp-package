import 'dart:typed_data';

import 'overlay_position.dart';
import 'sponsor_placement.dart';

/// A static sponsor image with its placement on the stream frame.
class SponsorOverlay {
  const SponsorOverlay({
    required this.bytes,
    @Deprecated('Use placement instead') this.position,
    this.placement,
  }) : assert(position != null || placement != null,
            'Provide placement (preferred) or position (deprecated)');

  final Uint8List bytes;

  @Deprecated('Use placement instead')
  final OverlayPosition? position;

  final SponsorPlacement? placement;

  Map<String, dynamic> toMap() {
    final p = placement ?? _legacyToPlacement(position!);
    return {'bytes': bytes, ...p.toMap()};
  }

  // Legacy OverlayPosition (0.0-1.0, x/y = top-left edge, height ignored by
  // old native aspect-fit) → new SponsorPlacement. height=100 preserves the
  // old "width-driven aspect fit" behavior under BoxFit.contain.
  static SponsorPlacement _legacyToPlacement(OverlayPosition o) =>
      SponsorPlacement(
        left: (o.x * 100).round(),
        top: (o.y * 100).round(),
        width: (o.width * 100).round().clamp(1, 100),
        height: 100,
      );
}
