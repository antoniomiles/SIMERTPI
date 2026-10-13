# CP19.2 - Correcciones Home ciudadano

Rama local fix/cp19.2-citizen-home-20261013. Base origin/develop 37bb3e29c2d16a13353aa103dcd875c45701ac60. Copia independiente C:/Windows/Temp/simertpi-cp192-home-20261013. Rama diagnostica conservada: diagnose/cp19.2-home-20261013. Diagnostico original conservado en C:/Windows/Temp/SIMERTPI_CP19_2_HOME_DIAGNOSTICO_20261013.md.

## Correcciones

1. Saludo: HomeGreetingController separa idle/loading/loaded/failed, muestra carga pendiente y reintento de nombre, usa firstName.trim y username.trim como respaldo. Cachea el exito por Home y evita lecturas duplicadas. Revision y guardas de sesion invalidan respuestas tardias. Home oculta datos cuando su propietario deja de ser el autenticado.
2. Navegacion: null/error de initialTap no selecciona campanita. Se conservan toques explicitos reales. Revision de navegacion invalida una lectura inicial tardia tras logout incluso con el mismo propietario; el destino pendiente se asocia a su propietario cuando se conoce. Bootstrap limpia tab y destino en transiciones de sesion, preservando una sesion existente al desbloquear. Home no hereda tab2 anterior y no abre campanita como fallback de errores de historial; callbacks de detalle comprueban propietario, revision y mounted.
3. Orden: ACTIVE/EXTENDED primero; startedAt descendente dentro de cada grupo; id ascendente como desempate. EXPIRED/MAX_TIME_REACHED siguen visibles conforme al filtro existente. prioritizeSession marca foco, sin ordenar. Panel usa claves por sesion y destaca la tarjeta enfocada.
4. Recargas: lecturas serializadas, solicitudes concurrentes agrupadas en una recarga pendiente, snapshots obsoletos descartados, publicacion atomica de datos vigentes. Una mutacion en curso invalida una lectura anterior; las recargas esperan hasta terminar busy. Error conserva datos previos como stale. Dispose invalida respuestas y libera esperas locales.

## Alcance y archivos

- `simertpi-citizen-app/lib/app/bootstrap/bootstrap.dart`
- `simertpi-citizen-app/lib/core/push/push_lifecycle.dart`
- `simertpi-citizen-app/lib/features/active_parking/presentation/active_parking_panel.dart`
- `simertpi-citizen-app/lib/features/active_parking/state/active_parking_controller.dart`
- `simertpi-citizen-app/lib/features/home/home_greeting_controller.dart`
- `simertpi-citizen-app/lib/features/home/home_page.dart`
- `simertpi-citizen-app/test/cp192_greeting_test.dart`
- `simertpi-citizen-app/test/cp192_home_support.dart`
- `simertpi-citizen-app/test/cp192_navigation_test.dart`
- `simertpi-citizen-app/test/cp192_parking_order_test.dart`
- `simertpi-citizen-app/test/push_lifecycle_test.dart`

HomeGreetingController es nuevo para aislar cache/revision y permitir pruebas de respuestas tardias sin servicios reales. El cambio en ActiveParkingPanel es indispensable para destacar el foco sin reordenar y mantener identidad de widgets. Las operaciones de pago, cotizacion, extension y cierre mantienen sus cuerpos originales; se modifico solo la coordinacion de lecturas y emision de estado.

La excepcion PUSH se limita a campos/metodos de navegacion y su invalidacion en logout. El resto de logout y los metodos de permisos, preferencias, registro FCM y revocacion permanecen iguales. No hay cambios en Firebase, recepcion nativa, presentacion Android, dispatcher ni campanita.

## Validaciones ejecutadas

31 casos unicos PASS: 8 saludo, 8 orden/recargas, 10 navegacion y 5 regresiones existentes seleccionadas. Analisis Dart focalizado No issues found; git diff --check PASS. No suites completas, backend, Android JVM ni FCM real.

```text
flutter test test/cp192_greeting_test.dart test/cp192_parking_order_test.dart test/cp192_navigation_test.dart --no-pub --reporter expanded
flutter test test/active_parking_test.dart test/auth_widget_test.dart test/push_lifecycle_test.dart --no-pub --name '(Empty and multiple operational|Offline retains last state|Close rejects duplicate tap|Processing locks button|absent or unsupported navigation)' --reporter expanded
flutter test test/cp192_greeting_test.dart test/cp192_navigation_test.dart --no-pub --name '(pending old account|history callback)' --reporter expanded
flutter test test/cp192_navigation_test.dart --no-pub --plain-name 'history callback from previous session cannot navigate after logout' --reporter expanded
```

Comandos ejecutados con dart.exe/flutter_tools.snapshot existentes. Dependencias resueltas desde cache con pub get --offline, sin cambios de pubspec/lock. Analisis de seis archivos productivos y cuatro archivos nuevos de pruebas; posterior analisis de los tests ampliados. Los cinco casos existentes cubren filtro de estados, datos stale, cierre incierto/duplicado, login/stack y navegacion vacia.

Incidencia local: el fixture de historial tardio usaba el contador requests heredado de lecturas generales de actividad. Se separo detailRequests y solo se revalido el caso afectado. No fue un fallo del backend ni una prueba fisica.

## Integracion futura CP19.1

Solapamientos comprobados por inventario de ambas entregas: home_page.dart, push_lifecycle.dart y test/push_lifecycle_test.dart.

Recomendacion: integrar CP19.1 primero cuando se autorice; crear otra copia de integracion basada en el resultado; aplicar CP19.2 por fragmentos, sin reemplazar archivos completos.

- Home: preservar _showPushOnboarding actualizado de CP19.1 y su sincronizacion automatica de permiso inicial. Incorporar saludo, entrada Home, revision y foco de CP19.2. CP19.2 no modifico su propio bloque de onboarding respecto a develop.
- PUSH: conservar los metodos de permisos/preferencias/registro y el OFF voluntario persistido de CP19.1. Incorporar guardas de navegacion, null sin navegacion y revision en logout. En _deliverPendingTarget conservar VEHICLE -> Perfil (tab3) de CP19.1; agregar la guarda de propietario antes de esa rama. PARKING conserva Home/foco. No copiar la version completa CP19.2 que parte de develop y aun no contiene los nuevos eventos de CP19.1.
- Tests: conservar los nuevos fakes/contratos de preferencias y pruebas de VEHICLE_REGISTERED/VEHICLE_DELETED de CP19.1; actualizar solo la expectativa de navegacion vacia a permanecer en la pesta?a actual. Resolver el fixture compartido por metodos, sin revertirlo.
- Tras combinar, ejecutar solamente los casos de navegacion, permiso inicial/interruptor y eventos de vehiculos afectados, junto con los casos CP19.2. No repetir suites completas.

V40 y todos los archivos CP19.1 permanecen sin modificaciones; hashes de su ultima entrega verificados. No se ejecuto una integracion ni se certifica aun la combinacion de ambas ramas. La validacion fisica de la APK resultante requiere autorizacion posterior.

## Estado final

Implementado y validado localmente. Sin commit, push, merge, despliegue, APK ni cambios Render/PostgreSQL. Workspace original no modificado. CP19.1 intacta. Esperar autorizacion para preparar la integracion.
