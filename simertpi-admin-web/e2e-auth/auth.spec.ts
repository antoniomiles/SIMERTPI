import { expect,test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const password='chrome-isolated-fixture-2026';
async function login(page: import('@playwright/test').Page,name='qa.admin',secret=password){
 await page.goto('/login');await page.getByLabel('Usuario',{exact:true}).fill(name);await page.getByLabel('Contraseña',{exact:true}).fill(secret);await page.getByRole('button',{name:'Iniciar sesión'}).click();
}
test('real cookie login, reload, protected route and logout with no browser credential storage',async({page})=>{
 await login(page);await expect(page).toHaveURL(/admin\/dashboard$/);
 await expect(page.getByText('qa.admin',{exact:true})).toBeVisible();
 const cookies=await page.context().cookies();const session=cookies.find(cookie=>cookie.name==='__Host-SIMERTPI_ADMIN_SESSION');
 expect(!!session&&session.httpOnly&&session.secure&&session.sameSite==='Strict').toBe(true);
 expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
 await page.reload();await expect(page.getByRole('button',{name:'Cerrar sesión',exact:true})).toBeVisible();
 const rejected=await page.request.post('/api/v1/admin/auth/logout',{data:{}});expect(rejected.status()).toBe(403);
 expect((await page.request.get('/api/v1/admin/auth/me')).status()).toBe(200);
 const a11y=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();expect(a11y.violations.map(item=>item.id)).toEqual([]);
 await page.screenshot({path:'artifacts/c-admin-authenticated.png',fullPage:true});
 await page.getByRole('button',{name:'Cerrar sesión',exact:true}).click();await expect(page).toHaveURL(/login$/);
 expect((await page.request.get('/api/v1/admin/auth/me')).status()).toBe(401);
 expect((await page.context().cookies()).some(cookie=>cookie.name==='__Host-SIMERTPI_ADMIN_SESSION')).toBe(false);
});
test('citizen inspector and disabled accounts cannot enter administrator portal',async({page})=>{
 for(const name of ['qa.citizen','qa.inspector','qa.disabled']){await login(page,name);await expect(page.getByRole('alert')).toContainText('cuenta sin acceso');await expect(page).toHaveURL(/login$/);}
});
test('incorrect password and missing csrf are rejected by the real backend',async({page})=>{
 await page.goto('/login');expect((await page.request.post('/api/v1/admin/auth/login',{data:{username:'qa.admin',password:'invalid-fixture'}})).status()).toBe(403);
 await page.getByLabel('Usuario',{exact:true}).fill('qa.admin');await page.getByLabel('Contraseña',{exact:true}).fill('invalid-fixture');await page.getByRole('button',{name:'Iniciar sesión'}).click();await expect(page.getByRole('alert')).toContainText('cuenta sin acceso');
});
test('unsafe redirects are discarded after a real login',async({page})=>{
 await page.goto('/login?returnUrl=https%3A%2F%2Funtrusted.invalid');await page.getByLabel('Usuario',{exact:true}).fill('qa.admin');await page.getByLabel('Contraseña',{exact:true}).fill(password);await page.getByRole('button',{name:'Iniciar sesión'}).click();await expect(page).toHaveURL('https://localhost:4443/admin/dashboard');
 await page.getByRole('button',{name:'Cerrar sesión',exact:true}).click();
});
test('superadministrator invites an administrator and the administrator cannot elevate roles', async ({ page }) => {
 test.setTimeout(90_000);
 await login(page, 'qa.it_admin');
 await expect(page).toHaveURL(/admin\/dashboard$/);
 await expect(page.getByText('Superadministrador', { exact: true })).toBeVisible();
 await page.goto('/admin/access');
 await expect(page.getByRole('heading', { name: 'Usuarios, roles y permisos' })).toBeVisible();
 await page.getByRole('button', { name: 'Crear cuenta', exact: true }).first().click();
 const invite = page.locator('.access-dialog');
 await invite.locator('[formControlName="username"]').fill('qa.created.admin');
 await invite.locator('[formControlName="email"]').fill('qa.created.admin@example.invalid');
 await invite.locator('[formControlName="firstName"]').fill('Cuenta');
 await invite.locator('[formControlName="lastName"]').fill('Administradora E2E');
 await invite.getByRole('checkbox', { name: /^Administrador SIMERTPI\b/ }).check();
 await invite.locator('[formControlName="reason"]').fill('Prueba E2E de aprovisionamiento administrativo');
 const createdResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
   && new URL(response.url()).pathname === '/api/v1/admin/access/users');
 await invite.getByRole('button', { name: 'Crear cuenta', exact: true }).click();
 const createdResponse = await createdResponsePromise;
 expect(createdResponse.status()).toBe(201);
 const mailboxPath = resolve(process.cwd(), '../simertpi-backend/target/admin-browser-mailbox.tsv');
 await expect.poll(async () => {
   try { return (await readFile(mailboxPath, 'utf8')).split(/\r?\n/).some(line => line.startsWith('qa.created.admin\t')); }
   catch { return false; }
 }, { message: 'La invitación aceptada por el proveedor de prueba debe aparecer en la bandeja aislada' }).toBe(true);
 const invitation = (await readFile(mailboxPath, 'utf8')).split(/\r?\n/)
   .find(line => line.startsWith('qa.created.admin\t'))?.split('\t')[1];
 expect(invitation).toBeTruthy();
 const activationToken = new URL(invitation!).hash.match(/^#token=([A-Za-z0-9_-]{43})$/)?.[1];
 expect(activationToken, 'El correo de prueba debe contener un token con el formato seguro esperado').toBeTruthy();
 const apiValidation = await page.request.post('/api/v1/municipal/auth/activation/validate', { data: { activationToken } });
 expect(apiValidation.status()).toBe(200);
 expect(await apiValidation.json()).toMatchObject({ status: 'VALID' });
 const createdLookup = await page.request.get('/api/v1/admin/access/users?q=qa.created.admin');
 expect(createdLookup.status()).toBe(200);
 const createdDirectory = await createdLookup.json() as { content: Array<{ id: string; username: string; roles: string[]; activationPending: boolean }> };
 expect(createdDirectory.content).toHaveLength(1);
 const createdAccount = createdDirectory.content[0];
 expect(createdAccount).toMatchObject({ username: 'qa.created.admin', roles: ['SIMERTPI_ADMIN'], activationPending: true });

 const activationPage = await page.context().newPage();
 const validationResponsePromise = activationPage.waitForResponse(response => response.request().method() === 'POST'
   && new URL(response.url()).pathname === '/api/v1/municipal/auth/activation/validate', { timeout: 10_000 });
 await activationPage.goto(invitation!);
 const validationResponse = await validationResponsePromise;
 expect(validationResponse.status(), 'La API de activación debe aceptar el token tomado del correo de prueba').toBe(200);
 expect(await validationResponse.json()).toMatchObject({ status: 'VALID' });
 await expect(activationPage.locator('#new-password')).toBeVisible();
 await activationPage.getByLabel('Nueva contraseña').fill('qa-created-admin-password-2026');
 await activationPage.getByLabel('Confirmar contraseña').fill('qa-created-admin-password-2026');
 const activationResponse = activationPage.waitForResponse(response => response.request().method() === 'POST'
   && new URL(response.url()).pathname === '/api/v1/municipal/auth/activate');
 await activationPage.getByRole('button', { name: 'Activar cuenta' }).click();
 expect((await activationResponse).status()).toBe(204);
 await expect(activationPage.getByRole('status')).toContainText('La cuenta se activó correctamente');
 await activationPage.close();
 await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();

 await login(page, 'qa.created.admin', 'qa-created-admin-password-2026');
 await expect(page).toHaveURL(/admin\/dashboard$/);
 await expect(page.getByText('Administrador', { exact: true })).toBeVisible();
 await expect(page.getByRole('link', { name: 'Usuarios, roles y permisos', exact: true })).toBeVisible();
 await page.goto('/admin/access');
 await expect(page.getByRole('button', { name: 'Crear inspector', exact: true })).toBeVisible();
 await expect(page.getByRole('button', { name: 'Crear cuenta', exact: true })).toHaveCount(0);
 await expect(page.getByRole('tab', { name: /Catálogo de roles/ })).toHaveCount(0);
 expect((await page.request.get('/api/v1/admin/access/roles')).status()).toBe(403);
 expect((await page.request.get('/api/v1/admin/access/permissions')).status()).toBe(403);

 const inspectorResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
   && new URL(response.url()).pathname === '/api/v1/admin/access/users');
 await page.getByRole('button', { name: 'Crear inspector', exact: true }).click();
 const inspectorDialog = page.locator('.access-dialog');
 await inspectorDialog.locator('[formControlName="username"]').fill('qa.created.inspector');
 await inspectorDialog.locator('[formControlName="email"]').fill('qa.created.inspector@example.invalid');
 await inspectorDialog.locator('[formControlName="firstName"]').fill('Inspector');
 await inspectorDialog.locator('[formControlName="lastName"]').fill('E2E');
 await inspectorDialog.locator('[formControlName="reason"]').fill('Prueba E2E de alcance del administrador');
 await inspectorDialog.getByRole('button', { name: 'Crear inspector', exact: true }).click();
 const inspectorResponse = await inspectorResponsePromise;
 expect(inspectorResponse.status()).toBe(201);
 const inspectorLookup = await page.request.get('/api/v1/admin/access/users?q=qa.created.inspector');
 expect(inspectorLookup.status()).toBe(200);
 const inspectorDirectory = await inspectorLookup.json() as { content: Array<{ id: string; username: string; updatedAt: string; roles: string[]; activationPending: boolean }> };
 expect(inspectorDirectory.content).toHaveLength(1);
 const inspector = inspectorDirectory.content[0];
 expect(inspector).toMatchObject({ username: 'qa.created.inspector', roles: ['INSPECTOR'], activationPending: true });
 expect((await page.request.get('/api/v1/admin/auth/csrf')).status()).toBe(204);
 const csrf = (await page.context().cookies()).find(cookie => cookie.name === '__Host-SIMERTPI_ADMIN_XSRF')?.value;
 expect(csrf).toBeTruthy();
 const escalation = await page.request.put(`/api/v1/admin/access/users/${inspector.id}/roles`, {
   headers: { 'X-XSRF-TOKEN': csrf! },
   data: { roles: ['IT_ADMIN'], reason: 'Intento E2E de elevación', updatedAt: inspector.updatedAt },
 });
 expect(escalation.status()).toBe(403);
 const persisted = await page.request.get('/api/v1/admin/access/users?q=qa.created.inspector');
 expect(persisted.status()).toBe(200);
 const directory = await persisted.json() as { content: Array<{ username: string; roles: string[]; activationPending: boolean }> };
 expect(directory.content).toHaveLength(1);
 expect(directory.content[0]).toMatchObject({ username: 'qa.created.inspector', roles: ['INSPECTOR'], activationPending: true });
});
test('unauthenticated deep link and real expiration return to login',async({page})=>{
 const testStartedAt = Date.now();
 test.setTimeout(7 * 60 * 1000);
 let lastAuthenticatedRequestAt = Date.now();
 page.on('response', response => {
  const path = new URL(response.url()).pathname;
  if (path.startsWith('/api/v1/admin/') && response.status() < 400
      && !['/api/v1/admin/auth/csrf','/api/v1/admin/auth/login','/api/v1/admin/auth/logout'].includes(path))
    lastAuthenticatedRequestAt = Date.now();
 });
 await page.goto('/admin/personal');await expect(page).toHaveURL(/login\?returnUrl=/);
 const sessionStartAt = Date.now();
 await page.locator('#username').fill('qa.admin');await page.locator('#password').fill(password);await page.getByRole('button',{name:/Iniciar/}).click();
 await expect(page).toHaveURL(/admin\/personal$/);
 await expect.poll(() => {
  const url = new URL(page.url());
  return url.pathname === '/login' && url.searchParams.get('reason') === 'expired';
 }, {timeout:5 * 60 * 1000 + 10_000}).toBe(true);
 const idleDurationMs = Date.now() - sessionStartAt;
 expect(idleDurationMs).toBeGreaterThan(4 * 60 * 1000 + 45_000);
 expect(idleDurationMs).toBeLessThanOrEqual(5 * 60 * 1000 + 15_000);
 await expect(page.getByRole('alert')).toContainText('expirado');
 expect((await page.request.get('/api/v1/admin/auth/me')).status()).toBe(401);
 const backendIdleExpiry = lastAuthenticatedRequestAt + 5 * 60 * 1000 + 1_500;
 // The browser countdown starts at login; the backend idle window can start
 // later when the last protected page request completes. Bound the test by
 // that observed deadline instead of shortening either real five-minute TTL.
 test.setTimeout(Math.max(test.info().timeout, backendIdleExpiry - testStartedAt + 15_000));
 if (backendIdleExpiry > Date.now()) await page.waitForTimeout(backendIdleExpiry - Date.now());
});
