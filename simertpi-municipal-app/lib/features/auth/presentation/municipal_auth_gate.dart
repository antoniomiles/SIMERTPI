import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/config/municipal_app_config.dart';
import '../data/municipal_auth_api.dart';
import '../data/municipal_auth_repository_impl.dart';
import '../data/municipal_token_store.dart';
import '../domain/municipal_auth_repository.dart';
import '../domain/municipal_session.dart';
import 'municipal_auth_controller.dart';
import 'municipal_home_page.dart';
import 'municipal_login_page.dart';

MunicipalAuthRepository createMunicipalAuthRepository(
  MunicipalAppConfig config, {
  MunicipalHttpTransport? transport,
  MunicipalTokenStore? tokenStore,
}) => MunicipalAuthRepositoryImpl(
  api: MunicipalAuthApi(
    config: config,
    transport: transport ?? IoMunicipalHttpTransport(),
  ),
  tokenStore: tokenStore ?? SecureMunicipalTokenStore(),
);

class MunicipalAuthGate extends StatefulWidget {
  const MunicipalAuthGate({
    super.key,
    required this.config,
    required this.repository,
  });

  final MunicipalAppConfig config;
  final MunicipalAuthRepository repository;

  @override
  State<MunicipalAuthGate> createState() => _MunicipalAuthGateState();
}

class _MunicipalAuthGateState extends State<MunicipalAuthGate>
    with WidgetsBindingObserver {
  late final MunicipalAuthController _controller;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller = MunicipalAuthController(widget.repository);
    unawaited(_controller.initialize());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_controller.onResume());
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _controller,
    builder: (context, _) {
      if (_controller.status == MunicipalAuthStatus.unconfigured) {
        return const _SessionVerificationPage();
      }
      final session = _controller.session;
      if (_controller.status == MunicipalAuthStatus.authenticated &&
          session != null) {
        return MunicipalHomePage(session: session, controller: _controller);
      }
      return MunicipalLoginPage(controller: _controller, config: widget.config);
    },
  );
}

class _SessionVerificationPage extends StatelessWidget {
  const _SessionVerificationPage();

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Semantics(
        liveRegion: true,
        label: 'Verificando sesión municipal',
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Verificando sesión…'),
          ],
        ),
      ),
    ),
  );
}
