# SIMERTPI Ciudadano

Flutter Android/iOS. CP18.1 incorpora sesión Bearer persistente y registro reales;
CP19 incorpora Home y vehículos propios; CP20 descubrimiento y QR; CP21 prepara solicitudes PENDING_PAYMENT. No incluye pagos CP22.

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

## APK física DEV (CP21.5)

`API_BASE_URL` admite el origen del backend (se añade `/api/v1` si no tiene ruta)
o la base API explícita. Las bases con ruta se conservan, sin duplicar `/api/v1`.
Las variables reales son `ENVIRONMENT` y `MAP_SOURCE`, no `APP_ENV`/`MAP_PROVIDER`.

```sh
flutter pub get
flutter build apk --debug --dart-define=ENVIRONMENT=dev --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 --dart-define=MAP_SOURCE=osm-dev
```

Instalar `build/app/outputs/flutter-apk/app-debug.apk` en Android. El dispositivo
requiere Internet y backend DEV remoto accesible; no requiere backend/DB ni USB
en el PC. Tras registrar, tocar «Ir a iniciar sesión» e ingresar el nombre de
usuario (no email) y la misma contraseña. No hay login automático.
OSM público permanece exclusivamente DEV con atribución; no es configuración PROD.
Diagnóstico, pruebas y limitaciones: [CP21.5](docs/checkpoint-21-5.md).