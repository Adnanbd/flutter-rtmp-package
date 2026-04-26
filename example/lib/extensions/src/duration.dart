part of '../extensions.dart';

extension DurationExt on Duration {
  String get formatDuration {
    final minutes = inMinutes % 60;
    final hours = inHours % 24;
    final daysTotal = inDays;

    final years = daysTotal ~/ 365;
    final months = (daysTotal % 365) ~/ 30;
    final weeks = (daysTotal % 30) ~/ 7;
    final days = (daysTotal % 7);

    final parts = <String>[];

    if (years > 0) parts.add('$years year${years > 1 ? 's' : ''}');
    if (months > 0) parts.add('$months month${months > 1 ? 's' : ''}');
    if (weeks > 0) parts.add('$weeks week${weeks > 1 ? 's' : ''}');
    if (days > 0) parts.add('$days day${days > 1 ? 's' : ''}');
    if (hours > 0) parts.add('$hours hr${hours > 1 ? 's' : ''}');
    if (minutes > 0) parts.add('$minutes min');

    // If duration is < 1 minute
    if (parts.isEmpty) return '0 min';

    return parts.join(' ');
  }
}
