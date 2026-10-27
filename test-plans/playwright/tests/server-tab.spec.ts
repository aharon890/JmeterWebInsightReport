import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, loadDirty, switchTab } from './helpers';

/**
 * Everything a spec needs to know about one row's chart, read off the ECharts instance itself —
 * not the DOM, which says only that a canvas exists. Null when the row has no chart container.
 */
async function chartOf(page: Page, id: string) {
  return page.evaluate((kpiId) => {
    const W = (window as any).WIR.serverKpis;
    const key = W._keyOf(kpiId);
    const el = document.getElementById('serverChart-' + key);
    if (!el) return null;
    const inst = (window as any).echarts.getInstanceByDom(el);
    if (!inst) return { key, present: false, canvas: !!el.querySelector('canvas') };
    const opt = inst.getOption();
    const rect = inst.getModel().getComponent('grid', 0).coordinateSystem.getRect();
    const graphic = (opt.graphic || [])[0];
    // What the reader can see of the card: the scrolling wrapper's visible box, in page x (SF34).
    const wrap = el.closest('.table-wrapper') as HTMLElement | null;
    const wr = wrap ? wrap.getBoundingClientRect() : null;
    return {
      key,
      present: true,
      canvas: !!el.querySelector('canvas'),
      width: inst.getWidth(),
      left: el.getBoundingClientRect().left,
      visible: wr && wrap ? { left: wr.left, right: wr.left + wrap.clientWidth } : null,
      grid: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      yExtent: inst.getModel().getComponent('yAxis', 0).axis.scale.getExtent(),
      yName: opt.yAxis[0].name,
      handles: ((graphic && graphic.elements) || []).map((e: any) => ({
        id: e.id,
        x: e.position ? e.position[0] : e.x,
        y: e.position ? e.position[1] : e.y,
      })),
      // The colour ECharts resolved for series 0 — what its legend and tooltip swatch show.
      visual: (() => {
        const style = inst.getModel().getSeriesByIndex(0).getData().getVisual('style') || {};
        return style.fill || style.stroke;
      })(),
      series: (opt.series as any[]).map((s) => ({
        name: s.name,
        data: s.data,
        showSymbol: s.showSymbol,
        lineWidth: s.lineStyle ? s.lineStyle.width : undefined,
        color: s.lineStyle ? s.lineStyle.color : undefined,
        lines: s.markLine && s.markLine.data ? s.markLine.data.map((d: any) => d.yAxis) : [],
        areas: s.markArea && s.markArea.data
          ? s.markArea.data.map((a: any) => [a[0].xAxis, a[1].xAxis]) : [],
      })),
    };
  }, id);
}

async function openChart(page: Page, id: string) {
  await page.click(`.wir-kpi-name:text-is("${id}")`);
  await expect.poll(async () => (await chartOf(page, id))?.canvas ?? false).toBe(true);
}

/**
 * WCAG contrast of every drag handle's label on one chart, measured against what is actually
 * behind the text: the handle's own (translucent) fill laid over the chart's background — the
 * chart's backgroundColor, or the first opaque ancestor's when the chart is transparent.
 */
async function handleContrast(page: Page, elId: string, idPrefix: string) {
  return page.evaluate(([id, prefix]) => {
    const el = document.getElementById(id);
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!inst) return [];
    const parse = (c: any): number[] | null => {
      if (typeof c !== 'string') return null;
      const s = c.trim();
      let m = /^#([0-9a-f]{3})$/i.exec(s);
      if (m) return m[1].split('').map((h) => parseInt(h + h, 16)).concat(1);
      m = /^#([0-9a-f]{6})$/i.exec(s);
      if (m) return [0, 2, 4].map((i) => parseInt(m![1].substr(i, 2), 16)).concat(1);
      m = /^rgba?\(([^)]+)\)$/i.exec(s);
      if (m) {
        const p = m[1].split(',').map((x) => parseFloat(x));
        return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
      }
      return null;
    };
    const over = (top: number[], under: number[]) =>
      [0, 1, 2].map((i) => top[i] * top[3] + under[i] * (1 - top[3])).concat(1);
    const lin = (v: number) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    const lum = (c: number[]) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
    const ratio = (a: number[], b: number[]) => {
      const la = lum(a), lb = lum(b);
      return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    };
    let bg = parse(inst.getOption().backgroundColor);
    if (!bg || bg[3] === 0) {
      bg = null;
      for (let n: Element | null = el; n; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c && c[3] > 0) { bg = c; break; }
      }
    }
    const base = bg || [255, 255, 255, 1];
    const g = (inst.getOption().graphic || [])[0];
    return ((g && g.elements) || [])
      .filter((e: any) => typeof e.id === 'string' && e.id.indexOf(prefix) === 0)
      .map((e: any) => {
        const fill = parse(e.style && e.style.fill) || [0, 0, 0, 0];
        const behind = over(fill, base);
        const text = parse(e.textContent && e.textContent.style && e.textContent.style.fill) || [0, 0, 0, 1];
        return {
          id: e.id as string,
          text: e.textContent && e.textContent.style ? String(e.textContent.style.fill) : '(none)',
          behind: 'rgb(' + behind.slice(0, 3).map(Math.round).join(',') + ')',
          backgroundLuminance: lum(base),
          ratio: ratio(text, behind),
        };
      });
  }, [elId, idPrefix] as [string, string]);
}

/**
 * The Server tab (M5), against E26 — the fixture built from wholly committed inputs, so every
 * number, reason sentence and instance count below is a constant rather than something a
 * timer-driven run happened to produce this time.
 *
 * E26 renders PASS, WARN, FAIL and six of the seven NOT_JUDGED reasons in one report, across
 * two components. That coverage is the point of the fixture: a
 * spec that only ever sees a passing KPI cannot notice that a reason row stopped rendering.
 */

test.describe('Server tab — the board', () => {
  test('[R] D25: no reader of the Server tab or the header chip is shown the word KPI', async ({ page }) => {
    // "KPI" could as well describe a client metric, so the word a reader sees is "server
    // assert(s)" — on the board, the editor, the reason rows, the empty states and the chip. The
    // internal names (WIR.serverKpis, #serverKpiTable, .wir-kpi-row) are not text and stay.
    // Every state a reader can bring the tab into below, each swept: the text, and the
    // tooltips, labels and placeholders a reader also reads.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const KPI = /\bKPIs?\b/i;
    const visible = () => page.evaluate(() => {
      const tab = document.getElementById('tab-server')!;
      const chip = document.querySelector('.wir-server-chip') as HTMLElement | null;
      const attrs: string[] = [];
      [tab, chip].forEach((root) => {
        if (!root) return;
        root.querySelectorAll('[title], [aria-label], [placeholder]').forEach((el) => {
          ['title', 'aria-label', 'placeholder'].forEach((a) => {
            const v = el.getAttribute(a);
            if (v) attrs.push(v);
          });
        });
      });
      return { tab: tab.innerText, chip: chip ? chip.innerText : '', attrs: attrs.join('\n') };
    });
    const sweep = async (state: string) => {
      const v = await visible();
      expect(v.tab, `${state}: #tab-server says KPI`).not.toMatch(KPI);
      expect(v.chip, `${state}: the header chip says KPI`).not.toMatch(KPI);
      expect(v.attrs, `${state}: a tooltip, label or placeholder says KPI`).not.toMatch(KPI);
      return v;
    };

    const board = await sweep('the board');
    expect(board.tab, 'the sweep must see the board, not an empty pane').toContain('cpu-headroom');
    // innerText is what is painted, so the chip's text-transform makes it upper case.
    expect(board.chip.toLowerCase(), 'the sweep must see the chip').toContain('kafka fail');

    // Every reason row and every chart, open at once.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      ['cpu-headroom', 'lag-spike', 'many-instances', 'typo-reducer'].forEach((id) => W.toggle(W._keyOf(id)));
    });
    await sweep('every chart open');

    // A filter down to nothing, and the editor both new and editing.
    // kafka has no WARN — E26's only WARN is on db.
    await page.locator('.wir-component-card[data-component="kafka"] .wir-gate-badge').click();
    await page.locator('.wir-server-search').fill('no such server assert');
    await expect(page.locator('#serverBoard')).toContainText('No server assert matches these filters.');
    await sweep('filtered to nothing');
    await page.click('.wir-server-actions .wir-btn-primary');
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    const editor = await sweep('the new-assert editor');
    expect(editor.tab).toContain('New server assert');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.closeEditor();
      W.edit(W._keyOf('typo-reducer'));
    });
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    expect((await sweep('editing a refused assert')).tab).toContain('Edit server assert');

    // The states the rename touched that the sweep did not visit (review:R, M7d X): the editor's
    // own refusals, a stale and a withheld row with their reason rows, the retired-key editor and
    // its "Remove" button, a document with no block, and a refused payload.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.closeEditor();
      // Every filter off again (the box included): the edits below are read off the board.
      W.clearFilters();
      W.addKpi();
    });
    // An empty id is no refusal since SF27 (Java derives it): the new assert's first refusal is
    // its missing query — moved deliberately from "Give the server assert an id."
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('names no "query"');
    await sweep('the editor refusing an assert with no query');
    await page.evaluate(() => (window as any).WIR.serverKpis.set('id', 'lag-spike'));
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('Another server assert');
    await sweep('the editor refusing a taken id');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.closeEditor();
      W.edit(W._keyOf('lag-sustained'));
      W.set('query', 'not_collected_anywhere');
      W.applyEditor();
      W.edit(W._keyOf('many-instances'));
      W.set('fail.value', '20');
      W.applyEditor();
    });
    await expect(page.locator('#serverBoard')).toContainText('NEEDS RE-COLLECTION');
    await expect(page.locator('#serverBoard')).toContainText('NOT RE-JUDGED');
    await sweep('a stale row and a withheld row');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').sustainedFor = '5s';
      W.document.adopt(doc);
      W.serverKpis.reread();
      W.serverKpis.edit(W.serverKpis._keyOf('lag-spike'));
    });
    await expect(page.locator('#serverEditor')).toContainText('Remove "sustainedFor"');
    await sweep('the editor on an assert with a retired key');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.serverKpis.closeEditor();
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      delete doc.serverMetrics;
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    expect((await sweep('a document that dropped the block')).tab).toContain('declare no server asserts');

    // The interim report: declared, nothing collected (step 1 of the choreography).
    await page.evaluate(() => {
      const W = window as any;
      W.WIR.document.adopt(JSON.parse(JSON.stringify(W.globalChartData.annotationsDocument)));
      const d = { ...W.chartData };
      delete d.serverGates;
      delete d.serverKpis;
      delete d.serverMetrics;
      d.serverEvaluated = false;
      W.WIR.serverKpis.init(d);
    });
    expect((await sweep('the interim report')).tab).toContain('server assert(s) were declared');

    // ...and the same report given a payload that was refused.
    await page.evaluate(() => {
      const W = window as any;
      const d = { ...W.chartData };
      delete d.serverGates;
      delete d.serverKpis;
      delete d.serverMetrics;
      d.serverEvaluated = false;
      d.serverPayloadRefused = { reason: 'FOREIGN_RUN', detail: 'the payload belongs to another run' };
      W.WIR.serverKpis.init(d);
    });
    expect((await sweep('a refused payload')).tab).toContain('declared server assert(s) was judged');
  });

  test('[U] D25: a document that still says kpis is refused on the tab, kept as written, and never read', async ({ page }) => {
    // Nothing was released under the old key, so there is no migration. The page must say so the
    // moment such a file is opened — Java refuses it at the next run (a config-level ERROR, exit 1
    // under failonerror) — and must neither read it as the list nor rename it on a Save: the
    // first would judge on screen what the run will not judge at all, the second would hide the
    // refusal the next run is about to make.
    await loadClean(page, 'E26');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.kpis = doc.serverMetrics.asserts;
      delete doc.serverMetrics.asserts;
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    const board = page.locator('#serverBoard');
    await expect(board.locator('.wir-server-findings'))
      .toContainText('"kpis" is not a serverMetrics key — server asserts are declared under serverMetrics.asserts');
    await expect(board.locator('.wir-server-findings')).toContainText('Rename "kpis" to "asserts"');
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(0);
    const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics);
    expect(written.kpis, 'the old key rides a Save as written').toHaveLength(12);
    expect(written.asserts, 'and is not read as the list').toEqual([]);

    // Renamed by the author: the refusal goes, and the twelve are rows again.
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts = doc.serverMetrics.kpis;
      delete doc.serverMetrics.kpis;
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(12);
    await expect(board).not.toContainText('"kpis" is not a serverMetrics key');
  });

  test('[R] the tab is the 8th, after Asserts and before Notes', async ({ page }) => {
    await loadClean(page, 'E26');
    const order = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.tab-btn')).map((b) => b.getAttribute('data-tab')));
    expect(order.indexOf('server')).toBeGreaterThan(order.indexOf('asserts'));
    expect(order.indexOf('server')).toBeLessThan(order.indexOf('notes'));
  });

  test('[R] one badge per component, named by its description, and none says observe', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    const badges = page.locator('.wir-gate-badge');
    await expect(badges).toHaveCount(2);
    // SF26 (moved deliberately): the badge sits in its card's header, whose type line already
    // carries the description, so its text is the verdict and the description is its name.
    await expect(badges.nth(0)).toHaveText('FAIL');
    await expect(badges.nth(0)).toHaveAttribute('aria-label', /^Kafka FAIL/);
    await expect(badges.nth(1)).toHaveText('WARN');
    await expect(badges.nth(1)).toHaveAttribute('aria-label', /^DB WARN/);

    // C6: every component builds, so no badge may claim it cannot fail the build.
    await expect(page.locator('.wir-gate-badge .wir-observe')).toHaveCount(0);
  });

  test('[R] the header chip names the worst BUILDING gate and counts the rest', async ({ page }) => {
    await loadClean(page, 'E26');
    // D17. kafka FAILs and db only WARNs; the chip names the worst and counts the rest.
    const chip = page.locator('.wir-server-chip');
    await expect(chip).toBeVisible();
    await expect(chip).toContainText('Kafka FAIL');
    await expect(chip).toContainText('1 more');
    await expect(chip).toHaveClass(/badge-fail/);
  });

  test('[R] L3-6: the header chip is the way to the Server tab, from any tab', async ({ page }) => {
    // The chip names a gate and counts the rest - "1 more" is a pointer to the tab, and it was a
    // <span> nobody could follow. It is a button now, bound by report.js to the same switchTab
    // the tab bar uses (data-wir-tab, a listener - no inline handler for the sweep to excuse).
    await loadClean(page, 'E26');
    const chip = page.locator('.wir-server-chip');
    await expect(chip).toHaveJSProperty('tagName', 'BUTTON');
    await expect(chip).toHaveAttribute('type', 'button');
    await expect(chip).not.toHaveAttribute('onclick', /.*/);
    await expect(chip).toHaveAttribute('title', /Server tab/);

    for (const from of ['summary', 'samplers', 'timeline']) {
      await switchTab(page, from);
      await expect(page.locator('#tab-server')).not.toHaveClass(/\bactive\b/);
      await chip.click();
      await expect(page.locator('#tab-server'), `the chip opens the Server tab from ${from}`)
        .toHaveClass(/\bactive\b/);
      await expect(page.locator('.tab-btn[data-tab="server"]')).toHaveClass(/\bactive\b/);
      await expect(page.locator(`#tab-${from}`)).not.toHaveClass(/\bactive\b/);
      // ...and arrives at a tab that has drawn, not at an empty pane.
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row').first()).toBeVisible();
    }

    // Keyboard too: it is a real button, so Enter on it does what a click does.
    await switchTab(page, 'summary');
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#tab-server')).toHaveClass(/\bactive\b/);
  });

  test('[R] rows are worst first within each component, and every NOT_JUDGED row carries its reason', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    // One card per component (design §3); the order is worst first inside each card.
    const byCard = await page.evaluate(() => {
      const out: Record<string, string[]> = {};
      document.querySelectorAll('.wir-component-card').forEach((card) => {
        out[card.getAttribute('data-component') || ''] = Array.from(card.querySelectorAll('tbody tr.wir-kpi-row'))
          .map((r) => (r.querySelector('.wir-chip') as HTMLElement).textContent!.trim());
      });
      return out;
    });
    expect(Object.keys(byCard)).toEqual(['kafka', 'db']);
    const kafka = byCard.kafka;
    const db = byCard.db;
    expect(kafka[0]).toBe('FAIL');
    expect(db[0]).toBe('WARN');
    // The three config errors sort above PASS: each means a check the file asked for never ran,
    // which is more interesting than one that passed.
    expect(db.indexOf('INVALID')).toBeLessThan(db.indexOf('PASS'));
    expect(db.indexOf('SOURCE FAILED')).toBeLessThan(db.indexOf('PASS'));
    expect(db.indexOf('LOW COVERAGE')).toBeLessThan(db.indexOf('PASS'));
    // ...and the reasons that are not config errors sink below it.
    expect(kafka.indexOf('PASS')).toBeLessThan(kafka.indexOf('LOAD NOT ACHIEVED'));
    expect(kafka.indexOf('PASS')).toBeLessThan(kafka.indexOf('DISABLED'));
    const statuses = kafka.concat(db);

    // Every unjudged KPI explains itself in the evaluator's own words.
    const reasons = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-reason-row'))
        .map((r) => r.textContent!.trim()));
    expect(reasons.length).toBe(statuses.filter((s) =>
      ['INVALID', 'SOURCE FAILED', 'LOW COVERAGE', 'LOAD NOT ACHIEVED', 'UNMATCHED', 'DISABLED',
        'NO VALUE'].includes(s)).length);
    expect(reasons.some((r) => r.includes('is not a reducer'))).toBe(true);
    expect(reasons.some((r) => r.includes('coverage'))).toBe(true);
  });

  test('[R] a KPI that decided nothing shows no measurement anywhere on its row', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    // typo-reducer never ran, and its query names a series that HAS two instances. Borrowing
    // that count put "2" in the Instances column of a row whose Actual is a dash — two cells
    // disagreeing about whether anything was measured.
    const cells = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
        .find((r) => r.textContent!.includes('typo-reducer'))!;
      return Array.from(row.children).map((td) => td.textContent!.trim());
    });
    expect(cells[2]).toBe('—');   // Actual
    expect(cells[4]).toBe('—');   // Coverage
    expect(cells[5]).toBe('—');   // Instances
  });

  test('[R] F5: a divideBy ratio is written in the unit its chart names, not as a bare %',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // Orchestrator call, 2026-10-02. cpu-per-rps is max cpu ÷ the throughput load:throughput
      // measured. "2.24 %" read as "CPU was 2.24 %", beside cpu-headroom's 65 %, while its chart
      // named the axis "% ÷ load:throughput". The CI/CD JSON keeps "unit": "%" — a contract.
      const cells = await page.evaluate(() => {
        const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
          .find((r) => r.querySelector('.wir-kpi-name')!.textContent!.trim() === 'cpu-per-rps')!;
        return Array.from(row.children).map((td) => td.textContent!.trim());
      });
      expect(cells[2]).toBe('2.24 % ÷ load:throughput');           // Actual
      expect(cells[3]).toBe('fail > 10,000 % ÷ load:throughput');  // Criteria
      await openChart(page, 'cpu-per-rps');
      expect((await chartOf(page, 'cpu-per-rps'))!.yName).toBe('% ÷ load:throughput');
      // A plain assert is unchanged: its declared unit, once.
      const headroom = await page.evaluate(() => {
        const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
          .find((r) => r.querySelector('.wir-kpi-name')!.textContent!.trim() === 'cpu-headroom')!;
        return row.children[2].textContent!.trim();
      });
      expect(headroom).toBe('65 %');
    });

  test('[R] F15: a deleted assert takes its build finding with it; a block-level finding stays',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // Orchestrator call, 2026-10-02. The build's one finding is about typo-reducer. A
      // block-level one is added beside it, as a payload from another document would carry it.
      await page.evaluate(() => {
        const W = window as any;
        const d = JSON.parse(JSON.stringify(W.chartData));
        d.serverFindings = d.serverFindings.concat([{ severity: 'warning',
          ruleId: 'serverMetrics.environments.prod', message: 'a block-level finding the board must keep' }]);
        W.WIR.serverKpis.init(d);
      });
      const findings = page.locator('.wir-server-findings .wir-finding');
      await expect(findings).toHaveCount(2);
      // By its text, not its position (SF18): the pane lists findings in ServerGateEvaluation's
      // phases, and a block's environments are the mapper's, ahead of any assert's — moved
      // deliberately from findings.first().
      await expect(findings.filter({ hasText: "typo-reducer reduce.kind 'maxx' is not a reducer" })).toHaveCount(1);
      await expect(findings.first()).toContainText('serverMetrics.environments.prod');

      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.remove(W._keyOf('typo-reducer'));
      });
      await expect(page.locator('.wir-kpi-name:text-is("typo-reducer")')).toHaveCount(0);
      await expect(findings).toHaveCount(1);
      await expect(findings.first()).toHaveText('serverMetrics.environments.prod a block-level finding the board must keep');
    });

  test('[R] the collection panel reports the source, and why it failed',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      /* Cell by cell (R3-18): the old `some(t => t.includes('1s'))` was satisfied by any row's
         text, so a step or offset written into the wrong column, or not at all, passed. One row
         since C8: a collection has one backend, given to wir-collect, named "metrics". */
      const cells = () => page.evaluate(() =>
        Array.from(document.querySelectorAll('.wir-collection tbody tr:not(.wir-reason-row)'))
          .map((r) => Array.from(r.children).map((td) => td.textContent!.trim())));
      // Source, Status, Step, Clock offset. PARTIAL: one series' row is unreadable on purpose and
      // the other seven came back (backends plan §8b). SF46: the Type and URL columns left the
      // default view under "no provenance in the GUI" - the backend and the CSV path are the
      // Source cell's tooltip, and server-metrics.json's - while the step and the clock offset,
      // which a reader needs to judge the series, stay.
      expect(await cells()).toEqual([['metrics', 'PARTIAL', '1s', '0']]);
      expect(await page.locator('.wir-collection thead th').allTextContents())
        .toEqual(['Source', 'Status', 'Step', 'Clock offset']);
      await expect(page.locator('.wir-collection tbody tr:not(.wir-reason-row) td').first())
        .toHaveAttribute('title', 'csv · e26-metrics.csv');   // P3: a CSV source records its file name only
      // Why, under its own row, in the collector's words: on one line, without the parser's
      // location block.
      const error = await page.evaluate(() => {
        const row = document.querySelector('.wir-collection tbody tr:not(.wir-reason-row)')!;
        const next = row.nextElementSibling as HTMLElement;
        return next && next.classList.contains('wir-reason-row') ? next.textContent!.trim() : null;
      });
      expect(error).toBe("1 of 8 series failed - first: e26-metrics.csv line 1328: time \"2023-11-14 22:13:20\" has no time zone - add one (2026-10-02T14:02:15+03:00, or a Z for UTC), write epoch milliseconds, or give the file's zone with --time-zone");
      // F16 (orchestrator call, 2026-10-02): the collected-at time is shown as the window lines
      // are, in the reader's zone, and the collector's own UTC nanosecond stamp stays reachable in
      // the title. The literal local text is pinned per zone in "the judged window in <zone>" below.
      // SF38: once, on the line a closed panel shows, with its zone; the footer keeps the id alone.
      const stamp = page.locator('.wir-collection-line .wir-collected-at');
      await expect(stamp).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC[+-]\d{2}:\d{2}$/);
      await expect(stamp).toHaveAttribute('title', '2026-10-22T06:06:19.389630Z');
      await expect(page.locator('.wir-collection .wir-collection-id')).toHaveText('Collection 60a852d6637b30c5');

      // An offset is signed and in the step's units: an unnoticed one is how a series lines up
      // against the wrong part of the run. E26 collected with none, so two are given here.
      const cases: [number, number, string, string][] = [[2500, 1000, '+2500ms', '1s'], [-60000, 15000, '-1m', '15s']];
      for (const [offset, step, shown, stepShown] of cases) {
        await page.evaluate(([o, st]) => {
          const W = window as any;
          const d = JSON.parse(JSON.stringify(W.chartData));
          d.serverMetrics.sources[0].clockOffsetMs = o;
          d.serverMetrics.sources[0].stepMillis = st;
          W.WIR.serverKpis.init(d);
        }, [offset, step]);
        const shifted = await cells();
        expect(shifted[0][3]).toBe(shown);
        expect(shifted[0][2]).toBe(stepShown);
      }
    });

  test('[R] a failed series says what happened, never "failed: null"', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    // R3-16 pinned that a refused CONNECTION read "failed: null" (a ConnectException has a null
    // message). E26 has no dead source any more (one collection, one backend: C8), so C37 pins
    // the connection sentence end to end, and this pins the shape here: the reason row and the
    // SOURCE_FAILED row both say what happened, never "null".
    const sourceError = await page.locator('.wir-collection tbody tr.wir-reason-row').allTextContents();
    expect(sourceError.length).toBeGreaterThan(0);
    for (const t of sourceError) {
      expect(t).not.toContain('failed: null');
      expect(t.trim().endsWith('null')).toBe(false);
    }

    const kpiReason = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
        .find((r) => r.textContent!.includes('source-never-answered'))!;
      return (row.nextElementSibling as HTMLElement).textContent!.trim();
    });
    expect(kpiReason).toContain('has no time zone');
    expect(kpiReason).not.toContain('failed: null');
  });

  test('[U] SF23: a series that could not be collected says so, never that its source never answered', async ({ page }) => {
    // E26's db/up fails on one CSV row with no time zone, while the source answers every other
    // series and reads PARTIAL. The reason phrase was "could not be collected because the source
    // never answered" — on the chart note, beside that PARTIAL. NotJudged.SOURCE_FAILED's words.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggle(W._keyOf('source-never-answered'));
    });
    const note = page.locator('#serverKpiTable .wir-chart-note');
    await expect(note).toHaveCount(1);
    await expect(note).toContainText('not judged — could not be collected');
    await expect(page.locator('#tab-server')).not.toContainText('never answered');
    await expect(page.locator('.wir-collection')).toContainText('PARTIAL');
  });

  test('[U] filtering by status re-renders the model, not the DOM', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    const all = await page.locator('#serverKpiTable tbody tr.wir-kpi-row').count();
    expect(all).toBe(12);

    await page.click('.wir-filter-chip:text-is("FAIL 1")');
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(1);

    // A filtered-out row is GONE, not display:none — a display filter would be erased by the
    // next render and would orphan the reason and chart rows belonging to the row above them.
    const hidden = await page.evaluate(() =>
      Array.from(document.querySelectorAll('#serverKpiTable tbody tr'))
        .filter((r) => (r as HTMLElement).style.display === 'none').length);
    expect(hidden).toBe(0);

    await page.click('.wir-filter-chip:text-is("FAIL 1")');   // toggles off
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(all);
  });

  test('[U] a component badge, the text filter and the status chips filter to exactly their rows (R3-18, U6)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // One filter row (design §3, U6): a text box and the status chips; the component filter is
      // each card's badge. The Gate and Source chip rows are gone — a card IS the component, and a
      // collection has one source (C8).
      const shown = () => page.evaluate(() =>
        Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row')).map((r) => ({
          id: r.querySelector('.wir-kpi-name')!.textContent!.trim(),
          component: r.closest('.wir-component-card')!.getAttribute('data-component'),
        })));
      const ids = async () => (await shown()).map((r) => r.id).sort();
      const badge = (id: string) => page.locator(`.wir-component-card[data-component="${id}"] .wir-gate-badge`);
      const warn = page.locator('.wir-server-filters .wir-filter-chip', { hasText: /^WARN \d+$/ });
      await expect(page.locator('.wir-filter-label:text-is("Source")')).toHaveCount(0);
      await expect(page.locator('.wir-filter-label:text-is("Gate")')).toHaveCount(0);

      // The badge narrows the board to its card, and a second click lets it go.
      await badge('kafka').click();
      expect(await ids()).toEqual(['cpu-headroom', 'lag-spike', 'needs-load', 'parked']);
      expect((await shown()).every((r) => r.component === 'kafka')).toBe(true);
      await expect(page.locator('.wir-component-card')).toHaveCount(1);

      // Two filters compose, and the chips count what the OTHER filters left (U6): kafka has no WARN.
      await expect(warn).toHaveCount(0);
      await badge('kafka').click();
      await warn.click();
      expect(await shown()).toEqual([{ id: 'lag-p50-warns', component: 'db' }]);
      await warn.click();
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(12);

      // The text box finds a row by its id, description or component, across cards, and keeps its
      // focus while the cards redraw under it.
      const box = page.locator('.wir-server-search');
      await box.fill('lag');
      expect(await ids()).toEqual(['lag-p50-warns', 'lag-spike', 'lag-sustained']);
      await expect(box).toBeFocused();
      await expect(page.locator('.wir-component-card')).toHaveCount(2);
      await box.fill('nothing at all');
      await expect(page.locator('#serverBoard')).toContainText('No server assert matches these filters.');

      // Clear filters puts every row back, the box included.
      await page.locator('#serverKpiTable .wir-link:text-is("Clear filters")').click();
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(12);
      await expect(page.locator('.wir-server-search')).toHaveValue('');
    });

  test('[R] the status chips sum to All n, and each selects its count (R3-18)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // "FAIL 1", the Asserts tab's wording, since SF41 — it read "FAIL (1)". The parse moved with it,
    // on purpose.
    const chips = await page.evaluate(() => {
      const status = Array.from(document.querySelectorAll('.wir-server-filters .wir-filter-chip'))
        .map((b) => b.textContent!.trim())
        .filter((t) => / \d+$/.test(t));
      return status.map((t) => {
        const m = /^(.*) (\d+)$/.exec(t)!;
        return { label: m[1], n: Number(m[2]) };
      });
    });
    const all = chips.find((c) => c.label === 'All')!;
    const rest = chips.filter((c) => c.label !== 'All');
    expect(all.n).toBe(12);
    expect(rest.reduce((n, c) => n + c.n, 0), 'a row counted by All and selectable by no chip').toBe(all.n);
    for (const c of rest) {
      await page.locator(`.wir-filter-chip:text-is("${c.label} ${c.n}")`).click();
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row'), c.label).toHaveCount(c.n);
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row .wir-chip').first()).toHaveText(c.label);
      await page.locator(`.wir-filter-chip:text-is("${c.label} ${c.n}")`).click();
    }
  });

  test('[R] F17: every place that shows coverage says it is of the collected window (D28)', async ({ page }) => {
    // Exploratory, 2026-10-02. D28 (plans/server-metrics-delivery.md): coverage stays a
    // collect-time property of the collected window, and "every place that shows coverage says
    // 'of the collected window'" — because the verdict beside it was taken over the JUDGED
    // window, and the two can disagree. On screen only a hover title on the cells said it; the
    // column header read "Coverage", the LOW COVERAGE reason row "coverage 6% is below the floor
    // 90%", the editor's lookup "Collected: 1 instance · coverage 100% · status OK".
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const WINDOW = /collected window/i;

    // A column header has no room for the phrase, so its tooltip may carry it there — on every
    // component's card.
    const headers = await page.locator('#serverKpiTable thead th', { hasText: /coverage/i })
      .evaluateAll((ths) => ths.map((th) => (th.textContent || '') + ' ' + (th.getAttribute('title') || '')));
    expect(headers.length, 'one Coverage header per card').toBe(2);
    headers.forEach((h) => expect(h, 'the Coverage column header').toMatch(WINDOW));

    // A sentence has the room, so it says it in words.
    const reason = await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
        .find((r) => r.querySelector('.wir-kpi-name')!.textContent!.trim() === 'sparse-coverage')!;
      const next = row.nextElementSibling as HTMLElement;
      return next && next.classList.contains('wir-reason-row') ? next.innerText : '';
    });
    expect(reason, 'sparse-coverage carries its LOW COVERAGE reason row').toMatch(/coverage/i);
    expect(reason, 'the LOW COVERAGE reason row').toMatch(WINDOW);

    await page.click('.wir-server-actions .wir-btn-primary');
    await page.locator('#serverEditor [data-field="query"]').fill('lag');
    const validate = page.locator('#serverEditorValidate');
    await expect(validate).toContainText('coverage');
    await expect(validate, 'the editor\'s Validate line').toContainText(WINDOW);
  });

  test('[U] the judged window is stated, and a ramp-up end inside it is excluded from the verdicts (R3-18)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      const note = page.locator('#serverBoard .wir-window-note');
      const expected = await page.evaluate(() => {
        const W = window as any;
        const w = W.chartData.serverMetrics.window;
        const f = (t: number) => W.formatTimeZoned(new Date(t));
        return {
          whole: 'Window judged: ' + f(w.start) + ' → ' + f(w.end),
          trimmed: 'Window judged: ' + f(w.start + 30000) + ' → ' + f(w.end) + ' · ramp-up excluded',
        };
      });
      await expect(note).toHaveText(expected.whole);

      // E26's payload carries no rampUpEnd, so nothing ever rendered "ramp-up excluded". Given one
      // 30s in — after lag's 900-msg spike at 18-27s — the window starts there, and the verdicts
      // are taken over what is left: lag-spike's max is 120, a PASS.
      const chip = (id: string) => page.locator('#serverKpiTable tbody tr.wir-kpi-row')
        .filter({ has: page.locator(`.wir-kpi-name:text-is("${id}")`) }).locator('.wir-chip');
      await expect(chip('lag-spike')).toHaveText('FAIL');
      await page.evaluate(() => {
        const W = window as any;
        const d = JSON.parse(JSON.stringify(W.chartData));
        d.serverMetrics.window.rampUpEnd = d.serverMetrics.window.start + 30000;
        W.WIR.serverKpis.init(d);
      });
      await expect(note).toHaveText(expected.trimmed);
      await expect(chip('lag-spike')).toHaveText('PASS');
      const actual = await page.evaluate(() => (window as any).WIR.serverKpis._board()
        .find((r: any) => r.id === 'lag-spike').actual);
      expect(actual).toBe(120);
    });
});

/* The window line, as literal text (review:F). The test above builds its expectation with
   formatTimeZoned, the function the board uses — so it pins how the line is put together and
   cannot catch a wrong or locale-dependent timestamp. E26's payload is committed, so its window
   is a constant: 1700000000026 .. 1700000048490 ms, and its collection 2026-10-22T06:06:19Z. Shown
   in the reader's zone, each stamp with its own offset (SF38), pinned here. Jerusalem is the case a
   single label for the tab would get wrong: the window is from November 2023 (+02:00) and the
   collection from October 2026 (+03:00, summer time). */
for (const [zone, start, end, collected] of [
  ['UTC', '2023-11-14 22:13:20 UTC+00:00', '2023-11-14 22:14:08 UTC+00:00', '2026-10-22 06:06:19 UTC+00:00'],
  ['Asia/Kolkata', '2023-11-15 03:43:20 UTC+05:30', '2023-11-15 03:44:08 UTC+05:30', '2026-10-22 11:36:19 UTC+05:30'],
  ['Asia/Jerusalem', '2023-11-15 00:13:20 UTC+02:00', '2023-11-15 00:14:08 UTC+02:00', '2026-10-22 09:06:19 UTC+03:00'],
]) {
  test.describe(`Server tab — the judged window in ${zone}`, () => {
    test.use({ timezoneId: zone });
    test(`[R] SF38: E26's window reads ${start} → ${end}`, async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await expect(page.locator('#serverBoard .wir-window-note'))
        .toHaveText(`Window judged: ${start} → ${end}`);
    });

    // F16: the collection time is in the same zone and format as the window line above it — not
    // 2026-10-03T09:35:40.994100500Z, raw UTC with nine fractional digits. SF38: with its own
    // offset, and printed once — it was the summary's "collected …" and the footer's "Collected …"
    // both, the same time twice.
    test(`[R] F16/SF38: E26's collection reads collected ${collected}, once`, async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await expect(page.locator('.wir-collection-line'))
        .toHaveText(`Collection: metrics PARTIAL · collected ${collected}`);
      const panel = (await page.locator('.wir-collection').textContent()) || '';
      const clock = collected.slice(0, 19);
      expect(panel.split(clock).length - 1, `"${clock}" in the Collection panel`).toBe(1);
    });

    // SF38: the chart's hover says which zone its time is in, as the window line does.
    test(`[R] SF38: a server chart's tooltip time carries ${collected.slice(-9)}`, async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.evaluate(() => { const W = (window as any).WIR.serverKpis; W.toggle(W._keyOf('lag-spike')); });
      await expect.poll(() => page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const el = document.getElementById('serverChart-' + W._keyOf('lag-spike'));
        const inst = el && (window as any).echarts.getInstanceByDom(el);
        if (!inst) return null;
        const w = (window as any).chartData.serverMetrics.window;
        return String(inst.getOption().tooltip[0].formatter([{ axisValue: w.start, value: [w.start, 1], seriesName: 'a' }]))
          .split('<br>')[0];
      })).toBe(start);
    });

    // ...and so does the picker's chart, whose tooltip is the row charts' (SF38, SF35): it was
    // ECharts' default, with no zone, and reverting it left every spec green. E27, the fixture
    // with a catalogue component, collected over the same November 2023 window as E26.
    test(`[R] SF38: the metric picker's chart tooltip time carries ${start.slice(-9)}`, async ({ page }) => {
      await loadClean(page, 'E27');
      await switchTab(page, 'server');
      await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
      await page.evaluate(() => (window as any).WIR.serverKpis.pickFocus('connections.used.pct'));
      await expect.poll(() => page.evaluate(() => {
        const el = document.getElementById('serverPickChart');
        const inst = el && (window as any).echarts.getInstanceByDom(el);
        if (!inst) return null;
        const f = inst.getOption().tooltip[0].formatter;
        if (typeof f !== 'function') return `formatter ${typeof f}`;
        const w = (window as any).chartData.serverMetrics.window;
        return String(f([{ axisValue: w.start, value: [w.start, 1], seriesName: 'a' }])).split('<br>')[0];
      })).toBe(start);
    });
  });
}

test.describe('Server tab — the charts', () => {
  test('[U] the breaching stretch is shaded, from the same band the verdict used',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.click('.wir-kpi-name:has-text("lag-spike")');
      await page.waitForTimeout(400);

      const chart = await page.evaluate(() => {
        const el = document.querySelector('.wir-server-chart')!;
        const inst = (window as any).echarts.getInstanceByDom(el);
        const opt = inst.getOption();
        return {
          series: opt.series.length,
          points: opt.series[0].data.length,
          areas: opt.series[0].markArea ? opt.series[0].markArea.data.length : 0,
          lines: opt.series[0].markLine
            ? opt.series[0].markLine.data.map((d: any) => d.yAxis) : [],
        };
      });

      expect(chart.series).toBe(1);
      expect(chart.points).toBeGreaterThan(10);
      // One stretch, in the middle of the window. A shaded region that ran to the edge would
      // mean the breach detection had lost the recovery.
      expect(chart.areas).toBe(1);
      // Both bands are drawn, and they are the numbers the row claims.
      expect(chart.lines).toEqual([300, 500]);

      // R3-10. Counting areas passes on an invisible one: a stretch used to end at its own last
      // breaching point, so a one-sample breach was [t, t] and drew nothing. It ends at the point
      // that recovered now, and has a width.
      const lag = await chartOf(page, 'lag-spike');
      const [from, to] = lag!.series[0].areas[0];
      expect(to).toBeGreaterThan(from);
      const recovered = (lag!.series[0].data as any[]).find((p) => p[0] === to);
      expect(recovered, 'the stretch ends ON a drawn point').toBeTruthy();
      expect(recovered[1]).toBeLessThanOrEqual(500);
    });

  test('[U] a capped instance list says so rather than showing a subset as the whole',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.click('.wir-kpi-name:has-text("many-instances")');
      await page.waitForTimeout(400);

      // D14 caps what the writer ships at 20 instances; the collector saw 21. A capped array
      // read as complete is a bug this codebase has shipped before.
      await expect(page.locator('.wir-chart-row .wir-dim'))
        .toContainText('and 1 more instance(s) not drawn');
      // F12 (orchestrator call, 2026-10-02 — this assertion was '20 of 21' and is rewritten on
      // purpose): the cell states the JUDGED count, 21, as the verdict and the JSON do. "20 of 21"
      // read as "judged across 20 of 21"; the draw cap is a display fact, said as one.
      const cell = await page.evaluate(() => {
        const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
          .find((r) => r.textContent!.includes('many-instances'))!;
        const td = row.children[5] as HTMLElement;   // Instances
        return { text: td.textContent!.trim(), title: td.getAttribute('title') };
      });
      expect(cell.text).toBe('21 (20 drawn)');
      expect(cell.title).toBe('Judged across all 21 instances; the report carries 20 of them to draw, '
        + 'because the writer caps the instances it ships per series');
      // An uncapped row is the bare count, with no title claiming a cap.
      const lag = await page.evaluate(() => {
        const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
          .find((r) => r.querySelector('.wir-kpi-name')!.textContent!.trim() === 'lag-spike')!;
        const td = row.children[5] as HTMLElement;   // Instances
        return { text: td.textContent!.trim(), title: td.getAttribute('title') };
      });
      expect(lag).toEqual({ text: '1', title: null });

      // L9-12. WHICH twenty matters: the cap kept the first twenty in collector order, and E26's
      // p20 — the 21st, and the highest — is the instance across: max took its number from.
      // The chart beside the verdict then never showed the series the verdict was about. The
      // instance a verdict depends on is kept first; since C5 the rest go worst first (here the
      // highest), so p0, the lowest, is the one left out. What is drawn keeps collector order.
      const names = await page.evaluate(() => {
        const el = document.querySelector('.wir-server-chart')!;
        const inst = (window as any).echarts.getInstanceByDom(el);
        return (inst.getOption().series as any[]).map((s) => s.name);
      });
      expect(names).toHaveLength(20);
      expect(names).toContain('instance=p20');
      expect(names).toContain('instance=p19');
      expect(names).not.toContain('instance=p0');
      expect(names[0]).toBe('instance=p1');
    });

  test('[U] a FAIL decided by an instance other than the first is shaded too',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      // L9-12. The wash was drawn from the first drawn instance alone. E26's cpu series has two
      // pods: a sits at 40-46, b at 61-65. Retune cpu-headroom's fail band to gt 50 and pod a
      // never comes near it while b is over it throughout — a FAIL under across: max with, before
      // the fix, an unshaded chart beside it. (Not many-instances: a capped series is not
      // re-judged in the page, so its chip would keep the baked PASS.)
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W._commitDrag(W._keyOf('cpu-headroom'), 'fail', 50);
      });
      const row = page.locator('#serverKpiTable tbody tr.wir-kpi-row')
        .filter({ has: page.locator('.wir-kpi-name:text-is("cpu-headroom")') }).first();
      await expect(row.locator('.wir-chip')).toHaveText('FAIL');
      await page.click('.wir-kpi-name:has-text("cpu-headroom")');
      await page.waitForTimeout(400);

      const chart = await page.evaluate(() => {
        const el = document.querySelector('.wir-server-chart')!;
        const inst = (window as any).echarts.getInstanceByDom(el);
        const series = inst.getOption().series as any[];
        return {
          first: series[0].name,
          firstMax: Math.max(...series[0].data.map((p: any) => p[1])),
          areas: series[0].markArea ? series[0].markArea.data.length : 0,
        };
      });
      expect(chart.first).toBe('instance=a');
      expect(chart.firstMax).toBeLessThan(50);   // the first instance does not breach...
      expect(chart.areas).toBeGreaterThan(0);    // ...and the chart is shaded anyway
    });

  test('[U] an open chart survives a theme toggle made on another tab', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openChart(page, 'lag-spike');
    const before = (await chartOf(page, 'lag-spike'))!;

    // refreshAllCharts disposes EVERY chart instance and rebuilds a hardcoded list. A server
    // chart not rebuilt there is destroyed by a dark-mode, colourblind, palette or granularity
    // click and never comes back — on a tab the reader is not even looking at, so nothing says
    // so. The toggle is made on the Summary tab, as a reader makes it (R3-18): toggled with the
    // Server tab showing, the hidden-tab rebuild this test exists for never ran.
    await switchTab(page, 'summary');
    await page.evaluate(() => (window as any).toggleDarkMode());
    await switchTab(page, 'server');

    await expect.poll(async () => (await chartOf(page, 'lag-spike'))?.canvas ?? false).toBe(true);
    const after = (await chartOf(page, 'lag-spike'))!;
    expect(after.present).toBe(true);
    // The same chart, not an empty instance: the same points, the same bands, the same breach.
    expect(after.series).toHaveLength(1);
    expect(after.series[0].data).toEqual(before.series[0].data);
    expect(after.series[0].lines).toEqual([300, 500]);
    expect(after.series[0].areas).toEqual(before.series[0].areas);
    expect(after.series[0].areas).toHaveLength(1);
    expect(after.handles.map((h) => h.id).sort()).toEqual(before.handles.map((h) => h.id).sort());
  });
});

test.describe('Server tab — charts that agree with the board (M7c E)', () => {
  test('[U] opening a second chart leaves the first one drawn (R3-2)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // toggle() rebuilt the board — every chart container with it — and then drew only the chart
    // it had just opened. lag-spike went blank the moment lag-p50-warns was opened.
    await openChart(page, 'lag-spike');
    await openChart(page, 'lag-p50-warns');
    const first = await chartOf(page, 'lag-spike');
    const second = await chartOf(page, 'lag-p50-warns');
    expect(first!.present && first!.canvas).toBe(true);
    expect(second!.present && second!.canvas).toBe(true);
    expect(first!.series[0].lines).toEqual([300, 500]);
    expect(second!.series[0].lines).toEqual([100, 5000]);

    // Closing one leaves the other, too.
    await page.click('.wir-kpi-name:text-is("lag-p50-warns")');
    expect(await chartOf(page, 'lag-p50-warns')).toBeNull();
    expect((await chartOf(page, 'lag-spike'))!.present).toBe(true);
  });

  test('[U] re-reading the document keeps every open chart drawn (R3-2)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openChart(page, 'lag-spike');
    await openChart(page, 'cpu-headroom');
    // reread() kept state.open and called a bare render(): two open rows, two empty boxes.
    await page.evaluate(() => (window as any).WIR.serverKpis.reread());
    expect((await chartOf(page, 'lag-spike'))!.canvas).toBe(true);
    expect((await chartOf(page, 'cpu-headroom'))!.canvas).toBe(true);
  });

  test('[U] an edit disposes the charts it replaces — no instance survives on detached DOM (R3-2)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await openChart(page, 'lag-spike');
      await openChart(page, 'cpu-headroom');
      const before = await page.evaluate(() => (window as any).chartInstances.length);

      // Every commit rebuilt the board and created a new instance per open chart without
      // disposing the old one, which stayed in chartInstances on a detached container: ten drags
      // took 2 instances to 12, each resized on every resize and kept in the connect group.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        for (let i = 0; i < 5; i++) W._commitDrag(W._keyOf('lag-spike'), 'fail', 600 + i * 10);
      });
      const after = await page.evaluate(() => {
        const all = (window as any).chartInstances as any[];
        return {
          count: all.length,
          detached: all.filter((c) => c.isDisposed() || !document.body.contains(c.getDom())).length,
        };
      });
      expect(after.detached).toBe(0);
      expect(after.count).toBe(before);
      expect((await chartOf(page, 'cpu-headroom'))!.canvas).toBe(true);
    });

  test('[U] seeded asserts and a shipped one each draw in their own container (C-6)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // Generate seeds asserts the shipped file never had. Their chart container was keyed on an
      // index into the BAKED list, which they are not in: every one got index 0 — one container
      // between them and the first shipped assert, so a chart and its handles drew under the
      // wrong row and the other stayed empty.
      const keys = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        ['lag-spike', 'lag-p50-warns', 'lag-sustained', 'cpu-headroom', 'cpu-per-rps', 'needs-load',
          'parked', 'typo-reducer'].forEach((id) => W.remove(W._keyOf(id)));
        W.generate();
        return (W._board() as any[]).filter((r) => /^n/.test(r.key)).map((r) => r.key);
      });
      // lag and cpu, each collected for kafka and for db: a series is its component and its
      // query (backends plan §8b), so four seeds.
      expect(keys).toHaveLength(4);
      for (const key of keys) {
        await page.evaluate((k) => (window as any).WIR.serverKpis.toggle(k), key);
      }
      await page.click('.wir-kpi-name:text-is("many-instances")');

      const containers = await page.evaluate(() =>
        Array.from(document.querySelectorAll('.wir-server-chart')).map((el) => ({
          id: el.id,
          drawn: !!(window as any).echarts.getInstanceByDom(el) && !!el.querySelector('canvas'),
        })));
      expect(containers).toHaveLength(5);
      expect(new Set(containers.map((c) => c.id)).size).toBe(5);
      expect(containers.every((c) => c.drawn)).toBe(true);
    });

  test('[U] the y-axis reaches the bands, so a passing assert shows its line and handle (R3-3)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // cpu-headroom PASSes: its data stays under 70% and its fail band is gt 95. The axis was
      // scaled to the data alone and a markLine does not widen it, so the fail line and its
      // handle sat above the canvas (y=-43) — and a threshold could only be dragged once it was
      // already breached, i.e. only ever loosened.
      for (const id of ['cpu-headroom', 'lag-p50-warns']) {
        await openChart(page, id);
        const c = (await chartOf(page, id))!;
        const top = Math.max(...c.series[0].lines);
        expect(top, `${id}: the band is inside the axis`).toBeLessThanOrEqual(c.yExtent[1]);
        expect(c.handles.length).toBeGreaterThan(0);
        for (const h of c.handles) {
          expect(h.y, `${h.id} below the top of the grid`).toBeGreaterThanOrEqual(c.grid.y);
          expect(h.y, `${h.id} above the bottom of the grid`).toBeLessThanOrEqual(c.grid.y + c.grid.height);
        }
      }
    });

  test('[U] handles follow the chart when the window narrows, and after a hidden rebuild (R3-4)',
    async ({ page }) => {
      await page.setViewportSize({ width: 1400, height: 900 });
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await openChart(page, 'lag-spike');
      const wide = (await chartOf(page, 'lag-spike'))!;
      expect(wide.handles).toHaveLength(2);

      // The handles were placed once, at chart.getWidth() - 96/-178, and never again: at 900px
      // the chart shrank to ~800px and the handles stayed at x=966 and 1048, past its edge.
      // The width changes either way: below 900px the two panels stack (design §3), so the chart
      // can grow — what matters is that the handles follow whatever it became.
      await page.setViewportSize({ width: 900, height: 900 });
      await expect.poll(async () => (await chartOf(page, 'lag-spike'))!.width).not.toBe(wide.width);
      // Bounded by what the card SHOWS (SF34), not by the chart's own width: at 900 the chart was
      // 880px in an 816px card, the fail handle at page x 836..910 against an edge of 858, and the
      // old `h.x + 74 <= c.width` held all the same.
      await expect.poll(async () => {
        const c = (await chartOf(page, 'lag-spike'))!;
        return !!c.visible && c.handles.every((h) => c.left + h.x >= c.visible!.left
          && c.left + h.x + 74 <= c.visible!.right + 0.5);
      }).toBe(true);

      // A theme click on another tab rebuilds the server chart inside a hidden (100px) box; the
      // handles came back at x=-78, on top of the axis labels.
      await switchTab(page, 'summary');
      await page.evaluate(() => (window as any).toggleDarkMode());
      await switchTab(page, 'server');
      await expect.poll(async () => {
        const c = await chartOf(page, 'lag-spike');
        return !!c && c.present && c.handles.length === 2 && !!c.visible
          && c.handles.every((h) => h.x >= c.grid.x && c.left + h.x + 74 <= c.visible!.right + 0.5);
      }).toBe(true);
    });

  test('[U] a scrape gap breaks the line, and lone points are drawn as points (R3-11)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // `sparse` holds three samples across a 48s window at a 1s step — 6% coverage. With no gap
    // marker it was drawn as two straight ramps, a continuous-looking line beside a LOW COVERAGE
    // row; the client charts fixed the same thing as C23.
    await openChart(page, 'sparse-coverage');
    const c = (await chartOf(page, 'sparse-coverage'))!;
    const data = c.series[0].data as any[];
    expect(data.some((p) => p[1] === null)).toBe(true);
    expect(data.filter((p) => p[1] !== null)).toHaveLength(3);
    // Islands between gaps draw nothing as a line, so their symbols are shown.
    expect(c.series[0].showSymbol).toBe(true);
    // A dense series keeps its plain line.
    await openChart(page, 'lag-spike');
    const lag = (await chartOf(page, 'lag-spike'))!;
    expect((lag.series[0].data as any[]).some((p) => p[1] === null)).toBe(false);
    expect(lag.series[0].showSymbol).toBe(false);
  });

  test('[U] server charts take the reader\'s palette and line thickness (R3-12)', async ({ page }) => {
    await loadClean(page, 'E26');
    await page.evaluate(() => {
      localStorage.setItem('wir_palette', 'okabe-ito');
      localStorage.setItem('wir_lineThickness', '4');
    });
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    // The colour-blind-safe palette is a promise about every chart. cpu-headroom's two pods drew
    // in ECharts' default #5470c6/#91cc75 at width 2 beside Timeline charts in okabe-ito at 4.
    await openChart(page, 'cpu-headroom');
    const c = (await chartOf(page, 'cpu-headroom'))!;
    const palette = await page.evaluate(() => (window as any).PALETTES['okabe-ito']);
    expect(c.series[0].color).toBe(palette[0]);
    expect(c.series[1].color).toBe(palette[1]);
    expect(c.visual).toBe(palette[0]);
    expect(c.series[0].lineWidth).toBe(4);
  });

  test('[U] the chart note names the reducer of the declared assert (L3-13)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // seriesNote read `k.reduce`, a field of the judged entries the board no longer draws from:
    // every note said "judged: —".
    await openChart(page, 'lag-sustained');
    const note = page.locator('.wir-chart-row .wir-chart-note');
    await expect(note).toContainText('judged: max sustained for 5s across max');
    await expect(note).not.toContainText('judged: —');
  });

  test('[R] F4: a chart of an assert that decided nothing draws no breach wash, and a muted limit',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // Orchestrator call, 2026-10-02. parked (DISABLED), needs-load (LOAD NOT ACHIEVED) and
      // typo-reducer (INVALID) each drew fail at 1 and a red area over the whole window — the
      // wash a FAIL gets — beside a row saying it decided nothing.
      const lineOpacities = (id: string) => page.evaluate((kpiId) => {
        const W = (window as any).WIR.serverKpis;
        const el = document.getElementById('serverChart-' + W._keyOf(kpiId))!;
        const opt = (window as any).echarts.getInstanceByDom(el).getOption();
        const ml = opt.series[0].markLine;
        return ml && ml.data ? ml.data.map((d: any) => d.lineStyle.opacity) : [];
      }, id);
      for (const [id, phrase] of [
        ['parked', 'not judged — was parked with enabled: false'],
        ['needs-load', 'not judged — was not judged because the load it depends on was not established'],
        ['typo-reducer', 'not judged — could not run'],
      ]) {
        await openChart(page, id);
        const c = (await chartOf(page, id))!;
        expect(c.series[0].areas, `${id} washed a breach it never judged`).toEqual([]);
        // The declared limit is still drawn — muted, so it does not read as a live threshold.
        expect(c.series[0].lines, `${id} lost its declared limit`).toEqual([1]);
        expect(await lineOpacities(id)).toEqual([0.4]);
        const note = page.locator('.wir-chart-row').filter({ has: page.locator('#serverChart-' + c.key) })
          .locator('.wir-chart-note');
        await expect(note).toContainText(phrase);
        await expect(note).not.toContainText('judged: ');
      }
      // A judged row is untouched: its wash, and its limits at full strength.
      await openChart(page, 'lag-spike');
      const lag = (await chartOf(page, 'lag-spike'))!;
      expect(lag.series[0].areas).toHaveLength(1);
      expect(await lineOpacities('lag-spike')).toEqual([1, 1]);
    });

  test('[U] a divideBy chart is drawn in the units its verdict uses (C-7)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // cpu-per-rps judges max cpu DIVIDED by what load:throughput measured. The raw line under a
    // band in ratio units put the fail line where it meant nothing, shaded a PASS as a breach,
    // and a drag to the plotted level wrote a ratio threshold the size of the raw value.
    await openChart(page, 'cpu-per-rps');
    const c = (await chartOf(page, 'cpu-per-rps'))!;
    const probe = await page.evaluate(() => {
      const W = (window as any).WIR;
      const rule = W.asserts.evaluate(W.asserts.currentRules(), (window as any).chartData).rules
        .find((r: any) => r.id === 'load:throughput');
      const by = rule.results.reduce((m: any, r: any) => (m && m.actual >= r.actual ? m : r), null).actual;
      const raw = W.serverKpis._state.metrics.series.find((s: any) => s.query === 'cpu').instances[0].points;
      return { by, raw };
    });
    expect(c.yName).toContain('÷ load:throughput');
    // Bucketed on the judged window's origin, so the drawn timestamps are not the raw ones; a
    // max-reduced line keeps its extremes, and those are what the ratio must reproduce.
    const drawn = (c.series[0].data as any[]).filter((p) => p[1] !== null).map((p) => p[1]);
    const raw = (probe.raw as any[]).map((p) => p[1]);
    expect(probe.by).toBeGreaterThan(0);
    expect(Math.max(...drawn) * probe.by).toBeCloseTo(Math.max(...raw), 6);
    // ...and the drawn line is not the raw one (by is not 1 on this run).
    expect(Math.max(...drawn)).not.toBeCloseTo(Math.max(...raw), 3);
    // The band is drawn on that axis, and reachable.
    expect(c.series[0].lines).toEqual([10000]);
    expect(c.yExtent[1]).toBeGreaterThanOrEqual(10000);
    await expect(page.locator('.wir-chart-row .wir-chart-note')).toContainText('drawn divided by');
  });

  test('[U] a slope chart draws no band, wash or handle on the raw axis (C-7)', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // A slope band is per minute; a horizontal line at its value over the raw series means
    // nothing, and a handle on it would write a slope threshold read off a level axis.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-headroom'));
      W.set('kind', 'slope');
      W.set('fail.value', '0.5');
      W.applyEditor();
    });
    await openChart(page, 'cpu-headroom');
    const c = (await chartOf(page, 'cpu-headroom'))!;
    expect(c.series.every((s) => s.lines.length === 0 && s.areas.length === 0)).toBe(true);
    expect(c.handles).toHaveLength(0);
    await expect(page.locator('.wir-chart-row .wir-chart-note')).toContainText('per minute');
  });

  test('[U] a divideBy band sits above the divided line, washes nothing on a PASS, and a drag writes a ratio (C-7)',
    async ({ page }) => {
      // The shape the finding named (lag-per-rps: raw ~700, ratio 56, fail gt 60), on E26's own
      // numbers: lag peaks at 900 msgs, load:throughput measured ~24.8/s, so the ratio peaks near
      // 36 — and a fail band of 40 sits between the two. cpu-per-rps cannot show a wrong-units
      // chart: its band (10000) is above both the raw and the divided line. Here, on the raw axis,
      // the band sat under the whole line — a red wash over a PASS — and a handle dragged to the
      // plotted peak wrote ~950 into a ratio threshold, a check that could never fire again.
      await loadClean(page, 'E26');
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.asserts.push({
          id: 'lag-per-rps', component: 'db', query: 'lag', unit: 'msgs',
          reduce: { kind: 'max' }, across: 'max', divideBy: 'load:throughput',
          fail: { gt: 40 },
        });
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      const chip = page.locator('#serverKpiTable tbody tr.wir-kpi-row')
        .filter({ has: page.locator('.wir-kpi-name:text-is("lag-per-rps")') }).locator('.wir-chip');
      await expect(chip).toHaveText('PASS');

      await openChart(page, 'lag-per-rps');
      const c = (await chartOf(page, 'lag-per-rps'))!;
      const probe = await page.evaluate(() => {
        const W = (window as any).WIR;
        const rule = W.asserts.evaluate(W.asserts.currentRules(), (window as any).chartData).rules
          .find((r: any) => r.id === 'load:throughput');
        const by = rule.results.reduce((m: any, r: any) => (m && m.actual >= r.actual ? m : r), null).actual;
        const raw = W.serverKpis._state.metrics.series.find((s: any) => s.query === 'lag').instances[0].points;
        return { by, rawMax: Math.max(...raw.map((p: any) => p[1])) };
      });
      const peak = Math.max(...(c.series[0].data as any[]).filter((p) => p[1] !== null).map((p) => p[1]));
      expect(peak).toBeCloseTo(probe.rawMax / probe.by, 6);
      // The fail line is ABOVE the divided line, on an axis in ratio units, and a PASS is not washed.
      expect(c.series[0].lines).toEqual([40]);
      expect(peak).toBeLessThan(40);
      expect(c.yExtent[1]).toBeGreaterThanOrEqual(40);
      expect(c.yExtent[1]).toBeLessThan(probe.rawMax);
      expect(c.series.every((s) => s.areas.length === 0)).toBe(true);

      // Drag the fail handle, with the mouse, to just above the plotted peak. What lands in the
      // file is the value under the pointer on THIS axis — a ratio — never the raw level there.
      const want = peak * 1.05;
      const key = c.key;
      const chart = page.locator('#serverChart-' + key);
      await chart.scrollIntoViewIfNeeded();
      const box = (await chart.boundingBox())!;
      const fail = c.handles.find((h) => /-fail$/.test(h.id))!;
      expect(fail).toBeTruthy();
      const targetY = await page.evaluate(([k, v]) => {
        const inst = (window as any).echarts.getInstanceByDom(document.getElementById('serverChart-' + k));
        return inst.convertToPixel({ gridIndex: 0 }, [0, v])[1];
      }, [key, want] as [string, number]);
      const x = box.x + fail.x + 37;
      await page.mouse.move(x, box.y + fail.y);
      await page.mouse.down();
      await page.mouse.move(x, box.y + targetY, { steps: 8 });
      await page.mouse.up();

      await expect.poll(() => page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const k = W._state.declaredKpis.find((d: any) => d.id === 'lag-per-rps');
        return k && k.fail ? k.fail.value : null;
      })).not.toBe(40);
      const written = await page.evaluate(() => (window as any).WIR.serverKpis._state.declaredKpis
        .find((d: any) => d.id === 'lag-per-rps').fail.value);
      expect(Math.abs(written - want), `wrote ${written}, wanted about ${want}`).toBeLessThan(2);
      await expect(chip).toHaveText('PASS');
      expect((await chartOf(page, 'lag-per-rps'))!.series[0].lines).toEqual([written]);
    });

  test('[U] one lone scrape in a dense series is drawn as a point, and only it is (R3-11)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // A symbol on a lone point is how it is seen at all: a point with a gap on both sides draws
      // nothing as a line. The whole series' symbols were switched on for it, so a dense series
      // with one lone scrape drew a dot on every one of its points. Two holes are knocked into
      // lag's 1s series here so the scrape at index 20 is left alone between them.
      const lone = await page.evaluate(() => {
        const inst = (window as any).WIR.serverKpis._state.metrics.series
          .find((s: any) => s.query === 'lag').instances[0];
        inst.points = inst.points.filter((_: any, i: number) => i < 15 || i === 20 || i > 25);
        return { t: inst.points[15][0], v: inst.points[15][1], kept: inst.points.length };
      });
      await openChart(page, 'lag-spike');
      const drawn = await page.evaluate((id) => {
        const W = (window as any).WIR.serverKpis;
        const inst = (window as any).echarts.getInstanceByDom(
          document.getElementById('serverChart-' + W._keyOf(id)));
        const opt = inst.getOption();
        const data = inst.getModel().getSeriesByIndex(0).getData();
        const marked: any[] = [];
        for (let i = 0; i < data.count(); i++) {
          if (data.getItemGraphicEl(i) && data.getItemVisual(i, 'symbol') !== 'none') {
            marked.push(opt.series[0].data[i]);
          }
        }
        return {
          showSymbol: opt.series[0].showSymbol,
          gaps: (opt.series[0].data as any[]).filter((p) => p[1] === null).length,
          values: (opt.series[0].data as any[]).filter((p) => p[1] !== null).length,
          marked,
        };
      }, 'lag-spike');
      expect(drawn.gaps).toBe(2);
      expect(drawn.values).toBeGreaterThan(20);
      expect(drawn.showSymbol).toBe(true);
      expect(drawn.marked).toHaveLength(1);
      expect(drawn.marked[0][1]).toBe(lone.v);
      expect(drawn.marked[0][0]).toBeGreaterThan(lone.t - 1000);
      expect(drawn.marked[0][0]).toBeLessThanOrEqual(lone.t);
    });

  test('[U] a thinned instance is drawn as one line, not an outage between every pair (R3-11)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // The writer thins an instance over its point cap by bucket, each bucket's lowest and highest
      // point kept (F6), and says so (pointsCapped, pointCount). Its points are then several steps
      // apart by construction; measured against the bare 1s step, every spacing was a gap and the
      // chart turned to dots. lag thinned here to every other point of 2n-1, closer together than
      // the writer's buckets ever put them (server-chart.test.js holds the far-ends worst case).
      const kept = await page.evaluate(() => {
        const inst = (window as any).WIR.serverKpis._state.metrics.series
          .find((s: any) => s.query === 'lag').instances[0];
        inst.points = inst.points.filter((_: any, i: number) => i % 2 === 0);
        inst.pointCount = inst.points.length * 2 - 1;
        inst.pointsCapped = true;
        return inst.points.length;
      });
      await openChart(page, 'lag-spike');
      const c = (await chartOf(page, 'lag-spike'))!;
      const data = c.series[0].data as any[];
      expect(data.filter((p) => p[1] !== null)).toHaveLength(kept);
      expect(data.filter((p) => p[1] === null)).toHaveLength(0);
      expect(c.series[0].showSymbol).toBe(false);
      await expect(page.locator('.wir-chart-row .wir-chart-note')).toContainText('thinned from');
    });

  test('[R] F2: dragging a server handle with the mouse commits and throws nothing', async ({ page }) => {
    // Exploratory, 2026-10-02. Every committed drag threw "TypeError: Cannot read properties of
    // null (reading 'getComponent')" from inside ECharts: ondragend commits, the commit re-renders
    // the board and disposes the chart, and zrender then dispatches the same mouseup into the
    // disposed instance. 4 of 4 drags in one session. The commit landed, so nothing on screen said
    // so — and the one existing mouse-drag spec (the divideBy one above) never listened for it.
    // Client-chart drags on E25 throw nothing.
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openChart(page, 'cpu-headroom');
    const c = (await chartOf(page, 'cpu-headroom'))!;
    const fail = c.handles.find((h) => /-fail$/.test(h.id))!;
    expect(fail, 'cpu-headroom has a fail handle to drag').toBeTruthy();

    const chart = page.locator('#serverChart-' + c.key);
    await chart.scrollIntoViewIfNeeded();
    const box = (await chart.boundingBox())!;
    const targetY = await page.evaluate(([k, v]) => {
      const inst = (window as any).echarts.getInstanceByDom(document.getElementById('serverChart-' + k));
      return inst.convertToPixel({ gridIndex: 0 }, [0, v])[1];
    }, [c.key, 80] as [string, number]);
    const x = box.x + fail.x + 37;
    await page.mouse.move(x, box.y + fail.y);
    await page.mouse.down();
    await page.mouse.move(x, box.y + targetY, { steps: 8 });
    await page.mouse.up();

    // The drag really committed — a spec whose drag never landed would see no error either.
    await expect.poll(() => page.evaluate(() => (window as any).WIR.serverKpis._state.declaredKpis
      .find((d: any) => d.id === 'cpu-headroom').fail.value)).not.toBe(95);
    const written = await page.evaluate(() => (window as any).WIR.serverKpis._state.declaredKpis
      .find((d: any) => d.id === 'cpu-headroom').fail.value);
    expect(Math.abs(written - 80), `wrote ${written}, dragged to about 80`).toBeLessThan(3);
    await page.waitForTimeout(300);
    expect(errors, 'a committed drag threw inside ECharts').toEqual([]);
  });

  test('[R] F6: a server chart tooltip shows the value as the board does — rounded, with its unit',
    async ({ page }) => {
      // Exploratory, 2026-10-02. The server charts set `tooltip: {trigger: 'axis'}` and nothing
      // else, so hovering cpu-per-rps read "pod=a 1.7770133333333331 / pod=b 2.503973333333333"
      // beside a board that formats the same assert as 2.24 — and lag-spike's tooltip said "900"
      // with no "msgs", cpu-headroom's "44" with no "%". Every client chart formats its tooltip.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      /** Show the tooltip at one drawn point of `id`'s chart and return what it reads. */
      const tipAt = async (id: string, pick: 'noisy' | 'max' | 'first') => {
        const raw = await page.evaluate(([kpiId, how]) => {
          const W = (window as any).WIR.serverKpis;
          const inst = (window as any).echarts.getInstanceByDom(
            document.getElementById('serverChart-' + W._keyOf(kpiId)));
          const data = inst.getOption().series[0].data as any[];
          const fraction = (v: number) => (String(v).split('.')[1] || '').length;
          // Only a point on the plot: the first bucket can sit before the window's start.
          const [x0, x1] = inst.getModel().getComponent('xAxis', 0).axis.scale.getExtent();
          let i = -1;
          data.forEach((p, j) => {
            if (p[1] === null || p[1] === undefined || p[0] < x0 || p[0] > x1) return;
            if (how === 'first' && i < 0) i = j;
            if (how === 'noisy' && i < 0 && fraction(p[1]) > 4) i = j;
            if (how === 'max' && (i < 0 || p[1] > data[i][1])) i = j;
          });
          if (i < 0) return null;
          inst.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: i });
          return data[i][1] as number;
        }, [id, pick] as [string, string]);
        expect(raw, `${id}: no ${pick} point to hover`).not.toBeNull();
        let text = '';
        await expect.poll(async () => {
          text = await page.evaluate((kpiId) => {
            const W = (window as any).WIR.serverKpis;
            const el = document.getElementById('serverChart-' + W._keyOf(kpiId))!;
            const tip = Array.from(el.querySelectorAll('div')).find((d) =>
              /z-index:\s*9999999/.test(d.getAttribute('style') || '')
              && (d as HTMLElement).style.display !== 'none');
            return tip ? (tip as HTMLElement).innerText : '';
          }, id);
          return text;
        }, { message: `${id}: the tooltip never showed` }).not.toBe('');
        return { raw: raw as number, text };
      };

      await openChart(page, 'cpu-per-rps');
      const ratio = await tipAt('cpu-per-rps', 'noisy');
      expect(ratio.text, 'the tooltip printed the raw float').not.toContain(String(ratio.raw));
      expect(ratio.text, 'float noise in the tooltip').not.toMatch(/\d\.\d{5,}/);

      await openChart(page, 'lag-spike');
      const lag = await tipAt('lag-spike', 'max');
      expect(lag.text, 'the lag tooltip has no unit').toMatch(/\bmsgs\b/);

      await openChart(page, 'cpu-headroom');
      const cpu = await tipAt('cpu-headroom', 'first');
      expect(cpu.text, 'the cpu tooltip has no unit').toContain('%');
    });

  test('[R] F10: a server handle\'s label is as legible in dark mode as in light', async ({ page }) => {
    // Exploratory, 2026-10-02. The handle labels are fixed fills, #991b1b (fail) and #92400e
    // (warn), on 0.14-alpha rects — chosen, says the comment above drawDragHandles, because "a
    // handle has to be legible in both" themes. On the dark theme's #100C2A they are about 2.3:1
    // and 2.7:1: "fail ⇕" and "warn ⇕" all but vanish. Measured as WCAG contrast of the label
    // against the handle's own fill laid over the chart background; light mode is the control.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openChart(page, 'lag-spike');
    const key = (await chartOf(page, 'lag-spike'))!.key;

    const light = await handleContrast(page, 'serverChart-' + key, 'wir-server-handle-');
    expect(light.map((h) => h.id).sort(), 'lag-spike draws a warn and a fail handle')
      .toEqual([`wir-server-handle-${key}-fail`, `wir-server-handle-${key}-warn`]);
    for (const h of light) {
      expect(h.ratio, `light mode, ${h.id}: ${h.text} on ${h.behind}`).toBeGreaterThanOrEqual(4.5);
    }

    await page.evaluate(() => (window as any).toggleDarkMode());
    await expect.poll(async () => (await handleContrast(page, 'serverChart-' + key, 'wir-server-handle-'))
      .filter((h) => h.backgroundLuminance < 0.2).length, { message: 'the chart never redrew dark' })
      .toBe(2);
    const dark = await handleContrast(page, 'serverChart-' + key, 'wir-server-handle-');
    for (const h of dark) {
      expect(h.ratio, `dark mode, ${h.id}: ${h.text} on ${h.behind}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  test('[U] opening a chart or filtering re-judges nothing; an edit judges once (R3-13)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      // render() ran the whole mirror twice — once for the status chips, once for the table —
      // and every chart toggle and filter click rendered. On a soak run that was seconds a click.
      const runs = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const start = W._mirrorRuns();
        W.toggle(W._keyOf('lag-spike'));
        W.filterStatus('FAIL');
        W.filterStatus('FAIL');
        W.toggle(W._keyOf('lag-spike'));
        const afterViews = W._mirrorRuns();
        W.toggleEnabled(W._keyOf('lag-spike'));
        return { views: afterViews - start, edit: W._mirrorRuns() - afterViews };
      });
      expect(runs.views).toBe(0);
      expect(runs.edit).toBe(1);
    });
});

test.describe('Server tab — the honest empty states', () => {
  test('[R] a run that declared no server metrics still opens the tab and says so',
    async ({ page }) => {
      // E02 has no serverMetrics block. The tab is visible anyway (unlike Compare and Asserts),
      // because the failure mode of hiding is worse: the Asserts tab's display:none meant that
      // when its payload failed to load the reveal never ran, and a reader saw a FAIL badge
      // with no way to find out why.
      await loadClean(page, 'E02');
      await expect(page.locator('.tab-btn[data-tab="server"]')).toBeVisible();
      await switchTab(page, 'server');
      await expect(page.locator('#serverBoard')).toContainText('declared no server metrics');
      // ...and no claim is made in the header.
      await expect(page.locator('.wir-server-chip')).toHaveCount(0);
    });

  test('[U] the interim report lists every declared assert and offers + Add server assert (R3-7, C-9)',
    async ({ page }) => {
      // Step 1 of the choreography: the annotations declare server asserts and nothing has been
      // collected yet. D23's flow writes the EXPECTED asserts first, then feeds in the actual
      // metrics — and this report rendered one sentence, no board, no + Add KPI and no editor, so
      // nothing could be reviewed or written until after a collection. The sentence also counted
      // the JUDGED entries, which only exist once a payload is judged: "0 server KPI(s) were
      // declared" on every interim report.
      //
      // Driven with exactly the shape GatePayloadBuilder.addServerTo writes for that report — no
      // serverGates, serverKpis or serverMetrics, serverEvaluated false — over E26's own document,
      // as the refused-payload test below drives its shape.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.evaluate(() => {
        const W = window as any;
        const d = { ...W.chartData };
        delete d.serverGates;
        delete d.serverKpis;
        delete d.serverMetrics;
        d.serverEvaluated = false;
        W.WIR.serverKpis.init(d);
      });

      const board = page.locator('#serverBoard');
      await expect(board).toContainText('12 server assert(s) were declared and none was judged');
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(12);
      await expect(page.locator('.wir-server-actions')).toContainText('+ Add server assert');
      await expect(page.locator('.wir-server-actions')).not.toContainText('Generate from this run');

      const chip = (id: string) => page.locator('#serverKpiTable tbody tr.wir-kpi-row')
        .filter({ has: page.locator(`.wir-kpi-name:text-is("${id}")`) }).locator('.wir-chip');
      // What needs no series is exact without one — a file defect, a parked assert, a load rule.
      await expect(chip('typo-reducer')).toHaveText('INVALID');
      await expect(chip('parked')).toHaveText('DISABLED');
      await expect(chip('needs-load')).toHaveText('LOAD NOT ACHIEVED');
      // Everything else waits for its actual half, and says so.
      await expect(chip('lag-spike')).toHaveText('NOT EVALUATED');
      await expect(page.locator('.wir-uncollected-row').first()).toContainText('Not collected yet');
      await expect(board).toContainText('nothing collected yet');
      // The status filter chip for those rows reads as the row's chip does, never the raw token
      // (review:C) — NOT_EVALUATED is a value appended past the fixed order on this report.
      const statusChips = await page.locator('.wir-server-filters .wir-filter-chip[onclick*="filterStatus"]')
        .allTextContents();
      expect(statusChips.some((c) => /^NOT EVALUATED \d+$/.test(c)), statusChips.join(' | ')).toBe(true);
      expect(statusChips.some((c) => c.indexOf('_') >= 0), 'a raw token on a chip: ' + statusChips.join(' | '))
        .toBe(false);

      // The editor works here, and its lookup says what it can: nothing has been collected.
      await page.click('.wir-server-actions .wir-btn-primary');
      await expect(page.locator('#serverEditor .wir-validate'))
        .toContainText('Nothing has been collected for this report yet');
      await page.locator('#serverEditor [data-field="id"]').fill('first-expected');
      await page.locator('#serverEditor [data-field="query"]').fill('kafka_lag');
      await page.locator('#serverEditor [data-field="fail.value"]').fill('500');
      await page.click('#serverEditorSave');
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(13);
      await expect(chip('first-expected')).toHaveText('NOT EVALUATED');
    });

  test('[U] a block with sources and no asserts yet is where authoring starts (R1-6)', async ({ page }) => {
    // `asserts: []` beside declared sources is the natural first step. Java calls it "not declared",
    // and the tab used to repeat that: "This run declared no server metrics. Add a serverMetrics
    // block…" — over a file that has one — with no + Add server assert.
    await loadClean(page, 'E26');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts = [];
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await expect(page.locator('.wir-server-actions')).toContainText('+ Add server assert');
    await expect(page.locator('#serverBoard')).not.toContainText('declared no server metrics');
    // Each component's card says it is empty and offers its own + metric (design §3).
    await expect(page.locator('#serverBoard')).toContainText('No server asserts on kafka yet');
    await expect(page.locator('.wir-component-card[data-component="kafka"] .wir-component-add')).toBeVisible();
  });

  test('[U] a document that drops the block does not claim the run declared nothing (R1-6)',
    async ({ page }) => {
      // An Open file… of a client-only annotations file, or an Edit document… that deletes the
      // block: the header still carries the build's server verdict, and the tab said "This run
      // declared no server metrics" beside it.
      await loadClean(page, 'E26');
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        delete doc.serverMetrics;
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      const board = page.locator('#serverBoard');
      await expect(board).not.toContainText('This run declared no server metrics');
      await expect(board).toContainText('declare no server asserts');
      await expect(board).toContainText('generated with 12');
      await expect(board).toContainText('the header still shows the verdict the build took');
      await expect(page.locator('.wir-server-chip')).toContainText('Kafka FAIL');
    });

  test('[U] ...and on the interim report it claims no verdict the header does not show (review:D)',
    async ({ page }) => {
      // Step 1 of the choreography judges nothing, so its header has no server verdict. Dropping the
      // block there said "the header still shows the verdict the build took on those" all the same.
      await loadClean(page, 'E26');
      await page.evaluate(() => {
        const W = window as any;
        const d = { ...W.chartData };
        delete d.serverGates;
        delete d.serverKpis;
        delete d.serverMetrics;
        d.serverEvaluated = false;
        W.WIR.serverKpis.init(d);
        W.WIR.asserts.openDocument();
        const doc = JSON.parse(W.WIR.document.text());
        delete doc.serverMetrics;
        W.WIR.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.WIR.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      const board = page.locator('#serverBoard');
      await expect(board).toContainText('declare no server asserts');
      await expect(board).toContainText('generated with 12 of them, declared and not yet collected');
      await expect(board).not.toContainText('header still shows');
    });

  test('[U] a block holding only a retired key and no source points at nothing it does not draw (review:R)',
    async ({ page }) => {
      // {"packs": [...]} or {"kpis": [...]} alone is refused, and the finding says why. The board
      // beside it claimed "This file declares server-metrics sources" and twice pointed at
      // "+ Add server assert" — a button the toolbar draws only when a source is declared.
      const blocks = [{ packs: ['kafka'] }, { kpis: [{ id: 'old', source: 'rec', query: 'cpu' }] }];
      for (const block of blocks) {
        for (const interim of [false, true]) {
          const what = JSON.stringify(block) + (interim ? ' on the interim report' : '');
          await loadClean(page, 'E26');
          await page.evaluate(({ b, step1 }) => {
            const W = window as any;
            if (step1) {
              const d = { ...W.chartData };
              delete d.serverGates;
              delete d.serverKpis;
              delete d.serverMetrics;
              d.serverEvaluated = false;
              W.WIR.serverKpis.init(d);
            }
            W.WIR.asserts.openDocument();
            const doc = JSON.parse(W.WIR.document.text());
            doc.serverMetrics = b;
            W.WIR.asserts.setDocumentText(JSON.stringify(doc, null, 2));
            W.WIR.asserts.applyDocument();
          }, { b: block, step1: interim });
          await switchTab(page, 'server');
          const board = page.locator('#serverBoard');
          await expect(board.locator('.wir-server-findings'), what).toContainText('is not');
          await expect(page.locator('.wir-server-actions'), what).not.toContainText('+ Add server assert');
          await expect(board, what).not.toContainText('declares server-metrics sources');
          await expect(board, what).not.toContainText('Add server assert');
          // ...and it offers the way forward it can draw: the first component's type cards.
          await expect(board, what).toContainText('so add one first');
          await expect(board.locator('.wir-first-component'), what).toBeVisible();
        }
      }
    });

  test('[R] a refused payload says it was refused and why, never that none was given',
    async ({ page }) => {
      // L9-4 / L3-12. A payload that was configured and refused (D12: not embedded) used to reach
      // the page as nothing at all, so the tab said "no collected payload was given — run
      // wir-collect": false, and its remedy re-runs the command whose output was just refused.
      // The likeliest refusal is the title trap, which that sentence cannot even hint at.
      //
      // What the generator ships for a refusal is pinned in Java
      // (ServerMetricsGenerationTest.aRefusedPayloadReachesThePageAsARefusalWithItsReasonNotAsNoPayload);
      // this drives the tab with exactly that shape over E26's own document, so no fixture has to
      // be generated broken. The detail carries markup on purpose: it is payload text, and the
      // tab must not run it.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await page.evaluate(() => {
        const W = window as any;
        const d = { ...W.chartData };
        delete d.serverGates;
        delete d.serverKpis;
        delete d.serverMetrics;
        d.serverEvaluated = false;
        d.serverPayloadRefused = {
          reason: 'FOREIGN_RUN',
          detail: 'the server-metrics payload sm.json belongs to run 0123456789abcdef, and this is run '
            + 'fedcba9876543210 — wir-collect\'s --test-name must be the same text as '
            + '-Jwebinsight.report.title <img src=x onerror="window.__refusedXss=1">',
        };
        W.WIR.serverKpis.init(d);
      });

      const board = page.locator('#serverBoard');
      await expect(board.locator('[data-wir-server-refused="FOREIGN_RUN"]')).toHaveCount(1);
      await expect(board).toContainText('was refused');
      await expect(board).toContainText('none of the 12 declared server assert(s) was judged');
      await expect(board).toContainText('--test-name must be the same text as -Jwebinsight.report.title');
      await expect(board).not.toContainText('no collected payload was given');
      // Escaped, not executed.
      await expect(board.locator('img')).toHaveCount(0);
      expect(await page.evaluate(() => (window as any).__refusedXss)).toBeUndefined();
    });
});

/* Exploratory, 2026-10-02: the Server tab's layout. Each of these was read off a screenshot of a
   tab whose numbers were all right — which is exactly why no assertion had looked. */
test.describe('Server tab — layout', () => {
  test('[R] F7: the board and Collection tables are styled — cells padded, headers over their values',
    async ({ page }) => {
      // Both tables are class "data-table", and no stylesheet has a rule for it: td and th
      // padding 0px, headers browser-centred. Adjacent cells ran together ("kafkarec" across
      // Gate/Source, "nothingmax" across Query/Reduce); centred headers sat over left-aligned
      // values (Collection: "Type" at x=228 over "file" at x=132); and on an edited row the 3px
      // inset bar was painted over the first letters of "edited" and "live". The Samplers table
      // (.stats-table) pads 6px 8px.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      const columns = (selector: string) => page.evaluate((sel) => {
        const table = document.querySelector(sel) as HTMLTableElement | null;
        if (!table) return null;
        const ths = Array.from(table.querySelectorAll('thead th'));
        const row = Array.from(table.querySelectorAll('tbody tr'))
          .find((r) => r.children.length === ths.length);
        const align = (a: string) => (a === 'start' ? 'left' : a === 'end' ? 'right' : a);
        return ths.map((th, i) => {
          const h = getComputedStyle(th);
          const d = getComputedStyle(row!.children[i]);
          return {
            header: (th.textContent || '').trim(),
            th: [parseFloat(h.paddingLeft), parseFloat(h.paddingRight)],
            td: [parseFloat(d.paddingLeft), parseFloat(d.paddingRight)],
            thAlign: align(h.textAlign),
            tdAlign: align(d.textAlign),
          };
        }).filter((c) => c.header);
      }, selector);

      for (const [name, selector] of [['the board', '#serverKpiTable table'], ['Collection', '.wir-collection table']]) {
        const cols = await columns(selector);
        expect(cols, `${name}: no table at ${selector}`).not.toBeNull();
        expect(cols!.length).toBeGreaterThan(3);
        for (const c of cols!) {
          expect(Math.min(...c.th), `${name}, "${c.header}": an unpadded header cell`).toBeGreaterThan(0);
          expect(Math.min(...c.td), `${name}, "${c.header}": an unpadded body cell`).toBeGreaterThan(0);
          expect(c.thAlign, `${name}, "${c.header}": the header is not aligned with its values`)
            .toBe(c.tdAlign);
        }
      }

      // An edited row's marker bar sits in the cell's padding, not over its text.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W._commitDrag(W._keyOf('lag-spike'), 'fail', 1000);
      });
      const first = page.locator('#serverKpiTable tbody tr.wir-kpi-row.wir-edited > td').first();
      await expect(first).toContainText('edited');
      const cell = await first.evaluate((td) => {
        const s = getComputedStyle(td);
        const bar = /(\d+(?:\.\d+)?)px/.exec(s.boxShadow);
        return { padding: parseFloat(s.paddingLeft), bar: bar ? parseFloat(bar[1]) : 0, shadow: s.boxShadow };
      });
      expect(cell.bar, `the edited row draws its bar (${cell.shadow})`).toBeGreaterThan(0);
      expect(cell.padding, 'the edited bar is painted over the cell\'s text').toBeGreaterThan(cell.bar);
    });

  test('[R] F8: the Server tab\'s gate row is a row of badges, never the Summary verdict card',
    async ({ page }) => {
      // The toolbar's badge row is <div class="wir-gate-strip">, the class of the client verdict
      // CARD (border, 4px left border, shadow, 24px padding, 24px margin); the Server tab's rule
      // overrides only display, wrap and gap. So E26's two small badges sat in an oversized card —
      // and a report with no serverGates (the interim report, a refused payload) drew an EMPTY
      // bordered card under its sentence.
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      const styles = await page.evaluate(() => {
        const badge = document.querySelector('#serverBoard .wir-gate-badge');
        const card = Array.from(document.querySelectorAll('.wir-gate-strip'))
          .find((e) => !e.closest('#serverBoard'));
        const of = (el: Element) => {
          const s = getComputedStyle(el);
          return {
            shadow: s.boxShadow,
            borders: [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth],
            padTop: parseFloat(s.paddingTop),
          };
        };
        return { row: badge ? of(badge.parentElement!) : null, card: card ? of(card) : null };
      });
      expect(styles.row, 'E26 draws component badges on the Server tab').not.toBeNull();
      expect(styles.card, 'E26 draws the client verdict card').not.toBeNull();
      // Since the component cards (design §3) a badge sits in its card's header — a card by design
      // — and never in the client verdict card's class, whose padding and shadow are the Summary's.
      const placement = await page.evaluate(() => {
        const badge = document.querySelector('#serverBoard .wir-gate-badge')!;
        return { strip: !!badge.closest('.wir-gate-strip'), head: !!badge.closest('.wir-component-head') };
      });
      expect(placement).toEqual({ strip: false, head: true });
      expect(styles.row!.shadow, 'the card header carries the verdict card\'s shadow').toBe('none');
      expect(styles.row!.padTop, 'the card header carries the verdict card\'s padding')
        .toBeLessThan(styles.card!.padTop);

      // ...and nothing on the board is an empty bordered box, with or without gates to show.
      const emptyBoxes = () => page.evaluate(() =>
        Array.from(document.querySelectorAll('#serverBoard div, #serverBoard section, #serverBoard p'))
          .filter((el) => {
            const h = el as HTMLElement;
            if (h.children.length || (h.textContent || '').trim() || !h.offsetHeight) return false;
            const s = getComputedStyle(h);
            return s.boxShadow !== 'none'
              || [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth]
                .some((w) => parseFloat(w) > 0);
          })
          .map((el) => el.tagName.toLowerCase() + '.' + (el as HTMLElement).className));
      expect(await emptyBoxes(), 'E26').toEqual([]);

      for (const refused of [false, true]) {
        await page.evaluate((r) => {
          const W = window as any;
          const d = { ...W.chartData };
          delete d.serverGates;
          delete d.serverKpis;
          delete d.serverMetrics;
          d.serverEvaluated = false;
          if (r) d.serverPayloadRefused = { reason: 'FOREIGN_RUN', detail: 'the payload belongs to another run' };
          W.WIR.serverKpis.init(d);
        }, refused);
        const what = refused ? 'a refused payload' : 'the interim report';
        await expect(page.locator('#serverBoard'), what).toContainText(refused ? 'was refused' : 'were declared');
        expect(await emptyBoxes(), `${what}: an empty bordered card`).toEqual([]);
      }
    });

  test('[R] F9: at 900px the Server tab does not scroll the page sideways, as no other tab does',
    async ({ page }) => {
      // At a 900px viewport every other tab's document is 900 wide; the Server tab's was 985 —
      // the board table does not fit and nothing contains it, so the whole page scrolls and the
      // Criteria column is cut at the card's edge. (At 390px it is 1327.) The Samplers table is
      // wider still and does not do this.
      await page.setViewportSize({ width: 900, height: 800 });
      await loadClean(page, 'E26');
      const tabs = await page.evaluate(() => Array.from(document.querySelectorAll('.tab-btn'))
        .filter((b) => (b as HTMLElement).offsetParent !== null)
        .map((b) => b.getAttribute('data-tab')!));
      expect(tabs).toContain('server');
      const widths: Record<string, number> = {};
      for (const t of tabs) {
        await switchTab(page, t);
        widths[t] = await page.evaluate(() => document.documentElement.scrollWidth);
      }
      const viewport = await page.evaluate(() => document.documentElement.clientWidth);
      expect(widths.server, `document scrollWidth per tab at ${viewport}px: ${JSON.stringify(widths)}`)
        .toBeLessThanOrEqual(viewport);
    });
});
