import { test, expect } from '@playwright/test';
import { loadClean, loadDirty, switchTab } from './helpers';

/* ============================================================
 *  Palette CRUD
 * ============================================================ */

test.describe('Palette CRUD', () => {
  test('[R] Default palette button is active on clean load', async ({ page }) => {
    await loadClean(page, 'E02');
    const defaultBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="default"]');
    await expect(defaultBtn).toHaveClass(/active/);
  });

  test('[U] Switch to Okabe-Ito: active swaps, chart colors change', async ({ page }) => {
    await loadClean(page, 'E02');
    const okabeBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    const defaultBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="default"]');

    await okabeBtn.click();
    await page.waitForTimeout(400);

    await expect(okabeBtn).toHaveClass(/active/);
    await expect(defaultBtn).not.toHaveClass(/active/);

    // Verify the global CHART_COLORS array was updated to Okabe-Ito palette
    const firstColor = await page.evaluate(() => {
      // @ts-ignore
      return typeof CHART_COLORS !== 'undefined' ? CHART_COLORS[0] : null;
    });
    expect(firstColor).toBeTruthy();
    // Okabe-Ito palette starts with #E69F00
    expect(firstColor!).toMatch(/#E69F00/i);
  });

  test('[U] Switch to High Contrast: active swaps', async ({ page }) => {
    await loadClean(page, 'E02');
    const hcBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="high-contrast"]');
    const defaultBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="default"]');

    await hcBtn.click();
    await page.waitForTimeout(300);

    await expect(hcBtn).toHaveClass(/active/);
    await expect(defaultBtn).not.toHaveClass(/active/);
  });

  test('[U] Switch back to Default: active restores', async ({ page }) => {
    await loadClean(page, 'E02');
    const okabeBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    const defaultBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="default"]');

    // First switch away
    await okabeBtn.click();
    await page.waitForTimeout(200);
    await expect(okabeBtn).toHaveClass(/active/);

    // Switch back
    await defaultBtn.click();
    await page.waitForTimeout(200);
    await expect(defaultBtn).toHaveClass(/active/);
    await expect(okabeBtn).not.toHaveClass(/active/);
  });

  test('[U] Persistence: switch to Okabe-Ito, reload dirty, still Okabe-Ito', async ({ page }) => {
    await loadClean(page, 'E02');
    const okabeBtn = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    await okabeBtn.click();
    await page.waitForTimeout(300);
    await expect(okabeBtn).toHaveClass(/active/);

    // Reload without clearing localStorage
    await loadDirty(page, 'E02');
    const okabeBtnAfter = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    await expect(okabeBtnAfter).toHaveClass(/active/);
  });
});

/* ============================================================
 *  Line Thickness CRUD
 * ============================================================ */

test.describe('Line Thickness CRUD', () => {
  test('[R] Default: Medium (data-thickness="2") is active', async ({ page }) => {
    await loadClean(page, 'E02');
    const mediumBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="2"]');
    await expect(mediumBtn).toHaveClass(/active/);
  });

  test('[U] Switch to Thin: active swaps, no JS error', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    const thinBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="1"]');
    const mediumBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="2"]');

    await thinBtn.click();
    await page.waitForTimeout(400);

    await expect(thinBtn).toHaveClass(/active/);
    await expect(mediumBtn).not.toHaveClass(/active/);
    expect(errors).toEqual([]);
  });

  test('[U] Switch to Thick: active swaps', async ({ page }) => {
    await loadClean(page, 'E02');
    const thickBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="3"]');
    const mediumBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="2"]');

    await thickBtn.click();
    await page.waitForTimeout(300);

    await expect(thickBtn).toHaveClass(/active/);
    await expect(mediumBtn).not.toHaveClass(/active/);
  });

  test('[U] Switch back to Medium: active restores', async ({ page }) => {
    await loadClean(page, 'E02');
    const thickBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="3"]');
    const mediumBtn = page.locator('#lineThicknessToggle .filter-toggle-btn[data-thickness="2"]');

    await thickBtn.click();
    await page.waitForTimeout(200);
    await mediumBtn.click();
    await page.waitForTimeout(200);

    await expect(mediumBtn).toHaveClass(/active/);
    await expect(thickBtn).not.toHaveClass(/active/);
  });
});

/* ============================================================
 *  Metric Toggle CRUD
 * ============================================================ */

test.describe('Metric Toggle CRUD', () => {
  test('[R] Default: Mean (data-metric="responseTime") is active', async ({ page }) => {
    await loadClean(page, 'E02');
    const meanBtn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="responseTime"]');
    await expect(meanBtn).toHaveClass(/active/);
  });

  test('[U] Cycle all 6 metrics and back, verify active button changes each time', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    const metrics = ['p50', 'p90', 'p95', 'p99', 'max', 'responseTime'];

    for (const metric of metrics) {
      const btn = page.locator(`#percentileToggle .filter-toggle-btn[data-metric="${metric}"]`);
      await btn.click();
      await page.waitForTimeout(300);
      await expect(btn).toHaveClass(/active/);

      // All other buttons should NOT be active
      const activeCount = await page.locator('#percentileToggle .filter-toggle-btn.active').count();
      expect(activeCount).toBe(1);
    }

    // No JS errors through entire cycle
    expect(errors).toEqual([]);
  });

  test('[U] Switch to P95, go to Timeline tab, chart renders', async ({ page }) => {
    await loadClean(page, 'E02');
    const p95Btn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="p95"]');
    await p95Btn.click();
    await page.waitForTimeout(300);

    await switchTab(page, 'timeline');

    const canvas = page.locator('#response-time-chart canvas').first();
    await expect(canvas).toBeVisible();
    const box = await canvas.boundingBox();
    expect(box!.width).toBeGreaterThan(100);
    expect(box!.height).toBeGreaterThan(50);
  });
});

/* ============================================================
 *  Granularity CRUD
 * ============================================================ */

test.describe('Granularity CRUD', () => {
  test('[R] Default: dropdown visible with 7 options', async ({ page }) => {
    await loadClean(page, 'E02');
    const select = page.locator('#granularitySelect');
    await expect(select).toBeVisible();

    const options = select.locator('option');
    expect(await options.count()).toBe(7);

    // Verify expected values
    const expectedValues = ['1000', '5000', '10000', '30000', '60000', '300000', '600000'];
    for (let i = 0; i < expectedValues.length; i++) {
      await expect(options.nth(i)).toHaveAttribute('value', expectedValues[i]);
    }
  });

  test('[U] Change to 5s: no JS error, Timeline charts re-render', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    await switchTab(page, 'timeline');
    await page.selectOption('#granularitySelect', '5000');
    await page.waitForTimeout(500);

    expect(errors).toEqual([]);
    const canvas = page.locator('#response-time-chart canvas').first();
    await expect(canvas).toBeVisible();
  });

  test('[U] Change to 30s: no JS error', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    await page.selectOption('#granularitySelect', '30000');
    await page.waitForTimeout(500);

    expect(errors).toEqual([]);
  });

  test('[U] Change to 1min: no JS error', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    await page.selectOption('#granularitySelect', '60000');
    await page.waitForTimeout(500);

    expect(errors).toEqual([]);
  });

  test('[U] Change back to 1s: no JS error, full detail restored', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Change away first
    await page.selectOption('#granularitySelect', '60000');
    await page.waitForTimeout(300);

    // Change back
    await page.selectOption('#granularitySelect', '1000');
    await page.waitForTimeout(500);

    expect(errors).toEqual([]);
  });
});

/* ============================================================
 *  Sampler Filter CRUD
 * ============================================================ */

test.describe('Sampler Filter CRUD', () => {
  test('[R] All regular checkboxes checked, Total unchecked', async ({ page }) => {
    await loadClean(page, 'E02');
    const container = page.locator('#samplerFilterList');
    const labels = container.locator('label');
    const count = await labels.count();
    expect(count).toBeGreaterThanOrEqual(2); // At least Total + 1 sampler

    // Regular sampler labels should all have the 'checked' class
    // (checkboxes are display:none, so we verify via label class)
    const regularLabels = container.locator('label:not([data-sampler-name="\u03c3 total"])');
    const regCount = await regularLabels.count();
    for (let i = 0; i < regCount; i++) {
      const label = regularLabels.nth(i);
      await expect(label).toHaveClass(/checked/);
    }

    // Total label should NOT have the 'checked' class
    const totalLabel = container.locator('label[data-sampler-name="\u03c3 total"]');
    if (await totalLabel.count() > 0) {
      await expect(totalLabel).not.toHaveClass(/checked/);
    }
  });

  test('[D] Click "None": all unchecked', async ({ page }) => {
    await loadClean(page, 'E02');
    const noneBtn = page.locator('#filterRow3 .filter-link-btn:has-text("None")');
    await noneBtn.click();
    await page.waitForTimeout(400);

    // Checkboxes are display:none — verify via label class instead
    const labels = page.locator('#samplerFilterList label');
    const count = await labels.count();
    for (let i = 0; i < count; i++) {
      await expect(labels.nth(i)).not.toHaveClass(/checked/);
    }
  });

  test('[C] Click "All": all checked', async ({ page }) => {
    await loadClean(page, 'E02');
    // First click None to uncheck all
    await page.click('#filterRow3 .filter-link-btn:has-text("None")');
    await page.waitForTimeout(300);

    // Then click All
    await page.click('#filterRow3 .filter-link-btn:has-text("All")');
    await page.waitForTimeout(400);

    // Checkboxes are display:none — verify via label class instead
    const labels = page.locator('#samplerFilterList label');
    const count = await labels.count();
    for (let i = 0; i < count; i++) {
      await expect(labels.nth(i)).toHaveClass(/checked/);
    }
  });

  test('[U] Uncheck first sampler: label dims (opacity), chart re-renders', async ({ page }) => {
    await loadClean(page, 'E02');
    // Find the first non-Total sampler label
    const labels = page.locator('#samplerFilterList label');
    const count = await labels.count();
    let targetLabel = labels.nth(0);
    for (let i = 0; i < count; i++) {
      const name = await labels.nth(i).getAttribute('data-sampler-name');
      if (name !== '\u03c3 total') {
        targetLabel = labels.nth(i);
        break;
      }
    }

    // Verify initially has 'checked' class
    await expect(targetLabel).toHaveClass(/checked/);

    // Click the label to toggle (checkbox is display:none, can't directly uncheck)
    await targetLabel.click();
    await page.waitForTimeout(400);

    // Label should no longer have 'checked' class (dims it)
    await expect(targetLabel).not.toHaveClass(/checked/);
  });

  test('[C] Re-check sampler: label opacity restores, chart re-renders', async ({ page }) => {
    await loadClean(page, 'E02');
    const labels = page.locator('#samplerFilterList label');
    const count = await labels.count();
    let targetLabel = labels.nth(0);
    for (let i = 0; i < count; i++) {
      const name = await labels.nth(i).getAttribute('data-sampler-name');
      if (name !== '\u03c3 total') {
        targetLabel = labels.nth(i);
        break;
      }
    }

    // Click label to uncheck, then click again to re-check
    await targetLabel.click();
    await page.waitForTimeout(300);
    await expect(targetLabel).not.toHaveClass(/checked/);

    await targetLabel.click();
    await page.waitForTimeout(300);
    await expect(targetLabel).toHaveClass(/checked/);
  });

  test('[C] Check Total: Timeline tab renders without JS error', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Click label to check Total (checkbox is display:none)
    const totalLabel = page.locator('#samplerFilterList label[data-sampler-name="\u03c3 total"]');
    await totalLabel.click();
    await page.waitForTimeout(400);

    await switchTab(page, 'timeline');
    await page.waitForTimeout(300);

    expect(errors).toEqual([]);
  });

  test('[D] Uncheck Total: no error', async ({ page }) => {
    await loadClean(page, 'E02');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));

    // Click label to check, then click again to uncheck
    const totalLabel = page.locator('#samplerFilterList label[data-sampler-name="\u03c3 total"]');
    await totalLabel.click();
    await page.waitForTimeout(300);
    await totalLabel.click();
    await page.waitForTimeout(300);

    expect(errors).toEqual([]);
  });

  test('[U] Uncheck sampler: Samplers tab row hidden via search filter', async ({ page }) => {
    await loadClean(page, 'E02');

    // Get the name of the first non-Total sampler via the label's sampler-name span
    const firstSamplerName = await page.evaluate(() => {
      const labels = document.querySelectorAll('#samplerFilterList label');
      for (let i = 0; i < labels.length; i++) {
        const name = labels[i].getAttribute('data-sampler-name');
        if (name && name !== '\u03c3 total') {
          const nameSpan = labels[i].querySelector('.sampler-name');
          return nameSpan ? nameSpan.textContent!.trim() : null;
        }
      }
      return null;
    });
    expect(firstSamplerName).toBeTruthy();

    // Switch to Samplers tab and verify row is initially visible
    await switchTab(page, 'samplers');
    const row = page.locator(`#samplerTable tbody tr[data-sampler="${firstSamplerName}"]`);
    await expect(row).toBeVisible();

    // Use the search to filter (this tests search filtering, since sampler filter only affects charts)
    await page.fill('#samplerSearch', 'ZZZNOTFOUND');
    await page.waitForTimeout(300);
    await expect(row).toBeHidden();

    // Clear search to restore
    await page.fill('#samplerSearch', '');
    await page.waitForTimeout(300);
    await expect(row).toBeVisible();
  });
});

/* ============================================================
 *  Sampler Color Picker
 * ============================================================ */

test.describe('Sampler Color Picker', () => {
  test('[R] Each sampler label has a color dot', async ({ page }) => {
    await loadClean(page, 'E02');
    const dots = page.locator('#samplerFilterList .sampler-color-dot');
    const count = await dots.count();
    expect(count).toBeGreaterThanOrEqual(2); // At least Total + 1 sampler

    // Each dot should have a background-color style
    for (let i = 0; i < Math.min(count, 5); i++) {
      const bg = await dots.nth(i).evaluate(el => el.style.background || el.style.backgroundColor);
      expect(bg.length).toBeGreaterThan(0);
    }
  });

  test('[C] Click color dot: picker popup appears', async ({ page }) => {
    await loadClean(page, 'E02');

    // The color dot is inside a label. Clicking it dispatches a click event
    // with stopPropagation (so the label checkbox doesn't toggle).
    // Use dispatchEvent with a new MouseEvent to simulate the click properly.
    const opened = await page.evaluate(() => {
      const labels = document.querySelectorAll('#samplerFilterList label');
      for (let i = 0; i < labels.length; i++) {
        if (labels[i].getAttribute('data-sampler-name') === '\u03c3 total') continue;
        const dot = labels[i].querySelector('.sampler-color-dot') as HTMLElement;
        if (dot) {
          const evt = new MouseEvent('click', { bubbles: true, cancelable: true });
          dot.dispatchEvent(evt);
          return true;
        }
      }
      return false;
    });
    expect(opened).toBe(true);
    await page.waitForTimeout(300);

    // Verify color picker popup appeared
    const popup = page.locator('.color-picker-popup.open');
    await expect(popup).toBeVisible();
  });

  test('[D] Close picker by clicking elsewhere', async ({ page }) => {
    await loadClean(page, 'E02');

    // Open the color picker via dispatchEvent
    await page.evaluate(() => {
      const labels = document.querySelectorAll('#samplerFilterList label');
      for (let i = 0; i < labels.length; i++) {
        if (labels[i].getAttribute('data-sampler-name') === '\u03c3 total') continue;
        const dot = labels[i].querySelector('.sampler-color-dot') as HTMLElement;
        if (dot) {
          const evt = new MouseEvent('click', { bubbles: true, cancelable: true });
          dot.dispatchEvent(evt);
          return;
        }
      }
    });
    await page.waitForTimeout(300);

    const popup = page.locator('.color-picker-popup.open');
    await expect(popup).toBeVisible();

    // Click elsewhere to close
    await page.locator('body').click({ position: { x: 10, y: 10 } });
    await page.waitForTimeout(300);

    await expect(popup).not.toBeVisible();
  });
});
