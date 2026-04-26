part of '../extensions.dart';

extension IntUtils on int? {
  String get toOverFormat {
    if (this == null) return '0';
    final ballCount = this!;
    final int overs = ballCount ~/ 6;
    final int balls = ballCount % 6;
    final overCount = '$overs.$balls';
    return overCount;
  }

  SizedBox get toHeight => SizedBox(height: this!.toDouble());
  SizedBox get toWidth => SizedBox(width: this!.toDouble());
  
  bool get isPowerOfTwo {
    if (this == null) return false;
    return this! > 0 && (this! & (this! - 1)) == 0;
  }

  String get formatFileSize {
    if (this == null || this == 0) return '';
    double sizeInKB = this! / 1024;
    if (sizeInKB >= 1024) {
      double sizeInMB = sizeInKB / 1024;
      return '${sizeInMB.toStringAsFixed(2)} MB';
    } else {
      return '${sizeInKB.toStringAsFixed(2)} KB';
    }
  }
}
