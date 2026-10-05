import 'package:flutter/material.dart';

import '../../../core/widgets/app_layout.dart';
import '../../../core/widgets/app_buttons.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/vehicle_service.dart';
import '../state/vehicles_controller.dart';
import 'vehicle_plate.dart';

class VehicleFormPage extends StatefulWidget {
  const VehicleFormPage({super.key, required this.controller});
  final VehiclesController controller;
  @override
  State<VehicleFormPage> createState() => _VehicleFormPageState();
}

class _VehicleFormPageState extends State<VehicleFormPage> {
  final _form = GlobalKey<FormState>();
  final _fields = List.generate(4, (_) => TextEditingController());
  @override
  void dispose() {
    for (final field in _fields) {
      field.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    String? optional(int i) => _fields[i].text.isEmpty ? null : _fields[i].text;
    final saved = await widget.controller.create(
      VehicleInput(
        _fields[0].text.trim().toUpperCase(),
        brand: optional(1),
        model: optional(2),
        color: optional(3),
      ),
    );
    if (mounted && saved) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) => PopScope(
      canPop: !widget.controller.processing,
      child: AppPage(
        title: 'Registrar vehículo',
        child: Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Datos del vehículo',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: AppSpace.md),
              const Text(
                'La placa es obligatoria. Los demás campos son opcionales.',
              ),
              const SizedBox(height: AppSpace.lg),
              for (var i = 0; i < _fields.length; i++) ...[
                AppTextField(
                  label: [
                    'Placa',
                    'Marca (opcional)',
                    'Modelo (opcional)',
                    'Color (opcional)',
                  ][i],
                  controller: _fields[i],
                  inputFormatters: i == 0 ? [UppercasePlateFormatter()] : null,
                  enabled: !widget.controller.processing,
                  textInputAction: i == 3
                      ? TextInputAction.done
                      : TextInputAction.next,
                  validator: (value) {
                    if (i == 0 && (value == null || value.trim().isEmpty)) {
                      return 'Ingresa la placa.';
                    }
                    if ((value?.length ?? 0) > [10, 100, 100, 50][i]) {
                      return 'El texto supera el máximo permitido.';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: AppSpace.md),
              ],
              if (widget.controller.message != null)
                Semantics(
                  liveRegion: true,
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: AppSpace.md),
                    child: Text(widget.controller.message!),
                  ),
                ),
              AsyncButton(
                label: 'Guardar vehículo',
                processingLabel: 'Guardando…',
                onPressed: _save,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
