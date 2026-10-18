import { test, expect } from '@playwright/test';
import { loadClean } from './helpers';

test('[C/R] beforeprint realises charts on tabs that were never opened', async ({ page }) => {
  await loadClean(page, 'E02');
  const before = await page.evaluate(() => (window as any).pendingChartInits.length);
  expect(before, 'some charts should still be pending on a freshly loaded report').toBeGreaterThan(0);

  await page.evaluate(() => window.dispatchEvent(new Event('beforeprint')));
  await page.waitForTimeout(500);

  const after = await page.evaluate(() => {
    const w = window as any;
    const canvases = Array.from(document.querySelectorAll('.tab-panel canvas')) as HTMLCanvasElement[];
    return {
      pending: w.pendingChartInits.length,
      charts: w.chartInstances.length,
      drawnCanvases: canvases.filter((c) => c.width > 0 && c.height > 0).length,
    };
  });
  expect(after.pending, 'nothing may remain pending when printing').toBe(0);
  expect(after.drawnCanvases).toBeGreaterThan(before);
});
