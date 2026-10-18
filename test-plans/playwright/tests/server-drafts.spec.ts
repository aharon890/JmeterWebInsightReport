import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loadClean, loadDirty, switchTab, expectNoJsErrors } from './helpers';

/** The committed file E26 is generated from (run-all-tests.sh, -Jwebinsight.report.annotations). */
const E26_ANNOTATIONS = path.resolve(__dirname, '..', '..', 'server-metrics', 'e26-server.json');

/** The status chip on one KPI's row of the board, or null when the row is not there. */
async function kpiStatus(page: Page, id: string): Promise<string | null> {
  return page.evaluate((kpiId) => {
    const row = Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row'))
      .find((r) => (r.querySelector('.wir-kpi-name') as HTMLElement).textContent!.trim() === kpiId);
    return row ? (row.querySelector('.wir-chip') as HTMLElement).textContent!.trim() : null;
  }, id);
}

/** One KPI as the next Download would write it. */
async function writtenKpi(page: Page, id: string): Promise<any> {
  return page.evaluate((kpiId) => JSON.parse((window as any).WIR.document.text())
    .serverMetrics.asserts.find((k: any) => k.id === kpiId), id);
}

/** Retune lag-spike's fail band through the editor's own handlers. */
async function editLagSpike(page: Page, value: string): Promise<void> {
  await page.evaluate((v) => {
    const W = (window as any).WIR.serverKpis;
    // Row handlers take the row's key, never an id (two KPIs may share one); _keyOf finds it.
    W.edit(W._keyOf('lag-spike'));
    W.set('fail.value', v);
    W.applyEditor();
  }, value);
}

/** Open a file through the real <input type="file">, the way acceptFile receives it. */
async function openFile(page: Page, name: string, doc: any): Promise<void> {
  await page.setInputFiles('#assertsFileInput', {
    name, mimeType: 'application/json',
    buffer: Buffer.from(typeof doc === 'string' ? doc : JSON.stringify(doc, null, 2)),
  });
}

/**
 * One file, two authors — WIR.document (D16).
 *
 * The Asserts tab writes `assertions` and `testNotes`; the Server tab's block is `serverMetrics`.
 * Whoever assembles the document has to be neither of them, or the second author is permanently
 * a guest in the first one's module.
 *
 * The round-trip test that guards this already existed and ran on E25, which declares no server
 * metrics — so the block was invisible to it. E26 is the only fixture carrying both, and the
 * delivery plan's M6 gate line asks for exactly this: "round-trip test on an unedited file with
 * both blocks".
 */

test.describe('The document assembler — both blocks', () => {
  test('[R] a no-op save on a file with both blocks drops nothing', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');

    const { written, loaded } = await page.evaluate(() => ({
      written: (window as any).WIR.document.text(),
      loaded: (window as any).globalChartData.annotationsDocument,
    }));
    const doc = JSON.parse(written);

    for (const key of Object.keys(loaded)) {
      expect(doc, `key "${key}" was dropped by a no-op save`).toHaveProperty(key);
    }
    // The key-order contract, which the tail loop must not disturb.
    expect(Object.keys(doc)[0]).toBe('$schema');
    expect(doc.assertions.length).toBe(loaded.assertions.length);
  });

  test('[R] the serverMetrics block survives a no-op save EXACTLY', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');

    const same = await page.evaluate(() => {
      const doc = JSON.parse((window as any).WIR.document.text());
      const loaded = (window as any).globalChartData.annotationsDocument;
      return {
        written: doc.serverMetrics,
        loaded: loaded.serverMetrics,
        kpiCount: doc.serverMetrics ? doc.serverMetrics.asserts.length : 0,
      };
    });

    // Deep equality, not presence — and structural rather than byte-for-byte, because the
    // writer emits a fixed key order the way the client's ruleToJson does. What must survive is
    // every key and every value, including the ones whose value happens to be the default:
    // dropping `"across": "max"` because max is the default is still a diff on a file nobody
    // edited, and this assertion is what caught it.
    //
    // A KPI rebuilt from chartData.serverKpis[] would look right and be wrong: those are the
    // JUDGED entries, whose `reduce` is only what Java could read (null for an unreadable one,
    // whose text the file still holds — and a sentence before A1-12), and which carry no
    // `enabled`, no `pack` and none of the file's unknown keys.
    expect(same.kpiCount).toBeGreaterThan(5);
    expect(same.written).toEqual(same.loaded);
  });

  test('[R] an unknown per-KPI key survives an edit to the KPI around it', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');

    const written = await page.evaluate(() => {
      const W = (window as any).WIR;
      // Give a KPI a key this build has never heard of, the way a team annotation or a future
      // plugin would, then edit the KPI through the editor and see whether it comes back.
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike')._ticket = 'PERF-441';
      W.document.adopt(doc);
      W.serverKpis.reread();

      W.serverKpis.edit(W.serverKpis._keyOf('lag-spike'));
      W.serverKpis.set('fail.value', '4321');
      W.serverKpis.applyEditor();

      return JSON.parse(W.document.text()).serverMetrics.asserts.find((k: any) => k.id === 'lag-spike');
    });

    expect(written.fail).toEqual({ gt: 4321 });
    expect(written._ticket, 'an unknown per-KPI key was dropped by an edit').toBe('PERF-441');
  });

  test('[R] the block is preserved by registration, not by a hand-kept allow-list',
    async ({ page }) => {
      await loadClean(page, 'E26');

      // The old builder carried a `known` map and copied everything NOT in it verbatim, so a
      // serverMetrics block survived only because nobody had added it to the list — correct
      // output for a reason that would stop being true the moment someone tidied the list.
      const owned = await page.evaluate(() => {
        const slices = (window as any).WIR.document._slices;
        return slices.map((s: any) => ({ name: s.name, keys: s.keys }));
      });

      const names = owned.map((s: any) => s.name);
      expect(names).toContain('asserts');
      expect(names).toContain('server');
      expect(owned.find((s: any) => s.name === 'server').keys).toEqual(['serverMetrics']);
    });
});

test.describe('The document assembler — the unsaved count', () => {
  test('[U] the pill counts an edit to a block the Asserts tab does not own', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');

    await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);

    // Visibility was always a whole-document string compare while the NUMBER counted only
    // rules and notes — so editing any other block produced a pill reading "0 unsaved changes".
    // A note is the cheapest way to reach the count through the assembler.
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'a note that was not there before');
    await page.waitForTimeout(300);
    await switchTab(page, 'asserts');

    const pill = page.locator('#assertsUnsavedPill');
    await expect(pill).toHaveCount(1);
    await expect(pill).toHaveText('1 unsaved change');
  });
});

test.describe('The document assembler — the second author re-reads', () => {
  test('[U] replacing the document updates the Server tab, not just the Asserts tab',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      const before = await page.locator('#serverKpiTable tbody tr.wir-kpi-row').count();
      expect(before).toBeGreaterThan(5);

      // Apply a document whose serverMetrics block declares nothing. The tab used to read the
      // payload once at init and never again, so it went on rendering the ORIGINAL run's KPIs
      // while a DIFFERENT block rode into the next download — with no pill on this tab and
      // nothing on screen saying the two disagreed.
      await page.evaluate(() => {
        const W = (window as any).WIR;
        const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
        doc.serverMetrics = { components: {}, asserts: [] };
        W.document.adopt(doc, 'replacement.json');
        W.serverKpis.reread();
      });

      // ...and says so of the DOCUMENT, not of the run: this run declared twelve, and the header
      // still carries their verdict (R1-6). "This run declared no server metrics" was false here.
      await expect(page.locator('#serverBoard')).toContainText('declare no server asserts');
      await expect(page.locator('#serverBoard')).not.toContainText('This run declared no server metrics');

      // ...and the block that would now be written is the replacement, not the original.
      const written = await page.evaluate(() =>
        JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts.length);
      expect(written).toBe(0);
    });
});

/**
 * The draft lifecycle runs once, after BOTH slices have read the payload (L3-1, C-2, R1-12).
 *
 * It ran inside WIR.asserts.init, before WIR.serverKpis.init. At that moment the server slice
 * did not know its collection yet, so it refused every draft saved against a real one — and no
 * server draft ever survived a reload, which no spec noticed because none reloaded. The same
 * early run cached the baseline with an empty server block, so an UNEDITED page said it had
 * unsaved work the moment anything re-rendered the Asserts tab.
 */
test.describe('Server drafts survive a reload', () => {
  test('[U] a server edit is restored after a reload, and counted', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await editLagSpike(page, '1234');
    expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 1234 });

    await loadDirty(page, 'E26');

    expect((await writtenKpi(page, 'lag-spike')).fail,
      'the server draft was dropped on reload').toEqual({ gt: 1234 });
    const doc = await page.evaluate(() => ({
      count: (window as any).WIR.document.count(),
      drafts: (window as any).WIR.document.hasDrafts(),
    }));
    expect(doc).toEqual({ count: 1, drafts: true });

    await switchTab(page, 'server');
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row.wir-edited')).toHaveCount(1);
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row.wir-edited'))
      .toContainText('lag-spike');

    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change');
  });

  test('[R] an unedited page has no drafts, and opens a file without a conflict',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await loadDirty(page, 'E26');   // a reload with nothing stored

      const doc = await page.evaluate(() => ({
        count: (window as any).WIR.document.count(),
        drafts: (window as any).WIR.document.hasDrafts(),
      }));
      expect(doc, 'an unedited page reports unsaved work').toEqual({ count: 0, drafts: false });

      await switchTab(page, 'asserts');
      // switchTab never re-renders the tab, which is what hid this: the pill and the banner
      // appeared on the NEXT render of anything, reading "0 unsaved changes".
      await page.evaluate(() => (window as any).WIR.asserts.rerender());
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
      await expect(page.locator('#assertsDraftBanner')).toHaveCount(0);

      const loaded = await page.evaluate(() =>
        JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument)));
      loaded.testNotes = 'opened from disk';
      await openFile(page, 'other.json', loaded);

      // Nothing to lose, so nothing to ask: the file loads straight away.
      await expect(page.locator('#assertsFileConflict')).toHaveCount(0);
      await expect(page.locator('#assertsSource')).toContainText('other.json');
    });

  test('[U] KPIs moved into a component added by Edit document… survive a reload', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');

    // R1-12. The record held only the KPIs, so a reload parsed them against the ORIGINAL
    // block: `payments` was undeclared, both KPIs read INVALID, and the Download wrapped them
    // in the original block (then its gates; its components since C1).
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.components.payments = { type: 'custom', description: 'Payments' };
      doc.serverMetrics.asserts.find((k: any) => k.id === 'cpu-headroom').component = 'payments';
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').component = 'payments';
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    expect(await kpiStatus(page, 'cpu-headroom')).not.toBe('INVALID');

    await loadDirty(page, 'E26');

    const after = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
    expect(after.serverMetrics.components.payments, 'the added component was lost on reload')
      .toEqual({ type: 'custom', description: 'Payments' });
    expect(after.serverMetrics.asserts.find((k: any) => k.id === 'cpu-headroom').component)
      .toBe('payments');
    expect(after.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').component)
      .toBe('payments');

    await switchTab(page, 'server');
    expect(await kpiStatus(page, 'cpu-headroom'), 'restored onto a block without its component')
      .not.toBe('INVALID');
    expect(await kpiStatus(page, 'lag-spike')).not.toBe('INVALID');
  });

  test('[U] an opened file is still the document after a reload, and still says so',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'asserts');

      const loaded = await page.evaluate(() =>
        JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument)));
      loaded.samplerNotes = { 'Fast API': 'from the opened file' };
      await openFile(page, 'imported.json', loaded);
      await expect(page.locator('#assertsSource')).toContainText('imported.json');
      // A block no slice owns is an unsaved change like any other. Both slices count 0 here —
      // the rules and the KPIs are the file's — so the pill used to read "0 unsaved changes".
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(1);

      await loadDirty(page, 'E26');
      await switchTab(page, 'asserts');

      const doc = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
      expect(doc.samplerNotes, 'an edit to a block no slice owns was lost on reload')
        .toEqual({ 'Fast API': 'from the opened file' });
      await expect(page.locator('#assertsSource')).toContainText('imported.json');
      await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
    });

  test('[U] a re-collected payload drops the server draft and keeps the rule edit',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await editLagSpike(page, '1234');

      await switchTab(page, 'asserts');
      await page.locator('tr[data-rule-id="load:throughput"] .wir-edit-btn').click();
      await page.fill('#editorfailValue', '0.002');
      await page.click('#editorSave');
      await expect(page.locator('#assertsUnsavedPill')).toHaveText('2 unsaved changes');

      // D12: a server draft belongs to a runId AND a collectionId. Stand in for a payload that
      // was collected again, under the same run.
      await page.evaluate(() => {
        const key = (window as any).WIR.document.draftKey();
        const record = JSON.parse(localStorage.getItem(key)!);
        record.slices.server.collectionId = 'a-previous-collection';
        localStorage.setItem(key, JSON.stringify(record));
      });

      await loadDirty(page, 'E26');

      expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 500 });
      const rule = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
        .assertions.find((a: any) => a.id === 'load:throughput'));
      expect(rule.fail, 'the rule edit was dropped with the server draft').toEqual({ lt: 0.002 });
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(1);
    });

  test('[U] a re-collected payload drops a server edit made through Edit document… too',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'asserts');

      // The twin of the test above, through the other door. The record also stores the
      // document the edit was made ON, and restore re-adopts that before any slice decides —
      // so the server slice dropping its snapshot dropped nothing: the base had already put
      // lag-spike's 4321 back, D12 or not.
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail = { gt: 4321 };
        doc.assertions.find((a: any) => a.id === 'load:throughput').fail = { lt: 0.002 };
        doc.samplerNotes = { 'Fast API': 'kept: not a server block' };
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 4321 });

      await page.evaluate(() => {
        const key = (window as any).WIR.document.draftKey();
        const record = JSON.parse(localStorage.getItem(key)!);
        record.slices.server.collectionId = 'a-previous-collection';
        localStorage.setItem(key, JSON.stringify(record));
      });

      await loadDirty(page, 'E26');

      expect((await writtenKpi(page, 'lag-spike')).fail,
        'the stored base brought back a server edit made against another collection')
        .toEqual({ gt: 500 });
      const doc = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
      expect(doc.serverMetrics).toEqual(await page.evaluate(() =>
        (window as any).globalChartData.annotationsDocument.serverMetrics));
      // Only the server half goes: the rule and the block no slice owns are the tester's.
      expect(doc.assertions.find((a: any) => a.id === 'load:throughput').fail).toEqual({ lt: 0.002 });
      expect(doc.samplerNotes).toEqual({ 'Fast API': 'kept: not a server block' });
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(2);
    });

  test('[U] a stored document is not laid over a report generated with another file',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'asserts');

      const loaded = await page.evaluate(() =>
        JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument)));
      loaded.samplerNotes = { 'Fast API': 'from the opened file' };
      await openFile(page, 'imported.json', loaded);
      await expect(page.locator('#assertsSource')).toContainText('imported.json');

      // The draft key is the runId, which hashes the run and not the annotations — so the main
      // loop (Download, edit the file, -g -o the same JTL) reopens under the same key, and the
      // PREVIOUS file was re-adopted over the one the report had just been generated with.
      // Stand in for that regenerated report by changing what the record says it was made on.
      await page.evaluate(() => {
        const key = (window as any).WIR.document.draftKey();
        const record = JSON.parse(localStorage.getItem(key)!);
        record.shipped = 'a-file-this-report-was-not-generated-with';
        localStorage.setItem(key, JSON.stringify(record));
      });

      await loadDirty(page, 'E26');
      await switchTab(page, 'asserts');

      const doc = await page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
      expect(doc.samplerNotes, 'a stored base was laid over a different shipped file')
        .toBeUndefined();
      await expect(page.locator('#assertsSource')).toHaveCount(0);
      expect(await page.evaluate(() => (window as any).WIR.document.hasDrafts())).toBe(false);
    });
});

/** R1-4 / R3-6: every count on the Asserts tab is the document's, not the Asserts slice's. */
/**
 * F1 (exploratory, 2026-10-02): the file the report was generated from is not an edit.
 *
 * Open file… with test-plans/server-metrics/e26-server.json — the exact file E26 was built from,
 * byte for byte — read "14 unsaved changes", marked every server assert edited, withheld
 * many-instances as NOT RE-JUDGED ("a server assert the build did not judge as written here") and
 * put "→ ? if saved" on the DB badge. Nothing had been edited. isEdited/bakedFor compare the KPI
 * as JSON text, so the file writing `gate` before the bands and Java's re-serialised
 * annotationsDocument writing it after was read as a change to every assert. Only a file the page
 * had itself downloaded round-tripped clean — which is not the file a team keeps in its repo.
 */
test.describe('Opening the file the report was generated from', () => {
  test('[R] F1: Open file… with E26\'s own annotations is no draft, no edited row and no withheld verdict',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      const board = () => page.evaluate(() =>
        Array.from(document.querySelectorAll('#serverKpiTable tbody tr.wir-kpi-row')).map((r) => [
          (r.querySelector('.wir-kpi-name') as HTMLElement).textContent!.trim(),
          (r.querySelector('.wir-chip') as HTMLElement).textContent!.trim(),
        ]));
      const before = await board();
      expect(before.length, 'the board must have drawn before the file is opened').toBeGreaterThan(5);

      await switchTab(page, 'asserts');
      await page.setInputFiles('#assertsFileInput', E26_ANNOTATIONS);
      await expect(page.locator('#assertsSource')).toContainText('e26-server.json');

      // The same file is the same document: nothing to save, so nothing may claim otherwise.
      await expect(page.locator('#assertsUnsavedPill'), 'the report\'s own file read as unsaved changes')
        .toHaveCount(0);
      await expect(page.locator('#assertsDraftBanner')).toHaveCount(0);
      expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);

      await switchTab(page, 'server');
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row.wir-edited'),
        'an unedited assert was marked edited').toHaveCount(0);
      // The board reads exactly as it did before the file was opened — every row, every verdict.
      expect(await board()).toEqual(before);
      const many = page.locator('#serverKpiTable tbody tr.wir-kpi-row')
        .filter({ has: page.locator('.wir-kpi-name:text-is("many-instances")') });
      await expect(many.locator('.wir-chip')).toHaveText('PASS');
      await expect(many).toContainText('from the build');
      await expect(page.locator('#serverBoard .wir-withheld-row')).toHaveCount(0);
      await expect(page.locator('#serverBoard .wir-would-be'), 'a gate badge claimed a save would move it')
        .toHaveCount(0);
    });
});

test.describe('The unsaved count — banners', () => {
  test('[U] a server-only edit is named by the banner and by the file-conflict strip',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await editLagSpike(page, '1234');

      await switchTab(page, 'asserts');
      await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
      await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change');
      // The chip beside it is the CLIENT gate, which a server edit cannot move — so it says so.
      await expect(page.locator('#assertsDraftBanner'))
        .toContainText('Client verdict if saved and re-run');

      await openFile(page, 'other.json', { assertions: [] });
      // It said "would replace 0 unsaved changes" and then offered to discard the server edit.
      await expect(page.locator('#assertsFileConflict')).toContainText('replace 1 unsaved change');
    });

  test('[R] F13: the banner names the server edits and sends them to the Server tab; the verdict stays the client\'s',
    async ({ page }) => {
      // Orchestrator call, 2026-10-02. After six server asserts were disabled and no client rule
      // touched, the banner read "6 unsaved changes · Client verdict if saved and re-run: FAIL" —
      // a count of server edits followed by a verdict that cannot cover them (D24).
      await loadClean(page, 'E26');
      await switchTab(page, 'server');
      await editLagSpike(page, '1234');
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.toggleEnabled(W._keyOf('cpu-headroom'));
      });
      await switchTab(page, 'asserts');
      const banner = page.locator('#assertsDraftBanner');
      await expect(banner).toContainText('2 unsaved changes · server asserts: 2 changed — see the Server tab'
        + ' · Client verdict if saved and re-run: ');
      // No server verdict is folded into the client sentence.
      await expect(banner.locator('#assertsWouldBe')).toHaveCount(1);
      await expect(banner).not.toContainText('Server verdict');

      // A client edit beside them: the total counts both, the server clause only its own.
      await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
      await expect(banner).toContainText('3 unsaved changes · server asserts: 2 changed — see the Server tab');

      // A client-only draft says nothing about the server.
      await page.evaluate(() => (window as any).WIR.asserts.discardDrafts());
      await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
      await expect(banner).toContainText('1 unsaved change · Client verdict if saved and re-run: ');
      await expect(page.locator('#assertsServerDrafts')).toHaveCount(0);
    });

  test('[U] a component added through Edit document… is one unsaved change, everywhere',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'asserts');

      // The server slice counted KPIs by id and nothing around them, so adding only a gate (a
      // component since C1) —
      // the first half of R1-12's own scenario — read {count: 0, drafts: true}: a pill and a
      // banner saying "0 unsaved changes", and a conflict strip that then discarded the edit.
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.components.payments = { type: 'custom', description: 'Payments' };
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      const doc = await page.evaluate(() => ({
        count: (window as any).WIR.document.count(),
        drafts: (window as any).WIR.document.hasDrafts(),
      }));
      expect(doc).toEqual({ count: 1, drafts: true });
      await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
      await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change');

      await openFile(page, 'other.json', { assertions: [] });
      await expect(page.locator('#assertsFileConflict')).toContainText('replace 1 unsaved change');
    });

  test('[U] a reorder is an unsaved change, and the count never reads 0 beside drafts',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'asserts');

      // Reordering changes no entry, so each slice's per-id count was 0 — yet the Download
      // writes a different file, so hasDrafts() was true. One per slice whose block differs.
      const counts = await page.evaluate(() => {
        const W = (window as any).WIR;
        const apply = (mutate: (d: any) => void) => {
          W.asserts.openDocument();
          const d = JSON.parse(W.document.text());
          mutate(d);
          W.asserts.setDocumentText(JSON.stringify(d, null, 2));
          W.asserts.applyDocument();
          return { count: W.document.count(), drafts: W.document.hasDrafts() };
        };
        const kpis = apply((d) => d.serverMetrics.asserts.reverse());
        const both = apply((d) => d.assertions.reverse());
        W.asserts.discardDrafts();
        const discarded = { count: W.document.count(), drafts: W.document.hasDrafts() };
        // A null component is a finding for the mapper, not a refusal of the file — and an edit.
        const frame = apply((d) => { d.serverMetrics.components.spare = null; });
        return { kpis, both, discarded, frame };
      });
      expect(counts.kpis).toEqual({ count: 1, drafts: true });
      expect(counts.both).toEqual({ count: 2, drafts: true });
      expect(counts.discarded).toEqual({ count: 0, drafts: false });
      expect(counts.frame).toEqual({ count: 1, drafts: true });
    });
});

/**
 * R1-8: a document is read by every slice before any of them changes.
 *
 * `"asserts": {}` (then spelled `"kpis"`) passed the Edit document… check, which looked at `assertions` only. Apply then
 * adopted the document and replaced the rules, and threw inside the Server tab's read — leaving
 * the editor open, the Server tab on the old KPIs and a Download that spliced them back in.
 */
test.describe('A serverMetrics block the plugin cannot read', () => {
  test('[U] Edit document… refuses it and names the key', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');

    const before = await page.evaluate(() => (window as any).WIR.document.text());
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts = {};
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    });

    await expect(page.locator('#docEditorApply')).toBeDisabled();
    await expect(page.locator('#docEditorProblem')).toContainText('serverMetrics.asserts');

    // Applying anyway is a no-op, not a half-applied document.
    await expectNoJsErrors(page, async () => {
      await page.evaluate(() => (window as any).WIR.asserts.applyDocument());
    });
    expect(await page.evaluate(() => (window as any).WIR.document.text())).toBe(before);
  });

  test('[U] Open file… refuses it out loud and changes nothing', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    const before = await page.evaluate(() => (window as any).WIR.document.text());
    const rows = await page.locator('#serverKpiTable tbody tr.wir-kpi-row').count();

    const doc = JSON.parse(before);
    doc.serverMetrics.asserts = [null];
    await expectNoJsErrors(page, async () => {
      await openFile(page, 'broken-server.json', doc);
      await expect(page.locator('#wir-toast'))
        .toContainText('broken-server.json is not a usable annotations file');
    });
    await expect(page.locator('#wir-toast')).toContainText('asserts[0]');

    // Refused, not held back: there are no drafts for it to conflict with.
    await expect(page.locator('#assertsFileConflict')).toHaveCount(0);
    await expect(page.locator('#assertsSource')).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).WIR.document.text())).toBe(before);
    await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(rows);
  });
});

/**
 * C-1: an unreadable band is never saved away.
 *
 * The parsed KPI has nowhere to hold a band it could not read, and kpiToJson wrote bands from
 * the model only — so a no-op Download turned "one readable band beside one unreadable one", an
 * INVALID_RULE and exit 1, into a KPI judged on warn alone that can never FAIL. The pure half is
 * pinned in test-plans/js-unit/server-document.test.js; this is the page.
 */
test.describe('An unreadable band', () => {
  test('[U] survives a no-op save, and the editor refuses to save it away unseen',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      await page.evaluate(() => {
        const W = (window as any).WIR;
        const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
        doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail = { gtt: 900 };
        W.document.adopt(doc);
        W.serverKpis.reread();
      });

      const noOp = await writtenKpi(page, 'lag-spike');
      expect(noOp.warn).toEqual({ gt: 300 });
      expect(noOp.fail, 'a no-op save dropped the unreadable fail band').toEqual({ gtt: 900 });

      // The form shows an empty Fail field, and "empty" means "no band" on Save. Saving an
      // unrelated change must not take the band with it.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('lag-spike'));
        W.set('description', 'touched something else');
        W.applyEditor();
      });
      await expect(page.locator('#serverEditor .wir-problem'))
        .toContainText("fail operator 'gtt' is not one of gt, gte, lt, lte");
      expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gtt: 900 });

      // Writing the band replaces it.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.set('fail.value', '700');
        W.applyEditor();
      });
      expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 700 });
    });

  test('[U] an unreadable reduce or across is not repaired by an unrelated edit',
    async ({ page }) => {
      await loadClean(page, 'E26');
      await switchTab(page, 'server');

      await page.evaluate(() => {
        const W = (window as any).WIR;
        const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
        doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').reduce = { kind: 'maxx' };
        doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-p50-warns').across = 'middle';
        W.document.adopt(doc);
        W.serverKpis.reread();
      });

      // The selects defaulted to 'max', so Save wrote {"kind": "max"} and "across": "max" — a
      // KPI Java refuses (INVALID_RULE, exit 1) became one it judges, and the form said nothing.
      for (const [id, sentence] of [
        ['lag-spike', "reduce.kind 'maxx' is not a reducer"],
        // Across.vocabulary() since SF2 added min, in Java's order.
        ['lag-p50-warns', "across 'middle' is not one of max, min, avg"],
      ]) {
        await page.evaluate((kpiId) => {
          const W = (window as any).WIR.serverKpis;
          W.edit(W._keyOf(kpiId));
          W.set('description', 'touched something else');
          W.applyEditor();
        }, id);
        await expect(page.locator('#serverEditor .wir-problem')).toContainText(sentence);
        await expect(page.locator('#serverEditor button.wir-btn-primary')).toBeDisabled();
        await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());
      }
      expect((await writtenKpi(page, 'lag-spike')).reduce).toEqual({ kind: 'maxx' });
      expect((await writtenKpi(page, 'lag-p50-warns')).across).toBe('middle');

      // The select shows no value rather than claiming 'max', so choosing max is a change.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('lag-spike'));
      });
      expect(await page.locator('#serverEditor select[data-field="kind"]').inputValue()).toBe('');
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.set('kind', 'max');
        W.applyEditor();
      });
      expect((await writtenKpi(page, 'lag-spike')).reduce).toEqual({ kind: 'max' });

      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('lag-p50-warns'));
        W.set('across', 'avg');
        W.applyEditor();
      });
      expect((await writtenKpi(page, 'lag-p50-warns')).across).toBe('avg');
    });
});

/**
 * B4 (CN18): only × releases a carried band.
 *
 * The test above edits through set(); this one works the controls a reader works. The editor's
 * empty box over a band the run could not read means the file's band, and setField released it on
 * ANY touch of that band's controls: pick another operator over the empty box, or type a number and
 * clear it, and Save was offered, the Download dropped the band, and the next run went from
 * INVALID_RULE and exit 1 to WARN or PASS and exit 0 — warn bands too. Now the box keeps the file's
 * band until a number the page reads replaces it, the band is shown under its row, and × — the
 * client editor's control since Batch K — is the one way to remove it. The pure half, every refused
 * shape and every touch, is test-plans/js-unit/server-write-back.test.js.
 */
test.describe('An unreadable band in the editor: only × releases it (B4)', () => {
  /** E26's committed file with an unreadable fail on lag-spike and an unreadable warn on lag-p50-warns. */
  function unreadableFile(): any {
    const doc = JSON.parse(fs.readFileSync(E26_ANNOTATIONS, 'utf8'));
    const kpi = (id: string) => doc.serverMetrics.asserts.find((k: any) => k.id === id);
    kpi('lag-spike').fail = { gtt: 500 };
    kpi('lag-p50-warns').warn = { gt: 100, lte: 1 };
    return doc;
  }

  const CARRIED = [
    { id: 'lag-spike', which: 'fail', band: { gtt: 500 }, other: ['warn', { gt: 300 }],
      sentence: "fail operator 'gtt' is not one of gt, gte, lt, lte" },
    { id: 'lag-p50-warns', which: 'warn', band: { gt: 100, lte: 1 }, other: ['fail', { gt: 5000 }],
      sentence: 'warn has 2 operators (gt, lte)' },
  ];

  async function openUnreadable(page: Page): Promise<void> {
    await loadClean(page, 'E26');
    await openFile(page, 'unreadable-bands.json', unreadableFile());
    await expect(page.locator('#assertsSource')).toContainText('unreadable-bands.json');
    await switchTab(page, 'server');
  }

  /** The row's own Edit button, found by the row's key. */
  async function editRow(page: Page, id: string): Promise<void> {
    const key = await page.evaluate((kpiId) => (window as any).WIR.serverKpis._keyOf(kpiId), id);
    await page.locator(`#serverKpiTable tbody tr.wir-kpi-row button[onclick*="edit('${key}')"]`).click();
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(1);
  }

  /** The editor refusing for the carried band, with no verdict claimed over it. */
  async function refusing(page: Page, sentence: string, why: string): Promise<void> {
    await expect(page.locator('#serverEditorSave'), why + ': Save was offered').toBeDisabled();
    await expect(page.locator('#serverEditorProblem'), why).toContainText(sentence);
    await expect(page.locator('#serverEditorVerdict'), why + ': "If saved" claimed a verdict over the file\'s band')
      .toContainText('Not judged until it can be saved');
    await expect(page.locator('#serverEditorVerdict .wir-chip')).toHaveCount(0);
  }

  async function download(page: Page): Promise<any> {
    const [file] = await Promise.all([page.waitForEvent('download'), page.click('#serverSaveBtn')]);
    return JSON.parse(fs.readFileSync((await file.path())!, 'utf-8'));
  }

  for (const c of CARRIED) {
    test(`[U] B4: an operator picked over the empty ${c.which} box keeps the file's band, and Save stays refused`,
      async ({ page }) => {
        await openUnreadable(page);
        await editRow(page, c.id);
        await expect(page.locator(`#serverEditor-${c.which}-value`)).toHaveValue('');
        await refusing(page, c.sentence, 'opened');

        for (const op of ['lt', 'gte']) {
          await page.selectOption(`#serverEditor-${c.which}-op`, op);
          await refusing(page, c.sentence, `${op} picked over the empty box`);
        }
        // What will be kept is on screen, beside the one control that removes it, and the box no
        // longer says that leaving it empty means no band. The box names the note, so a screen
        // reader tabbing through the row hears what an empty box keeps.
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText(JSON.stringify(c.band));
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText(`The file's ${c.which} band is kept`);
        await expect(page.locator(`#serverEditor-${c.which}-remove`)).toBeVisible();
        await expect(page.locator(`#serverEditor-${c.which}-remove`))
          .toHaveAttribute('aria-label', `Remove the ${c.which} band the file wrote`);
        // RB10: a 24×24 target (WCAG 2.2 2.5.8), the glyph's own 10×19 box before, and no taller
        // than the box beside it, so the row does not grow.
        const remove = (await page.locator(`#serverEditor-${c.which}-remove`).boundingBox())!;
        const value = (await page.locator(`#serverEditor-${c.which}-value`).boundingBox())!;
        expect(remove.width, 'the × is narrower than 24 px').toBeGreaterThanOrEqual(24);
        expect(remove.height, 'the × is shorter than 24 px').toBeGreaterThanOrEqual(24);
        expect(remove.height, 'the × made its row taller than its value box').toBeLessThanOrEqual(value.height);
        await expect(page.locator(`#serverEditor-${c.which}-value`)).toHaveAttribute('placeholder', 'the file\'s band');
        await expect(page.locator(`#serverEditor-${c.which}-value`))
          .toHaveAttribute('aria-describedby', `serverEditor-${c.which}-carried`);
        // The other band was read, so it has none of them.
        await expect(page.locator(`#serverEditor-${c.other[0]}-remove`)).toHaveCount(0);
        await expect(page.locator(`#serverEditor-${c.other[0]}-carried`)).toHaveCount(0);
        await expect(page.locator(`#serverEditor-${c.other[0]}-value`)).toHaveAttribute('placeholder', 'leave empty for none');
        await expect(page.locator(`#serverEditor-${c.other[0]}-value`)).not.toHaveAttribute('aria-describedby');

        await page.click('#serverEditorCancel');
        const written = (await download(page)).serverMetrics.asserts.find((k: any) => k.id === c.id);
        expect(written[c.which], 'the Download dropped the file\'s band').toEqual(c.band);
        expect(written[c.other[0] as string]).toEqual(c.other[1]);
      });

    test(`[U] B4: a number typed into the ${c.which} box and cleared again leaves the file's band`,
      async ({ page }) => {
        await openUnreadable(page);
        await editRow(page, c.id);
        const box = page.locator(`#serverEditor-${c.which}-value`);
        await box.click();
        await page.keyboard.type(c.which === 'fail' ? '900' : '150');
        // A number the page reads replaces the band while it is in the box, and is judged.
        await expect(page.locator('#serverEditorSave')).toBeEnabled();
        await expect(page.locator('#serverEditorVerdict .wir-chip')).toHaveCount(1);
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText('while it holds a value');
        // × there takes the typed number with it, and the note says so rather than "remove it".
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText(`× for no ${c.which} band`);
        for (let i = 0; i < 3; i++) await page.keyboard.press('Backspace');
        await expect(box).toHaveValue('');
        await expect(box, 'the typing lost its box').toBeFocused();
        await refusing(page, c.sentence, 'typed and cleared');
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText(JSON.stringify(c.band));
        await expect(page.locator(`#serverEditor-${c.which}-carried`)).toContainText(`The file's ${c.which} band is kept`);

        // Nothing a caller can do writes it away: the Save refuses whoever asks.
        await page.evaluate(() => (window as any).WIR.serverKpis.applyEditor());
        await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(1);
        expect((await writtenKpi(page, c.id))[c.which]).toEqual(c.band);
      });
  }

  test('[U] B4: × removes the file\'s band; Save then writes the band the form declares, or none', async ({ page }) => {
    await openUnreadable(page);

    // lag-spike: × on the fail band, the box left empty — no fail band at all, the warn kept.
    await editRow(page, 'lag-spike');
    await page.click('#serverEditor-fail-remove');
    await expect(page.locator('#serverEditor-fail-carried')).toHaveCount(0);
    await expect(page.locator('#serverEditor-fail-remove')).toHaveCount(0);
    await expect(page.locator('#serverEditor-fail-value'), '× sent focus away from the band it removed').toBeFocused();
    await expect(page.locator('#serverEditorProblem')).toHaveCount(0);
    await expect(page.locator('#serverEditorVerdict .wir-chip'), 'with the band gone the form is judged').toHaveCount(1);
    await page.click('#serverEditorSave');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    const spike = await writtenKpi(page, 'lag-spike');
    expect(spike.fail, 'the author removed it').toBeUndefined();
    expect(spike.warn).toEqual({ gt: 300 });

    // lag-p50-warns: × on the warn band from the keyboard — Tab from the box reaches it, Enter
    // presses it, focus comes back to the box — then a warn band typed, and that band is written.
    await editRow(page, 'lag-p50-warns');
    await page.locator('#serverEditor-warn-value').focus();
    await page.keyboard.press('Tab');
    await expect(page.locator('#serverEditor-warn-remove'), 'Tab from the box did not reach its ×').toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('#serverEditor-warn-carried')).toHaveCount(0);
    await expect(page.locator('#serverEditor-warn-value'), '× sent focus away from the band it removed').toBeFocused();
    await page.keyboard.type('150');
    await page.click('#serverEditorSave');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    const p50 = await writtenKpi(page, 'lag-p50-warns');
    expect(p50.warn).toEqual({ gt: 150 });
    expect(p50.fail).toEqual({ gt: 5000 });
  });

  test('[U] B4: an editor opened and closed with nothing touched leaves both bands verbatim in the Download',
    async ({ page }) => {
      await openUnreadable(page);
      for (const c of CARRIED) {
        await editRow(page, c.id);
        await refusing(page, c.sentence, 'untouched');
        await page.keyboard.press('Escape');
        await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
      }
      const doc = await download(page);
      for (const c of CARRIED) {
        expect(doc.serverMetrics.asserts.find((k: any) => k.id === c.id)[c.which]).toEqual(c.band);
      }
    });

  test('[U] B4: a catalogue assert\'s carried band is kept the same way, and × removes it (E27)', async ({ page }) => {
    const file = path.resolve(__dirname, '..', '..', 'server-catalogue', 'e27-server.json');
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    const lag = doc.serverMetrics.asserts.find((k: any) => k.metric === 'consumer.lag.max');
    lag.fail = { gt: '2k' };
    await loadClean(page, 'E27');
    await openFile(page, 'unreadable-catalogue.json', doc);
    await expect(page.locator('#assertsSource')).toContainText('unreadable-catalogue.json');
    await switchTab(page, 'server');

    const key = await page.evaluate(() => (window as any).WIR.serverKpis._state.declaredKpis
      .find((k: any) => k.metric === 'consumer.lag.max').key);
    const written = async () => (await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
      .serverMetrics.asserts.find((k: any) => k.metric === 'consumer.lag.max')));
    await page.locator(`#serverKpiTable tbody tr.wir-kpi-row button[onclick*="edit('${key}')"]`).click();
    const sentence = 'fail gt must be a number, not the text "2k"';
    await refusing(page, sentence, 'opened');
    await page.selectOption('#serverEditor-fail-op', 'lte');
    await refusing(page, sentence, 'an operator picked');
    await page.click('#serverEditor-fail-remove');
    await page.click('#serverEditorSave');
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    const saved = await written();
    expect(saved.fail).toBeUndefined();
    expect(saved.warn).toEqual({ gt: 500 });
    expect(saved.metric).toBe('consumer.lag.max');
  });
});

/**
 * S1 (CN19): a document is read as documentOf ships it. GatePayloadBuilder.documentOf writes an
 * overflowed band as the text "Infinity"; the file the team keeps holds the bare 1e400 Jackson
 * overflowed, which JSON.parse hands over as Infinity. The Server tab carried that number, so its
 * Download wrote {"gt": null} and opening the very file the report was generated from read as a
 * change. No fixture's file declares a 1e400, so the report generated from one is a copy of E26
 * whose annotationsDocument says what documentOf says for it.
 */
test.describe('A bare 1e400 in an opened file (S1)', () => {
  test('[U] S1: opening the report\'s own file, which writes lag-spike\'s fail as a bare 1e400, is no change, and the Download writes "Infinity"',
    async ({ page }, testInfo) => {
      const html = fs.readFileSync(path.resolve(__dirname, '..', '..', '..', 'test-results', 'E26', 'E26-report.html'), 'utf-8');
      const shipped = '\\"fail\\":{\\"gt\\":500}';
      expect(html.split(shipped).length - 1, 'lag-spike\'s fail band is in the shipped document once').toBe(1);
      const dir = testInfo.outputPath('overflowed');
      fs.mkdirSync(dir, { recursive: true });
      const copy = path.join(dir, 'E26-report.html');
      fs.writeFileSync(copy, html.replace(shipped, '\\"fail\\":{\\"gt\\":\\"Infinity\\"}'));
      await page.goto('file://' + copy, { waitUntil: 'networkidle' });

      const committed = JSON.parse(fs.readFileSync(E26_ANNOTATIONS, 'utf8'));
      committed.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail = { gt: '__BARE__' };
      // JSON.stringify cannot write 1e400, so the literal goes into the text by hand.
      const text = JSON.stringify(committed, null, 2).replace('"__BARE__"', '1e400');
      expect(text).toContain('"gt": 1e400');
      await page.setInputFiles('#assertsFileInput', { name: 'e26-overflow.json', mimeType: 'application/json',
        buffer: Buffer.from(text) });
      await expect(page.locator('#assertsSource')).toContainText('e26-overflow.json');

      expect(await page.evaluate(() => (window as any).WIR.document.count()),
        'the report\'s own file read as an unsaved change').toBe(0);
      await expect(page.locator('#assertsUnsavedPill')).toHaveCount(0);
      await switchTab(page, 'server');
      await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row.wir-edited')).toHaveCount(0);

      const [file] = await Promise.all([page.waitForEvent('download'), page.click('#serverSaveBtn')]);
      const doc = JSON.parse(fs.readFileSync((await file.path())!, 'utf-8'));
      expect(doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail,
        'written as null, the next run refuses it as "not null"').toEqual({ gt: 'Infinity' });
    });

  test('[U] S1: a bare 1e400 outside any assert — a component\'s description, a key nobody models in the block, in a client rule and in the document — is no change either, and is written as "Infinity"',
    async ({ page }, testInfo) => {
      // The test above holds an assert. The rest of the document was adopted as JSON.parse handed it
      // over, so each of these was downloaded as null and the report's own file read as a change.
      const html = fs.readFileSync(path.resolve(__dirname, '..', '..', '..', 'test-results', 'E26', 'E26-report.html'), 'utf-8');
      const splices: [string, string][] = [
        ['\\"kafka\\":{\\"type\\":\\"custom\\",\\"description\\":\\"Kafka\\"}',
          '\\"kafka\\":{\\"type\\":\\"custom\\",\\"description\\":\\"Infinity\\"}'],
        ['serverMetrics\\":{\\"components\\":{\\"kafka\\"',
          'serverMetrics\\":{\\"_ceiling\\":\\"Infinity\\",\\"components\\":{\\"kafka\\"'],
        ['annotationsDocument\\":{\\"version\\":\\"1.0\\",\\"testNotes\\":\\"\\",\\"assertions\\":[{\\"id\\":\\"load:throughput\\",',
          'annotationsDocument\\":{\\"_ceiling\\":\\"Infinity\\",\\"version\\":\\"1.0\\",\\"testNotes\\":\\"\\",'
            + '\\"assertions\\":[{\\"id\\":\\"load:throughput\\",\\"_limit\\":\\"Infinity\\",'],
      ];
      let copyHtml = html;
      for (const [from, to] of splices) {
        expect(copyHtml.split(from).length - 1, 'the shipped document holds ' + from + ' once').toBe(1);
        copyHtml = copyHtml.replace(from, to);
      }
      const dir = testInfo.outputPath('overflowed-elsewhere');
      fs.mkdirSync(dir, { recursive: true });
      const copy = path.join(dir, 'E26-report.html');
      fs.writeFileSync(copy, copyHtml);
      await page.goto('file://' + copy, { waitUntil: 'networkidle' });
      expect(await page.evaluate(() => (window as any).WIR.document.count()), 'precondition: the copy is unedited').toBe(0);

      const committed = JSON.parse(fs.readFileSync(E26_ANNOTATIONS, 'utf8'));
      committed._ceiling = '__BARE__';
      committed.assertions.find((a: any) => a.id === 'load:throughput')._limit = '__BARE__';
      committed.serverMetrics.components.kafka.description = '__BARE__';
      committed.serverMetrics._ceiling = '__BARE__';
      // JSON.stringify cannot write 1e400, so the literal goes into the text by hand.
      const text = JSON.stringify(committed, null, 2).split('"__BARE__"').join('1e400');
      expect(text.split('1e400').length - 1).toBe(4);
      await page.setInputFiles('#assertsFileInput', { name: 'e26-overflow-elsewhere.json', mimeType: 'application/json',
        buffer: Buffer.from(text) });
      await expect(page.locator('#assertsSource')).toContainText('e26-overflow-elsewhere.json');

      expect(await page.evaluate(() => (window as any).WIR.document.count()),
        'the report\'s own file read as an unsaved change').toBe(0);
      await switchTab(page, 'server');
      await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);

      const [file] = await Promise.all([page.waitForEvent('download'), page.click('#serverSaveBtn')]);
      const doc = JSON.parse(fs.readFileSync((await file.path())!, 'utf-8'));
      expect([doc._ceiling, doc.assertions.find((a: any) => a.id === 'load:throughput')._limit,
        doc.serverMetrics.components.kafka.description, doc.serverMetrics._ceiling],
        'written as null where documentOf writes "Infinity"').toEqual(['Infinity', 'Infinity', 'Infinity', 'Infinity']);
    });
});

/**
 * SF45: the document's edges, where a count or a draft went unsaid. The Server tab's pill did not
 * follow a note; a file with no "version" key — every server-era file the repository keeps —
 * counted the absent key as a change; a restored server draft was announced only on the Asserts
 * tab; and server edits made on the interim report were dropped on the step-3 report without a
 * word while the client half of the same record came back (the drop is D12's and stays).
 */
test.describe('The document\'s edges (SF45)', () => {
  test('[U] SF45: a note typed on the Notes tab shows on the Server tab\'s pill, and is counted with a server edit', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'only a note');
    await page.waitForTimeout(300);
    await switchTab(page, 'server');
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');

    // V8's step 5: a server edit, then the note cleared — the pill read "2 unsaved changes".
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggleEnabled(W._keyOf('cpu-headroom'));
    });
    await expect(page.locator('#serverUnsavedPill')).toHaveText('2 unsaved changes');
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', '');
    await page.waitForTimeout(300);
    await switchTab(page, 'server');
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
  });

  test('[U] SF45: opening the committed file with one band changed reads 1 unsaved change, however it arrived', async ({ page }) => {
    const committed = JSON.parse(fs.readFileSync(E26_ANNOTATIONS, 'utf8'));
    expect(committed, 'the committed file writes no version — the case this pins').not.toHaveProperty('version');
    committed.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').fail = { gt: 1234 };

    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    await openFile(page, 'onlyband.json', committed);
    await expect(page.locator('#assertsSource')).toContainText('onlyband.json');
    // It read "2 unsaved changes · server asserts: 1 changed": the absent version, as a second edit.
    await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change · server asserts: 1 changed');
    await switchTab(page, 'server');
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');

    // A version that IS changed is still one.
    committed.version = '2.0';
    await switchTab(page, 'asserts');
    await page.evaluate(() => (window as any).WIR.asserts.discardDrafts());
    await openFile(page, 'versioned.json', committed);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(2);
  });

  test('[U] SF45: a restored server draft is announced on the Server tab, with the document\'s Discard', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    // Made in this session: nothing was restored, so nothing to announce.
    await editLagSpike(page, '1234');
    await expect(page.locator('#serverDraftBanner')).toHaveCount(0);

    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    const banner = page.locator('#serverDraftBanner');
    await expect(banner).toContainText('Draft restored — 1 server-assert change to this report was kept in this '
      + 'browser and is not downloaded yet.');
    // The same Discard as the Asserts tab's, with its Undo: one document, one record.
    await page.click('#serverDiscardDraftsBtn');
    expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 500 });
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    await expect(banner).toHaveCount(0);
    await expect(page.locator('#wir-undo-toast')).toContainText('Discarded 1 unsaved change');
    await page.click('#wirUndoBtn');
    expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 1234 });

    // A client-only draft restores nothing on this tab, so it announces nothing here.
    await loadClean(page, 'E26');
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(page.locator('#serverDraftBanner')).toHaveCount(0);
  });

  test('[U] SF45: a server draft made on the interim report is not restored on the step-3 report, and both tabs say so', async ({ page }) => {
    // V8 reproduced it with a live listener run; the record is what matters, so it is made here: a
    // server edit and a note, stored under E26's run, with the server snapshot marked as taken on
    // the interim report — nothing collected, the sentinel collection 'uncollected'.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await editLagSpike(page, '41');
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'note typed on the interim');
    await page.waitForTimeout(300);
    await page.evaluate(() => {
      const key = (window as any).WIR.document.draftKey();
      const record = JSON.parse(localStorage.getItem(key)!);
      record.slices.server.collectionId = 'uncollected';
      localStorage.setItem(key, JSON.stringify(record));
    });

    await loadDirty(page, 'E26');
    // D12 holds: the server half is not restored; the client half is.
    expect((await writtenKpi(page, 'lag-spike')).fail).toEqual({ gt: 500 });
    expect(await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).testNotes))
      .toBe('note typed on the interim');
    expect(await page.evaluate(() => [(window as any).WIR.document.count(),
      (window as any).WIR.document.countOf('server')])).toEqual([1, 0]);

    await switchTab(page, 'server');
    const server = page.locator('#serverDroppedDrafts');
    await expect(server).toContainText('1 server-assert change saved in this browser was not restored: it was made '
      + 'on this run’s interim report, before anything was collected, and a server draft holds only for the '
      + 'collection it was made against');
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsDraftBanner')).toContainText('1 unsaved change');
    const asserts = page.locator('#assertsDroppedDrafts');
    await expect(asserts).toContainText('1 server-assert change saved in this browser was not restored — see the Server tab.');

    // Dismissed on one tab, gone from both — for this page; the record is left alone.
    const storedServer = () => page.evaluate(() => {
      const record = JSON.parse(localStorage.getItem((window as any).WIR.document.draftKey())!);
      const s = record.slices.server;
      return { collectionId: s.collectionId,
        fail: s.block.asserts.find((k: any) => k.id === 'lag-spike').fail };
    });
    await page.click('#assertsDismissDropped');
    await expect(asserts).toHaveCount(0);
    await switchTab(page, 'server');
    await expect(server).toHaveCount(0);
    expect(await storedServer(), 'Dismiss left the record alone').toEqual({ collectionId: 'uncollected', fail: { gt: 41 } });
    // ...so a reload still finds them, and says so again.
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(server).toHaveCount(1);

    // An edit here that is not a server edit — a note — keeps them saved. persist() rebuilt the
    // record from this page's own slices and wrote its unedited server block over the interim's:
    // the edit was gone for good while the notice still said it was saved in this browser.
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', 'note typed on the interim, and more on step 3');
    await page.waitForTimeout(300);
    expect(await storedServer(), 'a note on step 3 replaced the interim\'s server edit')
      .toEqual({ collectionId: 'uncollected', fail: { gt: 41 } });
    await switchTab(page, 'server');
    await expect(server, 'and the notice says what holds').toContainText('still saved for that report: open it and download the file there. The first server-assert edit on this page replaces it.');
    await expect(server).toContainText('Discard drafts and Dismiss leave it saved.');
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(server).toHaveCount(1);

    // A server edit here is what replaces them — the notice said so — and then it says nothing.
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggleEnabled(W._keyOf('cpu-headroom'));
    });
    const after = await storedServer();
    expect(after.collectionId, 'this page\'s own server edit is filed under its collection').not.toBe('uncollected');
    expect(after.fail).toEqual({ gt: 500 });
    await expect(server).toHaveCount(0);
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(0);

    // A record whose stale server snapshot held no edit — a client-only draft — drops nothing.
    await loadClean(page, 'E26');
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
    await page.evaluate(() => {
      const key = (window as any).WIR.document.draftKey();
      const record = JSON.parse(localStorage.getItem(key)!);
      record.slices.server.collectionId = 'uncollected';
      localStorage.setItem(key, JSON.stringify(record));
    });
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(server).toHaveCount(0);
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(0);
  });
});

/** What the interim report's draft looked like when it was stored, as holdingInterimDraft plants it. */
const HELD = { collectionId: 'uncollected', fail: { gt: 41 } };

/**
 * The stored record's server half — the collection it was made against, and lag-spike's fail band
 * (null when the stored block has no lag-spike: a component Delete takes it) — or null when there is
 * no record, or no server half. A missing record is an answer, not a TypeError.
 */
async function storedInterim(page: Page): Promise<{ collectionId: string, fail: any } | null> {
  return page.evaluate(() => {
    const raw = localStorage.getItem((window as any).WIR.document.draftKey());
    const s = raw ? JSON.parse(raw).slices.server : null;
    if (!s) return null;
    const lag = s.block && Array.isArray(s.block.asserts) ? s.block.asserts.find((k: any) => k.id === 'lag-spike') : null;
    return { collectionId: s.collectionId, fail: lag ? lag.fail : null };
  });
}

/**
 * E26 opened as the step-3 report over a record whose server half was made on the interim report:
 * lag-spike's fail band 41, the server snapshot marked 'uncollected' (and a note, when asked for,
 * which is a client draft this page DOES restore). As the SF45 test above plants it.
 */
async function holdingInterimDraft(page: Page, note?: string): Promise<void> {
  await loadClean(page, 'E26');
  await switchTab(page, 'server');
  await editLagSpike(page, '41');
  if (note !== undefined) {
    await switchTab(page, 'notes');
    await page.fill('#notesTextarea', note);
    await page.waitForTimeout(300);
  }
  await page.evaluate(() => {
    const key = (window as any).WIR.document.draftKey();
    const record = JSON.parse(localStorage.getItem(key)!);
    record.slices.server.collectionId = 'uncollected';
    localStorage.setItem(key, JSON.stringify(record));
  });
  await loadDirty(page, 'E26');
  await switchTab(page, 'server');
  await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);
  expect(await storedInterim(page)).toEqual(HELD);
}

/**
 * F1 (review of 3.1.37). The first server-assert edit on the step-3 page replaces what the record held
 * for the interim report — by design, and the notice says so. Its Undo did not hold it again: the
 * capture swapped the held snapshot in for this page's own, the server slice refused it as stale, and
 * the Undo's persist then wrote this page's block over the interim's for good. A server-assert Delete
 * stayed deleted; the two component Undos, which put the document back rather than a record, removed
 * the interim draft without a word.
 */
test.describe('An Undo puts back what the record held for the interim report (F1)', () => {
  test('[U] F1: Undo of a server-assert Delete restores the assert and the interim report\'s draft, and a reload keeps both', async ({ page }) => {
    await holdingInterimDraft(page);
    await page.evaluate(() => { const W = (window as any).WIR.serverKpis; W.remove(W._keyOf('cpu-headroom')); });
    expect(await writtenKpi(page, 'cpu-headroom')).toBeUndefined();
    // The notice said the first server-assert edit on this page replaces it, and it did.
    expect((await storedInterim(page))!.collectionId).not.toBe('uncollected');
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(0);

    await page.click('#wirUndoBtn');
    expect(await writtenKpi(page, 'cpu-headroom'), 'the Undo put the assert back').toBeDefined();
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(0);
    expect(await storedInterim(page), 'the Undo put the interim report\'s draft back in the record').toEqual(HELD);
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);

    await loadDirty(page, 'E26');
    expect(await writtenKpi(page, 'cpu-headroom')).toBeDefined();
    expect(await storedInterim(page)).toEqual(HELD);
    await switchTab(page, 'server');
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);
  });

  test('[U] F1: Undo of a component Delete keeps the interim report\'s draft', async ({ page }) => {
    await holdingInterimDraft(page);
    await page.evaluate(() => (window as any).WIR.serverKpis.deleteComponent('kafka'));
    expect(await writtenKpi(page, 'lag-spike')).toBeUndefined();
    expect((await storedInterim(page))?.collectionId).not.toBe('uncollected');

    await page.click('#wirUndoBtn');
    expect(await writtenKpi(page, 'lag-spike')).toBeDefined();
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(0);
    expect(await storedInterim(page), 'the component Undo removed the interim report\'s draft').toEqual(HELD);
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);

    await loadDirty(page, 'E26');
    expect(await writtenKpi(page, 'lag-spike')).toBeDefined();
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(0);
    expect(await storedInterim(page)).toEqual(HELD);
    await switchTab(page, 'server');
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);
  });

  test('[U] F1: Undo of the empty component + Add component left keeps the interim report\'s draft', async ({ page }) => {
    await holdingInterimDraft(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.addComponent('postgres');
      W.dialogSet('id', 'fresh-db');
      W.dialogAddComponent();
      W.closeDialog();
    });
    await expect(page.locator('#wir-undo-toast.visible')).toContainText('Component "fresh-db" has no server assert yet');
    await page.click('#wirUndoBtn');
    expect(await page.evaluate(() => Object.keys(JSON.parse((window as any).WIR.document.text())
      .serverMetrics.components))).not.toContain('fresh-db');
    expect(await storedInterim(page), 'the empty-component Undo removed the interim report\'s draft').toEqual(HELD);
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);

    await loadDirty(page, 'E26');
    expect(await page.evaluate(() => Object.keys(JSON.parse((window as any).WIR.document.text())
      .serverMetrics.components))).not.toContain('fresh-db');
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(0);
    expect(await storedInterim(page)).toEqual(HELD);
    await switchTab(page, 'server');
    await expect(page.locator('#serverDroppedDrafts')).toHaveCount(1);
  });
});

/**
 * F9 (review of 3.1.37). Discard drafts on the step-3 report deleted the stored record whole — the
 * interim report's server draft with it — while the notice beside the button said that draft was
 * still saved for that report. It is not this page's draft: D12's to drop HERE, never the record's.
 * Discard now drops this page's own slices and writes the held snapshot back, so the notice stays
 * true and the toast's count ("Discarded 1 unsaved change") is exact.
 */
test.describe('Discard drafts keeps what the record holds for another collection (F9)', () => {
  test('[U] F9: Discard drafts on the step-3 report keeps the interim report\'s server draft, and says so', async ({ page }) => {
    await holdingInterimDraft(page, 'note typed on the interim');
    await switchTab(page, 'asserts');
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(1);
    await page.click('#discardDraftsBtn');

    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toContainText('Discarded 1 unsaved change');
    await expect(toast).not.toContainText('changes');
    expect(await storedInterim(page), 'Discard deleted the interim report\'s draft').toEqual(HELD);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(1);
    await switchTab(page, 'server');
    const notice = page.locator('#serverDroppedDrafts');
    await expect(notice).toHaveCount(1);
    await expect(notice).toContainText('Discard drafts and Dismiss leave it saved.');

    // The Undo brings the discarded note back, and the held draft stays where it was.
    await page.click('#wirUndoBtn');
    expect(await page.evaluate(() => JSON.parse((window as any).WIR.document.text()).testNotes))
      .toBe('note typed on the interim');
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(1);
    expect(await storedInterim(page)).toEqual(HELD);

    // A reload still finds it, and says so again.
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(notice).toHaveCount(1);

    // Dismissed first, then Discard: the record still keeps it, and the notice stays dismissed here.
    await switchTab(page, 'asserts');
    await page.click('#assertsDismissDropped');
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(0);
    await page.click('#discardDraftsBtn');
    expect(await storedInterim(page), 'Dismiss then Discard deleted the interim report\'s draft').toEqual(HELD);
    await expect(page.locator('#assertsDroppedDrafts')).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    await expect(notice).toHaveCount(1);
  });

  test('[U] F9: Discard forgets what the reload restored, so an edit made after it is not announced as restored', async ({ page }) => {
    // Discard keeps the reader's Dismiss now, so it no longer resets the whole of what the reload
    // did — it clears `restored` by hand. Left set, the next server edit on this page raised "Draft
    // restored — kept in this browser" over a change made in this session, after the Discard.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await editLagSpike(page, '1234');
    await loadDirty(page, 'E26');
    await switchTab(page, 'server');
    const banner = page.locator('#serverDraftBanner');
    await expect(banner).toHaveCount(1);
    await page.click('#serverDiscardDraftsBtn');
    await expect(banner).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).WIR.document.restoredCount('server'))).toBe(0);

    await editLagSpike(page, '777');
    expect(await page.evaluate(() => (window as any).WIR.document.countOf('server'))).toBe(1);
    expect(await page.evaluate(() => (window as any).WIR.document.restoredCount('server')),
      'the Discarded draft\'s restore was still counted').toBe(0);
    await expect(banner, 'an edit made after the Discard was announced as a restored draft').toHaveCount(0);
  });

  test('[R] F9 control: with nothing held, Discard still removes the record', async ({ page }) => {
    await loadClean(page, 'E26');
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
    expect(await page.evaluate(() => localStorage.getItem((window as any).WIR.document.draftKey()))).not.toBeNull();
    await switchTab(page, 'asserts');
    await page.click('#discardDraftsBtn');
    expect(await page.evaluate(() => localStorage.getItem((window as any).WIR.document.draftKey())),
      'a Discard with nothing held for another collection leaves no record behind').toBeNull();
  });
});

/**
 * C-5 / L3-3: the mirror judges requiresLoad and divideBy against the Asserts tab's CURRENT
 * rules. It read the loaded document's, which changed only on an import — so retuning a load
 * rule left its dependants on the old verdict while the Download carried the new rule, and the
 * next run would gate differently from what the tab showed.
 */
test.describe('Server KPIs follow the client rules they name', () => {
  test('[U] retuning a load rule re-decides the KPI that requires it', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    expect(await kpiStatus(page, 'needs-load')).toBe('LOAD NOT ACHIEVED');

    // load:unreachable asks for 100000/s; lower it to what the run achieved.
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="load:unreachable"] .wir-edit-btn').click();
    await page.fill('#editorfailValue', '0.001');
    await page.click('#editorSave');
    await expect(page.locator('#assertsUnsavedPill')).toHaveText('1 unsaved change');

    await switchTab(page, 'server');
    // Judged now — and on cpu's maximum, which is far over needs-load's limit of 1.
    expect(await kpiStatus(page, 'needs-load'), 'the KPI kept the old load verdict')
      .toBe('FAIL');
  });

  test('[U] a rule added as a draft can be chosen as a KPI\'s load rule', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    await page.locator('tr[data-rule-id="load:throughput"] .wir-dup-btn').click();
    await expect(page.locator('tr[data-rule-id="load:throughput-copy"]')).toHaveCount(1);

    await switchTab(page, 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('needs-load'));
    });
    const options = await page.locator('#serverEditor select[data-field="requiresLoad"] option')
      .evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
    expect(options).toContain('load:throughput-copy');
  });
});
