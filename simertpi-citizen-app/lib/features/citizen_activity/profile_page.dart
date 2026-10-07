import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/errors/app_failure.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/operational_ui.dart';
import 'activity_service.dart';

class CitizenProfilePage extends StatefulWidget {
  const CitizenProfilePage({super.key, this.gateway, this.onNotifications});
  final ActivityGateway? gateway;
  final VoidCallback? onNotifications;
  @override
  State<CitizenProfilePage> createState() => _CitizenProfilePageState();
}

class _CitizenProfilePageState extends State<CitizenProfilePage> {
  ActivityGateway? gateway;
  CitizenData? profile;
  String? error;
  bool loading = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (gateway == null) {
      gateway = widget.gateway ?? ActivityService(AppScope.of(context).api);
      load();
    }
  }

  Future<void> load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final data = await gateway!.profile();
      if (mounted) setState(() => profile = data);
    } catch (e) {
      if (mounted) {
        setState(
          () => error = e is AppFailure
              ? e.message
              : const AppFailure(FailureKind.unknown).message,
        );
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Perfil')),
    body: SafeArea(
      child: RefreshIndicator(
        onRefresh: load,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (loading) const Center(child: CircularProgressIndicator()),
            if (error != null) ErrorState(message: error!, onRetry: load),
            if (profile != null && !loading)
              OperationalCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Icon(Icons.person_outline, size: 48),
                    Text(
                      '${profile!['firstName'] ?? ''} ${profile!['lastName'] ?? ''}'
                          .trim(),
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    SummaryRow(
                      'Usuario',
                      profile!['username'] as String? ?? '',
                    ),
                    SummaryRow('Correo', profile!['email'] as String? ?? ''),
                    if ((profile!['phone'] as String?)?.isNotEmpty == true)
                      SummaryRow('Teléfono', profile!['phone'] as String),
                  ],
                ),
              ),
            const SizedBox(height: 16),
            ListTile(
              minTileHeight: 48,
              leading: const Icon(Icons.history),
              title: const Text('Historial'),
              onTap: () => Navigator.pushNamed(context, AppRoute.history.path),
            ),
            ListTile(
              minTileHeight: 48,
              leading: const Icon(Icons.directions_car_outlined),
              title: const Text('Mis vehículos'),
              onTap: () => Navigator.pushNamed(context, AppRoute.vehicles.path),
            ),
            ListTile(
              minTileHeight: 48,
              leading: const Icon(Icons.notifications_outlined),
              title: const Text('Notificaciones'),
              onTap: widget.onNotifications,
            ),
            ListTile(
              minTileHeight: 48,
              leading: const Icon(Icons.tune),
              title: const Text('Preferencias de notificación'),
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) =>
                      NotificationPreferencesPage(gateway: gateway!),
                ),
              ),
            ),
            ListTile(
              minTileHeight: 48,
              leading: const Icon(Icons.logout),
              title: const Text('Cerrar sesión'),
              onTap: () => AppScope.of(context).auth.logout(),
            ),
          ],
        ),
      ),
    ),
  );
}

class NotificationPreferencesPage extends StatefulWidget {
  const NotificationPreferencesPage({super.key, required this.gateway});
  final ActivityGateway gateway;
  @override
  State<NotificationPreferencesPage> createState() =>
      _NotificationPreferencesPageState();
}

class _NotificationPreferencesPageState
    extends State<NotificationPreferencesPage> {
  Map<String, bool>? values;
  bool busy = false;
  String? error;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final data = await widget.gateway.preferences();
      if (mounted) setState(() => values = data);
    } catch (e) {
      if (mounted) {
        setState(
          () => error = e is AppFailure
              ? e.message
              : const AppFailure(FailureKind.unknown).message,
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> save() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.gateway.savePreferences(values!);
      if (mounted) AppSnackbar.show(context, 'Preferencias guardadas.');
    } catch (e) {
      if (mounted) {
        setState(
          () => error = e is AppFailure
              ? e.message
              : const AppFailure(FailureKind.unknown).message,
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Preferencias')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (busy) const Center(child: CircularProgressIndicator()),
          if (error != null)
            ErrorState(message: error!, onRetry: values == null ? load : save),
          if (values != null) ...[
            SwitchListTile(
              title: const Text('Notificaciones en el celular'),
              value: values!['PUSH'] ?? false,
              onChanged: busy
                  ? null
                  : (v) => setState(() => values!['PUSH'] = v),
            ),
            SwitchListTile(
              title: const Text('Correo electrónico'),
              value: values!['EMAIL'] ?? false,
              onChanged: busy
                  ? null
                  : (v) => setState(() => values!['EMAIL'] = v),
            ),
            const InfoCard(
              'Estos canales estarán disponibles próximamente. Tus preferencias quedarán guardadas. Los avisos dentro de la aplicación permanecen activos.',
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: busy ? null : save,
              child: const Text('Guardar preferencias'),
            ),
          ],
        ],
      ),
    ),
  );
}
