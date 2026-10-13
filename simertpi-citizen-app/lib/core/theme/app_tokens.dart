import 'package:flutter/material.dart';

// Citizen visual identity tokens shared centrally.
abstract final class AppColors {
  /// Approved SIMERTPI green sampled from the citizen welcome action.
  static const brandGreen = Color(0xFF0B6B3A);

  /// Sampled from the dark blue mountain range in simertpinoche.png.
  static const brandNightBlue = Color(0xFF020562);
  static const primary = brandGreen;
  static const accent = Color(0xFFF2C230);
  static const canvas = Color(0xFFF7F9FC);
  static const action = brandGreen;
  static const successSurface = Color(0xFFE8F7EE);
  static const warningSurface = Color(0xFFFFF5D9);
  static const criticalSurface = Color(0xFFFAD9DD);
  static const dangerSurface = Color(0xFFFDECEC);
  static const surface = Colors.white;
  static const ink = Color(0xFF141F2E);
  static const muted = Color(0xFF616E7D);
  static const parkingHint = Color(0xFFEBF5FF);
  static const input = Color(0xFFEEF4FA);
  static const outline = Color(0xFFCDD6DB);
  static const skeleton = Color(0xFFE5EBEE);
  static const available = Color(0xFF167344);
  static const endingSoonMarker = Color(0xFFF4B400);
  static const endingSoon = Color(0xFF8A6400);
  static const occupied = Color(0xFFA32835);
  static const danger = Color(0xFFA32835);
}

@immutable
class AppBrandPalette extends ThemeExtension<AppBrandPalette> {
  const AppBrandPalette({required this.isNight, required this.primary});

  static const day = AppBrandPalette(
    isNight: false,
    primary: AppColors.brandGreen,
  );
  static const night = AppBrandPalette(
    isNight: true,
    primary: AppColors.brandNightBlue,
  );

  final bool isNight;
  final Color primary;

  @override
  AppBrandPalette copyWith({bool? isNight, Color? primary}) => AppBrandPalette(
    isNight: isNight ?? this.isNight,
    primary: primary ?? this.primary,
  );

  @override
  AppBrandPalette lerp(ThemeExtension<AppBrandPalette>? other, double t) {
    if (other is! AppBrandPalette) return this;
    return AppBrandPalette(
      isNight: t < .5 ? isNight : other.isNight,
      primary: Color.lerp(primary, other.primary, t) ?? primary,
    );
  }
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
