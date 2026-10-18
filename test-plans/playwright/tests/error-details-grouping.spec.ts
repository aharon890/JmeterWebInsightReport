import { test, expect, Page } from '@playwright/test';
import { loadClean, loadDirty, switchTab, expectNoJsErrors } from './helpers';

// ---------------------------------------------------------------------------
// Error Details — "By time" / "Grouped" view toggle
// ---------------------------------------------------------------------------

const TIME_BTN = '#errViewTimeBtn';
const GROUP_BTN = '#errViewGroupBtn';
const HEADS = '#errorGroupsTable tbody tr[data-group-head]';
const BODIES = '#errorGroupsTable tbody tr[data-group-body]';
const OCCURRENCE = 'div[style*="border-left"]';

async function goErrors(page: Page, testId = 'E02') {
  await loadClean(page, testId);
  await switchTab(page, 'errors');
}

async function goGrouped(page: Page, testId = 'E02') {
  await goErrors(page, testId);
  await page.click(GROUP_BTN);
  await expect(page.locator('#errViewGroup')).toBeVisible();
}

/** Count rows actually on screen — the view code sets display inline. */
function visibleCount(page: Page, selector: string): Promise<number> {
  return page.locator(selector).evaluateAll(
    (rows: HTMLElement[]) => rows.filter(r => r.style.display !== 'none').length
  );
}

/** Head-row count cells read like "12x" — pull out the number. */
async function groupCounts(page: Page): Promise<number[]> {
  const cells = await page.locator(`${HEADS} td:nth-child(2)`).allTextContents();
  return cells.map(c => parseInt(c.replace(/\D/g, ''), 10));
}

test.describe('Error Details — view toggle [E02]', () => {
  test('defaults to By time, and Grouped swaps the visible view', async ({ page }) => {
    await goErrors(page);

    await expect(page.locator(TIME_BTN)).toHaveClass(/active/);
    await expect(page.locator(GROUP_BTN)).not.toHaveClass(/active/);
    await expect(page.locator('#errViewTime')).toBeVisible();
    await expect(page.locator('#errViewGroup')).toBeHidden();

    await page.click(GROUP_BTN);
    await expect(page.locator(GROUP_BTN)).toHaveClass(/active/);
    await expect(page.locator(TIME_BTN)).not.toHaveClass(/active/);
    await expect(page.locator('#errViewGroup')).toBeVisible();
    await expect(page.locator('#errViewTime')).toBeHidden();

    // ...and back
    await page.click(TIME_BTN);
    await expect(page.locator(TIME_BTN)).toHaveClass(/active/);
    await expect(page.locator('#errViewTime')).toBeVisible();
    await expect(page.locator('#errViewGroup')).toBeHidden();
  });

  test('Grouped choice persists across reload', async ({ page }) => {
    await goGrouped(page);
    expect(await page.evaluate(() => localStorage.getItem('wir_errDetailsView'))).toBe('group');

    await loadDirty(page, 'E02');
    await switchTab(page, 'errors');

    await expect(page.locator('#errViewGroup')).toBeVisible();
    await expect(page.locator('#errViewTime')).toBeHidden();
    await expect(page.locator(GROUP_BTN)).toHaveClass(/active/);
  });

  test('group counts sum to the total error record count', async ({ page }) => {
    await goGrouped(page);

    const totalRecords = await page.evaluate(
      () => (window as any).globalChartData.errorRecords.length
    );
    expect(totalRecords).toBeGreaterThan(0);

    const counts = await groupCounts(page);
    expect(counts.length).toBeGreaterThan(0);
    expect(counts.reduce((a, c) => a + c, 0)).toBe(totalRecords);
  });

  test('groups are sorted by count descending', async ({ page }) => {
    await goGrouped(page);

    const counts = await groupCounts(page);
    for (let i = 1; i < counts.length; i++) {
      expect(counts[i]).toBeLessThanOrEqual(counts[i - 1]);
    }
  });

  test('clicking a head row expands its occurrence list, clicking again collapses', async ({ page }) => {
    await goGrouped(page);

    const head = page.locator(HEADS).first();
    const body = page.locator(BODIES).first();
    await expect(body).toBeHidden();
    await expect(head.locator('td').first()).toHaveText('▶');

    await head.click();
    await expect(body).toBeVisible();
    await expect(head.locator('td').first()).toHaveText('▼');

    // One occurrence line per counted error
    const count = (await groupCounts(page))[0];
    expect(await body.locator(OCCURRENCE).count()).toBe(count);

    await head.click();
    await expect(body).toBeHidden();
    await expect(head.locator('td').first()).toHaveText('▶');
  });

  test('occurrence lines show HH:mm:ss.SSS and a thread name', async ({ page }) => {
    await goGrouped(page);

    await page.locator(HEADS).first().click();
    const line = page.locator(`${BODIES} ${OCCURRENCE}`).first();
    await expect(line).toBeVisible();

    const spans = line.locator('span');
    await expect(spans.first()).toHaveText(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
    expect((await spans.nth(1).textContent())!.trim().length).toBeGreaterThan(0);
  });

  test('Expand All / Collapse All toggle every group', async ({ page }) => {
    await goGrouped(page);

    const total = await page.locator(BODIES).count();
    expect(total).toBeGreaterThan(0);
    expect(await visibleCount(page, BODIES)).toBe(0);

    await page.click('#errViewGroup .table-toolbar button:has-text("Expand All")');
    expect(await visibleCount(page, BODIES)).toBe(total);

    await page.click('#errViewGroup .table-toolbar button:has-text("Collapse All")');
    expect(await visibleCount(page, BODIES)).toBe(0);
  });

  test('search filters groups and preserves open state for matches', async ({ page }) => {
    await goGrouped(page);

    const headCount = await page.locator(HEADS).count();
    const search = page.locator('#errViewGroup input.table-search');

    // Open every group, then filter them all out
    await page.click('#errViewGroup .table-toolbar button:has-text("Expand All")');
    await search.fill('__no_such_error__');
    await page.waitForTimeout(200);
    expect(await visibleCount(page, HEADS)).toBe(0);
    // Non-matching bodies hide too — no occurrence list left orphaned on screen
    expect(await visibleCount(page, BODIES)).toBe(0);

    // Clearing restores every head, and groups that were open are open again
    await search.fill('');
    await page.waitForTimeout(200);
    expect(await visibleCount(page, HEADS)).toBe(headCount);
    expect(await visibleCount(page, BODIES)).toBe(headCount);
  });

  test('search narrows groups to a matching response code', async ({ page }) => {
    await goGrouped(page);

    const headCount = await page.locator(HEADS).count();
    const search = page.locator('#errViewGroup input.table-search');

    await search.fill('500');
    await page.waitForTimeout(200);
    const matched = await visibleCount(page, HEADS);
    expect(matched).toBeGreaterThan(0);
    expect(matched).toBeLessThanOrEqual(headCount);

    // Every visible head really does contain the term
    const shown = await page.locator(HEADS).evaluateAll(
      (rows: HTMLElement[]) => rows.filter(r => r.style.display !== 'none').map(r => r.textContent || '')
    );
    for (const text of shown) expect(text).toContain('500');
  });

  test('first seen is not after last seen for every group', async ({ page }) => {
    await goGrouped(page);

    const rows = await page.locator(HEADS).evaluateAll((heads: HTMLTableRowElement[]) =>
      heads.map(h => ({ first: h.cells[5].textContent!.trim(), last: h.cells[6].textContent!.trim() }))
    );
    expect(rows.length).toBeGreaterThan(0);
    for (const { first, last } of rows) {
      expect(first).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
      expect(last).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/);
      // Zero-padded HH:mm:ss.SSS compares correctly as a string
      expect(first <= last).toBe(true);
    }
  });

  test('CSV export works from both views', async ({ page }) => {
    await goErrors(page);
    const csvBtn = page.locator('#tab-errors section')
      .filter({ hasText: 'Error Details' }).locator('.table-csv-btn');

    await expectNoJsErrors(page, async () => {
      await csvBtn.click();
      await page.waitForTimeout(200);
      await page.click(GROUP_BTN);
      await csvBtn.click();
      await page.waitForTimeout(200);
    });
  });

  test('grouped view renders in dark + colorblind mode', async ({ page }) => {
    await goGrouped(page);

    await expectNoJsErrors(page, async () => {
      await page.click('#darkModeToggle');
      await page.click('#cbModeToggle');
      await page.locator(HEADS).first().click();
      await page.waitForTimeout(200);
    });

    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    await expect(page.locator('body')).toHaveClass(/cb-mode/);
    await expect(page.locator('#errViewGroup')).toBeVisible();
    await expect(page.locator(BODIES).first()).toBeVisible();

    // Themed tokens resolve to real colors rather than transparent fallbacks
    const chipBg = await page.locator(`${HEADS} .error-code`).first()
      .evaluate(el => getComputedStyle(el).backgroundColor);
    expect(chipBg).not.toBe('rgba(0, 0, 0, 0)');

    const borderColor = await page.locator(`${BODIES} ${OCCURRENCE}`).first()
      .evaluate(el => getComputedStyle(el).borderLeftColor);
    expect(borderColor).not.toBe('rgba(0, 0, 0, 0)');
  });
});

test.describe('Error Details — view toggle edge cases', () => {
  test('report with zero errors has no toggle and no JS error [E06]', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await goErrors(page, 'E06');
    });
    expect(await page.locator('#errorViewToggle').count()).toBe(0);
    expect(await page.locator('#errorGroupsTable').count()).toBe(0);
  });

  test('100% error report groups every record [E07]', async ({ page }) => {
    await goGrouped(page, 'E07');

    const counts = await groupCounts(page);
    expect(counts.length).toBeGreaterThan(0);

    const totalRecords = await page.evaluate(
      () => (window as any).globalChartData.errorRecords.length
    );
    expect(counts.reduce((a, c) => a + c, 0)).toBe(totalRecords);
  });
});
