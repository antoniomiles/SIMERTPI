# SIMERTPI Ciudadano

Flutter Android/iOS. CP18.1 incorpora sesión Bearer persistente y registro reales;
CP19 incorpora Home y vehículos propios. No incluye estacionamiento, mapa ni pagos.

Requisitos: Flutter 3.47.6 / Dart 3.13.5, Android SDK; macOS/Xcode para iOS.

```sh
flutter pub get
flutter run --dart-define=ENVIRONMENT=dev --dart-define=API_BASE_URL=<base-del-backend-terminada-en-/api/v1>
flutter analyze
flutter test
flutter build apk --debug
```

`ENVIRONMENT` admite dev/qa/uat/prod; HTTPS obligatorio fuera de DEV. La URL
proviene del despliegue real, nunca de un dominio inventado. Sin URL, DEV
muestra login pero las operaciones fallan de forma controlada.

Ver [CP17](docs/checkpoint-17.md), [CP18](docs/checkpoint-18.md) y
[CP18.1](docs/checkpoint-18-1.md). La sesión se conserva en almacenamiento seguro
nativo; nunca se persiste la contraseña. El backend rota access/refresh y revoca
la sesión en logout. Recuperación de contraseña sigue sin contrato.
Ver [CP19](docs/checkpoint-19.md). Sin secretos ni proveedores externos.
