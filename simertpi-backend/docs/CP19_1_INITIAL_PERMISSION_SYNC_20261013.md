# CP19.1 - Sincronizacion del permiso inicial y Perfil

Corregido en la copia aislada fix/cp19.1-vehicle-push-20261013. El permiso Android de Welcome no tenia una activacion automatica vinculada al primer ciudadano autenticado; tampoco existia una marca persistida que diferenciara OFF voluntario de rechazo del permiso.

## Comportamiento verificado

| Caso | Resultado local probado |
|---|---|
| PERMITIR inicial | Al autenticarse, se habilita PUSH mediante el endpoint existente y se registra FCM sin otro dialogo; Perfil ON solo tras registro confirmado. |
| NO PERMITIR inicial | Sin registro ni activacion. Perfil OFF, con permiso/Ajustes disponibles. |
| Conceder en Ajustes | Al regresar se completa la intencion pendiente y Perfil lee la preferencia despues de sincronizar; ON sin segundo toque. |
| OFF voluntario | Marca segura por cuenta, PUT PUSH=false, puerta cerrada antes de la red. Persiste en logout/reinicio/login y no se reactiva por el permiso Android. Si falla guardar OFF, sigue cerrado y reintenta al regresar. |
| Fallo FCM | Registro failed y Perfil OFF/pendiente, aunque el permiso y la preferencia backend sean true. Se reintenta sin otro permiso. |

La autorizacion inicial sin cuenta no puede registrar un destinatario anonimo: se vincula una sola vez al primer propietario autenticado. No se hereda por otras cuentas. Las marcas usan FlutterSecureStorage y el namespace existente de ambiente; clearActive en logout conserva la decision voluntaria. Una preferencia OFF preexistente sin intencion pendiente no se sobrescribe automaticamente (caso legado probado).

Se distingue permiso OS, eleccion voluntaria persistida y registro confirmado. Un retorno correcto de registro no certifica visualizacion fisica.

## Archivos

- `simertpi-citizen-app/lib/core/push/push_lifecycle.dart`
- `simertpi-citizen-app/lib/features/home/home_page.dart`
- `simertpi-citizen-app/lib/features/citizen_activity/profile_page.dart`
- `simertpi-citizen-app/test/push_lifecycle_test.dart`
- `simertpi-citizen-app/test/push_preferences_test.dart`
- `simertpi-citizen-app/test/push_onboarding_home_test.dart`
- `simertpi-citizen-app/test/push_initial_permission_sync_test.dart`

## Validacion

18 casos Flutter focalizados PASS: 11 de sincronizacion inicial (8 unitarios y 3 widgets), 2 de Home y 5 de Preferencias. La prueba anterior de regreso de Ajustes fue adaptada: el segundo toque ahora desactiva, porque la activacion ya es automatica. Se revalido solo ese caso tras la correccion. No se repitieron las 64 pruebas completas ni las suites backend/Android JVM.

```text
flutter test test/push_initial_permission_sync_test.dart test/push_onboarding_home_test.dart test/push_preferences_test.dart --no-pub --reporter expanded
flutter test test/push_preferences_test.dart --no-pub --plain-name "single push switch: settingsReturn" --reporter expanded
dart analyze lib/core/push/push_lifecycle.dart lib/features/home/home_page.dart lib/features/citizen_activity/profile_page.dart test/push_initial_permission_sync_test.dart test/push_onboarding_home_test.dart test/push_preferences_test.dart
git diff --check
```

Ejecucion con dart.exe y flutter_tools.snapshot instalados. Analisis: No issues found; diff check PASS. Evidencia y hashes en CP19_1_INITIAL_PERMISSION_SYNC_VALIDATION_20261013.json. Los registros de 64 pruebas anteriores siguen como evidencia historica; sus hashes Flutter afectados han sido sustituidos por este registro nuevo.

Backend, receptores Android, contratos de campanita, eventos de vehiculos y V40 permanecen intactos respecto a la entrega anterior. No se crea migracion ni endpoint nuevo. PUT de preferencias modifica solo PUSH, conservando otros canales.

Sin commit, push, merge, despliegue, APK, cambios DEV, credenciales ni workspace original. Recepcion real y permiso fisico Android pendientes de la validacion manual autorizada posteriormente.
