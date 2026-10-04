import 'package:flutter/material.dart';

import '../errors/app_failure.dart';
import '../theme/app_tokens.dart';
import 'app_feedback.dart';

class PrimaryButton extends StatelessWidget {
  const PrimaryButton({super.key, required this.label, this.onPressed});
  final String label;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) =>
      FilledButton(onPressed: onPressed, child: Text(label));
}

class SecondaryButton extends StatelessWidget {
  const SecondaryButton({
    super.key,
    required this.label,
    this.onPressed,
    this.filled = false,
  });
  final String label;
  final VoidCallback? onPressed;
  final bool filled;
  @override
  Widget build(BuildContext context) => OutlinedButton(
    style: filled
        ? OutlinedButton.styleFrom(
            backgroundColor: AppColors.input,
            foregroundColor: AppColors.primary,
            side: BorderSide.none,
            minimumSize: const Size(AppSize.touchTarget, 54),
          )
        : null,
    onPressed: onPressed,
    child: Text(label),
  );
}

class DestructiveButton extends StatelessWidget {
  const DestructiveButton({super.key, required this.label, this.onPressed});
  final String label;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => OutlinedButton(
    style: OutlinedButton.styleFrom(foregroundColor: AppColors.danger),
    onPressed: onPressed,
    child: Wrap(
      spacing: AppSpace.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [const Icon(Icons.warning_amber_rounded), Text(label)],
    ),
  );
}

class AsyncButton extends StatefulWidget {
  const AsyncButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.processingLabel = 'Procesando…',
  });
  final String label;
  final String processingLabel;
  final Future<void> Function() onPressed;
  @override
  State<AsyncButton> createState() => _AsyncButtonState();
}

class _AsyncButtonState extends State<AsyncButton> {
  bool _processing = false;
  Future<void> _run() async {
    if (_processing) return;
    setState(() => _processing = true);
    try {
      await widget.onPressed();
    } catch (error) {
      if (mounted) {
        AppSnackbar.show(
          context,
          error is AppFailure
              ? error.message
              : const AppFailure(FailureKind.unknown).message,
        );
      }
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: _processing,
    child: FilledButton(
      onPressed: _processing ? null : _run,
      child: Wrap(
        spacing: AppSpace.sm,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          if (_processing)
            const SizedBox.square(
              dimension: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          Text(_processing ? widget.processingLabel : widget.label),
        ],
      ),
    ),
  );
}
