import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/*
 * The Samplers table as a tree (4.2.34). With Transaction Details on, a transaction's rows used to keep
 * the alphabetical order and only gain an indent, so each looked like it belonged to the row above:
 * on tails-conversation-flows the stale-link request sat under "Edit and pin my message", "Open the
 * conversation" under "Login". E31 is that plan's shape: "Open the conversation" (with its stale-link
 * request) and "Send and reply" run on their own and inside "Whole session"; "Search the thread" holds
 * three requests. The order itself is samplerTreeOrder's (js-unit/sampler-tree.test.js); this is
 * where the rows land in the page, and that every walker keeps them there.
 */

interface ShownRow { name: string; depth: number; child: boolean }

async function goSamplers(page: Page, testId = 'E31') {
  await loadClean(page, testId);
  await switchTab(page, 'samplers');
}

/** The sampler rows on screen, in order, with their depth. */
async function shownRows(page: Page): Promise<ShownRow[]> {
  return page.locator('#samplerTable tbody tr[data-sampler]').evaluateAll((rows: HTMLTableRowElement[]) =>
    rows.filter(r => r.style.display !== 'none' && !r.classList.contains('total-sampler-row')).map(r => ({
      name: r.getAttribute('data-sampler')!,
      depth: Number(r.style.getPropertyValue('--wir-depth') || '0'),
      child: r.classList.contains('transaction-child'),
    })));
}

/** Every shown child row sits under its own transaction: the nearest row above it one level up. */
async function expectEveryChildUnderItsTransaction(page: Page) {
  const rows = await shownRows(page);
  const parentOf: Record<string, string> = await page.evaluate(() => ({ ...(window as any).transactionChildNames }));
  let children = 0;
  rows.forEach((r, i) => {
    if (!r.child) return;
    children++;
    let j = i - 1;
    while (j >= 0 && rows[j].depth >= r.depth) j--;
    expect(j, `${r.name} has no row above it one level up`).toBeGreaterThanOrEqual(0);
    expect(rows[j].name, `${r.name} sits under the wrong row`).toBe(parentOf[r.name]);
    expect(rows[j].depth, `${r.name}'s depth`).toBe(r.depth - 1);
  });
  return children;
}

async function transactionDetails(page: Page, on: boolean) {
  const active = await page.locator('#transactionChildrenBtn').evaluate(b => b.classList.contains('active'));
  if (active !== on) {
    await page.locator('#transactionChildrenBtn').click();
    await page.waitForTimeout(200);
  }
}

test.describe('Samplers table as a tree [E31]', () => {
  test('with Transaction Details on, every request sits directly under its own transaction', async ({ page }) => {
    await goSamplers(page);
    await transactionDetails(page, true);
    const children = await expectEveryChildUnderItsTransaction(page);
    expect(children, 'E31 has requests inside transactions').toBeGreaterThanOrEqual(5);
    const rows = await shownRows(page);
    const open = rows.findIndex(r => r.name === 'Open the conversation');
    expect(rows[open + 1].name).toBe('GET conversation');
    expect(rows.find(r => r.name === 'GET conversation (stale link, fails on purpose)')!.depth).toBe(1);
  });

  test('a name that also runs on its own is a top-level row, and says where else it ran', async ({ page }) => {
    await goSamplers(page);
    const rows = await shownRows(page);
    expect(rows.map(r => r.name), 'shown with Transaction Details off').toContain('Open the conversation');
    const title = await page.locator('#samplerTable tbody tr[data-sampler="Open the conversation"] .sampler-name-text')
      .getAttribute('title');
    expect(title).toContain('Also runs inside: Whole session');
    const whole = await page.locator('#samplerTable tbody tr[data-sampler="Whole session"] .sampler-name-text')
      .getAttribute('title');
    expect(whole).toContain('Also contains: ');
    expect(whole).toContain('Open the conversation');
  });

  test('a sort orders the rows and keeps each request under its transaction', async ({ page }) => {
    await goSamplers(page);
    await transactionDetails(page, true);
    for (const header of ['Samples', 'Mean (ms)', 'Sampler']) {
      await page.locator('#samplerTable thead th', { hasText: header }).first().click();
      await page.waitForTimeout(150);
      await expectEveryChildUnderItsTransaction(page);
      const total = await page.locator('#samplerTable tbody tr').first().getAttribute('class');
      if (total && total.includes('total-sampler-row')) expect(total).toContain('total-sampler-row');
    }
  });

  test('a search keeps a matching request\'s transaction above it, with Transaction Details on or off', async ({ page }) => {
    await goSamplers(page);
    await transactionDetails(page, true);
    await page.locator('#samplerSearch').fill('stale');
    await page.waitForTimeout(150);
    expect((await shownRows(page)).map(r => r.name))
      .toEqual(['Open the conversation', 'GET conversation (stale link, fails on purpose)']);
    await transactionDetails(page, false);
    expect((await shownRows(page)).map(r => r.name), 'the transaction stands for its request')
      .toEqual(['Open the conversation']);
  });

  test('× on a transaction hides its requests with it, and the switch does not bring them back', async ({ page }) => {
    await goSamplers(page);
    await transactionDetails(page, true);
    const row = page.locator('#samplerTable tbody tr[data-sampler="Search the thread"]');
    await row.hover();
    await row.locator('.row-hide-btn, [onclick^="hideRow"]').first().click({ force: true });
    await page.waitForTimeout(150);
    let names = (await shownRows(page)).map(r => r.name);
    expect(names).not.toContain('Search the thread');
    expect(names).not.toContain('GET messages (search)');
    await transactionDetails(page, false);
    await transactionDetails(page, true);
    names = (await shownRows(page)).map(r => r.name);
    expect(names, 'the hidden transaction stays hidden').not.toContain('GET messages (search)');
    await expect(page.locator('#showHiddenRowsBtn')).toHaveText(/Show 1 Hidden Row/);
    await page.locator('#showHiddenRowsBtn').click();
    names = (await shownRows(page)).map(r => r.name);
    expect(names).toContain('Search the thread');
    expect(names).toContain('GET messages (search)');
    await expectEveryChildUnderItsTransaction(page);
  });

  test('the indent stays on the name cell when the Sampler column is dragged away from the first place', async ({ page }) => {
    // Review finding (2026-11-05): the indent and the parent's bold were on td:first-child
    await goSamplers(page);
    await transactionDetails(page, true);
    await page.evaluate(() => {
      const table = document.getElementById('samplerTable');
      (window as any).reorderColumns(table, 0, 2);
    });
    const cell = page.locator('#samplerTable tbody tr[data-sampler="GET conversation"] td.sampler-name-td');
    expect(await cell.evaluate(td => getComputedStyle(td).paddingLeft)).toBe('32px');
    const first = page.locator('#samplerTable tbody tr[data-sampler="GET conversation"] td').first();
    expect(await first.evaluate(td => td.classList.contains('sampler-name-td'))).toBe(false);
    expect(await first.evaluate(td => getComputedStyle(td).paddingLeft), 'not the column now first').not.toBe('32px');
  });

  test('the sampler chips follow the same order, and keep their colours', async ({ page }) => {
    await goSamplers(page);
    await transactionDetails(page, true);
    const chips: string[] = await page.locator('#samplerFilterList label[data-sampler-name]').evaluateAll(
      (ls: HTMLElement[]) => ls.map(l => l.querySelector('.sampler-name')!.textContent!.trim())
        .filter(n => n !== 'Σ Total'));
    const i = chips.indexOf('Search the thread');
    expect(chips.slice(i, i + 4)).toEqual(['Search the thread', 'GET messages (search)', 'GET people (search)', 'GET search facets']);
    const mismatched = await page.evaluate(() => {
      const w = window as any;
      const out: string[] = [];
      document.querySelectorAll('#samplerFilterList .sampler-color-dot[data-sampler]').forEach((dot: any) => {
        const name = dot.getAttribute('data-sampler');
        const idx = w.globalChartData.samplers.findIndex((s: any) => s.name === name);
        const probe = document.createElement('span');
        probe.style.background = w.getColorForSampler(name, idx);
        if (probe.style.background !== dot.style.background) out.push(name);
      });
      return out;
    });
    expect(mismatched, 'a chip whose colour is not its chart line\'s').toEqual([]);
  });
});

test.describe('Samplers table as a tree [E02]', () => {
  test('a transaction that sorts last still comes before its own requests', async ({ page }) => {
    // "TC — Browse Session" sorts after its requests by name, so they sat above it
    await goSamplers(page, 'E02');
    await transactionDetails(page, true);
    expect(await expectEveryChildUnderItsTransaction(page)).toBeGreaterThan(0);
  });
});
