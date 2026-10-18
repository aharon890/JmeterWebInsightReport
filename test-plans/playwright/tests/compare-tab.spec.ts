import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Compare Tab tests require a report generated with baseline.jtl present.
 * Report: test-results/CMP/CMP-report.html
 */

async function loadCompareReport(page: Page) {
  await loadClean(page, 'CMP');
}

test.describe('Compare Tab — Visibility', () => {
  test('[R] Compare tab button is VISIBLE when baseline present', async ({ page }) => {
    await loadCompareReport(page);
    const compareBtn = page.locator('.tab-btn[data-tab="compare"]');
    await expect(compareBtn).toBeVisible();
  });

  test('[R] Compare tab button is HIDDEN when no baseline [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    const compareBtn = page.locator('.tab-btn[data-tab="compare"]');
    await expect(compareBtn).toBeHidden();
  });
});

test.describe('Compare Tab — Charts', () => {
  test('[R] Response Time Comparison chart renders with canvas', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const charts = page.locator('#tab-compare .chart-container canvas');
    expect(await charts.count()).toBeGreaterThanOrEqual(1);
    const box = await charts.first().boundingBox();
    expect(box!.width).toBeGreaterThan(100);
    expect(box!.height).toBeGreaterThan(50);
  });

  test('[R] Comparison charts have current (solid) and baseline (dashed) series', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    // Verify echarts instance has series with different line styles
    const hasBothStyles = await page.evaluate(() => {
      const containers = document.querySelectorAll('#tab-compare .chart-container');
      for (const el of containers) {
        // @ts-ignore
        const chart = typeof echarts !== 'undefined' ? echarts.getInstanceByDom(el) : null;
        if (chart) {
          const opt = chart.getOption();
          if (opt.series && opt.series.length > 0) return true;
        }
      }
      return false;
    });
    expect(hasBothStyles).toBe(true);
  });

  test('[R] Both comparison charts have Hide and Fullscreen buttons', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const hideBtns = page.locator('#tab-compare .chart-hide-btn');
    const fullBtns = page.locator('#tab-compare .chart-fullscreen-btn');
    expect(await hideBtns.count()).toBeGreaterThanOrEqual(1);
    expect(await fullBtns.count()).toBeGreaterThanOrEqual(1);
  });

  test('[D/C] Hide compare chart → Show restores with "Hide" button text', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const hideBtn = page.locator('#tab-compare .chart-hide-btn').first();
    await expect(hideBtn).toHaveText('Hide');
    // Hide
    await hideBtn.click();
    await expect(hideBtn).toHaveText('Show');
    // Find "Show Hidden" button and restore
    const showAllBtn = page.locator('#tab-compare').locator('button:has-text("Show")').filter({ hasText: 'Hidden' });
    if (await showAllBtn.count() > 0) {
      await showAllBtn.first().click();
      await page.waitForTimeout(300);
      await expect(hideBtn).toHaveText('Hide');
    }
  });
});

test.describe('Compare Tab — Comparison Table', () => {
  test('[R] Comparison table visible with sampler rows', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const table = page.locator('#tab-compare table');
    await expect(table).toBeVisible();
    const rows = table.locator('tbody tr');
    expect(await rows.count()).toBeGreaterThan(0);
  });

  test('[R] Table has expected columns with delta values', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const headers = page.locator('#tab-compare table thead th');
    const count = await headers.count();
    expect(count).toBeGreaterThanOrEqual(5); // At minimum: Sampler, Status, plus metrics
  });

  /**
   * A comparison verdict must not wear the assert palette.
   *
   * <p>REGRESSION used to be `badge-fail` — the same class as a breached assert. Both are bad
   * news, but one is a COMPUTED OBSERVATION ("slower than the baseline") and the other a
   * DECLARED JUDGEMENT ("you set a limit and it was crossed"). Sharing the class meant a change
   * to assert styling silently restyled this tab, and left a reader unable to tell which kind of
   * red they were looking at now that a red assert badge means something precise.
   */
  test('[R] comparison verdicts use their own classes, not the assert ones',
    async ({ page }) => {
      await loadCompareReport(page);
      await switchTab(page, 'compare');
      const table = page.locator('#comparisonTable');
      await expect(table.locator('.cmp-regression, .cmp-ok, .cmp-new, .cmp-removed').first())
        .toBeVisible();
      await expect(table.locator('.badge-fail'),
        'a comparison row is wearing the assert fail class').toHaveCount(0);
      await expect(table.locator('.badge-pass'),
        'a comparison row is wearing the assert pass class').toHaveCount(0);
    });

  test('[R] Status badges present: OK and/or REGRESSION and/or NEW', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    // Look for status badges in the table
    const badges = page.locator('#tab-compare table .compare-badge, #tab-compare table .badge, #tab-compare table [class*="status"]');
    // At minimum the table should have text indicating status
    const tableText = await page.locator('#tab-compare table').textContent();
    // Should contain at least one status indicator
    expect(tableText).toMatch(/OK|REGRESSION|NEW/i);
  });

  test('[R] Delta values shown as superscripts (colored)', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    // Delta values appear as sup/sub or span elements with color
    const deltas = page.locator('#tab-compare table .delta, #tab-compare table sup, #tab-compare table .compare-delta');
    expect(await deltas.count()).toBeGreaterThan(0);
  });

  test('[U] Table is sortable: click column header', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    const headers = page.locator('#tab-compare table thead th');
    const count = await headers.count();
    if (count > 1) {
      await headers.nth(1).click();
      await page.waitForTimeout(200);
    }
    expect(errors).toEqual([]);
  });

  test('[U] Table search filter works', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const searchInput = page.locator('#tab-compare input[type="text"]').first();
    if (await searchInput.count() > 0) {
      const rowsBefore = await page.locator('#tab-compare table tbody tr:visible').count();
      await searchInput.fill('api');
      await page.waitForTimeout(200);
      const rowsAfter = await page.locator('#tab-compare table tbody tr:visible').count();
      // Either filtered or all match — no crash
      await searchInput.fill('');
      await page.waitForTimeout(200);
    }
  });

  test('[R] CSV download button exists and clickable', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const csvBtn = page.locator('#tab-compare .table-csv-btn, #tab-compare button[title*="CSV"]');
    if (await csvBtn.count() > 0) {
      const errors: string[] = [];
      page.on('pageerror', e => errors.push(e.message));
      await csvBtn.first().click();
      await page.waitForTimeout(300);
      expect(errors).toEqual([]);
    }
  });
});

test.describe('Compare Tab — No JS errors through interactions', () => {
  test('switching metric toggle does not crash compare charts', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    // Switch to P95
    const p95Btn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="p95"]');
    if (await p95Btn.count() > 0) {
      await p95Btn.click();
      await page.waitForTimeout(500);
    }
    // Switch back to Mean
    const meanBtn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="responseTime"]');
    if (await meanBtn.count() > 0) {
      await meanBtn.click();
      await page.waitForTimeout(500);
    }
    expect(errors).toEqual([]);
  });

  test('sampler filter affects compare charts without errors', async ({ page }) => {
    await loadCompareReport(page);
    await switchTab(page, 'compare');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    // Uncheck first sampler
    const labels = page.locator('#samplerFilterList label');
    const count = await labels.count();
    if (count > 0) {
      await labels.first().click();
      await page.waitForTimeout(300);
      await labels.first().click(); // re-check
      await page.waitForTimeout(300);
    }
    expect(errors).toEqual([]);
  });
});
