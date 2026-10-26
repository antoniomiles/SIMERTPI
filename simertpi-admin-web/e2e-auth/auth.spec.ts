import { expect,test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
const password='chrome-isolated-fixture-2026';
async function login(page: import('@playwright/test').Page,name='qa.admin'){
 await page.goto('/login');await page.getByLabel('Usuario',{exact:true}).fill(name);await page.getByLabel('Contraseña',{exact:true}).fill(password);await page.getByRole('button',{name:'Iniciar sesión'}).click();
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
test('unauthenticated deep link and real expiration return to login',async({page})=>{
 test.setTimeout(7 * 60 * 1000);
 let lastAuthenticatedRequestAt = Date.now();
 page.on('response', response => {
  const path = new URL(response.url()).pathname;
  if (path.startsWith('/api/v1/admin/') && !['/api/v1/admin/auth/csrf','/api/v1/admin/auth/login'].includes(path)) lastAuthenticatedRequestAt = Date.now();
 });
 await page.goto('/admin/personal');await expect(page).toHaveURL(/login\?returnUrl=/);
 const loginResponse = page.waitForResponse(response => response.url().includes('/api/v1/admin/auth/login') && response.request().method()==='POST');
 await page.locator('#username').fill('qa.admin');await page.locator('#password').fill(password);await page.getByRole('button',{name:/Iniciar/}).click();
 const login = await loginResponse;expect(login.status()).toBe(200);
 const identity = await login.json() as {sessionExpiresAt:string};
 const ttlMs = Date.parse(identity.sessionExpiresAt) - Date.now();
 expect(ttlMs).toBeGreaterThan(4 * 60 * 1000);
 expect(ttlMs).toBeLessThanOrEqual(5 * 60 * 1000 + 5_000);
 await expect(page).toHaveURL(/admin\/personal$/);
 await expect(page).toHaveURL(/login\?reason=expired/,{timeout:5 * 60 * 1000 + 10_000});
 await expect(page.getByRole('alert')).toContainText('expirado');
 const backendIdleExpiry = lastAuthenticatedRequestAt + 5 * 60 * 1000 + 1_500;
 if (backendIdleExpiry > Date.now()) await page.waitForTimeout(backendIdleExpiry - Date.now());
 expect((await page.request.get('/api/v1/admin/auth/me')).status()).toBe(401);
});
