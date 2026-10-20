import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Product bugs the 2026-10-19 docs-vs-code check found: each was described as working in the
 * documentation, and each is pinned here against the behaviour the docs promise (PB1-PB10,
 * PB13, PB14 of tmp/PUBLIC-REPO-AND-DOCS-PLAN.md).
 */


async function evaluate<T>(page: Page, fn: () => T): Promise<T> {
  return page.evaluate(fn);
}

test.describe('Compare Apply keeps Java\'s regression rule [CMP]', () => {

  test('a removed sampler stays a regression after Apply, and What Changed names it as removed', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
    const r = await evaluate(page, () => {
      const w = window as any;
      const diffs = w.globalChartData.comparisonDiffs;
      const template = diffs.find((d: any) => !d.isNew && !d.removed);
      diffs.push({ name: 'Gone sampler', isNew: false, removed: true, regression: true,
        baseSamples: template.currSamples, baseP95: template.currP95 });
      w.applyComparisonThresholds();
      const gone = diffs.find((d: any) => d.name === 'Gone sampler');
      return { regression: gone.regression, summary: document.getElementById('whatChangedContent')!.textContent };
    });
    expect(r.regression).toBe(true);
    expect(r.summary).toContain('Gone sampler (removed)');
    expect(r.summary).not.toContain('Gone sampler ( P95)');
  });

  test('a baseline P95 of 0 followed by any increase is +100% on Apply, as in Java', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
    const regression = await evaluate(page, () => {
      const w = window as any;
      const diffs = w.globalChartData.comparisonDiffs;
      const template = diffs.find((d: any) => !d.isNew && !d.removed);
      // Java ships p95PctChange 0 when the baseline is 0 (getP95PctChange guards the division)
      diffs.push(Object.assign({}, template, { name: 'Was zero', currP95: 50, deltaP95: 50,
        p95PctChange: 0, deltaErrorRate: 0, regression: true }));
      (document.getElementById('thP95') as HTMLInputElement).value = '10';
      w.applyComparisonThresholds();
      return diffs.find((d: any) => d.name === 'Was zero').regression;
    });
    expect(regression).toBe(true);
  });

  test('Samples deltas are neutral, not red [CMP]', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
    const html = await evaluate(page, () => (window as any).inlineDelta(25, '', 'neutral'));
    expect(html).toContain('delta-neutral');
    expect(html).not.toContain('delta-bad');
  });

  test('the Compare Σ Total line follows the Metric toggle [CMP]', async ({ page }) => {
    await loadClean(page, 'CMP');
    const r = await evaluate(page, () => {
      const w = window as any;
      w.samplerVisibility['Σ Total'] = true;
      const pick = (metric: string) => {
        const s = w.buildComparisonSeries(w.globalChartData, metric)
          .find((x: any) => x.name === 'Σ Total (Current)');
        return s ? JSON.stringify(s.data) : null;
      };
      return { mean: pick('responseTime'), p95: pick('p95') };
    });
    expect(r.mean).not.toBeNull();
    expect(r.p95).not.toBeNull();
    expect(r.p95).not.toBe(r.mean);
  });
});

test.describe('Filter bar and theme [E02]', () => {

  test('the Default preset leaves Σ Total off', async ({ page }) => {
    await loadClean(page, 'E02');
    const on = await evaluate(page, () => {
      const w = window as any;
      w.resetToDefaults();
      return w.samplerVisibility['Σ Total'];
    });
    expect(on).toBe(false);
  });

  test('a colorblind mode restored from storage says "(on)"', async ({ page }) => {
    await loadClean(page, 'E02');
    await page.evaluate(() => localStorage.setItem('wir_colorblindMode', 'true'));
    await page.reload();
    await expect(page.locator('#cbModeToggle')).toHaveAttribute('title', 'Colorblind mode (on)');
  });
});

test.describe('Samplers table [E02]', () => {

  test('after a column is dragged, unchecking it in the Columns menu hides that column', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const hidden = await evaluate(page, () => {
      const w = window as any;
      const table = document.getElementById('samplerTable') as HTMLTableElement;
      const headers = () => Array.from(table.tHead!.rows[0].cells);
      const logicalOf = (th: HTMLTableCellElement, i: number) => th.getAttribute('data-col-id') ?? String(i);
      headers().forEach((th, i) => { if (th.getAttribute('data-col-id') == null) th.setAttribute('data-col-id', String(i)); });
      w.reorderColumns(table, 2, 5);
      const target = headers()[5];                     // logical column 2, now at position 5
      const logical = logicalOf(target, 5);
      w.hiddenColumns = {};
      w.hiddenColumns[logical] = true;
      w.applyColumnVisibility();
      return headers().filter(th => th.style.display === 'none').map(th => th.getAttribute('data-col-id'));
    });
    expect(hidden).toEqual(['2']);
  });

  test('Show Hidden Rows restores a transaction child hidden with ×', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    await page.locator('#transactionChildrenBtn').click();          // children shown
    const child = page.locator('#samplerTable tbody tr.transaction-child').first();
    await expect(child).toBeVisible();
    await child.locator('.row-hide-btn').click();
    await expect(child).toBeHidden();
    await page.locator('#showHiddenRowsBtn').click();
    await expect(child).toBeVisible();
  });
});

test.describe('Errors tab [E05]', () => {

  test('Error Breakdown Fullscreen enlarges both of its charts, and Exit restores them', async ({ page }) => {
    await loadClean(page, 'E05');
    await switchTab(page, 'errors');
    const section = page.locator('section[data-chart-id="error-breakdown"]');
    const btn = section.locator('.chart-fullscreen-btn');
    await btn.click();
    await expect(section).toHaveClass(/chart-fullscreen/);
    await expect(btn).toHaveText('Exit Fullscreen');
    expect(await page.locator('#error-by-type-chart').evaluate(e => (e as HTMLElement).style.height)).toContain('100vh');
    await btn.click();
    await expect(section).not.toHaveClass(/chart-fullscreen/);
    expect(await page.locator('#error-by-sampler-chart').evaluate(e => (e as HTMLElement).style.height)).toBe('300px');
  });

  test('the Error Details ellipsis truncates Message, not Class', async ({ page }) => {
    await loadClean(page, 'E05');
    await switchTab(page, 'errors');
    const r = await page.locator('#errorDetailsTable tbody tr').first().evaluate(row => {
      const cells = (row as HTMLTableRowElement).cells;
      return { cls: getComputedStyle(cells[3]).textOverflow, msg: getComputedStyle(cells[4]).textOverflow };
    });
    expect(r.msg).toBe('ellipsis');
    expect(r.cls).not.toBe('ellipsis');
  });
});

test.describe('Copy that used to say what the code does not [E25]', () => {

  test('Copy Summary copies the P95/P99 labels without the verdict badge', async ({ page }) => {
    await loadClean(page, 'E25');
    const html = await evaluate(page, () => {
      let copied = '';
      (window as any).fallbackCopyText = (t: string) => { copied = t; };
      Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
      (window as any).copySummaryAsHtml();
      return copied;
    });
    expect(html).toContain('P95, whole run');
    expect(html).not.toMatch(/P95, whole run\s*(PASS|WARN|FAIL)/);
  });

  test('the shipped page no longer says "nothing is hidden" or "drop it next to the JTL"', async ({ page }) => {
    await loadClean(page, 'E25');
    const src = await page.content();
    expect(src).not.toContain('nothing is hidden');
    expect(src).not.toContain('drop it next to the JTL');
    expect(src).toContain('received KB/s and apdex seed as');
  });
});
