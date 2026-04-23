class RtmpBroadcasterException implements Exception {
  const RtmpBroadcasterException(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => 'RtmpBroadcasterException($code): $message';
}
