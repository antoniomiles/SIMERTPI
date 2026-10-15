import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_municipal_app/core/config/municipal_app_config.dart';
import 'package:simertpi_municipal_app/core/errors/municipal_auth_failure.dart';
import 'package:simertpi_municipal_app/core/security/municipal_identity.dart';
import 'package:simertpi_municipal_app/features/auth/domain/municipal_session.dart';
import 'package:simertpi_municipal_app/features/shift/domain/inspector_shift.dart';

void main() {
  group('Municipal environment configuration', () {
    test('does not invent a backend URL when no define is supplied', () {
      const config = MunicipalAppConfig(
        environment: MunicipalEnvironment.development,
        apiBaseUrl: '',
      );

      expect(config.isApiConfigured, isFalse);
      expect(config.apiBaseUri, isNull);
      expect(config.configurationMessage, contains('sin configurar'));
    });

    test(
      'accepts HTTPS URLs without query, fragment, or embedded credentials',
      () {
        const valid = MunicipalAppConfig(
          environment: MunicipalEnvironment.production,
          apiBaseUrl: 'https://backend.example/api/v1',
        );
        const embeddedCredentials = MunicipalAppConfig(
          environment: MunicipalEnvironment.production,
          apiBaseUrl: 'https://user:secret@backend.example/api/v1',
        );
        const queryString = MunicipalAppConfig(
          environment: MunicipalEnvironment.production,
          apiBaseUrl: 'https://backend.example/api/v1?token=secret',
        );

        expect(valid.isApiConfigured, isTrue);
        expect(embeddedCredentials.isApiConfigured, isFalse);
        expect(queryString.isApiConfigured, isFalse);
      },
    );

    test('allows local HTTP only in development', () {
      const local = MunicipalAppConfig(
        environment: MunicipalEnvironment.development,
        apiBaseUrl: 'http://127.0.0.1:8080/api/v1',
      );
      const production = MunicipalAppConfig(
        environment: MunicipalEnvironment.production,
        apiBaseUrl: 'http://127.0.0.1:8080/api/v1',
      );
      const unknown = MunicipalAppConfig(
        environment: MunicipalEnvironment.unknown,
        apiBaseUrl: 'http://127.0.0.1:8080/api/v1',
      );

      expect(local.isApiConfigured, isTrue);
      expect(production.isApiConfigured, isFalse);
      expect(unknown.isApiConfigured, isFalse);
    });
  });

  test('maps only known municipal authorities', () {
    expect(MunicipalRole.fromAuthority('INSPECTOR'), MunicipalRole.inspector);
    expect(MunicipalRole.fromAuthority('CITIZEN'), isNull);
    expect(MunicipalRole.fromAuthority('UNKNOWN'), isNull);
  });

  test('identity enables inspection only for an enabled inspector', () {
    final inspector = MunicipalIdentity(
      id: 'user-id',
      username: 'inspector',
      roles: {MunicipalRole.inspector},
      enabled: true,
    );
    final disabled = MunicipalIdentity(
      id: 'user-id-2',
      username: 'disabled',
      roles: {MunicipalRole.inspector},
      enabled: false,
    );

    expect(inspector.canInspect, isTrue);
    expect(disabled.canInspect, isFalse);
    expect(
      () => inspector.roles.add(MunicipalRole.auditor),
      throwsUnsupportedError,
    );
  });

  test('session state model is separate from current citizen token DTO', () {
    final identity = MunicipalIdentity(
      id: 'user-id',
      username: 'inspector',
      roles: {MunicipalRole.inspector},
      enabled: true,
    );
    final session = MunicipalSession(
      identity: identity,
      authenticatedAt: DateTime.utc(2026, 10, 14),
    );

    expect(session.identity, same(identity));
    expect(MunicipalAuthStatus.signedOut.name, 'signedOut');
  });

  test('classifies authentication and authorization failures safely', () {
    expect(
      MunicipalAuthFailure.fromHttpStatus(401).kind,
      MunicipalAuthFailureKind.unauthenticated,
    );
    expect(
      MunicipalAuthFailure.fromHttpStatus(403).kind,
      MunicipalAuthFailureKind.forbidden,
    );
    expect(
      MunicipalAuthFailure.fromHttpStatus(503).kind,
      MunicipalAuthFailureKind.unavailable,
    );
  });

  test('shift model is conceptual and validates chronology', () {
    final shift = InspectorShift(
      id: 'shift-id',
      inspectorId: 'inspector-id',
      startedAt: DateTime.utc(2026, 10, 14, 8),
    );
    expect(shift.status, InspectorShiftStatus.open);
    expect(
      () => InspectorShift(
        id: 'shift-id',
        inspectorId: 'inspector-id',
        startedAt: DateTime.utc(2026, 10, 14, 10),
        endedAt: DateTime.utc(2026, 10, 14, 9),
      ),
      throwsArgumentError,
    );
  });
}
