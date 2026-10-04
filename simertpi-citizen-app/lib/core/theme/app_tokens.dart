import 'package:flutter/material.dart';

// PENDIENTE VALIDACIÓN VISUAL FIGMA. Provisional, replace centrally.
abstract final class AppColors {
  static const primary = Color(0xFF24465B);
  static const accent = Color(0xFF226B62);
  static const canvas = Color(0xFFF7F9FA);
  static const surface = Colors.white;
  static const ink = Color(0xFF182C38);
  static const muted = Color(0xFF52636D);
  static const outline = Color(0xFFCDD6DB);
  static const skeleton = Color(0xFFE5EBEE);
  static const danger = Color(0xFFA32835);
}

abstract final class AppSpace {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
  static const page = EdgeInsets.all(lg);
}

abstract final class AppSize {
  static const radius = 16.0;
  static const inputRadius = 12.0;
  static const touchTarget = 48.0;
  static const contentWidth = 640.0;
}
