import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab, expectNoJsErrors } from './helpers';

/**
 * Group A of plans/report-design-and-bugs-2026-10.md — the client-side defects the 2026-10-02
 * exploratory session found on E10, E11, E25 and E26, each pinned where it was seen.
 *
 * B1  a non-numeric warn was accepted and silently dropped on Save
 * B2  Actual and "Worst breaches" showed the FIRST matched sampler, not the worst
 * B3  a WARN breach was printed against the fail limit it never crossed
 * B4  "Worst breaches" was not ordered by how bad the breach was
 * B8  the preview said "Nothing matched" for a rule with no bands
 * U2  Delete and Discard drafts threw work away with no way back
 * U3  a dead warn band blocked Save on the Asserts tab only
 * U4  the editors ignored the keyboard: no Escape, no focus, no trap
 * U5  the Criteria hint overflowed the Asserts editor
 * U9  the ASSERTS badge was inert, disabled rules were missing from the count line, and Cancel
 *     after a ⊕ stranded the tester on the Asserts tab
 */

async function openAssertsEditor(page: Page, fixture: string, ruleId: string) {
  await loadClean(page, fixture);
  await switchTab(page, 'asserts');
  await page.locator(`tr[data-rule-id="${ruleId}"] .wir-edit-btn`).click();
  await expect(page.locator('#assertEditor .wir-slideover')).toBeVisible();
}

/** "1,972 ms" → 1972. */
function numberIn(text: string): number {
  const m = text.replace(/,/g, '').match(/-?\d+(\.\d+)?/);
  return m ? parseFloat(m[0]) : NaN;
}

test.describe('Client asserts — Group A fixes', () => {

  test('B2: Actual is the worst matched sampler, the same one the Budget cell names', async ({ page }) => {
    await loadClean(page, 'E11');
    await switchTab(page, 'asserts');
    const rows = page.locator('tr.wir-rule-row');
    const n = await rows.count();
    let checked = 0;
    for (let i = 0; i < n; i++) {
      const row = rows.nth(i);
      const title = await row.locator('td.wir-budget').getAttribute('title');
      if (!title || !title.startsWith('Worst measured')) continue;
      const worst = numberIn(title.replace('Worst measured', ''));
      const actual = numberIn(await row.locator('td.wir-num:not(.wir-budget)').innerText());
      const id = await row.getAttribute('data-rule-id');
      expect(actual, `Actual of ${id} is not the worst measured value its Budget cell names`)
        .toBeCloseTo(worst, 0);
      checked++;
    }
    expect(checked, 'no row carried a Budget to compare with').toBeGreaterThan(0);
  });

  test('B3: a WARN breach is shown against the warn limit it crossed', async ({ page }) => {
    await loadClean(page, 'E25');
    const warnRows = page.locator('.wir-gate-breaches .wir-breach', {
      has: page.locator('.wir-chip', { hasText: 'WARN' }),
    });
    expect(await warnRows.count()).toBeGreaterThan(0);
    const detail = await warnRows.first().locator('.wir-breach-detail').innerText();
    // E25's moderate-api-p95: warn > 1 ms, fail > 999,999 ms. It WARNs, so the fail limit is
    // the one number this row must not quote.
    expect(detail).not.toContain('999,999');
    expect(detail).toMatch(/vs > 1 ms/);
  });

  test('B4: Worst breaches are the biggest breaches, biggest first', async ({ page }) => {
    await loadClean(page, 'E11');
    const shown = await page.locator('.wir-gate-breaches .wir-breach .wir-rule-id').allInnerTexts();
    expect(shown.length).toBe(3);
    const statuses = await page.locator('.wir-gate-breaches .wir-breach .wir-chip').allInnerTexts();
    expect(statuses.every((s) => s === 'FAIL'), 'E11 has more than three FAILs').toBe(true);
    // E11's breaches by margin: errorRate ~18,000%, mean ~4,800%, then the percentiles. On status
    // alone the strip led with p95 at what its first sampler made 200% and left mean out.
    expect(shown[0], 'the biggest breach must lead').toBe('default:errorRate');
    expect(shown, 'a 4,800% breach was left off the top three').toContain('default:mean');
  });

  test('B1: a warn value that is not a number is refused, not dropped on Save', async ({ page }) => {
    await openAssertsEditor(page, 'E25', 'fast-api-p95');
    await page.fill('#editorwarnValue', '5o0');
    await expect(page.locator('#editorProblem')).toContainText('Warn must be a number');
    await expect(page.locator('#editorProblem')).toContainText('"5o0"');
    await expect(page.locator('#editorSave')).toBeDisabled();
  });

  test('U3: a warn band that can never fire is a note, and Save stays on', async ({ page }) => {
    await openAssertsEditor(page, 'E25', 'slow-api-p95');
    await page.fill('#editorwarnValue', '600');
    await page.fill('#editorfailValue', '500');
    await expect(page.locator('#editorProblem')).toHaveCount(0);
    await expect(page.locator('#editorNote')).toContainText('Warn never fires');
    await expect(page.locator('#editorSave')).toBeEnabled();
  });

  test('B8: with no band the preview says so, not "Nothing matched"', async ({ page }) => {
    await openAssertsEditor(page, 'E25', 'slow-api-p95');
    await page.fill('#editorwarnValue', '');
    await page.fill('#editorfailValue', '');
    await expect(page.locator('#editorPreviewEmpty')).toContainText('No band yet');
    await expect(page.locator('#editorPreviewEmpty')).not.toContainText('Nothing matched');
  });

  test('U5: the Asserts editor body never scrolls sideways', async ({ page }) => {
    await openAssertsEditor(page, 'E25', 'fast-api-p95');
    const overflow = await page.locator('#assertEditor .wir-slideover-body')
      .evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    // The criteria section is a block again, not a flex row squeezing the hint.
    const display = await page.locator('#editorBandsField').evaluate((el) => getComputedStyle(el).display);
    expect(display).toBe('block');
  });

  test('U4: focus moves in, Tab stays in, Escape closes and focus returns', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const edit = page.locator('tr[data-rule-id="slow-api-p95"] .wir-edit-btn');
    await edit.focus();
    await page.keyboard.press('Enter');
    const dialog = page.locator('#assertEditor .wir-slideover');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    await expect(dialog).toHaveAttribute('aria-labelledby', 'assertEditorTitle');
    expect(await page.evaluate(() => !!document.activeElement?.closest('#assertEditor .wir-slideover')))
      .toBe(true);

    for (let i = 0; i < 40; i++) await page.keyboard.press('Tab');
    expect(await page.evaluate(() => !!document.activeElement?.closest('#assertEditor .wir-slideover')),
      'Tab walked out of the dialog').toBe(true);

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    expect(await page.evaluate(() =>
      document.activeElement?.closest('tr')?.getAttribute('data-rule-id'))).toBe('slow-api-p95');
  });

  test('U4: the server editor obeys the same keys, and its bands are labelled', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.locator('button[onclick*="WIR.serverKpis.edit"]').first().click();
    const dialog = page.locator('#serverEditor .wir-slideover');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    await expect(page.locator('#serverEditor [data-field="warn.value"]')).toHaveAttribute('aria-label', 'Warn value');
    await expect(page.locator('#serverEditor [data-field="fail.op"]')).toHaveAttribute('aria-label', 'Fail operator');
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  });

  test('U2: a deleted rule comes back with Undo', async ({ page }) => {
    await expectNoJsErrors(page, async () => {
      await loadClean(page, 'E25');
      await switchTab(page, 'asserts');
      await page.locator('tr[data-rule-id="slow-api-p95"] .wir-del-btn').click();
      await expect(page.locator('tr[data-rule-id="slow-api-p95"]')).toHaveCount(0);
      await expect(page.locator('#wir-undo-toast')).toContainText('slow-api-p95');
      await page.click('#wirUndoBtn');
      await expect(page.locator('tr[data-rule-id="slow-api-p95"]')).toHaveCount(1);
    });
  });

  test('U2: Discard drafts can be undone, server edits included', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const before = await page.locator('button[onclick*="WIR.serverKpis.remove"]').count();
    await page.locator('button[onclick*="WIR.serverKpis.remove"]').first().click();
    await expect(page.locator('button[onclick*="WIR.serverKpis.remove"]')).toHaveCount(before - 1);

    await switchTab(page, 'asserts');
    await page.click('#discardDraftsBtn');
    await expect(page.locator('#wir-undo-toast')).toContainText('Discarded 1 unsaved change');
    await switchTab(page, 'server');
    await expect(page.locator('button[onclick*="WIR.serverKpis.remove"]')).toHaveCount(before);

    await page.click('#wirUndoBtn');
    await expect(page.locator('button[onclick*="WIR.serverKpis.remove"]')).toHaveCount(before - 1);
  });

  test('U2: a deleted server assert comes back with Undo', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const removes = page.locator('button[onclick*="WIR.serverKpis.remove"]');
    const before = await removes.count();
    await removes.first().click();
    await expect(removes).toHaveCount(before - 1);
    await expect(page.locator('#wir-undo-toast')).toContainText('deleted');
    await page.click('#wirUndoBtn');
    await expect(removes).toHaveCount(before);
  });

  test('U9a: the ASSERTS badge opens the Asserts tab', async ({ page }) => {
    await loadClean(page, 'E25');
    const chip = page.locator('.wir-asserts-chip');
    await expect(chip).toHaveText(/ASSERTS (FAIL|WARNING|PASS)/);
    expect(await chip.evaluate((el) => el.tagName)).toBe('BUTTON');
    await chip.click();
    await expect(page.locator('.tab-btn[data-tab="asserts"]')).toHaveClass(/active/);
  });

  test('U9b: the count line names disabled rules and says the invalid one could not run', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    const counts = page.locator('#assertsToolbar .wir-toolbar-counts');
    await expect(counts).toContainText('1 disabled');
    await expect(counts).toContainText('invalid (could not run)');
  });

  test('U9c: Cancel after a ⊕ goes back to the Samplers tab', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'samplers');
    await page.locator('.wir-seed-btn').first().click();
    await expect(page.locator('#assertEditor .wir-slideover')).toBeVisible();
    await expect(page.locator('.tab-btn[data-tab="asserts"]')).toHaveClass(/active/);
    await page.locator('#editorFoot button', { hasText: 'Cancel' }).click();
    await expect(page.locator('.tab-btn[data-tab="samplers"]')).toHaveClass(/active/);
  });

  test('B10: the whole-run percentile cards do not call themselves averages', async ({ page }) => {
    await loadClean(page, 'E25');
    await expect(page.locator('.summary-card .card-label', { hasText: 'P95, whole run' })).toHaveCount(1);
    await expect(page.locator('.summary-card .card-label', { hasText: /Avg P9/ })).toHaveCount(0);
  });
});

/** The document the next Download writes. */
const docOf = (page: Page) => page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
/** A rule's `enabled` as the file would say it, or 'absent'. */
const enabledIn = (doc: any, id: string) => {
  const r = doc.assertions.find((x: any) => x.id === id);
  return r ? r.enabled !== false : 'absent';
};
const serverDisabled = (doc: any) => doc.serverMetrics.asserts.filter((a: any) => a.enabled === false).length;
/* The Undo button, pressed even once its toast is withdrawn: the fade is the only thing in the way. */
const undoAnyway = (page: Page) => page.evaluate(() => (document.getElementById('wirUndoBtn') as HTMLButtonElement).click());

/**
 * F5 (review of 3.1.37). Every Undo restores a document captured BEFORE its action, so an edit made
 * while its toast was up — on either tab, a typed note included — was written over by the Undo, and
 * nothing said so. The offer is now withdrawn the moment the document moves, and an Undo reached
 * anyway writes nothing. What is not a change — a filter, a sort, Expand all, a tab switch — keeps it.
 */
test.describe('F5: an Undo is offered only while the document is as its action left it', () => {
  test('[U] F5: client Delete, then Disable on another rule: the Undo is withdrawn and takes nothing', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-del-btn').click();
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toHaveClass(/visible/);
    await page.locator('tr[data-rule-id="fast-api-p95"] .wir-toggle-btn').click();
    await expect(toast, 'an Undo that would revert the Disable just made').not.toHaveClass(/visible/);
    await undoAnyway(page);
    const d = await docOf(page);
    expect(enabledIn(d, 'slow-api-p95')).toBe('absent');
    expect(enabledIn(d, 'fast-api-p95')).toBe(false);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(2);
  });

  test('[U] F5: the toast is the page\'s, not the tab\'s: a server edit withdraws a client Delete\'s Undo', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="load:unreachable"] .wir-del-btn').click();
    await switchTab(page, 'server');
    const toast = page.locator('#wir-undo-toast');
    await expect(toast, 'a tab switch is not a change').toHaveClass(/visible/);
    const before = serverDisabled(await docOf(page));
    await page.locator('button[onclick*="WIR.serverKpis.toggleEnabled"]').first().click();
    await expect(toast).not.toHaveClass(/visible/);
    await undoAnyway(page);
    const d = await docOf(page);
    expect(enabledIn(d, 'load:unreachable')).toBe('absent');
    expect(serverDisabled(d)).toBe(before + 1);
  });

  test('[U] F5: server Delete, then a note typed: the Undo is withdrawn, and the note stays', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const n = (await docOf(page)).serverMetrics.asserts.length;
    await page.locator('button[onclick*="WIR.serverKpis.remove"]').first().click();
    await expect(page.locator('#wir-undo-toast')).toHaveClass(/visible/);
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'typed while the toast was up');
    // The note is written after a 150 ms debounce; that write is what withdraws the offer.
    await expect(page.locator('#wir-undo-toast')).not.toHaveClass(/visible/);
    await undoAnyway(page);
    const d = await docOf(page);
    expect(d.serverMetrics.asserts.length).toBe(n - 1);
    expect(d.testNotes).toBe('typed while the toast was up');
  });

  test('[U] F5: Discard drafts, then a new edit: the Undo is withdrawn and does not revert the edit', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="fast-api-p95"] .wir-toggle-btn').click();
    await page.click('#discardDraftsBtn');
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toContainText('Discarded 1 unsaved change');
    await page.locator('tr[data-rule-id="moderate-api-p95"] .wir-toggle-btn').click();
    await expect(toast).not.toHaveClass(/visible/);
    await undoAnyway(page);
    const d = await docOf(page);
    expect(enabledIn(d, 'fast-api-p95')).toBe(true);
    expect(enabledIn(d, 'moderate-api-p95')).toBe(false);
  });

  test('[R] F5: what is not a change keeps the Undo — Expand all, a filter chip, a sort, a tab switch, the search box', async ({ page }) => {
    // Server: Expand all, a filter chip on and off, and a tab switch.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const n = (await docOf(page)).serverMetrics.asserts.length;
    await page.locator('button[onclick*="WIR.serverKpis.remove"]').first().click();
    await page.click('#serverExpandAll');
    await page.locator('#serverBoard .wir-filter-chip').nth(1).click();
    await page.locator('#serverBoard .wir-filter-chip').nth(1).click();
    await switchTab(page, 'asserts');
    await switchTab(page, 'server');
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toHaveClass(/visible/);
    await page.click('#wirUndoBtn');
    expect((await docOf(page)).serverMetrics.asserts.length).toBe(n);

    // Client: a status chip on and off, a sort, and the search box.
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="slow-api-p95"] .wir-del-btn').click();
    await page.locator('.wir-status-chip').first().click();
    await page.locator('.wir-status-chip').first().click();
    await page.locator('#assertsTable th[data-sort-col]').first().click();
    await page.fill('#assertsSearch', 'api');
    await page.fill('#assertsSearch', '');
    await expect(toast).toHaveClass(/visible/);
    await page.click('#wirUndoBtn');
    expect(enabledIn(await docOf(page), 'slow-api-p95')).toBe(true);
  });
});
