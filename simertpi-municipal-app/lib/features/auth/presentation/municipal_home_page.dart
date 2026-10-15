import 'package:flutter/material.dart';

import '../domain/municipal_session.dart';
import 'municipal_auth_controller.dart';

class MunicipalHomePage extends StatelessWidget {
  const MunicipalHomePage({
    super.key,
    required this.session,
    required this.controller,
  });

  final MunicipalSession session;
  final MunicipalAuthController controller;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('SIMERTPI Municipal'),
      actions: [
        IconButton(
          key: const Key('municipal-logout'),
          tooltip: 'Cerrar sesión',
          onPressed: controller.isBusy ? null : controller.signOut,
          icon: const Icon(Icons.logout),
        ),
      ],
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.verified_user_outlined, size: 40),
                  const SizedBox(height: 16),
                  Text(
                    'Sesión municipal activa',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Inspector: ${session.identity.username}',
                    key: const Key('municipal-authenticated-identity'),
                  ),
                  if (controller.failure != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      'No se pudo renovar la sesión: ${controller.failure!.userMessage}',
                      key: const Key('municipal-session-warning'),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  const SizedBox(height: 8),
                  Text(
                    'Acceso válido hasta ${session.accessExpiresAt.toLocal()}',
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
