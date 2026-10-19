import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const preferenceKey = 'simertpi.admin.ui.sidebar';

test('Poppins uses the same licensed local files as the citizen application', async ({ page }) => {
  for (const file of [
    'Poppins-Regular.ttf',
    'Poppins-Medium.ttf',
    'Poppins-SemiBold.ttf',
  ]) {
    const hash = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');
    expect(hash(`public/fonts/${file}`)).toBe(hash(`../simertpi-citizen-app/assets/fonts/${file}`));
  }
  const license = (path: string) => readFileSync(path, 'utf8').replace(/\r\n?/g, '\n').trimEnd();
  expect(license('public/fonts/OFL.txt')).toBe(license('../simertpi-citizen-app/assets/fonts/OFL.txt'));
  await page.goto('/preview/personal');
  await page.evaluate(async () => {
    await Promise.all(
      [400, 500, 600].map((weight) => document.fonts.load(`${weight} 16px Poppins`)),
    );
  });
  expect(
    await page.evaluate(() =>
      [400, 500, 600].every((weight) => document.fonts.check(`${weight} 16px Poppins`)),
    ),
  ).toBe(true);
  const families = await page
    .locator('h1, .nav-item, .button, input, select, th, label, .notice')
    .evaluateAll((elements) => elements.map((element) => getComputedStyle(element).fontFamily));
  expect(families.every((family) => family.startsWith('Poppins'))).toBe(true);
  const sizes = await page
    .locator('.nav-item, .pending-label, .preview-banner, .main-footer, th, label')
    .evaluateAll((elements) =>
      elements.map((element) => parseFloat(getComputedStyle(element).fontSize)),
    );
  expect(sizes.every((size) => size >= 12)).toBe(true);
});

test('desktop toggles width, preserves accessible names and persists only a visual preference', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/preview/dashboard');
  const column = page.locator('.main-column');
  const expandedLeft = (await column.boundingBox())!.x;
  await expect(page.getByRole('button', { name: 'Contraer menú lateral' })).toHaveAttribute(
    'aria-expanded',
    'true',
  );
  await page.getByRole('button', { name: 'Contraer menú lateral' }).focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('button', { name: 'Expandir menú lateral' })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  expect((await column.boundingBox())!.x).toBeLessThan(expandedLeft);
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  expect(
    await page
      .locator('.nav-label')
      .first()
      .evaluate((element) => getComputedStyle(element).clipPath),
  ).toBe('inset(50%)');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Expandir menú lateral' })).toBeVisible();
  expect(await page.evaluate(() => ({ ...localStorage }))).toEqual({
    [preferenceKey]: 'collapsed',
  });
  expect(await page.evaluate(() => sessionStorage.length)).toBe(0);
  expect(await page.context().cookies()).toEqual([]);
  await page.getByRole('button', { name: 'Expandir menú lateral' }).click();
  expect((await column.boundingBox())!.x).toBe(expandedLeft);
  expect(await page.evaluate((key) => localStorage.getItem(key), preferenceKey)).toBe('expanded');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'artifacts/b1-sidebar-expanded.png', fullPage: true });
});

test('collapsed tooltips support hover, keyboard, remaining pending items, Escape and accessibility', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/preview/dashboard');
  await page.getByRole('button', { name: 'Contraer menú lateral' }).click();
  const personnel = page.getByRole('link', { name: 'Personal municipal', exact: true });
  await personnel.hover();
  await expect(page.getByRole('tooltip')).toHaveText('Personal municipal');
  await page.getByRole('tooltip').hover();
  await expect(page.getByRole('tooltip')).toBeVisible();
  await personnel.focus();
  await expect(personnel).toHaveAttribute('aria-describedby', 'navigation-tooltip');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('tooltip')).toHaveCount(0);
  const pending = page.getByRole('link', {
    name: 'Inspectores · Pendiente',
    exact: true,
  });
  await pending.focus();
  await expect(pending).toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByRole('tooltip')).toHaveText('Inspectores · Pendiente');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/dashboard$/);
  await page.keyboard.press('Escape');
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(
    result.violations.map((item) => ({
      id: item.id,
      nodes: item.nodes.map((node) => node.target),
    })),
  ).toEqual([]);
  await page.screenshot({ path: 'artifacts/b1-sidebar-collapsed.png', fullPage: true });
  await personnel.focus();
  await expect(page.getByRole('tooltip')).toHaveText('Personal municipal');
  await expect(page.getByRole('tooltip')).toBeVisible();
  // Full-page captures resize the viewport and intentionally dismiss responsive tooltips.
  await page.screenshot({ path: 'artifacts/b1-sidebar-tooltip.png', fullPage: false });
  await expect(page.getByRole('tooltip')).toBeVisible();
});

test('drawer retains desktop preference, traps keyboard focus and closes on selection', async ({
  page,
}) => {
  await page.goto('/preview/dashboard');
  await page.getByRole('button', { name: 'Contraer menú lateral' }).click();
  await page.setViewportSize({ width: 820, height: 1180 });
  const toggle = page.locator('.topbar button[aria-controls="sidebar"]');
  await expect(page.locator('#sidebar')).toHaveAttribute('inert', '');
  await toggle.click();
  await expect(page.locator('.sidebar nav a').first()).toBeFocused();
  await expect(page.locator('.portal-layout')).not.toHaveClass(/is-collapsed/);
  const last = page.locator('.sidebar [tabindex="0"]').last();
  await last.focus();
  await page.keyboard.press('Tab');
  await expect(page.locator('.drawer-close')).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(last).toBeFocused();
  await page.getByRole('link', { name: 'Personal municipal', exact: true }).click();
  await expect(page).toHaveURL(/personal$/);
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#main-content')).toBeFocused();
  await toggle.click();
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(
    result.violations.map((item) => ({
      id: item.id,
      nodes: item.nodes.map((node) => node.target),
    })),
  ).toEqual([]);
  await page.screenshot({ path: 'artifacts/b1-drawer-tablet.png', fullPage: true });
  await page.keyboard.press('Escape');
  await expect(toggle).toBeFocused();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByRole('button', { name: 'Expandir menú lateral' })).toBeVisible();
});

test('drawer remains usable when visual storage is blocked', async ({ page }) => {
  await page.addInitScript(() =>
    Object.defineProperty(window, 'localStorage', {
      get: () => {
        throw new DOMException('denied', 'SecurityError');
      },
    }),
  );
  await page.goto('/preview/dashboard');
  await page.getByRole('button', { name: 'Contraer menú lateral' }).click();
  await expect(page.getByRole('button', { name: 'Expandir menú lateral' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.topbar button[aria-controls="sidebar"]').click();
  await expect(page.locator('.drawer-close')).toBeVisible();
  await page.locator('.drawer-close').click();
  await expect(page.locator('#sidebar')).toHaveAttribute('inert', '');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
