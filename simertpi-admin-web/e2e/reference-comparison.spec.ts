import { expect, test, type Page } from '@playwright/test';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { mkdirSync, writeFileSync } from 'node:fs';

const zoneId = '00000000-0000-4000-8000-000000000011';
const streetId = '00000000-0000-4000-8000-000000000022';
const spaceId = '00000000-0000-4000-8000-000000000033';
const zone2Id = '00000000-0000-4000-8000-000000000012';
const zone3Id = '00000000-0000-4000-8000-000000000013';
const street2Id = '00000000-0000-4000-8000-000000000023';
const street3Id = '00000000-0000-4000-8000-000000000024';
const space2Id = '00000000-0000-4000-8000-000000000034';
const space3Id = '00000000-0000-4000-8000-000000000035';
const space4Id = '00000000-0000-4000-8000-000000000036';
const now = '2026-10-25T12:00:00Z';
const identity = {
  userId: '00000000-0000-4000-8000-000000000044', username: 'qa-visual-fixture',
  roles: ['SIMERTPI_ADMIN'], enabled: true,
  sessionExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  absoluteExpiresAt: new Date(Date.now() + 7_200_000).toISOString(),
};
const zone = {
  id: zoneId, code: 'QA-VISUAL-Z', name: 'Zona sintética de comparación',
  description: 'Datos locales ilustrativos', active: true, createdAt: now, updatedAt: now,
  streetCount: 1, spaceCount: 1, activeSpaceCount: 1,
};
const street = {
  id: streetId, zoneId, zoneCode: zone.code, zoneName: zone.name,
  code: 'QA-VISUAL-S', name: 'Calle sintética', description: null, active: true,
  createdAt: now, updatedAt: now, spaceCount: 1,
};
const space = {
  id: spaceId, zoneId, zoneCode: zone.code, zoneName: zone.name, streetId,
  streetCode: street.code, streetName: street.name, code: 'QA-VISUAL-P',
  qrCode: `SIMERTPI:SPACE:${spaceId}`, spaceNumber: 'QA-01', latitude: -3.67,
  longitude: -79.65, spaceType: 'STANDARD', active: true,
  operationalStatus: 'AVAILABLE', mapStatus: 'NO_ACTIVE_SESSION', createdAt: now, updatedAt: now,
};
// Synthetic visual-only fixtures mirror the number and semantic status variety
// in the approved HTML. They are intercepted by Playwright and never reach a database.
const zoneRows = [zone,
  { ...zone, id: zone2Id, code: 'QA-VISUAL-Z2', name: 'Zona secundaria sintética', active: true, streetCount: 1, spaceCount: 2, activeSpaceCount: 2 },
  { ...zone, id: zone3Id, code: 'QA-VISUAL-Z3', name: 'Zona deshabilitada sintética', active: false, streetCount: 1, spaceCount: 1, activeSpaceCount: 0 },
];
const streetRows = [street,
  { ...street, id: street2Id, code: 'QA-VISUAL-S2', name: 'Calle secundaria sintética', spaceCount: 2 },
  { ...street, id: street3Id, zoneId: zone2Id, zoneCode: 'QA-VISUAL-Z2', zoneName: 'Zona secundaria sintética', code: 'QA-VISUAL-S3', name: 'Calle de zona secundaria', spaceCount: 1 },
];
const spaceRows = [space,
  { ...space, id: space2Id, code: 'QA-VISUAL-P2', spaceNumber: 'QA-02', mapStatus: 'ACTIVE', operationalStatus: 'OCCUPIED', latitude: -3.6701, longitude: -79.6501 },
  { ...space, id: space3Id, code: 'QA-VISUAL-P3', spaceNumber: 'QA-03', mapStatus: 'ENDING_SOON', operationalStatus: 'OCCUPIED', latitude: -3.6702, longitude: -79.6502 },
  { ...space, id: space4Id, zoneId: zone3Id, zoneCode: 'QA-VISUAL-Z3', zoneName: 'Zona deshabilitada sintética', streetId: street3Id, streetCode: 'QA-VISUAL-S3', streetName: 'Calle de zona secundaria', code: 'QA-VISUAL-P4', spaceNumber: 'QA-04', latitude: null, longitude: null, active: false, operationalStatus: 'DISABLED', mapStatus: 'DISABLED' },
];
const viewports = [
  { width: 1536, height: 1024 }, { width: 1366, height: 768 },
  { width: 768, height: 1024 }, { width: 390, height: 844 },
];
const routes = {
  zones: { url: '/admin/zones', refTab: 'zonas', refRow: 'Z-01', heading: 'Zonas de estacionamiento', create: /Nueva zona/, row: 'QA-VISUAL-Z' },
  streets: { url: '/admin/streets', refTab: 'calles', refRow: 'C-01', heading: 'Calles y tramos', create: /Nueva calle/, row: 'QA-VISUAL-S' },
  spaces: { url: '/admin/spaces', refTab: 'espacios', refRow: 'E-001', heading: 'Espacios de estacionamiento', create: /Nuevo espacio/, row: 'QA-VISUAL-P' },
} as const;

async function installCatalogResponses(page: Page) {
  await page.route('**/api/v1/admin/auth/me', route => route.fulfill({ json: identity }));
  await page.route('**/api/v1/admin/parking/zones', route => route.fulfill({ json: [zone] }));
  await page.route('**/api/v1/admin/parking/configuration/effective**', route => route.fulfill({
    json: { generalTariff: null, generalBillingDaysConfigured: false,
      zones: [
        { zoneId, tariff: { code: 'QA-GENERAL', amount: '0.25', currency: 'USD', durationMinutes: 60 }, tariffSource: 'GENERAL' },
        { zoneId: zone2Id, tariff: { code: 'QA-ZONE', amount: '0.30', currency: 'USD', durationMinutes: 60 }, tariffSource: 'ZONE' },
        { zoneId: zone3Id, tariff: null, tariffSource: 'UNCONFIGURED' },
      ] },
  }));
  await page.route('**/api/v1/admin/parking/catalog/zones**', route => route.fulfill({
    json: { page: 0, size: 10, totalElements: zoneRows.length, totalPages: 1, content: zoneRows },
  }));
  await page.route('**/api/v1/admin/parking/catalog/streets**', route => route.fulfill({
    json: { page: 0, size: 10, totalElements: streetRows.length, totalPages: 1, content: streetRows },
  }));
  await page.route('**/api/v1/admin/parking/catalog/spaces**', route => route.fulfill({
    json: { page: 0, size: 10, totalElements: spaceRows.length, totalPages: 1, content: spaceRows },
  }));
  await page.route('**/api/v1/admin/parking/zones/*/streets', route => route.fulfill({ json: [street] }));
  await page.route('**/api/v1/admin/parking/streets/*/spaces', route => route.fulfill({ json: [space] }));
  await page.route('**/api/v1/admin/parking/catalog/map', route => route.fulfill({
    json: { totalSpaces: 1, georeferencedSpaces: 1, pendingGeoreference: 0, evaluatedAt: now,
      refreshIntervalSeconds: 60, markers: [space] },
  }));
  await page.route('**/api/v1/admin/parking/catalog/map/tiles/**', route => route.abort());
  await page.route('**/api/v1/admin/parking/catalog/spaces/nearby**', route => route.fulfill({ json: [] }));
}

async function prepareReference(page: Page, tab: string, state: 'list' | 'detail' | 'create' | 'edit') {
  await page.goto(pathToFileURL(resolve('../..', 'SIMERTPI_referencia_visual_zonas_calles_espacios.html')).href);
  await page.locator(`[data-tab="${tab}"]`).click();
  const row = page.locator('tbody tr[data-row]').first();
  if (state === 'detail' || state === 'edit') await row.click();
  if (state === 'create') await page.locator('#create').click();
  if (state === 'edit') await row.locator('[data-edit]').click();
}

async function prepareAngular(page: Page, key: keyof typeof routes, state: 'list' | 'detail' | 'create' | 'edit') {
  const route = routes[key];
  await page.goto(`http://127.0.0.1:4301${route.url}`);
  await expect(page.getByRole('heading', { name: route.heading, exact: true })).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: route.row }).first();
  if (state === 'detail') await row.getByRole('button', { name: 'Ver detalle' }).click();
  if (state === 'create') await page.getByRole('button', { name: route.create }).click();
  if (state === 'edit') await row.getByRole('button', { name: 'Editar' }).click();
  if (state === 'create' || state === 'edit') await expect(page.getByRole('dialog')).toBeVisible();
}

test('side-by-side comparison against the delivered HTML at all requested viewport sizes', async ({ page, browser }, testInfo) => {
  test.setTimeout(180_000);
  const evidenceDirectory = resolve('artifacts/reference-comparison-20261027');
  mkdirSync(evidenceDirectory, { recursive: true });
  const appPage = page;
  const referencePage = await browser.newPage();
  const board = await browser.newPage();
  await installCatalogResponses(appPage);
  const stateNames = ['list', 'detail', 'create', 'edit'] as const;

  for (const key of Object.keys(routes) as (keyof typeof routes)[]) {
    for (const state of stateNames) {
      await prepareReference(referencePage, routes[key].refTab, state);
      await prepareAngular(appPage, key, state);
      for (const viewport of viewports) {
        await referencePage.setViewportSize(viewport);
        await appPage.setViewportSize(viewport);
        await expect(appPage.locator('body')).toBeVisible();
        const referenceImage = await referencePage.screenshot({ animations: 'disabled' });
        const angularImage = await appPage.screenshot({ animations: 'disabled' });
        const name = `${key}-${state}-${viewport.width}x${viewport.height}`;
        writeFileSync(resolve(evidenceDirectory, `${name}-reference.png`), referenceImage);
        writeFileSync(resolve(evidenceDirectory, `${name}-angular.png`), angularImage);
        await testInfo.attach(`${name}-reference`, { body: referenceImage, contentType: 'image/png' });
        await testInfo.attach(`${name}-angular`, { body: angularImage, contentType: 'image/png' });
        await board.setViewportSize({ width: viewport.width * 2, height: viewport.height });
        await board.setContent(`<style>*{box-sizing:border-box}body{margin:0;font:12px Arial;background:#fff}.labels{height:26px;display:flex}.labels span{width:50%;padding:6px 10px;background:#eef2f5}main{display:flex;width:100%;height:calc(100vh - 26px)}img{display:block;width:50%;height:100%;object-fit:fill;border-right:1px solid #83909a}</style><div class="labels"><span>Referencia HTML</span><span>Angular</span></div><main><img alt="Referencia" src="data:image/png;base64,${referenceImage.toString('base64')}"><img alt="Angular" src="data:image/png;base64,${angularImage.toString('base64')}"></main>`);
        const comparisonImage = await board.screenshot({ animations: 'disabled' });
        writeFileSync(resolve(evidenceDirectory, `${name}-lado-a-lado.png`), comparisonImage);
        await testInfo.attach(`${name}-lado-a-lado`, { body: comparisonImage, contentType: 'image/png' });
      }
      if (state === 'create' || state === 'edit') {
        await appPage.getByRole('dialog').getByRole('button', { name: 'Cancelar', exact: true }).click();
        await referencePage.locator('#cancel').click();
      }
    }
  }
  await referencePage.close();
  await board.close();
});
