import { test, expect, Page } from '@playwright/test';
import { loadClean, loadDirty, switchTab, expectNoJsErrors } from './helpers';

// ---------------------------------------------------------------------------
// Error Details — Group by (spec 002, stage 5)
//
// The "By time" / "Grouped" switch is the table's Group by now, as on every table of the report:
// none (the errors in time order), the sampler and type of error (what the Grouped view showed), the
// error class, or the response code. A group's header counts its errors; its rows are the table's own
// rows, so the search, x, Columns and CSV work on them as on the ungrouped table.
// ---------------------------------------------------------------------------

const GROUP = '#errorDetailsTable-group';
const HEADS = '#errorDetailsTable tr.wir-group-row';
const ROWS = '#errorDetailsTable tbody tr.error-row-main';

async function goErrors(page: Page, testId = 'E02') {
  await loadClean(page, testId);
  await switchTab(page, 'errors');
}

async function goGrouped(page: Page, testId = 'E02') {
  await goErrors(page, testId);
  await page.selectOption(GROUP, 'error');
  await expect(page.locator(HEADS).first()).toBeVisible();
}

/** The count each group's header gives: "12 errors". */
async function groupCounts(page: Page): Promise<number[]> {
  const cells = await page.locator(`${HEADS} .wir-group-counts`).allTextContents();
  return cells.map((c) => parseInt(c, 10));
}

function visibleCount(page: Page, selector: string): Promise<number> {
  return page.locator(selector).evaluateAll((rows: HTMLElement[]) => rows.filter((r) => r.style.display !== 'none').length);
}

test.describe('Error Details — Group by [E02]', () => {
  test('opens in time order, ungrouped, and Group by offers the old Grouped view, the class and the code', async ({ page }) => {
    await goErrors(page);
    await expect(page.locator(GROUP)).toHaveValue('');
    await expect(page.locator(HEADS)).toHaveCount(0);
    const options = await page.locator(`${GROUP} option`).evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
    expect(options).toEqual(['', 'error', 'class', 'code']);
  });

  test('group counts add up to every error kept in detail', async ({ page }) => {
    await goGrouped(page);
    const counts = await groupCounts(page);
    const kept = await page.evaluate(() => (window as any).globalChartData.errorRecords.length);
    expect(counts.reduce((a, c) => a + c, 0)).toBe(kept);
  });

  test('groups come largest first', async ({ page }) => {
    await goGrouped(page);
    const counts = await groupCounts(page);
    expect(counts).toEqual([...counts].sort((a, b) => b - a));
  });

  test('a group\'s header closes and opens its rows; Collapse all and Expand all do every group', async ({ page }) => {
    await goGrouped(page);
    const all = await visibleCount(page, ROWS);
    const first = page.locator(HEADS).first();
    const n = (await groupCounts(page))[0];
    await first.locator('.wir-group-toggle').click();
    expect(await visibleCount(page, ROWS)).toBe(all - n);
    await page.click('#errorDetailsTable-collapse-all');
    expect(await visibleCount(page, ROWS)).toBe(0);
    await page.click('#errorDetailsTable-expand-all');
    expect(await visibleCount(page, ROWS)).toBe(all);
  });

  test('the search narrows the groups to the rows that match', async ({ page }) => {
    await goGrouped(page);
    const code = (await page.locator(`${ROWS} td[data-col-id], ${ROWS} td`).nth(2).textContent())!.trim();
    await page.fill('#errorDetailsTable-search', code);
    const shown = await page.locator(ROWS).evaluateAll((rows: HTMLElement[]) =>
      rows.filter((r) => r.style.display !== 'none').map((r) => r.textContent || ''));
    expect(shown.length).toBeGreaterThan(0);
    for (const text of shown) expect(text).toContain(code);
  });

  test('the grouping is remembered for this report', async ({ page }) => {
    await goGrouped(page);
    await loadDirty(page, 'E02');
    await switchTab(page, 'errors');
    await expect(page.locator(GROUP)).toHaveValue('error');
    await expect(page.locator(HEADS).first()).toBeVisible();
  });

  test('grouped by code, each group is one response code', async ({ page }) => {
    await goErrors(page);
    await page.selectOption(GROUP, 'code');
    const labels = await page.locator(`${HEADS} .wir-group-toggle`).allTextContents();
    expect(labels.length).toBeGreaterThan(0);
    expect(new Set(labels).size).toBe(labels.length);
  });

  test('CSV from the grouped table writes every row kept, whatever is closed', async ({ page }) => {
    await goGrouped(page);
    await page.click('#errorDetailsTable-collapse-all');
    const csv = await page.evaluate(() => {
      let captured = '';
      const w = window as any;
      const real = w.downloadCsvFile;
      w.downloadCsvFile = (_n: string, c: string) => { captured = c; };
      w.WIR.table.csv('errorDetailsTable');
      w.downloadCsvFile = real;
      return captured;
    });
    const kept = await page.evaluate(() => (window as any).globalChartData.errorRecords.length);
    expect(csv.trim().split('\n').length).toBe(kept + 1);
  });

  test('grouped, the page draws with no error in dark and colourblind mode', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await goGrouped(page);
      await page.evaluate(() => {
        document.body.classList.add('dark-mode');
        document.body.classList.add('colorblind-mode');
      });
      await expect(page.locator(HEADS).first()).toBeVisible();
    });
  });
});

test.describe('Error Details — Group by [E07]', () => {
  test('a 100% error report groups every record', async ({ page }) => {
    await goGrouped(page, 'E07');
    const counts = await groupCounts(page);
    expect(counts.length).toBeGreaterThan(0);
    const kept = await page.evaluate(() => (window as any).globalChartData.errorRecords.length);
    expect(counts.reduce((a, c) => a + c, 0)).toBe(kept);
  });
});
