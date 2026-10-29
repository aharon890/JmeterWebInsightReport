import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

const EXPECTED_HEADERS = [
  'Sampler', 'Samples', 'Errors', 'Error %',
  'Mean (ms)', 'Median (ms)', 'P90 (ms)', 'P95 (ms)', 'P99 (ms)',
  'Min (ms)', 'Max (ms)', 'Throughput', 'Recv KB/s', 'Sent KB/s', 'Apdex',
];

async function goSamplers(page: Page, testId = 'E02') {
  await loadClean(page, testId);
  await switchTab(page, 'samplers');
}

/** Return visible header texts from the sampler table (excludes hidden columns). */
async function visibleHeaders(page: Page): Promise<string[]> {
  return page.locator('#samplerTable thead th').evaluateAll(
    (ths: HTMLTableCellElement[]) =>
      ths.filter(th => th.style.display !== 'none').map(th => th.textContent!.trim())
  );
}

/** Count visible (non-hidden, non-detail) data rows. */
async function visibleDataRowCount(page: Page): Promise<number> {
  return page.locator('#samplerTable tbody tr').evaluateAll(
    (rows: HTMLTableRowElement[]) =>
      rows.filter(
        r =>
          r.style.display !== 'none' &&
          !r.classList.contains('sampler-detail-row') &&
          !r.classList.contains('total-sampler-row')
      ).length
  );
}

// ---------------------------------------------------------------------------
// Table defaults
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Table defaults', () => {
  test('15 column headers present and all visible', async ({ page }) => {
    await goSamplers(page);
    const headers = await visibleHeaders(page);
    expect(headers.length).toBe(15);   // the Aggregate Report's columns, Sent KB/s included (B8), plus Apdex
    for (const name of EXPECTED_HEADERS) {
      expect(headers).toContain(name);
    }
  });

  test('total row appears after enabling Total sampler, with non-zero Samples value', async ({ page }) => {
    await goSamplers(page);

    // Total sampler is unchecked by default — no total row initially
    const totalRow = page.locator('#samplerTable tbody tr.total-sampler-row');
    expect(await totalRow.count()).toBe(0);

    // Enable Total sampler by clicking its label (checkbox is display:none)
    const totalLabel = page.locator('#samplerFilterList label[data-sampler-name="\u03c3 total"]');
    await totalLabel.click();
    await page.waitForTimeout(400);

    // Now the total row should appear
    await expect(totalRow).toBeVisible();

    // Total row should contain a sigma symbol
    await expect(totalRow).toContainText('\u03A3');

    // Samples cell (index 1) should be a non-zero number
    const samplesText = await totalRow.locator('td').nth(1).textContent();
    const samplesValue = parseInt(samplesText!.replace(/,/g, ''), 10);
    expect(samplesValue).toBeGreaterThan(0);
  });

  test('data row count > 0', async ({ page }) => {
    await goSamplers(page);
    const count = await visibleDataRowCount(page);
    expect(count).toBeGreaterThan(0);
  });

  test('each data row has expand chevron and hide button', async ({ page }) => {
    await goSamplers(page);
    const dataRows = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child)'
    );
    const count = await dataRows.count();
    expect(count).toBeGreaterThan(0);

    for (let i = 0; i < Math.min(count, 5); i++) {
      const row = dataRows.nth(i);
      await expect(row.locator('.sampler-expand-icon')).toBeVisible();
      await expect(row.locator('.row-hide-btn')).toBeVisible();
    }
  });

  test('Apdex column shows values for each sampler', async ({ page }) => {
    await goSamplers(page);
    const apdexCells = page.locator('#samplerTable tbody tr[data-sampler]:not(.total-sampler-row) .apdex-badge');
    const count = await apdexCells.count();
    expect(count).toBeGreaterThan(0);

    for (let i = 0; i < Math.min(count, 5); i++) {
      const text = await apdexCells.nth(i).textContent();
      expect(text!.trim()).toMatch(/^\d/);
    }
  });
});

// ---------------------------------------------------------------------------
// Sort CRUD
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Sort CRUD', () => {
  test('clicking Samples header reorders rows; clicking again reverses', async ({ page }) => {
    await goSamplers(page);

    // Get first data row name before sort
    const firstDataRow = () =>
      page.locator(
        '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child):visible'
      ).first();

    const nameBefore = await firstDataRow().getAttribute('data-sampler');

    // Click "Samples" header to sort ascending
    await page.locator('#samplerTable thead th', { hasText: 'Samples' }).click();
    await page.waitForTimeout(300);
    const nameAfterFirst = await firstDataRow().getAttribute('data-sampler');
    // Rows should have reordered (or at least sorting was applied)
    // Note: if data row with fewest samples was already first, name could be same
    // so we check the header got a sort indicator instead
    const headerSort = await page.locator('#samplerTable thead th', { hasText: 'Samples' }).getAttribute('data-sort');
    expect(headerSort).toBe('asc');

    // Click again to reverse sort
    await page.locator('#samplerTable thead th', { hasText: 'Samples' }).click();
    await page.waitForTimeout(300);
    const headerSortDesc = await page.locator('#samplerTable thead th', { hasText: 'Samples' }).getAttribute('data-sort');
    expect(headerSortDesc).toBe('desc');

    // The first row after desc sort should be different from after asc sort
    const nameAfterSecond = await firstDataRow().getAttribute('data-sampler');
    expect(nameAfterSecond).not.toBe(nameAfterFirst);
  });
});

// ---------------------------------------------------------------------------
// Search CRUD
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Search CRUD', () => {
  test('search input filters rows, clear restores all', async ({ page }) => {
    await goSamplers(page);

    const searchInput = page.locator('#samplerSearch');
    await expect(searchInput).toBeVisible();

    const countBefore = await visibleDataRowCount(page);

    // Type a filter term that matches some but not all samplers
    await searchInput.fill('admin');
    await page.waitForTimeout(300);
    const countFiltered = await visibleDataRowCount(page);
    expect(countFiltered).toBeGreaterThan(0);
    expect(countFiltered).toBeLessThan(countBefore);

    // Clear search restores all rows
    await searchInput.fill('');
    await page.waitForTimeout(300);
    const countRestored = await visibleDataRowCount(page);
    expect(countRestored).toBe(countBefore);
  });
});

// ---------------------------------------------------------------------------
// Column hide/show CRUD
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Column hide/show CRUD', () => {
  test('Columns button opens menu with 15 checked checkboxes', async ({ page }) => {
    await goSamplers(page);

    const columnsBtn = page.locator('#columnMenuBtn');
    await expect(columnsBtn).toBeVisible();
    await columnsBtn.click();

    const menu = page.locator('#columnMenu');
    await expect(menu).toHaveClass(/open/);

    const checkboxes = menu.locator('input[type="checkbox"]');
    expect(await checkboxes.count()).toBe(15);

    // All should be checked initially
    for (let i = 0; i < 15; i++) {
      await expect(checkboxes.nth(i)).toBeChecked();
    }
  });

  test('uncheck Min and Max hides columns, re-check restores them', async ({ page }) => {
    await goSamplers(page);
    await page.locator('#columnMenuBtn').click();
    const menu = page.locator('#columnMenu');

    // Find "Min (ms)" checkbox by its label text
    const minLabel = menu.locator('label', { hasText: 'Min (ms)' });
    const maxLabel = menu.locator('label', { hasText: 'Max (ms)' });

    // Uncheck Min — should go from 15 to 14 visible headers
    await minLabel.locator('input').uncheck();
    await page.waitForTimeout(200);
    let headers = await visibleHeaders(page);
    expect(headers.length).toBe(14);
    expect(headers).not.toContain('Min (ms)');

    // Uncheck Max — should go to 13
    await maxLabel.locator('input').uncheck();
    await page.waitForTimeout(200);
    headers = await visibleHeaders(page);
    expect(headers.length).toBe(13);
    expect(headers).not.toContain('Max (ms)');

    // Verify data rows also lost the columns
    const firstDataCells = await page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row)'
    ).first().locator('td').evaluateAll(
      (cells: HTMLTableCellElement[]) => cells.filter(c => c.style.display !== 'none').length
    );
    expect(firstDataCells).toBe(13);

    // Re-check Min — back to 14
    await minLabel.locator('input').check();
    await page.waitForTimeout(200);
    headers = await visibleHeaders(page);
    expect(headers.length).toBe(14);
    expect(headers).toContain('Min (ms)');

    // Re-check Max — back to 15
    await maxLabel.locator('input').check();
    await page.waitForTimeout(200);
    headers = await visibleHeaders(page);
    expect(headers.length).toBe(15);
    expect(headers).toContain('Max (ms)');
  });
});

// ---------------------------------------------------------------------------
// Row hide/show CRUD
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Row hide/show CRUD', () => {
  test('hide two rows shows counter, show hidden restores both', async ({ page }) => {
    await goSamplers(page);

    const showHiddenBtn = page.locator('#showHiddenRowsBtn');
    await expect(showHiddenBtn).toBeHidden();

    const dataRows = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child):visible'
    );
    const countBefore = await dataRows.count();

    // Remember first two row names
    const row1Name = await dataRows.nth(0).getAttribute('data-sampler');
    const row2Name = await dataRows.nth(1).getAttribute('data-sampler');

    // Hide first data row via its X button
    await dataRows.nth(0).locator('.row-hide-btn').click({ force: true });
    await page.waitForTimeout(200);
    await expect(showHiddenBtn).toBeVisible();
    await expect(showHiddenBtn).toHaveText(/Show 1 Hidden Row/);

    // Hide second data row
    // Re-query because indices shifted
    const visibleRows = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child):visible'
    );
    await visibleRows.nth(0).locator('.row-hide-btn').click({ force: true });
    await page.waitForTimeout(200);
    await expect(showHiddenBtn).toHaveText(/Show 2 Hidden Rows/);

    // Show all hidden rows
    await showHiddenBtn.click();
    await page.waitForTimeout(300);
    await expect(showHiddenBtn).toBeHidden();

    const countAfter = await visibleDataRowCount(page);
    expect(countAfter).toBe(countBefore);

    // Verify both rows are back
    const restoredRow1 = page.locator(`#samplerTable tbody tr[data-sampler="${row1Name}"]`);
    const restoredRow2 = page.locator(`#samplerTable tbody tr[data-sampler="${row2Name}"]`);
    await expect(restoredRow1).toBeVisible();
    await expect(restoredRow2).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Detail expansion CRUD
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Detail expansion CRUD', () => {
  test('no detail panels on initial load', async ({ page }) => {
    await goSamplers(page);
    const detailRows = page.locator('#samplerTable tbody tr.sampler-detail-row');
    expect(await detailRows.count()).toBe(0);
  });

  test('click row expands detail with 3 chart slots and dropdown', async ({ page }) => {
    await goSamplers(page);

    // Click first data row to expand
    const firstRow = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child)'
    ).first();
    await firstRow.click();
    await page.waitForTimeout(500);

    // Detail row should appear
    const detailRow = page.locator('#samplerTable tbody tr.sampler-detail-row').first();
    await expect(detailRow).toBeVisible();

    // Owning row should be marked expanded
    await expect(firstRow).toHaveClass(/sampler-expanded/);

    // 3 chart slots by default
    const slots = detailRow.locator('.detail-chart-slot');
    expect(await slots.count()).toBe(3);

    // Each slot has a dropdown select
    for (let i = 0; i < 3; i++) {
      await expect(slots.nth(i).locator('select.detail-chart-select')).toBeVisible();
    }
  });

  test('change slot dropdown re-renders without error', async ({ page }) => {
    await goSamplers(page);

    const firstRow = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child)'
    ).first();
    await firstRow.click();
    await page.waitForTimeout(500);

    const detailRow = page.locator('#samplerTable tbody tr.sampler-detail-row').first();
    const select = detailRow.locator('select.detail-chart-select').first();

    // Change to a different chart type
    await expectNoJsErrors(page, async () => {
      await select.selectOption('throughput');
      await page.waitForTimeout(400);
    });

    // Slot should reflect the new key
    const slot = detailRow.locator('.detail-chart-slot').first();
    await expect(slot).toHaveAttribute('data-chart-key', 'throughput');
  });

  test('add chart slot then remove it', async ({ page }) => {
    await goSamplers(page);

    // Expand a row to make Add/Remove buttons visible
    const firstRow = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child)'
    ).first();
    await firstRow.click();
    await page.waitForTimeout(500);

    const addBtn = page.locator('#addDetailChartBtn');
    const removeBtn = page.locator('#removeDetailChartBtn');
    await expect(addBtn).toBeVisible();

    // Add a 4th chart slot
    await addBtn.click();
    await page.waitForTimeout(300);
    const detailRow = page.locator('#samplerTable tbody tr.sampler-detail-row').first();
    expect(await detailRow.locator('.detail-chart-slot').count()).toBe(4);

    // Remove the 4th slot
    await removeBtn.click();
    await page.waitForTimeout(300);
    expect(await detailRow.locator('.detail-chart-slot').count()).toBe(3);
  });

  test('click expanded row again collapses detail', async ({ page }) => {
    await goSamplers(page);

    const firstRow = page.locator(
      '#samplerTable tbody tr[data-sampler]:not(.total-sampler-row):not(.transaction-child)'
    ).first();

    // Expand
    await firstRow.click();
    await page.waitForTimeout(500);
    expect(await page.locator('#samplerTable tbody tr.sampler-detail-row').count()).toBe(1);

    // Collapse
    await firstRow.click();
    await page.waitForTimeout(300);
    expect(await page.locator('#samplerTable tbody tr.sampler-detail-row').count()).toBe(0);
    await expect(firstRow).not.toHaveClass(/sampler-expanded/);
  });
});

// ---------------------------------------------------------------------------
// Transaction Controllers [E02]
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — Transaction controllers [E02]', () => {
  test('Transaction Details button hidden when no transaction hierarchy in data', async ({ page }) => {
    await goSamplers(page);

    const toggleBtn = page.locator('#transactionChildrenBtn');

    // E02 has no transactionHierarchy in chartData, so button stays hidden
    const hasHierarchy = await page.evaluate(() => {
      // @ts-ignore
      return typeof globalChartData !== 'undefined' && globalChartData
        // @ts-ignore
        && globalChartData.transactionHierarchy
        // @ts-ignore
        && Object.keys(globalChartData.transactionHierarchy).length > 0;
    });

    if (hasHierarchy) {
      // If hierarchy exists, button should be visible and functional
      await expect(toggleBtn).toBeVisible();

      // By default, transaction children are hidden
      const childRows = page.locator('#samplerTable tbody tr.transaction-child');
      const hiddenCount = await childRows.evaluateAll(
        (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display === 'none').length
      );
      expect(hiddenCount).toBeGreaterThan(0);

      // Click toggle — children should appear
      await toggleBtn.click();
      await page.waitForTimeout(300);
      const visibleChildCount = await childRows.evaluateAll(
        (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display !== 'none').length
      );
      expect(visibleChildCount).toBeGreaterThan(0);

      // Click toggle again — children hidden
      await toggleBtn.click();
      await page.waitForTimeout(300);
      const hiddenAfter = await childRows.evaluateAll(
        (rows: HTMLTableRowElement[]) => rows.filter(r => r.style.display === 'none').length
      );
      expect(hiddenAfter).toBeGreaterThan(0);
    } else {
      // No hierarchy — button should remain hidden
      await expect(toggleBtn).toBeHidden();
    }
  });
});

// ---------------------------------------------------------------------------
// CSV export
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — CSV export', () => {
  test('CSV button is visible and click produces no JS error', async ({ page }) => {
    await goSamplers(page);

    const csvBtn = page.locator('#tab-samplers .table-csv-btn');
    await expect(csvBtn).toBeVisible();

    await expectNoJsErrors(page, async () => {
      // The click triggers a download; we only verify no error is thrown
      await csvBtn.click();
      await page.waitForTimeout(300);
    });
  });
});

// ---------------------------------------------------------------------------
// Assert badges [E11]
// ---------------------------------------------------------------------------

test.describe('Samplers Tab — assert badges [E11]', () => {
  test('at least one FAIL badge visible', async ({ page }) => {
    await loadClean(page, 'E11');
    await switchTab(page, 'samplers');

    const failBadges = page.locator('#samplerTable .assert-badge-fail');
    expect(await failBadges.count()).toBeGreaterThan(0);
    await expect(failBadges.first()).toBeVisible();
  });
});
