import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * P2: the interim report (server asserts declared, nothing collected) tells the operator what to
 * do: the window to export, the exact names the CSV must carry, a CSV template, and how to add it.
 * Driven with the interim shape over E26's own document — the shape ServerMetricsGenerationTest
 * pins for a report generated without a payload — so no fixture has to be generated interim.
 */
test.describe('Server tab — how to finish an interim report', () => {
  async function interim(page: any) {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = { ...W.chartData };
      delete d.serverGates;
      delete d.serverKpis;
      delete d.serverMetrics;
      d.serverEvaluated = false;
      W.WIR.serverKpis.init(d);
    });
  }

  test('[R] the panel names the run window in UTC and epoch ms', async ({ page }) => {
    await interim(page);
    const panel = page.locator('#serverFinish');
    await expect(panel).toBeVisible();
    const w = await page.evaluate(() => (window as any).chartData.runWindow);
    expect(typeof w.start).toBe('number');
    await expect(panel).toContainText(new Date(w.start).toISOString());
    await expect(panel).toContainText(String(w.start));
    await expect(panel).toContainText('Tools › Generate HTML report');
  });

  test('[R] the panel names the listener’s Server data field and Rebuild button as the GUI route', async ({ page }) => {
    await interim(page);
    const panel = page.locator('#serverFinish');
    // The GUI route first, the menu kept for a JTL the listener did not write, and the shell last.
    await expect(panel).toContainText('Server data (CSV)');
    await expect(panel).toContainText('Rebuild report with server data');
    await expect(panel).toContainText('Tools › Generate HTML report');
    await expect(panel).toContainText('jmeter -g <results.jtl>');
    const text = (await panel.textContent()) || '';
    expect(text.indexOf('Rebuild report with server data'))
      .toBeLessThan(text.indexOf('Tools › Generate HTML report'));
  });

  test('[R] the panel lists every series a CSV must carry, by its exact names', async ({ page }) => {
    await interim(page);
    const rows = page.locator('#serverFinish tbody tr');
    expect(await rows.count()).toBeGreaterThan(0);
    // E26's custom asserts are named by their query text, which is what the metric cell must hold.
    await expect(page.locator('#serverFinish')).toContainText('kafka');
  });

  test('[R] the CSV template has the header and one row per series at the run start', async ({ page }) => {
    await interim(page);
    const text: string = await page.evaluate(() => (window as any).WIR.serverKpis._csvTemplateText());
    const lines = text.trim().split('\n');
    expect(lines[0]).toBe('time,component,metric,instance,value');
    const rows = await page.locator('#serverFinish tbody tr').count();
    expect(lines.length - 1).toBe(rows);
    const start = await page.evaluate(() => new Date((window as any).chartData.runWindow.start).toISOString());
    expect(lines[1].startsWith(start + ',')).toBe(true);
    // A custom query with = + - @ is never prefixed with a quote (csvQuote would).
    expect(text).not.toContain(",'");
  });
});
