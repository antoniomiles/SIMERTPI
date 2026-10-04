import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../app/router/app_router.dart';
import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_layout.dart';
import '../data/auth_service.dart';
import '../state/auth_controller.dart';
import 'auth_page.dart';

class RegisterPage extends StatefulWidget {
  const RegisterPage({super.key});
  @override
  State<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends State<RegisterPage> {
  final _form = GlobalKey<FormState>();
  final _fields = List.generate(6, (_) => TextEditingController());
  bool _processing = false, _hidden = true, _created = false;
  String? _message;
  @override
  void dispose() {
    _fields[5].clear();
    for (final field in _fields) {
      field.dispose();
    }
    super.dispose();
  }

  Future<void> _register() async {
    if (_processing || !_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _processing = true;
      _message = null;
    });
    try {
      await AppScope.of(context).auth.gateway.register(
        RegistrationRequest(
          username: _fields[0].text.trim(),
          firstName: _fields[1].text.trim(),
          lastName: _fields[2].text.trim(),
          email: _fields[3].text.trim(),
          phone: _fields[4].text.trim().isEmpty ? null : _fields[4].text.trim(),
          password: _fields[5].text,
        ),
      );
      if (!mounted) return;
      _fields[5].clear();
      setState(() => _created = true);
    } catch (error) {
      if (mounted) {
        setState(() => _message = authErrorMessage(error, registration: true));
      }
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  String? _required(String? value, int max) =>
      value == null || value.trim().isEmpty
      ? 'Completa este campo.'
      : value.length > max
      ? 'Usa como máximo $max caracteres.'
      : null;
  @override
  Widget build(BuildContext context) => AuthPage(
    title: 'Crear cuenta',
    subtitle: 'Registra tus datos para usar el servicio.',
    canGoBack: true,
    child: _created
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Cuenta creada. Ahora puedes iniciar sesión.'),
              const SizedBox(height: AppSpace.lg),
              PrimaryButton(
                label: 'Ir a iniciar sesión',
                onPressed: () => Navigator.of(context)
                    .pushNamedAndRemoveUntil(AppRoute.login.path, (_) => false),
              ),
            ],
          )
        : Form(
            key: _form,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                AppTextField(
                  label: 'Nombre de usuario',
                  controller: _fields[0],
                  enabled: !_processing,
                  textInputAction: TextInputAction.next,
                  validator: (v) =>
                      _required(v, 100) ??
                      (v!.contains(RegExp(r'[\r\n]'))
                          ? 'Revisa tu nombre de usuario.'
                          : null),
                ),
                const SizedBox(height: AppSpace.md),
                AppTextField(
                  label: 'Nombres',
                  controller: _fields[1],
                  enabled: !_processing,
                  textInputAction: TextInputAction.next,
                  validator: (v) => _required(v, 100),
                ),
                const SizedBox(height: AppSpace.md),
                AppTextField(
                  label: 'Apellidos',
                  controller: _fields[2],
                  enabled: !_processing,
                  textInputAction: TextInputAction.next,
                  validator: (v) => _required(v, 100),
                ),
                const SizedBox(height: AppSpace.md),
                AppTextField(
                  label: 'Correo electrónico',
                  controller: _fields[3],
                  enabled: !_processing,
                  keyboardType: TextInputType.emailAddress,
                  textInputAction: TextInputAction.next,
                  validator: (v) =>
                      _required(v, 255) ??
                      (!RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
                              .hasMatch(v!.trim())
                          ? 'Revisa el correo electrónico.'
                          : null),
                ),
                const SizedBox(height: AppSpace.md),
                AppTextField(
                  label: 'Teléfono (opcional)',
                  controller: _fields[4],
                  enabled: !_processing,
                  keyboardType: TextInputType.phone,
                  textInputAction: TextInputAction.next,
                  validator: (v) => v != null && v.length > 30
                      ? 'Usa como máximo 30 caracteres.'
                      : null,
                ),
                const SizedBox(height: AppSpace.md),
                AppTextField(
                  label: 'Contraseña',
                  controller: _fields[5],
                  enabled: !_processing,
                  obscureText: _hidden,
                  autofillHints: const [AutofillHints.newPassword],
                  textInputAction: TextInputAction.done,
                  validator: (v) => v == null || v.trim().isEmpty
                      ? 'Ingresa una contraseña.'
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
                if (_message != null) AuthMessage(_message!),
                AsyncButton(
                  label: 'CREAR CUENTA',
                  processingLabel: 'Creando cuenta…',
                  onPressed: _register,
                ),
              ],
            ),
          ),
  );
}
