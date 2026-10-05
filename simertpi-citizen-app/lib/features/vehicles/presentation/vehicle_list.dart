import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';
import '../../../core/theme/app_typography.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/widgets/app_skeleton.dart';
import '../data/vehicle_service.dart';
import '../state/vehicles_controller.dart';
import 'vehicle_plate.dart';

class VehicleList extends StatelessWidget {
  const VehicleList({super.key, required this.controller, required this.onAdd});
  final VehiclesController controller;
  final VoidCallback onAdd;

  Future<void> _deactivate(BuildContext context, CitizenVehicle vehicle) async {
    if (controller.processing) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) {
          var processing = controller.processing;
          return PopScope(
            canPop: !processing,
            child: AlertDialog(
              scrollable: true,
              title: const Text('Dar de baja vehículo'),
              content: Text(
                '¿Deseas dar de baja ${vehicle.plate.toUpperCase()} de tu cuenta?\n\nEl vehículo dejará de estar disponible para nuevos estacionamientos. Tu historial anterior se conservará.',
              ),
              actions: [
                TextButton(
                  onPressed: processing
                      ? null
                      : () => Navigator.pop(dialogContext),
                  child: const Text('Cancelar'),
                ),
                AsyncButton(
                  label: 'Dar de baja',
                  processingLabel: 'Dando de baja…',
                  onPressed: () async {
                    final result = controller.deactivate(vehicle.id);
                    setDialogState(() {});
                    final success = await result;
                    if (!dialogContext.mounted) return;
                    if (success) {
                      Navigator.pop(dialogContext);
                      if (context.mounted) {
                        AppSnackbar.show(context, 'Vehículo dado de baja.');
                      }
                    } else {
                      Navigator.pop(dialogContext);
                      if (context.mounted) {
                        AppSnackbar.show(
                          context,
                          controller.message ??
                              'No pudimos dar de baja el vehículo.',
                        );
                      }
                    }
                  },
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final state = controller.phase;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Mis vehículos', style: AppTypography.section),
          const SizedBox(height: AppSpace.md),
          if (state == VehiclesPhase.initial || state == VehiclesPhase.loading)
            const SkeletonList(count: 2)
          else if (state == VehiclesPhase.error)
            ErrorState(message: controller.message!, onRetry: controller.load)
          else ...[
            if (controller.refreshing) const LinearProgressIndicator(),
            if (controller.message != null) ...[
              ErrorState(
                message: controller.message!,
                onRetry: controller.load,
              ),
              const SizedBox(height: AppSpace.md),
            ],
            if (state == VehiclesPhase.empty)
              const EmptyState(message: 'No tienes vehículos registrados.'),
            for (final vehicle in controller.items) ...[
              _VehicleCard(
                vehicle: vehicle,
                processing: controller.processing,
                onDeactivate: () => _deactivate(context, vehicle),
              ),
              const SizedBox(height: AppSpace.sm),
            ],
          ],
          const SizedBox(height: AppSpace.sm),
          SizedBox(
            key: const ValueKey('vehicles-add-footer'),
            width: double.infinity,
            child: FilledButton.tonalIcon(
              onPressed: controller.processing ? null : onAdd,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.input,
                foregroundColor: AppColors.primary,
                minimumSize: const Size(48, 56),
              ),
              icon: const Icon(Icons.add),
              label: const Text('Agregar vehículo'),
            ),
          ),
        ],
      );
    },
  );
}

class _VehicleCard extends StatelessWidget {
  const _VehicleCard({
    required this.vehicle,
    required this.processing,
    required this.onDeactivate,
  });
  final CitizenVehicle vehicle;
  final bool processing;
  final VoidCallback onDeactivate;
  @override
  Widget build(BuildContext context) {
    final plate = vehicle.plate.toUpperCase();
    final menu = PopupMenuButton<String>(
      enabled: !processing,
      tooltip: 'Opciones del vehículo $plate',
      constraints: const BoxConstraints(minWidth: 180),
      onSelected: (_) => onDeactivate(),
      itemBuilder: (_) => [
        PopupMenuItem(
          value: 'deactivate',
          child: Semantics(
            label: 'Dar de baja vehículo $plate',
            child: const Text('Dar de baja vehículo'),
          ),
        ),
      ],
      icon: const Icon(Icons.more_vert),
    );
    final information = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          label: 'Vehículo, placa ${plate.split('').join(' ')}',
          child: ExcludeSemantics(
            child: Text(plate, style: AppTypography.plate),
          ),
        ),
        const SizedBox(height: AppSpace.xs),
        Text('Vehículo registrado', style: AppTypography.caption),
      ],
    );
    return Container(
      padding: const EdgeInsets.all(AppSpace.md),
      decoration: BoxDecoration(
        color: AppColors.input,
        borderRadius: BorderRadius.circular(AppSize.buttonRadius),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          if (MediaQuery.textScalerOf(context).scale(16) > 24) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: VehiclePlate(plate: plate)),
                    const SizedBox(width: AppSpace.sm),
                    menu,
                  ],
                ),
                const SizedBox(height: AppSpace.md),
                information,
              ],
            );
          }
          final plateWidth = (constraints.maxWidth * .40).clamp(72.0, 150.0);
          return Row(
            children: [
              SizedBox(
                width: plateWidth,
                child: VehiclePlate(plate: plate),
              ),
              const SizedBox(width: AppSpace.sm),
              Expanded(child: information),
              menu,
            ],
          );
        },
      ),
    );
  }
}
