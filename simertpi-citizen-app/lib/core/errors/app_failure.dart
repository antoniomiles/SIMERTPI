enum FailureKind {
  offline,
  timeout,
  unavailable,
  unauthorized,
  rejected,
  forbidden,
  conflict,
  invalidRequest,
  unknown,
}

class AppFailure implements Exception {
  const AppFailure(
    this.kind, {
    this.correlationId,
    this.outcomeUnknown = false,
  });
  final FailureKind kind;
  final String? correlationId;
  // A lost response does not prove that a write failed on the server.
  final bool outcomeUnknown;

  String get message => switch (kind) {
    FailureKind.offline => 'Revisa tu conexión e inténtalo nuevamente.',
    FailureKind.timeout when outcomeUnknown =>
      'No pudimos confirmar el resultado. Consulta el estado antes de repetir.',
    FailureKind.timeout => 'La respuesta está tardando. Inténtalo nuevamente.',
    FailureKind.unavailable =>
      'El servicio no está disponible en este momento.',
    FailureKind.unauthorized => 'Necesitas iniciar sesión para continuar.',
    FailureKind.forbidden => 'No tienes acceso a esta operación.',
    FailureKind.conflict => 'La información ya está registrada.',
    FailureKind.invalidRequest => 'Revisa los datos ingresados.',
    FailureKind.rejected => 'No fue posible completar la operación.',
    FailureKind.unknown when outcomeUnknown =>
      'No pudimos confirmar el resultado. Consulta el estado antes de repetir.',
    FailureKind.unknown => 'No pudimos cargar la información.',
  };

  @override
  String toString() => 'AppFailure(${kind.name})';
}
