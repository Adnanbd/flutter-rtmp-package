class UsbDeviceInfo {
  const UsbDeviceInfo({
    required this.deviceId,
    required this.vendorId,
    required this.productId,
    required this.productName,
    required this.manufacturerName,
    required this.hasPermission,
  });

  final int deviceId;
  final int vendorId;
  final int productId;
  final String productName;
  final String manufacturerName;
  final bool hasPermission;

  factory UsbDeviceInfo.fromMap(Map<Object?, Object?> map) => UsbDeviceInfo(
        deviceId: map['deviceId'] as int,
        vendorId: map['vendorId'] as int,
        productId: map['productId'] as int,
        productName: map['productName'] as String,
        manufacturerName: map['manufacturerName'] as String,
        hasPermission: map['hasPermission'] as bool? ?? false,
      );

  @override
  String toString() => '$manufacturerName $productName (id:$deviceId)';
}

class UsbAudioDeviceInfo {
  const UsbAudioDeviceInfo({
    required this.deviceId,
    required this.productName,
    required this.type,
  });

  final int deviceId;
  final String productName;
  final int type;

  factory UsbAudioDeviceInfo.fromMap(Map<Object?, Object?> map) => UsbAudioDeviceInfo(
        deviceId: map['deviceId'] as int,
        productName: map['productName'] as String,
        type: map['type'] as int,
      );

  @override
  String toString() => '$productName (id:$deviceId)';
}
