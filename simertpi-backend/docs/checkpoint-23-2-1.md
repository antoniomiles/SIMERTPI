# CP23.2.1 — Vencimiento, gracia y actuación verbal

Esta decisión reemplaza el bloqueo total de EXPIRED de CP23.2. No cambia tarifa, fracción, ofertas 30–240, horarios, pagos, mapa ni máximo continuo.

## Política y proyección

No se agregan estados persistidos a ParkingSession. El DTO propio de sesión añade `operational`: `state`, `evaluatedAt`, `nextTransitionAt`, `extensionEligible`, `closeAllowed`. Los timestamps son servidor; el cliente solo proyecta presentación entre consultas y no muestra el límite ni duración de gracia.

| Proyección | Condición | Home | Acciones ciudadanas |
|---|---|---|---|
| ACTIVE | Contrato vigente, antes del aviso | Verde | Extender si hay opción válida; Finalizar |
| ENDING_SOON | Umbral publicado de notificación/availability | Amarillo | Igual a ACTIVE |
| EXPIRED_IN_GRACE | `end <= now < end + grace` | Rojo: Tu tiempo de estacionamiento terminó | Extender si reglas permiten; Finalizar |
| GRACE_EXCEEDED | `now >= end + grace`, sin actuación válida | Rojo crítico: Período de gracia finalizado | Ninguna |
| REGULARIZATION_ALLOWED | Actuación verbal válida para ese vencimiento | Rojo: Regulariza tu estacionamiento | Extender si reglas permiten; Finalizar |
| MAX_TIME_REACHED | 240 minutos contratados acumulados y actuación válida tras gracia | Rojo: Debes mover tu vehículo a otro espacio | Nunca Extender; Finalizar únicamente tras actuación válida registrada después del fin de gracia |

La gracia se conserva en Tariff y no mueve expectedEndAt. Llegar al límite exacto excluye gracia. `SessionLifecycle` centraliza elegibilidad temporal; motor de reglas añade calendario, máximo acumulado, importe, fin nuevo futuro y restricciones existentes. Cierre y creación/aprobación de extensión revalidan servidor. Un fin nuevo es el anterior expectedEndAt más la duración comprada, nunca un reinicio desde now. Si ya quedó completamente en el pasado, se rechaza la oferta.

Siempre se conserva startedAt: 180 contratados admite como máximo 60 adicionales; 210 admite 30; 240 ninguno. La ventana horaria o la condición de fin nuevo futuro pueden limitar más las ofertas. El acumulado contratado es expectedEndAt menos startedAt; la gracia y la amonestación no se suman. Incluso 240 contratados pasan primero por EXPIRED_IN_GRACE y GRACE_EXCEEDED; únicamente después de la actuación válida se proyecta MAX_TIME_REACHED con Finalizar. No existe un segundo flujo por horas de reloj transcurridas.

EXPIRED y MAX_TIME_REACHED siguen persistidos como ocupantes. Proyecciones de gracia/actuación no liberan espacio ni vehículo. Solo un cierre confirmado produce COMPLETED. La amonestación no cancela pagos ni cierra automáticamente. Un pago de extensión pendiente sigue impidiendo cierre; se mantienen replay, recovery y revalidación CP12.

## Contrato municipal mínimo

`POST /api/v1/parking-sessions/{sessionId}/verbal-warnings`

Autenticación y autoridad INSPECTOR, también verificada en servicio mediante InspectorAuthorizationService (usuario habilitado). No recibe userId, inspectorId, hora, vehículo ni espacio del cliente. Las convenciones existentes permiten actuación municipal global; no existe asignación territorial que se haya inventado para este endpoint.

Se prohíbe registrar la actuación sobre una sesión ciudadana propia incluso si el propietario también tiene INSPECTOR. No puede utilizar esa autoridad para desbloquearse a sí mismo.

El propietario queda sujeto a elegibilidad temporal de cierre incluso si además posee rol municipal. El cierre operacional municipal sobre sesiones ajenas conserva el contrato existente.

Header obligatorio: `Idempotency-Key` (1–128 caracteres).

Body: `{ "observation": "texto real escrito por el controlador" }`.

Respuesta 200 con registro persistido: id, parkingSessionId, inspectorId, contractEndAt, recordedAt, observation, actionType=VERBAL_WARNING, active e idempotencyKey. No hay fotos, video, firma ni adjuntos. Observación de 1–500 caracteres, trim, no whitespace vacío, sin etiquetas HTML ni NUL; se trata como texto plano. El ejemplo de documentación nunca se inserta automáticamente.

El controlador solo registra después del fin de gracia sobre sesión todavía abierta. Identificador y hora los asigna servidor/DB. V32 crea `enforcement.verbal_warnings`, FK a sesión/controlador, sin borrar ni modificar registros históricos. La sesión preserva vehículo/espacio para consulta municipal futura. `@Audited` registra la acción con actor autenticado.

## Idempotencia y vigencia

Bloqueo de fila de ParkingSession serializa registro con cierre/extensión. Repetición del mismo inspector/key/payload devuelve el registro original. Cambiar payload con esa key devuelve conflicto. UNIQUE inspector/key y sesión/contractEndAt evita duplicados/carreras; una segunda key sobre el mismo vencimiento devuelve conflicto explícito. La validez requiere active=true y coincidencia exacta con expectedEndAt actual. Después de una extensión, la actuación anterior se conserva pero no desbloquea un vencimiento nuevo. No se implementa revocación/CRUD municipal en este checkpoint.

Eventos automáticos históricos llamados AMONESTACION de CP11 son señales de control, no prueba de conversación humana. Nunca habilitan regularización: únicamente el registro manual VERBAL_WARNING lo hace. No se crean notificaciones nuevas de CP24.

## Flutter y sincronización

Se conserva composición CP23.2. Color/copy/acciones dependen de proyección y flags backend, con fallback conservador para contratos anteriores. Reloj de presentación anclado a evaluatedAt servidor; tick local no modifica sesión. Cruces temporales consultan backend; resume refresca; dispose/background cancelan timer. Mientras GRACE_EXCEEDED/MAX_TIME_REACHED permanece visible, una consulta cada 60 segundos permite descubrir actuación municipal sin reiniciar. No hay polling por segundo; el segundo es únicamente repintado local.

No se muestra duración de gracia, countdown, graceEndAt ni observación interna municipal al ciudadano. Sí se muestra el texto funcional aprobado del estado. Availability pública continúa sin PII ni datos financieros. No se usa matrícula como identidad global.

## Límites de esta entrega

Requiere desplegar backend con V32 antes de validar APK físicamente. No se despliega ni modifica Render desde esta ejecución. No se construye app municipal ni administrador: el contrato es testeable por API. Configuración DEV y validación administrativa/normativa pendiente de CP23.2 permanecen sin cambios.


## Validación de cierre

Backend: mvn test, 462 pruebas, 0 fallos, 0 errores. Flutter: 253 pruebas aprobadas y una captura opcional omitida. flutter analyze: sin incidencias. dart format --output=none --set-exit-if-changed lib test: 87 archivos, 0 cambios. La cobertura nueva incluye límites temporales exactos, amonestación persistida/idempotente, prohibición de autoamonestación, pago pendiente frente a aprobación real, máximo continuo, ocupación, lifecycle/resume y layouts 320x640/landscape con texto 200%. Prueba física del flujo municipal/ciudadano pendiente después del despliegue autorizado de backend/V32.


Compatibilidad: un indicador persistido MAX_TIME_REACHED preexistente basado en tiempo transcurrido no puede impedir una regularización autorizada con acumulado inferior al máximo. Al crear una extensión elegible, bajo el bloqueo existente y tras quote backend, se corrige únicamente ese indicador al estado vigente/vencido correspondiente. No cambian startedAt, expectedEndAt ni importe; la aprobación utiliza PaymentService sin modificaciones. Con 240 contratados no hay quote elegible y no se aplica esta corrección.
