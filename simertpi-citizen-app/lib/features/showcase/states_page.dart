import 'package:flutter/material.dart';

import 'showcase_controller.dart';

import '../../core/errors/app_failure.dart';
import '../../core/state/async_state.dart';
import '../../core/theme/app_tokens.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/app_layout.dart';
import '../../core/widgets/app_skeleton.dart';

class StatesPage extends StatefulWidget {
  const StatesPage({super.key});
  @override
  State<StatesPage> createState() => _StatesPageState();
}

class _StatesPageState extends State<StatesPage> {
  final _controller = ShowcaseController();
  LoadPhase get _phase => _controller.phase;
  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _select(LoadPhase phase) => _controller.select(phase);
  @override
  Widget build(BuildContext context) => AppPage(
    title: 'Estados de pantalla',
    child: ListenableBuilder(
      listenable: _controller,
      builder: (context, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'La información cambia. El contexto permanece.',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: AppSpace.sm),
          const Text(
            'Demostración manual de estados. No hay consultas ni esperas artificiales.',
          ),
          const SizedBox(height: AppSpace.lg),
          Wrap(
            spacing: AppSpace.sm,
            runSpacing: AppSpace.sm,
            children: [
              for (final entry in const {
                LoadPhase.loading: 'Cargando',
                LoadPhase.empty: 'Vacío',
                LoadPhase.error: 'Error',
                LoadPhase.success: 'Listo',
              }.entries)
                ChoiceChip(
                  label: Text(entry.value),
                  selected: _phase == entry.key,
                  onSelected: (_) => _select(entry.key),
                ),
            ],
          ),
          const SizedBox(height: AppSpace.lg),
          if (_phase == LoadPhase.loading) ...[
            const SkeletonList(count: 2),
            const SizedBox(height: AppSpace.md),
            SecondaryButton(
              label: 'Mostrar contenido de ejemplo',
              onPressed: () => _select(LoadPhase.success),
            ),
          ] else
            AppCard(
              child: switch (_phase) {
                LoadPhase.empty => const EmptyState(
                  message: 'Esta sección de ejemplo no tiene información.',
                ),
                LoadPhase.error => ErrorState(
                  message: const AppFailure(FailureKind.offline).message,
                  onRetry: () => _select(LoadPhase.success),
                ),
                LoadPhase.success => const Column(
                  children: [
                    Icon(
                      Icons.check_circle_outline,
                      color: AppColors.accent,
                      size: 32,
                    ),
                    SizedBox(height: AppSpace.md),
                    Text('Vista de demostración lista.'),
                  ],
                ),
                _ => const Text(
                  'Selecciona un estado para ver su presentación.',
                ),
              },
            ),
        ],
      ),
    ),
  );
}
