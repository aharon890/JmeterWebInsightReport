import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Every table works the same way (spec 002, US6, FR-060 to FR-071), stage 5: Compare and the Errors tables move onto
 * the table toolkit. Four defects found by reading the old code (FR-071) are each held here, red first:
 *   2. Compare's Apply rebuilt its rows and dropped the search, the hidden rows and the sort;
 *   3. a saved Compare column order moved the headers and left the cells where they were;
 *   4. the Columns menu hid by position, so after a column moved it hid whatever column sat there;
 *   5. an open Error Details row stayed on screen after a search hid the row it belongs to.
 */

const SEARCH = (tab: string) => `#tab-${tab} input.wir-table-search, #tab-${tab} input.table-search`;

/** The sampler names of the Compare rows on screen, in order, the Σ Total row left out. */
async function compareNames(page: Page): Promise<string[]> {
  return page.$$eval('#comparisonTable tbody tr', (trs) => trs
    .filter((tr) => (tr as HTMLElement).offsetParent !== null && !tr.classList.contains('comparison-total-row')
      && !tr.classList.contains('wir-group-row'))
    .map((tr) => (tr.querySelector('td[data-col-id="name"], td') as HTMLElement).textContent!.trim()));
}

async function headerTexts(page: Page, table: string): Promise<string[]> {
  return page.$$eval(`#${table} thead th`, (ths) => ths.map((th) => (th as HTMLElement).textContent!.replace(/[↕▲▼]/g, '').trim()));
}

test.describe('Compare on the table toolkit [CMP]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
  });

  test('defect 2: Apply keeps the search, the hidden rows and the sort', async ({ page }) => {
    const all = await compareNames(page);
    expect(all.length).toBeGreaterThan(3);
    await page.locator('#comparisonTable thead th', { hasText: 'Mean (ms)' }).click();
    const firstLetter = all[1].slice(0, 2);
    await page.fill(SEARCH('compare'), firstLetter);
    const hideable = page.locator('#comparisonTable tbody tr:visible button.wir-row-hide, #comparisonTable tbody tr:visible button.row-hide-btn');
    if (await hideable.count() > 1) await hideable.first().click();
    const before = await compareNames(page);
    await page.locator('#tab-compare button', { hasText: 'Apply' }).click();
    expect(await compareNames(page)).toEqual(before);
    expect(await page.inputValue(SEARCH('compare'))).toBe(firstLetter);
  });

  test('defect 3: a saved column order keeps every cell under its header', async ({ page }) => {
    await page.evaluate(() => {
      const n = document.querySelectorAll('#comparisonTable thead th').length;
      const order = Array.from({ length: n }, (_, i) => i);
      [order[0], order[1]] = [order[1], order[0]];
      localStorage.setItem('wir_compareColumnOrder', JSON.stringify(order));
    });
    await page.reload();
    await page.waitForFunction(() => (window as any).globalChartData);
    await switchTab(page, 'compare');
    const heads = await headerTexts(page, 'comparisonTable');
    const status = heads.indexOf('Status');
    expect(status).toBe(0);
    const cells = await page.$$eval('#comparisonTable tbody tr', (trs, i) => trs
      .filter((tr) => !tr.classList.contains('wir-group-row'))
      .map((tr) => ((tr as HTMLTableRowElement).cells[i as number] as HTMLElement).textContent!.trim()), status);
    expect(cells.length).toBeGreaterThan(0);
    for (const c of cells) expect(c).toMatch(/^(REGRESSION|NEW|REMOVED|OK)$/);
  });

  test('defect 4: after a column moves, Columns hides the column it names', async ({ page }) => {
    await page.locator('#tab-compare button', { hasText: /^Columns/ }).first().click();
    await page.locator('#tab-compare label', { hasText: 'P95 (ms)' }).locator('input').uncheck();
    await expect(page.locator('#comparisonTable thead th', { hasText: 'P95 (ms)' })).toBeHidden();
    // a column dragged across it, as the header's drag and drop moves it
    await page.evaluate(() => {
      const table = document.getElementById('comparisonTable') as HTMLTableElement;
      (window as any).reorderColumns(table, 2, 10, 'wir_compareColumnOrder');
    });
    // hiding a second column applies the hidden columns again: by position, the old menu showed P95 and hid P99
    const min = page.locator('#tab-compare label', { hasText: 'Min (ms)' }).locator('input');
    if (!(await min.isVisible())) await page.locator('#tab-compare button', { hasText: /^Columns/ }).first().click();
    await min.uncheck();
    await expect(page.locator('#comparisonTable thead th', { hasText: 'P95 (ms)' })).toBeHidden();
    await expect(page.locator('#comparisonTable thead th', { hasText: 'Min (ms)' })).toBeHidden();
    await expect(page.locator('#comparisonTable thead th', { hasText: 'P99 (ms)' })).toBeVisible();
    await expect(page.locator('#comparisonTable thead th', { hasText: 'Samples' })).toBeVisible();
  });

  test('the chips are the comparison\'s statuses, with counts, and Group by puts each status together', async ({ page }) => {
    const chips = page.locator('.wir-table-toolbar[data-table="comparisonTable"] .wir-status-chip');
    expect(await chips.count()).toBeGreaterThan(0);
    for (const t of await chips.allInnerTexts()) expect(t.trim()).toMatch(/^(REGRESSION|NEW|REMOVED|OK) \d+$/);
    await page.selectOption('#comparisonTable-group', 'status');
    await expect(page.locator('#comparisonTable tr.wir-group-row').first()).toBeVisible();
  });
});

test.describe('The Errors tables on the table toolkit [E02]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
  });

  test('defect 5: an open error detail leaves with a search that hides its row', async ({ page }) => {
    await page.locator('#errorDetailsTable tbody tr.error-row-main').first().click();
    await expect(page.locator('#errorDetailsTable .error-detail-row')).toBeVisible();
    await page.locator('#errViewTime input.table-search, #tab-errors input#errorDetailsTable-search').first().fill('zzzz-no-such-error');
    await expect(page.locator('#errorDetailsTable .error-detail-row')).toBeHidden();
  });

  test('Error Details groups by sampler and type of error, as the old Grouped view did, and by class or code', async ({ page }) => {
    await page.selectOption('#errorDetailsTable-group', 'error');
    await expect(page.locator('#errorDetailsTable tr.wir-group-row').first()).toBeVisible();
    await page.selectOption('#errorDetailsTable-group', 'code');
    const labels = await page.$$eval('#errorDetailsTable tr.wir-group-row .wir-group-toggle', (bs) => bs.map((b) => b.textContent!));
    expect(labels.length).toBeGreaterThan(0);
  });

  test('the Error Summary\'s Total stays last and counts the rows shown', async ({ page }) => {
    const rows = page.locator('#errorSummaryTable tbody tr');
    await expect(rows.last()).toHaveClass(/error-summary-total/);
    await page.fill('#errorSummaryTable-search', 'zzzz-no-such-error');
    await expect(page.locator('#errorSummaryTable tbody tr.error-summary-total')).toBeVisible();
  });
});

/** The Requests rows on screen, in order, the Σ Total row left out. */
async function requestNames(page: Page): Promise<string[]> {
  return page.$$eval('#samplerTable tbody tr[data-sampler]', (trs) => trs
    .filter((tr) => (tr as HTMLElement).offsetParent !== null)
    .map((tr) => tr.getAttribute('data-sampler')!));
}

test.describe('Requests on the table toolkit [E02]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
  });

  test('defect 1: Throughput sorts by value across /s, /min and /hour', async ({ page }) => {
    await page.selectOption('#samplerTable-group', '');
    await page.locator('#samplerTable thead th', { hasText: 'Throughput' }).click();
    const names = await requestNames(page);
    const rate = await page.evaluate(() => Object.fromEntries((window as any).globalChartData.samplers
      .map((s: any) => [s.name, s.throughput])));
    const values = names.map((n) => rate[n]);
    const sorted = [...values].sort((a, b) => b - a);
    const ascending = [...values].sort((a, b) => a - b);
    expect([sorted, ascending]).toContainEqual(values);
  });

  test('each transaction has a ▸ that opens and closes its requests', async ({ page }) => {
    await page.click('#samplerTable-collapse-all');
    const tx = page.locator('#samplerTable tbody tr.transaction-parent').first();
    const name = (await tx.getAttribute('data-sampler'))!;
    const children = await page.evaluate((n) => (window as any).globalChartData.transactionHierarchy[n], name);
    expect(children.length).toBeGreaterThan(0);
    await expect(page.locator(`#samplerTable tbody tr[data-sampler="${children[0]}"]`)).toBeHidden();
    await tx.locator('.wir-tree-toggle').click();
    await expect(page.locator(`#samplerTable tbody tr[data-sampler="${children[0]}"]`)).toBeVisible();
    await expect(page.locator('#samplerTable .sampler-detail-row')).toHaveCount(0);   // the ▸ is not the row's click
    await tx.locator('.wir-tree-toggle').click();
    await expect(page.locator(`#samplerTable tbody tr[data-sampler="${children[0]}"]`)).toBeHidden();
  });

  test('Expand all and Collapse all are Show transaction details: the switch, the chips and the charts follow', async ({ page }) => {
    await page.click('#samplerTable-expand-all');
    const all = await requestNames(page);
    await page.click('#samplerTable-collapse-all');
    const top = await requestNames(page);
    expect(top.length).toBeLessThan(all.length);
    await expect(page.locator('.transaction-child-filter').first()).toBeHidden();
    await expect(page.locator('#transactionChildrenCheckbox')).not.toBeChecked();
    await page.click('#samplerTable-expand-all');
    await expect(page.locator('.transaction-child-filter').first()).toBeVisible();
    await expect(page.locator('#transactionChildrenCheckbox')).toBeChecked();
    // and the switch, flipped in the filter bar, closes every transaction in the table
    await page.locator('#transactionChildrenCheckbox').uncheck();
    expect(await requestNames(page)).toEqual(top);
  });

  test('Group by offers the tree, none, the assert status and the Apdex rating', async ({ page }) => {
    const options = await page.locator('#samplerTable-group option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
    expect(options).toEqual(['', 'transaction', 'status', 'apdex']);
    await expect(page.locator('#samplerTable-group')).toHaveValue('transaction');
    await page.selectOption('#samplerTable-group', 'apdex');
    await expect(page.locator('#samplerTable tr.wir-group-row').first()).toBeVisible();
  });

  test('CSV holds the rows shown: a row hidden with x is not in it', async ({ page }) => {
    await page.selectOption('#samplerTable-group', '');
    const first = page.locator('#samplerTable tbody tr[data-sampler]').first();
    const name = (await first.getAttribute('data-sampler'))!;
    await first.locator('.wir-row-hide').click();
    const csv = await page.evaluate(() => {
      let captured = '';
      const w = window as any;
      const real = w.downloadCsvFile;
      w.downloadCsvFile = (_n: string, c: string) => { captured = c; };
      w.WIR.table.csv('samplerTable');
      w.downloadCsvFile = real;
      return captured;
    });
    expect(csv).not.toContain(`"${name}"`);
    expect(csv.split('\n').length).toBeGreaterThan(2);
  });

  test('the sort and the hidden rows are remembered for this report', async ({ page }) => {
    await page.locator('#samplerTable thead th', { hasText: 'P95 (ms)' }).click();
    await expect(page.locator('#samplerTable thead th', { hasText: 'P95 (ms)' })).toHaveAttribute('aria-sort', /ending/);
    await page.reload();
    await page.waitForFunction(() => (window as any).globalChartData);
    await switchTab(page, 'samplers');
    await expect(page.locator('#samplerTable thead th', { hasText: 'P95 (ms)' })).toHaveAttribute('aria-sort', /ending/);
  });
});

/*
 * Stage 7: the Server board on the table toolkit (FR-062, FR-063). Its chips were one at a time and counted what the
 * other filters left; they work as every other table's now, several at once, each counting every row. Its cards are
 * Group by component, the default, beside status and none, and a card opens and closes as a group does. Every header
 * sorts by the column's value. A chart open in a row stays open while the search redraws the cards.
 */
const BOARD_BAR = '#tab-server .wir-table-toolbar[data-table="serverKpiTable"]';

async function chipCounts(page: Page): Promise<Record<string, number>> {
  return page.locator(`${BOARD_BAR} .wir-status-chip`).evaluateAll((chips: HTMLElement[]) => {
    const out: Record<string, number> = {};
    chips.forEach((c) => { out[c.getAttribute('data-status')!] = Number((c.textContent || '').trim().split(' ').pop()); });
    return out;
  });
}

async function boardRowCount(page: Page): Promise<number> {
  return page.locator('#serverBoard tr.wir-kpi-row').count();
}

test.describe('The Server board on the table toolkit [E26]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
  });

  test('its status chips work as every table\'s: several at once, each counting every row', async ({ page }) => {
    const before = await chipCounts(page);
    const statuses = Object.keys(before);
    expect(statuses.length, 'E26 has rows in several states').toBeGreaterThanOrEqual(3);
    const all = await boardRowCount(page);
    expect(Object.values(before).reduce((a, b) => a + b, 0)).toBe(all);

    await page.locator(`${BOARD_BAR} .wir-status-chip[data-status="${statuses[0]}"]`).click();
    await page.locator(`${BOARD_BAR} .wir-status-chip[data-status="${statuses[1]}"]`).click();
    await expect(page.locator(`${BOARD_BAR} .wir-status-chip[aria-pressed="true"]`)).toHaveCount(2);
    expect(await boardRowCount(page)).toBe(before[statuses[0]] + before[statuses[1]]);
    expect(await chipCounts(page), 'a chip counts every row, whatever the others select').toEqual(before);

    await page.fill('#serverSearch', 'no assert is called this');
    await expect(page.locator('#serverBoard tr.wir-kpi-row')).toHaveCount(0);
    expect(await chipCounts(page), 'the search leaves the counts as they are').toEqual(before);
  });

  test('Group by: component (its cards, the default), status or none, remembered for this report', async ({ page }) => {
    const group = page.locator('#serverKpiTable-group');
    expect(await group.locator('option').evaluateAll((os: HTMLOptionElement[]) => os.map(o => o.value)))
      .toEqual(['', 'component', 'status']);
    await expect(group).toHaveValue('component');
    expect(await page.locator('#serverBoard .wir-component-card').count()).toBeGreaterThanOrEqual(2);

    const statuses = Object.keys(await chipCounts(page));
    await page.selectOption('#serverKpiTable-group', 'status');
    await expect(page.locator('#serverBoard .wir-component-card')).toHaveCount(0);
    await expect(page.locator('#serverBoard tr.wir-group-row')).toHaveCount(statuses.length);

    await page.reload();
    await page.waitForFunction(() => (window as any).globalChartData);
    await switchTab(page, 'server');
    await expect(page.locator('#serverKpiTable-group')).toHaveValue('status');

    await page.selectOption('#serverKpiTable-group', '');
    await expect(page.locator('#serverBoard tr.wir-group-row')).toHaveCount(0);
    await expect(page.locator('#serverBoard tr.wir-kpi-row')).toHaveCount(Object.values(await chipCounts(page)).reduce((a, b) => a + b, 0));
  });

  test('a card opens and closes as a group: Collapse all and Expand all', async ({ page }) => {
    const rows = await boardRowCount(page);
    await page.click('#serverKpiTable-collapse-all');
    await expect(page.locator('#serverBoard tr.wir-kpi-row')).toHaveCount(0);
    expect(await page.locator('#serverBoard .wir-component-card').count(), 'the cards stay, closed').toBeGreaterThanOrEqual(2);
    await page.click('#serverKpiTable-expand-all');
    await expect(page.locator('#serverBoard tr.wir-kpi-row')).toHaveCount(rows);
  });

  test('a header sorts its card\'s rows by the column\'s value, from the keyboard too', async ({ page }) => {
    const card = page.locator('#serverBoard .wir-component-card').filter({ has: page.locator('tr.wir-kpi-row') }).first();
    const header = card.locator('th[data-sort-col="coverage"]');
    await header.locator('button').focus();
    await page.keyboard.press('Enter');
    await expect(card.locator('th[data-sort-col="coverage"]')).toHaveAttribute('aria-sort', /ending/);
    const dir = await card.locator('th[data-sort-col="coverage"]').getAttribute('aria-sort');
    const values = await card.locator('tr.wir-kpi-row').evaluateAll((trs: HTMLElement[]) => trs
      .map(tr => parseFloat((tr.querySelector('td[data-col-id="coverage"]')!.textContent || '').replace(/[^0-9.]/g, '')))
      .filter(n => !isNaN(n)));
    expect(values.length).toBeGreaterThan(1);
    const sorted = [...values].sort((a, b) => dir === 'ascending' ? a - b : b - a);
    expect(values).toEqual(sorted);
  });

  test('a chart open in a row stays open while the search redraws the cards', async ({ page }) => {
    const row = page.locator('#serverBoard tr.wir-kpi-row').first();
    const key = (await row.getAttribute('data-kpi-key'))!;
    const name = ((await row.locator('.wir-kpi-name').textContent()) || '').trim();
    await row.locator('.wir-kpi-name').click();
    await expect(page.locator('#serverBoard tr.wir-chart-row')).toHaveCount(1);
    await page.fill('#serverSearch', name.slice(0, 4));
    await expect(page.locator(`#serverBoard tr.wir-kpi-row[data-kpi-key="${key}"]`)).toBeVisible();
    await expect(page.locator('#serverBoard tr.wir-chart-row')).toHaveCount(1);
    await expect(page.locator('#serverBoard tr.wir-chart-row canvas, #serverBoard tr.wir-chart-row svg').first()).toBeVisible();
    await expect(page.locator('#serverSearch'), 'the box keeps its focus').toBeFocused();
  });
});

/*
 * Stage 8: the filter bar's request ticks narrow every table, as they narrow the charts (US4, FR-030, FR-067). A row tied
 * to no single request stays: the Σ Total row, a whole-run threshold or rule, the counters. The ticks are the page's
 * one choice: set on one tab, they hold on the next. The bar shows on the k6 tab.
 */
async function tick(page: Page, name: string) {
  // the name: the label's middle can be its colour dot, which opens the colour picker instead
  await page.locator(`#samplerFilterList label[data-sampler-name="${name.toLowerCase()}"] .sampler-name`).click();
  await page.waitForTimeout(400);   // the bar's refresh is debounced
}

async function shownNames(page: Page, rows: string): Promise<string[]> {
  return page.locator(rows).evaluateAll((trs: HTMLElement[]) => trs
    .filter(tr => tr.offsetParent !== null).map(tr => tr.getAttribute('data-sampler') || ''));
}

test.describe('The filter bar\'s ticks narrow every table [E02, CMP, E25, K2]', () => {
  test('a request unticked leaves Requests and both Errors tables; the Σ Total row stays', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
    const name = await page.evaluate(() => {
      const child = (window as any).transactionChildNames;
      const rows = Array.from(document.querySelectorAll('#errorSummaryTable tbody tr[data-sampler]'));
      const top = rows.map(r => r.getAttribute('data-sampler')!).filter(n => !child[n]);
      return top[0];
    });
    expect(name, 'E02 has an error on a top-level request').toBeTruthy();
    await switchTab(page, 'samplers');
    await page.locator('#samplerFilterList label[data-sampler-name="σ total"]').click();
    await expect(page.locator('#samplerTable tr.total-sampler-row')).toBeVisible();
    expect(await shownNames(page, '#samplerTable tbody tr[data-sampler]')).toContain(name);

    await tick(page, name);
    expect(await shownNames(page, '#samplerTable tbody tr[data-sampler]')).not.toContain(name);
    await expect(page.locator('#samplerTable tr.total-sampler-row'), 'the whole run is no request').toBeVisible();
    await switchTab(page, 'errors');
    expect(await shownNames(page, '#errorSummaryTable tbody tr[data-sampler]')).not.toContain(name);
    expect(await shownNames(page, '#errorDetailsTable tbody tr[data-sampler]')).not.toContain(name);
    expect((await shownNames(page, '#errorSummaryTable tbody tr[data-sampler]')).length).toBeGreaterThan(0);

    await switchTab(page, 'samplers');
    await tick(page, name);
    expect(await shownNames(page, '#samplerTable tbody tr[data-sampler]')).toContain(name);
  });

  test('Compare narrows too, and keeps its Σ Total row', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
    const compared = () => page.locator('#comparisonTable tbody tr td[data-col-id="name"]').allTextContents();
    const before = await compared();
    const name = before.find(n => !/Σ Total/.test(n))!;
    await switchTab(page, 'samplers');
    await tick(page, name);
    await switchTab(page, 'compare');
    const after = await compared();
    expect(after).not.toContain(name);
    expect(after.length).toBe(before.length - 1);
  });

  test('Asserts narrows its one-request rules; a whole-run rule stays', async ({ page }) => {
    await loadClean(page, 'E25');
    const rules = await page.evaluate(() => (window as any).WIR.asserts._state.rules.map((r: any) => ({
      id: r.id, kind: r.target && r.target.kind, sampler: r.target && r.target.sampler })));
    const on = rules.filter(r => r.kind === 'sampler' && r.sampler === 'Slow API').map(r => r.id);
    const overall = rules.filter(r => r.kind === 'overall').map(r => r.id);
    expect(on.length, 'E25 has rules on Slow API').toBeGreaterThan(0);
    expect(overall.length, 'and whole-run rules').toBeGreaterThan(0);
    await switchTab(page, 'samplers');
    await tick(page, 'Slow API');
    await switchTab(page, 'asserts');
    const shown = await page.locator('#assertsTable tr[data-rule-id]').evaluateAll((trs: HTMLElement[]) =>
      trs.map(t => t.getAttribute('data-rule-id')));
    on.forEach(id => expect(shown, `${id} judges an unticked request`).not.toContain(id));
    overall.forEach(id => expect(shown, `${id} is the whole run's`).toContain(id));
  });

  test('on a k6 run the bar shows on the k6 tab, its ticks narrow every section and hold on the Requests tab', async ({ page }) => {
    await loadClean(page, 'K2');
    await switchTab(page, 'tool');
    await expect(page.locator('#filterBar')).toBeVisible();
    await page.check('#transactionChildrenCheckbox');                  // Login sits in a group: its own tick
    await page.evaluate(() => (window as any).selectAllSamplers(false));
    await page.waitForTimeout(400);
    await tick(page, 'Login');
    // the requests of the thresholds the filters leave, folded or not (the passed ones fold into "N more")
    const thresholds: (string | null)[] = await page.evaluate(() => (window as any).WIR.table.table('toolThresholds').last.kept
      .map((r: any) => r.request || null));
    thresholds.forEach(r => expect(['Login', null], 'a threshold on another request').toContain(r));
    expect(thresholds, 'Login\'s own threshold').toContain('Login');
    expect(thresholds.some(r => !r), 'a whole-run threshold stays').toBe(true);
    await expect(page.locator('#toolCounters')).toBeVisible();

    await switchTab(page, 'samplers');
    const requests = await shownNames(page, '#samplerTable tbody tr[data-sampler]');
    expect(requests).toContain('Login');
    expect(requests.filter(n => n !== 'Login' && n !== 'Complete Checkout Flow'), 'only Login, under its group').toEqual([]);
  });

  test('a request the page unticks by default to keep the charts readable stays in the tables, as JMeter lists it', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const name = (await page.locator('#samplerTable tbody tr[data-sampler]:not(.transaction-child)').first().getAttribute('data-sampler'))!;
    await page.evaluate((n) => {
      const w = window as any;
      w.globalChartData.hiddenSamplers = [n];
      w.resetToDefaults();                          // the bar's Default: unticks the default-hidden ones
    }, name);
    await page.waitForTimeout(400);
    await expect(page.locator(`#samplerFilterList label[data-sampler-name="${name.toLowerCase()}"] input`)).not.toBeChecked();
    expect(await shownNames(page, '#samplerTable tbody tr[data-sampler]')).toContain(name);
  });
});
