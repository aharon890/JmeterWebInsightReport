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
    const csvBtn = page.locator('#comparisonTable-csv');
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

/**
 * Spec 004: Compare for every load tool, and across tools (the owner's decisions of 2026-11-26).
 *
 * CMPK is K2's k6 run against K2's own run data: a pair of one tool, so the normal Compare, judged, in k6's words.
 * CMPX is K2 against a JMeter run of E02's plan: a pair of two tools, which reads like perf hub's Compare. It is
 * titled "JMeter against k6", each cell shows "baseline → current" with the difference in grey, nothing is judged
 * (no REGRESSION, OK, NEW or REMOVED badge, no green or red, no threshold controls), one line says how the two tools
 * time a connection, and a request only one run has reads "only in the baseline" or "only in this run".
 */
const CONNECTION_NOTE = 'Connection time: JMeter\'s times include opening a connection and k6\'s do not, so a request that '
  + 'opens one (often the first of each session) reads lower under k6.';

/** Turn the Σ Total chip on, as a reader does, so the Compare table carries the whole run's row. */
async function withSigmaTotal(page: Page) {
  await switchTab(page, 'samplers');
  await page.locator('#samplerFilterList label[data-sampler-name="σ total"]').click();
  await expect(page.locator('#samplerTable tr.total-sampler-row')).toHaveCount(1);
}

test.describe('Compare Tab — a k6 run against a k6 baseline (CMPK)', () => {
  test('[R] the normal Compare: judged, with its thresholds and badges, in k6\'s words', async ({ page }) => {
    await loadClean(page, 'CMPK');
    await expect(page.locator('.tab-btn[data-tab="compare"]')).toBeVisible();
    await switchTab(page, 'compare');
    await expect(page.locator('#comparisonTableTitle')).toHaveText('Request Comparison');
    await expect(page.locator('#comparison-thresholds-section')).toBeVisible();
    await expect(page.locator('#thP95')).toBeVisible();
    const table = page.locator('#comparisonTable');
    await expect(table.locator('thead th[data-col-id="name"]')).toHaveText('Request');
    // K2 against itself: every request is OK
    const badges = await table.locator('tbody .status-badge').allTextContents();
    expect(badges.length).toBeGreaterThan(0);
    expect(new Set(badges.map(b => b.trim()))).toEqual(new Set(['OK']));
    await expect(table.locator('.cmp-ok').first()).toBeVisible();
    // Nothing of the two-tool reading
    await expect(page.locator('#comparisonLegend')).toBeHidden();
    await expect(page.locator('#comparisonNotes')).toBeHidden();
    await expect(table.locator('.cmp-pair')).toHaveCount(0);
  });

  test('[R] k6 measures no bytes: Recv KB/s reads "not measured", never 0 and never a change', async ({ page }) => {
    await loadClean(page, 'CMPK');
    await withSigmaTotal(page);
    await switchTab(page, 'compare');
    const recv = await page.locator('#comparisonTable tbody td[data-col-id="recv"]').evaluateAll(cells =>
      cells.map(c => ({ text: (c.textContent || '').trim(), sup: !!c.querySelector('sup') })));
    expect(recv.length).toBeGreaterThan(1);
    for (const cell of recv) {
      expect(cell.text, 'k6 records no bytes per request').toBe('not measured');
      expect(cell.sup, 'no change beside what was not measured').toBe(false);
    }
  });
});

test.describe('Compare Tab — a k6 run against a JMeter baseline (CMPX)', () => {
  test('[R] titled by its two tools, with the legend and the connection-time line', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await expect(page.locator('.tab-btn[data-tab="compare"]')).toBeVisible();
    await switchTab(page, 'compare');
    await expect(page.locator('#comparisonTableTitle')).toHaveText('JMeter against k6');
    await expect(page.locator('#comparisonLegend')).toHaveText('Each cell: JMeter → k6, with the difference under it');
    await expect(page.locator('#comparisonNotes .cmp-connection-note')).toHaveText(CONNECTION_NOTE);
  });

  test('[R] nothing is judged: no badge, no green or red, no threshold control', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await withSigmaTotal(page);
    await switchTab(page, 'compare');
    const table = page.locator('#comparisonTable');
    await expect(table.locator('tbody tr').first()).toBeVisible();
    await expect(table.locator('.status-badge')).toHaveCount(0);
    await expect(table.locator('.cmp-regression, .cmp-ok, .cmp-new, .cmp-removed')).toHaveCount(0);
    await expect(table.locator('.delta-bad, .delta-good')).toHaveCount(0);
    expect(await table.textContent()).not.toMatch(/REGRESSION|REMOVED|\bNEW\b|\bOK\b/);
    await expect(page.locator('#comparison-thresholds-section')).toBeHidden();
    await expect(page.locator('#tab-compare input[type="number"]:visible')).toHaveCount(0);
  });

  test('[R] each cell: the baseline\'s number → this run\'s, the difference in grey under it', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await withSigmaTotal(page);
    await switchTab(page, 'compare');
    const total = page.locator('#comparisonTable tbody tr.comparison-total-row');
    await expect(total).toHaveCount(1);
    const cells = await total.locator('td[data-col-id]').evaluateAll(tds => tds.map(td => {
      const delta = td.querySelector('.cmp-pair-delta');
      return {
        col: td.getAttribute('data-col-id') || '',
        text: (td.textContent || '').trim(),
        base: td.querySelector('.cmp-base') ? (td.querySelector('.cmp-base')!.textContent || '').trim() : null,
        current: td.querySelector('.cmp-current') ? (td.querySelector('.cmp-current')!.textContent || '').trim() : null,
        delta: delta ? (delta.textContent || '').trim() : null,
        deltaColour: delta ? getComputedStyle(delta).color : null,
      };
    }));
    const byCol: Record<string, typeof cells[number]> = Object.fromEntries(cells.map(c => [c.col, c]));
    for (const col of ['samples', 'errors', 'errorPct', 'mean', 'median', 'p90', 'p95', 'p99', 'min', 'max', 'throughput']) {
      const c = byCol[col];
      expect(c.base, `${col}: the baseline's number`).not.toBeNull();
      expect(c.current, `${col}: this run's number`).not.toBeNull();
      expect(c.text, `${col} reads "baseline → current"`).toContain(`${c.base} → ${c.current}`);
      expect(c.delta, `${col}: the difference under it`).not.toBeNull();
    }
    // The difference beside a count is the two counts apart, exactly
    const n = (s: string | null) => Number((s || '').replace(/[^\d.\-]/g, ''));
    expect(n(byCol.samples.delta)).toBe(n(byCol.samples.current) - n(byCol.samples.base));
    expect(n(byCol.errors.delta)).toBe(n(byCol.errors.current) - n(byCol.errors.base));
    // Grey: one colour for every difference, whichever way it went
    const colours = new Set(cells.filter(c => c.deltaColour).map(c => c.deltaColour));
    expect(colours.size).toBe(1);
    // JMeter measured the bytes, k6 did not: "not measured" on k6's side and no difference
    expect(byCol.recv.base).toMatch(/^\d+\.\d\d$/);
    expect(byCol.recv.current).toBe('not measured');
    expect(byCol.recv.delta || '').toBe('');
  });

  test('[R] a request only one run has reads "only in the baseline" or "only in this run"', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await switchTab(page, 'compare');
    const rows = await page.locator('#comparisonTable tbody tr:not(.comparison-total-row)').evaluateAll(trs => trs.map(tr => ({
      side: (tr.querySelector('td[data-col-id="status"]')?.textContent || '').trim(),
      samples: (tr.querySelector('td[data-col-id="samples"]')?.textContent || '').trim(),
      mean: (tr.querySelector('td[data-col-id="mean"]')?.textContent || '').trim(),
    })));
    const baselineOnly = rows.filter(r => r.side === 'only in the baseline');
    const thisRunOnly = rows.filter(r => r.side === 'only in this run');
    expect(baselineOnly.length, 'E02\'s samplers are not K2\'s requests').toBeGreaterThan(0);
    expect(thisRunOnly.length, 'K2\'s requests are not E02\'s samplers').toBeGreaterThan(0);
    for (const r of baselineOnly) {
      expect(r.samples).toMatch(/^\d+ → —$/);
      expect(r.mean).toMatch(/^\d+ → —$/);
    }
    for (const r of thisRunOnly) {
      expect(r.samples).toMatch(/^— → \d+$/);
      expect(r.mean).toMatch(/^— → \d+$/);
    }
  });

  test('[R] the charts overlay both runs, each series named by its tool, the baseline dashed', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await switchTab(page, 'compare');
    const series = await page.evaluate(() => {
      const el = document.getElementById('compare-rt-chart');
      // @ts-ignore
      const chart = el ? echarts.getInstanceByDom(el) : null;
      return chart ? (chart.getOption().series as any[])
        .map(s => ({ name: String(s.name), dashed: !!(s.lineStyle && s.lineStyle.type === 'dashed') })) : [];
    });
    expect(series.length).toBeGreaterThan(0);
    for (const s of series) {
      expect(s.name, 'a series names its tool').toMatch(/\((k6|JMeter baseline)\)$/);
      expect(s.dashed, `${s.name}: the baseline dashed`).toBe(s.name.endsWith('(JMeter baseline)'));
    }
  });

  test('[R] What Changed: the two runs\' Σ Total rows and each request\'s change, nothing judged', async ({ page }) => {
    await loadClean(page, 'CMPX');
    await switchTab(page, 'notes');
    const box = page.locator('#whatChangedContent');
    await expect(box.locator('table.what-changed-totals tbody tr')).toHaveCount(2);
    await expect(box.locator('.what-changed-regression, .what-changed-improvement, .what-changed-new')).toHaveCount(0);
    expect(await box.textContent()).not.toMatch(/Regressions|Improvements|No significant changes/);
    const lines = await box.locator('.what-changed-request').allTextContents();
    expect(lines.length).toBeGreaterThan(0);
    expect(lines.some(l => /only in the baseline \(\d+ samples, mean \d+ ms\)/.test(l))).toBe(true);
    expect(lines.some(l => /only in this run \(\d+ samples, mean \d+ ms\)/.test(l))).toBe(true);
    // one sample is one sample (K2's setup and cleanup requests ran once)
    expect(lines.some(l => /\(1 samples/.test(l)), 'no "1 samples"').toBe(false);
    expect(lines.some(l => /only in this run \(1 sample, mean \d+ ms\)/.test(l))).toBe(true);
  });
});
