import 'dart:async';

import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../app/router/app_router.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_layout.dart';
import 'auth_page.dart';
import '../state/auth_controller.dart';
import 'biometric_invitation.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({
    super.key,
    this.lockMode = false,
    this.configureBiometrics = false,
  });
  final bool lockMode;
  final bool configureBiometrics;
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _form = GlobalKey<FormState>();
  final _username = TextEditingController(),
      _password = TextEditingController();
  bool _hidden = true;
  bool _initialized = false, _submitting = false, _finishing = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      _initialized = true;
      if (widget.lockMode) {
        _username.text = AppScope.of(context).auth.username ?? '';
      }
    }
  }

  @override
  void dispose() {
    _username.dispose();
    _password.clear();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (_submitting || _finishing) return;
    if (!_form.currentState!.validate()) return;
    final auth = AppScope.of(context).auth;
    FocusScope.of(context).unfocus();
    _submitting = true;
    try {
      final success = widget.lockMode
          ? await auth.unlockPassword(
              _username.text,
              _password.text,
              configureBiometrics: widget.configureBiometrics,
            )
          : await auth.login(_username.text, _password.text);
      if (!mounted) return;
      if (success) {
        _password.clear();
        if (!widget.lockMode) {
          _finishing = true;
          // Login loading ends before the optional dialog awaits human input.
          unawaited(_finishLogin(auth));
        }
      }
    } finally {
      _submitting = false;
    }
  }

  Future<void> _finishLogin(AuthController auth) async {
    try {
      if (widget.configureBiometrics) {
        final success = await auth.enableBiometrics();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                success
                    ? 'Ingreso biométrico activado en este dispositivo'
                    : auth.message ??
                          'No pudimos activar el ingreso biométrico',
              ),
            ),
          );
        }
      } else {
        await offerBiometricInvitation(context, auth);
      }
    } finally {
      _finishing = false;
      auth.completeLoginPresentation();
      if (mounted && auth.isAuthenticated && !auth.isLocked) {
        Navigator.of(context)
            .pushNamedAndRemoveUntil(AppRoute.home.path, (_) => false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = AppScope.of(context).auth;
    return ListenableBuilder(
      listenable: auth,
      builder: (context, _) => AuthPage(
        title: widget.lockMode ? 'Desbloquea SIMERTPI' : 'Inicia sesión',
        subtitle: widget.lockMode
            ? 'Verifica tu identidad para continuar.'
            : 'Accede a tu cuenta SIMERTPI.',
        canGoBack: !widget.lockMode && Navigator.of(context).canPop(),
        child: Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AppTextField(
                label: 'Nombre de usuario',
                controller: _username,
                enabled:
                    !auth.busy && (!widget.lockMode || auth.username == null),
                autofillHints: const [AutofillHints.username],
                textInputAction: TextInputAction.next,
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Ingresa tu nombre de usuario.'
                    : value.contains(RegExp(r'[\r\n]'))
                    ? 'Revisa tu nombre de usuario.'
                    : null,
              ),
              const SizedBox(height: AppSpace.md),
              AppTextField(
                label: 'Contraseña',
                controller: _password,
                obscureText: _hidden,
                enabled: !auth.busy,
                autofillHints: const [AutofillHints.password],
                textInputAction: TextInputAction.done,
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Ingresa tu contraseña.'
                    : null,
                suffixIcon: IconButton(
                  tooltip: _hidden
                      ? 'Mostrar contraseña'
                      : 'Ocultar contraseña',
                  onPressed: () => setState(() => _hidden = !_hidden),
                  icon: Icon(
                    _hidden
                        ? Icons.visibility_outlined
                        : Icons.visibility_off_outlined,
                  ),
                ),
              ),
              const SizedBox(height: AppSpace.lg),
              if (auth.message != null) AuthMessage(auth.message!),
              if (auth.canRetryLogin)
                TextButton(
                  onPressed: auth.busy ? null : _login,
                  child: const Text('Reintentar'),
                ),
              AsyncButton(
                label: 'INGRESAR',
                processingLabel: 'Ingresando…',
                onPressed: _login,
              ),
              const SizedBox(height: AppSpace.sm),
              if (widget.lockMode)
                TextButton(
                  onPressed: auth.busy ? null : () => auth.logout(),
                  child: const Text('Cerrar sesión'),
                ),
              if (!widget.lockMode)
                TextButton(
                  onPressed: auth.phase == AuthPhase.processing
                      ? null
                      : () => Navigator.pushNamed(
                          context,
                          AppRoute.register.path,
                        ),
                  child: const Text('¿No tienes cuenta? Crear cuenta'),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
