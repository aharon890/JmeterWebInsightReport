import { test, expect } from '@playwright/test';
import { loadClean, loadDirty, switchTab, expectNoJsErrors } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Drafts and write-back.
 *
 * The report is a static file: it cannot write to disk, so "save" means download or clipboard,
 * and until the tester does one of those nothing has changed for the next run. Two verdicts
 * therefore exist at once and must never be confused — what this run WAS gated on, and what it
 * WOULD be if the drafts were saved.
 */

async function openTab(page: any) {
  await loadClean(page, 'E25');
  await switchTab(page, 'asserts');
}

async function editFailValue(page: any, ruleId: string, value: string) {
  await page.locator(`tr[data-rule-id="${ruleId}"] .wir-edit-btn`).click();
  await page.fill('#editorfailValue', value);
  await page.click('#editorSave');
  await expect(page.locator('.wir-slideover')).toHaveCount(0);
}

test.describe('Drafts', () => {

  test('an edit becomes a draft, marked, counted, with no JS errors', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openTab(page);
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);

      await editFailValue(page, 'slow-api-p95', '999999');

      await expect(page.locator('#assertsUnsavedPill')).toContainText('1 unsaved change');
      await expect(page.locator('tr[data-rule-id="slow-api-p95"] .wir-tag-draft'))
        .toHaveText('EDITED');
    });
  });

  /**
   * A Notes edit is a draft, which it was not.
   *
   * <p>The draft model was `{rules, deletions}` and the notes textarea was read live off the
   * DOM inside buildDocument. So typing in the Notes tab produced a Download that differed from
   * the file with NOTHING on screen saying so — no pill, no banner, nothing to discard, and it
   * did not survive a reload either. Counting the candidate document against the loaded one
   * covers it, and covers every block added later without a second mechanism per block.
   */
  test('typing in the Notes tab is an unsaved change like any other', async ({ page }) => {
    await openTab(page);
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);

    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'A note nobody asked for.');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsUnsavedPill')).toContainText('1 unsaved change');
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc.testNotes).toBe('A note nobody asked for.');
  });

  test('discarding puts the notes back too', async ({ page }) => {
    await openTab(page);
    await switchTab(page, 'notes');
    const original = await page.inputValue('#notesTextarea');
    await page.fill('#notesTextarea', 'temporary');
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);

    await page.click('#discardDraftsBtn');

    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
    await switchTab(page, 'notes');
    expect(await page.inputValue('#notesTextarea')).toBe(original);
  });

  test('a deletion counts even though there is no row left to mark', async ({ page }) => {
    // `draftState` marks a rule the session touched; a deleted rule leaves nothing to mark,
    // which is why a separate deletions counter existed. The diff sees the absence.
    await openTab(page);
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.deleteRule(A._keyOf('parked-rule')); });

    await expect(page.locator('#assertsUnsavedPill')).toContainText('1 unsaved change');
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.some((a: any) => a.id === 'parked-rule')).toBe(false);
  });

  test('an edit that lands back on the original value is not a change', async ({ page }) => {
    // The diff is against the document, not against "was anything typed". Editing a threshold
    // and typing the old value back leaves a file identical to the one loaded, so there is
    // nothing to save and the pill must say so — a marker-based count could not.
    await openTab(page);
    const before = await page.evaluate(() => (window as any).WIR.asserts._documentText());

    await editFailValue(page, 'slow-api-p95', '999999');
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);
    await editFailValue(page, 'slow-api-p95', '2');

    expect(await page.evaluate(() => (window as any).WIR.asserts._documentText())).toBe(before);
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
  });

  test('a note on a report that declared nothing is still a draft, and does not throw',
    async ({ page }) => {
      // The first draft that can exist without a gate. Both state.gate and state.draftGate are
      // null on a report with no annotations, and the draft banner read `would.status` off
      // whichever it found — which was nothing. Unreachable until a Notes edit started
      // triggering a render, and then reachable by typing one character.
      const errors: string[] = [];
      page.on('pageerror', e => errors.push(e.message));

      await loadClean(page, 'E02');
      await switchTab(page, 'notes');
      await page.fill('#notesTextarea', 'first note on a gateless run');
      await switchTab(page, 'asserts');

      await expect(page.locator('#assertsUnsavedPill')).toContainText('1 unsaved change');
      await expect(page.locator('#assertsDraftBanner')).toContainText('NOT EVALUATED');
      expect(errors).toEqual([]);
    });

  test('the gate strip keeps the verdict this run was actually gated on', async ({ page }) => {
    // The point of the draft model. Moving the header because someone typed in a form would
    // make the report lie about what happened.
    await openTab(page);
    const gated = await page.locator('.wir-gate-strip .wir-chip').first().innerText();

    await editFailValue(page, 'slow-api-p95', '999999');
    // Above moderate-api-p95's warn band of 1 — dropping fail below warn would make a dead warn
    // band and put a note in the form, and this test is about the header not the form.
    await editFailValue(page, 'moderate-api-p95', '100');

    await expect(page.locator('.wir-gate-strip .wir-chip').first()).toHaveText(gated);
    await expect(page.locator('#assertsDraftBanner'))
      .toContainText('The header badge stays on the verdict this run was actually gated on');
  });

  test('the banner says what the verdict would be if saved', async ({ page }) => {
    await openTab(page);
    await editFailValue(page, 'slow-api-p95', '999999');
    // "Client": the chip is the client gate, which a server edit cannot move (R3-6).
    await expect(page.locator('#assertsDraftBanner'))
      .toContainText('Client verdict if saved and re-run');
    await expect(page.locator('#assertsWouldBe')).toBeVisible();
  });

  test('a new rule is marked NEW rather than EDITED', async ({ page }) => {
    // A rule added in this session does not exist in the file, so calling it "edited" would
    // misdescribe what a save is about to do.
    await openTab(page);
    await page.click('#addAssertBtn');
    await page.fill('#editorId', 'brand-new-rule');
    await page.fill('#editorfailValue', '999999');
    await page.click('#editorSave');

    await expect(page.locator('tr[data-rule-id="brand-new-rule"] .wir-tag-draft'))
      .toHaveText('NEW');
  });

  test('a rule can be duplicated, disabled and deleted', async ({ page }) => {
    await openTab(page);
    const before = await page.locator('#assertsTable tbody tr.wir-rule-row').count();

    await page.locator('tr[data-rule-id="fast-api-p95"] .wir-dup-btn').click();
    await expect(page.locator('tr[data-rule-id="fast-api-p95-copy"]')).toHaveCount(1);

    await page.locator('tr[data-rule-id="fast-api-p95-copy"] .wir-toggle-btn').click();
    await expect(page.locator('tr[data-rule-id="fast-api-p95-copy"]'))
      .toHaveClass(/wir-row-disabled/);

    await page.locator('tr[data-rule-id="fast-api-p95-copy"] .wir-del-btn').click();
    await expect(page.locator('tr[data-rule-id="fast-api-p95-copy"]')).toHaveCount(0);
    await expect(page.locator('#assertsTable tbody tr.wir-rule-row')).toHaveCount(before);
  });

  test('drafts survive a reload', async ({ page }) => {
    // On file:// there is no server-side copy of what you typed. An accidental refresh
    // costing a tuning session is exactly the kind of loss people do not forgive.
    await openTab(page);
    await editFailValue(page, 'slow-api-p95', '424242');

    await loadDirty(page, 'E25');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsUnsavedPill')).toContainText('1 unsaved change');
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 424242 });
  });

  test('discard returns to what the file said', async ({ page }) => {
    await openTab(page);
    await editFailValue(page, 'slow-api-p95', '999999');
    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);

    await page.click('#discardDraftsBtn');

    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
    await expect(page.locator('.wir-tag-draft')).toHaveCount(0);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 2 });
  });

  test('drafts belong to the run they were written against', async ({ page }) => {
    // A draft keyed loosely would follow the tester into a different report and quietly
    // rewrite rules against numbers they never saw.
    await openTab(page);
    await editFailValue(page, 'slow-api-p95', '999999');

    await loadDirty(page, 'E10');
    const leaked = await page.evaluate(() => (window as any).WIR.asserts._draftCount());
    expect(leaked).toBe(0);
  });
});

test.describe('Write-back', () => {

  test('round-trip: open and save with no edits yields an equivalent file', async ({ page }) => {
    // Guarantee (1) of the design. If this ever fails, the editor is a lossy converter.
    await openTab(page);

    const { written, loaded } = await page.evaluate(() => ({
      written: (window as any).WIR.asserts._documentText(),
      loaded: (window as any).globalChartData.annotationsDocument,
    }));
    const doc = JSON.parse(written);

    for (const key of Object.keys(loaded)) {
      expect(doc, `key "${key}" was dropped by a no-op save`).toHaveProperty(key);
    }
    expect(doc.assertions.length).toBe(loaded.assertions.length);
    expect(doc.samplerNotes).toEqual(loaded.samplerNotes);
    expect(doc.timelineMarkers).toEqual(loaded.timelineMarkers);
  });

  test('a key this version does not understand survives verbatim', async ({ page }) => {
    // E25's annotations carry a "_comment" array. Dropping what it did not understand is how
    // an editor teaches people not to trust it.
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc._comment).toBeDefined();
    expect(Array.isArray(doc._comment)).toBe(true);
  });

  test('$schema is inserted when the file had none, and kept when it had one', async ({ page }) => {
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    expect(doc.$schema).toContain('report-annotations.schema.json');
    expect(Object.keys(doc)[0]).toBe('$schema');
  });

  test('an edit reaches the saved file in the schema\'s shape', async ({ page }) => {
    await openTab(page);
    await editFailValue(page, 'slow-api-p95', '1234');

    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    const rule = doc.assertions.find((a: any) => a.id === 'slow-api-p95');
    expect(rule.fail).toEqual({ gt: 1234 });
    expect(rule.target).toEqual({ sampler: 'Slow API' });
    expect(rule.metric).toBe('p95');
  });

  test('defaults are omitted rather than written out', async ({ page }) => {
    // enabled:true is the default. Writing it makes every rule noisier to read and every diff
    // larger than the change. onMissing is not written because it no longer exists: an
    // unmatched rule judges nothing, and that is not a per-rule policy any more.
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    const rule = doc.assertions.find((a: any) => a.id === 'fast-api-p95');
    expect(rule.enabled).toBeUndefined();
    expect(rule.onMissing, 'onMissing is deleted, not defaulted').toBeUndefined();
  });

  /**
   * A key we do not understand survives the round trip, because that is the deal.
   *
   * <p>The policy is that a key outside our shape is not graded — no warning, no INVALID, just
   * ignored. That is only defensible while it is also PRESERVED. ruleToJson rebuilt each rule
   * from the seven fields it knows, so a save silently rewrote the tester's file without the
   * ones it did not: "we do not grade your file" would have meant "we quietly edit it".
   *
   * <p>E25's smoke-only-rule carries `owner` and `_ticket` for exactly this.
   */
  test('unknown per-rule keys survive a save', async ({ page }) => {
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    const rule = doc.assertions.find((a: any) => a.id === 'smoke-only-rule');

    expect(rule.owner, 'an unknown key was dropped on save').toBe('perf-team');
    expect(rule._ticket).toBe('PERF-1042');
    // And the keys we do model are still there and still first.
    expect(Object.keys(rule).slice(0, 2)).toEqual(['id', 'description']);
    expect(rule.fail).toEqual({ gt: 999999 });
  });

  test('an unknown key survives being edited around', async ({ page }) => {
    // The load-bearing half: carrying keys through an untouched rule is easy. Carrying them
    // through a rule the tester just edited is what stops the editor eating them.
    await openTab(page);
    await editFailValue(page, 'smoke-only-rule', '123456');

    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
    const rule = doc.assertions.find((a: any) => a.id === 'smoke-only-rule');
    expect(rule.fail).toEqual({ gt: 123456 });
    expect(rule.owner, 'editing a rule ate a key the editor never showed').toBe('perf-team');
  });

  test.describe('Document editor', () => {

    /**
     * The remaining nine blocks become CRUD-able.
     *
     * <p>Before this, `assertions[]` was the only block the report could create, update or
     * delete. samplerNotes, timelineMarkers, comparisonThresholds, version and anything a
     * newer plugin wrote were readable and nothing else — so "manage the asserts JSON from the
     * GUI" was true of one key out of ten.
     */
    test('opens on the document the report would write', async ({ page }) => {
      await openTab(page);
      await page.click('#editDocumentBtn');

      const text = await page.inputValue('#docEditorText');
      const doc = JSON.parse(text);
      expect(doc.assertions.length).toBeGreaterThan(0);
      expect(doc.testNotes, 'every block, not just the rules').toBeDefined();
      expect(doc.samplerNotes).toBeDefined();
    });

    test('a block the rules table does not own can be edited and survives a save',
      async ({ page }) => {
        await openTab(page);
        await page.click('#editDocumentBtn');

        const doc = JSON.parse(await page.inputValue('#docEditorText'));
        doc.samplerNotes = { 'Fast API': 'edited from the report' };
        doc.timelineMarkers = [{ timestamp: 1700000009000, label: 'added here', type: 'info' }];
        await page.fill('#docEditorText', JSON.stringify(doc, null, 2));
        await page.click('#docEditorApply');

        const saved = JSON.parse(
          await page.evaluate(() => (window as any).WIR.asserts._documentText()));
        expect(saved.samplerNotes['Fast API']).toBe('edited from the report');
        expect(saved.timelineMarkers[0].label).toBe('added here');
        await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);
      });

    test('editing assertions here re-evaluates them, like any other edit', async ({ page }) => {
      // ruleFromJson earns its keep: the payload carries rules Java already resolved, and text
      // typed into a JSON box has none of that. Without the conversion every rule would come
      // back unusable.
      await openTab(page);
      await page.click('#editDocumentBtn');

      const doc = JSON.parse(await page.inputValue('#docEditorText'));
      const rule = doc.assertions.find((a: any) => a.id === 'slow-api-p95');
      rule.fail = { gt: 999999 };
      await page.fill('#docEditorText', JSON.stringify(doc, null, 2));
      await page.click('#docEditorApply');

      const status = await page.evaluate(() => {
        const rules = (window as any).WIR.asserts._visible();
        return rules.indexOf('slow-api-p95');
      });
      expect(status, 'the rule must still be in the table').toBeGreaterThanOrEqual(0);
      // WARN, not PASS: slow-api-p95 carries `warn: {gt: 1}` beside its fail band, and only the
      // fail band was raised. That is the stronger assertion — it proves BOTH bands came back
      // through ruleFromJson and re-evaluated, rather than the rule merely ceasing to fail.
      await expect(page.locator('tr[data-rule-id="slow-api-p95"] td').first())
        .toContainText('WARN');
    });

    test('invalid JSON is refused, and says why', async ({ page }) => {
      await openTab(page);
      await page.click('#editDocumentBtn');
      await page.fill('#docEditorText', '{ "assertions": [ oops');

      await expect(page.locator('#docEditorProblem')).toContainText('Not valid JSON');
      await expect(page.locator('#docEditorApply')).toBeDisabled();
    });

    test('a wrongly-shaped assertions block is refused before it reaches the evaluator',
      async ({ page }) => {
        await openTab(page);
        await page.click('#editDocumentBtn');
        await page.fill('#docEditorText', '{ "assertions": { "not": "an array" } }');

        await expect(page.locator('#docEditorProblem')).toContainText('must be an array');
        await expect(page.locator('#docEditorApply')).toBeDisabled();
      });

    test('Discard puts the other blocks back too', async ({ page }) => {
      await openTab(page);
      await page.click('#editDocumentBtn');
      const doc = JSON.parse(await page.inputValue('#docEditorText'));
      doc.samplerNotes = { 'Fast API': 'temporary' };
      await page.fill('#docEditorText', JSON.stringify(doc, null, 2));
      await page.click('#docEditorApply');
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);

      await page.click('#discardDraftsBtn');

      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
      const saved = JSON.parse(
        await page.evaluate(() => (window as any).WIR.asserts._documentText()));
      expect(saved.samplerNotes['Fast API']).not.toBe('temporary');
    });

    test('the header badge keeps the verdict this run was gated on', async ({ page }) => {
      // Applying a document is a proposal about a future run, not a re-judgement of this one.
      await openTab(page);
      const badge = await page.locator('.header-badge .status-badge').first().innerText();

      await page.click('#editDocumentBtn');
      const doc = JSON.parse(await page.inputValue('#docEditorText'));
      doc.assertions = [];
      await page.fill('#docEditorText', JSON.stringify(doc, null, 2));
      await page.click('#docEditorApply');

      expect(await page.locator('.header-badge .status-badge').first().innerText()).toBe(badge);
    });
  });

  test.describe('Open a file', () => {

    /* A real <input type="file">, driven the way Playwright drives one. */
    async function drop(page: any, name: string, doc: any) {
      await page.setInputFiles('#assertsFileInput', {
        name, mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(doc, null, 2)),
      });
    }

    /**
     * Rule 5's other half: open a JSON and see what it would decide about THIS run.
     *
     * <p>There was no import path of any kind — no picker, no drop target, no paste — so the
     * only way to find out what a file decided was to run JMeter again with it.
     */
    test('a loaded file is evaluated against the run already on screen', async ({ page }) => {
      await openTab(page);
      await drop(page, 'from-disk.json', {
        assertions: [{
          id: 'imported', target: { sampler: 'Fast API' },
          metric: 'p95', fail: { gt: 0 },
        }],
      });

      // One rule, judged against this run's numbers — not carried over as text.
      await expect(page.locator('#assertsTable tbody tr.wir-rule-row')).toHaveCount(1);
      await expect(page.locator('tr[data-rule-id="imported"] td').first()).toContainText('FAIL');
      await expect(page.locator('#assertsSource')).toContainText('from-disk.json');
    });

    test('the header badge still reports what this run was gated on', async ({ page }) => {
      // The reason import feeds the draft path. A file opened after the fact is a question;
      // letting it repaint the verdict would make the report lie about its own run.
      await openTab(page);
      const badge = await page.locator('.header-badge .status-badge').first().innerText();

      await drop(page, 'green.json', {
        assertions: [{ id: 'always-ok', target: { scope: 'overall' },
                       metric: 'throughput', fail: { lt: 0.0001 } }],
      });

      expect(await page.locator('.header-badge .status-badge').first().innerText()).toBe(badge);
      await expect(page.locator('#assertsSource')).toBeVisible();
    });

    test('a file that would destroy drafts is held back, not applied', async ({ page }) => {
      // The three possible behaviours are: silently lose the drafts, silently refuse the file,
      // or ask. Only the third is defensible, and it has to name what is at stake.
      await openTab(page);
      await editFailValue(page, 'slow-api-p95', '424242');
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);

      await drop(page, 'other.json', { assertions: [] });

      await expect(page.locator('#assertsFileConflict')).toContainText('1 unsaved change');
      await expect(page.locator('#assertsFileConflict')).toContainText('other.json');
      // And nothing happened yet: the edited rule is still there.
      const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
      expect(doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 424242 });
    });

    test('Keep my drafts dismisses the file and changes nothing', async ({ page }) => {
      await openTab(page);
      await editFailValue(page, 'slow-api-p95', '424242');
      await drop(page, 'other.json', { assertions: [] });

      await page.click('#fileConflictKeep');

      await expect(page.locator('#assertsFileConflict')).toHaveCount(0);
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(1);
      const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
      expect(doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 424242 });
    });

    test('Discard my drafts loads the file, because that is what was asked for',
      async ({ page }) => {
        await openTab(page);
        await editFailValue(page, 'slow-api-p95', '424242');
        await drop(page, 'other.json', {
          assertions: [{ id: 'only-me', target: { sampler: 'Fast API' },
                         metric: 'p95', fail: { gt: 999999 } }],
        });

        await page.click('#fileConflictDiscard');

        await expect(page.locator('#assertsFileConflict')).toHaveCount(0);
        const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));
        expect(doc.assertions).toHaveLength(1);
        expect(doc.assertions[0].id).toBe('only-me');
      });

    /**
     * F1, client half (exploratory, 2026-10-02). Opening E25's own annotations file — the one in
     * test-plans/annotations/ it is generated from, unedited — read "2 unsaved changes". Java's
     * re-serialised annotationsDocument carries `"samplers": null` and `"enabled": null` in each
     * rule and null description/color in timelineMarkers; the file has none of those keys, and the
     * count read the difference in spelling as two edits. A pill on a file nobody touched sends the
     * reader looking for a change that does not exist, and offers to "discard" it.
     */
    test('[R] F1: opening the report\'s own annotations file is not an unsaved change', async ({ page }) => {
      await openTab(page);
      const own = path.resolve(__dirname, '..', '..', 'annotations', 'asserts.json');
      await page.setInputFiles('#assertsFileInput', own);
      await expect(page.locator('#assertsSource')).toContainText('asserts.json');

      await expect(page.locator('#assertsUnsavedPill'), 'the report\'s own file read as unsaved changes')
        .toHaveCount(0);
      await expect(page.locator('#assertsDraftBanner')).toHaveCount(0);
      await expect(page.locator('#assertsTable .wir-tag-draft')).toHaveCount(0);
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    });

    test('[U] a file with a legacy loadValidity block keeps its load check through a Download', async ({ page }) => {
      // E25's own file declares its load check as the legacy loadValidity block, which the run
      // migrates into assertions[] (LoadValidityMigration). The page read assertions[] and nothing
      // else, and never writes loadValidity back — so opening that file and downloading it wrote a
      // document with no load check in it at all. The migration is the run's, rule for rule (the
      // parity vectors' migrationCases); this is that the Download carries it.
      await openTab(page);
      const own = path.resolve(__dirname, '..', '..', 'annotations', 'asserts.json');
      expect(JSON.parse(fs.readFileSync(own, 'utf-8')).loadValidity, 'the file still declares the legacy block')
        .toBeTruthy();
      await page.setInputFiles('#assertsFileInput', own);
      await expect(page.locator('#assertsSource')).toContainText('asserts.json');

      const written = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
      expect(written.loadValidity, 'the block is consumed, as the run consumes it').toBeUndefined();
      const load = (written.assertions as any[]).filter((r) => r.id === 'load:throughput');
      expect(load, 'the Download lost the load check').toHaveLength(1);
      expect(load[0].target).toEqual({ scope: 'overall' });
      expect(load[0].warn).toEqual({ lt: 0.0015 });
      expect(load[0].fail).toEqual({ lt: 0.001 });
      await expect(page.locator('#assertsTable')).toContainText('load:throughput');
    });

    test('a file that is not a usable document is refused by name', async ({ page }) => {
      await openTab(page);
      await page.setInputFiles('#assertsFileInput', {
        name: 'broken.json', mimeType: 'application/json',
        buffer: Buffer.from('{ "assertions": [ oops'),
      });

      // Nothing loaded, nothing lost, and no provenance strip claiming otherwise.
      await expect(page.locator('#assertsSource')).toHaveCount(0);
      await expect(page.locator('#assertsTable tbody tr.wir-rule-row').first()).toBeVisible();
    });
  });

  /**
   * A label the loader invented must not become an id in the tester's file.
   *
   * <p>A rule that declares no id gets the positional label `assertions[3]`, so findings, JUnit
   * testcases and table rows have something to name it by. Writing that back turns a label into
   * a permanent id — and a POSITIONAL one, so inserting a rule above it makes the name a lie.
   * It also changes what the file says on a save the tester made for an unrelated reason.
   *
   * <p>E25 carries an id-less rule for this.
   */
  test('no rule is written back under a machine-generated id', async ({ page }) => {
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.asserts._documentText()));

    expect(doc.assertions.some((a: any) => a.id && a.id.indexOf('legacy:') === 0)).toBe(false);
    expect(doc.assertions.some((a: any) => a.id && /^assertions\[\d+\]$/.test(a.id)),
      'a positional label was written back as a real id').toBe(false);
    expect(doc.assertions.some((a: any) => a.id === 'moderate-api-p90'),
      'a DERIVED name was written back as a real id').toBe(false);

    // And the rule is still there, with everything it did declare.
    const idless = doc.assertions.find((a: any) => a.id === undefined);
    expect(idless, 'the id-less rule vanished from the file').toBeDefined();
    expect(idless.metric).toBe('p90');
    expect(idless.fail).toEqual({ gt: 999999 });
  });

  test('an id-less rule is still named everywhere a name is needed', async ({ page }) => {
    // Not writing the name back must not mean having no name. The table row, the findings and a
    // JUnit testcase all need something to point at.
    //
    // The name is `slug(target)-metric` — AssertionRule's documented rule for an omitted id, and
    // stable, unlike the positional `assertions[N]` the mapper falls back to for a rule it could
    // not build at all. Both are the plugin's names, and neither is written to the file.
    await openTab(page);
    const ids = await page.evaluate(() => (window as any).WIR.asserts._visible());
    expect(ids, 'the id-less rule must still be labelled in the table')
      .toContain('moderate-api-p90');
  });
});

/**
 * C-1, the client half: an unreadable band is never saved away.
 *
 * A band Java could not read arrives absent from the payload, its sentence in
 * declarationProblems, and ruleToJson wrote bands from the model only — so a Download with nothing
 * edited turned "one readable band beside one unreadable one", an INVALID_RULE and exit 1, into a
 * rule judged on warn alone that can never FAIL: the next run read WARN and exited 0. The editor
 * did the same on any Save, because an empty Fail box means "no band". The server tab fixed this
 * as C-1 (server-drafts.spec.ts); the pure half of this one is in
 * test-plans/js-unit/asserts-write-back.test.js, which also covers the load path — no fixture
 * declares an unreadable band, so these drive E25 through Open file… with a crafted document.
 */
test.describe('An unreadable band (C-1) [E25]', () => {

  async function openWith(page: any, name: string, text: string) {
    await page.setInputFiles('#assertsFileInput', {
      name, mimeType: 'application/json', buffer: Buffer.from(text),
    });
    await expect(page.locator('#assertsSource')).toContainText(name);
  }

  async function written(page: any, id: string) {
    return JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()))
      .assertions.find((a: any) => a.id === id);
  }

  test('[U] survives a save, and the editor refuses to save it away unseen', async ({ page }) => {
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
    doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail = { gtt: 900 };
    await openWith(page, 'unreadable.json', JSON.stringify(doc, null, 2));
    await expect(page.locator('tr[data-rule-id="slow-api-p95"] td').first()).toContainText('INVALID');

    const noOp = await written(page, 'slow-api-p95');
    expect(noOp.warn).toEqual({ gt: 1 });
    expect(noOp.fail, 'a save dropped the unreadable fail band').toEqual({ gtt: 900 });

    // The form shows an empty Fail field, and "empty" means "no band" on Save. Saving an unrelated
    // change must not take the band with it.
    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await page.fill('#editorDescription', 'touched something else');
    await expect(page.locator('#editorProblem'))
      .toContainText("Fail operator 'gtt' is not one of gt, gte, lt, lte");
    await expect(page.locator('#editorSave')).toBeDisabled();

    // Nor does an operator picked over the empty box, or a number typed and taken back: the box is
    // empty either way, and an empty box over a carried band is the file's band.
    await page.selectOption('#editorfailOp', 'lt');
    await expect(page.locator('#editorProblem'))
      .toContainText("Fail operator 'gtt' is not one of gt, gte, lt, lte");
    await expect(page.locator('#editorSave')).toBeDisabled();
    await page.fill('#editorfailValue', '7');
    await expect(page.locator('#editorSave')).toBeEnabled();
    await page.fill('#editorfailValue', '');
    await expect(page.locator('#editorSave')).toBeDisabled();
    await page.evaluate(() => (window as any).WIR.asserts.saveEditor());
    expect((await written(page, 'slow-api-p95')).fail, 'a refused Save wrote').toEqual({ gtt: 900 });

    // Writing the band replaces it, under the operator picked above.
    await page.fill('#editorfailValue', '700');
    await expect(page.locator('#editorSave')).toBeEnabled();
    await page.click('#editorSave');
    await expect(page.locator('.wir-slideover')).toHaveCount(0);
    const saved = await written(page, 'slow-api-p95');
    expect(saved.fail).toEqual({ lt: 700 });
    expect(saved.description).toBe('touched something else');
  });

  test('[U] a carried warn band shows as a band row, and its × is how it goes', async ({ page }) => {
    // Shown as "+ warn band" it would have no × — the one control that releases a carried band —
    // and the refusal would point at a band the form says does not exist.
    await openTab(page);
    const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
    doc.assertions.find((a: any) => a.id === 'slow-api-p95').warn = { gtt: 1 };
    await openWith(page, 'unreadable-warn.json', JSON.stringify(doc, null, 2));

    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
    await expect(page.locator('#editorwarnValue')).toHaveValue('');
    await expect(page.locator('#editorAddwarn')).toHaveCount(0);
    await expect(page.locator('#editorProblem'))
      .toContainText("Warn operator 'gtt' is not one of gt, gte, lt, lte");
    await expect(page.locator('#editorSave')).toBeDisabled();

    // RB10: the × is named in words, the Server editor's for a band the file wrote, and is a 24×24
    // target (WCAG 2.2 2.5.8); it was named "×" and measured about 10×19. The fail band was read, and
    // may as well have been typed here, so it is only "the fail band".
    const remove = page.locator('.wir-band-row', { has: page.locator('#editorwarnValue') })
      .locator('button[title="Remove this band"]');
    await expect(remove).toHaveAttribute('id', 'editorwarnRemove');
    await expect(remove).toHaveAccessibleName('Remove the warn band the file wrote');
    await expect(page.locator('#editorfailRemove')).toHaveAccessibleName('Remove the fail band');
    for (const id of ['#editorwarnRemove', '#editorfailRemove']) {
      const box = (await page.locator(id).boundingBox())!;
      expect(box.width, id + ' is narrower than 24 px').toBeGreaterThanOrEqual(24);
      expect(box.height, id + ' is shorter than 24 px').toBeGreaterThanOrEqual(24);
      const row = (await page.locator('.wir-band-row', { has: page.locator(id) }).boundingBox())!;
      const value = (await page.locator(id.replace('Remove', 'Value')).boundingBox())!;
      expect(box.height, 'the × made its row taller than its value box').toBeLessThanOrEqual(value.height);
      expect(row.height, 'the band row wrapped').toBeLessThan(2 * value.height);
    }
    await remove.click();
    await expect(page.locator('#editorProblem')).toHaveCount(0);
    await page.click('#editorSave');
    await expect(page.locator('.wir-slideover')).toHaveCount(0);
    const saved = await written(page, 'slow-api-p95');
    expect(saved.warn, 'the author removed it').toBeUndefined();
    expect(saved.fail).toEqual({ gt: 2 });
  });

  test('[U] a rule whose only band is unreadable is refused for that band, and the preview says so',
    async ({ page }) => {
      // G3's retired-outside-operator has a fail band and nothing else. Asked first whether the rule
      // had any band, the editor told its author to add one to a rule that has one, and the preview
      // said "No band yet" (emptyPreviewReason). The js-unit half pins the refusal; this line is DOM.
      await openTab(page);
      const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
      const rule = doc.assertions.find((a: any) => a.id === 'slow-api-p95');
      delete rule.warn;
      rule.fail = { outside: [1, 2] };
      await openWith(page, 'unreadable-only.json', JSON.stringify(doc, null, 2));

      await page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn').click();
      await expect(page.locator('#editorProblem')).toContainText('Fail outside is no longer an operator');
      await expect(page.locator('#editorPreviewEmpty')).toContainText('fail outside is no longer an operator');
      await expect(page.locator('#editorPreviewEmpty')).not.toContainText('No band yet');
      await expect(page.locator('#editorSave')).toBeDisabled();
      expect((await written(page, 'slow-api-p95')).fail).toEqual({ outside: [1, 2] });
    });

  test('[U] a bare 1e400 is written back as Java writes it, and Edit document… of the page\'s own text changes nothing',
    async ({ page }) => {
      await openTab(page);
      const doc = JSON.parse(await page.evaluate(() => (window as any).WIR.document.text()));
      doc.assertions.find((a: any) => a.id === 'slow-api-p95').fail = { gt: '__BARE__' };
      // JSON.stringify cannot write 1e400, so the literal goes into the text by hand.
      await openWith(page, 'overflow.json', JSON.stringify(doc, null, 2).replace('"__BARE__"', '1e400'));

      const reason = await page.evaluate(() => (window as any).WIR.asserts._state.rules
        .find((r: any) => r.id === 'slow-api-p95').notJudgedDetail);
      expect(reason).toContain('fail gt must be a number, not the number Infinity');
      // Carried as the number, JSON.stringify would write null — a change nobody made. Written as
      // GatePayloadBuilder.documentOf writes it, the next run refuses it as the text "Infinity".
      expect((await written(page, 'slow-api-p95')).fail).toEqual({ gt: 'Infinity' });

      const count = await page.evaluate(() => (window as any).WIR.document.count());
      const text = await page.evaluate(() => {
        (window as any).__rulesBefore = (window as any).WIR.asserts._state.rules.slice();
        return (window as any).WIR.document.text();
      });
      await page.click('#editDocumentBtn');
      await page.click('#docEditorApply');
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(count);
      expect(await page.evaluate(() => (window as any).WIR.document.text())).toBe(text);
      expect(await page.evaluate(() => (window as any).__rulesBefore
        .every((r: any, i: number) => r === (window as any).WIR.asserts._state.rules[i])),
        'N1: an unchanged block keeps the very same rules').toBe(true);
    });
});

test.describe('The Notes tab writes the same document', () => {

  /** Click a button that downloads a blob and return what it wrote. */
  async function capture(page: any, locator: any): Promise<any> {
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      locator.click(),
    ]);
    return JSON.parse(fs.readFileSync((await download.path())!, 'utf-8'));
  }

  test('Save Annotations keeps the gate and the keys it does not understand', async ({ page }) => {
    // The Notes tab used to have its own builder that flattened the file into seven fields.
    // Editing a note and saving therefore deleted `assertions` — the next run had no gate at
    // all, and nothing anywhere said so. Same for any key this version does not know.
    await loadClean(page, 'E25');
    await switchTab(page, 'notes');

    const doc = await capture(page, page.locator('.notes-toolbar button', { hasText: 'Save Annotations' }));

    expect(doc.assertions, 'the Notes-tab save dropped the gate').toBeDefined();
    expect(doc.assertions.length).toBeGreaterThan(0);
    expect(doc._comment, 'the Notes-tab save dropped an unknown key').toBeDefined();
  });

  test('a note edited on the Notes tab reaches the file, gate intact', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'edited in the browser');

    const doc = await capture(page, page.locator('.notes-toolbar button', { hasText: 'Save Annotations' }));

    expect(doc.testNotes).toBe('edited in the browser');
    expect(doc.assertions.length).toBeGreaterThan(0);
  });

  test('both save buttons write byte-identical documents', async ({ page }) => {
    // Two buttons, one builder. If these ever diverge there are two builders again.
    await loadClean(page, 'E25');
    await switchTab(page, 'notes');
    const fromNotes = await capture(page,
      page.locator('.notes-toolbar button', { hasText: 'Save Annotations' }));

    await switchTab(page, 'asserts');
    const fromAsserts = await capture(page, page.locator('#saveAssertsBtn'));

    expect(fromNotes).toEqual(fromAsserts);
  });
});

/**
 * Copy assertions copies the Download's `assertions` block (B1).
 *
 * <p>It called buildDocument(), which fe13431 deleted when the document moved to WIR.document, so
 * every click threw a ReferenceError: nothing reached the clipboard, no toast said so, and whatever
 * the clipboard held before stayed there to be pasted as the rules. The one test that called it
 * did so on a page without its payload, which refuses before the call. These click the button.
 */
test.describe('Copy assertions (B1) [E25]', () => {

  /** Whatever the page writes to the clipboard, kept rather than sent to the OS. */
  async function stubClipboard(page: any) {
    await page.evaluate(() => {
      const w = window as any;
      w.__copied = [];
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: (t: string) => { w.__copied.push(t); return Promise.resolve(); } },
      });
    });
  }

  async function downloaded(page: any): Promise<any> {
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('#saveAssertsBtn'),
    ]);
    return JSON.parse(fs.readFileSync((await download.path())!, 'utf-8'));
  }

  async function copyAssertions(page: any): Promise<string> {
    await page.evaluate(() => { document.getElementById('wir-toast')?.remove(); });
    await page.click('#copyAssertsOnlyBtn');
    await expect(page.locator('#wir-toast')).toHaveText('assertions array copied to the clipboard');
    const copied: string[] = await page.evaluate(() => (window as any).__copied);
    expect(copied, 'one clipboard write per click').toHaveLength(1);
    return copied[0];
  }

  test('[U] the clipboard gets the assertions array the Download writes, and a toast says so',
      async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openTab(page);
      await stubClipboard(page);

      const copied = await copyAssertions(page);
      const doc = await downloaded(page);

      expect(doc.assertions.length, 'precondition: E25 declares rules').toBeGreaterThan(0);
      expect(JSON.parse(copied)).toEqual(doc.assertions);
      // The block as the file spells it: the same JSON.stringify, two-space indent, one newline.
      expect(copied).toBe(JSON.stringify(doc.assertions, null, 2) + '\n');
    });
  });

  test('[U] after an edit the copy carries the draft, as the Download does', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await openTab(page);
      await editFailValue(page, 'slow-api-p95', '999999');
      await stubClipboard(page);

      const copied = JSON.parse(await copyAssertions(page));
      const doc = await downloaded(page);

      expect(copied.find((a: any) => a.id === 'slow-api-p95').fail).toEqual({ gt: 999999 });
      expect(copied).toEqual(doc.assertions);
    });
  });
});

/**
 * A legacy `loadValidity` block becomes an ORDINARY rule.
 *
 * It used to expand into a rule stamped with a non-file origin, which this tab then refused to
 * edit, disable, delete or retune — because the rule came from a block the GUI could not write,
 * so a change had nowhere to be saved. The refusal offered "Duplicate it to get an editable
 * copy", and that was worse: the duplicate landed in assertions[] while the block survived the
 * save, so the file declared the same check twice.
 *
 * These tests are the inverse of the ones they replace. Everything the report used to refuse, it
 * must now do — that is the entire fix. E25 still carries the block, so the migration itself is
 * exercised on every regression run.
 */
test.describe('A migrated loadValidity rule is ordinary [E25]', () => {

  const MIGRATED = 'load:throughput';

  test('the block becomes a rule the client evaluator judges like any other', async ({ page }) => {
    await loadClean(page, 'E25');
    const rule = await page.evaluate((id) => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === id), MIGRATED);

    expect(rule, 'the loadValidity block produced no rule').toBeTruthy();
    expect(rule.origin, 'origin is gone from the model entirely').toBeUndefined();
    expect(rule.target.kind).toBe('overall');
    expect(rule.metric).toBe('throughput');
    // Worse means smaller: a `gt` band here could never fire.
    expect(rule.fail.op).toBe('lt');
    expect(['PASS', 'WARN', 'FAIL']).toContain(rule.status);
  });

  test('saving writes it into assertions[] and carries no block', async ({ page }) => {
    await loadClean(page, 'E25');
    const doc = JSON.parse(await page.evaluate(
      () => (window as any).WIR.asserts._documentText()));

    expect(doc.assertions.map((a: any) => a.id)).toContain(MIGRATED);
    // The check now lives in exactly one place. Both halves matter: in assertions[], and NOT
    // also in a block — carrying both is the double declaration this change exists to end.
    expect(doc.loadValidity, 'the block must not survive the save').toBeUndefined();
  });

  test('it offers Edit, like every other rule', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const row = page.locator(`tr[data-rule-id="${MIGRATED}"]`);
    await expect(row).toHaveCount(1);
    await expect(row.locator('.wir-edit-btn')).toHaveCount(1);
    await expect(row.locator('.wir-dup-btn')).toHaveCount(1);
    await expect(row.locator('.wir-del-btn')).toHaveCount(1);
  });

  test('it can be disabled', async ({ page }) => {
    await loadClean(page, 'E25');
    await page.evaluate((id) => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf(id)); }, MIGRATED);

    const after = await page.evaluate((id) => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === id), MIGRATED);
    expect(after.enabled).toBe(false);
    expect(after.draftState, 'a real change must be marked dirty').toBeTruthy();
  });

  /*
   * The WARN band, not fail, and the reason is worth writing down.
   *
   * E25's migrated rule is `warn lt 0.0015 · fail lt 0.001`, both below `draggableValue`'s 2dp
   * rounding grain. Dragging `fail` upward would also make `warn` unreachable — for a `lt` pair
   * the fail band must sit below the warn band — so the dead-band guard refuses it, correctly and
   * for reasons that have nothing to do with this change. Retuning `warn` to 0.01 keeps both
   * bands live and exercises the same commitDrag path.
   *
   * The refusal that USED to fire here was the derived-rule one, before any of that logic ran.
   * That is what this test proves is gone.
   */
  test('it can be retuned from the chart', async ({ page }) => {
    await loadClean(page, 'E25');
    const before = await page.evaluate((id) => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === id).warn.value, MIGRATED);

    await page.evaluate((id) => (window as any).WIR.asserts._retune(id, 0.01, 'warn'), MIGRATED);

    const after = await page.evaluate((id) => (window as any).WIR.asserts._state.rules
      .find((r: any) => r.id === id), MIGRATED);
    expect(after.warn.value, 'the retune was refused').not.toBe(before);
    expect(after.warn.value).toBe(0.01);
    expect(after.draftState, 'a real change must be marked dirty').toBeTruthy();
  });

  /* The motivating defect, stated as a test: this is the rule you could not delete. */
  test('it can be deleted — the defect that started this', async ({ page }) => {
    await loadClean(page, 'E25');
    await page.evaluate((id) => { const A = (window as any).WIR.asserts; A.deleteRule(A._keyOf(id)); }, MIGRATED);

    const still = await page.evaluate((id) => (window as any).WIR.asserts._state.rules
      .some((r: any) => r.id === id), MIGRATED);
    expect(still, 'the rule survived a delete').toBe(false);

    // And the delete reaches the file, rather than the block putting it straight back.
    const doc = JSON.parse(await page.evaluate(
      () => (window as any).WIR.asserts._documentText()));
    expect(doc.assertions.map((a: any) => a.id)).not.toContain(MIGRATED);
    expect(doc.loadValidity).toBeUndefined();
  });

  test('no rule carries an origin, and no origin filter exists', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const anyOrigin = await page.evaluate(() => (window as any).WIR.asserts._state.rules
      .some((r: any) => r.origin !== undefined));
    expect(anyOrigin, 'origin survived somewhere in the client model').toBe(false);

    await page.click('#assertsMoreFilters');
    await expect(page.locator('#assertsFilter-origin')).toHaveCount(0);
  });
});
