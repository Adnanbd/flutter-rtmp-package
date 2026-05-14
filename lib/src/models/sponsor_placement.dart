/// Sponsor overlay placement using edge anchors + BoxFit.contain sizing.
///
/// Coords are integers in `0-100` (percent of stream dimensions, post-rotation).
///
/// Position rules (per axis, independently):
/// - only `left`  → image's left edge sits at `left%` from frame's left
/// - only `right` → image's right edge sits at `right%` from frame's right
/// - both `left` and `right` → centered, both values ignored
/// - neither → centered
///
/// Size: `width` and `height` describe the max bounding box (% of stream
/// width/height). The image is scaled aspect-preserving to fit inside that
/// box (BoxFit.contain). Whichever constraint is hit first wins.
class SponsorPlacement {
  const SponsorPlacement({
    this.left,
    this.right,
    this.top,
    this.bottom,
    required this.width,
    required this.height,
  });

  final int? left;
  final int? right;
  final int? top;
  final int? bottom;
  final int width;
  final int height;

  Map<String, dynamic> toMap() => {
        if (left != null) 'left': left,
        if (right != null) 'right': right,
        if (top != null) 'top': top,
        if (bottom != null) 'bottom': bottom,
        'width': width,
        'height': height,
      };
}
