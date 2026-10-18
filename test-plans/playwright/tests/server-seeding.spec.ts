import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Seeding and chart authoring (M7b).
 *
 * Two ways into a KPI that do not involve typing a threshold into a form: derive one from what
 * a series actually did, or drag it off the chart. Both produce a real declaration in the file;
 * neither is allowed to produce a check that looks stronger than it is.
 */

async function openServer(page: any) {
  await loadClean(page, 'E26');
  await switchTab(page, 'server');
}

function rowOf(page: any, id: string) {
  return page.locator('#serverKpiTable tbody tr.wir-kpi-row')
    .filter({ has: page.locator(`.wir-kpi-name:text-is("${id}")`) }).first();
}

test.describe('Generate from this run', () => {
  test('[R] the button is absent when every collected series already has a KPI',
    async ({ page }) => {
      await openServer(page);
      // E26 declares one for each. A button that produces nothing teaches the reader it is
      // broken, so it is not offered.
      await expect(page.locator('.wir-server-actions')).not.toContainText('Generate from this run');
    });

  test('[U] an unclaimed series becomes a KPI seeded from what it actually did',
    async ({ page }) => {
      await openServer(page);

      // Free the `lag` series by removing every KPI that claims it. A series is its component and
      // its query (backends plan §8b), and E26 collects lag for kafka and for db: two seeds.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
      });
      await expect(page.locator('.wir-server-actions')).toContainText('Generate from this run (2)');

      await page.evaluate(() => (window as any).WIR.serverKpis.generate());

      const seeded = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .filter((k: any) => /seeded from/.test(k.description || '')));

      expect(seeded.map((k: any) => [k.component, k.query]).sort()).toEqual([['db', 'lag'], ['kafka', 'lag']]);
      // 1.5x the observed maximum of 900: loose enough not to fail on the run it came from,
      // tight enough to be worth tightening.
      expect(seeded[0].fail).toEqual({ gt: 1350 });
      // ...and it SAYS it was derived, because a row reading PASS that nobody chose is
      // otherwise indistinguishable from one that was.
      expect(seeded[0].description).toContain('review this threshold');
    });

  test('[U] SF26: a long custom query wraps in the review, and its checkbox is not named by all of it',
    async ({ page }) => {
      await page.setViewportSize({ width: 1024, height: 800 });
      await openServer(page);
      // No committed fixture collects a long query, so E26's kafka lag series is collected once
      // more under one. A ~140-character {...} selector is one unbreakable token: at 1024 px it
      // pushed the review's Fail column out of the slide-over (body 994 wide in 941).
      const long = 'max by (pod) (container_memory_working_set_bytes{namespace="checkout-production",'
        + 'container="payments-settlement-worker",pod=~"payments-settlement-worker-[a-z0-9]+-[a-z0-9]+",image!=""})';
      await page.evaluate((q) => {
        const W = window as any;
        const d = JSON.parse(JSON.stringify(W.chartData));
        const lag = d.serverMetrics.series.find((s: any) => s.component === 'kafka' && s.query === 'lag');
        d.serverMetrics.series.push(Object.assign(JSON.parse(JSON.stringify(lag)), { query: q }));
        W.WIR.serverKpis.init(d);
      }, long);
      await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
      const row = page.locator('#serverDialog .wir-seed-review tbody tr', { has: page.locator('code') });
      await expect(row).toHaveCount(1);
      await expect(row.locator('code')).toHaveText(long);
      const fit = await page.evaluate(() => {
        const body = document.querySelector('#serverDialog .wir-slideover-body') as HTMLElement;
        return { scroll: body.scrollWidth, client: body.clientWidth };
      });
      expect(fit.scroll, 'the review scrolls sideways').toBeLessThanOrEqual(fit.client);
      await expect(row.locator('input[type="checkbox"]')).toHaveAttribute('aria-label', 'Add ' + long.slice(0, 59) + '…');
    });

  test('[U] a seeded KPI is judged immediately — it is not "needs re-collection"',
    async ({ page }) => {
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
        W.generate();
      });

      // The staleness question is about the PAYLOAD, not the edit history. A KPI seeded from a
      // collected series was never declared and is perfectly answerable — asking "was this
      // declared before" instead marked it NEEDS RE-COLLECTION against the very series it was
      // derived from.
      await expect(rowOf(page, 'lag').locator('.wir-chip')).toHaveText('PASS');
      await expect(rowOf(page, 'lag').locator('td').nth(2)).toContainText('900');
    });

  test('[R] with no component to put it in, nothing is offered (R1-9)', async ({ page }) => {
    // R1-9 was a series on a source the file no longer declared: Generate seeded asserts naming an
    // unknown source — INVALID_RULE at the next run, a config error, exit 1. A file names no
    // source since C8; what a seed needs now is a component to belong to, and with none declared
    // every seed would be the same config error ("names no component").
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.components = {};
      doc.serverMetrics.asserts = [];
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await expect(page.locator('#serverBoard')).toContainText('declare no server asserts');
    // No button, whether the toolbar is drawn or not.
    await expect(page.locator('#tab-server button', { hasText: 'Generate from this run' })).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).WIR.serverKpis._seedable().length)).toBe(0);
  });

  test('[U] a negative series seeds a KPI that PASSes the run it came from (C-8)', async ({ page }) => {
    // 1.5x the observed maximum moves a negative value DOWN: a series peaking at -120 seeded
    // `fail: {gt: -180}`, which -120 breaches — a FAIL on the very run it claims to pass.
    await openServer(page);
    const observed = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const lag = W._state.metrics.series.find((s: any) => s.query === 'lag');
      lag.instances.forEach((i: any) => i.points.forEach((p: any) => { p[1] = -p[1]; }));
      ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
      W.generate();
      return -120;   // the least negative point inside the judged window
    });
    const seeded = await page.evaluate(() =>
      JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
        .find((k: any) => k.query === 'lag'));
    expect(seeded.fail.gt, 'the seed fires on the value it was taken from').toBeGreaterThan(observed);
    expect(seeded.fail).toEqual({ gt: -60 });
    await expect(rowOf(page, 'lag').locator('.wir-chip')).toHaveText('PASS');
  });

  test('[U] a capped series seeds from the true maximum, not the page\'s thinned copy (C-8)',
    async ({ page }) => {
      // E26's `many` carries 21 instances against a cap of 20. The writer now carries the maximum
      // over all of them (judgedMax); the page's copy may not hold it — here it is removed, as a
      // thinned point or a dropped pod would be — and the seed must not be taken from what is left.
      await openServer(page);
      const truth = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const many = W._state.metrics.series.find((s: any) => s.query === 'many');
        const judgedMax = many.judgedMax;
        many.instances = many.instances.filter((i: any) =>
          !i.points.some((p: any) => p[1] >= judgedMax));
        W.remove(W._keyOf('many-instances'));
        W.generate();
        return judgedMax;
      });
      expect(truth, 'the writer carries the true maximum of a capped series').toBe(32);
      const seeded = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.query === 'many'));
      expect(seeded.fail).toEqual({ gt: 48 });
    });

  test('[U] SF12: in the review, a seed whose only band is erased or blank is refused, never written as fail > 0',
    async ({ page }) => {
      // A custom seed has one band, its fail. Erased, the seed would judge nothing (KpiUsability
      // refuses a band-less assert, exit 1 next run); one space used to be written as gt 0.
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
        W.reviewSeeds();
      });
      const review = page.locator('#serverDialog .wir-seed-review');
      const add = page.locator('#serverAddSeedsBtn');
      const problem = page.locator('#serverDialog .wir-slideover-foot .wir-problem');
      await expect(review.locator('[data-wir-dlg="seedfail:0"]')).toHaveValue('1350');
      // The review's own name, the button's (Q11, U8h): never "Generate from sources".
      await expect(page.locator('#serverDialog .wir-slideover-head strong')).toHaveText('Generate from this run — review');
      for (const blank of ['', '   ']) {
        await review.locator('[data-wir-dlg="seedfail:0"]').fill(blank);
        await expect(problem, JSON.stringify(blank)).toHaveText(
          'A seed with no fail value and no warn band would judge nothing — give it a fail value, or untick it.');
        await expect(add, JSON.stringify(blank)).toBeDisabled();
      }
      await review.locator('[data-wir-dlg="seedfail:0"]').fill('9oo');
      await expect(problem).toHaveText('A fail value is not a number.');
      await expect(add).toBeDisabled();
      // Unticking it is how a seed is skipped.
      await review.locator('[data-wir-dlg="seed:0"]').uncheck();
      await expect(problem).toHaveCount(0);
      await expect(add).toHaveText('Add 1 server assert');
      await page.click('#serverAddSeedsBtn');
      const seeded = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .filter((k: any) => /seeded from/.test(k.description || '')));
      expect(seeded.map((k: any) => k.fail)).toEqual([{ gt: 1350 }]);
    });

  test('[U] editing a seeded KPI to an uncollected query still withholds the verdict',
    async ({ page }) => {
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
        W.generate();
        W.edit(W._keyOf('lag'));
        W.set('query', 'not_a_real_query');
        W.applyEditor();
      });
      await expect(rowOf(page, 'lag').locator('.wir-chip')).toHaveText('NEEDS RE-COLLECTION');
    });

  test('[U] two series whose queries slug alike are seeded under two ids, through generate() (R3-8)',
    async ({ page }) => {
      // `cpu{a}` and `cpu.a` both slug to "cpu-a". The ids were checked against the declared asserts
      // only, so one Generate pass seeded both as "cpu-a" — one name for two rows, in serverAsserts[]
      // and JUnit alike. Pinned on the button's own path: _idFor alone does not show generate()
      // hands it the batch.
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const lag = W._state.metrics.series.find((s: any) => s.query === 'lag');
        ['cpu{a}', 'cpu.a'].forEach((q) => {
          const copy = JSON.parse(JSON.stringify(lag));
          copy.query = q;
          copy.resolvedQuery = q;
          W._state.metrics.series.push(copy);
        });
        W.rerender();
      });
      await expect(page.locator('.wir-server-actions')).toContainText('Generate from this run (2)');
      await page.evaluate(() => (window as any).WIR.serverKpis.generate());
      const seeded = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .filter((k: any) => /seeded from/.test(k.description || '')).map((k: any) => [k.id, k.query]));
      expect(seeded).toEqual([['cpu-a', 'cpu{a}'], ['cpu-a-2', 'cpu.a']]);
    });

  test('[U] the toolbar says why nothing can be generated when the run-level requiresLoad failed (review:D)',
    async ({ page }) => {
      // Every seed inherits serverMetrics.requiresLoad. With that load rule FAILed, each would read
      // LOAD NOT ACHIEVED and decide nothing, so none is offered — and the button used to vanish
      // with no word, on the very run a reader would most want to know why.
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.requiresLoad = 'load:unreachable';
        doc.serverMetrics.asserts = doc.serverMetrics.asserts.filter((k: any) => k.query !== 'lag');
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      const actions = page.locator('.wir-server-actions');
      await expect(actions).not.toContainText('Generate from this run (');
      // "Generate from this run" (Q11, U8h) — it said "from sources", which a file has not had since C8.
      await expect(actions.locator('.wir-seed-blocked')).toContainText(
        "Generate from this run is unavailable: serverMetrics.requiresLoad did not hold — "
        + "load rule 'load:unreachable' FAILED");
      await expect(actions.locator('.wir-seed-blocked')).toContainText('LOAD NOT ACHIEVED');

      // The load rule retuned to what the run achieved: the seed is offered again, and the note goes.
      await switchTab(page, 'asserts');
      await page.evaluate(() => (window as any).WIR.asserts._retune('load:unreachable', 10, 'fail'));
      await switchTab(page, 'server');
      // lag, once per component that collected it (backends plan §8b).
      await expect(actions).toContainText('Generate from this run (2)');
      await expect(actions.locator('.wir-seed-blocked')).toHaveCount(0);
    });
});

test.describe('Retuning from the chart', () => {
  test('[U] an open chart carries a draggable handle per band', async ({ page }) => {
    await openServer(page);
    await page.click('.wir-kpi-name:text-is("lag-spike")');
    await page.waitForTimeout(400);

    const { handles, key, grid } = await page.evaluate(() => {
      const el = document.querySelector('.wir-server-chart')!;
      const inst = (window as any).echarts.getInstanceByDom(el);
      const graphic = (inst.getOption().graphic || [])[0];
      const rect = inst.getModel().getComponent('grid', 0).coordinateSystem.getRect();
      return {
        handles: ((graphic && graphic.elements) || []).map((e: any) => {
          const y = e.position ? e.position[1] : e.y;
          // What a drag released right here would commit: ondragend reads convertFromPixel.
          return { id: e.id, y, reads: inst.convertFromPixel({ gridIndex: 0 }, [0, y])[1] };
        }),
        key: (window as any).WIR.serverKpis._keyOf('lag-spike'),
        grid: { top: rect.y, bottom: rect.y + rect.height },
      };
    });

    // The kind is in the id because a KPI has two bands and ECharts keys graphic elements by
    // id — without it the warn handle would REPLACE the fail handle rather than sit beside it.
    // The KPI part is the row KEY, not the id: two KPIs may share an id (M7c group C).
    expect(handles.map((h) => h.id).sort())
      .toEqual([`wir-server-handle-${key}-fail`, `wir-server-handle-${key}-warn`]);
    // ...and each sits ON its own line, inside the plot (R3-18): an id is no evidence that the
    // handle is where a reader can grab it, or that releasing it unmoved leaves the band alone.
    for (const [kind, value] of [['warn', 300], ['fail', 500]] as const) {
      const h = handles.find((x) => x.id.endsWith('-' + kind))!;
      expect(h.y, `${kind} handle inside the grid`).toBeGreaterThanOrEqual(grid.top);
      expect(h.y, `${kind} handle inside the grid`).toBeLessThanOrEqual(grid.bottom);
      expect(Math.abs(h.reads - value), `${kind} handle reads ${h.reads}`).toBeLessThan(value * 0.01);
    }
  });

  test('[U] a seeded assert can be dragged: its band and handle are on the plot (R3-3)',
    async ({ page }) => {
      await openServer(page);
      // Generate seeds the fail band ABOVE the observed maximum, by construction — and the axis
      // was scaled to the data alone, so every seeded assert's line and handle sat above the
      // canvas. The workflow M7b promised ("loose enough not to fail, tight enough to be worth
      // tightening") could not be performed from the chart at all.
      const key = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained'].forEach((id) => W.remove(W._keyOf(id)));
        W.generate();
        const k = W._keyOf('lag');
        W.toggle(k);
        return k;
      });
      await expect.poll(() => page.evaluate((k) => {
        const el = document.getElementById('serverChart-' + k);
        return !!(el && (window as any).echarts.getInstanceByDom(el));
      }, key)).toBe(true);

      const c = await page.evaluate((k) => {
        const el = document.getElementById('serverChart-' + k)!;
        const inst = (window as any).echarts.getInstanceByDom(el);
        const rect = inst.getModel().getComponent('grid', 0).coordinateSystem.getRect();
        const graphic = (inst.getOption().graphic || [])[0];
        return {
          top: rect.y,
          bottom: rect.y + rect.height,
          yMax: inst.getModel().getComponent('yAxis', 0).axis.scale.getExtent()[1],
          lines: inst.getOption().series[0].markLine.data.map((d: any) => d.yAxis),
          handles: ((graphic && graphic.elements) || []).map((e: any) => (e.position ? e.position[1] : e.y)),
        };
      }, key);
      expect(c.lines).toEqual([1350]);
      expect(c.yMax).toBeGreaterThanOrEqual(1350);
      expect(c.handles).toHaveLength(1);
      expect(c.handles[0]).toBeGreaterThanOrEqual(c.top);
      expect(c.handles[0]).toBeLessThanOrEqual(c.bottom);
    });

  test('[U] REV-15: a legend click that rescales the axis puts the handle back on its band, '
    + 'and hiding the first instance hides it', async ({ page }) => {
      await openServer(page);
      // cpu-headroom is kafka's cpu: instance a (40-46 %), which carries the band lines, and the
      // taller b (61-65 %). Its band, gt 95, is above the data, so the axis is bounded to keep it on
      // the plot (R3-3) and no legend click rescales it. Dragged to 45, inside the data, the axis is
      // ECharts' own again, and hiding b takes it from about 0..70 to 0..50.
      const key = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W._commitDrag(W._keyOf('cpu-headroom'), 'fail', 45);
        const k = W._keyOf('cpu-headroom');
        W.toggle(k);
        return k;
      });
      const id = `wir-server-handle-${key}-fail`;
      // Each handle's anchor (what a drag released there reads) beside the band's line as the axis
      // now draws it. The watch is bound where the page builds the chart, initChartFor, which only
      // a real row can reach: js-unit's server-chart.test.js calls it by hand.
      const read = () => page.evaluate((k) => {
        const el = document.getElementById('serverChart-' + k);
        const inst = el && (window as any).echarts.getInstanceByDom(el);
        if (!inst) return null;
        const graphic = (inst.getOption().graphic || [])[0];
        const handles: Record<string, number> = {};
        ((graphic && graphic.elements) || []).filter((e: any) => /^wir-server-handle-/.test(e.id))
          .forEach((e: any) => { handles[e.id] = e.position ? e.position[1] : e.y; });
        return {
          names: inst.getOption().series.map((s: any) => s.name) as string[],
          handles,
          band: inst.convertToPixel({ gridIndex: 0 }, [0, 45])[1] as number,
        };
      }, key);
      const legend = (name: string) => page.evaluate(({ k, n }) => {
        const inst = (window as any).echarts.getInstanceByDom(document.getElementById('serverChart-' + k));
        inst.dispatchAction({ type: 'legendToggleSelect', name: n });
      }, { k: key, n: name });

      await expect.poll(async () => Object.keys((await read())?.handles || {})).toEqual([id]);
      const shown = (await read())!;
      expect(shown.names).toHaveLength(2);
      expect(shown.handles[id]).toBeCloseTo(shown.band, 3);

      // The tall instance hidden: the axis rescales, and the handle moves with its line.
      await legend(shown.names[1]);
      const rescaled = (await read())!;
      expect(Math.abs(rescaled.band - shown.band), 'precondition: hiding b rescaled the axis')
        .toBeGreaterThan(20);
      expect(Object.keys(rescaled.handles)).toEqual([id]);
      expect(rescaled.handles[id]).toBeCloseTo(rescaled.band, 3);

      // The first instance hidden: the band line was its markLine and went with it, and so does
      // the handle. Shown again, the handle is back on the line.
      await legend(shown.names[0]);
      expect((await read())!.handles).toEqual({});
      await legend(shown.names[0]);
      const back = (await read())!;
      expect(Object.keys(back.handles)).toEqual([id]);
      expect(back.handles[id]).toBeCloseTo(back.band, 3);
    });

  test('[U] a dragged threshold becomes the declared one, rounded and re-judged',
    async ({ page }) => {
      await openServer(page);
      await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('FAIL');

      // Drive the commit directly rather than synthesising a pointer gesture: what is under
      // test is that a dragged value reaches the document and re-decides, not ECharts' drag.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W._commitDrag(W._keyOf('lag-spike'), 'fail', 5000.28374);
      });

      const written = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.id === 'lag-spike').fail);

      // Rounded: a threshold read off a pixel is not precise to fourteen decimal places, and
      // writing 5000.28374 into the file makes the next reader think it was chosen.
      expect(written).toEqual({ gt: 5000 });
      await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('WARN');
    });
});

test.describe('F2: a proposal stays inside a percentage', () => {
  test('[U] Generate over E27\'s 68 % peak proposes warn > 76 % / fail > 84 %, never past 100, and Add writes them',
    async ({ page }) => {
      // E27's connections.used.pct peaks at 68 % (pg-2). Headroom taken from the run alone seeded
      // fail > 102 %, a band the metric cannot reach, on a row that then reads PASS on every run.
      // postgres.json declares the metric's ceiling of 100, and the headroom is capped at the same
      // share of the room left below it: 68 + 32/2 and 68 + 32/4.
      await loadClean(page, 'E27');
      await switchTab(page, 'server');
      // E27 asserts it, and an asserted series is never seeded: free it first.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.remove(W._keyOf('orders-db-connections-used-pct'));
      });
      await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
      const row = page.locator('#serverDialog .wir-seed-review tbody tr', { hasText: 'Connections used (%)' });
      await expect(row).toHaveCount(1);
      await expect(row.locator('td').nth(3)).toHaveText('warn > 76 %');
      await expect(row.locator('[data-wir-dlg^="seedfail:"]')).toHaveValue('84');
      await expect(row.locator('.wir-chip')).toHaveText('PASS');

      await page.click('#serverAddSeedsBtn');
      const written = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.metric === 'connections.used.pct'));
      expect({ component: written.component, metric: written.metric, warn: written.warn, fail: written.fail })
        .toEqual({ component: 'orders-db', metric: 'connections.used.pct', warn: { gt: 76 }, fail: { gt: 84 } });
    });
});
