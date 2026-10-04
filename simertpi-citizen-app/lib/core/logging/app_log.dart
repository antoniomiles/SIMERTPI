import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';

class AppLog {
  const AppLog();
  // No arbitrary payload, URI, exception, header or destination is accepted.
  void requestResult({required bool success, required String correlationId}) {
    if (kDebugMode &&
        RegExp(r'^[A-Za-z0-9._:-]{1,128}$').hasMatch(correlationId)) {
      developer.log(
        'event=api_request result=${success ? 'SUCCESS' : 'FAILED'} '
        'correlationId=$correlationId',
        name: 'simertpi',
      );
    }
  }
}
