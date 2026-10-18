import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

const CHART_IDS = [
  'response-time-chart',
  'throughput-chart',
  'error-rate-chart',
  'bytes-throughput-chart',
  'connect-latency-chart',
  'response-codes-chart',
  'percentile-bar-chart',
  'scatter-chart',
  'heatmap-chart',
] as const;

async function goTimeline(page: Page, testId = 'E02') {
  await loadClean(page, testId);
  await switchTab(page, 'timeline');
}

test.describe('Timeline Tab — Charts exist and render', () => {
  test('all 9 chart sections are present with correct data-chart-id', async ({ page }) => {
    await goTimeline(page);
    for (const id of CHART_IDS) {
      const section = page.locator(`.timeline-chart-section[data-chart-id="${id}"]`);
      await expect(section).toBeVisible();
    }
  });

  test('each chart container has a canvas with non-zero dimensions', async ({ page }) => {
    await goTimeline(page);
    for (const id of CHART_IDS) {
      const canvas = page.locator(`#${id} canvas`).first();
      await expect(canvas).toBeVisible();
      const box = await canvas.boundingBox();
      expect(box).toBeTruthy();
      expect(box!.width).toBeGreaterThan(0);
      expect(box!.height).toBeGreaterThan(0);
    }
  });

  test('each chart section has drag handle, Hide button, and Fullscreen button', async ({ page }) => {
    await goTimeline(page);
    for (const id of CHART_IDS) {
      const section = page.locator(`.timeline-chart-section[data-chart-id="${id}"]`);
      await expect(section.locator('.chart-drag-handle')).toBeVisible();
      const hideBtn = section.locator('.chart-hide-btn');
      await expect(hideBtn).toBeVisible();
      await expect(hideBtn).toHaveText('Hide');
      await expect(section.locator('.chart-fullscreen-btn')).toBeVisible();
    }
  });
});

test.describe('Timeline Tab — Hide/Show CRUD', () => {
  test('hide one chart, then a second, then show all restores both', async ({ page }) => {
    await goTimeline(page);

    const section1 = page.locator(`.timeline-chart-section[data-chart-id="${CHART_IDS[0]}"]`);
    const section2 = page.locator(`.timeline-chart-section[data-chart-id="${CHART_IDS[1]}"]`);
    const showHiddenBtn = page.locator('#showHiddenChartsBtn');

    // Initially no "Show Hidden Charts" button
    await expect(showHiddenBtn).toBeHidden();

    // Hide chart 1
    await section1.locator('.chart-hide-btn').click();
    await expect(section1).toHaveClass(/chart-hidden/);
    await expect(section1.locator('.chart-hide-btn')).toHaveText('Show');
    await expect(showHiddenBtn).toBeVisible();
    await expect(showHiddenBtn).toHaveText(/Show 1 Hidden Chart/);

    // Hide chart 2
    await section2.locator('.chart-hide-btn').click();
    await expect(section2).toHaveClass(/chart-hidden/);
    await expect(showHiddenBtn).toHaveText(/Show 2 Hidden Charts/);

    // Click "Show Hidden Charts" to restore all
    await showHiddenBtn.click();
    await expect(section1).not.toHaveClass(/chart-hidden/);
    await expect(section2).not.toHaveClass(/chart-hidden/);

    // All hide buttons should say "Hide"
    for (const id of CHART_IDS) {
      const btn = page.locator(`.timeline-chart-section[data-chart-id="${id}"] .chart-hide-btn`);
      await expect(btn).toHaveText('Hide');
    }

    // "Show Hidden Charts" button should be gone
    await expect(showHiddenBtn).toBeHidden();
  });

  test('restored charts still render with visible canvases', async ({ page }) => {
    await goTimeline(page);
    const section = page.locator(`.timeline-chart-section[data-chart-id="${CHART_IDS[0]}"]`);

    // Hide then restore
    await section.locator('.chart-hide-btn').click();
    await page.locator('#showHiddenChartsBtn').click();
    await page.waitForTimeout(300);

    // Canvas should still be visible and rendered
    const canvas = page.locator(`#${CHART_IDS[0]} canvas`).first();
    await expect(canvas).toBeVisible();
  });
});

test.describe('Timeline Tab — Fullscreen', () => {
  test('toggle fullscreen adds and removes class', async ({ page }) => {
    await goTimeline(page);
    const section = page.locator(`.timeline-chart-section[data-chart-id="${CHART_IDS[0]}"]`);
    const fsBtn = section.locator('.chart-fullscreen-btn');

    // Enter fullscreen
    await fsBtn.click();
    await expect(section).toHaveClass(/chart-fullscreen/);

    // Exit fullscreen
    await fsBtn.click();
    await expect(section).not.toHaveClass(/chart-fullscreen/);
  });
});

test.describe('Timeline Tab — Chart interaction with sampler filter', () => {
  test('uncheck a sampler then re-check without JS errors', async ({ page }) => {
    await goTimeline(page);

    // Wait for sampler filter to populate — use first non-Total label
    // (Total is unchecked by default; clicking it would check it, not uncheck)
    const filterList = page.locator('#samplerFilterList');
    const regularLabel = filterList.locator('label:not([data-sampler-name="\u03c3 total"])').first();
    await expect(regularLabel).toBeVisible();
    await expect(regularLabel).toHaveClass(/\bchecked\b/);

    // Uncheck the sampler — should not throw
    await expectNoJsErrors(page, async () => {
      await regularLabel.click();
      await page.waitForTimeout(500);
    });

    // The label should lose the "checked" class
    await expect(regularLabel).not.toHaveClass(/\bchecked\b/);

    // Re-check — should not throw
    await expectNoJsErrors(page, async () => {
      await regularLabel.click();
      await page.waitForTimeout(500);
    });

    await expect(regularLabel).toHaveClass(/\bchecked\b/);
  });
});

test.describe('Timeline Tab — Zero errors edge case [E06]', () => {
  test('error rate chart shows "no data" placeholder when zero errors', async ({ page }) => {
    await goTimeline(page, 'E06');

    // When zero errors, showNoData replaces chart canvas with a placeholder div
    const chartContainer = page.locator('#error-rate-chart');
    await expect(chartContainer).toBeVisible();

    // The container should show a "No errors" message instead of a canvas
    const text = await chartContainer.textContent();
    expect(text).toContain('No errors');

    // There should be no canvas element
    const canvas = page.locator('#error-rate-chart canvas');
    expect(await canvas.count()).toBe(0);
  });
});
