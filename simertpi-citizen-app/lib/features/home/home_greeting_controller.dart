import 'package:flutter/foundation.dart';

enum GreetingState { idle, loading, loaded, failed }

/// One cached greeting per mounted authenticated Home; stale results never publish.
class HomeGreetingController extends ChangeNotifier {
  HomeGreetingController({required this.read, required this.isCurrentSession});
  final Future<Map<String, dynamic>> Function() read;
  final bool Function() isCurrentSession;
  GreetingState state = GreetingState.idle;
  String? name;
  int _revision = 0;
  bool _disposed = false;
  void invalidate() {
    _revision++;
    name = null;
    state = GreetingState.idle;
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    if (_disposed ||
        !isCurrentSession() ||
        state == GreetingState.loading ||
        state == GreetingState.loaded) {
      return;
    }
    final revision = ++_revision;
    state = GreetingState.loading;
    notifyListeners();
    try {
      final profile = await read();
      if (_disposed || revision != _revision || !isCurrentSession()) return;
      final first = (profile['firstName'] as String?)?.trim();
      final username = (profile['username'] as String?)?.trim();
      final value = first?.isNotEmpty == true ? first : username;
      if (value == null || value.isEmpty) {
        throw const FormatException('Greeting unavailable');
      }
      name = value;
      state = GreetingState.loaded;
    } catch (_) {
      if (_disposed || revision != _revision || !isCurrentSession()) return;
      name = null;
      state = GreetingState.failed;
    } finally {
      if (!_disposed && revision == _revision && isCurrentSession()) {
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _revision++;
    super.dispose();
  }
}
