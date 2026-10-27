import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * The Server tab's board as redesigned (plans/report-design-and-bugs-2026-10.md §3, step 4a):
 * one card per component, rows closed until asked, two panels in an open row, one filter row,
 * the document's Download and unsaved pill on this tab (U1), and the client filter bar hidden
 * here (D4). E26 has two components, kafka and db.
 */

test.describe('Server tab — the component board', () => {
  test('[R] one card per component, in declaration order, each with its own rows and + metric', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const cards = page.locator('.wir-component-card');
    await expect(cards).toHaveCount(2);
    expect(await cards.evaluateAll((els) => els.map((e) => e.getAttribute('data-component'))))
      .toEqual(['kafka', 'db']);
    await expect(cards.nth(0).locator('.wir-component-head')).toContainText('Custom · Kafka');
    await expect(cards.nth(0).locator('.wir-component-count')).toHaveText('4 server asserts');
    await expect(cards.nth(1).locator('.wir-component-count')).toHaveText('8 server asserts');
    await expect(cards.nth(0).locator('tbody tr.wir-kpi-row')).toHaveCount(4);
    // Six columns of content and the actions: Source, Gate and Query left the board (design §3).
    const headers = await cards.nth(0).locator('thead th').allTextContents();
    expect(headers.map((h) => h.trim())).toEqual(['Status', 'Server assert', 'Actual', 'Criteria',
      'Coverage', 'Instances', '']);

    // + metric opens the editor on that component.
    await cards.nth(1).locator('.wir-component-add').click();
    await expect(page.locator('#serverEditor select[data-field="component"]')).toHaveValue('db');
  });

  test('[R] every row starts closed — a FAIL included — and opens to two panels', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await expect(page.locator('.wir-chart-row')).toHaveCount(0);
    const fail = page.locator('tr.wir-kpi-row', { has: page.locator('.wir-kpi-name:text-is("lag-spike")') });
    await expect(fail.locator('.wir-row-toggle')).toHaveAttribute('aria-expanded', 'false');

    await fail.locator('.wir-row-toggle').click();
    await expect(fail.locator('.wir-row-toggle')).toHaveAttribute('aria-expanded', 'true');
    const panels = page.locator('.wir-chart-row .wir-panel-title');
    await expect(panels).toHaveText(['Value over time', 'Per instance']);
    // The per-instance panel draws one bar per instance, the reducer's value for each.
    const bars = await page.evaluate(() => {
      const el = document.querySelector('.wir-instance-chart')!;
      const inst = (window as any).echarts.getInstanceByDom(el);
      const opt = inst.getOption();
      return { names: opt.yAxis[0].data, values: opt.series[0].data };
    });
    expect(bars.names).toEqual(['instance=a']);
    expect(bars.values).toEqual([900]);
  });

  test('[R] C1: an instance cell that already reads as a label is shown bare, not as instance=<label>', async ({ page }) => {
    // A CSV's instance cell becomes the label {instance: <cell>}. Petstore's exporter wrote the
    // series' own labels into it, so the CSV path read "instance=routeId=catalog" where the replay of
    // the same run read "routeId=catalog", and the doubled prefix took the Per instance chart's width.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const k = d.serverKpis.find((x: any) => x.id === 'cpu-headroom');
      const s = d.serverMetrics.series.find((x: any) => x.query === k.query);
      s.instances[0].labels = { instance: 'routeId=catalog' };
      s.instances[1].labels = { instance: 'plain' };
      W.WIR.serverKpis.init(d);
      const S = W.WIR.serverKpis; S.toggle(S._keyOf('cpu-headroom'));
    });
    await expect.poll(() => page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      return !!(window as any).echarts.getInstanceByDom(document.getElementById('serverInstances-' + W._keyOf('cpu-headroom'))!);
    })).toBe(true);
    const named = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const key = W._keyOf('cpu-headroom');
      const per = (window as any).echarts.getInstanceByDom(document.getElementById('serverInstances-' + key)!).getOption();
      const time = (window as any).echarts.getInstanceByDom(document.getElementById('serverChart-' + key)!).getOption();
      return { bars: per.yAxis[0].data, lines: (time.series as any[]).map((s) => s.name).filter((n) => n) };
    });
    expect(named.bars).toEqual(['routeId=catalog', 'instance=plain']);
    expect(named.lines).toEqual(expect.arrayContaining(['routeId=catalog', 'instance=plain']));
  });

  test('[R] 5.2: instances are named by the labels that tell them apart, as Grafana\'s Auto legend names series', async ({ page }) => {
    // Petstore F2's app-pods rows named two catalog pods "container=catalog, n…" twice: every label
    // was written, the shared namespace and container first, and the pod that tells them apart was cut.
    // Grafana's legend Auto mode writes only the labels whose values differ; one series keeps them all.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const series = (id: string) => {
        const k = d.serverKpis.find((x: any) => x.id === id);
        return d.serverMetrics.series.find((x: any) => x.query === k.query);
      };
      const two = series('cpu-headroom');
      two.instances[0].labels = { namespace: 'petstorems', pod: 'catalog-a', container: 'catalog' };
      two.instances[1].labels = { namespace: 'petstorems', pod: 'catalog-b', container: 'catalog' };
      series('lag-spike').instances[0].labels = { pod: 'p', container: 'c' };
      W.WIR.serverKpis.init(d);
      const S = W.WIR.serverKpis;
      S.toggle(S._keyOf('cpu-headroom'));
      S.toggle(S._keyOf('lag-spike'));
    });
    const names = (id: string) => page.evaluate((kid) => {
      const W = (window as any).WIR.serverKpis;
      const key = W._keyOf(kid);
      const per = (window as any).echarts.getInstanceByDom(document.getElementById('serverInstances-' + key)!);
      const time = (window as any).echarts.getInstanceByDom(document.getElementById('serverChart-' + key)!);
      if (!per || !time) return null;
      return { bars: per.getOption().yAxis[0].data, lines: (time.getOption().series as any[]).map((s) => s.name).filter((n) => n) };
    }, id);
    await expect.poll(() => names('cpu-headroom')).not.toBeNull();
    const two = (await names('cpu-headroom'))!;
    expect([...two.bars].sort()).toEqual(['pod=catalog-a', 'pod=catalog-b']);
    expect(two.lines).toEqual(expect.arrayContaining(['pod=catalog-a', 'pod=catalog-b']));
    await expect.poll(() => names('lag-spike')).not.toBeNull();
    expect((await names('lag-spike'))!.bars).toEqual(['container=c, pod=p']);
  });

  test('[R] Expand all rows opens every row, and a second click closes them', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.click('#serverExpandAll');
    await expect(page.locator('tr.wir-kpi-row .wir-row-toggle[aria-expanded="true"]')).toHaveCount(12);
    await expect(page.locator('#serverExpandAll')).toHaveText('Collapse all rows');
    await page.click('#serverExpandAll');
    await expect(page.locator('.wir-chart-row')).toHaveCount(0);
  });

  test('[R] U1: the tab has the document\'s Download and unsaved pill — and no pill on an untouched page', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // The board rendered once before the document had its baseline, and read "1 unsaved change"
    // on a page nobody had touched until something else redrew it.
    await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);
    await expect(page.locator('#serverSaveBtn')).toHaveText('Download report-annotations.json');

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggleEnabled(W._keyOf('cpu-headroom'));
    });
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');

    const download = page.waitForEvent('download');
    await page.click('#serverSaveBtn');
    expect((await download).suggestedFilename()).toBe('report-annotations.json');
  });

  test('[R] D4: the client filter bar is hidden on the Server tab and back on the others', async ({ page }) => {
    await loadClean(page, 'E26');
    await expect(page.locator('#filterBar')).toBeVisible();
    await switchTab(page, 'server');
    await expect(page.locator('#filterBar')).toBeHidden();
    await expect(page.locator('#filterBarToggle')).toBeHidden();
    await switchTab(page, 'samplers');
    await expect(page.locator('#filterBar')).toBeVisible();
  });

  test('[R] the Collection is one line, open when the source did not answer in full', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const line = page.locator('.wir-collection-line');
    // SF46: the backend's type is not on the line ("metrics (csv)") - no provenance in the GUI.
    await expect(line).toContainText('Collection: metrics PARTIAL');
    await expect(line).not.toContainText('csv');
    // E26's source is PARTIAL, so the detail is open; an OK one stays a line.
    await expect(page.locator('details.wir-collection')).toHaveAttribute('open', '');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      d.serverMetrics.sources[0].status = 'OK';
      delete d.serverMetrics.sources[0].error;
      // A series with nothing usable also opens it (P3), so make every series usable too.
      d.serverMetrics.series.forEach((x: any) => { x.status = 'OK'; delete x.error; });
      W.WIR.serverKpis.init(d);
    });
    await expect(page.locator('details.wir-collection')).not.toHaveAttribute('open', '');
  });

  test('[R] P3: a series with no usable data opens the Collection and is listed with its reason', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      d.serverMetrics.sources[0].status = 'OK';
      delete d.serverMetrics.sources[0].error;
      d.serverMetrics.series.forEach((x: any) => { x.status = 'OK'; delete x.error; });
      d.serverMetrics.series[0].status = 'FAILED';
      d.serverMetrics.series[0].error = 'm.csv has 3 rows for app/cpu and none inside the run';
      W.WIR.serverKpis.init(d);
    });
    await expect(page.locator('details.wir-collection')).toHaveAttribute('open', '');
    await expect(page.locator('.wir-unusable-series li')).toHaveCount(1);
    await expect(page.locator('.wir-unusable-series li')).toContainText('none inside the run');
  });

  test('[R] C6: an unusable series no assert reads is listed, and does not open the Collection; an asserted one does', async ({ page }) => {
    // E27 collects every catalogue metric of its two components (D1), and asserts two of them. A
    // catalogue extra under the coverage floor is worth a line, not an open panel: on petstore F2
    // an INSUFFICIENT heap.live.growth nobody asserted opened it on every load.
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    const mutate = (asserted: boolean) => page.evaluate((assertedToo) => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      d.serverMetrics.sources.forEach((s: any) => { s.status = 'OK'; delete s.error; });
      d.serverMetrics.series.forEach((x: any) => { x.status = 'OK'; delete x.error; });
      d.serverMetrics.series.find((x: any) => x.component === 'orders-db' && x.metric === 'up').status = 'INSUFFICIENT';
      if (assertedToo) {
        d.serverMetrics.series.find((x: any) => x.component === 'orders-db'
          && x.metric === 'connections.used.pct').status = 'INSUFFICIENT';
      }
      W.WIR.serverKpis.init(d);
    }, asserted);

    await mutate(false);
    await expect(page.locator('details.wir-collection')).not.toHaveAttribute('open', '');
    const items = page.locator('.wir-unusable-series li');
    await expect(items).toHaveCount(1);
    await expect(items.first()).toContainText('orders-db/up INSUFFICIENT');
    await expect(items.first()).toContainText('(no assert reads it)');

    await mutate(true);
    await expect(page.locator('details.wir-collection')).toHaveAttribute('open', '');
    await expect(items).toHaveCount(2);
    // The one an assert reads first, and not marked.
    await expect(items.nth(0)).toContainText('orders-db/connections.used.pct INSUFFICIENT');
    await expect(items.nth(0)).not.toContainText('no assert reads it');
    await expect(items.nth(1)).toContainText('(no assert reads it)');
  });

  test('[U] SF26: a catalogue assert is named by its label, with its id the dim line under it — on the board and in the editor', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    const key = await page.evaluate(() => (window as any).WIR.serverKpis._keyOf('orders-db-connections-used-pct'));
    const row = page.locator(`tr.wir-kpi-row[data-kpi-key="${key}"]`);
    // It read "orders-db-connections-used-pct | derived id | Connections used (%)": the derived id
    // in bold, the metric's name as an afterthought. The id stays on screen — it is the JUnit
    // testcase name, what a CI log is searched for — and still says it is derived.
    await expect(row.locator('.wir-kpi-name')).toHaveText('Connections used (%)');
    await expect(row.locator('.wir-kpi-id')).toHaveText('orders-db-connections-used-pct · derived id');
    // The text box still finds it by either.
    await page.fill('.wir-server-search', 'orders-db-connections-used');
    await expect(page.locator('tr.wir-kpi-row')).toHaveCount(1);
    await page.fill('.wir-server-search', '');

    // The editor's title names it the same way ("Edit server assert — orders-db-connections-used-pct").
    await row.locator('button', { hasText: 'Edit' }).click();
    const head = page.locator('#serverEditor .wir-slideover-head');
    await expect(head.locator('strong')).toHaveText('Edit server assert — Connections used (%)');
    await expect(head.locator('.wir-kpi-id')).toHaveText('orders-db-connections-used-pct');
    // ...and the dialog is still named by its title.
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveAttribute('aria-labelledby', 'serverEditorTitle');
  });

  test('[U] SF26: a card header names its component\'s description once — the badge is the verdict', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    const head = page.locator('.wir-component-card[data-component="orders-db"] .wir-component-head');
    // "orders-db Postgres · Orders DB Orders DB PASS".
    await expect(head.locator('.wir-component-type')).toHaveText('Postgres · Orders DB');
    const badge = head.locator('.wir-gate-badge');
    await expect(badge).toHaveText('PASS');
    expect((await head.innerText()).split('Orders DB').length - 1, 'the description, once').toBe(1);
    // A click narrows the board to this card, so the button is named for the component.
    await expect(badge).toHaveAttribute('aria-label', 'Orders DB PASS — show only this component');
  });

  test('[U] SF24: a LOW COVERAGE row counts the instances the JSON and JUnit count', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // sparse-coverage was collected (6% of the window) and decided nothing: Coverage read "6%"
    // and Instances "—", while E26-report.json and JUnit said 1 instance.
    const cells = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const row = document.querySelector(`tr.wir-kpi-row[data-kpi-key="${W._keyOf('sparse-coverage')}"]`)!;
      return Array.from(row.children).map((td) => td.textContent!.trim());
    });
    expect(cells[0]).toContain('LOW COVERAGE');
    expect(cells[4]).toBe('6%');      // Coverage
    expect(cells[5]).toBe('1');       // Instances, as serverAsserts[].instances
    // A row that measured nothing still reads a dash in both (the typo-reducer pin, server-tab).
    const noValue = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const row = document.querySelector(`tr.wir-kpi-row[data-kpi-key="${W._keyOf('source-never-answered')}"]`)!;
      return [row.children[4].textContent!.trim(), row.children[5].textContent!.trim()];
    });
    expect(noValue).toEqual(['—', '—']);
  });
});

test.describe('Summary — the server card', () => {
  test('[R] the Summary tab carries a server card beside the Asserts card, worst first, with the way to the tab', async ({ page }) => {
    await loadClean(page, 'E26');
    const card = page.locator('#serverStrip .wir-server-strip');
    await expect(card).toBeVisible();
    await expect(card.locator('.wir-gate-label').first()).toHaveText('Server');
    // The verdict the run was gated on: the worst component (D24), as the header chip says it.
    await expect(card.locator('.wir-chip-lg')).toHaveText('FAIL');
    await expect(card.locator('.wir-gate-counts .wir-count')).toHaveText(['Kafka FAIL', 'DB WARN']);
    // Worst first — the FAIL leads. (FAIL ranks 0, and `rank || 99` once sorted it out of the three.)
    const rows = card.locator('.wir-gate-breach');
    await expect(rows).toHaveCount(3);
    // SF26 (moved deliberately from 'kafka · lag-spike'): a line names its component as the
    // count above it does — "Kafka", not the id — so one card names one component one way.
    await expect(rows.first()).toContainText('Kafka · lag-spike');
    await expect(rows.first()).toContainText('900 msgs vs > 500 msgs');
    await card.locator('#openServerBtn').click();
    await expect(page.locator('#tab-server')).toHaveClass(/\bactive\b/);
  });

  test('[R] SF22: the card\'s word is the build\'s serverVerdict, on both server fixtures', async ({ page }) => {
    for (const id of ['E26', 'E27']) {
      await loadClean(page, id);
      const verdict = await page.evaluate(() => (window as any).chartData.serverVerdict);
      expect(verdict, `${id} ships the fold's word`).toBeTruthy();
      await expect(page.locator('#serverStrip .wir-chip-lg'), id)
        .toHaveText(verdict.status.replace(/_/g, ' '));
    }
  });

  test('[U] SF22: the card prints the fold Java chose and never folds again — the asserts in no component included',
    async ({ page }) => {
      // E27 plus an assert naming the undeclared component 'cache', as Java ships that run (V4 of
      // the 2026-10-03 sweep): its components fold to PASS and WARN, but the reserved entry the
      // asserts in no component are counted under is NOT_EVALUATED, and that is serverStatus and
      // the chip's word. The card folded the declared components with a rank table of its own and
      // read WARN beside a NOT EVALUATED chip, on the very run that exits 1.
      await page.addInitScript(() => {
        let stored: any;
        Object.defineProperty(window, 'chartData', {
          configurable: true,
          get() { return stored; },
          set(value) {
            if (value && value.serverGates) {
              value.annotationsDocument.serverMetrics.asserts.push(
                { id: 'cache-lag', component: 'cache', query: 'lag', fail: { gt: 1 } });
              value.serverKpis.push({
                id: 'cache-lag', component: 'cache', query: 'lag', unit: '', reduce: { kind: 'max' },
                across: 'max', status: 'NOT_JUDGED', notJudged: 'INVALID_RULE',
                reason: "component 'cache' is not declared in serverMetrics.components",
                actual: null, coverage: null, instances: 0, fail: { gt: 1 },
              });
              const counts = { total: 1, disabled: 0, noMatch: 0, invalidRule: 1, loadNotAchieved: 0,
                noValue: 0, sourceFailed: 0, lowCoverage: 0 };
              value.serverVerdict = {
                status: 'NOT_EVALUATED', component: '(no gate)', label: '(no component)', others: 2,
                reserved: { id: '(no gate)', label: '(no component)',
                  // evaluated: 1, as Java ships it — an INVALID_RULE assert is counted as evaluated.
                  collectionId: value.serverGates[0].collectionId, status: 'NOT_EVALUATED', declared: 1,
                  evaluated: 1, passed: 0, warned: 0, failed: 0, notJudged: counts },
              };
            }
            stored = value;
          },
        });
      });
      await loadClean(page, 'E27');
      const card = page.locator('#serverStrip .wir-server-strip');
      await expect(card.locator('.wir-chip-lg')).toHaveText('NOT EVALUATED');
      // The declared components as the build counted them, then the entry the word came from,
      // named as the chip and the Server tab name it.
      await expect(card.locator('.wir-gate-counts .wir-count'))
        .toHaveText(['Orders DB PASS', 'Event bus WARN', '(no component) NOT EVALUATED']);
      await expect(card.locator('.wir-gate-breach', { hasText: 'cache-lag' }))
        .toContainText('(no component) · cache-lag');
    });

  test('[U] SF26: one name per assert on the card — a catalogue one by its label, its id the dim line under it', async ({ page }) => {
    await loadClean(page, 'E27');
    const card = page.locator('#serverStrip .wir-server-strip');
    await expect(card.locator('.wir-gate-counts .wir-count')).toHaveText(['Orders DB PASS', 'Event bus WARN']);
    // "events · events-consumer-lag-max": the component by its id beside a count naming it "Event
    // bus", and the metric never by name.
    const line = card.locator('.wir-gate-breach').first();
    await expect(line.locator('strong')).toHaveText('Event bus · Consumer lag, worst partition (msgs)');
    await expect(line.locator('.wir-kpi-id')).toHaveText('events-consumer-lag-max');
  });

  test('[R] a run that declared no server asserts has no server card', async ({ page }) => {
    await loadClean(page, 'E10');
    await expect(page.locator('.wir-server-strip')).toHaveCount(0);
  });
});

test.describe('Generate, with a review (U9d)', () => {
  test('[U] Generate opens a review; unticked seeds are not added, an edited band is', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
    const review = page.locator('#serverDialog .wir-seed-review');
    // Every collected catalogue series no assert judges whose seed passes this run. Since Q9 E27
    // carries database.size.growth, and pg-1's up = 0: up folds to its worst instance (SF2), so
    // the catalogue's fail < 1 FAILs this run, and a seed that does not pass its own run is no
    // seed — up is not offered.
    await expect(review.locator('tbody tr td:nth-child(3)')).toHaveText(['Longest open transaction (s)',
      'Cache hit ratio (%)', 'Database size growth (bytes/min)', 'Brokers in the cluster', 'Messages in (msg/s)']);
    // Nothing is added until Add.
    expect(await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts.length)).toBe(2);

    await review.locator('[data-wir-dlg="seed:2"]').uncheck();
    await review.locator('[data-wir-dlg="seedfail:0"]').fill('9');
    await expect(page.locator('#serverAddSeedsBtn')).toHaveText('Add 4 server asserts');
    await page.click('#serverAddSeedsBtn');

    const asserts = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts);
    expect(asserts.length).toBe(6);
    const added = asserts.slice(2);
    expect(added.map((k: any) => k.metric)).toEqual(['transaction.longest.seconds', 'cache.hit.pct', 'brokers', 'messages.in.rate']);
    expect(added[0].fail).toEqual({ gt: 9 });
    added.forEach((k: any) => expect(k.query).toBeUndefined());
  });

  test('[U] SF12/SF13: an erased or blank fail is no fail band, and each seed reads what the board will', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.click('.wir-server-actions .wir-btn:has-text("Generate from this run")');
    const review = page.locator('#serverDialog .wir-seed-review');
    // Seed 0 is Longest open transaction: warn > 3.6, fail > 4.3. Every seed passes its own run.
    await expect(review.locator('thead th').last()).toHaveText('Verdict');
    await expect(review.locator('tbody tr .wir-chip')).toHaveText(['PASS', 'PASS', 'PASS', 'PASS', 'PASS']);
    // A fail value under its run's own number: judged live, by the board's derivation.
    await review.locator('[data-wir-dlg="seedfail:0"]').fill('2');
    await expect(review.locator('tr[data-seed="0"] .wir-chip')).toHaveText('FAIL');
    // ...with the warning the editor would give the same assert: its warn > 3.6 can no longer fire.
    // Shown, never blocking.
    await expect(review.locator('tr[data-seed="0"] .wir-editor-warning'))
      .toHaveText([/^warn can never fire: fail > 2 s is tested first and already covers warn > 3\.6 s/]);
    await expect(review.locator('tr[data-seed="1"] .wir-editor-warning')).toHaveCount(0);
    await expect(page.locator('#serverAddSeedsBtn')).toBeEnabled();
    // Erased, the seeded fail used to be written anyway; one space wrote fail > 0. Both are no
    // fail band now — the seed keeps its warn, as Set thresholds would.
    await review.locator('[data-wir-dlg="seedfail:0"]').fill('');
    await review.locator('[data-wir-dlg="seedfail:1"]').fill(' ');
    for (const i of [2, 3, 4]) await review.locator(`[data-wir-dlg="seed:${i}"]`).uncheck();
    await expect(page.locator('#serverAddSeedsBtn')).toBeEnabled();
    await page.click('#serverAddSeedsBtn');
    const added = (await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts)).slice(2);
    expect(added.map((k: any) => [k.metric, k.warn, k.fail])).toEqual([
      ['transaction.longest.seconds', { gt: 3.6 }, undefined],
      ['cache.hit.pct', { lt: 73 }, undefined],
    ]);
  });
});

/** Value over time as drawn for one assert: its points, its first x, and its breach wash. */
async function overTime(page: Page, id: string) {
  return page.evaluate((assertId) => {
    const W = (window as any).WIR.serverKpis;
    const el = document.getElementById('serverChart-' + W._keyOf(assertId));
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!inst) return null;
    const s = inst.getOption().series[0];
    const areas = s.markArea && s.markArea.data
      ? s.markArea.data.map((a: any) => [a[0].xAxis, a[1].xAxis]) : [];
    return { points: s.data.length, firstX: s.data[0][0], areas };
  }, id);
}

test.describe('Server tab — Value over time has its own granularity (SF39, SF42)', () => {
  test('[U] SF39: a granularity set on the Timeline tab never re-buckets a server chart — a 10 s spike is not a 48 s wash',
    async ({ page }) => {
      // The client filter bar's granularity (and autoDetectGranularity, which sets it on every run
      // over five minutes) re-bucketed the server charts although D4 hides that bar here. At 10m,
      // E26's lag-spike was two points — one a bucket BEFORE the axis — and one red wash across
      // the whole 48 s window, for a breach of 10 s.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.evaluate(() => { const W = (window as any).WIR.serverKpis; W.toggle(W._keyOf('lag-spike')); });
      await expect.poll(() => overTime(page, 'lag-spike').then((c) => c && c.points)).toBe(49);
      const atStep = (await overTime(page, 'lag-spike'))!;
      expect(atStep.areas).toHaveLength(1);
      expect(atStep.areas[0][1] - atStep.areas[0][0], 'the stretch that breached, ten 1s steps').toBe(10000);

      // The bar on another tab, and the rebuild its change sets off for every chart on the page.
      await switchTab(page, 'timeline');
      await page.selectOption('#granularitySelect', '600000');
      await page.waitForTimeout(400);
      await switchTab(page, 'server');
      const after = (await overTime(page, 'lag-spike'))!;
      expect(after, 'still drawn at the collection step').toEqual(atStep);
      // At 10m the first point was a bucket ten minutes before the axis; at the step it is the
      // collector's own first point, one step outside the window at most.
      const w = await page.evaluate(() => (window as any).chartData.serverMetrics.window);
      expect(after.firstX, 'no bucket minutes before the axis').toBeGreaterThan(w.start - 60000);
      // ...and the panel says what it is drawn at.
      await expect(page.locator('.wir-server-granularity')).toHaveValue('0');
      await expect(page.locator('.wir-server-granularity option:checked')).toHaveText('Collection step (1s)');
    });

  test('[U] SF42: the panel\'s selector offers the step and the coarser rungs shorter than the window, and moves every open row',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.toggle(W._keyOf('lag-spike'));
        W.toggle(W._keyOf('lag-sustained'));
      });
      const selects = page.locator('.wir-chart-row .wir-server-granularity');
      await expect(selects).toHaveCount(2);
      // One per open row, on Value over time only: Per instance is one number per instance whatever
      // the bucket. The title stays the panel's name.
      await expect(page.locator('.wir-chart-row .wir-panel-title')).toHaveText(
        ['Value over time', 'Per instance', 'Value over time', 'Per instance']);
      // E26 is 48 s at a 1 s step: 1m and coarser would fold the run into one point.
      expect(await selects.first().locator('option').allTextContents())
        .toEqual(['Collection step (1s)', '5s', '10s', '30s']);
      await expect(selects.first()).toHaveAttribute('aria-label', 'Granularity of Value over time');
      await expect.poll(() => overTime(page, 'lag-sustained').then((c) => c && c.points)).toBe(49);

      // 48 s in 10 s buckets, plus the bucket of the collector's one point before the window:
      // exactly 6 — an upper bound let a chart bucketed coarser than the rung chosen pass.
      await selects.first().selectOption('10000');
      await expect.poll(() => overTime(page, 'lag-spike').then((c) => c && c.points)).toBe(6);
      await expect.poll(() => overTime(page, 'lag-sustained').then((c) => c && c.points)).toBe(6);
      await expect(selects.nth(1), 'one choice for every open row, as the Samplers tab\'s').toHaveValue('10000');
      // A closed select stepped with the arrow keys commits on every press: the select in hand is
      // never rebuilt under them (SF30's rule), so the next press still lands.
      await selects.first().focus();
      await page.keyboard.press('ArrowUp');
      await expect(selects.first()).toBeFocused();
      await expect(selects.first()).toHaveValue('5000');
      await expect.poll(() => overTime(page, 'lag-spike').then((c) => c && c.points)).toBe(11);
      await page.keyboard.press('ArrowUp');
      await expect(selects.first()).toBeFocused();
      await expect(selects.first()).toHaveValue('0');
      await expect.poll(() => overTime(page, 'lag-spike').then((c) => c && c.points)).toBe(49);
      // A row opened now draws at the choice in force, and a re-render keeps it.
      await selects.first().selectOption('30000');
      await page.evaluate(() => { const W = (window as any).WIR.serverKpis; W.toggle(W._keyOf('lag-p50-warns')); });
      await expect(page.locator('.wir-chart-row .wir-server-granularity')).toHaveCount(3);
      for (const s of await page.locator('.wir-chart-row .wir-server-granularity').all()) {
        await expect(s).toHaveValue('30000');
      }
      await expect.poll(() => overTime(page, 'lag-p50-warns').then((c) => c && c.points)).toBe(3);
    });
});

test.describe('Server tab — the filter row (SF40, SF41)', () => {
  const chips = (page: Page) => page.locator('.wir-server-filters .wir-filter-chip');

  test('[U] SF40: typing in the filter box re-counts the status chips and offers Clear filters, keeping the focus',
    async ({ page }) => {
      // U6's residue: the chips and Clear filters were rebuilt only by a full render, so "lag" left
      // three rows under "All (12)" with no Clear filters, and the next chip click counted them.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      const box = page.locator('.wir-server-search');
      await box.click();
      await page.keyboard.type('lag');
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(3);
      await expect(chips(page)).toHaveText(['All 3', 'FAIL 1', 'WARN 1', 'PASS 1']);
      await expect(page.locator('.wir-server-filters .wir-clear-filters')).toBeVisible();
      await expect(box).toBeFocused();
      await expect(box).toHaveValue('lag');

      // Nothing left: the chips say so too, beside the board's own way out.
      await page.keyboard.type('zz');
      await expect(chips(page)).toHaveText(['All 0']);
      await expect(box).toBeFocused();
      await page.locator('.wir-server-filters .wir-clear-filters').click();
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(12);
      await expect(chips(page).first()).toHaveText('All 12');
      await expect(page.locator('.wir-server-search')).toHaveValue('');
      await expect(page.locator('.wir-server-filters .wir-clear-filters')).toHaveCount(0);
    });

  test('[U] SF41: the status chips read as the Asserts tab\'s — "FAIL 1" — and say whether they are on, with no "Not judged ▾" on either tab',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // They read "All (12) | FAIL (1) | …" with no aria-pressed, beside the Asserts tab's "FAIL 1".
      const texts = await chips(page).allTextContents();
      expect(texts[0]).toBe('All 12');
      expect(texts).toContain('FAIL 1');
      for (const t of texts) expect(t, 'no parenthesised count').not.toMatch(/\(\d+\)/);
      expect(await chips(page).evaluateAll((els) => els.map((e) => e.getAttribute('aria-pressed'))))
        .toEqual(texts.map((_, i) => (i === 0 ? 'true' : 'false')));

      await chips(page).filter({ hasText: /^FAIL 1$/ }).click();
      await expect(chips(page).filter({ hasText: /^FAIL 1$/ })).toHaveAttribute('aria-pressed', 'true');
      await expect(chips(page).first()).toHaveAttribute('aria-pressed', 'false');
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(1);

      // Every NOT_JUDGED reason is still a chip of its own, on both tabs (Q12): §3's "Not judged ▾"
      // contradicted its own "shaped like the Asserts tab", which has no ▾.
      await chips(page).first().click();
      await expect(page.locator('.wir-server-filters')).not.toContainText('Not judged');
      await expect(page.locator('.wir-server-filters')).not.toContainText('▾');
      expect(texts).toEqual(expect.arrayContaining(['INVALID 1', 'SOURCE FAILED 1', 'LOW COVERAGE 1',
        'LOAD NOT ACHIEVED 1', 'UNMATCHED 1', 'DISABLED 1']));
      await switchTab(page, 'asserts');
      const asserts = await page.locator('#tab-asserts .wir-status-chip').allTextContents();
      expect(asserts.length).toBeGreaterThan(0);
      for (const t of asserts) expect(t).toMatch(/^[A-Z][A-Z ]* \d+$/);
      await expect(page.locator('#tab-asserts .wir-status-chips')).not.toContainText('Not judged');
    });

  test('[U] SF41: a component badge is a toggle in the same set, and says whether it is on', async ({ page }) => {
    // filterGate flips state.filters.gate as a chip flips the status, but the badge carried no
    // aria-pressed, and once on its name still said "— show only this component".
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const badge = page.locator('.wir-component-card[data-component="kafka"] .wir-gate-badge');
    await expect(badge).toHaveAttribute('aria-pressed', 'false');
    await expect(badge).toHaveAttribute('aria-label', /— show only this component$/);
    await badge.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#serverBoard .wir-component-card')).toHaveCount(1);
    await expect(badge).toHaveAttribute('aria-pressed', 'true');
    await expect(badge).toHaveAttribute('aria-label', /— show every component$/);
    await expect(badge).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(badge).toHaveAttribute('aria-pressed', 'false');
  });
});

test.describe('Server tab — SF46 residue', () => {
  test('[U] SF46: a row whose reducer did not parse prints no "? across max"', async ({ page }) => {
    // F4 took "judged: ? across max" off the chart note; 4a's reducer line put it back on the row.
    // The reason row under it already quotes what the file wrote ('maxx').
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const row = page.locator('tr.wir-kpi-row', { has: page.locator('.wir-kpi-name:text-is("typo-reducer")') });
    await expect(row.locator('.wir-chip')).toHaveText('INVALID');
    await expect(row.locator('.wir-kpi-reduce')).toHaveCount(0);
    await expect(row).not.toContainText('?');
    await expect(row.locator('xpath=following-sibling::tr[1]')).toContainText('maxx');
    // A reducer the assert chose and Java read is still said.
    const sustained = page.locator('tr.wir-kpi-row', { has: page.locator('.wir-kpi-name:text-is("lag-sustained")') });
    await expect(sustained.locator('.wir-kpi-reduce')).toContainText('across');
  });
});
