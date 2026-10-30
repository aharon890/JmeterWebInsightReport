import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Error-detail truncation captions (CORE-3 / FE-5).
 *
 * The engine keeps only the first 500 detailed error records. The by-code and
 * by-sampler charts fall back to the unbounded server aggregate, so their counts
 * stay correct; the error *timeline* plots the individual records and therefore
 * simply stops partway through a run whose errors ramp up late. Both cases need a
 * caption, and the caption must not accumulate — initErrorBreakdownCharts re-runs
 * on every theme, palette, granularity and sampler-filter change.
 */

/** Force the truncated code path and re-render the error charts. */
async function renderTruncated(page: any) {
  await page.evaluate(() => {
    const w = window as any;
    w.globalChartData.errorRecordsTruncated = true;
    w.initErrorBreakdownCharts(w.globalChartData);
  });
  await page.waitForTimeout(200);
}

async function noteCount(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    if (!el || !el.parentNode) return -1;
    return (el.parentNode as Element).querySelectorAll('.truncation-note').length;
  }, chartId);
}

async function noteText(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    const note = el && el.parentNode && (el.parentNode as Element).querySelector('.truncation-note');
    return note ? note.textContent : null;
  }, chartId);
}

test.describe('Error truncation captions', () => {
  test('[C/R] the error timeline warns that only the first 500 errors are plotted', async ({ page }) => {
    await loadClean(page, 'E07'); // 100% error rate
    await switchTab(page, 'errors');
    await renderTruncated(page);

    const text = await noteText(page, 'error-timeline-chart');
    expect(text, 'timeline must carry a truncation caption').toBeTruthy();
    expect(text).toContain('first 500');
    // The reader needs to know where the plotted data stops, otherwise the empty
    // tail reads as "the errors stopped".
    expect(text).toMatch(/last one at \d{2}:\d{2}:\d{2}/);
  });

  test('[C/R] breakdown charts keep their own caption wording', async ({ page }) => {
    await loadClean(page, 'E07');
    await switchTab(page, 'errors');
    await renderTruncated(page);

    for (const id of ['error-by-type-chart', 'error-by-sampler-chart']) {
      const text = await noteText(page, id);
      expect(text, `${id} must carry a truncation caption`).toBeTruthy();
      expect(text).toContain('Showing all errors');
    }
  });

  test('[C/R] captions do not accumulate across re-renders', async ({ page }) => {
    await loadClean(page, 'E07');
    await switchTab(page, 'errors');

    for (let i = 0; i < 4; i++) await renderTruncated(page);

    for (const id of ['error-timeline-chart', 'error-by-type-chart', 'error-by-sampler-chart']) {
      expect(await noteCount(page, id), `${id} should have exactly one caption`).toBe(1);
    }
  });

  test('[C/R] a caption is removed once the data is no longer truncated', async ({ page }) => {
    await loadClean(page, 'E07');
    await switchTab(page, 'errors');
    await renderTruncated(page);
    expect(await noteCount(page, 'error-timeline-chart')).toBe(1);

    await page.evaluate(() => {
      const w = window as any;
      w.globalChartData.errorRecordsTruncated = false;
      w.initErrorBreakdownCharts(w.globalChartData);
    });
    await page.waitForTimeout(200);

    for (const id of ['error-timeline-chart', 'error-by-type-chart', 'error-by-sampler-chart']) {
      expect(await noteCount(page, id), `${id} caption should be gone`).toBe(0);
    }
  });

  test('[R] an untruncated report shows no captions at all', async ({ page }) => {
    await loadClean(page, 'E07');
    await switchTab(page, 'errors');
    await page.waitForTimeout(300);

    const truncated = await page.evaluate(() => !!(window as any).globalChartData.errorRecordsTruncated);
    test.skip(truncated, 'fixture exceeded the 500-error cap');

    for (const id of ['error-timeline-chart', 'error-by-type-chart', 'error-by-sampler-chart']) {
      expect(await noteCount(page, id)).toBe(0);
    }
  });
});

test.describe('Response-codes chart', () => {
  test('[C/R] the response-codes chart counts every response, not the first 500 errors (T5)',
      async ({ page }) => {
    // It was built from the 500-capped error records and said so; it is JMeter's Codes per Second
    // now, every response by code, so whether the records were truncated no longer matters to it.
    await loadClean(page, 'E07');
    await switchTab(page, 'timeline');
    const sums = await page.evaluate(() => {
      const w = window as any;
      w.globalChartData.errorRecordsTruncated = true;
      w.initResponseCodesChart(w.globalChartData);
      const total = Object.values(w.globalChartData.responseCodes as Record<string, (number | null)[]>)
        .reduce((a, counts) => a + counts.reduce((b: number, c) => b + (c || 0), 0), 0);
      return { total, samples: w.globalChartData.requestSampleCount as number };
    });
    await page.waitForTimeout(200);

    expect(sums.total, 'one count per response').toBe(sums.samples);
    const text = await noteText(page, 'response-codes-chart');
    expect(text).toContain('Every response');
    expect(text).not.toContain('first 500');
  });

  test('[C/R] the timeline keeps its caption when the filter hides every kept error',
      async ({ page }) => {
    await loadClean(page, 'E07');
    await switchTab(page, 'errors');

    await page.evaluate(() => {
      const w = window as any;
      w.globalChartData.errorRecordsTruncated = true;
      // Hide every sampler so getFilteredErrorRecords returns nothing: "no errors for the
      // selected samplers" is only true of the 500 kept records, so the caption must stay.
      Object.keys(w.samplerVisibility).forEach((k) => { w.samplerVisibility[k] = false; });
      w.initErrorTimelineChart(w.globalChartData);
    });
    await page.waitForTimeout(200);

    const text = await noteText(page, 'error-timeline-chart');
    expect(text, 'clearing the note here reads as "there were no errors"').toBeTruthy();
    expect(text).toContain('500');
  });
});
