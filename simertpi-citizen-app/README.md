# SIMERTPI Ciudadano

Flutter Android/iOS. CP18 incorpora autenticación HTTP Basic y registro reales;
el Home sigue siendo el showcase CP17, sin operaciones de estacionamiento.

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

Ver [CP17](docs/checkpoint-17.md) y [CP18](docs/checkpoint-18.md).
La sesión Basic vive solo en memoria: reiniciar requiere ingresar nuevamente.
No se persisten contraseñas. Recuperación, refresh y tokens no están soportados
por el backend actual. Sin secretos ni proveedores externos.
