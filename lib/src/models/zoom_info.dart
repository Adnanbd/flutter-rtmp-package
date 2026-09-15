/// Which camera API zooms.
enum ZoomSource {
  /// Phone camera (Camera2). [ZoomInfo] values are true zoom ratios.
  camera2,

  /// USB (UVC) camera hardware zoom. Ratios come from the camera's zoom range;
  /// see `docs/specs/camera-zoom.md` for how raw values map to them.
  uvc,
}

/// Camera zoom state, from `getZoom`, `setZoom` and `RtmpStatusType.zoomChanged`.
///
/// Zoom happens in the camera, so the preview and the stream zoom together and
/// overlays are not zoomed.
class ZoomInfo {
  const ZoomInfo({
    required this.supported,
    required this.min,
    required this.max,
    required this.current,
    required this.source,
  });

  /// False when the camera has no zoom control (some USB cameras); then
  /// [min], [max] and [current] are all 1.0.
  final bool supported;

  /// Smallest ratio. Can be below 1.0 on phones with an ultra-wide lens (API 30+).
  final double min;
  final double max;
  final double current;
  final ZoomSource source;

  factory ZoomInfo.fromMap(Map<dynamic, dynamic> map) => ZoomInfo(
        supported: map['supported'] as bool,
        min: (map['min'] as num).toDouble(),
        max: (map['max'] as num).toDouble(),
        current: (map['current'] as num).toDouble(),
        source: ZoomSource.values.firstWhere(
          (s) => s.name == map['source'],
          orElse: () => ZoomSource.camera2,
        ),
      );

  @override
  bool operator ==(Object other) =>
      other is ZoomInfo &&
      other.supported == supported &&
      other.min == min &&
      other.max == max &&
      other.current == current &&
      other.source == source;

  @override
  int get hashCode => Object.hash(supported, min, max, current, source);

  @override
  String toString() => 'ZoomInfo(supported: $supported, $min–$max, current: $current, ${source.name})';
}
