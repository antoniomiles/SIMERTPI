import 'package:flutter/material.dart';

import 'app_buttons.dart';
import '../theme/app_tokens.dart';

abstract final class AppSnackbar {
  static void show(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }
}

abstract final class AppDialog {
  static Future<bool> confirm(
    BuildContext context, {
    required String title,
    required String message,
    required String actionLabel,
  }) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancelar'),
            ),
            DestructiveButton(
              label: actionLabel,
              onPressed: () => Navigator.pop(context, true),
            ),
          ],
        ),
      ) ??
      false;
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.message,
    this.actionLabel,
    this.onAction,
  });
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => _StateContent(
    icon: Icons.inbox_outlined,
    title: 'Nada por aquí todavía',
    message: message,
    action: actionLabel != null
        ? SecondaryButton(label: actionLabel!, onPressed: onAction)
        : null,
  );
}

class ErrorState extends StatelessWidget {
  const ErrorState({super.key, required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => _StateContent(
    icon: Icons.error_outline,
    title: 'No pudimos continuar',
    message: message,
    action: SecondaryButton(label: 'Reintentar', onPressed: onRetry),
  );
}

class _StateContent extends StatelessWidget {
  const _StateContent({
    required this.icon,
    required this.title,
    required this.message,
    this.action,
  });
  final IconData icon;
  final String title;
  final String message;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 32, color: AppColors.muted),
        const SizedBox(height: AppSpace.md),
        Text(
          title,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: AppSpace.sm),
        Text(message, textAlign: TextAlign.center),
        if (action != null) ...[const SizedBox(height: AppSpace.lg), action!],
      ],
    ),
  );
}
