import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import * as path from 'path';
import { loadClean, getReportPath } from './helpers';

/**
 * AF5: the Summary tab's Copy Verdict, beside Copy Summary.
 *
 * Copy Summary copies the summary cards and the top five, and neither verdict. Copy Verdict writes
 * the verdict as a few plain lines — the header's name and time, the client verdict in the header
 * badge's word with the gate strip's counts, the server chip's verdict when server asserts are
 * declared, the strip's "Draft differs" line when it shows one, and the report's file name — read
 * off the page, never judged again. js-unit/verdict-block.test.js holds the text; this holds the
 * button to what the page shows.
 */

/** Click Copy Verdict with the clipboard stubbed, and hand back what it wrote. */
async function copyVerdict(page: Page): Promise<string[]> {
  await page.evaluate(() => {
    const w = window as any;
    w.__copied = null;
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text: string) => { w.__copied = text; } }
    });
  });
  await page.click('#copyVerdictBtn');
  // Copy Summary's feedback, on this button — asked at once: the label reverts after two seconds.
  // Every click, a second one inside those two seconds included.
  await expect(page.locator('#copyVerdictBtn')).toHaveText('Copied!');
  await expect.poll(() => page.evaluate(() => (window as any).__copied)).not.toBeNull();
  return String(await page.evaluate(() => (window as any).__copied)).split('\n');
}

const shown = async (page: Page, selector: string) =>
  ((await page.locator(selector).first().textContent()) || '').replace(/\s+/g, ' ').trim();

/** "Asserts: <badge word> — <N rules declared>: <the strip's counts>", from the header and the strip. */
async function assertsLine(page: Page) {
  const badge = await shown(page, '.header-badge .wir-asserts-chip');
  const sub = await shown(page, '#gateStrip .wir-gate-sub');
  const declared = /^\d+ rules? declared/.exec(sub);
  expect(declared, sub).not.toBeNull();
  const counts = (await page.locator('#gateStrip .wir-gate-counts .wir-count').allTextContents())
    .map((c) => c.replace(/\s+/g, ' ').trim());
  return `Asserts: ${badge.replace(/^ASSERTS /, '')} — ${declared![0]}: ${counts.join(', ')}`;
}

test.describe('AF5: Copy Verdict', () => {
  test('[U] on E25 the block is the header, the client verdict as the strip counts it, and the file', async ({ page }) => {
    await loadClean(page, 'E25');
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    const button = page.locator('#tab-summary #copyVerdictBtn');
    await expect(button).toBeVisible();
    await expect(button).toHaveText('Copy Verdict');

    const lines = await copyVerdict(page);
    expect(lines.length).toBeLessThanOrEqual(6);
    expect(lines[0]).toBe(`${await shown(page, '.report-header h1')} — ${await shown(page, '#reportGeneratedAt')}`);
    expect(lines[1]).toBe(await assertsLine(page));
    expect(lines[1].startsWith('Asserts: FAIL — ')).toBe(true);
    // E25 declares no server assert, and nothing is drafted.
    expect(await page.locator('.wir-server-chip').count()).toBe(0);
    expect(lines.filter((l) => l.startsWith('Server:'))).toEqual([]);
    expect(lines.filter((l) => l.startsWith('Draft differs'))).toEqual([]);
    expect(lines[lines.length - 1]).toBe(path.basename(getReportPath('E25')));
    // ...and the label comes back.
    await expect(button).toHaveText('Copy Verdict', { timeout: 5000 });
    expect(errors).toEqual([]);
  });

  test('[U] a draft adds the strip\'s line, and moves no count — the block speaks for the run as gated', async ({ page }) => {
    await loadClean(page, 'E25');
    const before = await copyVerdict(page);
    await page.evaluate(() => (window as any).WIR.asserts._retune('slow-api-p95', 99999));
    await expect(page.locator('#gateStripDraft')).toHaveText('Draft differs — see Asserts');
    const after = await copyVerdict(page);
    expect(after[1]).toBe(before[1]);
    expect(after).toContain('Draft differs — see Asserts');
    expect(after.length).toBeLessThanOrEqual(6);
    // Two clicks, likely inside the feedback's two seconds: the label still comes back.
    await expect(page.locator('#copyVerdictBtn')).toHaveText('Copy Verdict', { timeout: 5000 });
  });

  test('[U] on E26 the server line is the chip\'s verdict, as chartData.serverVerdict gives it', async ({ page }) => {
    await loadClean(page, 'E26');
    const v = await page.evaluate(() => (window as any).globalChartData.serverVerdict);
    expect(v, 'E26 is judged against its payload').toBeTruthy();
    const word = v.status === 'NOT_EVALUATED' ? 'NOT EVALUATED' : v.status;
    const lines = await copyVerdict(page);
    const server = lines.filter((l) => l.startsWith('Server:'));
    expect(server).toEqual([`Server: ${word}` + (v.label ? ` — ${v.label}` : '')
      + (v.othersText ? ` · ${v.othersText}` : '')]);
    // E26 is a fixed fixture: kafka FAILs and db WARNs. Pinned as text, not from serverVerdict.othersText -
    // the field the line is built from, so a wrong count there passed the line above (Stage 6, the audit).
    expect(server).toEqual(['Server: FAIL — Kafka · 1 warn']);
    // The same word and component the header chip shows, and the same counts by state (5.5, B2).
    const chip = await shown(page, '.wir-server-chip');
    expect(chip).toBe('Server · Kafka FAIL · 1 warn');
    if (v.label) expect(chip).toContain(`${v.label} ${word}`);
    expect(lines[1]).toBe(await assertsLine(page));
    expect(lines.length).toBeLessThanOrEqual(6);
  });
});
