import 'package:flutter/foundation.dart';

import '../../core/state/async_state.dart';

// Explicit manual demonstration, not simulated server data or a business flow.
class ShowcaseController extends ChangeNotifier {
  LoadPhase _phase = LoadPhase.initial;
  LoadPhase get phase => _phase;
  void select(LoadPhase phase) {
    _phase = phase;
    notifyListeners();
  }

  void retry() => select(LoadPhase.success);
}
