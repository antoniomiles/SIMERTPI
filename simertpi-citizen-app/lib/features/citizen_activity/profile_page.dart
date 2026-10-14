import 'package:flutter/material.dart';

import '../../app/bootstrap/bootstrap.dart';
import '../../app/router/app_router.dart';
import '../../core/errors/app_failure.dart';
import '../../core/widgets/app_feedback.dart';
import '../../core/widgets/operational_ui.dart';
import 'activity_service.dart';
import '../../core/push/push_lifecycle.dart';
import '../auth/presentation/security_settings.dart';

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
            const SizedBox(height: 16),
            const SecuritySettings(),
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
    extends State<NotificationPreferencesPage>
    with WidgetsBindingObserver {
  Map<String, bool>? values;
  bool busy = false;
  PushPermissionState permissionState = PushPermissionState.notDetermined;
  String? error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.pushLifecycle?.registrationStatus.addListener(_registrationChanged);
    load();
  }

  void _registrationChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.pushLifecycle?.registrationStatus.removeListener(
      _registrationChanged,
    );
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      load();
    }
  }

  Future<void> load() async {
    if (busy) {
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final state = widget.pushLifecycle == null
          ? PushPermissionState.unavailable
          : await widget.pushLifecycle!.refreshPermission();
      final data = await widget.gateway.preferences();
      if (mounted) {
        setState(() {
          values = data;
          permissionState = state;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'No pudimos comprobar las notificaciones. Intenta nuevamente.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  Future<void> togglePush(bool enabled) async {
    final push = widget.pushLifecycle;
    if (push == null || busy) {
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      // Read the current backend map before any write so the other channels
      // remain unchanged if the API's preference contract evolves.
      final before = await widget.gateway.preferences();
      if (enabled) {
        final state = await push.requestPermission();
        if (mounted) {
          setState(() => permissionState = state);
        }
        if (state == PushPermissionState.systemSettingsRequired) {
          await push.openSystemSettings();
          return;
        }
        if (state != PushPermissionState.granted) return;
      } else {
        await push.disablePushVoluntarily();
      }
      final next = await widget.gateway.preferences();
      for (final entry in before.entries.where(
        (entry) => entry.key != 'PUSH',
      )) {
        if (next[entry.key] != entry.value) {
          throw StateError('Changing PUSH also changed another preference');
        }
      }
      if (mounted) {
        setState(() => values = next);
      }
      if (enabled &&
          push.registrationState != PushRegistrationState.registered &&
          mounted) {
        setState(
          () => error = 'La activación está pendiente. Intenta nuevamente.',
        );
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => error = 'No pudimos guardar o activar las notificaciones. Intenta nuevamente.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => busy = false);
      }
    }
  }

  Future<void> toggleEmail(bool enabled) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final current = await widget.gateway.preferences();
      await widget.gateway.savePreferences({...current, 'EMAIL': enabled});
      final confirmed = await widget.gateway.preferences();
      if (confirmed['EMAIL'] != enabled) {
        throw StateError('Email preference was not confirmed by the server');
      }
      for (final entry in current.entries.where(
        (entry) => entry.key != 'EMAIL',
      )) {
        if (confirmed[entry.key] != entry.value) {
          throw StateError('Changing EMAIL also changed another preference');
        }
      }
      if (mounted) setState(() => values = confirmed);
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'No pudimos guardar las preferencias. Intenta nuevamente.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  bool get enabled =>
      values?['PUSH'] == true &&
      permissionState == PushPermissionState.granted &&
      widget.pushLifecycle?.registrationState ==
          PushRegistrationState.registered;
  String get status {
    if (widget.pushLifecycle == null) {
      return 'Firebase no configurado en esta compilación.';
    }
    if (permissionState == PushPermissionState.denied ||
        permissionState == PushPermissionState.systemSettingsRequired) {
      return 'Android no permite los avisos. Habilítalos en Ajustes.';
    }
    if (enabled) {
      return 'Notificaciones permitidas en este dispositivo.';
    }
    if (values?['PUSH'] == true) {
      return 'La activación está pendiente. Intenta nuevamente.';
    }
    return 'Notificaciones desactivadas.';
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Preferencias')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (busy) const Center(child: CircularProgressIndicator()),
          if (values != null)
            SwitchListTile(
              title: const Text('Notificaciones PUSH'),
              value: enabled,
              onChanged: busy || widget.pushLifecycle == null
                  ? null
                  : togglePush,
            ),
          if (values != null)
            SwitchListTile(
              title: const Text('Notificaciones por correo electrónico'),
              value: values!['EMAIL'] ?? false,
              onChanged: busy ? null : toggleEmail,
            ),
          Text(status),
          if (permissionState == PushPermissionState.denied ||
              permissionState == PushPermissionState.systemSettingsRequired)
            TextButton(
              onPressed: busy || widget.pushLifecycle == null
                  ? null
                  : () => widget.pushLifecycle!.openSystemSettings(),
              child: const Text('Abrir Ajustes'),
            ),
          if (error != null) ErrorState(message: error!, onRetry: load),
          const InfoCard(
            'Los avisos dentro de la aplicación permanecen disponibles aunque desactives las notificaciones del celular.',
          ),
        ],
      ),
    ),
  );
}
