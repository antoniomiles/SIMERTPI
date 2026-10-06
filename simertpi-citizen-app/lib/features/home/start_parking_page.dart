import 'package:flutter/material.dart';

import '../../app/router/app_router.dart';
import '../../core/widgets/app_layout.dart';
import '../../core/widgets/app_buttons.dart';
import '../../core/widgets/operational_ui.dart';
import '../../core/theme/app_tokens.dart';

class StartParkingPage extends StatelessWidget {
  const StartParkingPage({super.key});
  @override
  Widget build(BuildContext context) => AppPage(
    showNavigation: true,
    title: 'Buscar estacionamiento',
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '¿Dónde vas a estacionar?',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: AppSpace.md),
        const Text('Escanea el QR del espacio o búscalo manualmente.'),
        const SizedBox(height: AppSpace.lg),
        const OperationalCard(
          color: AppColors.parkingHint,
          border: Colors.transparent,
          child: Column(
            children: [
              Padding(
                padding: EdgeInsets.all(16),
                child: Icon(Icons.qr_code_2, size: 72, color: AppColors.action),
              ),
              Text('Escanear código QR'),
              Text('Identifica zona y espacio'),
            ],
          ),
        ),
        const SizedBox(height: AppSpace.md),
        PrimaryButton(
          label: 'ESCANEAR QR',
          onPressed: () => Navigator.pushNamed(context, AppRoute.qr.path),
        ),
        const SizedBox(height: AppSpace.md),
        const Row(
          children: [
            Expanded(child: Divider()),
            Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text('o'),
            ),
            Expanded(child: Divider()),
          ],
        ),
        const SizedBox(height: AppSpace.md),
        SecondaryButton(
          label: 'BUSCAR ESTACIONAMIENTO',
          onPressed: () =>
              Navigator.pushNamed(context, AppRoute.discovery.path),
        ),
      ],
    ),
  );
}
