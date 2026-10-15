enum MunicipalAuthFailureKind {
  unauthenticated,
  forbidden,
  unavailable,
  invalidResponse,
  secureStorage,
}

/// Safe classification of HTTP outcomes; server bodies are never shown.
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
      'Usuario o contraseña incorrectos, o sesión vencida.',
    MunicipalAuthFailureKind.forbidden =>
      'La cuenta no tiene autorización para esta aplicación.',
    MunicipalAuthFailureKind.unavailable => 'No pudimos conectar con el servicio. Revisa tu conexión e inténtalo de nuevo.',
    MunicipalAuthFailureKind.invalidResponse =>
      'El servicio respondió de forma inesperada. Inténtalo de nuevo.',
    MunicipalAuthFailureKind.secureStorage =>
      'No se pudo proteger la sesión en este dispositivo. Inténtalo de nuevo.',
  };
}

class MunicipalAuthException implements Exception {
  const MunicipalAuthException(this.failure);

  final MunicipalAuthFailure failure;

  String get userMessage => failure.userMessage;
}
