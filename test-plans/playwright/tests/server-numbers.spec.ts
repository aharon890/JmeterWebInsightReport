import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * How the Server tab writes a number (Batch 3, plans/server-tab-followups.md; the pure half is
 * test-plans/js-unit/server-format.test.js):
 *
 *   - SF15: seconds were rounded to whole units on every verdict surface, and a band was rounded
 *     with them: warn 0.4 / fail 0.6 read "warn > 0 s · fail > 1 s", 3.6 and 4.3 both read "4 s",
 *     and 2.9 read "3 s" beside "fail > 3 s" for a band of 2.95.
 *   - SF16: a slope verdict never said "/min", and a divideBy verdict lost its divisor on the
 *     Summary card; the Per instance panel drew a slope's per-minute bars on the raw unit's axis,
 *     with no band, and never divided a slope ratio.
 *   - SF17: one quantity in two spellings on one screen — the picker's Suggested line, Actual
 *     against Criteria — and axis ticks too long for the room the chart gives them.
 *
 * E27 is the catalogue fixture: transaction.longest.seconds peaks at 2.9 s on one instance, and
 * database.size.growth (a slope) at 60,000,000 B/min on pg-1. E26 is the custom one.
 */

/**
 * The board row of one assert, as text: [status chip, name, Actual, Criteria, ...]. Found by the
 * row's key, never by its name: since Q11 a catalogue row is NAMED by its metric's label, and
 * its id is the dim line under it (SF26).
 */
async function rowCells(page: Page, id: string) {
  return page.evaluate((kpiId) => {
    const W = (window as any).WIR.serverKpis;
    const row = document.querySelector(`#serverKpiTable tbody tr.wir-kpi-row[data-kpi-key="${W._keyOf(kpiId)}"]`);
    if (!row) return null;
    const cells = Array.from(row.children).map((td) => (td as HTMLElement).innerText.trim());
    cells[0] = row.querySelector('.wir-chip')!.textContent!.trim();
    return cells;
  }, id);
}

/** Add server asserts to the document the page holds, as Open file… with them would. */
async function addAsserts(page: Page, asserts: Record<string, unknown>[]) {
  await page.evaluate((list) => {
    const W = (window as any).WIR;
    W.asserts.openDocument();
    const doc = JSON.parse(W.document.text());
    list.forEach((k: any) => doc.serverMetrics.asserts.push(k));
    W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    W.asserts.applyDocument();
  }, asserts);
}

/**
 * Rewrite baked entries of chartData.serverKpis BEFORE report.js reads them: the Summary card
 * draws what the build judged, and neither fixture's file has a slope or a ratio that breached.
 * A setter defined first on window is what the template's `var chartData = {...}` calls.
 */
async function patchBakedBeforeLoad(page: Page, patches: Record<string, Record<string, unknown>>) {
  await page.addInitScript((p) => {
    let stored: any;
    Object.defineProperty(window, 'chartData', {
      configurable: true,
      get() { return stored; },
      set(value) {
        ((value && value.serverKpis) || []).forEach((k: any) => {
          if (p[k.id]) Object.assign(k, p[k.id]);
        });
        stored = value;
      },
    });
  }, patches);
}

/** The Per instance chart of one row, read off the ECharts instance. */
async function instanceChart(page: Page, id: string) {
  return page.evaluate((kpiId) => {
    const W = (window as any).WIR.serverKpis;
    const el = document.getElementById('serverInstances-' + W._keyOf(kpiId));
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!inst) return null;
    const opt = inst.getOption();
    const s = opt.series[0];
    const axis = inst.getModel().getComponent('xAxis', 0).axis;
    const marks = s.markLine && s.markLine.data ? s.markLine.data : [];
    return {
      name: opt.xAxis[0].name,
      values: s.data,
      lines: marks.map((d: any) => d.xAxis),
      opacities: marks.map((d: any) => d.lineStyle.opacity),
      labels: axis.getViewLabels().map((l: any) => l.formattedLabel),
      tooltip: opt.tooltip[0].formatter({ name: 'probe', value: s.data[0] }),
    };
  }, id);
}

/** The Value over time chart of one row, read off the ECharts instance; `probe` is fed to its tooltip. */
async function timeChart(page: Page, id: string, probe: number) {
  return page.evaluate(([kpiId, v]) => {
    const W = (window as any).WIR.serverKpis;
    const el = document.getElementById('serverChart-' + W._keyOf(kpiId));
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!inst) return null;
    const opt = inst.getOption();
    const t = opt.series[0].data[0][0];
    return {
      name: opt.yAxis[0].name,
      labels: inst.getModel().getComponent('yAxis', 0).axis.getViewLabels().map((l: any) => l.formattedLabel),
      tooltip: opt.tooltip[0].formatter([{ seriesName: 'probe', axisValue: t, value: [t, v] }]),
      note: (document.querySelector('.wir-chart-note') as HTMLElement).innerText,
    };
  }, [id, probe] as [string, number]);
}

async function openRow(page: Page, id: string) {
  const key = await page.evaluate((kpiId) => (window as any).WIR.serverKpis._keyOf(kpiId), id);
  await page.click(`tr.wir-kpi-row[data-kpi-key="${key}"] .wir-kpi-name`);
  await expect(page.locator('.wir-chart-row')).toHaveCount(1);
}

test.describe('SF15: seconds and bands are not rounded', () => {
  test('[U] SF15: warn 0.4 / fail 0.6 s, 3.6 / 4.3 s and a 2.95 s band read as written, beside a 2.9 s actual', async ({ page }) => {
    await loadClean(page, 'E27');
    await addAsserts(page, [
      { id: 'tx-small', component: 'orders-db', metric: 'transaction.longest.seconds', warn: { gt: 0.4 }, fail: { gt: 0.6 } },
      { id: 'tx-close', component: 'orders-db', metric: 'transaction.longest.seconds', warn: { gt: 3.6 }, fail: { gt: 4.3 } },
      { id: 'tx-near', component: 'orders-db', metric: 'transaction.longest.seconds', fail: { gt: 2.95 } },
    ]);
    await switchTab(page, 'server');
    // "warn > 0 s · fail > 1 s" — the dead-band signature, on a healthy assert.
    const small = (await rowCells(page, 'tx-small'))!;
    expect(small[0]).toBe('FAIL');
    expect(small[2]).toBe('2.9 s');
    expect(small[3]).toBe('warn > 0.4 s · fail > 0.6 s\nbreached > 0.6 s');
    // Two different bands that both read "4 s".
    expect((await rowCells(page, 'tx-close'))![3]).toBe('warn > 3.6 s · fail > 4.3 s');
    // "3 s" against "fail > 3 s", on an assert that PASSes.
    const near = (await rowCells(page, 'tx-near'))!;
    expect(near[0]).toBe('PASS');
    expect(near[2]).toBe('2.9 s');
    expect(near[3]).toBe('fail > 2.95 s');

    // The tooltips are measured numbers too: Value over time wrote a 2.95 s point as "3 s" and the
    // Per instance bar's 2.9 s as "3 s".
    await openRow(page, 'tx-small');
    const time = (await timeChart(page, 'tx-small', 2.95))!;
    // The name sits in a span that cuts it (5.1: Grafana-style rows); the number is what this pins.
    expect(time.tooltip).toContain('<span class="wir-tip-name">probe</span>: <strong>2.95 s</strong>');
    expect((await timeChart(page, 'tx-small', 0.004))!.tooltip).toContain('<span class="wir-tip-name">probe</span>: <strong>0.004 s</strong>');
    expect((await instanceChart(page, 'tx-small'))!.tooltip).toBe('<span class="wir-tip-name wir-tip-wrap">probe</span>: <strong>2.9 s</strong>');
  });

  test('[U] SF15: an Actual is never written onto a band it did not reach — 2.996 s is not "3 s" beside "≥ 3 s"', async ({ page }) => {
    // E26's lag-spike FAIL and cpu-headroom PASS, moved to the edge of their bands in the build's
    // own entries: two decimals wrote both actuals as the band they sit either side of.
    await patchBakedBeforeLoad(page, {
      'lag-spike': { unit: 's', actual: 3.004, warn: null, fail: { gt: 3 }, breached: { gt: 3 } },
    });
    await loadClean(page, 'E26');
    await expect(page.locator('#serverStrip .wir-gate-breach').nth(0))
      .toContainText('Kafka · lag-spike 3.004 s vs > 3 s');
    await switchTab(page, 'server');
    // The live board: cpu-per-rps's ratio (max cpu ÷ the measured throughput) has more than two
    // decimals. Its band is moved to the ratio's own two-decimal spelling, on the side it did not
    // reach — `gte` above a ratio that rounds up (a PASS), `gt` below one that rounds down (a FAIL).
    const actual: number = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'cpu-per-rps').actual);
    const twoDp = Math.round(actual * 100) / 100;
    expect(twoDp, 'cpu-per-rps happens to have two decimals; this needs another assert').not.toBe(actual);
    const above = twoDp > actual;
    await page.evaluate(([op, v]) => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-per-rps'));
      W.set('fail.op', op);
      W.set('fail.value', v);
      W.applyEditor();
    }, [above ? 'gte' : 'gt', String(twoDp)]);
    const cells = (await rowCells(page, 'cpu-per-rps'))!;
    expect(cells[0]).toBe(above ? 'PASS' : 'FAIL');
    const shown = parseFloat(cells[2].replace(/,/g, ''));
    // Written as the band, it read as ON it — a PASS that looked breached, or a FAIL that looked on.
    expect(shown).not.toBe(twoDp);
    expect(Math.sign(shown - twoDp)).toBe(Math.sign(actual - twoDp));
    expect(cells[2]).toMatch(/^[\d.]+ % ÷ load:throughput$/);
  });

  test('[U] SF15: the picker shows the run\'s 2.9 s, suggests 4.3 / 3.6 s, and the board keeps them after Add', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await expect(page.locator('.wir-pick-row[data-metric="transaction.longest.seconds"] .wir-pick-peak'))
      .toHaveText('peak 2.9 s');
    await page.click('.wir-pick-name:has-text("Longest open transaction (s)")');
    const facts = page.locator('.wir-pick-detail');
    await expect(facts).toContainText('peak 2.9 s over 1 instance');
    // SF17: the Suggested line in the board's spelling and the unit of the line above it.
    await expect(facts).toContainText('fail > 4.3 s · warn > 3.6 s');
    await page.check('[data-wir-dlg="pick:transaction.longest.seconds"]');
    await page.click('#serverPickNext');
    const row = page.locator('.wir-thresholds tr[data-metric="transaction.longest.seconds"]');
    await expect(row.locator('td.num')).toHaveText('peak 2.9 s');
    await page.click('#serverAddAssertsBtn');
    const cells = (await rowCells(page, 'orders-db-transaction-longest-seconds'))!;
    expect(cells[2]).toBe('2.9 s');
    expect(cells[3]).toBe('warn > 3.6 s · fail > 4.3 s');
  });

  test('[U] SF15: the Summary card does not round a seconds actual or its limit', async ({ page }) => {
    // E26's lag-spike FAIL and lag-p50-warns WARN, re-unitised in the build's own entries: the card
    // read "1 s vs > 0 s" and "3 s vs > 3 s".
    await patchBakedBeforeLoad(page, {
      'lag-spike': { unit: 's', actual: 0.6, fail: { gt: 0.4 }, breached: { gt: 0.4 } },
      'lag-p50-warns': { unit: 's', actual: 2.95, warn: { gt: 2.9 }, breached: { gt: 2.9 } },
    });
    await loadClean(page, 'E26');
    const rows = page.locator('#serverStrip .wir-gate-breach');
    await expect(rows.nth(0)).toContainText('Kafka · lag-spike 0.6 s vs > 0.4 s');
    await expect(rows.nth(1)).toContainText('DB · lag-p50-warns 2.95 s vs > 2.9 s');
  });
});

test.describe('SF16: a verdict number is in the unit it is compared in', () => {
  test('[U] SF16: a slope reads per minute on the board, its Per instance axis and bands, and the editor', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-headroom'));
      W.set('kind', 'slope');
      W.set('fail.value', '0.5');
    });
    // The editor's band boxes name the unit they are compared in — for every assert.
    await expect(page.locator('#serverEditorWarnUnit')).toHaveText('%/min');
    await expect(page.locator('#serverEditorFailUnit')).toHaveText('%/min');
    await page.evaluate(() => (window as any).WIR.serverKpis.applyEditor());

    const cells = (await rowCells(page, 'cpu-headroom'))!;
    expect(cells[2]).toMatch(/^-?[\d,.]+ %\/min$/);
    expect(cells[3]).toMatch(/^fail > 0\.5 %\/min/);

    await openRow(page, 'cpu-headroom');
    const inst = (await instanceChart(page, 'cpu-headroom'))!;
    // The bars are the slopes the verdict folds: per minute, on an axis that says so, against the
    // band — it was "%" and no line at all.
    expect(inst.name).toBe('%/min');
    expect(inst.lines).toEqual([0.5]);
    expect(inst.tooltip).toMatch(/ %\/min<\/strong>$/);
    const actual = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'cpu-headroom').actual);
    expect(Math.max(...(inst.values as number[]))).toBeCloseTo(actual, 9);
  });

  test('[U] SF16: a slope ratio\'s Per instance bars are divided, on a per-minute-per-load axis', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-per-rps'));
      W.set('kind', 'slope');
      W.applyEditor();
    });
    const cells = (await rowCells(page, 'cpu-per-rps'))!;
    expect(cells[2]).toMatch(/ %\/min ÷ load:throughput$/);
    expect(cells[3]).toBe('fail > 10,000 %/min ÷ load:throughput');
    await openRow(page, 'cpu-per-rps');
    const inst = (await instanceChart(page, 'cpu-per-rps'))!;
    expect(inst.name).toBe('%/min ÷ load:throughput');
    // across avg: the bars average to the verdict. Undivided they sat 24.76x outside it.
    const actual = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'cpu-per-rps').actual);
    const values = inst.values as number[];
    expect(values.reduce((a, b) => a + b, 0) / values.length).toBeCloseTo(actual, 9);
    expect(inst.lines).toEqual([10000]);
  });

  test('[U] SF16: the editor\'s band unit follows the unit, the reducer and the divisor as they are chosen', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
    });
    const unit = page.locator('#serverEditorFailUnit');
    await expect(unit).toHaveText('msgs');
    await page.evaluate(() => (window as any).WIR.serverKpis.set('divideBy', 'load:throughput'));
    await expect(unit).toHaveText('msgs ÷ load:throughput');
    await page.evaluate(() => (window as any).WIR.serverKpis.set('kind', 'slope'));
    await expect(unit).toHaveText('msgs/min ÷ load:throughput');
    await page.evaluate(() => (window as any).WIR.serverKpis.set('unit', ''));
    await expect(unit).toHaveText('/min ÷ load:throughput');
    await expect(page.locator('#serverEditorWarnUnit')).toHaveText('/min ÷ load:throughput');
  });

  test('[U] SF16: the Summary card writes a slope per minute and a ratio per unit of its load', async ({ page }) => {
    // The build's own entries carry reduce.kind and divideBy; the card wrote the bare unit.
    await patchBakedBeforeLoad(page, {
      'lag-spike': { reduce: { kind: 'slope' } },
      'lag-p50-warns': { divideBy: 'load:throughput' },
    });
    await loadClean(page, 'E26');
    const rows = page.locator('#serverStrip .wir-gate-breach');
    await expect(rows.nth(0)).toContainText('Kafka · lag-spike 900 msgs/min vs > 500 msgs/min');
    await expect(rows.nth(1)).toContainText(
      'DB · lag-p50-warns 120 msgs ÷ load:throughput vs > 100 msgs ÷ load:throughput');
  });

  test('[U] SF16/SF17: a catalogue slope reads per minute in Set thresholds, on the board and in Generate\'s review — grouped alike', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    // Generate's review first, while nothing asserts the metric: the seed's bands in B/min.
    await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
    const seed = page.locator('#serverDialog .wir-seed-review tbody tr', { hasText: 'Database size growth' });
    await expect(seed.locator('td').nth(3)).toHaveText('warn > 75,000,000 B/min');
    await expect(seed.locator('.wir-band-unit')).toHaveText('B/min');
    await page.evaluate(() => (window as any).WIR.serverKpis.closeDialog());

    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    await page.click('.wir-pick-name:has-text("Database size growth (bytes/min)")');
    await expect(page.locator('.wir-pick-detail')).toContainText('fail > 90,000,000 B/min · warn > 75,000,000 B/min');
    await page.check('[data-wir-dlg="pick:database.size.growth"]');
    await page.click('#serverPickNext');
    const row = page.locator('.wir-thresholds tr[data-metric="database.size.growth"]');
    await expect(row.locator('td.num')).toHaveText('slope 60,000,000 B/min');
    await expect(row.locator('.wir-band-unit')).toHaveText(['B/min', 'B/min']);
    await page.click('#serverAddAssertsBtn');

    // The live mirror's slope is 59,999,999.99999998: it read "60000000 B" beside "50,000,000 B".
    const cells = (await rowCells(page, 'orders-db-database-size-growth'))!;
    expect(cells[2]).toBe('60,000,000 B/min');
    expect(cells[3]).toBe('warn > 75,000,000 B/min · fail > 90,000,000 B/min');
  });
});

test.describe('SF17: large numbers fit their axes', () => {
  test('[U] SF17: a 1e9-scale series and its per-minute bars tick short — no label clipped or overprinted', async ({ page }) => {
    await loadClean(page, 'E27');
    await addAsserts(page, [
      { component: 'orders-db', metric: 'database.size.growth', warn: { gt: 75000000 }, fail: { gt: 90000000 } },
    ]);
    await switchTab(page, 'server');
    await openRow(page, 'orders-db-database-size-growth');
    const yLabels = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const el = document.getElementById('serverChart-' + W._keyOf('orders-db-database-size-growth'));
      const inst = (window as any).echarts.getInstanceByDom(el);
      return inst.getModel().getComponent('yAxis', 0).axis.getViewLabels().map((l: any) => l.formattedLabel);
    });
    // "6,000,000,000" was drawn as "0,000,000", its leading digits off the chart's left edge.
    expect(yLabels.some((l: string) => /G$/.test(l))).toBe(true);
    yLabels.forEach((l: string) => expect(l.length).toBeLessThanOrEqual(5));
    // One prefix per axis: chosen per tick, an axis mixed "500,000" with "1M".
    yLabels.forEach((l: string) => expect(l).toMatch(/^(0|[\d.]+G)$/));
    const inst = (await instanceChart(page, 'orders-db-database-size-growth'))!;
    expect(inst.name).toBe('B/min');
    expect(inst.lines).toEqual([75000000, 90000000]);
    // "10,000,0020,000,000..." printed over one another.
    expect(inst.labels.some((l: string) => /M$/.test(l))).toBe(true);
    inst.labels.forEach((l: string) => expect(l.length).toBeLessThanOrEqual(5));
    inst.labels.forEach((l: string) => expect(l).toMatch(/^(0|[\d.]+M)$/));

    // SF16: Value over time is the SERIES, a level in B — only the verdict is per minute, so the
    // two panels of one row name two units on purpose, and the note says which the bands are in.
    const time = (await timeChart(page, 'orders-db-database-size-growth', 1))!;
    expect(time.name).toBe('B');
    expect(time.note).toContain('the bands judge the slope of a least-squares fit per minute, in B/min, '
      + 'so they are not drawn against the series itself');
  });

  test('[U] SF17: the Summary tab writes one throughput once — the Asserts card reads 24.76 /s, as the Throughput card does', async ({ page }) => {
    // The client card's formatter wrote rates to one decimal: "throughput 24.8 /s vs < 100000 /s"
    // one card from the Test Summary card's "24.76/s". The server reason beside them is Java's
    // client-rule sentence, mirrored verbatim, and is not this formatter's.
    await loadClean(page, 'E26');
    const card = page.locator('.summary-card', { has: page.locator('.card-label', { hasText: /^Throughput$/ }) });
    await expect(card.locator('.card-value')).toHaveText('24.76/s');
    await expect(page.locator('#gateStrip .wir-breach', { hasText: 'load:unreachable' }))
      .toContainText('throughput 24.76 /s vs < 100,000 /s');
  });
});

test.describe('SF36: a row that decided nothing draws its Per instance limits muted', () => {
  test('[U] SF36: a LOAD NOT ACHIEVED row\'s Per instance lines are muted, a slope\'s included, and a judged row\'s are not', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // needs-load decided nothing (its load rule FAILED): its bar past a full-strength fail line
    // read as a breach the run never earned. Value over time already muted it (F4).
    expect((await rowCells(page, 'needs-load'))![0]).toBe('LOAD NOT ACHIEVED');
    await openRow(page, 'needs-load');
    const plain = (await instanceChart(page, 'needs-load'))!;
    expect(plain.lines).toEqual([1]);
    expect(plain.opacities).toEqual([0.4]);
    // From Batch 3 a slope draws its bands here too — the same row, as a slope, the same mute.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('needs-load'));
      W.set('kind', 'slope');
      W.set('fail.value', '-150');
      W.applyEditor();
    });
    expect((await rowCells(page, 'needs-load'))![0]).toBe('LOAD NOT ACHIEVED');
    const slope = (await instanceChart(page, 'needs-load'))!;
    expect(slope.name).toBe('%/min');
    expect(slope.lines).toEqual([-150]);
    expect(slope.opacities).toEqual([0.4]);
    // A row that judged keeps its lines at full strength.
    await page.click('.wir-kpi-name:text-is("needs-load")');
    await openRow(page, 'lag-spike');
    expect((await instanceChart(page, 'lag-spike'))!.opacities).toEqual([1, 1]);
  });

  test('[U] SF16/SF36: a ratio whose divisor gave no number draws its bars undivided, with no band, on the undivided unit', async ({ page }) => {
    await loadClean(page, 'E26');
    // A divideBy naming no declared rule, as a file can hold it (the editor offers only declared
    // ones): INVALID, and nothing to divide by. instanceFrame's no-divisor arm — never divided by
    // null, and no line in ratio units over undivided bars.
    await addAsserts(page, [
      { id: 'slope-no-divisor', component: 'db', query: 'cpu', unit: '%', reduce: { kind: 'slope' },
        divideBy: 'load:missing', fail: { gt: 1 } },
      { id: 'slope-plain', component: 'db', query: 'cpu', unit: '%', reduce: { kind: 'slope' }, fail: { gt: 1 } },
    ]);
    await switchTab(page, 'server');
    expect((await rowCells(page, 'slope-no-divisor'))![0]).toBe('INVALID');
    await openRow(page, 'slope-no-divisor');
    const inst = (await instanceChart(page, 'slope-no-divisor'))!;
    expect(inst.name).toBe('%/min');
    expect(inst.lines).toEqual([]);
    const values = inst.values as (number | null)[];
    expect(values.length).toBeGreaterThan(0);
    expect(values.every((v) => v !== null && isFinite(v))).toBe(true);
    // Undivided: the same bars as the same slope with no divideBy at all.
    await page.click('.wir-kpi-name:text-is("slope-no-divisor")');
    await openRow(page, 'slope-plain');
    expect((await instanceChart(page, 'slope-plain'))!.values).toEqual(values);
  });
});

test.describe('SF17: every reader gets one spelling of a number', () => {
  test.use({ locale: 'de-DE' });

  test('[U] SF17: in a German browser both tabs write "," groups and "." decimals, as the cards beside them do', async ({ page }) => {
    await loadClean(page, 'E26');
    // Not vacuous: this browser really does write German digits by default.
    expect(await page.evaluate(() => (1234.5).toLocaleString())).toBe('1.234,5');
    // The Thymeleaf card is FormatUtil's, US-fixed; the client card beside it now agrees.
    await expect(page.locator('#gateStrip .wir-breach', { hasText: 'load:unreachable' }))
      .toContainText('throughput 24.76 /s vs < 100,000 /s');
    await switchTab(page, 'server');
    // The board read "fail > 10.000 %" — ten thousand in German — beside an editor box holding
    // "10000", and "2,24" one row from Java's sentences in ".".
    const cells = (await rowCells(page, 'cpu-per-rps'))!;
    expect(cells[2]).toMatch(/^\d+\.\d+ % ÷ load:throughput$/);
    expect(cells[3]).toBe('fail > 10,000 % ÷ load:throughput');
  });
});
