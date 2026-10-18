import { test, expect } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * The assert editor.
 *
 * Its reason to exist is the live preview: every matched sampler with its value and recomputed
 * status as you type. Converging on a threshold takes three runs when each adjustment is a
 * hand-edit against numbers read off a screen; this is what makes it one.
 *
 * Saving arrives with the draft model in M9 — the Save button is deliberately disabled here.
 */

async function openEditorFor(page: any, ruleId: string) {
  await loadClean(page, 'E25');
  await switchTab(page, 'asserts');
  await page.locator(`tr[data-rule-id="${ruleId}"] .wir-edit-btn`).click();
  await expect(page.locator('.wir-slideover')).toBeVisible();
}

test.describe('Assert editor', () => {

  test('opens on a rule, pre-filled, with no JS errors', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openEditorFor(page, 'slow-api-p95');
      await expect(page.locator('#editorMetric')).toHaveValue('p95');
      await expect(page.locator('#editorfailValue')).toHaveValue('2');
      await expect(page.locator('#editorSampler')).toHaveValue('Slow API');
    });
  });

  test('the scrim and the close button both dismiss it', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.locator('.wir-scrim').click();
    await expect(page.locator('.wir-slideover')).toHaveCount(0);

    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await page.locator('.wir-slideover-head .wir-link').click();
    await expect(page.locator('.wir-slideover')).toHaveCount(0);
  });

  test('the live verdict flips at the boundary as you type', async ({ page }) => {
    // The whole point. Raising the limit past the observed value must turn the preview from
    // FAIL to something else, and lowering it must bring it back — without a reload, and
    // against this run's real numbers.
    await openEditorFor(page, 'slow-api-p95');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('FAIL');

    await page.fill('#editorfailValue', '999999');
    await expect(page.locator('#editorPreviewVerdict')).not.toHaveText('FAIL');

    await page.fill('#editorfailValue', '1');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('FAIL');
  });

  test('the preview lists the matched samplers with their values', async ({ page }) => {
    await openEditorFor(page, 'api-error-rate');
    const rows = page.locator('.wir-preview-row');
    expect(await rows.count()).toBeGreaterThan(1);
    await expect(rows.first()).toContainText('API');
    // A verdict without the number is what the old badge gave; the preview must show both.
    await expect(page.locator('#editorPreview')).toContainText('Preview — unsaved');
  });

  test('a pattern shows what it matches while you type it', async ({ page }) => {
    // Kills the typo'd-target failure class before it can reach a file.
    await openEditorFor(page, 'api-error-rate');
    await expect(page.locator('#editorMatchPreview')).toContainText('Matches');

    await page.fill('#editorPattern', 'nothing-matches-this*');
    await expect(page.locator('#editorMatchPreview')).toContainText('Matches nothing in this run');

    await page.fill('#editorPattern', '*API');
    await expect(page.locator('#editorMatchPreview')).toContainText('Matches');
  });

  test('an unparseable regex is reported rather than silently matching nothing', async ({ page }) => {
    await openEditorFor(page, 'api-error-rate');
    await page.fill('#editorPattern', 'regex:[unclosed');
    await expect(page.locator('#editorMatchPreview')).toContainText('not a valid expression');
  });

  test('an edited rule whose sampler is not in this run is never silently retargeted',
      async ({ page }) => {
    // renamed-sampler targets "Fast APIs", which does not exist. Dropping it to whatever is
    // first in the list would quietly point the rule at a different sampler.
    await openEditorFor(page, 'renamed-sampler');
    await expect(page.locator('#editorSampler')).toHaveValue('Fast APIs');
    await expect(page.locator('#editorSampler option[selected]')).toContainText('not in this run');
  });

  test('the metric picker is grouped by family', async ({ page }) => {
    // `family` has been in the payload since the gate shipped, documented as "grouping for
    // the editor's metric picker", and the picker ignored it — sixteen metrics as one flat
    // list. Grouping is what the field is for; emitting it and not reading it was the bug.
    await openEditorFor(page, 'slow-api-p95');

    const groups = await page.locator('#editorMetric optgroup')
      .evaluateAll(els => els.map(e => e.getAttribute('label')));
    expect(groups).toContain('Latency');
    expect(groups.length).toBeGreaterThan(1);

    // Every option still lives in a group — a metric outside one would be unreachable-looking.
    const loose = await page.locator('#editorMetric > option').count();
    expect(loose, 'a metric sits outside every family group').toBe(0);
  });

  test('switching scope drops a metric the new scope does not have', async ({ page }) => {
    // stdDev is per-sampler only. Leaving it selected would put the form in a state the
    // engine rejects, which the tester would discover on the next run.
    await openEditorFor(page, 'slow-api-p95');
    await page.selectOption('#editorMetric', 'stdDev');
    await expect(page.locator('#editorMetric')).toHaveValue('stdDev');

    await page.click('.wir-seg[data-mode="overall"]');
    await expect(page.locator('#editorMetric')).not.toHaveValue('stdDev');
  });

  test('a whole-run rule evaluates against the aggregate', async ({ page }) => {
    await openEditorFor(page, 'run-throughput');
    await expect(page.locator('.wir-preview-row')).toHaveCount(1);
    await expect(page.locator('.wir-preview-row')).toContainText('Whole run');
  });

  test('a band can be removed, and removing warn reads as a choice', async ({ page }) => {
    await openEditorFor(page, 'moderate-api-p95');
    await expect(page.locator('#editorwarnValue')).toHaveCount(1);

    await page.locator('.wir-band-row').first().locator('.wir-link').click();
    await expect(page.locator('#editorwarnValue')).toHaveCount(0);
    await expect(page.locator('.wir-slideover-body'))
      .toContainText('goes straight from pass to fail');

    await page.click('#editorAddwarn');
    await expect(page.locator('#editorwarnValue')).toHaveCount(1);
  });

  test('a duplicate id is refused, and says why ids matter', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorId', 'fast-api-p95');
    await expect(page.locator('#editorProblem')).toContainText('already used');
    await expect(page.locator('#editorProblem')).toContainText('JUnit testcases');
  });

  test('keeping a rule\'s own id is not a duplicate', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorId', 'slow-api-p95');
    await expect(page.locator('#editorProblem')).toHaveCount(0);
  });

  test('a rule with no bands is refused, and says what a band is for', async ({ page }) => {
    // Both bands have to go: one band is a perfectly good rule, and "removing warn is a
    // choice, not an accident" is exactly the case that must stay valid.
    await openEditorFor(page, 'slow-api-p95');
    await page.locator('.wir-band-row .wir-link').first().click();
    await expect(page.locator('#editorProblem')).toHaveCount(0);

    await page.locator('.wir-band-row .wir-link').first().click();
    await expect(page.locator('.wir-band-row')).toHaveCount(0);
    await expect(page.locator('#editorProblem')).toContainText('can never decide anything');
  });

  test('a valid rule reports no problem', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await expect(page.locator('#editorProblem')).toHaveCount(0);
  });

  /**
   * A band field is a string, and the old parser read it with `+value`.
   *
   * `+' '` is 0 and `isNaN(0)` is false, so a threshold left as whitespace became ZERO —
   * silently, and an errorRate rule typed that way gates the build on "never a single error".
   * `+'Infinity'` also passes isNaN, and then JSON.stringify writes `{"gt": null}`, so the
   * saved file carries a band the next run cannot read at all.
   */
  test('whitespace is not a threshold of zero', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorfailValue', '   ');

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.fail, 'blank read as a real band').toBeUndefined();
    // slow-api-p95 still has its warn band, so one blank field is a valid one-band rule.
    // Saving must drop the fail band rather than write gt 0 into the file.
    await page.click('#editorSave');
    const doc = JSON.parse(await page.evaluate(
      () => (window as any).WIR.asserts._documentText()));
    const saved = doc.assertions.find((a: any) => a.id === 'slow-api-p95');
    expect(saved.fail, 'a blank field became a real threshold').toBeUndefined();
    expect(saved.warn).toEqual({ gt: 1 });
  });

  test('blanking every band is refused rather than saved as zeroes', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorwarnValue', '   ');
    await page.fill('#editorfailValue', '  ');

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.warn).toBeUndefined();
    expect(rule.fail).toBeUndefined();
    await expect(page.locator('#editorProblem')).toContainText('can never decide anything');
  });

  test('Infinity is not a number a band can hold', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorfailValue', 'Infinity');

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.fail, 'Infinity survived into the band').toBeUndefined();
    // And what a save would write must be readable JSON, not {"gt": null}.
    const written = await page.evaluate(() => (window as any).WIR.asserts._documentText());
    expect(written).not.toContain('"gt": null');
  });

  /**
   * Typed, not filled.
   *
   * Every other spec here uses fill(), which assigns the whole string in one operation and
   * never types a second character — so none of them could see that each keystroke ran
   * renderEditor(), replaced the slide-over's innerHTML, and destroyed the very input being
   * typed into. A threshold could only be entered one digit at a time, and the panel jumped
   * on every one.
   */
  test('a multi-digit threshold can be typed, one keystroke after another', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');

    const field = page.locator('#editorfailValue');
    await field.click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.type('2500', { delay: 30 });

    await expect(field, 'keystrokes after the first were lost').toHaveValue('2500');
    // The field must still be the focused element — losing it is how the digits went missing.
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('editorfailValue');

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.fail).toEqual({ op: 'gt', value: 2500 });
  });

  test('the live verdict still follows what is typed', async ({ page }) => {
    // The reason the editor re-rendered at all. Updating in place must not cost this.
    await openEditorFor(page, 'slow-api-p95');

    const field = page.locator('#editorfailValue');
    await field.click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.type('1', { delay: 30 });
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('FAIL');

    // WARN, not PASS: the rule keeps its warn band at gt 1, and Slow API's p95 is ~1936.
    // What matters is that the verdict tracked the typing across two distinct states.
    await page.keyboard.type('00000', { delay: 30 });
    await expect(field).toHaveValue('100000');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('WARN');
  });

  /**
   * The panel element itself must survive every change.
   *
   * `.wir-slideover` carries `animation: wirSlideIn 0.18s ease-out`. renderEditor() used to
   * replace it on every change, so the browser saw a new element and replayed that animation:
   * the panel flew in again on every keystroke, tab switch, dropdown pick and checkbox
   * toggle. Identity is the assertion — if the node is the same node, the animation cannot
   * have restarted.
   */
  test('the panel is never re-created, whatever you change', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');

    await page.evaluate(() => {
      (window as any).__panel = document.querySelector('.wir-slideover');
    });
    const survived = () => page.evaluate(
      () => (window as any).__panel === document.querySelector('.wir-slideover'));

    // Every kind of change the review named, in one pass.
    await page.click('.wir-seg[data-mode="samplers"]');          // target mode tab
    expect(await survived(), 'switching target mode re-created the panel').toBe(true);

    await page.click('.wir-seg[data-mode="sampler"]');
    await page.selectOption('#editorMetric', 'p99');             // metric
    expect(await survived(), 'changing the metric re-created the panel').toBe(true);

    await page.selectOption('#editorfailOp', 'gte');             // the "if" symbol
    expect(await survived(), 'changing the operator re-created the panel').toBe(true);

    await page.locator('#editorEnabled').click();                // checkbox
    expect(await survived(), 'toggling enabled re-created the panel').toBe(true);

    await page.fill('#editorId', 'renamed-rule');                // a text field
    expect(await survived(), 'typing re-created the panel').toBe(true);

    // And the panel is still a working editor after all of that.
    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.id).toBe('renamed-rule');
    expect(rule.metric).toBe('p99');
  });

  test('changing the operator keeps focus and does not scroll the panel', async ({ page }) => {
    // Picking an operator re-renders the form — the direction decides which hints show, and
    // the preview re-evaluates. That must not move the reader: the panel used to scroll back
    // to the top and drop focus on every dropdown change.
    await openEditorFor(page, 'slow-api-p95');

    // Driven the way the browser drives it when a user picks an option — focused, value set,
    // change fired. Playwright's click() and selectOption() both scroll the control into view
    // as part of actionability, which would reset the very scrollTop this asserts on.
    const place = await page.evaluate(() => {
      const body = document.querySelector('.wir-slideover-body') as HTMLElement;
      const el = document.getElementById('editorfailOp') as HTMLSelectElement;
      el.focus();
      body.scrollTop = 120;
      const before = body.scrollTop;

      el.value = 'gte';
      el.dispatchEvent(new Event('change', { bubbles: true }));

      const after = document.querySelector('.wir-slideover-body') as HTMLElement;
      return { before: before, after: after.scrollTop, focused: document.activeElement?.id };
    });

    test.skip(place.before === 0, 'panel is not scrollable at this viewport');
    expect(place.focused, 'the operator lost focus on re-render').toBe('editorfailOp');
    expect(place.after, 'the panel scrolled on re-render').toBe(place.before);

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.fail.op).toBe('gte');
  });

  test('typing a pattern keeps the caret and still previews the matches', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.click('.wir-seg[data-mode="match"]');

    const field = page.locator('#editorPattern');
    await field.click();
    await page.keyboard.type('*API', { delay: 30 });

    await expect(field).toHaveValue('*API');
    expect(await page.evaluate(() => document.activeElement?.id)).toBe('editorPattern');
    await expect(page.locator('#editorMatchPreview')).toContainText('Matches');
  });

  test('a real number still parses, padded or not', async ({ page }) => {
    await openEditorFor(page, 'slow-api-p95');
    await page.fill('#editorfailValue', '  2500  ');

    const rule = await page.evaluate(() => (window as any).WIR.asserts._editorRule());
    expect(rule.fail).toEqual({ op: 'gt', value: 2500 });
  });
});

test.describe('New assert', () => {

  test('the toolbar opens an empty editor with a derived id', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#addAssertBtn');

    await expect(page.locator('.wir-slideover')).toBeVisible();
    await expect(page.locator('.wir-slideover-head')).toContainText('New assert');
    // The placeholder shows what the engine would derive, so the auto id is never a surprise.
    await expect(page.locator('#editorId')).toHaveAttribute('placeholder', /-p95 \(auto\)/);
  });

  test('a new rule previews against the run before it is saved', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#addAssertBtn');

    await page.fill('#editorfailValue', '1');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('FAIL');

    await page.fill('#editorfailValue', '999999');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('PASS');
  });

  test('saving is not offered yet', async ({ page }) => {
    // Honest about scope: the draft model and write-back land in the next milestone, and a
    // Save button that silently did nothing would be worse than one that says it cannot.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.click('#addAssertBtn');
    await expect(page.locator('#editorSave')).toBeDisabled();
  });
});
