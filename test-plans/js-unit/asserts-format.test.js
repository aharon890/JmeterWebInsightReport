/**
 * Unit tests for how the Asserts tab WRITES a number (N3's page half) — the pure half; the rendered
 * surfaces are in Playwright (client-numbers.spec.ts, on E26's near-band client rules).
 *
 *   - An actual is never written on a band it is not on, or across one. formatActual writes the
 *     client's own precision — whole units for ms and s, two decimals otherwise — and adds digits
 *     only where that number would mislead against a band the rule declares, warn and fail alike.
 *     A FAIL at 373.8625 under `gt 373.86` read "mean 374 ms vs > 374 ms", and a WARN at 119.4675
 *     read "119 ms" under "warn > 119 ms".
 *   - A band is never rounded. describeBand writes the constant as the file holds it, through
 *     WIR.digits.formatBand — the Server tab's SF15 rule, now on both tabs: "fail ≥ 211 ms" was
 *     211.41, and a drag to 558.82 and one to 559.3 both read "559 ms".
 *   - Where nothing would mislead, the text is formatValue's, spelling and all. formatValue's number
 *     now comes from WIR.digits.measured, the one copy of "rounded, and never zero when it is not",
 *     so its table below holds every unit to what it wrote before (value-formatting.spec.ts's cases,
 *     asserts-pure.test.js's), and moves only past 2^53 / 100, where it was one unit off.
 *
 * The digits rule itself is defined by digits-vectors.json and run by digits.test.js; this file is
 * about the client's text around it.
 *
 * Two client numbers the same round found beside the text, pinned at the end:
 *   - The wizard's seeds never sit across the observation they were seeded from (Q20): at
 *     Tolerance 0 round2 put a 8.29893 /s floor at `lt 8.3`, Math.round a 211.405 ms warn at
 *     `gt 211`, and the step-down a 2 % error rate's warn at `gt 1.99` — rules that FAILed or
 *     WARNed the moment they were created; and below a cent, at any tolerance, round2 put a
 *     0.004 KB/s send rate's fail band at `gt 0`. Option B (3.1.48): to the nearest grain, as
 *     3.1.44 seeded, and away from the run only where the nearest is across it, or a warn on it —
 *     3.1.45's round-down at every tolerance moved a 0.2 /s floor's warn onto its fail and 27.5's
 *     to 23.09. Never past float noise either way: a band computed from the sliders is on the
 *     grain it is a billionth from, while which side of the run it is on is decided exactly.
 *   - A percentile off the vocabulary's grid is `ms` when the page re-parses it, as MetricRef has
 *     it (Q21): p85 lost its unit on Edit then Save, and its digits with it.
 *   - So is every spelling MetricRef reads (S3, L21): `Throughput`, `MEAN` and a qualified
 *     `errorRate:class=protocol` re-parsed with no unit, and the first two under a name no chart
 *     line or loaded rule matched.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage } = require('./harness');

const WIR = loadReportJs();
const A = WIR.asserts;

if (!A || !A._formatValue) {
    throw new Error('report.js exposed no WIR.asserts._formatValue — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// ---------------------------------------------------------------- an actual beside its bands

test('an actual is never written on or across a band the rule declares', () => {
    // The whole-ms base, rounded ONTO the band: a PASS at 373.8625 under gt 374.
    assert.strictEqual(A._formatActual(373.8625, 'ms', [374]), '373.9 ms');
    // Clear of it — 374 is above 373.86, as the value is — so it is the base, as today.
    assert.strictEqual(A._formatActual(373.8625, 'ms', [373.86]), '374 ms');
    // ON the band it is on: a PASS at exactly 373.86 under gt 373.86.
    assert.strictEqual(A._formatActual(373.86, 'ms', [373.86]), '373.86 ms');
    // Every band the rule declares counts, warn as much as fail: a WARN at 119.4675.
    assert.strictEqual(A._formatActual(119.4675, 'ms', [119.46, 1000]), '119.5 ms');
    // Rounded across it: a whole-run PASS at 234.9117 read "235 ms" beside "fail > 235 ms".
    assert.strictEqual(A._formatActual(234.91166666666666, 'ms', [235]), '234.9 ms');
    // Two decimals, on the band: an errorRate FAIL over gt 2.08.
    assert.strictEqual(A._formatActual(2.0833333333333335, '%', [2.08]), '2.083 %');
    // A FAIL over gt 0 is never 0.
    assert.strictEqual(A._formatActual(0.004999750012499375, '%', [0]), '0.005 %');
    assert.strictEqual(A._formatActual(24.760647078243647, '/s', [24.7606]), '24.761 /s');
});

test('float noise is never shown to separate an actual from its band (C1, L8)', () => {
    // A T-Digest 993.2499999999998 PASSes gte 993.25 by ~2e-13. The whole-ms base is not misled;
    // a band at 993.25 itself still reads "993.25" on the Criteria cell beside it.
    assert.strictEqual(A._formatActual(993.2499999999998, 'ms', [993.25]), '993 ms');
});

test('unextended, an actual is formatValue\'s text — its spelling included', () => {
    // KB/s is grouped, as FormatUtil groups the Samplers table's rates (L15); nothing here is near
    // the band.
    assert.strictEqual(A._formatActual(1234.567, 'KB/s', [2000]), '1,234.57 KB/s');
    assert.strictEqual(A._formatActual(1234.567, 'KB/s', [2000]), A._formatValue(1234.567, 'KB/s'));
    assert.strictEqual(A._formatActual(0.0015, '/s', []), '0.0015 /s');
    assert.strictEqual(A._formatActual(1250, 'ms'), '1,250 ms');
    // An absent band is null in ruleBands' pair, and is no band.
    assert.strictEqual(A._formatActual(373.8625, 'ms', [null, 374]), '373.9 ms');
    assert.strictEqual(A._formatActual(373.8625, 'ms', [undefined, null]), '374 ms');
});

test('an unmeasured actual is a dash, whatever the bands', () => {
    assert.strictEqual(A._formatActual(null, 'ms', [1]), '—');
    assert.strictEqual(A._formatActual(undefined, 'ms', [1]), '—');
    assert.strictEqual(A._formatActual(NaN, '%', [1]), '—');
});

// ---------------------------------------------------------------- a band as the file holds it

test('a band is written with every digit it has, never rounded', () => {
    assert.strictEqual(A._describeBand({ op: 'gt', value: 373.86 }, 'ms'), '> 373.86 ms');
    assert.strictEqual(A._describeBand({ op: 'gte', value: 211.41 }, 'ms'), '≥ 211.41 ms');
    assert.strictEqual(A._describeBand({ op: 'gt', value: 119.46 }, 'ms'), '> 119.46 ms');
    assert.strictEqual(A._describeBand({ op: 'gt', value: 558.82 }, 'ms'), '> 558.82 ms');
    // Below the rounding grain too — the 0.0015 /s floor that once read "< 0 /s".
    assert.strictEqual(A._describeBand({ op: 'lt', value: 0.0015 }, '/s'), '< 0.0015 /s');
    // Grouped as the actual beside it is, and a whole band stays whole.
    assert.strictEqual(A._describeBand({ op: 'gt', value: 1000 }, 'ms'), '> 1,000 ms');
    assert.strictEqual(A._describeBand({ op: 'lte', value: 0.98 }, ''), '≤ 0.98');
    assert.strictEqual(A._describeBand(null, 'ms'), null);
});

// ---------------------------------------------------------------- SF17: the reader's locale

test('SF17: a German reader gets the same digits — en-US, as the cards beside them', () => {
    assert.strictEqual((1234.5).toLocaleString('de-DE'), '1.234,5',
        'this node has no German ICU data, so the locale case below would prove nothing');
    const DA = loadReportJs(undefined, { locale: 'de-DE' }).asserts;
    assert.strictEqual(DA._formatActual(1234.5678, 'ms', [1234.57]), '1,234.568 ms');
    assert.strictEqual(DA._describeBand({ op: 'gt', value: 1234.57 }, 'ms'), '> 1,234.57 ms');
    assert.strictEqual(DA._formatValue(1234.5678, 'ms'), '1,235 ms');
});

// ---------------------------------------------------------------- formatValue's text, unmoved

/*
 * formatValue's number comes from WIR.digits.measured, and its spelling from WIR.digits.enDigits in
 * every unit: grouped en-US, as FormatUtil's "#,##0.##" writes the cards and tables beside it. % and
 * a fractional value outside ms, s and /s were written ungrouped — "1234.57 KB/s", "1234.5", "1000 %"
 * (L15). Significant digits where the base would have written a non-zero value as zero. Every case
 * in value-formatting.spec.ts and asserts-pure.test.js, plus one per spelling.
 */
const VALUE_TEXT = [
    // value-formatting.spec.ts's CASES
    [0.0015, '/s', '0.0015 /s'],
    [0.001, '/s', '0.001 /s'],
    [0.004, '%', '0.004 %'],
    [0.4, 'ms', '0.4 ms'],
    [0.000012, '/s', '0.000012 /s'],
    [5.6, '/s', '5.6 /s'],
    [1250, 'ms', '1,250 ms'],
    [12.5, '%', '12.5 %'],
    [0.98, '', '0.98'],
    [2000, '/s', '2,000 /s'],
    [24.7606, '/s', '24.76 /s'],
    [0, '/s', '0 /s'],
    [0, 'ms', '0 ms'],
    [null, '/s', '—'],
    // asserts-pure.test.js's: absent is a dash, never a number
    [undefined, 'ms', '—'],
    [NaN, 'ms', '—'],
    // one per spelling
    [373.8625, 'ms', '374 ms'],
    [2.954, 's', '3 s'],
    [0.0015, 'ms', '0.0015 ms'],
    [0.0000123, 'ms', '0.000012 ms'],
    [-0.4, 'ms', '-0.4 ms'],
    [2.0833333333333335, '%', '2.08 %'],
    // L15: grouped as FormatUtil groups, in every unit.
    [1000, '%', '1,000 %'],
    [-0.004, '%', '-0.004 %'],
    [0.004999750012499375, '%', '0.005 %'],
    [24.760647078243647, '/s', '24.76 /s'],
    [10000, '', '10,000'],
    [1234.5, '', '1,234.5'],
    [1234.999, '', '1,235'],
    [1234.567, 'KB/s', '1,234.57 KB/s'],
    [12.345, 'KB/s', '12.35 KB/s'],
    [-1234.5, '', '-1,234.5'],
    [799450271802898, '', '799,450,271,802,898'],
    [799450271802898, 'ms', '799,450,271,802,898 ms'],
    [90071992547409.9, '%', '90,071,992,547,409.9 %'],
    // Below the grain, significant digits — written out, never in E notation.
    [1e-7, '/s', '0.0000001 /s']
];

test('formatValue writes every unit grouped en-US, the measured number unmoved', () => {
    const wrong = VALUE_TEXT
        .map(([value, unit, expected]) => ({ value, unit, expected, got: A._formatValue(value, unit) }))
        .filter((c) => c.got !== c.expected)
        .map((c) => 'formatValue(' + c.value + ', "' + c.unit + '") = "' + c.got + '", expected "'
            + c.expected + '"');
    assert.deepStrictEqual(wrong, []);
});

test('C1: past 2^53 / 100 a two-decimal value is written as it is, never one unit off', () => {
    // Math.round(v * 100) / 100 loses the last digit once v * 100 passes 2^53 — the Server tab's
    // C1, which the client's own rounding had too: 799450271802898 read "799450271802897.9 %".
    assert.strictEqual(A._formatValue(799450271802898, '%'), '799,450,271,802,898 %');
    assert.strictEqual(A._formatValue(799450271802898, '/s'), '799,450,271,802,898 /s');
    assert.strictEqual(A._formatValue(90071992547410.03, ''), '90,071,992,547,410.03');
    assert.strictEqual(A._formatActual(799450271802898, '%', [799450271802898]), '799,450,271,802,898 %');
});

// ---------------------------------------------------------------- the wizard's seeds (Q20)

/* A page object from the vm context has that context's Object.prototype: compare data, not realms. */
const plain = (v) => JSON.parse(JSON.stringify(v));

/*
 * The wizard's promise is a gate that passes as written, and Tolerance 0 is where the seeder's
 * rounding met the observation head-on: there a floor's fail band IS the observation, and so is a
 * ceiling's warn, so rounding to the nearest put one of them on the wrong side of the run about
 * half the time. A fail band may sit ON the observation, because that is what Tolerance 0 asks for
 * (`fail > 2 %` over a 2 % error rate passes); a warn on it warns of nothing, and is dropped.
 */
test('Tolerance 0: a seeded floor is rounded down, never above the run it came from', () => {
    // GET /api/products throughput, as the wizard previewed it: `fail < 8.3 /s` over 8.29893 /s.
    assert.deepStrictEqual(plain(A._seedBands('throughput', 8.29892736363825, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 8.29 } });
    assert.deepStrictEqual(plain(A._seedBands('receivedKbPerSec', 6.62906, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 6.62 } });
    // A number already on the grain is itself, not a cent lower — 0.29 * 100 is
    // 28.999999999999996, which Math.floor alone would have floored to `lt 0.28`.
    assert.deepStrictEqual(plain(A._seedBands('throughput', 8.3, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 8.3 } });
    assert.deepStrictEqual(plain(A._seedBands('throughput', 0.29, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 0.29 } });
    // An apdex of 0.995 seeded `warn < 1`: a warn above the score it was seeded from.
    assert.deepStrictEqual(plain(A._seedBands('apdex', 0.995, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 0.99 } });
});

test('Tolerance 0: an upward warn is rounded up, and dropped where it is not above the run', () => {
    // GET /api/search mean 211.405 ms seeded `warn > 211 ms`, and WARNed as it was created.
    assert.deepStrictEqual(plain(A._seedBands('mean', 211.405, 0, 0.8)),
        { warn: { op: 'gt', value: 212 }, fail: { op: 'gt', value: 250 } });
    // A 2 % error rate: fail sits on it; the warn, stepped down to `> 1.99 %`, sat below it.
    assert.deepStrictEqual(plain(A._seedBands('errorRate', 2, 0, 0.8)),
        { warn: null, fail: { op: 'gt', value: 2 } });
    assert.deepStrictEqual(plain(A._seedBands('errorRate', 2.0833333333333335, 0, 0.8)),
        { warn: { op: 'gt', value: 2.09 }, fail: { op: 'gt', value: 2.5 } });
});

test('below a cent an upward fail band is rounded up, never to a `gt 0` the run breaches', () => {
    // sentKbPerSec is upward and two decimals: niceUp seeded 0.004 and 0.0015, and round2 took
    // both to 0 — at Tolerance 0 and at the default 20 % alike.
    assert.deepStrictEqual(plain(A._seedBands('sentKbPerSec', 0.004, 0, 0.8)),
        { warn: null, fail: { op: 'gt', value: 0.01 } });
    assert.deepStrictEqual(plain(A._seedBands('sentKbPerSec', 0.0012, 0.2, 0.8)),
        { warn: null, fail: { op: 'gt', value: 0.01 } });
});

test('float noise is not headroom: a band computed onto the grain stays on it', () => {
    // 100 * 1.1 is 110.00000000000001 and 200 * 1.16 is 232.00000000000003: a bare Math.ceil
    // seeded `warn > 111 ms` and `warn > 233 ms`. These are the seeds the page always made.
    assert.deepStrictEqual(plain(A._seedBands('p95', 100, 0.2, 0.5)),
        { warn: { op: 'gt', value: 110 }, fail: { op: 'gt', value: 150 } });
    assert.deepStrictEqual(plain(A._seedBands('p95', 200)),
        { warn: { op: 'gt', value: 232 }, fail: { op: 'gt', value: 250 } });
    // 0.75 * 0.8 is 0.6000000000000001 and 1.5 * 0.8 is 1.2000000000000002: `warn > 0.61 %`
    // beside `fail > 0.75 %` was a cent of noise, not a cent of room.
    assert.deepStrictEqual(plain(A._seedBands('errorRate', 0.5)),
        { warn: { op: 'gt', value: 0.6 }, fail: { op: 'gt', value: 0.75 } });
    assert.deepStrictEqual(plain(A._seedBands('errorRate', 1)),
        { warn: { op: 'gt', value: 1.2 }, fail: { op: 'gt', value: 1.5 } });
    // niceUp's own 0.15 is 0.15000000000000002: rounded up strictly, the fail band read 0.16.
    assert.deepStrictEqual(plain(A._seedBands('sentKbPerSec', 0.12, 0.2, 0.8)),
        { warn: { op: 'gt', value: 0.14 }, fail: { op: 'gt', value: 0.15 } });
});

test('a seed that never touched its observation is seeded as before', () => {
    // asserts-seeding.spec.ts's two pins, at the default 20 % / 80 %.
    assert.deepStrictEqual(plain(A._seedBands('p95', 812)),
        { warn: { op: 'gt', value: 942 }, fail: { op: 'gt', value: 1000 } });
    assert.deepStrictEqual(plain(A._seedBands('throughput', 50)),
        { warn: { op: 'lt', value: 42 }, fail: { op: 'lt', value: 40 } });
});

test('option B: a seed clear of the run is the nearest grain, as 3.1.44 seeded it', () => {
    // 3.1.45 rounded every floor down, at every tolerance, so a band 3.1.44 had placed clear of the
    // run moved a grain further from it: 0.2 /s seeded its warn at 0.168 -> 0.16, onto the fail
    // band, and it was dropped; 8.2989 /s seeded `fail < 6.63` where 6.63912 is nearer 6.64.
    assert.deepStrictEqual(plain(A._seedBands('throughput', 0.2)),
        { warn: { op: 'lt', value: 0.17 }, fail: { op: 'lt', value: 0.16 } });
    assert.deepStrictEqual(plain(A._seedBands('throughput', 8.2989)),
        { warn: { op: 'lt', value: 6.97 }, fail: { op: 'lt', value: 6.64 } });
    // 27.5 * 0.84 is 23.099999999999998, and a strict round-down read it as below 23.1: 23.09.
    assert.deepStrictEqual(plain(A._seedBands('throughput', 27.5)),
        { warn: { op: 'lt', value: 23.1 }, fail: { op: 'lt', value: 22 } });
});

test('a band across the run by float noise alone is across it: the evaluator compares exactly', () => {
    // A T-Digest p95 of 1500.0000000000002 at Tolerance 0: niceUp forgives 1e-9 and seeded
    // `fail > 1500`, which the evaluator FAILs by 2e-13. The next whole ms is clear, and the warn,
    // with no whole ms left between the run and the fail band, is dropped.
    assert.deepStrictEqual(plain(A._seedBands('p95', 1500.0000000000002, 0, 0.8)),
        { warn: null, fail: { op: 'gt', value: 1501 } });
    // E10's p90, the digits rule's own noise case: 3.1.44 wrote its warn at `> 1587`, below it,
    // and 3.1.45 dropped it, though 1588 is clear and below the fail band.
    assert.deepStrictEqual(plain(A._seedBands('p90', 1587.0000000000002, 0, 0.5)),
        { warn: { op: 'gt', value: 1588 }, fail: { op: 'gt', value: 2000 } });
    // The other way, a floor: the nearest cent is above a run that is a hair below it.
    assert.deepStrictEqual(plain(A._seedBands('throughput', 0.29999999999999993, 0, 0.8)),
        { warn: null, fail: { op: 'lt', value: 0.29 } });
});

test('option B: a warn the nearest grain puts ON the run moves one grain away, and is kept', () => {
    // Tolerance 0 over a whole-ms or whole-cent observation: warn IS the run, and so is its nearest
    // grain. 3.1.44 wrote it there (`warn > 211`, which warns of nothing) and 3.1.45 dropped it,
    // so every such rule lost its warn. One grain past the run is clear of it and below the fail
    // band, so it is the seed. 211.405 (off the grain) cannot tell the two apart; these can.
    assert.deepStrictEqual(plain(A._seedBands('mean', 211, 0, 0.8)),
        { warn: { op: 'gt', value: 212 }, fail: { op: 'gt', value: 250 } });
    assert.deepStrictEqual(plain(A._seedBands('p95', 812, 0, 0.8)),
        { warn: { op: 'gt', value: 813 }, fail: { op: 'gt', value: 1000 } });
    assert.deepStrictEqual(plain(A._seedBands('errorRate', 1.46, 0, 0.8)),
        { warn: { op: 'gt', value: 1.47 }, fail: { op: 'gt', value: 1.5 } });
});

test('a floor is never stepped to 0: under a cent it stays the 0.01 floor (L22)', () => {
    // A lower-is-worse run below a cent. The smallest floor two decimals write, 0.01, sits above
    // it — the one seed that FAILs the run it came from (L22, unchanged by design). Stepped a
    // grain away from the run it would be `lt 0`, which can never fire: the silently-green gate
    // the seeder's floor exists to prevent.
    for (const [metric, observed] of [['throughput', 0.005], ['apdex', 0], ['receivedKbPerSec', 0.004]]) {
        for (const headroom of [0, 0.2]) {
            assert.deepStrictEqual(plain(A._seedBands(metric, observed, headroom, 0.8)),
                { warn: null, fail: { op: 'lt', value: 0.01 } }, metric + '@' + observed + ' h=' + headroom);
        }
    }
});

test('no seeded band breaches its observation, at any tolerance, and no warn sits on it', () => {
    // Every observation a floor seeds from is at least 0.01: below that the 0.01 floor itself
    // sits above the run (a band two decimals cannot place), which is not this rounding (L22).
    // An observation that is float noise just past a nice step is covered now: which side of the
    // run a band is on is decided exactly, as the evaluator decides it.
    const observations = {
        p95: [0.3, 2, 4, 100, 119.4675, 200, 211, 211.405, 373.8625, 993.2499999999998,
            1500.0000000000002, 1587.0000000000002],
        mean: [0.3, 2, 4, 119.4675, 211, 211.405, 234.91166666666666],
        errorRate: [0, 0.21, 0.49, 0.5, 1, 2, 2.0833333333333335, 37.5],
        sentKbPerSec: [0.0012, 0.004, 0.0049, 0.012, 0.072, 0.12, 0.14, 120.4567],
        duration: [0.004, 1.5, 61],
        sampleCount: [1, 400, 1234],
        throughput: [0.03, 0.2, 0.29999999999999993, 6.6, 8.2989, 8.29892736363825, 8.3, 24.760647078243647,
            27.5],
        receivedKbPerSec: [6.62906, 120.4567],
        apdex: [0.62, 0.937, 0.995, 1]
    };
    const bad = [];
    for (const metric of Object.keys(observations)) {
        for (const observed of observations[metric]) {
            for (const headroom of [0, 0.05, 0.2, 0.5]) {
                for (const warnPct of [0.5, 0.8, 0.99]) {
                    const b = A._seedBands(metric, observed, headroom, warnPct);
                    const tag = metric + '@' + observed + ' h=' + headroom + ' w=' + warnPct;
                    const breaches = (band) => band.op === 'gt' ? observed > band.value : observed < band.value;
                    if (breaches(b.fail)) bad.push(tag + ': fail ' + b.fail.op + ' ' + b.fail.value);
                    if (b.warn && (breaches(b.warn) || b.warn.value === observed)) {
                        bad.push(tag + ': warn ' + b.warn.op + ' ' + b.warn.value);
                    }
                }
            }
        }
    }
    assert.deepStrictEqual(bad, []);
});

// ---------------------------------------------------------------- a percentile's unit (Q21)

/* The vocabulary as MetricRef.vocabulary() ships it: the percentile grid, no p85. */
const GRID = ['p50', 'p75', 'p90', 'p95', 'p99', 'p99.9'].map((name) => ({ name, unit: 'ms' }));
const VOCABULARY = {
    sampler: GRID.concat([{ name: 'mean', unit: 'ms' }, { name: 'throughput', unit: '/s' },
        { name: 'errorRate', unit: '%' }]),
    overall: GRID.concat([{ name: 'mean', unit: 'ms' }, { name: 'duration', unit: 's' },
        { name: 'errorRate', unit: '%' }])
};

test('every percentile MetricRef reads is ms when the page re-parses the rule', () => {
    const P = loadReportJs({ assertMetrics: VOCABULARY }).asserts;
    const unitOf = (metric, target) => P._ruleFromJson(
        { id: 'r', target: target || { sampler: 'A' }, metric, fail: { gt: 162 } }, 0).unit;
    // Off the grid: '' before, so a p85 actual of 161.725 read "161.73" after an unchanged Save.
    assert.strictEqual(unitOf('p85'), 'ms');
    assert.strictEqual(unitOf('P85'), 'ms');
    assert.strictEqual(unitOf('p99.95'), 'ms');
    assert.strictEqual(unitOf('median'), 'ms');
    assert.strictEqual(unitOf('p85', { scope: 'overall' }), 'ms');
    // On the grid, and everything else, it is the vocabulary's, as it was.
    assert.strictEqual(unitOf('p95'), 'ms');
    assert.strictEqual(unitOf('throughput'), '/s');
    assert.strictEqual(unitOf('duration', { scope: 'overall' }), 's');
    // Not percentiles to MetricRef: p100 is max, p0 is min, and a typo is no metric at all.
    assert.strictEqual(unitOf('p100'), '');
    assert.strictEqual(unitOf('p0'), '');
    assert.strictEqual(unitOf('p95x'), '');
    assert.strictEqual(unitOf('pineapple'), '');
    // Why it matters: the digits are keyed on the unit.
    assert.strictEqual(P._formatActual(161.725, 'ms', [200]), '162 ms');
    assert.strictEqual(P._formatActual(161.725, '', [200]), '161.73');
});

test('the editor keeps a p85 rule in ms, so Save writes the rule it was shown', () => {
    const entry = { id: 'products-p85', target: { sampler: 'A' }, metric: 'p85', fail: { gt: 162 } };
    /* GatePayloadBuilder ships the rule with MetricRef's unit; the editor re-derives its own. */
    const shipped = Object.assign(plain(A._ruleFromJson(entry, 0)), { unit: 'ms', status: 'PASS',
        unmatched: false, matched: 1, results: [{ sampler: 'A', actual: 161.725, status: 'PASS' }] });
    const WIR = loadReportPage({
        runId: 'p85-unit',
        annotationsDocument: { assertions: [entry] },
        assertions: [shipped],
        assertMetrics: VOCABULARY,
        samplers: [{ name: 'A', count: 10 }],
        overall: { totalSamples: 10 }
    });
    assert.strictEqual(WIR.asserts.currentRules()[0].unit, 'ms', 'precondition: ms on load');
    WIR.asserts.edit(WIR.asserts._keyOf('products-p85'));
    assert.strictEqual(WIR.asserts._editorRule().unit, 'ms');
});

// ---------------------------------------------------------------- every spelling MetricRef reads (S3)

/* Both vocabularies as MetricRef.vocabulary() ships them (GatePayloadBuilder.vocabulary): every
   name in its canonical spelling, with its unit. */
const unitOf = (names, unit) => names.map((name) => ({ name, unit }));
const FULL_VOCABULARY = {
    sampler: unitOf(['sampleCount', 'errorCount'], '').concat(unitOf(['errorRate'], '%'),
        unitOf(['mean', 'min', 'max', 'stdDev'], 'ms'), unitOf(['throughput'], '/s'),
        unitOf(['apdex'], ''), unitOf(['latency', 'connectTime'], 'ms'),
        unitOf(['receivedKbPerSec', 'sentKbPerSec'], 'KB/s'), GRID),
    overall: unitOf(['totalSamples'], '').concat(unitOf(['errorRate'], '%'), unitOf(['duration'], 's'),
        unitOf(['maxThreads'], ''), unitOf(['mean'], 'ms'), unitOf(['throughput'], '/s'),
        unitOf(['apdex'], ''), GRID)
};

test('S3: a rule re-parsed has MetricRef\'s name and unit, whichever way the file spelled it', () => {
    const P = loadReportJs({ assertMetrics: FULL_VOCABULARY }).asserts;
    const read = (metric, target) => {
        const rule = P._ruleFromJson({ id: 'r', target: target || { sampler: 'A' }, metric, fail: { gt: 50 } }, 0);
        return [rule.metric, rule.unit];
    };
    // Names are case-insensitive to MetricRef.parse, and GatePayloadBuilder ships the canonical one.
    assert.deepStrictEqual(read('Throughput'), ['throughput', '/s']);
    assert.deepStrictEqual(read('MEAN'), ['mean', 'ms']);
    assert.deepStrictEqual(read('ErrorRate'), ['errorRate', '%']);
    assert.deepStrictEqual(read('RECEIVEDKBPERSEC'), ['receivedKbPerSec', 'KB/s']);
    assert.deepStrictEqual(read('Duration', { scope: 'overall' }), ['duration', 's']);
    // A qualified error metric takes its base metric's unit (MetricRef.parseQualified).
    assert.deepStrictEqual(read('errorRate:class=protocol', { scope: 'overall' }),
        ['errorRate:class=protocol', '%']);
    assert.deepStrictEqual(read('errorRate:code=5*'), ['errorRate:code=5*', '%']);
    assert.deepStrictEqual(read('ErrorRate:Class=PROTOCOL'), ['errorRate:class=protocol', '%']);
    assert.deepStrictEqual(read('errorCount:code=5*'), ['errorCount:code=5*', '']);
    // Percentiles: the canonical name too — p95.0 is p95, median is p50 (each checked against
    // MetricRef.parse in jshell).
    assert.deepStrictEqual(read('p95.0'), ['p95', 'ms']);
    assert.deepStrictEqual(read('P85'), ['p85', 'ms']);
    assert.deepStrictEqual(read('median'), ['p50', 'ms']);
    assert.deepStrictEqual(read('p99.90'), ['p99.9', 'ms']);
    assert.deepStrictEqual(read('p007'), ['p7', 'ms']);
    // Below 0.001 too. Java's Double.toString writes scientific notation there, p0.0001 as p1.0E-4,
    // which parseMetric used to refuse, so from 3.1.48 such a rule kept the name it was written
    // under (Batch L review). parseMetric reads Java's grammar now (RB8), so the page names it as
    // MetricRef does, and reads that name back.
    assert.deepStrictEqual(read('p0.0001'), ['p1.0E-4', 'ms']);
    assert.deepStrictEqual(read('P0.00015'), ['p1.5E-4', 'ms']);
    assert.deepStrictEqual(read('p1.0E-4'), ['p1.0E-4', 'ms']);
    assert.strictEqual(P._whyUnusable(P._ruleFromJson({ id: 'r', target: { sampler: 'A' },
        metric: 'p0.0001', fail: { gt: 50 } }, 0)), null);
    assert.strictEqual(P._whyUnusable(P._ruleFromJson({ id: 'r', target: { sampler: 'A' },
        metric: 'p1.0E-4', fail: { gt: 50 } }, 0)), null);
    // The unit is the metric's, whatever the scope, as Java's is: a metric asked where it has no
    // value is refused for THAT, in the canonical name Usability writes.
    assert.deepStrictEqual(read('ReceivedKbPerSec', { scope: 'overall' }), ['receivedKbPerSec', 'KB/s']);
    assert.strictEqual(P._whyUnusable(P._ruleFromJson({ id: 'r', target: { sampler: 'A' },
        metric: 'TotalSamples', fail: { gt: 1 } }, 0)), "metric 'totalSamples' is not available per sampler");
    // And on a rule built by hand, which no ruleFromJson canonicalised: the sentence looks the name
    // up itself (whyUnusable's metricRef), so it does not quote the spelling it was handed.
    assert.strictEqual(P._whyUnusable({ id: 'r', target: { kind: 'sampler', sampler: 'A', text: 'A' },
        metric: 'TotalSamples', enabled: true, fail: { op: 'gt', value: 1 } }),
        "metric 'totalSamples' is not available per sampler");
    // Not a metric to MetricRef: kept as written, with no unit.
    assert.deepStrictEqual(read('thruughput'), ['thruughput', '']);
    assert.deepStrictEqual(read('p95:class=protocol'), ['p95:class=protocol', '']);
    assert.deepStrictEqual(read('errorRate:class=funcional'), ['errorRate:class=funcional', '']);
    // The id Java derives is from the name AS WRITTEN (AssertionRule.deriveId), not the canonical one.
    assert.strictEqual(P._ruleFromJson({ target: { sampler: 'A' }, metric: 'Throughput', fail: { lt: 1 } }, 0).id,
        'a-Throughput');
    // Why it matters: the digits are keyed on the unit — 2.08 % and 374 ms, not 2.08 and 373.86.
    assert.strictEqual(P._formatActual(2.0833333333333335, '%', [50]), '2.08 %');
    assert.strictEqual(P._formatActual(373.8625, 'ms', [1000]), '374 ms');
    assert.strictEqual(P._formatActual(373.8625, '', [1000]), '373.86');
});

/* A page as Java writes it for a file that spells its metrics its own way: the document as the file
   has it, the rules with MetricRef's canonical name and unit. */
function spelledPage() {
    const entries = [
        { id: 'q-err', target: { scope: 'overall' }, metric: 'errorRate:class=protocol', fail: { gt: 50 } },
        { id: 'tp-cap', target: { scope: 'overall' }, metric: 'Throughput', fail: { lt: 1 } },
        { id: 'mean-cap', target: { sampler: 'A' }, metric: 'MEAN', fail: { gt: 1000 } }
    ];
    const javaShips = { 'q-err': ['errorRate:class=protocol', '%'], 'tp-cap': ['throughput', '/s'],
        'mean-cap': ['mean', 'ms'] };
    const shipped = entries.map((entry, i) => Object.assign(plain(A._ruleFromJson(entry, i)), {
        metric: javaShips[entry.id][0], unit: javaShips[entry.id][1], status: 'PASS', unmatched: false,
        matched: 1, results: [{ sampler: entry.target.sampler || '__overall__', actual: 1, status: 'PASS' }] }));
    return {
        entries,
        WIR: loadReportPage({
            runId: 's3-spellings',
            annotationsDocument: { assertions: entries },
            assertions: shipped,
            assertMetrics: FULL_VOCABULARY,
            samplers: [{ name: 'A', count: 10, mean: 373.8625, throughput: 24.76, errorRate: 0 }],
            overall: { totalSamples: 10, mean: 373.8625, throughput: 24.76, errorRate: 0 }
        })
    };
}

test('S3: Edit then Save of a qualified rule keeps its unit, so its digits', () => {
    const { WIR } = spelledPage();
    const unit = (id) => WIR.asserts.currentRules().filter((r) => r.id === id)[0].unit;
    assert.strictEqual(unit('q-err'), '%', 'precondition: % on load, as Java shipped it');
    WIR.asserts.edit(WIR.asserts._keyOf('q-err'));
    assert.strictEqual(WIR.asserts._editorRule().unit, '%');
    WIR.asserts.saveEditor();
    assert.strictEqual(unit('q-err'), '%');
    WIR.asserts.edit(WIR.asserts._keyOf('mean-cap'));
    WIR.asserts.saveEditor();
    assert.strictEqual(unit('mean-cap'), 'ms');
});

test('S3: opening the report\'s own file is no change, whatever the file\'s spelling (F1)', () => {
    const { WIR, entries } = spelledPage();
    assert.strictEqual(WIR.document.count(), 0, 'precondition: nothing unsaved on load');
    // Open file… and Edit document… both reach the slice through WIR.document.replace.
    assert.strictEqual(WIR.document.replace(JSON.parse(JSON.stringify({ assertions: entries })), null), null);
    assert.strictEqual(WIR.document.count(), 0);
    assert.deepStrictEqual(plain(WIR.asserts.currentRules().map((r) => [r.id, r.metric, r.unit,
        r.draftState || null])), [['q-err', 'errorRate:class=protocol', '%', null],
        ['tp-cap', 'throughput', '/s', null], ['mean-cap', 'mean', 'ms', null]]);
});

// ---------------------------------------------------------------- the Budget cell (L13, L14)

test('L14: the Budget label is never written on 100% for a rule that is not on its line', () => {
    // E26's three Budget rules: a FAIL 0.0007% past its line read "100%", as a PASS 0.04% and one
    // 0.002% short of it did — three cells, two verdicts, one number. The label is the percentage
    // beside the line at 100, through WIR.digits.beside at whole percents.
    assert.strictEqual(A._budgetLabel(373.8625 / 373.86), '100.001%');
    assert.strictEqual(A._budgetLabel(234.9117 / 235), '99.96%');
    assert.strictEqual(A._budgetLabel(211.405 / 211.41), '99.998%');
    // On the line it is on: a gte met to the digit is 100% of its budget, and a breach.
    assert.strictEqual(A._budgetLabel(1), '100%');
    // Clear of it, the whole percent it always was, ungrouped (the specs parseInt it).
    assert.strictEqual(A._budgetLabel(0.89), '89%');
    assert.strictEqual(A._budgetLabel(24), '2400%');
    // Some of the budget, but less than a percent: "<1%", never "0%".
    assert.strictEqual(A._budgetLabel(0.002), '<1%');
});

test('L13: the Budget describes the worst judged match, the one the Actual cell and JUnit name', () => {
    // A warn band pointing away from the fail band: A WARNs, B passes nearer the fail band. The
    // rule's status came from A, so its Actual cell, its JUnit message and attainment all describe A
    // (RuleResult.worstJudged() / worstMatch, severity first). The Budget took the worst by value
    // alone and described B — 50% beside an Actual of 5 ms.
    const corridor = A._attainmentOf({
        id: 'l13-corridor', metric: 'p95', unit: 'ms', status: 'WARN', matched: 2,
        warn: { op: 'lt', value: 10 }, fail: { op: 'gt', value: 100 },
        results: [{ sampler: 'A', actual: 5, status: 'WARN' }, { sampler: 'B', actual: 50, status: 'PASS' }]
    });
    assert.strictEqual(corridor.actual, 5);
    assert.strictEqual(corridor.consumed, 0.05);
    assert.strictEqual(corridor.breached, false);

    // Where the bands point one way the two definitions agree, and the furthest past the line wins.
    const twoFails = A._attainmentOf({
        id: 'l13-two-fails', metric: 'p95', unit: 'ms', status: 'FAIL', matched: 3,
        fail: { op: 'gt', value: 100 },
        results: [{ sampler: 'A', actual: 150, status: 'FAIL' }, { sampler: 'B', actual: 300, status: 'FAIL' },
            { sampler: 'C', actual: 20, status: 'PASS' }]
    });
    assert.strictEqual(twoFails.actual, 300);
    assert.strictEqual(twoFails.consumed, 3);
    assert.strictEqual(twoFails.breached, true);
});

test('NIT-2: within one status, the worst is the furthest past the band that status was decided against', () => {
    // A corridor, warn lt 10 beside fail gt 100: WARNs at 8 and 2 beside a PASS at 50. A WARN was
    // decided against the warn band, so the 2 is the worst. Both copies took the fail band's
    // direction for every status and named the 8, the WARN nearest to passing. The parity vectors'
    // corridor-worst-warn holds the page's copy to Java's; the server mirror's, which requiresLoad
    // and divideBy read a client load rule through, is held here.
    const corridor = {
        id: 'nit2-corridor', metric: 'p95', unit: 'ms', status: 'WARN', matched: 3,
        warn: { op: 'lt', value: 10 }, fail: { op: 'gt', value: 100 },
        results: [{ sampler: 'A', actual: 8, status: 'WARN' }, { sampler: 'B', actual: 2, status: 'WARN' },
            { sampler: 'C', actual: 50, status: 'PASS' }]
    };
    const budget = A._attainmentOf(corridor);
    assert.strictEqual(budget.actual, 2);
    assert.strictEqual(budget.consumed, 0.02);
    assert.strictEqual(WIR.serverKpis._worstJudged(corridor).sampler, 'B');

    // A FAIL is still measured by the fail band, whichever way the warn band points.
    const failing = {
        id: 'nit2-corridor-fail', metric: 'p95', unit: 'ms', status: 'FAIL', matched: 3,
        warn: { op: 'lt', value: 10 }, fail: { op: 'gt', value: 100 },
        results: [{ sampler: 'A', actual: 150, status: 'FAIL' }, { sampler: 'B', actual: 300, status: 'FAIL' },
            { sampler: 'C', actual: 2, status: 'WARN' }]
    };
    assert.strictEqual(A._attainmentOf(failing).actual, 300);
    assert.strictEqual(WIR.serverKpis._worstJudged(failing).sampler, 'B');
});

// ---------------------------------------------------------------------------

let failed = 0;
for (const t of tests) {
    try {
        t.fn();
        console.log('  ok   ' + t.name);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + t.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
