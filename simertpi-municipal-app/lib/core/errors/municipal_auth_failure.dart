enum MunicipalAuthFailureKind { unauthenticated, forbidden, unavailable }

/// Safe classification of HTTP outcomes; it does not parse server error bodies.
class MunicipalAuthFailure {
  const MunicipalAuthFailure(this.kind);

  final MunicipalAuthFailureKind kind;

  factory MunicipalAuthFailure.fromHttpStatus(int statusCode) {
    if (statusCode == 401) {
      return const MunicipalAuthFailure(
        MunicipalAuthFailureKind.unauthenticated,
      );
    }
    if (statusCode == 403) {
      return const MunicipalAuthFailure(MunicipalAuthFailureKind.forbidden);
    }
    return const MunicipalAuthFailure(MunicipalAuthFailureKind.unavailable);
  }

  String get userMessage => switch (kind) {
    MunicipalAuthFailureKind.unauthenticated =>
      'No se pudo validar la identidad municipal.',
    MunicipalAuthFailureKind.forbidden =>
      'La cuenta no tiene autorización para esta función.',
    MunicipalAuthFailureKind.unavailable =>
      'No fue posible completar la solicitud. Inténtalo más tarde.',
  };
}
