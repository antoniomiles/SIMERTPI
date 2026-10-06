import 'package:flutter/material.dart';

// Verified citizen auth Figma nodes 5:591 and 5:607; tokens shared centrally.
abstract final class AppColors {
  static const primary = Color(0xFF05306B);
  static const accent = Color(0xFF05A1D1);
  static const canvas = Color(0xFFF7F9FC);
  static const action = Color(0xFF0D6EFD);
  static const successSurface = Color(0xFFE8F7EE);
  static const warningSurface = Color(0xFFFFF5D9);
  static const criticalSurface = Color(0xFFFAD9DD);
  static const dangerSurface = Color(0xFFFDECEC);
  static const surface = Colors.white;
  static const ink = Color(0xFF141F2E);
  static const muted = Color(0xFF616E7D);
  static const parkingHint = Color(0xFFEBF5FF);
  static const input = Color(0xFFF5F7FA);
  static const outline = Color(0xFFCDD6DB);
  static const skeleton = Color(0xFFE5EBEE);
  static const available = Color(0xFF167344);
  static const endingSoonMarker = Color(0xFFF4B400);
  static const endingSoon = Color(0xFF8A6400);
  static const occupied = Color(0xFFA32835);
  static const danger = Color(0xFFA32835);
}

abstract final class AppSpace {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
  static const page = EdgeInsets.all(md);
}

abstract final class AppSize {
  static const radius = 16.0;
  static const inputRadius = 10.0;
  static const buttonRadius = 12.0;
  static const brandHeader = 58.0;
  static const qrCardMinHeight = 118.0;
  static const touchTarget = 48.0;
  static const contentWidth = 640.0;
  static const mapViewport = 320.0;
}
