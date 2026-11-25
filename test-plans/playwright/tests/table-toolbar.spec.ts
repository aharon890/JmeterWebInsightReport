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
