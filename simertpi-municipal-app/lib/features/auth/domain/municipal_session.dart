import 'package:flutter/foundation.dart';

import '../../../core/security/municipal_identity.dart';

/// Domain proposal only. It is not a DTO for the current mobile-auth API.
@immutable
class MunicipalSession {
  const MunicipalSession({
    required this.identity,
    required this.authenticatedAt,
  });

  final MunicipalIdentity identity;
  final DateTime authenticatedAt;
}

enum MunicipalAuthStatus {
  unconfigured,
  signedOut,
  authenticating,
  authenticated,
  failed,
}
