import 'package:flutter/foundation.dart';

import '../../../core/security/municipal_identity.dart';

/// Authenticated municipal identity exposed to presentation and domain code.
@immutable
class MunicipalSession {
  const MunicipalSession({
    required this.identity,
    required this.authenticatedAt,
    required this.accessExpiresAt,
  });

  final MunicipalIdentity identity;
  final DateTime authenticatedAt;
  final DateTime accessExpiresAt;
}

enum MunicipalAuthStatus {
  unconfigured,
  signedOut,
  authenticating,
  authenticated,
  failed,
}
