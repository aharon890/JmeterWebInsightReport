import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loadClean, switchTab, getReportPath } from './helpers';
import { REQUIRED_FIXTURES } from '../global-setup';

/**
 * Every tab tells one story about the run's counts (the owner's ask, 2026-11-05).
 *
 * The tails finding (tails-conversation-flows: 0 errors shown where JMeter's Aggregate Report
 * counted 17) passed every suite, because every suite compared the report with itself and every
 * whole-run figure read one shared count. These tests read what each tab displays and know nothing
 * of where it comes from, so a tab that one day computes its own number cannot drift unnoticed:
 *
 *  - the whole run: the Summary tiles, the Samplers Σ Total row and the Timeline's Σ Total lines
 *    show the CI/CD file's totalSamples, totalErrors and errorRate. R07 holds that file to JMeter's
 *    Aggregate Report (what a listener in the plan received), so between the two a tab that differs
 *    from JMeter fails;
 *  - each sampler: its Samplers row shows the file's figures for it, which R07 holds to the
 *    Aggregate Report over every row of results.jtl;
 *  - the Errors tab counts failed requests, its own stated rule (JMeter's HTML report's): each
 *    sampler's errors there are its errors in the Samplers table, its Total is the sum of its rows
 *    and every failed request, and on a run without transactions the whole run's failures. Every
 *    sampler it names has a row in the Samplers table and a chip in the filter bar.
 *
 * One rule is not "equal": adding up the Samplers rows gives more than the whole run whenever a
 * request runs inside a transaction (the request and its transaction are both rows), so no test
 * here adds rows up to reach a whole-run figure.
 */

interface CiCdSampler { name: string; sampleCount: number; errorRate: number }
interface CiCd { totalSamples: number; totalErrors: number; errorRate: number; samplers: CiCdSampler[] }

function ciCdFile(id: string): CiCd {
  const html = getReportPath(id);
  return JSON.parse(fs.readFileSync(html.replace(/\.html$/, '.json'), 'utf8'));
}

/** A count or a percentage as a cell writes it: "7,225", "20⊕", "6.58%". */
function numberIn(text: string | null): number {
  const digits = (text || '').replace(/[^\d.\-]/g, '');
  if (digits === '') throw new Error(`no number in "${text}"`);
  return Number(digits);
}

/** The rate a count makes of the run, as the file writes it: its exact value, to two decimals. */
function expectRate(shown: number, errors: number, samples: number, what: string): void {
  const exact = samples > 0 ? errors * 100 / samples : 0;
  expect(Math.abs(shown - exact), `${what}: ${shown}% shown, ${errors} of ${samples} is ${exact}%`)
    .toBeLessThanOrEqual(0.005 + 1e-9);
}

async function summaryTile(page: Page, label: string): Promise<string> {
  const card = page.locator('#tab-summary .summary-card').filter({
    has: page.locator('.card-label', { hasText: new RegExp(`^\\s*${label}\\s*$`) }),
  });
  await expect(card, `the Summary tab has one "${label}" tile`).toHaveCount(1);
  return (await card.locator('.card-value').first().textContent() || '').trim();
}

/** Every row of the Samplers table, hidden ones included, as name -> its cells by column title. */
async function samplerRows(page: Page): Promise<{ rows: Map<string, Record<string, string>>; classes: Map<string, string>; total: Record<string, string> | null }> {
  const read = await page.evaluate(() => {
    const table = document.getElementById('samplerTable') as HTMLTableElement;
    const heads = Array.from(table.tHead!.rows[0].cells).map(c => (c.textContent || '').trim());
    const asRecord = (r: HTMLTableRowElement) => {
      const o: Record<string, string> = {};
      Array.from(r.cells).forEach((c, i) => { if (heads[i]) o[heads[i]] = (c.textContent || '').trim(); });
      return o;
    };
    const rows = Array.from(table.tBodies[0].rows);
    const total = rows.find(r => r.classList.contains('total-sampler-row'));
    return {
      total: total ? asRecord(total) : null,
      rows: rows.filter(r => r.getAttribute('data-sampler') !== null
          && !r.classList.contains('total-sampler-row')
          && !r.classList.contains('sampler-detail-row') && !r.classList.contains('wir-judging-row'))
        .map(r => ({ name: r.getAttribute('data-sampler')!, cls: r.className, cells: asRecord(r) })),
    };
  });
  const rows = new Map<string, Record<string, string>>();
  const classes = new Map<string, string>();
  for (const r of read.rows) {
    expect(rows.has(r.name), `"${r.name}" has one row in the Samplers table`).toBe(false);
    rows.set(r.name, r.cells);
    classes.set(r.name, r.cls);
  }
  return { rows, classes, total: read.total };
}

/** The Σ Total series a Timeline chart draws: [window start, value] points. */
async function totalLine(page: Page, chartId: string): Promise<Array<[number, number | null]> | null> {
  return page.evaluate((id) => {
    const dom = document.getElementById(id);
    const chart = dom && (window as any).echarts.getInstanceByDom(dom);
    if (!chart) return null;
    const s = (chart.getOption().series || []).find((x: any) => x.name === 'Σ Total');
    return s ? s.data : null;
  }, chartId);
}

for (const id of REQUIRED_FIXTURES) {
  test.describe(`${id}: every tab shows the run's own counts`, () => {
    test('the Summary tiles, the Σ Total row and the Timeline\'s Σ Total lines show the whole run of the CI/CD file', async ({ page }) => {
      const file = ciCdFile(id);
      expectRate(file.errorRate, file.totalErrors, file.totalSamples, 'the CI/CD file\'s errorRate');
      await loadClean(page, id);

      // Summary
      expect(numberIn(await summaryTile(page, 'Total Samples')), 'the Total Samples tile').toBe(file.totalSamples);
      expectRate(numberIn(await summaryTile(page, 'Error Rate')), file.totalErrors, file.totalSamples, 'the Error Rate tile');

      // Samplers: Σ Total on, as a reader turns it on, from its chip in the filter bar
      await switchTab(page, 'samplers');
      await page.locator('#samplerFilterList label[data-sampler-name="σ total"]').click();
      await expect(page.locator('#samplerTable tr.total-sampler-row')).toHaveCount(1);
      const { total } = await samplerRows(page);
      expect(numberIn(total!['Samples']), 'the Σ Total row\'s Samples').toBe(file.totalSamples);
      expect(numberIn(total!['Errors']), 'the Σ Total row\'s Errors').toBe(file.totalErrors);
      expectRate(numberIn(total!['Error %']), file.totalErrors, file.totalSamples, 'the Σ Total row\'s Error %');

      // Timeline, at the granularity the page picked for this run
      await switchTab(page, 'timeline');
      const granularitySeconds = Number(await page.locator('#granularitySelect').inputValue()) / 1000;
      await expect.poll(() => totalLine(page, 'throughput-chart'), { message: 'the Throughput chart draws a Σ Total line' })
        .not.toBeNull();
      // A point is the window's samples over the window's width, so a point times the width is a count
      const throughput = (await totalLine(page, 'throughput-chart'))!;
      const drawnSamples = throughput.reduce((sum, [, v]) => sum + (v == null ? 0 : v * granularitySeconds), 0);
      expect(Math.abs(drawnSamples - file.totalSamples),
        `the Throughput chart's Σ Total line adds up to ${drawnSamples} samples, the run had ${file.totalSamples}`)
        .toBeLessThan(0.05);

      const errorRate = await totalLine(page, 'error-rate-chart');
      if (file.totalErrors === 0) {
        // The chart says there were none instead of drawing a flat line
        await expect(page.locator('#error-rate-chart')).toContainText('No errors recorded');
      } else {
        expect(errorRate, 'the Error Rate chart draws a Σ Total line').not.toBeNull();
        // A point is the window's failures over the window's samples. The samples behind it are not
        // drawn, so they are read from what the chart was drawn from, per second, grouped into the
        // chart's windows (multiples of the granularity from the epoch, drawn at their start).
        const behind = await page.evaluate((g) => {
          const d = (window as any).globalChartData;
          const out: Record<string, number> = {};
          d.timestamps.forEach((t: number, i: number) => {
            const start = t - (t % (g * 1000));
            out[start] = (out[start] || 0) + (d.sampleCounts[i] || 0);
          });
          return out;
        }, granularitySeconds);
        const drawnErrors = errorRate!.reduce((sum, [t, v]) => sum + (v == null ? 0 : v * (behind[t] || 0) / 100), 0);
        // Each second's rate reaches the page written to two decimals: 0.005 points of the second's
        // samples at most, so 0.00005 of the run's samples over the whole line
        expect(Math.abs(drawnErrors - file.totalErrors),
          `the Error Rate chart's Σ Total line accounts for ${drawnErrors} failures, the run had ${file.totalErrors}`)
          .toBeLessThanOrEqual(0.5 + 0.00005 * file.totalSamples);
      }
    });

    test('each sampler\'s row in the Samplers table shows the CI/CD file\'s figures for it', async ({ page }) => {
      const file = ciCdFile(id);
      await loadClean(page, id);
      await switchTab(page, 'samplers');
      const { rows } = await samplerRows(page);

      expect([...rows.keys()].sort(), 'one row for every sampler the CI/CD file lists, and no other')
        .toEqual(file.samplers.map(s => s.name).sort());
      for (const s of file.samplers) {
        const row = rows.get(s.name)!;
        const samples = numberIn(row['Samples']);
        const errors = numberIn(row['Errors']);
        expect(samples, `"${s.name}": Samples`).toBe(s.sampleCount);
        expectRate(s.errorRate, errors, samples, `"${s.name}": the CI/CD file's errorRate beside the row's ${errors} errors`);
        expectRate(numberIn(row['Error %']), errors, samples, `"${s.name}": Error %`);
      }
    });

    test('the Errors tab counts each sampler\'s failures as the Samplers table does, and names no sampler the table and the filter bar do not show', async ({ page }) => {
      const file = ciCdFile(id);
      await loadClean(page, id);
      await switchTab(page, 'samplers');
      const { rows, classes } = await samplerRows(page);
      await switchTab(page, 'errors');

      const errorsTab = await page.evaluate(() => {
        const table = document.getElementById('errorSummaryTable') as HTMLTableElement | null;
        if (!table) return null;
        const heads = Array.from(table.tHead!.rows[0].cells).map(c => (c.textContent || '').trim());
        const col = (title: string) => heads.indexOf(title);
        const all = Array.from(table.tBodies[0].rows).filter(r => !r.classList.contains('error-detail-row'));
        const total = all.find(r => r.classList.contains('error-summary-total'));
        const cell = (r: HTMLTableRowElement, title: string) => (r.cells[col(title)]?.textContent || '').trim();
        return {
          rows: all.filter(r => r !== total).map(r => ({ sampler: cell(r, 'Sampler'), count: cell(r, 'Count') })),
          total: total ? { label: cell(total, 'Sampler'), count: cell(total, 'Count'), ofErrors: cell(total, '% of Errors') } : null,
        };
      });
      const transactionsLeftOut = await page.locator('#tab-errors .wir-population-note').count() > 0;

      if (!errorsTab || errorsTab.rows.length === 0) {
        // No table: nothing failed but, at most, transactions' own results
        for (const [name, cells] of rows) {
          if (numberIn(cells['Errors']) > 0) {
            expect(transactionsLeftOut && /transaction-parent/.test(classes.get(name)!),
              `"${name}" failed, and the Errors tab has no row for it`).toBe(true);
          }
        }
        if (!transactionsLeftOut) expect(file.totalErrors, 'the run failed, and the Errors tab lists nothing').toBe(0);
        return;
      }

      const perSampler = new Map<string, number>();
      for (const r of errorsTab.rows) perSampler.set(r.sampler, (perSampler.get(r.sampler) || 0) + numberIn(r.count));
      const listed = [...perSampler.values()].reduce((a, b) => a + b, 0);

      expect(errorsTab.total, 'the Error Summary has a Total row').not.toBeNull();
      expect(errorsTab.total!.label, 'by default every row is in the Total').toBe('Total');
      expect(numberIn(errorsTab.total!.count), 'the Total is the sum of the rows').toBe(listed);
      expect(errorsTab.total!.ofErrors, 'the rows are every failed request').toBe('100.00%');

      for (const [sampler, count] of perSampler) {
        expect(rows.has(sampler), `"${sampler}" is on the Errors tab, so it has a row in the Samplers table`).toBe(true);
        await expect(page.locator(`#samplerFilterList label[data-sampler-name="${sampler.toLowerCase().replace(/"/g, '\\"')}"]`),
          `"${sampler}" is on the Errors tab, so it has a chip in the filter bar`).toHaveCount(1);
        expect(count, `"${sampler}": its errors on the Errors tab are its Errors in the Samplers table`)
          .toBe(numberIn(rows.get(sampler)!['Errors']));
      }
      for (const [name, cells] of rows) {
        if (numberIn(cells['Errors']) === 0 || perSampler.has(name)) continue;
        // Left out only as the tab's note says: a transaction's own result
        expect(transactionsLeftOut && /transaction-parent/.test(classes.get(name)!),
          `"${name}" failed ${cells['Errors']} times, and the Errors tab does not list it`).toBe(true);
      }
      if (!transactionsLeftOut) {
        expect(listed, 'with no transaction, every failure of the run is a failed request').toBe(file.totalErrors);
      }
    });
  });
}
