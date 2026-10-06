import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/app_tokens.dart';
import 'citizen_navigation.dart';

class AppPage extends StatelessWidget {
  const AppPage({
    super.key,
    required this.title,
    required this.child,
    this.showNavigation = false,
    this.navigationIndex = 0,
  });
  final String title;
  final Widget child;
  final bool showNavigation;
  final int navigationIndex;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title)),
    bottomNavigationBar: showNavigation
        ? CitizenNavigation(selected: navigationIndex)
        : null,
    body: SafeArea(
      top: false,
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: AppSize.contentWidth),
          child: SingleChildScrollView(
            padding: AppSpace.page,
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            child: child,
          ),
        ),
      ),
    ),
  );
}

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Material(
    color: AppColors.surface,
    borderRadius: BorderRadius.circular(AppSize.radius),
    child: Padding(
      padding: const EdgeInsets.all(AppSpace.lg),
      child: SizedBox(width: double.infinity, child: child),
    ),
  );
}

class AppTextField extends StatelessWidget {
  const AppTextField({
    super.key,
    required this.label,
    this.controller,
    this.errorText,
    this.keyboardType = TextInputType.text,
    this.obscureText = false,
    this.validator,
    this.suffixIcon,
    this.autofillHints,
    this.textInputAction,
    this.enabled = true,
    this.onChanged,
    this.inputFormatters,
  });
  final String label;
  final TextEditingController? controller;
  final String? errorText;
  final TextInputType keyboardType;
  final bool obscureText;
  final String? Function(String?)? validator;
  final Widget? suffixIcon;
  final Iterable<String>? autofillHints;
  final TextInputAction? textInputAction;
  final bool enabled;
  final ValueChanged<String>? onChanged;
  final List<TextInputFormatter>? inputFormatters;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      ExcludeSemantics(
        child: Text(label, style: Theme.of(context).textTheme.labelMedium),
      ),
      const SizedBox(height: AppSpace.sm),
      Semantics(
        label: label,
        child: TextFormField(
          controller: controller,
          keyboardType: keyboardType,
          obscureText: obscureText,
          validator: validator,
          enabled: enabled,
          onChanged: onChanged,
          inputFormatters: inputFormatters,
          autofillHints: autofillHints,
          textInputAction: textInputAction,
          autocorrect: !obscureText && autofillHints == null,
          enableSuggestions: !obscureText && autofillHints == null,
          autovalidateMode: AutovalidateMode.onUserInteraction,
          decoration: InputDecoration(
            errorText: errorText,
            suffixIcon: suffixIcon,
          ),
        ),
      ),
    ],
  );
}
