import '../../core/network/api_client.dart';
import '../../core/errors/app_failure.dart';

typedef CitizenData = Map<String, dynamic>;

class ActivitySlice {
  const ActivitySlice(this.items, this.hasMore);
  final List<CitizenData> items;
  final bool hasMore;
}

abstract interface class ActivityGateway {
  Future<ActivitySlice> list(String resource, int offset);
  Future<CitizenData> detail(String resource, String id);
  Future<CitizenData> read(String id);
  Future<int> unread();
  Future<CitizenData> profile();
  Future<Map<String, bool>> preferences();
  Future<void> savePreferences(Map<String, bool> values);
}

class ActivityService implements ActivityGateway {
  const ActivityService(this.api);
  final ApiClient? api;
  ApiClient get client =>
      api ?? (throw const AppFailure(FailureKind.unavailable));
  static String path(String resource) =>
      resource == 'history' ? 'citizen/history' : 'notifications/inbox';
  static CitizenData object(Object? value) {
    if (value is! Map) throw const AppFailure(FailureKind.unknown);
    return Map<String, dynamic>.from(value);
  }

  @override
  Future<ActivitySlice> list(String resource, int offset) async {
    final result = object(
      (await client.request(
        ApiMethod.get,
        path(resource),
        query: {'limit': '20', 'offset': '$offset'},
      )).body,
    );
    if (result['items'] is! List || result['hasMore'] is! bool) {
      throw const AppFailure(FailureKind.unknown);
    }
    return ActivitySlice(
      (result['items'] as List).map(object).toList(),
      result['hasMore'] as bool,
    );
  }

  @override
  Future<CitizenData> detail(String resource, String id) async => object(
    (await client.request(ApiMethod.get, '${path(resource)}/$id')).body,
  );
  @override
  Future<CitizenData> read(String id) async => object(
    (await client.request(
      ApiMethod.patch,
      'notifications/inbox/$id/read',
    )).body,
  );
  @override
  Future<int> unread() async {
    final count = object(
      (await client.request(ApiMethod.get, 'notifications/unread-count')).body,
    )['unreadCount'];
    if (count is! int || count < 0) throw const AppFailure(FailureKind.unknown);
    return count;
  }

  @override
  Future<CitizenData> profile() async =>
      object((await client.request(ApiMethod.get, 'citizen/profile')).body);
  @override
  Future<Map<String, bool>> preferences() async {
    final rows = (await client.request(
      ApiMethod.get,
      'notifications/preferences',
    )).body;
    if (rows is! List) throw const AppFailure(FailureKind.unknown);
    return {
      for (final row in rows.map(object))
        if (row['channel'] == 'PUSH' || row['channel'] == 'EMAIL')
          row['channel'] as String: row['enabled'] == true,
    };
  }

  @override
  Future<void> savePreferences(Map<String, bool> values) async {
    await client.request(
      ApiMethod.put,
      'notifications/preferences',
      body: values,
    );
  }
}
