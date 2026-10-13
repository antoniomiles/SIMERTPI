import 'package:flutter/material.dart';

import 'app_tokens.dart';

// Citizen text styles inherit the bundled Poppins family from AppTheme.
abstract final class AppTypography {
  static const brand = TextStyle(fontSize: 23, fontWeight: FontWeight.w600);
  static const section = TextStyle(
    fontSize: 19,
    height: 1.4,
    fontWeight: FontWeight.w500,
    color: AppColors.ink,
  );
  static const plate = TextStyle(
    fontSize: 17,
    height: 1.4,
    fontWeight: FontWeight.w600,
    color: AppColors.ink,
  );
  static const caption = TextStyle(
    fontSize: 13,
    height: 1.5,
    color: AppColors.muted,
  );
}
