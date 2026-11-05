import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
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
 *  - each sampler: its Samplers row, and its Compare tab row where the run has a baseline, show the
 *    file's figures for it, which R07 holds to the Aggregate Report over every row of results.jtl;
 *  - the Errors tab counts failed requests, its own stated rule (JMeter's HTML report's): each
 *    sampler's failures there, in the table and in the By Sampler chart, are its Errors in the
 *    Samplers table; a Transaction Controller's own result is never among them; on a run with no
 *    transaction and no sub-result its Total is the whole run's failures. Every sampler it names has
 *    a row in the Samplers table and a chip in the filter bar, both shown once "Show transaction
 *    details" is ticked (the stale-link request once had neither, the owner's second question).
 *
 * One rule is not "equal": adding up the Samplers rows gives more than the whole run whenever a
 * request runs inside a transaction (the request and its transaction are both rows), so no test
 * here adds rows up to reach a whole-run figure.
 *
 * A limit, as R07's: a sampler's error count reaches the CI/CD file only as a two-decimal rate, which
 * pins it exactly below 10,000 samples. Above that a request row's count is still held by the Errors
 * tab, which counts it independently.
 */

// The server writes its counts in en-US; the Σ Total row formats with the browser's locale, so the
// test pins the locale it reads numbers in (under de-DE "7,225" is "7.225").
test.use({ locale: 'en-US' });

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

interface SamplerRow { cells: Record<string, string>; transactionController: boolean; child: boolean; shown: boolean }

/** Every row of the Samplers table, hidden ones included, by name, and the Σ Total row. */
async function samplerRows(page: Page): Promise<{ rows: Map<string, SamplerRow>; total: Record<string, string> | null }> {
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
        .map(r => ({
          name: r.getAttribute('data-sampler')!,
          cells: asRecord(r),
          // The mark the template gives a Transaction Controller's row, and only that row: the
          // transaction-parent class also goes to a request whose sub-results nest under it
          transactionController: !!r.querySelector('.tc-icon'),
          child: r.classList.contains('transaction-child'),
          shown: (r as HTMLElement).offsetParent !== null,
        })),
    };
  });
  const rows = new Map<string, SamplerRow>();
  for (const r of read.rows) {
    expect(rows.has(r.name), `"${r.name}" has one row in the Samplers table`).toBe(false);
    rows.set(r.name, r);
  }
  return { rows, total: read.total };
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

/** The Timeline's Σ Total lines at the granularity the select shows. */
async function expectTimelineLines(page: Page, file: CiCd): Promise<void> {
  const granularity = Number(await page.locator('#granularitySelect').inputValue());
  const at = `at ${granularity / 1000} s`;

  // A Throughput point is the window's samples over the window's width: a point times the width is a count
  const throughput = (await totalLine(page, 'throughput-chart'))!;
  const drawnSamples = throughput.reduce((sum, [, v]) => sum + (v == null ? 0 : v * granularity / 1000), 0);
  expect(Math.abs(drawnSamples - file.totalSamples),
    `${at}, the Throughput chart's Σ Total line adds up to ${drawnSamples} samples, the run had ${file.totalSamples}`)
    .toBeLessThan(0.05);

  if (file.totalErrors === 0) {
    // The chart says there were none instead of drawing a flat line
    await expect(page.locator('#error-rate-chart')).toContainText('No errors recorded');
    return;
  }
  const errorRate = await totalLine(page, 'error-rate-chart');
  expect(errorRate, `${at}, the Error Rate chart draws a Σ Total line`).not.toBeNull();
  // An Error Rate point is the window's failures over the window's samples. The samples behind it are
  // not drawn, so they are read from what the chart was drawn from, per second, grouped into the
  // chart's windows (multiples of the granularity from the epoch, drawn at their start).
  const behind: Record<string, number> = await page.evaluate((g) => {
    const d = (window as any).globalChartData;
    const out: Record<string, number> = {};
    d.timestamps.forEach((t: number, i: number) => {
      const start = t - (t % g);
      out[start] = (out[start] || 0) + (d.sampleCounts[i] || 0);
    });
    return out;
  }, granularity);
  // Each second's rate reaches the page written to two decimals, 0.005 points of its samples at most,
  // so a window's drawn failures are within 0.00005 of its samples of a whole count: below 10,000
  // samples a window names its count exactly, and the counts must add up to the run's failures.
  let failures = 0;
  for (const [t, v] of errorRate!) {
    if (v == null) continue;
    const samples = behind[t] || 0;
    const drawn = v * samples / 100;
    const count = Math.round(drawn);
    expect(samples, `${at}, the window at ${t} holds too many samples for a two-decimal rate to name its failures`)
      .toBeLessThan(10000);
    expect(Math.abs(drawn - count), `${at}, the window at ${t} draws ${drawn} failures of ${samples} samples, no whole count`)
      .toBeLessThanOrEqual(0.00005 * samples + 1e-6);
    failures += count;
  }
  expect(failures, `${at}, the Error Rate chart's Σ Total line accounts for ${failures} failures, the run had ${file.totalErrors}`)
    .toBe(file.totalErrors);
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

      // Timeline: at the granularity the page picked for this run, then per second
      await switchTab(page, 'timeline');
      await expect.poll(() => totalLine(page, 'throughput-chart'), { message: 'the Throughput chart draws a Σ Total line' })
        .not.toBeNull();
      await expectTimelineLines(page, file);
      if (await page.locator('#granularitySelect').inputValue() !== '1000') {
        await page.locator('#granularitySelect').selectOption('1000');
        await expect.poll(async () => {
          const line = await totalLine(page, 'throughput-chart');
          const seconds = await page.evaluate(() => (window as any).globalChartData.timestamps.length);
          return line ? line.length === seconds : false;
        }, { message: 'the Timeline redraws per second' }).toBe(true);
        await expectTimelineLines(page, file);
      }
    });

    test('each sampler\'s row in the Samplers table, and in the Compare tab, shows the CI/CD file\'s figures for it', async ({ page }) => {
      const file = ciCdFile(id);
      await loadClean(page, id);
      await switchTab(page, 'samplers');
      const { rows } = await samplerRows(page);

      expect([...rows.keys()].sort(), 'one row for every sampler the CI/CD file lists, and no other')
        .toEqual(file.samplers.map(s => s.name).sort());
      for (const s of file.samplers) {
        const row = rows.get(s.name)!.cells;
        const samples = numberIn(row['Samples']);
        const errors = numberIn(row['Errors']);
        expect(samples, `"${s.name}": Samples`).toBe(s.sampleCount);
        expectRate(s.errorRate, errors, samples, `"${s.name}": the CI/CD file's errorRate beside the row's ${errors} errors`);
        expectRate(numberIn(row['Error %']), errors, samples, `"${s.name}": Error %`);
      }

      // The Compare tab's current side of each sampler, on a run with a baseline
      if (!(await page.locator('.tab-btn[data-tab="compare"]').isVisible())) return;
      await switchTab(page, 'compare');
      const compared = await page.evaluate(() => {
        const table = document.getElementById('comparisonTable') as HTMLTableElement;
        const heads = Array.from(table.tHead!.rows[0].cells).map(c => (c.textContent || '').trim());
        // A cell is the current value with the change after it in a <sup>
        const current = (c: HTMLTableCellElement | undefined) => (c && c.childNodes[0] ? c.childNodes[0].textContent || '' : '').trim();
        return Array.from(table.tBodies[0].rows).map(r => ({
          name: r.cells[heads.indexOf('Sampler')].getAttribute('title') || '',
          samples: current(r.cells[heads.indexOf('Samples')]),
          errors: current(r.cells[heads.indexOf('Errors')]),
          rate: current(r.cells[heads.indexOf('Error %')]),
        }));
      });
      const present = compared.filter(c => c.samples !== '—' && c.samples !== '');
      expect(present.length, 'the Compare tab lists this run\'s samplers').toBeGreaterThan(0);
      for (const c of present) {
        const row = rows.get(c.name);
        expect(row, `"${c.name}" is on the Compare tab, so it has a row in the Samplers table`).toBeDefined();
        expect(numberIn(c.samples), `"${c.name}": the Compare tab's current Samples`).toBe(numberIn(row!.cells['Samples']));
        expect(numberIn(c.errors), `"${c.name}": the Compare tab's current Errors`).toBe(numberIn(row!.cells['Errors']));
        expectRate(numberIn(c.rate), numberIn(row!.cells['Errors']), numberIn(row!.cells['Samples']), `"${c.name}": the Compare tab's current Error %`);
      }
    });

    test('the Errors tab counts each sampler\'s failures as the Samplers table does, and names no sampler the table and the filter bar do not show', async ({ page }) => {
      const file = ciCdFile(id);
      await loadClean(page, id);
      await switchTab(page, 'samplers');
      const { rows } = await samplerRows(page);
      const noNesting = [...rows.values()].every(r => !r.transactionController && !r.child);
      await switchTab(page, 'errors');

      const errorsTab = await page.evaluate(() => {
        const table = document.getElementById('errorSummaryTable') as HTMLTableElement | null;
        if (!table) return null;
        const heads = Array.from(table.tHead!.rows[0].cells).map(c => (c.textContent || '').trim());
        const all = Array.from(table.tBodies[0].rows).filter(r => !r.classList.contains('error-detail-row'));
        const total = all.find(r => r.classList.contains('error-summary-total'));
        const cell = (r: HTMLTableRowElement, title: string) => (r.cells[heads.indexOf(title)]?.textContent || '').trim();
        const chart = (chartId: string) => {
          const dom = document.getElementById(chartId);
          const c = dom && (window as any).echarts.getInstanceByDom(dom);
          return c ? c.getOption() : null;
        };
        const bar = chart('error-by-sampler-chart');
        const pie = chart('error-by-type-chart');
        const valueOf = (d: any) => (d && typeof d === 'object' ? d.value : d);
        // The Error Details list, one row per recorded failure with its message (capped, and said so)
        const details = document.getElementById('errorDetailsTable') as HTMLTableElement | null;
        const detailHeads = details ? Array.from(details.tHead!.rows[0].cells).map(c => (c.textContent || '').trim()) : [];
        const records = details ? Array.from(details.tBodies[0].rows).filter(r => r.classList.contains('error-row-main')).map(r => ({
          sampler: (r.cells[detailHeads.indexOf('Sampler')]?.textContent || '').trim(),
          message: (r.cells[detailHeads.indexOf('Message')]?.textContent || '').trim(),
        })) : [];
        return {
          rows: all.filter(r => r !== total).map(r => ({ sampler: r.getAttribute('data-sampler') || '', count: cell(r, 'Count') })),
          total: total ? { label: cell(total, 'Sampler'), count: cell(total, 'Count'), ofSamples: cell(total, '% of All Samples') } : null,
          bySampler: bar ? (bar.xAxis[0].data as string[]).map((name, i) => ({ name, value: valueOf(bar.series[0].data[i]) })) : null,
          byCode: pie ? (pie.series[0].data as any[]).map(valueOf) : null,
          records,
          recordsComplete: !(window as any).globalChartData.errorRecordsTruncated,
        };
      });

      if (!errorsTab || errorsTab.rows.length === 0) {
        // No table: nothing failed but, at most, transactions' own results
        for (const [name, r] of rows) {
          if (numberIn(r.cells['Errors']) > 0) {
            expect(r.transactionController, `"${name}" failed, and the Errors tab has no row for it`).toBe(true);
          }
        }
        if (noNesting) expect(file.totalErrors, 'the run failed, and the Errors tab lists nothing').toBe(0);
        return;
      }

      const perSampler = new Map<string, number>();
      for (const r of errorsTab.rows) perSampler.set(r.sampler, (perSampler.get(r.sampler) || 0) + numberIn(r.count));
      const listed = [...perSampler.values()].reduce((a, b) => a + b, 0);

      expect(errorsTab.total, 'the Error Summary has a Total row').not.toBeNull();
      expect(errorsTab.total!.label, 'by default every row is in the Total').toBe('Total');
      expect(numberIn(errorsTab.total!.count), 'the Total is the sum of the rows').toBe(listed);

      for (const [sampler, count] of perSampler) {
        const row = rows.get(sampler);
        expect(row, `"${sampler}" is on the Errors tab, so it has a row in the Samplers table`).toBeDefined();
        if (!row!.transactionController) {
          expect(count, `"${sampler}": its failures on the Errors tab are its Errors in the Samplers table`)
            .toBe(numberIn(row!.cells['Errors']));
          continue;
        }
        // A Transaction Controller's own result is left out (E3), as JMeter's HTML report leaves out a
        // result carrying the "Number of samples in transaction" message. A transaction the test end or a
        // "Stop Thread" cut off is written without that message, and JMeter's HTML report and this tab
        // count it as a failed request: so a transaction's row here is only ever those results.
        const own = errorsTab.records.filter(r => r.sampler === sampler);
        for (const r of own) {
          expect(r.message, `"${sampler}" on the Errors tab: a transaction's own completed result, left out (E3)`)
            .not.toContain('Number of samples in transaction');
        }
        if (errorsTab.recordsComplete) {
          expect(own.length, `"${sampler}": its failures on the Errors tab are the ones its Error Details list`).toBe(count);
        }
        expect(count, `"${sampler}": the Errors tab counts no more of its failures than the Samplers table`)
          .toBeLessThanOrEqual(numberIn(row!.cells['Errors']));
      }
      for (const [name, r] of rows) {
        if (numberIn(r.cells['Errors']) === 0 || perSampler.has(name)) continue;
        expect(r.transactionController,
          `"${name}" failed ${r.cells['Errors']} times, and the Errors tab does not list it`).toBe(true);
      }
      if (await page.locator('#tab-errors .wir-population-note').count() === 0) {
        expect([...rows.values()].some(r => r.transactionController),
          'a run with a Transaction Controller says that the Errors tab leaves its own result out').toBe(false);
      }

      // The tab's two charts count the same failures as its table
      expect(errorsTab.bySampler, 'the Errors tab draws its By Sampler chart').not.toBeNull();
      for (const bar of errorsTab.bySampler!) {
        expect(bar.value, `the By Sampler bar of "${bar.name}"`).toBe(perSampler.get(bar.name));
      }
      expect(errorsTab.byCode, 'the Errors tab draws its By Response Code chart').not.toBeNull();
      expect(errorsTab.byCode!.reduce((a: number, b: number) => a + b, 0), 'the By Response Code chart adds up to the Total')
        .toBe(listed);

      // With no transaction and no sub-result, every failed request is a failure of the whole run
      if (noNesting) {
        expect(listed, 'with no nesting, the Errors tab\'s Total is the whole run\'s failures').toBe(file.totalErrors);
        expectRate(numberIn(errorsTab.total!.ofSamples), file.totalErrors, file.totalSamples,
          'with no nesting, the Total\'s % of All Samples is the whole run\'s error rate');
      }

      // Every sampler the tab names can be found: a chip in the filter bar and a row in the Samplers
      // table, both shown once transaction details are (a request inside a transaction hides with them)
      const detailsSwitch = page.locator('#transactionChildrenBtn');
      if (await detailsSwitch.isVisible() && !(await page.locator('#transactionChildrenCheckbox').isChecked())) {
        await detailsSwitch.click();
        await expect(page.locator('#transactionChildrenCheckbox')).toBeChecked();
      }
      const chips = await page.evaluate((names) => names.map((name) => {
        const boxes = Array.from(document.querySelectorAll('#samplerFilterList input[type="checkbox"]'))
          .filter((b) => (b as HTMLInputElement).value === name);
        const label = boxes[0] && boxes[0].closest('label');
        return { name, count: boxes.length, shown: !!label && (label as HTMLElement).offsetParent !== null };
      }), [...perSampler.keys()]);
      for (const chip of chips) {
        expect(chip.count, `"${chip.name}" is on the Errors tab, so it has one chip in the filter bar`).toBe(1);
        expect(chip.shown, `"${chip.name}" is on the Errors tab, so its chip shows with transaction details on`).toBe(true);
      }
      await switchTab(page, 'samplers');
      const shownRows = (await samplerRows(page)).rows;
      for (const sampler of perSampler.keys()) {
        expect(shownRows.get(sampler)!.shown,
          `"${sampler}" is on the Errors tab, so its Samplers row shows with transaction details on`).toBe(true);
      }
    });
  });
}
