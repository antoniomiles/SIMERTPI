import 'package:flutter/material.dart';

import '../../app/router/app_router.dart';
import '../../app/bootstrap/bootstrap.dart';
import '../../core/theme/app_tokens.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_layout.dart';

class HomePage extends StatelessWidget {
  const HomePage({super.key});
  @override
  Widget build(BuildContext context) => AppPage(
    title: 'SIMERTPI',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Chip(label: Text('Demostración')),
        const SizedBox(height: AppSpace.lg),
        Text(
          'Una experiencia clara, desde el inicio.',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: AppSpace.md),
        const Text(
          'Esta vista presenta la base visual de la aplicación ciudadana. '
          'No realiza trámites ni operaciones de estacionamiento.',
        ),
        const SizedBox(height: AppSpace.xl),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(
                Icons.touch_app_outlined,
                color: AppColors.accent,
                size: 32,
              ),
              const SizedBox(height: AppSpace.md),
              Text(
                'Explora la interfaz',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: AppSpace.sm),
              const Text(
                'Botones, campos y mensajes con un comportamiento consistente.',
              ),
              const SizedBox(height: AppSpace.lg),
              SizedBox(
                width: double.infinity,
                child: PrimaryButton(
                  label: 'Ver componentes',
                  onPressed: () =>
                      Navigator.pushNamed(context, AppRoute.components.path),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpace.lg),
        SizedBox(
          width: double.infinity,
          child: SecondaryButton(
            label: 'Ver estados de pantalla',
            onPressed: () => Navigator.pushNamed(context, AppRoute.states.path),
          ),
        ),
        const SizedBox(height: AppSpace.lg),
        TextButton(
          onPressed: () => AppScope.of(context).auth.logout(),
          child: const Text('Cerrar sesión'),
        ),
        const Text('Base visual de CP17 · autenticación ciudadana habilitada.'),
      ],
    ),
  );
}
