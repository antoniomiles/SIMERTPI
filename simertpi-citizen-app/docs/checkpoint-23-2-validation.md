# CP23.2 â€” ValidaciÃ³n funcional/visual final (2026-10-06)

Baseline develop / e77e692. Se conservaron los cambios CP23.2 previos y las referencias del usuario. Ãndice vacÃ­o. Sin stage, commit, push, deploy ni instalaciÃ³n de APK. V31 intacta; sin migraciones nuevas.

## Validaciones finales

- Backend: mvn test, 441 PASS, 0 fallos/errores/omitidos, BUILD SUCCESS, 8:33 min.
- Flutter: flutter test, 223 PASS, 1 captura opcional omitida.
- Dirigidos: backend 39 PASS; Flutter nuevas reglas 4 PASS; suite visual con capturas y reglas 17 PASS (ver log final).
- flutter analyze: No issues found.
- dart format lib test: 86 archivos.
- APK DEV recompilada: build/app/outputs/flutter-apk/app-debug.apk. ENVIRONMENT=dev; API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1; MAP_SOURCE=osm-dev. No instalada.
- git diff --check: PASS. Los avisos LF/CRLF no son errores de whitespace.

Logs ignorados backend/target/cp232-final-backend-full.log, cp232-final-flutter-full.log, cp232-final-analyze.log, cp232-final-apk.log, cp232-final-captures.log.

## Cierre de producto

Backend referencia configurada USD 0.25/60 minutos, fracciÃ³n 30, mÃ¡ximo 240, gracia operacional 10, roundingMode HALF_UP/escala 2. Ofertas calculadas con BigDecimal: 30=.13,60=.25,90=.38,120=.50,150=.63,180=.75,210=.88,240=1.00. Solo se ofrecen duraciones vÃ¡lidas bajo calendario/hora disponible; Flutter no calcula dinero.

El dataset de referencia requiere SIMERTPI_DEV_SEED_ENABLED=true y SIMERTPI_DEV_PINAS_ORDINANCE_2021_ENABLED=true, perfil dev aislado. No se habilitÃ³ Render. Sesiones histÃ³ricas conservan la tarifa anterior. La decisiÃ³n actual del producto no acredita vigencia consolidada de ordenanza 2026. Calendario anual nacional/excepciones requieren confirmaciÃ³n administrativa.

ACTIVE/EXTENDED vigentes: Extender/Finalizar segÃºn reglas. ProyecciÃ³n amarilla: mismo umbral publicado de recordatorios EXPIRATION. EXPIRED y MAX_TIME_REACHED: card roja SIN controles, tampoco cierre/extensiÃ³n ciudadana backend. Elapsed ACTIVE/EXTENDED tambiÃ©n rechaza nueva extensiÃ³n; gracia no cambia expectedEndAt. MAX muestra obligaciÃ³n de mover vehÃ­culo. Scheduler existente materializa los estados; contador local bloquea acciones inmediatamente al vencimiento y solicita refresh.

Extensiones solo antes de expectedEndAt, mÃ¡ximo acumulado. 180 min contratados ofrece 30/60; 210 ofrece 30; 240 ninguna. Replay de intenciÃ³n previa conserva recuperaciÃ³n/idempotencia y no constituye una nueva extensiÃ³n.

Confeti nativo de 2 segundos solo en dos Ã©xitos confirmados, decorativo, sin interceptar input, cancelado al dispose y omitido con disableAnimations. Auth, ownership, privacidad, concurrencia y lifecycle de vehÃ­culos preservados.

## Evidencia visual y lÃ­mites

Capturas finales de widgets con fixtures: build/cp23.2-final-verified-2/. No equivalen a prueba fÃ­sica. Mapa usa tiles neutros en test; cartografÃ­a OSM real puede diferir del boceto sin constituir defecto de UI. Capturas anteriores en cp23.2-review y cp23.2-final-review no representan la fixture tarifaria final.

Se verificaron 320x640, landscape y escala 200% mediante widget tests. Falta aprobaciÃ³n humana en telÃ©fono. No se instalÃ³ APK ni desplegÃ³ backend. El APK necesita backend actualizado y flags DEV configurados para prueba remota.

payment_contract.dart y payment_intent_store.dart figuran M por representaciÃ³n/metadata; contenido normalizado igual a HEAD, sin hunks funcionales. Git diff real identifica los cambios de cÃ³digo.

## Archivos tracked con cambios de contenido

```text
simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingRulesController.java
simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/extension/MobileExtensionController.java
simertpi-backend/src/main/java/ec/gob/simertpi/application/dev/DevParkingSeed.java
simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingAvailabilityService.java
simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java
simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionService.java
simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/rules/ParkingRulesService.java
simertpi-backend/src/main/java/ec/gob/simertpi/config/SecurityConfig.java
simertpi-backend/src/main/resources/application.yml
simertpi-backend/src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java
simertpi-backend/src/test/java/ec/gob/simertpi/application/dev/DevParkingSeedPostgresIntegrationTest.java
simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionServiceTest.java
simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/rules/ParkingRulesServiceTest.java
simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart
simertpi-citizen-app/lib/core/theme/app_theme.dart
simertpi-citizen-app/lib/core/theme/app_tokens.dart
simertpi-citizen-app/lib/core/widgets/app_layout.dart
simertpi-citizen-app/lib/features/active_parking/data/active_parking_service.dart
simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart
simertpi-citizen-app/lib/features/active_parking/state/active_parking_controller.dart
simertpi-citizen-app/lib/features/discovery/data/parking_catalog.dart
simertpi-citizen-app/lib/features/discovery/presentation/discovery_page.dart
simertpi-citizen-app/lib/features/discovery/presentation/parking_map.dart
simertpi-citizen-app/lib/features/discovery/presentation/space_selection_page.dart
simertpi-citizen-app/lib/features/discovery/presentation/space_status.dart
simertpi-citizen-app/lib/features/home/home_page.dart
simertpi-citizen-app/lib/features/home/start_parking_page.dart
simertpi-citizen-app/lib/features/parking/data/parking_contract.dart
simertpi-citizen-app/lib/features/parking/presentation/parking_page.dart
simertpi-citizen-app/lib/features/parking/state/parking_controller.dart
simertpi-citizen-app/lib/features/payments/presentation/payments_page.dart
simertpi-citizen-app/test/active_parking_test.dart
simertpi-citizen-app/test/discovery_test.dart
simertpi-citizen-app/test/operational_redesign_test.dart
simertpi-citizen-app/test/parking_test.dart
simertpi-citizen-app/test/payments_test.dart
```

## git status --short (archivos nuevos expandidos)

```text
 M simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/ParkingRulesController.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/api/parking/extension/MobileExtensionController.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/application/dev/DevParkingSeed.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingAvailabilityService.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/ParkingSessionService.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionService.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/rules/ParkingRulesService.java
 M simertpi-backend/src/main/java/ec/gob/simertpi/config/SecurityConfig.java
 M simertpi-backend/src/main/resources/application.yml
 M simertpi-backend/src/test/java/ec/gob/simertpi/api/parking/ParkingSessionCreationHttpIntegrationTest.java
 M simertpi-backend/src/test/java/ec/gob/simertpi/application/dev/DevParkingSeedPostgresIntegrationTest.java
 M simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/extension/ParkingSessionExtensionServiceTest.java
 M simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/rules/ParkingRulesServiceTest.java
 M simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart
 M simertpi-citizen-app/lib/core/theme/app_theme.dart
 M simertpi-citizen-app/lib/core/theme/app_tokens.dart
 M simertpi-citizen-app/lib/core/widgets/app_layout.dart
 M simertpi-citizen-app/lib/features/active_parking/data/active_parking_service.dart
 M simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart
 M simertpi-citizen-app/lib/features/active_parking/state/active_parking_controller.dart
 M simertpi-citizen-app/lib/features/discovery/data/parking_catalog.dart
 M simertpi-citizen-app/lib/features/discovery/presentation/discovery_page.dart
 M simertpi-citizen-app/lib/features/discovery/presentation/parking_map.dart
 M simertpi-citizen-app/lib/features/discovery/presentation/space_selection_page.dart
 M simertpi-citizen-app/lib/features/discovery/presentation/space_status.dart
 M simertpi-citizen-app/lib/features/home/home_page.dart
 M simertpi-citizen-app/lib/features/home/start_parking_page.dart
 M simertpi-citizen-app/lib/features/parking/data/parking_contract.dart
 M simertpi-citizen-app/lib/features/parking/presentation/parking_page.dart
 M simertpi-citizen-app/lib/features/parking/state/parking_controller.dart
 M simertpi-citizen-app/lib/features/payments/data/payment_contract.dart
 M simertpi-citizen-app/lib/features/payments/data/payment_intent_store.dart
 M simertpi-citizen-app/lib/features/payments/presentation/payments_page.dart
 M simertpi-citizen-app/test/active_parking_test.dart
 M simertpi-citizen-app/test/discovery_test.dart
 M simertpi-citizen-app/test/operational_redesign_test.dart
 M simertpi-citizen-app/test/parking_test.dart
 M simertpi-citizen-app/test/payments_test.dart
?? simertpi-backend/docs/checkpoint-23-2.md
?? simertpi-backend/src/main/java/ec/gob/simertpi/application/dev/DevPinasCalendar.java
?? simertpi-backend/src/main/java/ec/gob/simertpi/application/parking/rules/ParkingDurationOptions.java
?? simertpi-backend/src/main/resources/dev/pinas-ordinance-2021.sql
?? simertpi-backend/src/test/java/ec/gob/simertpi/application/dev/DevPinasCalendarTest.java
?? simertpi-backend/src/test/java/ec/gob/simertpi/application/parking/rules/ParkingDurationOptionsTest.java
?? simertpi-citizen-app/docs/checkpoint-23-2-validation.md
?? simertpi-citizen-app/docs/checkpoint-23-2.md
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.20 (1).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.20.jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.21 (1).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.21 (2).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.21.jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.22 (1).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.22 (2).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.22 (3).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.22 (4).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.22.jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23 (1).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23 (2).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23 (3).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23 (4).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23 (5).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.23.jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.24 (1).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.24 (2).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.24 (3).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.24 (4).jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Image 2026-10-05 at 21.45.24.jpeg"
?? "simertpi-citizen-app/docs/reference/WhatsApp Video 2026-10-05 at 21.49.08.mp4"
?? simertpi-citizen-app/docs/reference/boceto.jpeg
?? simertpi-citizen-app/lib/core/widgets/citizen_navigation.dart
?? simertpi-citizen-app/lib/core/widgets/operational_ui.dart
?? simertpi-citizen-app/lib/core/widgets/success_celebration.dart
?? simertpi-citizen-app/lib/features/discovery/presentation/parking_map_marker.dart
?? simertpi-citizen-app/lib/features/parking/presentation/duration_options.dart
?? simertpi-citizen-app/test/duration_options_http_test.dart
?? simertpi-citizen-app/test/final_parking_rules_test.dart
?? simertpi-citizen-app/test/visual_fidelity_test.dart
```
