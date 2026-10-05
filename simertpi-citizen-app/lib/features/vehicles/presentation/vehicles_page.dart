import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/widgets/app_feedback.dart';
import '../../../core/theme/app_tokens.dart';
import '../data/vehicle_service.dart';
import '../state/vehicles_controller.dart';
import 'vehicle_form_page.dart';
import 'vehicle_list.dart';

class VehiclesPage extends StatefulWidget {
  const VehiclesPage({super.key, this.controller});
  final VehiclesController? controller;
  @override
  State<VehiclesPage> createState() => _VehiclesPageState();
}

class _VehiclesPageState extends State<VehiclesPage> {
  VehiclesController? _controller;
  bool _openingForm = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_controller != null) return;
    final scope = AppScope.of(context);
    _controller =
        widget.controller ??
        VehiclesController(VehicleService(scope.api, scope.auth.userId));
    _controller!.load();
  }

  @override
  void dispose() {
    if (widget.controller == null) _controller?.dispose();
    super.dispose();
  }

  Future<void> _add() async {
    if (_openingForm || _controller!.processing) {
      return;
    }
    _openingForm = true;
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => VehicleFormPage(controller: _controller!),
      ),
    );
    _openingForm = false;
    if (mounted && saved == true) {
      await _controller!.load();
      if (mounted) AppSnackbar.show(context, 'Vehículo registrado.');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Mis vehículos')),
    body: SafeArea(
      child: RefreshIndicator(
        onRefresh: _controller!.load,
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: AppSpace.page,
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: (constraints.maxHeight - 48).clamp(
                  0,
                  double.infinity,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [VehicleList(controller: _controller!, onAdd: _add)],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
