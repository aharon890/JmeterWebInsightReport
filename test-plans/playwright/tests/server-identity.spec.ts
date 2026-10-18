import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * One declared server KPI is one row — whatever its id says (M7c group C).
 *
 * Java accepts two KPIs with one id: written twice, or derived twice from one query (two
 * id-less KPIs on `lag` are both `kafka-lag`). The board used to key everything by id, so both
 * rows showed one verdict, Edit opened the first and Save replaced the last, Delete removed
 * both, and the baked verdict of a deleted KPI appeared under another that took its name. And
 * an edited KPI on a CAPPED series kept the build's verdict beside a threshold the build never
 * judged. Every handler now takes the row's key; the baked verdict is looked up by key AND an
 * unchanged definition; a capped series the build did not judge as written is WITHHELD.
 */

async function openServer(page: Page) {
  await loadClean(page, 'E26');
  await switchTab(page, 'server');
}

/** Make `kpis` the serverMetrics.asserts of E26's own document, and re-read the Server tab. */
async function withKpis(page: Page, kpis: any[]) {
  await page.evaluate((list) => {
    const W = (window as any).WIR;
    const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
    doc.serverMetrics.asserts = list;
    W.document.adopt(doc);
    W.serverKpis.reread();
  }, kpis);
}

async function board(page: Page): Promise<any[]> {
  return page.evaluate(() => (window as any).WIR.serverKpis._board());
}

async function writtenKpis(page: Page): Promise<any[]> {
  return page.evaluate(() => JSON.parse((window as any).WIR.document.text()).serverMetrics.asserts);
}

/** The rendered rows whose name cell reads `id`, in board order. */
function rowsNamed(page: Page, id: string) {
  return page.locator('#serverKpiTable tbody tr.wir-kpi-row')
    .filter({ has: page.locator(`.wir-kpi-name:text-is("${id}")`) });
}

// Two id-less KPIs on one query: Java judges them as kafka-lag FAIL (max 900 > 500) and kafka-lag
// PASS (p50 under 5000), each on its own.
const MAX_FAILS = { component: 'kafka', query: 'lag', unit: 'msgs', reduce: { kind: 'max' },
  fail: { gt: 500 }, pack: 'kafka@1', team: 'payments' };
const P50_PASSES = { component: 'kafka', query: 'lag', unit: 'msgs', reduce: { kind: 'p50' },
  across: 'max', fail: { gt: 5000 } };

test.describe('Id-less and duplicate-id KPIs are separate rows', () => {
  test('[U] two id-less KPIs on one query get the derived id and their OWN verdicts (R1-2, R2-2)',
    async ({ page }) => {
      await openServer(page);
      await withKpis(page, [MAX_FAILS, P50_PASSES]);

      const rows = await board(page);
      expect(rows.map((r) => r.id)).toEqual(['kafka-lag', 'kafka-lag']);
      expect(rows.map((r) => r.display), 'one verdict was shown for both rows').toEqual(['FAIL', 'PASS']);
      expect(rows[0].key).not.toBe(rows[1].key);

      // Rendered: two rows named kafka-lag, one FAIL and one PASS, each marked as a derived id.
      await expect(rowsNamed(page, 'kafka-lag')).toHaveCount(2);
      await expect(rowsNamed(page, 'kafka-lag').locator('.wir-chip')).toHaveText(['FAIL', 'PASS']);
      await expect(rowsNamed(page, 'kafka-lag').first()).toContainText('derived id');
    });

  test('[R] a no-op save writes an id-less KPI back exactly — no "id": "", the pack and team kept',
    async ({ page }) => {
      await openServer(page);
      await withKpis(page, [MAX_FAILS, P50_PASSES]);

      // Byte for byte, key order included: the writer used to emit "id": "" and drop pack and
      // team, because it looked the file entry up by an id the entry never had.
      const written = await writtenKpis(page);
      expect(JSON.stringify(written), 'a no-op save rewrote the KPIs')
        .toBe(JSON.stringify([MAX_FAILS, P50_PASSES]));
    });

  test('[U] Edit on the second row edits the second KPI, and only it (R1-2)', async ({ page }) => {
    await openServer(page);
    await withKpis(page, [MAX_FAILS, P50_PASSES]);

    // Through the rendered button, which is what carries the row key.
    await rowsNamed(page, 'kafka-lag').nth(1).locator('button', { hasText: 'Edit' }).click();
    await expect(page.locator('#serverEditor select[data-field="kind"]')).toHaveValue('p50');
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.set('fail.value', '6000');
      W.applyEditor();
    });

    const written = await writtenKpis(page);
    expect(written.length).toBe(2);
    expect(written[0], 'the OTHER KPI was overwritten').toEqual(MAX_FAILS);
    expect(written[1].fail).toEqual({ gt: 6000 });
    expect(written[1].id, 'a derived id was written into the file').toBeUndefined();
    expect(written[1].reduce).toEqual({ kind: 'p50' });
    expect(written[1].across, 'a field the edit did not touch was re-spelled').toBe('max');
  });

  test('[U] Delete, Disable and a drag act on one row, not on every KPI with the id (R1-2, R3-8)',
    async ({ page }) => {
      await openServer(page);
      // Explicit duplicates: a copy-pasted block that was never renamed.
      await withKpis(page, [
        Object.assign({ id: 'lag' }, MAX_FAILS),
        Object.assign({ id: 'lag' }, P50_PASSES),
      ]);
      let rows = await board(page);
      expect(rows.map((r) => r.display)).toEqual(['FAIL', 'PASS']);
      // Each row shows its own Actual: the p50 row does not borrow the max row's 900.
      expect(rows[0].actual).not.toBe(rows[1].actual);

      const second = rows[1].key;
      await page.evaluate((key) => (window as any).WIR.serverKpis.toggleEnabled(key), second);
      rows = await board(page);
      expect(rows.map((r) => r.display)).toEqual(['FAIL', 'DISABLED']);

      await page.evaluate((key) => (window as any).WIR.serverKpis._commitDrag(key, 'fail', 7000), second);
      let written = await writtenKpis(page);
      expect(written[0].fail, 'the drag moved the other KPI').toEqual({ gt: 500 });
      expect(written[1].fail).toEqual({ gt: 7000 });

      await rowsNamed(page, 'lag').filter({ has: page.locator('.wir-chip:text-is("FAIL")') })
        .locator('button', { hasText: 'Delete' }).click();
      written = await writtenKpis(page);
      expect(written.length, 'Delete removed every KPI sharing the id').toBe(1);
      expect(written[0].reduce).toEqual({ kind: 'p50' });
    });

  test('[U] the editor will not introduce a duplicate, but a file\'s own duplicate can be retuned',
    async ({ page }) => {
      await openServer(page);
      await withKpis(page, [
        Object.assign({ id: 'lag' }, MAX_FAILS),
        Object.assign({ id: 'lag' }, P50_PASSES),
      ]);
      // Retuning one of two existing duplicates is allowed — Java accepts the file as it is.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._board()[1].key);
        W.set('fail.value', '6500');
      });
      await expect(page.locator('#serverEditor button.wir-btn-primary')).toBeEnabled();
      await page.evaluate(() => (window as any).WIR.serverKpis.closeEditor());

      // Renaming another KPI onto that id is a new collision, and is refused.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.addKpi();
        W.set('id', 'lag');
        W.set('query', 'cpu');
        W.set('fail.value', '90');
      });
      await expect(page.locator('#serverEditor .wir-problem')).toContainText('already has that id');
    });
});

test.describe('A capped series is never re-judged under a definition the build did not judge', () => {
  test('[U] retuning a capped KPI withholds its verdict, with a reason, a chip and a rank (C-4, R3-9)',
    async ({ page }) => {
      await openServer(page);
      // many-instances: 21 pods, capped at 20 in the page. Java judged 32 against gt 10000: PASS.
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.edit(W._keyOf('many-instances'));
        W.set('fail.value', '20');
        W.applyEditor();
      });

      const row = rowsNamed(page, 'many-instances');
      await expect(row.locator('.wir-chip'), 'the build\'s PASS was shown beside a band it never judged')
        .toHaveText('NOT RE-JUDGED');
      await expect(row.locator('td').nth(2)).toHaveText('—');
      await expect(page.locator('.wir-withheld-row')).toContainText('capped');
      await expect(page.locator('.wir-withheld-row')).toContainText('jmeter -g');

      // A filter chip selects it, and the chips sum to All n ("FAIL 1", the Asserts tab's
      // wording, since SF41).
      const chips = await page.locator('.wir-server-filters .wir-filter-chip[onclick*="filterStatus"]')
        .allTextContents();
      const all = Number(/^All (\d+)$/.exec(chips[0])![1]);
      const summed = chips.slice(1).reduce((n, c) => n + Number(/ (\d+)$/.exec(c)![1]), 0);
      expect(summed, `the status chips do not add up to All: ${chips.join(' | ')}`).toBe(all);
      expect(chips).toContain('NOT RE-JUDGED 1');

      // Worst-first: above every PASS in its own component's card (many-instances is db's).
      const order = await page.locator('.wir-component-card[data-component="db"] tbody tr.wir-kpi-row .wir-chip').allTextContents();
      expect(order.indexOf('NOT RE-JUDGED')).toBeLessThan(order.indexOf('PASS'));

      await page.evaluate(() => (window as any).WIR.serverKpis.filterStatus('NOT RE-JUDGED'));
      await expect(page.locator('#serverKpiTable tbody tr.wir-kpi-row')).toHaveCount(1);
    });

  test('[U] a duplicate of a capped KPI is withheld, not left NOT EVALUATED (R3-9)', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.duplicate(W._keyOf('many-instances'));
    });
    await expect(rowsNamed(page, 'many-instances-2').locator('.wir-chip')).toHaveText('NOT RE-JUDGED');
    // The original is untouched, and keeps the build's verdict.
    await expect(rowsNamed(page, 'many-instances').locator('.wir-chip')).toHaveText('PASS');
    await expect(rowsNamed(page, 'many-instances')).toContainText('from the build');
  });

  test('[U] a KPI renamed onto a deleted KPI\'s id does not inherit its baked verdict (R1-13)',
    async ({ page }) => {
      await openServer(page);
      await page.evaluate(() => {
        const W = (window as any).WIR.serverKpis;
        W.remove(W._keyOf('lag-spike'));                 // Java: FAIL
        W.edit(W._keyOf('many-instances'));              // capped, Java: PASS
        W.set('id', 'lag-spike');
        W.applyEditor();
      });
      const row = rowsNamed(page, 'lag-spike');
      await expect(row).toHaveCount(1);
      await expect(row.locator('.wir-chip'), "lag-spike's FAIL was shown under many-instances' query")
        .toHaveText('NOT RE-JUDGED');
      // It is many-instances' row under a new name — its description came with it. (The board no
      // longer draws the query: design §3.)
      await expect(row).toContainText('21 instances against a cap of 20');
    });

  test('[U] retuning the client rule a capped KPI names withholds the build\'s verdict (review:C)',
    async ({ page }) => {
      // The load rule a KPI requires or divides by is part of what Java judged. A LOAD_NOT_ACHIEVED
      // (or a ratio) baked against the OLD rule fell back to "from the build" once the rule was
      // retuned to pass — the capped series cannot be re-judged here, so the honest answer is none.
      // E26's capped KPI names no client rule, so each case ships the page a file where it does:
      // the build's answer for it is the same PASS (load:throughput passes on plain.jtl).
      const variants = [
        { name: 'its own requiresLoad', assertKey: 'requiresLoad' },
        { name: 'its divideBy', assertKey: 'divideBy' },
        { name: 'the block-level requiresLoad', blockKey: 'requiresLoad' },
      ];
      for (const v of variants) {
        await openServer(page);
        await page.evaluate((variant: any) => {
          const W = window as any;
          const d = { ...W.chartData, annotationsDocument: JSON.parse(JSON.stringify(W.chartData.annotationsDocument)) };
          const block = d.annotationsDocument.serverMetrics;
          if (variant.assertKey) block.asserts.find((k: any) => k.id === 'many-instances')[variant.assertKey] = 'load:throughput';
          if (variant.blockKey) block[variant.blockKey] = 'load:throughput';
          // The page as the build wrote it for that file: the shipped document IS this one.
          W.globalChartData = d;
          W.chartData = d;
          W.WIR.serverKpis.init(d);
        }, v);
        const row = rowsNamed(page, 'many-instances');
        await expect(row.locator('.wir-chip'), `${v.name}: the build's verdict, untouched`).toHaveText('PASS');
        await expect(row, v.name).toContainText('from the build');

        // Retuned (lt 0.001 -> lt 1), and the run still passes it at 24.76 req/s: nothing on the
        // page can re-judge the capped series against the new rule, so nothing is shown.
        await page.evaluate(() => (window as any).WIR.asserts._retune('load:throughput', 1, 'fail'));
        const load = await page.evaluate(() => (window as any).WIR.asserts.currentRules()
          .find((r: any) => r.id === 'load:throughput'));
        expect(load.status, 'the retuned load rule still passes').toBe('PASS');
        await expect(row.locator('.wir-chip'), `${v.name}: the build's PASS was shown under a retuned load rule`)
          .toHaveText('NOT RE-JUDGED');
      }
    });

  test('[R] F3: a block change that cannot move a capped assert\'s verdict leaves it the build\'s',
    async ({ page }) => {
      // Exploratory, 2026-10-02. bakedFor() drops the build's verdict whenever frameOf(block) —
      // the whole block bar its asserts — differs from the shipped one, gate LABELS included. So
      // relabelling "DB" to "Database" withheld many-instances (NOT RE-JUDGED, and "→ ? if saved"
      // on the DB badge) with a reason row saying it was "a server assert the build did not judge
      // as written here" — when it is written exactly as judged. Relabelling a gate it is not
      // in, adding an unrelated gate and re-pointing ANOTHER source's URL all did the same, while
      // the assert actually on that source stayed "SOURCE FAILED live". None of these can change
      // what Java decides about many-instances (many / max / db / gt 10000). Since C1 a gate is a
      // component and its label is the component's description; a source is not in the file (C8),
      // so "another source's URL" became another component's type.
      const changes: Array<[string, string]> = [
        ['its component relabelled', 'db-label'],
        ['a component it is not in relabelled', 'kafka-label'],
        ['an unrelated component added', 'new-gate'],
        ['another component\'s type changed', 'kafka-type'],
      ];
      for (const [what, change] of changes) {
        await openServer(page);
        await page.evaluate((c) => {
          const W = (window as any).WIR;
          W.asserts.openDocument();
          const doc = JSON.parse(W.document.text());
          const block = doc.serverMetrics;
          if (c === 'db-label') block.components.db.description = 'Database';
          if (c === 'kafka-label') block.components.kafka.description = 'Kafka cluster';
          if (c === 'new-gate') block.components.jvm = { type: 'custom', description: 'JVM' };
          if (c === 'kafka-type') block.components.kafka.type = 'postgres';
          W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
          W.asserts.applyDocument();
        }, change);
        await switchTab(page, 'server');

        const row = rowsNamed(page, 'many-instances');
        await expect(row.locator('.wir-chip'), `${what}: the build's PASS was withheld`).toHaveText('PASS');
        await expect(row, what).toContainText('from the build');
        await expect(row, `${what}: an untouched assert was marked edited`).not.toHaveClass(/wir-edited/);
        await expect(page.locator('#serverBoard .wir-withheld-row'), `${what}: a withheld reason row`)
          .toHaveCount(0);
        if (change !== 'db-label') {
          // None of these moves a verdict in DB, so DB's badge has nothing to predict.
          const db = page.locator('#serverBoard .wir-gate-badge').nth(1);
          // Its name is its aria-label since SF26: the card header carries the description.
          await expect(db).toHaveAttribute('aria-label', /^DB /);
          await expect(db.locator('.wir-would-be'), `${what}: "→ ? if saved" on the DB badge`).toHaveCount(0);
        }
      }
    });

  test('[U] a block change that CAN move a capped assert\'s verdict still withholds it (F3\'s other half)',
    async ({ page }) => {
      // F3 narrowed what bakedFor compares from the whole block to what Java reads when it judges
      // THIS assert (judgedFrameOf). The narrowing must stop there: the coverage floor and the
      // ramp-up exclusion decide the verdict and the window it is taken over, its own source is
      // what its series was collected under, and a gate it names that is no longer declared makes
      // it a config error. Each of those, on a capped series, is still a definition the build did
      // not judge — NOT RE-JUDGED or refused, never "from the build".
      const changes: Array<[string, string]> = [
        ['the coverage floor', 'floor'],
        ['the ramp-up exclusion', 'rampup'],
        ['its own component\'s type', 'db-type'],
        ['its own component removed', 'db-removed'],
      ];
      for (const [what, change] of changes) {
        await openServer(page);
        await page.evaluate((c) => {
          const W = (window as any).WIR;
          W.asserts.openDocument();
          const doc = JSON.parse(W.document.text());
          const block = doc.serverMetrics;
          if (c === 'floor') block.collection = Object.assign({}, block.collection, { coverageFloor: 0.5 });
          if (c === 'rampup') block.collection = Object.assign({}, block.collection, { excludeRampUp: false });
          if (c === 'db-type') block.components.db.type = 'postgres';
          if (c === 'db-removed') delete block.components.db;
          W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
          W.asserts.applyDocument();
        }, change);
        await switchTab(page, 'server');
        const row = rowsNamed(page, 'many-instances');
        await expect(row.locator('.wir-chip'), `${what}: still shown as the build's verdict`)
          .not.toHaveText('PASS');
        await expect(row, what).not.toContainText('from the build');
      }
    });

  test('[U] F4: an assert renamed onto a capped sibling\'s id, then its component deleted: the sibling keeps its row and the build\'s verdict',
    async ({ page }) => {
      // Every Server-tab write re-adopts the whole block, and assignKeys gave the re-read asserts
      // their row keys by id occurrence alone: the j-th "many-instances" took the j-th key called
      // that. So kafka's cpu-headroom, renamed onto db's capped many-instances and FIRST in the file,
      // took db's key; db's row became a new, edited one, its baked PASS was withheld (NOT
      // RE-JUDGED: a capped series cannot be re-judged here), the count read one too many, and the
      // wrong keys went into the draft and came back on a reload. Rows pair on their text first now.
      await openServer(page);
      const dbRow = async () => (await board(page)).filter((r) => r.id === 'many-instances' && r.gate === 'db');
      const expectDbRowIsTheBuilds = async (when: string) => {
        const rows = await dbRow();
        expect(rows.length, `${when}: db's many-instances row`).toBe(1);
        expect(rows[0].capped, 'precondition: a capped series').toBe(true);
        expect([rows[0].display, rows[0].edited, rows[0].withheld], `${when}: db's capped row lost the build's verdict`)
          .toEqual(['PASS', false, false]);
      };
      await expectDbRowIsTheBuilds('as shipped');

      // Edit document…: kafka's cpu-headroom (asserts[0]) renamed onto db's many-instances.
      await page.evaluate(() => {
        const W = (window as any).WIR;
        W.asserts.openDocument();
        const doc = JSON.parse(W.document.text());
        doc.serverMetrics.asserts[0].id = 'many-instances';
        W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        W.asserts.applyDocument();
      });
      await switchTab(page, 'server');
      await expectDbRowIsTheBuilds('after the rename');
      // cpu-headroom gone, one assert added — not db's row "edited" as well.
      await expect(page.locator('#serverUnsavedPill')).toHaveText('2 unsaved changes');

      // The card's ⋯ → Delete component on kafka: its four asserts, the renamed one included.
      const card = page.locator('.wir-component-card[data-component="kafka"]');
      await card.locator('.wir-component-more').click();
      await expect(card.locator('.wir-component-delete')).toHaveText('Delete component and its 4 server asserts');
      await card.locator('.wir-component-delete').click();
      await expectDbRowIsTheBuilds('after Delete component');
      await expect(page.locator('#serverUnsavedPill')).toHaveText('5 unsaved changes');

      // The keys are in the draft: a reload restores the same rows, not the mispairing.
      await page.reload({ waitUntil: 'networkidle' });
      await switchTab(page, 'server');
      await expectDbRowIsTheBuilds('after a reload');
      await expect(page.locator('#serverUnsavedPill')).toHaveText('5 unsaved changes');
    });

  test('[U] what needs no series is still decided live on a capped KPI', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.toggleEnabled(W._keyOf('many-instances'));
    });
    // Parked is parked, whatever the payload holds — no reason to withhold it.
    await expect(rowsNamed(page, 'many-instances').locator('.wir-chip')).toHaveText('DISABLED');
  });
});

test.describe('A retired key is shown, refused and removable in the editor (R1-11)', () => {
  test('[U] the KPI stays INVALID until the key is removed, and the Save drops it', async ({ page }) => {
    await openServer(page);
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(JSON.stringify((window as any).globalChartData.annotationsDocument));
      doc.serverMetrics.asserts.find((k: any) => k.id === 'lag-spike').sustainedFor = '5s';
      W.document.adopt(doc);
      W.serverKpis.reread();
    });
    // Java: INVALID_RULE — a top-level "sustainedFor" is ignored. The mirror judged it FAIL.
    await expect(rowsNamed(page, 'lag-spike').locator('.wir-chip')).toHaveText('INVALID');

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('lag-spike'));
      // The fix the sentence asks for: a tolerance under reduce.over...
      W.set('over', 'sustainedFor');
      W.set('overValue', '5s');
    });
    // ...is not enough while the key is still there: the form says why, and offers the way out.
    await expect(page.locator('#serverEditor .wir-retired-keys'))
      .toContainText('"sustainedFor" at the top level of a server assert is ignored and belongs under reduce.over');
    await expect(page.locator('#serverEditor button.wir-btn-primary')).toBeDisabled();

    await page.locator('#serverEditor button', { hasText: 'Remove "sustainedFor"' }).click();
    await expect(page.locator('#serverEditor .wir-retired-keys')).toHaveCount(0);
    await expect(page.locator('#serverEditor button.wir-btn-primary')).toBeEnabled();
    await page.evaluate(() => (window as any).WIR.serverKpis.applyEditor());

    const written = (await writtenKpis(page)).find((k: any) => k.id === 'lag-spike');
    expect(written.sustainedFor, 'the retired key survived the Save').toBeUndefined();
    expect(written.reduce).toEqual({ kind: 'max', over: { sustainedFor: '5s' } });
    await expect(rowsNamed(page, 'lag-spike').locator('.wir-chip')).not.toHaveText('INVALID');
  });
});

test.describe('A client rule with no id is named the way Java names it (R1-3, R2-3)', () => {
  test('[U] divideBy "run-throughput" resolves on the board and in the editor', async ({ page }) => {
    await openServer(page);
    await switchTab(page, 'asserts');
    // load:throughput loses its id — Java calls it run-throughput — and cpu-per-rps names that.
    await page.evaluate(() => {
      const W = (window as any).WIR;
      W.asserts.openDocument();
      const doc = JSON.parse(W.document.text());
      delete doc.assertions.find((a: any) => a.id === 'load:throughput').id;
      doc.serverMetrics.asserts.find((k: any) => k.id === 'cpu-per-rps').divideBy = 'run-throughput';
      W.asserts.setDocumentText(JSON.stringify(doc, null, 2));
      W.asserts.applyDocument();
    });
    await switchTab(page, 'server');

    const row = (await board(page)).find((r) => r.id === 'cpu-per-rps');
    // The verdict Java takes: cpu's maximum over the run's measured throughput, far under 10000.
    // `not INVALID` alone also passed on NOT EVALUATED or LOAD NOT ACHIEVED (review:C).
    expect(row.display, 'judged in CI, INVALID on the board: "not a declared client rule"').toBe('PASS');
    expect(row.notJudged).toBeNull();
    expect(row.live, 'judged here, not borrowed from the build').toBe(true);

    await page.evaluate(() => {
      const W = (window as any).WIR.serverKpis;
      W.edit(W._keyOf('cpu-per-rps'));
    });
    const select = page.locator('#serverEditor select[data-field="divideBy"]');
    await expect(select).toHaveValue('run-throughput');
    const options = await select.locator('option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value));
    expect(options).toContain('run-throughput');
    expect(options.filter((o) => o === ''), 'an id-less rule appeared as a blank option').toHaveLength(1);
  });
});
