import 'package:flutter/material.dart';

import '../core/config/municipal_app_config.dart';
import 'bootstrap/municipal_bootstrap_page.dart';

class SimertpiMunicipalApp extends StatelessWidget {
  const SimertpiMunicipalApp({super.key, this.config});

  final MunicipalAppConfig? config;

  @override
  Widget build(BuildContext context) {
    final appConfig = config ?? MunicipalAppConfig.fromEnvironment();
    return MaterialApp(
      title: 'SIMERTPI Municipal',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0B6B3A),
          primary: const Color(0xFF0B6B3A),
          secondary: const Color(0xFFF2C230),
        ),
        scaffoldBackgroundColor: const Color(0xFFF7F9FC),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF0B6B3A),
          foregroundColor: Colors.white,
          centerTitle: false,
        ),
      ),
      home: MunicipalBootstrapPage(config: appConfig),
    );
  }
}
