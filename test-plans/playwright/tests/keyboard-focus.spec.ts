import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Focus that survives a re-render, and selects that are never rebuilt under the keyboard
 * (plans/server-tab-followups.md, Batch 5) — on both tabs, because plan §3 asks for one keyboard
 * and focus behaviour across them.
 *
 * SF29. The Server board and the Asserts table are rebuilt from their state on every action, and
 * the rebuild replaced the control that had just been pressed: after a ▸, a status chip, Expand
 * all, Disable, Duplicate or Delete, focus was on <body>, and the next Tab started from the top of
 * the tab. After a Delete, Undo was 86 Tabs away, behind a toast that hides in 8 s. Save, in
 * either editor, dropped focus the same way — on the client because closeEditor focused the Edit
 * button and render() then replaced it.
 *
 * SF30. The editors rebuilt a select on its own change. Chromium's type-ahead belongs to the
 * select on the page, so "la" in Reduce committed "l" (last), then searched afresh for "a" (avg);
 * and every arrow key on a closed Metric select is a change on Windows and Linux, so browsing it
 * reset the reducer and across the author had chosen to each metric's catalogue defaults.
 *
 * The older specs press with .click() and set values with .fill() and selectOption(), which is
 * why all of this was green. These drive the real keyboard.
 */

async function open(page: Page, fixture: string, tab: string) {
  await loadClean(page, fixture);
  await switchTab(page, tab);
}

function boardRow(page: Page, key: string) {
  return page.locator(`#serverBoard tr.wir-kpi-row[data-kpi-key="${key}"]`);
}

function boardAction(page: Page, key: string, text: string | RegExp) {
  return boardRow(page, key).locator('.wir-row-actions button', { hasText: text });
}

function ruleRow(page: Page, id: string) {
  return page.locator(`#assertsTable tr[data-rule-id="${id}"]`);
}

/** Enter on `control`, by the keyboard. */
async function press(page: Page, control: ReturnType<Page['locator']>) {
  await control.focus();
  await page.keyboard.press('Enter');
}

/** The row after this one on its card (else the one before), as the board drew it. */
function neighbourKey(page: Page, key: string) {
  return boardRow(page, key).evaluate((tr) => {
    const rows = Array.from(tr.parentElement!.querySelectorAll('tr.wir-kpi-row'));
    const at = rows.indexOf(tr);
    return (rows[at + 1] || rows[at - 1]).getAttribute('data-kpi-key');
  });
}

/** Label a control, so a replaced one can be told from the one that was focused. */
async function mark(control: ReturnType<Page['locator']>) {
  await control.evaluate((el) => { (el as any).wirMark = 'the control the keyboard is on'; });
}

async function expectSameNode(control: ReturnType<Page['locator']>, why: string) {
  expect(await control.evaluate((el) => (el as any).wirMark === 'the control the keyboard is on'),
    `${why}: the select was replaced under the keyboard`).toBe(true);
}

test.describe('SF29: focus stays on the control that was pressed', () => {
  test('[U] the Server board: ▸, the name, a status chip, Expand all, Disable and Duplicate keep focus', async ({ page }) => {
    await open(page, 'E26', 'server');
    const toggle = boardRow(page, 'k1').locator('.wir-row-toggle');
    const name = boardRow(page, 'k1').locator('.wir-kpi-name');
    await press(page, toggle);
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(toggle).toBeFocused();
    // The next Tab goes on along the row, not back to the top of the tab.
    await page.keyboard.press('Tab');
    await expect(name).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(name, 'the ▸ and the name call one handler; the name is the one pressed').toBeFocused();

    const failChip = page.locator('#serverBoard .wir-filter-chip', { hasText: /^FAIL \d+$/ });
    await press(page, failChip);
    await expect(failChip).toHaveClass(/wir-on/);
    await expect(failChip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(failChip).not.toHaveClass(/wir-on/);
    await expect(failChip).toBeFocused();

    const expand = page.locator('#serverExpandAll');
    await press(page, expand);
    await expect(expand).toHaveText('Collapse all rows');
    await expect(expand).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(expand).toHaveText('Expand all rows');
    await expect(expand).toBeFocused();

    const disable = boardAction(page, 'k0', /^(Disable|Enable)$/);
    await press(page, disable);
    await expect(disable).toHaveText('Enable');
    await expect(disable).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(disable).toHaveText('Disable');
    await expect(disable).toBeFocused();

    const rows = page.locator('#serverBoard tr.wir-kpi-row');
    const before = await rows.count();
    const duplicate = boardAction(page, 'k0', 'Duplicate');
    await press(page, duplicate);
    await expect(rows).toHaveCount(before + 1);
    await expect(duplicate).toBeFocused();
  });

  test('[U] Server Delete: focus is on Undo at once, the toast stays while it has focus, Tab goes on from the row, Undo comes back to Delete', async ({ page }) => {
    await open(page, 'E26', 'server');
    const undo = page.locator('#wirUndoBtn');
    const toast = page.locator('#wir-undo-toast');

    await press(page, boardAction(page, 'k2', 'Delete'));
    await expect(boardRow(page, 'k2')).toHaveCount(0);
    await expect(undo, 'Undo has to be reachable without 86 Tabs').toBeFocused();
    // Undo by the keyboard: the row is back, and so is focus, on the Delete that was pressed.
    await page.keyboard.press('Enter');
    await expect(boardRow(page, 'k2')).toHaveCount(1);
    await expect(boardAction(page, 'k2', 'Delete')).toBeFocused();

    // Again, and this time leave the toast: past the 8 s it would hide in, it is still up while
    // it has focus; Tab then goes on from where the row was — the next row's ▸, never its Delete.
    const next = await neighbourKey(page, 'k2');
    await page.clock.install();
    await page.keyboard.press('Enter');
    await expect(undo).toBeFocused();
    await page.clock.runFor(9000);
    await expect(toast).toHaveClass(/visible/);
    await page.keyboard.press('Tab');
    await expect(boardRow(page, next!).locator('.wir-row-toggle')).toBeFocused();
    // Once it has faded it is out of the tab order: an invisible Undo is one key from reverting a
    // delete nobody can see.
    await page.clock.runFor(3000);
    await expect(toast).not.toHaveClass(/visible/);
    await expect(undo).toBeHidden();
  });

  test('[U] the Asserts table: Duplicate, Disable, the matches toggle and a status chip keep focus; Delete hands it to Undo', async ({ page }) => {
    await open(page, 'E25', 'asserts');
    const duplicate = ruleRow(page, 'slow-api-p95').locator('.wir-dup-btn');
    await press(page, duplicate);
    await expect(ruleRow(page, 'slow-api-p95-copy')).toHaveCount(1);
    await expect(duplicate).toBeFocused();

    const disable = ruleRow(page, 'slow-api-p95').locator('.wir-toggle-btn');
    await press(page, disable);
    await expect(disable).toHaveText('Enable');
    await expect(disable).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(disable).toHaveText('Disable');
    await expect(disable).toBeFocused();

    const matches = ruleRow(page, 'slow-api-p95').locator('.wir-matches-btn');
    await press(page, matches);
    await expect(page.locator('#assertsTable tr.wir-match-row[data-match-of="slow-api-p95"]').first()).toBeVisible();
    await expect(matches).toBeFocused();

    // Delete: Undo at once; Undo by the keyboard puts the row back and focus on its Delete.
    const undo = page.locator('#wirUndoBtn');
    await press(page, ruleRow(page, 'moderate-api-p95').locator('.wir-del-btn'));
    await expect(ruleRow(page, 'moderate-api-p95')).toHaveCount(0);
    await expect(undo).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(ruleRow(page, 'moderate-api-p95')).toHaveCount(1);
    await expect(ruleRow(page, 'moderate-api-p95').locator('.wir-del-btn')).toBeFocused();
    // Again, and Tab out of the toast: back to the next row's Edit, never its Delete.
    const ids = await page.locator('#assertsTable tr[data-rule-id]').evaluateAll((trs) => trs.map((t) => t.getAttribute('data-rule-id')));
    const next = ids[ids.indexOf('moderate-api-p95') + 1];
    await page.keyboard.press('Enter');
    await expect(undo).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(ruleRow(page, next!).locator('.wir-edit-btn')).toBeFocused();

    // A status chip, last: it filters the rows the presses above were on.
    const chip = page.locator('.wir-status-chip').first();
    await press(page, chip);
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    await expect(chip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(chip).toHaveAttribute('aria-pressed', 'false');
    await expect(chip).toBeFocused();
  });

  test('[U] a held Enter on Undo puts the row back once — its auto-repeat never presses the Delete Undo focused, on both tabs', async ({ page }) => {
    // Undo restored the row and focused its Delete inside the first keydown, so the key's
    // auto-repeat landed on that Delete and deleted the row again: 11 -> 12 -> 11 rows.
    const holdEnter = async () => {
      await page.keyboard.down('Enter');
      await page.keyboard.down('Enter');   // repeat: true, as a held key sends them
      await page.keyboard.down('Enter');
      await page.keyboard.up('Enter');
      await page.waitForTimeout(150);
    };
    await open(page, 'E26', 'server');
    const rows = page.locator('#serverBoard tr.wir-kpi-row');
    const before = await rows.count();
    await press(page, boardAction(page, 'k2', 'Delete'));
    await expect(rows).toHaveCount(before - 1);
    await expect(page.locator('#wirUndoBtn')).toBeFocused();
    await holdEnter();
    await expect(rows, 'the held Enter deleted the restored row again').toHaveCount(before);
    await expect(boardRow(page, 'k2')).toHaveCount(1);
    await expect(boardAction(page, 'k2', 'Delete')).toBeFocused();
    // The guard ends with the key: the next press is a press.
    await page.keyboard.press('Enter');
    await expect(boardRow(page, 'k2')).toHaveCount(0);

    await open(page, 'E25', 'asserts');
    const rules = page.locator('#assertsTable tr[data-rule-id]');
    const ruleCount = await rules.count();
    await press(page, ruleRow(page, 'moderate-api-p95').locator('.wir-del-btn'));
    await expect(rules).toHaveCount(ruleCount - 1);
    await expect(page.locator('#wirUndoBtn')).toBeFocused();
    await holdEnter();
    await expect(rules, 'the held Enter deleted the restored rule again').toHaveCount(ruleCount);
    await expect(ruleRow(page, 'moderate-api-p95').locator('.wir-del-btn')).toBeFocused();
  });

  test('[U] Tab or Escape from Undo, with nothing left beside the deleted row, lands on the tab — never <body>', async ({ page }) => {
    // Client: the last rule gone, the empty state is all there is, and it has no search box.
    await open(page, 'E25', 'asserts');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(W.document.text());
      doc.assertions = doc.assertions.filter((a: any) => a.id === 'slow-api-p95');
      W.document.replace(doc);
      W.asserts.rerender();
    });
    await expect(page.locator('#assertsTable tr[data-rule-id]')).toHaveCount(1);
    await press(page, ruleRow(page, 'slow-api-p95').locator('.wir-del-btn'));
    await expect(page.locator('#assertsEmpty')).toBeVisible();
    await expect(page.locator('#wirUndoBtn')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.locator('#emptyAddAssertBtn')).toBeFocused();

    // Server: the only assert on the "(no component)" card takes the card with it, and that card
    // never has a "+ metric".
    await open(page, 'E27', 'server');
    await page.evaluate(() => {
      const W = (window as any).WIR;
      const doc = JSON.parse(W.document.text());
      doc.serverMetrics.asserts.push({ id: 'ghost-up', component: 'ghost', query: 'up',
        reduce: { kind: 'max' }, fail: { gt: 1 } });
      W.document.replace(doc);
      W.serverKpis.rerender();
    });
    const ghost = await page.evaluate(() => (window as any).WIR.serverKpis._keyOf('ghost-up'));
    await press(page, boardAction(page, ghost, 'Delete'));
    await expect(boardRow(page, ghost)).toHaveCount(0);
    await expect(page.locator('#wirUndoBtn')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.locator('#serverAddComponent')).toBeFocused();
  });

  test('[U] F5: a toast withdrawn while Undo has focus hands focus to where Tab would have gone — never <body>, on both tabs', async ({ page }) => {
    // An edit that does not take focus with it — the notes debounce, a drag-retune — withdraws an
    // Undo the keyboard is on. Withdrawn, the toast is hidden, so focus cannot stay on it (SF29).
    const undo = page.locator('#wirUndoBtn');
    const toast = page.locator('#wir-undo-toast');

    await open(page, 'E25', 'asserts');
    const ids = await page.locator('#assertsTable tr[data-rule-id]').evaluateAll((trs) => trs.map((t) => t.getAttribute('data-rule-id')));
    const next = ids[ids.indexOf('moderate-api-p95') + 1];
    await press(page, ruleRow(page, 'moderate-api-p95').locator('.wir-del-btn'));
    await expect(undo).toBeFocused();
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('fast-api-p95')); });
    await expect(toast).not.toHaveClass(/visible/);
    await expect(ruleRow(page, next!).locator('.wir-edit-btn')).toBeFocused();

    await open(page, 'E26', 'server');
    const after = await neighbourKey(page, 'k2');
    await press(page, boardAction(page, 'k2', 'Delete'));
    await expect(undo).toBeFocused();
    await page.evaluate(() => (window as any).WIR.serverKpis.toggleEnabled('k0'));
    await expect(toast).not.toHaveClass(/visible/);
    await expect(boardRow(page, after!).locator('.wir-row-toggle')).toBeFocused();
  });

  test('[U] Save with the filters hiding the saved row puts focus on Clear filters and says why, on both tabs', async ({ page }) => {
    // The FAIL chip on, the failing assert loosened, Save: the saved row's Edit is not drawn, and
    // the opener was that same Edit — focus fell to <body> on both tabs.
    await open(page, 'E26', 'server');
    await press(page, page.locator('#serverBoard .wir-filter-chip', { hasText: /^FAIL \d+$/ }));
    await expect(boardRow(page, 'k1')).toHaveCount(1);
    await press(page, boardAction(page, 'k1', 'Edit'));
    await page.locator('#serverEditor [data-field="fail.value"]').fill('999999999');
    await press(page, page.locator('#serverEditorSave'));
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    await expect(boardRow(page, 'k1'), 'loosened, it is no longer a FAIL row').toHaveCount(0);
    await expect(page.locator('#serverFilterChips .wir-clear-filters')).toBeFocused();
    await expect(page.locator('#wir-toast')).toContainText('hidden by the current filters');

    await open(page, 'E25', 'asserts');
    await press(page, page.locator('.wir-status-chip[data-status="FAIL"]'));
    await expect(ruleRow(page, 'slow-api-p95')).toHaveCount(1);
    await press(page, ruleRow(page, 'slow-api-p95').locator('.wir-edit-btn'));
    await page.locator('#editorfailValue').fill('999999999');
    await press(page, page.locator('#editorSave'));
    await expect(page.locator('#assertEditor .wir-slideover')).toHaveCount(0);
    await expect(ruleRow(page, 'slow-api-p95')).toHaveCount(0);
    await expect(page.locator('#assertsClearFilters')).toBeFocused();
    await expect(page.locator('#wir-toast')).toContainText('hidden by the current filters');
  });

  test('[U] Save returns focus to the saved row, on both editors — a renamed client rule included', async ({ page }) => {
    await open(page, 'E26', 'server');
    await press(page, boardAction(page, 'k0', 'Edit'));
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
    await page.locator('#serverEditor [data-field="description"]').click();
    await page.keyboard.press('End');
    await page.keyboard.type('!');
    await press(page, page.locator('#serverEditorSave'));
    await expect(page.locator('#serverEditor .wir-slideover')).toHaveCount(0);
    await expect(boardAction(page, 'k0', 'Edit')).toBeFocused();

    await open(page, 'E25', 'asserts');
    await press(page, ruleRow(page, 'slow-api-p95').locator('.wir-edit-btn'));
    await expect(page.locator('#assertEditor .wir-slideover')).toBeVisible();
    await page.locator('#editorDescription').click();
    await page.keyboard.press('End');
    await page.keyboard.type(' x');
    await press(page, page.locator('#editorSave'));
    await expect(page.locator('#assertEditor .wir-slideover')).toHaveCount(0);
    await expect(ruleRow(page, 'slow-api-p95').locator('.wir-edit-btn')).toBeFocused();

    // Renamed: the row is found by the id it was saved under.
    await press(page, ruleRow(page, 'slow-api-p95').locator('.wir-edit-btn'));
    await page.locator('#editorId').click();
    await page.keyboard.press('Control+A');
    await page.keyboard.type('slow-api-p95-renamed');
    await press(page, page.locator('#editorSave'));
    await expect(ruleRow(page, 'slow-api-p95-renamed').locator('.wir-edit-btn')).toBeFocused();
  });
});

test.describe('SF30: a focused select is never rebuilt', () => {
  async function editOrdersDb(page: Page) {
    await open(page, 'E27', 'server');
    await page.click('.wir-component-card[data-component="orders-db"] button[onclick*="WIR.serverKpis.edit"]');
    await expect(page.locator('#serverEditor .wir-slideover')).toBeVisible();
  }

  test('[U] typing a prefix into a server editor select lands where a plain select would', async ({ page }) => {
    await editOrdersDb(page);
    const kind = page.locator('#serverEditor-kind');
    await mark(kind);
    await kind.focus();
    await page.keyboard.type('la', { delay: 40 });
    await expect(kind, '"la" committed l (last), then a fresh search for a').toHaveValue('last');
    await page.waitForTimeout(1100);   // Chromium's type-ahead ends after a second
    await page.keyboard.type('p9', { delay: 40 });
    await expect(kind).toHaveValue('p90');
    await expectSameNode(kind, 'Reduce');
    await expect(kind).toBeFocused();

    // The Metric select reads in labels (Q11): "rep" is Replication lag, not R(olled-back),
    // then E(xporter), then nothing for p.
    const metric = page.locator('#serverEditor-metric');
    await mark(metric);
    await metric.focus();
    await page.keyboard.type('rep', { delay: 40 });
    await expect(metric).toHaveValue('replication.lag.seconds');
    await expectSameNode(metric, 'Metric');
    await expect(metric).toBeFocused();
  });

  test('[U] arrowing through the Metric select keeps the reducer and across chosen; leaving it on another metric applies that metric\'s defaults', async ({ page }) => {
    await editOrdersDb(page);
    const kind = page.locator('#serverEditor-kind');
    const across = page.locator('#serverEditor-across');
    const metric = page.locator('#serverEditor-metric');
    await expect(metric).toHaveValue('connections.used.pct');
    await kind.selectOption('p95');
    await across.selectOption('avg');

    // Down to Active connections and back up: every arrow is a change, and none is a pick.
    await metric.focus();
    await page.keyboard.press('ArrowDown');
    await expect(metric).toHaveValue('connections.active');
    await page.keyboard.press('ArrowUp');
    await expect(metric).toHaveValue('connections.used.pct');
    await expect(kind, 'browsing the metrics reset the reducer the author chose').toHaveValue('p95');
    await expect(across).toHaveValue('avg');
    // Left on the metric it started from: nothing was picked.
    await page.keyboard.press('Tab');
    await expect(kind).toHaveValue('p95');
    await expect(across).toHaveValue('avg');

    // Up to `up` and leave it there: that is a pick, and it starts from the catalogue (min, min).
    await metric.focus();
    await page.keyboard.press('ArrowUp');
    await expect(metric).toHaveValue('up');
    await expect(kind).toHaveValue('p95');
    await page.keyboard.press('Tab');
    await expect(kind).toHaveValue('min');
    await expect(across).toHaveValue('min');
    // A pick from the list (not the keyboard) is a pick at once, as it always was.
    await metric.selectOption('connections.active');
    await expect(kind).toHaveValue('max');
    await expect(across).toHaveValue('max');
  });

  test('[U] after arrowing the Metric select to another metric, a mouse click on Cancel at human speed still closes the editor', async ({ page }) => {
    // Leaving the select commits the browse on the next task — between the mousedown on Cancel and
    // its mouseup — and the foot was rebuilt around the focused control, which it kept only by id.
    // Cancel had none: it was replaced under the pointer, no click fired, and focus went to the
    // header's ×, whose key it shared. Playwright's instant click.() never sees it.
    await editOrdersDb(page);
    const metric = page.locator('#serverEditor-metric');
    await metric.focus();
    await page.keyboard.press('ArrowDown');
    await expect(metric).toHaveValue('connections.active');
    const box = (await page.locator('#serverEditorFoot .wir-btn', { hasText: 'Cancel' }).boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.waitForTimeout(100);
    await page.mouse.up();
    await expect(page.locator('#serverEditor .wir-slideover'), 'Cancel did nothing').toHaveCount(0);
    // Back on the Edit that opened it, not on a × inside a dialog that is gone.
    expect(await page.evaluate(() => (document.activeElement?.getAttribute('onclick') || '').trim()))
      .toMatch(/^WIR\.serverKpis\.edit\(/);
  });

  test('[U] a "More filters" select takes a typed prefix — the table is rebuilt around it', async ({ page }) => {
    // setFilter re-rendered the whole table host, so "p99" committed p, then searched afresh, and
    // landed on p90 with focus on a twin of the select.
    await open(page, 'E25', 'asserts');
    const more = page.locator('#assertsMoreFilters');
    await press(page, more);
    // The kept button takes the new label: the press was what changed it.
    await expect(more).toContainText('Fewer filters');
    await expect(more).toBeFocused();
    const metric = page.locator('#assertsFilter-metric');
    await mark(metric);
    await metric.focus();
    await page.keyboard.type('p99', { delay: 40 });
    await expect(metric, '"p99" committed p, then a fresh search').toHaveValue('p99');
    await expectSameNode(metric, 'More filters: Metric');
    await expect(metric).toBeFocused();
    await expect(page.locator('#assertsRulesCount')).toContainText('Showing ');
  });

  /*
   * U1. fillKeepingFocus and replaceKeepingFocus rebuild around the focused control by walking its
   * path and its twin's in step (rebuildAround). With the twin at another place — another depth, or
   * another tag on the way — that walk threw half way through a rebuild (2+ levels deeper), or
   * finished it wrong without a word: the twin's wrapper dropped, the control's id given to a
   * wrapper, its options moved out of the select. No caller produces such a shape today (0 in
   * ~17,700 instrumented calls over E25-E27), so the pin drives the two functions directly, over
   * markup of each shape. A shape that does not match is the plain replace they already fall back
   * to, with the caller's focus restore applying.
   */
  test('[U] U1: a twin at another place than the focused control is a plain replace — no throw, nothing stray', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await loadClean(page, 'E26');
    const results = await page.evaluate(() => {
      const O = '<option value="1">one</option><option value="2">two</option>';
      const W = window as any;
      /* What `container.innerHTML = html` leaves in a container of this tag, in this context. */
      function plainFill(build: () => HTMLElement, html: string) {
        const c = build();
        c.innerHTML = html;
        const out = c.innerHTML;
        c.closest('[data-u1]')!.remove();
        return out;
      }
      function holder() {
        const h = document.createElement('div');
        h.setAttribute('data-u1', '');
        document.body.appendChild(h);
        return h;
      }
      const out: any[] = [];
      function fill(name: string, live: string, next: string, table?: string) {
        const build = () => {
          const h = holder();
          if (!table) {
            h.innerHTML = live;
            return h;
          }
          h.innerHTML = '<table>' + live + '</table>';
          return h.querySelector(table) as HTMLElement;
        };
        const expected = plainFill(build, next);
        const c = build();
        (c.querySelector('#x') as HTMLElement).focus();
        let threw = null;
        try {
          W.fillKeepingFocus(c, next);
        } catch (e) {
          threw = String(e);
        }
        out.push({ name, threw, html: c.innerHTML, expected });
        c.closest('[data-u1]')!.remove();
      }
      function replace(name: string, live: string, next: string) {
        const expectedHost = holder();
        expectedHost.innerHTML = next;
        const expected = expectedHost.innerHTML;
        expectedHost.remove();
        const h = holder();
        h.innerHTML = live;
        (h.querySelector('#x') as HTMLElement).focus();
        let threw = null;
        try {
          W.replaceKeepingFocus(h.firstElementChild, next);
        } catch (e) {
          threw = String(e);
        }
        out.push({ name, threw, html: h.innerHTML, expected });
        h.remove();
      }
      fill('same depth, another tag on the path', '<span><select id="x">' + O + '</select></span>',
        '<p><select id="x">' + O + '</select></p>');
      fill('the twin deeper', '<select id="x">' + O + '</select>',
        '<label><span>New</span><select id="x">' + O + '</select><em>t</em></label>');
      fill('the focused control 1 level deeper', '<label><select id="x">' + O + '</select></label>',
        '<select id="x">' + O + '</select><p>after</p>');
      fill('the focused control 2 levels deeper', '<div><label><select id="x">' + O + '</select></label></div>',
        '<select id="x">' + O + '</select><p>after</p>');
      replace('replaceKeepingFocus, the focused control 2 levels deeper',
        '<div id="sec"><div><label><select id="x">' + O + '</select></label></div></div>',
        '<div id="sec"><select id="x">' + O + '</select></div>');
      fill('a duplicate id, its first copy at another depth', '<label><select id="x">' + O + '</select></label>',
        '<select id="x">' + O + '</select><div><label><select id="x">' + O + '</select></label></div>');
      fill('a THEAD container', '<thead><tr><th>A<button id="x">x</button></th></tr></thead>',
        '<tr><th>B<button id="x">y</button></th></tr>', 'thead');
      fill('a TR container', '<tbody><tr><td>A<button id="x">x</button></td></tr></tbody>',
        '<td>B<button id="x">y</button></td>', 'tr');
      return out;
    });
    // Every shape at once, so a red run names each one that went wrong and how.
    const wrong = results.filter((r) => r.threw !== null || r.html !== r.expected)
      .map((r) => r.threw !== null ? `${r.name}: threw half way through a rebuild: ${r.threw}`
        : `${r.name}: left ${r.html} where a plain replace leaves ${r.expected}`);
    expect(wrong).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('[U] U1: a twin at the same place is still rebuilt around — the focused select is the same node', async ({ page }) => {
    // The guard must fall back only on a mismatch: the SF30 type-ahead fix is this path.
    await loadClean(page, 'E26');
    const r = await page.evaluate(() => {
      const O = (a: string) => '<option value="1">' + a + '</option><option value="2">two</option>';
      const h = document.createElement('div');
      document.body.appendChild(h);
      h.innerHTML = '<label class="f"><span>Old</span><select id="x">' + O('one') + '</select></label><p>old</p>';
      const keep = h.querySelector('#x') as HTMLSelectElement;
      keep.focus();
      (window as any).fillKeepingFocus(h, '<label class="f"><span>New</span><select id="x">' + O('uno')
        + '</select></label><p>new</p>');
      const now = h.querySelector('#x') as HTMLSelectElement;
      const result = {
        same: now === keep,
        focused: document.activeElement === keep,
        label: h.querySelector('span')!.textContent,
        option: now.options[0].textContent,
        after: h.querySelector('p')!.textContent,
      };
      h.remove();
      return result;
    });
    expect(r).toEqual({ same: true, focused: true, label: 'New', option: 'uno', after: 'new' });
  });

  test('[U] the Asserts editor\'s Metric select takes a typed prefix', async ({ page }) => {
    await open(page, 'E25', 'asserts');
    await page.click('tr[data-rule-id="slow-api-p95"] .wir-edit-btn');
    const metric = page.locator('#editorMetric');
    await expect(metric).toHaveValue('p95');
    await mark(metric);
    await metric.focus();
    await page.keyboard.type('ma', { delay: 40 });
    await expect(metric, '"ma" committed m (mean), then a fresh search for a (apdex)').toHaveValue('max');
    await expectSameNode(metric, 'the client Metric');
    await expect(metric).toBeFocused();
  });
});
