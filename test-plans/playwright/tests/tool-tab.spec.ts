import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as zlib from 'zlib';
import { loadClean, switchTab } from './helpers';
import { REQUIRED_FIXTURES } from '../global-setup';

/**
 * The tool's own tab (spec 001, decision 10, FR-008, task T053), on K1: a k6 run built by `build-report --from k6`.
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

async function rows(page: Page, table: string, attrs: string[]) {
  return page.$$eval(`${table} tbody tr`, (trs, a) => trs.map((tr) => ({
    attrs: (a as string[]).map((x) => tr.getAttribute(x)),
    cells: Array.from(tr.querySelectorAll('td')).map((td) => (td as HTMLElement).textContent!.trim()),
  })), attrs);
}

function checkTrend(where: string, row: { cells: string[] }, want: Trend, firstStat: number) {
  const shown = row.cells.slice(firstStat);
  expect(Math.abs(Number(shown[0]) - want.avg), `${where}: avg ${shown[0]} is the mean ${want.avg}, to two decimals`)
    .toBeLessThanOrEqual(0.005 + 1e-9);
  expect(shown.slice(1), `${where}: min, med, max, p(90), p(95)`).toEqual(want.cells);
}

test.describe('The tool\'s tab: k6\'s own measures', () => {
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
    const want: string[] = [];
    for (const [metric, m] of Object.entries<any>(summary().metrics)) {
      for (const [expr, t] of Object.entries<any>(m.thresholds || {})) want.push(`${metric} | ${expr} | ${t.ok ? 'passed' : 'failed'}`);
    }
    const got = (await rows(page, '#toolThresholds', [])).map((r) => r.cells.join(' | '));
    expect(got.sort()).toEqual(want.sort());
    expect(got.some((r) => r.endsWith('failed')), 'K1 fails one threshold on purpose').toBe(true);
    // decision 4: the report's verdict is the test's asserts; a failed threshold does not make it FAIL
    await expect(page.locator('header .wir-asserts-chip')).toHaveText('ASSERTS PASS');
  });

  test('checks are summary.json\'s passes and fails, per group and name', async ({ page }) => {
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
    const got = (await rows(page, '#toolChecks', ['data-group'])).map((r) => `${r.attrs[0]} | ${r.cells[1]} | ${r.cells[2]} | ${r.cells[3]}`);
    expect(got.sort()).toEqual(want.sort());
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
    const heads = await page.$$eval('#toolGroupDurations thead th', (ths) => ths.map((t) => (t as HTMLElement).innerText.trim()));
    expect(heads.slice(2)).toEqual(['avg', 'min', 'med', 'max', 'p(90)', 'p(95)']);
    for (const r of shown) {
      const want = trend(byGroup.get(r.attrs[0]!)!);
      expect(r.cells[1], `${r.attrs[0]}: its passes`).toBe(String(want.count));
      checkTrend(r.attrs[0]!, r, want, 2);
    }
  });

  test('the six timing parts, over every request and per request, in k6\'s columns', async ({ page }) => {
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
    const shown = await rows(page, '#toolTimings', ['data-request', 'data-part']);
    const key = (req: string | null, part: string | null) => `${req} ${part}`;
    const got = new Map(shown.map((r) => [key(r.attrs[0], r.attrs[1]), r]));
    for (const p of PARTS) {
      const row = got.get(key('*', p));
      expect(row, `every request's ${p}`).toBeDefined();
      expect(row!.cells[1]).toBe(K6_PART(p));
      checkTrend(`every request's ${p}`, row!, trend(all.get(p)!), 2);
    }
    for (const [name, parts] of byRequest) {
      for (const [p, values] of parts) {
        const row = got.get(key(name, p));
        expect(row, `${name}'s ${p}`).toBeDefined();
        checkTrend(`${name}'s ${p}`, row!, trend(values), 2);
      }
    }
    expect(shown.length).toBe(PARTS.length * (byRequest.size + 1));
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

test('no JMeter fixture has a tool tab', async ({ page }) => {
  // K1 and K2 are k6 runs' reports; every other fixture is a JMeter run's
  for (const id of REQUIRED_FIXTURES.filter((f) => !['K1', 'K2'].includes(f))) {
    await loadClean(page, id);
    await expect(page.locator('.tab-btn[data-tab="tool"]'), id).toHaveCount(0);
    await expect(page.locator('#tab-tool'), id).toHaveCount(0);
  }
});
