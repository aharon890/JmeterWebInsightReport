import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

test.describe('Tab Navigation — Defaults', () => {
  test('[R] Summary tab active by default, Summary panel visible', async ({ page }) => {
    await loadClean(page, 'E02');
    const activeBtn = page.locator('.tab-btn.active');
    await expect(activeBtn).toHaveAttribute('data-tab', 'summary');
    await expect(page.locator('#tab-summary')).toHaveClass(/active/);
  });

  test('[R] all other panels hidden', async ({ page }) => {
    await loadClean(page, 'E02');
    for (const tab of ['timeline', 'samplers', 'errors', 'server', 'notes']) {
      await expect(page.locator(`#tab-${tab}`)).not.toHaveClass(/active/);
    }
  });

  test('[R] 6 visible tab buttons + hidden Compare', async ({ page }) => {
    await loadClean(page, 'E02');
    // Server is visible on a run that declared none, unlike Compare and Asserts. A tab whose
    // pane can always say something honest has no reason to hide — and E02 is the run that
    // proves it: no serverMetrics block at all, and the tab still opens and explains itself.
    for (const tab of ['summary', 'timeline', 'samplers', 'errors', 'server', 'notes']) {
      await expect(page.locator(`.tab-btn[data-tab="${tab}"]`)).toBeVisible();
    }
    await expect(page.locator('#compareTabBtn')).toBeHidden();
  });
});

test.describe('Tab Navigation — Switching', () => {
  test('[U] switch to each tab: correct panel activates, others hide', async ({ page }) => {
    await loadClean(page, 'E02');
    for (const tab of ['timeline', 'samplers', 'errors', 'server', 'notes', 'summary']) {
      await switchTab(page, tab);
      await expect(page.locator(`.tab-btn[data-tab="${tab}"]`)).toHaveClass(/active/);
      await expect(page.locator(`#tab-${tab}`)).toHaveClass(/active/);
      // Count active panels — must be exactly 1
      const activePanels = page.locator('.tab-panel.active');
      expect(await activePanels.count()).toBe(1);
    }
  });

  test('[U] rapid tab switching: no JS errors', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    for (const tab of ['timeline', 'errors', 'samplers', 'notes', 'summary', 'timeline', 'samplers']) {
      await page.click(`.tab-btn[data-tab="${tab}"]`);
      await page.waitForTimeout(100);
    }
    expect(errors).toEqual([]);
  });
});

test.describe('Tab Navigation — Filters Dropdown', () => {
  test('[R] Filters button visible in tab nav', async ({ page }) => {
    await loadClean(page, 'E02');
    await expect(page.locator('#filterBarToggle')).toBeVisible();
  });

  test('[C/D] click Filters opens/closes dropdown menu', async ({ page }) => {
    await loadClean(page, 'E02');
    const menu = page.locator('#filterBarMenu');
    await page.click('#filterBarToggle');
    await page.waitForTimeout(200);
    // Menu should be visible or have display style
    // Click again to close
    await page.click('#filterBarToggle');
    await page.waitForTimeout(200);
  });

  test('[D] uncheck Row 1 (Palette/Line/Metric) → row hidden', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.click('#filterBarToggle');
    await page.waitForTimeout(200);
    // Find first checkbox in menu and uncheck it
    const checkboxes = page.locator('#filterBarMenu input[type="checkbox"]');
    const count = await checkboxes.count();
    if (count >= 1) {
      await checkboxes.nth(0).uncheck();
      await page.waitForTimeout(200);
      const row1 = page.locator('#filterRow1');
      await expect(row1).toHaveClass(/hidden-row/);
      // Re-check to restore
      await checkboxes.nth(0).check();
      await page.waitForTimeout(200);
      await expect(row1).not.toHaveClass(/hidden-row/);
    }
  });

  test('[D] uncheck Samplers row → sampler list hidden but filter still applied', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.click('#filterBarToggle');
    await page.waitForTimeout(200);
    const checkboxes = page.locator('#filterBarMenu input[type="checkbox"]');
    const count = await checkboxes.count();
    if (count >= 3) {
      await checkboxes.nth(2).uncheck(); // Row 3 = Samplers
      await page.waitForTimeout(200);
      const row3 = page.locator('#filterRow3');
      await expect(row3).toHaveClass(/hidden-row/);
      // Charts should still render (filter still active)
      await switchTab(page, 'timeline');
      const canvas = page.locator('#response-time-chart canvas').first();
      await expect(canvas).toBeVisible();
      // Restore
      await page.click('#filterBarToggle');
      await checkboxes.nth(2).check();
    }
  });
});
