# CP19.1 - PUSH de registro y baja de vehiculos

Base aislada: develop 37bb3e29c2d16a13353aa103dcd875c45701ac60.
Rama: fix/cp19.1-vehicle-push-20261013.
No hay APK, despliegue, commit, push ni modificaciones de PostgreSQL DEV.

## Diagnostico previo a la implementacion

VehicleService.createVehicle registra el vehiculo propio. VehicleService.deactivate
realiza una baja logica: active=false; no elimina filas ni historiales. La API real
es PUT /api/v1/vehicles/{id}/deactivation. La auditoria existente se llama
VEHICLE_DEACTIVATED y se conserva sin cambios. No existian eventos outbox de
notificacion VEHICLE_REGISTERED/VEHICLE_DELETED ni reglas VEHICLE_* en el codigo.
La consulta de solo lectura a Render DEV tambien devolvio cero reglas VEHICLE_*.
No se renombra la auditoria ni se genera un tercer evento equivalente.

Antes de escribir la migracion se verifico origin/develop: V39 era la ultima
version y V40 estaba disponible. V40__vehicle_push_notifications.sql amplia
la restriccion de tipos permitidos y agrega solo las dos reglas PUSH faltantes.
No modifica V1-V39, no utiliza V36, repair ni outOfOrder. No modifica la funcion
project_citizen_inbox, las reglas IN_APP ni las pantallas de la campanita.

## Implementacion

- VehicleEventPublisher escribe solo en el outbox existente, dentro de la misma
  transaccion del cambio de vehiculo (Propagation.MANDATORY). Nunca invoca FCM.
- Tras save exitoso se registra VEHICLE_REGISTERED; una baja efectiva registra
  VEHICLE_DELETED. Errores y rollback no dejan eventos publicables. Una segunda
  baja de un vehiculo ya inactivo no registra otro evento.
- UUID estable por vehiculo/tipo y ON CONFLICT evitan duplicar el mismo evento.
  La generacion y entregas conservan sus restricciones de unicidad existentes.
- La placa y propietario se capturan desde el vehiculo persistido, no desde un
  destinatario proporcionado por el cliente.
- NotificationOutboxProcessor utiliza los dos eventos en su circuito asincrono
  existente. Valida el propietario del recurso antes de generar la notificacion.
- NotificationDispatcher valida propietario, tipo, referencia y evento outbox;
  incorpora la placa del snapshot y vigencia de seis horas desde ocurrido el
  evento. Selecciona exclusivamente dispositivos activos del propietario.
- Se reutilizan Firebase Admin, FCM data-only, prioridad HIGH, TTL, reintentos y
  gestion de resultados inciertos de CP19. Un error de FCM no revierte la
  operacion de vehiculo ya confirmada.
- Ambos eventos son obligatorios en el alcance, pero las reglas usan
  mandatory=false para respetar Permitir notificaciones (preferencia PUSH).
  El dispatcher vuelve a comprobarla antes de enviar, incluso en reintentos.
- Android acepta referencia VEHICLE solamente para estos dos tipos. Conserva
  gate por propietario/consentimiento, permiso Android, canal, cache limitada
  por notificacion/cuenta, vigencia y privacidad en pantalla bloqueada.
- Al tocar un aviso, el lifecycle abre el Perfil autenticado (Mis vehiculos),
  sin enviar un vehicleId al panel de estacionamiento. Una baja logica ya no
  tiene un detalle activo. No cambia la navegacion de avisos de estacionamiento.

## Texto Android

VEHICLE_REGISTERED:
Titulo: Vehiculo registrado.
Mensaje: Tu vehiculo con placa {placa} fue registrado correctamente.

VEHICLE_DELETED:
Titulo: Vehiculo eliminado.
Mensaje: El vehiculo con placa {placa} fue eliminado de tu cuenta.

Los textos del codigo incluyen los acentos correspondientes. La variable
interna de plantilla se llama {plate}; procede del snapshot real del vehiculo.

## Pruebas focalizadas

VehiclePushIntegrationTest utiliza PostgreSQL 16 aislado, operaciones de
VehicleService y el outbox/dispatcher reales. Solo la frontera Firebase es
mock: no se envia ninguna notificacion a un telefono o usuario real.
Cubre propietario exclusivo, placa, campanita sin nuevos items, rechazo de
operaciones, rollback, baja repetida, replay del outbox, preferencia desactivada
y revocacion de preferencia tras encolar.

VehiclePushMigrationIntegrationTest verifica upgrade V39->V40, checksums
historicos, ausencia de V36, dos reglas opcionales y segunda ejecucion sin
migraciones adicionales. VehicleControllerIntegrationTest verifica regresion
focalizada de los contratos de vehiculos existentes. Se reutiliza el contrato
FcmPushNotificationProviderTest.

Flutter push_lifecycle_test cubre referencia VEHICLE, Perfil autenticado y
preservacion de navegacion, consentimiento, logout y contador anteriores.
Android PushPresentationPolicyTest verifica destinatario, recurso correcto,
texto exacto y ausencia de placa inventada, junto con la politica CP19 existente.
Resultados definitivos: CP19_1_VEHICLE_PUSH_VALIDATION_20261013.json.

## Pendientes fisicos y publicacion

La APK CP19 anterior no conoce estos dos tipos de eventos. Para validarlos se
necesitan backend con V40 y una nueva APK con CP19.1, ambos pendientes de
revision/autorizacion. No se han generado ni desplegado durante este trabajo.

Tras autorizacion, probar ambos eventos con app abierta, en segundo plano,
cerrada normalmente y pantalla bloqueada; repetir baja, provocar un registro
rechazado, desactivar la preferencia y cambiar de cuenta. Esperado: solo el
propietario recibe el aviso; no hay avisos por fallos o bajas repetidas; el
interruptor desactivado impide envios; campanita mantiene su comportamiento.

Una app detenida forzosamente requiere reapertura. Prioridad, TTL y cache
reducen retrasos/duplicados sin asegurar entrega inmediata o exactamente una
vez. La recepcion real en Android permanece PENDIENTE; las pruebas mock no la
certifican. No se requiere USB ni ADB.
