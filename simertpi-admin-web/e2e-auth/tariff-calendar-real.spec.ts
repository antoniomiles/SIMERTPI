import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

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

test.afterEach(() => {
  writeFileSync(resolve(process.cwd(), '../simertpi-backend/target/admin-browser.stop'), 'stop');
});

test('real backend tariff/calendar writes persist across reload and retain history', async ({ page }, testInfo) => {
  test.setTimeout(240_000);
  const mutationResponses: Array<{ url: string; status: number }> = [];
  page.on('response', async (response) => {
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
  const savedVersion = await page.evaluate(async () => {
    const response = await fetch('/api/v1/admin/parking/tariffs');
    const rows = await response.json();
    return rows.find((row: any) => row.code === 'E2E-TAR-GENERAL-V2');
  });
  expect(savedVersion).toBeTruthy();
  expect(savedVersion.rowVersion).toBe(0);
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
  await expect(page.getByRole('heading', { name: 'Historial de cambios de tarifas' })).toBeVisible();
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
  const scheduledBefore = await page.evaluate(async () => {
    const response = await fetch('/api/v1/admin/parking/tariffs');
    const rows = await response.json();
    return rows.find((row: any) => row.code === 'E2E-TAR-SCHEDULED');
  });
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
  const unchangedAfterConflict = await page.evaluate(async () => {
    const response = await fetch('/api/v1/admin/parking/tariffs');
    const rows = await response.json();
    return rows.find((row: any) => row.code === 'E2E-TAR-SCHEDULED');
  });
  expect(unchangedAfterConflict.id).toBe(scheduledBefore.id);
  expect(unchangedAfterConflict.rowVersion).toBe(scheduledBefore.rowVersion);
  expect(unchangedAfterConflict.amount).toBe(scheduledBefore.amount);
  expect(unchangedAfterConflict.validFrom).toBe(scheduledBefore.validFrom);
  expect(unchangedAfterConflict.validTo).toBe(scheduledBefore.validTo);
  await scheduledEditor.locator('[formControlName="validFrom"]').fill(`${plusDays(16)}T12:00`);
  await scheduledEditor.locator('[formControlName="validTo"]').fill(`${plusDays(19)}T12:00`);
  const editScheduledTariff = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/admin\/parking\/tariffs\//.test(new URL(r.url()).pathname));
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
  await revisedCalendarRow.getByRole('button', { name: 'Historial' }).click();
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

  expect(mutationResponses.length).toBeGreaterThanOrEqual(7);
  expect(mutationResponses.every(item => item.status >= 200 && item.status < 300)).toBe(true);
});
