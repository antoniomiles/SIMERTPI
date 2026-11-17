# Payment recovery — 2026-11-16

## Recovered source

The existing repository is `../SIMERTPI`; its main checkout was left unchanged.
Integration branch: `fix/payment-recovery-20261116`, based on recovered
`origin/develop` at `547dd22e940c7f4032fcca9ee7917b0ecf04326c`.
Backend, citizen Flutter, municipal Flutter and Angular Admin were recovered.
Objects `9b9d7f0` and `d51e434` exist; `a74d4bf` and `6b01a5c` do not resolve.
Fetch failed because outbound GitHub access is blocked. Therefore remote refs
and the current Render deployment have not been verified. Render authentication
returned an accepted-authentication retry response twice without service access.
No push, deployment, Render configuration change or DEV data mutation occurred.

## Consolidated behavior

The DEV profile selects the backend sandbox with deterministic APPROVED outcome.
Existing transactional approval persists payment/attempt and activates the session;
ownership checks, idempotency, locks and unique constraints remain in place.
No schema migration is needed. Production cannot instantiate the sandbox.
Explicit environment overrides still take precedence: before any future DEV
deployment verify profile `dev`, provider `SANDBOX_STUB` and sandbox enabled.

Citizen Flutter retains a recovered approved payment rather than creating another
checkout. HTTP 401/403 is shown as an access error while retaining the durable
intent for recovery. Confirmation still requires backend payment approval and an
ACTIVE session; the existing confirmation navigation remains unchanged.
The historical DEV 403 cause cannot be conclusively established without live
request/deployment evidence. Authorization has not been weakened to bypass it.

## Validation evidence

- Java 21.0.13 and Node 26.13.1 available.
- Maven available inside IntelliJ at
  `C:/Program Files/JetBrains/IntelliJ IDEA 2026.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`.
- Maven online and offline attempts failed before compilation: missing
  `org.springframework.boot:spring-boot-starter-parent:3.5.6`; Maven Central
  connection denied (`Permission denied: getsockopt`). No Java test executed.
- Docker client 29.9.3 available, engine named-pipe access denied.
  PostgreSQL tests could not run. No standalone PostgreSQL tools found.
- Flutter/Dart SDK unavailable; Flutter tests and visual navigation verification
  could not run. No functional test is reported PASS.
- `git diff 547dd22 --check` passed.

Added Java configuration tests check deterministic DEV approval and production
exclusion. Added HTTP/PostgreSQL coverage uses the real simulator and bearer login
to check APPROVED payment, ACTIVE session, owner GET, other-user 403, replay and
one payment/attempt/session. Flutter regressions cover approved recovery, 403
without duplicate checkout, and recovery into the confirmation screen.

Once dependencies and Docker access are restored, run from `simertpi-backend`:

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' '-Dtest=DevPaymentConfigurationTest,DevSimulatedPaymentHttpPostgresIntegrationTest,PaymentCreationHttpPostgresIntegrationTest,PaymentProviderHttpIntegrationTest' test
```

From `simertpi-citizen-app`, after restoring the SDK:

```powershell
flutter pub get
flutter test test/final_pr2_integration_test.dart test/payments_test.dart
```

These commands document pending validation, not completed results. Admin,
municipal, SMTP, application users/roles and production tariffs were not changed.
