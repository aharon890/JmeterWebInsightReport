import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * A declared COMPONENT, as the page answers for it (plans/server-metrics-components.md, C1).
 *
 * ServerMetricsMapper refuses a component whose type the catalogue does not know — keeps it under
 * its id with the reason, and makes every assert naming it INVALID_RULE: "component '<id>' has
 * type '<type>', which is not one of postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2,
 * influxdb3, jvm, http-service, kubernetes-pod, mysql, custom". The mirror must answer the same on
 * the board, in the editor and in the
 * findings pane, for the block the page holds NOW. The
 * sentences are the parity vectors' (per-case `components`); this pins the surfaces. (Before C8
 * this file pinned the same for a SOURCE the mapper refused.) The unknown type is `nosuchdb`: it
 * was `oracle` until Oracle became a catalogue type.
 */

async function openServer(page: Page) {
  await loadClean(page, 'E26');
  await switchTab(page, 'server');
}

/** Replace the page's document through Edit document…, exactly as a tester would. */
async function editDocument(page: Page, edit: (doc: any) => void) {
  await page.evaluate((fn) => {
    const W = (window as any).WIR;
    W.asserts.openDocument();
    const doc = JSON.parse(W.document.text());
    // eslint-disable-next-line no-new-func
    new Function('doc', fn)(doc);
    W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    W.asserts.applyDocument();
  }, `(${edit.toString()})(doc);`);
  await switchTab(page, 'server');
}

async function board(page: Page): Promise<any[]> {
  return page.evaluate(() => (window as any).WIR.serverKpis._board());
}

/** The catalogue's types in index order, then custom: what the refusal lists. */
const VOCABULARY = 'postgres, kafka, redis, host-linux, aws-ec2, oracle, influxdb2, influxdb3, jvm, http-service,'
  + ' kubernetes-pod, mysql, custom';
const UNKNOWN_TYPE_REFUSAL = "component 'kafka' has type 'nosuchdb', which is not one of " + VOCABULARY;

test.describe('A component the mapper refuses', () => {
  test('[U] makes every assert on it INVALID on the board, in the mapper\'s words', async ({ page }) => {
    await openServer(page);
    const before = (await board(page)).find((r) => r.id === 'cpu-headroom');
    expect(before.display, 'E26 judges cpu-headroom on kafka').toBe('PASS');

    await editDocument(page, (doc: any) => { doc.serverMetrics.components.kafka.type = 'nosuchdb'; });

    const rows = await board(page);
    const onKafka = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
      .serverMetrics.asserts.filter((k: any) => k.component === 'kafka').map((k: any) => k.id));
    expect(onKafka.length, 'E26 declares several asserts on kafka').toBeGreaterThan(3);
    // Every assert on kafka — PASS, FAIL and the rest alike — is the run's INVALID_RULE now...
    onKafka.filter((id: string) => id !== 'parked').forEach((id: string) => {
      const r = rows.find((x) => x.id === id);
      expect(r.display, `${id} was judged live on a component the run refuses`).toBe('INVALID');
      expect(r.notJudged).toBe('INVALID_RULE');
    });
    expect(rows.find((r) => r.id === 'cpu-headroom').detail).toBe(UNKNOWN_TYPE_REFUSAL);
    // ...except what the evaluator decides first: a parked assert is parked.
    expect(rows.find((r) => r.id === 'parked').display).toBe('DISABLED');
    // An assert on another component is untouched.
    expect(rows.find((r) => r.id === 'lag-p50-warns').display).toBe('WARN');

    // The findings pane answers for the block the page holds now, under the component's label...
    const findings = page.locator('#serverBoard .wir-server-findings');
    await expect(findings).toContainText('serverMetrics.components.kafka');
    await expect(findings).toContainText("type 'nosuchdb' is not one of " + VOCABULARY);
    // ...and once per assert on it, under the assert's own label, as ServerMetricsMapper files them.
    const perAssert = page.locator('#serverBoard .wir-finding-error', { hasText: "component 'kafka' has type 'nosuchdb'" });
    await expect(perAssert.filter({ hasText: 'cpu-headroom' })).toHaveCount(1);
    await expect(perAssert, 'one per assert on kafka, the parked one included').toHaveCount(onKafka.length);
  });

  test('[U] the editor refuses to save an assert on it, says why, and marks it in the select',
    async ({ page }) => {
      await openServer(page);
      await editDocument(page, (doc: any) => { doc.serverMetrics.components.legacy = { type: 'nosuchdb' }; });

      await page.click('.wir-server-actions .wir-btn-primary');          // + Add server assert
      const component = page.locator('#serverEditor select[data-field="component"]');
      const labels = await component.locator('option').allTextContents();
      expect(labels).toContain('legacy (cannot be used)');
      expect(labels).toContain('kafka (custom)');

      await page.locator('#serverEditor [data-field="id"]').fill('legacy-lag');
      await component.selectOption('legacy');
      await page.locator('#serverEditor [data-field="query"]').fill('lag');
      await page.locator('#serverEditor [data-field="fail.value"]').fill('500');
      await expect(page.locator('#serverEditor .wir-problem')).toHaveText(
        "component 'legacy' has type 'nosuchdb', which is not one of " + VOCABULARY);
      await expect(page.locator('#serverEditorSave')).toBeDisabled();

      // On a usable component the same assert saves.
      await component.selectOption('kafka');
      await expect(page.locator('#serverEditor .wir-problem')).toHaveCount(0);
      await expect(page.locator('#serverEditorSave')).toBeEnabled();
    });

  test('[U] fixing the type clears the finding and the asserts on it judge again', async ({ page }) => {
    await openServer(page);
    await editDocument(page, (doc: any) => { doc.serverMetrics.components.kafka.type = 'nosuchdb'; });
    expect((await board(page)).find((r) => r.id === 'lag-spike').detail).toBe(UNKNOWN_TYPE_REFUSAL);
    await expect(page.locator('#serverBoard .wir-server-findings')).toContainText('serverMetrics.components.kafka');
    await expect(page.locator('#serverBoard .wir-finding-error', { hasText: "component 'kafka' has type" })
      .filter({ hasText: 'lag-spike' })).toHaveCount(1);

    await editDocument(page, (doc: any) => { doc.serverMetrics.components.kafka.type = 'custom'; });
    expect((await board(page)).find((r) => r.id === 'lag-spike').display).toBe('FAIL');
    // E26's own findings (typo-reducer's) stay; the component's is gone with the problem, and so
    // is every per-assert refusal it raised.
    await expect(page.locator('#serverBoard')).not.toContainText('serverMetrics.components.kafka');
    await expect(page.locator('#serverBoard')).not.toContainText('has type');
  });
});

test.describe('A component the draft no longer declares (SWEEP-9)', () => {
  test('[U] its badge says "component" — the word on every surface since C7 — never "gate"', async ({ page }) => {
    await openServer(page);
    await editDocument(page, (doc: any) => { delete doc.serverMetrics.components.kafka; });
    const badge = page.locator('.wir-component-card[data-component="kafka"] .wir-gate-badge');
    await expect(badge).toHaveCount(1);
    await expect(badge).toHaveAttribute('title', /; if saved: the document no longer declares this component$/);
    expect(await badge.getAttribute('title')).not.toContain('this gate');
  });
});

/** The document the next Download writes. */
async function written(page: Page): Promise<any> {
  return page.evaluate(() => JSON.parse((window as any).WIR.document.text()));
}

/**
 * SF44: a component could be added on the Server tab and then neither edited nor removed there —
 * the cards had no ⋯ (design §3's mock), and the toolbar had no Edit document…, which the editor's
 * own warning told the reader to use. E27 is the catalogue fixture: orders-db (Postgres, one
 * assert) and events (Kafka, one assert).
 */
test.describe('A component edited or removed on its card (SF44)', () => {
  test('[U] SF44: Delete component takes its asserts with it, as one draft, and Undo brings both back', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    const before = await board(page);
    const card = page.locator('.wir-component-card[data-component="orders-db"]');
    await expect(card.locator('.wir-component-more')).toHaveAttribute('aria-expanded', 'false');
    await card.locator('.wir-component-more').click();
    await expect(card.locator('.wir-component-more')).toHaveAttribute('aria-expanded', 'true');
    // The first action takes focus; Delete says what goes with the component.
    await expect(card.locator('.wir-component-edit')).toBeFocused();
    await expect(card.locator('.wir-component-delete')).toHaveText('Delete component and its 1 server assert');
    await card.locator('.wir-component-delete').click();

    // Through the document, and with nothing left naming what is gone: an assert left on a deleted
    // component is the "(no component)" card, INVALID_RULE, and a CONFIG ERROR on the next run.
    const doc = await written(page);
    expect(Object.keys(doc.serverMetrics.components)).toEqual(['events']);
    expect(doc.serverMetrics.asserts.map((a: any) => a.component)).toEqual(['events']);
    // The build judged orders-db, so its card stays for the badge (SWEEP-9) — with nothing on it,
    // and nothing that would add an assert to a component the file no longer declares.
    const gone = page.locator('.wir-component-card[data-component="orders-db"]');
    await expect(gone.locator('.wir-gate-badge')).toHaveAttribute('title', /the document no longer declares this component$/);
    await expect(gone.locator('.wir-component-empty')).toHaveText('The document no longer declares orders-db, so a '
      + 'Save writes none — the badge is the verdict this run was gated on.');
    await expect(gone.locator('.wir-component-add, .wir-component-more')).toHaveCount(0);
    await expect(page.locator('.wir-component-card[data-component=""]')).toHaveCount(0);
    expect((await board(page)).filter((r) => r.display === 'INVALID')).toEqual([]);
    await expect(page.locator('#wir-undo-toast')).toContainText(
      'Component "orders-db" and its 1 server assert deleted — draft');
    await expect(page.locator('#wirUndoBtn')).toBeFocused();
    // The component and its assert: two changes, on both tabs' pills.
    await expect(page.locator('#serverUnsavedPill')).toHaveText('2 unsaved changes');

    await page.click('#wirUndoBtn');
    expect(Object.keys((await written(page)).serverMetrics.components)).toEqual(['orders-db', 'events']);
    expect(await page.evaluate(() => (window as any).WIR.document.count())).toBe(0);
    await expect(page.locator('#serverUnsavedPill')).toHaveCount(0);
    // The same rows, with the same verdicts: the baked ones are found again under their keys.
    expect((await board(page)).map((r) => [r.id, r.display])).toEqual(before.map((r) => [r.id, r.display]));
    await expect(page.locator('.wir-component-card[data-component="orders-db"] .wir-component-more')).toBeFocused();
  });

  test('[U] SF44: Edit description / match writes those two keys only, and a new match needs re-collecting', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    const shipped = (await written(page)).serverMetrics.components;

    await page.locator('.wir-component-card[data-component="events"] .wir-component-more').click();
    await page.locator('.wir-component-card[data-component="events"] .wir-component-edit').click();
    const dialog = page.locator('#serverDialog .wir-slideover');
    await expect(dialog.locator('.wir-slideover-head strong')).toHaveText('Edit component — events');
    // The id and the type are what every assert on it is named and collected by: not edited here.
    await expect(dialog.locator('[data-wir-dlg="id"]')).toHaveCount(0);
    await expect(dialog.locator('#serverEditComponentWhat')).toContainText('Kafka · id events');
    const description = dialog.locator('[data-wir-dlg="description"]');
    await expect(description).toHaveValue(shipped.events.description);
    await description.fill('Event bus (renamed)');
    await page.click('#serverSaveComponentBtn');

    expect((await written(page)).serverMetrics.components.events)
      .toEqual({ ...shipped.events, description: 'Event bus (renamed)' });
    expect((await written(page)).serverMetrics.components['orders-db']).toEqual(shipped['orders-db']);
    await expect(page.locator('.wir-component-card[data-component="events"] .wir-component-type'))
      .toContainText('Event bus (renamed)');
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('.wir-component-card[data-component="events"] .wir-component-more')).toBeFocused();
    // A label is not what was judged: the assert keeps the verdict the build took.
    const eventsRow = (await board(page)).find((r) => r.gate === 'events');
    expect(eventsRow.display).not.toBe('NEEDS RE-COLLECTION');

    // A match changes the catalogue query collected under the component (D22): its assert is no
    // longer answered by this collection, and says so rather than borrowing the old series' number.
    await page.locator('.wir-component-card[data-component="orders-db"] .wir-component-more').click();
    await page.locator('.wir-component-card[data-component="orders-db"] .wir-component-edit').click();
    await page.locator('#serverDialog [data-wir-dlg="match"]').fill('instance="other-db:5432"');
    await page.click('#serverSaveComponentBtn');
    expect((await written(page)).serverMetrics.components['orders-db'].match).toBe('instance="other-db:5432"');
    expect((await board(page)).find((r) => r.gate === 'orders-db').display).toBe('NEEDS RE-COLLECTION');

    // An emptied field is removed, not written blank.
    await page.locator('.wir-component-card[data-component="events"] .wir-component-more').click();
    await page.locator('.wir-component-card[data-component="events"] .wir-component-edit').click();
    await page.locator('#serverDialog [data-wir-dlg="description"]').fill('');
    await page.click('#serverSaveComponentBtn');
    expect((await written(page)).serverMetrics.components.events).not.toHaveProperty('description');
  });

  test('[U] SF44: the ⋯ actions close on Escape, back on the ⋯, and are offered only on a declared component', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const more = page.locator('.wir-component-card[data-component="kafka"] .wir-component-more');
    await more.click();
    await expect(page.locator('.wir-component-menu')).toHaveCount(1);
    await page.keyboard.press('Escape');
    await expect(page.locator('.wir-component-menu')).toHaveCount(0);
    await expect(more).toBeFocused();
    // A click elsewhere closes them too.
    await more.click();
    await page.click('#serverBoard .wir-window-note');
    await expect(page.locator('.wir-component-menu')).toHaveCount(0);

    // Not on a component the document no longer declares (the build still judged it), and not on
    // the "(no component)" card: there is no component in the file for either to edit or delete.
    await editDocument(page, (doc: any) => {
      delete doc.serverMetrics.components.kafka;
    });
    await expect(page.locator('.wir-component-card[data-component="kafka"]')).toHaveCount(1);
    await expect(page.locator('.wir-component-card[data-component="kafka"] .wir-component-more')).toHaveCount(0);
    await expect(page.locator('.wir-component-card[data-component=""] .wir-component-more')).toHaveCount(0);
    await expect(page.locator('.wir-component-card[data-component="db"] .wir-component-more')).toHaveCount(1);
  });

  test('[U] SF44: Edit document… is on the Server toolbar and opens over this tab, modal, focus back on Escape', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const button = page.locator('#tab-server #serverEditDocumentBtn');
    await expect(button).toHaveText('Edit document…');
    await button.click();
    // Not in the hidden Asserts pane: over the tab it was asked from.
    const text = page.locator('#docEditorText');
    await expect(text).toBeVisible();
    await expect(text).toBeFocused();
    await expect(page.locator('#documentEditor .wir-slideover')).toHaveAttribute('aria-modal', 'true');
    await page.keyboard.press('Escape');
    await expect(text).toHaveCount(0);
    await expect(button).toBeFocused();

    // Applied from here, the Server tab re-reads the block: a component added in the file is a card.
    await button.click();
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.components.payments = { type: 'custom', description: 'Payments' };
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    });
    await page.click('#docEditorApply');
    await expect(page.locator('.wir-component-card[data-component="payments"]')).toHaveCount(1);
    await expect(page.locator('#serverUnsavedPill')).toHaveText('1 unsaved change');
    await expect(page.locator('#tab-server #serverEditDocumentBtn')).toBeFocused();
  });
});

/* The Undo button, pressed even once its toast is withdrawn: the fade is the only thing in the way. */
const undoAnyway = (page: Page) => page.evaluate(() => (document.getElementById('wirUndoBtn') as HTMLButtonElement).click());

/** A component card's ⋯, then one of its actions. */
async function componentAction(page: Page, id: string, action: 'edit' | 'delete') {
  const card = page.locator(`.wir-component-card[data-component="${id}"]`);
  await card.locator('.wir-component-more').click();
  await card.locator(`.wir-component-${action}`).click();
}

/** Edit description / match on a card, writing `description`. */
async function renameComponent(page: Page, id: string, description: string) {
  await componentAction(page, id, 'edit');
  await page.locator('#serverDialog [data-wir-dlg="description"]').fill(description);
  await page.click('#serverSaveComponentBtn');
}

/**
 * F5 (review of 3.1.37), on the component actions: their Undo put back the whole document captured
 * before them, over any edit made while the toast was up — and after a component edit a server-row
 * Delete's Undo restored nothing and said nothing (the slice refuses a snapshot whose frame moved).
 * The offer is withdrawn the moment the document moves; an Undo reached anyway writes nothing.
 */
test.describe('F5: a component action\'s Undo never writes over a newer edit', () => {
  test('[U] F5: Delete component, then a server Disable: the Undo is withdrawn, and the Disable stays', async ({ page }) => {
    await loadClean(page, 'E27');
    await switchTab(page, 'server');
    await componentAction(page, 'events', 'delete');
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toContainText('Component "events"');
    await expect(toast).toHaveClass(/visible/);
    await page.locator('button[onclick*="WIR.serverKpis.toggleEnabled"]').first().click();
    await expect(toast, 'an Undo that would put events back over the Disable just made').not.toHaveClass(/visible/);
    await undoAnyway(page);
    const b = (await written(page)).serverMetrics;
    expect(Object.keys(b.components)).toEqual(['orders-db']);
    expect(b.asserts.filter((a: any) => a.enabled === false).length).toBe(1);
  });

  test('[U] F5: a server Delete, then a component edit: no Undo is left that would restore nothing', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await page.evaluate(() => { const W = (window as any).WIR.serverKpis; W.remove(W._keyOf('lag-spike')); });
    const toast = page.locator('#wir-undo-toast');
    await expect(toast).toContainText('deleted');
    await expect(toast).toHaveClass(/visible/);
    await renameComponent(page, 'db', 'Orders DB (renamed)');
    await expect(toast, 'the Undo would have restored nothing, and said nothing').not.toHaveClass(/visible/);
    await undoAnyway(page);
    const doc = await written(page);
    expect(doc.serverMetrics.asserts.some((a: any) => a.id === 'lag-spike')).toBe(false);
    expect(doc.serverMetrics.components.db.description).toBe('Orders DB (renamed)');
  });
});

/** The Asserts table as a reader sees it: each rule's chip and its draft marker, by id. */
async function clientRows(page: Page): Promise<string[][]> {
  return page.$$eval('#assertsTable tr[data-rule-id]', (trs) => trs.map((tr) => [
    tr.getAttribute('data-rule-id')!,
    (tr.querySelector('td .wir-chip') as HTMLElement).textContent!.trim(),
    tr.querySelector('.wir-tag-draft') ? (tr.querySelector('.wir-tag-draft') as HTMLElement).textContent!.trim() : '',
  ]).sort((a, b) => a[0].localeCompare(b[0])));
}

/** The client gate as the page shows it: the rows, the toolbar's verdict, and the strip's breaches. */
async function clientGate(page: Page) {
  return {
    rows: await clientRows(page),
    toolbar: (await page.locator('#assertsVerdict').textContent())!.trim(),
    breaches: await page.locator('.wir-gate-breaches', { hasText: 'Worst breaches' }).count(),
  };
}

/* By id, as clientRows sorts them: the two load rules and E26's four near-band rules
   (client-numbers.spec.ts). breaches counts the strip's "Worst breaches" block, not its rows. */
const E26_JUDGED = {
  rows: [
    ['checkout-mean-on-band', 'FAIL', ''],
    ['load:throughput', 'PASS', ''],
    ['load:unreachable', 'FAIL', ''],
    ['products-mean-warns', 'WARN', ''],
    ['run-mean-whole-ms', 'PASS', ''],
    ['search-mean-gte-on-band', 'PASS', ''],
  ],
  toolbar: 'FAIL',
  breaches: 1,
};

/**
 * N1 (review of 3.1.37). Every Server-tab component write goes through WIR.document.replace(), and
 * the client slice's applier set the client rules to the document's, re-parsed and judged by nobody:
 * every row read NOT EVALUATED, the strip's "Worst breaches" and the sampler badges went, while the
 * toolbar and the header still said FAIL. The SF44 specs run on E27, which has no client rules — that
 * is why nothing caught it. The applier now leaves the client slice exactly as it was when the
 * client block did not change, and re-judges it when it did.
 */
test.describe('A Server-tab write leaves the client gate judged (N1)', () => {
  test('[U] N1: Edit description, Delete component and its Undo, + Add component, Cancel and Undo', async ({ page }) => {
    await loadClean(page, 'E26');
    expect(await clientGate(page), 'precondition').toEqual(E26_JUDGED);
    await switchTab(page, 'server');

    await renameComponent(page, 'kafka', 'Kafka (renamed)');
    expect(await clientGate(page), 'Edit description / match').toEqual(E26_JUDGED);

    await componentAction(page, 'db', 'delete');
    expect(await clientGate(page), 'Delete component').toEqual(E26_JUDGED);
    await page.click('#wirUndoBtn');
    expect(await clientGate(page), 'its Undo').toEqual(E26_JUDGED);

    await page.click('#serverAddComponent');
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('n1-probe');
    await page.locator('#serverDialog .wir-type-card', { hasText: 'Postgres' }).click();
    await page.click('#serverAddComponentBtn');
    expect(await clientGate(page), '+ Add component').toEqual(E26_JUDGED);
    await page.locator('#serverDialog .wir-slideover-foot button', { hasText: 'Cancel' }).click();
    await expect(page.locator('#wir-undo-toast.visible')).toContainText('Component "n1-probe" has no server assert yet');
    await page.click('#wirUndoBtn');
    expect(Object.keys((await written(page)).serverMetrics.components)).not.toContain('n1-probe');
    expect(await clientGate(page), 'its Undo').toEqual(E26_JUDGED);
  });

  test('[U] N1: a disabled client rule keeps DISABLED and EDITED across a server-only write', async ({ page }) => {
    await loadClean(page, 'E26');
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
    const edited = [
      ['checkout-mean-on-band', 'FAIL', ''],
      ['load:throughput', 'PASS', ''],
      ['load:unreachable', 'DISABLED', 'EDITED'],
      ['products-mean-warns', 'WARN', ''],
      ['run-mean-whole-ms', 'PASS', ''],
      ['search-mean-gte-on-band', 'PASS', ''],
    ];
    expect(await clientRows(page)).toEqual(edited);
    await switchTab(page, 'server');
    await renameComponent(page, 'kafka', 'Kafka (renamed)');
    expect(await clientRows(page), 'the marker and the verdict went with the re-parse').toEqual(edited);
  });

  test('[U] N1: Delete component, then Disable a client rule: the toolbar agrees with the rows the document holds', async ({ page }) => {
    // 3.1.37: the Undo put the deleted component back over the Disable, re-parsed the rules
    // unjudged, and left the toolbar reading PASS · 0 failed over a document whose load:unreachable
    // FAILs. The end state alone is held by three fixes at once — F5 makes the Undo a no-op, and an
    // Undo that did run would reinstate the capture (F1), whose restore re-judges — so the client gate
    // is checked straight after the Delete too, which N1 alone decides.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    await componentAction(page, 'db', 'delete');
    expect(await clientGate(page), 'Delete component re-parsed the client rules and judged none of them').toEqual(E26_JUDGED);
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('load:unreachable')); });
    await undoAnyway(page);
    const enabled = await page.evaluate(() => JSON.parse((window as any).WIR.document.text())
      .assertions.find((r: any) => r.id === 'load:unreachable').enabled !== false);
    const rows = await clientRows(page);
    expect(rows.map((r) => r[1]), 'a row the page did not judge').not.toContain('NOT EVALUATED');
    expect(rows.find((r) => r[0] === 'load:unreachable')![1]).toBe(enabled ? 'FAIL' : 'DISABLED');
    const fails = rows.filter((r) => r[1] === 'FAIL').length;
    await expect(page.locator('#assertsVerdict')).toHaveText(fails > 0 ? 'FAIL' : 'PASS');
    await expect(page.locator('#assertsToolbar .wir-toolbar-counts')).toContainText(fails + ' failed');
  });

  test('[U] N1: a redraw that reuses rows straight after a replace() draws the block replace() adopted', async ({ page }) => {
    // replace()'s contract is the server slice's too: the rows and seeds the last render decided
    // were left cached by its applier, so a chart toggle — which redraws from that cache — drew
    // the removed assert again, until something rendered the board in full.
    await loadClean(page, 'E26');
    await switchTab(page, 'server');
    const out = await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      const D = (window as any).WIR.document;
      const shown = () => document.querySelectorAll('#serverBoard [data-kpi-key]').length;
      const before = shown();
      const doc = JSON.parse(D.text());
      doc.serverMetrics.asserts.shift();
      const problem = D.replace(doc, D.source());
      W.toggle(W._state.declaredKpis[0].key);
      return { before, problem, declared: W._state.declaredKpis.length, after: shown() };
    });
    expect(out.problem).toBeNull();
    expect(out.declared).toBe(out.before - 1);
    expect(out.after, 'the reused rows were the old block\'s').toBe(out.declared);
  });

  test('[U] N1: + Add your first component on E25 keeps every client status and the sampler badges', async ({ page }) => {
    await loadClean(page, 'E25');
    const badges = page.locator('#samplerTable .assert-badge-mini');
    const statuses = await clientRows(page);
    const marks = await badges.allTextContents();
    expect(statuses.length, 'E25 declares thirteen client rules').toBe(13);
    expect(marks.length, 'E25 badges its samplers').toBeGreaterThan(0);

    await switchTab(page, 'server');
    await page.locator('#serverBoard .wir-first-component .wir-type-card', { hasText: 'Postgres' }).click();
    await page.locator('#serverDialog [data-wir-dlg="id"]').fill('first-db');
    await page.click('#serverAddComponentBtn');
    expect(Object.keys((await written(page)).serverMetrics.components)).toEqual(['first-db']);
    expect(await clientRows(page)).toEqual(statuses);
    await expect(badges).toHaveText(marks);
  });
});

test.describe('An id two server asserts share (R2-2, R3-8)', () => {
  test('[U] is a live WARNING finding, never a refusal, and goes when one is renamed', async ({ page }) => {
    await openServer(page);
    const shared = page.locator('#serverBoard .wir-finding-warning', { hasText: 'server asserts are judged under the id' });
    await expect(shared).toHaveCount(0);

    await editDocument(page, (doc: any) => {
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-p50-warns').id = 'lag-spike';
    });
    await expect(shared).toHaveCount(1);
    await expect(shared).toContainText("2 server asserts are judged under the id 'lag-spike'");
    // Both are still judged, each on its own row: a shared name is not a defect in either check.
    const rows = (await board(page)).filter((r) => r.id === 'lag-spike');
    expect(rows.map((r) => r.display)).toEqual(['FAIL', 'WARN']);

    await editDocument(page, (doc: any) => {
      doc.serverMetrics.asserts.filter((k: any) => k.id === 'lag-spike')[1].id = 'lag-p50-warns';
    });
    await expect(shared).toHaveCount(0);
  });
});
