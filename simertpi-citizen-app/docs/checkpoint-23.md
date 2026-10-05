# CP23 — Parking activo, extensión y finalización

Baseline: develop / 26e1563; working tree inicial limpio. Sin commits ni push. No CP24.

## Cierre visual de vehículos
Se retira únicamente el acceso Agregar del encabezado. El botón inferior permanece con cero o varios vehículos y reutiliza VehicleFormPage. Placa, uppercase, menú, baja lógica y V31 permanecen intactos.

## Contratos y estados
GET /api/v1/parking-sessions/user/{userId} permite recuperar las sesiones propias. GET /api/v1/parking-sessions/{id} resincroniza una sesión. Home muestra todas las sesiones ACTIVE, EXTENDED, EXPIRED y MAX_TIME_REACHED: el dominio no limita una por usuario. PENDING_PAYMENT conserva su flujo CP22; COMPLETED y CANCELLED no son abiertas.

POST /api/v1/parking-sessions/{id}/close se conserva. ACTIVE, EXTENDED, EXPIRED y MAX_TIME_REACHED son cerrables, excepto cuando existe extensión con pago pendiente. COMPLETED retorna sin modificar endedAt al repetir el cierre. Ownership se verifica en backend y se bloquea la fila durante cambios concurrentes. Historial y asociaciones de vehículos se conservan.

## Extensión: contrato mínimo incorporado
El endpoint legado de extensiones creaba la intención sin enviar al proveedor; tampoco existía cotización ciudadana. Se incorporan GET /api/v1/parking-sessions/{id}/extensions/quote?additionalMinutes=N y POST /api/v1/parking-sessions/{id}/extensions/mobile. Requieren CITIZEN y ownership.

Request móvil: additionalMinutes, paymentMethod, idempotencyKey, expectedAmount decimal y expectedEndAt ISO. El proveedor se obtiene de configuración backend. La respuesta es SessionExtension existente con paymentId. Se reutilizan reglas y despacho CP12, fuera de locks. La cotización se revalida bajo bloqueo; cambios de precio/fin devuelven conflicto. Replay con la misma clave y contenido retorna la misma extensión/pago, sin nuevo cobro. DECLINED, FAILED y CANCELLED cierran la intención de extensión sin ampliar tiempo.

Estados extensibles: ACTIVE, EXTENDED y EXPIRED, sujetos a calendario, fracción mínima, máximo continuo y gracia. MAX_TIME_REACHED no permite extensión. La extensión solo se confirma después de Payment APPROVED y sesión EXTENDED. Flutter no calcula dinero ni cambia estados.

## Flutter y recuperación
Feature active_parking separa gateway, controller y presentación. Reutiliza ApiClient Bearer, refresh, correlation ID, reglas, catálogo y PaymentGateway CP22. Las intenciones se persisten en secure storage, separadas por ambiente/API/propietario, antes del POST. No contiene contraseña ni datos de tarjeta. Ante respuesta incierta se consulta el pago o se repite explícitamente la misma intención idempotente; nunca se crea una clave nueva automáticamente.

Home da prioridad a las sesiones abiertas y conserva QR/búsqueda/vehículos. El contador deriva de expectedEndAt backend con reloj local, es informativo, no autoriza operaciones. Refresca al llegar a cero una vez por fecha, al reanudar y al regresar de los flujos; timer suspendido en background y eliminado al salir. No polling de red agresivo. El estado retenido tras error está marcado como pendiente de actualización. Cierre requiere confirmación, bloquea doble interacción y reconsulta antes de repetir tras timeout.

## Ambiente, UX y límites
Se mantiene únicamente TEST en DEV según CP22, identificado como prueba sin pago real; no se inventa método productivo. Providers UNCONFIGURED en Render no permiten despacho; requieren configuración/redeploy autorizado. Sandbox PENDING requiere resolución por proveedor/configuración existente; no se autoaprueba desde Flutter. No se cancela automáticamente una operación financiera pendiente. iOS y prueba física CP23 quedan pendientes; APK no instalado automáticamente.

Figma: un intento controlado, bloqueado por MCP Starter tool call limit. PENDIENTE VALIDACIÓN VISUAL FIGMA. Se reutiliza Design System, sin branding/dependencias nuevas. Semantics de estado/tiempo, texto además de color, targets existentes y layouts 320x640, landscape y texto 200% se cubren en widgets.

## Validación
Resultados finales se consignan en el reporte de ejecución. Pruebas dirigidas backend verifican quote/ownership, despacho, replay concurrente, rechazo, estados cerrables, cierre repetido e historial. Flutter cubre estados, contador/resume, confirmaciones, timeout, persistencia segura, HTTP exacto/Bearer/correlation y responsive. Se ejecutan además Maven completo, flutter analyze, flutter test, formato, APK DEV y git diff --check.

## Deuda y siguiente etapa
Reloj de dispositivo puede diferir del servidor: contador orientativo, backend decide. Resultado financiero pendiente conserva intención para recuperación; no se fuerza aprobación/cancelación. Proveedor productivo y validación física requieren entorno autorizado. Consultas municipales ambiguas por matrícula quedan fuera de CP23. CP24 no iniciado.

Validación ejecutada: backend dirigido 33/33; Maven completo 428/428 (sin skipped); Flutter completo 174/174; flutter analyze sin observaciones; dart format aplicado. APK DEV y diff final: consultar resultado del reporte de cierre.
APK DEV compilada correctamente: build/app/outputs/flutter-apk/app-debug.apk (207662880 bytes). ENVIRONMENT=dev; API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1; MAP_SOURCE=osm-dev. No instalada. git diff --check correcto; índice vacío. Validación física y visual Figma pendientes.
