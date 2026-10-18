import { test, expect } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * Sampler-table sorting and copy feedback (FE-4 / FE-6 / FE-7, CORE-4).
 */

test.describe('Sampler table — text sort', () => {
  test('[R] numeric cells are parsed with their units stripped', async ({ page }) => {
    await loadClean(page, 'E02');
    const parsed = await page.evaluate(() => {
      const f = (window as any).parseNumericCell;
      return {
        thousands: f('1,234'),
        percent: f('12.3%'),
        rate: f('4.5/s'),
        withUnit: f('120 ms'),
        plain: f('42'),
        empty: f(''),
        text: f('n/a'),
      };
    });
    expect(parsed.thousands).toBe(1234);
    expect(parsed.percent).toBeCloseTo(12.3, 6);
    expect(parsed.rate).toBeCloseTo(4.5, 6);
    expect(parsed.withUnit).toBe(120);
    expect(parsed.plain).toBe(42);
    expect(parsed.empty).toBe(0);
    expect(parsed.text).toBe(0);
  });

  test('[C/R] sampler names sort alphabetically on the real label', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Plant names whose ordering the old [,%/s] normalizer got wrong: it stripped the
    // letter "s" and "/", so "signup" compared as "ignup" and sorted under "i".
    const planted = ['zebra', 'signup', 'apple', 'search'];
    await page.evaluate((names: string[]) => {
      const rows = Array.from(
        document.querySelectorAll('#samplerTable tbody tr[data-sampler]:not(.total-sampler-row)')
      ).slice(0, names.length);
      rows.forEach((row, i) => {
        const cell = row.querySelector('.sampler-name-text') || row.querySelector('td');
        if (cell) cell.textContent = names[i];
      });
    }, planted);

    // Sort by the Sampler column (logical column id 0, text).
    await page.evaluate(() => (window as any).sortTable(0, 'text'));
    await page.waitForTimeout(200);

    const order = await page.evaluate((names: string[]) => {
      const rows = Array.from(
        document.querySelectorAll('#samplerTable tbody tr[data-sampler]:not(.total-sampler-row)')
      );
      return rows
        .map((r) => {
          const c = r.querySelector('.sampler-name-text') || r.querySelector('td');
          return c ? c.textContent!.trim() : '';
        })
        .filter((t) => names.includes(t));
    }, planted);

    expect(order).toEqual(['apple', 'search', 'signup', 'zebra']);
  });

  test('[C/R] numeric column sorts by value, not by string', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const samplesColId = await page.evaluate(() => {
      const ths = Array.from(document.querySelectorAll('#samplerTable thead th'));
      const idx = ths.findIndex((th) => /^Samples/i.test(th.textContent!.trim()));
      return idx >= 0 ? parseInt(ths[idx].getAttribute('data-col-id') || String(idx), 10) : -1;
    });
    test.skip(samplesColId < 0, 'Samples column not present');

    await page.evaluate((id: number) => (window as any).sortTable(id, 'num'), samplesColId);
    await page.waitForTimeout(200);

    const values = await page.evaluate((id: number) => {
      const table = document.getElementById('samplerTable') as HTMLTableElement;
      const header = (window as any).findHeaderByColId(table, id);
      const col = header.cellIndex;
      return Array.from(table.tBodies[0].rows)
        .filter((r) => !r.classList.contains('sampler-detail-row') && !r.classList.contains('total-sampler-row'))
        .map((r) => (window as any).parseNumericCell(r.cells[col].textContent.trim()));
    }, samplesColId);

    const sorted = [...values].sort((a, b) => a - b);
    expect(values).toEqual(sorted);
  });
});

test.describe('Column order restore', () => {
  test('[C/R] a saved order is applied using stable column ids', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const result = await page.evaluate(() => {
      const w = window as any;
      const table = document.getElementById('samplerTable') as HTMLTableElement;
      w.tagColumnIds(table);

      const n = table.tHead!.rows[0].cells.length;
      // Move the last column to the front.
      const order = [n - 1, ...Array.from({ length: n - 1 }, (_, i) => i)];
      w.applyColumnOrder(table, order);

      return Array.from(table.tHead!.rows[0].cells).map((c) =>
        parseInt(c.getAttribute('data-col-id')!, 10)
      );
    });

    expect(result[0]).toBe(result.length - 1);
    expect(result.slice(1)).toEqual(Array.from({ length: result.length - 1 }, (_, i) => i));
  });
});

test.describe('Copy feedback', () => {
  test('[C/R] copying a response body shows a toast and raises no error', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));

    await loadClean(page, 'E07'); // 100% errors, so error detail rows exist
    await switchTab(page, 'errors');

    const shown = await page.evaluate(async () => {
      const w = window as any;
      // Stub the clipboard so the test does not depend on browser permissions.
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: () => Promise.resolve() },
      });

      const pre = document.createElement('pre');
      pre.id = 'wir-test-body';
      pre.setAttribute('data-raw', 'response body text');
      document.body.appendChild(pre);

      w.copyResponseBody('wir-test-body');
      await new Promise((r) => setTimeout(r, 200));

      const toast = document.getElementById('wir-toast');
      return {
        exists: !!toast,
        visible: !!toast && toast.classList.contains('visible'),
        text: toast ? toast.textContent : null,
      };
    });

    expect(shown.exists, 'a toast element should be created').toBe(true);
    expect(shown.visible).toBe(true);
    expect(shown.text).toContain('copied');
    expect(errors, 'copyResponseBody must not throw ReferenceError').toEqual([]);
  });

  test('[C/R] a blocked clipboard reports failure instead of rejecting unhandled', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));

    await loadClean(page, 'E07');

    const text = await page.evaluate(async () => {
      const w = window as any;
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: () => Promise.reject(new Error('denied')) },
      });

      const pre = document.createElement('pre');
      pre.id = 'wir-test-body-fail';
      pre.setAttribute('data-raw', 'x');
      document.body.appendChild(pre);

      w.copyResponseBody('wir-test-body-fail');
      await new Promise((r) => setTimeout(r, 200));
      const toast = document.getElementById('wir-toast');
      return toast ? toast.textContent : null;
    });

    expect(text).toContain('Copy failed');
    expect(errors).toEqual([]);
  });
});

test.describe('Column drag after a restored order', () => {
  test('[C/R] dragging moves the column under the cursor, not the one at its old index',
      async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const result = await page.evaluate(() => {
      const w = window as any;
      const table = document.getElementById('samplerTable') as HTMLTableElement;
      w.tagColumnIds(table);
      const head = table.tHead!.rows[0];
      const n = head.cells.length;

      const ids = () => Array.from(head.cells)
        .map((c) => parseInt(c.getAttribute('data-col-id')!, 10));

      // Restore a non-identity order: last column moved to the front. After this the
      // handlers' attach-time indices no longer match the physical positions.
      const restored = [n - 1, ...Array.from({ length: n - 1 }, (_, i) => i)];
      w.applyColumnOrder(table, restored);
      const before = ids();

      // Now simulate the drag the handler performs: take whatever is physically at
      // position 3 and drop it at position 1. reorderColumns is what the drop handler
      // calls, with the LIVE cell indices.
      const movedId = before[3];
      w.reorderColumns(table, head.cells[3].cellIndex, head.cells[1].cellIndex, 'wir_dragProbe');
      const after = ids();

      return { before, after, movedId, landedAt: after.indexOf(movedId) };
    });

    // The column that was physically third must now sit second.
    expect(result.landedAt).toBe(1);
    // And the drag must not have been a no-op or moved something else.
    expect(result.after).not.toEqual(result.before);
    expect([...result.after].sort((a, b) => a - b))
      .toEqual([...result.before].sort((a, b) => a - b));
  });
});
