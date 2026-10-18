import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/* ============================================================
 *  User Flow: Investigate a Slow Sampler End-to-End
 *
 *  Open E02 → verify summary → read metrics → note slowest →
 *  Samplers tab sort → expand detail → change chart → add/remove slot →
 *  collapse → Errors tab → search & clear
 * ============================================================ */

test.describe('Flow: Investigate a Slow Sampler', () => {
  test('end-to-end investigation of the slowest sampler', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // --- Step 1: Open E02, verify summary loads ---
    await loadClean(page, 'E02');
    await expect(page.locator('#tab-summary')).toHaveClass(/active/);

    // --- Step 2: Read Total Samples value (> 0) ---
    const totalSamplesCard = page.locator('.summary-card').nth(0);
    const totalSamplesText = await totalSamplesCard.locator('.card-value').textContent();
    const totalSamples = parseInt(totalSamplesText!.replace(/,/g, ''));
    expect(totalSamples).toBeGreaterThan(0);

    // --- Step 3: Read the whole-run P95 value (> 0) ---
    const allCards = page.locator('.summary-card');
    const p95Text = await allCards.nth(4).locator('.card-value').textContent();
    // P95 card shows value with "ms" suffix
    const p95Value = parseFloat(p95Text!.replace(/[^0-9.]/g, ''));
    expect(p95Value).toBeGreaterThan(0);

    // --- Step 4: Note the slowest sampler from Top 5 rank #1 ---
    const top1Row = page.locator('.top-sampler-row').first();
    await expect(top1Row.locator('.top-sampler-rank')).toHaveText('1');
    const slowestName = (await top1Row.locator('.top-sampler-name').textContent())!.trim();
    expect(slowestName.length).toBeGreaterThan(0);

    // --- Step 5: Click Samplers tab ---
    await switchTab(page, 'samplers');
    await expect(page.locator('#tab-samplers')).toHaveClass(/active/);

    // --- Step 6: Click P95 column header to sort descending ---
    // P95 is column index 7 (0-based). Click once for ascending, again for descending
    const p95Header = page.locator('#samplerTable thead th').nth(7);
    await expect(p95Header).toContainText('P95');
    await p95Header.click();
    await page.waitForTimeout(200);
    // Check sort direction; if ascending, click again
    const sortDir = await p95Header.getAttribute('data-sort');
    if (sortDir === 'asc') {
      await p95Header.click();
      await page.waitForTimeout(200);
    }

    // --- Step 7: Verify first data row matches Top 5 #1 name ---
    const firstDataRow = page.locator('#samplerTable tbody tr:not(.sampler-detail-row)').first();
    const firstRowName = await firstDataRow.locator('.sampler-name-text').textContent();
    expect(firstRowName!.trim()).toBe(slowestName);

    // --- Step 8: Click first row to expand detail panel ---
    await firstDataRow.click();
    await page.waitForTimeout(500);
    await expect(firstDataRow).toHaveClass(/sampler-expanded/);

    // --- Step 9: Verify 3 chart slots in detail panel ---
    const detailRow = page.locator('.sampler-detail-row').first();
    await expect(detailRow).toBeVisible();
    let slots = detailRow.locator('.detail-chart-slot');
    expect(await slots.count()).toBe(3);

    // --- Step 10: Change slot 1 dropdown to a different chart, verify it renders ---
    const firstSlotSelect = slots.first().locator('.detail-chart-select');
    const currentValue = await firstSlotSelect.inputValue();
    // Pick a different chart key
    const options = await firstSlotSelect.locator('option').allTextContents();
    expect(options.length).toBeGreaterThan(1);
    // Find a value different from current
    const allOptionValues = await firstSlotSelect.locator('option').evaluateAll(
      opts => (opts as HTMLOptionElement[]).map(o => o.value)
    );
    const differentKey = allOptionValues.find(v => v !== currentValue) || 'throughput';
    await firstSlotSelect.selectOption(differentKey);
    await page.waitForTimeout(500);

    // Verify the chart container has a canvas (ECharts rendered)
    const slotCanvas = slots.first().locator('.detail-chart-container canvas');
    await expect(slotCanvas.first()).toBeVisible();

    // --- Step 11: Click "+ Add Chart" → 4th slot appears ---
    const addBtn = page.locator('#addDetailChartBtn');
    await expect(addBtn).toBeVisible();
    await addBtn.click();
    await page.waitForTimeout(400);
    slots = detailRow.locator('.detail-chart-slot');
    expect(await slots.count()).toBe(4);

    // --- Step 12: Click "x Remove Chart" → back to 3 ---
    const removeBtn = page.locator('#removeDetailChartBtn');
    await expect(removeBtn).toBeVisible();
    await removeBtn.click();
    await page.waitForTimeout(400);
    slots = detailRow.locator('.detail-chart-slot');
    expect(await slots.count()).toBe(3);

    // --- Step 13: Click row to collapse → detail gone ---
    await firstDataRow.click();
    await page.waitForTimeout(400);
    await expect(firstDataRow).not.toHaveClass(/sampler-expanded/);
    const detailRowsAfterCollapse = page.locator('.sampler-detail-row');
    expect(await detailRowsAfterCollapse.count()).toBe(0);

    // --- Step 14: Switch to Errors tab → verify error table has rows ---
    await switchTab(page, 'errors');
    await expect(page.locator('#tab-errors')).toHaveClass(/active/);

    const errorTable = page.locator('#errorSummaryTable');
    const errorRows = errorTable.locator('tbody tr');
    expect(await errorRows.count()).toBeGreaterThan(0);

    // --- Step 15: Search for the slow sampler name → filtered ---
    const errorSearch = page.locator('#tab-errors .table-search').first();
    await errorSearch.fill(slowestName);
    await page.waitForTimeout(300);

    // Check visible rows contain the sampler name
    const visibleRows = errorTable.locator('tbody tr:visible');
    const visibleCount = await visibleRows.count();
    // Some rows may be filtered out; verify at least one visible row contains the name
    // (If the sampler has no errors, visibleCount could be 0 — that's also valid)
    if (visibleCount > 0) {
      const firstVisibleText = await visibleRows.first().textContent();
      expect(firstVisibleText!.toLowerCase()).toContain(slowestName.toLowerCase());
    }

    // --- Step 16: Clear search → all rows back ---
    await errorSearch.fill('');
    await page.waitForTimeout(300);

    const allRowsAfterClear = errorTable.locator('tbody tr');
    expect(await allRowsAfterClear.count()).toBeGreaterThan(0);

    // No JS errors throughout the entire flow
    expect(errors).toEqual([]);
  });
});
