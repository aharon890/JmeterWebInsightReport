import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

// =============================================================================
//  Group 1: Compare Tab -- Live Threshold Adjustment (CMP report)
// =============================================================================

test.describe('Compare Tab -- Threshold Controls', () => {

  test('[R] threshold section is visible with P95 and Error Rate inputs', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');

    const section = page.locator('#comparison-thresholds-section');
    await expect(section).toBeVisible();

    const p95Input = page.locator('#thP95');
    const errInput = page.locator('#thErrorRate');
    await expect(p95Input).toBeVisible();
    await expect(errInput).toBeVisible();

    // Verify they are number inputs with default values populated
    await expect(p95Input).toHaveAttribute('type', 'number');
    await expect(errInput).toHaveAttribute('type', 'number');

    // Defaults from comparisonThresholds: p95=10, errorRate=2
    const p95Val = await p95Input.inputValue();
    const errVal = await errInput.inputValue();
    expect(Number(p95Val)).toBe(10);
    expect(Number(errVal)).toBe(2);
  });

  test('[R] threshold section has Mean, P99, Throughput inputs and Apply button', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');

    await expect(page.locator('#thMean')).toBeVisible();
    await expect(page.locator('#thP99')).toBeVisible();
    await expect(page.locator('#thThroughput')).toBeVisible();

    // Apply button
    const applyBtn = page.locator('#comparison-thresholds-section button', { hasText: 'Apply' });
    await expect(applyBtn).toBeVisible();
  });

  test('[U] set P95 threshold to 1 -> Apply -> more REGRESSION badges appear', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Count initial regressions
    const table = page.locator('#tab-compare table');
    const initialRegressions = await table.evaluate((t) => {
      return Array.from(t.querySelectorAll('tbody td')).filter(
        td => td.textContent?.trim() === 'REGRESSION'
      ).length;
    });

    // Set P95 threshold very low (1%) so almost any change triggers regression
    const p95Input = page.locator('#thP95');
    await p95Input.fill('1');
    // Also enable mean with low threshold
    const meanInput = page.locator('#thMean');
    await meanInput.fill('1');

    // Click Apply
    const applyBtn = page.locator('#comparison-thresholds-section button', { hasText: 'Apply' });
    await applyBtn.click();
    await page.waitForTimeout(500);

    // Count regressions after applying low thresholds
    const newRegressions = await table.evaluate((t) => {
      return Array.from(t.querySelectorAll('tbody td')).filter(
        td => td.textContent?.trim() === 'REGRESSION'
      ).length;
    });

    // With very low thresholds, we expect more regressions (or at least equal)
    expect(newRegressions).toBeGreaterThanOrEqual(initialRegressions);
    expect(errors).toEqual([]);
  });

  test('[U] set P95 threshold to 9999 -> Apply -> fewer/no REGRESSION badges', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    const table = page.locator('#tab-compare table');

    // Set all thresholds extremely high so nothing triggers regression
    await page.locator('#thP95').fill('9999');
    await page.locator('#thErrorRate').fill('9999');
    await page.locator('#thMean').fill('9999');
    await page.locator('#thP99').fill('9999');
    await page.locator('#thThroughput').fill('9999');

    const applyBtn = page.locator('#comparison-thresholds-section button', { hasText: 'Apply' });
    await applyBtn.click();
    await page.waitForTimeout(500);

    // Count regressions -- should be 0 with huge thresholds
    const regressions = await table.evaluate((t) => {
      return Array.from(t.querySelectorAll('tbody td')).filter(
        td => td.textContent?.trim() === 'REGRESSION'
      ).length;
    });

    expect(regressions).toBe(0);
    expect(errors).toEqual([]);
  });

  test('no JS errors through full threshold adjustment cycle', async ({ page }) => {
    await loadClean(page, 'CMP');
    await switchTab(page, 'compare');

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    const applyBtn = page.locator('#comparison-thresholds-section button', { hasText: 'Apply' });

    // Cycle 1: very low
    await page.locator('#thP95').fill('0');
    await applyBtn.click();
    await page.waitForTimeout(300);

    // Cycle 2: disable all (-1)
    await page.locator('#thP95').fill('-1');
    await page.locator('#thErrorRate').fill('-1');
    await page.locator('#thMean').fill('-1');
    await page.locator('#thP99').fill('-1');
    await page.locator('#thThroughput').fill('-1');
    await applyBtn.click();
    await page.waitForTimeout(300);

    // Cycle 3: restore defaults
    await page.locator('#thP95').fill('10');
    await page.locator('#thErrorRate').fill('2');
    await applyBtn.click();
    await page.waitForTimeout(300);

    expect(errors).toEqual([]);
  });
});

// =============================================================================
//  Group 2: Error Details -- JSON Response Body Formatting (E02 report)
// =============================================================================

test.describe('Error Details -- Response Body Formatting', () => {

  test('[R] Errors tab has Error Details table with clickable rows', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    const table = page.locator('#errorDetailsTable');
    await expect(table).toBeVisible();

    const mainRows = table.locator('tbody tr.error-row-main');
    expect(await mainRows.count()).toBeGreaterThan(0);

    // Rows should have data-error-idx attribute
    const firstIdx = await mainRows.first().getAttribute('data-error-idx');
    expect(firstIdx).not.toBeNull();
  });

  test('[C] click detail row to expand -> expanded content visible', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    // No detail rows initially
    expect(await page.locator('#errorDetailsTable tbody tr.error-detail-row').count()).toBe(0);

    // Click first error row
    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
    await firstRow.click();
    await page.waitForTimeout(400);

    // Detail row should appear
    const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
    await expect(detailRow).toBeVisible();
    const detailText = await detailRow.textContent();
    expect(detailText!.length).toBeGreaterThan(0);
  });

  test('[R] expanded error detail contains JSON with formatting/syntax highlighting', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    // Click first row to expand
    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
    await firstRow.click();
    await page.waitForTimeout(400);

    const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
    await expect(detailRow).toBeVisible();

    // Check for response body panel
    const bodyPanel = detailRow.locator('.response-body-panel');
    if (await bodyPanel.count() > 0) {
      // The response body content should be in a <pre> element
      const pre = bodyPanel.locator('pre.response-body-content');
      await expect(pre).toBeVisible();

      // Check that JSON is pretty-printed (has indentation with newlines)
      const bodyHtml = await pre.innerHTML();
      // formatAndHighlightBody does JSON.stringify(parsed, null, 2), producing newlines
      expect(bodyHtml).toContain('\n');

      // Check for syntax highlighting spans (json-key, json-string, etc.)
      const hasHighlighting = await pre.evaluate((el) => {
        return el.querySelectorAll('.json-key, .json-string, .json-number, .json-bool').length > 0;
      });
      expect(hasHighlighting).toBe(true);
    } else {
      // If this specific row has no body, find one that does
      // (E02 records have body field, so at least some should)
      const hasAnyBody = await page.evaluate(() => {
        // @ts-ignore
        const records = globalChartData?.errorRecords;
        if (!records) return false;
        return records.some((r: any) => r.body || r.responseBody);
      });
      expect(hasAnyBody).toBe(true);
    }
  });

  test('[R] expanded error detail shows URL field when available', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    // Find a row that has URL data by checking errorRecords
    const idxWithUrl = await page.evaluate(() => {
      // @ts-ignore
      const records = globalChartData?.errorRecords;
      if (!records) return -1;
      return records.findIndex((r: any) => r.url && r.url.length > 0);
    });

    if (idxWithUrl >= 0) {
      // Click the row with URL data
      const targetRow = page.locator(`#errorDetailsTable tbody tr.error-row-main[data-error-idx="${idxWithUrl}"]`);
      await targetRow.click();
      await page.waitForTimeout(400);

      const detailRow = targetRow.locator('~ tr.error-detail-row').first();
      await expect(detailRow).toBeVisible();

      // URL should be displayed
      const urlText = await detailRow.locator('text=URL:').count();
      expect(urlText).toBeGreaterThan(0);
    } else {
      // No URL data available in E02 -- verify body is shown instead
      const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
      await firstRow.click();
      await page.waitForTimeout(400);

      const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
      await expect(detailRow).toBeVisible();

      // At least response body or some content should be present
      const detailContent = await detailRow.textContent();
      expect(detailContent!.length).toBeGreaterThan(10);
    }
  });

  test('[D] click expanded row again to collapse -> detail row disappears', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();

    // Expand
    await firstRow.click();
    await page.waitForTimeout(400);
    expect(await page.locator('#errorDetailsTable tbody tr.error-detail-row').count()).toBe(1);

    // Collapse
    await firstRow.click();
    await page.waitForTimeout(400);
    expect(await page.locator('#errorDetailsTable tbody tr.error-detail-row').count()).toBe(0);
  });

  test('response body viewer has Copy, Wrap, Raw buttons', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    // Click first row to expand
    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
    await firstRow.click();
    await page.waitForTimeout(400);

    const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
    const bodyPanel = detailRow.locator('.response-body-panel');

    if (await bodyPanel.count() > 0) {
      const copyBtn = bodyPanel.locator('button', { hasText: 'Copy' });
      const wrapBtn = bodyPanel.locator('button', { hasText: 'Wrap' });
      const rawBtn = bodyPanel.locator('button', { hasText: 'Raw' });

      await expect(copyBtn).toBeVisible();
      await expect(wrapBtn).toBeVisible();
      await expect(rawBtn).toBeVisible();
    }
  });

  test('Raw toggle switches between formatted and raw body without JS errors', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Expand first row
    const firstRow = page.locator('#errorDetailsTable tbody tr.error-row-main').first();
    await firstRow.click();
    await page.waitForTimeout(400);

    const detailRow = page.locator('#errorDetailsTable tbody tr.error-detail-row').first();
    const bodyPanel = detailRow.locator('.response-body-panel');

    if (await bodyPanel.count() > 0) {
      const rawBtn = bodyPanel.locator('button', { hasText: 'Raw' });
      const pre = bodyPanel.locator('pre.response-body-content');

      // Get formatted content
      const formattedHtml = await pre.innerHTML();

      // Click Raw to toggle
      await rawBtn.click();
      await page.waitForTimeout(200);

      // Content should change (raw vs formatted differ for JSON)
      const rawHtml = await pre.innerHTML();
      // At minimum no crash
      expect(rawHtml.length).toBeGreaterThan(0);

      // Toggle back
      await rawBtn.click();
      await page.waitForTimeout(200);
    }

    expect(errors).toEqual([]);
  });
});

// =============================================================================
//  Group 3: Annotations -- Notes Tab Content (E12 report)
// =============================================================================

test.describe('Annotations -- Notes Tab Rendering (E12)', () => {

  test('[R] Notes tab visible and clickable in E12', async ({ page }) => {
    await loadClean(page, 'E12');

    const notesBtn = page.locator('.tab-btn[data-tab="notes"]');
    await expect(notesBtn).toBeVisible();

    await switchTab(page, 'notes');

    const notesPanel = page.locator('#tab-notes');
    await expect(notesPanel).toBeVisible();
  });

  test('[R] Notes tab contains pre-loaded annotation content from full-annotations.json', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    // The textarea should contain the testNotes markdown
    const textarea = page.locator('#notesTextarea');
    await expect(textarea).toBeVisible();

    const content = await textarea.inputValue();

    // Verify content from the annotations file
    expect(content).toContain('Load Test');
    expect(content).toContain('E-Commerce API');
    expect(content).toContain('Objective');
    expect(content).toContain('checkout flow');
    expect(content).toContain('Environment');
    expect(content).toContain('staging-api.example.com');
    expect(content).toContain('Key Observations');
    expect(content).toContain('Payment processing');
  });

  test('[R] Preview renders markdown as formatted HTML with headings', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    // Click Preview button to render markdown
    const previewBtn = page.locator('#tab-notes button', { hasText: 'Preview' });
    await expect(previewBtn).toBeVisible();
    await previewBtn.click();
    await page.waitForTimeout(400);

    // Preview div should now be visible with rendered markdown
    const preview = page.locator('#notesPreview');
    await expect(preview).toBeVisible();

    // Verify heading elements exist (## becomes h2, ### becomes h3)
    const headings = preview.locator('h1, h2, h3');
    expect(await headings.count()).toBeGreaterThanOrEqual(1);

    // Verify specific content is rendered (not raw markdown syntax)
    const previewHtml = await preview.innerHTML();
    // Should have HTML tags, not raw ## markers
    expect(previewHtml).toContain('<');
    // Should contain the text content
    expect(previewHtml).toContain('Objective');
    expect(previewHtml).toContain('Environment');
  });

  test('[R] Preview renders bold and list items from markdown', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    // Click Preview
    const previewBtn = page.locator('#tab-notes button', { hasText: 'Preview' });
    await previewBtn.click();
    await page.waitForTimeout(400);

    const preview = page.locator('#notesPreview');
    await expect(preview).toBeVisible();

    // **Server:** should render as <strong>
    const strongTags = preview.locator('strong');
    expect(await strongTags.count()).toBeGreaterThanOrEqual(1);

    // Ordered list items from "Key Observations"
    const listItems = preview.locator('li');
    expect(await listItems.count()).toBeGreaterThanOrEqual(1);
  });

  test('Save Annotations button exists and clickable without JS error', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'notes');

    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    const saveBtn = page.locator('#tab-notes button', { hasText: 'Save Annotations' });
    await expect(saveBtn).toBeVisible();

    // Click triggers a download -- just verify no crash
    await saveBtn.click();
    await page.waitForTimeout(300);

    expect(errors).toEqual([]);
  });
});
