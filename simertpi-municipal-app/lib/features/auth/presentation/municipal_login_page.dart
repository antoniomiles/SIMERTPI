import 'package:flutter/material.dart';

import '../../../core/config/municipal_app_config.dart';
import '../../../core/errors/municipal_auth_failure.dart';
import '../domain/municipal_session.dart';
import 'municipal_auth_controller.dart';

class MunicipalLoginPage extends StatefulWidget {
  const MunicipalLoginPage({
    super.key,
    required this.controller,
    required this.config,
  });

  final MunicipalAuthController controller;
  final MunicipalAppConfig config;

  @override
  State<MunicipalLoginPage> createState() => _MunicipalLoginPageState();
}

class _MunicipalLoginPageState extends State<MunicipalLoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _obscurePassword = true;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!widget.config.isApiConfigured ||
        !(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    FocusScope.of(context).unfocus();
    await widget.controller.signIn(
      username: _username.text,
      password: _password.text,
    );
    if (widget.controller.status == MunicipalAuthStatus.authenticated) {
      _password.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final busy = widget.controller.status == MunicipalAuthStatus.authenticating;
    final failure = widget.controller.failure;
    final width = MediaQuery.sizeOf(context).width;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: width < 600 ? 480 : 440),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Icon(
                          Icons.account_balance_outlined,
                          size: 48,
                          color: Color(0xFF0B6B3A),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'SIMERTPI Municipal',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Acceso para personal municipal',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 28),
                        TextFormField(
                          key: const Key('municipal-username'),
                          controller: _username,
                          enabled: !busy,
                          keyboardType: TextInputType.text,
                          textInputAction: TextInputAction.next,
                          autofillHints: const [AutofillHints.username],
                          decoration: const InputDecoration(
                            labelText: 'Usuario',
                            prefixIcon: Icon(Icons.person_outline),
                            border: OutlineInputBorder(),
                          ),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                              ? 'Ingresa tu usuario.'
                              : null,
                        ),
                        const SizedBox(height: 16),
                        TextFormField(
                          key: const Key('municipal-password'),
                          controller: _password,
                          enabled: !busy,
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.done,
                          autofillHints: const [AutofillHints.password],
                          onFieldSubmitted: (_) => _submit(),
                          decoration: InputDecoration(
                            labelText: 'Contraseña',
                            prefixIcon: const Icon(Icons.lock_outline),
                            border: const OutlineInputBorder(),
                            suffixIcon: IconButton(
                              tooltip: _obscurePassword
                                  ? 'Mostrar contraseña'
                                  : 'Ocultar contraseña',
                              onPressed: busy
                                  ? null
                                  : () => setState(
                                      () =>
                                          _obscurePassword = !_obscurePassword,
                                    ),
                              icon: Icon(
                                _obscurePassword
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                              ),
                            ),
                          ),
                          validator: (value) => value == null || value.isEmpty
                              ? 'Ingresa tu contraseña.'
                              : null,
                        ),
                        if (failure != null) ...[
                          const SizedBox(height: 16),
                          Semantics(
                            liveRegion: true,
                            child: Text(
                              failure.userMessage,
                              key: const Key('municipal-login-error'),
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                          if (failure.kind ==
                              MunicipalAuthFailureKind.unavailable) ...[
                            const SizedBox(height: 8),
                            Text(
                              'Entorno: ${widget.config.environment.label}',
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ],
                        if (!widget.config.isApiConfigured) ...[
                          const SizedBox(height: 16),
                          const Text(
                            'Backend sin configurar. No se realizarán conexiones.',
                            key: Key('municipal-backend-unconfigured'),
                            textAlign: TextAlign.center,
                          ),
                        ],
                        const SizedBox(height: 24),
                        FilledButton(
                          key: const Key('municipal-login-submit'),
                          onPressed: busy || !widget.config.isApiConfigured
                              ? null
                              : _submit,
                          child: busy
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text('Iniciar sesión'),
                        ),
                        if (widget.controller.canRetryRestore) ...[
                          const SizedBox(height: 8),
                          TextButton(
                            onPressed: busy
                                ? null
                                : widget.controller.retryRestore,
                            child: const Text('Reintentar verificación'),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
