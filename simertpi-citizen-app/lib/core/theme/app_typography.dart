import 'package:flutter/material.dart';

import 'app_tokens.dart';

// Sizes verified in the citizen Home frame (5:627) during CP19.
// Inter remains pending an official, licensed asset; use the platform fallback.
abstract final class AppTypography {
  static const brand = TextStyle(fontSize: 23, fontWeight: FontWeight.w700);
  static const section = TextStyle(
    fontSize: 19,
    height: 1.4,
    fontWeight: FontWeight.w700,
    color: AppColors.ink,
  );
  static const plate = TextStyle(
    fontSize: 17,
    height: 1.4,
    fontWeight: FontWeight.w700,
    color: AppColors.ink,
  );
  static const caption = TextStyle(
    fontSize: 13,
    height: 1.5,
    color: AppColors.muted,
  );
}
