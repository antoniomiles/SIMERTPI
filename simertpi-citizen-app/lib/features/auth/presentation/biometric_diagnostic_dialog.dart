import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/logging/biometric_diagnostic.dart';

class BiometricDiagnosticHost extends StatefulWidget {
  const BiometricDiagnosticHost({
    super.key,
    required this.navigatorKey,
    required this.ready,
    required this.child,
  });
  final GlobalKey<NavigatorState> Function() navigatorKey;
  final bool Function() ready;
  final Widget child;
  @override
  State<BiometricDiagnosticHost> createState() =>
      _BiometricDiagnosticHostState();
}

class _BiometricDiagnosticHostState extends State<BiometricDiagnosticHost> {
  Timer? _retry;
  bool _presenting = false;
  @override
  void initState() {
    super.initState();
    BiometricDiagnostics.pending.addListener(_schedule);
    _schedule();
  }

  void _schedule() {
    if (_presenting || !mounted) return;
    _retry?.cancel();
    WidgetsBinding.instance.addPostFrameCallback((_) => _present());
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  void _present() {
    if (!mounted || _presenting) return;
    final report = BiometricDiagnostics.pending.value;
    if (report == null) return;
    final scope = AppScope.of(context);
    if (!biometricDiagnosticEnabled(scope.config.environment) ||
        (report.source != null &&
            !identical(report.source, scope.auth.biometricDiagnosticSource))) {
      BiometricDiagnostics.pending.value = null;
      return;
    }
    final navigator = widget.navigatorKey().currentState;
    final overlayContext = navigator?.overlay?.context;
    if (!widget.ready() || overlayContext == null) {
      _retry = Timer(const Duration(milliseconds: 150), _schedule);
      return;
    }
    _presenting = true;
    BiometricDiagnostics.pending.value = null;
    showDialog<void>(
      context: overlayContext,
      barrierDismissible: false,
      builder: (_) => BiometricDiagnosticDialog(report: report),
    ).whenComplete(() {
      _presenting = false;
      if (mounted) _schedule();
    });
  }

  @override
  void dispose() {
    _retry?.cancel();
    BiometricDiagnostics.pending.removeListener(_schedule);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// Observes transitions without changing navigation or route ownership.
class BiometricDiagnosticRouteObserver extends NavigatorObserver {
  Route<dynamic>? current;
  bool get settled =>
      current is! TransitionRoute ||
      (current as TransitionRoute).animation?.status ==
          AnimationStatus.completed;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      current = route;
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      current = previousRoute;
  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) =>
      current = newRoute;
}

class BiometricDiagnosticDialog extends StatelessWidget {
  const BiometricDiagnosticDialog({super.key, required this.report});
  final BiometricDiagnostic report;
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Diagnóstico biométrico — DEV'),
    content: SingleChildScrollView(child: SelectableText(report.text)),
    actions: [
      TextButton(
        onPressed: () async {
          try {
            await Clipboard.setData(ClipboardData(text: report.text));
          } catch (_) {
            /* Clipboard failure must not affect the session. */
          }
        },
        child: const Text('Copiar diagnóstico'),
      ),
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Cerrar'),
      ),
    ],
  );
}
