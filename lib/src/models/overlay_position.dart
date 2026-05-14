/// Normalized position and size of an overlay layer (all values 0.0–1.0).
@Deprecated('Use SponsorPlacement instead')
class OverlayPosition {
  const OverlayPosition({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
  });

  final double x;
  final double y;
  final double width;
  final double height;

  Map<String, double> toMap() => {
        'x': x,
        'y': y,
        'width': width,
        'height': height,
      };
}
