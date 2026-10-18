import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import { loadClean, switchTab, getReportPath } from './helpers';

/**
 * N3's page half (tmp/client-number-precision.md §2): how the Asserts tab writes a client number
 * beside a band, and what the Budget bar judges. On E26's four near-band client rules
 * (test-plans/server-metrics/e26-server.json), whose means the client's rounding put on or across
 * their bands — three within a hundredth, the whole-run mean 0.09 under its whole-ms band:
 *
 *   checkout-mean-on-band     POST /api/checkout mean 373.8625  fail > 373.86          FAIL
 *   run-mean-whole-ms         whole-run mean      234.9117  fail > 235             PASS
 *   search-mean-gte-on-band   GET /api/search mean    211.405   fail >= 211.41         PASS
 *   products-mean-warns       GET /api/products mean  119.4675  warn > 119.46, fail > 1000  WARN
 *
 * Two defects, one cause each:
 *
 *   - The Budget bar is a judgement, and it judged two decimals of the actual. Java shipped each
 *     match's actual to the page as round2 — 373.86 under `gt 373.86` — so on load the FAIL drew
 *     green and the gte PASS red, while the JSON's attainment.breached said the opposite. One
 *     unrelated edit re-judged the run uncapped and swapped both to the right colour; Discard
 *     drafts restored the payload and swapped them back. The page now gets Java's double.
 *   - The text rounded the actual to the client's display precision (whole ms) and the band with
 *     it: "mean 374 ms vs > 374 ms" on a FAIL, "fail ≥ 211 ms" for 211.41, "warn > 119 ms" for
 *     119.46, "run aggregate 235 ms" under "fail > 235 ms" on a PASS. A band is now written with
 *     every digit it has, and an actual gains digits only where the base would put it on or across
 *     a band the rule declares (WIR.digits, held to Java's Digits by digits-vectors.json).
 *
 * The pure half — every format, the vectors — is in js-unit (asserts-format.test.js, digits.test.js).
 */

const BUDGET_RULES = ['checkout-mean-on-band', 'run-mean-whole-ms', 'search-mean-gte-on-band'];

/** Each Budget rule's bar colour, by id. */
async function bars(page: Page): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const id of BUDGET_RULES) {
    const cls = await page.locator(`#assertsTable tr[data-rule-id="${id}"] .wir-budget-fill`).getAttribute('class');
    out[id] = ((cls || '').match(/wir-budget-(ok|warn|over)/) || ['none'])[0];
  }
  return out;
}

/** The colour Java's attainment calls for: over where it says the fail band was breached. */
function javasBars(): Record<string, string> {
  const json = JSON.parse(fs.readFileSync(getReportPath('E26').replace(/\.html$/, '.json'), 'utf8'));
  const out: Record<string, string> = {};
  for (const id of BUDGET_RULES) {
    const rule = json.assertions.find((a: any) => a.id === id);
    out[id] = rule.attainment.breached ? 'wir-budget-over' : 'wir-budget-ok';
  }
  return out;
}

/** A rule row's Criteria and Actual cells. */
async function cells(page: Page, id: string): Promise<[string, string]> {
  const row = page.locator(`#assertsTable tr[data-rule-id="${id}"]`);
  return [
    (await row.locator('td.wir-criteria').innerText()).trim(),
    (await row.locator('td.wir-num:not(.wir-budget)').innerText()).trim(),
  ];
}

/** Every value-line label on the Timeline's mean chart: the rule lines and the run aggregate. */
async function meanChartLabels(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    // @ts-ignore
    const chart = echarts.getInstanceByDom(document.getElementById('response-time-chart'));
    const out: string[] = [];
    for (const s of chart.getOption().series) {
      for (const d of (s.markLine && s.markLine.data) || []) {
        if (d.yAxis !== undefined && d.label) out.push(d.label.formatter);
      }
    }
    return out;
  });
}

async function runAggregateLabel(page: Page): Promise<string | undefined> {
  return (await meanChartLabels(page)).find((l) => l.startsWith('run aggregate'));
}

test.describe('Client numbers beside a band [E26]', () => {

  test('[R] N3: a Budget bar draws Java\'s verdict — on load, after an unrelated edit, after Discard', async ({ page }) => {
    const expected = javasBars();
    // Java's own answer, so the colours below are checked against a judge, not against this spec:
    // the FAIL breached its band, and neither PASS did.
    expect(expected).toEqual({
      'checkout-mean-on-band': 'wir-budget-over',
      'run-mean-whole-ms': 'wir-budget-ok',
      'search-mean-gte-on-band': 'wir-budget-ok',
    });

    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    expect(await bars(page), 'on load').toEqual(expected);

    // products-mean-warns, not load:throughput: cpu-per-rps divides by load:throughput, so an
    // edit to it would move the Server tab under this test.
    const toggle = page.locator('#assertsTable tr[data-rule-id="products-mean-warns"] .wir-toggle-btn');
    await toggle.click();
    expect(await bars(page), 'after an unrelated Disable, re-judged by the page').toEqual(expected);

    await page.click('#discardDraftsBtn');
    await expect(page.locator('#assertsTable tr[data-rule-id="products-mean-warns"]')).not.toHaveClass(/wir-row-disabled/);
    expect(await bars(page), 'after Discard drafts, the payload restored').toEqual(expected);

    await toggle.click();
    await toggle.click();
    expect(await bars(page), 'after Disable then Enable').toEqual(expected);
  });

  test('[R] L14: a Budget is never written on 100% for a rule that is not on its line — in the JSON or on the page', async ({ page }) => {
    // 373.8625 over `gt 373.86` is 1.0000067 of its budget, a FAIL; 211.405 under `gte 211.41` is
    // 0.9999763, a PASS. round4 wrote both as consumed 1.0 and Math.round labelled both "100%" — one
    // number for two verdicts, and the number that means "exactly on the line". Each is now written
    // beside the line (Digits.beside(consumed, 4, 1.0), and WIR.digits.beside at whole percents).
    const json = JSON.parse(fs.readFileSync(getReportPath('E26').replace(/\.html$/, '.json'), 'utf8'));
    const consumed = (id: string) => json.assertions.find((a: any) => a.id === id).attainment.consumed;
    expect(consumed('checkout-mean-on-band')).toBe(1.00001);
    expect(consumed('search-mean-gte-on-band')).toBe(0.99998);
    expect(consumed('run-mean-whole-ms'), 'clear of the line: four places, as ever').toBe(0.9996);

    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    const label = (id: string) => page.locator(`#assertsTable tr[data-rule-id="${id}"] .wir-budget-pct`);
    await expect(label('checkout-mean-on-band')).toHaveText('100.001%');
    await expect(label('search-mean-gte-on-band')).toHaveText('99.998%');
    await expect(label('run-mean-whole-ms')).toHaveText('99.96%');
    await expect(label('products-mean-warns'), 'clear of the line: whole percents, as ever').toHaveText('12%');
    // The title says the same number as the cell.
    expect(await page.locator('#assertsTable tr[data-rule-id="checkout-mean-on-band"] td.wir-budget')
      .getAttribute('title')).toContain('100.001% of the budget');
  });

  test('[R] N3: an actual is never written on or across a band, and a band is written as declared', async ({ page }) => {
    await loadClean(page, 'E26');

    // The Summary strip's worst breaches: the actual against the band that decided it.
    const breach = (id: string) => page.locator('#gateStrip .wir-breach', { hasText: id }).locator('.wir-breach-detail');
    await expect(breach('checkout-mean-on-band')).toHaveText('mean 374 ms vs > 373.86 ms');
    await expect(breach('products-mean-warns')).toHaveText('mean 119.5 ms vs > 119.46 ms');

    await switchTab(page, 'asserts');
    // 374 is above 373.86 as 373.8625 is, so the whole-ms base stands; 234.9 and 119.5 are the
    // digits that keep a PASS under 235 and a WARN over 119.46 on their own side.
    expect(await cells(page, 'checkout-mean-on-band')).toEqual(['fail > 373.86 ms', '374 ms']);
    expect(await cells(page, 'run-mean-whole-ms')).toEqual(['fail > 235 ms', '234.9 ms']);
    expect(await cells(page, 'search-mean-gte-on-band')).toEqual(['fail ≥ 211.41 ms', '211 ms']);
    expect(await cells(page, 'products-mean-warns')).toEqual(['warn > 119.46 ms · fail > 1,000 ms', '119.5 ms']);

    // The Budget cell's title writes the Actual cell's number, against the same bands.
    const budgetTitle = (id: string) =>
      page.locator(`#assertsTable tr[data-rule-id="${id}"] td.wir-budget`).getAttribute('title');
    expect(await budgetTitle('checkout-mean-on-band')).toContain('Worst measured 374 ms against fail > 373.86 ms');
    expect(await budgetTitle('run-mean-whole-ms')).toContain('Worst measured 234.9 ms against fail > 235 ms');
    expect(await budgetTitle('products-mean-warns')).toContain('Worst measured 119.5 ms against fail > 1,000 ms');

    // A rule's match rows, expanded: each sampler's actual, against the rule's bands.
    await page.locator('#assertsTable tr[data-rule-id="products-mean-warns"] .wir-matches-btn').click();
    await expect(page.locator('#assertsTable tr.wir-match-row[data-match-of="products-mean-warns"] td.wir-num'))
      .toHaveText('119.5 ms');

    // The samplers table: the cell's verdict title and the judging row quote the warn band the
    // WARN was decided against, labelled warn (B3) — not the fail band it never crossed.
    await switchTab(page, 'samplers');
    const products = '#samplerTable tbody tr[data-sampler="GET /api/products"]';
    expect(await page.locator(products + ' td[data-assert-cell="mean"] .wir-cell-verdict').getAttribute('title'))
      .toContain('119.5 ms vs > 119.46 ms');
    await page.locator(products + ' .assert-badge-mini').click();
    await expect(page.locator('.wir-judging-row .wir-judging', { hasText: 'products-mean-warns' }))
      .toContainText('mean = 119.5 ms · warn > 119.46 ms');

    // The Timeline's mean chart: the run aggregate against the whole-run rule drawn beside it.
    await switchTab(page, 'timeline');
    await page.waitForTimeout(700);
    expect(await meanChartLabels(page)).toContain('run aggregate 234.9 ms');

    // The drag toast places a band, so it writes one as Criteria will: a drag to 558.82 (FAIL)
    // and one to 559.3 (PASS) both read "→ 559 ms". No mouse: a drag fine enough to land on a
    // hundredth needs a chart thousands of pixels tall, and the text is what is under test.
    const toast = page.locator('#wir-toast');
    await page.evaluate(() => (window as any).WIR.asserts._previewDrag('checkout-mean-on-band', 558.82, 'responseTime', 'fail'));
    await expect(toast).toHaveText('checkout-mean-on-band fail → 558.82 ms');
    await page.evaluate(() => (window as any).WIR.asserts._previewDrag('checkout-mean-on-band', 559.3, 'responseTime', 'fail'));
    await expect(toast).toHaveText('checkout-mean-on-band fail → 559.3 ms');
  });

  test('[R] N3: the editor preview writes each actual against the bands as typed', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'asserts');
    const previewActual = page.locator('#editorPreview .wir-preview-row .wir-num');

    // As loaded: the WARN's own bands, as on its row.
    await page.locator('#assertsTable tr[data-rule-id="products-mean-warns"] .wir-edit-btn').click();
    await expect(page.locator('#editorPreview .wir-preview-row')).toContainText('GET /api/products');
    await expect(previewActual).toHaveText('119.5 ms');
    await page.locator('.wir-slideover-head .wir-link').click();
    await expect(page.locator('.wir-slideover')).toHaveCount(0);

    // As typed, not as saved: 373.8625 against a typed `gt 373.863` PASSes and is written below
    // it. Against the saved `gt 373.86` both would read "374 ms" — which is what 373.862 and
    // 373.863 both previewed, one FAIL and one PASS.
    await page.locator('#assertsTable tr[data-rule-id="checkout-mean-on-band"] .wir-edit-btn').click();
    await page.fill('#editorfailValue', '373.863');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('PASS');
    await expect(previewActual).toHaveText('373.86 ms');
    await page.fill('#editorfailValue', '373.862');
    await expect(page.locator('#editorPreviewVerdict')).toHaveText('FAIL');
    await expect(previewActual).toHaveText('374 ms');
  });

  test('[R] Q15: the run aggregate is written against the enabled whole-run rules on its chart, and no other', async ({ page }) => {
    await loadClean(page, 'E26');
    await switchTab(page, 'timeline');
    await page.waitForTimeout(700);
    expect(await runAggregateLabel(page)).toBe('run aggregate 234.9 ms');

    // Disabled, run-mean-whole-ms judges nothing, so its band no longer decides the digits.
    await page.evaluate(() => { const A = (window as any).WIR.asserts; A.toggleEnabled(A._keyOf('run-mean-whole-ms')); });
    await expect.poll(() => runAggregateLabel(page)).toBe('run aggregate 235 ms');

    // A sampler rule's band at 235 on the same chart is about POST /api/checkout's series, not
    // the run's: it must not give the aggregate a digit either.
    await page.evaluate(() => (window as any).WIR.asserts._retune('checkout-mean-on-band', 235, 'fail'));
    expect(await page.evaluate(() => (window as any).WIR.asserts._chartBands('responseTime')),
      'precondition: the sampler rule\'s band is drawn on this chart').toContainEqual(
      { id: 'checkout-mean-on-band', kind: 'fail', value: 235 });
    expect(await meanChartLabels(page)).toContain('checkout-mean-on-band fail > 235 ms');
    expect(await runAggregateLabel(page)).toBe('run aggregate 235 ms');
  });
});
