import 'package:flutter/material.dart';

import '../../core/theme/app_tokens.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/app_layout.dart';

class ComponentsPage extends StatelessWidget {
  const ComponentsPage({super.key});
  @override
  Widget build(BuildContext context) => AppPage(
    title: 'Componentes',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Cada acción tiene respuesta',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppSpace.sm),
        const Text('Componentes de demostración. No se envía información.'),
        const SizedBox(height: AppSpace.lg),
        const AppTextField(label: 'Texto de ejemplo'),
        const SizedBox(height: AppSpace.lg),
        AsyncButton(
          label: 'Probar feedback',
          onPressed: () async {
            AppSnackbar.show(context, 'Acción de demostración completada.');
          },
        ),
        const SizedBox(height: AppSpace.md),
        const SecondaryButton(label: 'Acción no disponible'),
        const SizedBox(height: AppSpace.xl),
        const Divider(),
        const SizedBox(height: AppSpace.md),
        const Text(
          'Las acciones con consecuencias utilizan un estilo diferenciado.',
        ),
        const SizedBox(height: AppSpace.md),
        DestructiveButton(
          label: 'Ver confirmación',
          onPressed: () async {
            await AppDialog.confirm(
              context,
              title: 'Confirmación de demostración',
              message: 'No se eliminará información. Este diálogo muestra una acción importante.',
              actionLabel: 'Confirmar ejemplo',
            );
          },
        ),
      ],
    ),
  );
}
