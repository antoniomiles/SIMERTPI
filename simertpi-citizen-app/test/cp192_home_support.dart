import 'dart:async';

import 'package:flutter/material.dart';
import 'package:simertpi_citizen_app/app/bootstrap/bootstrap.dart';
import 'package:simertpi_citizen_app/core/config/app_config.dart';
import 'package:simertpi_citizen_app/core/network/api_client.dart';
import 'package:simertpi_citizen_app/features/auth/state/auth_controller.dart';
import 'package:simertpi_citizen_app/features/home/home_page.dart';
import 'package:simertpi_citizen_app/features/parking/data/parking_contract.dart';

import 'active_parking_test.dart' as active;
import 'citizen_activity_test.dart' show FakeActivity;

class GreetingApi extends ApiClient {
  GreetingApi(this.read)
    : super(baseUrl: Uri.parse('https://example.invalid/api/v1'));
  final Future<Map<String, dynamic>> Function() read;
  int reads = 0;
  @override
  Future<ApiResponse> request(
    ApiMethod method,
    String path, {
    Object? body,
    bool authenticated = true,
    Map<String, String> headers = const {},
    Map<String, String> query = const {},
    String? correlationId,
    bool retryUnauthorized = true,
    Object? Function(String)? responseDecoder,
  }) async {
    if (path == 'users/mine') {
      reads++;
      return ApiResponse(200, await read(), 'qa');
    }
    return const ApiResponse(200, [], 'qa');
  }
}

ParkingReceipt receipt(
  String id,
  String status,
  int minute, {
  String owner = 'fixture-citizen',
}) => ParkingReceipt({
  'id': id,
  'userId': owner,
  'vehicleId': 'v',
  'parkingSpaceId': 's',
  'status': status,
  'startedAt': DateTime.utc(2026, 10, 4, 15, minute).toIso8601String(),
  'expectedEndAt': DateTime.utc(2026, 10, 4, 16, minute).toIso8601String(),
});

class OrderedGateway extends active.FakeActive {
  List<ParkingReceipt> values = [];
  Completer<List<ParkingReceipt>>? pending;
  @override
  Future<List<ParkingReceipt>> mine(String owner) async {
    loads++;
    if (error != null) throw error!;
    final wait = pending;
    pending = null;
    return wait != null ? await wait.future : List.of(values);
  }
}

Widget homeFixture(
  AuthController auth,
  GreetingApi api,
  OrderedGateway gateway, {
  ValueNotifier<int>? tab,
  ValueNotifier<String?>? target,
  FakeActivity? activity,
  Key? appKey,
}) => AppScope(
  config: AppConfig.parse(environment: 'dev'),
  auth: auth,
  api: api,
  citizenTab: tab,
  parkingSessionTarget: target,
  child: MaterialApp(
    key: appKey,
    home: HomePage(
      activeController: active.controller(gateway),
      activityGateway: activity ?? FakeActivity(),
    ),
  ),
);
