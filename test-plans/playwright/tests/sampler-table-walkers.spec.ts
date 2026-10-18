import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * The rows that belong to a sampler row go where it goes.
 *
 * A sampler row can be followed by its chart panel (.sampler-detail-row, a click on the row) and by
 * the rules judging it (.wir-judging-row, a click on its verdict badge), in either order. Every
 * walker of the table knew about the chart panel and none knew about the judging row:
 *
 *  - NEW-judging-row-blind: sortTable sorted the judging row as a data row of its own (it landed by
 *    its own text, away from its sampler), the search filtered it by its own text (hidden under a
 *    sampler that matched), and hiding the Sampler column hid its only cell;
 *  - NEW-double-detail-row: toggleSamplerDetail looked only at the next row for an open panel, so
 *    with the judging row in between a second panel was built and the first never disposed — and
 *    toggleJudgingRules had the mirror image of it;
 *  - S4-showAllCharts: the Timeline's "Show Hidden Charts" read the whole document, so it un-hid
 *    the Errors and Compare tabs' charts too and left their own buttons stale.
 */

const SLOW = '#samplerTable tbody tr[data-sampler="Slow API"]';

async function openJudgingRow(page: Page): Promise<void> {
  await loadClean(page, 'E25');
  await switchTab(page, 'samplers');
  await page.locator(`${SLOW} .assert-badge-mini`).click();
  await expect(page.locator('.wir-judging-row')).toHaveCount(1);
}

/** The class (or sampler name) of every row in the sampler table's body, in order. */
async function bodyRows(page: Page): Promise<string[]> {
  return page.locator('#samplerTable tbody tr').evaluateAll((rows: HTMLTableRowElement[]) =>
    rows.map((r) => r.classList.contains('wir-judging-row') ? 'judging'
      : r.classList.contains('sampler-detail-row') ? 'detail'
        : r.getAttribute('data-sampler') || r.className));
}

/** What follows the Slow API row, up to the next sampler row. */
async function companionsOfSlow(page: Page): Promise<string[]> {
  const rows = await bodyRows(page);
  const at = rows.indexOf('Slow API');
  const out: string[] = [];
  for (let i = at + 1; i < rows.length && (rows[i] === 'judging' || rows[i] === 'detail'); i++) out.push(rows[i]);
  return out;
}

test.describe('The judging row belongs to its sampler [E25]', () => {

  test('a sort in either direction keeps it directly under its sampler', async ({ page }) => {
    await openJudgingRow(page);
    for (const _ of [1, 2]) {
      await page.evaluate(() => (window as any).sortTable(0, 'text'));
      expect(await companionsOfSlow(page)).toEqual(['judging']);
      expect((await bodyRows(page)).filter((r) => r === 'judging')).toHaveLength(1);
    }
  });

  test('a numeric sort keeps it under its sampler too, with the chart panel beside it', async ({ page }) => {
    await openJudgingRow(page);
    await page.locator(`${SLOW} .sampler-name-text`).click();
    await expect(page.locator('#samplerTable tbody tr.sampler-detail-row')).toHaveCount(1);
    const before = await companionsOfSlow(page);
    expect(before.sort()).toEqual(['detail', 'judging']);
    await page.evaluate(() => (window as any).sortTable(7, 'num'));   // P95
    expect((await companionsOfSlow(page)).sort()).toEqual(['detail', 'judging']);
  });

  test('the search shows and hides it with its sampler, never by its own text', async ({ page }) => {
    await openJudgingRow(page);
    const judging = page.locator('.wir-judging-row');
    const search = page.locator('#samplerSearch');

    // The sampler matches; the rule list never names it ("slow-api-p95", "p95 = …"), so searched by
    // its own text the judging row was hidden under its own sampler.
    await search.fill('Slow API');
    await expect(page.locator(SLOW)).toBeVisible();
    await expect(judging).toBeVisible();

    // No sampler is called "p95", and the rule list says it twice: it stayed on screen alone.
    await search.fill('p95');
    await expect(page.locator(SLOW)).toBeHidden();
    await expect(judging).toBeHidden();

    await search.fill('');
    await expect(judging).toBeVisible();
  });

  test('hiding the Sampler column keeps the rule list on screen', async ({ page }) => {
    await openJudgingRow(page);
    await page.locator('#columnMenuBtn').click();
    await page.locator('#columnMenu label', { hasText: 'Sampler' }).locator('input').uncheck();
    await expect(page.locator('#samplerTable thead th').first()).toBeHidden();   // precondition
    await expect(page.locator('.wir-judging-row td')).toBeVisible();
    await expect(page.locator('.wir-judging-row')).toContainText('slow-api-p95');
  });

  test('hiding the sampler with × takes its judging row and its chart panel with it', async ({ page }) => {
    await openJudgingRow(page);
    await page.locator(`${SLOW} .sampler-name-text`).click();
    await expect(page.locator('#samplerTable tbody tr.sampler-detail-row')).toHaveCount(1);
    await page.locator(`${SLOW} .row-hide-btn`).click();
    await expect(page.locator('.wir-judging-row')).toHaveCount(0);
    await expect(page.locator('#samplerTable tbody tr.sampler-detail-row')).toHaveCount(0);
  });
});

test.describe('One chart panel per sampler, whatever is open beside it [E25]', () => {

  test('with the judging row open, a click on the row closes its panel instead of building a second', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    const panels = page.locator('#samplerTable tbody tr.sampler-detail-row');

    await page.locator(`${SLOW} .sampler-name-text`).click();          // the panel, directly below
    await expect(panels).toHaveCount(1);
    await page.locator(`${SLOW} .assert-badge-mini`).click();           // the judging row, between them
    await expect(page.locator('.wir-judging-row')).toHaveCount(1);
    expect(await companionsOfSlow(page)).toEqual(['judging', 'detail']);

    await page.locator(`${SLOW} .sampler-name-text`).click();
    await expect(panels).toHaveCount(0);
    await expect(page.locator(SLOW)).not.toHaveClass(/sampler-expanded/);

    await page.locator(`${SLOW} .sampler-name-text`).click();
    await expect(panels).toHaveCount(1);
  });

  test('with the panel open, the badge closes its judging row instead of building a second', async ({ page }) => {
    await openJudgingRow(page);
    await page.locator(`${SLOW} .sampler-name-text`).click();           // the panel goes in between
    expect(await companionsOfSlow(page)).toEqual(['detail', 'judging']);

    await page.locator(`${SLOW} .assert-badge-mini`).click();
    await expect(page.locator('.wir-judging-row')).toHaveCount(0);
    await expect(page.locator('#samplerTable tbody tr.sampler-detail-row')).toHaveCount(1);
  });
});

test.describe('"Show Hidden Charts" is the Timeline\'s own [E02]', () => {

  test('it shows the Timeline\'s hidden charts and leaves the Errors tab\'s hidden, and its button', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
    const errorChart = page.locator('#tab-errors .timeline-chart-section[data-chart-id="error-breakdown"]');
    const errorsButton = page.locator('#showHiddenErrorChartsBtn');
    await errorChart.locator('.chart-hide-btn').click();
    await expect(errorChart).toHaveClass(/chart-hidden/);
    await expect(errorsButton).toHaveText('Show 1 Hidden Chart');

    await switchTab(page, 'timeline');
    const timelineButton = page.locator('#showHiddenChartsBtn');
    await expect(timelineButton).toBeHidden();               // nothing on THIS tab is hidden
    const timelineChart = page.locator('#tab-timeline .timeline-chart-section').first();
    await timelineChart.locator('.chart-hide-btn').click();
    await expect(timelineButton).toHaveText('Show 1 Hidden Chart');   // not 2

    await timelineButton.click();
    await expect(timelineChart).not.toHaveClass(/chart-hidden/);
    await expect(timelineButton).toBeHidden();
    await expect(errorChart).toHaveClass(/chart-hidden/);
    await expect(errorsButton).toHaveText('Show 1 Hidden Chart');
  });
});

test.describe('Transaction Details takes a child\'s rows with it [E02]', () => {

  test('collapsing the children hides an open child\'s chart panel, and expanding brings it back', async ({ page }) => {
    // toggleTransactionChildrenVisibility hid and showed the child rows alone: a chart panel open
    // under a child stayed on screen under nothing once the children were collapsed.
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const toggle = page.locator('#transactionChildrenBtn');
    const child = page.locator('#samplerTable tbody tr.transaction-child').first();
    const panels = page.locator('#samplerTable tbody tr.sampler-detail-row');

    await toggle.click();                                    // children shown
    await expect(child).toBeVisible();
    await child.locator('.sampler-name-text').click();       // its chart panel, directly below
    await expect(panels).toHaveCount(1);
    await expect(panels.first()).toBeVisible();

    await toggle.click();                                    // children collapsed
    await expect(child).toBeHidden();
    await expect(panels.first()).toBeHidden();

    await toggle.click();                                    // and shown again
    await expect(child).toBeVisible();
    await expect(panels.first()).toBeVisible();
  });
});
