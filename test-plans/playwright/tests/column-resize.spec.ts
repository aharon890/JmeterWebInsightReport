import { test, expect } from '@playwright/test';
import { loadClean, loadDirty, switchTab } from './helpers';

/**
 * Sampler-table column resizing.
 *
 * The table renders with `table-layout: auto`, so the first drag has to pin every
 * column at its current width and switch to fixed layout — otherwise widening one
 * column silently re-flows all the others. These tests cover that freeze, the
 * interaction with the two behaviours the header already had (sort on click,
 * drag-to-reorder), and the persistence round trip.
 */

const HEADER = '#samplerTable thead th';

/** Width of the header at `index`, as rendered. */
async function colWidth(page: any, index: number): Promise<number> {
  return page.evaluate(
    (i: number) => Math.round(
      document.getElementById('samplerTable')!.tHead!.rows[0].cells[i].getBoundingClientRect().width,
    ),
    index,
  );
}

/** Every visible header width, for asserting neighbours did not move. */
async function allWidths(page: any): Promise<number[]> {
  return page.evaluate(() =>
    Array.from(document.getElementById('samplerTable')!.tHead!.rows[0].cells)
      .filter(th => getComputedStyle(th as HTMLElement).display !== 'none')
      .map(th => Math.round((th as HTMLElement).getBoundingClientRect().width)));
}

/** Drag the divider on header `index` by `dx` pixels. */
async function dragDivider(page: any, index: number, dx: number): Promise<void> {
  const handle = page.locator(HEADER).nth(index).locator('.col-resize-handle');
  const box = await handle.boundingBox();
  if (!box) throw new Error(`no resize handle on header ${index}`);
  const y = box.y + box.height / 2;
  const x = box.x + box.width / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx, y, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(200);
}

test.describe('Sampler table — default column layout', () => {
  test('[R] the sampler column keeps a usable width and does not ellipsise its names', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // The regression this guards: every numeric column is `white-space: nowrap`,
    // which leaves the sampler column as the only one auto layout can shrink, and
    // its name span is `flex: 1; min-width: 0` — a zero min-content contribution.
    // Nothing stopped an engine sizing the column from that and collapsing it to
    // just the assert badge, rendering every name as "GET /api/...".
    expect(await colWidth(page, 0)).toBeGreaterThanOrEqual(260);

    const truncated = await page.evaluate(() => {
      const span = document
        .getElementById('samplerTable')!
        .tBodies[0].rows[0].cells[0].querySelector('.sampler-name-text') as HTMLElement;
      return span.scrollWidth > span.clientWidth + 1;
    });
    expect(truncated).toBe(false);
  });

  test('[R] the truncation rules are bound to the sampler column, not to :first-child', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Reordering used to move the ellipsis onto whichever column landed first.
    const hasClass = await page.evaluate(() =>
      document.getElementById('samplerTable')!.tBodies[0].rows[0].cells[0]
        .classList.contains('sampler-name-td'));
    expect(hasClass).toBe(true);
  });

  test('[C] every header carries a resize handle', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const headers = await page.locator(HEADER).count();
    const handles = await page.locator(`${HEADER} .col-resize-handle`).count();
    expect(handles).toBe(headers);
  });
});

test.describe('Sampler table — resizing', () => {
  test('[U] dragging a divider resizes that column and leaves its neighbours alone', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const before = await colWidth(page, 0);
    const neighboursBefore = (await allWidths(page)).slice(1);

    await dragDivider(page, 0, 150);

    expect(await colWidth(page, 0)).toBeCloseTo(before + 150, -1);
    expect((await allWidths(page)).slice(1)).toEqual(neighboursBefore);
  });

  test('[U] the first drag freezes the table into fixed layout', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    expect(await page.evaluate(() =>
      getComputedStyle(document.getElementById('samplerTable')!).tableLayout)).toBe('auto');

    await dragDivider(page, 0, 80);

    const frozen = await page.evaluate(() => {
      const t = document.getElementById('samplerTable')!;
      return { cls: t.classList.contains('cols-frozen'), layout: getComputedStyle(t).tableLayout };
    });
    expect(frozen.cls).toBe(true);
    expect(frozen.layout).toBe('fixed');
  });

  test('[U] grabbing the divider does not sort the column', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // The header is a sort button; the divider sits on top of it and must swallow
    // the click that ends the drag.
    await dragDivider(page, 0, 60);
    const sort = await page.evaluate(() =>
      document.getElementById('samplerTable')!.tHead!.rows[0].cells[0].getAttribute('data-sort'));
    expect(sort).toBeNull();
  });

  test('[U] clicking the header body still sorts after a resize', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    await dragDivider(page, 0, 60);
    await page.locator(HEADER).nth(1).click();
    await page.waitForTimeout(200);

    const sort = await page.evaluate(() =>
      document.getElementById('samplerTable')!.tHead!.rows[0].cells[1].getAttribute('data-sort'));
    expect(['asc', 'desc']).toContain(sort);
  });

  test('[U] a column cannot be dragged below its minimum width', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    await dragDivider(page, 0, -2000);
    expect(await colWidth(page, 0)).toBeGreaterThanOrEqual(56);
  });
});

test.describe('Sampler table — auto-fit', () => {
  test('[U] double-clicking a divider fits the column to its widest value', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    // Pin the column narrow first. Measuring has to happen against an unpinned
    // table — under fixed layout a cell reports the width it was pinned to, so a
    // naive auto-fit would just re-apply the narrow width.
    await page.evaluate(() => {
      const t = document.getElementById('samplerTable')!;
      (window as any).freezeColumnWidths(t);
      (window as any).setColumnWidth(t.tHead!.rows[0].cells[0], 70);
      (window as any).syncTableWidth(t);
    });
    await page.waitForTimeout(150);
    expect(await colWidth(page, 0)).toBeLessThan(100);

    const neighboursBefore = (await allWidths(page)).slice(1);
    await page.locator(HEADER).first().locator('.col-resize-handle').dblclick();
    await page.waitForTimeout(250);

    expect(await colWidth(page, 0)).toBeGreaterThan(200);
    expect((await allWidths(page)).slice(1)).toEqual(neighboursBefore);

    const truncated = await page.evaluate(() => {
      const span = document
        .getElementById('samplerTable')!
        .tBodies[0].rows[0].cells[0].querySelector('.sampler-name-text') as HTMLElement;
      return span.scrollWidth > span.clientWidth + 1;
    });
    expect(truncated).toBe(false);
  });
});

test.describe('Sampler table — width persistence', () => {
  test('[C/R] a dragged width survives a reload', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    await dragDivider(page, 0, 120);
    const pinned = await colWidth(page, 0);

    await loadDirty(page, 'E02');
    await switchTab(page, 'samplers');

    expect(await colWidth(page, 0)).toBeCloseTo(pinned, -1);
  });

  test('[D] Reset Widths appears once frozen and restores the automatic layout', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    const original = await colWidth(page, 0);
    await expect(page.locator('#resetWidthsBtn')).toBeHidden();

    await dragDivider(page, 0, 140);
    await expect(page.locator('#resetWidthsBtn')).toBeVisible();

    await page.locator('#resetWidthsBtn').click();
    await page.waitForTimeout(250);

    const after = await page.evaluate(() => {
      const t = document.getElementById('samplerTable')!;
      return { cls: t.classList.contains('cols-frozen'), layout: getComputedStyle(t).tableLayout };
    });
    expect(after.cls).toBe(false);
    expect(after.layout).toBe('auto');
    expect(await colWidth(page, 0)).toBeCloseTo(original, -1);
    await expect(page.locator('#resetWidthsBtn')).toBeHidden();
  });

  test('[U] hiding a column shrinks the frozen table by exactly that column', async ({ page }) => {
    await loadClean(page, 'E02');
    await switchTab(page, 'samplers');

    await dragDivider(page, 0, 60);

    const measured = await page.evaluate(() => {
      const t = document.getElementById('samplerTable')!;
      return {
        table: Math.round(t.getBoundingClientRect().width),
        col: Math.round(t.tHead!.rows[0].cells[13].getBoundingClientRect().width),
      };
    });

    await page.evaluate(() => {
      (window as any).hiddenColumns[13] = true;
      (window as any).applyColumnVisibility();
    });
    await page.waitForTimeout(200);

    const after = await page.evaluate(() =>
      Math.round(document.getElementById('samplerTable')!.getBoundingClientRect().width));
    expect(measured.table - after).toBeCloseTo(measured.col, -1);
  });
});
