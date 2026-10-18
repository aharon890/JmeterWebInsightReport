import { test, expect } from '@playwright/test';
import { loadClean, loadDirty, switchTab, expectNoJsErrors } from './helpers';

/**
 * Filtering and sorting the Assertion Rules table.
 *
 * Both are view-only: nothing here may move the verdict, the gate strip, the worst-breaches
 * list, the findings card, the header badge or the downloaded file. Several
 * tests below assert exactly that, because "it only changes the view" is the whole contract
 * and is the thing a future refactor would break silently.
 *
 * E25 is the fixture with something of everything: 13 rules, six statuses, one unmatched
 * target, one invalid metric, a glob rule, and one rule migrated from the legacy
 * loadValidity block — which is now indistinguishable from a hand-written one.
 *
 * WIR.asserts._visible() returns the rule ids the table would render, in order. Reading that
 * rather than the DOM keeps these assertions about the filter/sort engine instead of about
 * how a row happens to be marked up.
 */

/* E25's declared rule count: 12 in assertions[], 1 migrated from the legacy block.
   Pinned rather than derived, because a rule silently vanishing from the fixture is exactly
   what these filter tests would otherwise hide. */
const ALL = 13;

async function openTab(page) {
  await loadClean(page, 'E25');
  await switchTab(page, 'asserts');
}

const visible = (page) => page.evaluate(() => (window as any).WIR.asserts._visible());

test.describe('Assertion Rules — filtering [E25]', () => {

  test('opens unfiltered, worst-first, with no JS errors', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openTab(page);
      expect(await visible(page)).toHaveLength(ALL);
      await expect(page.locator('#assertsRulesCount'))
        .toHaveText(`${ALL} rules · sorted by severity ↑`);
      // Nothing to clear, so no affordance offering to.
      await expect(page.locator('#assertsClearFilters')).toHaveCount(0);
    });
  });

  test('status chips count the whole run, not the filtered view', async ({ page }) => {
    // Counts that shrink as you filter read 0 for everything you did not pick, which hides
    // what the run actually contains.
    await openTab(page);
    const before = await page.locator('.wir-status-chip').allInnerTexts();
    expect(before.map(s => s.trim())).toEqual(
      ['FAIL 1', 'WARN 1', 'INVALID 1', 'PASS 8', 'UNMATCHED 1', 'DISABLED 1']);

    await page.click('.wir-status-chip[data-status="FAIL"]');
    const after = await page.locator('.wir-status-chip').allInnerTexts();
    expect(after.map(s => s.trim())).toEqual(before.map(s => s.trim()));
  });

  test('a reason with no rules gets no chip', async ({ page }) => {
    // E25 has nothing the run could not measure, so NO VALUE must not render an empty chip.
    // Chips are the run's own contents; a zero one would be noise that reads as a category.
    await openTab(page);
    await expect(page.locator('.wir-status-chip[data-status="NO VALUE"]')).toHaveCount(0);
  });

  test('searching a sampler name finds the rules that judge it without naming it',
    async ({ page }) => {
      // The reason the filter resolves targets rather than reading rule.results: the glob rule
      // below never contains the string "Moderate API" anywhere in its own text, and
      // rule.results is capped at 200 by GatePayloadBuilder and silently uncapped again by the
      // client evaluator after any edit.
      await openTab(page);
      await page.fill('#assertsSearch', 'Moderate API');
      expect(await visible(page)).toEqual([
        'moderate-api-p95',   // names it
        'api-error-rate',     // glob *API
        'moderate-api-p90',   // names it too, and has no id of its own — see asserts-save
      ]);
    });

  test('search matches id, description and metric', async ({ page }) => {
    await openTab(page);

    await page.fill('#assertsSearch', 'parked-rule');
    expect(await visible(page)).toEqual(['parked-rule']);

    await page.fill('#assertsSearch', 'apdex');
    expect(await visible(page)).toEqual(['run-apdex-ceiling']);

    await page.fill('#assertsSearch', 'smoke');
    expect(await visible(page)).toEqual(['smoke-only-rule']);
  });

  test('typing does not steal focus from the search box', async ({ page }) => {
    // renderTable() rebuilds the whole card; a naive re-render on every keystroke destroys the
    // <input> being typed into and takes the caret with it.
    await openTab(page);
    await page.fill('#assertsSearch', 'api');
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('assertsSearch');
    expect(await page.inputValue('#assertsSearch')).toBe('api');
  });

  test('the count line switches to "Showing N of M"', async ({ page }) => {
    await openTab(page);
    await page.fill('#assertsSearch', 'parked');
    await expect(page.locator('#assertsRulesCount'))
      .toHaveText(`Showing 1 of ${ALL} rules · sorted by severity ↑`);
  });

  test('status chips OR within themselves', async ({ page }) => {
    await openTab(page);
    await page.click('.wir-status-chip[data-status="FAIL"]');
    expect(await visible(page)).toEqual(['slow-api-p95']);

    await page.click('.wir-status-chip[data-status="WARN"]');
    expect(await visible(page)).toEqual(['slow-api-p95', 'moderate-api-p95']);

    // Clicking an active chip removes it again.
    await page.click('.wir-status-chip[data-status="FAIL"]');
    expect(await visible(page)).toEqual(['moderate-api-p95']);
  });

  test('a chip and the search box AND together', async ({ page }) => {
    await openTab(page);
    // "api-" spans PASS, WARN and FAIL, so this proves the chip and the box AND rather than
    // either one quietly winning: only the PASS members survive.
    await page.click('.wir-status-chip[data-status="PASS"]');
    await page.fill('#assertsSearch', 'api-');
    expect((await visible(page)).sort()).toEqual(
      ['api-error-rate', 'fast-api-p95', 'moderate-api-p90', 'slow-api-tail']);
  });

  test('Clear filters appears only when something is filtered, and resets everything',
    async ({ page }) => {
      await openTab(page);
      await page.click('.wir-status-chip[data-status="FAIL"]');
      await page.fill('#assertsSearch', 'slow');
      await expect(page.locator('#assertsClearFilters')).toBeVisible();

      await page.click('#assertsClearFilters');
      expect(await visible(page)).toHaveLength(ALL);
      expect(await page.inputValue('#assertsSearch')).toBe('');
      await expect(page.locator('.wir-status-chip-on')).toHaveCount(0);
      await expect(page.locator('#assertsClearFilters')).toHaveCount(0);
    });

  test('the advanced row is collapsed until asked for', async ({ page }) => {
    await openTab(page);
    await expect(page.locator('#assertsAdvanced')).toHaveCount(0);
    await page.click('#assertsMoreFilters');
    await expect(page.locator('#assertsAdvanced')).toBeVisible();
    await expect(page.locator('#assertsMoreFilters')).toContainText('Fewer filters');
  });

  test('each advanced control filters', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsMoreFilters');

    await page.selectOption('#assertsFilter-metric', 'apdex');
    expect(await visible(page)).toEqual(['run-apdex-ceiling']);
    await page.selectOption('#assertsFilter-metric', '');

    await page.selectOption('#assertsFilter-kind', 'overall');
    // load:throughput is a whole-run rule like the others — the legacy block it came from is
    // migrated into assertions[] at load, so nothing about it is special any more.
    expect(await visible(page)).toEqual(
      ['load:throughput', 'run-apdex-ceiling', 'run-throughput']);
    await page.selectOption('#assertsFilter-kind', '');

    await page.selectOption('#assertsFilter-metric', 'p99');
    expect(await visible(page)).toContain('smoke-only-rule');
  });

  test('"Judges sampler" offers Whole run plus every sampler, and filters by resolved match',
    async ({ page }) => {
      await openTab(page);
      await page.click('#assertsMoreFilters');

      // The whole run first, keyed apart from any sampler — one could be called "Whole run" — and
      // labelled so only on screen (REV-4).
      const options = await page.locator('#assertsFilter-sampler option')
        .evaluateAll(els => els.map(e => [(e as HTMLOptionElement).value, e.textContent]));
      expect(options).toEqual([['', 'Any'], ['__overall__', 'Whole run'], ['Fast API', 'Fast API'],
        ['Moderate API', 'Moderate API'], ['Slow API', 'Slow API']]);

      await page.selectOption('#assertsFilter-sampler', { label: 'Whole run' });
      expect(await visible(page)).toEqual(
        ['load:throughput', 'run-apdex-ceiling', 'run-throughput']);

      await page.selectOption('#assertsFilter-sampler', 'Moderate API');
      expect(await visible(page)).toEqual(
        ['moderate-api-p95', 'api-error-rate', 'moderate-api-p90']);
    });

  test('the More filters button reports how many advanced filters are set', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsMoreFilters');
    await page.selectOption('#assertsFilter-metric', 'p95');
    await page.selectOption('#assertsFilter-kind', 'sampler');
    await expect(page.locator('#assertsMoreFilters')).toContainText('· 2');
  });

  /**
   * "Unmatched only" is gone; the UNMATCHED chip is the one control for it.
   *
   * <p>Two controls did one job. Before A3, `unmatched` was a cross-cutting flag that could
   * coexist with any status, so a dedicated checkbox was the only way to isolate those rules.
   * Since the collapse, an unmatched rule IS a display status with its own chip in the strip —
   * so the checkbox was a second, less discoverable way to press the same button.
   */
  test('the UNMATCHED chip is how you isolate unmatched rules', async ({ page }) => {
    await openTab(page);
    await expect(page.locator('#assertsFilter-unmatchedOnly'),
      'the redundant checkbox must be gone').toHaveCount(0);

    await page.click('.wir-status-chip[data-status="UNMATCHED"]');
    expect(await visible(page)).toEqual(['renamed-sampler']);
  });

  test('Drafts only', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsMoreFilters');

    // Nothing is a draft until something is edited in this session.
    await page.check('#assertsFilter-draftsOnly');
    expect(await visible(page)).toEqual([]);
  });

  test('the filtered-empty panel replaces the table but keeps the toolbar', async ({ page }) => {
    await openTab(page);
    await page.fill('#assertsSearch', 'no-such-rule-anywhere');

    await expect(page.locator('#assertsFilteredEmpty')).toBeVisible();
    await expect(page.locator('#assertsFilteredEmpty'))
      .toContainText(`All ${ALL} rules are hidden by the current filter.`);
    await expect(page.locator('#assertsTable')).toHaveCount(0);
    // The toolbar survives, or there would be no way back.
    await expect(page.locator('#assertsSearch')).toBeVisible();

    await page.click('#assertsFilteredEmpty .wir-btn');
    expect(await visible(page)).toHaveLength(ALL);
  });

  test('a report that declared nothing shows the empty state, not a filtered one', async ({ page }) => {
    // E02 has no annotations. The tab is reachable now, but there is nothing to filter, so the
    // filter bar must not render — and the filtered-empty panel in particular must never be what
    // a rule-less report shows. It would blame a filter for the absence of rules.
    await loadClean(page, 'E02');
    await expect(page.locator('#assertsTabBtn')).toBeVisible();

    await expect(page.locator('#assertsEmpty')).toHaveCount(1);
    await expect(page.locator('#assertsFilteredEmpty')).toHaveCount(0);
    await expect(page.locator('#assertsSearch')).toHaveCount(0);
  });

  test('changing a filter collapses an expanded Matches row', async ({ page }) => {
    await openTab(page);
    await page.click('tr[data-rule-id="api-error-rate"] .wir-matches-btn');
    expect(await page.locator('.wir-match-row').count()).toBeGreaterThan(0);

    await page.fill('#assertsSearch', 'api');
    await expect(page.locator('.wir-match-row')).toHaveCount(0);
  });
});

test.describe('Assertion Rules — sorting [E25]', () => {

  test('every header except actions is sortable and marked up for a screen reader',
    async ({ page }) => {
      await openTab(page);
      const cols = await page.locator('#assertsTable th[data-sort-col]')
        .evaluateAll(els => els.map(e => e.getAttribute('data-sort-col')));
      expect(cols).toEqual(
        ['severity', 'id', 'target', 'metric', 'criteria', 'actual', 'budget', 'matches']);

      await expect(page.locator('#assertsTable th[data-sort-col="severity"]'))
        .toHaveAttribute('aria-sort', 'ascending');
      await expect(page.locator('#assertsTable th[data-sort-col="id"]'))
        .toHaveAttribute('aria-sort', 'none');
    });

  test('clicking a new column takes its default direction, clicking it again flips',
    async ({ page }) => {
      await openTab(page);
      const actual = page.locator('#assertsTable th[data-sort-col="actual"]');

      // Actual and Matches read "worst first" / "most first"; everything else ascends.
      await actual.click();
      await expect(actual).toHaveAttribute('aria-sort', 'descending');
      await actual.click();
      await expect(actual).toHaveAttribute('aria-sort', 'ascending');

      const rule = page.locator('#assertsTable th[data-sort-col="id"]');
      await rule.click();
      await expect(rule).toHaveAttribute('aria-sort', 'ascending');
    });

  test('sorting by rule id is alphabetical', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="id"]');
    const ids = await visible(page);
    expect(ids).toEqual([...ids].sort((a, b) => a.localeCompare(b)));
  });

  test('metric sorts numerically, so p99.9 lands after p99', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="metric"]');
    const metrics = await page.locator('#assertsTable tbody tr.wir-rule-row td:nth-child(4)')
      .allInnerTexts();
    const trimmed = metrics.map(m => m.trim());
    expect(trimmed.indexOf('p99.9')).toBeGreaterThan(trimmed.lastIndexOf('p99'));
  });

  test('Actual sorts by breach margin, not by the raw number', async ({ page }) => {
    // The column mixes ms, %, /s and a unitless apdex — the raw numbers are not comparable.
    // slow-api-p95 is 1,910 ms against a 2 ms fail band, so it is ~955x past its own budget
    // and must outrank every rule whose number is larger but whose budget is larger still.
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="actual"]');
    const order = await visible(page);
    expect(order[0]).toBe('slow-api-p95');

    // run-throughput reads a much SMALLER raw number against a band it comfortably clears,
    // so by margin it must sit below the rule that is 955x past its own budget — which is the
    // whole point of sorting on margin rather than on the number in the cell.
    expect(order.indexOf('slow-api-p95'))
      .toBeLessThan(order.indexOf('run-throughput'));
  });

  test('the breach margin is a fraction of the rule\'s own threshold', async ({ page }) => {
    await openTab(page);
    const margin = await page.evaluate(() => {
      const api = (window as any).WIR.asserts;
      // slow-api-p95: fail > 2 ms, actual ~1,910 ms  ->  (1910 - 2) / 2, a very large number.
      return api._breachMargin({
        fail: { op: 'gt', value: 2 },
        results: [{ sampler: 'Slow API', actual: 1910, status: 'FAIL' }],
      });
    });
    expect(margin).toBeCloseTo(954, 0);
  });

  test('a zero threshold cannot produce an infinite margin', async ({ page }) => {
    // E25's run-throughput really is `fail < 0 /s`. Dividing by it would sort a comfortably
    // passing rule to the top of a descending breach-margin sort.
    await openTab(page);
    const margin = await page.evaluate(() => (window as any).WIR.asserts._breachMargin({
      fail: { op: 'lt', value: 0 },
      results: [{ sampler: 'Whole run', actual: 5.6, status: 'PASS' }],
    }));
    expect(margin).toBe(-Infinity);

    await page.click('#assertsTable th[data-sort-col="actual"]');
    expect((await visible(page))[0]).not.toBe('run-throughput');
  });

  test('rules with no comparable value sink to the bottom of a breach-margin sort',
    async ({ page }) => {
      await openTab(page);
      await page.click('#assertsTable th[data-sort-col="actual"]');
      const order = await visible(page);

      // Three rules have no margin to compute: nothing matched (renamed-sampler), nothing was
      // evaluated (parked-rule), and the metric could not be read (unknown-metric). All sit at
      // -Infinity, so a descending sort puts them last — in rule-id order, because that is how
      // ties break in both directions.
      //
      // Deliberately three and not four: a rule whose fail threshold is ZERO also lands at
      // -Infinity (x/0 is not a margin), which is why no rule in this fixture carries one.
      // run-throughput is `lt: 0.001` and run-apdex-ceiling is `gt: 2` for exactly that reason.
      expect(order.slice(-3).sort()).toEqual(
        ['parked-rule', 'renamed-sampler', 'unknown-metric']);
    });

  test('Matches sorts by how many samplers the rule judged', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="matches"]');
    const order = await visible(page);
    // The glob rule judges all three samplers; more than any single-sampler rule.
    expect(order[0]).toBe('api-error-rate');
  });

  test('ties break on rule id, so repeated clicks cannot shuffle equal rows', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="metric"]');
    const first = await visible(page);
    await page.click('#assertsTable th[data-sort-col="metric"]');   // desc
    await page.click('#assertsTable th[data-sort-col="metric"]');   // asc again
    expect(await visible(page)).toEqual(first);
  });

  test('Enter on a focused header sorts it and keeps focus there', async ({ page }) => {
    await openTab(page);
    const rule = page.locator('#assertsTable th[data-sort-col="id"]');
    await rule.focus();
    await page.keyboard.press('Enter');
    await expect(rule).toHaveAttribute('aria-sort', 'ascending');
    expect(await page.evaluate(
      () => document.activeElement?.getAttribute('data-sort-col'))).toBe('id');
  });

  test('the sort is remembered across a reload; the filters are not', async ({ page }) => {
    // A remembered sort is a preference. A remembered filter is a trap: "Showing 4 of 15"
    // next to a FAIL badge reads as a bug, not as a filter someone left on.
    await openTab(page);
    await page.click('#assertsTable th[data-sort-col="id"]');
    await page.fill('#assertsSearch', 'legacy');

    await loadDirty(page, 'E25');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsTable th[data-sort-col="id"]'))
      .toHaveAttribute('aria-sort', 'ascending');
    expect(await visible(page)).toHaveLength(ALL);
    expect(await page.inputValue('#assertsSearch')).toBe('');
  });
});

test.describe('Assertion Rules — status popover [E25]', () => {

  test('opens from the Status header without sorting it', async ({ page }) => {
    await openTab(page);
    const severity = page.locator('#assertsTable th[data-sort-col="severity"]');
    await expect(severity).toHaveAttribute('aria-sort', 'ascending');

    await page.click('#assertsStatusBtn');
    await expect(page.locator('#assertsStatusMenu')).toBeVisible();
    // The click must not have reached the header underneath it.
    await expect(severity).toHaveAttribute('aria-sort', 'ascending');
  });

  test('renders outside the table scroll container, so it is not clipped', async ({ page }) => {
    // .wir-table-wrap is overflow-x: auto, which promotes overflow-y to auto — a popover
    // nested inside it is cut off at the header row. This is the regression guard.
    await openTab(page);
    await page.click('#assertsStatusBtn');

    const insideWrap = await page.evaluate(
      () => !!document.querySelector('.wir-table-wrap #assertsStatusMenu'));
    expect(insideWrap).toBe(false);

    const box = await page.locator('#assertsStatusMenu').boundingBox();
    expect(box!.width).toBeGreaterThan(150);
    expect(box!.height).toBeGreaterThan(100);
  });

  test('multi-selects, and stays in sync with the toolbar chips', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsStatusBtn');
    await page.locator('#assertsStatusMenu input[type="checkbox"]').first().check();
    expect(await visible(page)).toEqual(['slow-api-p95']);

    const onChips = await page.locator('.wir-status-chip-on')
      .evaluateAll(els => els.map(e => e.getAttribute('data-status')));
    expect(onChips).toEqual(['FAIL']);
  });

  test('Breaching only selects FAIL, WARN and INVALID', async ({ page }) => {
    await openTab(page);
    await page.click('#assertsStatusBtn');
    await page.click('#assertsStatusMenu .wir-status-menu-foot .wir-link:nth-child(2)');

    const onChips = await page.locator('.wir-status-chip-on')
      .evaluateAll(els => els.map(e => e.getAttribute('data-status')));
    expect(onChips.sort()).toEqual(['FAIL', 'INVALID', 'WARN']);
    // Worst first, and INVALID now sorts BELOW the real breaches rather than above them: a
    // threshold that was actually exceeded is the more actionable row. It stays in this
    // selection though — "breaching only" means "what needs attention", and a rule the file
    // got wrong needs attention even though it no longer decides anything.
    expect(await visible(page)).toEqual(
      ['slow-api-p95', 'moderate-api-p95', 'unknown-metric']);
    await expect(page.locator('#assertsStatusMenu')).toHaveCount(0);
  });

  /**
   * The popover is `position: fixed`, so anything past the fold is unreachable, not just ugly.
   *
   * It opened downward unconditionally and clamped only the horizontal edge. With the toolbar
   * low in the viewport the two footer buttons sat below the fold, and because a fixed element
   * does not move with the page, scrolling could not bring them back — Playwright reported
   * "element is outside of the viewport" and retried until it timed out. A real user gets the
   * same popover with two buttons they cannot click.
   */
  test('the popover stays inside the viewport, flipping above the button if it must',
    async ({ page }) => {
      await openTab(page);
      // A short viewport with the toolbar near the bottom is the case that broke.
      await page.setViewportSize({ width: 1280, height: 620 });
      await page.click('#assertsStatusBtn');

      const box = await page.locator('#assertsStatusMenu').boundingBox();
      const h = await page.evaluate(() => window.innerHeight);
      expect(box!.y, 'the popover starts above the top edge').toBeGreaterThanOrEqual(0);
      expect(box!.y + box!.height, 'the popover runs past the fold').toBeLessThanOrEqual(h);

      // And the footer is genuinely clickable, which is the thing that actually failed.
      await page.click('#assertsStatusMenu .wir-status-menu-foot .wir-link:nth-child(2)');
      await expect(page.locator('#assertsStatusMenu')).toHaveCount(0);
    });

  test('All statuses clears the filter', async ({ page }) => {
    await openTab(page);
    await page.click('.wir-status-chip[data-status="FAIL"]');
    await page.click('#assertsStatusBtn');
    await page.click('#assertsStatusMenu .wir-status-menu-foot .wir-link:nth-child(1)');
    expect(await visible(page)).toHaveLength(ALL);
  });

  test('closes on an outside click and on Escape', async ({ page }) => {
    await openTab(page);

    await page.click('#assertsStatusBtn');
    await expect(page.locator('#assertsStatusMenu')).toBeVisible();
    await page.click('#assertsRulesCount');
    await expect(page.locator('#assertsStatusMenu')).toHaveCount(0);

    await page.click('#assertsStatusBtn');
    await expect(page.locator('#assertsStatusMenu')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('#assertsStatusMenu')).toHaveCount(0);
  });

  test('the button reports how many statuses are selected', async ({ page }) => {
    await openTab(page);
    await page.click('.wir-status-chip[data-status="FAIL"]');
    await page.click('.wir-status-chip[data-status="PASS"]');
    await expect(page.locator('#assertsStatusBtn')).toContainText('2');
  });
});

test.describe('Assertion Rules — filtering is view-only [E25]', () => {

  test('the gate strip, findings and header badge ignore the filter', async ({ page }) => {
    // The whole contract. A filtered table must never make the report say the run was
    // judged differently than it was.
    await openTab(page);
    const badge = await page.locator('.header-badge').first().innerText();

    await page.click('.wir-status-chip[data-status="PASS"]');
    expect(await visible(page)).toHaveLength(8);

    await switchTab(page, 'summary');
    await expect(page.locator('.wir-gate-strip .wir-chip-lg')).toHaveText('FAIL');
    await expect(page.locator('.wir-gate-breaches .wir-breach')).toHaveCount(3);

    await switchTab(page, 'asserts');
    // The unknown metric, the renamed sampler's empty target, and the legacy block's migration
    // WARNING, which reaches a real run since GAP-1 — the loader used to throw its id away. A filter
    // is no draft, so the notice stands (NIT-3).
    await expect(page.locator('#assertsFindings .wir-findings-title'))
      .toHaveText('3 configuration problems');
    await expect(page.locator('#assertsToolbar .wir-toolbar-counts'))
      .toContainText(`${ALL} declared`);
    expect(await page.locator('.header-badge').first().innerText()).toBe(badge);
  });

  test('the downloaded document still carries every rule, in file order', async ({ page }) => {
    await openTab(page);
    const before = await page.evaluate(
      () => JSON.parse((window as any).WIR.asserts._documentText()).assertions
        .map((a: any) => a.id));

    await page.click('.wir-status-chip[data-status="FAIL"]');
    await page.click('#assertsTable th[data-sort-col="id"]');

    const after = await page.evaluate(
      () => JSON.parse((window as any).WIR.asserts._documentText()).assertions
        .map((a: any) => a.id));
    expect(after).toEqual(before);
  });
});
