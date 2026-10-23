import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const previews = [
  '/preview/dashboard',
  '/preview/map',
  '/preview/personal',
  '/preview/audit',
  '/preview/zones',
  '/preview/tariffs',
  '/preview/tariffs/billing-days',
  '/preview/tariffs/holidays',
  '/preview/tariffs/hours',
  '/preview/tariffs/history',
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
  for (const path of ['/admin/dashboard', '/admin/personal', '/admin/map', '/admin/violations', '/admin/access', '/admin/tariffs', '/admin/tariffs/billing-days']) {
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
  await expect(page.getByRole('heading', { name: 'Configuración de tarifas' })).toBeVisible();
  await expect(page.getByText('Esta vista no consulta ni modifica datos.')).toBeVisible();
  expect(requests).toEqual([]);
});

test('tariffs navigation has the five exact Spanish options, descriptions, active state, pending views and history', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/preview/tariffs');
  const menu = page.getByRole('button', { name: 'Tarifas y horarios' });
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  const labels = [
    'Configuración de tarifas', 'Días de cobro', 'Días especiales y feriados', 'Horarios de cobro', 'Historial de cambios',
  ];
  await expect(page.locator('.nav-subitem')).toHaveCount(5);
  for (const label of labels) await expect(page.getByRole('link', { name: new RegExp(label) })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Configuración de tarifas', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('.nav-subitem small')).toHaveText([
    'Tarifa general y excepciones por zona.', 'Calendario semanal general y por zona.', 'Fechas especiales y excepciones.',
    'Horarios generales y excepciones.', 'Versiones, vigencias y responsables.',
  ]);
  await page.getByRole('button', { name: 'Contraer menú lateral' }).click();
  await expect(page.locator('.nav-subitem')).toHaveCount(0);
  await page.getByRole('button', { name: 'Tarifas y horarios' }).click();
  await expect(page.locator('.nav-subitem')).toHaveCount(5);
  await expect(page.getByRole('link', { name: /Días especiales y feriados/ })).toHaveAttribute('href', '/preview/tariffs/holidays');
  await page.getByRole('link', { name: /Días especiales y feriados/ }).click();
  await expect(page).toHaveURL(/\/preview\/tariffs\/holidays$/);
  await expect(page.getByRole('heading', { name: 'Días especiales y feriados' })).toBeVisible();
  await expect(page.locator('.card > .tag')).toHaveText('Pendiente');
  await expect(page.getByRole('heading', { name: 'Funcionalidad pendiente' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tarifas y horarios' })).toHaveAttribute('aria-expanded', 'true');
});

test('tariff history preview stays empty and does not call administrative history APIs', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => { if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url()); });
  await page.goto('/preview/tariffs/history');
  await expect(page.getByRole('heading', { name: 'Historial de cambios de tarifas' })).toBeVisible();
  await expect(page.locator('.preview-note')).toContainText('no consulta ni modifica datos');
  await expect(page.getByRole('heading', { name: 'No hay cambios registrados' })).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('tariff history remains a separate responsive screen and opens complete change details accessibly', async ({ page }) => {
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const history = {
    items: [{ id: 'history-event-1', tariffId: 'tariff-version-2', previousTariffId: 'tariff-version-1', action: 'REVISED',
      actorId: 'admin-1', actorName: 'admin.fixture', occurredAt: '2026-10-22T15:00:00Z', effectiveAt: '2027-01-01T05:00:00Z',
      reason: 'Corrección aprobada', beforeState: { id: 'tariff-version-1', versionGroupId: 'series-1', rowVersion: 0,
        code: 'GEN-01', name: 'Tarifa general', zoneId: null, currency: 'USD', amount: 0.25, durationMinutes: 60,
        minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 5, roundingMode: 'HALF_UP',
        validFrom: '2026-01-01T05:00:00Z', validTo: null, normativeReference: 'Ordenanza anterior', active: true,
        cancelledAt: null, cancelledByUserId: null, cancellationReason: null },
      afterState: { id: 'tariff-version-2', versionGroupId: 'series-1', previousVersionId: 'tariff-version-1', rowVersion: 0,
        code: 'GEN-01-V2', name: 'Tarifa general revisada', zoneId: null, currency: 'USD', amount: 0.3, durationMinutes: 45,
        minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 5, roundingMode: 'HALF_UP',
        validFrom: '2027-01-01T05:00:00Z', validTo: null, normativeReference: 'Ordenanza nueva', active: true,
        cancelledAt: null, cancelledByUserId: null, cancellationReason: null } }], page: 0, size: 25, total: 1,
  };
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/parking/tariffs/history**', (route) => route.fulfill({ json: history }));
  await page.goto('http://127.0.0.1:4301/admin/tariffs/history?tariffId=tariff-version-1');
  await expect(page.getByRole('heading', { name: 'Historial de cambios de tarifas' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Volver a tarifas' })).toBeVisible();
  await expect(page.locator('.history-series')).toContainText('GEN-01-V2 · Tarifa general revisada');
  await expect(page.locator('.history-table')).toContainText('admin.fixture');
  await expect(page.locator('.history-table')).toContainText('Corrección aprobada');
  await expect(page.locator('.history-table')).toContainText('Importe');
  await expect(page.locator('.history-table')).toContainText('0,25 USD');

  await page.setViewportSize({ width: 390, height: 844 });
  const historyRegion = page.getByRole('region', { name: /Registros del historial/ });
  await expect(historyRegion).toBeVisible();
  expect(await historyRegion.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'Ver detalle' }).click();
  const detail = page.getByRole('dialog', { name: 'Detalle del cambio' });
  await expect(detail).toBeVisible();
  await expect(detail).toContainText('Valores anteriores');
  await expect(detail).toContainText('Valores registrados después del cambio');
  await expect(detail).toContainText('Ordenanza anterior');
  await expect(detail).toContainText('Ordenanza nueva');
  await expect(detail).toContainText('1 de enero de 2027');
  const a11y = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(a11y.violations.map((item) => item.id)).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(detail).toBeHidden();
});

test('billing calendar preview states that unselected days are free and does not call the API', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => { if (['fetch', 'xhr'].includes(request.resourceType())) requests.push(request.url()); });
  await page.goto('/preview/tariffs/billing-days');
  await expect(page.locator('app-page-heading h1')).toHaveText('Días de cobro', { timeout: 15000 });
  await expect(page.locator('#main-content')).toContainText('no consulta ni modifica datos');
  expect(requests).toEqual([]);
});

test('billing calendar scheduled lifecycle edits, cancels with a reason and confirms only after backend success', async ({ page }) => {
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  let calendar: Record<string, any> = {
    id: 'calendar-e2e', zoneId: null, zoneCode: null, zoneName: null,
    validFrom: new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10), validTo: null,
    normativeReference: 'Ordenanza de prueba local', active: true, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    rowVersion: 3, versionGroupId: 'calendar-series-e2e', previousCalendarId: null,
    cancelledAt: null, cancelledByUserId: null, cancellationReason: null, cancelledByUsername: null,
    days: { '1': true, '2': true, '3': false, '4': true, '5': true, '6': false, '7': false },
  };
  let updateBody: Record<string, unknown> | null = null;
  let cancelBody: Record<string, unknown> | null = null;
  let updateCount = 0;
  let cancelCount = 0;
  let finishUpdate!: () => void;
  let updatePending = new Promise<void>((resolve) => { finishUpdate = resolve; });

  await page.addInitScript(() => {
    const nativeCookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => `${nativeCookie?.get?.call(document) ?? ''}; __Host-SIMERTPI_ADMIN_XSRF=calendar-csrf-fixture`,
      set: (value: string) => nativeCookie?.set?.call(document, value),
    });
  });
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/auth/csrf', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/admin/parking/billing-day-calendars', (route) => route.fulfill({ json: [calendar] }));
  await page.route('**/api/v1/admin/parking/billing-day-calendars/calendar-e2e', async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback();
    updateCount++;
    updateBody = route.request().postDataJSON();
    await updatePending;
    calendar = { ...calendar, ...updateBody, rowVersion: calendar.rowVersion + 1, updatedAt: new Date().toISOString() };
    return route.fulfill({ status: 200, json: calendar });
  });
  await page.route('**/api/v1/admin/parking/billing-day-calendars/calendar-e2e/cancel', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    cancelCount++;
    cancelBody = route.request().postDataJSON();
    calendar = { ...calendar, active: false, cancelledAt: new Date().toISOString(), cancellationReason: cancelBody.reason as string, rowVersion: calendar.rowVersion + 1 };
    return route.fulfill({ status: 200, json: calendar });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: {
    at: new Date().toISOString(), generalTariff: null, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [],
  } }));
  await page.goto('http://127.0.0.1:4301/admin/tariffs/billing-days');
  await expect(page.locator('app-page-heading h1')).toHaveText('Días de cobro', { timeout: 15000 });
  const row = page.locator('.calendar-versions-table tbody tr').filter({ hasText: 'Programado' });
  const actions = row.locator('.calendar-actions .button');
  await expect(actions).toHaveText(['Ver', 'Editar', 'Cancelar programación', 'Historial']);
  const boxes = await actions.evaluateAll((buttons) => buttons.map((button) => {
    const rect = button.getBoundingClientRect();
    return { width: rect.width, height: rect.height, top: rect.top };
  }));
  expect(Math.max(...boxes.map((box) => box.width)) - Math.min(...boxes.map((box) => box.width))).toBeLessThan(1);
  expect(new Set(boxes.map((box) => box.height)).size).toBe(1);
  expect(boxes.slice(1).every((box, index) => box.top >= boxes[index].top + boxes[index].height), JSON.stringify(boxes)).toBe(true);

  await actions.nth(1).click();
  const editor = page.getByRole('dialog', { name: 'Editar calendario programado' });
  await expect(editor).toBeVisible();
  await editor.locator('[formControlName="validFrom"]').fill(new Date(Date.now() + 12 * 86400000).toISOString().slice(0, 10));
  await editor.locator('[formControlName="reason"]').fill('Corrección local de calendario');
  await editor.getByRole('button', { name: 'Guardar cambios' }).click();
  const saving = editor.getByRole('button', { name: 'Guardando cambios…' });
  await expect(saving).toBeDisabled();
  expect(updateCount).toBe(1);
  expect(updateBody).toMatchObject({ expectedVersion: 3, chargeableDays: [1, 2, 4, 5], reason: 'Corrección local de calendario' });
  finishUpdate();
  await expect(editor).toBeHidden();
  await expect(page.locator('.success-note')).toContainText('Calendario actualizado correctamente.');
  await expect(page.locator('.success-note')).toContainText('calendar-e2e');

  const refreshedRow = page.locator('.calendar-versions-table tbody tr').filter({ hasText: 'Programado' });
  await refreshedRow.scrollIntoViewIfNeeded();
  const refreshedButtons = refreshedRow.locator('.calendar-actions .button');
  const cancelButton = refreshedRow.getByRole('button', { name: 'Cancelar programación' });
  await cancelButton.scrollIntoViewIfNeeded();
  const refreshedHitTargets = await refreshedButtons.evaluateAll((buttons) => buttons.map((button) => {
    const rect = button.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return { label: button.textContent?.trim(), left: rect.left, top: rect.top, width: rect.width, height: rect.height,
      hit: hit?.closest('.calendar-actions .button') === button, hitElement: hit?.outerHTML.slice(0, 180) };
  }));
  expect(refreshedHitTargets.every((item) => item.hit), JSON.stringify(refreshedHitTargets)).toBe(true);
  await cancelButton.click();
  const cancelDialog = page.getByRole('dialog', { name: '¿Cancelar este calendario?' });
  await expect(cancelDialog).toBeVisible();
  await cancelDialog.getByRole('button', { name: 'Confirmar cancelación' }).click();
  expect(cancelCount).toBe(0);
  await cancelDialog.locator('[formControlName="reason"]').fill('Programación reemplazada en prueba local');
  await cancelDialog.getByRole('button', { name: 'Confirmar cancelación' }).click();
  await expect.poll(() => cancelCount).toBe(1);
  expect(cancelBody).toMatchObject({ reason: 'Programación reemplazada en prueba local', expectedVersion: 4 });
  await expect(cancelDialog).toBeHidden();
  await expect(page.locator('.success-note')).toContainText('Programación cancelada correctamente.');
  await expect(page.locator('.calendar-versions-table tbody tr')).toContainText('Cancelado');
});

test('tariff editor is opened on demand and sends the exact API decimal using admin CSRF', async ({ page }) => {
  const createBodies: unknown[] = [];
  const csrfHeaders: string[] = [];
  let csrfReads = 0;
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const tariff = {
    id: 'local-tariff', code: 'GEN-2027', name: 'Tarifa general', zoneId: null, zoneCode: null, zoneName: null,
    currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
    gracePeriodMinutes: 0, roundingMode: 'HALF_UP', normativeReference: 'Ordenanza de prueba', active: true,
    validFrom: '2027-01-01T05:00:00Z', validTo: null, createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z',
  };
  const effective = {
    at: new Date().toISOString(), generalTariff: null, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [],
  };
  await page.addInitScript(() => {
    const nativeCookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => `${nativeCookie?.get?.call(document) ?? ''}; __Host-SIMERTPI_ADMIN_XSRF=browser-csrf-fixture`,
      set: (value: string) => nativeCookie?.set?.call(document, value),
    });
  });
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/auth/csrf', async (route) => {
    csrfReads++;
    if (csrfReads === 1) {
      await route.fulfill({ status: 502, body: '{"code":"UPSTREAM_HTTP_RESPONSE"}' });
      return;
    }
    await route.fulfill({ status: 204 });
  });
  await page.route('**/api/v1/admin/parking/tariffs', (route) => {
    if (route.request().method() === 'POST') {
      createBodies.push(route.request().postDataJSON());
      csrfHeaders.push(route.request().headers()['x-xsrf-token'] ?? '');
      return route.fulfill({ status: 201, json: tariff });
    }
    return route.fulfill({ json: [] });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: effective }));
  await page.goto('http://127.0.0.1:4301/admin/tariffs');
  await expect(page.getByRole('heading', { name: 'Configuración de tarifas' })).toBeVisible();
  await expect(page.locator('form')).toHaveCount(0);
  await page.getByRole('button', { name: 'Crear tarifa' }).click();
  await expect(page.getByPlaceholder('Ej.: Tarifa general de estacionamiento')).toBeVisible();
  await expect(page.getByPlaceholder('Ej.: 0,25')).toBeVisible();
  const firstFormRow = await page.locator('.tariff-data-grid > .field').evaluateAll((fields) => fields.slice(0, 3).map((field) => ({
    fieldTop: field.getBoundingClientRect().top,
    controlTop: field.querySelector('input, select')?.getBoundingClientRect().top,
  })));
  expect(new Set(firstFormRow.map((field) => field.fieldTop)).size).toBe(1);
  expect(new Set(firstFormRow.map((field) => field.controlTop)).size).toBe(1);
  await page.locator('[formControlName="code"]').fill('GEN-2027');
  await page.locator('[formControlName="name"]').fill('Tarifa general');
  await page.locator('[formControlName="currency"]').fill('USD');
  await page.locator('[formControlName="amount"]').fill('0,25');
  await page.locator('[formControlName="durationMinutes"]').fill('60');
  await page.locator('[formControlName="minMinutes"]').fill('15');
  await page.locator('[formControlName="maxContinuousMinutes"]').fill('240');
  await page.locator('[formControlName="gracePeriodMinutes"]').fill('0');
  await page.locator('[formControlName="validFrom"]').fill('2027-01-01T00:00');
  await page.locator('[formControlName="normativeReference"]').fill('Ordenanza de prueba');
  await page.getByRole('button', { name: 'Registrar tarifa' }).click();
  await expect.poll(() => createBodies.length).toBe(1);
  expect(csrfReads).toBe(2);
  expect(csrfHeaders).toEqual(['browser-csrf-fixture']);
  expect(createBodies[0]).toMatchObject({ amount: '0.25', currency: 'USD', zoneId: null, code: 'GEN-2027' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('scheduled tariff actions are aligned and view/edit use accessible responsive dialogs', async ({ page }) => {
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const scheduled = {
    id: 'scheduled-visual-1', code: 'GEN-FUT-01', name: 'Tarifa programada', zoneId: null, zoneCode: null, zoneName: null,
    currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
    gracePeriodMinutes: 5, roundingMode: 'HALF_UP', normativeReference: 'Ordenanza local de prueba', active: true,
    validFrom: new Date(Date.now() + 86400000).toISOString(), validTo: null,
    rowVersion: 0, versionGroupId: 'scheduled-visual-series', previousVersionId: null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), cancelledAt: null,
    cancelledByUserId: null, cancellationReason: null, cancelledByUsername: null,
  };
  let updatedBody: Record<string, unknown> | null = null;
  let updateCount = 0;
  await page.addInitScript(() => {
    const nativeCookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => `${nativeCookie?.get?.call(document) ?? ''}; __Host-SIMERTPI_ADMIN_XSRF=visual-csrf-fixture`,
      set: (value: string) => nativeCookie?.set?.call(document, value),
    });
  });
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/auth/csrf', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/admin/parking/tariffs', (route) => route.fulfill({ json: [scheduled] }));
  await page.route('**/api/v1/admin/parking/tariffs/scheduled-visual-1', async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback();
    updateCount++;
    updatedBody = route.request().postDataJSON();
    return route.fulfill({ status: 200, json: { ...scheduled, ...updatedBody } });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: {
    at: new Date().toISOString(), generalTariff: null, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [],
  } }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('http://127.0.0.1:4301/admin/tariffs');
  await expect(page.getByRole('heading', { name: 'Configuración de tarifas' })).toBeVisible();

  const actions = page.locator('.row-actions .button');
  await expect(actions).toHaveText(['Ver', 'Editar', 'Cancelar programación', 'Historial']);
  const actionBoxes = await actions.evaluateAll((buttons) => buttons.map((button) => {
    const rect = button.getBoundingClientRect();
    return { width: rect.width, top: rect.top, height: rect.height };
  }));
  expect(Math.max(...actionBoxes.map((box) => box.width)) - Math.min(...actionBoxes.map((box) => box.width))).toBeLessThan(1);
  expect(actionBoxes[0].top).toBeLessThan(actionBoxes[1].top);
  expect(actionBoxes[1].top).toBeLessThan(actionBoxes[2].top);
  expect(new Set(actionBoxes.map((box) => box.height)).size).toBe(1);

  await actions.nth(0).click();
  const detail = page.getByRole('dialog', { name: 'Detalle de tarifa' });
  await expect(detail).toBeVisible();
  for (const label of ['Código', 'Nombre', 'Estado', 'Aplicación', 'Importe', 'Duración por importe', 'Mínimo facturable', 'Máximo continuo', 'Tolerancia', 'Regla de redondeo', 'Vigente desde (hora de Ecuador)', 'Referencia normativa']) {
    await expect(detail.getByText(label, { exact: true })).toBeVisible();
  }
  const detailA11y = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(detailA11y.violations.map((item) => item.id)).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(detail).toBeHidden();
  await expect(actions.nth(0)).toBeFocused();
  await actions.nth(0).click();
  await expect(detail).toBeVisible();
  await detail.getByRole('button', { name: 'Cerrar', exact: true }).click();
  await expect(detail).toBeHidden();

  await page.setViewportSize({ width: 390, height: 844 });
  await actions.nth(1).click();
  const editor = page.getByRole('dialog', { name: 'Editar tarifa programada' });
  await expect(editor).toBeVisible();
  const box = await editor.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  const editorShell = editor.locator('.dialog-shell');
  expect(await editorShell.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  await expect(editor.locator('[formControlName="amount"]')).toHaveValue('0,25');
  await editor.locator('[formControlName="amount"]').fill('0,50');
  await page.keyboard.press('Escape');
  await expect(editor.getByRole('alertdialog')).toContainText('Hay cambios sin guardar');
  await editor.getByRole('button', { name: 'Seguir editando' }).click();
  await editor.locator('[formControlName="amount"]').fill('0,75');
  await editor.locator('[formControlName="reason"]').fill('Corrección de prueba local');
  await editor.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect.poll(() => updateCount).toBe(1);
  expect(updatedBody).toMatchObject({ amount: '0.75' });
  await expect(editor).toBeHidden();
  await expect(page.locator('.success-note')).toContainText('La tarifa programada se actualizó correctamente.');
});

test('current tariff edits create one future version and early finalization requires an audited reason', async ({ page }) => {
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const current = {
    id: 'current-visual-1', code: 'GEN-CURRENT-01', name: 'Tarifa general vigente', zoneId: null, zoneCode: null, zoneName: null,
    currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
    gracePeriodMinutes: 5, roundingMode: 'HALF_UP', normativeReference: 'Ordenanza local de prueba', active: true,
    validFrom: new Date(Date.now() - 86400000).toISOString(), validTo: null, rowVersion: 4,
    versionGroupId: 'current-series', previousVersionId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  };
  let rows: Record<string, unknown>[] = [current];
  let revisionBody: Record<string, unknown> | null = null;
  let endBody: Record<string, unknown> | null = null;
  let revisionCount = 0;
  let endCount = 0;
  await page.addInitScript(() => {
    const nativeCookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => `${nativeCookie?.get?.call(document) ?? ''}; __Host-SIMERTPI_ADMIN_XSRF=current-csrf-fixture`,
      set: (value: string) => nativeCookie?.set?.call(document, value),
    });
  });
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/auth/csrf', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/admin/parking/tariffs', (route) => route.fulfill({ json: rows }));
  await page.route('**/api/v1/admin/parking/tariffs/current-visual-1/revise', async (route) => {
    revisionCount++;
    revisionBody = route.request().postDataJSON();
    const body = revisionBody as Record<string, unknown>;
    const replacement = { ...current, id: 'current-visual-2', code: 'GEN-CURRENT-02', amount: 0.30, rowVersion: 0,
      previousVersionId: current.id, validFrom: body['validFrom'] as string };
    rows = [{ ...current, rowVersion: 5, validTo: new Date(Date.parse(replacement.validFrom) - 1000).toISOString() }, replacement];
    return route.fulfill({ status: 200, json: replacement });
  });
  await page.route('**/api/v1/admin/parking/tariffs/current-visual-1/end', async (route) => {
    endCount++;
    endBody = route.request().postDataJSON();
    return route.fulfill({ status: 200, json: { ...current, validTo: (endBody as Record<string, unknown>)['effectiveAt'] } });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: {
    at: new Date().toISOString(), generalTariff: current, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [],
  } }));

  await page.goto('http://127.0.0.1:4301/admin/tariffs');
  const row = page.getByRole('row').filter({ hasText: 'GEN-CURRENT-01' });
  await expect(row.getByText('Vigente', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: 'Editar' }).click();
  const editor = page.getByRole('dialog', { name: 'Editar tarifa vigente · crear versión' });
  await expect(editor).toBeVisible();
  await expect(editor.getByText(/No se sobrescribe el historial/)).toBeVisible();
  await editor.locator('[formControlName="amount"]').fill('0,30');
  await editor.locator('[formControlName="validFrom"]').fill(new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 16));
  await editor.locator('[formControlName="reason"]').fill('Corrección respaldada en prueba local');
  await editor.getByRole('button', { name: 'Guardar nueva versión' }).click();
  await expect.poll(() => revisionCount).toBe(1);
  expect(revisionBody).toMatchObject({ amount: '0.30', expectedVersion: 4, reason: 'Corrección respaldada en prueba local' });
  await expect(page.locator('.success-note')).toContainText('Tarifa actualizada correctamente. Se creó una nueva versión y se conservó el historial.');

  await row.getByRole('button', { name: 'Finalizar vigencia' }).click();
  const endDialog = page.getByRole('dialog', { name: 'Finalizar vigencia de GEN-CURRENT-01' });
  await expect(endDialog).toBeVisible();
  await expect(endDialog).toContainText('Si no existe una versión de reemplazo');
  await endDialog.locator('[formControlName="validTo"]').fill(new Date(Date.now() + 24 * 3600000).toISOString().slice(0, 16));
  await endDialog.locator('[formControlName="reason"]').fill('Finalización anticipada de prueba local');
  await endDialog.getByRole('button', { name: 'Programar finalización' }).click();
  await expect.poll(() => endCount).toBe(1);
  expect(endBody).toMatchObject({ reason: 'Finalización anticipada de prueba local', expectedVersion: 5 });
  expect(revisionCount).toBe(1);
});

test('current tariff supports immediate revision and immediate ending with an uncovered-zone warning', async ({ page }) => {
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const current = {
    id: 'current-now-1', code: 'GEN-NOW-01', name: 'Tarifa general vigente', zoneId: null, zoneCode: null, zoneName: null,
    currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
    gracePeriodMinutes: 5, roundingMode: 'HALF_UP', normativeReference: 'Referencia local', active: true,
    validFrom: new Date(Date.now() - 86400000).toISOString(), validTo: null, rowVersion: 3,
    versionGroupId: 'current-now-series', previousVersionId: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  };
  let rows: Record<string, unknown>[] = [current];
  let revisionBody: Record<string, unknown> | null = null;
  let endBody: Record<string, unknown> | null = null;
  let currentForEffective = current;
  await page.addInitScript(() => {
    const nativeCookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => `${nativeCookie?.get?.call(document) ?? ''}; __Host-SIMERTPI_ADMIN_XSRF=current-csrf-fixture`,
      set: (value: string) => nativeCookie?.set?.call(document, value),
    });
  });
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/auth/csrf', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/admin/parking/tariffs', (route) => route.fulfill({ json: rows }));
  await page.route('**/api/v1/admin/parking/tariffs/current-now-1/revise-now', async (route) => {
    revisionBody = route.request().postDataJSON();
    const now = new Date().toISOString();
    const replacement = { ...current, id: 'current-now-2', code: 'GEN-NOW-02', amount: 0.30, rowVersion: 0,
      previousVersionId: current.id, validFrom: now };
    currentForEffective = replacement;
    rows = [{ ...current, validTo: new Date(Date.parse(now) - 1).toISOString(), rowVersion: 4 }, replacement];
    return route.fulfill({ status: 200, json: replacement });
  });
  await page.route('**/api/v1/admin/parking/tariffs/current-now-2/end-now', async (route) => {
    endBody = route.request().postDataJSON();
    const ended = { ...currentForEffective, validTo: new Date().toISOString() };
    rows = [...rows.filter((row) => row['id'] !== 'current-now-2'), ended];
    return route.fulfill({ status: 200, json: ended });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: {
    at: new Date().toISOString(), generalTariff: currentForEffective, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null,
    zones: [{ zoneId: 'zone-real-id-1', zoneCode: 'Z-01', zoneName: 'Centro', tariff: currentForEffective,
      tariffSource: 'GENERAL', billingCalendarId: null, chargeable: true, billingDaysConfigured: false,
      billingDaysSource: 'LEGACY_UNCONFIGURED', billingDaysNormativeReference: null }],
  } }));

  await page.goto('http://127.0.0.1:4301/admin/tariffs');
  const row = page.getByRole('row').filter({ hasText: 'GEN-NOW-01' });
  await row.getByRole('button', { name: 'Editar' }).click();
  const editor = page.getByRole('dialog', { name: 'Editar tarifa vigente · crear versión' });
  await editor.getByLabel('Aplicar ahora').check();
  await editor.locator('[formControlName="amount"]').fill('0,30');
  await editor.locator('[formControlName="reason"]').fill('Corrección inmediata respaldada');
  await editor.getByRole('button', { name: 'Guardar nueva versión' }).click();
  await expect(page.locator('.success-note')).toContainText('las sesiones ya iniciadas conservan su tarifa anterior.');
  expect(revisionBody).not.toHaveProperty('validFrom');
  expect(revisionBody).toMatchObject({ amount: '0.30', expectedVersion: 3, reason: 'Corrección inmediata respaldada' });

  const replacementRow = page.getByRole('row').filter({ hasText: 'GEN-NOW-02' });
  await replacementRow.getByRole('button', { name: 'Finalizar vigencia' }).click();
  const endDialog = page.getByRole('dialog', { name: 'Finalizar vigencia de GEN-NOW-02' });
  await endDialog.getByLabel('Finalizar ahora').check();
  await expect(endDialog).toContainText('estas zonas quedarían sin tarifa aplicable: Z-01 · Centro');
  await endDialog.locator('[formControlName="reason"]').fill('Cese inmediato respaldado');
  await endDialog.getByRole('button', { name: 'Finalizar ahora' }).click();
  expect(endBody).toEqual({ reason: 'Cese inmediato respaldado', expectedVersion: 0 });
});

test('tariff write uses the CSRF cookie issued by its preflight, not the token read before rotation', async ({ page }) => {
  let cookieAndHeaderMatch = false;
  let postCount = 0;
  const identity = {
    userId: 'local-test-admin', username: 'admin.fixture', roles: ['SIMERTPI_ADMIN'], enabled: true,
    sessionExpiresAt: new Date(Date.now() + 600000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 3600000).toISOString(),
  };
  const tariff = {
    id: 'local-tariff-rotation', code: 'ROTATION-2027', name: 'Tarifa de prueba', zoneId: null, zoneCode: null, zoneName: null,
    currency: 'USD', amount: 0.25, durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240,
    gracePeriodMinutes: 0, roundingMode: 'HALF_UP', normativeReference: 'Referencia local de prueba', active: true,
    validFrom: '2027-01-01T05:00:00Z', validTo: null, createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z',
  };
  await page.route('**/api/v1/admin/auth/me', (route) => route.fulfill({ json: identity }));
  let csrfReads = 0;
  await page.route('**/api/v1/admin/auth/csrf', (route) => {
    csrfReads++;
    const value = csrfReads === 1 ? 'initial-fixture' : 'rotated-fixture';
    return route.fulfill({ status: 204, headers: {
      'Set-Cookie': `__Host-SIMERTPI_ADMIN_XSRF=${value}; Path=/; Secure; SameSite=Strict`,
    } });
  });
  await page.route('**/api/v1/admin/parking/tariffs', (route) => {
    if (route.request().method() === 'POST') {
      postCount++;
      const cookie = route.request().headers().cookie ?? '';
      const match = cookie.match(/(?:^|; )__Host-SIMERTPI_ADMIN_XSRF=([^;]*)/);
      cookieAndHeaderMatch = match?.[1] === 'rotated-fixture' &&
        route.request().headers()['x-xsrf-token'] === match?.[1];
      return route.fulfill({ status: cookieAndHeaderMatch ? 201 : 403, json: cookieAndHeaderMatch ? tariff : { status: 403 } });
    }
    return route.fulfill({ json: [] });
  });
  await page.route('**/api/v1/admin/parking/zones', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/admin/parking/configuration/effective*', (route) => route.fulfill({ json: {
    at: new Date().toISOString(), generalTariff: null, generalBillingCalendarId: null, generalChargeable: true,
    generalBillingDaysConfigured: false, generalBillingNormativeReference: null, zones: [],
  } }));
  await page.goto('http://localhost:4301/admin/tariffs');
  await page.evaluate(async () => {
    await fetch('/api/v1/admin/auth/csrf', { credentials: 'include' });
  });
  await expect(page.getByRole('heading', { name: 'Configuración de tarifas' })).toBeVisible();
  await page.getByRole('button', { name: 'Crear tarifa' }).click();
  await page.locator('[formControlName="code"]').fill('ROTATION-2027');
  await page.locator('[formControlName="name"]').fill('Tarifa de prueba');
  await page.locator('[formControlName="currency"]').fill('USD');
  await page.locator('[formControlName="amount"]').fill('0,25');
  await page.locator('[formControlName="durationMinutes"]').fill('60');
  await page.locator('[formControlName="minMinutes"]').fill('15');
  await page.locator('[formControlName="maxContinuousMinutes"]').fill('240');
  await page.locator('[formControlName="gracePeriodMinutes"]').fill('0');
  await page.locator('[formControlName="validFrom"]').fill('2027-01-01T00:00');
  await page.locator('[formControlName="normativeReference"]').fill('Referencia local de prueba');
  await page.getByRole('button', { name: 'Registrar tarifa' }).click();
  await expect.poll(() => postCount).toBe(1);
  expect(cookieAndHeaderMatch).toBe(true);
  expect(postCount).toBe(1);
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
