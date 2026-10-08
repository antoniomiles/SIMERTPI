import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/errors/app_failure.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/operational_ui.dart';
import 'activity_service.dart';
import '../../core/push/push_lifecycle.dart';

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
                  builder: (_) => NotificationPreferencesPage(
                    gateway: gateway!,
                    pushLifecycle: AppScope.of(context).pushLifecycle,
                  ),
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
  const NotificationPreferencesPage({
    super.key,
    required this.gateway,
    this.pushLifecycle,
  });
  final ActivityGateway gateway;
  final PushDeviceLifecycle? pushLifecycle;
  @override
  State<NotificationPreferencesPage> createState() =>
      _NotificationPreferencesPageState();
}

class _NotificationPreferencesPageState
    extends State<NotificationPreferencesPage> {
  Map<String, bool>? values;
  bool busy = false;
  bool requestingPermission = false;
  PushPermissionState permissionState = PushPermissionState.notDetermined;
  String? error;
  @override
  void initState() {
    super.initState();
    load();
    refreshPermission();
  }

  Future<void> refreshPermission() async {
    final push = widget.pushLifecycle;
    if (push == null) {
      if (mounted) {
        setState(() => permissionState = PushPermissionState.unavailable);
      }
      return;
    }
    final state = await push.refreshPermission();
    if (mounted) setState(() => permissionState = state);
  }

  Future<void> enablePushPermission() async {
    final push = widget.pushLifecycle;
    if (push == null) return;
    setState(() => requestingPermission = true);
    final state = permissionState == PushPermissionState.systemSettingsRequired
        ? await push.openSystemSettings().then((_) => push.refreshPermission())
        : await push.requestPermission();
    if (mounted) {
      setState(() {
        permissionState = state;
        requestingPermission = false;
      });
      if (state == PushPermissionState.granted) {
        AppSnackbar.show(
          context,
          'Notificaciones activadas en este dispositivo.',
        );
      }
    }
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
              'SIMERTPI puede avisarte cuando tu estacionamiento esté próximo a finalizar. Los avisos dentro de la aplicación permanecen disponibles aunque rechaces este permiso.',
            ),
            Text(switch (permissionState) {
              PushPermissionState.granted =>
                'Permiso del dispositivo activado.',
              PushPermissionState.systemSettingsRequired => 'El permiso está desactivado. Puedes habilitarlo desde los ajustes del dispositivo.',
              PushPermissionState.denied =>
                'El permiso del dispositivo está desactivado.',
              PushPermissionState.notDetermined =>
                'El permiso del dispositivo aún no se ha solicitado.',
              PushPermissionState.unavailable => 'Las notificaciones del dispositivo no están configuradas en esta versión.',
            }, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed:
                  requestingPermission ||
                      permissionState == PushPermissionState.unavailable
                  ? null
                  : enablePushPermission,
              icon: const Icon(Icons.notifications_active_outlined),
              label: Text(
                permissionState == PushPermissionState.systemSettingsRequired
                    ? 'Abrir ajustes del dispositivo'
                    : 'Activar notificaciones en este dispositivo',
              ),
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
