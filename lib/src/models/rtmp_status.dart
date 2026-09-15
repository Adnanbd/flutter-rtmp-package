import 'zoom_info.dart';

enum RtmpStatusType {
  connected,
  disconnected,
  error,
  warning,
  bitrate,
  reconnecting,
  previewBound,
  // Raised when the native GL preview is released — the platform view's
  // SurfaceTexture was destroyed, or the view itself disposed. Both are
  // swallowed by `RtmpBroadcastController.statusStream`, which uses them to
  // drive `previewBound`.
  //
  // Keep this in the enum: `RtmpStatus.fromMap` maps an unknown type string
  // to `error`, so a native event with no matching value would surface as a
  // stream error to every listener.
  previewUnbound,
  usbDetached,
  // Dynamic overlays (docs/specs/dynamic-overlays.md). `RtmpStatus.overlayId`
  // is set; `overlayRemoved` also carries `reason`
  // (removed | expired | cleared | completed).
  overlayShown,
  overlayHidden,
  overlayRemoved,
  // Camera zoom changed without a setZoom call (docs/specs/camera-zoom.md).
  // `RtmpStatus.zoom` is set; `reason` is reapplied | clamped | reset | cameraSwitched.
  zoomChanged,
}

class RtmpStatus {
  const RtmpStatus({
    required this.type,
    this.kbps,
    this.reason,
    this.errorCode,
    this.errorMessage,
    this.reconnectAttempt,
    this.overlayId,
    this.zoom,
  });

  final RtmpStatusType type;
  final int? kbps;
  final String? reason;
  final String? errorCode;
  final String? errorMessage;
  final int? reconnectAttempt;

  /// Dynamic overlay id for overlay events and overlay warnings.
  final String? overlayId;

  /// Zoom state for [RtmpStatusType.zoomChanged].
  final ZoomInfo? zoom;

  factory RtmpStatus.fromMap(Map<dynamic, dynamic> map) {
    final typeStr = map['type'] as String;
    final type = RtmpStatusType.values.firstWhere(
      (e) => e.name == typeStr,
      orElse: () => RtmpStatusType.error,
    );
    return RtmpStatus(
      type: type,
      kbps: map['kbps'] as int?,
      reason: map['reason'] as String?,
      errorCode: map['code'] as String?,
      errorMessage: map['message'] as String?,
      reconnectAttempt: map['attempt'] as int?,
      overlayId: map['id'] as String?,
      zoom: map['zoom'] is Map ? ZoomInfo.fromMap(map['zoom'] as Map) : null,
    );
  }
}
