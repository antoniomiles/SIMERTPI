import 'package:flutter/foundation.dart';

enum MunicipalRole {
  inspector('INSPECTOR'),
  supervisor('SUPERVISOR'),
  simertpiAdmin('SIMERTPI_ADMIN'),
  itAdmin('IT_ADMIN'),
  auditor('AUDITOR');

  const MunicipalRole(this.authority);
  final String authority;

  static MunicipalRole? fromAuthority(String authority) {
    for (final role in MunicipalRole.values) {
      if (role.authority == authority) return role;
    }
    return null;
  }
}

@immutable
class MunicipalIdentity {
  MunicipalIdentity({
    required this.id,
    required this.username,
    required Set<MunicipalRole> roles,
    required this.enabled,
  }) : roles = Set.unmodifiable(roles) {
    if (id.trim().isEmpty || username.trim().isEmpty) {
      throw ArgumentError('Municipal identity requires an id and username.');
    }
  }

  /// Backend user identifier. No municipal identity API currently exposes it.
  final String id;
  final String username;
  final Set<MunicipalRole> roles;
  final bool enabled;

  bool get canInspect => enabled && roles.contains(MunicipalRole.inspector);
}
