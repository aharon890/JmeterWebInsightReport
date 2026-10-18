import { test, expect } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * Turning a number you are looking at into a rule.
 *
 * The gap this closes: a tester reads "p95 1925 ms" in the samplers table, decides that is
 * roughly the budget, and then has to leave the report, open a JSON file, and invent a
 * threshold from memory. The ⊕ makes the number the starting point.
 */

test.describe('Seeding from a metric cell', () => {

  test('every assertable metric cell gets a ⊕, and nothing else does', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');

    expect(await page.locator('.wir-seed-btn').count()).toBeGreaterThan(0);
    await expect(page.locator('.wir-seed-btn[data-seed-metric="p95"]').first()).toHaveCount(1);

    // Recv KB/s used to be the documented example of a column with no assertable counterpart.
    // It has one now — named receivedKbPerSec, in the unit the column header carries, so the
    // number a rule judges is the number the cell shows. The old bytes-per-second spelling is
    // deliberately NOT a metric: it would have meant reading 976 off the screen and writing a
    // threshold of 999424.
    await expect(page.locator('.wir-seed-btn[data-seed-metric="receivedKbPerSec"]').first())
      .toHaveCount(1);
    await expect(page.locator('.wir-seed-btn[data-seed-metric="receivedBytesPerSec"]'))
      .toHaveCount(0);
    // Sampler, Errors and the notes column carry no metric and must not get a button.
    await expect(page.locator('#samplerTable tbody tr[data-sampler] td:not([data-assert-cell]) .wir-seed-btn'))
      .toHaveCount(0);
  });

  test('⊕ opens the editor pre-filled with that sampler, metric and seeded bands',
      async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await loadClean(page, 'E25');
      await switchTab(page, 'samplers');

      await page.locator(
        '#samplerTable tbody tr[data-sampler="Slow API"] .wir-seed-btn[data-seed-metric="p95"]'
      ).click();

      await expect(page.locator('.wir-slideover')).toBeVisible();
      await expect(page.locator('#editorMetric')).toHaveValue('p95');
      await expect(page.locator('#editorSampler')).toHaveValue('Slow API');

      // Seeded from the observed value with headroom, so the new rule passes as written —
      // a seeded rule that failed the moment it was created would be useless.
      await expect(page.locator('#editorPreviewVerdict')).toHaveText('PASS');
    });
  });

  test('⊕ does not also expand the row it sits in', async ({ page }) => {
    // The row opens per-sampler charts on click. Seeding must not do both.
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    const detailsBefore = await page.locator('.sampler-detail-row').count();

    await page.locator(
      '#samplerTable tbody tr[data-sampler="Slow API"] .wir-seed-btn[data-seed-metric="mean"]'
    ).click();

    expect(await page.locator('.sampler-detail-row').count()).toBe(detailsBefore);
  });

  test('seeded bands are numbers a person would have chosen', async ({ page }) => {
    // 974.4 reads like a machine picked it and gets hand-edited to 1000 immediately.
    await loadClean(page, 'E25');

    const seeded = await page.evaluate(() => ({
      latency: (window as any).WIR.asserts._seedBands('p95', 812),
      nice: [1, 812, 0.0034, 47000].map(v => (window as any).WIR.asserts._niceUp(v)),
    }));

    expect(seeded.latency.fail.op).toBe('gt');
    expect(seeded.latency.fail.value).toBe(1000);         // 812 × 1.2 = 974.4 → 1000
    // 80% of fail is 800 — below the 812 this was seeded from, so the rule would have
    // warned the moment it was created. Floored into the headroom band instead.
    expect(seeded.latency.warn.value).toBe(942);          // 812 × 1.16
    expect(seeded.nice).toEqual([1, 1000, 0.004, 50000]);
  });

  test('a seeded rule always passes the value it was seeded from', async ({ page }) => {
    // The wizard's whole promise, as arithmetic rather than as one lucky fixture. warn at
    // 80% of a fail band 20% above today is 0.96 × today: below the observation. niceUp()
    // hid it for years by sometimes rounding fail past 1.25 × the observation, so which
    // samplers a run happened to produce decided whether the promise held.
    await loadClean(page, 'E25');

    const bad = await page.evaluate(() => {
      const seed = (window as any).WIR.asserts._seedBands;
      const out: string[] = [];
      // A `gt` band breaches when the value is strictly above it, so the rule passes
      // exactly while observed <= band.
      // The spread deliberately reaches below 1 ms. It used to bottom out at 0.7, just above
      // the point where Math.round takes both bands to 0 and `fail: gt 0` fails on the value
      // that seeded it — and JMeter's `elapsed` is integer ms, so a Dummy or JSR223 sampler
      // averaging a fraction of a millisecond is ordinary rather than exotic.
      for (const metric of ['p95', 'p99', 'mean', 'errorRate']) {
        for (const observed of [0.1, 0.3, 0.4, 0.7, 3, 20.69, 99, 137, 586, 812, 1936, 4001]) {
          const b = seed(metric, observed);
          if (b.warn && observed > b.warn.value) {
            out.push(`${metric}@${observed}: warn ${b.warn.value} is below it`);
          }
          if (observed > b.fail.value) {
            out.push(`${metric}@${observed}: fail ${b.fail.value} is below it`);
          }
        }
      }
      return out;
    });

    expect(bad, 'a seeded band breaches on the value that seeded it').toEqual([]);
  });

  test('for lower-is-worse metrics the direction flips', async ({ page }) => {
    // Throughput and apdex breach downward. A gt band on them would never fire.
    await loadClean(page, 'E25');

    const seeded = await page.evaluate(() => ({
      throughput: (window as any).WIR.asserts._seedBands('throughput', 50),
      apdex: (window as any).WIR.asserts._seedBands('apdex', 0.9),
    }));

    expect(seeded.throughput.fail.op).toBe('lt');
    expect(seeded.throughput.fail.value).toBe(40);        // 20% below today
    expect(seeded.apdex.fail.op).toBe('lt');
    expect(seeded.apdex.fail.value).toBeCloseTo(0.72, 2);
  });

  test('a near-zero error rate does not seed a threshold nothing can satisfy', async ({ page }) => {
    await loadClean(page, 'E25');
    const seeded = await page.evaluate(
      () => (window as any).WIR.asserts._seedBands('errorRate', 0.01));
    expect(seeded.fail.value).toBeGreaterThanOrEqual(0.5);
  });

  test('a seeded warn band is never dead', async ({ page }) => {
    // warn === fail is the dead band AssertionValidator flags on every run thereafter, and the
    // editor refuses to let a human save it. The seeder used to produce it in bulk: the ms floors
    // fixed the bottom of the range and said nothing about rounding COLLAPSE higher up, so a
    // sampler observed at 4 ms with the default +20% seeded fail = niceUp(4.8) = 5 and
    // warn = max(4, 4.64) = 4.64 — both rounding to 5. A JSR223, Debug or cached sampler at a few
    // milliseconds is ordinary, so this was reachable through the ⊕ on an everyday plan.
    //
    // Where there is genuinely no room, the answer is no warn band at all — a fail-only rule is
    // a shape the model already has and the editor already explains.
    await loadClean(page, 'E25');

    const bad = await page.evaluate(() => {
      const seed = (window as any).WIR.asserts._seedBands;
      const out: string[] = [];
      const observations: Record<string, number[]> = {
        mean: [0.3, 1, 2, 4, 74, 812], min: [0, 1, 25], max: [4, 38, 9400],
        stdDev: [1, 3, 642], p50: [1, 61], p90: [2, 1420], p95: [3, 6, 243], p99: [4, 14, 4200],
        errorRate: [0, 0.05, 0.21, 3.82], errorCount: [0, 1, 91],
        throughput: [0.03, 6.6, 26.8], apdex: [0.62, 0.97, 1],
      };
      for (const metric of Object.keys(observations)) {
        for (const observed of observations[metric]) {
          for (const headroom of [0, 0.05, 0.2, 0.5, 1]) {
            for (const warnPct of [0.01, 0.5, 0.8, 0.99]) {
              const b = seed(metric, observed, headroom, warnPct);
              const tag = `${metric}@${observed} h=${headroom} w=${warnPct}`;
              if (!b.fail) { out.push(`${tag}: no fail band`); continue; }
              if (!b.warn) continue;              // deliberate: no room for one
              const inverted = b.fail.op === 'gt'
                ? !(b.warn.value < b.fail.value)  // gt: warn must trip first, so it sits lower
                : !(b.warn.value > b.fail.value); // lt: worse means smaller, so warn sits higher
              if (inverted) {
                out.push(`${tag}: warn ${b.warn.value} vs fail ${b.fail.value} (${b.fail.op})`);
              }
            }
          }
        }
      }
      return out;
    });

    expect(bad, 'a seeded warn band that cannot trip before fail').toEqual([]);
  });

  test('a lower-is-worse band can always fire, however wide the headroom', async ({ page }) => {
    // `observed × (1 - headroom)` reaches zero at headroom 1 and goes negative past it, so a
    // throughput rule seeded there renders as configured, counts as passing, and is incapable of
    // failing. A silently-green gate produced by the seeder is the exact outcome the gate exists
    // to prevent, so the band is floored — and apdex, which is a score, cannot leave (0, 1).
    await loadClean(page, 'E25');

    const bad = await page.evaluate(() => {
      const seed = (window as any).WIR.asserts._seedBands;
      const out: string[] = [];
      for (const [metric, observed] of [['throughput', 13.4], ['apdex', 0.97]] as [string, number][]) {
        for (const headroom of [0.2, 0.5, 0.9, 1, 2, 3]) {
          const b = seed(metric, observed, headroom, 0.8);
          const tag = `${metric}@${observed} h=${headroom}`;
          if (b.fail.op !== 'lt') out.push(`${tag}: expected lt, got ${b.fail.op}`);
          if (!(b.fail.value > 0)) out.push(`${tag}: fail lt ${b.fail.value} can never fire`);
          if (metric === 'apdex' && !(b.fail.value < 1)) {
            out.push(`${tag}: apdex fail ${b.fail.value} is outside (0,1)`);
          }
        }
      }
      return out;
    });

    expect(bad, 'a seeded band that no measurement could ever breach').toEqual([]);
  });
});

test.describe('Adoption from a report with no gate [E02]', () => {
  /**
   * The path the ⊕ buttons exist for, end to end.
   *
   * Every step of it worked except the last: on a report with no declared rules the buttons
   * rendered, the editor opened, the draft was stored — and the tab it went to stayed hidden
   * with an empty toolbar, so there was no Download button and nothing to do with the work.
   * Seeding is only useful if it ends at a file.
   *
   * The tab no longer waits to be revealed — it is reachable from the start, so "the tab is
   * hidden until there is something on it" has gone. What it was really protecting, that a run
   * which declared nothing claims no verdict, is asserted where it belongs: on the gate strip,
   * in asserts-tab.spec.ts.
   */

  test('seeding a rule fills the tab and gives it a way to save', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const seed = page.locator('#samplerTable tbody tr[data-sampler] .wir-seed-btn[data-seed-metric="p95"]').first();
    await expect(seed, 'E02 must carry the ⊕ buttons even with no gate').toHaveCount(1);
    await seed.click();

    await expect(page.locator('.wir-slideover')).toBeVisible();
    await page.click('#editorSave');

    // The tab is reachable...
    await expect(page.locator('#assertsTabBtn')).toBeVisible();
    // ...the toolbar exists rather than being empty...
    await expect(page.locator('#assertsUnsavedPill')).toBeVisible();
    await expect(page.locator('#saveAssertsBtn')).toBeVisible();

    // ...and it produces a document with the new rule in it.
    const doc = JSON.parse(await page.evaluate(
      () => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.length).toBe(1);
    expect(doc.assertions[0].metric).toBe('p95');
  });

  test('the toolbar carries a real verdict for the draft, on a report that declared none',
      async ({ page }) => {
    // The seeded rule has bands, so the draft gate reaches a real verdict — the toolbar on a
    // no-gate report must show it rather than the empty slot it used to render.
    // (The null-status case is a different path; asserts-tab.spec.ts covers it.)
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');
    await page.locator('#samplerTable tbody tr[data-sampler] .wir-seed-btn[data-seed-metric="p95"]')
      .first().click();
    await page.click('#editorSave');

    await expect(page.locator('#assertsVerdict')).toHaveText(/PASS|WARN|FAIL/);
  });
});

test.describe('Sampler badges', () => {

  test('the badge is the worst verdict of the rules judging that sampler', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');

    await expect(page.locator('#samplerTable tbody tr[data-sampler="Slow API"] .assert-badge-mini'))
      .toHaveText('FAIL');
    await expect(page.locator('#samplerTable tbody tr[data-sampler="Fast API"] .assert-badge-mini'))
      .toHaveText('PASS');
  });

  test('clicking it lists every rule that judged the sampler, with its limit', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');

    await page.locator('#samplerTable tbody tr[data-sampler="Slow API"] .assert-badge-mini').click();

    const judging = page.locator('.wir-judging');
    expect(await judging.count()).toBeGreaterThan(0);
    await expect(judging.first()).toContainText('slow-api-p95');
    // The number and the limit, not just a verdict.
    await expect(page.locator('.wir-judging-row')).toContainText('p95 =');
    await expect(page.locator('.wir-judging-row')).toContainText('fail >');
  });

  test('clicking again closes it', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    const badge = page.locator('#samplerTable tbody tr[data-sampler="Slow API"] .assert-badge-mini');

    await badge.click();
    await expect(page.locator('.wir-judging-row')).toHaveCount(1);
    await badge.click();
    await expect(page.locator('.wir-judging-row')).toHaveCount(0);
  });

  test('the badge follows a draft', async ({ page }) => {
    // The badge is recomputed from the current rules, not baked in. Relaxing the rule that
    // fails Slow API must turn its badge green without a reload.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await page.fill('#editorfailValue', '999999');
    await page.fill('#editorwarnValue', '999998');
    await page.click('#editorSave');

    await switchTab(page, 'samplers');
    await expect(page.locator('#samplerTable tbody tr[data-sampler="Slow API"] .assert-badge-mini'))
      .toHaveText('PASS');
  });
});

/**
 * Which metric cells are already gated.
 *
 * Every assertable cell carried an identical ⊕ whether or not that sampler and metric already
 * had a rule, so the likeliest mistake in the feature was seeding a second rule for something
 * already covered — and uniqueId names it `fast-api-p95-2` without a word, producing two rules
 * and two JUnit testcases for one intention. The inverse is worth as much: with the verdict on
 * the cell, the table stops being a list of numbers and becomes a map of what is gated.
 */
test.describe('Cell verdicts', () => {

  const cell = (sampler: string, metric: string) =>
    `#samplerTable tbody tr[data-sampler="${sampler}"] td[data-assert-cell="${metric}"]`;

  test('a metric a rule already judges carries its verdict', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');

    const slow = page.locator(cell('Slow API', 'p95') + ' .wir-cell-verdict');
    await expect(slow).toHaveCount(1);
    await expect(slow).toHaveClass(/wir-chip-fail/);

    const fast = page.locator(cell('Fast API', 'p95') + ' .wir-cell-verdict');
    await expect(fast).toHaveClass(/wir-chip-pass/);
  });

  test('the marker names the rule and its limit, so the cell says why', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    const title = await page.locator(cell('Slow API', 'p95') + ' .wir-cell-verdict')
      .getAttribute('title');
    expect(title).toContain('slow-api-p95');
    expect(title).toContain('p95');
  });

  test('a metric no rule judges gets no verdict, only the seed button', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    // Nothing in E25 names `min`.
    await expect(page.locator(cell('Slow API', 'min') + ' .wir-cell-verdict')).toHaveCount(0);
    await expect(page.locator(cell('Slow API', 'min') + ' .wir-seed-btn')).toHaveCount(1);
  });

  test('the seed button says "add another" where a rule already exists', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    expect(await page.locator(cell('Slow API', 'p95') + ' .wir-seed-btn').getAttribute('title'))
      .toContain('Add another');
    expect(await page.locator(cell('Slow API', 'min') + ' .wir-seed-btn').getAttribute('title'))
      .not.toContain('Add another');
  });

  test('clicking the marker edits the rule rather than starting a duplicate',
      async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    await page.locator(cell('Slow API', 'p95') + ' .wir-cell-verdict').click();

    await expect(page.locator('.wir-slideover')).toBeVisible();
    await expect(page.locator('.wir-slideover-head')).toContainText('slow-api-p95');
  });

  /**
   * The payload caps a rule's results at MAX_MATCHES_PER_RULE (200) and cappedTo keeps the
   * WORST, so on a blanket rule over 500 samplers the ~300 that passed carry no result at all.
   * Reading `rule.results` directly left those rows unbadged — and then any unrelated edit
   * re-evaluated client-side with no cap and 300 badges appeared at once. Reproduced here at
   * three samplers, which is the same shape.
   */
  test('a capped result set still judges every sampler the rule matched', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');

    await page.evaluate(() => {
      const data = JSON.parse(JSON.stringify((window as any).globalChartData));
      data.assertions = [{
        id: 'blanket-p95', description: '', origin: 'file',
        target: { kind: 'match', match: '*', text: '*' },
        metric: 'p95', unit: 'ms', enabled: true,
        fail: { op: 'gt', value: 999999 },
        status: 'PASS', unmatched: false,
        matched: 3,                                     // the true total the payload reports
        results: [{ sampler: 'Slow API', actual: 5, status: 'PASS' }]   // …and all it carried
      }];
      (window as any).WIR.asserts.init(data);
    });

    await expect(page.locator(cell('Slow API', 'p95') + ' .wir-cell-verdict')).toHaveCount(1);
    await expect(
      page.locator(cell('Fast API', 'p95') + ' .wir-cell-verdict'),
      'a sampler the cap dropped was left unjudged'
    ).toHaveCount(1);
    await expect(page.locator(cell('Moderate API', 'p95') + ' .wir-cell-verdict')).toHaveCount(1);
  });

  test('a cell is marked as soon as a rule is drafted for it', async ({ page }) => {
    // Recomputed from the current rules, so seeding from a cell marks that cell before the
    // toast has faded — no reload, no save.
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    await expect(page.locator(cell('Slow API', 'min') + ' .wir-cell-verdict')).toHaveCount(0);

    await page.locator(cell('Slow API', 'min') + ' .wir-seed-btn').click();
    await page.click('#editorSave');

    await switchTab(page, 'samplers');
    await expect(page.locator(cell('Slow API', 'min') + ' .wir-cell-verdict')).toHaveCount(1);
  });
});

test.describe('Generate from run', () => {

  test('the wizard previews what it would create before creating it', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    await expect(page.locator('.wir-modal')).toBeVisible();
    await expect(page.locator('#wizardPreview')).toContainText('Will create');
    await expect(page.locator('#wizardApply')).toContainText('draft rule');
  });

  test('blanket shape produces one rule per metric, not one per sampler', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const perSampler = await page.locator('#wizardApply').innerText();
    await page.click('#wizardShapeBlanket');
    const blanket = await page.locator('#wizardApply').innerText();

    const count = (text: string) => parseInt(text.replace(/\D+/g, ''), 10);
    expect(count(blanket)).toBeLessThan(count(perSampler));
    expect(count(blanket)).toBe(2);   // p95 and errorRate are the defaults
  });

  test('changing headroom changes the bands it would write', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const tight = await page.locator('#wizardPreview').innerText();
    await page.locator('#wizardHeadroom').fill('50');
    const loose = await page.locator('#wizardPreview').innerText();

    expect(loose).not.toBe(tight);
  });

  test('generated rules arrive as drafts, and every one of them passes', async ({ page }) => {
    // First-run adoption: a tester with zero config gets a credible gate in four clicks, then
    // prunes. A generated gate that failed immediately would be worse than none.
    // loadClean already wipes the wir_* keys, so there are no drafts to discard first.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');
    await page.click('#wizardApply');

    await expect(page.locator('.wir-modal')).toHaveCount(0);
    await expect(page.locator('#assertsUnsavedPill')).toBeVisible();

    const generated = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .filter((r: any) => r.description === 'Generated from this run')
      .map((r: any) => ({ id: r.id, status: r.status })));

    expect(generated.length).toBeGreaterThan(0);
    for (const rule of generated) {
      expect(rule.status, `generated rule ${rule.id} does not pass as written`).toBe('PASS');
    }
  });

  test('generated rules do not collide with ids that already exist', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');
    await page.click('#wizardApply');

    const ids = await page.evaluate(
      () => (window as any).WIR.asserts._state.rules.map((r: any) => r.id));
    expect(new Set(ids).size).toBe(ids.length);
  });

  test('the metric picker offers the engine vocabulary, not a hardcoded five', async ({ page }) => {
    // The list used to be five names in the JavaScript, so the wizard could reach a fraction of
    // what the editor beside it offered — and would have gone stale the moment MetricRef changed.
    // It is now `assertMetrics` from the payload, which IS MetricRef.vocabulary().
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const offered = await page.$$eval('#wizardMetrics input[data-metric]',
      els => els.map(e => e.getAttribute('data-metric')));
    const vocabulary = await page.evaluate(
      () => ((window as any).globalChartData.assertMetrics.sampler as any[]).map(m => m.name));

    expect(offered.slice().sort()).toEqual(vocabulary.slice().sort());
    expect(offered.length).toBeGreaterThan(5);
    // The grid percentiles and stdDev are the ones the old list could never reach.
    expect(offered).toEqual(expect.arrayContaining(['p50', 'p75', 'p99.9', 'stdDev', 'min', 'max']));
  });

  test('metrics are grouped by family, using the same labels as the editor', async ({ page }) => {
    // One FAMILY_LABEL map for both pickers. Declaring a second one here would not shadow the
    // editor's — `var` is function-scoped and the module is a single IIFE — it would rename the
    // editor's optgroups too, which is exactly what happened before this test existed.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const families = await page.$$eval('#wizardMetrics .wir-wz-fam-label',
      els => els.map(e => e.textContent?.trim()));
    expect(families).toEqual(['Latency', 'Errors', 'Volume', 'Quality']);

    // The editor's own picker must still say the same words.
    await page.click('#wizardApply');
    await page.click('#addAssertBtn');
    const optgroups = await page.locator('#editorMetric optgroup')
      .evaluateAll(els => els.map(e => e.getAttribute('label')));
    expect(optgroups.slice().sort()).toEqual(families.slice().sort());
  });

  test('All and None move every metric, Defaults comes back to p95 and error rate', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const ticked = () => page.$$eval('#wizardMetrics input[data-metric]',
      els => els.filter(e => (e as HTMLInputElement).checked).length);
    const total = await page.$$eval('#wizardMetrics input[data-metric]', els => els.length);

    expect(await ticked()).toBe(2);
    const metricActs = page.locator('#wizardMetricCount ~ .wir-wz-acts');
    await page.click('#wizardMetricsAll');
    expect(await ticked()).toBe(total);
    await metricActs.getByText('None', { exact: true }).click();
    expect(await ticked()).toBe(0);
    await expect(page.locator('#wizardApply')).toBeDisabled();
    await metricActs.getByText('Defaults', { exact: true }).click();
    expect(await ticked()).toBe(2);
  });
});

test.describe('Generate from run — choosing the samplers', () => {

  test('the sampler table lists every sampler with the numbers to judge by', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const rows = await page.$$eval('#wizardSamplerTable tbody tr',
      els => els.filter(e => e.querySelector('input[type=checkbox]')).length);
    const inRun = await page.evaluate(() => (window as any).globalChartData.samplers
      .filter((s: any) => s.count > 0).length);

    expect(rows).toBeGreaterThan(0);
    expect(rows).toBe(inRun);
    // The numbers are the point: choosing by evidence rather than by memory of the plan.
    await expect(page.locator('#wizardSamplerTable thead')).toContainText('Samples');
    await expect(page.locator('#wizardSamplerTable thead')).toContainText('p95');
  });

  test('unticking a sampler removes its rules and the count says so', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const before = await page.locator('#wizardApply').innerText();
    await page.locator('#wizardSamplerTable tbody tr input[type=checkbox]:checked').first().uncheck();
    const after = await page.locator('#wizardApply').innerText();

    const count = (t: string) => parseInt(t.replace(/\D+/g, ''), 10);
    expect(count(after)).toBeLessThan(count(before));
    // 2 default metrics per sampler, so removing one sampler removes exactly two rules.
    expect(count(before) - count(after)).toBe(2);
  });

  test('None disables Apply, All brings every sampler back', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const ticked = () => page.$$eval('#wizardSamplerTable tbody input[type=checkbox]',
      els => els.filter(e => (e as HTMLInputElement).checked).length);
    const total = await page.$$eval('#wizardSamplerTable tbody input[type=checkbox]', els => els.length);

    await page.click('#wizardSamplersAll');
    expect(await ticked()).toBe(total);

    await page.locator('#wizardSamplerCount ~ .wir-wz-acts')
      .getByText('None', { exact: true }).click();
    expect(await ticked()).toBe(0);
    await expect(page.locator('#wizardApply')).toBeDisabled();
  });

  test('a blanket rule over a subset names the samplers instead of matching everything',
    async ({ page }) => {
      // `match: "*"` is a live pattern the next run re-resolves, not a shorthand for "the ones I
      // chose": a sampler added to the plan later would be judged by a rule nobody pointed at it,
      // and a helper deliberately unticked here would be swept straight back in.
      await loadClean(page, 'E25');
      await switchTab(page, 'asserts');
      await page.click('#generateAssertsBtn');
      await page.click('#wizardShapeBlanket');

      await page.click('#wizardSamplersAll');
      const whenAll = await page.evaluate(
        () => (window as any).WIR.asserts._wizardRules()[0].target);
      expect(whenAll.kind).toBe('match');
      expect(whenAll.match).toBe('*');

      await page.locator('#wizardSamplerTable tbody tr input[type=checkbox]:checked').first().uncheck();
      const whenSome = await page.evaluate(
        () => (window as any).WIR.asserts._wizardRules()[0].target);
      expect(whenSome.kind).toBe('samplers');
      expect(whenSome.samplers.length).toBeGreaterThan(0);
    });
});

test.describe('Generate from run — tuning', () => {

  test('a typed tolerance past the slider is used, and the caption says so', async ({ page }) => {
    // The defect this pins: the caption was bound to the slider's clamped copy while the seeding
    // read the raw number, so typing 500 with a slider that stops at 100 captioned 100 and built
    // every rule at 500. The caption reads the value in use; the clamp is stated, not hidden.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    await page.fill('#wizardHeadroomInput', '500');

    await expect(page.locator('#wizardHeadroomLabel')).toHaveText('500%');
    await expect(page.locator('.wir-wz-past')).toBeVisible();
    const effective = await page.evaluate(() => (window as any).WIR.asserts._wizardState().headroom);
    expect(effective).toBe(500);
  });

  test('typing a partial number does not snap the slider or lose the caret', async ({ page }) => {
    // "2" on the way to "25". Keeping the raw text beside the parsed number is what stops the
    // field being rewritten under the caret, and the partial re-render is what stops the input
    // being replaced wholesale on every keystroke.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    const input = page.locator('#wizardHeadroomInput');
    await input.click();
    await input.press('Control+a');
    await input.type('25', { delay: 30 });

    await expect(input).toHaveValue('25');
    await expect(input).toBeFocused();
    expect(await page.evaluate(() => (window as any).WIR.asserts._wizardState().headroom)).toBe(25);
  });

  test('warn-at is clamped to 1-99, because 100 makes warn and fail the same line', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    await page.fill('#wizardWarnPctInput', '100');
    expect(await page.evaluate(() => (window as any).WIR.asserts._wizardState().warnPct)).toBe(99);

    await page.fill('#wizardWarnPctInput', '0');
    expect(await page.evaluate(() => (window as any).WIR.asserts._wizardState().warnPct)).toBe(1);
  });

  test('the preview states the multiplication that produced the rule count', async ({ page }) => {
    // samplers × metrics is arithmetic people do not do in their heads, and it is the whole
    // reason a selection matters. Saying the count without saying where it came from invites
    // the "why are there 600 rules" question after the fact.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#generateAssertsBtn');

    await expect(page.locator('#wizardPreview')).toContainText(/\d+ samplers? × \d+ metrics?/);
  });
});
