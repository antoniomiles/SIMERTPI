import 'package:flutter/material.dart';

import '../../../core/theme/app_tokens.dart';

/// Figma citizen auth frame: branding header, 24px gutter, scrolling content.
class AuthPage extends StatelessWidget {
  const AuthPage({
    super.key,
    required this.title,
    required this.subtitle,
    required this.child,
    this.canGoBack = false,
  });
  final String title, subtitle;
  final Widget child;
  final bool canGoBack;
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        automaticallyImplyLeading: canGoBack,
        backgroundColor: Colors.white,
        foregroundColor: AppColors.ink,
        toolbarHeight: 68,
      ),
      body: SafeArea(
        top: false,
        child: ColoredBox(
          color: Colors.white,
          child: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: AppSize.contentWidth),
              child: SingleChildScrollView(
                padding: AppSpace.page,
                keyboardDismissBehavior:
                    ScrollViewKeyboardDismissBehavior.onDrag,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Center(
                      child: Image.asset(
                        'assets/branding/SIMERTPI_logotipo_negro.png',
                        key: const ValueKey('auth-brand-logo'),
                        width: 156,
                        height: 52,
                        fit: BoxFit.contain,
                        semanticLabel: 'SIMERTPI, Tu estacionamiento municipal',
                      ),
                    ),
                    const SizedBox(height: AppSpace.md),
                    Semantics(
                      header: true,
                      child: Text(
                        title,
                        style: Theme.of(context).textTheme.headlineMedium,
                      ),
                    ),
                    const SizedBox(height: AppSpace.sm),
                    Text(
                      subtitle,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    const SizedBox(height: AppSpace.xl),
                    child,
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

class AuthMessage extends StatelessWidget {
  const AuthMessage(this.message, {super.key});
  final String message;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Padding(
      padding: const EdgeInsets.only(bottom: AppSpace.md),
      child: Text(
        message,
        style: Theme.of(context).textTheme.bodyMedium
            ?.copyWith(color: AppColors.danger),
      ),
    ),
  );
}
