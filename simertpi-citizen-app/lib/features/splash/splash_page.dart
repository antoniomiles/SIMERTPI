import 'package:flutter/material.dart';

import '../../app/router/app_router.dart';
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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        Navigator.of(context).pushReplacementNamed(AppRoute.home.path);
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
