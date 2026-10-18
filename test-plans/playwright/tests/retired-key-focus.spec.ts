import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * REV-9: where focus goes after a retired key's Remove, in both editors.
 *
 * The Remove button goes with its key and has no twin in the redrawn form, so restoreDialogPlace put
 * focus on the slide-over panel itself — a keyboard reader was left on no control at all, Tab
 * starting again from the top of the form. It goes to the Remove that took the pressed one's place
 * (the next key's, else the one before it) and, with none left, to Save, as removeBand sends it to
 * the band's box.
 */

test.describe('REV-9: after Remove on a retired key, focus is on a control', () => {
  test('[U] the Asserts editor: the other key\'s Remove, then Save', async ({ page }) => {
    await loadClean(page, 'E25');
    await switchTab(page, 'asserts');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      const rule = doc.assertions.find((r: any) => r.id === 'slow-api-p95');
      rule.observe = true;
      rule.onMissing = 'fail';
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
      W.asserts.edit(W.asserts._keyOf('slow-api-p95'));
    });
    const editor = page.locator('#assertEditor');
    await expect(editor.locator('.wir-retired-keys button')).toHaveCount(2);
    await expect(editor.locator('#editorSave')).toBeDisabled();

    // The last of two: focus goes to the one before it.
    await editor.locator('button', { hasText: 'Remove "onMissing"' }).click();
    await expect(editor.locator('.wir-retired-keys button')).toHaveCount(1);
    await expect(editor.locator('button', { hasText: 'Remove "observe"' })).toBeFocused();

    // The last one: nothing left to remove, so Save — which the key was holding back.
    await page.keyboard.press('Enter');
    await expect(editor.locator('.wir-retired-keys')).toHaveCount(0);
    await expect(editor.locator('#editorSave')).toBeEnabled();
    await expect(editor.locator('#editorSave')).toBeFocused();
  });

  test('[U] the Server tab\'s editor: the next key\'s Remove, then Save', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      const kpi = doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike');
      kpi.onMissing = 'fail';
      kpi.sustainedFor = '5s';
      W.document.adopt(doc);
      W.serverKpis.reread();
      W.serverKpis.edit(W.serverKpis._keyOf('lag-spike'));
      // The tolerance the sustainedFor sentence asks for, so only the keys hold Save back.
      W.serverKpis.set('over', 'sustainedFor');
      W.serverKpis.set('overValue', '5s');
    });
    const editor = page.locator('#serverEditor');
    await expect(editor.locator('.wir-retired-keys button')).toHaveCount(2);

    // The first of two: focus goes to the next one, which has taken its place.
    await editor.locator('button', { hasText: 'Remove "onMissing"' }).click();
    await expect(editor.locator('.wir-retired-keys button')).toHaveCount(1);
    await expect(editor.locator('button', { hasText: 'Remove "sustainedFor"' })).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(editor.locator('.wir-retired-keys')).toHaveCount(0);
    await expect(editor.locator('#serverEditorSave')).toBeEnabled();
    await expect(editor.locator('#serverEditorSave')).toBeFocused();
  });
});
