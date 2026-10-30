# First Superadministrator bootstrap in DEV

This procedure is deliberately manual and is not run by application startup or Flyway. It creates only a new, pending `IT_ADMIN`; it never promotes an existing citizen or changes an existing account.

## Identity and approval

For each approved operation, the operator supplies the intended email using `SIMERTPI_BOOTSTRAP_SUPERADMIN_EMAIL` to the local bootstrap process (the PowerShell wrapper accepts `-ExpectedSuperadminEmail`). The CLI requires the fixed username `superadmin.simertpi.dev`, validates that the entered email matches the configured email, and requires the exact typed confirmation `CONFIRM DEV SUPERADMIN`. The configured email is not stored in code. The wrapper restores the prior process environment when it exits.

The service serializes bootstrap, rejects an existing `IT_ADMIN`, username, or email, creates the user disabled with no password hash and only `IT_ADMIN`, and generates a one-use activation token stored as a hash with a 24-hour expiry. Do not retry an ambiguous operation; inspect the account and audit status first. No account is promoted.

## SMTP and Outlook OAuth status

V53 implements Microsoft Outlook OAuth2/XOAUTH2 for the fixed `smtp-mail.outlook.com:587` endpoint with mandatory STARTTLS and certificate validation. It stores OAuth tokens encrypted at rest, refreshes them server-side, and does not expose them to Angular. The legacy V52 username/password SMTP path remains available for supported providers. Never use an Outlook account password or disable TLS/certificate checks.

The Outlook connection remains inactive until the operator registers a Microsoft application that supports personal Microsoft accounts, configures the exact DEV callback, and the owner of `simertpi@outlook.com` grants the required delegated consent. No Microsoft registration, consent, credentials, or live email send is included in this repository. See [V53-OUTLOOK-OAUTH-OPERATIONS.md](V53-OUTLOOK-OAUTH-OPERATIONS.md) for the configuration and verification procedure. Do not run bootstrap until the Outlook connection is authorized and its SMTP test succeeds.

## Render and secrets

Before any later DEV operation, the operator must verify directly in Render Dashboard that `SIMERTPI_SMTP_ENCRYPTION_KEY` is present and stable. Never replace a key used to encrypt credentials without a tested migration/rotation procedure. The bootstrap process also needs the same key and a verified HTTPS portal origin. The local command must use a verified DEV database host, PostgreSQL 16, database `simertpi`, CA validation and `sslmode=verify-full`. Do not put credentials in command-line arguments, files, shell history, logs, Git, or chat.

## Future operator sequence

1. Review V53 and configure the Microsoft application registration, callback, and delegated consent as documented in [V53-OUTLOOK-OAUTH-OPERATIONS.md](V53-OUTLOOK-OAUTH-OPERATIONS.md).
2. Verify Render's encryption key and public portal origin without exposing their values.
3. Build the bootstrap CLI locally and run its preflight.
4. Obtain explicit approval identifying the account holder and operator.
5. Configure and verify the SMTP candidate through the supported OAuth flow; send only a specifically authorized test message if required.
6. Run the wrapper interactively with `-Superadministrator -ExpectedSuperadminEmail antoxavie1992@gmail.com`, after independently confirming the DEV database target. The email is a non-secret argument; all credentials must use a hidden/private input mechanism.
7. Confirm the provider's acceptance and audit status without displaying the activation token. Provider acceptance is not proof of mailbox delivery.
8. The mailbox owner opens the HTTPS invitation and establishes their own password.

No SMTP settings, user, invitation, or email are created by this documentation or by a migration. Do not use this procedure against production.
