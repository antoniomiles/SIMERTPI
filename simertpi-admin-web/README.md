# SIMERTPI Admin Web — CP25.3-C

Interfaz CP25.3-B.1 aprobada con autenticación administrativa real, independiente de
Flutter y sin registro público de funcionarios. Angular 22.2.3, Node portable 22.23.3,
TypeScript 6.0.3 y Poppins local; no se agregaron dependencias de producto.

Workspace: C:/Windows/Temp/simertpi-cp25.3-c-admin-auth-20261016.
Backend y guía completa: ../docs/CP25.3-C_ADMIN_AUTH.md.
Bootstrap no ejecutado: ../docs/DEV-ADMIN-BOOTSTRAP.md.
Publicación futura, pendiente de autorización: ../docs/CP25.3-C_DEV_PUBLICATION.md.

## Revisar la interfaz sin sesión

```powershell
.\scripts\portal.cmd run preview -- --port 4202
```

Abrir http://127.0.0.1:4202/login. Esta configuración deshabilita HTTP administrativo,
no simula autenticación y no abre /admin. Conserva menú, preferencia visual y avisos.

## Validación reproducible

```powershell
.\scripts\portal.cmd ci
.\scripts\portal.cmd run build
.\scripts\portal.cmd test
.\scripts\portal.cmd run check:source
.\scripts\portal.cmd run test:e2e
.\scripts\portal.cmd run test:auth
```

La prueba real requiere Java 21, Maven, Docker y OpenSSL (Git for Windows en este
entorno). Arranca un backend de test y PostgreSQL 16 desechable, con fixtures solo de
test. Nunca usa Render. HTTPS local usa un certificado efímero ignorado con confianza
limitada al proceso y pin SPKI en Chrome; no se desactiva CSRF ni Secure. Los puertos
8089/4443 y 4300/4301 deben estar libres. Las pruebas cierran sus propios servidores.
No ejecutar fixtures o scripts de pruebas en DEV ni añadirlos al artefacto de producción.

En ejecución real /api/v1 es relativo al origen HTTPS del portal; requiere proxy acordado.
El script start:https es auxiliar para backend local en 8089 y certificado de QA generado;
no es configuración de despliegue ni proporciona usuarios fuera del harness aislado.
No guardar credenciales o tokens en almacenamiento web. La única preferencia local es
simertpi.admin.ui.sidebar. El formulario de personal sigue sin crear cuentas.

La sesión se restaura con /admin/auth/me, no se persiste en Angular y expira según el
servidor. ReturnUrl solo admite rutas internas conocidas. Un cierre de sesión fallido
muestra error y no afirma haber revocado la sesión.

Los docs CP25.3-B/B.1 conservados son históricos; las capacidades vigentes de esta CP
se documentan en ../docs/CP25.3-C_ADMIN_AUTH.md y su informe de validación.
