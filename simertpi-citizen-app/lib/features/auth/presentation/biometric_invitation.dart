import 'package:flutter/material.dart';

import '../state/auth_controller.dart';

Future<void> offerBiometricInvitation(
  BuildContext context,
  AuthController auth,
) async {
  if (!await auth.shouldInviteBiometrics() || !context.mounted) return;
  // Durable installation marker before presentation, independent of the choice.
  try {
    await auth.markBiometricInvitation();
  } catch (_) {
    return;
  }
  if (!context.mounted) return;
  final enabled = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      title: const Text('Activa el ingreso biométrico'),
      content: const SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '¿Deseas utilizar tu huella o reconocimiento facial para acceder más fácilmente a SIMERTPI en este dispositivo?',
            ),
            SizedBox(height: 12),
            Text('SIMERTPI no almacena tus huellas ni datos faciales'),
            SizedBox(height: 12),
            Text(
              'Las personas que tengan biometría registrada en este teléfono también podrían acceder a tu sesión',
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Ahora no'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Activar biometría'),
        ),
      ],
    ),
  );
  if (enabled != true) {
    try {
      await auth.declineBiometrics();
    } catch (_) {}
    return;
  }
  final success = await auth.enableBiometrics();
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        success
            ? 'Ingreso biométrico activado en este dispositivo'
            : auth.message ?? 'No pudimos activar el ingreso biométrico. Puedes intentarlo más tarde desde tu perfil',
      ),
    ),
  );
}
