import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../state/vehicles_controller.dart';

class VehicleList extends StatelessWidget {
  const VehicleList({
    super.key,
    required this.controller,
    required this.onAdd,
    this.limit,
  });
  final VehiclesController controller;
  final VoidCallback onAdd;
  final int? limit;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final state = controller.phase;
      if (state == VehiclesPhase.initial || state == VehiclesPhase.loading) {
        return const SkeletonList(count: 2);
      }
      if (state == VehiclesPhase.error) {
        return ErrorState(
          message: controller.message!,
          onRetry: controller.load,
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (controller.refreshing) const LinearProgressIndicator(),
          if (controller.message != null) ...[
            ErrorState(message: controller.message!, onRetry: controller.load),
            const SizedBox(height: AppSpace.md),
          ],
          if (state == VehiclesPhase.empty)
            EmptyState(
              message: 'No tienes vehículos registrados.',
              actionLabel: 'Registrar vehículo',
              onAction: onAdd,
            ),
          for (final vehicle in controller.items.take(
            limit ?? controller.items.length,
          )) ...[
            Semantics(
              label: 'Vehículo, placa ${vehicle.plate.split('').join(' ')}',
              child: Container(
                padding: const EdgeInsets.all(AppSpace.md),
                decoration: BoxDecoration(
                  color: AppColors.input,
                  borderRadius: BorderRadius.circular(AppSize.buttonRadius),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ExcludeSemantics(
                      child: Text(
                        vehicle.plate,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    const SizedBox(height: AppSpace.xs),
                    Text(
                      vehicle.description.isEmpty
                          ? 'Vehículo registrado'
                          : vehicle.description,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    if (!vehicle.active) const Text('Inactivo'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: AppSpace.sm),
          ],
        ],
      );
    },
  );
}
