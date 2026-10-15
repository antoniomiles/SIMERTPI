import 'package:flutter/material.dart';

import '../core/config/municipal_app_config.dart';
import '../features/auth/data/municipal_auth_api.dart';
import '../features/auth/data/municipal_token_store.dart';
import '../features/auth/domain/municipal_auth_repository.dart';
import '../features/auth/presentation/municipal_auth_gate.dart';

class SimertpiMunicipalApp extends StatelessWidget {
  const SimertpiMunicipalApp({
    super.key,
    this.config,
    this.authRepository,
    this.tokenStore,
    this.transport,
  });

  final MunicipalAppConfig? config;
  final MunicipalAuthRepository? authRepository;
  final MunicipalTokenStore? tokenStore;
  final MunicipalHttpTransport? transport;

  @override
  Widget build(BuildContext context) {
    final appConfig = config ?? MunicipalAppConfig.fromEnvironment();
    final repository =
        authRepository ??
        createMunicipalAuthRepository(
          appConfig,
          tokenStore: tokenStore,
          transport: transport,
        );
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
      home: MunicipalAuthGate(config: appConfig, repository: repository),
    );
  }
}
