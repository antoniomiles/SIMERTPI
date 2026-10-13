import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../core/theme/app_theme.dart';
import '../core/security/human_activity.dart';
import '../features/auth/state/auth_controller.dart';
import '../features/auth/presentation/login_page.dart';
import '../features/auth/presentation/biometric_diagnostic_dialog.dart';
import '../features/welcome/welcome_page.dart';
import '../features/welcome/welcome_theme.dart';
import 'router/app_router.dart';

class SimertpiApp extends StatefulWidget {
  const SimertpiApp({
    super.key,
    required this.auth,
    this.initialRoute,
    this.utcNow,
  });
  final AuthController auth;
  final String? initialRoute;
  final DateTime Function()? utcNow;
  @override
  State<SimertpiApp> createState() => _SimertpiAppState();
}

class _SimertpiAppState extends State<SimertpiApp> with WidgetsBindingObserver {
  final _navigator = GlobalKey<NavigatorState>();
  final _diagnosticRoutes = BiometricDiagnosticRouteObserver();
  Timer? _brandModeTimer;
  late ValueNotifier<WelcomeMode> _brandMode;
  late bool _hadSession;
  final _lockNavigator = GlobalKey<NavigatorState>();
  bool _concealed = false;
  bool _resettingSession = false;
  String? _routeOwner;
  int _routeGeneration = 0;
  final _privatePointers = <int>{};

  DateTime _nowUtc() => (widget.utcNow ?? DateTime.now)().toUtc();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _brandMode = ValueNotifier(welcomeModeAt(_nowUtc()));
    _scheduleBrandModeRefresh();
    _hadSession = widget.auth.isAuthenticated;
    _routeOwner = widget.auth.userId;
    widget.auth.addListener(_authChanged);
    widget.auth.inactivity?.addListener(_lockChanged);
    FocusManager.instance.addEarlyKeyEventHandler(_humanKey);
  }

  KeyEventResult _humanKey(KeyEvent event) {
    if (_concealed || _resettingSession) return KeyEventResult.handled;
    final wasLocked = widget.auth.isLocked;
    widget.auth.inactivity?.activity();
    return !wasLocked && widget.auth.isLocked
        ? KeyEventResult.handled
        : KeyEventResult.ignored;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    widget.auth.inactivity?.check();
    if (state == AppLifecycleState.resumed) {
      _refreshBrandMode();
      unawaited(_resumeSecurely());
    } else {
      setState(() => _concealed = true);
      unawaited(widget.auth.inactivity?.flush());
    }
  }

  Future<void> _resumeSecurely() async {
    await widget.auth.inactivity?.resume();
    if (widget.auth.isAuthenticated) await widget.auth.refreshBiometricStatus();
    if (mounted) setState(() => _concealed = false);
  }

  @override
  Future<bool> didPopRoute() async {
    widget.auth.inactivity?.activity();
    return widget.auth.isLocked || _resettingSession || _concealed;
  }

  void _lockChanged() {
    if (!mounted) return;
    if (widget.auth.isLocked || _resettingSession || _concealed) {
      for (final pointer in _privatePointers.toList()) {
        GestureBinding.instance.cancelPointer(pointer);
      }
      _privatePointers.clear();
    }
    unawaited(
      widget.auth.deviceSecurity
          ?.protect(widget.auth.isLocked)
          .catchError((Object _) {}),
    );
    setState(() {});
  }

  void _refreshBrandMode() {
    if (!mounted) return;
    final updated = welcomeModeAt(_nowUtc());
    if (updated != _brandMode.value) _brandMode.value = updated;
    _scheduleBrandModeRefresh();
  }

  void _scheduleBrandModeRefresh() {
    _brandModeTimer?.cancel();
    final now = _nowUtc();
    final delay = nextWelcomeModeChangeUtc(now).difference(now);
    _brandModeTimer = Timer(
      delay.isNegative ? Duration.zero : delay,
      _refreshBrandMode,
    );
  }

  void _authChanged() {
    _lockChanged();
    final signedOut = _hadSession && !widget.auth.isAuthenticated;
    final changedAccount =
        _routeOwner != null &&
        widget.auth.userId != null &&
        _routeOwner != widget.auth.userId;
    _routeOwner = widget.auth.userId;
    _hadSession = widget.auth.isAuthenticated;
    if (signedOut || changedAccount) {
      _resettingSession = true;
      _lockChanged();
      final generation = ++_routeGeneration;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && generation == _routeGeneration) {
          final route = AppRouter.generate(
            RouteSettings(
              name: widget.auth.isAuthenticated
                  ? AppRoute.home.path
                  : AppRoute.welcome.path,
            ),
            widget.auth,
            brandMode: _brandMode,
          ) as TransitionRoute<dynamic>;
          _navigator.currentState?.pushAndRemoveUntil(route, (_) => false);
          void completed(AnimationStatus status) {
            if (status != AnimationStatus.completed) return;
            route.animation?.removeStatusListener(completed);
            if (mounted && generation == _routeGeneration) {
              setState(() => _resettingSession = false);
            }
          }

          route.animation?.addStatusListener(completed);
          if (route.animation?.status == AnimationStatus.completed) {
            completed(AnimationStatus.completed);
          }
        }
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _brandModeTimer?.cancel();
    _brandMode.dispose();
    widget.auth.removeListener(_authChanged);
    widget.auth.inactivity?.removeListener(_lockChanged);
    FocusManager.instance.removeEarlyKeyEventHandler(_humanKey);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: _navigator,
    navigatorObservers: [_diagnosticRoutes],
    title: 'SIMERTPI',
    debugShowCheckedModeBanner: false,
    locale: const Locale('es'),
    supportedLocales: const [Locale('es')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: AppTheme.light,
    themeMode: ThemeMode.light,
    initialRoute: widget.initialRoute ?? AppRoute.splash.path,
    onGenerateRoute: (settings) =>
        AppRouter.generate(settings, widget.auth, brandMode: _brandMode),
    onGenerateInitialRoutes: (route) => [
      AppRouter.generate(
        RouteSettings(name: route),
        widget.auth,
        brandMode: _brandMode,
      ),
    ],
    builder: (context, child) => BiometricDiagnosticHost(
      navigatorKey: () => widget.auth.isLocked ? _lockNavigator : _navigator,
      ready: () =>
          !_concealed &&
          !_resettingSession &&
          !widget.auth.busy &&
          !widget.auth.awaitingInvitation &&
          (widget.auth.isLocked || _diagnosticRoutes.settled),
      child: HumanActivity(
        onActivity: () => widget.auth.inactivity?.activity(),
        child: Listener(
          behavior: HitTestBehavior.translucent,
          onPointerDown: (event) {
            if (!widget.auth.isLocked && !_resettingSession && !_concealed) {
              _privatePointers.add(event.pointer);
              widget.auth.inactivity?.activity();
            }
          },
          onPointerUp: (event) => _privatePointers.remove(event.pointer),
          onPointerCancel: (event) => _privatePointers.remove(event.pointer),
          onPointerMove: (event) {
            if (event.buttons != 0) widget.auth.inactivity?.activity();
          },
          onPointerSignal: (_) => widget.auth.inactivity?.activity(),
          child: Focus(
            onKeyEvent: (_, event) {
              final wasLocked = widget.auth.isLocked;
              widget.auth.inactivity?.activity();
              if (!wasLocked && widget.auth.isLocked) {
                return KeyEventResult.handled;
              }
              return KeyEventResult.ignored;
            },
            child: Stack(
              children: [
                Offstage(
                  offstage:
                      widget.auth.isLocked || _concealed || _resettingSession,
                  child: ExcludeFocus(
                    excluding:
                        widget.auth.isLocked || _concealed || _resettingSession,
                    child: child!,
                  ),
                ),
                if (widget.auth.isLocked)
                  Positioned.fill(
                    child: ValueListenableBuilder<WelcomeMode>(
                      valueListenable: _brandMode,
                      builder: (context, mode, _) => Theme(
                        data: AppTheme.forNight(mode == WelcomeMode.night),
                        child: HeroControllerScope.none(
                          child: Navigator(
                            key: _lockNavigator,
                            onGenerateRoute: (settings) =>
                                MaterialPageRoute<void>(
                                  settings: settings,
                                  builder: (_) => PopScope(
                                    canPop: false,
                                    child: settings.name == AppRoute.login.path
                                        ? LoginPage(
                                            lockMode: true,
                                            configureBiometrics:
                                                settings.arguments == true,
                                          )
                                        : const WelcomePage(lockMode: true),
                                  ),
                                ),
                          ),
                        ),
                      ),
                    ),
                  ),
                if (_concealed || _resettingSession)
                  const Positioned.fill(
                    child: ColoredBox(color: Color(0xFFF5F7F6)),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
