# CP23.2 — Fidelidad visual y reglas dinámicas

Baseline `develop / e77e692`. Referencias: `reference/boceto.jpeg`, 21 capturas y video WhatsApp aportados por el usuario. Se analizaron antes de implementar. El boceto dirige la composición; los contratos backend dirigen datos y acciones.

Ver [auditoría backend y fuente normativa](../../simertpi-backend/docs/checkpoint-23-2.md). La referencia de 2021 está aislada en un seed DEV opt-in. No se certifica vigencia normativa 2026 ni se despliega automáticamente. No hay cálculo de dinero Flutter, tarifas hardcodeadas ni cambios V31.

## Matriz de composición

| Panel | Resultado | Implementación |
|---|---|---|
| 1 Home | MATCH | Saludo, card azul Estacionar, grid 2×2, navegación compacta |
| 2 Buscar/QR | MATCH | AppBar azul, QR prominente, escanear/divisor/buscar |
| 3 Zonas | MATCH | Lista/Mapa, thumbnail neutro, calle/cantidad real y chevron |
| 4 Espacios lista | MATCH | Cards compactas, chip operacional, tiempo y texto |
| 5 Mapa | PARTIAL de evidencia | Pines numerados implementados; captura sin tiles de red, cartografía física pendiente |
| 6 Detalle | MATCH | Estado, tiempo prominente, información y acción bloqueada |
| 7 Vehículos | MATCH | Ocupado rojo/lock, disponible blanco/radio, selección azul |
| 8 Tiempo/precio | MATCH | Cotizaciones agrupadas backend, radios/cards, Continuar |
| 9 Resumen/pago | MATCH | Filas alineadas, total destacado, confirmación y caption DEV |
| 10 Procesando | MATCH | Spinner central, espera y explicación sin éxito anticipado |
| 11 Éxito | MATCH | Check verde, resumen, Aceptar; requiere APPROVED + ACTIVE |
| 12 Home post-parking | MATCH | Card compacta real y refresh de CP23 |
| 13 Extensión | MATCH | Opciones backend, total adicional y confirmación |
| 14 Resultado extensión | MATCH | Check, tiempo ampliado confirmado, importe y volver |
| 15 Vencido/próximo a vencer | MATCH | Superficies roja/amarilla y acciones según backend |
| 16 Finalización | MATCH | Diálogo explícito; EXPIRED y MAX_TIME_REACHED no ofrecen acciones ciudadanas |

MATCH evalúa estructura y estados, no una aprobación pixel-perfect física. Las capturas usan fixtures de pruebas, por lo que nombres y duraciones no representan el dataset desplegado ni valores municipales. Thumbnails neutros sustituyen imágenes ilustrativas. Falta revisión humana de APK frente al boceto en el teléfono real.

## Componentes

`OperationalCard`, `InfoCard`, `SummaryRow`, `SuccessMark`, `CitizenNavigation`, `ParkingMapMarker`, `DurationOptions`. Tokens reutilizados, nuevas superficies centrales, sin fotos externas ni paquetes nuevos. Vehicles/VehiclePlate/baja/uppercase siguen intactos.

## Capturas reproducibles

```powershell
flutter test test/visual_fidelity_test.dart --dart-define=VISUAL_CAPTURE=true
```

En el entorno Windows de desarrollo se cargan Arial y MaterialIcons para que las capturas sean legibles. La generación es opcional y sus archivos quedan en `build/cp23.2-review`, ignorado por Git. Son widgets reales con fixtures y no sustituyen un E2E físico. Incluye storyboard, procesamiento, extensión y colores críticos.

## Validación y compilación

Backend: 440 tests, cero fallos/errores/skips, BUILD SUCCESS. Flutter: resultados finales y manifiesto en `checkpoint-23-2-validation.md`.

```powershell
flutter build apk --debug --dart-define=ENVIRONMENT=dev --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 --dart-define=MAP_SOURCE=osm-dev
```

APK esperado `build/app/outputs/flutter-apk/app-debug.apk`. No se instala automáticamente. El nuevo contrato de opciones y la política de cierre requieren desplegar el backend antes de validar el APK contra Render. Esta ejecución no hace deploy, stage, commit ni push. CP24 permanece fuera del alcance.


## Decisión final posterior a la primera validación

La regla anterior de extensión EXPIRED y cierre MAX_TIME_REACHED queda reemplazada: ambas cards rojas SIN botones; backend rechaza iniciar extensión o cierre ciudadano. EXPIRED: «El tiempo contratado ha finalizado». MAX_TIME_REACHED: «Has cumplido el tiempo máximo permitido en este espacio. Debes mover tu vehículo a otro espacio».

Duraciones y precios vienen del contrato agrupado backend; la referencia configurada presenta 30/60/90/120/150/180/210/240 y 0.13/0.25/0.38/0.50/0.63/0.75/0.88/1.00. Etiquetas compuestas humanas y máximo continuo informado sin input técnico. El calendario puede restringir ofertas al tiempo operacional disponible; no se muestran opciones inválidas.

SuccessCelebration reutiliza CustomPainter/AnimationController durante dos segundos únicamente en éxito inicial y extensión confirmada. Reduce motion omite las partículas; el check y navegación permanecen accesibles. Sin dependencias nuevas.

Los resultados 440/219 corresponden a la validación anterior; los resultados finales se informan en el reporte final y logs cp232-final-*. Ningún commit, stage, push, deploy ni instalación realizados.
