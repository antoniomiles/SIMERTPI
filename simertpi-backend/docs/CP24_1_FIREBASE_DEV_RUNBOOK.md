# CP24.1 — Firebase DEV, V34 y pruebas físicas Android

Esta guía prepara la configuración manual de FCM para SIMERTPI DEV. No
contiene credenciales y no habilita ni despliega servicios por sí sola.

## Estado y límites

- CP24.1 reutiliza el inbox lógico, outbox, dispatcher, preferencias,
  registros de dispositivos y reintentos existentes.
- FCM Android está preparado por código; la entrega real sigue **PENDIENTE**
  hasta configurar Firebase/ADC y comprobarla en un teléfono.
- EMAIL real corresponde a CP24.2. WhatsApp permanece fuera de alcance.
- El paquete Android configurado es
  `ec.gob.simertpi.simertpi_citizen_app`.
- No se requiere `google-services.json`: Flutter inicializa con `FirebaseOptions`
  creadas desde cuatro Dart defines. No guardar esos valores públicos junto con
  secretos; restringir la clave API a la aplicación cuando Firebase lo permita.
- Firebase Admin usa ADC mediante `GOOGLE_APPLICATION_CREDENTIALS` o una
  identidad de workload. Nunca subir una clave de servicio al repositorio.

## 1. Preparar el proyecto Firebase manualmente

1. Crear/seleccionar un proyecto Firebase separado para DEV.
2. Habilitar Firebase Cloud Messaging y registrar una aplicación Android con
   el paquete exacto indicado arriba.
3. Copiar desde la configuración de la aplicación Android los valores públicos:
   API key, App ID, sender ID y project ID. No usar valores inventados.
4. Crear una identidad de servicio de mínimo privilegio con capacidad de enviar
   mensajes FCM. Preferir identidad administrada/ADC cuando el entorno lo admita.
   Si se necesita una clave JSON, almacenarla en un gestor de secretos seguro,
   fuera del repositorio y del directorio sincronizado; restringir acceso y
   rotarla según la política municipal.
5. Mantener desactivado FCM en el backend hasta que la credencial y el proyecto
   hayan sido verificados. La ausencia de Firebase opcional en Flutter debe
   dejar utilizables las funciones ciudadanas que no son PUSH.

### Variables de compilación Flutter

Los nombres exactos leídos por `FirebasePushRuntime.fromDefines()` son:

- `FCM_FIREBASE_API_KEY`
- `FCM_FIREBASE_APP_ID`
- `FCM_FIREBASE_SENDER_ID`
- `FCM_FIREBASE_PROJECT_ID`

Desde PowerShell, en `simertpi-citizen-app`, compilar pasando valores reales
obtenidos de la consola Firebase. Los marcadores siguientes son instrucciones,
no valores válidos; reemplazarlos localmente y no guardar el comando resultante
en scripts compartidos ni logs:

```powershell
flutter build apk --debug `
  --dart-define=ENVIRONMENT=dev `
  --dart-define=API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1 `
  --dart-define=MAP_SOURCE=osm-dev `
  --dart-define=FCM_FIREBASE_API_KEY=<FIREBASE_ANDROID_API_KEY> `
  --dart-define=FCM_FIREBASE_APP_ID=<FIREBASE_ANDROID_APP_ID> `
  --dart-define=FCM_FIREBASE_SENDER_ID=<FIREBASE_SENDER_ID> `
  --dart-define=FCM_FIREBASE_PROJECT_ID=<FIREBASE_PROJECT_ID>
```

Sin los cuatro defines, el runtime PUSH opcional no se crea. No se debe
interpretar la ausencia del servicio como error de login o de estacionamiento.

## 2. Configurar backend DEV en Render (paso humano posterior)

Variables reconocidas por `application.yml` / configuración Firebase:

- `SIMERTPI_NOTIFICATION_FCM_ENABLED`: `false` mientras no se haya completado
  la configuración y la ventana de verificación; activar explícitamente solo
  después de validar ADC y el proyecto.
- `SIMERTPI_NOTIFICATION_PUSH_PROVIDER`: usar exactamente `FCM` para seleccionar
  `FcmPushNotificationProvider`; el default `UNCONFIGURED` no envía PUSH.
- `SIMERTPI_FCM_PROJECT_ID`: ID del proyecto Firebase DEV, no un secreto.
- `GOOGLE_APPLICATION_CREDENTIALS`: ruta del archivo de credencial montado
  mediante un mecanismo seguro, o usar ADC/workload identity disponible. No
  introducir el contenido de una clave en esta variable.

El backend solo crea Firebase Admin cuando
`SIMERTPI_NOTIFICATION_FCM_ENABLED=true`; en ese caso exige
`SIMERTPI_FCM_PROJECT_ID` y ADC válido. Al dejarlo `false`, Firebase Admin no se
inicializa. No configurar el proveedor como `FCM` antes de incluir la versión
que registra el provider y contar con el entorno preparado. Al habilitar el
servicio, actualizar coordinadamente `SIMERTPI_NOTIFICATION_FCM_ENABLED=true`,
`SIMERTPI_NOTIFICATION_PUSH_PROVIDER=FCM`, `SIMERTPI_FCM_PROJECT_ID` y ADC:
el provider FCM solo se registra cuando FCM está habilitado, y el registro
rechaza seleccionar `FCM` si no hay exactamente un provider disponible.

No copiar valores secretos a tickets, capturas, terminal compartida ni logs.
Antes de activar FCM, comprobar en logs solo estado de configuración y errores
sanitizados; nunca imprimir tokens de registro o credenciales.

## 3. Checklist de despliegue Flyway V34 en Render DEV

Este procedimiento es para una futura ventana aprobada. Esta guía no ejecuta
deploy, SQL ni cambios de Render.

### Antes del deploy

- Confirmar commit/rama aprobados y que el servicio objetivo sea el backend DEV.
- Obtener backup/snapshot de `simertpi-postgres-dev` y validar que sea restaurable.
- Confirmar en logs/estado Flyway que V33 está aplicada y que el esquema está
  listo para aplicar V34. No aplicar SQL manualmente.
- Revisar la migración versionada
  `V34__parking_extension_confirmed_notifications.sql` junto con constraints y
  pruebas; V34 es forward-only.
- Dejar FCM deshabilitado hasta que se comprueben la credencial ADC y el
  proyecto. Un fallo de PUSH no debe impedir operaciones de estacionamiento.

### Durante/después del deploy

- Permitir que Spring/Flyway aplique V34 durante el arranque normal.
- Revisar logs de arranque para Flyway V34 exitosa; detener la validación ante
  checksum mismatch, fallo SQL, constraint o startup.
- Verificar liveness y readiness `UP` y que el servicio DEV responda.
- Verificar sin datos personales que las reglas de extensión confirmada están
  presentes, que IN_APP sigue disponible y que los registros de delivery se
  mantienen separados por dispositivo/canal.
- Activar FCM explícitamente solo tras validar ADC, project ID, preferencias y
  una entrega controlada a un dispositivo de prueba autorizado.
- Revisar estado del outbox/dispatcher/delivery con identificadores técnicos
  internos; no registrar FCM tokens ni payload sensible.

### Recuperación

- Si el arranque falla antes de completar V34: detener el rollout e investigar;
  no improvisar cambios manuales en producción ni en la base DEV compartida.
- Si V34 ya se aplicó, no editar V34 ni tratar de reconstruir constraints
  anteriores. Flyway del proyecto se maneja hacia adelante: usar restauración
  del snapshot anterior si se requiere rollback completo, o corregir mediante
  una nueva migración V35+ conservando datos. No existe downgrade automático.
- Mantener FCM apagado hasta resolver cualquier problema de schema/startup.

La confirmación de extensión se agrega al outbox en la misma transacción
que aprueba el pago y aplica el nuevo fin de sesión. El despacho usa el
scheduler existente (intervalo configurado por defecto: 5000 ms), así que
“inmediata” significa generada tras la aprobación, no una entrega síncrona ni
un SLA de recepción. La recepción física puede tardar más.

## 4. Plan de prueba física Android

Todos los casos empiezan **PENDIENTE**. Marcar PASS solo con ejecución en un
teléfono físico y conservar evidencia sin tokens ni datos personales. Evidencia
recomendada: estado visible del teléfono, hora aproximada, ID técnico seguro,
evento/outbox/delivery sanitizados y estado del inbox. No capturar credenciales.

| # | Caso y precondición | Pasos | Resultado esperado | Evidencia requerida | Estado |
|---|---|---|---|---|---|
| 01 | APK sin defines FCM | Abrir y usar login/Home | App operativa; PUSH opcional no inicializado | pantalla + log sanitizado | PENDIENTE |
| 02 | Instalación limpia, sin consentimiento | Abrir Perfil, no activar | Sin token ni registro de dispositivo | pantalla + ausencia de registro backend | PENDIENTE |
| 03 | Usuario acepta activar PUSH | Activar desde Preferencias | Se inicia flujo de permiso y registro | pantalla + auditoría de device | PENDIENTE |
| 04 | Android permite notificaciones | Aceptar permiso | Preferencia/permiso/token asociados al usuario actual | Android settings + device activo | PENDIENTE |
| 05 | Android deniega permiso | Denegar | App y funciones ciudadanas siguen operativas; no token entregado | pantalla + backend sin nuevo device activo | PENDIENTE |
| 06 | Token disponible | Activar con permiso | Backend asocia token al ciudadano autenticado sin exponerlo | registro sanitizado | PENDIENTE |
| 07 | Usuario/espacio DEV libre | Iniciar estacionamiento | Flujo normal; inbox conserva lógica existente | sesión y inbox | PENDIENTE |
| 08 | Sesión en ventana de aviso | Esperar regla de aviso | Evento ENDING_SOON según configuración | outbox + notification/delivery | PENDIENTE |
| 09 | App foreground | Recibir ENDING_SOON | Inbox/badge se actualiza sin crear duplicado local | pantalla + conteo lógico | PENDIENTE |
| 10 | App background | Recibir aviso | Android muestra notificación | teléfono + delivery FCM aceptado | PENDIENTE |
| 11 | App terminada | Recibir aviso y tocar | App arranca; valida sesión/backend antes de navegar | teléfono + navegación | PENDIENTE |
| 12 | Sesión autenticada y activa | Tocar aviso | Abre estacionamiento propio actualizado | pantalla + consulta backend | PENDIENTE |
| 13 | Sesión elegible | Elegir extender | Muestra opciones recibidas de backend | pantalla/cotización | PENDIENTE |
| 14 | Cotización real | Solicitar duración | Importe y duración provienen del backend | respuesta/captura sanitizada | PENDIENTE |
| 15 | Extensión lista | Continuar por pago existente | No se paga al tocar PUSH; flujo normal requiere confirmación | navegación/pago | PENDIENTE |
| 16 | Sandbox/medio autorizado aprueba | Aprobar pago | Backend confirma pago y aplica extensión | pago APPROVED + expected end | PENDIENTE |
| 17 | Extensión aprobada | Esperar notificación | Una notificación lógica y deliveries por dispositivo | inbox/outbox/delivery | PENDIENTE |
| 18 | Extensión aprobada con dispositivo | Recibir FCM | Push de extensión confirmada llega después de aplicación backend | teléfono + session actualizada | PENDIENTE |
| 19 | Nuevo expectedEndAt | Abrir detalle | Hora coincide con dato backend en zona Ecuador | detalle + API | PENDIENTE |
| 20 | Pago PENDING | Mantener resultado pendiente | No hay confirmación ni tiempo añadido | pago y sesión | PENDIENTE |
| 21 | Pago REJECTED | Rechazar pago | No hay confirmación ni extensión aplicada | pago y sesión | PENDIENTE |
| 22 | Aprobación posterior válida | Completar aprobación tardía | Una aplicación y una notificación lógica | registros con misma referencia | PENDIENTE |
| 23 | App cerrada durante pago | Cerrar antes del resultado | Recovery consulta backend; no asumir éxito | reinicio + estado servidor | PENDIENTE |
| 24 | Push visible | Tocar dos veces rápidamente | Sin acción de negocio ni filas duplicadas | UI/inbox/delivery | PENDIENTE |
| 25 | Push de sesión/end antiguo | Extender antes de tocar | Reconsulta muestra estado nuevo o fallback seguro | backend + pantalla | PENDIENTE |
| 26 | Dispositivo registrado | Logout normal | Revocación confirmada o pendiente durable; sesión limpia | backend/device + app | PENDIENTE |
| 27 | Sesión expirada | Tocar push | Login; navegación pendiente solo tras autenticar/revalidar | login + destino seguro | PENDIENTE |
| 28 | Dos cuentas en teléfono | Logout A, login B | B no hereda destino ni device de A | dos cuentas + ownership | PENDIENTE |
| 29 | Permiso previamente concedido | Revocar desde Android Settings | App detecta revocación al reanudar; no promete entrega | settings + refresh app | PENDIENTE |
| 30 | Conectividad apagada | Activar/revocar sin red | App funciona y revocación no se reporta como confirmada | pantalla + cola pendiente | PENDIENTE |
| 31 | FCM temporalmente no disponible | Provocar fallo controlado | Delivery retryable; parking/pago no se bloquea | estado delivery y operación | PENDIENTE |
| 32 | Token FCM inválido en fixture controlado | Entrega provider invalid token | Delivery/device se desactiva según regla; token no aparece en logs | estado sanitizado | PENDIENTE |
| 33 | Dos dispositivos autorizados | Registrar ambos y emitir evento | Una fila lógica, deliveries separadas | inbox count + dos deliveries | PENDIENTE |
| 34 | Preferencia PUSH OFF | Desactivar en Perfil y emitir evento | IN_APP continúa; no nueva delivery PUSH | preferences + inbox/delivery | PENDIENTE |
| 35 | PUSH sin permiso o apagado | Generar evento IN_APP | Inbox y unread badge funcionan sin PUSH | pantalla + unreadCount | PENDIENTE |

## 5. Criterio de cierre de prueba

No declarar lista la entrega física por pasar pruebas unitarias o por ver un
ID de aceptación FCM. En el modelo actual `DELIVERED` significa que FCM aceptó
el mensaje y devolvió su message ID; no es un recibo de que Android lo haya
mostrado ni de que el ciudadano lo haya visto. La aceptación del proveedor no
demuestra visualización en Android. Registrar para cada caso teléfono/modelo, versión Android, versión de
APK, timestamp, resultado y referencias sanitizadas del backend. Confirmar
separadamente foreground, background y terminated.
