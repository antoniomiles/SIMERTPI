import 'package:flutter/material.dart';

import '../../app/router/app_router.dart';
import '../../app/bootstrap/bootstrap.dart';
import '../../core/theme/app_tokens.dart';

class SplashPage extends StatefulWidget {
  const SplashPage({super.key});
  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final auth = AppScope.of(context).auth;
      await auth.restore();
      if (mounted) {
        Navigator.of(context).pushNamedAndRemoveUntil(
          auth.isAuthenticated ? AppRoute.home.path : AppRoute.login.path,
          (_) => false,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: Padding(
          padding: AppSpace.page,
          child: Text(
            'SIMERTPI',
            semanticsLabel: 'SIMERTPI, iniciando aplicación',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
        ),
      ),
    ),
  );
}
