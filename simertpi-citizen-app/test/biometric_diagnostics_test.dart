import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:simertpi_citizen_app/core/errors/app_failure.dart';
import 'package:simertpi_citizen_app/core/logging/app_log.dart';

void main() {
  test(
    'network cause is available without copying arbitrary exception text',
    () {
      final line = AppLog.biometricLine(
        'BIO_REGISTER_CHALLENGE_FAILED',
        error: const AppFailure(
          FailureKind.unknown,
          code: 'NETWORK_UNREACHABLE',
          diagnosticExceptionClass: 'SocketException',
        ),
      );
      expect(line, contains('sourceExceptionClass=SocketException'));
      expect(line, contains('errorCode=NETWORK_UNREACHABLE'));
      final rejected = AppLog.biometricLine(
        'BIO_REGISTER_CHALLENGE_FAILED',
        error: const AppFailure(
          FailureKind.unknown,
          diagnosticExceptionClass: 'SECRET_EXCEPTION',
        ),
      );
      expect(rejected, isNot(contains('SECRET_EXCEPTION')));
    },
  );
  test(
    'diagnostics retain HTTP metadata without arbitrary sensitive strings',
    () {
      final line = AppLog.biometricLine(
        'BIO_REGISTER_CHALLENGE_FAILED',
        error: const AppFailure(
          FailureKind.forbidden,
          httpStatus: 403,
          correlationId: '0123456789abcdef0123456789abcdef',
          messageOverride: 'SECRET_BODY',
        ),
        path: '/api/v1/auth/device/register/challenge',
        source: 'HTTP',
      );
      expect(line, contains('httpStatus=403'));
      expect(line, contains('errorType=forbidden'));
      expect(line, contains('correlationId=0123456789abcdef0123456789abcdef'));
      expect(line, isNot(contains('SECRET_BODY')));
      final blocked = AppLog.biometricLine(
        'BIO_ANDROID_PROMPT_FAILED',
        error: PlatformException(
          code: 'SECRET_NATIVE',
          message: 'SECRET_MESSAGE',
          details: {
            'exceptionClass': 'SECRET_CLASS',
            'nativeCode': 'SECRET_CODE',
            'payload': 'SECRET_PAYLOAD',
            'owner': 'SECRET_OWNER',
          },
        ),
        path: '/unsafe?token=SECRET_TOKEN',
        correlationId: 'R' * 43,
      );
      expect(blocked, isNot(contains('SECRET')));
      expect(blocked, isNot(contains('R' * 43)));
      expect(blocked, contains('nativeCode=OTHER'));
      expect(AppLog.biometricLine('SECRET_STAGE'), isNull);
    },
  );
  test(
    'native details accept only technical allowlisted codes and classes',
    () {
      final line = AppLog.biometricLine(
        'BIO_KEYSTORE_PREPARE_FAILED',
        error: PlatformException(
          code: 'UNAVAILABLE',
          details: {
            'nativeCode': 'KEY_GENERATION',
            'exceptionClass': 'ProviderException',
            'androidCode': 7,
            'privateKey': 'SECRET',
          },
        ),
      );
      expect(line, contains('nativeDetail=KEY_GENERATION'));
      expect(line, contains('nativeExceptionClass=ProviderException'));
      expect(line, contains('androidCode=7'));
      expect(line, isNot(contains('SECRET')));
    },
  );
  test(
    'stage wrapper calls action once and preserves exact exception',
    () async {
      const logger = AppLog();
      final failure = StateError('SECRET');
      var calls = 0;
      expect(
        await logger.biometricStep('BIO_KEYSTORE_PREPARE', () async {
          calls++;
          return 42;
        }),
        42,
      );
      expect(calls, 1);
      await expectLater(
        logger.biometricStep<void>('BIO_REGISTER_VERIFY', () async {
          calls++;
          throw failure;
        }),
        throwsA(same(failure)),
      );
      expect(calls, 2);
    },
  );
}
