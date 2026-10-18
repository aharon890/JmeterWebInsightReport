import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/* ============================================================
 *  User Flow: Customize Report View for Presentation
 *
 *  Dark mode + palette + thickness → Timeline hide/show/fullscreen →
 *  Copy Summary → Save/Load/Delete preset
 * ============================================================ */

test.describe('Flow: Customize Report for Presentation', () => {
  test('dark mode, palette, thickness, chart visibility, fullscreen, copy summary, presets', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // --- Step 1: Open E02, toggle dark mode ON ---
    await loadClean(page, 'E02');
    await page.click('#darkModeToggle');
    await page.waitForTimeout(300);
    await expect(page.locator('body')).toHaveClass(/dark-mode/);

    // --- Step 2: Switch palette to Okabe-Ito ---
    const okabeBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    await okabeBtn.click();
    await page.waitForTimeout(400);
    await expect(okabeBtn).toHaveClass(/active/);

    // --- Step 3: Switch line to Thick ---
    const thickBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="3"]');
    await thickBtn.click();
    await page.waitForTimeout(300);
    await expect(thickBtn).toHaveClass(/active/);

    // --- Step 4: Timeline tab — hide Scatter chart, hide Heatmap chart ---
    await switchTab(page, 'timeline');

    const scatterSection = page.locator('.timeline-chart-section[data-chart-id="scatter-chart"]');
    const scatterHideBtn = scatterSection.locator('.chart-hide-btn');
    await scatterHideBtn.click();
    await page.waitForTimeout(200);
    await expect(scatterSection).toHaveClass(/chart-hidden/);

    const heatmapSection = page.locator('.timeline-chart-section[data-chart-id="heatmap-chart"]');
    const heatmapHideBtn = heatmapSection.locator('.chart-hide-btn');
    await heatmapHideBtn.click();
    await page.waitForTimeout(200);
    await expect(heatmapSection).toHaveClass(/chart-hidden/);

    // --- Step 5: Verify "Show 2 Hidden Charts" button ---
    const showHiddenBtn = page.locator('#showHiddenChartsBtn');
    await expect(showHiddenBtn).toBeVisible();
    const showHiddenText = await showHiddenBtn.textContent();
    expect(showHiddenText).toContain('2');
    expect(showHiddenText).toContain('Hidden');

    // --- Step 6: Click Fullscreen on Response Time chart, verify, exit ---
    const rtSection = page.locator('.timeline-chart-section[data-chart-id="response-time-chart"]');
    const fullscreenBtn = rtSection.locator('.chart-fullscreen-btn');
    await fullscreenBtn.click();
    await page.waitForTimeout(300);
    await expect(rtSection).toHaveClass(/chart-fullscreen/);
    const btnText = await fullscreenBtn.textContent();
    expect(btnText).toContain('Exit');

    // Exit fullscreen
    await fullscreenBtn.click();
    await page.waitForTimeout(300);
    await expect(rtSection).not.toHaveClass(/chart-fullscreen/);

    // --- Step 7: Click "Show Hidden Charts" → all buttons say "Hide" ---
    await showHiddenBtn.click();
    await page.waitForTimeout(300);

    // Both scatter and heatmap should no longer be hidden
    await expect(scatterSection).not.toHaveClass(/chart-hidden/);
    await expect(heatmapSection).not.toHaveClass(/chart-hidden/);

    // Their buttons should say "Hide" again
    await expect(scatterSection.locator('.chart-hide-btn')).toContainText('Hide');
    await expect(heatmapSection.locator('.chart-hide-btn')).toContainText('Hide');

    // --- Step 8: Summary tab — click Copy Summary → no error ---
    await switchTab(page, 'summary');
    const copyBtn = page.locator('button:has-text("Copy Summary")');
    await expect(copyBtn).toBeVisible();
    await copyBtn.click();
    await page.waitForTimeout(300);

    // --- Step 9: Save preset "Dark Presentation" ---
    // We need to handle the prompt() dialog
    page.once('dialog', async dialog => {
      await dialog.accept('Dark Presentation');
    });
    await page.click('.filter-link-btn:has-text("Save")');
    await page.waitForTimeout(400);

    // --- Step 10: Verify preset in dropdown ---
    const presetSelect = page.locator('#presetSelect');
    const presetOptions = await presetSelect.locator('option').allTextContents();
    expect(presetOptions).toContain('Dark Presentation');

    // --- Step 11: Switch palette back to Default ---
    const defaultBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="default"]');
    await defaultBtn.click();
    await page.waitForTimeout(300);
    await expect(defaultBtn).toHaveClass(/active/);
    await expect(okabeBtn).not.toHaveClass(/active/);

    // --- Step 12: Load "Dark Presentation" preset → Okabe-Ito active again ---
    await presetSelect.selectOption('Dark Presentation');
    await page.waitForTimeout(400);
    await expect(okabeBtn).toHaveClass(/active/);

    // --- Step 13: Delete "Dark Presentation" → verify removed from dropdown ---
    // First ensure "Dark Presentation" is still selected in the dropdown
    await expect(presetSelect).toHaveValue('Dark Presentation');

    await page.click('.filter-link-btn:has-text("Del")');
    await page.waitForTimeout(300);

    const optionsAfterDelete = await presetSelect.locator('option').allTextContents();
    expect(optionsAfterDelete).not.toContain('Dark Presentation');

    // No JS errors throughout the entire flow
    expect(errors).toEqual([]);
  });
});

/* ============================================================
 *  Second Flow: Sampler Filtering with E05 (20 samplers)
 * ============================================================ */

test.describe('Flow: Sampler Filtering (E05)', () => {
  test('uncheck all, check 2, verify filtered view, then restore', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // --- Step 1: Open E05 ---
    await loadClean(page, 'E05');

    // --- Step 2: Click "None" → all unchecked ---
    // Use generic selector — E05 has no filterRow3 id
    const noneBtn = page.locator('.filter-link-btn:has-text("None")');
    await noneBtn.click();
    await page.waitForTimeout(400);

    // Checkboxes are display:none — verify via label class
    const allLabels = page.locator('#samplerFilterList label');
    const totalLabelCount = await allLabels.count();
    for (let i = 0; i < totalLabelCount; i++) {
      await expect(allLabels.nth(i)).not.toHaveClass(/checked/);
    }

    // --- Step 3: Check 2 specific samplers by clicking their labels ---
    const samplerLabels = page.locator('#samplerFilterList label:not([data-sampler-name="\u03c3 total"])');
    const samplerCount = await samplerLabels.count();
    expect(samplerCount).toBeGreaterThanOrEqual(2);

    const sampler1Label = samplerLabels.nth(0);
    const sampler2Label = samplerLabels.nth(1);

    // Get sampler names from the label's sampler-name span
    const sampler1Name = await sampler1Label.locator('.sampler-name').textContent();
    const sampler2Name = await sampler2Label.locator('.sampler-name').textContent();

    // Click labels to check them (checkbox is display:none)
    await sampler1Label.click();
    await page.waitForTimeout(200);
    await sampler2Label.click();
    await page.waitForTimeout(400);

    await expect(sampler1Label).toHaveClass(/checked/);
    await expect(sampler2Label).toHaveClass(/checked/);

    // --- Step 4: Timeline tab — chart renders with only those 2 samplers ---
    await switchTab(page, 'timeline');
    const timelineCanvas = page.locator('#response-time-chart canvas').first();
    await expect(timelineCanvas).toBeVisible();

    // Verify ECharts series: 2 sampler series + possibly "Active Threads" overlay
    const visibleSeriesCount = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      if (!el) return 0;
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return 0;
      const option = chart.getOption();
      if (!option.series) return 0;
      // Count only sampler series (exclude "Active Threads" overlay)
      return option.series.filter(
        (s: { name: string }) => s.name !== 'Active Threads'
      ).length;
    });
    expect(visibleSeriesCount).toBe(2);

    // --- Step 5: Samplers tab — use search to verify the 2 specific samplers ---
    await switchTab(page, 'samplers');

    // Search for the first sampler name
    if (sampler1Name) {
      await page.fill('#samplerSearch', sampler1Name.trim());
      await page.waitForTimeout(300);
      const visibleRows = page.locator('#samplerTable tbody tr:not(.sampler-detail-row):visible');
      expect(await visibleRows.count()).toBeGreaterThanOrEqual(1);
      await page.fill('#samplerSearch', '');
      await page.waitForTimeout(200);
    }

    // --- Step 6: Errors tab — verify errors visible (E05 may have errors) ---
    await switchTab(page, 'errors');
    // The error table may or may not have rows depending on E05 data
    // Just verify no JS errors
    await page.waitForTimeout(300);

    // --- Step 7: Click "All" → everything restored ---
    const allBtn = page.locator('.filter-link-btn:has-text("All")');
    await allBtn.click();
    await page.waitForTimeout(400);

    // Verify all labels now have 'checked' class
    for (let i = 0; i < totalLabelCount; i++) {
      await expect(allLabels.nth(i)).toHaveClass(/checked/);
    }

    // Timeline should now have more than 2 sampler series
    await switchTab(page, 'timeline');
    await page.waitForTimeout(400);
    const restoredSeriesCount = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      if (!el) return 0;
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return 0;
      const option = chart.getOption();
      if (!option.series) return 0;
      // Count only sampler series (exclude "Active Threads" overlay)
      return option.series.filter(
        (s: { name: string }) => s.name !== 'Active Threads'
      ).length;
    });
    expect(restoredSeriesCount).toBeGreaterThan(2);

    // No JS errors throughout the entire flow
    expect(errors).toEqual([]);
  });
});
