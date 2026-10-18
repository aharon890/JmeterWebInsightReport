import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, loadDirty, switchTab } from './helpers';

/**
 * The findings pane on each tab, as the document it describes (SF18, plan Q8: one live list per
 * tab, headed by which document it describes).
 *
 * The Server tab's list was baked once, from chartData.serverFindings: a finding the draft fixed
 * stayed above a row now judged live, and a problem the draft introduced was never said. It is
 * rebuilt now, in ServerGateEvaluation's phases and under its ruleIds — the parity vectors'
 * documentCases pin the list itself in Java and in node; this pins the surface, and that on the
 * file a real run was built from the live list IS the build's.
 *
 * The Asserts tab cannot rebuild the validator's findings (they are about the run), so each is
 * MARKED for what the draft did to its rule — per rule, never by comparing sentences.
 */

async function openServer(page: Page, id = 'E26') {
  await loadClean(page, id);
  await switchTab(page, 'server');
}

function pane(page: Page) {
  return page.locator('#serverBoard .wir-server-findings');
}

const MAXX = "reduce.kind 'maxx' is not a reducer — valid here: max, min, avg, last, slope, pN (e.g. p95)";

/** What ServerKpiEvaluator says about a 1500 ms sustainedFor on E26's 1 s collection. */
const WHOLE_STEP = "reduce.over sustainedFor 1.5s is not a whole number of the collection's 1s steps, so no "
  + 'window can ever span it - use a multiple of 1s, or collect with a finer wir-collect --step';

/** A row of the board by the id it is judged under. */
function rowOf(page: Page, id: string) {
  return page.locator('#serverKpiTable tbody tr.wir-kpi-row', { hasText: id }).first();
}

/**
 * Replace the page's document through Edit document… on the Asserts tab, as a user pasting a file
 * would: `mutate` names one of the edits below, so the page code stays a literal.
 */
async function editDocument(page: Page, mutate: string, arg: unknown = null) {
  await page.evaluate(({ m, a }) => {
    const W = (window as any).WIR;
    W.asserts.openDocument();
    const doc = JSON.parse(W.document.text());
    const kpi = (id: string) => doc.serverMetrics.asserts.find((k: any) => k.id === id);
    const rule = (id: string) => doc.assertions.find((r: any) => r.id === id);
    if (m === 'many-instances over') kpi('many-instances').reduce = { kind: 'max', over: { sustainedFor: a } };
    if (m === 'many-instances description') kpi('many-instances').description = a;
    if (m === 'push assert') doc.serverMetrics.asserts.push(a);
    if (m === 'server block') doc.serverMetrics = a;
    if (m === 'environments') Object.assign(doc.serverMetrics, a);
    if (m === 'unknown-metric') Object.assign(rule('unknown-metric'), a);
    if (m === 'settle and break') {
      // Every finding the file had, settled. The migrated legacy rule's notice (GAP-1) is by the
      // draft itself: its Download writes the rule the block became and no block, so the next run
      // files no notice (NIT-3) — the rule stays.
      doc.assertions = doc.assertions.filter((r: any) => r.id !== 'renamed-sampler');
      rule('unknown-metric').metric = 'p95';
      doc.assertions.push(a);
    }
    W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    W.asserts.applyDocument();
  }, { m: mutate, a: arg });
}

test.describe('The Server tab\'s findings (SF18)', () => {
  for (const id of ['E26', 'E27']) {
    test(`[U] on the file ${id} was generated with, the live list is the build's, word for word`, async ({ page }) => {
      await openServer(page, id);
      const { live, baked } = await page.evaluate(() => {
        const W = window as any;
        const triple = (f: any) => [f.severity, f.ruleId, f.message];
        return {
          live: W.WIR.serverKpis._findings().map(triple),
          baked: (W.globalChartData.serverFindings || []).map(triple)
        };
      });
      // In Java's order too: the mirror rebuilds the list from the document, and it must not drift
      // from what the CI/CD JSON's findings[] carries for the same file.
      expect(live).toEqual(baked);
      if (baked.length) {
        await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'generated');
        await expect(pane(page).locator('.wir-findings-scope')).toHaveText('In the file this report was generated with.');
      } else {
        await expect(pane(page)).toHaveCount(0);
      }
    });
  }

  test('[U] B6: fixing typo-reducer\'s reducer in the editor clears its finding, and says the draft has none', async ({ page }) => {
    await openServer(page);
    await expect(pane(page).locator('.wir-findings-title')).toHaveText('1 configuration problem');
    await expect(pane(page)).toContainText(MAXX);
    const chip = (await page.locator('.wir-server-chip').textContent()) || '';

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('typo-reducer'));
    });
    await page.locator('#serverEditor [data-field="kind"]').selectOption('max');
    await page.click('#serverEditorSave');

    await expect(pane(page)).not.toContainText("'maxx'");
    await expect(pane(page).locator('.wir-findings-title')).toHaveText('No configuration problems in this draft');
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'draft');
    await expect(pane(page).locator('.wir-findings-scope')).toHaveText(
      'In this draft, as the next run will read it — the file this report was generated with had 1 problem.');
    // D24: the header chip keeps the build's word — the pane describes the draft, the chip the run.
    await expect(page.locator('.wir-server-chip')).toHaveText(chip);

    // A reload restores the draft, and the pane answers for it rather than for the build again.
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(pane(page)).not.toContainText("'maxx'");
  });

  test('[U] B6: a duplicate of a refused assert is said, under its own id', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.duplicate(W._keyOf('typo-reducer'));
    });
    const rows = pane(page).locator('.wir-finding-error');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0).locator('.wir-rule-id')).toHaveText('typo-reducer');
    await expect(rows.nth(1).locator('.wir-rule-id')).toHaveText('typo-reducer-2');
    await expect(rows.nth(1)).toContainText(MAXX);
    await expect(pane(page).locator('.wir-findings-title')).toHaveText('2 configuration problems');
  });

  test('[U] B6: a warn the fail band covers, saved, is a WARNING in the pane, as the next run files it', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-headroom'));
      W.set('warn.op', 'gt');
      W.set('warn.value', '99');
      W.applyEditor();
    });
    const warning = pane(page).locator('.wir-finding-warning', { hasText: 'warn can never fire' });
    await expect(warning).toHaveCount(1);
    await expect(warning.locator('.wir-rule-id')).toHaveText('cpu-headroom');
    await expect(warning).toContainText('fail > 95 % is tested first and already covers warn > 99 %. Swapped values?');
    // After the mapper's: typo-reducer's ERROR stays first, as ServerGateEvaluation orders them.
    await expect(pane(page).locator('.wir-finding').first().locator('.wir-rule-id')).toHaveText('typo-reducer');
  });

  test('[U] B6: an imported band the mapper refuses is said, though the build never saw it', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts.push({ id: 'imported-bad', component: 'db', query: 'cpu',
        reduce: { kind: 'max' }, fail: { gtt: 9 } });
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');
    const bad = pane(page).locator('.wir-finding-error', { hasText: "fail operator 'gtt' is not one of gt, gte, lt, lte" });
    await expect(bad).toHaveCount(1);
    await expect(bad.locator('.wir-rule-id')).toHaveText('imported-bad');
  });

  test('[U] the scope is the server slice\'s: a client-only edit leaves an unchanged list "as generated"', async ({ page }) => {
    await openServer(page);
    // A note is the client slice's (testNotes): the document now differs, the server half does not.
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'a note');
    await expect.poll(() => page.evaluate(() => (window as any).WIR.document.countOf('asserts'))).toBeGreaterThan(0);
    await switchTab(page, 'server');
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'generated');
    await expect(pane(page).locator('.wir-findings-scope')).toHaveText('In the file this report was generated with.');
  });

  test('[U] the pane is styled as the Asserts tab\'s findings are: a heading, a rule, and an error that reads as one', async ({ page }) => {
    await openServer(page);
    await expect(pane(page)).toHaveClass(/\bwir-findings\b/);
    await expect(pane(page).locator('h2.wir-findings-title')).toHaveCount(1);
    const style = await pane(page).evaluate((el) => {
      const s = getComputedStyle(el);
      const id = el.querySelector('.wir-finding-error .wir-rule-id') as HTMLElement;
      return { border: s.borderLeftWidth, weight: getComputedStyle(id).fontWeight };
    });
    expect(style.border).toBe('4px');
    expect(Number(style.weight)).toBeGreaterThanOrEqual(600);
  });

  test('[U] SF18 review: an edited capped assert the step cannot hold is refused live — on the board and in the pane', async ({ page }) => {
    await openServer(page);
    await editDocument(page, 'many-instances over', '1500ms');
    await switchTab(page, 'server');
    // many-instances is E26's capped series (21 instances, 20 drawn): its points are not re-judged
    // here, but its record and the collection's step are exact, so the refusal the next run makes
    // is made here. It read NOT RE-JUDGED and left the pane, while the editor's footer said it.
    await expect(rowOf(page, 'many-instances').locator('.wir-chip')).toHaveText('INVALID');
    const refusal = pane(page).locator('.wir-finding-error', { hasText: WHOLE_STEP });
    await expect(refusal).toHaveCount(1);
    await expect(refusal.locator('.wir-rule-id')).toHaveText('many-instances');
    // After the mapper's, as ServerGateEvaluation orders the run-time refusals.
    await expect(pane(page).locator('.wir-finding .wir-rule-id')).toHaveText(['typo-reducer', 'many-instances']);
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'draft');
  });

  test('[U] SF18 review: a capped assert the build refused keeps its refusal — untouched, and after an edit that does not answer it', async ({ page }) => {
    // E26 as Java ships it for a file whose many-instances holds a level for 1500 ms: the series is
    // capped on the page, the verdict INVALID_RULE, and the finding filed after the mapper's.
    await page.addInitScript((reason) => {
      let stored: any;
      Object.defineProperty(window, 'chartData', {
        configurable: true,
        get() { return stored; },
        set(value) {
          if (value && value.serverKpis && value.annotationsDocument) {
            const k = value.annotationsDocument.serverMetrics.asserts.find((a: any) => a.id === 'many-instances');
            k.reduce = { kind: 'max', over: { sustainedFor: '1500ms' } };
            Object.assign(value.serverKpis.find((b: any) => b.id === 'many-instances'), {
              status: 'NOT_JUDGED', notJudged: 'INVALID_RULE', reason, actual: null, coverage: null,
              instances: 0, breached: null });
            value.serverFindings.push({ severity: 'error', ruleId: 'many-instances', message: reason });
          }
          stored = value;
        },
      });
    }, WHOLE_STEP);
    await openServer(page);
    const listed = () => page.evaluate(() => (window as any).WIR.serverKpis._findings()
      .map((f: any) => [f.severity, f.ruleId, f.message]));
    expect(await listed()).toEqual([['error', 'typo-reducer', MAXX], ['error', 'many-instances', WHOLE_STEP]]);
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'generated');

    // A new description answers nothing: the next run refuses the same assert, and the pane says so
    // rather than dropping it because the row now holds a definition the build did not judge.
    await editDocument(page, 'many-instances description', 'retitled');
    await switchTab(page, 'server');
    await expect(rowOf(page, 'many-instances').locator('.wir-chip')).toHaveText('INVALID');
    expect(await listed()).toEqual([['error', 'typo-reducer', MAXX], ['error', 'many-instances', WHOLE_STEP]]);
    await expect(pane(page).locator('.wir-findings-title')).toHaveText('2 configuration problems');
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'draft');
  });

  test('[U] SF18 review: a NEEDS RE-COLLECTION row still says the refusal no collection answers', async ({ page }) => {
    await openServer(page);
    await editDocument(page, 'push assert', { id: 'needs-typo', component: 'db', query: 'cpu-new',
      reduce: { kind: 'max' }, fail: { gt: 1 }, requiresLoad: 'load:nope' });
    await switchTab(page, 'server');
    await expect(rowOf(page, 'needs-typo')).toContainText('NEEDS RE-COLLECTION');
    const refusal = pane(page).locator('.wir-finding-error', { hasText: "requiresLoad names 'load:nope'" });
    await expect(refusal).toHaveCount(1);
    await expect(refusal.locator('.wir-rule-id')).toHaveText('needs-typo');
    // Never "no series was collected": the re-collection the row asks for answers that one.
    await expect(pane(page)).not.toContainText('no series was collected');
  });

  test('[U] the scope follows the server slice\'s own edits, even ones that leave the list as it was', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      W.set('fail.value', '600');
      W.applyEditor();
    });
    // The list is the build's word for word; the document is not, and the line says which it is.
    await expect(pane(page).locator('.wir-findings-title')).toHaveText('1 configuration problem');
    await expect(pane(page)).toHaveAttribute('data-wir-findings-scope', 'draft');
    await expect(pane(page).locator('.wir-findings-scope')).toHaveText(
      'In this draft, as the next run will read it — the file this report was generated with had 1 problem.');
  });
});

test.describe('The Asserts tab\'s findings (SF18)', () => {
  test('[U] B6: fixing unknown-metric marks its finding fixed in this draft — by its rule, not its text', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const findings = page.locator('#assertsFindings .wir-findings');
    // unknown-metric's ERROR, renamed-sampler's NO_MATCH WARNING, and the legacy loadValidity
    // block's migration WARNING — which reaches a real run since GAP-1 (the loader threw its id
    // away) and stands while there is no draft: the next run reads this file, block and all (NIT-3).
    await expect(findings.locator('.wir-findings-title')).toHaveText('3 configuration problems');
    const notice = findings.locator('.wir-finding', { hasText: 'the legacy loadValidity block was migrated' });
    await expect(notice).toHaveCount(1);
    await expect(findings).toHaveAttribute('data-wir-findings-scope', 'generated');
    await expect(findings.locator('.wir-findings-scope')).toHaveText('In the file this report was generated with.');
    await expect(findings.locator('[data-wir-finding-mark]')).toHaveCount(0);

    await page.click('#assertsBroken .wir-broken-row button');               // Fix
    await page.selectOption('#editorMetric', 'p95');
    await page.click('#editorSave');

    const fixed = findings.locator('.wir-finding', { hasText: "unknown metric 'p95x'" });
    await expect(fixed).toHaveAttribute('data-wir-finding-mark', 'fixed');
    await expect(fixed.locator('.wir-finding-mark')).toHaveText('fixed in this draft');
    // The sentence is the build's, struck through: kept until the file is regenerated.
    const strike = await fixed.locator('.wir-finding-text').evaluate((el) => getComputedStyle(el).textDecorationLine);
    expect(strike).toBe('line-through');
    // The other finding is about a rule nobody touched, and stands.
    const other = findings.locator('.wir-finding', { hasText: 'matched nothing in this run' });
    await expect(other).toHaveCount(1);
    expect(await other.getAttribute('data-wir-finding-mark')).toBeNull();
    // The notice is settled by the draft, though nothing touched its rule: the draft's Download
    // writes that rule and no block, so the next run files no notice (NIT-3). Fixed, never edited.
    await expect(notice).toHaveAttribute('data-wir-finding-mark', 'fixed');
    await expect(findings.locator('.wir-findings-title')).toHaveText('1 configuration problem');
    await expect(findings).toHaveAttribute('data-wir-findings-scope', 'draft');
  });

  test('[U] B6: a rule deleted in this draft settles its finding; one an edit left unmatched stands', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const findings = page.locator('#assertsFindings .wir-findings');
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.deleteRule(A._keyOf('unknown-metric')); });
    // "Gone", not "fixed": an id alone cannot tell a deletion from a rename, and a renamed rule that
    // is still broken is in the live "could not run" banner under its new id.
    const gone = findings.locator('.wir-finding', { hasText: "unknown metric 'p95x'" });
    await expect(gone).toHaveAttribute('data-wir-finding-mark', 'gone');
    await expect(gone.locator('.wir-finding-mark')).toHaveText('no rule in this draft is called this');
    await expect(gone).toHaveClass(/\bwir-finding-settled\b/);
    // renamed-sampler: its description changed and its target did not — still unmatched, so the
    // finding is the draft's as much as the build's, and is not marked.
    await page.evaluate(() => {
      const A = (window as any).WIR.asserts;
      A.edit(A._keyOf('renamed-sampler'));
      A.setField('description', 'still asks for Fast APIs');
      A.saveEditor();
    });
    const unmatched = findings.locator('.wir-finding', { hasText: 'matched nothing in this run' });
    await expect(unmatched).toHaveCount(1);
    expect(await unmatched.getAttribute('data-wir-finding-mark')).toBeNull();
    await expect(findings.locator('.wir-findings-title')).toHaveText('1 configuration problem');
  });

  async function openAsserts(page: Page) {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    return page.locator('#assertsFindings .wir-findings');
  }

  test('[U] SF18 review: an edit that leaves the rule refused for another reason marks its finding edited, and counts it', async ({ page }) => {
    const findings = await openAsserts(page);
    // The metric is fixed, a band is broken: the rule no longer names p95x, so the build's sentence
    // does not stand as written — and the rule still cannot run.
    await editDocument(page, 'unknown-metric', { metric: 'p95', fail: { gtt: 1 } });
    await expect(page.locator('#assertsBroken')).toContainText("fail operator 'gtt'");
    const edited = findings.locator('.wir-finding', { hasText: "unknown metric 'p95x'" });
    await expect(edited).toHaveAttribute('data-wir-finding-mark', 'edited');
    await expect(edited.locator('.wir-finding-mark')).toHaveText('the rule is edited in this draft — the next run checks it again');
    await expect(edited).not.toHaveClass(/\bwir-finding-settled\b/);
    await expect(findings.locator('.wir-findings-title')).toHaveText('2 configuration problems');
  });

  test('[U] SF18 review: disabling a broken rule fixes nothing, and disabling an unmatched one settles its warning', async ({ page }) => {
    const findings = await openAsserts(page);
    // AssertionValidator runs Usability.check on every rule, enabled or not: the next run still
    // files unknown-metric's ERROR. The Server tab kept the same finding for the same action.
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('unknown-metric')); });
    const broken = findings.locator('.wir-finding', { hasText: "unknown metric 'p95x'" });
    await expect(broken).toHaveCount(1);
    expect(await broken.getAttribute('data-wir-finding-mark')).toBeNull();
    await expect(findings.locator('.wir-findings-title')).toHaveText('2 configuration problems');
    // checkTargetMatches skips a disabled rule, so its unmatched target is not filed again.
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('renamed-sampler')); });
    const unmatched = findings.locator('.wir-finding', { hasText: 'matched nothing in this run' });
    await expect(unmatched).toHaveAttribute('data-wir-finding-mark', 'fixed');
    await expect(findings.locator('.wir-findings-title')).toHaveText('1 configuration problem');
  });

  test('[U] SF18 review: a renamed rule whose target still matches nothing keeps its finding, counted, as edited', async ({ page }) => {
    const findings = await openAsserts(page);
    await page.evaluate(() => {
      const A = (window as any).WIR.asserts;
      A.edit(A._keyOf('renamed-sampler'));
      A.setField('id', 'renamed-sampler-x');
      A.saveEditor();
    });
    // The next run files the same warning under renamed-sampler-x; struck through as "gone", it was
    // said in neither box (the "could not run" banner lists INVALID_RULE only).
    const unmatched = findings.locator('.wir-finding', { hasText: 'matched nothing in this run' });
    await expect(unmatched).toHaveAttribute('data-wir-finding-mark', 'edited');
    await expect(unmatched).not.toHaveClass(/\bwir-finding-settled\b/);
    await expect(findings.locator('.wir-findings-title')).toHaveText('2 configuration problems');
  });

  test('[U] SF18 review: with the file\'s problems settled, a rule the draft broke stops the heading saying the draft has none', async ({ page }) => {
    const findings = await openAsserts(page);
    await editDocument(page, 'settle and break', { id: 'new-bad', target: { scope: 'overall' }, metric: 'p95xx', fail: { gt: 1 } });
    await expect(page.locator('#assertsBroken')).toContainText('new-bad');
    await expect(findings.locator('.wir-findings-title')).toHaveText('None of the generated file’s problems remains in this draft');
    await expect(findings).not.toHaveClass(/\bwir-findings-clear\b/);
    await expect(findings.locator('.wir-findings-scope')).toContainText('This draft has its own: 1 rule that could not run (above).');

    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.deleteRule(A._keyOf('new-bad')); });
    await expect(findings.locator('.wir-findings-title')).toHaveText('No configuration problems in this draft');
    await expect(findings).toHaveClass(/\bwir-findings-clear\b/);
  });

  test('[U] the scope is the client slice\'s: a server-only edit leaves the Asserts tab\'s findings "as generated"', async ({ page }) => {
    const findings = await openAsserts(page);
    await editDocument(page, 'server block', { components: { db: { type: 'custom' } }, asserts: [] });
    expect(await page.evaluate(() => {
      const D = (window as any).WIR.document;
      return [D.countOf('asserts'), D.countOf('server') > 0];
    })).toEqual([0, true]);
    await expect(findings).toHaveAttribute('data-wir-findings-scope', 'generated');
    await expect(findings.locator('.wir-findings-scope')).toHaveText('In the file this report was generated with.');
    // ...and nothing on it is marked, the migration notice included (NIT-3).
    await expect(findings.locator('[data-wir-finding-mark]')).toHaveCount(0);
    await expect(findings.locator('.wir-findings-title')).toHaveText('3 configuration problems');
  });
});

test.describe('The Server tab\'s findings read the block itself again (SF18 review)', () => {
  test('[U] a draft whose defaultEnv names no declared environment is told so — a config error the build never saw', async ({ page }) => {
    await openServer(page);
    await editDocument(page, 'environments', { environments: { prod: { vars: { ns: 'shop' } } }, defaultEnv: 'staging' });
    await switchTab(page, 'server');
    const env = pane(page).locator('.wir-finding-error', { hasText: "'staging' is not one of the declared environments: prod" });
    await expect(env).toHaveCount(1);
    await expect(env.locator('.wir-rule-id')).toHaveText('serverMetrics.defaultEnv');
    // The mapper's block-level findings come before any assert's.
    await expect(pane(page).locator('.wir-finding .wir-rule-id')).toHaveText(['serverMetrics.defaultEnv', 'typo-reducer']);
  });

  test('[U] a ramp-up finding the page cannot judge again says so, by the key it is about, once that key changes', async ({ page }) => {
    // E26 as a run whose report and payload disagree about the ramp-up would ship it.
    const RAMP = 'the payload was collected with a ramp-up of 0 ms';
    await page.addInitScript((message) => {
      let stored: any;
      Object.defineProperty(window, 'chartData', {
        configurable: true,
        get() { return stored; },
        set(value) {
          if (value && value.serverFindings) {
            value.serverFindings.push({ severity: 'warning', ruleId: 'serverMetrics.collection', message });
          }
          stored = value;
        },
      });
    }, RAMP);
    await openServer(page);
    const ramp = pane(page).locator('.wir-finding-warning', { hasText: RAMP });
    await expect(ramp).toHaveCount(1);
    await expect(ramp.locator('.wir-finding-mark')).toHaveCount(0);

    await editDocument(page, 'environments', { collection: { excludeRampUp: false } });
    await switchTab(page, 'server');
    // Named by its key — "the block it is about" named nothing a reader can find in the file.
    await expect(ramp.locator('.wir-finding-mark')).toHaveText(
      'as generated — serverMetrics.collection has changed since, and this page cannot check it again');
  });
});
