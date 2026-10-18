import { test, expect } from '@playwright/test';
import { loadClean } from './helpers';

/**
 * How a threshold is written into a cell.
 *
 * This exists because of one shipped bug with an outsized consequence. Every branch of
 * `formatValue` rounded to a fixed number of decimals — sensible for the magnitudes a report
 * usually carries, and wrong at the bottom of the scale. A loadValidity floor of 0.0015 /s
 * rounded to one decimal, so the criteria cell read `fail < 0 /s`.
 *
 * That is not an aesthetic loss. `< 0 /s` is the exact signature of a band nothing can
 * breach — the dead-band defect fixed in the same release — so a correct, passing rule wore
 * the appearance of a broken one, while the validator stayed silent because the band was
 * genuinely reachable. The reader gets two contradictory signals and no way to choose.
 *
 * So the property under test is not "numbers look nice". It is: **a non-zero threshold never
 * renders as zero, in any unit.** Everything else here guards the fix against a regression in
 * the other direction — that the fix must not put a tail of decimals on ordinary numbers, and
 * must not turn a genuine zero into something else.
 *
 * Driven against the function rather than a rendered report on purpose: the interesting cases
 * are the extremes of the scale, one fixture per magnitude would be absurd, and the value
 * that actually shipped broken exists in exactly one fixture by luck.
 */

type Case = { value: number | null; unit: string; expected: string; why: string };

const CASES: Case[] = [
  // ---- the regression itself: small but real, in every unit that rounds ----
  { value: 0.0015, unit: '/s', expected: '0.0015 /s',
    why: "E25's loadValidity warn floor — this is the value that shipped as '0 /s'" },
  { value: 0.001, unit: '/s', expected: '0.001 /s',
    why: 'its fail floor, one decimal place further down' },
  { value: 0.004, unit: '%', expected: '0.004 %',
    why: 'an error-rate band below the 2-decimal cutoff' },
  { value: 0.4, unit: 'ms', expected: '0.4 ms',
    why: 'sub-millisecond: rounds to whole ms, so it vanished too' },
  { value: 0.000012, unit: '/s', expected: '0.000012 /s',
    why: 'far below the cutoff — significant digits, not more decimals, is why this survives' },

  // ---- ordinary magnitudes must be untouched by the fix ----
  { value: 5.6, unit: '/s', expected: '5.6 /s', why: 'a normal throughput' },
  { value: 1250, unit: 'ms', expected: '1,250 ms', why: 'thousands separator kept' },
  { value: 12.5, unit: '%', expected: '12.5 %', why: 'a normal error rate' },
  { value: 0.98, unit: '', expected: '0.98', why: 'apdex, unitless' },
  // A rate is the Test Summary card's "#,##0.##": grouped, two decimals (SF17). "2000 /s" until
  // 3.1.33; one decimal put "24.8 /s" one card from the Throughput card's "24.76/s".
  { value: 2000, unit: '/s', expected: '2,000 /s', why: 'a large rate gains no decimals, and is grouped' },
  { value: 24.7606, unit: '/s', expected: '24.76 /s', why: "E26's throughput, as the Throughput card writes it" },

  // ---- zero is a real measurement and must stay zero ----
  { value: 0, unit: '/s', expected: '0 /s', why: 'a genuine zero is not "very small"' },
  { value: 0, unit: 'ms', expected: '0 ms', why: 'same, in the unit that rounds hardest' },

  // ---- absent is not zero ----
  { value: null, unit: '/s', expected: '—', why: 'unanswerable renders as an em-dash, never 0' },
];

test.describe('Threshold and value formatting', () => {

  test('a non-zero value never renders as zero, and ordinary values are unchanged',
      async ({ page }) => {
    await loadClean(page, 'E25');

    const actual = await page.evaluate((cases) => {
      const fmt = (window as any).WIR.asserts._formatValue;
      return cases.map((c: any) => fmt(c.value, c.unit));
    }, CASES);

    const mismatches = CASES
      .map((c, i) => ({ ...c, got: actual[i] }))
      .filter(c => c.got !== c.expected);

    expect(mismatches.map(m =>
      `formatValue(${m.value}, "${m.unit}") = "${m.got}", expected "${m.expected}" — ${m.why}`
    )).toEqual([]);
  });

  test('the property holds across magnitudes nobody wrote a case for', async ({ page }) => {
    // The table above is a list of values someone thought of. This is the rule itself, swept
    // across six orders of magnitude in every unit — a future branch added to formatValue
    // that rounds at some new cutoff fails here without anyone having to predict the cutoff.
    await loadClean(page, 'E25');

    const offenders = await page.evaluate(() => {
      const fmt = (window as any).WIR.asserts._formatValue;
      const bad: string[] = [];
      for (const unit of ['ms', 's', '%', '/s', '']) {
        for (const value of [0.000001, 0.00001, 0.0001, 0.001, 0.01, 0.1, 0.25, 0.5, 0.9]) {
          const text = fmt(value, unit);
          // Strip the unit and any grouping, then read the number back.
          const n = parseFloat(String(text).replace(/,/g, ''));
          if (n === 0) bad.push(`formatValue(${value}, "${unit}") = "${text}"`);
        }
      }
      return bad;
    });

    expect(offenders, 'a real threshold rendered as zero').toEqual([]);
  });

  test('the criteria cell of the rule that exposed this reads its real bands',
      async ({ page }) => {
    // The end-to-end half. E25's loadValidity target is deliberately tiny (0.002 /s) so the
    // scenario passes on any machine, which is exactly what put its bands under the cutoff.
    await loadClean(page, 'E25');
    await page.click('[data-tab="asserts"]');

    const criteria = await page.locator('tr[data-rule-id="load:throughput"] td').nth(4)
      .innerText();

    expect(criteria, `criteria cell rounded a real threshold away: "${criteria}"`)
      .not.toMatch(/[<>≤≥]\s*0\s*\/s/);
    expect(criteria).toContain('0.0015');
    expect(criteria).toContain('0.001');
  });
});
