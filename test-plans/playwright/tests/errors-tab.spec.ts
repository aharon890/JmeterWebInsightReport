import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

async function goErrors(page: Page, testId = 'E02') {
  await loadClean(page, testId);
  await switchTab(page, 'errors');
}

// ---------------------------------------------------------------------------
// Error charts [E02]
// ---------------------------------------------------------------------------

test.describe('Errors Tab — Charts [E02]', () => {
  test('error breakdown section renders with two chart containers', async ({ page }) => {
    await goErrors(page);

    const breakdownSection = page.locator('.timeline-chart-section[data-chart-id="error-breakdown"]');
    await expect(breakdownSection).toBeVisible();

    // Pie/doughnut charts rendered via ECharts into these containers
    const byTypeChart = page.locator('#error-by-type-chart canvas').first();
    const bySamplerChart = page.locator('#error-by-sampler-chart canvas').first();
    await expect(byTypeChart).toBeVisible();
    await expect(bySamplerChart).toBeVisible();
  });

  test('error timeline section renders with canvas', async ({ page }) => {
    await goErrors(page);

    const timelineSection = page.locator('.timeline-chart-section[data-chart-id="error-timeline"]');
    await expect(timelineSection).toBeVisible();

    const canvas = page.locator('#error-timeline-chart canvas').first();
    await expect(canvas).toBeVisible();
    const box = await canvas.boundingBox();
    expect(box).toBeTruthy();
    expect(box!.width).toBeGreaterThan(0);
  });

  test('each error chart section has Hide and Fullscreen buttons', async ({ page }) => {
    await goErrors(page);

    const errorChartIds = ['error-breakdown', 'error-timeline'];
    for (const id of errorChartIds) {
      const section = page.locator(`.timeline-chart-section[data-chart-id="${id}"]`);
      await expect(section.locator('.chart-hide-btn')).toHaveText('Hide');
      await expect(section.locator('.chart-fullscreen-btn')).toBeVisible();
    }
  });

  test('hide error chart then show all restores buttons to Hide', async ({ page }) => {
    await goErrors(page);

    const section = page.locator('.timeline-chart-section[data-chart-id="error-breakdown"]');
    const showHiddenBtn = page.locator('#showHiddenErrorChartsBtn');

    // Hide the breakdown chart
    await section.locator('.chart-hide-btn').click();
    await expect(section).toHaveClass(/chart-hidden/);
    await expect(showHiddenBtn).toBeVisible();

    // Click "Show Hidden Charts"
    await showHiddenBtn.click();
    await expect(section).not.toHaveClass(/chart-hidden/);
    await expect(section.locator('.chart-hide-btn')).toHaveText('Hide');
    await expect(showHiddenBtn).toBeHidden();
  });
});

// ---------------------------------------------------------------------------
// Error Summary table
// ---------------------------------------------------------------------------

test.describe('Errors Tab — Error Summary table', () => {
  test('table visible with expected columns', async ({ page }) => {
    await goErrors(page);

    const table = page.locator('#errorSummaryTable');
    await expect(table).toBeVisible();

    const headers = await table.locator('thead th').allTextContents();
    const trimmed = headers.map(h => h.trim()).filter(h => h.length > 0);
    expect(trimmed).toContain('Sampler');
    expect(trimmed).toContain('Response Code');
    // Class is its own column, not a span inside Response Code. It shipped inside that cell,
    // and filterGenericTable/sortGenericTable read a cell's full text — so typing "failure"
    // matched every row, and the sort compared "500HTTP failure" against "200Non-protocol…".
    expect(trimmed).toContain('Class');
    expect(trimmed).toContain('Count');
    expect(trimmed).toContain('% of Errors');
    expect(trimmed).toContain('% of All Samples');
  });

  test('search filters rows and clear restores them', async ({ page }) => {
    await goErrors(page);

    const searchInput = page.locator('#tab-errors .table-toolbar input.table-search').first();
    await expect(searchInput).toBeVisible();

    const allRows = page.locator('#errorSummaryTable tbody tr');
    const countBefore = await allRows.evaluateAll(
      (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
    );

    // Search for "500"
    await searchInput.fill('500');
    await page.waitForTimeout(300);
    const countFiltered = await allRows.evaluateAll(
      (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
    );
    expect(countFiltered).toBeGreaterThan(0);
    expect(countFiltered).toBeLessThan(countBefore);

    // Clear search
    await searchInput.fill('');
    await page.waitForTimeout(300);
    const countRestored = await allRows.evaluateAll(
      (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
    );
    expect(countRestored).toBe(countBefore);
  });

  test('hide row shows counter, show hidden restores', async ({ page }) => {
    await goErrors(page);

    const showHiddenBtn = page.locator('#showHiddenBtn-errorSummaryTable');
    await expect(showHiddenBtn).toBeHidden();

    // Hide the first row
    const firstRow = page.locator('#errorSummaryTable tbody tr').first();
    await firstRow.locator('.row-hide-btn').click({ force: true });
    await page.waitForTimeout(200);
    await expect(showHiddenBtn).toBeVisible();
    await expect(showHiddenBtn).toHaveText(/Show.*Hidden/);

    // Restore
    await showHiddenBtn.click();
    await page.waitForTimeout(200);
    await expect(showHiddenBtn).toBeHidden();
    await expect(firstRow).toBeVisible();
  });

  test('CSV button click produces no JS error', async ({ page }) => {
    await goErrors(page);

    // The CSV button is in the Error Summary section header
    const csvBtn = page.locator('#tab-errors section').filter({ hasText: 'Error Summary' }).locator('.table-csv-btn');
    await expect(csvBtn).toBeVisible();

    await expectNoJsErrors(page, async () => {
      await csvBtn.click();
      await page.waitForTimeout(300);
    });
  });
});

// ---------------------------------------------------------------------------
// Error Summary — the Total row (B2)
// ---------------------------------------------------------------------------

/**
 * The Total row, read by HEADER, never by position. It was built by literal index from before the
 * Class column existed (f601ae1, v3.0.0), so on every report with an error it summed the Class
 * cells ("HTTP failure⊕" parses to nothing) and read `Total · · 0 · 100% · 0.00% ·` — 0 errors and
 * 0.00% on E07, where every sample failed. No spec read the row, which is how it shipped.
 *
 * The rule it follows now: the Total is the sum of the rows the table is SHOWING. % of Errors is
 * the share of every error those rows hold, % of All Samples their share of every sample — the
 * same two denominators each row's own cells divide by. So its Count is the sum of the Count
 * column above it, its percentages are that sum's exact share — their column's sum up to each
 * row's own rounding to two decimals — and it says 100.00% only when every row is counted.
 */
async function readErrorTotal(page: Page) {
  return page.evaluate(() => {
    const t = document.getElementById('errorSummaryTable') as HTMLTableElement;
    const head = Array.from(t.tHead!.rows[0].cells).map((c) => c.textContent!.trim());
    const at = (name: string) => head.indexOf(name);
    const text = (r: HTMLTableRowElement, name: string) => (r.cells[at(name)]?.textContent ?? '').trim();
    const rows = Array.from(t.tBodies[0].rows);
    const data = rows.filter((r) => !r.classList.contains('error-summary-total'));
    const totals = rows.filter((r) => r.classList.contains('error-summary-total'));
    const shown = data.filter((r) => r.style.display !== 'none');
    const d = (window as any).globalChartData;
    const total = totals[0];
    return {
      totals: totals.length,
      isLast: total === rows[rows.length - 1],
      visible: !!total && total.style.display !== 'none',
      rows: data.length,
      shown: shown.length,
      samplers: data.map((r) => r.getAttribute('data-sampler')),
      shownSum: shown.reduce((s, r) => s + parseInt(text(r, 'Count').replace(/,/g, ''), 10), 0),
      allSum: data.reduce((s, r) => s + parseInt(text(r, 'Count').replace(/,/g, ''), 10), 0),
      errors: d.requestErrorCount as number,
      samples: d.requestSampleCount as number,
      transactions: (d.samplers || []).filter((s: any) => s.isTransactionController).map((s: any) => s.name as string),
      cells: total ? {
        sampler: text(total, 'Sampler'),
        code: text(total, 'Response Code'),
        cls: text(total, 'Class'),
        count: text(total, 'Count'),
        pctErrors: text(total, '% of Errors'),
        pctAll: text(total, '% of All Samples'),
      } : null,
    };
  });
}

type ErrorTotal = Awaited<ReturnType<typeof readErrorTotal>>;

/** What the Total must read when it covers the rows shown: their Count, and that count's share of each population. */
function expectTotalOfShown(t: ErrorTotal, why: string) {
  expect(t.totals, `${why}: exactly one Total row`).toBe(1);
  expect(t.isLast, `${why}: the Total is not the last row`).toBe(true);
  expect(t.visible, `${why}: the Total row is hidden`).toBe(true);
  expect(t.cells, why).toEqual({
    sampler: t.shown === t.rows ? 'Total' : `Total (${t.shown} of ${t.rows} rows)`,
    code: '',
    cls: '',
    count: t.shownSum.toLocaleString('en-US'),
    pctErrors: (t.shownSum * 100 / t.errors).toFixed(2) + '%',
    pctAll: (t.shownSum * 100 / t.samples).toFixed(2) + '%',
  });
}

test.describe('Errors Tab — Error Summary Total row (B2)', () => {
  test('[E07] every sample failed: the Total is every sample, 100.00% of errors and of all samples', async ({ page }) => {
    await goErrors(page, 'E07');
    const t = await readErrorTotal(page);
    expect(t.shown, 'E07 hides an error row by default').toBe(t.rows);
    // The research's reading of this fixture: 345 of 345 failed, and the Total said 0 and 0.00%.
    expect(t.errors, 'E07 no longer fails every sample').toBe(t.samples);
    expect(t.allSum, 'the rows do not add up to the run\'s errors').toBe(t.errors);
    expect(t.cells).toEqual({
      sampler: 'Total', code: '', cls: '',
      count: t.samples.toLocaleString('en-US'), pctErrors: '100.00%', pctAll: '100.00%',
    });
    expectTotalOfShown(t, 'E07');
  });

  test('[E05] the Total is the run\'s error count, and its share of every sample', async ({ page }) => {
    await goErrors(page, 'E05');
    const t = await readErrorTotal(page);
    expect(t.shown, 'E05 hides an error row by default').toBe(t.rows);
    expect(t.allSum, 'the rows do not add up to the run\'s errors').toBe(t.errors);
    expect(t.cells!.pctErrors).toBe('100.00%');
    expectTotalOfShown(t, 'E05');
  });

  test('[E02] the rows are the failed requests: no transaction\'s own row, and every row is shown (E3)', async ({ page }) => {
    // E3: a transaction's own failure was listed beside the request that failed it, and the
    // requests' rows were collapsed under it by default. JMeter's HTML report lists the requests.
    await goErrors(page);
    const t = await readErrorTotal(page);
    expect(t.transactions.length, 'E02 no longer has a transaction').toBeGreaterThan(0);
    for (const tx of t.transactions) expect(t.samplers, `a row of transaction ${tx}`).not.toContain(tx);
    expect(t.shown).toBe(t.rows);
    expect(t.allSum, 'the rows do not add up to the run\'s failed requests').toBe(t.errors);
    expectTotalOfShown(t, 'E02');
    expect(t.cells!.pctErrors).toBe('100.00%');
    await expect(page.locator('#tab-errors .wir-population-note')).toContainText('failed requests');

    // Transaction Details does not change the error tables: they list requests either way.
    await switchTab(page, 'samplers');
    await page.click('#transactionChildrenBtn');
    await switchTab(page, 'errors');
    const after = await readErrorTotal(page);
    expect(after.shown).toBe(t.rows);
    expect(after.cells).toEqual(t.cells);
  });

  test('[E02] the search never hides the Total, and the Total follows it', async ({ page }) => {
    await goErrors(page);
    const search = page.locator('#tab-errors .table-toolbar input.table-search').first();

    await search.fill('pricing');
    const some = await readErrorTotal(page);
    expect(some.shown, 'no row matches "pricing" on E02').toBeGreaterThan(0);
    expect(some.shown).toBeLessThan(some.rows);
    expectTotalOfShown(some, 'searching "pricing"');

    await search.fill('no sampler is called this');
    const none = await readErrorTotal(page);
    expect(none.shown).toBe(0);
    expectTotalOfShown(none, 'a search that matches nothing');
    expect(none.cells!.count).toBe('0');

    await search.fill('');
    expectTotalOfShown(await readErrorTotal(page), 'search cleared');
  });

  test('[E02] a row hidden with × leaves the Total, and Show Hidden brings back only that row', async ({ page }) => {
    await goErrors(page);
    const before = await readErrorTotal(page);

    const first = page.locator('#errorSummaryTable tbody tr:not(.error-summary-total)').first();
    await first.locator('.row-hide-btn').click({ force: true });
    const hidden = await readErrorTotal(page);
    expect(hidden.shown).toBe(before.shown - 1);
    expectTotalOfShown(hidden, 'one row hidden with ×');

    // Show Hidden used to clear every row's display, bringing back rows nobody had unhidden.
    await page.click('#showHiddenBtn-errorSummaryTable');
    const back = await readErrorTotal(page);
    expect(back.shown, 'Show Hidden revealed rows nobody hid').toBe(before.shown);
    expect(back.cells).toEqual(before.cells);
  });

  test('[E02] Show Hidden keeps the search: it brings back what × hid, not what the search hides', async ({ page }) => {
    await goErrors(page);
    const search = page.locator('#tab-errors .table-toolbar input.table-search').first();
    await search.fill('pricing');
    const searched = await readErrorTotal(page);

    await page.locator('#errorSummaryTable tbody tr:not(.error-summary-total)')
      .filter({ hasText: 'pricing' }).first().locator('.row-hide-btn').click({ force: true });
    expectTotalOfShown(await readErrorTotal(page), 'searching, one match hidden with ×');

    await page.click('#showHiddenBtn-errorSummaryTable');
    const back = await readErrorTotal(page);
    expect(back.shown, 'Show Hidden undid the search').toBe(searched.shown);
    expectTotalOfShown(back, 'searching, after Show Hidden');
  });

  test('[E02] Transaction Details keeps the search and what × hid', async ({ page }) => {
    await goErrors(page);
    const toggleChildren = async () => {
      await switchTab(page, 'samplers');
      await page.click('#transactionChildrenBtn');
      await switchTab(page, 'errors');
    };

    // The search first: expanding the transactions changes nothing on the error tables (E3).
    const search = page.locator('#tab-errors .table-toolbar input.table-search').first();
    await search.fill('pricing');
    const searched = await readErrorTotal(page);
    await toggleChildren();
    const expanded = await readErrorTotal(page);
    expect(expanded.shown, 'expanding the children showed rows the search hides').toBe(searched.shown);
    expectTotalOfShown(expanded, 'searching, children expanded');

    // A child hidden with × stays hidden through a collapse and an expand.
    await search.fill('');
    const child = page.locator('#errorSummaryTable tbody tr:not(.error-summary-total)')
      .filter({ hasText: 'GET /api/search' }).first();
    await child.locator('.row-hide-btn').click({ force: true });
    const hidden = await readErrorTotal(page);
    await toggleChildren();
    await toggleChildren();
    const again = await readErrorTotal(page);
    expect(again.shown, 'expanding the children brought back a row hidden with ×').toBe(hidden.shown);
    await expect(child).toBeHidden();
    await expect(page.locator('#showHiddenBtn-errorSummaryTable')).toHaveText('Show 1 Hidden Row');
    expectTotalOfShown(again, 'one child hidden with ×, collapsed and expanded again');
  });
});

// ---------------------------------------------------------------------------
// Error Details table
// ---------------------------------------------------------------------------

test.describe('Errors Tab — Error Details table', () => {
  test('table visible with expected columns', async ({ page }) => {
    await goErrors(page);

    const table = page.locator('#errorDetailsTable');
    await expect(table).toBeVisible();

    const headers = await table.locator('thead th').allTextContents();
    const trimmed = headers.map(h => h.trim()).filter(h => h.length > 0);
    expect(trimmed).toContain('Time');
    expect(trimmed).toContain('Sampler');
    expect(trimmed).toContain('Code');
    expect(trimmed).toContain('Class');
    expect(trimmed).toContain('Message');
    expect(trimmed).toContain('Thread');
  });

  test('click row expands detail with URL/body, click again collapses', async ({ page }) => {
    await goErrors(page);

    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
    await expect(firstRow).toBeVisible();

    // No detail rows initially
    expect(await page.locator('#errorDetailsTable tbody tr.error-detail-row').count()).toBe(0);

    // Click to expand
    await firstRow.click();
    await page.waitForTimeout(300);
    const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
    await expect(detailRow).toBeVisible();

    // Detail should contain URL or body or response info
    const detailText = await detailRow.textContent();
    expect(detailText!.length).toBeGreaterThan(0);

    // Click again to collapse
    await firstRow.click();
    await page.waitForTimeout(300);
    expect(await page.locator('#errorDetailsTable tbody tr.error-detail-row').count()).toBe(0);
  });

  test('search by sampler filters rows', async ({ page }) => {
    await goErrors(page);

    // The search input for the error details table (the Grouped view has its own — scope to By time)
    const searchInput = page.locator('#errViewTime input.table-search');
    await expect(searchInput).toBeVisible();

    // Get a sampler name from the first row
    const firstSampler = await page.locator('#errorDetailsTable tbody tr.error-row-main').first().locator('td').nth(1).textContent();
    const samplerName = firstSampler!.trim();

    const allMainRows = page.locator('#errorDetailsTable tbody tr.error-row-main');
    const countBefore = await allMainRows.evaluateAll(
      (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
    );

    // Search by sampler name
    await searchInput.fill(samplerName);
    await page.waitForTimeout(300);
    const countFiltered = await allMainRows.evaluateAll(
      (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
    );
    expect(countFiltered).toBeGreaterThan(0);
    expect(countFiltered).toBeLessThanOrEqual(countBefore);
  });

  test('CSV button click produces no JS error', async ({ page }) => {
    await goErrors(page);

    const csvBtn = page.locator('#tab-errors section').filter({ hasText: 'Error Details' }).locator('.table-csv-btn');
    await expect(csvBtn).toBeVisible();

    await expectNoJsErrors(page, async () => {
      await csvBtn.click();
      await page.waitForTimeout(300);
    });
  });
});

// ---------------------------------------------------------------------------
// Sampler filter integration
// ---------------------------------------------------------------------------

test.describe('Errors Tab — Sampler filter integration', () => {
  test('uncheck sampler affects error charts, re-check restores without JS errors', async ({ page }) => {
    await goErrors(page);

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Get the first sampler label from the filter bar that matches an error sampler
    const firstSamplerCell = page.locator('#errorSummaryTable tbody tr').first().locator('td').first();
    const samplerName = (await firstSamplerCell.textContent())!.trim();

    // Uncheck the sampler in the filter bar (click label to toggle)
    const filterLabel = page.locator('#samplerFilterList label', { hasText: samplerName });
    await expect(filterLabel).toHaveClass(/checked/);
    await filterLabel.click();
    await page.waitForTimeout(500);

    // Label should lose 'checked' class
    await expect(filterLabel).not.toHaveClass(/checked/);

    // Error charts should re-render without JS errors
    expect(errors).toEqual([]);

    // Re-check the sampler
    await filterLabel.click();
    await page.waitForTimeout(500);

    // Label should regain 'checked' class
    await expect(filterLabel).toHaveClass(/checked/);

    // Still no JS errors
    expect(errors).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Edge cases
// ---------------------------------------------------------------------------

test.describe('Errors Tab — Edge case: Zero errors [E06]', () => {
  test('no error charts, shows placeholder text, no JS errors', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await goErrors(page, 'E06');
    });

    // No chart sections should be present for errors
    const errorBreakdown = page.locator('.timeline-chart-section[data-chart-id="error-breakdown"]');
    expect(await errorBreakdown.count()).toBe(0);

    const errorTimeline = page.locator('.timeline-chart-section[data-chart-id="error-timeline"]');
    expect(await errorTimeline.count()).toBe(0);

    // "No errors recorded" placeholder should be visible
    const placeholder = page.locator('#tab-errors .notes-placeholder');
    await expect(placeholder).toBeVisible();
    await expect(placeholder).toContainText('No errors');

    // No errorSummaryTable or errorDetailsTable should exist
    expect(await page.locator('#errorSummaryTable').count()).toBe(0);
    expect(await page.locator('#errorDetailsTable').count()).toBe(0);
  });
});

test.describe('Errors Tab — Edge case: 100% errors [E07]', () => {
  test('all chart sections render, tables have rows', async ({ page }) => {
    await goErrors(page, 'E07');

    // Breakdown charts should render
    const breakdownSection = page.locator('.timeline-chart-section[data-chart-id="error-breakdown"]');
    await expect(breakdownSection).toBeVisible();
    await expect(page.locator('#error-by-type-chart canvas').first()).toBeVisible();

    // Error timeline should render
    const timelineSection = page.locator('.timeline-chart-section[data-chart-id="error-timeline"]');
    await expect(timelineSection).toBeVisible();

    // Error summary table should have rows
    const summaryRows = page.locator('#errorSummaryTable tbody tr');
    expect(await summaryRows.count()).toBeGreaterThan(0);

    // Error details table should have rows
    const detailRows = page.locator('#errorDetailsTable tbody tr.error-row-main');
    expect(await detailRows.count()).toBeGreaterThan(0);
  });
});

/**
 * A 2xx counted as an error must say so, on every surface that shows it.
 *
 * Errors are collected on `success === false` and `errorType` is the response code verbatim, so a
 * sample failed by a Response Assertion reaches the Errors tab as 200 / OK — a row that reads
 * like a pass while being counted as an error. Confirmed against a real `-g` run.
 *
 * The class is computed once in Java and carried in the payload, so nothing here re-derives it:
 * these assert that the client surfaces READ it, which is what stops them drifting from the
 * server-rendered tables or from each other.
 */
test.describe('Error classes on the client surfaces', () => {

  /** A payload with one of each class, in the shape the serializer produces. */
  async function withClassifiedErrors(page: any) {
    await loadClean(page, 'E07');
    await page.evaluate(() => {
      const d = (window as any).globalChartData;
      /* A sampler the fixture actually has: the by-code chart counts only errors belonging to
         samplers the filter bar is showing, so an invented name is filtered straight out. */
      const name = (d.samplers && d.samplers[0] && d.samplers[0].name) || 'GET /api/items';
      const mk = (code: string, msg: string, cls: string, label: string, dead: boolean) => ({
        samplerName: name, sampler: name,
        responseCode: code, code: code,
        responseMessage: msg, message: msg,
        errorClass: cls, errorClassLabel: label, messageMeaningless: dead,
        occurrenceCount: 2, count: 2, percentageOfErrors: 50, percentageOfAllSamples: 40,
        timestamp: 1785949469000, threadName: 'tg 1-1', thread: 'tg 1-1',
      });
      d.errorSummaries = [
        mk('200', 'OK', 'nonProtocol', 'Non-protocol failure (assertion or script)', true),
        mk('500', 'Internal Server Error', 'protocol', 'HTTP failure', false),
      ];
      d.errorsByType = d.errorSummaries;
      d.errorRecords = d.errorSummaries.map((e: any) => ({ ...e }));
    });
  }

  test('the by-response-code pie labels the 200 slice with its class', async ({ page }) => {
    // A bare "200" slice in an error breakdown reads as a success. This one can only be fixed
    // client-side — the pie is built by report.js from the payload.
    await withClassifiedErrors(page);
    await switchTab(page, 'errors');
    await page.waitForTimeout(600);

    const names = await page.evaluate(() => {
      const el = document.getElementById('error-by-type-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return [];
      const series = chart.getOption().series || [];
      return (series[0]?.data || []).map((d: any) => d.name);
    });

    expect(names.length).toBeGreaterThan(0);
    expect(names.some((n: string) => n.includes('200') && n.toLowerCase().includes('non-protocol')),
      `a bare 200 slice: ${JSON.stringify(names)}`).toBe(true);

    // Short form on the slice. The full label is a 42-character sentence and the donut is
    // ~300px, so the sentence either overlapped its neighbour or was ellipsised into
    // "Non-protocol failu…" — which is worse than the bare code this was meant to fix.
    // The sentence still reaches the reader, through the tooltip.
    for (const n of names) {
      expect(n.length, `slice label too long for the donut: ${n}`).toBeLessThan(24);
      expect(n).not.toContain('(assertion or script)');
    }
  });

  test('the summary CSV carries the class and drops the meaningless message',
      async ({ page }) => {
    await withClassifiedErrors(page);
    const csv = await page.evaluate(() => {
      let captured = '';
      const w = window as any;
      const real = w.downloadCsvFile;
      w.downloadCsvFile = (_n: string, c: string) => { captured = c; };
      w.exportErrorSummaryCsv();
      w.downloadCsvFile = real;
      return captured;
    });

    expect(csv).toContain('Error Class');
    expect(csv).toContain('Non-protocol failure');
    // "OK" exported as a failure message is the same untruth as printing it.
    // Matched on the code rather than the sampler: the fixture supplies the name.
    const nonProtocolRow = csv.split('\n').find(l => l.includes('","200",'));
    expect(nonProtocolRow, csv).toBeTruthy();
    expect(nonProtocolRow!.includes('OK'), `exported "OK" as a failure message: ${nonProtocolRow}`)
      .toBe(false);
  });

  test('the details CSV carries the class too', async ({ page }) => {
    await withClassifiedErrors(page);
    const csv = await page.evaluate(() => {
      let captured = '';
      const w = window as any;
      const real = w.downloadCsvFile;
      w.downloadCsvFile = (_n: string, c: string) => { captured = c; };
      w.exportErrorDetailsCsv();
      w.downloadCsvFile = real;
      return captured;
    });

    expect(csv).toContain('Error Class');
    expect(csv).toContain('Non-protocol failure');
  });

  test('a real failure message is never suppressed', async ({ page }) => {
    // Only the 2xx reason phrase goes. A 500's message, and an exception on a transport
    // failure, are the most useful things on the row.
    await withClassifiedErrors(page);
    const csv = await page.evaluate(() => {
      let captured = '';
      const w = window as any;
      const real = w.downloadCsvFile;
      w.downloadCsvFile = (_n: string, c: string) => { captured = c; };
      w.exportErrorSummaryCsv();
      w.downloadCsvFile = real;
      return captured;
    });
    expect(csv).toContain('Internal Server Error');
  });
});
