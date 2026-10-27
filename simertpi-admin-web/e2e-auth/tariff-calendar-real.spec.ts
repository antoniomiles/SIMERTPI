import { expect, test } from '@playwright/test';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { BarcodeFormat, BinaryBitmap, DecodeHintType, HybridBinarizer, MultiFormatReader, RGBLuminanceSource } from '@zxing/library';

const decodeQrImage = async (page: import('@playwright/test').Page, dataUrl: string) => {
  const image = await page.evaluate(async (source) => {
    const element = new Image(); element.src = source; await element.decode();
    const canvas = document.createElement('canvas'); canvas.width = element.naturalWidth; canvas.height = element.naturalHeight;
    const context = canvas.getContext('2d'); if (!context) throw new Error('Canvas 2D no disponible para verificar el QR.');
    context.drawImage(element, 0, 0);
    return { width: canvas.width, height: canvas.height, pixels: Array.from(context.getImageData(0, 0, canvas.width, canvas.height).data) };
  }, dataUrl);
  const reader = new MultiFormatReader();
  reader.setHints(new Map([[DecodeHintType.POSSIBLE_FORMATS, [BarcodeFormat.QR_CODE]]]));
  const argb = new Int32Array(image.width * image.height);
  for (let index = 0; index < argb.length; index++) {
    const offset = index * 4;
    argb[index] = (image.pixels[offset + 3] << 24) | (image.pixels[offset] << 16) | (image.pixels[offset + 1] << 8) | image.pixels[offset + 2];
  }
  const source = new RGBLuminanceSource(argb, image.width, image.height);
  return reader.decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
};
const expectQrImageToDecode = async (page: import('@playwright/test').Page, image: import('@playwright/test').Locator, expected: string) => {
  await expect.poll(async () => {
    const dataUrl = await image.getAttribute('src');
    if (!dataUrl?.startsWith('data:image/png;base64,')) return '';
    try { return await decodeQrImage(page, dataUrl); } catch { return ''; }
  }, { timeout: 10_000 }).toBe(expected);
};
const waitForVisibleMapTiles = async (page: import('@playwright/test').Page) => page.waitForFunction(() => {
  const tiles = Array.from(document.querySelectorAll<HTMLImageElement>('.leaflet-container .leaflet-tile'))
    .filter(tile => { const box = tile.getBoundingClientRect(); return box.width > 0 && box.height > 0; });
  return tiles.length > 0 && tiles.every(tile => tile.complete && tile.naturalWidth === 256 && tile.naturalHeight === 256);
}, null, { timeout: 30_000 });

const password = 'chrome-isolated-fixture-2026';
const ecuadorToday = () => new Intl.DateTimeFormat('en-CA', {
  timeZone: 'America/Guayaquil', year: 'numeric', month: '2-digit', day: '2-digit',
}).format(new Date());
const plusDays = (days: number) => {
  const value = new Date(`${ecuadorToday()}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
};
const formatEcuadorDate = (date: string) => new Intl.DateTimeFormat('es-EC', {
  timeZone: 'America/Guayaquil', day: 'numeric', month: 'long', year: 'numeric',
}).format(new Date(`${date}T12:00:00-05:00`));
const exactRow = (page: import('@playwright/test').Page, text: string) =>
  page.getByRole('row').filter({ has: page.getByText(text, { exact: true }) });
const apiResponse = (page: import('@playwright/test').Page, pathname: string, method = 'GET') =>
  page.waitForResponse(response => response.request().method() === method
    && new URL(response.url()).pathname === pathname);

test('real backend tariff/calendar writes persist across reload and retain history', async ({ page }, testInfo) => {
  test.setTimeout(360_000);
  const mutationResponses: Array<{ url: string; status: number }> = [];
  const mapBrowserErrors: string[] = [];
  const mapRequestFailures: string[] = [];
  const authResponses: Array<{ path: string; status: number }> = [];
  page.on('console', message => { if (message.type() === 'error') mapBrowserErrors.push(message.text()); });
  page.on('pageerror', error => mapBrowserErrors.push(error.message));
  page.on('requestfailed', request => {
    const failure = request.failure()?.errorText ?? '';
    // Leaflet cancels off-screen tiles with ERR_ABORTED when filters or viewport change.
    // Those are expected cancellations; actual upstream/network failures remain asserted.
    if (/openstreetmap|\/catalog\/map\/tiles\//i.test(request.url()) && failure !== 'net::ERR_ABORTED') {
      mapRequestFailures.push(`${request.method()} ${request.url()} ${failure}`);
    }
  });
  page.on('response', async (response) => {
    const responsePath = new URL(response.url()).pathname;
    if (responsePath === '/api/v1/admin/auth/me') authResponses.push({ path: responsePath, status: response.status() });
    if (/\/(revise-now|end-now|revise|end|cancel)$/.test(new URL(response.url()).pathname)) {
      mutationResponses.push({ url: new URL(response.url()).pathname, status: response.status() });
      console.log('REAL_WRITE_RESPONSE', response.request().method(), new URL(response.url()).pathname, response.status());
    }
  });

  await page.setViewportSize({ width: 1536, height: 1024 });
  await page.goto('/login');
  await page.getByLabel('Usuario', { exact: true }).fill('qa.admin');
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page).toHaveURL(/admin\/dashboard$/);
  console.log('REAL_E2E_LOGIN_OK');
  // Current tariff: revise immediately, extend valid-to, inspect, reload, inspect again.
  await page.goto('/admin/tariffs');
  const generalRow = exactRow(page, 'E2E-TAR-GENERAL');
  await expect(generalRow).toBeVisible();
  await generalRow.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('real-tariff-list-1536x1024.png') });
  const finalizeAction = generalRow.getByRole('button', { name: 'Finalizar vigencia' });
  await expect(finalizeAction).toBeVisible();
  expect(await finalizeAction.evaluate(button => button.scrollWidth <= button.clientWidth)).toBe(true);
  await generalRow.getByRole('button', { name: 'Editar' }).click();
  const tariffEditor = page.locator('.tariff-editor-panel');
  await expect(tariffEditor).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('real-tariff-editor-1536x1024.png') });
  await tariffEditor.getByLabel('Aplicar ahora').check();
  await tariffEditor.locator('[formControlName="code"]').fill('E2E-TAR-GENERAL-V2');
  await tariffEditor.locator('[formControlName="name"]').fill('E2E Tarifa general versión 2');
  await tariffEditor.locator('[formControlName="amount"]').fill('0,30');
  await tariffEditor.locator('[formControlName="validTo"]').fill('2030-12-22T22:06');
  await tariffEditor.locator('[formControlName="normativeReference"]').fill('E2E-GENERAL-REF-V2');
  await tariffEditor.locator('[formControlName="reason"]').fill('E2E modificación inmediata con respaldo');
  const reviseNow = page.waitForResponse(r => r.request().method() === 'POST' && /\/revise-now$/.test(new URL(r.url()).pathname));
  await tariffEditor.getByRole('button', { name: 'Guardar nueva versión' }).click();
  const reviseResponse = await reviseNow;
  const reviseRequest = reviseResponse.request().postDataJSON();
  expect(reviseRequest.expectedVersion).toBe(0);
  expect(reviseResponse.status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Tarifa actualizada correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-tariff-saved-toast-1536x1024.png') });
  const savedRow = exactRow(page, 'E2E-TAR-GENERAL-V2');
  await expect(savedRow).toBeVisible();
  await savedRow.getByRole('button', { name: 'Ver' }).click();
  let detail = page.getByRole('dialog', { name: 'Detalle de tarifa' });
  await expect(detail).toContainText('0,30 USD');
  await expect(detail).toContainText('22 de diciembre de 2030');
  await detail.getByRole('button', { name: 'Cerrar detalle de tarifa' }).click();
  await page.reload();
  const persistedRow = exactRow(page, 'E2E-TAR-GENERAL-V2');
  await persistedRow.getByRole('button', { name: 'Ver' }).click();
  detail = page.getByRole('dialog', { name: 'Detalle de tarifa' });
  await expect(detail).toContainText('0,30 USD');
  await expect(detail).toContainText('22 de diciembre de 2030');
  await page.screenshot({ path: testInfo.outputPath('real-tariff-detail-after-reload-1536x1024.png') });
  await detail.getByRole('button', { name: 'Cerrar detalle de tarifa' }).click();
  await persistedRow.getByRole('link', { name: 'Historial', exact: true }).click();
  await expect(page).toHaveURL(/admin\/tariffs\/history/);
  await expect(page.getByRole('heading', { name: 'Historial de cambios de tarifas', exact: true })).toBeVisible();
  await expect(page.getByText('E2E modificación inmediata con respaldo')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('real-tariff-history-1536x1024.png') });

  // Current tariff immediate ending and another tariff with a scheduled ending.
  await page.goto('/admin/tariffs');
  console.log('REAL_TARIFF_REVISION_AND_HISTORY_OK');
  const immediateRow = exactRow(page, 'E2E-TAR-END-NOW');
  await immediateRow.getByRole('button', { name: 'Finalizar vigencia' }).click();
  const endDialog = page.getByRole('dialog', { name: /Finalizar vigencia/ });
  await endDialog.getByLabel('Finalizar ahora').check();
  await endDialog.locator('[formControlName="reason"]').fill('E2E finalización inmediata');
  const endNow = page.waitForResponse(r => r.request().method() === 'POST' && /\/end-now$/.test(new URL(r.url()).pathname));
  await endDialog.getByRole('button', { name: 'Finalizar ahora' }).click();
  expect((await endNow).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('vigencia se finalizó correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-tariff-ended-now-1536x1024.png') });
  await page.reload();
  await expect(exactRow(page, 'E2E-TAR-END-NOW').getByText('Finalizada', { exact: true })).toBeVisible();

  const scheduledEndRow = exactRow(page, 'E2E-TAR-END-SCHEDULED');
  await scheduledEndRow.getByRole('button', { name: 'Finalizar vigencia' }).click();
  const scheduledEndDialog = page.getByRole('dialog', { name: /Finalizar vigencia/ });
  await scheduledEndDialog.locator('[formControlName="validTo"]').fill(`${plusDays(12)}T12:00`);
  await scheduledEndDialog.locator('[formControlName="reason"]').fill('E2E finalización programada');
  const scheduledEnd = page.waitForResponse(r => r.request().method() === 'POST' && /\/end$/.test(new URL(r.url()).pathname));
  await scheduledEndDialog.getByRole('button', { name: 'Programar finalización' }).click();
  expect((await scheduledEnd).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('finalización se programó correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-tariff-ended-scheduled-1536x1024.png') });
  await page.reload();
  const scheduledEndRecordRow = exactRow(page, 'E2E-TAR-END-SCHEDULED');
  await expect(scheduledEndRecordRow).toContainText('Vigente');
  await expect(scheduledEndRecordRow).toContainText(formatEcuadorDate(plusDays(12)));

  // Scheduled tariff edit and cancellation use the actual server and CSRF cookie.
  const scheduledTariff = exactRow(page, 'E2E-TAR-SCHEDULED');
  const scheduledBeforeText = await scheduledTariff.innerText();
  await scheduledTariff.getByRole('button', { name: 'Editar' }).click();
  const scheduledEditor = page.locator('.tariff-editor-panel');
  await scheduledEditor.locator('[formControlName="amount"]').fill('0,40');
  await scheduledEditor.locator('[formControlName="normativeReference"]').fill('E2E-TAR-SCHEDULED-REF-V2');
  await scheduledEditor.locator('[formControlName="reason"]').fill('E2E corrección de programación');
  await scheduledEditor.locator('[formControlName="validFrom"]').fill(`${plusDays(25)}T12:00`);
  await scheduledEditor.locator('[formControlName="validTo"]').fill('');
  const conflictingEdit = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/tariffs\//.test(new URL(r.url()).pathname));
  await scheduledEditor.getByRole('button', { name: 'Guardar cambios' }).click();
  expect((await conflictingEdit).status()).toBe(409);
  await expect(page.locator('app-operation-toast-outlet [role="alert"]')).toContainText('Ya existe una tarifa con una vigencia que se cruza');
  await expect(scheduledEditor).toBeVisible();
  await expect(scheduledEditor.locator('[formControlName="amount"]')).toHaveValue('0,40');
  await page.screenshot({ path: testInfo.outputPath('evidence-real-tariff-conflict-409-1536x1024.png') });
  const tariffsAfterConflictResponse = apiResponse(page, '/api/v1/admin/parking/tariffs');
  await page.reload();
  expect((await tariffsAfterConflictResponse).status()).toBe(200);
  expect(await exactRow(page, 'E2E-TAR-SCHEDULED').innerText()).toBe(scheduledBeforeText);
  await exactRow(page, 'E2E-TAR-SCHEDULED').getByRole('button', { name: 'Editar' }).click();
  await expect(scheduledEditor).toBeVisible();
  await scheduledEditor.locator('[formControlName="reason"]').fill('E2E ajuste tras corregir el conflicto');
  await scheduledEditor.locator('[formControlName="validFrom"]').fill(`${plusDays(16)}T12:00`);
  await scheduledEditor.locator('[formControlName="validTo"]').fill(`${plusDays(19)}T12:00`);
  const editScheduledTariff = page.waitForResponse(
    r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/tariffs\//.test(new URL(r.url()).pathname),
    { timeout: 20_000 },
  );
  await scheduledEditor.getByRole('button', { name: 'Guardar cambios' }).click();
  expect((await editScheduledTariff).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('tarifa programada se actualizó correctamente');
  const editedScheduledRow = exactRow(page, 'E2E-TAR-SCHEDULED');
  await editedScheduledRow.getByRole('button', { name: 'Cancelar programación' }).click();
  const cancelTariffDialog = page.getByRole('dialog', { name: /Cancelar programación/ });
  await page.screenshot({ path: testInfo.outputPath('real-tariff-cancel-dialog-1536x1024.png') });
  await cancelTariffDialog.locator('[formControlName="reason"]').fill('Cancelación E2E PostgreSQL');
  const cancelTariff = page.waitForResponse(r => r.request().method() === 'POST' && /\/cancel$/.test(new URL(r.url()).pathname));
  await cancelTariffDialog.getByRole('button', { name: 'Confirmar cancelación' }).click();
  expect((await cancelTariff).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('programación fue cancelada');
  await page.screenshot({ path: testInfo.outputPath('evidence-tariff-cancelled-toast-1536x1024.png') });
  await page.reload();
  await expect(exactRow(page, 'E2E-TAR-SCHEDULED').getByText('Cancelada', { exact: true })).toBeVisible();

  // Calendar current version, scheduled edit/cancel and current-calendar scheduled ending.
  await page.goto('/admin/tariffs/billing-days');
  await expect(page.getByRole('heading', { name: 'Días de cobro', exact: true })).toBeVisible();
  const currentCalendarRow = exactRow(page, 'E2E-CAL-GENERAL');
  await expect(currentCalendarRow).toBeVisible();
  await currentCalendarRow.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('real-billing-days-list-1536x1024.png') });
  await currentCalendarRow.getByRole('button', { name: 'Editar' }).click();
  let calendarDialog = page.getByRole('dialog');
  await page.screenshot({ path: testInfo.outputPath('real-calendar-current-editor-1536x1024.png') });
  await calendarDialog.locator('[formControlName="validFrom"]').fill(plusDays(8));
  await calendarDialog.locator('[formControlName="normativeReference"]').fill('E2E-CAL-GENERAL-V2');
  await calendarDialog.locator('[formControlName="reason"]').fill('E2E versionado de calendario');
  const weekdays = calendarDialog.locator('.weekday-options');
  for (const day of ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes']) {
    const box = weekdays.getByLabel(day, { exact: false });
    if (day !== 'Martes' && day !== 'Jueves' && await box.isChecked()) await box.uncheck();
  }
  const reviseCalendar = page.waitForResponse(r => r.request().method() === 'POST' && /\/billing-day-calendars\/[^/]+\/revise$/.test(new URL(r.url()).pathname));
  await calendarDialog.getByRole('button', { name: 'Guardar nueva versión' }).click();
  expect((await reviseCalendar).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Calendario actualizado correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-calendar-saved-toast-1536x1024.png') });
  await page.reload();
  const revisedCalendarRow = exactRow(page, 'E2E-CAL-GENERAL-V2');
  await expect(revisedCalendarRow).toBeVisible();
  await revisedCalendarRow.getByRole('button', { name: 'Ver' }).click();
  const calendarDetail = page.locator('dialog.calendar-dialog').filter({ hasText: 'Semana configurada' });
  await expect(calendarDetail).toContainText('Martes');
  await expect(calendarDetail).toContainText('Jueves');
  await expect(calendarDetail).toContainText('Gratuito');
  await calendarDetail.getByRole('button', { name: 'Cerrar detalle' }).click();
  const calendarHistoryResponse = page.waitForResponse(response => {
    const path = new URL(response.url()).pathname;
    return response.request().method() === 'GET' && /\/billing-day-calendars\/[^/]+\/history$/.test(path);
  }, { timeout: 15_000 });
  await revisedCalendarRow.getByRole('button', { name: 'Historial' }).click();
  const calendarHistoryHttp = await calendarHistoryResponse;
  console.log('REAL_CALENDAR_HISTORY_RESPONSE', calendarHistoryHttp.status());
  if (!calendarHistoryHttp.ok()) console.log('REAL_CALENDAR_HISTORY_ERROR', (await calendarHistoryHttp.text()).slice(0, 500));
  expect(calendarHistoryHttp.status()).toBe(200);
  const calendarHistory = page.locator('dialog.calendar-dialog').filter({ has: page.getByRole('button', { name: 'Cerrar historial' }) });
  await expect(calendarHistory).toContainText('E2E versionado de calendario');
  await page.screenshot({ path: testInfo.outputPath('real-calendar-history-1536x1024.png') });
  await calendarHistory.getByRole('button', { name: 'Cerrar historial' }).click();
  console.log('REAL_CALENDAR_VERSION_HISTORY_OK');

  const scheduledCalendarRow = exactRow(page, 'E2E-CAL-SCHEDULED');
  await scheduledCalendarRow.getByRole('button', { name: 'Editar' }).click();
  calendarDialog = page.getByRole('dialog');
  await calendarDialog.locator('[formControlName="validFrom"]').fill(plusDays(25));
  await calendarDialog.locator('[formControlName="normativeReference"]').fill('E2E-CAL-SCHEDULED-V2');
  await calendarDialog.locator('[formControlName="reason"]').fill('E2E edición de calendario programado');
  const scheduledWeekdays = calendarDialog.locator('.weekday-options');
  const wednesday = scheduledWeekdays.getByLabel('Miércoles', { exact: false });
  if (!(await wednesday.isChecked())) await wednesday.check();
  const editCalendar = page.waitForResponse(r => r.request().method() === 'PUT' && /\/billing-day-calendars\//.test(new URL(r.url()).pathname));
  await calendarDialog.getByRole('button', { name: 'Guardar cambios' }).click();
  expect((await editCalendar).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Calendario actualizado correctamente');
  const editedCalendar = exactRow(page, 'E2E-CAL-SCHEDULED-V2');
  await editedCalendar.getByRole('button', { name: 'Cancelar programación' }).click();
  const cancelCalendarDialog = page.getByRole('dialog', { name: /Cancelar este calendario/ });
  await page.screenshot({ path: testInfo.outputPath('real-calendar-cancel-dialog-1536x1024.png') });
  await cancelCalendarDialog.locator('[formControlName="reason"]').fill('Cancelación E2E PostgreSQL');
  console.log('REAL_CALENDAR_CANCEL_READY');
  const cancelCalendar = page.waitForResponse(r => r.request().method() === 'POST' && /\/billing-day-calendars\/[^/]+\/cancel$/.test(new URL(r.url()).pathname));
  await cancelCalendarDialog.getByRole('button', { name: 'Confirmar cancelación' }).click();
  expect((await cancelCalendar).status()).toBe(200);
  console.log('REAL_CALENDAR_CANCEL_RESPONSE_OK');
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Programación cancelada correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-calendar-cancelled-toast-1536x1024.png') });
  console.log('REAL_CALENDAR_CANCEL_TOAST_OK');
  await page.reload();
  await expect(exactRow(page, 'E2E-CAL-SCHEDULED-V2').getByText('Cancelado', { exact: true })).toBeVisible();
  console.log('REAL_CALENDAR_CANCEL_RELOAD_OK');

  const endingCalendarRow = exactRow(page, 'E2E-CAL-END');
  console.log('REAL_CALENDAR_END_ROW_OK');
  await endingCalendarRow.getByRole('button', { name: 'Finalizar vigencia' }).click();
  console.log('REAL_CALENDAR_END_DIALOG_OPENED');
  const calendarEndDialog = page.locator('dialog.calendar-dialog').filter({ has: page.locator('[formControlName="effectiveFrom"]') });
  await expect(calendarEndDialog).toBeVisible();
  await calendarEndDialog.locator('[formControlName="effectiveFrom"]').fill(plusDays(18));
  await calendarEndDialog.locator('[formControlName="reason"]').fill('E2E finalización de calendario');
  const endCalendar = page.waitForResponse(r => r.request().method() === 'POST' && /\/billing-day-calendars\/[^/]+\/end$/.test(new URL(r.url()).pathname));
  await calendarEndDialog.getByRole('button', { name: 'Confirmar finalización' }).click();
  expect((await endCalendar).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Vigencia finalizada correctamente');
  await page.screenshot({ path: testInfo.outputPath('evidence-calendar-ended-toast-1536x1024.png') });
  await page.reload();
  const endedCalendarRow = exactRow(page, 'E2E-CAL-END');
  await expect(endedCalendarRow).toContainText('Vigente');
  await expect(endedCalendarRow).toContainText(formatEcuadorDate(plusDays(17)));

  // Real PostgreSQL-backed catalog lifecycle: create, edit, reload, and verify all three screens.
  await page.goto('/admin/zones');
  await expect(page.getByRole('heading', { name: 'Zonas de estacionamiento', exact: true })).toBeVisible();
  await expect(exactRow(page, 'E2E-GENERAL')).toContainText('E2E-TAR-GENERAL-V2');
  await expect(exactRow(page, 'E2E-GENERAL')).toContainText('E2E-TAR-GENERAL-V2 · 0,30 USD / 60 min');
  await expect(exactRow(page, 'E2E-GENERAL')).toContainText('Tarifa general');
  await expect(exactRow(page, 'E2E-END-SCHEDULED')).toContainText('E2E-TAR-END-SCHEDULED');
  await expect(exactRow(page, 'E2E-END-SCHEDULED')).toContainText('Excepción de zona');
  await exactRow(page, 'E2E-GENERAL').click();
  await expect(page.locator('.record-detail')).toContainText('E2E-TAR-GENERAL-V2 · 0,30 USD / 60 min');
  await page.screenshot({ path: testInfo.outputPath('zones-list-1536x1024.png') });
  await page.getByRole('button', { name: 'Nueva zona' }).click();
  let catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('zones-create-form-1536x1024.png') });
  await catalogDialog.getByLabel('Código').fill('E2E-CATALOG-NEW');
  await catalogDialog.getByLabel('Nombre').fill('E2E Zona nueva');
  const createZone = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/v1\/admin\/parking\/zones$/.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Crear registro' }).click();
  const createZoneResponse = await createZone;
  expect(createZoneResponse.status()).toBe(201);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Zona creada correctamente');
  let catalogZoneRow = exactRow(page, 'E2E-CATALOG-NEW');
  await expect(catalogZoneRow).toBeVisible();
  await expect(catalogZoneRow).toContainText('E2E-TAR-GENERAL-V2');
  await expect(catalogZoneRow).toContainText('Tarifa general');
  await catalogZoneRow.getByRole('button', { name: 'Editar' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('zones-edit-form-1536x1024.png') });
  await catalogDialog.getByLabel('Código').fill('E2E-CATALOG-V2');
  await catalogDialog.getByLabel('Nombre').fill('E2E Zona actualizada');
  const updateZone = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/zones\//.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Guardar cambios' }).click();
  expect((await updateZone).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Zona actualizada correctamente');
  await page.reload();
  catalogZoneRow = exactRow(page, 'E2E-CATALOG-V2');
  await expect(catalogZoneRow).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('zones-persisted-1536x1024.png') });
  await catalogZoneRow.click();
  await expect(page.locator('.record-detail')).toContainText('E2E-CATALOG-V2');
  await page.screenshot({ path: testInfo.outputPath('zones-detail-1536x1024.png') });
  const streetsNavigation = page.waitForURL(/\/admin\/streets\?zoneId=/);
  await page.getByRole('button', { name: 'Ver calles asociadas' }).click();
  await streetsNavigation;
  const catalogZoneId = new URL(page.url()).searchParams.get('zoneId');
  expect(catalogZoneId).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/admin/streets\\?zoneId=${catalogZoneId}`));
  await expect(page.locator('.catalog-toolbar').getByLabel('Zona')).toHaveValue(catalogZoneId!);

  await page.goto(`/admin/streets?zoneId=${catalogZoneId}`);
  await expect(page.getByRole('heading', { name: 'Calles y tramos', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'No hay calles y tramos registrados', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('streets-list-1536x1024.png') });
  await page.getByRole('button', { name: 'Nueva calle' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('streets-create-form-1536x1024.png') });
  await catalogDialog.getByLabel('Zona').selectOption(catalogZoneId!);
  await catalogDialog.getByLabel('Código').fill('E2E-STREET');
  await catalogDialog.getByLabel('Nombre').fill('E2E Calle');
  const createStreet = page.waitForResponse(r => r.request().method() === 'POST' && /\/streets$/.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Crear registro' }).click();
  const createStreetResponse = await createStreet;
  expect(createStreetResponse.status()).toBe(201);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Calle creada correctamente');
  let catalogStreetRow = exactRow(page, 'E2E-STREET');
  await expect(catalogStreetRow).toBeVisible();
  await catalogStreetRow.getByRole('button', { name: 'Editar' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('streets-edit-form-1536x1024.png') });
  await catalogDialog.getByLabel('Código').fill('E2E-STREET-V2');
  await catalogDialog.getByLabel('Nombre').fill('E2E Calle actualizada');
  const updateStreet = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/streets\//.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Guardar cambios' }).click();
  expect((await updateStreet).status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Calle actualizada correctamente');
  await page.reload();
  catalogStreetRow = exactRow(page, 'E2E-STREET-V2');
  await expect(catalogStreetRow).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('streets-persisted-1536x1024.png') });
  await catalogStreetRow.click();
  await expect(page.locator('.record-detail')).toContainText('E2E-STREET-V2');
  await page.screenshot({ path: testInfo.outputPath('streets-detail-1536x1024.png') });
  await page.getByRole('link', { name: 'Espacios', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Espacios de estacionamiento', exact: true })).toBeVisible();
  await page.locator('.catalog-toolbar').getByLabel('Zona').selectOption(catalogZoneId!);
  await expect(page.getByRole('heading', { name: 'No hay espacios registrados', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('spaces-list-1536x1024.png') });
  await page.getByRole('button', { name: 'Nuevo espacio' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('spaces-create-form-1536x1024.png') });
  await catalogDialog.getByLabel('Zona').selectOption(catalogZoneId!);
  const createdStreetOption = catalogDialog.getByLabel('Calle').locator('option').filter({ hasText: 'E2E-STREET-V2' });
  await expect(createdStreetOption).toHaveCount(1);
  const catalogStreetId = await createdStreetOption.getAttribute('value');
  expect(catalogStreetId).toBeTruthy();
  await catalogDialog.getByLabel('Calle').selectOption(catalogStreetId!);
  await catalogDialog.getByLabel('Código').fill('E2E-SPACE');
  await catalogDialog.getByLabel('Número').fill('01');
  await catalogDialog.getByLabel('Tipo').fill('STANDARD');
  // Keep the synthetic E2E marker in the municipality's Ecuador region so the
  // real OSM tile assertion exercises recognizable land cartography.
  await catalogDialog.getByLabel('Latitud').fill('-3.672');
  await catalogDialog.getByLabel('Longitud').fill('-79.652');
  const createSpace = page.waitForResponse(r => r.request().method() === 'POST' && /\/spaces$/.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Crear registro' }).click();
  const createSpaceResponse = await createSpace;
  expect(createSpaceResponse.status()).toBe(201);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Espacio creado correctamente');
  let catalogSpaceRow = page.getByRole('row').filter({ hasText: 'E2E-SPACE' });
  await expect(catalogSpaceRow).toBeVisible();
  await catalogSpaceRow.getByRole('button', { name: 'Ver detalle' }).click();
  const createdSpaceQr = (await page.locator('.record-detail .qr-content').innerText()).trim();
  expect(createdSpaceQr).toMatch(/^SIMERTPI:SPACE:[0-9a-f-]{36}$/i);
  await catalogSpaceRow.getByRole('button', { name: 'Editar' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await page.screenshot({ path: testInfo.outputPath('spaces-edit-form-1536x1024.png') });
  await catalogDialog.getByLabel('Código').fill('E2E-SPACE-V2');
  await catalogDialog.getByLabel('Número').fill('02');
  const updateSpace = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/spaces\//.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Guardar cambios' }).click();
  const updateSpaceResponse = await updateSpace;
  expect(updateSpaceResponse.status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Espacio actualizado correctamente');
  await page.reload();
  catalogSpaceRow = page.getByRole('row').filter({ hasText: 'E2E-SPACE-V2' });
  await expect(catalogSpaceRow).toBeVisible();
  await expect(catalogSpaceRow).toContainText('Sin sesión activa');
  await catalogSpaceRow.getByRole('button', { name: 'Ver detalle' }).click();
  await expect(page.getByText(createdSpaceQr, { exact: true })).toBeVisible();
  const detailQrImage = page.getByRole('img', { name: 'Código QR del espacio E2E-SPACE-V2' });
  await expectQrImageToDecode(page, detailQrImage, createdSpaceQr);
  const detailQrData = await detailQrImage.getAttribute('src');
  expect(detailQrData).toMatch(/^data:image\/png;base64,/);

  await page.screenshot({ path: testInfo.outputPath('space-qr-detail-real-postgres-1536x1024.png') });
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Descargar QR' }).click();
  const downloadedQr = await downloadPromise;
  expect(readFileSync(await downloadedQr.path()).toString('base64')).toBe(detailQrData!.split(',')[1]);
  const printPromise = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'Imprimir QR' }).click();
  const printPage = await printPromise;
  const printQrImage = printPage.getByRole('img', { name: 'QR E2E-SPACE-V2' });
  await expectQrImageToDecode(printPage, printQrImage, createdSpaceQr);
  const printQrData = await printQrImage.getAttribute('src');
  expect(printQrData).toBe(detailQrData);

  await printPage.screenshot({ path: testInfo.outputPath('space-qr-print-real-postgres.png'), fullPage: true });
  await page.screenshot({ path: testInfo.outputPath('spaces-persisted-1536x1024.png') });
  await expect(page.locator('.record-detail').getByRole('heading', { name: 'Detalle de espacio', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('spaces-detail-1536x1024.png') });

  await page.getByRole('button', { name: 'Nuevo espacio' }).click();
  catalogDialog = page.locator('dialog.catalog-dialog');
  await catalogDialog.getByLabel('Zona').selectOption(catalogZoneId);
  await catalogDialog.getByLabel('Calle').selectOption(catalogStreetId);
  await catalogDialog.getByLabel('Código').fill('E2E-SPACE-PENDING');
  await catalogDialog.getByLabel('Número').fill('03');
  await catalogDialog.getByLabel('Tipo').fill('STANDARD');
  const createPendingSpace = page.waitForResponse(r => r.request().method() === 'POST' && /\/spaces$/.test(new URL(r.url()).pathname));
  await catalogDialog.getByRole('button', { name: 'Crear registro' }).click();
  expect((await createPendingSpace).status()).toBe(201);

  mapBrowserErrors.length = 0;
  mapRequestFailures.length = 0;
  const realTilePromise = page.waitForResponse(response => /\/api\/v1\/admin\/parking\/catalog\/map\/tiles\/\d+\/\d+\/\d+$/.test(new URL(response.url()).pathname), { timeout: 30_000 });
  await page.goto('/admin/map');
  await expect(page.getByRole('heading', { name: 'Ubicaciones georreferenciadas', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('map-before-filter-1536x1024.png') });
  console.log('REAL_MAP_INITIAL_TEXT', (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(-600));
  const zoneSelector = page.getByLabel('Zona', { exact: true });
  try {
    await expect(zoneSelector).toBeVisible({ timeout: 10_000 });
  } catch (error) {
    const diagnostics = {
      url: page.url(),
      title: await page.title(),
      body: (await page.locator('body').innerText()).slice(0, 1500),
      authResponses: [...authResponses],
    };
    await page.screenshot({ path: testInfo.outputPath('zone-selector-not-visible-diagnostics.png'), fullPage: true });
    throw new Error(`El selector Zona no se renderizó. Diagnóstico: ${JSON.stringify(diagnostics)}. Causa original: ${String(error)}`);
  }
  await zoneSelector.selectOption(catalogZoneId, { timeout: 10_000 });
  await expect(page.getByLabel('Calle', { exact: true })).toBeVisible();
  await page.getByLabel('Calle', { exact: true }).selectOption(catalogStreetId);
  const coverageSummary = page.locator('.summary-grid article');
  await expect(coverageSummary.nth(0)).toContainText('2');
  await expect(coverageSummary.nth(1)).toContainText('1');
  await expect(coverageSummary.nth(2)).toContainText('1');
  const markerList = page.getByRole('list', { name: /Espacios georreferenciados/ });
  await expect(markerList).toContainText('E2E-SPACE-V2');
  await expect(markerList).not.toContainText('E2E-SPACE-PENDING');
  const realTile = await realTilePromise;
  expect(realTile.status()).toBe(200);
  expect(realTile.headers()['content-type']).toContain('image/png');
  const tileBytes = await realTile.body();
  expect(tileBytes.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  expect(tileBytes.readUInt32BE(16)).toBe(256);
  expect(tileBytes.readUInt32BE(20)).toBe(256);
  const loadedTiles = page.locator('.leaflet-tile-loaded');
  await expect(loadedTiles.first()).toBeVisible();
  expect(await loadedTiles.count()).toBeGreaterThan(0);
  await waitForVisibleMapTiles(page);
  expect(mapBrowserErrors).toEqual([]);
  expect(mapRequestFailures).toEqual([]);
  const visibleMap = page.locator('.live-map-panel app-parking-map');
  await visibleMap.scrollIntoViewIfNeeded();
  expect(await visibleMap.evaluate(element => { const box=element.getBoundingClientRect(); return box.width>0 && box.height>0 && box.top<innerHeight && box.bottom>0; })).toBe(true);
  await page.screenshot({ path: 'artifacts/map-real-osm-postgres-1536x1024.png' });
  console.log('REAL_QR_DECODE_DOWNLOAD_PRINT_AND_OSM_TILE_OK');

  const mapZoneOption = page.getByLabel('Zona', { exact: true }).locator('option').filter({ hasText: 'E2E-MAP-STATES' });
  await expect(mapZoneOption).toHaveCount(1);
  const mapStatusZoneId = await mapZoneOption.getAttribute('value');
  expect(mapStatusZoneId).toBeTruthy();
  await page.getByLabel('Zona', { exact: true }).selectOption(mapStatusZoneId!);
  const mapStreetOption = page.getByLabel('Calle', { exact: true }).locator('option').filter({ hasText: 'E2E-MAP-ST' });
  await expect(mapStreetOption).toHaveCount(1);
  const mapStatusStreetId = await mapStreetOption.getAttribute('value');
  expect(mapStatusStreetId).toBeTruthy();
  await page.getByLabel('Calle', { exact: true }).selectOption(mapStatusStreetId!);
  const statusMarkerList = page.getByRole('list', { name: /Espacios georreferenciados/ });
  for (const statusLabel of [
    'Sesión vigente', 'Próxima a vencer', 'Tiempo vencido; sigue ocupado hasta el cierre',
    'Tiempo máximo alcanzado; sigue ocupado', 'Pago pendiente; espacio reservado',
    'Sin sesión activa registrada; no confirma disponibilidad física',
    'Deshabilitado administrativamente', 'Estado operativo no disponible',
  ]) await expect(statusMarkerList).toContainText(statusLabel);
  await expect(statusMarkerList.getByRole('button').filter({ hasText: 'E2E-MAP-DISABLED' })).toContainText('Deshabilitado administrativamente');
  await expect(statusMarkerList.getByRole('button').filter({ hasText: 'E2E-MAP-PAYMENT' })).toContainText('Pago pendiente; espacio reservado');
  for (const label of [
    'Sesión vigente', 'Próxima a vencer', 'Tiempo vencido; sigue ocupado', 'Tiempo máximo alcanzado',
    'Sin sesión activa registrada', 'Deshabilitado', 'Pago pendiente', 'Estado no disponible',
  ]) await expect(page.locator('.map-legend span').filter({ hasText: label }).locator('b')).toHaveText('1');
  const mapClusters=page.locator('.live-map-panel .simertpi-map-cluster');
  const clustersBefore=await mapClusters.count();
  expect(clustersBefore).toBeGreaterThan(0);
  await waitForVisibleMapTiles(page);
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'artifacts/map-real-osm-multi-status-1536x1024.png' });
  await mapClusters.first().click();
  await expect.poll(async()=>mapClusters.count()).toBeLessThan(clustersBefore);
  await statusMarkerList.getByRole('button').filter({ hasText: 'E2E-MAP-ACTIVE' }).click();
  await expect(page.locator('.map-selection .state-chip')).toHaveText('Sesión vigente');
  await waitForVisibleMapTiles(page);
  expect(mapBrowserErrors).toEqual([]);
  expect(mapRequestFailures).toEqual([]);
  await page.screenshot({ path: 'artifacts/map-real-osm-marker-selected-1536x1024.png' });
  console.log('REAL_MAP_SERVER_STATES_AND_COLORS_OK');

  await page.goto('/admin/spaces');
  await expect(page).toHaveURL(/\/admin\/spaces$/);
  const spacesToolbar = page.locator('.catalog-toolbar');
  await expect(spacesToolbar).toBeVisible({ timeout: 10_000 });
  const spacesZoneFilter = spacesToolbar.getByRole('combobox', { name: 'Zona', exact: true });
  await expect(spacesZoneFilter).toBeVisible({ timeout: 10_000 });
  await expect(spacesZoneFilter).toBeEnabled({ timeout: 10_000 });
  await spacesZoneFilter.selectOption(catalogZoneId, { timeout: 10_000 });
  const selectedSpaceRow = page.getByRole('row').filter({ hasText: 'E2E-SPACE-V2' });
  await expect(selectedSpaceRow).toBeVisible({ timeout: 10_000 });
  await selectedSpaceRow.getByRole('button', { name: 'Editar' }).click({ timeout: 10_000 });
  mapBrowserErrors.length = 0;
  mapRequestFailures.length = 0;
  await page.getByRole('button', { name: 'Seleccionar en mapa' }).click({ timeout: 10_000 });
  const locationMap = page.locator('.map-dialog .parking-map-canvas');
  await expect(locationMap).toBeVisible();
  await waitForVisibleMapTiles(page);
  await locationMap.scrollIntoViewIfNeeded();
  const locationMapBox = await locationMap.boundingBox();
  expect(locationMapBox).not.toBeNull();
  await page.mouse.click(locationMapBox!.x + locationMapBox!.width * .78, locationMapBox!.y + locationMapBox!.height * .74);
  await expect(page.locator('.coordinate-readout')).toContainText(',');
  await page.screenshot({ path: 'artifacts/space-location-selector-selected-real-osm-1536x1024.png' });
  await page.getByRole('button', { name: /Confirmar ubicaci/ }).click();
  const saveSelectedLocation = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/spaces\//.test(new URL(r.url()).pathname));
  await page.locator('dialog.catalog-dialog').getByRole('button', { name: 'Guardar cambios' }).click();
  const updateLocationResponse = await saveSelectedLocation;
  expect(updateLocationResponse.status()).toBe(200);
  await expect(page.locator('app-operation-toast-outlet [role="status"]')).toContainText('Espacio actualizado correctamente');
  await page.reload();
  const persistedLocationRow = page.getByRole('row').filter({ hasText: 'E2E-SPACE-V2' });
  await persistedLocationRow.getByRole('button', { name: 'Editar' }).click();
  const persistedLocationDialog = page.locator('dialog.catalog-dialog');
  expect(Number(await persistedLocationDialog.getByLabel('Latitud').inputValue())).not.toBe(-3.672);
  expect(Number(await persistedLocationDialog.getByLabel('Longitud').inputValue())).not.toBe(-79.652);
  await persistedLocationDialog.getByRole('button', { name: 'Cancelar' }).click();
  expect(mapBrowserErrors).toEqual([]);
  expect(mapRequestFailures).toEqual([]);
  console.log('REAL_MAP_LOCATION_SELECTION_PERSISTED');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/map');
  await page.getByLabel('Zona', { exact: true }).selectOption(catalogZoneId);
  await page.getByLabel('Calle', { exact: true }).selectOption(catalogStreetId);
  await waitForVisibleMapTiles(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(mapBrowserErrors).toEqual([]);
  expect(mapRequestFailures).toEqual([]);
  const mobileVisibleMap = page.locator('.live-map-panel app-parking-map');
  await mobileVisibleMap.scrollIntoViewIfNeeded();
  expect(await mobileVisibleMap.evaluate(element => { const box=element.getBoundingClientRect(); return box.width>0 && box.height>0 && box.top<innerHeight && box.bottom>0; })).toBe(true);
  await page.screenshot({ path: 'artifacts/map-real-osm-postgres-390x844.png' });

  await page.goto('/admin/spaces');
  await page.getByRole('row').filter({ hasText: 'E2E-SPACE-V2' }).getByRole('button', { name: 'Editar' }).click();
  mapBrowserErrors.length = 0;
  mapRequestFailures.length = 0;
  await page.getByRole('button', { name: 'Seleccionar en mapa' }).click();
  await waitForVisibleMapTiles(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(mapBrowserErrors).toEqual([]);
  expect(mapRequestFailures).toEqual([]);
  await page.screenshot({ path: 'artifacts/space-location-selector-real-osm-390x844.png' });
  await page.getByRole('button', { name: 'Cancelar', exact: true }).last().click();
  await page.locator('dialog.catalog-dialog').getByRole('button', { name: 'Cancelar' }).click();

  for (const screen of [{ path: '/admin/zones', title: 'Zonas de estacionamiento', name: 'zones', create: 'Nueva zona' }, { path: '/admin/streets', title: 'Calles y tramos', name: 'streets', create: 'Nueva calle' }, { path: '/admin/spaces', title: 'Espacios de estacionamiento', name: 'spaces', create: 'Nuevo espacio' }]) {
    await page.goto(screen.path);
    await expect(page.getByRole('heading', { name: screen.title, exact: true })).toBeVisible();
    await expect(page.locator('tbody tr').first()).toBeVisible();
    for (const viewport of [{ width: 1536, height: 1024 }, { width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      const suffix = `${viewport.width}x${viewport.height}`;
      await page.screenshot({ path: testInfo.outputPath(`${screen.name}-list-${suffix}.png`) });
      const firstRow = page.locator('tbody tr').first();
      await firstRow.getByRole('button', { name: 'Ver detalle' }).click();
      await page.locator('.record-detail').scrollIntoViewIfNeeded();
      await page.screenshot({ path: testInfo.outputPath(`${screen.name}-detail-${suffix}.png`) });
      await firstRow.getByRole('button', { name: 'Editar' }).click();
      await page.screenshot({ path: testInfo.outputPath(`${screen.name}-edit-${suffix}.png`) });
      await page.locator('dialog.catalog-dialog').getByRole('button', { name: 'Cancelar' }).click();
      await page.getByRole('button', { name: new RegExp(screen.create) }).click();
      await page.screenshot({ path: testInfo.outputPath(`${screen.name}-create-${suffix}.png`) });
      await page.locator('dialog.catalog-dialog').getByRole('button', { name: 'Cancelar' }).click();
    }
  }
  console.log('REAL_E2E_ZONES_STREETS_SPACES_PERSISTED');

  expect(mutationResponses.length).toBeGreaterThanOrEqual(7);
  expect(mutationResponses.every(item => item.status >= 200 && item.status < 300)).toBe(true);
});

test('real backend map markers transition on each 60-second refresh and recover after an upstream error', async ({ page }, testInfo) => {
  // Four actual 60-second refresh cycles are part of this scenario; the
  // administrative session itself still has its configured five-minute idle TTL.
  test.setTimeout(420_000);
  const browserErrors: string[] = [];
  page.on('console', message => { if (message.type() === 'error') browserErrors.push(message.text()); });
  page.on('pageerror', error => browserErrors.push(error.message));
  await page.goto('/login');
  await page.getByLabel('Usuario', { exact: true }).fill('qa.admin');
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page).toHaveURL(/admin\/dashboard$/);
  await page.setViewportSize({ width: 1536, height: 1024 });
  await page.goto('/admin/map');
  await expect(page.getByRole('heading', { name: 'Ubicaciones georreferenciadas' })).toBeVisible();
  const transitionZoneOption = page.getByLabel('Zona', { exact: true }).locator('option').filter({ hasText: 'E2E-MAP-TRANSITIONS' });
  await expect(transitionZoneOption).toHaveCount(1);
  const zoneId = await transitionZoneOption.getAttribute('value');
  expect(zoneId).toBeTruthy();
  await page.getByLabel('Zona', { exact: true }).selectOption(zoneId!);
  const transitionStreetOption = page.getByLabel('Calle', { exact: true }).locator('option').filter({ hasText: 'E2E-MAP-TRANSITIONS-ST' });
  await expect(transitionStreetOption).toHaveCount(1);
  const streetId = await transitionStreetOption.getAttribute('value');
  expect(streetId).toBeTruthy();
  const initialMapResponse = apiResponse(page, '/api/v1/admin/parking/catalog/map');
  await page.getByLabel('Calle', { exact: true }).selectOption(streetId!);
  expect((await initialMapResponse).status()).toBe(200);
  const markerList = page.getByRole('list', { name: /Espacios georreferenciados/ });
  const row = markerList.getByRole('button').filter({ hasText: 'E2E-MAP-TRANSITIONS-P' });
  await expect(row).toContainText('Sesión vigente');
  await expect(row).toHaveCount(1);
  await page.screenshot({ path: testInfo.outputPath('map-transition-active-1536x1024.png') });
  const transitionMarkerRow = markerList.locator('li').filter({ hasText: 'E2E-MAP-TRANSITIONS-P' });
  await expect(transitionMarkerRow).toHaveCount(1);
  const identity = await transitionMarkerRow.getAttribute('data-marker-id');
  expect(identity).toBeTruthy();
  const commandPath = resolve(process.cwd(), '../simertpi-backend/target/admin-browser-map-transition.command');
  const acknowledgmentPath = resolve(process.cwd(), '../simertpi-backend/target/admin-browser-map-transition.ack');
  const waitForStatus = async (expected: string, label: string) => {
    const deadline = Date.now() + 75_000;
    while (Date.now() < deadline) {
      const remaining = Math.max(1, deadline - Date.now());
      const response = await page.waitForResponse(candidate => candidate.request().method() === 'GET'
        && new URL(candidate.url()).pathname === '/api/v1/admin/parking/catalog/map', { timeout: remaining });
      expect(response.status()).toBe(200);
      const displayed = await expect.poll(async () => (await row.innerText().catch(() => '')).includes(label), { timeout: 5_000 })
        .toBe(true).then(() => true, () => false);
      if (displayed) return transitionMarkerRow.getAttribute('data-marker-id');
    }
    throw new Error(`El refresco real de 60 segundos no reflejó el estado ${expected}.`);
  };
  const transition = async (command: string, expected: string, label: string, markerClass: string) => {
    writeFileSync(acknowledgmentPath, 'PENDING');
    writeFileSync(commandPath, command);
    await expect.poll(() => existsSync(acknowledgmentPath) ? readFileSync(acknowledgmentPath, 'utf8') : '', { timeout: 10_000 }).toBe(command);
    const currentIdentity = await waitForStatus(expected, label);
    await expect(row).toContainText(label);
    await expect(row).toHaveCount(1);
    await expect(page.locator(`.live-map-panel .simertpi-map-icon-${markerClass}`)).toHaveCount(1);
    await expect(page.locator('.map-legend span').filter({ hasText: label }).locator('b')).toHaveText('1');
    await page.screenshot({ path: testInfo.outputPath(`map-transition-${expected.toLowerCase()}-1536x1024.png`) });
    expect(currentIdentity).toBe(identity);
  };
  await transition('YELLOW', 'ENDING_SOON', 'Próxima a vencer', 'ending-soon');
  await transition('EXPIRED', 'EXPIRED', 'Tiempo vencido; sigue ocupado', 'expired');
  await transition('DISABLE', 'DISABLED', 'Deshabilitado', 'disabled');
  await transition('COMPLETE', 'NO_ACTIVE_SESSION', 'Sin sesión activa registrada', 'no-session');

  let failOnce = true;
  await page.route('**/api/v1/admin/parking/catalog/map**', async route => {
    if (failOnce) { failOnce = false; await route.abort('failed'); }
    else await route.continue();
  });
  const errorStart = browserErrors.length;
  await page.getByRole('button', { name: 'Actualizar datos' }).click();
  await expect(page.locator('.live-map-panel > [role="alert"]').first()).toBeVisible();
  await expect(page.locator('.map-marker-list')).toHaveCount(0);
  expect(browserErrors.slice(errorStart)).toEqual(['Failed to load resource: net::ERR_FAILED']);
  browserErrors.length = 0;
  const recovered = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/admin/parking/catalog/map');
  await page.getByRole('button', { name: 'Reintentar consulta' }).click();
  expect((await recovered).status()).toBe(200);
  await expect(row).toContainText('Sin sesión activa registrada');
  await expect(page.locator('.live-map-panel > [role="alert"]')).toHaveCount(0);
  await expect(row).toHaveCount(1);
  await page.unroute('**/api/v1/admin/parking/catalog/map**');
  expect(browserErrors).toEqual([]);
  console.log('REAL_MAP_60_SECOND_SERVER_STATE_TRANSITIONS_AND_NETWORK_RECOVERY_OK');
});
