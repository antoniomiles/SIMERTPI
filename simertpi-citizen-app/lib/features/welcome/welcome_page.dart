import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/theme/app_tokens.dart';
import '../auth/data/device_credential.dart';
import '../auth/data/device_security.dart';

class WelcomePage extends StatefulWidget {
  const WelcomePage({super.key, this.lockMode = false});
  final bool lockMode;

  @override
  State<WelcomePage> createState() => _WelcomePageState();
}

class _WelcomePageState extends State<WelcomePage> {
  bool _permissionCheckScheduled = false;
  bool _handling = false;

  Future<void> _biometric() async {
    final auth = AppScope.of(context).auth;
    if (_handling || auth.busy) return;
    _handling = true;
    try {
      final bindings = (await auth.biometricBindings())
          .where((b) => b.usable)
          .toList();
      if (!mounted) return;
      DeviceBinding? selected;
      if (widget.lockMode) {
        selected = bindings.where((b) => b.owner == auth.userId).firstOrNull;
        if (auth.signingDevice == null &&
            auth.biometricStatus == BiometricStatus.enabled) {
          await auth.unlockBiometrics();
          return;
        }
      } else if (bindings.length == 1) {
        selected = bindings.single;
      } else if (bindings.length > 1) {
        selected = await showDialog<DeviceBinding>(
          context: context,
          useRootNavigator: false,
          builder: (context) => SimpleDialog(
            title: const Text('Selecciona tu cuenta'),
            children: bindings
                .map(
                  (b) => SimpleDialogOption(
                    onPressed: () => Navigator.pop(context, b),
                    child: Text(b.username),
                  ),
                )
                .toList(),
          ),
        );
        if (selected == null) return;
      }
      if (selected != null) {
        final success = widget.lockMode
            ? await auth.unlockBiometrics()
            : await auth.loginBiometrics(selected);
        if (!mounted) return;
        if (success && !widget.lockMode) {
          Navigator.of(context)
              .pushNamedAndRemoveUntil(AppRoute.home.path, (_) => false);
        } else if (!success) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                auth.message ?? 'No se puede utilizar la biometría en este momento. Ingresa con tu contraseña',
              ),
            ),
          );
        }
        return;
      }
      if (!mounted) return;
      final configure = await showDialog<bool>(
        context: context,
        useRootNavigator: false,
        builder: (context) => AlertDialog(
          title: const Text('Activar ingreso biométrico'),
          content: const Text(
            '¿Deseas configurar tu huella o reconocimiento facial para ingresar a SIMERTPI desde este dispositivo?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Ahora no'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Configurar'),
            ),
          ],
        ),
      );
      if (configure != true || !mounted) return;
      final available = await auth.deviceSecurity?.status(
        auth.userId ?? 'availability',
      );
      if (!mounted) return;
      if (available == null || available == BiometricStatus.unavailable) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'No se puede utilizar la biometría en este momento. Ingresa con tu contraseña',
            ),
          ),
        );
        return;
      }
      await Navigator.of(context)
          .pushNamed(AppRoute.login.path, arguments: true);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(biometricErrorMessage(error))));
      }
    } finally {
      _handling = false;
    }
  }

  Future<void> _register() async {
    if (!widget.lockMode) {
      Navigator.of(context).pushNamed(AppRoute.register.path);
      return;
    }
    final auth = AppScope.of(context).auth;
    if (_handling || auth.busy) return;
    _handling = true;
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        useRootNavigator: false,
        builder: (context) => AlertDialog(
          title: const Text('Cerrar sesión'),
          content: const Text(
            'Para crear otra cuenta debes cerrar la sesión actual.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Cerrar sesión'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
      // The root clears old private routes after logout. Registration remains
      // available from the same original welcome screen, never the private stack.
      await auth.logout();
    } finally {
      _handling = false;
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_permissionCheckScheduled) return;
    _permissionCheckScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final prompt = widget.lockMode
          ? null
          : AppScope.of(context).welcomePermissionPrompt;
      if (prompt != null) {
        prompt.requestOnce();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final brand =
        Theme.of(context).extension<AppBrandPalette>() ?? AppBrandPalette.day;
    final night = brand.isNight;
    final foreground = brand.primary;
    final logo = night
        ? 'assets/branding/SIMERTPI_logotipo_blanco.png'
        : 'assets/branding/SIMERTPI_logotipo_negro.png';
    final illustration = night
        ? 'assets/welcome/simertpinoche.png'
        : 'assets/welcome/simertpidia.png';

    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 600;
            final textScale = MediaQuery.textScalerOf(context).scale(1);
            final actionHeight = compact ? 56.0 : 70.0;
            final accessibleActionHeight = textScale > 1.4
                ? 108.0
                : actionHeight;
            final createHeight = textScale > 1.4
                ? 72.0
                : compact
                ? 54.0
                : 66.0;
            // The approved PNGs are 941 × 1672. Match their real ratio so the
            // complete illustration reaches both screen edges without crop.
            const artAspectRatio = 941 / 1672;
            final artWidth = constraints.maxWidth;
            final logoWidth = math.min(238.0, artWidth * .86).toDouble();
            final logoHeight = compact ? 44.0 : 60.0;
            final artHeight = artWidth / artAspectRatio;
            final actions = Transform.translate(
              key: const ValueKey('welcome-actions-offset'),
              offset: const Offset(0, -2),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: _WelcomeAction(
                          icon: Icons.person_outline,
                          label: 'Usuario y contrase\u00F1a',
                          foreground: foreground,
                          surface: night
                              ? const Color(0xFFF1F5FA)
                              : const Color(0xFFFAFCFA),
                          height: accessibleActionHeight,
                          onPressed: () =>
                              Navigator.of(context)
                                  .pushNamed(AppRoute.login.path),
                        ),
                      ),
                      const SizedBox(width: AppSpace.sm),
                      Expanded(
                        child: _WelcomeAction(
                          icon: Icons.fingerprint,
                          label: 'Ingresar con huella',
                          foreground: foreground,
                          surface: night
                              ? const Color(0xFFE7EDF5)
                              : const Color(0xFFF1F4F2),
                          height: accessibleActionHeight,
                          biometric: true,
                          onPressed: _biometric,
                        ),
                      ),
                    ],
                  ),
                  SizedBox(height: textScale > 1.4 ? 8 : 4),
                  SizedBox(
                    width: double.infinity,
                    height: createHeight,
                    child: FilledButton.icon(
                      key: const ValueKey('welcome-create-account'),
                      onPressed: _register,
                      icon: const Icon(Icons.person_add_alt_1),
                      label: const Text('Crear cuenta'),
                      style: FilledButton.styleFrom(
                        backgroundColor: foreground,
                        foregroundColor: Colors.white,
                        minimumSize: const Size(48, 48),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );

            if (artHeight > constraints.maxHeight) {
              return SingleChildScrollView(
                key: const ValueKey('welcome-constrained-scroll'),
                child: Column(
                  children: [
                    SizedBox(
                      width: artWidth,
                      height: artHeight,
                      child: Stack(
                        alignment: Alignment.topCenter,
                        children: [
                          Image.asset(
                            illustration,
                            key: ValueKey(
                              'welcome-art-${night ? 'night' : 'day'}',
                            ),
                            width: artWidth,
                            height: artHeight,
                            fit: BoxFit.fill,
                            excludeFromSemantics: true,
                          ),
                          Padding(
                            padding: const EdgeInsets.only(top: 36),
                            child: Image.asset(
                              logo,
                              key: ValueKey(
                                'welcome-logo-${night ? 'night' : 'day'}',
                              ),
                              width: logoWidth,
                              height: logoHeight,
                              fit: BoxFit.contain,
                              semanticLabel:
                                  'SIMERTPI, Tu estacionamiento municipal',
                            ),
                          ),
                        ],
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 16, 18, 12),
                      child: actions,
                    ),
                  ],
                ),
              );
            }

            return Stack(
              key: const ValueKey('welcome-no-scroll-layout'),
              fit: StackFit.expand,
              children: [
                Align(
                  alignment: Alignment.topCenter,
                  child: Image.asset(
                    illustration,
                    key: ValueKey('welcome-art-${night ? 'night' : 'day'}'),
                    width: artWidth,
                    height: artHeight,
                    fit: BoxFit.fill,
                    alignment: Alignment.topCenter,
                    excludeFromSemantics: true,
                  ),
                ),
                Positioned(
                  top: compact ? 34 : 36,
                  left: 0,
                  right: 0,
                  child: Center(
                    child: Image.asset(
                      logo,
                      key: ValueKey('welcome-logo-${night ? 'night' : 'day'}'),
                      width: logoWidth,
                      height: logoHeight,
                      fit: BoxFit.contain,
                      semanticLabel: 'SIMERTPI, Tu estacionamiento municipal',
                    ),
                  ),
                ),
                Positioned(left: 18, right: 18, bottom: 12, child: actions),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _WelcomeAction extends StatelessWidget {
  const _WelcomeAction({
    required this.icon,
    required this.label,
    required this.foreground,
    required this.surface,
    required this.height,
    required this.onPressed,
    this.biometric = false,
  });

  final IconData icon;
  final String label;
  final Color foreground;
  final Color surface;
  final double height;
  final bool biometric;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: true,
      label: label,
      child: SizedBox(
        height: height,
        child: OutlinedButton(
          key: ValueKey(
            biometric ? 'welcome-biometric-action' : 'welcome-login-action',
          ),
          onPressed: onPressed,
          style: OutlinedButton.styleFrom(
            foregroundColor: foreground,
            backgroundColor: surface,
            side: BorderSide(color: foreground.withValues(alpha: .28)),
            minimumSize: const Size(48, 48),
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Icon(
                icon,
                key: ValueKey(
                  biometric ? 'welcome-biometric-icon' : 'welcome-login-icon',
                ),
                size: 20,
              ),
              const SizedBox(height: 2),
              Flexible(
                child: Text(
                  label,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontFamily: 'Poppins',
                    fontSize: 11,
                    height: 1.1,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              // Preserve the approved icon/label positions after removing the
              // pending badge: reserve its original line without visible text.
              if (biometric)
                const ExcludeSemantics(
                  child: Text(
                    ' ',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 10,
                      fontFamily: 'Poppins',
                      fontWeight: FontWeight.w400,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
