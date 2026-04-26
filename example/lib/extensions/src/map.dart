part of '../extensions.dart';

extension MapExt on Map<String, dynamic> {
  /// Remove fields/nested fields that are null or empty
  Map<String, dynamic> get clean {
    final newMap = <String, dynamic>{};

    /// Helper to check if a value is null or empty
    bool isEmpty(dynamic value) {
      if (value == null) return true;
      if (value is String) return value.trim().isEmpty;
      if (value is Map) return value.isEmpty;
      if (value is List) return value.isEmpty;
      return false;
    }

    /// Processes lists recursively
    List<dynamic> processList(List list) {
      return list
          .where((item) => !isEmpty(item)) // Remove empty items
          .map((item) {
        if (item is Map<String, dynamic>) {
          return item.clean;
        } else if (item is List) {
          return processList(item);
        }
        return item;
      }).toList();
    }

    for (final entry in entries) {
      final value = entry.value;

      if (isEmpty(value)) continue; // Skip null/empty values

      if (value is Map<String, dynamic>) {
        final cleanedMap = value.clean;
        if (cleanedMap.isNotEmpty) {
          newMap[entry.key] = cleanedMap;
        }
      } else if (value is List) {
        final cleanedList = processList(value);
        if (cleanedList.isNotEmpty) {
          newMap[entry.key] = cleanedList;
        }
      } else {
        newMap[entry.key] = value;
      }
    }

    return newMap;
  }
}
