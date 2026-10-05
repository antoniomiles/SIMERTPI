# CP22 — Pago + confirmación + resultado

Baseline heredado: develop/e60c73d. CP21.5 conservado; no commit/push/redeploy.
Implementación técnica DEV. Prueba E2E física de payment: PENDIENTE.
CP23/CP24 no implementados.

## Descubrimiento y contratos reales CP12

Se inspeccionaron PaymentController, CreatePaymentRequest/PaymentResponse,
CreatePaymentService, PaymentIntegrationService/Service, PaymentStatus,
PaymentProviderRegistry/SandboxPaymentProvider, webhooks, idempotencia SQL,
seguridad, configuración y pruebas HTTP/PostgreSQL/contratos CP12.

| Operación | Contrato | Resultado | Seguridad |
|---|---|---|---|
| Crear | POST /api/v1/payments, Idempotency-Key; parkingSessionId UUID + paymentMethod String no vacío máximo 50 | 201 PaymentResponse | CITIZEN + ownership |
| Consultar | GET /api/v1/payments/{id} | 200 PaymentResponse | CITIZEN + ownership |
| Consultar proveedor | POST /api/v1/payments/{id}/refresh, sin body | 200 PaymentResponse | CITIZEN + ownership |

El móvil reutiliza ApiClient, Bearer y X-Correlation-ID. No envía monto/moneda ni
Basic. PaymentResponse: id, parkingSessionId, provider, providerTransactionId,
amount, currency, status, paymentMethod, paidAt, createdAt, updatedAt. No se muestran
referencias externas arbitrarias ni exception bodies. JSON decimal preservado
como lexema/string por parkingJson/money, sin cálculo monetario Flutter.

Estados reales: PENDING, PROCESSING, APPROVED, DECLINED, FAILED, REFUNDED, CANCELLED.
UNKNOWN es técnico del provider y no figura en PaymentResponse: CP12 conserva
PROCESSING/PENDING y findings de revisión. El móvil no inventa un estado financiero.

Solo se crea desde PENDING_PAYMENT propio, no finalizado/expirado y tarifa vigente.
Backend bloquea sesión/pago, valida propiedad, calcula monto desde tarifa/duración,
crea intento durable y despacha fuera de transacción. APPROVED activa vía backend;
otros resultados no activan. Aprobación tardía/cancelación se conserva para revisión.
Backend protege concurrencia y referencias de attempts antiguos. Webhook no se
consume desde Flutter y no hay verificador/productivo ni integración externa nueva.

## Métodos y proveedores: límite explícito

No existe catálogo/enum/API de métodos de pago ni checkout bancario. paymentMethod
es metadata String; incluso no se transmite al PaymentProviderRequest actual.
CARD aparece en pruebas históricas, TEST en pruebas CP12; ninguno demuestra una
integración bancaria. No se ofrecen Banco Pichincha, tarjetas ni billeteras ficticias.

Solo ENVIRONMENT=dev ofrece «Solicitud de pago DEV» con metadata TEST, identificada
como prueba técnica. QA/UAT/PROD no ofrecen métodos inventados ni botón para cobrar.
Esto no equivale a disponibilidad de un método productivo.

Único adapter: SANDBOX_STUB, exclusivo dev/test y habilitación explícita. Configuración
principal/Render declarada: SIMERTPI_PAYMENT_PROVIDER=UNCONFIGURED y
SIMERTPI_PAYMENT_SANDBOX_ENABLED=false. Dev seed NO cambia esos valores. 503 se muestra
como servicio de pago no disponible, sin éxito ni activación local. No se modifica
Render en esta ejecución. Para una futura prueba sandbox autorizada el operador puede
seleccionar SANDBOX_STUB y habilitar sandbox únicamente en dev. Su outcome por defecto
es PENDING; los tests controlan APPROVED/DECLINED/UNKNOWN. No hay transferencia real.

## UI/estado y navegación

features/payments mantiene feature-first, ChangeNotifier y componentes CP17.
PaymentsPage reúne método, resumen, confirmación, procesamiento contextual y resultado.
Desde la solicitud creada CP21: «Continuar al pago». Desde Inicio: «Consultar pago
pendiente» permite recuperar el último intento o escoger una solicitud pendiente real.
Después de un resultado terminal, «Ver solicitudes pendientes» permite seleccionar otra solicitud propia sin abandonar una operación incierta. El registro durable anterior permanece hasta confirmar un nuevo intento. No hay contador activo, extensión, finalización, historial ni funciones CP23/CP24.

Resumen: código real de espacio desde catálogo CP20, placa propia CP19, duración
persistida y cotización backend. Antes de confirmar se reconsulta sesión, catálogo,
vehículo, reglas y tarifa; un cambio exige revisión. Importe previo es ESTIMADO:
no existe contrato de cotización bloqueada/versionada. El backend valida/cobra y el
resultado muestra exclusivamente PaymentResponse.amount/currency. Cambios entre esa
revalidación y POST siguen bajo autoridad backend; no se promete precio congelado.

APPROVED muestra pago confirmado; afirma activación solo tras consultar también la
sesión y recibir ACTIVE. Si no es ACTIVE advierte revisión. El resultado incluye
referencia UUID SIMERTPI real e importe; no inventa recibo fiscal/PDF ni número SIM-...
DECLINED/FAILED permiten revisar otro intento explícito solo tras consultar nuevamente
el pago y revalidar sesión pendiente; nunca retry automático. PENDING/PROCESSING solo
permiten comprobar estado mediante GET y query del provider.

## Idempotencia, incertidumbre y persistencia

La intención mínima (owner, sessionId, TEST, key aleatoria 128 bits, timestamp,
paymentId cuando conocido) se guarda en flutter_secure_storage ANTES del POST.
Namespace ambiente+API+ciudadano. No password, token auth, tarjeta, PAN/CVV, payload
bancario ni log sensible. Un fallo de storage impide enviar. AsyncButton + controller
protegen doble tap; Back se bloquea durante procesamiento. Logout/restore/refresh
CP18.1 permanecen sin cambio. La intención del pago no se borra por logout: separada
por usuario, solo se consulta de nuevo después de autenticarse; evita perder una
operación financiera incierta al cerrar sesión.

Si hay paymentId: restaurar usa GET; consulta al proveedor solo bajo acción explícita.
Si se perdió respuesta sin ID: recuperación explícita reenvía EXACTAMENTE body/key
anteriores. Puede completar la primera solicitud si no llegó originalmente: la UI
lo explica. No genera una nueva intención ni un cobro optimista. CP12 evita redispatch
cuando ya reclamó el attempt. Sin respuesta definitiva, 5xx/timeout/no conexión no
se consideran DECLINED/FAILED. No se hace polling.

CREATE_PAYMENT keys expiran server-side a las 24h. El móvil restringe replay a menos
de 23h y reloj no retrocedido; fuera de ventana deriva a revisión, nunca nueva key.
GET por ID sigue disponible. Falta contrato de lookup por idempotency key/por sesión
para recuperación sin ID fuera de TTL; reloj incorrecto puede requerir soporte.
Un almacenamiento corrupto/ownership ajeno falla cerrado.

## Figma / UX

Figma oficial, página 01 — App Ciudadano, metadata accedida en esta ejecución:
5:709 «08 — Pago», 5:729 «09 — Confirmación de pago», 5:748 «10 — Resultado».
Solicitud get_design_context de 5:729 bloqueada por «Figma MCP tool call limit on
Starter plan». No hubo screenshot/contexto visual de alta fidelidad. No se cambian
tokens ni se declara fidelidad pixel-perfect: PENDIENTE VALIDACIÓN VISUAL FIGMA.
Gaps Figma/backend: métodos bancarios/tarjeta/billetera sin adapter, monto antes de
POST no congelado, recibo/PDF sin endpoint. Design System existente reutilizado.

Targets y Semantics del DS, mensajes con texto/iconos, estados explícitos, skeleton
solo carga inicial y progreso contextual al enviar. Tests 320x640 y landscape640x320,
texto200% y teclado160px sin overflow. Sin delays artificiales ni fixtures en app.

## Validación y ejecución

Pruebas CP22: 29 Flutter (HTTP, controller, widget, storage, TTL, restore, estados,
quote change, owner, 401 retry mismo key/body/decoder, 403/409/503, doble tap, guards,
responsive). Backend agrega 3 escenarios Bearer (APPROVED/DECLINED/UNKNOWN) al suite
CP12 con ownership, replay, correlation, precio backend y relación sesión/pago.
Dirigidos backend: 48 tests PASS; seed CP21.5: 6 PASS. Suite backend final: BUILD SUCCESS, 414 tests, 0 failures/errors/skipped y Flyway validate V30.

Resultado final técnico: PASS CON OBSERVACIONES (provider/métodos productivos y
validación visual Figma pendientes; E2E físico no realizado).
flutter pub get PASS; dart format --set-exit-if-changed . PASS (68 archivos,
0 cambios); flutter analyze: No issues found; flutter test: 132 PASS, 0 fallos.
Se conservan los 103 tests previos y se agregan 29 CP22, sin nuevos paquetes Flutter.
APK DEV compilada realmente con los tres dart-define indicados abajo: build EXIT 0,
assembleDebug 226.4s, build/app/outputs/flutter-apk/app-debug.apk. No upload ni install
físico desde esta ejecución. git diff --check PASS, cambios sin stage; no add/commit/push.

```powershell
flutter pub get
dart format --set-exit-if-changed .
flutter analyze
flutter test
flutter build apk --debug `
  --dart-define=ENVIRONMENT=dev `
  --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 `
  --dart-define=MAP_SOURCE=osm-dev
```

APK no versionada. Tras revisión y redeploy del dataset seguir estrictamente:
registro/login → vehículo → zona/calle/espacio → QR/código → duración → cotización
→ PENDING_PAYMENT; comprobar CP21 físico antes de payment. No se probó payment físico
ni se conectó un proveedor nuevo. Coordenadas seed omitidas y QR físico pendiente.

Deuda: catálogo/métodos/provider real, contrato quote congelado, recuperación sin ID
fuera de TTL, recibo fiscal, validación Figma, iOS/macOS. Próximo roadmap CP23, pero
no iniciado y solo después de revisión/cierre CP22 y validación física ordenada.