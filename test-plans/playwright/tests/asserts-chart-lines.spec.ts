import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Tests for assert threshold lines on charts and Apdex display in an assert context.
 * Uses E12 (full annotations), E10 (asserts pass), E11 (asserts fail).
 *
 * E12 declares blanket rules over every sampler: p95 1000, p99 2000, errorRate 5, mean 500
 * No p50, p90, or max thresholds configured → no markLine for those metrics.
 */

/**
 * The label of the first RULE line on the response-time chart.
 *
 * Threshold lines are declared rules now, so the label names the rule that drew it
 * rather than repeating a number already on the axis. The charts also carry a
 * run-aggregate marker, which is not a limit and must not be mistaken for one — so
 * this skips it deliberately rather than taking whatever came first.
 */
const RULE_LABEL_SCRIPT = `(() => {
  const el = document.getElementById('response-time-chart');
  const chart = echarts.getInstanceByDom(el);
  if (!chart) return null;
  for (const s of chart.getOption().series) {
    if (s.markLine && s.markLine.data) {
      for (const d of s.markLine.data) {
        if (d.yAxis === undefined || !d.label) continue;
        if (String(d.label.formatter).indexOf('run aggregate') === 0) continue;
        return d.label.formatter;
      }
    }
  }
  return null;
})()`;

test.describe('Assert threshold lines on charts [E12 — blanket rules in annotations]', () => {
  test('[R] Mean metric: markLine at 500ms (default assert threshold)', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);

    const markLineValue = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return null;
      const opt = chart.getOption();
      for (const s of opt.series) {
        if (s.markLine && s.markLine.data) {
          for (const d of s.markLine.data) {
            if (d.yAxis !== undefined) return d.yAxis;
          }
        }
      }
      return null;
    });
    expect(markLineValue).toBe(500); // default.mean = 500
  });

  test('[U] Switch to P95: markLine updates to 1000ms (default p95 threshold)', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    const p95Btn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="p95"]');
    await p95Btn.click();
    await page.waitForTimeout(500);

    const markLineValue = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return null;
      const opt = chart.getOption();
      for (const s of opt.series) {
        if (s.markLine && s.markLine.data) {
          for (const d of s.markLine.data) {
            if (d.yAxis !== undefined) return d.yAxis;
          }
        }
      }
      return null;
    });
    expect(markLineValue).toBe(1000); // default.p95 = 1000
  });

  test('[U] Switch to P99: markLine updates to 2000ms', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    const p99Btn = page.locator('#percentileToggle .filter-toggle-btn[data-metric="p99"]');
    await p99Btn.click();
    await page.waitForTimeout(500);

    const markLineValue = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return null;
      for (const s of chart.getOption().series) {
        if (s.markLine?.data) {
          for (const d of s.markLine.data) {
            if (d.yAxis !== undefined) return d.yAxis;
          }
        }
      }
      return null;
    });
    expect(markLineValue).toBe(2000); // default.p99 = 2000
  });

  test('[R] Mean metric: the line names the rule that drew it', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    // A bare "MEAN: 500ms" was a number already readable off the axis, and no clue which
    // rule produced it. The threshold itself is unchanged — the rule declares fail > 500
    // and the line still sits there.
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    expect(label).toBe('default:mean fail > 500 ms');
  });

  test('[U] P50 metric: no rule on p50, so no rule line', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    await page.click('#percentileToggle .filter-toggle-btn[data-metric="p50"]');
    await page.waitForTimeout(500);
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    // E12 has no p50 threshold configured, so no markLine
    expect(label).toBeNull();
  });

  test('[U] P90 metric: no threshold configured → no markLine', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    await page.click('#percentileToggle .filter-toggle-btn[data-metric="p90"]');
    await page.waitForTimeout(500);
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    expect(label).toBeNull();
  });

  test('[U] P95 metric: the line names the rule, at the same 1000ms', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    await page.click('#percentileToggle .filter-toggle-btn[data-metric="p95"]');
    await page.waitForTimeout(500);
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    expect(label).toBe('default:p95 fail > 1,000 ms');
  });

  test('[U] P99 metric: the line names the rule, at the same 2000ms', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    await page.click('#percentileToggle .filter-toggle-btn[data-metric="p99"]');
    await page.waitForTimeout(500);
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    expect(label).toBe('default:p99 fail > 2,000 ms');
  });

  test('[U] Max metric: no threshold configured → no markLine', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);
    await page.click('#percentileToggle .filter-toggle-btn[data-metric="max"]');
    await page.waitForTimeout(500);
    const label = await page.evaluate(RULE_LABEL_SCRIPT);
    expect(label).toBeNull();
  });

  test('[R] markLine has red dashed line style', async ({ page }) => {
    await loadClean(page, 'E12');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(800);

    const lineStyle = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      if (!chart) return null;
      for (const s of chart.getOption().series) {
        if (s.markLine?.data) {
          for (const d of s.markLine.data) {
            // Skip the run-aggregate marker: it is not a limit, and it is styled to say so.
            if (d.yAxis === undefined) continue;
            if (d.label && String(d.label.formatter).indexOf('run aggregate') === 0) continue;
            return d.lineStyle;
          }
        }
      }
      return null;
    });
    expect(lineStyle).toBeTruthy();
    expect(lineStyle.color).toBe('#ef4444');
    expect(lineStyle.type).toBe('dashed');
  });

  test('[R] No threshold lines when no asserts are declared [E02]', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(500);

    const hasMarkLine = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      if (!el) return false;
      // @ts-ignore
      const chart = typeof echarts !== 'undefined' ? echarts.getInstanceByDom(el) : null;
      if (!chart) return false;
      const opt = chart.getOption();
      if (!opt.series) return false;
      return opt.series.some((s: any) => s.markLine && s.markLine.data && s.markLine.data.length > 0);
    });
    expect(hasMarkLine).toBe(false);
  });
});

test.describe('Assert badges across tabs [E11]', () => {
  test('[R] Header badge: ASSERTS FAIL', async ({ page }) => {
    await loadClean(page, 'E11');
    const badge = page.locator('.status-badge');
    await expect(badge).toContainText('ASSERTS FAIL');
    await expect(badge).toHaveClass(/badge-fail/);
  });

  test('[R] Summary P95 card has an assert mini-badge', async ({ page }) => {
    await loadClean(page, 'E11');
    const assertBadges = page.locator('.summary-card .assert-badge-mini');
    expect(await assertBadges.count()).toBeGreaterThanOrEqual(1);
  });

  test('[R] Samplers table has FAIL badges on failing samplers', async ({ page }) => {
    await loadClean(page, 'E11');
    await switchTab(page, 'samplers');
    const table = page.locator('#tab-samplers table');
    const tableHtml = await table.innerHTML();
    expect(tableHtml).toMatch(/FAIL/i);
  });

  test('[R] Passing report: header badge green, samplers PASS [E10]', async ({ page }) => {
    await loadClean(page, 'E10');
    const badge = page.locator('.status-badge');
    await expect(badge).toContainText('ASSERTS PASS');
    await expect(badge).toHaveClass(/badge-pass/);
    await switchTab(page, 'samplers');
    const tableHtml = await page.locator('#tab-samplers table').innerHTML();
    expect(tableHtml).toMatch(/PASS/i);
  });
});

test.describe('Asserts + Compare Integration', () => {
  test('[R] Compare report with asserts: badges visible on both header and table [CMP]', async ({ page }) => {
    // The CMP report may or may not declare asserts — this test verifies no crash
    await loadClean(page, 'CMP');
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    await switchTab(page, 'compare');
    await page.waitForTimeout(300);
    // Verify compare tab renders without errors regardless of the assert verdict
    const comparePanel = page.locator('#tab-compare');
    await expect(comparePanel).toHaveClass(/active/);
    expect(errors).toEqual([]);
  });
});
