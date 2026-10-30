import { expect, test } from '@playwright/test';

const password = 'chrome-isolated-fixture-2026';
const submittedSmtpPassword = 'qa-smtp-app-password-not-real';

async function login(page: import('@playwright/test').Page, username: string) {
  await page.goto('/login');
  await page.getByLabel('Usuario', { exact: true }).fill(username);
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
}

test('Superadministrador configura SMTP con persistencia real y Administrador queda denegado', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page, 'qa.it_admin');
  await expect(page).toHaveURL(/admin\/dashboard$/);
  await expect(page.getByText('Superadministrador', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Configuración del sistema', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Correo electrónico', exact: true })).toBeVisible();

  await page.goto('/admin/system/email');
  await expect(page.getByRole('heading', { name: 'Correo electrónico', exact: true })).toBeVisible();
  await expect(page.getByText('Sin configuración activa', { exact: true })).toBeVisible();

  await page.getByLabel('Nombre del remitente').fill('SIMERTPI QA');
  await page.getByLabel('Correo remitente').fill('qa.smtp@example.invalid');
  await page.getByRole('textbox', { name: /Servidor/ }).fill('smtp.gmail.com');
  await page.getByRole('spinbutton', { name: /Puerto/ }).fill('587');
  await page.getByLabel('Usuario SMTP').fill('qa.smtp@example.invalid');
  await page.getByLabel('Contraseña de aplicación').fill(submittedSmtpPassword);
  await page.getByRole('textbox', { name: /Motivo/ }).fill('E2E sintético de configuración SMTP');

  const draftResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === '/api/v1/admin/system/email/draft',
  );
  await page.getByRole('button', { name: 'Guardar borrador', exact: true }).click();
  const draftResponse = await draftResponsePromise;
  expect(draftResponse.status()).toBe(200);
  // Read persisted state through an independent API request. The component reloads
  // the settings snapshot immediately after saving, which can navigate/reload the
  // browser response before Chromium exposes the original response body to Playwright.
  const draftSnapshotResponse = await page.request.get('/api/v1/admin/system/email');
  expect(draftSnapshotResponse.status()).toBe(200);
  const savedSnapshot = await draftSnapshotResponse.json() as {
    draft: { version: number; state: string; credentialConfigured: boolean };
  };
  const draft = savedSnapshot.draft;
  expect(draft).toMatchObject({ state: 'DRAFT', credentialConfigured: true });
  expect(JSON.stringify(draft)).not.toContain(submittedSmtpPassword);
  await expect(page.getByRole('status').filter({ hasText: 'Borrador de correo guardado correctamente' }))
    .toContainText('Borrador de correo guardado correctamente');
  await expect(page.getByLabel('Contraseña de aplicación')).toHaveValue('');

  const connectionResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/admin/system/email/test-connection',
  );
  await page.getByRole('button', { name: 'Probar conexión', exact: true }).click();
  const connectionResponse = await connectionResponsePromise;
  expect(connectionResponse.status()).toBe(200);
  await expect(page.getByRole('status').filter({ hasText: 'Conexión SMTP verificada' }))
    .toContainText('Conexión SMTP verificada');

  const emailResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/admin/system/email/test-email',
  );
  await page.getByRole('button', { name: 'Enviar correo de prueba', exact: true }).click();
  const emailResponse = await emailResponsePromise;
  expect(emailResponse.status()).toBe(200);
  await expect(page.getByRole('status').filter({ hasText: 'El proveedor aceptó el correo de prueba' }))
    .toContainText('El proveedor aceptó el correo de prueba');
  // Test-only SmtpTransport captures this synthetic operation; it opens no external SMTP socket.

  await page.getByRole('textbox', { name: /Motivo/ }).fill('E2E sintético: activar SMTP');
  const activateResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/admin/system/email/activate',
  );
  await page.getByRole('button', { name: 'Activar configuración', exact: true }).click();
  const activateResponse = await activateResponsePromise;
  expect(activateResponse.status()).toBe(200);
  await expect(page.getByRole('status').filter({ hasText: 'Configuración de correo activada' }))
    .toContainText('Configuración de correo activada');
  await expect(page.getByText('Configuración activa', { exact: true })).toBeVisible();

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Correo electrónico', exact: true })).toBeVisible();
  await expect(page.getByText('SIMERTPI QA <qa.smtp@example.invalid>')).toBeVisible();
  const response = await page.request.get('/api/v1/admin/system/email');
  expect(response.status()).toBe(200);
  const snapshot = await response.json() as { active: { version: number; credentialConfigured: boolean }; history: Array<{ action: string; result: string }> };
  expect(snapshot.active).toMatchObject({ version: draft.version, credentialConfigured: true });
  expect(snapshot.history.map((entry) => entry.action)).toEqual(expect.arrayContaining([
    'SMTP_DRAFT_SAVED',
    'SMTP_CONNECTION_TESTED',
    'SMTP_TEST_EMAIL_SENT',
    'SMTP_CONFIGURATION_ACTIVATED',
  ]));
  expect(snapshot.history.every((entry) => entry.result === 'SUCCESS')).toBe(true);
  expect(snapshot.history.filter((entry) => ['SMTP_CONNECTION_TESTED', 'SMTP_TEST_EMAIL_SENT', 'SMTP_CONFIGURATION_ACTIVATED'].includes(entry.action))
    .every((entry) => entry.version === draft.version)).toBe(true);
  expect(JSON.stringify(snapshot)).not.toContain(submittedSmtpPassword);
  expect(JSON.stringify(snapshot)).not.toMatch(/credential_(?:nonce|ciphertext)|activationToken/i);

  await page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  await expect(page).toHaveURL(/login$/);
  await login(page, 'qa.admin');
  await expect(page).toHaveURL(/admin\/dashboard$/);
  await expect(page.getByRole('link', { name: 'Correo electrónico', exact: true })).toHaveCount(0);
  expect((await page.request.get('/api/v1/admin/system/email')).status()).toBe(403);
  await page.goto('/admin/system/email');
  await expect(page).toHaveURL(/admin\/dashboard$/);
});
