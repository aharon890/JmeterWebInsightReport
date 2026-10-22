import { test, expect, type Page } from '@playwright/test';
import * as path from 'path';
import { loadClean, switchTab } from './helpers';

/**
 * E28 and E29: real reports in the shapes the hand-built in-page states only imitated (research
 * §4). E28 is `jmeter -g` reading a committed CSV that does not line up with the run, one assert per
 * way (gen-e28.js), plus the interim and refused pages of the same file; E29 a generated four-hour
 * soak at the page's caps. Every number is a constant: E28 is over plain.jtl, E29 from a fixed seed.
 */

const RESULTS = path.resolve(__dirname, '..', '..', '..', 'test-results');

async function openPage(page: Page, rel: string) {
  const url = 'file://' + path.join(RESULTS, rel);
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('wir_'))
    .forEach(k => localStorage.removeItem(k)));
  await page.goto(url, { waitUntil: 'networkidle' });
  await switchTab(page, 'server');
}

function row(page: Page, id: string) {
  return page.locator('#serverBoard tr', { hasText: id }).first();
}

test.describe('E28 — server data that does not line up with the run', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'E28');
    await switchTab(page, 'server');
  });

  test('[R] only the aligned series is judged; each misalignment is said in its own words', async ({ page }) => {
    const verdicts = await page.evaluate(() => {
      const out: Record<string, string> = {};
      ((window as any).chartData.serverKpis || []).forEach((k: any) => {
        out[k.id] = k.status + (k.notJudged ? '/' + k.notJudged : '');
      });
      return out;
    });
    expect(verdicts).toEqual({
      aligned: 'PASS',
      shifted: 'NOT_JUDGED/SOURCE_FAILED',
      heap: 'NOT_JUDGED/SOURCE_FAILED',
      blank: 'NOT_JUDGED/SOURCE_FAILED',
      spaced: 'NOT_JUDGED/INVALID_RULE',
      split: 'NOT_JUDGED/SOURCE_FAILED',
    });
  });

  test('[R] the Collection panel opens on the failed series and gives each one a cause and a fix', async ({ page }) => {
    const panel = page.locator('.wir-collection');
    await expect(panel).toContainText('They start 3h late, which is usually a time zone');
    await expect(panel).toContainText('move the rows with --clock-offset -3h');
    await expect(panel).toContainText('50 rows name app/heap_used - component and metric must be written exactly');
    await expect(panel).toContainText('every value is empty or NaN');
    await expect(panel).toContainText('e28-metrics.csv line 235: has 6 cells where the header has 5');
  });

  test('[R] H11: 4 s rows under sustainedFor 30s at the default step are refused for their measured spacing', async ({ page }) => {
    await expect(page.locator('#serverBoard')).toContainText(
      "reduce.over sustainedFor 30s is not a whole number of the data's measured 4s spacing (collected at --step 15s)");
    await expect(row(page, 'spaced')).toContainText('INVALID');
  });

  test('[R] the not-judged asserts are counted, and said not to fail the build', async ({ page }) => {
    await expect(page.locator('#serverBoard')).toContainText(
      '5 server asserts were not judged — each row below says why. Server data problems do not fail the build');
  });
});

test.describe('E28 — the interim and refused pages of the same file', () => {
  test('[R] interim: NOT COLLECTED, the window, the six names, and no wir-collect-only instruction', async ({ page }) => {
    await openPage(page, 'E28/interim/E28-interim.html');
    await expect(page.locator('.wir-server-chip')).toHaveText('Server · NOT COLLECTED');
    const finish = page.locator('#serverFinish');
    await expect(finish).toContainText('2023-11-14T22:13:20.026Z');
    await expect(finish).toContainText('1700000000026');
    for (const m of ['aligned', 'shifted', 'heap.used', 'blank', 'spaced', 'split']) {
      await expect(finish.locator('tbody tr', { hasText: m }).first()).toBeVisible();
    }
    // The CSV route is two commands: the page no longer sends a CSV user to wir-collect first.
    const board = page.locator('#serverBoard');
    await expect(board).toContainText('a CSV goes straight to jmeter -g');
    await expect(board).not.toContainText('run wir-collect with this file against');
    await expect(board.locator('.wir-uncollected-row').first())
      .toHaveText('Not collected yet — add this run’s server data (below) and regenerate the report to judge this server assert.');
  });

  test('[R] refused: REFUSED, the two run ids and the --test-name rule, nothing judged', async ({ page }) => {
    await openPage(page, 'E28/refused/E28-refused.html');
    await expect(page.locator('.wir-server-chip')).toHaveText('Server · REFUSED');
    const board = page.locator('#serverBoard');
    await expect(board).toContainText('belongs to run 967a1b397b7fb1d2');
    await expect(board).toContainText("wir-collect's --test-name must be the same text as -Jwebinsight.report.title");
    await expect(board).toContainText('fails the build only under webinsight.report.server.failonerror=true');
    expect(await board.locator('.wir-uncollected-row').count()).toBe(6);
  });
});

test.describe('E29 — a four-hour soak at the page caps', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'E29');
    await switchTab(page, 'server');
  });

  test('[R] the payload came from the sidecar, and the board carries the build verdicts', async ({ page }) => {
    const src = await page.evaluate(() => Array.from(document.querySelectorAll('script[src]'))
      .map(s => (s as HTMLScriptElement).getAttribute('src')));
    expect(src).toContain('E29-report-data.js');
    await expect(page.locator('.wir-server-chip')).toHaveText('Server · Fleet FAIL');
    await expect(row(page, 'cpu-spike')).toContainText('97.5 %');
    await expect(row(page, 'cpu-held')).toContainText('46.6 %');
    await expect(row(page, 'mem-growth')).toContainText('0.03 %/min');
  });

  test('[R] 20 of 24 instances are drawn, and said to be', async ({ page }) => {
    await expect(row(page, 'cpu-spike')).toContainText('24 (20 drawn)');
    const drawn = await page.evaluate(() => (window as any).chartData.serverMetrics.series
      .map((s: any) => [s.query, s.instanceCount, s.instances.length]));
    expect(drawn).toEqual([['cpu', 24, 20], ['mem', 24, 20]]);
  });

  test('[R] a thinned instance still reaches the 3-second spike the build judged', async ({ page }) => {
    const node17 = await page.evaluate(() => {
      const cpu = (window as any).chartData.serverMetrics.series.find((s: any) => s.query === 'cpu');
      const inst = cpu.instances.find((i: any) => i.labels.instance === 'node-17');
      return { kept: inst.points.length, of: inst.pointCount,
        max: Math.max.apply(null, inst.points.map((p: number[]) => p[1])) };
    });
    expect(node17.kept).toBe(4000);
    expect(node17.of).toBeGreaterThan(14000);
    expect(node17.max).toBe(97.5);
  });

  test('[R] the axis spans the four hours', async ({ page }) => {
    await expect(page.locator('#serverBoard').locator('xpath=..')).toContainText('Window judged: ');
    const w = await page.evaluate(() => (window as any).chartData.runWindow);
    expect(w.end - w.start).toBeGreaterThan(3.9 * 3600000);
  });
});
