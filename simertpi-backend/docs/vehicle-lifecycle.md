# Vehículos ciudadanos: normalización y baja lógica

Baseline: develop / 9b9d7f0. Intervención puntual; no CP23/CP24 ni cambios de pagos.

## Modelo y contrato

Cada fila identity.vehicles representa la asociación de un usuario con una placa.
Ya existía active; la baja conserva ID, propietario y todas las referencias.
PUT /api/v1/vehicles/{id}/deactivation requiere CITIZEN y ownership. Devuelve el
VehicleResponse existente, active=false, HTTP 200. Repetir la baja es idempotente:
no altera updated_at nuevamente. No existen edición, transferencia ni DELETE.

Se bloquea con HTTP 409 si existen sesiones PENDING_PAYMENT, ACTIVE, EXTENDED,
EXPIRED o MAX_TIME_REACHED: son los estados operativos reales del backend. No se
cancelan solicitudes, pagos ni estacionamientos. La baja genera auditoría funcional.
Listado ciudadano devuelve solo activos; consultas por ID conservan el historial.
La creación de estacionamientos y la baja toman el mismo bloqueo pesimista del vehículo.
Así, baja e inicio concurrentes no dejan sesiones nuevas de un vehículo inactivo.

## V31 y datos anteriores

V1–V30 no se modifican. V31 normaliza con upper(btrim(plate)) conservando todos
los IDs. Sustituye UNIQUE global por índice único parcial por usuario y placa
normalizada únicamente para asociaciones activas. Dos usuarios pueden compartir
placa; dar de baja uno no afecta al otro. Registrar otra vez una placa dada de
baja crea una asociación nueva sin reutilizar ni eliminar el registro histórico.

Si existen colisiones activas dentro de un usuario, o longitud normalizada inválida,
la migración falla atómicamente antes de modificar los datos. No fusiona, borra ni
desactiva silenciosamente. Revisar y resolver explícitamente esas asociaciones antes
del despliegue, conservando historial y aplicando los controles operativos.

Consulta de diagnóstico (solo lectura; no ejecutada contra Render):

```sql
SELECT user_id, upper(btrim(plate)) AS normalized_plate, count(*)
FROM identity.vehicles WHERE active
GROUP BY user_id, upper(btrim(plate)) HAVING count(*) > 1;
```

La creación normaliza con trim/Locale.ROOT; el índice protege duplicados concurrentes
también si un escritor SQL utiliza minúsculas. No se impone regex municipal nueva.
La consulta singular interna por matrícula deja de ser inequívoca si hay varias
asociaciones, incluso históricas: responde 409, sin escoger un propietario arbitrario.
Las operaciones internas necesitan ID de vehículo/sesión para ese caso. El futuro
contrato plural de consultas municipales permanece fuera de esta intervención.

## Flutter y operación

Se reutiliza VehicleFormPage desde los dos accesos Agregar vehículo en Home/listado.
VehiclePlate es un gráfico genérico de widgets, sin símbolos oficiales. Solo separa
visual y reversiblemente letras ASCII seguidas de números: TBE1234 → TBE 1234,
ATL768 → ATL 768. El request nunca incluye ese espacio gráfico.
UppercasePlateFormatter transforma escritura/paste conservando selección y composición;
el request normaliza nuevamente. No persiste nuevas credenciales ni datos sensibles.
Menú único Dar de baja, diálogo explícito, AsyncButton, sin retirada optimista.
Después de confirmación se actualiza y refresca la lista. Error/timeout conserva el
vehículo y pide consultar antes de repetir; no declara una baja incierta exitosa.
Sin límites artificiales de vehículos; scroll y reorganización con texto ampliado.

Se requiere desplegar backend/V31 antes de usar la nueva baja desde la APK remota.
Render no se modifica en esta intervención; APK no se instala automáticamente.
La composición aprobada por el usuario tiene prioridad; se reutiliza el Design System
existente sin depender de nuevas consultas Figma ni agregar dependencias.

## Validación final

- Pruebas dirigidas de vehículos/Flyway: 19, sin fallos, errores ni omitidas.
- Suite completa `mvn test`: 423, failures 0, errors 0, skipped 0 (baseline 414).
- PostgreSQL Testcontainers 16.15; Flyway valida V1–V31. Las pruebas de migración
  comprueban normalización, preservación de IDs y rollback ante colisiones.
- Flutter: `dart format --set-exit-if-changed lib test`, sin cambios;
  `flutter analyze`, sin incidencias; `flutter test`, 151 correctos (baseline 132).
- 19 pruebas Flutter nuevas: controles superiores/inferiores, formatter, placa,
  baja/confirmación/conflicto/doble acción, HTTP Bearer/correlation ID, layouts
  320×640, 390×844 y 640×320 con escalado 100%/200% y semántica.
- APK debug DEV compilada con `ENVIRONMENT=dev`,
  `API_BASE_URL=https://simertpi-backend-dev.onrender.com/api/v1`,
  `MAP_SOURCE=osm-dev`. Ruta: `simertpi-citizen-app/build/app/outputs/flutter-apk/app-debug.apk`.
- No instalación ni validación física de esta versión; pendiente tras desplegar
  backend/V31. Sin operaciones sobre Render, staging, commit ni push.
