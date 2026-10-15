# SIMERTPI Municipal

Independent Flutter application for municipal personnel. This CP25.1 baseline intentionally provides only app configuration, domain types/ports, and an informative bootstrap screen.

No login, token storage, shift operations, inspection workflows, or backend calls are implemented. Do not treat the conceptual session/shift models as API payloads. The integration gaps and verified backend contracts are documented in `../docs/CP25.1_MUNICIPAL_ARCHITECTURE.md`.

## Configuration

Optional compile-time defines:

- `SIMERTPI_ENV`: `development`, `staging`, or `production`.
- `SIMERTPI_API_BASE_URL`: full API base URL. No endpoint is built into the app. A valid URL only reports configuration; it does not imply a connection or supported municipal login.

No secret belongs in Dart defines or source control.
