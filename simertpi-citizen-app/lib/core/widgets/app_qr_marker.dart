import 'package:flutter/material.dart';

import '../theme/app_tokens.dart';

// Portable equivalent of the textual ▣ marker in Figma, not a scannable QR.
class AppQrMarker extends StatelessWidget {
  const AppQrMarker({super.key});
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: Container(
      width: 28,
      height: 28,
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.accent, width: 2),
      ),
      child: Center(
        child: Container(width: 10, height: 10, color: AppColors.accent),
      ),
    ),
  );
}
