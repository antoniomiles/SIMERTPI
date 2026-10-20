import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const previews = [
  '/preview/dashboard',
  '/preview/map',
  '/preview/personal',
  '/preview/audit',
  '/preview/zones',
  '/preview/tariffs',
  '/preview/parking-sessions',
  '/preview/violations',
  '/preview/payments',
  '/preview/reports',
  '/preview/access',
];
test('login refuses access without transmitting credentials or creating session state', async ({
  page,
}) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/login');
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page.getByText('Ingrese su contraseña.', { exact: true })).toBeVisible();
  await page.getByLabel('Usuario', { exact: true }).fill('presentation.review');
  await page.getByLabel('Contraseña', { exact: true }).fill('local-validation-only');
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page.getByRole('alert')).toContainText('No se enviaron las credenciales');
  await expect(page.getByLabel('Contraseña', { exact: true })).toHaveValue('');
  await expect(page).toHaveURL(/\/login$/);
  expect(requests).toEqual([]);
  expect(
    await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })),
  ).toEqual({ local: 0, session: 0 });
  expect(await page.context().cookies()).toEqual([]);
});

test('dashboard preview stays data-free and protected routes redirect to login', async ({
  page,
}) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/dashboard');
  await expect(page.getByRole('heading', { name: /SIMERTPI/ })).toBeVisible();
  await expect(page.locator('.preview-note').getByText('Previsualización local')).toBeVisible();
  await expect(page.locator('.metric-grid')).toHaveCount(0);
  expect(requests).toEqual([]);
  for (const path of ['/admin/dashboard', '/admin/personal', '/admin/map', '/admin/violations', '/admin/access']) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login\?returnUrl=/);
    expect(new URL(page.url()).searchParams.get('returnUrl')).toBe(path);
    await expect(page.getByRole('heading', { name: 'Bienvenido a SIMERTPI' })).toBeVisible();
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/preview/dashboard');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(results.violations.map((item) => item.id)).toEqual([]);
  expect(requests).toEqual([]);
});

test('access preview is disconnected and contains no sample accounts or roles', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/access');
  await expect(page.getByRole('heading', { name: 'Usuarios, roles y permisos' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Previsualización sin datos' })).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('access preview remains accessible and fits a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/preview/access');
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(results.violations.map((item) => item.id)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('map coverage preview does not query the backend or draw sample locations', async ({
  page,
}) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/map');
  await expect(page.getByRole('heading', { name: 'Mapa de estacionamientos' })).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Vista geográfica pendiente de validación municipal' }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Previsualización sin datos' })).toBeVisible();
  await expect(page.locator('[data-map-marker]')).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('map coverage remains accessible and fits a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/preview/map');
  await expect(page.getByRole('heading', { name: 'Mapa de estacionamientos' })).toBeVisible();
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(results.violations.map((item) => item.id)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});

test('violations preview stays local and does not fabricate enforcement records', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/violations');
  await expect(page.getByRole('heading', { name: 'Infracciones' })).toBeVisible();
  await expect(page.locator('#main-content .preview-note').getByText('Previsualización local')).toBeVisible();
  await expect(page.locator('#main-content .preview-note').getByText(/no consulta registros ni muestra datos de ejemplo/i)).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('payments administrative route requires a real admin session', async ({ page }) => {
  await page.goto('/admin/payments');
  await expect(page).toHaveURL(/\/login\?returnUrl=/);
  expect(new URL(page.url()).searchParams.get('returnUrl')).toBe('/admin/payments');
  await expect(page.getByRole('heading', { name: 'Bienvenido a SIMERTPI' })).toBeVisible();
});

test('reports administrative route requires a real admin session', async ({ page }) => {
  await page.goto('/admin/reports');
  await expect(page).toHaveURL(/\/login\?returnUrl=/);
  expect(new URL(page.url()).searchParams.get('returnUrl')).toBe('/admin/reports');
  await expect(page.getByRole('heading', { name: 'Bienvenido a SIMERTPI' })).toBeVisible();
});

test('production configuration denies preview even on loopback', async ({ page }) => {
  await page.goto('http://127.0.0.1:4301/preview/personal');
  await expect(page).toHaveURL('http://127.0.0.1:4301/login');
  await expect(page.getByRole('link', { name: 'Abrir previsualización local' })).toHaveCount(0);
});

test('personnel preview stays local and validates presentation without calling the API', async ({
  page,
}) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/personal');
  await expect(page.getByRole('heading', { name: 'Vista de preparación' })).toBeVisible();
  expect(requests).toEqual([]);
  await page.getByRole('button', { name: 'Revisar alta de inspector' }).click();
  await page.getByRole('button', { name: 'Validar formulario' }).click();
  await expect(page.locator('#inspector-user')).toHaveAttribute('aria-invalid', 'true');
  await page.locator('#inspector-user').fill('presentation.review');
  await page.locator('#first-name').fill('Revisión');
  await page.locator('#last-name').fill('Local');
  await page.locator('#email').fill('review@example.invalid');
  await page.getByRole('button', { name: 'Validar formulario' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Formato válido' })).toContainText(
    'No se creó una cuenta',
  );
  expect(requests).toEqual([]);
  await page.getByRole('button', { name: 'Cancelar', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nuevo inspector municipal' })).toHaveCount(0);
});

test('audit preview does not simulate events or call administrative APIs', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/audit');
  await expect(page.getByRole('heading', { name: 'No se pudo cargar la auditoría' })).toBeVisible();
  expect(requests).toEqual([]);
});

test('zones preview stays local and never calls catalog APIs', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/zones');
  await expect(page.getByRole('heading', { name: 'Zonas, calles y espacios' })).toBeVisible();
  await expect(page.getByText('Esta vista no consulta ni modifica datos.')).toBeVisible();
  expect(requests).toEqual([]);
});

test('tariffs preview stays local and never calls configuration APIs', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/tariffs');
  await expect(page.getByRole('heading', { name: 'Tarifas y horarios' })).toBeVisible();
  await expect(page.getByText('Esta vista no consulta ni modifica datos.')).toBeVisible();
  expect(requests).toEqual([]);
});

test('parking sessions preview stays local and never calls operational APIs', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url());
  });
  await page.goto('/preview/parking-sessions');
  await expect(page.getByRole('heading', { name: 'Estacionamientos y sesiones' })).toBeVisible();
  await expect(page.getByText('Esta vista no consulta ni modifica datos.')).toBeVisible();
  expect(requests).toEqual([]);
});

test('tablet menu supports keyboard, escape and hidden navigation isolation', async ({ page }) => {
  await page.setViewportSize({ width: 820, height: 1180 });
  await page.goto('/preview/dashboard');
  const menu = page.locator('button[aria-controls="sidebar"]');
  await expect(page.locator('#sidebar')).toHaveAttribute('inert', '');
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('.main-column')).toHaveAttribute('inert', '');
  await expect(page.locator('.sidebar nav a').first()).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeFocused();
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#sidebar')).toHaveAttribute('inert', '');
});

test('skip control reaches the main content with keyboard', async ({ page }) => {
  await page.goto('/preview/dashboard');
  const skip = page.getByRole('button', { name: 'Saltar al contenido principal' });
  await skip.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main-content')).toBeFocused();
});

for (const path of ['/login', ...previews]) {
  test(`basic accessibility and desktop capture: ${path}`, async ({ page }) => {
    const runtimeErrors: string[] = [];
    const external: string[] = [];
    page.on('pageerror', (error) => runtimeErrors.push(error.name));
    await page.route('**/*', async (route) => {
      const url = new URL(route.request().url());
      if (!['127.0.0.1', 'localhost'].includes(url.hostname)) {
        external.push(url.origin);
        await route.abort();
      } else await route.continue();
    });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(path);
    await expect(page.locator('h1, h2').first()).toBeVisible();
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();
    expect(
      results.violations.map((item) => ({
        id: item.id,
        nodes: item.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
    expect(runtimeErrors).toEqual([]);
    expect(external).toEqual([]);
    await page.screenshot({
      path: `artifacts/${path.replaceAll('/', '-')}-desktop.png`,
      fullPage: true,
    });
    await page.setViewportSize({ width: 820, height: 1180 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: `artifacts/${path.replaceAll('/', '-')}-tablet.png`,
      fullPage: true,
    });
  });
}

test('inspector form accessibility and mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/preview/personal');
  await page.getByRole('button', { name: 'Revisar alta de inspector' }).click();
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(results.violations.map((item) => item.id)).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: 'artifacts/inspector-form-mobile.png', fullPage: true });
});
