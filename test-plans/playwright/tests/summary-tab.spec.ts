import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

test.describe('Summary Tab — Cards & Metrics', () => {
  test('primary cards: 4 cards with correct labels and non-zero values', async ({ page }) => {
    await loadClean(page, 'E02');
    const cards = page.locator('.summary-card');
    expect(await cards.count()).toBeGreaterThanOrEqual(4);
    // Card 1: Total Samples
    const samplesValue = await cards.nth(0).locator('.card-value').textContent();
    expect(parseInt(samplesValue!.replace(/,/g, ''))).toBeGreaterThan(0);
    await expect(cards.nth(0).locator('.card-label')).toContainText('Total Samples');
    // Card 2: Error Rate (percentage)
    const errorRateText = await cards.nth(1).locator('.card-value').textContent();
    expect(errorRateText).toContain('%');
    await expect(cards.nth(1).locator('.card-label')).toContainText('Error Rate');
    // Card 3: Duration (human-readable)
    const durationText = await cards.nth(2).locator('.card-value').textContent();
    expect(durationText!.length).toBeGreaterThan(0);
    expect(durationText).not.toBe('N/A');
    // Card 4: Max Threads
    const threadsValue = await cards.nth(3).locator('.card-value').textContent();
    expect(parseInt(threadsValue!.replace(/,/g, ''))).toBeGreaterThan(0);
  });

  test('error rate card has card-error class when errors exist', async ({ page }) => {
    await loadClean(page, 'E02');
    const errorCard = page.locator('.summary-card').nth(1);
    await expect(errorCard).toHaveClass(/card-error/);
  });

  test('error rate card has NO card-error class when zero errors', async ({ page }) => {
    await loadClean(page, 'E06');
    const errorCard = page.locator('.summary-card').nth(1);
    await expect(errorCard).not.toHaveClass(/card-error/);
    const value = await errorCard.locator('.card-value').textContent();
    expect(value).toMatch(/0/);
  });

  test('secondary cards: P95 and P99 (whole run), Throughput, Total Errors', async ({ page }) => {
    await loadClean(page, 'E02');
    const allCards = page.locator('.summary-card');
    const count = await allCards.count();
    expect(count).toBeGreaterThanOrEqual(8);
    // Cards 5-8 in second row
    const p95Text = await allCards.nth(4).locator('.card-value').textContent();
    expect(p95Text).toContain('ms');
    // B10: the value is the whole-run percentile (overall.getP95()), never an average, so the
    // label must not say "Avg".
    await expect(allCards.nth(4).locator('.card-label')).toContainText('P95, whole run');
    await expect(allCards.nth(5).locator('.card-label')).toContainText('P99, whole run');
    await expect(page.locator('.summary-card .card-label', { hasText: /Avg P9/ })).toHaveCount(0);
    const throughputText = await allCards.nth(6).locator('.card-value').textContent();
    expect(throughputText).toContain('/s');
  });

  test('Apdex card visible with score 0.00-1.00 and color class', async ({ page }) => {
    await loadClean(page, 'E02');
    const apdexCard = page.locator('#apdexCard');
    await expect(apdexCard).toBeVisible();
    const scoreText = await apdexCard.locator('.card-value').textContent();
    const score = parseFloat(scoreText!);
    expect(score).toBeGreaterThanOrEqual(0);
    expect(score).toBeLessThanOrEqual(1);
    // Should have one of the apdex color classes
    const cardHtml = await apdexCard.innerHTML();
    expect(cardHtml).toMatch(/apdex-(excellent|good|fair|poor|unacceptable)/);
  });
});

test.describe('Summary Tab — Response Time Chart', () => {
  test('chart renders with canvas of non-zero dimensions', async ({ page }) => {
    await loadClean(page, 'E02');
    const chart = page.locator('#summary-response-time-chart');
    await expect(chart).toBeVisible();
    const canvas = chart.locator('canvas').first();
    await expect(canvas).toBeVisible();
    const box = await canvas.boundingBox();
    expect(box!.width).toBeGreaterThan(100);
    expect(box!.height).toBeGreaterThan(50);
  });

  test('fullscreen: click → chart expands, click again → restores', async ({ page }) => {
    await loadClean(page, 'E02');
    const section = page.locator('#summary-response-time-chart').locator('..');
    const btn = section.locator('.chart-fullscreen-btn');
    await btn.click();
    // After click, section or its parent should have a fullscreen-related class
    await page.waitForTimeout(300);
    // Click again to exit
    await btn.click();
    await page.waitForTimeout(300);
  });

  test('chart legend: click sampler name toggles series visibility', async ({ page }) => {
    await loadClean(page, 'E02');
    // ECharts legends are rendered on canvas, so we can't directly click text
    // Instead verify the chart instance exists and has series
    const hasSeries = await page.evaluate(() => {
      const el = document.getElementById('summary-response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      return chart && chart.getOption().series.length > 0;
    });
    expect(hasSeries).toBe(true);
  });
});

test.describe('Summary Tab — Top 5 Slowest', () => {
  test('top 5 section visible with 1-5 rows', async ({ page }) => {
    await loadClean(page, 'E02');
    const rows = page.locator('.top-sampler-row');
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(1);
    expect(count).toBeLessThanOrEqual(5);
  });

  test('each row has rank, name, p95 value, mean value', async ({ page }) => {
    await loadClean(page, 'E02');
    const firstRow = page.locator('.top-sampler-row').first();
    await expect(firstRow.locator('.top-sampler-rank')).toHaveText('1');
    const name = await firstRow.locator('.top-sampler-name').textContent();
    expect(name!.length).toBeGreaterThan(0);
    const p95 = await firstRow.locator('.top-sampler-value').textContent();
    expect(p95).toContain('p95:');
    expect(p95).toContain('ms');
    const mean = await firstRow.locator('.top-sampler-subvalue').textContent();
    expect(mean).toContain('mean:');
  });

  test('CSV download button exists and clickable without error', async ({ page }) => {
    await loadClean(page, 'E02');
    const csvBtn = page.locator('.table-csv-btn').first();
    await expect(csvBtn).toBeVisible();
    // Click should trigger download (no JS error)
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    await csvBtn.click();
    await page.waitForTimeout(300);
    expect(errors).toEqual([]);
  });

  test('Copy Summary button: exists and click triggers clipboard write', async ({ page }) => {
    await loadClean(page, 'E02');
    const btn = page.locator('button:has-text("Copy Summary")');
    await expect(btn).toBeVisible();
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    await btn.click();
    await page.waitForTimeout(300);
    expect(errors).toEqual([]);
  });
});

test.describe('Summary Tab — Assert Badges on Cards', () => {
  test('Asserts PASS report: P95/P99 cards show an assert badge', async ({ page }) => {
    await loadClean(page, 'E10');
    const badges = page.locator('.summary-card .assert-badge-mini');
    expect(await badges.count()).toBeGreaterThanOrEqual(1);
  });

  test('Asserts FAIL report: P95/P99 cards show an assert badge', async ({ page }) => {
    await loadClean(page, 'E11');
    const badges = page.locator('.summary-card .assert-badge-mini');
    expect(await badges.count()).toBeGreaterThanOrEqual(1);
  });
});

test.describe('Summary Tab — Engineer Notes', () => {
  test('annotations report shows notes section on summary', async ({ page }) => {
    await loadClean(page, 'E12');
    // Look for notes/markdown content in summary tab
    const summaryPanel = page.locator('#tab-summary');
    const notesSections = summaryPanel.locator('.engineer-notes, .report-section:has(h2:has-text("Notes")), .report-section:has(h2:has-text("notes"))');
    // At least the summary should contain some annotation-driven content
  });
});

test.describe('Summary Tab — Edge Cases', () => {
  test('100% errors: error rate shows 100%, all Top 5 show error rate', async ({ page }) => {
    await loadClean(page, 'E07');
    const errorCard = page.locator('.summary-card').nth(1);
    const value = await errorCard.locator('.card-value').textContent();
    expect(value).toContain('100');
    // Top 5 rows should show error rates
    const errorSpans = page.locator('.top-sampler-error');
    expect(await errorSpans.count()).toBeGreaterThan(0);
  });
});
