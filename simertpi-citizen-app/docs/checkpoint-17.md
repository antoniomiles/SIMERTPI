# CP17 — Flutter ciudadano

## Baseline y alcance

Develop limpio al iniciar, HEAD `8448863` (CP16). Proyecto hermano `simertpi-citizen-app`; backend intacto. Flutter 3.47.6 stable, Dart 3.13.5, SDK `C:\src\flutter`; Windows 10. Android/iOS generados mediante Flutter create. CP17 es foundation y showcase identificado como demostración: no login, vehículos, mapa, QR, parking, pagos ni datos municipales ficticios.

## Arquitectura

Feature-first: `app` concentra bootstrap, dependencias y router; `core` configuración, red, errores, estado, theme y componentes; `features/splash` y `features/showcase` tienen responsabilidades reales. No directorios/capas vacíos para funcionalidades futuras.

Estado: ChangeNotifier nativo, ListenableBuilder y estado local efímero. ShowcaseController controla la demostración; AsyncController ofrece INITIAL/LOADING/SUCCESS/EMPTY/ERROR/REFRESHING, conserva contenido en refresh, impide cargas duplicadas y evita notificaciones tras dispose. Cada sección puede tener su controlador: carga progresiva sin bloquear toda la pantalla. CP18 podrá agregar repositorios/servicios con responsabilidad real.

Navigator nativo con AppRoute centralizado, rutas inicial/splash, home, componentes y estados. No guards ficticios: política pública/protegida y sesión se incorporan en CP18; deep links/notificaciones requerirán validación del destino y autorización. Bootstrap valida configuración y posee/cierra ApiClient; AppScope permite acceso a configuración/cliente. Splash pasa al showcase al terminar el primer frame, sin delay artificial.

## Diseño y UX

**PENDIENTE VALIDACIÓN VISUAL FIGMA.** No se proporcionó enlace accesible ni assets locales. Colores neutrales institucionales provisionales, tipografía del sistema y Material icons, centralizados en AppColors/AppSpace/AppSize/AppTheme. No se afirma identidad gráfica definitiva ni se dibuja un logo municipal. Light theme únicamente; dark mode pendiente de diseño aprobado. Launcher icons nativos generados por Flutter son placeholders para reemplazar con assets oficiales.

AppPage/AppCard/AppTextField, Primary/Secondary/Destructive/AsyncButton, AppDialog/AppSnackbar, AppSkeleton/SkeletonCard/SkeletonList, EmptyState y ErrorState con retry. Un CTA dominante, superficies sin sombras decorativas, acciones destructivas separadas y confirmaciones únicamente de demostración. No se muestran valores de parking ni respuestas técnicas al ciudadano.

SafeArea, scroll adaptable al teclado, ancho máximo 640, Wrap y tamaños táctiles mínimos 48. Texto admite escalado, iconos acompañados de texto, errores cerca del campo, mensajes semánticos liveRegion. Skeleton estático sin shimmer constante, contenido ficticio o duración mínima; el showcase selecciona estados manualmente, no finge llamadas. AsyncButton protege doble interacción, muestra progreso contextual y restaura estado ante error. Operaciones críticas futuras esperarán confirmación real.

**OBSERVACIÓN UX:** navegación, colores, tipografía y branding finales necesitan contraste y revisión de consistencia con Figma antes de pantallas CP18–CP24. El showcase no define la Home funcional. La futura Home debe priorizar parking activo sin inventar tiempos/estados.

## Configuración y networking

Una estrategia: `--dart-define=ENVIRONMENT=dev|qa|uat|prod` y `--dart-define=API_BASE_URL=<base-real>`. No URLs cloud hardcodeadas. DEV permite URL ausente para showcase sin llamadas; QA/UAT/PROD requieren URL HTTPS válida. Rechaza credenciales embebidas, query y fragment. No secretos de servidor en app; dart-define no es almacén secreto.

ApiClient usa dart:io/HttpClient (Android/iOS), JSON, headersProvider para futura autenticación, timeout conexión 10 s y respuesta 15 s como límites técnicos iniciales configurables al construirlo. No dependencia HTTP externa ni endpoints/modelos inventados. Rutas relativas y origen fijo, sin redirecciones automáticas ni retries ciegos. Las pruebas HTTP usan exclusivamente servidor efímero loopback con rutas fixture, no backend/proveedores.

Contrato real: `X-Correlation-ID`, `[A-Za-z0-9._:-]{1,128}`. Genera ID seguro o conserva el válido, reemplaza inválido y propaga el retornado por backend. AppFailure separa offline/timeout/unavailable/unauthorized/rejected/unknown. Una escritura cuya respuesta se pierde queda incierta: no se anuncia fracaso definitivo ni se reenvía automáticamente. Almacenamiento seguro de sesión pendiente CP18.

Logging central solo debug, campos event/result/correlationId validados; no URL, cuerpos, headers, excepciones, email, teléfono ni tokens. En release no emite esos eventos. Android release niega cleartext; debug permite HTTP local. iOS conserva ATS: DEV en iOS requiere HTTPS o excepción local revisada posteriormente, no se habilita HTTP globalmente.

Dependencias directas: Flutter SDK y flutter_localizations (widgets en español); tests Flutter SDK y flutter_lints del template para análisis. Se quitó cupertino_icons sin uso. Lockfile versionable para aplicación. Flutter/Dart no se reinstalaron ni actualizaron. Solo se completaron los componentes Android faltantes exigidos por el build, descritos abajo. Sin dependencias de funcionalidades futuras.

## Ejecución

```sh
flutter pub get
flutter run --dart-define=ENVIRONMENT=dev
# Usar una base REAL suministrada por infraestructura; no existe dominio productivo asumido.
flutter run --dart-define=ENVIRONMENT=qa --dart-define=API_BASE_URL=<base-https-real>
dart format --set-exit-if-changed .
flutter analyze
flutter test
flutter build apk --debug
```

`.gitignore` excluye .dart_tool/build/IDE/.env/APK/IPA/logs/temporales; no se agrega .env ni credencial. Android debug firmado solo para desarrollo con configuración estándar Flutter; no certificado productivo ni release signing preparado.

## Validación y deuda

Gate final: flutter pub get correcto; dart format --set-exit-if-changed . correcto (22 archivos, 0 cambios); flutter analyze: No issues found; flutter test: 17 passed, 0 failures. Pruebas útiles de configuración, bootstrap/router/theme, estados/retry, doble acción, error sanitizado, contraste/targets, pantallas pequeñas/orientación y 200% texto, correlation/HTTP local y escrituras inciertas. Primer Android build real falló: NDK 28.2.13676358 ausente y shim sdkmanager no resolvió el identificador legacy. Se verificó el catálogo y se instaló exactamente ndk/28.2.13676358 mediante Android CLI existente, sin actualizar otros paquetes (sdk --ignore-outdated-xmls install ndk/28.2.13676358). Pkg.Revision confirmado. El retry del build instaló automáticamente Platform 36 revision 2 requerido por compileSdk con licencia previamente aceptada. No se cambiaron versiones del template ni backend. JDK Android Studio 25.0.3/Gradle 9.3.1 emitieron advertencias de acceso nativo y bandera de procesadores Windows: se conservan, sin suprimirlas ni afirmar análisis de seguridad del toolchain. Segundo intento APK detectó conflicto del manifest debug al habilitar HTTP local. Corregido con tools:replace exclusivamente debug; main/release conserva usesCleartextTraffic=false. Build APK debug final exitoso (assembleDebug 453.3 s, exit 0), build/app/outputs/flutter-apk/app-debug.apk, 152105234 bytes, ignorado por Git. Se repitió solo el build Android después de corregir sus fallos; no se repitió innecesariamente la suite Dart aprobada.

El intento de lanzar el AVD configurado headless en 5580 falló porque el mismo AVD ya estaba en uso. Después ADB reconoció emulator-5554 existente con sys.boot_completed=1; se utiliza esa instancia sin detenerla ni modificar el AVD. El gestor advirtió RAM recomendada 16 GiB frente a 8 GiB disponible. APK instalada en emulator-5554: Success. Cold start am start -W agotó la espera (Status: timeout, WaitTime 44982 ms), aunque mantuvo PID 9847. La comprobación posterior confirmó topResumedActivity de SIMERTPI y captura visual de Home/Showcase completamente renderizado. Smoke básico verificado con esa observación de tiempo; no se declara startup/performance validado ni E2E. No se detuvo el emulador del usuario. Capturas/logs auxiliares fuera del repo se retiran tras verificar. iOS no validable desde Windows: necesita macOS/Xcode.

Pendientes: Figma/branding oficial, revisión visual con dispositivos físicos/lector de pantalla, release signing, endpoints/dominios reales por ambiente, política de sesión/guards y almacenamiento seguro en CP18. Estado CP17: PASS CON OBSERVACIONES (Figma pendiente, cold-start timeout del emulador, iOS sin build Windows). git diff --check correcto y revisión adicional de whitespace en los 88 archivos nuevos versionables sin hallazgos. Git muestra solo simertpi-citizen-app nueva, backend intacto. APK/build/.dart_tool/local.properties/IDE quedan ignorados, sin secretos ni .env real. No avanzar a CP18 en este checkpoint. No git add/commit/push.
