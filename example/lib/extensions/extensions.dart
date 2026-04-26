import 'dart:convert';


import 'package:flutter/material.dart';


part 'src/context.dart';
part 'src/duration.dart';
part 'src/enum.dart';
part 'src/int.dart';
part 'src/iterable.dart';
part 'src/map.dart';
part 'src/string.dart';

class ErrorsModel {
  List<String>? errors;

  ErrorsModel({
    this.errors,
  });

  ErrorsModel copyWith({
    List<String>? errors,
  }) =>
      ErrorsModel(
        errors: errors ?? this.errors,
      );

  factory ErrorsModel.fromRawJson(String str) => ErrorsModel.fromJson(json.decode(str));

  String toRawJson() => json.encode(toJson());

  factory ErrorsModel.fromJson(Map<String, dynamic> json) => ErrorsModel(
        errors: json['errors'] == null ? [] : List<String>.from(json['errors']!.map((x) => x)),
      );

  Map<String, dynamic> toJson() => {
        'errors': errors == null ? [] : List<dynamic>.from(errors!.map((x) => x)),
      };
}
