import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/*
 * "Show transaction details" (4.2.35). It was a button in the Samplers table's toolbar, but what it
 * decides is used on every tab: the chips in the filter bar's Samplers row, the lines the charts draw,
 * which errors the Errors tab's sampler filter shows, the Samplers rows. So on the Timeline the charts
 * could leave the requests inside transactions out with nothing there to say so or to change it. It is
 * a checkbox in the Samplers row now, after All / None, on every tab that shows that row, and the
 * table's toolbar has none: one switch. Its id stays transactionChildrenBtn.
 */

const SWITCH = '#transactionChildrenBtn';

async function chipNames(page: Page): Promise<string[]> {
  return page.locator('#samplerFilterList label[data-sampler-name]').evaluateAll((ls: HTMLElement[]) =>
    ls.filter(l => l.style.display !== 'none').map(l => l.querySelector('.sampler-name')!.textContent!.trim()));
}

test.describe('Show transaction details, in the Samplers row [E31]', () => {
  test('it sits after All / None in the filter bar, and the table toolbar has no switch of its own', async ({ page }) => {
    await loadClean(page, 'E31');
    await switchTab(page, 'timeline');
    const sw = page.locator(SWITCH);
    await expect(sw).toBeVisible();
    await expect(sw).toContainText('Show transaction details');
    expect(await sw.evaluate(el => !!el.closest('#filterRow3')), 'in the Samplers row').toBe(true);
    await expect(page.locator(`${SWITCH} input[type="checkbox"]`)).not.toBeChecked();
    await switchTab(page, 'samplers');
    expect(await page.locator('#tab-samplers #transactionChildrenBtn, #tab-samplers .column-toggle-btn', { hasText: 'Transaction Details' }).count())
      .toBe(0);
  });

  test('ticked on the Timeline, the chips, the charts and the Samplers table all follow', async ({ page }) => {
    await loadClean(page, 'E31');
    await switchTab(page, 'timeline');
    const before = await chipNames(page);
    expect(before).not.toContain('GET messages (search)');
    const seriesBefore: string[] = await page.evaluate(() => (window as any).getVisibleSamplerNames());
    expect(seriesBefore).not.toContain('GET messages (search)');

    await page.locator(SWITCH).click();
    await page.waitForTimeout(300);
    await expect(page.locator(`${SWITCH} input[type="checkbox"]`)).toBeChecked();
    expect(await chipNames(page)).toContain('GET messages (search)');
    const seriesAfter: string[] = await page.evaluate(() => (window as any).getVisibleSamplerNames());
    expect(seriesAfter, 'the charts draw the requests inside transactions').toContain('GET messages (search)');

    await switchTab(page, 'samplers');
    const shown = await page.locator('#samplerTable tbody tr[data-sampler="GET messages (search)"]')
      .evaluate((r: HTMLElement) => r.style.display !== 'none');
    expect(shown, 'the table follows the same switch').toBe(true);
  });

  test('the choice survives a reload', async ({ page }) => {
    await loadClean(page, 'E31');
    await page.locator(SWITCH).click();
    await page.waitForTimeout(200);
    await page.reload();
    await page.waitForTimeout(500);
    await expect(page.locator(`${SWITCH} input[type="checkbox"]`)).toBeChecked();
    expect(await chipNames(page)).toContain('GET messages (search)');
  });
});

test.describe('Show transaction details keeps in step [E31]', () => {
  test('the checkbox says the state whoever flips it, and takes the box\'s own value', async ({ page }) => {
    await loadClean(page, 'E31');
    const box = page.locator(`${SWITCH} input[type="checkbox"]`);
    await page.evaluate(() => (window as any).toggleTransactionChildrenVisibility());
    await expect(box).toBeChecked();
    // the box's own value wins over a flip: a box the browser restored ticked stays ticked when ticked
    await page.evaluate(() => (window as any).toggleTransactionChildrenVisibility(true));
    await expect(box).toBeChecked();
    expect(await page.evaluate(() => (window as any).showTransactionChildren)).toBe(true);
    await box.click();
    await expect(box).not.toBeChecked();
    expect(await page.evaluate(() => (window as any).showTransactionChildren)).toBe(false);
  });

  test('a flip keeps the search: a Σ Total row the search hides stays hidden', async ({ page }) => {
    await loadClean(page, 'E31');
    await switchTab(page, 'samplers');
    await page.locator('#samplerFilterList label[data-sampler-name="σ total"]').click();
    await expect(page.locator('#samplerTable tr.total-sampler-row')).toBeVisible();
    await page.locator('#samplerSearch').fill('stale');
    await page.waitForTimeout(150);
    await expect(page.locator('#samplerTable tr.total-sampler-row')).toBeHidden();
    await page.locator(SWITCH).click();
    await page.waitForTimeout(200);
    await expect(page.locator('#samplerTable tr.total-sampler-row'), 'the flip rebuilt it shown').toBeHidden();
  });
});

test.describe('Show transaction details, on a run without transactions [E05]', () => {
  test('there is nothing to show, so there is no switch', async ({ page }) => {
    await loadClean(page, 'E05');
    await expect(page.locator(SWITCH)).toBeHidden();
  });
});
