import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as zlib from 'zlib';
import { loadClean, loadDirty, switchTab } from './helpers';
import { REQUIRED_FIXTURES } from '../global-setup';

/**
 * The tool's own tab (spec 001, decision 10, FR-008; spec 002, US1-US3), on K1 (the k6 shapes run) and K2 (petstore's
 * checkout smoke, 50 thresholds, one failed): k6 runs built by `build-report --from k6`.
 *
 * Its numbers are held to sources the page does not compute:
 *   - thresholds, checks, iterations, dropped iterations and vus_max to k6's own summary.json;
 *   - group durations and the six timing parts to the run data the build wrote beside the report, aggregated here by
 *     the Aggregate Report's rule (the value at rank round(n * p), the fraction a float), which is what the engine's
 *     exact aggregator answers. k6's own percentiles interpolate, so they are not the reference for those.
 * Bytes per request read "not measured", and no JMeter fixture has a tool tab.
 */

const ROOT = path.resolve(__dirname, '..', '..', '..');
const K1_INPUTS = path.join(ROOT, 'test-plans', 'run-data', 'K1');
const K1_RESULTS = path.join(ROOT, 'test-results', 'K1');
const PARTS = ['blocked', 'connecting', 'tlsHandshaking', 'sending', 'waiting', 'receiving'];
const K6_PART = (p: string) => 'http_req_' + p.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase());

const summary = () => JSON.parse(fs.readFileSync(path.join(K1_INPUTS, 'summary.json'), 'utf-8'));

function runData(): any[] {
  const text = zlib.gunzipSync(fs.readFileSync(path.join(K1_RESULTS, 'K1-report-run-data.jsonl.gz'))).toString('utf-8');
  return text.split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
}

/** JMeter's StatCalculator.getPercentPoint over whole hundredths: k = round(n * (float) p / 100f). */
function percentile(sorted: number[], p: number): number {
  if (p >= 100) return sorted[sorted.length - 1];
  const fraction = Math.fround(Math.fround(p) / 100);
  const k = Math.round(Math.fround(sorted.length * fraction));
  return sorted[Math.max(0, k - 1)];
}

type Trend = { count: number; avg: number; cells: string[] };

/** k6's summary columns: avg, min, med, max, p(90), p(95), from values in whole hundredths of a ms. */
function trend(hundredths: number[]): Trend {
  const s = [...hundredths].sort((a, b) => a - b);
  const two = (h: number) => (h / 100).toFixed(2);
  const avg = s.reduce((a, b) => a + b, 0) / 100 / s.length;
  return {
    count: s.length, avg,
    cells: [two(s[0]), two(percentile(s, 50)), two(s[s.length - 1]), two(percentile(s, 90)), two(percentile(s, 95))],
  };
}

const K2_INPUTS = path.join(ROOT, 'test-plans', 'run-data', 'K2');
const k2Summary = () => JSON.parse(fs.readFileSync(path.join(K2_INPUTS, 'summary.json'), 'utf-8'));

/** Every row of a k6 table, the cells' text and the attributes asked for. */
async function rows(page: Page, table: string, attrs: string[]) {
  return page.$$eval(`${table} tbody tr.wir-tool-row`, (trs, a) => trs.map((tr) => ({
    attrs: (a as string[]).map((x) => tr.getAttribute(x)),
    cells: Array.from(tr.querySelectorAll(':scope > td')).map((td) => (td as HTMLElement).textContent!.trim()),
  })), attrs);
}

/** The thresholds table with no grouping and nothing folded: every threshold a row. */
async function everyThreshold(page: Page) {
  await page.selectOption('#toolThresholds-group', '');
  for (let fold = page.locator('#toolThresholds .wir-fold-show'); await fold.count(); ) await fold.first().click();
}

function checkTrend(where: string, cells: string[], want: Trend) {
  expect(Math.abs(Number(cells[0]) - want.avg), `${where}: avg ${cells[0]} is the mean ${want.avg}, to two decimals`)
    .toBeLessThanOrEqual(0.005 + 1e-9);
  expect(cells.slice(1), `${where}: min, med, max, p(90), p(95)`).toEqual(want.cells);
}

test.describe('The tool\'s tab: k6\'s own measures [K1]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'K1');
  });

  test('a tab named k6, after Server and before Notes', async ({ page }) => {
    const order = await page.$$eval('#tabNav .tab-btn', (bs) => bs.map((b) => b.getAttribute('data-tab')));
    expect(order.indexOf('tool')).toBe(order.indexOf('server') + 1);
    expect(order.indexOf('notes')).toBe(order.indexOf('tool') + 1);
    await expect(page.locator('.tab-btn[data-tab="tool"]')).toHaveText('k6');
    await expect(page.locator('.tab-btn[data-tab="tool"]')).toBeVisible();
  });

  test('thresholds are summary.json\'s, each with its own result, and never the verdict', async ({ page }) => {
    await switchTab(page, 'tool');
    await everyThreshold(page);
    const want: string[] = [];
    for (const [metric, m] of Object.entries<any>(summary().metrics)) {
      for (const [expr, t] of Object.entries<any>(m.thresholds || {})) want.push(`${metric} | ${expr} | ${t.ok ? 'passed' : 'failed'}`);
    }
    const got = (await rows(page, '#toolThresholds', ['data-metric', 'data-expression']))
      .map((r) => `${r.attrs[0]} | ${r.attrs[1]} | ${r.cells[0]}`);
    expect(got.sort()).toEqual(want.sort());
    // decision 4: the report's verdict is the test's asserts; a failed threshold does not make it FAIL
    await expect(page.locator('header .wir-asserts-chip')).toHaveText('ASSERTS PASS');
  });

  test('a failed rate threshold says how far past in points, on the strip and its row, with no share of the limit', async ({ page }) => {
    // A rate's limit and value are percentages already: "17.18% over (1718%)" read as two percentages that contradict
    // each other (owner, 2026-11-27: "Fix the 2 report wordings")
    await switchTab(page, 'tool');
    const failed = summary().metrics['http_req_failed'];
    expect(failed.thresholds['rate<0.01'].ok).toBe(false);
    expect((failed.values.rate * 100).toFixed(2)).toBe('18.18');
    await expect(page.locator('#toolStrip')).toContainText('http_req_failed rate<0.01 (rate = 18.18%, 17.18 points over)');
    const row = page.locator('#toolThresholds tr.wir-tool-row[data-metric="http_req_failed"][data-expression="rate<0.01"]');
    await expect(row.locator('td[data-col-id="measured"]')).toHaveText('rate = 18.18%');
    await expect(row.locator('td.wir-tool-distance')).toHaveText('17.18 points over');
    for (const where of [page.locator('#toolStrip'), row]) await expect(where).not.toContainText('1718');
  });

  test('checks are summary.json\'s passes and fails, under their group, failed first', async ({ page }) => {
    await switchTab(page, 'tool');
    const want: string[] = [];
    const walk = (g: any) => {
      for (const c of g.checks || []) {
        const group = c.path.slice(0, c.path.length - ('::' + c.name).length);
        want.push(`${group} | ${c.name} | ${c.passes} | ${c.fails}`);
      }
      for (const sub of g.groups || []) walk(sub);
    };
    walk(summary().root_group);
    const got = await rows(page, '#toolChecks', ['data-group', 'data-check']);
    expect(got.map((r) => `${r.attrs[0]} | ${r.attrs[1]} | ${r.cells[2]} | ${r.cells[3]}`).sort()).toEqual(want.sort());
    // US3: the check that always fails leads its group, and the failed chip leaves only it
    await expect(page.locator('#toolChecks tr.wir-group-row').first()).toContainText('Complete Checkout Flow');
    expect(got[0].attrs[1]).toBe('server error is 200');
    await page.click('.wir-table-toolbar[data-table="toolChecks"] .wir-status-chip[data-status="FAIL"]');
    await expect(page.locator('#toolChecks tr.wir-tool-row')).toHaveCount(1);
  });

  test('group durations are each group\'s passes on their wall time, in k6\'s columns', async ({ page }) => {
    await switchTab(page, 'tool');
    const byGroup = new Map<string, number[]>();
    for (const r of runData()) {
      if (r.type === 'sample' && r.kind === 'transaction' && r.wallTime != null) {
        if (!byGroup.has(r.name)) byGroup.set(r.name, []);
        byGroup.get(r.name)!.push(r.wallTime * 100);
      }
    }
    expect(byGroup.size).toBeGreaterThan(1);
    const shown = await rows(page, '#toolGroupDurations', ['data-group']);
    expect(shown.map((r) => r.attrs[0]).sort()).toEqual([...byGroup.keys()].sort());
    const cols = await page.$$eval('#toolGroupDurations thead th[data-col-id]', (ths) => ths.map((t) => t.getAttribute('data-col-id')));
    expect(cols.slice(2)).toEqual(['avg', 'min', 'med', 'max', 'p(90)', 'p(95)']);
    for (const r of shown) {
      const want = trend(byGroup.get(r.attrs[0]!)!);
      expect(r.cells[1], `${r.attrs[0]}: its passes`).toBe(String(want.count));
      checkTrend(r.attrs[0]!, r.cells.slice(2, 2 + 6), want);
    }
  });

  test('the timing parts, one row per request at the chosen statistic, and every statistic on its row', async ({ page }) => {
    await switchTab(page, 'tool');
    const byRequest = new Map<string, Map<string, number[]>>();
    const all = new Map<string, number[]>();
    for (const r of runData()) {
      if (r.type !== 'sample' || !r.timings) continue;
      if (!byRequest.has(r.name)) byRequest.set(r.name, new Map());
      for (const p of PARTS) {
        if (r.timings[p] == null) continue;
        const h = Math.round(r.timings[p] * 100);
        const m = byRequest.get(r.name)!;
        if (!m.has(p)) m.set(p, []);
        m.get(p)!.push(h);
        if (!all.has(p)) all.set(p, []);
        all.get(p)!.push(h);
      }
    }
    expect(all.get('waiting')!.length).toBe(summary().metrics.http_reqs.values.count);
    const cellsOf = async () => new Map((await rows(page, '#toolTimings', ['data-request']))
      .map((r) => [r.attrs[0], r.cells.slice(1, 1 + PARTS.length)]));
    // avg, the default: each part's mean to the hundredth, over every request and per request
    let shown = await cellsOf();
    PARTS.forEach((p, i) => {
      expect(Math.abs(Number(shown.get('*')![i]) - trend(all.get(p)!).avg), `every request's ${p}`).toBeLessThanOrEqual(0.005 + 1e-9);
    });
    for (const [name, parts] of byRequest) {
      expect(shown.has(name), `a row for ${name}`).toBe(true);
      PARTS.forEach((p, i) => {
        if (parts.has(p)) expect(Math.abs(Number(shown.get(name)![i]) - trend(parts.get(p)!).avg), `${name}'s ${p}`).toBeLessThanOrEqual(0.005 + 1e-9);
      });
    }
    // the switch: every cell at p(95), the Aggregate Report's rank
    await page.selectOption('#toolTimings-statistic', 'p(95)');
    shown = await cellsOf();
    for (const [name, parts] of byRequest) {
      PARTS.forEach((p, i) => { if (parts.has(p)) expect(shown.get(name)![i], `${name}'s ${p} p(95)`).toBe(trend(parts.get(p)!).cells[4]); });
    }
    // a row opens to every statistic of every part, k6's names
    const login = page.locator('#toolTimings tr.wir-tool-row[data-request="Login"]');
    await login.locator('.wir-tool-more').click();
    const detail = page.locator('#toolTimings .wir-tool-detail-row');
    await expect(detail).toHaveCount(1);
    await expect(detail.locator('tbody tr')).toHaveCount(PARTS.length + 1);
    await expect(detail).toContainText('http_req_tls_handshaking');
    await expect(detail).toContainText('http_req_duration');
  });

  test('iterations, dropped iterations and vus_max are summary.json\'s; Max VUs is the most VUs active', async ({ page }) => {
    const m = summary().metrics;
    await expect(page.locator('#tab-summary .summary-card', { hasText: 'Max VUs' }).locator('.card-value'))
      .toHaveText(String(m.vus.values.max));
    await switchTab(page, 'tool');
    await expect(page.locator('#toolCounters [data-counter="iterations"] .card-value')).toHaveText(String(m.iterations.values.count));
    await expect(page.locator('#toolCounters [data-counter="dropped_iterations"] .card-value')).toHaveText('0');
    await expect(page.locator('#toolCounters [data-counter="vus_max"] .card-value')).toHaveText(String(m.vus_max.values.max));
  });

  test('bytes per request read "not measured", not 0 (FR-008)', async ({ page }) => {
    await switchTab(page, 'samplers');
    const heads = await page.$$eval('#samplerTable thead th', (ths) => ths.map((t) => (t as HTMLElement).innerText.trim()));
    const recv = heads.indexOf('Recv KB/s');
    const sent = heads.indexOf('Sent KB/s');
    expect(recv).toBeGreaterThan(0);
    const cells = await page.$$eval('#samplerTable tbody tr[data-sampler]', (trs, [a, b]) => trs.map((tr) => {
      const td = tr.querySelectorAll('td');
      return [(td[a as number] as HTMLElement).textContent!.trim(), (td[b as number] as HTMLElement).textContent!.trim()];
    }), [recv, sent]);
    expect(cells.length).toBeGreaterThan(5);
    for (const c of cells) expect(c).toEqual(['not measured', 'not measured']);
  });
});

/**
 * Spec 002's scenarios on K2: the first screen says which threshold failed and by how much (US1, SC-001); the
 * thresholds regroup and narrow; the timings are one row per request (US2, SC-002); one search narrows every section
 * (SC-003); a request's name opens its row on the Requests tab (FR-008).
 */
test.describe('The k6 tab, organised and interactive [K2]', () => {
  test.beforeEach(async ({ page }) => {
    await loadClean(page, 'K2');
    await switchTab(page, 'tool');
  });

  test('the first screen names the failed threshold, what k6 measured and how far past its limit', async ({ page }) => {
    // SC-001 on the strip itself: the failed threshold, what k6 measured and how far past its limit (spec 002, stage 8:
    // the filter bar shows on this tab too, and the strip keeps the answer on the first screen whatever its height)
    await expect(page.locator('#toolStrip')).toHaveText(
      'Thresholds: 1 of 50 failed: Login p(95)<=600 (p(95) = 779.72 ms, 179.72 ms over (30%)) · Checks: none ran');
    await expect(page.locator('#toolThresholdsHost h2')).toHaveText('Thresholds (49 of 50 passed)');
    const chips = page.locator('.wir-table-toolbar[data-table="toolThresholds"] .wir-status-chip');
    await expect(chips).toHaveText(['failed 1', 'passed 49']);
    // the one open group holds the failure, every other is closed and shows its own counts
    const groups = page.locator('#toolThresholds tr.wir-group-row');
    await expect(groups.first()).toContainText('http_req_duration');
    await expect(groups.first().locator('.wir-group-toggle')).toHaveAttribute('aria-expanded', 'true');
    const closed = await groups.locator('.wir-group-toggle[aria-expanded="false"]').count();
    expect(closed).toBe((await groups.count()) - 1);
    const login = page.locator('#toolThresholds tr.wir-tool-row').first();
    await expect(login).toHaveAttribute('data-metric', 'http_req_duration{name:Login}');
    await expect(login).toContainText('p(95)<=600');
    await expect(login).toContainText('p(95) = 779.72 ms');
    await expect(login).toContainText('179.72 ms over (30%)');
    // SC-001: no scrolling, the filter bar shown above it
    await expect(page.locator('#filterBar')).toBeVisible();
    const box = await page.locator('#toolStrip').boundingBox();
    expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
    // what k6 measured is k6's own number (SC-006)
    expect(k2Summary().metrics['http_req_duration{name:Login}'].values['p(95)'].toFixed(2)).toBe('779.72');
  });

  test('the always-true thresholds fold into one line, and are counted', async ({ page }) => {
    await page.selectOption('#toolThresholds-group', '');
    await expect(page.locator('#toolThresholds .wir-fold-row')).toHaveText(/41 always-true thresholds/);
    await expect(page.locator('#toolThresholds tr.wir-tool-row')).toHaveCount(9);
    await page.locator('#toolThresholds .wir-fold-show').click();
    await expect(page.locator('#toolThresholds tr.wir-tool-row')).toHaveCount(50);
  });

  test('grouped by request, Login\'s thresholds sit together; the rest are tied to no one request', async ({ page }) => {
    await page.selectOption('#toolThresholds-group', 'request');
    const loginGroup = page.locator('#toolThresholds tr.wir-group-row', { hasText: 'Login' }).first();
    await expect(loginGroup.locator('.wir-group-counts')).toHaveText('4 thresholds · 1 failed · 3 passed');
    await expect(page.locator('#toolThresholds tr.wir-group-row').last()).toContainText('Not tied to one request');
  });

  test('the failed chip leaves the failed threshold; the search narrows by metric, tag or threshold', async ({ page }) => {
    await page.click('.wir-table-toolbar[data-table="toolThresholds"] .wir-status-chip[data-status="FAIL"]');
    await expect(page.locator('#toolThresholds tr.wir-tool-row')).toHaveCount(1);
    await page.click('.wir-table-toolbar[data-table="toolThresholds"] .wir-status-chip[data-status="FAIL"]');
    await page.selectOption('#toolThresholds-group', '');
    await page.locator('#toolThresholds .wir-fold-show').click();
    await page.fill('#toolSearch-thresholds', 'Checkout');
    const metrics = await page.$$eval('#toolThresholds tr.wir-tool-row', (trs) => trs.map((tr) => tr.getAttribute('data-metric')!));
    expect(metrics.length).toBeGreaterThan(0);
    for (const m of metrics) expect(m).toContain('Checkout');
  });

  test('one search narrows every section, the timings too (SC-003)', async ({ page }) => {
    await page.fill('#toolSearch-thresholds', 'Login');
    await expect(page.locator('#toolSearch-timings')).toHaveValue('Login');
    const requests = await page.$$eval('#toolTimings tr.wir-tool-row', (trs) => trs.map((tr) => tr.getAttribute('data-request')));
    // every request stays (it is the whole run); each match, with the group above it as context
    expect(requests).toEqual(['*', 'Admin Login (cleanup)', 'Complete Checkout Flow', 'Login']);
  });

  test('the timings: one row per request under its group, sorted by waiting in one click (SC-002)', async ({ page }) => {
    const all = await page.$$eval('#toolTimings tr.wir-tool-row', (trs) => trs.map((tr) => tr.getAttribute('data-request')));
    expect(all[0]).toBe('*');
    expect(all).toHaveLength(14);   // 12 requests, every request, and the group they sit under
    await page.click('#toolTimings-sort-waiting');
    const sorted = await page.$$eval('#toolTimings tr.wir-tool-row', (trs) => trs.map((tr) => tr.getAttribute('data-request')));
    // a sort orders siblings: the cleanup that waited longest leads the top level, Login leads the checkout flow
    expect(sorted[1]).toBe('Restore Stock (cleanup)');
    expect(sorted[sorted.indexOf('Complete Checkout Flow') + 1]).toBe('Login');
  });

  test('a request\'s name opens its row on the Requests tab, with its detail panel', async ({ page }) => {
    await page.locator('#toolThresholds tr.wir-tool-row').first().locator('.wir-tool-request').click();
    await expect(page.locator('.tab-btn[data-tab="samplers"]')).toHaveClass(/active/);
    const row = page.locator('#samplerTable tbody tr[data-sampler="Login"]');
    await expect(row).toBeVisible();
    await expect(page.locator('#samplerTable .sampler-detail-row')).toHaveCount(1);
  });

  test('the grouping and the statistic are remembered for this report', async ({ page }) => {
    await page.selectOption('#toolThresholds-group', 'condition');
    await page.selectOption('#toolTimings-statistic', 'p(99)');
    await loadDirty(page, 'K2');
    await switchTab(page, 'tool');
    await expect(page.locator('#toolThresholds-group')).toHaveValue('condition');
    await expect(page.locator('#toolTimings-statistic')).toHaveValue('p(99)');
  });
});

// K1 and K2 are k6 runs' reports, and so are CMPK and CMPX (K2's run with a baseline, spec 004); every other fixture
// is a JMeter run's. One test each: twenty pages in one test outran its 30 seconds while the rest of the suite ran
// beside it (2026-11-25).
const K6_RUNS = ['K1', 'K2', 'CMPK', 'CMPX'];
for (const id of REQUIRED_FIXTURES.filter((f) => !K6_RUNS.includes(f))) {
  test(`${id}, a JMeter run, has no tool tab`, async ({ page }) => {
    await loadClean(page, id);
    await expect(page.locator('.tab-btn[data-tab="tool"]')).toHaveCount(0);
    await expect(page.locator('#tab-tool')).toHaveCount(0);
  });
}

/*
 * Stage 9 (US5, FR-040, FR-041): a k6 request's detail panel charts its six timing parts over time, at the report's
 * time buckets, and a k6 threshold on that request's duration is a line at its limit on its response time chart (and
 * its percentiles chart), labelled as k6's threshold: not an assert, and not draggable. A JMeter run carries nothing new.
 */
async function openDetail(page: Page, name: string) {
  await switchTab(page, 'samplers');
  await page.click('#samplerTable-expand-all');
  await page.locator(`#samplerTable tbody tr[data-sampler="${name}"] .sampler-name-text`).click();
  await expect(page.locator('#samplerTable tr.sampler-detail-row')).toHaveCount(1);
  await page.waitForTimeout(300);   // the slots' charts are drawn after the row
}

async function slotOption(page: Page, key: string) {
  return page.evaluate((k) => {
    const slot = document.querySelector(`.sampler-detail-row .detail-chart-slot[data-chart-key="${k}"] .detail-chart-container`);
    const chart = slot ? (window as any).echarts.getInstanceByDom(slot) : null;
    if (!chart) return null;
    const o = chart.getOption();
    return {
      series: (o.series || []).map((s: any) => s.name),
      lines: (o.series || []).flatMap((s: any) => ((s.markLine && s.markLine.data) || []).map((d: any) => ({
        y: d.yAxis, label: (d.label && d.label.formatter) || d.name }))),
      graphics: JSON.stringify(o.graphic || []),
      // the points drawn as a dot: a second with a request and none on either side
      dots: (o.series || []).map((s: any) => (s.data || []).filter((d: any) => d && !Array.isArray(d) && d.symbol === 'circle').length),
    };
  }, key);
}

test.describe('Over time: the timing parts and k6\'s limits [K2, E02]', () => {
  test('Login\'s detail panel charts the six timing parts over time', async ({ page }) => {
    await loadClean(page, 'K2');
    await openDetail(page, 'Login');
    const keys = await page.locator('.sampler-detail-row .detail-chart-slot').evaluateAll((s: HTMLElement[]) =>
      s.map(e => e.getAttribute('data-chart-key')));
    expect(keys, 'a k6 run opens the parts by default').toContain('timingParts');
    const parts = await slotOption(page, 'timingParts');
    expect(parts!.series).toEqual(['Blocked', 'Connecting', 'TLS', 'Sending', 'Waiting', 'Receiving']);
    // Login ran 6 times in 36 s, each second alone: a line with no symbol drew nothing, so each is a dot
    expect(parts!.dots[4], "Waiting's lone seconds are dots").toBeGreaterThan(0);
    expect((await slotOption(page, 'responseTime'))!.dots[0], "and the response time's").toBeGreaterThan(0);
    // at the report's own time buckets: one value per second, as every other series
    const aligned = await page.evaluate(() => {
      const s = (window as any).globalChartData.perSamplerSeries.Login;
      return s.waiting.length === s.responseTime.length && s.waiting.some((v: any) => typeof v === 'number');
    });
    expect(aligned).toBe(true);
  });

  test('k6\'s p(95)<=600 on Login is a line at 600 on its response time and percentiles charts, k6\'s and not draggable', async ({ page }) => {
    await loadClean(page, 'K2');
    await openDetail(page, 'Login');
    const rt = await slotOption(page, 'responseTime');
    expect(rt!.lines).toEqual([{ y: 600, label: 'k6 threshold: p(95)<=600' }]);
    expect(rt!.graphics, 'no drag handle: the file\'s threshold is k6\'s').not.toContain('wir-handle');
    await page.locator('.sampler-detail-row .detail-chart-slot').nth(0).locator('select.detail-chart-select')
      .selectOption('percentiles');
    await page.waitForTimeout(300);
    expect((await slotOption(page, 'percentiles'))!.lines).toEqual([{ y: 600, label: 'k6 threshold: p(95)<=600' }]);
  });

  test('a JMeter run carries no timing parts and offers no parts chart', async ({ page }) => {
    await loadClean(page, 'E02');
    const keys = await page.evaluate(() => {
      const s = (window as any).globalChartData.perSamplerSeries;
      return Object.keys(s[Object.keys(s)[0]]);
    });
    expect(keys).not.toContain('waiting');
    const name = await page.locator('#samplerTable tbody tr[data-sampler]:not(.transaction-child)').first().getAttribute('data-sampler');
    await openDetail(page, name!);
    const options = await page.locator('.sampler-detail-row select.detail-chart-select').first()
      .locator('option').evaluateAll((os: HTMLOptionElement[]) => os.map(o => o.value));
    expect(options).not.toContain('timingParts');
  });
});
