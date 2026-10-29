# Jerarquía administrativa SIMERTPI

## Roles existentes

| Código persistido | Nombre visible | Alcance |
| --- | --- | --- |
| `IT_ADMIN` | Superadministrador | Acceso al portal y a las funciones administrativas de negocio; puede gestionar cuentas, asignar roles del catálogo y consultar la matriz de capacidades. |
| `SIMERTPI_ADMIN` | Administrador | Operación municipal: tarifas, calendarios, zonas, calles, espacios, inspecciones, reportes e inspectores. Solo puede consultar y administrar cuentas con rol `INSPECTOR`; no puede administrar roles ni cuentas administrativas. |

Los otros roles del catálogo se conservan. Las operaciones de usuario vuelven a comprobar el rol habilitado del actor en backend; las restricciones de Angular solo controlan la presentación. Las rutas no declaradas siguen denegadas.

Una cuenta no puede combinar `IT_ADMIN` y `SIMERTPI_ADMIN`. Un administrador municipal solo puede asignar `INSPECTOR`. El backend controla concurrencia mediante versión optimista y bloqueo transaccional compartido. No permite auto deshabilitación ni auto modificación de roles, y conserva al menos una cuenta habilitada por cada rol administrativo.

## Cuentas e invitaciones

Las nuevas cuentas se crean deshabilitadas y sin contraseña. El backend emite un token aleatorio de activación, almacena únicamente su hash, aplica expiración y uso único, y nunca lo escribe en auditoría. El valor original se devuelve una sola vez con `Cache-Control: no-store`; una invitación administrativa pendiente solo la puede reemitir un Superadministrador, con motivo y control de versión. El Administrador municipal puede invitar inspectores por el flujo existente.

Los cambios de perfil, estado y roles guardan motivo, actor, cuenta objetivo y valores permitidos en auditoría. La consulta de historial aplica una lista explícita de campos y excluye tokens, hashes, contraseñas y secretos.

## Permisos

Los permisos efectivos actualmente se expresan mediante autoridades y reglas de Spring Security en código. `GET /api/v1/admin/access/permissions` presenta una matriz de solo lectura; los endpoints de gestión asignan o revocan roles existentes y no modifican el catálogo ni crean permisos arbitrarios. No existe aún un modelo persistente para editar dinámicamente la definición de privilegios de cada rol. Ese comportamiento requeriría un diseño de autorización independiente y una migración específica; no se simula mediante payloads ni queda habilitado por este checkpoint.

## Primer Superadministrador

No se promueve automáticamente ninguna cuenta y no se asigna `IT_ADMIN` mediante una migración. El procedimiento manual preparado, con preflight TLS y aprobación registrada, está en [DEV-SUPERADMIN-BOOTSTRAP.md](DEV-SUPERADMIN-BOOTSTRAP.md). No se ha ejecutado en DEV ni en producción.
