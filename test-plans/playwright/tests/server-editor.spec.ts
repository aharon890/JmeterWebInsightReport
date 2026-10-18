import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * The server KPI editor (M7).
 *
 * The promise: retune a threshold, watch the verdict recompute, save the file, and have the
 * next `jmeter -g` reproduce what you were shown. The mirror (M6a) is what makes the middle
 * step true; this is the surface that reaches it, and the two states it must never confuse —
 * a verdict it can recompute, and one it must withhold.
 */

async function openServer(page: any) {
  await loadClean(page, 'E26');
  await switchTab(page, 'server');
}

function rowOf(page: any, id: string) {
  return page.locator(`#serverKpiTable tbody tr.wir-kpi-row:has-text("${id}")`).first();
}

/** Open the editor on the first KPI judged under `id`. Row handlers take a KEY, never an id. */
async function editKpi(page: any, id: string) {
  await page.evaluate((kpiId: string) => {
    const W = (window as any).WIR.serverKpis;
    W.edit(W._keyOf(kpiId));
  }, id);
}

test.describe('The KPI editor', () => {
  test('[R] the form offers the tolerance only where a tolerance means something',
    async ({ page }) => {
      await openServer(page);

      // `over` is defined on max and min, the two reducers that pick a level. On avg, last, pN
      // or slope there is no level to hold and the evaluator refuses it — so the form must not
      // offer it, or the editor is a way to write a KPI the next run rejects.
      await editKpi(page, 'lag-spike');
      await expect(page.locator('#serverEditor')).toContainText('Tolerance');

      await page.evaluate(() => (window as any).WIR.serverKpis.set('kind', 'avg'));
      await expect(page.locator('#serverEditor')).not.toContainText('Tolerance');

      await page.evaluate(() => (window as any).WIR.serverKpis.set('kind', 'min'));
      await expect(page.locator('#serverEditor')).toContainText('Tolerance');
    });

  test('[R] Validate query is an exact lookup, never an estimate', async ({ page }) => {
    await openServer(page);
    await editKpi(page, 'lag-spike');

    // D22: the payload holds the series the collector actually fetched. A query in it can be
    // answered precisely; one that is not cannot be answered at all. An editor that guessed
    // "looks like 3 series" would be teaching the author to trust a number it invented.
    await expect(page.locator('.wir-validate')).toHaveText(/Collected: 1 instance/);
    await expect(page.locator('.wir-validate')).toHaveText(/coverage 100%/);

    await page.evaluate(() => (window as any).WIR.serverKpis.set('query', 'never_collected'));
    await expect(page.locator('.wir-validate')).toHaveText(/Not collected/);
    await expect(page.locator('.wir-validate')).toHaveText(/re-run wir-collect/);
  });

  test('[B5] Validate query on a capped series counts the collector\'s instances, not the drawn ones (SF14)',
    async ({ page }) => {
      // E26's `many` came back with 21 instances; the writer ships 20 and says so (instanceCount).
      // The line counted the 20 — "Collected: 20 instances" beside a board cell and a JSON saying 21.
      await openServer(page);
      expect(await page.evaluate(() => {
        const s = (window as any).WIR.serverKpis._state.metrics.series.find((x: any) => x.query === 'many');
        return [s.instanceCount, s.instances.length];
      })).toEqual([21, 20]);
      await editKpi(page, 'many-instances');
      const validate = page.locator('#serverEditorValidate');
      await expect(validate).toHaveText(/^Collected: 21 instances \(20 drawn\) · coverage 100% of the collected window · status OK · capped in this page/);
      await expect(validate).not.toContainText('Collected: 20');

      // The 4c line for a form with no query yet: a stale-fixture canary (U8k), since the fixtures
      // once carried the sentence one edit behind the tree.
      await page.evaluate(() => (window as any).WIR.serverKpis.set('query', ''));
      await expect(validate).toHaveText('Write a query, or choose a catalogue metric.');
    });

  test('[R] a KPI the next run would refuse cannot be saved', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => (window as any).WIR.serverKpis.addKpi());

    // Nothing to collect yet. (This test opened on "No id yet", whose premise was false: Java
    // derives the id of an assert the file leaves unnamed — SF27, the next test.)
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('query');
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeDisabled();

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.set('id', 'new-kpi');
      W.set('query', 'lag');
    });
    // Still no band: a KPI with neither warn nor fail can never decide anything, and the
    // evaluator refuses it. Refusing here is the point — the alternative is a download that
    // exits 1 on the next run.
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('band');

    await page.evaluate(() => (window as any).WIR.serverKpis.set('fail.value', '900'));
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
  });

  test('[R] SF27: an assert saved with no id is given the id Java derives, as + metric and Generate give theirs', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => (window as any).WIR.serverKpis.addKpi());
    // E26's first component is kafka. ServerKpi.deriveId: slug(component)-slug(query), the query
    // slug cut at 40 characters — "sum(rate(http_requests_total[1m]))" is
    // sum-rate-http-requests-total-1m (the parity vectors hold kpiFromJson to Java's derivation).
    await page.fill('#serverEditor [data-field="query"]', 'sum(rate(http_requests_total[1m]))');
    await page.fill('#serverEditor [data-field="fail.value"]', '900');
    const idBox = page.locator('#serverEditor [data-field="id"]');
    await expect(idBox).toHaveValue('');
    await expect(idBox).toHaveAttribute('placeholder',
      'kafka-sum-rate-http-requests-total-1m (derived — leave empty to use it)');
    // The editor alone refused this ("Give the server assert an id.") while the picker and
    // Generate wrote id-less asserts all along.
    await expect(page.locator('#serverEditorProblem')).toHaveCount(0);
    await page.click('#serverEditorSave');

    const row = page.locator('tr.wir-kpi-row', { has: page.locator('.wir-kpi-name:text-is("kafka-sum-rate-http-requests-total-1m")') });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('derived id');
    const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
      .serverMetrics.asserts.find((k: any) => k.query === 'sum(rate(http_requests_total[1m]))'));
    expect(written.id, 'a derived id is never written into the file').toBeUndefined();

    // A second one would be judged under the same derived id: that is still refused, by name.
    await page.evaluate(() => (window as any).WIR.serverKpis.addKpi());
    await page.fill('#serverEditor [data-field="query"]', 'sum(rate(http_requests_total[1m]))');
    await page.fill('#serverEditor [data-field="fail.value"]', '900');
    await expect(page.locator('#serverEditorProblem')).toHaveText("Another server assert is already called "
      + "'kafka-sum-rate-http-requests-total-1m', the id this one would be given — give it an id.");
    await expect(page.locator('#serverEditorSave')).toBeDisabled();
  });

  test('[U] SF43: the form opens on what the assert judges — Id last, and Fail in view at 1440×900', async ({ page }) => {
    // SWEEP-8: Id led the form, required-looking, and on E27 the Fail field's top was 834 against
    // a body bottom of 835. Java derives an empty id (SF27), so it waits at the bottom.
    await page.setViewportSize({ width: 1440, height: 900 });
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._state.declaredKpis[0].key);
    });
    const labels = await page.locator('#serverEditorBody .wir-field > span:first-child').allTextContents();
    expect(labels.slice(0, 5)).toEqual(['Component', 'Metric', 'Warn', 'Fail', 'If saved']);
    expect(labels.slice(-2)).toEqual(['Description', 'Id']);
    const fold = await page.locator('#serverEditor [data-field="fail.value"]').evaluate((el) => {
      const body = el.closest('.wir-slideover-body') as HTMLElement;
      return { bottom: el.getBoundingClientRect().bottom, bodyBottom: body.getBoundingClientRect().bottom,
        scrolled: body.scrollTop };
    });
    expect(fold.scrolled).toBe(0);
    expect(fold.bottom, 'the Fail field is below the fold').toBeLessThanOrEqual(fold.bodyBottom);
  });

  test('[U] SF43: "If saved" is the board\'s verdict as the band is typed — and the board agrees after Save', async ({ page }) => {
    await openServer(page);
    await editKpi(page, 'lag-spike');
    const line = page.locator('#serverEditorVerdict');
    await expect(line.locator('.wir-chip')).toHaveText('FAIL');
    await expect(line).toContainText('actual 900 msgs · breached > 500 msgs · judged live');

    // Typed, not set: the line follows the keystrokes, and the field keeps its caret.
    const fail = page.locator('#serverEditor [data-field="fail.value"]');
    await fail.fill('');
    await fail.pressSequentially('5000');
    await expect(fail).toBeFocused();
    await expect(line.locator('.wir-chip')).toHaveText('WARN');
    await expect(line).toContainText('breached > 300 msgs');
    // The row behind the panel keeps the saved verdict until Save...
    await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('FAIL');
    await page.click('#serverEditorSave');
    // ...and then reads what the line said.
    await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('WARN');
  });

  test('[U] SF43: on a capped series "If saved" keeps the build\'s verdict, then withholds one, as the board does', async ({ page }) => {
    // V5: an evaluateKpis preview would re-judge E26's many-instances from the page's thinned copy
    // (20 of 21 instances). The board keeps Java's verdict while the assert is as judged, and
    // reads NOT RE-JUDGED once it is edited; the line is asked through the same derivation.
    await openServer(page);
    const onBoard = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .find((r: any) => r.id === 'many-instances'));
    expect([onBoard.display, onBoard.capped, onBoard.live]).toEqual(['PASS', true, false]);
    await editKpi(page, 'many-instances');
    const line = page.locator('#serverEditorVerdict');
    await expect(line.locator('.wir-chip')).toHaveText('PASS');
    await expect(line).toContainText('the build’s verdict — this series is capped here');
    await expect(line).not.toContainText('judged live');

    await page.locator('#serverEditor [data-field="fail.value"]').fill('1');
    await expect(line.locator('.wir-chip')).toHaveText('NOT RE-JUDGED');
    await expect(line.locator('.wir-editor-verdict-reason')).toContainText('this series reached the page capped (20 of 21 instances)');
  });

  test('[U] SF43: "If saved" says "judged live" only of a row that decided something', async ({ page }) => {
    // It appended "judged live" whenever the row was live, so a parked assert read "DISABLED judged
    // live Not judged — was parked with enabled: false" — the chip and its suffix disagreeing. The
    // form's line calls such a row "live", where it came from; the board says "live" only of an
    // edited row (LIVE-MARKER).
    await openServer(page);
    await editKpi(page, 'parked');
    const line = page.locator('#serverEditorVerdict');
    await expect(line.locator('.wir-chip')).toHaveText('DISABLED');
    await expect(line).not.toContainText('judged live');
    await expect(line.locator('.wir-dim').first()).toHaveText('live');
    await page.click('#serverEditorCancel');
    await editKpi(page, 'lag-spike');
    await expect(line).toContainText('judged live');
  });

  test('[U] SF43: a form Save refuses has no "If saved", and a new assert reads as the board will read it', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => (window as any).WIR.serverKpis.addKpi());
    const line = page.locator('#serverEditorVerdict');
    // No query yet: nothing a Save could write, so no chip — NEEDS RE-COLLECTION over an empty
    // form would answer a question it cannot ask yet.
    await expect(line).toHaveText(/If saved\s*Not judged until it can be saved — the reason is below\./);
    await expect(line.locator('.wir-chip')).toHaveCount(0);

    // A query this run collected, judged live; one it did not, NEEDS RE-COLLECTION — the board's
    // word for a new assert nothing collected answers for (SF13's lesson: never INVALID).
    await page.locator('#serverEditor [data-field="query"]').fill('lag');
    await page.locator('#serverEditor [data-field="fail.value"]').fill('100000');
    await expect(line.locator('.wir-chip')).toHaveText('PASS');
    await expect(line).toContainText('judged live');
    await page.locator('#serverEditor [data-field="query"]').fill('never_collected');
    await expect(line.locator('.wir-chip')).toHaveText('NEEDS RE-COLLECTION');
    await expect(line.locator('.wir-editor-verdict-reason')).toContainText('nothing in this run’s collection answers for it');
  });

  test('[U] retuning a threshold re-decides the verdict live', async ({ page }) => {
    await openServer(page);
    await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('FAIL');

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      W.set('fail.value', '5000');   // above the spike; warn > 300 still breaches
      W.applyEditor();
    });

    // Recomputed by the browser mirror, against the same vectors the Java evaluator answers to.
    await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('WARN');
    await expect(rowOf(page, 'lag-spike')).toContainText('live');

    // R3-5. The gate badge above that row stayed "Kafka FAIL" with nothing beside it, over a gate
    // whose rows no longer contain a FAIL. It keeps the verdict the run was gated on — and says
    // what a Save would make it, folded from the live rows the way GateSummary folds them.
    const kafka = page.locator('.wir-gate-badge').nth(0);
    // The verdict as text, the component as its name (SF26: the header already says "Kafka").
    await expect(kafka).toContainText('FAIL');
    await expect(kafka).toHaveAttribute('aria-label', 'Kafka FAIL, WARN if saved — show only this component');
    await expect(kafka.locator('.wir-would-be')).toHaveText('→ WARN if saved');
    await expect(page.locator('.wir-gate-note')).toContainText('verdict this run was gated on');
    // DB's rows did not move, so its badge says nothing more.
    await expect(page.locator('.wir-gate-badge').nth(1).locator('.wir-would-be')).toHaveCount(0);
  });

  test('[U] a gate whose asserts all pass now says PASS if saved (R3-5)', async ({ page }) => {
    await openServer(page);
    // On the unedited report the live fold agrees with the baked verdict for every gate — the
    // strip adds nothing, which is the parity check this fold has against Java's.
    expect(await page.evaluate(() =>
      (window as any).WIR.serverKpis._gateStrip().filter((g: any) => g.changed).length)).toBe(0);
    await expect(page.locator('.wir-would-be')).toHaveCount(0);

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      W.set('warn.value', '5000');
      W.set('fail.value', '5000');
      W.applyEditor();
    });
    // kafka now holds cpu-headroom PASS, lag-spike PASS, needs-load LOAD NOT ACHIEVED and parked
    // DISABLED — neither reason gates, so the gate would read PASS.
    await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('PASS');
    const kafka = page.locator('.wir-gate-badge').nth(0);
    // The verdict as text, the component as its name (SF26).
    await expect(kafka).toContainText('FAIL');
    await expect(kafka).toHaveAttribute('aria-label', /^Kafka FAIL/);
    await expect(kafka.locator('.wir-would-be')).toHaveText('→ PASS if saved');
    await expect(kafka).toHaveAttribute('title', /as this run was gated; if saved: PASS/);
  });

  test('[U] a gate holding an assert with no verdict here cannot say what it would read (R3-5)',
    async ({ page }) => {
      await openServer(page);
      // cpu-headroom's query changed: NEEDS RE-COLLECTION, no verdict on this page. kafka's other
      // decided rows fold to FAIL (lag-spike), and nothing can improve on a FAIL — so it still
      // says FAIL. Retune lag-spike to pass as well, and the gate honestly does not know.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('cpu-headroom'));
        W.set('query', 'cpu_renamed');
        W.applyEditor();
      });
      expect(await page.evaluate(() =>
        (window as any).WIR.serverKpis._gateStrip()[0])).toMatchObject({ id: 'kafka', changed: false });

      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('lag-spike'));
        W.set('warn.value', '5000');
        W.set('fail.value', '5000');
        W.applyEditor();
      });
      const kafka = page.locator('.wir-gate-badge').nth(0);
      await expect(kafka.locator('.wir-would-be')).toHaveText('→ ? if saved');
      await expect(kafka).toHaveAttribute('title', /no verdict on this page/);
    });

  test('[U] an untouched gate that decided nothing adds no "if saved" (R3-5)', async ({ page }) => {
    // Java writes a gate whose status is null as the STRING "NOT_EVALUATED" (CiCdJsonSerializer
    // .gateBlock, which GatePayloadBuilder shares for chartData.serverGates); the fold returns null
    // for the same thing. Compared raw, every such gate read "→ NOT EVALUATED if saved", under the
    // caption about edits, on a page nobody had touched. E26's two gates both decide something, so
    // the unedited-page check above could not see it.
    //
    // Two documented ways to get one, driven with exactly the shape the generator writes over E26's
    // own document (as server-tab.spec.ts drives the interim and refused-payload shapes): a declared
    // component no assert names — "empty ones included" — and a component whose only assert never
    // reached its source (SOURCE_FAILED).
    await openServer(page);
    await page.evaluate(() => {
      const W = window as any;
      const d = JSON.parse(JSON.stringify(W.chartData));
      const block = d.annotationsDocument.serverMetrics;
      block.components.idle = { type: 'custom', description: 'Idle' };
      block.components.ops = { type: 'custom', description: 'Ops' };
      block.asserts.find((k: any) => k.id === 'source-never-answered').component = 'ops';
      d.serverKpis.find((k: any) => k.id === 'source-never-answered').gate = 'ops';
      const db = d.serverGates.find((g: any) => g.id === 'db');
      db.declared -= 1;
      db.evaluated -= 1;
      db.notJudged.total -= 1;
      db.notJudged.sourceFailed -= 1;
      const none = { total: 0, disabled: 0, noMatch: 0, noValue: 0, invalidRule: 0, sourceFailed: 0,
        lowCoverage: 0, loadNotAchieved: 0 };
      d.serverGates.push(
        { id: 'idle', label: 'Idle', failsBuild: true, collectionId: db.collectionId,
          status: 'NOT_EVALUATED', declared: 0, evaluated: 0, passed: 0, warned: 0, failed: 0,
          notJudged: none },
        { id: 'ops', label: 'Ops', failsBuild: true, collectionId: db.collectionId,
          status: 'NOT_EVALUATED', declared: 1, evaluated: 1, passed: 0, warned: 0, failed: 0,
          notJudged: { ...none, total: 1, sourceFailed: 1 } });
      // The page as generated with this document: WIR.document reads "what the page shipped
      // with" from globalChartData, and a baked verdict is trusted only while the block around it
      // is the shipped one — so both halves are handed the same data, as initCharts hands them.
      W.globalChartData = d;
      W.WIR.serverKpis.init(d);
    });

    const strip = await page.evaluate(() => (window as any).WIR.serverKpis._gateStrip());
    expect(strip.map((g: any) => g.id)).toEqual(['kafka', 'db', 'idle', 'ops']);
    for (const id of ['idle', 'ops']) {
      expect(strip.find((g: any) => g.id === id), id).toMatchObject(
        { baked: 'NOT_EVALUATED', would: null, unknown: false, changed: false });
    }
    expect(strip.filter((g: any) => g.changed).map((g: any) => g.id)).toEqual([]);
    await expect(page.locator('.wir-gate-badge').nth(2)).toHaveText('NOT EVALUATED');
    await expect(page.locator('.wir-gate-badge').nth(2)).toHaveAttribute('aria-label', /^Idle NOT EVALUATED/);
    await expect(page.locator('.wir-would-be')).toHaveCount(0);
    await expect(page.locator('.wir-gate-note')).toHaveCount(0);

    // Normalised, not blinded: a move INTO deciding something, and a move OUT of it, still show.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-headroom'));
      W.set('component', 'idle');
      W.applyEditor();
      W.toggleEnabled(W._keyOf('lag-spike'));
    });
    // kafka keeps needs-load (LOAD NOT ACHIEVED) and parked (DISABLED) only: nothing decided.
    await expect(page.locator('.wir-gate-badge').nth(0).locator('.wir-would-be'))
      .toHaveText('→ NOT EVALUATED if saved');
    // idle now holds cpu-headroom, and the cpu series this run collected is kafka's: a series is
    // its component and its query (backends plan §8b), so under idle it needs re-collecting and
    // the badge cannot know its verdict — but it does move, which is what this half pins.
    await expect(page.locator('.wir-gate-badge').nth(2).locator('.wir-would-be'))
      .toHaveText('→ ? if saved');
    await expect(page.locator('.wir-gate-badge').nth(3).locator('.wir-would-be')).toHaveCount(0);
  });

  test('[U] changing the query withholds the verdict rather than estimating one',
    async ({ page }) => {
      await openServer(page);

      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('lag-spike'));
        W.set('query', 'lag_renamed');
        W.applyEditor();
      });

      // D22 again, on the board this time. A number computed from the OLD series under the NEW
      // query is the most confidently wrong thing this tab could show.
      await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('NEEDS RE-COLLECTION');
      await expect(rowOf(page, 'lag-spike').locator('td').nth(2)).toHaveText('—');
      // R3-14. The one state the tab has to say out loud rendered as bare text: no rule for
      // .wir-chip-needs-re-collection, so the only chip on the board with no background at all.
      const style = await rowOf(page, 'lag-spike').locator('.wir-chip').evaluate((el) => {
        const s = getComputedStyle(el);
        return { background: s.backgroundColor, border: s.borderTopStyle };
      });
      expect(style.background).not.toBe('rgba(0, 0, 0, 0)');
      expect(style.background).not.toBe('transparent');
      expect(style.border).toBe('dashed');
      // E26 renders six reason rows of its own, so this one is selected by its text rather than
      // by position — the sentence is the assertion, not where it happens to sit. A shipped assert
      // whose query moved says so in the file's words: "component or query", never "source" (C8,
      // U8e) — moved deliberately from 'source or query changed'.
      await expect(page.locator('.wir-reason-row', { hasText: 'Its component or query changed' }))
        .toContainText('re-run wir-collect with this file to judge it');
      await expect(page.locator('#serverBoard')).not.toContainText('source or query changed');
    });

  test('[U] U8e: a server assert new in this draft is never "changed" — it says why nothing answers it',
    async ({ page }) => {
      await openServer(page);
      // A custom assert added here on a query this run never collected: nothing changed, it is new.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.addKpi();
        W.set('id', 'brand-new');
        W.set('query', 'never_collected_total');
        W.set('fail.value', '5');
        W.applyEditor();
      });
      await expect(rowOf(page, 'brand-new').locator('.wir-chip')).toHaveText('NEEDS RE-COLLECTION');
      const reason = page.locator('.wir-reason-row', { hasText: 'new in this draft' });
      await expect(reason).toHaveText(/^This server assert is new in this draft, and nothing in this run’s collection answers for it — download the file and re-run wir-collect with this file to judge it\.$/);
      await expect(page.locator('#serverBoard')).not.toContainText('changed, so nothing collected');
    });

  test('[U] SF1: a padded query is the series it names — Validate finds it and the row stays judged',
    async ({ page }) => {
      await openServer(page);
      // U7: every text field of an assert was read trimmed except the query, so "lag " named a
      // series nobody collected: NEEDS RE-COLLECTION here, NO_MATCH in Java, and a run that should
      // have FAILed exited 0. Both sides now trim it as String.trim does (javaTrim here).
      await editKpi(page, 'lag-spike');
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.set('query', '  lag\t');
        W.set('fail.value', '600');
      });
      await expect(page.locator('#serverEditorValidate')).toContainText('Collected:');
      await page.evaluate(() => (window as any).WIR.serverKpis.applyEditor());
      await expect(rowOf(page, 'lag-spike').locator('.wir-chip')).toHaveText('FAIL');
      await expect(rowOf(page, 'lag-spike')).toContainText('live');
      // The padding is how the query is READ, not an edit to it: the file keeps its own spelling.
      const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
        .serverMetrics.asserts.find((k: any) => k.id === 'lag-spike'));
      expect(written.query).toBe('lag');
      expect(written.fail).toEqual({ gt: 600 });
    });

  test('[R] SF2: Across instances offers max, min and avg, in Java\'s order', async ({ page }) => {
    await openServer(page);
    await editKpi(page, 'lag-spike');
    expect((await page.locator('#serverEditor select[data-field="across"] option').allTextContents())
      .map((t) => t.trim())).toEqual(['max', 'min', 'avg']);
  });

  test('[U] SF1: Validate does not send a query with an invisible end to a re-collect that cannot help',
    async ({ page }) => {
      await openServer(page);
      await editKpi(page, 'lag-spike');
      // A no-break space is not trimmed (Q2): no source returns that series, so "Not collected.
      // Download the file and re-run wir-collect" pointed at a step that changes nothing.
      await page.evaluate(() => (window as any).WIR.serverKpis.set('query', String.fromCharCode(0xa0) + 'lag'));
      await expect(page.locator('#serverEditorValidate')).toHaveText('Collecting again will not help: the query has '
        + 'an invisible character at its start (U+00A0) that is not trimmed like a plain space, so no series will '
        + 'match it — delete it.');
      await expect(page.locator('#serverEditorValidate')).not.toContainText('re-run wir-collect');
    });

  test('[U] a capped series is judged but never re-judged', async ({ page }) => {
    await openServer(page);

    // The page carries at most 20 instances per series where Java judged the collector's
    // uncapped payload, so E26's 21-instance series would recompute to a different number
    // than the one the build was gated on. It keeps the baked verdict and is not marked live.
    const row = rowOf(page, 'many-instances');
    await expect(row.locator('.wir-chip')).toHaveText('PASS');
    await expect(row).not.toContainText('live');
    // An uncapped series is re-judged here, but an untouched row's verdict is the build's, so the
    // board does not say "live" of it (LIVE-MARKER): only of a row whose assert, or a client rule it
    // names, was edited. _board().live still says where the verdict came from.
    const board = await page.evaluate(() => (window as any).WIR.serverKpis._board()
      .filter((r: any) => r.id === 'lag-p50-warns' || r.id === 'many-instances')
      .map((r: any) => [r.id, r.live, r.markedLive]));
    expect(board).toEqual([['lag-p50-warns', true, false], ['many-instances', false, false]]);
    await expect(rowOf(page, 'lag-p50-warns').locator('.wir-live')).toHaveCount(0);
  });

  test('[U] a server edit moves the shared unsaved count', async ({ page }) => {
    await openServer(page);
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);

    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      W.set('fail.value', '1234');
      W.applyEditor();
    });

    // One file, two authors: the pill lives on the Asserts tab and is assembled from every
    // slice, so an edit made here has to reach it.
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
    // ...and so does the banner under it, which counted the Asserts slice alone and read
    // "0 unsaved changes" beside a pill reading 1 (R3-6).
    await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change');
  });

  test('[R] the editor can be typed into — focus, caret and the panel survive every keystroke',
    async ({ page }) => {
      // R1-1 / R3-1. Every keystroke ran set() -> renderEditor() -> host.innerHTML = <new panel>:
      // the focused input was detached after ONE character and focus fell to <body>, so a KPI
      // could only be authored by pasting each field. Every spec before this one set values
      // through W.set(), which never types a second character — which is why none saw it.
      await openServer(page);
      await page.click('.wir-server-actions .wir-btn-primary');
      const panel = await page.locator('#serverEditor .wir-slideover').elementHandle();

      const id = page.locator('#serverEditor [data-field="id"]');
      await id.click();
      await page.keyboard.type('kafka-lag');
      await expect(id).toHaveValue('kafka-lag');
      await expect(id).toBeFocused();

      const query = page.locator('#serverEditor [data-field="query"]');
      await query.pressSequentially('lag');
      await expect(query).toHaveValue('lag');
      await expect(query).toBeFocused();
      // What depends on the value still follows it: the validate line is an exact lookup.
      await expect(page.locator('#serverEditor .wir-validate')).toHaveText(/Collected: 1 instance/);

      // A caret in the middle of the text stays where it was put.
      await page.keyboard.press('ArrowLeft');
      await page.keyboard.press('ArrowLeft');
      await page.keyboard.type('_');
      await expect(query).toHaveValue('l_ag');
      await page.keyboard.press('Backspace');
      await expect(query).toHaveValue('lag');

      const fail = page.locator('#serverEditor [data-field="fail.value"]');
      await fail.pressSequentially('5000');
      await expect(fail).toHaveValue('5000');
      await expect(fail).toBeFocused();

      // The same panel, never rebuilt: replacing it replayed the slide-in animation per key.
      expect(await page.evaluate((el) => el!.isConnected, panel), 'the slide-over was replaced').toBe(true);

      // A change to the form's SHAPE rebuilds its body — and puts focus back where it was.
      const kind = page.locator('#serverEditor [data-field="kind"]');
      await kind.focus();
      await kind.selectOption('avg');
      await expect(kind).toBeFocused();
      await expect(kind).toHaveValue('avg');
      expect(await page.evaluate((el) => el!.isConnected, panel)).toBe(true);
      await expect(fail).toHaveValue('5000');

      await page.click('#serverEditorSave');
      const written = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.id === 'kafka-lag'));
      expect(written).toMatchObject({ id: 'kafka-lag', query: 'lag', reduce: { kind: 'avg' }, fail: { gt: 5000 } });
    });

  test('[R] a sustainedFor that no window can span cannot be saved (R1-5)', async ({ page }) => {
    // E26's collection is at 1s. A 1500ms tolerance has no complete window anywhere, which
    // the evaluator refuses at run time — INVALID_RULE, a config error, exit 1 in every gate. The
    // form saved it.
    await openServer(page);
    await editKpi(page, 'lag-spike');
    await page.locator('#serverEditor [data-field="over"]').selectOption('sustainedFor');
    const held = page.locator('#serverEditor [data-field="overValue"]');
    await held.pressSequentially('1500ms');
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('whole number');
    // The collection's step and the flag that sets it (U8d), in ServerKpiEvaluator's words. It
    // said "the source's 1s steps", from before C8 took sources out of the file — moved
    // deliberately with the Java sentence and its vector.
    await expect(page.locator('#serverEditor .wir-problem')).toContainText("the collection's 1s steps");
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('or collect with a finer wir-collect --step');
    await expect(page.locator('#serverEditor .wir-problem')).not.toContainText("the source's");
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeDisabled();

    await held.fill('2s');
    await expect(page.locator('#serverEditor .wir-problem')).toHaveCount(0);
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
  });

  test('[R] SF19: a tolerance chosen and left empty cannot be saved, in the mapper\'s words', async ({ page }) => {
    // B7. The editor wrote `over` only when its box held something, so a tolerance picked and left
    // empty saved as none at all — the plain reducer — under a grey "30s" that read as a value set.
    await openServer(page);
    await editKpi(page, 'lag-spike');
    const problem = page.locator('#serverEditor #serverEditorProblem');
    const save = page.locator('#serverEditorSave');
    await page.locator('#serverEditor [data-field="over"]').selectOption('sustainedFor');
    const held = page.locator('#serverEditor [data-field="overValue"]');
    await expect(held).toHaveAttribute('placeholder', 'e.g. 30s');
    await expect(problem).toHaveText(
      'reduce.over sustainedFor must be a duration such as "30s", "2m", "500ms" or "1h", not the text ""');
    await expect(save).toBeDisabled();
    await held.pressSequentially('5s');
    await expect(problem).toHaveCount(0);
    await expect(save).toBeEnabled();
    await held.press('Backspace');
    await held.press('Backspace');
    await expect(save).toBeDisabled();
    // Spaces are no value either: read as String.trim reads them.
    await held.fill('   ');
    await expect(problem).toContainText('not the text ""');
    await expect(save).toBeDisabled();

    await page.locator('#serverEditor [data-field="over"]').selectOption('breachBudget');
    await expect(held).toHaveAttribute('placeholder', 'e.g. 0.02');
    await expect(problem).toHaveText(
      'reduce.over breachBudget must be a fraction 0..1 of the window, not the text "" — 2% is written 0.02');
    await expect(save).toBeDisabled();
  });

  test('[R] SF19: erasing a declared tolerance does not remove it — Tolerance: none does', async ({ page }) => {
    await openServer(page);
    await editKpi(page, 'lag-sustained');
    const held = page.locator('#serverEditor [data-field="overValue"]');
    await expect(held).toHaveValue('5s');
    await held.fill('');
    await expect(page.locator('#serverEditorProblem')).toContainText('sustainedFor must be a duration');
    await expect(page.locator('#serverEditorSave')).toBeDisabled();
    // The one way to remove it, and the file then says what the author chose.
    await page.locator('#serverEditor [data-field="over"]').selectOption('');
    await expect(page.locator('#serverEditorSave')).toBeEnabled();
    await page.click('#serverEditorSave');
    const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
      .find((k: any) => k.id === 'lag-sustained'));
    expect(written.reduce).toEqual({ kind: 'max' });
  });

  test('[U] U8i: the run-level requiresLoad warning names a control that exists, and where', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.requiresLoad = 'nope';
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await editKpi(page, 'cpu-headroom');
    const warning = page.locator('#serverEditorWarnings .wir-editor-warning',
      { hasText: 'serverMetrics.requiresLoad applies to this server assert' });
    await expect(warning).toHaveCount(1);
    // Batch 4 pointed the sentence at the Asserts tab, the only one that had the button; SF44 gave
    // the Server toolbar its own, so it points here — and the control it names is on this tab.
    await expect(warning).toContainText(/— fix it with Edit document… in this tab’s toolbar$/);
    await expect(page.locator('#tab-server #serverEditDocumentBtn')).toHaveText('Edit document…');
  });

  test('[R] a divideBy over several samplers cannot be saved (R1-5)', async ({ page }) => {
    // "A ratio needs one measured load": a divisor rule matching three samplers is INVALID_RULE at
    // the next run. The select offered it and Save wrote it.
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.assertions.push({
        id: 'load:per-api', target: { match: '*/api/*' }, metric: 'throughput', fail: { lt: 0.001 },
      });
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await editKpi(page, 'cpu-per-rps');
    await page.locator('#serverEditor [data-field="divideBy"]').selectOption('load:per-api');
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('matched 3 samplers');
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeDisabled();

    // requiresLoad on the same rule is fine: it asks whether the load held, not for one number.
    await page.locator('#serverEditor [data-field="divideBy"]').selectOption('load:throughput');
    await page.locator('#serverEditor [data-field="requiresLoad"]').selectOption('load:per-api');
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
  });

  test('[R] every value the model holds is one the form can show and clear (R1-7)', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-p50-warns').reduce = { kind: 'p99.9' };
      W.document.adopt(doc);
      W.serverKpis.reread();
    });

    // A pN the fixed list does not carry: the select read "max" while Save wrote p99.9.
    await editKpi(page, 'lag-p50-warns');
    const kind = page.locator('#serverEditor [data-field="kind"]');
    await expect(kind).toHaveValue('p99.9');
    await expect(kind.locator('option:checked')).toContainText('p99.9');
    await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());

    // A tolerance on a reducer that cannot hold one: switching max -> p95 hid the control and kept
    // the value, so Save was refused with nothing on screen to clear.
    await editKpi(page, 'lag-sustained');
    await kind.selectOption('p95');
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
    await page.click('#serverEditorSave');
    const sustained = await page.evaluate(() =>
      JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
        .find((k: any) => k.id === 'lag-sustained'));
    expect(sustained.reduce).toEqual({ kind: 'p95' });

    // A field that LOOKS empty is empty: Number(' ') is 0, and "   " saved as `warn: {gt: 0}`.
    await editKpi(page, 'lag-spike');
    await page.locator('#serverEditor [data-field="warn.value"]').fill('   ');
    await page.click('#serverEditorSave');
    const spike = await page.evaluate(() =>
      JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
        .find((k: any) => k.id === 'lag-spike'));
    expect(spike.warn, 'a blank warn field wrote a band').toBeUndefined();
    expect(spike.fail).toEqual({ gt: 500 });
  });

  test('[R] a file p95 with a tolerance shows the control that removes it (R1-7)', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-p50-warns').reduce =
        { kind: 'p95', over: { sustainedFor: '5s' } };
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    await editKpi(page, 'lag-p50-warns');
    await expect(page.locator('#serverEditor .wir-problem')).toContainText('only defined on max and min');
    await page.locator('#serverEditor [data-field="over"]').selectOption('');
    await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
  });

  test('[R] Validate query never calls a series that did not collect "Collected" (R1-10)',
    async ({ page }) => {
      await openServer(page);
      for (const [id, status, reads] of [
        ['source-never-answered', 'FAILED', 'SOURCE FAILED'],
        ['nothing-recorded', 'EMPTY', 'UNMATCHED'],
        ['sparse-coverage', 'INSUFFICIENT', 'LOW COVERAGE'],
      ]) {
        await editKpi(page, id);
        const line = page.locator('#serverEditor .wir-validate');
        await expect(line).toContainText(status);
        await expect(line).toContainText(reads);
        await expect(line).not.toContainText('Collected:');
        await expect(line).not.toHaveClass(/wir-validate-ok/);
        await expect(line).toHaveClass(/wir-validate-warn/);
        await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());
      }
      await editKpi(page, 'lag-spike');
      await expect(page.locator('#serverEditor .wir-validate')).toHaveClass(/wir-validate-ok/);
    });

  test('[U] the editor and a drag say what Java will WARN about, without refusing (C-11)',
    async ({ page }) => {
      await openServer(page);
      await editKpi(page, 'lag-spike');
      // warn > 900 behind fail > 500: warn can never fire. Java WARNs; the form used to say nothing.
      await page.locator('#serverEditor [data-field="warn.value"]').fill('900');
      await expect(page.locator('#serverEditor .wir-editor-warning'))
        .toContainText('warn can never fire: fail > 500 msgs is tested first');
      await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
      await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());

      // A drag has no form, so the sentence arrives as a toast — and the drag still commits.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W._commitDrag(W._keyOf('lag-spike'), 'warn', 6000);
      });
      await expect(page.locator('#wir-toast')).toContainText('warn can never fire');
      const warn = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts
          .find((k: any) => k.id === 'lag-spike').warn);
      expect(warn).toEqual({ gt: 6000 });
    });

  test('[R] a breach budget of 0.07 reads 7%, not the noise of 0.07 * 100 (C-13)', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-sustained').reduce =
        { kind: 'max', over: { breachBudget: 0.07 } };
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    const row = rowOf(page, 'lag-sustained');
    await expect(row).toContainText('max breach budget 7%');
    await expect(row).not.toContainText('7.000000000000001');
  });

  test('[U] delete and duplicate reach the document', async ({ page }) => {
    await openServer(page);
    const before = await page.locator('#serverKpiTable tbody tr.wir-kpi-row').count();

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.duplicate(W._keyOf('lag-spike'));
    });
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(before + 1);
    await expect(rowOf(page, 'lag-spike-2')).toBeVisible();

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.remove(W._keyOf('lag-spike-2'));
    });
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(before);

    const ids = await page.evaluate(() =>
      JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts.map((k: any) => k.id));
    expect(ids).not.toContain('lag-spike-2');
  });

  test('[U] a component named like an Object.prototype key is labelled by its name (review:D)',
    async ({ page }) => {
      // The select's labels were looked up in a plain {} — so a gate called "toString" read
      // "function toString() { [native code] }" in the Gate select. The value was right; the label
      // lied. One select since C1, the Component select, and both names go through it.
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.components.toString = { type: 'custom', description: 'Odd' };
        doc.serverMetrics.components.constructor = { type: 'postgres' };
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      await page.click('.wir-server-actions .wir-btn-primary');          // + Add server assert
      const labels = await page.locator('#serverEditor select[data-field="component"] option').allTextContents();
      expect(labels).toContain('toString (custom)');
      expect(labels).toContain('constructor (Postgres)');
      labels.forEach((label) =>
        expect(label, 'an option labelled with a function\'s source text').not.toContain('native code'));
    });
});

/**
 * SF2 on E27's catalogue components. postgres.json declares across min on up and cache.hit.pct, so
 * for those two the default fold is min, not max — and both the editor's Across select and the
 * file it writes have to know that.
 */
test.describe('Across on a catalogue metric (E27)', () => {
  async function openCatalogue(page: any) {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
  }

  /** The serverMetrics assert a Save would write now, found by the catalogue metric it names. */
  async function writtenAssert(page: any, metric: string) {
    return page.evaluate((m: string) => JSON.parse((window as any).WIR.document.text())
      .serverMetrics.asserts.find((k: any) => k.metric === m), metric);
  }

  test('[U] F2b: a band past the catalogue ceiling is a warning in the editor and after a drag, never a refusal',
    async ({ page }) => {
      // The page never proposes fail > 150 % on a percentage, but one typed or dragged got no word:
      // it can never fire, and the assert PASSes every run. Java now WARNs (BAND_ABOVE_CEILING), and
      // the editor and a drag say the same sentence, conditionally, without blocking Save.
      const SENTENCE = 'fail > 150 % can only fire above the ceiling of 100 % the catalogue declares for '
        + 'connections.used.pct. If it never reads above its ceiling the band never fires';
      await openCatalogue(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.addKpi();
        W.set('id', 'conn-past-ceiling');
        W.set('component', 'orders-db');
        W.set('metric', 'connections.used.pct');
        W.set('fail.op', 'gt');
        W.set('fail.value', '150');
      });
      await expect(page.locator('#serverEditor .wir-editor-warning')).toContainText(SENTENCE);
      await expect(page.locator('#serverEditor .wir-btn-primary')).toBeEnabled();
      // At the ceiling with gte it can still fire on the ceiling itself: nothing to say.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.set('fail.op', 'gte');
        W.set('fail.value', '100');
      });
      await expect(page.locator('#serverEditor .wir-editor-warning', { hasText: 'ceiling' })).toHaveCount(0);
      await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());

      // The file's own connections.used.pct assert (fail > 85 %), dragged to 150: the sentence
      // arrives as a toast, and the drag still commits.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        const key = W._state.declaredKpis.find((k: any) => k.metric === 'connections.used.pct').key;
        W._commitDrag(key, 'fail', 150);
      });
      await expect(page.locator('#wir-toast')).toContainText(SENTENCE);
      expect((await writtenAssert(page, 'connections.used.pct')).fail).toEqual({ gt: 150 });
    });

  test('[U] the Across select starts at the catalogue\'s fold — min on cache.hit.pct — and leaves it unwritten',
    async ({ page }) => {
      await openCatalogue(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.addKpi();
        W.set('id', 'hit-ratio');
        W.set('component', 'orders-db');
        W.set('metric', 'cache.hit.pct');
      });
      // It read max while the assert was judged min: the editor said one fold, the run used another.
      await expect(page.locator('#serverEditor select[data-field="across"]')).toHaveValue('min');
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.set('fail.op', 'lt');
        W.set('fail.value', '97.5');
        W.applyEditor();
      });
      expect(await writtenAssert(page, 'cache.hit.pct'))
        .toEqual({ id: 'hit-ratio', component: 'orders-db', metric: 'cache.hit.pct', fail: { lt: 97.5 } });
    });

  test('[U] across max chosen on a file\'s up assert is an edit: counted, written, and judged as written',
    async ({ page }) => {
      // canonicalJson wrote across only when it was not 'max'. The catalogue's fold for up is min,
      // so max was dropped: the row read PASS, nothing counted, Download wrote the file without it,
      // and the next run FAILed with the catalogue's min.
      await openCatalogue(page);
      // The page as Java ships it for E27's file with an up assert added: the document is the one
      // the page arrived with, so nothing is unsaved yet. Its series is uncapped, so the row is
      // judged here, live.
      await page.evaluate(() => {
        const W = window as any;
        const d = JSON.parse(JSON.stringify(W.globalChartData));
        d.annotationsDocument.serverMetrics.asserts.push({ component: 'orders-db', metric: 'up', fail: { lt: 1 } });
        W.globalChartData = d;
        W.WIR.serverKpis.init(d);
        W.WIR.document.start();
        W.WIR.serverKpis.rerender();
      });
      const row = page.locator('.wir-component-card[data-component="orders-db"] tr.wir-kpi-row', { hasText: 'orders-db-up' });
      // pg-1 reports up = 0 once (Q9): folded with min, the database that went down decides.
      await expect(row.locator('.wir-chip')).toHaveText('FAIL');
      await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);

      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('orders-db-up'));
      });
      await expect(page.locator('#serverEditor select[data-field="across"]')).toHaveValue('min');
      await page.selectOption('#serverEditor select[data-field="across"]', 'max');
      await page.click('#serverEditorSave');

      await expect(row.locator('.wir-chip')).toHaveText('PASS');
      await expect(row).toContainText('across max');
      await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
      expect(await writtenAssert(page, 'up')).toEqual({ component: 'orders-db', metric: 'up', fail: { lt: 1 }, across: 'max' });

      // A Duplicate is a new assert in this build's shape, and keeps the override too.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.duplicate(W._keyOf('orders-db-up'));
      });
      const ups = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
        .serverMetrics.asserts.filter((k: any) => k.metric === 'up'));
      expect(ups.map((k: any) => k.across)).toEqual(['max', 'max']);
    });
});

test.describe('The Metric and Query fields (SF26)', () => {
  test('[U] the Metric select reads in labels, grouped as the picker groups them, Custom query… last — and still writes the id', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await editKpi(page, 'orders-db-connections-used-pct');
    const select = page.locator('#serverEditor select[data-field="metric"]');
    // It listed raw ids, flat, with "Custom query…" first.
    await expect(select.locator('option[value="connections.used.pct"]')).toHaveText('Connections used (%)');
    expect(await select.locator('optgroup').evaluateAll((els) => els.map((e) => e.getAttribute('label'))))
      .toEqual(['Availability', 'Connections', 'Transactions', 'Locks', 'Cache', 'Replication', 'Disk', 'Custom']);
    const options = await select.locator('option').evaluateAll((els) =>
      els.map((e) => [(e as HTMLOptionElement).value, e.textContent]));
    expect(options[options.length - 1]).toEqual(['', 'Custom query…']);
    // The id the file carries, under the select that no longer shows it.
    await expect(page.locator('#serverEditorMetricId')).toHaveText('metric: connections.used.pct');

    await select.selectOption({ label: 'Cache hit ratio (%)' });
    await expect(page.locator('#serverEditorMetricId')).toHaveText('metric: cache.hit.pct');
    await page.click('#serverEditorSave');
    const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts);
    expect(written.map((k: any) => k.metric)).toContain('cache.hit.pct');
  });

  test('[U] a custom component offers no Metric choice, and a long query wraps without taking a line break', async ({ page }) => {
    await openServer(page);
    await editKpi(page, 'lag-spike');
    // kafka is custom: the select's one option was "Custom query…".
    await expect(page.locator('#serverEditor select[data-field="metric"]')).toHaveCount(0);
    await expect(page.locator('#serverEditorMetricNone')).toHaveText(
      'Custom query — a custom component has no catalogue, so each assert writes its own query');

    // A long PromQL selector is one unbreakable token; in a one-line input a third of it showed.
    const query = page.locator('#serverEditor [data-field="query"]');
    const long = 'max(kafka_consumergroup_lag{namespace="checkout-production",consumergroup="payments-'
      + 'settlement-worker",topic="payments.settlement.requests.v2",partition=~"[0-9]+"})';
    await query.fill(long);
    const box = await query.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, tag: el.tagName }));
    expect(box.tag).toBe('TEXTAREA');
    expect(box.sw, 'the query scrolls sideways instead of wrapping').toBeLessThanOrEqual(box.cw + 1);
    // A query is its exact text — a series is found by it (D22) — so Enter adds no line break.
    await query.press('End');
    await query.press('Enter');
    await expect(query).toHaveValue(long);
    await expect(query).toBeFocused();
    // Nor does any other way in (SF26 review): text inserted with a line break in it — a paste, a
    // drop, a soft keyboard — loses the break, a run of them becoming one space, and an insertion
    // of nothing but breaks is dropped. The one-line input it replaced could not hold one either.
    await page.keyboard.insertText('\n\n+ 0');
    await expect(query).toHaveValue(long + ' + 0');
    await page.keyboard.insertText('\n');
    await expect(query).toHaveValue(long + ' + 0');
    await page.click('#serverEditorSave');
    const written = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
      .serverMetrics.asserts.find((k: any) => k.id === 'lag-spike'));
    expect(written.query).toBe(long + ' + 0');
  });

  test('[U] a query the file already writes on two lines keeps them while a character is typed into it', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').query = 'sum(lag)\nby (pod)';
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    await editKpi(page, 'lag-spike');
    const query = page.locator('#serverEditor [data-field="query"]');
    await expect(query).toHaveValue('sum(lag)\nby (pod)');
    await query.press('Control+End');
    await page.keyboard.type('x');
    // Only what was just put in is cleaned: the file's own break is its query, as written.
    await expect(query).toHaveValue('sum(lag)\nby (pod)x');
  });
});
