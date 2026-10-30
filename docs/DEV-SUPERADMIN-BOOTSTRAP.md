# First Superadministrator bootstrap in DEV

This procedure is deliberately manual and is not run by application startup or Flyway. It creates only a new, pending `IT_ADMIN`; it never promotes an existing citizen or changes an existing account.

## Identity and approval

For each approved operation, the operator supplies the intended email using `SIMERTPI_BOOTSTRAP_SUPERADMIN_EMAIL` to the local bootstrap process (the PowerShell wrapper accepts `-ExpectedSuperadminEmail`). The CLI requires the fixed username `superadmin.simertpi.dev`, validates that the entered email matches the configured email, and requires the exact typed confirmation `CONFIRM DEV SUPERADMIN`. The destination is fixed by the approved DEV operation to `simertpi@outlook.com`; the operator cannot select another address. The wrapper restores the prior process environment when it exits.

The service serializes bootstrap, rejects an existing `IT_ADMIN`, username, or email, creates the user disabled with no password hash and only `IT_ADMIN`, and generates a one-use activation token stored as a hash with a 24-hour expiry. Do not retry an ambiguous operation; inspect the account and audit status first. No account is promoted.

## SMTP and Outlook OAuth status

V53 implements Microsoft Outlook OAuth2/XOAUTH2 for the fixed `smtp-mail.outlook.com:587` endpoint with mandatory STARTTLS and certificate validation. It stores OAuth tokens encrypted at rest, refreshes them server-side, and does not expose them to Angular. The legacy V52 username/password SMTP path remains available for supported providers. Never use an Outlook account password or disable TLS/certificate checks.

The Outlook connection remains inactive until the operator registers a Microsoft application that supports personal Microsoft accounts, configures the exact DEV callback, and the owner of `simertpi@outlook.com` grants the required delegated consent. No Microsoft registration or consent is required for the Gmail bootstrap. See [V53-OUTLOOK-OAUTH-OPERATIONS.md](V53-OUTLOOK-OAUTH-OPERATIONS.md) for later Outlook configuration. For the approved V54 initial activation, Gmail SMTP is used instead of Microsoft OAuth. Microsoft OAuth remains optional and unchanged for later use.

For a future custom SMTP provider, its exact hostname must also be included in the backend `SIMERTPI_SMTP_ALLOWED_HOSTS` allowlist before it can be saved or tested from Angular. This remains an infrastructure security control against SSRF; adding an approved host does not require a code change. The default allowlist contains Gmail only.

## Render and secrets

Before any later DEV operation, the operator must verify directly in Render Dashboard that `SIMERTPI_SMTP_ENCRYPTION_KEY` is present and stable. Never replace a key used to encrypt credentials without a tested migration/rotation procedure. The bootstrap process also needs the exact same key and the fixed DEV portal origin `https://simertpi-admin-web-dev.onrender.com`. The interactive wrapper requests the key via hidden input if it is not already present in the process; the operator must verify the key in Render before using `-EncryptionKeyVerifiedFromRender`. The Gmail app password is requested separately by the Java console via non-echoing input. The local command must use a verified DEV database host, PostgreSQL 16, database `simertpi`, CA validation and `sslmode=verify-full`. Do not put credentials in command-line arguments, files, shell history, logs, Git, or chat.

## Future operator sequence

1. Confirm the deployed V53 backend and portal SHA and the current DEV database target. No Microsoft application is needed for the temporary Gmail activation.
2. Verify the existing encryption key in Render Dashboard; do not rotate or overwrite it. Confirm the fixed DEV portal URL.
3. Build the bootstrap CLI locally and run its preflight.
4. Obtain explicit approval identifying the account holder and operator.
5. For the first IT_ADMIN, run the bootstrap wrapper with `-Superadministrator -ExpectedSuperadminEmail simertpi@outlook.com -EncryptionKeyVerifiedFromRender`. It verifies Gmail `smtp.gmail.com:587` using mandatory STARTTLS and the hidden app-password input, and creates/sends a single pending invitation only after SMTP authentication succeeds.
6. Confirm the wrapper result and database/audit status without displaying the activation token. Provider acceptance is not proof of mailbox delivery.
7. The mailbox owner opens the HTTPS invitation, establishes their own password, and confirms successful login. The operator must not activate the account on their behalf.


No SMTP settings, user, invitation, or email are created by this documentation or by a migration. Do not use this procedure against production.
