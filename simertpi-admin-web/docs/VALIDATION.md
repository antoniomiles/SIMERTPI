# CP25.3-B — Registro de validación local

Fecha: 2026-10-16, America/Bogota. Rama base develop, HEAD remoto consultado antes
 de clonar: 07b6ead1c7c0f72aef182b018b57b23a9a5adb3d.
Workspace: C:/Windows/Temp/simertpi-cp25.3-b-admin-web-20261016.

## Resultados finales

| Verificación | Resultado |
| --- | --- |
| npm ci con package-lock.json | PASS: 286 paquetes instalados, 287 auditados, 0 vulnerabilidades reportadas |
| ng build --configuration dev | PASS, configuración DEV sin habilitar API ni preview |
| ng build (production) | PASS, 291.52 kB iniciales; transferencia estimada 80.19 kB |
| ng test --watch=false | PASS: 17 pruebas, 5 archivos |
| playwright test, Chrome instalado | PASS: 10 pruebas, 0 fallos, 0 reintentos |
| check:source | PASS: 29 archivos de aplicación revisados |
| git diff --check con archivos nuevos incluidos mediante intent-to-add | PASS |
| Servidores de prueba | Detenidos; puertos loopback 4200 y 4201 sin listener |

Los reportes se corresponden con la última ejecución después de corregir el foco del
menú y separar los estados vacíos del desplazamiento horizontal de la tabla.
Se corrigieron además la dependencia circular de tipos de entorno y textos UTF-8 de
las pruebas. No quedan fallos conocidos de estas suites.

## Cobertura y límites

Pruebas unitarias: guards y deep links, preview explícito solo en loopback/desarrollo,
validaciones de login y alta, borrado de contraseña, ausencia de sesión autenticada,
cliente HTTP bloqueado y errores genéricos sin cuerpos del servidor.

Chrome: login sin fetch/XHR, cookies ni almacenamiento local/de sesión; protección de
/admin aun dentro de preview; producción rechaza preview en loopback; formulario
sin escritura; estados vacío/carga/error; teclado, foco, Escape y navegación oculta
con inert; capturas responsive. Sin solicitudes externas en las pantallas revisadas.
Las pruebas usan datos de presentación reservados, nunca credenciales operativas.

Axe: 0 infracciones en login, dashboard y personal en escritorio y formulario de
inspector a 390 px, con reglas WCAG 2 A/AA y 2.1 AA, incluyendo contraste de texto.
Se revisaron capturas en 1440 px, 820 px y formulario en 390 px. Sin desbordamiento
horizontal del documento; la tabla tiene su propio desplazamiento. Revisión de
teclado automatizada y capturas inspeccionadas. Esto no certifica conformidad total:
falta comprobación con lectores de pantalla y usuarios municipales.

No se probó autenticación real, CSRF servidor, permisos efectivos, bootstrap, cuentas,
PostgreSQL, Render o APK: esas integraciones no se implementan en CP25.3-B.
El timeout y envío con cookies son preparación detrás del bloqueo HTTP, no controles
validados contra un backend administrativo existente. No hubo commits ni despliegues.

## Dependencias resueltas

Runtime aislado: Node 22.23.3, npm 10.9.9. ZIP oficial verificado con SHA-256 antes
de extracción, bajo ../.local-tools; no modifica herramientas globales.

| Dependencia | Versión resuelta |
| --- | --- |
| @angular/core | 22.2.3 |
| @angular/cli | 22.2.3 |
| @angular/build | 22.2.3 |
| typescript | 6.0.3 |
| rxjs | 7.8.2 |
| tslib | 2.8.1 |
| vitest | 5.0.5 |
| jsdom | 30.1.3 |
| @playwright/test | 1.64.1 |
| @axe-core/playwright | 4.14.0 |
| prettier | 3.9.10 |

TypeScript strict y strictTemplates habilitados. package-lock.json fija el árbol
completo. npm ci se ejecutó después de instalar todas las dependencias de pruebas.

## Artefactos locales ignorados

- artifacts/-login-desktop.png y -login-tablet.png.
- artifacts/-preview-dashboard-desktop.png y -preview-dashboard-tablet.png.
- artifacts/-preview-personal-desktop.png y -preview-personal-tablet.png.
- artifacts/inspector-form-mobile.png.
- playwright-report/index.html y test-results/.
- dist/simertpi-admin-web/browser, salida de producción local sin publicar.

El manifiesto docs/FILES.txt enumera todos los archivos nuevos de fuente/configuración.
Los cambios revisables pertenecen únicamente a simertpi-admin-web; el runtime y la
caché se excluyen mediante .git/info/exclude de esta copia aislada.

## Próxima tarea, pendiente de autorización

Implementar sesión administrativa real y bootstrap auditado del primer SIMERTPI_ADMIN,
según ADR-001 y los contratos propuestos. Validar primero con PostgreSQL aislado.
No activar la API, desplegar ni crear inspector.pruebas.dev desde esta entrega.
