import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../app/router/app_router.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_layout.dart';
import 'auth_page.dart';
import '../state/auth_controller.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _form = GlobalKey<FormState>();
  final _username = TextEditingController(),
      _password = TextEditingController();
  bool _hidden = true;
  @override
  void dispose() {
    _username.dispose();
    _password.clear();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (!_form.currentState!.validate()) return;
    final auth = AppScope.of(context).auth;
    FocusScope.of(context).unfocus();
    final success = await auth.login(_username.text, _password.text);
    if (!mounted) return;
    _password.clear();
    if (success) {
      Navigator.of(context)
          .pushNamedAndRemoveUntil(AppRoute.home.path, (_) => false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = AppScope.of(context).auth;
    return ListenableBuilder(
      listenable: auth,
      builder: (context, _) => AuthPage(
        title: 'Inicia sesión',
        subtitle: 'Accede a tu cuenta SIMERTPI.',
        child: Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AppTextField(
                label: 'Nombre de usuario',
                controller: _username,
                enabled: auth.phase != AuthPhase.processing,
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
                enabled: auth.phase != AuthPhase.processing,
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
              AsyncButton(
                label: 'INGRESAR',
                processingLabel: 'Ingresando…',
                onPressed: _login,
              ),
              const SizedBox(height: AppSpace.sm),
              TextButton(
                onPressed: auth.phase == AuthPhase.processing
                    ? null
                    : () =>
                          Navigator.pushNamed(context, AppRoute.register.path),
                child: const Text('¿No tienes cuenta? Crear cuenta'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
