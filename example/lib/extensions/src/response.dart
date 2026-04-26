import 'dart:convert';
import 'dart:developer';

import 'package:http/http.dart';

import '../extensions.dart';

extension StreamedResponseExt on StreamedResponse {
  bool get isSuccessful => statusCode >= 200 && statusCode < 300;

  Future<String> get body => stream.bytesToString();

  Future<Map<String, dynamic>> get json async => jsonDecode(await body);

  Future<Map<String, dynamic>> get data async => (await json)['data'];

  Future<String> get errors async => (await body).toErrors;
}

extension ResponseExt on Response {
  bool get isSuccessful => statusCode >= 200 && statusCode < 300;

  String get errors => body.toErrors;

  Map<String, dynamic> get json {
    try {
      return jsonDecode(body);
    } catch (e) {
      return {};
    }
  }

  Map<String, dynamic> get data {
    try {
      return json['data'] ?? {};
    } catch (e) {
      log('Error Response Data : $e');
      return {};
    }
  }

  void logResponse() {
    log('Response Path : ${request!.url.toString()}', name: 'Response');
    log('Response Data : ${body.prettyJson}', name: 'Response');

    if (!isSuccessful) {
      log('Response Errors : $errors', name: 'Response');
    }
    log('Response Status Code : $statusCode', name: 'Response');
  }
}
