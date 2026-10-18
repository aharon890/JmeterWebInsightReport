import { test, expect } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * The Asserts tab and the Summary gate strip, read-only.
 *
 * E25 is built for this: its thresholds are deliberately extreme so that one of every rule
 * state — PASS, WARN, FAIL, and three of the four NOT_JUDGED reasons — renders on
 * any machine. The scenarios run 4-up, and a realistic limit would make these assertions depend
 * on how loaded the runner was.
 *
 * E02 has no annotations at all and is the control: nothing about the gate may appear.
 */

test.describe('Asserts tab [E25]', () => {

  test('the tab appears and opens without JS errors', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await loadClean(page, 'E25');
      await expect(page.locator('#assertsTabBtn')).toBeVisible();
      await switchTab(page, 'asserts');
      await expect(page.locator('#assertsTable')).toBeVisible();
    });
  });

  test('the tab sits after Compare and before Notes', async ({ page }) => {
    // Deliberate: the first four positions are muscle memory for anyone who has used the
    // report. The gate is still the headline — the Summary tab opens with the strip.
    await loadClean(page, 'E25');
    const tabs = await page.locator('.tab-btn').evaluateAll(
      els => els.map(e => e.getAttribute('data-tab')));
    expect(tabs.indexOf('asserts')).toBeGreaterThan(tabs.indexOf('compare'));
    expect(tabs.indexOf('asserts')).toBeLessThan(tabs.indexOf('notes'));
  });

  test('every rule state renders', async ({ page }) => {
    // The fixture exists to produce all of these in one run. If a state stops appearing here,
    // either the fixture drifted or a status stopped being rendered — both worth failing on.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const statuses = await page.locator('#assertsTable tbody tr.wir-rule-row td:first-child')
      .allInnerTexts();
    const seen = new Set(statuses.map(s => s.trim()));

    for (const state of ['PASS', 'WARN', 'FAIL', 'UNMATCHED', 'DISABLED', 'INVALID']) {
      expect(seen, `no row rendered with status ${state}`).toContain(state);
    }
  });

  test('rules are sorted worst-first', async ({ page }) => {
    // A table you read top-down and stop when it stops being interesting.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    // The DISPLAY vocabulary, worst first — a mirror of SORT_RANK in report.js. INVALID, and
    // the two server-side config errors after it, sit between WARN and PASS: none is a verdict
    // any more, but a check the file asked for that never ran is still more interesting than
    // one that passed, and the table is read top-down until it stops being interesting. Only
    // the client subset can appear on E25; a server value maps here so a row carrying one
    // fails this spec loudly rather than sorting as undefined.
    const rank: Record<string, number> = {
      FAIL: 0, WARN: 1, INVALID: 2, 'SOURCE FAILED': 3, 'LOW COVERAGE': 4, PASS: 5,
      'NO VALUE': 6, 'LOAD NOT ACHIEVED': 7, UNMATCHED: 8, DISABLED: 9,
    };
    const statuses = (await page.locator('#assertsTable tbody tr.wir-rule-row td:first-child')
      .allInnerTexts()).map(s => rank[s.trim()]);

    for (let i = 1; i < statuses.length; i++) {
      expect(statuses[i], `row ${i} is ranked above row ${i - 1}`)
        .toBeGreaterThanOrEqual(statuses[i - 1]);
    }
  });

  test('an unmatched rule shows a follow-up row with a did-you-mean', async ({ page }) => {
    // A rule that matched nothing is a different problem from one that breached — the fix is
    // usually a typo in the target, not a slower system — so it gets a different treatment.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const followUp = page.locator('.wir-followup-row');
    await expect(followUp).toHaveCount(1);
    await expect(followUp).toContainText('Matched nothing in this run');
    await expect(followUp).toContainText('nothing was judged');
    await expect(followUp).toContainText('Did you mean');
    await expect(followUp).toContainText('Fast API');
  });

  test('a disabled rule is dimmed rather than hidden', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const row = page.locator('#assertsTable tbody tr[data-rule-id="parked-rule"]');
    await expect(row).toHaveCount(1);
    await expect(row).toHaveClass(/wir-row-disabled/);
  });

  test('an off-grid percentile resolves to a real number, not a dash', async ({ page }) => {
    // p99.9 is not on the standard grid the samplers table renders — it comes back out of the
    // retained digest. A rule on it must therefore carry an actual, or the row would claim
    // PASS while showing nothing it passed against.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const row = page.locator('#assertsTable tbody tr[data-rule-id="slow-api-tail"]');
    await expect(row.locator('td').first()).toContainText('PASS');
    // The Actual cell specifically: Budget is also .wir-num, and its own emptiness is a
    // different claim entirely (see the budget specs).
    await expect(row.locator('td.wir-num:not(.wir-budget)')).not.toContainText('—');
  });

  test('matches expand to show every match, passing ones included', async ({ page }) => {
    // A verdict alone does not say which of the matched samplers breached, nor how close the
    // others were — which is exactly what a tester needs in order to move a threshold.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const row = page.locator('#assertsTable tbody tr[data-rule-id="api-error-rate"]');
    await expect(page.locator('[data-match-of="api-error-rate"]')).toHaveCount(0);

    await row.locator('.wir-matches-btn').click();
    const matches = page.locator('[data-match-of="api-error-rate"]');
    expect(await matches.count()).toBeGreaterThan(1);
    await expect(matches.first()).toContainText('PASS');

    await row.locator('.wir-matches-btn').click();
    await expect(page.locator('[data-match-of="api-error-rate"]')).toHaveCount(0);
  });

  test('criteria read as bands, not as raw JSON', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsTable tbody tr[data-rule-id="slow-api-p95"] .wir-criteria'))
      .toContainText('fail > 2 ms');
    // A whole-run rule on a unitless metric, so the symbol renders with no suffix after it.
    await expect(page.locator('#assertsTable tbody tr[data-rule-id="run-apdex-ceiling"] .wir-criteria'))
      .toContainText('fail > 2');
  });

  /**
   * The banner that replaced a verdict.
   *
   * <p>Before A3 a rule the file got wrong was INVALID, a status that outranked FAIL, so a
   * mistyped metric name stopped a build. It decides nothing now — nothing was measured — and
   * this is the compensation: the tab cannot be read as healthy while a rule is broken, because
   * the first thing on it says which rule, why, and offers to open the editor there.
   */
  test('a rule that could not run is banner-ed at the top of the tab', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const banner = page.locator('#assertsBroken .wir-broken-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('1 rule could not run');
    // The sentence, not just the id: "unknown-metric is broken" sends nobody anywhere.
    await expect(banner).toContainText('unknown metric');
    await expect(banner.locator('.wir-rule-id')).toHaveText('unknown-metric');

    // It sits above the toolbar, so it cannot be scrolled past on the way in.
    const order = await page.evaluate(() => {
      const broken = document.querySelector('#assertsBroken')!;
      const toolbar = document.querySelector('#assertsToolbar')!;
      return broken.compareDocumentPosition(toolbar) & Node.DOCUMENT_POSITION_FOLLOWING;
    });
    expect(order, 'the banner must precede the toolbar').toBeTruthy();

    // And there is no dismiss control. A banner that can be dismissed is one the person who
    // most needed it dismissed once, months ago.
    await expect(banner.locator('button')).toHaveCount(1);
    await expect(banner.locator('button')).toHaveText('Fix');
  });

  test('Fix opens the editor on the rule that could not run', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    await page.click('#assertsBroken .wir-broken-row button');
    await expect(page.locator('#editorId')).toHaveValue('unknown-metric');
  });

  test('a run with nothing broken shows no banner [E25]', async ({ page }) => {
    // The negative half, on the fixture that HAS a banner: filtering must not create one, and
    // an empty banner host must render nothing rather than an empty red box.
    await loadClean(page, 'E02');
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsBroken .wir-broken-banner')).toHaveCount(0);
  });

  /**
   * Rule 5's column: actual against expected, as the one number that answers "how close".
   *
   * <p>Actual and Criteria were already beside each other, so the raw pair was on screen. What
   * was missing is what the pair MEANS — 24% of the budget, or 212% of it — which is the
   * question someone deciding whether to move a threshold actually has.
   */
  test('the Budget column says how much of the fail band was used', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    // Asserted by MEANING, not by parsing the number. E25's thresholds are deliberately
    // extreme so every rule state renders on any machine, which puts most rules under 1% —
    // and "<1%" is not a number parseInt can read.
    const passing = page.locator('tr[data-rule-id="fast-api-p95"]');
    await expect(passing.locator('.wir-budget-pct')).toBeVisible();
    await expect(passing.locator('.wir-budget-fill'),
      'a rule inside its band is not coloured as a breach').toHaveClass(/wir-budget-ok/);

    const breaching = page.locator('tr[data-rule-id="slow-api-p95"]');
    await expect(breaching.locator('.wir-budget-fill')).toHaveClass(/wir-budget-over/);
    expect(parseInt((await breaching.locator('.wir-budget-pct').innerText()).replace('%', ''), 10),
      'past the line must read over 100%, not clamp to it').toBeGreaterThan(100);
  });

  test('a budget that rounds to nothing reads "<1%", not "0%"', async ({ page }) => {
    // 1,986 ms against a 999,999 ms limit really is 0.2% of the budget. Rounded to "0%" it
    // reads exactly like the EMPTY cell beside it, which means "there is no budget to
    // measure" — and "used almost none of it" and "has none of it" are opposite facts.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    await expect(page.locator('tr[data-rule-id="slow-api-tail"] .wir-budget-pct'))
      .toHaveText('<1%');
  });

  test('a rule with nothing to measure against shows no budget, not 0%', async ({ page }) => {
    // The one thing this column must never say. 0% reads as "comfortably inside" — the exact
    // opposite of the truth for a rule that measured nothing at all.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    for (const id of ['renamed-sampler', 'unknown-metric', 'parked-rule']) {
      await expect(page.locator(`tr[data-rule-id="${id}"] .wir-budget-pct`),
        `${id} must show no budget`).toHaveCount(0);
    }
  });

  test('the bar clamps but the number does not', async ({ page }) => {
    // A rule that failed by 3x and one that failed by a hair must not look alike — but a
    // 300%-wide bar would break the row, so only the bar is capped.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const fill = page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-fill');
    const width = await fill.evaluate(el => (el as HTMLElement).style.width);
    expect(width).toBe('100%');
    expect(parseInt(
      (await page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-pct').innerText())
        .replace('%', ''), 10)).toBeGreaterThan(100);
  });

  test('the budget follows a draft, because it is computed here', async ({ page }) => {
    // The reason attainmentOf mirrors Attainment.of instead of shipping the number from Java:
    // the client re-evaluates as you type, and a number baked at generation time would be
    // wrong exactly when someone is looking at it.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const before = await page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-pct').innerText();

    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await page.fill('#editorfailValue', '999999');
    await page.click('#editorSave');

    const after = await page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-pct').innerText();
    expect(after, 'the budget did not follow the edit').not.toBe(before);
    // From over its budget to nowhere near it. Asserted by colour, because the new value is
    // "<1%" — which is the point of that label and not a number parseInt can read.
    //
    // wir-budget-warn, not -ok: the rule keeps `warn: {gt: 1}` and only the FAIL band was
    // raised, so it is still over its warn line. The bar colours by status, and that is the
    // most useful thing this column says — "nowhere near the fail budget, and still WARN".
    await expect(page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-fill'))
      .toHaveClass(/wir-budget-warn/);
    await expect(page.locator('tr[data-rule-id="slow-api-p95"] .wir-budget-fill'))
      .not.toHaveClass(/wir-budget-over/);
  });

  test('validation findings are shown, with the suggestion', async ({ page }) => {
    // The same findings the JMeter log and the CI/CD JSON carry. The report is the artifact you
    // debug the config from, so it must not be the one place that stays quiet.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const findings = page.locator('.wir-finding');
    expect(await findings.count()).toBeGreaterThanOrEqual(2);
    await expect(page.locator('#assertsFindings')).toContainText("did you mean 'p95'");
  });
});

test.describe('Summary gate strip [E25]', () => {

  test('the strip leads the Summary tab with the verdict and its make-up', async ({ page }) => {
    await loadClean(page, 'E25');

    const strip = page.locator('.wir-gate-strip');
    await expect(strip).toBeVisible();
    // FAIL, not INVALID. The verdict is the worst rule that DECIDED something, and E25's
    // unknown-metric rule decides nothing — it used to outrank the real breach beside it and
    // put the word INVALID on a run where a threshold had genuinely been exceeded.
    await expect(strip.locator('.wir-chip').first()).toHaveText('FAIL');
    await expect(strip).toContainText('declared');
    await expect(strip).toContainText('only these decide the client verdict');
  });

  test('[R] F14: the strip speaks for the client verdict only, on a run whose server gate fails too',
    async ({ page }) => {
      // Orchestrator call, 2026-10-02 (D24). E26's building Kafka gate is at FAIL, and the strip
      // said its 2 client rules were "only these decide the verdict". Six since Batch I: the two
      // load rules and the four near-band rules client-numbers.spec.ts reads.
      await loadClean(page, 'E26');
      const sub = page.locator('#gateStrip .wir-gate-sub');
      await expect(sub).toHaveText('6 rules declared — only these decide the client verdict');
    });

  test('the counts add up to the number of declared rules', async ({ page }) => {
    // The same arithmetic the CI/CD JSON promises. If the strip and the gate block disagreed,
    // one of them would be lying to somebody.
    await loadClean(page, 'E25');

    const counts = await page.locator('.wir-count').allInnerTexts();
    const total = counts.reduce((sum, text) => sum + parseInt(text.trim(), 10), 0);

    const declaredText = await page.locator('.wir-gate-sub').innerText();
    const declared = parseInt(declaredText.trim(), 10);
    expect(total).toBe(declared);
  });

  test('the worst breaches name the rule, the number and the limit', async ({ page }) => {
    // The thing the old badge got wrong: it said FAIL and left you to go and find out why.
    await loadClean(page, 'E25');

    const breaches = page.locator('.wir-breach');
    expect(await breaches.count()).toBeGreaterThan(0);
    expect(await breaches.count()).toBeLessThanOrEqual(3);
    await expect(page.locator('.wir-gate-breaches')).toContainText('slow-api-p95');
    // actual vs limit, not just a verdict
    await expect(page.locator('.wir-gate-breaches')).toContainText('vs');
  });

  test('the strip links through to the tab', async ({ page }) => {
    await loadClean(page, 'E25');
    await page.click('#openAssertsBtn');
    await expect(page.locator('#tab-asserts')).toHaveClass(/active/);
  });
});

test.describe('A gate that decided nothing', () => {

  test('reads NOT EVALUATED rather than rendering a blank chip', async ({ page }) => {
    // chipClass(null) produced `wir-chip-null`, which matches no CSS rule, and chipLabel(null)
    // produced '' — an empty unstyled box where the verdict goes. Null is what evaluateAll
    // returns when nothing gates, which needs every rule disabled: DISABLED is not in GATES,
    // so it holds even for E25's INVALID and unmatched rules.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    await page.evaluate(() => {
      const A = (window as any).WIR.asserts;
      // Every rule can be disabled now — there is no derived rule to refuse — so this simply
      // turns the whole gate off and checks what a null verdict renders as.
      // Filter first — `parked-rule` is already enabled:false, and toggling would ENABLE it.
      A._state.rules
        .filter((r: any) => r.enabled !== false)
        .map((r: any) => r.key)
        .forEach((key: string) => A.toggleEnabled(key));
    });

    // The null path was genuinely taken. Without this the assertions below are vacuous —
    // which is exactly how the first version of this test passed while testing nothing.
    expect(await page.evaluate(
      () => (window as any).WIR.asserts._state.draftGate.status)).toBeNull();

    const verdict = page.locator('#assertsVerdict');
    await expect(verdict).toHaveText('NOT EVALUATED');
    await expect(verdict).toHaveClass(/wir-chip-not_evaluated/);
    // The draft banner renders through the same chipLabel/chipClass pair.
    await expect(page.locator('#assertsWouldBe')).toHaveText('NOT EVALUATED');
  });
});

test.describe('No gate declared [E02]', () => {

  test('no verdict is claimed when nothing was declared', async ({ page }) => {
    // A run with no gate has not passed one. A green strip saying otherwise is the lie this
    // whole feature exists to prevent — so the strip stays absent. What changed is the tab: it
    // used to be hidden too, which protected nothing (the strip is the thing that could lie) and
    // cost everything (there was no way in from the tab bar). Reachable, and still silent.
    await loadClean(page, 'E02');

    await expect(page.locator('.wir-gate-strip')).toHaveCount(0);
    await expect(page.locator('#assertsTabBtn')).toBeVisible();
  });

  test('the tab a gateless run opens on offers the two ways to declare', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsEmpty')).toBeVisible();
    await expect(page.locator('#emptyAddAssertBtn')).toBeVisible();
    await expect(page.locator('#emptyGenerateBtn')).toBeVisible();

    // The toolbar is the other half: seeding is only useful if it ends at a file, so Download
    // has to exist before there is anything to download.
    await expect(page.locator('#saveAssertsBtn')).toBeVisible();
    await expect(page.locator('#assertsVerdict')).toHaveText('NOT EVALUATED');

    // The metric vocabulary is the thing the payload used to withhold here. Without it the
    // editor this button opens has an empty dropdown.
    const metrics = await page.evaluate(
      () => (window as any).globalChartData?.assertMetrics?.sampler?.length ?? 0);
    expect(metrics, 'a gateless report must still carry the metric vocabulary').toBeGreaterThan(0);
  });
});

test.describe('Theming', () => {

  test('the tab uses design tokens, so dark mode needs no extra rules', async ({ page }) => {
    // Every colour in the new section is a custom property. A hardcoded one is a bug that only
    // shows up for the reader who needs dark mode most.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const light = await page.locator('.wir-asserts-table th').first()
      .evaluate(el => getComputedStyle(el).backgroundColor);

    await page.evaluate(() => document.body.classList.add('dark-mode'));
    const dark = await page.locator('.wir-asserts-table th').first()
      .evaluate(el => getComputedStyle(el).backgroundColor);

    expect(dark).not.toBe(light);
  });

  test('a hostile rule id or description cannot inject markup', async ({ page }) => {
    // Rule ids, descriptions, targets and sampler names all come from a file a user wrote.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');

    const injected = await page.evaluate(() => {
      const hostile = '<img src=x onerror="window.__pwned=1">';
      // Re-render through the real path with a hostile rule rather than poking the DOM.
      (window as any).WIR.asserts.init({
        gate: { status: 'FAIL', declared: 1, evaluated: 1, passed: 0, warned: 0, failed: 1,
                unmatched: 0, disabled: 0, skipped: 0, invalid: 0 },
        assertions: [{
          id: hostile, description: hostile, origin: 'file',
          target: { kind: 'sampler', text: hostile, sampler: hostile },
          metric: 'p95', unit: 'ms', enabled: true,
          status: 'FAIL', unmatched: false, matched: 1,
          results: [{ sampler: hostile, actual: 1, status: 'FAIL' }],
        }],
        assertFindings: [{ severity: 'error', ruleId: hostile, message: hostile }],
      });
      return {
        pwned: !!(window as any).__pwned,
        images: document.querySelectorAll('#tab-asserts img').length,
        rendered: (document.querySelector('#assertsTable .wir-rule-id') as HTMLElement).innerText,
      };
    });

    expect(injected.pwned).toBe(false);
    expect(injected.images).toBe(0);
    expect(injected.rendered).toContain('<img');
  });
});
