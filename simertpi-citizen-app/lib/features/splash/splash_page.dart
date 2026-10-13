import 'dart:async';

import 'package:flutter/material.dart';

import '../../app/router/app_router.dart';
import '../../app/bootstrap/bootstrap.dart';
import '../../core/theme/app_tokens.dart';

class SplashPage extends StatefulWidget {
  const SplashPage({super.key});
  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage>
    with SingleTickerProviderStateMixin {
  late final AnimationController _transition;

  @override
  void initState() {
    super.initState();
    _transition = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 620),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final auth = AppScope.of(context).auth;
      await Future.wait([auth.restore(), _transition.forward()]);
      if (mounted) {
        Navigator.of(context).pushNamedAndRemoveUntil(
          auth.isAuthenticated ? AppRoute.home.path : AppRoute.welcome.path,
          (_) => false,
        );
      }
    });
  }

  @override
  void dispose() {
    _transition.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final brand =
        Theme.of(context).extension<AppBrandPalette>() ?? AppBrandPalette.day;
    final night = brand.isNight;
    return Scaffold(
      backgroundColor: night ? AppColors.brandNightBlue : Colors.white,
      body: SafeArea(
        child: Center(
          child: FadeTransition(
            opacity: CurvedAnimation(
              parent: _transition,
              curve: Curves.easeOut,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Image.asset(
                night
                    ? 'assets/branding/SIMERTPI_logotipo_blanco.png'
                    : 'assets/branding/SIMERTPI_logotipo_negro.png',
                height: 86,
                fit: BoxFit.contain,
                semanticLabel: 'SIMERTPI, Tu estacionamiento municipal',
              ),
            ),
          ),
        ),
      ),
    );
  }
}
