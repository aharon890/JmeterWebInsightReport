import { test, expect } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * The lines are the rules.
 *
 * A threshold line used to be decoration drawn from a blanket default threshold: one line, for
 * whichever of four metrics was selected, unrelated to anything you could click. Now every
 * rule judging the charted metric draws its fail band, labelled with the rule id, and the line
 * can be dragged to retune that rule.
 */

async function openTimeline(page: any, metric?: string) {
  await loadClean(page, 'E25');
  await switchTab(page, 'timeline');
  await page.waitForTimeout(700);
  if (metric) {
    await page.click(`.filter-toggle-btn[data-metric="${metric}"]`);
    await page.waitForTimeout(700);
  }
}

function markLineLabels(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    // @ts-ignore
    const chart = echarts.getInstanceByDom(el);
    const out: string[] = [];
    for (const s of chart.getOption().series) {
      if (s.markLine && s.markLine.data) {
        for (const d of s.markLine.data) {
          if (d.yAxis !== undefined && d.label) out.push(d.label.formatter);
        }
      }
    }
    return out;
  }, chartId);
}

test.describe('Rule threshold lines', () => {

  test('every rule on the charted metric draws a line, named by rule id', async ({ page }) => {
    // A bare "P95: 1000ms" told you a number you could already read off the axis and not
    // which rule it came from.
    await expectNoJsErrors(page, async () => {
      await openTimeline(page, 'p95');
      const labels = await markLineLabels(page, 'response-time-chart');

      expect(labels.some(l => l.startsWith('slow-api-p95 fail'))).toBe(true);
      expect(labels.some(l => l.startsWith('fast-api-p95 fail'))).toBe(true);
      // Every line names a rule that exists in assertions[]. Nothing produces a
      // machine-generated `legacy:` line, and a line nobody can point at a rule for is the
      // thing this whole feature replaced.
      expect(labels.some(l => l.startsWith('legacy:'))).toBe(false);
      // load:throughput judges throughput, so it must not draw on a p95 chart.
      expect(labels.some(l => l.startsWith('load:'))).toBe(false);
    });
  });

  test('the lines follow the selected metric', async ({ page }) => {
    // E25's only mean rule is the parked one, so the default view has no rule lines — and
    // switching to P95 must bring them in.
    await openTimeline(page);
    expect(await page.evaluate(() => (window as any).WIR.asserts._strictestFirst('responseTime')))
      .toEqual([]);

    await page.click('.filter-toggle-btn[data-metric="p95"]');
    await page.waitForTimeout(700);
    expect((await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('responseTime'))).length)
      .toBeGreaterThan(0);
  });

  test('the strictest rule comes first, so the binding limit is the emphasised one',
      async ({ page }) => {
    // Five identical-looking lines would suggest five independent limits when four of them
    // can never be the one that decides.
    await openTimeline(page, 'p95');
    const order = await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('responseTime'));
    expect(order[0]).toBe('slow-api-p95');   // fail > 2 ms, the tightest in the fixture
  });

  test('a p50 rule draws, whichever way its metric was spelled', async ({ page }) => {
    // The chart mapped the p50 toggle to the metric name 'median', while MetricRef kept a
    // `p50` rule's name as "p50" — so of the two spellings of one quantile, the chart only
    // ever matched one and a p50 rule silently drew nothing. Both canonicalise to p50 now.
    await openTimeline(page, 'p50');

    const drawn = await page.evaluate(() => {
      const w = (window as any).WIR.asserts;
      // Re-init with one rule of each spelling, through the real payload shape.
      const data = JSON.parse(JSON.stringify((window as any).globalChartData));
      const base = data.assertions[0];
      data.assertions = [
        { ...base, id: 'spelled-p50', metric: 'p50', fail: { op: 'gt', value: 900 },
          warn: undefined, enabled: true },
        { ...base, id: 'spelled-median', metric: 'median', fail: { op: 'gt', value: 950 },
          warn: undefined, enabled: true },
      ];
      w.init(data);
      return w._rulesForChart('responseTime').map((r: any) => r.id);
    });

    expect(drawn, 'a rule written `p50` drew no line').toContain('spelled-p50');
  });

  test('a disabled rule draws no line', async ({ page }) => {
    await openTimeline(page, 'p95');
    const drawn = await page.evaluate(
      () => (window as any).WIR.asserts._rulesForChart('responseTime').map((r: any) => r.id));
    expect(drawn).not.toContain('parked-rule');
  });

  test('the error-rate chart draws its own rules', async ({ page }) => {
    await openTimeline(page);
    const drawn = await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('errorRate'));
    expect(drawn).toContain('api-error-rate');
  });

  /**
   * A rule is a property of a sampler, and the chart draws whichever samplers are ticked.
   *
   * Every rule on the charted metric used to draw across every chart regardless of what it
   * judged or what was on screen: with only "Slow API" ticked, the throughput chart still drew
   * `fast-api-throughput fail < 5 /s` — a limit on a sampler that was not there, over a series
   * it does not judge. A ten-sampler plan with a p95 rule each drew ten lines, nine of them
   * about nothing visible.
   */
  test('a rule stops drawing when its sampler is filtered off the chart', async ({ page }) => {
    await openTimeline(page, 'p95');
    const before = await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('responseTime'));
    expect(before).toContain('fast-api-p95');
    expect(before).toContain('slow-api-p95');

    /* The label, not the input: the checkboxes are display:none and styled through the label. */
    await page.click('#samplerFilterList label[data-sampler-name="fast api"]');
    await page.waitForTimeout(700);

    const after = await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('responseTime'));
    expect(after, 'a rule about a hidden sampler still drew').not.toContain('fast-api-p95');
    expect(after, 'a rule about a visible sampler stopped drawing').toContain('slow-api-p95');
  });

  test('a whole-run rule draws whatever the sampler filter says', async ({ page }) => {
    // It genuinely applies to the whole chart, so it is the one kind that must not be filtered.
    await openTimeline(page);
    /* The label, not the input: the checkboxes are display:none and styled through the label. */
    await page.click('#samplerFilterList label[data-sampler-name="fast api"]');
    await page.waitForTimeout(700);
    expect(await page.evaluate(
      () => (window as any).WIR.asserts._strictestFirst('throughput'))).toContain('run-throughput');
  });

  test('a level placed on the throughput chart becomes a floor, not a ceiling',
      async ({ page }) => {
    // This path hardcoded `gt`, so "+ Assert from chart" on the throughput chart authored
    // "fail if throughput EXCEEDS x" — the opposite of what clicking there means, and a rule
    // that could never fire. Rounding goes away from the observation too: rounding a floor UP
    // moves it towards the value it exists to protect.
    await openTimeline(page);
    const band = await page.evaluate(
      () => (window as any).WIR.asserts._chartBand('throughput', 4.7));
    expect(band.op).toBe('lt');
    expect(band.value).toBeLessThanOrEqual(4.7);
  });

  test('a level placed on a latency chart is still a ceiling', async ({ page }) => {
    await openTimeline(page);
    const band = await page.evaluate(() => (window as any).WIR.asserts._chartBand('p95', 812));
    expect(band.op).toBe('gt');
    expect(band.value).toBeGreaterThanOrEqual(812);
  });

  test('the run aggregate is marked, so the aggregate-vs-buckets distinction is visible',
      async ({ page }) => {
    // These rules judge the whole-run aggregate, not the per-bucket series they are drawn
    // over. Without this the line invites the reading "it fires when a bucket crosses".
    await openTimeline(page, 'p95');
    const labels = await markLineLabels(page, 'response-time-chart');
    expect(labels.some(l => l.startsWith('run aggregate'))).toBe(true);
  });

  test('line colours are resolved, because a canvas cannot read a CSS variable',
      async ({ page }) => {
    // ECharts draws to a canvas. Passing var(--color-error) through produces an invisible or
    // default-coloured line rather than an error, so this is the kind of thing that ships.
    // The token still defines the colour; it is resolved at draw time, which is also what
    // makes dark mode work — toggling it re-runs the charts.
    await openTimeline(page, 'p95');

    const style = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      for (const s of chart.getOption().series) {
        if (!s.markLine || !s.markLine.data) continue;
        for (const d of s.markLine.data) {
          if (d.yAxis === undefined || !d.label) continue;
          if (String(d.label.formatter).indexOf('run aggregate') === 0) continue;
          return d.lineStyle;
        }
      }
      return null;
    });

    expect(style).not.toBeNull();
    expect(style.color).not.toContain('var(');
    expect(style.color).toMatch(/^#|^rgb/);
  });

  /**
   * No rules means no lines and no handles — but the way in is still there.
   *
   * This asserted `.wir-place-btn` count 0, which pinned the opposite of what the button is
   * for. "+ Assert from chart" was only added once `state.rules.length` was non-zero, so it
   * was absent from exactly the report that has everything still to declare: the chart could
   * never be the way in, only a second way in for someone who had already found the first.
   * A rule still has to exist before anything is DRAWN — a line and a handle belong to a rule
   * — and that is what the first two assertions hold.
   */
  test('no rules means no lines and no handles, but the place button is still offered',
    async ({ page }) => {
      await loadClean(page, 'E02');
      await switchTab(page, 'timeline');
      await page.waitForTimeout(700);

      const labels = await markLineLabels(page, 'response-time-chart');
      expect(labels.filter(l => l.includes('fail'))).toHaveLength(0);
      expect(await page.evaluate(
        () => (window as any).WIR.asserts._rulesForChart('responseTime').length)).toBe(0);
      /* Count, not visibility: some chart sections start collapsed behind "Show N hidden
         charts", so the first button on the page legitimately is not on screen. What matters
         is that the buttons were created at all — they were not, before. */
      expect(await page.locator('.wir-place-btn').count()).toBeGreaterThan(0);
    });
});

test.describe('Drag to retune', () => {

  test('a handle is drawn for each rule line', async ({ page }) => {
    await openTimeline(page, 'p95');
    const handles = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      const g = chart.getOption().graphic;
      return g && g[0] && g[0].elements ? g[0].elements.length : 0;
    });
    expect(handles).toBeGreaterThan(0);
  });

  test('retuning a band becomes a draft, like any other edit', async ({ page }) => {
    // Dragging is a pointer gesture; what matters is that releasing it goes through the same
    // draft model as typing in the editor, so there is one place a change becomes real.
    await openTimeline(page, 'p95');
    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 5000));

    await switchTab(page, 'asserts');
    await expect(page.locator('tr[data-rule-id="slow-api-p95"] .wir-criteria'))
      .toContainText('fail > 5,000 ms');
    await expect(page.locator('#assertsUnsavedPill')).toBeVisible();
    await expect(page.locator('tr[data-rule-id="slow-api-p95"] .wir-tag-draft'))
      .toHaveText('EDITED');
  });

  test('a retuned band reaches the saved file', async ({ page }) => {
    await openTimeline(page, 'p95');
    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 4321));

    const doc = JSON.parse(await page.evaluate(
      () => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 4321 });
  });

  test('a nonsensical drag is ignored rather than written', async ({ page }) => {
    // Dragging below the axis produces a negative or zero level, which is not a threshold
    // anyone meant to set.
    await openTimeline(page, 'p95');
    const before = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === 'slow-api-p95').fail.value);

    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', -50));

    const after = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === 'slow-api-p95').fail.value);
    expect(after).toBe(before);
  });
});

test.describe('The chart agrees with the table', () => {

  test('the Summary chart draws its rule lines on first paint', async ({ page }) => {
    // initCharts built the Summary chart before WIR.asserts.init had loaded a single rule,
    // so it asked for mark lines, was correctly told there were none, and kept that answer.
    // The one chart on the tab the report opens on was the only one without rule lines.
    await loadClean(page, 'E25');

    const labels = await markLineLabels(page, 'summary-response-time-chart');
    expect(labels.some(l => l.startsWith('run aggregate')),
      'the Summary chart drew no rule lines on first paint').toBe(true);
  });

  test('committing a drag moves the line, not just the row', async ({ page }) => {
    // commitDrag called render(), which redraws the tab. The chart kept the old threshold,
    // so the dashed line, the table row and the file a save would write disagreed about the
    // same rule — and the chart is the thing being looked at while dragging.
    await openTimeline(page, 'p95');

    const before = await page.evaluate(() => (window as any).WIR.asserts._rulesForChart('responseTime')
      .find((r: any) => r.id === 'slow-api-p95').fail.value);

    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 3333));
    await page.waitForTimeout(700);

    const labels = await markLineLabels(page, 'response-time-chart');
    expect(before).not.toBe(3333);
    expect(labels.some(l => l.startsWith('slow-api-p95 fail') && l.includes('3,333')),
      `the chart still draws the old threshold: ${labels.join(' | ')}`).toBe(true);
  });

  test('a handle still means its own value after the chart is zoomed', async ({ page }) => {
    // Handles are positioned by converting a rule's value to a pixel, once. Zooming the
    // x-axis rescales y, so the pixel came to mean a different number — a 1500 ms rule's
    // handle sat where 1286 now was, and releasing it committed 1286. The tester never saw
    // that number and never chose it.
    await openTimeline(page, 'p95');

    const rule = await page.evaluate(() => (window as any).WIR.asserts
      ._rulesForChart('responseTime')[0]);

    const meansBefore = await page.evaluate((id: string) => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      return (window as any).WIR.asserts._handleValue(echarts.getInstanceByDom(el), id, 'responseTime');
    }, rule.id);
    expect(meansBefore, 'no handle was drawn for the first rule').not.toBeNull();
    expect(meansBefore).toBeCloseTo(rule.fail.value, 0);

    // Zoom the x-axis, which is what rescales y.
    await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      echarts.getInstanceByDom(el).dispatchAction({ type: 'dataZoom', start: 20, end: 60 });
    });
    await page.waitForTimeout(600);

    const meansAfter = await page.evaluate((id: string) => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      return (window as any).WIR.asserts._handleValue(echarts.getInstanceByDom(el), id, 'responseTime');
    }, rule.id);

    expect(meansAfter, 'the handle vanished after zooming').not.toBeNull();
    expect(meansAfter, 'the handle now sits where a different value is')
      .toBeCloseTo(rule.fail.value, 0);
  });

  test('the line moves for every way of changing a rule, not just dragging', async ({ page }) => {
    // commitDrag called refreshAllCharts and nothing else did, so editing, deleting,
    // disabling, discarding, converting and the wizard all left the dashed line on the old
    // value. Disabling was the worst: rulesForChart filters on enabled !== false, so the
    // chart kept drawing a limit that no longer existed.
    await openTimeline(page, 'p95');

    const drawn = () => page.evaluate(() => (window as any).WIR.asserts
      ._rulesForChart('responseTime').map((r: any) => r.id));
    const labels = () => markLineLabels(page, 'response-time-chart');

    expect(await drawn()).toContain('slow-api-p95');

    // Disable it — the line must go, without any chart interaction.
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('slow-api-p95')); });
    await page.waitForTimeout(300);
    expect((await labels()).some(l => l.startsWith('slow-api-p95 fail')),
      'a disabled rule still draws its band').toBe(false);

    // Re-enable, then delete it — same again.
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('slow-api-p95')); });
    await page.waitForTimeout(300);
    expect((await labels()).some(l => l.startsWith('slow-api-p95 fail'))).toBe(true);

    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.deleteRule(A._keyOf('slow-api-p95')); });
    await page.waitForTimeout(300);
    expect((await labels()).some(l => l.startsWith('slow-api-p95 fail')),
      'a deleted rule still draws its band').toBe(false);
  });

  test('a rule refresh keeps the reader zoomed where they were', async ({ page }) => {
    // The refresh used to dispose and rebuild every chart, so the dataZoom window was lost —
    // by the very gesture (drag after zoom) the handle fix exists to make correct.
    await openTimeline(page, 'p95');

    await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      echarts.getInstanceByDom(el).dispatchAction({ type: 'dataZoom', start: 30, end: 70 });
    });
    await page.waitForTimeout(500);

    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 4444));
    await page.waitForTimeout(500);

    const zoom = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const dz = echarts.getInstanceByDom(el).getOption().dataZoom || [];
      return dz.length ? { start: dz[0].start, end: dz[0].end } : null;
    });
    expect(zoom, 'the chart lost its dataZoom on refresh').toBeTruthy();
    expect(zoom!.start).toBeCloseTo(30, 0);
    expect(zoom!.end).toBeCloseTo(70, 0);
  });

  test('a chart with rule lines is not repainting when nothing is happening', async ({ page }) => {
    // drawDragHandles ends in setOption; setOption renders; rendering re-fired the `finished`
    // event that called drawDragHandles. An idle report sat at ~62 repaints a second burning
    // about a third of a core, on any run with a throughput or error-rate rule, from the
    // moment the Timeline tab was opened.
    await openTimeline(page, 'p95');

    const renders = await page.evaluate(async () => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      let n = 0;
      const count = () => { n++; };
      chart.on('rendered', count);
      await new Promise(r => setTimeout(r, 2000));
      chart.off('rendered', count);
      return n;
    });

    // A settled chart repaints a handful of times at most; the loop produced ~120 in 2s.
    expect(renders, `idle chart repainted ${renders} times in 2s`).toBeLessThan(20);
  });

  test('a threshold of zero can be dragged to', async ({ page }) => {
    // `value <= 0` rejected the commit, which made 0 the one level an errorRate handle could
    // not reach — and "no errors at all" is the strictest rule anyone writes, not a mistake.
    await openTimeline(page);
    await page.evaluate(() => (window as any).WIR.asserts._retune('api-error-rate', 0));

    const value = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === 'api-error-rate').fail.value);
    expect(value).toBe(0);
  });

  test('[R] AF4b: a band below a hundredth survives its handle — released in place, or moved a pixel',
    async ({ page }) => {
      // A drag landed on Math.round(v * 100) / 100 whatever the axis, so E25's run-throughput
      // (`fail lt 0.001`) became `lt 0` — a band that can never fire — the moment its handle was
      // released, even where it was picked up. The release goes through the same commit the
      // handle's ondragend calls, with the value and the pixel span read off the real chart.
      await openTimeline(page, 'p95');
      const at = (dy: number) => page.evaluate((offset: number) => {
        const el = document.getElementById('throughput-chart');
        // @ts-ignore
        const chart = echarts.getInstanceByDom(el);
        const g = (chart.getOption().graphic || [])[0];
        // A handle is keyed by its row (rowKeys), never by the rule's id.
        const key = (window as any).WIR.asserts._keyOf('run-throughput');
        const handle = ((g && g.elements) || []).find((e: any) => e.id === 'wir-handle-' + key + '-fail');
        if (!handle) return null;
        const y = (handle.position ? handle.position[1] : handle.y) + offset;
        const value = chart.convertFromPixel({ gridIndex: 0 }, [0, y])[1];
        const span = Math.abs(value - chart.convertFromPixel({ gridIndex: 0 }, [0, y + 1])[1]);
        return { value, span };
      }, dy);
      const fail = () => page.evaluate(() => (window as any).WIR.asserts._state.rules
        .find((r: any) => r.id === 'run-throughput').fail.value);

      const inPlace = await at(0);
      expect(inPlace, 'run-throughput draws no fail handle').not.toBeNull();
      await page.evaluate((p: any) => (window as any).WIR.asserts._retune('run-throughput', p.value, 'fail', p.span), inPlace);
      expect(await fail(), 'a handle released where it was rewrote its band').toBe(0.001);
      await switchTab(page, 'asserts');
      await expect(page.locator('tr[data-rule-id="run-throughput"] .wir-tag-draft')).toHaveCount(0);
      await expect(page.locator('tr[data-rule-id="run-throughput"] .wir-criteria')).not.toContainText('< 0 /s');

      // One pixel up: a real retune, on the grain that pixel spans, and never onto 0.
      await switchTab(page, 'timeline');
      const moved = await at(-1);
      await page.evaluate((p: any) => (window as any).WIR.asserts._retune('run-throughput', p.value, 'fail', p.span), moved!);
      const landed = await fail();
      expect(landed, 'a pixel up landed on 0').toBeGreaterThan(0);
      expect(Math.abs(landed - moved!.value), 'landed further than a pixel from where it was released')
        .toBeLessThanOrEqual(moved!.span);
    });
});

test.describe('Assert from chart', () => {

  test('each chart with rules offers to place one', async ({ page }) => {
    await openTimeline(page, 'p95');
    expect(await page.locator('.wir-place-btn').count()).toBeGreaterThan(0);
  });

  test('the button arms and disarms crosshair mode', async ({ page }) => {
    await openTimeline(page, 'p95');
    const button = page.locator('.wir-place-btn[data-chart="response-time-chart"]');

    await button.click();
    await expect(button).toHaveClass(/wir-place-active/);
    await expect(button).toContainText('Click the chart');

    await button.click();
    await expect(button).not.toHaveClass(/wir-place-active/);
    await expect(button).toContainText('+ Assert from chart');
  });

  /**
   * A click on a decorated element is still a click on a level.
   *
   * <p>The zrender handler bailed on `event.target`, assuming a decorated target meant a series
   * point that the echarts click handler would take. That handler only fires for SERIES
   * clicks — so a click landing on an assert threshold line, an axis pointer or any other
   * zrender element reached neither handler and silently did nothing.
   *
   * <p>E25 draws rule lines across this chart, so this sweeps the vertical middle: every one of
   * these heights is a level someone could point at, and none of them may be inert.
   */
  test('clicking anywhere on the plot places a threshold, lines included', async ({ page }) => {
    const box0 = await (async () => {
      await openTimeline(page, 'p95');
      return page.locator('#response-time-chart').boundingBox();
    })();

    for (const frac of [0.3, 0.4, 0.5, 0.6]) {
      await openTimeline(page, 'p95');
      await page.locator('.wir-place-btn[data-chart="response-time-chart"]').click();
      const box = await page.locator('#response-time-chart').boundingBox() || box0;
      await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height * frac);
      await page.waitForTimeout(400);
      await expect(page.locator('.wir-slideover'),
        `a click at ${frac} of the plot height did nothing`).toBeVisible();
      await page.click('.wir-slideover .wir-link');
    }
  });

  test('clicking a level opens the editor seeded with it', async ({ page }) => {
    await openTimeline(page, 'p95');
    await page.locator('.wir-place-btn[data-chart="response-time-chart"]').click();

    const box = await page.locator('#response-time-chart').boundingBox();
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 3);
    await page.waitForTimeout(400);

    await expect(page.locator('.wir-slideover')).toBeVisible();
    await expect(page.locator('#editorMetric')).toHaveValue('p95');
    // Seeded from where the reader pointed, rounded to a number a person would choose.
    const seeded = await page.locator('#editorfailValue').inputValue();
    expect(Number(seeded)).toBeGreaterThan(0);
  });
});

/**
 * Warn bands are drawn and draggable.
 *
 * They were not. rulesForChart filtered on `rule.fail`, so a rule contributed exactly one line
 * and the code picked the fail band — which meant the line a tester most wants to place by eye,
 * the one that says "look at this" before anything has actually broken, could not be seen or
 * moved. A warn-only rule drew nothing at all.
 */
test.describe('Warn bands', () => {

  test('a rule with both bands draws both, labelled by kind', async ({ page }) => {
    await openTimeline(page, 'p95');
    const bands = await page.evaluate(
      () => (window as any).WIR.asserts._chartBands('responseTime'));

    const slow = bands.filter((b: any) => b.id === 'slow-api-p95');
    expect(slow.map((b: any) => b.kind).sort()).toEqual(['fail', 'warn']);

    const labels = await markLineLabels(page, 'response-time-chart');
    expect(labels.some(l => l.startsWith('slow-api-p95 fail'))).toBe(true);
    expect(labels.some(l => l.startsWith('slow-api-p95 warn'))).toBe(true);
  });

  test('a fail-only rule still draws exactly one line', async ({ page }) => {
    await openTimeline(page);
    const bands = await page.evaluate(
      () => (window as any).WIR.asserts._chartBands('errorRate'));
    const errs = bands.filter((b: any) => b.id === 'api-error-rate');
    expect(errs).toHaveLength(1);
    expect(errs[0].kind).toBe('fail');
  });

  test('each band has its own drag handle', async ({ page }) => {
    // One rule, two handles. ECharts keys graphic elements by id, so without the kind in the id
    // the warn handle would replace the fail handle instead of sitting beside it.
    // A handle is named by its rule's row KEY, as the Server tab's are (client-id-keyed-handlers):
    // by id, two rules judged under one drew one handle between them.
    await openTimeline(page, 'p95');
    const { ids, key } = await page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      const g = (chart.getOption().graphic || [])[0];
      return {
        ids: ((g && g.elements) || []).map((e: any) => e.id),
        key: (window as any).WIR.asserts._keyOf('slow-api-p95')
      };
    });
    expect(key).toMatch(/^r\d+$/);
    expect(ids).toContain(`wir-handle-${key}-fail`);
    expect(ids).toContain(`wir-handle-${key}-warn`);
  });

  test('dragging the warn band retunes warn, and leaves fail alone', async ({ page }) => {
    await openTimeline(page, 'p95');
    const before = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === 'slow-api-p95').fail.value);

    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 1.5, 'warn'));

    const after = await page.evaluate(() => {
      const r = (window as any).WIR.asserts._state.rules
        .find((x: any) => x.id === 'slow-api-p95');
      return { warn: r.warn.value, fail: r.fail.value };
    });
    expect(after.warn).toBe(1.5);
    expect(after.fail, 'dragging warn moved the fail band').toBe(before);
  });

  test('a drag that would make warn unreachable is refused, not clamped', async ({ page }) => {
    // The editor already refuses this rule: `fail` is tested first, so a warn the fail band
    // already covers can never fire. The chart could author exactly what the editor refuses,
    // leaving the validator to complain about it on every later run. Refusing keeps one answer
    // to "is this rule allowed"; clamping would invent a number nobody chose.
    await openTimeline(page, 'p95');
    const original = await page.evaluate(() => {
      const r = (window as any).WIR.asserts._state.rules
        .find((x: any) => x.id === 'slow-api-p95');
      return { warn: r.warn.value, fail: r.fail.value };
    });

    // slow-api-p95 is warn > 1, fail > 2. Drag fail below warn.
    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 0.5));

    const after = await page.evaluate(() => {
      const r = (window as any).WIR.asserts._state.rules
        .find((x: any) => x.id === 'slow-api-p95');
      return { warn: r.warn.value, fail: r.fail.value };
    });
    expect(after, 'the chart authored a rule the editor refuses').toEqual(original);
  });
});

/** Every `wir-handle-` element on one chart, as the rect it occupies, with the grid it sits in. */
async function handleRects(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    // @ts-ignore
    const chart = el && echarts.getInstanceByDom(el);
    if (!chart) return null;
    const grid = chart.getModel().getComponent('grid', 0).coordinateSystem.getRect();
    // The right margin holds tick labels only when a labelled value axis is drawn there.
    let rightLabels = false;
    for (let i = 0; chart.getModel().getComponent('yAxis', i); i++) {
      const axis = chart.getModel().getComponent('yAxis', i);
      if (axis.axis && axis.axis.position === 'right' && axis.get('show') !== false
          && axis.get(['axisLabel', 'show']) !== false) rightLabels = true;
    }
    const g = chart.getOption().graphic;
    const handles = (g && g[0] && g[0].elements ? g[0].elements : [])
      .filter((e: any) => e && typeof e.id === 'string' && e.id.indexOf('wir-handle-') === 0)
      .map((e: any) => {
        const px = e.position ? e.position[0] : e.x;
        const py = e.position ? e.position[1] : e.y;
        const s = e.shape || { x: 0, y: 0, width: 0, height: 0 };
        return { id: e.id, x: px + s.x, y: py + s.y, w: s.width, h: s.height };
      });
    return {
      grid: { x: grid.x, y: grid.y, w: grid.width, h: grid.height },
      right: rightLabels ? grid.x + grid.width : chart.getWidth(),
      handles,
    };
  }, chartId);
}

/** WCAG contrast of each handle's label against its own fill laid over the chart background. */
async function handleContrast(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    // @ts-ignore
    const chart = el && echarts.getInstanceByDom(el);
    if (!chart) return [];
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
    let bg = parse(chart.getOption().backgroundColor);
    if (!bg || bg[3] === 0) {
      bg = null;
      for (let n: Element | null = el; n; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c && c[3] > 0) { bg = c; break; }
      }
    }
    const base = bg || [255, 255, 255, 1];
    const g = (chart.getOption().graphic || [])[0];
    return ((g && g.elements) || [])
      .filter((e: any) => typeof e.id === 'string' && e.id.indexOf('wir-handle-') === 0)
      .map((e: any) => {
        const behind = over(parse(e.style && e.style.fill) || [0, 0, 0, 0], base);
        const style = e.textContent && e.textContent.style;
        return {
          id: e.id as string,
          text: style ? String(style.fill) : '(none)',
          behind: 'rgb(' + behind.slice(0, 3).map(Math.round).join(',') + ')',
          backgroundLuminance: lum(base),
          ratio: ratio(parse(style && style.fill) || [0, 0, 0, 1], behind),
        };
      });
  }, chartId);
}

/* Exploratory, 2026-10-02: the handles, once the axis reaches every band. */
test.describe('Drag handles stay usable (client half)', () => {
  test('[R] F10: a rule handle\'s label is as legible in dark mode as in light', async ({ page }) => {
    // The client handle reads its label colour through token('--badge-fail-text') — which reads
    // documentElement while the theme class lives on body, so it never sees dark mode — and gets
    // #991b1b / #92400e on a 0.14-alpha fill either way. On the dark chart background that is
    // about 2.3:1 and 2.7:1: "fail ⇕" and "warn ⇕" all but vanish. The Server tab's handles have
    // the same fixed fills. Light mode is the control: the same measure passes there.
    await openTimeline(page, 'p95');
    const light = await handleContrast(page, 'response-time-chart');
    expect(light.length, 'the p95 chart draws handles').toBeGreaterThan(1);
    expect(light.some((h: any) => /-warn$/.test(h.id)) && light.some((h: any) => /-fail$/.test(h.id)),
      'both a warn and a fail handle are measured').toBe(true);
    for (const h of light) {
      expect(h.ratio, `light mode, ${h.id}: ${h.text} on ${h.behind}`).toBeGreaterThanOrEqual(4.5);
    }

    await page.evaluate(() => (window as any).toggleDarkMode());
    await expect.poll(async () => {
      const now = await handleContrast(page, 'response-time-chart');
      return now.length === light.length && now.every((h: any) => h.backgroundLuminance < 0.2);
    }, { message: 'the chart never redrew dark with its handles' }).toBe(true);
    const dark = await handleContrast(page, 'response-time-chart');
    for (const h of dark) {
      expect(h.ratio, `dark mode, ${h.id}: ${h.text} on ${h.behind}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  test('[R] F11: once the axis widens, no handle sits on another or over the axis labels',
    async ({ page }) => {
      // R3-3 widened the value axis so every band is on the plot. On E25's p95 chart that is
      // 0..1,200,000 ms: fast-api-p95 and moderate-api-p95 (both fail > 999,999) put their
      // handles on the same pixel, so only the top one can be grabbed; three more (warn 1,
      // fail 2, warn 1) collapse onto the x-axis line, two of them on one pixel again, over the
      // time tick labels; and a fail handle runs from x=1190 to 1264 past the grid's right edge
      // at 1206, over the Threads axis labels. The throughput chart stacks run-throughput and
      // load:throughput the same way. E25's extreme thresholds are deliberate (they make the
      // verdicts machine-independent); a handle that cannot be grabbed is not.
      await openTimeline(page, 'p95');
      const problems: string[] = [];
      for (const chartId of ['response-time-chart', 'throughput-chart']) {
        const c = await handleRects(page, chartId);
        expect(c, `${chartId} is drawn`).not.toBeNull();
        expect(c!.handles.length, `${chartId} draws more than one handle`).toBeGreaterThan(1);
        const { grid, right, handles } = c!;
        // Inside the plot vertically (the time labels are below it), and short of the right
        // value axis's labels where the chart has one (the Threads axis on response time).
        for (const h of handles) {
          if (h.x < grid.x || h.x + h.w > right || h.y < grid.y || h.y + h.h > grid.y + grid.h) {
            problems.push(`${chartId}: ${h.id} [${h.x},${h.y} ${h.w}x${h.h}] reaches the axis labels — `
              + `plot [${grid.x},${grid.y} ${grid.w}x${grid.h}], right limit ${right}`);
          }
        }
        for (let i = 0; i < handles.length; i++) {
          for (let j = i + 1; j < handles.length; j++) {
            const a = handles[i], b = handles[j];
            if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
              problems.push(`${chartId}: ${a.id} and ${b.id} overlap at (${a.x},${a.y}) / (${b.x},${b.y})`);
            }
          }
        }
      }
      expect(problems, problems.join('\n')).toEqual([]);
    });

  test('[R] U10a / L18: every rule label and the run aggregate\'s is drawn once, under no handle and on no other label',
    async ({ page }) => {
      // The labels were their markLines', at the right end of each line: under the handles F11 put
      // there, and on one another where two rules share a value — 814 px² under a handle and 2,075
      // px² of label on label on E25's p95 chart (EXPLORATORY-LOG, SF35) — and the run aggregate's
      // label sat on a band's line beside the band's own (CN16). They are laid out with the handles
      // now, as text the handles pass draws; a markLine draws none.
      await openTimeline(page, 'p95');
      const read = (chartId: string) => page.evaluate((id: string) => {
        const el = document.getElementById(id);
        // @ts-ignore
        const chart = el && echarts.getInstanceByDom(el);
        if (!chart) return null;
        const opt = chart.getOption();
        const formatters: string[] = [];
        const shownByMarkLine: string[] = [];
        for (const s of opt.series) {
          for (const d of ((s.markLine && s.markLine.data) || [])) {
            if (d.yAxis === undefined || !d.label || typeof d.label.formatter !== 'string') continue;
            formatters.push(d.label.formatter);
            if (d.label.show !== false) shownByMarkLine.push(d.label.formatter);
          }
        }
        const wanted = new Set(formatters);
        const texts: any[] = [];
        chart.getZr().storage.getDisplayList(true).forEach((e: any) => {
          if ((e.type !== 'tspan' && e.type !== 'text') || e.invisible || e.ignore || !e.style) return;
          if (!wanted.has(String(e.style.text))) return;
          const b = e.getBoundingRect().clone();
          b.applyTransform(e.getComputedTransform());
          texts.push({ t: String(e.style.text), x: b.x, y: b.y, w: b.width, h: b.height });
        });
        const g = (opt.graphic || [])[0];
        const handles = ((g && g.elements) || [])
          .filter((e: any) => typeof e.id === 'string' && e.id.indexOf('wir-handle-') === 0)
          .map((e: any) => {
            const px = e.position ? e.position[0] : e.x;
            const py = e.position ? e.position[1] : e.y;
            return { id: e.id, x: px + e.shape.x, y: py + e.shape.y, w: e.shape.width, h: e.shape.height };
          });
        return { formatters, shownByMarkLine, texts, handles };
      }, chartId);
      const area = (a: any, b: any) => {
        const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        return w > 0 && h > 0 ? w * h : 0;
      };
      await expect.poll(async () => {
        const problems: string[] = [];
        for (const chartId of ['response-time-chart', 'throughput-chart']) {
          const c = await read(chartId);
          if (!c) { problems.push(`${chartId}: not drawn`); continue; }
          if (!c.formatters.some((f: string) => f.indexOf('run aggregate') === 0)) problems.push(`${chartId}: no run aggregate`);
          if (c.shownByMarkLine.length) problems.push(`${chartId}: a markLine still draws ${c.shownByMarkLine.join(', ')}`);
          for (const f of c.formatters) {
            const n = c.texts.filter((t: any) => t.t === f).length;
            if (n !== 1) problems.push(`${chartId}: "${f}" drawn ${n} times`);
          }
          c.texts.forEach((t: any, i: number) => {
            for (const h of c.handles) {
              const a = area(t, h);
              if (a > 1) problems.push(`${chartId}: "${t.t}" under ${h.id} (${a.toFixed(1)}px²)`);
            }
            for (const u of c.texts.slice(i + 1)) {
              const a = area(t, u);
              if (a > 1) problems.push(`${chartId}: "${t.t}" on "${u.t}" (${a.toFixed(1)}px²)`);
            }
          });
        }
        return problems.join('\n') || 'ok';
      }).toBe('ok');
    });
});

/* Batch 6 (plans/server-tab-followups.md, SF37): what a rule line, its label and its handle are
   painted in, measured as WCAG contrast of the colour the canvas was given against what is behind
   it — the chart's own background, or, on a transparent chart, the section it sits on. */
async function openThemedTimeline(page: any, metric: string, theme: { dark?: boolean, cb?: boolean }) {
  await loadClean(page, 'E25');
  await page.evaluate(([d, c]: [boolean, boolean]) => {
    localStorage.setItem('wir_darkMode', String(d));
    localStorage.setItem('wir_colorblindMode', String(c));
  }, [!!theme.dark, !!theme.cb]);
  await page.reload({ waitUntil: 'networkidle' });
  await switchTab(page, 'timeline');
  await page.waitForTimeout(700);
  await page.click(`.filter-toggle-btn[data-metric="${metric}"]`);
  await page.waitForTimeout(700);
}

/** Every rule line, rule label and handle border on one chart, with the contrast each was drawn at. */
async function ruleInk(page: any, chartId: string) {
  return page.evaluate((id: string) => {
    const el = document.getElementById(id);
    // @ts-ignore
    const chart = el && echarts.getInstanceByDom(el);
    if (!chart) return null;
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
    const lin = (v: number) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    const lum = (c: number[]) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
    const opt = chart.getOption();
    let paper = parse(opt.backgroundColor);
    if (!paper || paper[3] === 0) {
      paper = null;
      for (let n: Element | null = el; n; n = n.parentElement) {
        const c = parse(getComputedStyle(n).backgroundColor);
        if (c && c[3] > 0) { paper = c; break; }
      }
    }
    const base = paper || [255, 255, 255, 1];
    const ratio = (color: any, opacity: number) => {
      const c = parse(color);
      if (!c) return 0;
      const a = c[3] * opacity;
      const drawn = [0, 1, 2].map((i) => c[i] * a + base[i] * (1 - a));
      const la = lum(drawn), lb = lum(base);
      return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    };
    const lines: any[] = [];
    const aggregates: any[] = [];
    for (const s of opt.series) {
      for (const d of ((s.markLine && s.markLine.data) || [])) {
        const label = d.label ? String(d.label.formatter) : '';
        if (d.yAxis !== undefined && label.indexOf('run aggregate') === 0) {
          const ls = d.lineStyle || {};
          const op = ls.opacity === undefined ? 1 : ls.opacity;
          aggregates.push({ label, color: String(ls.color), ratio: ratio(ls.color, op) });
          continue;
        }
        if (d.yAxis === undefined || !label) continue;
        const ls = d.lineStyle || {};
        lines.push({ label, kind: / warn /.test(label) ? 'warn' : 'fail', color: String(ls.color),
          width: ls.width, opacity: ls.opacity === undefined ? 1 : ls.opacity,
          ratio: ratio(ls.color, ls.opacity === undefined ? 1 : ls.opacity) });
      }
    }
    const formatters = new Set(lines.map((l) => l.label));
    const labels: any[] = [];
    chart.getZr().storage.getDisplayList(true).forEach((e: any) => {
      if ((e.type !== 'tspan' && e.type !== 'text') || e.invisible || e.ignore || !e.style) return;
      if (!formatters.has(String(e.style.text))) return;
      const op = e.style.opacity === undefined ? 1 : e.style.opacity;
      labels.push({ text: String(e.style.text), fill: String(e.style.fill), opacity: op, ratio: ratio(e.style.fill, op) });
    });
    const g = (opt.graphic || [])[0];
    const handles = ((g && g.elements) || [])
      .filter((e: any) => typeof e.id === 'string' && e.id.indexOf('wir-handle-') === 0)
      .map((e: any) => ({ id: e.id, stroke: String(e.style.stroke), ratio: ratio(e.style.stroke, 1) }));
    return {
      background: typeof opt.backgroundColor === 'string' ? opt.backgroundColor : null,
      paperLuminance: lum(base),
      errorToken: getComputedStyle(document.body).getPropertyValue('--color-error').trim(),
      lines, labels, handles, aggregates,
    };
  }, chartId);
}

test.describe('SF37: rule lines in every theme (client half)', () => {
  for (const dark of [false, true]) {
    const theme = dark ? 'dark' : 'light';
    test(`[R] SF37: in ${theme} mode rule lines and handle borders reach 3:1 and rule labels 4.5:1, on a transparent chart`,
      async ({ page }) => {
        // Warn lines were the warning token at 0.55 (1.53:1 on white), every label but the
        // strictest at 0.55 (3.24:1 light, 4.23:1 dark), and the handle borders rgba 0.55 (2.13:1
        // light, 2.25:1 dark). A non-strictest fail LINE stays dimmed at 0.45: strictestFirst's "the
        // rest are dimmed", by decision — so the check is on the emphasised fail line and on warn.
        await openThemedTimeline(page, 'p95', { dark });
        // Polled: a label fades in, and a reading mid-animation is not what the reader sees.
        await expect.poll(async () => {
          const problems: string[] = [];
          for (const chartId of ['response-time-chart', 'throughput-chart']) {
            const ink = await ruleInk(page, chartId);
            if (!ink) { problems.push(`${chartId}: not drawn`); continue; }
            if (dark && ink.background !== 'transparent') problems.push(`${chartId}: background ${ink.background}`);
            if (dark && ink.paperLuminance > 0.2) problems.push(`${chartId}: a dark chart on a light paper`);
            const judged = ink.lines.filter((l: any) => l.kind === 'warn' || l.width > 1);
            if (!judged.some((l: any) => l.kind === 'warn') || !judged.some((l: any) => l.kind === 'fail')) {
              problems.push(`${chartId}: no warn line or no emphasised fail line to measure`);
            }
            for (const l of judged) {
              if (l.ratio < 3) problems.push(`${chartId}: "${l.label}" line ${l.color}@${l.opacity} ${l.ratio.toFixed(2)}:1`);
            }
            if (!ink.labels.length) problems.push(`${chartId}: no rule label drawn`);
            for (const t of ink.labels) {
              if (t.ratio < 4.5) problems.push(`${chartId}: label "${t.text}" ${t.fill}@${t.opacity} ${t.ratio.toFixed(2)}:1`);
            }
            if (!ink.handles.length) problems.push(`${chartId}: no handle drawn`);
            for (const h of ink.handles) {
              if (h.ratio < 3) problems.push(`${chartId}: ${h.id} border ${h.stroke} ${h.ratio.toFixed(2)}:1`);
            }
          }
          return problems.join('\n');
        }).toBe('');
      });

    test(`[R] SF37: colour-blind mode reaches the rule lines, in ${theme} mode`, async ({ page }) => {
      // token() read documentElement while cb-mode redefines --color-error on body, so a fail line
      // stayed #ef4444 beside a body whose --color-error was #ea580c — although its comment said
      // the lines "follow the colourblind palettes". The handles take colour-blind orange too.
      await openThemedTimeline(page, 'p95', { dark, cb: true });
      const ink = (await ruleInk(page, 'throughput-chart'))!;
      expect(ink.errorToken, 'colour-blind mode is on').toBe('#ea580c');
      const fails = ink.lines.filter((l: any) => l.kind === 'fail');
      expect(fails.length, 'the throughput chart draws fail lines').toBeGreaterThan(0);
      for (const l of fails) expect(l.color, l.label).toBe(ink.errorToken);
      const failHandles = ink.handles.filter((h: any) => /-fail$/.test(h.id));
      expect(failHandles.length).toBeGreaterThan(0);
      for (const h of failHandles) expect(h.stroke, h.id).toBe(dark ? 'rgba(251,146,60,0.9)' : 'rgba(194,65,12,0.9)');
      // Warn is fuchsia here, not amber: beside the orange fail, amber #b45309 was one colour to a
      // deuteranope (ΔE 0.7) — the mode for colour-blind readers drew the two bands alike.
      const warnHandles = ink.handles.filter((h: any) => /-warn$/.test(h.id));
      expect(warnHandles.length, 'the throughput chart draws warn handles').toBeGreaterThan(0);
      for (const h of warnHandles) expect(h.stroke, h.id).toBe(dark ? 'rgba(240,171,252,0.9)' : 'rgba(134,25,143,0.9)');
      for (const l of ink.lines.filter((x: any) => x.kind === 'warn')) {
        expect(l.color, l.label).toBe(dark ? '#f0abfc' : '#86198f');
      }

      // The dotted "run aggregate" line is --color-success, colour-blind blue — #2563eb in dark
      // mode too, 2.83:1 on the transparent dark chart's #1e293b, under the 3:1 a chart line needs.
      const p95 = (await ruleInk(page, 'response-time-chart'))!;
      expect(p95.aggregates.length, 'the P95 chart marks the run aggregate').toBeGreaterThan(0);
      for (const a of p95.aggregates) {
        expect(a.ratio, `"${a.label}" ${a.color}`).toBeGreaterThanOrEqual(3);
      }
    });
  }

  test('[R] SF37: dark mode draws a palette\'s dark variant, and the filter dots follow it when toggled', async ({ page }) => {
    // High-contrast's #0000FF is 1.70:1 on the dark section: its series all but vanished. The dark
    // variant is drawn only while dark mode is on, so the palette picked (and light mode) is
    // untouched — and the filter bar's dots, the series' key, have to change with it.
    await loadClean(page, 'E25');
    await page.evaluate(() => localStorage.setItem('wir_palette', 'high-contrast'));
    await page.reload({ waitUntil: 'networkidle' });
    await switchTab(page, 'timeline');
    const firstDot = () => page.locator('#samplerFilterList .sampler-color-dot').nth(1)
      .evaluate((el) => getComputedStyle(el).backgroundColor);
    const seriesColors = () => page.evaluate(() => {
      const el = document.getElementById('response-time-chart');
      // @ts-ignore
      const chart = el && echarts.getInstanceByDom(el);
      return chart ? chart.getOption().series
        .map((s: any) => String((s.itemStyle && s.itemStyle.color) || (s.lineStyle && s.lineStyle.color)).toLowerCase()) : [];
    });
    await expect.poll(seriesColors).toContain('#0000ff');
    const rgb = (hex: string) => 'rgb(' + [1, 3, 5].map((i) => parseInt(hex.substr(i, 2), 16)).join(', ') + ')';
    expect(await firstDot()).toBe(rgb('#0000ff'));

    await page.evaluate(() => (window as any).toggleDarkMode());
    await expect.poll(seriesColors, 'the first series in dark mode').toContain('#6b7bff');
    expect(await seriesColors()).not.toContain('#0000ff');
    // The dot and the line it keys are one colour, after the toggle as on a reload.
    await expect.poll(firstDot).toBe(rgb('#6b7bff'));
    expect(await page.evaluate(() => (window as any).CHART_COLORS[0]), 'the reader\'s pick').toBe('#0000FF');

    // Its colour picker marks the palette slot's swatch, the colour the reader picked — the dot
    // draws the dark variant, which is no swatch, so none was marked — and picking that swatch
    // keeps the slot: stored as a custom colour, #0000FF was drawn on the dark chart at 1.70:1.
    await page.locator('#samplerFilterList .sampler-color-dot').nth(1).click();
    const selected = page.locator('.color-picker-popup.open .color-swatch.selected');
    await expect(selected, 'the current colour\'s swatch is marked').toHaveCount(1);
    expect(await selected.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(rgb('#0000ff'));
    await selected.click();
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('wir_customColors') || '{}')),
      'the slot\'s own colour stored as a custom one').toEqual({});
    await expect.poll(seriesColors).toContain('#6b7bff');
  });

  test('[R] SF37: a dark chart\'s PNG and SVG exports paint the card it sat on; a light one\'s PNG paints nothing', async ({ page }) => {
    // Dark charts are transparent on screen, so an export without a background is light text on
    // nothing wherever it is pasted. Removing both background lines left every spec green.
    await openThemedTimeline(page, 'p95', { dark: true });
    const exported = () => page.evaluate(() => {
      const el = document.getElementById('response-time-chart')!;
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      let paper: string | null = null;
      for (let n: Element | null = el; n && !paper; n = n.parentElement) {
        const c = getComputedStyle(n).backgroundColor;
        if (c && c !== 'transparent' && !/,\s*0\)$/.test(c)) paper = c;
      }
      const png = chart.getOption().toolbox[0].feature.saveAsImage;
      let href: string | null = null;
      const click = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function () { href = (this as HTMLAnchorElement).href; };
      try { (window as any).exportChartAsSvg(chart); } finally { HTMLAnchorElement.prototype.click = click; }
      const svg = href ? decodeURIComponent(String(href).replace(/^data:image\/svg\+xml;charset=utf-8,/i, '')) : '';
      return { paper, png: png.backgroundColor === undefined ? null : String(png.backgroundColor), svg };
    });
    const dark = await exported();
    expect(dark.paper, 'a dark card under the chart').not.toBeNull();
    expect(dark.png, 'the PNG paints the card').toBe(dark.paper);
    const fill = (c: string) => c.replace(/\s+/g, '');
    expect(fill(dark.svg), 'the SVG paints the card').toContain(fill(dark.paper!));

    await page.evaluate(() => (window as any).toggleDarkMode());
    await page.waitForTimeout(500);
    const light = await exported();
    expect(light.png, 'a light export keeps ECharts\' own white').toBeNull();
  });
});

test.describe('The value axis reaches the rule lines (R3-3, client half)', () => {
  /* The axis was scaled to the data and a markLine does not widen it, so a band above the data —
     every passing upward rule, a load target the run missed by a mile — drew its line and its drag
     handle off the canvas: the threshold could be dragged only once breached. The Server tab's
     charts took bandAxisBounds in M7c; these are the Asserts tab's, fixed the same way in M7d. */
  async function axisOf(page: any, chartId: string) {
    return page.evaluate((id: string) => {
      const el = document.getElementById(id);
      // @ts-ignore
      const chart = echarts.getInstanceByDom(el);
      const extent = chart.getModel().getComponent('yAxis', 0).axis.scale.getExtent();
      const grid = chart.getModel().getComponent('grid', 0).coordinateSystem.getRect();
      const g = chart.getOption().graphic;
      const handles = (g && g[0] && g[0].elements ? g[0].elements : [])
        .filter((e: any) => e && typeof e.id === 'string' && e.id.indexOf('wir-handle-') === 0)
        .map((e: any) => (e.position ? e.position[1] : e.y));
      return { extent, grid: { y: grid.y, height: grid.height }, handles };
    }, chartId);
  }

  test('[U] a load target far above the data is drawn on the plot, with its handle', async ({ page }) => {
    // E26's load:unreachable asks for 100000 req/s; the run does ~25. Its line sat ~4000 plot
    // heights above the canvas.
    await loadClean(page, 'E26');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(700);
    const before = await axisOf(page, 'throughput-chart');
    expect(before.extent[1], 'the axis stops short of the 100000 line').toBeGreaterThanOrEqual(100000);
    expect(before.handles.length).toBeGreaterThan(0);
    for (const y of before.handles) {
      expect(y, 'a handle above the grid').toBeGreaterThanOrEqual(before.grid.y - 1);
      expect(y, 'a handle below the grid').toBeLessThanOrEqual(before.grid.y + before.grid.height + 1);
    }

    // Retuned to what the run achieved, nothing needs the room: the axis goes back to the data's.
    await page.evaluate(() => (window as any).WIR.asserts._retune('load:unreachable', 10, 'fail'));
    await page.waitForTimeout(300);
    const after = await axisOf(page, 'throughput-chart');
    expect(after.extent[1], 'the widened axis outlived the line that needed it').toBeLessThan(1000);
  });

  test('[U] an axis built with a fixed max keeps it when no line needs more', async ({ page }) => {
    // The error-rate chart is 0..100 by construction; a rule inside it must not move it.
    await openTimeline(page);
    const axis = await page.evaluate(() => {
      const el = document.getElementById('error-rate-chart');
      // @ts-ignore
      const chart = el && echarts.getInstanceByDom(el);
      return chart ? chart.getModel().getComponent('yAxis', 0).axis.scale.getExtent() : null;
    });
    test.skip(axis === null, 'E25 draws no error-rate chart');
    expect(axis![1]).toBe(100);
  });
});
