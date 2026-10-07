# CP24 — Historial, inbox y perfil ciudadano

Baseline: develop / e6b721f9a688a7c3c8accf7ab8727be4fd6f2e5a.

## Contratos ciudadanos

Todos requieren CITIZEN y derivan el propietario del principal autenticado.
Tener además un rol municipal nunca amplía las consultas `citizen`.

| Método | Ruta `/api/v1` | Respuesta |
|---|---|---|
| GET | `/citizen/profile` | firstName, lastName, username, email, phone |
| GET | `/citizen/history?limit=20&offset=0` | items, limit, offset, hasMore |
| GET | `/citizen/history/{id}` | session, extensions aprobadas |
| GET | `/notifications/inbox?limit=20&offset=0` | items, limit, offset, hasMore |
| GET | `/notifications/inbox/{id}` | notificación propia |
| PATCH | `/notifications/inbox/{id}/read` | lectura idempotente |
| GET | `/notifications/unread-count` | unreadCount lógico |
| GET/PUT | `/notifications/preferences` | contrato CP13 existente |

Paginación: 1–100 elementos, offset 0–1 000 000. Se solicita una fila
adicional para calcular hasMore sin descargar el conjunto completo.
Orden estable: historial started_at DESC/id DESC; inbox created_at DESC/id DESC.
Los UUID se utilizan exclusivamente como referencias de rutas, no como texto UI.
No hay acciones de pago, extensión ni cierre al tocar una notificación.

## Historial y dinero

El historial incluye solamente COMPLETED y CANCELLED. EXPIRED,
MAX_TIME_REACHED y PENDING_PAYMENT continúan siendo operacionales, no historial.
La placa procede de la relación persistida con el vehículo, incluso inactivo.
Zona/calle/espacio proceden del catálogo relacionado; no se inventa un snapshot
histórico de nombres si posteriormente el catálogo cambia.

El tiempo contratado acumulado es expected_end_at − started_at. La ocupación
real es ended_at − started_at cuando existe cierre. Son conceptos separados.
Los importes se componen de payments.payments con status APPROVED, una vez por
payment; no se suman intentos ni session.total_amount por segunda vez.
Las extensiones se muestran cuando extensión y pago están APPROVED.
El SUM PostgreSQL NUMERIC se recibe como BigDecimal y se transmite con escala
2 como texto decimal. Flutter únicamente presenta ese texto.
Se agrupa por moneda: nunca se suman monedas incompatibles.

## Inbox lógico y compatibilidad V33

V1–V32 permanecen intactas. V33 añade notification.inbox_items como proyección
de los sobres CP13 notification.notifications, no otro servicio de generación,
outbox o dispatcher. Su clave lógica es usuario + tipo + source_event_id.
Los registros legacy sin source_event_id conservan su identidad individual:
no se presume que dos registros sin identidad fiable representen el mismo hecho.

El backfill conserva sobres y deliveries existentes, el timestamp más antiguo
y cualquier lectura previa. Un trigger transaccional mantiene la proyección
para inserciones CP13; IN_APP prevalece como contenido ciudadano cuando está
presente. Las APIs legacy mine/read permanecen disponibles. La lectura legacy
se refleja en la proyección; leer por la API nueva solo modifica inbox_items,
sin modificar estado de envío ni provider.

El contador utiliza un índice parcial de filas propias con read_at IS NULL.
Las preferencias PUSH/EMAIL no desactivan IN_APP. WHATSAPP no se muestra en UI.
No se añadieron tokens, dispositivos ficticios ni fixtures persistentes DEV.
Las pruebas utilizan datos aislados y reproducibles.

## Semántica de eventos

- PARKING_ENDING_SOON: aviso previo, misma autoridad temporal que Home/mapa.
- PARKING_TIME_EXPIRED: el contrato alcanzó expectedEndAt.
- PARKING_GRACE_EXCEEDED: superación interna de gracia, sin exponer duración.
- MAX_TIME_REACHED: máximo contratado alcanzado.
- PAYMENT_APPROVED, PAYMENT_DECLINED, PAYMENT_FAILED: resultado de pago.
- PARKING_COMPLETED: cierre confirmado, repetición de cierre no crea otro aviso.
- VERBAL_WARNING: exclusivamente registro humano válido V32; nunca incluye
  observación o identidad del inspector en el contenido ciudadano.

EXPIRATION se conserva como tipo de control legacy. Sus reglas existentes se
clasifican en aviso previo (minutes_before > 0) o vencimiento real (= 0).
AMONESTACION automático sigue siendo un hecho de control; solo origina el
mensaje de gracia finalizada, no una falsa actuación verbal humana.
GRACE_PERIOD y EXCESS_* no producen spam en el inbox ciudadano.

Cada aviso/vencimiento usa la identidad contractual estable legacy basada en
sesión y expectedEndAt (clave EXPIRATION). Los nuevos tipos de notificación
son diferentes y forman parte de la unicidad lógica; compartir referencia al
mismo contrato no colisiona. Conservar esa referencia permite reconocer avisos
CP13 existentes tras migrar, sin duplicarlos al arrancar el nuevo scheduler.
La unicidad de control incorpora contract_end_at, permitiendo
otros vencimientos de la misma sesión tras extensión. Los hechos de control
antiguos conservan contract_end_at NULL porque esa referencia no se registró;
asignarles el contrato actual podría bloquear un vencimiento posterior legítimo.
El trigger completa la referencia real en toda inserción nueva.
El scheduler de control bloquea la sesión durante evaluación para evitar
clasificar un contrato que está siendo extendido/cerrado.
El dispatcher verifica al reclamar cada aviso pendiente/reintento que la
sesión esté vigente y coincidan contrato e identidad temporal. Un aviso viejo
queda SUPPRESSED, incluso si fue generado desde una lectura anterior a la
extensión. No se eliminan avisos históricos ya entregados.

## V33 deployment and recovery strategy

Antes de un despliegue productivo, crear un backup/snapshot de PostgreSQL y
validar que pueda leerse/restaurarse. Confirmar la versión de Flyway y que V32
esté aplicada antes de iniciar el despliegue.

Flyway aplica V33 hacia adelante durante el arranque normal. Tras el deploy,
verificar logs de startup, versión de schema V33, filas del inbox lógico,
preservación de sobres y deliveries, y health/readiness.

Si el arranque falla antes de completar V33, detener el despliegue e investigar
causa y estado de Flyway/schema. No improvisar cambios SQL manuales en producción.

Las migraciones de este proyecto son forward-only; V33 no tiene downgrade ni
rollback automático. Si V33 ya se aplicó y luego se requiere recuperación, no
intentar reconstruir la unicidad anterior por sesión/tipo: pueden existir
múltiples vencimientos legítimos para una misma sesión, tipo y distinto
`contract_end_at`. Elegir entre restaurar el backup/snapshot previo al deploy
para un rollback completo, o avanzar con una nueva migración V34+ que conserve
los datos y corrija la condición. No modificar V33 después de aplicarla en un
entorno compartido.

## Flutter, sesión y accesibilidad

Historial se abre desde Home/Perfil. Notificaciones y Perfil reemplazan los
placeholders dentro del Home existente, manteniendo su IndexedStack y Android
Back. ApiMethod.patch utiliza el mismo ApiClient, Bearer, refresh y manejo 401.
El contador compartido permite badge en la navegación existente (0 oculto,
99+ como máximo visual). Se reconsulta al navegar, confirmar parking y resume;
no se añadió polling. La lista se refresca al entrar a Notificaciones/resume
y admite pull-to-refresh. Una lectura actualiza lista y contador.

Perfil es solo lectura, sin afirmaciones de verificación. Preferencias editan
celular/correo y explican que los canales externos todavía no están disponibles.
Los errores usan AppFailure y los componentes existentes del design system.
Pruebas cubren 320×640, landscape y texto al 200 %, estados vacíos/carga/error,
lectura, paginación, total monetario y ausencia de IDs en UI.

## CP24.1 y CP24.2

CP24.1: proveedor PUSH real, permisos SO, token refresh/dispositivo, tratamiento
foreground/background/terminated, payload mínimo y tap seguro con reconsulta.
Reutilizar devices/preferences/NotificationProviderRegistry/deliveries/retry.
La coordinación login/logout debe suscribirse al AuthController existente,
desregistrar el dispositivo propio antes o best effort durante logout y no
debilitar limpieza secure storage ni recuperación 401. No hay token FCM ahora.

CP24.2: proveedor EMAIL real, configuración/credenciales externas y plantillas
de entrega. Reutilizar el mismo registry/outbox/dispatcher/retry, sin duplicar
el evento lógico. UNCONFIGURED no representa una entrega física; SANDBOX solo
es un simulador de pruebas explícitamente habilitado.

WHATSAPP, edición de datos, password, verificación y app municipal están fuera
de CP24. No se modifican tarifas, calendario, pagos ni reglas CP23.2.1.
