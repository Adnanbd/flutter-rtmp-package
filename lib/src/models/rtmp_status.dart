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
}

class RtmpStatus {
  const RtmpStatus({
    required this.type,
    this.kbps,
    this.reason,
    this.errorCode,
    this.errorMessage,
    this.reconnectAttempt,
  });

  final RtmpStatusType type;
  final int? kbps;
  final String? reason;
  final String? errorCode;
  final String? errorMessage;
  final int? reconnectAttempt;

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
    );
  }
}
