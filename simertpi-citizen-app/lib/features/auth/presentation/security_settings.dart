import 'package:flutter/material.dart';

import '../../../app/bootstrap/bootstrap.dart';
import '../../../core/widgets/operational_ui.dart';
import '../data/device_security.dart';

class SecuritySettings extends StatefulWidget {
  const SecuritySettings({super.key});
  @override
  State<SecuritySettings> createState() => _SecuritySettingsState();
}

class _SecuritySettingsState extends State<SecuritySettings> {
  bool _loaded = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_loaded) {
      _loaded = true;
      AppScope.of(context).auth.refreshBiometricStatus();
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = AppScope.of(context).auth;
    return ListenableBuilder(
      listenable: auth,
      builder: (context, _) => OperationalCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Seguridad', style: Theme.of(context).textTheme.titleMedium),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Ingreso biométrico'),
              subtitle: Text(
                'Utiliza huella o reconocimiento facial en este dispositivo\n${switch (auth.biometricStatus) {
                  BiometricStatus.enabled => 'Habilitado',
                  BiometricStatus.disabled => 'Deshabilitado',
                  BiometricStatus.unavailable => 'No disponible',
                }}',
              ),
              value: auth.biometricStatus == BiometricStatus.enabled,
              onChanged:
                  auth.busy ||
                      auth.biometricStatus == BiometricStatus.unavailable
                  ? null
                  : (enable) async {
                      if (enable) {
                        final success = await auth.enableBiometrics();
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                success
                                    ? 'Ingreso biométrico activado en este dispositivo'
                                    : auth.message ?? 'No pudimos activar el ingreso biométrico',
                              ),
                            ),
                          );
                        }
                      } else {
                        final confirm = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('Desactivar biometría'),
                            content: const Text(
                              'Podrás continuar ingresando con tu contraseña.',
                            ),
                            actions: [
                              TextButton(
                                onPressed: () => Navigator.pop(context, false),
                                child: const Text('Cancelar'),
                              ),
                              FilledButton(
                                onPressed: () => Navigator.pop(context, true),
                                child: const Text('Desactivar'),
                              ),
                            ],
                          ),
                        );
                        if (confirm == true) await auth.disableBiometrics();
                      }
                    },
            ),
            const ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text('Bloqueo por inactividad'),
              subtitle: Text(
                'SIMERTPI se bloquea después de cinco minutos sin actividad\nActivo',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
