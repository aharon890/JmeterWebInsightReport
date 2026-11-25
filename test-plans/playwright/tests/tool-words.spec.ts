import { test, expect, Page } from '@playwright/test';
import { loadClean, switchTab } from './helpers';

/**
 * A k6 run's report uses k6's words (spec 001, decision 7, FR-007, SC-004, task T052).
 *
 * K1 is a k6 run built by `build-report --from k6`. Every tab it shows, the header, the tab bar, the filter bar and
 * every title, placeholder and label a reader can point at are swept for JMeter's words: "JMeter", Sampler, Thread,
 * Transaction (Controller). Three things are allowed, and are cut out before the sweep:
 *   - exception 1, the product's name: "JMeter Web Insight Suite" / "JMeter Web Insight Report", and its JAR's;
 *   - exception 2, the one sentence saying the numbers follow JMeter's Aggregate Report rules;
 *   - the annotations file's own key names, quoted as keys ("sampler", "samplers", maxThreads).
 * A JMeter run keeps today's words: spot checks over E02 and E31 below, and the before/after comparison of every tab
 * recorded in the pull request.
 */

const FORBIDDEN: Array<[string, RegExp]> = [
  ['JMeter', /JMeter/],
  ['Sampler', /sampler/i],
  ['Thread', /\bthreads?\b/i],
  ['Transaction', /transaction/i],
];

const ALLOWED = [
  'JMeter Web Insight Suite',
  'JMeter Web Insight Report',
  'jmeter-web-insight-report',
  "Numbers follow JMeter's Aggregate Report rules.",
  '"sampler"', '"samplers"', 'maxThreads',
];

function offending(text: string): string[] {
  let t = text;
  for (const a of ALLOWED) t = t.split(a).join(' ');
  const found: string[] = [];
  for (const line of t.split('\n')) {
    for (const [word, re] of FORBIDDEN) {
      if (re.test(line)) found.push(`${word}: ${line.trim().slice(0, 160)}`);
    }
  }
  return found;
}

/** What a reader can see or point at on the page now: visible text and every title, placeholder and label. */
async function readerText(page: Page): Promise<string> {
  return page.evaluate(() => {
    const parts: string[] = [];
    for (const sel of ['header', '#tabNav', '.filter-bar', 'footer']) {
      document.querySelectorAll(sel).forEach((e) => parts.push((e as HTMLElement).innerText));
    }
    const active = document.querySelector('.tab-panel.active') as HTMLElement | null;
    // The Server tab's catalogue cards describe the servers (a database's transactions, a JVM's threads), not the
    // load tool: their words are the server's own
    let text = active ? active.innerText : '';
    document.querySelectorAll('.wir-type-cards .wir-dim').forEach((e) => {
      text = text.split((e as HTMLElement).innerText).join(' ');
    });
    if (active) parts.push(text);
    document.querySelectorAll('[title],[placeholder],[aria-label]').forEach((e) => {
      parts.push(e.getAttribute('title') || '', e.getAttribute('placeholder') || '', e.getAttribute('aria-label') || '');
    });
    return parts.join('\n');
  });
}

test.describe('A k6 run uses k6\'s words', () => {
  for (const id of ['K1', 'K2']) test(`no JMeter word on any tab of ${id}, outside the two exceptions`, async ({ page }) => {
    // K2 (spec 002): the k6 tab's thresholds, timings, toolbars and groups are in k6's words too
    await loadClean(page, id);
    const tabs = await page.$$eval('.tab-btn', (bs) => bs.filter((b) => (b as HTMLElement).offsetParent !== null)
      .map((b) => b.getAttribute('data-tab') as string));
    expect(tabs).toContain('samplers');
    const found: string[] = [];
    for (const tab of tabs) {
      await switchTab(page, tab);
      for (const f of offending(await readerText(page))) found.push(`[${tab}] ${f}`);
    }
    // The Errors tab's two other views and the Columns and Filters menus are text a reader opens
    await switchTab(page, 'errors');
    for (const btn of await page.$$('#errorViewToggle button')) {
      if (await btn.isVisible()) {
        await btn.click();
        for (const f of offending(await readerText(page))) found.push(`[errors view] ${f}`);
      }
    }
    expect([...new Set(found)], 'JMeter words a reader sees on a k6 run').toEqual([]);
  });

  test('K1 names k6 in its header and uses Requests, VU, Max VUs and Group', async ({ page }) => {
    await loadClean(page, 'K1');
    await expect(page.locator('header .report-subtitle')).toContainText('| k6');
    await expect(page.locator('.tab-btn[data-tab="samplers"]')).toHaveText('Requests');
    await expect(page.locator('#tab-summary .card-label', { hasText: 'Max VUs' })).toHaveCount(1);
    await expect(page.locator('#samplerTable thead th').first()).toHaveText(/^Request/);
    await expect(page.locator('label', { hasText: 'Show group details' })).toHaveCount(1);
    await switchTab(page, 'errors');
    const heads = await page.$$eval('#tab-errors th', (ths) => ths.map((t) => (t as HTMLElement).innerText.trim()));
    expect(heads).toContain('VU');
  });

  test('a JMeter run keeps its words: Samplers, Max Threads, Thread, Show transaction details', async ({ page }) => {
    await loadClean(page, 'E31');
    await expect(page.locator('header .report-subtitle')).toContainText('| JMeter ');
    await expect(page.locator('.tab-btn[data-tab="samplers"]')).toHaveText('Samplers');
    await expect(page.locator('#tab-summary .card-label', { hasText: 'Max Threads' })).toHaveCount(1);
    await expect(page.locator('#samplerTable thead th').first()).toHaveText(/^Sampler/);
    await expect(page.locator('label', { hasText: 'Show transaction details' })).toHaveCount(1);
    await loadClean(page, 'E02');
    await switchTab(page, 'errors');
    const heads = await page.$$eval('#tab-errors th', (ths) => ths.map((t) => (t as HTMLElement).innerText.trim()));
    expect(heads).toContain('Thread');
    expect(heads).not.toContain('VU');
  });
});
