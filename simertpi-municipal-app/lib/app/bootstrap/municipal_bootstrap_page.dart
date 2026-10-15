import 'package:flutter/material.dart';

import '../../core/config/municipal_app_config.dart';

class MunicipalBootstrapPage extends StatelessWidget {
  const MunicipalBootstrapPage({super.key, required this.config});

  final MunicipalAppConfig config;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('SIMERTPI Municipal')),
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
                    const Icon(Icons.account_balance_outlined, size: 40),
                    const SizedBox(height: 16),
                    Text(
                      'Aplicación municipal en preparación',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      'La arquitectura base está lista. El acceso municipal y los turnos todavía no están habilitados.',
                    ),
                    const SizedBox(height: 20),
                    Text('Entorno: ${config.environment.label}'),
                    const SizedBox(height: 8),
                    Text(config.configurationMessage),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
