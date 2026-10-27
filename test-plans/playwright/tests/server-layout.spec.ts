import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * The Server tab at the widths people use and in the themes they read it in (Batch 6,
 * plans/server-tab-followups.md, SF32-SF35 and SF37), against E26 and E27.
 *
 * Every bound here is absolute — a pixel the reader can or cannot see, a contrast ratio of the
 * colours the canvas was given — never "as the other tabs do": at 390px every other tab already
 * scrolls the page sideways (525/390, from the time-range bar), so a relative pin would pass on
 * anything. And every chart is measured off ECharts' own display list, because the DOM only says a
 * canvas exists.
 */

/** Open these rows' charts. */
async function openRows(page: Page, ids: string[]) {
  for (const id of ids) {
    await page.evaluate((k) => { const W = (window as any).WIR.serverKpis; W.toggle(W._keyOf(k)); }, id);
  }
  for (const id of ids) {
    await expect.poll(() => page.evaluate((k) => {
      const W = (window as any).WIR.serverKpis;
      const el = document.getElementById('serverChart-' + W._keyOf(k));
      return !!el && !!(window as any).echarts.getInstanceByDom(el);
    }, id), { message: `${id}'s chart is drawn` }).toBe(true);
  }
}

type Paint = {
  w: number, h: number,
  backgroundOption: string | null,
  /** The colour behind the canvas: the chart's own background, or the first opaque one under it. */
  paper: string,
  grid: { x: number, y: number, w: number, h: number },
  page: { left: number, right: number },
  /** The wrapper's visible box, in page coordinates: what the reader sees of a scrolling card. */
  wrapper: { left: number, right: number } | null,
  texts: { t: string, x: number, y: number, w: number, h: number, fill: string, opacity: number }[],
  handles: { id: string, x: number, top: number, bottom: number, w: number, stroke: string }[],
  lines: { value: number, color: string, opacity: number, label: string, labelShown: boolean }[],
  axisNames: string[],
  series: string[],
};

/** What one chart painted, read off its ECharts instance and its zrender display list. */
async function paint(page: Page, domId: string): Promise<Paint | null> {
  return page.evaluate((id) => {
    const el = document.getElementById(id);
    const inst = el && (window as any).echarts.getInstanceByDom(el);
    if (!el || !inst) return null;
    const opt = inst.getOption();
    let paper: string | null = null;
    const own = opt.backgroundColor;
    if (typeof own === 'string' && own !== 'transparent' && !/,\s*0\)$/.test(own)) paper = own;
    for (let n: Element | null = el; !paper && n; n = n.parentElement) {
      const c = getComputedStyle(n).backgroundColor;
      if (c && c !== 'transparent' && !/,\s*0\)$/.test(c)) paper = c;
    }
    const grid = inst.getModel().getComponent('grid', 0).coordinateSystem.getRect();
    const r = el.getBoundingClientRect();
    const wrap = el.closest('.table-wrapper') as HTMLElement | null;
    const wr = wrap ? wrap.getBoundingClientRect() : null;
    const texts: any[] = [];
    inst.getZr().storage.getDisplayList(true).forEach((e: any) => {
      if ((e.type !== 'tspan' && e.type !== 'text') || e.invisible || e.ignore || !e.style || !e.style.text) return;
      const b = e.getBoundingRect().clone();
      b.applyTransform(e.getComputedTransform());
      texts.push({ t: String(e.style.text), x: b.x, y: b.y, w: b.width, h: b.height,
        fill: String(e.style.fill), opacity: e.style.opacity === undefined ? 1 : e.style.opacity });
    });
    const g = (opt.graphic || [])[0];
    const handles = ((g && g.elements) || []).filter((e: any) => typeof e.id === 'string' && /handle/.test(e.id))
      .map((e: any) => ({ id: e.id, x: e.position[0], top: e.position[1] + e.shape.y,
        bottom: e.position[1] + e.shape.y + e.shape.height, w: e.shape.width, stroke: String(e.style.stroke) }));
    const lines: any[] = [];
    (opt.series || []).forEach((s: any) => ((s.markLine && s.markLine.data) || []).forEach((d: any) => {
      lines.push({ value: d.yAxis !== undefined ? d.yAxis : d.xAxis, color: String(d.lineStyle && d.lineStyle.color),
        opacity: d.lineStyle && d.lineStyle.opacity !== undefined ? d.lineStyle.opacity : 1,
        label: String(d.label && d.label.formatter), labelShown: !(d.label && d.label.show === false) });
    }));
    const names = ([] as string[]).concat((opt.xAxis || []).map((a: any) => a.name), (opt.yAxis || []).map((a: any) => a.name))
      .filter((n: any) => typeof n === 'string' && n.length > 0);
    return {
      w: inst.getWidth(), h: inst.getHeight(),
      backgroundOption: typeof own === 'string' ? own : null,
      paper: paper || 'rgb(255, 255, 255)',
      grid: { x: grid.x, y: grid.y, w: grid.width, h: grid.height },
      page: { left: r.left, right: r.right },
      wrapper: wr && wrap ? { left: wr.left, right: wr.left + wrap.clientWidth } : null,
      texts, handles, lines, axisNames: names,
      series: (opt.series || []).map((s: any) => String((s.lineStyle && s.lineStyle.color) || (s.itemStyle && s.itemStyle.color))),
    };
  }, domId);
}

async function rowChart(page: Page, id: string, which: 'time' | 'instances' = 'time') {
  const key = await page.evaluate((k) => (window as any).WIR.serverKpis._keyOf(k), id);
  return paint(page, (which === 'time' ? 'serverChart-' : 'serverInstances-') + key);
}

// ---- WCAG, on the colours the canvas was given ---------------------------------------------------

function rgba(c: string): number[] {
  let m = /^#([0-9a-f]{3})$/i.exec(c);
  if (m) return m[1].split('').map((h) => parseInt(h + h, 16)).concat(1);
  m = /^#([0-9a-f]{6})$/i.exec(c);
  if (m) return [0, 2, 4].map((i) => parseInt(m![1].substr(i, 2), 16)).concat(1);
  m = /^rgba?\(([^)]+)\)$/i.exec(c);
  if (m) {
    const p = m[1].split(',').map((x) => parseFloat(x));
    return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
  }
  throw new Error('not a colour: ' + c);
}
const lin = (v: number) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
const lum = (c: number[]) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
/** Contrast of `color` drawn at `opacity` over the opaque `paper`. */
function contrast(color: string, paper: string, opacity = 1) {
  const c = rgba(color);
  const b = rgba(paper);
  const a = c[3] * opacity;
  const drawn = [0, 1, 2].map((i) => c[i] * a + b[i] * (1 - a));
  const la = lum(drawn);
  const lb = lum(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

const overlap = (a: { x: number, y: number, w: number, h: number }, b: { x: number, y: number, w: number, h: number }) =>
  Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x))
  * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));

const handleBox = (h: Paint['handles'][number]) => ({ x: h.x, y: h.top, w: h.w, h: h.bottom - h.top });

// ---- SF32 -----------------------------------------------------------------------------------------

test.describe('SF32: the board keeps room for a name at laptop widths', () => {
  for (const id of ['E26', 'E27']) {
    test(`[R] SF32: on ${id} at 1024px and 900px the Server assert column holds a name on two lines at most, and at 1024 every row action is on the card`,
      async ({ page }) => {
        // The column is the one <col> with no width, and the six others took 900px of a table
        // whose minimum was 860: 40px at 1024 and nothing at 900, so "orders-db-connections-used-pct"
        // stood one letter per line, 653px tall. F9 (the page does not scroll sideways) stayed green.
        for (const width of [1024, 900]) {
          await page.setViewportSize({ width, height: 900 });
          await loadClean(page, id);
          await switchTab(page, 'server');
          const cards = await page.evaluate(() => Array.from(document.querySelectorAll('.wir-component-card'))
            .map((card) => {
              const wrap = card.querySelector('.table-wrapper') as HTMLElement;
              const wr = wrap.getBoundingClientRect();
              return {
                id: card.getAttribute('data-component'),
                column: card.querySelector('thead th:nth-child(2)')!.getBoundingClientRect().width,
                tallestName: Math.max(...Array.from(card.querySelectorAll('.wir-kpi-name'))
                  .map((b) => b.getBoundingClientRect().height)),
                visibleRight: wr.left + wrap.clientWidth,
                actionsRight: Array.from(card.querySelectorAll('.wir-row-actions .wir-link'))
                  .map((a) => a.getBoundingClientRect().right),
              };
            }));
          expect(cards.length, `${id} draws its component cards`).toBeGreaterThan(0);
          for (const c of cards) {
            expect(c.column, `${width}px, ${c.id}: the Server assert column`).toBeGreaterThanOrEqual(160);
            expect(c.tallestName, `${width}px, ${c.id}: the tallest name`).toBeLessThanOrEqual(48);
            if (width === 1024) {
              // The redesign's reason (plan §2 #4, "it scrolls at 1024 px, hiding the actions"): a
              // floor bought by pushing Edit and Delete off the card would be the old defect back.
              for (const right of c.actionsRight) {
                expect(right, `1024px, ${c.id}: a row action past the card's edge`).toBeLessThanOrEqual(c.visibleRight + 0.5);
              }
            }
          }
          // F9 still holds: the table scrolls inside its card, never the page.
          expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
        }
      });
  }
});

// ---- SF33 -----------------------------------------------------------------------------------------

test.describe('SF33: the picker keeps its list, and Set thresholds its table, at narrow widths', () => {
  for (const [width, height] of [[960, 1040], [900, 900], [390, 844]]) {
    test(`[R] SF33: at ${width}x${height} the picker shows metrics, and the last one can be ticked`, async ({ page }) => {
      // Below 1000px the three columns stack, and the list — a scroll container, so the one grid row
      // whose minimum is zero — took whatever the filters and the detail pane left: 148px with two
      // rows at 960x1040, 8px with none at 900x900, nothing at all at 390.
      await page.setViewportSize({ width, height });
      await loadClean(page, 'E27');
      await switchTab(page, 'server');
      await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
      const list = page.locator('#serverPickList');
      await expect(list).toBeVisible();
      await expect.poll(() => list.evaluate((el) => {
        const box = el.getBoundingClientRect();
        const rows = Array.from(el.querySelectorAll('.wir-pick-row')).filter((r) => {
          const b = r.getBoundingClientRect();
          return b.top >= box.top - 0.5 && b.bottom <= box.bottom + 0.5;
        });
        return `${el.clientHeight >= 150 ? 'tall enough' : el.clientHeight + 'px tall'}, `
          + `${rows.length >= 4 ? 'rows in view' : rows.length + ' rows in view'}`;
      }), { message: 'the list\'s height, and the metric rows wholly in view in it' })
        .toBe('tall enough, rows in view');
      // Every metric is reachable: the last one, scrolled to and ticked by a real click.
      const last = page.locator('#serverPickList .wir-pick-row input[type="checkbox"]').last();
      await last.scrollIntoViewIfNeeded();
      await last.click();
      await expect(last).toBeChecked();
    });
  }

  test('[R] SF33: at 390px Set thresholds\' table scrolls inside its panel, never past it', async ({ page }) => {
    // The table is 645px wide in a 359px panel, and nothing contained it: the whole dialog body
    // scrolled sideways, taking the Prefill chips and the heading with it.
    await page.setViewportSize({ width: 390, height: 844 });
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
    // Picked through the module, not the list: at 390 the list itself was SF33's other half.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.pickToggle('deadlocks');
      W.pickToggle('transaction.longest.seconds');
      W.pickNext();
    });
    await expect(page.locator('.wir-thresholds tr[data-metric="deadlocks"]')).toBeVisible();
    const m = await page.evaluate(() => {
      const panel = document.querySelector('#serverDialog .wir-slideover') as HTMLElement;
      const body = panel.querySelector('.wir-slideover-body') as HTMLElement;
      const table = panel.querySelector('.wir-thresholds') as HTMLElement;
      const wrap = table.closest('.table-wrapper') as HTMLElement | null;
      return {
        panelRight: panel.getBoundingClientRect().right,
        bodyScroll: body.scrollWidth, bodyWidth: body.clientWidth,
        wrapRight: wrap ? wrap.getBoundingClientRect().right : null,
        wrapScrolls: wrap ? wrap.scrollWidth > wrap.clientWidth : false,
      };
    });
    expect(m.bodyScroll, 'the dialog body scrolls sideways').toBeLessThanOrEqual(m.bodyWidth);
    expect(m.wrapRight, 'the table has a wrapper that holds it').not.toBeNull();
    expect(m.wrapRight!).toBeLessThanOrEqual(m.panelRight);
    expect(m.wrapScrolls, 'the table, wider than the panel, scrolls in its wrapper').toBe(true);
  });

  test('[R] SF33: at 390px Generate\'s review table scrolls inside its panel, never past it', async ({ page }) => {
    // The same defect as Set thresholds', in the review: body scrollWidth 533 in a 358px body, the
    // table in no wrapper (E27: E26 has no seed to review). Removing the review's wrapper left every spec green.
    await page.setViewportSize({ width: 390, height: 844 });
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.evaluate(() => (window as any).WIR.serverKpis.reviewSeeds());
    await expect(page.locator('#serverDialog .wir-seed-review')).toBeVisible();
    const m = await page.evaluate(() => {
      const panel = document.querySelector('#serverDialog .wir-slideover') as HTMLElement;
      const body = panel.querySelector('.wir-slideover-body') as HTMLElement;
      const table = panel.querySelector('.wir-seed-review') as HTMLElement;
      const wrap = table.closest('.table-wrapper') as HTMLElement | null;
      return {
        panelRight: panel.getBoundingClientRect().right,
        bodyScroll: body.scrollWidth, bodyWidth: body.clientWidth,
        wrapRight: wrap ? wrap.getBoundingClientRect().right : null,
        wrapScrolls: wrap ? wrap.scrollWidth > wrap.clientWidth : false,
      };
    });
    expect(m.bodyScroll, 'the dialog body scrolls sideways').toBeLessThanOrEqual(m.bodyWidth);
    expect(m.wrapRight, 'the review table has a wrapper that holds it').not.toBeNull();
    expect(m.wrapRight!).toBeLessThanOrEqual(m.panelRight);
    expect(m.wrapScrolls, 'the review, wider than the panel, scrolls in its wrapper').toBe(true);
  });
});

// ---- SF34 -----------------------------------------------------------------------------------------

test.describe('SF34: an open row\'s charts fit the card the reader can see', () => {
  for (const width of [900, 768, 390]) {
    test(`[R] SF34: at ${width}px an open row's charts and both handles are inside the card's visible edge`, async ({ page }) => {
      // The chart row is one cell across a table wider than the card, so its charts took the
      // table's width: 880px in a 684px card at 768, and the handles — the way to retune — at page x
      // 754 and 836 against a card edge of 726. R3-4 stayed green, because it bounds a handle by the
      // chart's own width rather than by what the card shows.
      await page.setViewportSize({ width, height: 900 });
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await openRows(page, ['lag-spike']);
      await expect.poll(async () => {
        const c = await rowChart(page, 'lag-spike');
        const i = await rowChart(page, 'lag-spike', 'instances');
        if (!c || !i || !c.wrapper) return 'not drawn';
        const problems: string[] = [];
        if (c.handles.length !== 2) problems.push(`${c.handles.length} handles`);
        for (const chart of [c, i]) {
          if (chart.page.left < c.wrapper.left - 0.5 || chart.page.right > c.wrapper.right + 0.5) {
            problems.push(`a chart at ${chart.page.left}..${chart.page.right} in a card showing ${c.wrapper.left}..${c.wrapper.right}`);
          }
        }
        for (const h of c.handles) {
          const left = c.page.left + h.x;
          if (left < c.wrapper.left || left + h.w > c.wrapper.right + 0.5) {
            problems.push(`${h.id} at page x ${left}..${left + h.w}, the card shows ${c.wrapper.left}..${c.wrapper.right}`);
          }
        }
        return problems.join('; ') || 'ok';
      }).toBe('ok');
    });
  }
});

// ---- SF35 -----------------------------------------------------------------------------------------

test.describe('SF35: labels, handles and axis names stop overlapping', () => {
  test('[R] SF35: every server handle stays inside its plot — one at the axis minimum too', async ({ page }) => {
    // The server's handles sat at fixed columns, centred on the band's pixel, with no clamp (the
    // client got F11's layout, the server never did): parked's fail rect ran 4.3px below the plot,
    // lag-p50-warns' warn 3.6px, and cpu-headroom dragged to fail 0 ran 205..219 against a plot
    // bottom of 212, over the time labels.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => (window as any).WIR.serverKpis.expandAll());
    const outside = async () => {
      const keys: string[] = await page.evaluate(() =>
        Array.from(document.querySelectorAll('.wir-server-chart')).map((el) => el.id));
      const problems: string[] = [];
      let seen = 0;
      for (const id of keys) {
        const c = await paint(page, id);
        if (!c) continue;
        for (const h of c.handles) {
          seen++;
          if (h.top < c.grid.y - 0.5 || h.bottom > c.grid.y + c.grid.h + 0.5) {
            problems.push(`${h.id} ${h.top}..${h.bottom} against the plot ${c.grid.y}..${c.grid.y + c.grid.h}`);
          }
        }
      }
      return seen ? problems.join('; ') || 'ok' : 'no handles drawn';
    };
    await expect.poll(outside).toBe('ok');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W._commitDrag(W._keyOf('cpu-headroom'), 'fail', 0);
    });
    await expect.poll(async () => {
      const c = await rowChart(page, 'cpu-headroom');
      const fail = c && c.handles.find((h) => /-fail$/.test(h.id));
      if (!c || !fail) return 'no fail handle';
      // The anchor still reads 0: only the rect moved.
      const anchorValue = await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const el = document.getElementById('serverChart-' + W._keyOf('cpu-headroom'))!;
        const inst = (window as any).echarts.getInstanceByDom(el);
        const e = inst.getOption().graphic[0].elements.find((x: any) => /-fail$/.test(x.id));
        return inst.convertFromPixel({ gridIndex: 0 }, [0, e.position[1]])[1];
      });
      if (Math.abs(anchorValue) > 1e-6) return `the anchor reads ${anchorValue}`;
      return fail.bottom <= c.grid.y + c.grid.h + 0.5 && fail.top >= c.grid.y - 0.5
        ? 'ok' : `${fail.top}..${fail.bottom} against ${c.grid.y}..${c.grid.y + c.grid.h}`;
    }).toBe('ok');
    await expect.poll(outside).toBe('ok');
  });

  test('[R] SF35: a band is named once, by its handle — no label sits under a handle', async ({ page }) => {
    // The band's own "fail" label sat on the top edge of its handle on every chart (34px², 17% of
    // the label), and with warn 480 beside fail 500 the warn label covered 170px² of the fail
    // handle. The handle already says "fail ⇕".
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openRows(page, ['lag-spike', 'lag-p50-warns', 'cpu-headroom']);
    const underHandles = async () => {
      const problems: string[] = [];
      for (const id of ['lag-spike', 'lag-p50-warns', 'cpu-headroom']) {
        const c = await rowChart(page, id);
        if (!c || !c.handles.length) return `${id}: no handles`;
        for (const t of c.texts) {
          if (/⇕$/.test(t.t)) continue;   // the handle's own label
          for (const h of c.handles) {
            const a = overlap(t, handleBox(h));
            if (a > 0) problems.push(`${id}: "${t.t}" under ${h.id} (${a.toFixed(1)}px²)`);
          }
        }
        const named = c.texts.filter((t) => t.t === 'warn' || t.t === 'fail').map((t) => t.t);
        if (named.length) problems.push(`${id}: the band named again beside its handle: ${named.join(', ')}`);
      }
      return problems.join('; ') || 'ok';
    };
    await expect.poll(underHandles).toBe('ok');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W._commitDrag(W._keyOf('lag-spike'), 'warn', 480);
    });
    await expect.poll(underHandles).toBe('ok');
  });

  test('[R] SF35: two close bands\' Per instance labels do not print over each other ("wafail")', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openRows(page, ['lag-spike']);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W._commitDrag(W._keyOf('lag-spike'), 'warn', 480);
    });
    await expect.poll(async () => {
      const i = await rowChart(page, 'lag-spike', 'instances');
      if (!i) return 'not drawn';
      const warn = i.texts.find((t) => t.t === 'warn');
      const fail = i.texts.find((t) => t.t === 'fail');
      if (!warn || !fail) return `labels: ${i.texts.map((t) => t.t).join(', ')}`;
      if (i.lines.map((l) => l.value).join() !== '480,500') return `lines ${i.lines.map((l) => l.value).join()}`;
      const problems: string[] = [];
      const a = overlap(warn, fail);
      if (a > 0) problems.push(`warn and fail overlap by ${a.toFixed(1)}px²`);
      for (const t of [warn, fail]) {
        if (t.x < 0 || t.y < 0 || t.x + t.w > i.w || t.y + t.h > i.h) {
          problems.push(`"${t.t}" at ${t.x},${t.y} ${t.w}x${t.h} leaves the ${i.w}x${i.h} canvas`);
        }
      }
      return problems.join('; ') || 'ok';
    }).toBe('ok');
  });

  test('[R] SF35: every axis name is inside its canvas — the y name at the top, the Per instance unit under its axis', async ({ page }) => {
    // Value over time: the y name's 12px box began 3px above the canvas on every row chart, and
    // "% ÷ load:throughput" also ran off the left edge. Per instance: the name — the verdict unit
    // since SF16 — sat at the axis's end beside a 24px margin, so "msgs" showed as "m" and a ratio
    // unit was nearly all cut.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openRows(page, ['lag-spike', 'cpu-per-rps']);
    const check = async () => {
      const problems: string[] = [];
      for (const id of ['lag-spike', 'cpu-per-rps']) {
        for (const which of ['time', 'instances'] as const) {
          const c = await rowChart(page, id, which);
          if (!c) return `${id} ${which}: not drawn`;
          for (const name of c.axisNames) {
            const t = c.texts.find((x) => x.t === name);
            if (!t) { problems.push(`${id} ${which}: "${name}" is not drawn`); continue; }
            if (t.x < -0.5 || t.y < -0.5 || t.x + t.w > c.w + 0.5 || t.y + t.h > c.h + 0.5) {
              problems.push(`${id} ${which}: "${name}" at ${t.x.toFixed(1)},${t.y.toFixed(1)} ${t.w.toFixed(0)}x${t.h.toFixed(0)} on a ${c.w}x${c.h} canvas`);
            }
          }
        }
      }
      return problems.join('; ') || 'ok';
    };
    await expect.poll(check).toBe('ok');
    // The longest a unit gets: a slope ratio, "%/min ÷ load:throughput".
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-per-rps'));
      W.set('kind', 'slope');
      W.applyEditor();
    });
    await expect.poll(async () => (await rowChart(page, 'cpu-per-rps', 'instances'))?.axisNames.join())
      .toBe('%/min ÷ load:throughput');
    await expect.poll(check).toBe('ok');
  });

  for (const [width, height] of [[1440, 900], [390, 844]]) {
    test(`[R] SF35: at ${width}px the picker chart's time labels do not print over one another`, async ({ page }) => {
      // On the 300px detail pane nine time labels read "00:13:2025:13:3003:13:35…".
      await page.setViewportSize({ width, height });
      await loadClean(page, 'E27');
      await switchTab(page, 'server');
      await page.click('.wir-component-card[data-component="orders-db"] .wir-component-add');
      await page.evaluate(() => (window as any).WIR.serverKpis.pickFocus('transaction.longest.seconds'));
      await expect.poll(async () => {
        const c = await paint(page, 'serverPickChart');
        if (!c) return 'not drawn';
        const ticks = c.texts.filter((t) => /^\d\d:\d\d/.test(t.t));
        if (ticks.length < 2) return `${ticks.length} time labels`;
        const clashes: string[] = [];
        for (let i = 0; i < ticks.length; i++) {
          for (let j = i + 1; j < ticks.length; j++) {
            if (overlap(ticks[i], ticks[j]) > 0) clashes.push(`${ticks[i].t} / ${ticks[j].t}`);
          }
        }
        return clashes.join(', ') || 'ok';
      }).toBe('ok');
    });
  }

  for (const width of [390, 640]) {
    test(`[R] SF35: at ${width}px an open row's Value over time does not print its time labels over one another`, async ({ page }) => {
      // SF34 made a row's chart the card's width (286px at 390) and its time axis had no
      // hideOverlap: nine labels, 15 overlapping pairs at 390 and 8 at 640 — "22:1226:2…". On
      // 3.1.35 the chart was 880px, off the card, and its labels had room.
      await page.setViewportSize({ width, height: 900 });
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await openRows(page, ['lag-spike']);
      await expect.poll(async () => {
        const c = await rowChart(page, 'lag-spike');
        if (!c) return 'not drawn';
        const ticks = c.texts.filter((t) => /^\d\d:\d\d/.test(t.t));
        if (ticks.length < 2) return `${ticks.length} time labels`;
        const clashes: string[] = [];
        for (let i = 0; i < ticks.length; i++) {
          for (let j = i + 1; j < ticks.length; j++) {
            if (overlap(ticks[i], ticks[j]) > 0) clashes.push(`${ticks[i].t} / ${ticks[j].t}`);
          }
        }
        return clashes.join(', ') || 'ok';
      }).toBe('ok');
    });
  }

  test('[R] SF35: at 390px the Server tab\'s toolbar wraps inside the window', async ({ page }) => {
    // The buttons could not wrap: they squeezed into three-line labels and "Download
    // report-annotations.json" ended at x=434 in a 390px window. An absolute bound, because at
    // 390 every other tab already scrolls sideways.
    await page.setViewportSize({ width: 390, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const rights = await page.locator('.wir-server-actions > *').evaluateAll((els) =>
      els.map((e) => ({ text: (e.textContent || '').trim(), right: e.getBoundingClientRect().right })));
    expect(rights.length).toBeGreaterThan(2);
    for (const r of rights) expect(r.right, `"${r.text}"`).toBeLessThanOrEqual(390);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'the Server tab at 390px')
      .toBeLessThanOrEqual(390);
  });
});

// ---- SF37 -----------------------------------------------------------------------------------------

async function setTheme(page: Page, dark: boolean, palette?: string) {
  await page.evaluate(([d, p]) => {
    localStorage.setItem('wir_darkMode', String(d));
    if (p) localStorage.setItem('wir_palette', p as string);
  }, [dark, palette || ''] as [boolean, string]);
  await page.reload({ waitUntil: 'networkidle' });
}

test.describe('SF37: the Server tab\'s charts reach contrast in both themes', () => {
  for (const dark of [false, true]) {
    const theme = dark ? 'dark' : 'light';
    test(`[R] SF37: in ${theme} mode band lines and handle borders reach 3:1 and band labels 4.5:1, on a transparent chart`,
      async ({ page }) => {
        // Handle borders were rgba 0.55 in both themes (fail 2.25:1 dark, 2.13:1 light; warn 1.53:1
        // light), the server's warn #f0a020 2.15:1 on white and fail #dc3545 4.19:1 on dark. And the
        // dark charts painted ECharts' own #100c2a inside the report's slate card.
        await loadClean(page, 'E26');
        await setTheme(page, dark);
        await switchTab(page, 'server');
        await openRows(page, ['lag-spike']);
        await expect.poll(async () => {
          const c = await rowChart(page, 'lag-spike');
          const i = await rowChart(page, 'lag-spike', 'instances');
          if (!c || !i) return 'not drawn';
          const problems: string[] = [];
          for (const chart of [c, i]) {
            // The chart paints nothing of its own: in dark mode ECharts' theme would (#100c2a), and
            // light mode never did.
            if (dark ? chart.backgroundOption !== 'transparent'
              : chart.backgroundOption !== null && chart.backgroundOption !== 'transparent') {
              problems.push(`background ${chart.backgroundOption}`);
            }
            if (dark && lum(rgba(chart.paper)) > 0.2) problems.push(`a dark chart on ${chart.paper}`);
            if (chart.lines.length !== 2) problems.push(`${chart.lines.length} band lines`);
            for (const l of chart.lines) {
              const r = contrast(l.color, chart.paper, l.opacity);
              if (r < 3) problems.push(`${l.label} line ${l.color} ${r.toFixed(2)}:1`);
            }
          }
          if (c.handles.length !== 2) problems.push(`${c.handles.length} handles`);
          for (const h of c.handles) {
            const r = contrast(h.stroke, c.paper);
            if (r < 3) problems.push(`${h.id} border ${h.stroke} ${r.toFixed(2)}:1`);
          }
          for (const kind of ['warn', 'fail']) {
            const t = i.texts.find((x) => x.t === kind);
            if (!t) { problems.push(`no Per instance "${kind}" label`); continue; }
            const r = contrast(t.fill, i.paper, t.opacity);
            if (r < 4.5) problems.push(`Per instance "${kind}" ${t.fill}@${t.opacity} ${r.toFixed(2)}:1`);
          }
          return problems.join('; ') || 'ok';
        }).toBe('ok');
      });
  }

  test('[R] SF37: a row that decided nothing keeps its Per instance label legible while its line stays muted', async ({ page }) => {
    // F4 muted a NOT_JUDGED row's line and its label alike: the label at 0.5 was 1.84:1. The line
    // carries "decided nothing"; the label still has to say which band it is (§5).
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await openRows(page, ['needs-load']);
    await expect.poll(async () => {
      const i = await rowChart(page, 'needs-load', 'instances');
      if (!i) return 'not drawn';
      const line = i.lines[0];
      const label = i.texts.find((t) => t.t === 'fail');
      if (!line || !label) return 'no fail band';
      if (line.opacity !== 0.4) return `the line at ${line.opacity}, not muted`;
      const r = contrast(label.fill, i.paper, label.opacity);
      return r >= 4.5 ? 'ok' : `the label ${label.fill}@${label.opacity} is ${r.toFixed(2)}:1`;
    }).toBe('ok');
  });

  test('[R] C2: Per instance names every bar of a capped row, inside the canvas, however long the names', async ({ page }) => {
    // Petstore's slowest-statement row (27 instances, 20 drawn, each named by its SQL text) showed
    // four labels, clipped off both edges, and no bars: ECharts' auto interval skipped the labels that
    // did not fit 260px, and containLabel cannot fit one wider than the canvas.
    await page.setViewportSize({ width: 1280, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const k = d.serverKpis.find((x: any) => x.id === 'many-instances');
      const s = d.serverMetrics.series.find((x: any) => x.query === k.query);
      s.instances.forEach((inst: any, i: number) => {
        inst.labels = { instance: 'host-' + i + '-' + 'x'.repeat(110) };
      });
      W.WIR.serverKpis.init(d);
    });
    await openRows(page, ['many-instances']);
    let c: Paint | null = null;
    await expect.poll(async () => {
      c = await rowChart(page, 'many-instances', 'instances');
      return !!c;
    }, { message: 'the Per instance chart is drawn' }).toBe(true);
    const labels = c!.texts.filter((t) => /host-\d/.test(t.t));
    expect(labels.length, labels.map((l) => l.t.slice(0, 12)).join(' ')).toBe(20);
    for (const l of labels) {
      expect(l.x, `${l.t.slice(0, 12)} starts inside the canvas`).toBeGreaterThanOrEqual(0);
      expect(l.x + l.w, `${l.t.slice(0, 12)} ends inside the canvas`).toBeLessThanOrEqual(c!.w);
    }
    expect(c!.grid.w, `plot ${c!.grid.w} of ${c!.w}`).toBeGreaterThanOrEqual(0.4 * c!.w);
    expect(c!.h, 'a row of at least 18px per bar').toBeGreaterThanOrEqual(20 * 18);
  });

  test('[R] 5.1: the Value over time tooltip is never cut by the card, however long the names', async ({ page }) => {
    // The end-of-Stage-4 session: on petstore F2 the tooltip of a row with long names sat at left -77px
    // (617px wide) and at -590px (1220px wide), clipped by the card's overflow: the reader lost the time
    // and the start of every name. Grafana renders its tooltips in a portal; ECharts' appendTo does it.
    await page.setViewportSize({ width: 1280, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const k = d.serverKpis.find((x: any) => x.id === 'many-instances');
      const s = d.serverMetrics.series.find((x: any) => x.query === k.query);
      // As long as petstore's statements: a 1221px tooltip that no side of the cursor can hold.
      s.instances.forEach((inst: any, i: number) => { inst.labels = { query: 'select ' + 'x'.repeat(200) + ' ' + i }; });
      W.WIR.serverKpis.init(d);
    });
    await openRows(page, ['many-instances']);
    const box = await page.evaluate(async () => {
      const W = (window as any);
      const el = document.getElementById('serverChart-' + W.WIR.serverKpis._keyOf('many-instances'))!;
      const inst = W.echarts.getInstanceByDom(el);
      // hover the right edge of the plot, where a wide tooltip has to flip left
      inst.dispatchAction({ type: 'showTip', x: inst.getWidth() - 40, y: inst.getHeight() / 2 });
      await new Promise((r) => setTimeout(r, 400));
      const tips = [...document.querySelectorAll('div')].filter((d) => {
        const st = getComputedStyle(d);
        return st.position === 'absolute' && st.display !== 'none' && st.visibility !== 'hidden'
          && Number(st.opacity) > 0 && /select x{10}/.test(d.textContent || '') && d.children.length > 0
          && d.getBoundingClientRect().width > 100;
      });
      const t = tips[tips.length - 1];
      if (!t) return null;
      const r = t.getBoundingClientRect();
      // the visible part: the rect cut by every ancestor that clips
      let left = r.left, right = r.right;
      for (let a = t.parentElement; a; a = a.parentElement) {
        const st = getComputedStyle(a);
        if (/(hidden|auto|scroll|clip)/.test(st.overflowX)) {
          const ar = a.getBoundingClientRect();
          left = Math.max(left, ar.left); right = Math.min(right, ar.right);
        }
      }
      return { left: r.left, right: r.right, visibleLeft: left, visibleRight: right, vw: window.innerWidth };
    });
    expect(box, 'a tooltip is shown').not.toBeNull();
    expect(box!.left, JSON.stringify(box)).toBeGreaterThanOrEqual(0);
    expect(box!.right, JSON.stringify(box)).toBeLessThanOrEqual(box!.vw);
    expect(box!.visibleLeft - box!.left, 'no ancestor clips its left: ' + JSON.stringify(box)).toBeLessThanOrEqual(0.5);
    expect(box!.right - box!.visibleRight, 'no ancestor clips its right: ' + JSON.stringify(box)).toBeLessThanOrEqual(0.5);
  });

  test('[R] C2: a Per instance name that fits is drawn whole, not cut', async ({ page }) => {
    // The end-of-Stage-4 session on petstore F2/F5: "routeId=catalog" read "routeId=cata…" and
    // "instance 1" read "instanc…", far under the 38% cap. The room was measured in 12px sans-serif
    // and the axis drew in ECharts' platform default (12px Microsoft YaHei on Windows), 3-6% wider.
    await page.setViewportSize({ width: 1280, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const k = d.serverKpis.find((x: any) => x.id === 'cpu-headroom');
      const s = d.serverMetrics.series.find((x: any) => x.query === k.query);
      s.instances[0].labels = { instance: 'routeId=identity' };
      s.instances[1].labels = {};
      W.WIR.serverKpis.init(d);
    });
    await openRows(page, ['cpu-headroom']);
    let c: Paint | null = null;
    await expect.poll(async () => { c = await rowChart(page, 'cpu-headroom', 'instances'); return !!c; }).toBe(true);
    const names = c!.texts.filter((t) => /routeId|instance/.test(t.t)).map((t) => t.t).sort();
    expect(names).toEqual(['instance 2', 'routeId=identity']);
  });

  test('[R] C4: a five-minute row never prints one minute twice on its time axis', async ({ page }) => {
    // formatClock drops the seconds once the visible range reaches two minutes, whatever tick ECharts
    // chose: petstore's 300s rows ticked every 30s and read "18:35 18:35 18:36 18:36". The client
    // Timeline of the same run ticked by the minute at every width (N9, checked on F2), so the floor
    // is the Server tab's.
    await page.setViewportSize({ width: 1280, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      // F2's shape: a window that starts 39.65s past a minute, runs 300s and has a 60s ramp-up, so
      // the row charts the 240s judged window - on which ECharts ticks every 30s.
      const start = Math.floor(d.serverMetrics.window.start / 60000) * 60000 + 39650;
      const shift = start - d.serverMetrics.window.start;
      d.serverMetrics.window = { start, end: start + 300000, rampUpEnd: start + 60000 };
      d.serverMetrics.series.forEach((s: any) => s.instances.forEach((i: any) =>
        i.points.forEach((p: any) => { p[0] += shift; })));
      W.visibleTimeRangeMs = 300000;
      W.WIR.serverKpis.init(d);
    });
    await openRows(page, ['cpu-headroom']);
    const c = await rowChart(page, 'cpu-headroom');
    const clock = c!.texts.filter((t) => /^\d{2}:\d{2}(:\d{2})?$/.test(t.t)).sort((a, b) => a.x - b.x).map((t) => t.t);
    expect(clock.length, clock.join(' ')).toBeGreaterThanOrEqual(3);
    for (let i = 1; i < clock.length; i++) {
      expect(clock[i], clock.join(' ')).not.toBe(clock[i - 1]);
    }
  });

  test('[R] C4: Per instance value labels keep a gap, not only no overlap', async ({ page }) => {
    // hideOverlap hides a label whose box intersects another; two that only touch both stay, and
    // read as one number ("1,0001,200" on petstore's catalog-db rows). At 1024 the chart is 303px
    // wide and its ticks sat 0.5px apart.
    await page.setViewportSize({ width: 1024, height: 900 });
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const k = d.serverKpis.find((x: any) => x.id === 'many-instances');
      const s = d.serverMetrics.series.find((x: any) => x.query === k.query);
      s.instances.forEach((inst: any, i: number) => inst.points.forEach((p: any) => { p[1] = i * 60; }));   // 0 .. 1200
      W.WIR.serverKpis.init(d);
    });
    await openRows(page, ['many-instances']);
    let c: Paint | null = null;
    await expect.poll(async () => { c = await rowChart(page, 'many-instances', 'instances'); return !!c; }).toBe(true);
    // The value axis: the row of labels under the plot.
    const below = c!.texts.filter((t) => t.y > c!.grid.y + c!.grid.h && /\d/.test(t.t)).sort((a, b) => a.x - b.x);
    expect(below.length, below.map((t) => t.t).join(' ')).toBeGreaterThanOrEqual(2);
    for (let i = 1; i < below.length; i++) {
      const gap = below[i].x - (below[i - 1].x + below[i - 1].w);
      expect(gap, `${below[i - 1].t} | ${below[i].t}: ${below.map((t) => t.t).join(' ')}`).toBeGreaterThanOrEqual(4);
    }
  });

  test('[R] SF37: a dark chart draws the high-contrast palette at 3:1 — its dark variant', async ({ page }) => {
    // High-contrast's #0000FF, #8800CC, #004400 and #880000 were 1.65 to 2.63:1 on ECharts' dark
    // canvas, and on the card a transparent chart shows (#111827) #666600 is 2.93:1 too: a series in
    // them all but vanished.
    await loadClean(page, 'E26');
    await setTheme(page, true, 'high-contrast');
    await switchTab(page, 'server');
    await openRows(page, ['many-instances']);
    await expect.poll(async () => {
      const c = await rowChart(page, 'many-instances');
      if (!c) return 'not drawn';
      if (c.series.length < 9) return `${c.series.length} series`;
      const weak = c.series.filter((s) => contrast(s, c.paper) < 3);
      return weak.length ? `under 3:1 on ${c.paper}: ${weak.join(', ')}` : 'ok';
    }).toBe('ok');
    // The palette the reader picked is still the one the page holds.
    expect(await page.evaluate(() => (window as any).CHART_COLORS[0])).toBe('#0000FF');
  });
});
