import 'package:flutter/material.dart';

import '../../../core/config/municipal_app_config.dart';
import '../../enforcement/data/inspector_enforcement_api.dart';
import '../../enforcement/presentation/inspector_workspace_page.dart';
import '../data/municipal_auth_api.dart';
import '../data/municipal_token_store.dart';
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
                  const SizedBox(height: 20),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      key: const Key('open-inspector-workspace'),
                      onPressed: session.identity.canInspect
                          ? () {
                              final api = InspectorEnforcementApi(
                                config: MunicipalAppConfig.fromEnvironment(),
                                tokenStore: SecureMunicipalTokenStore(),
                                transport: IoMunicipalHttpTransport(),
                              );
                              Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => InspectorWorkspacePage(
                                    inspectorId: session.identity.id,
                                    repository: api,
                                    onSessionExpired: () =>
                                        controller.signOut(),
                                  ),
                                ),
                              );
                            }
                          : null,
                      icon: const Icon(Icons.fact_check_outlined),
                      label: const Text('Inspecciones'),
                    ),
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
