# SIMERTPI Municipal

Independent Flutter application for municipal personnel. CP25.2-C adds username/password login, server-verified inspector identity, secure token persistence, session restoration/renewal, logout, and a protected identity home.

Shift operations, biometrics, inspections, violations, evidence, and operational maps remain out of scope. Passwords are sent only to the municipal login endpoint and are never persisted. Access/refresh tokens are stored together using `flutter_secure_storage`; the app verifies `/me` before restoring a protected session and renews rotated tokens through the municipal refresh contract.

## Configuration

Optional compile-time defines:

- `SIMERTPI_ENV`: `development`, `staging`, or `production`.
- `SIMERTPI_API_BASE_URL`: API base URL ending in `/api/v1`. No production URL is built into the app. HTTPS is required except HTTP loopback in development.

The client uses only `/municipal/auth/login`, `/refresh`, `/logout`, and `/me`; it never calls citizen authentication. No credentials or secrets belong in Dart defines or source control. CP25.2-B and its V41 migration must be deployed before this client can authenticate against an environment that does not yet contain the municipal backend/schema.
