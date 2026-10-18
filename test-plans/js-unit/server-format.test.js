/**
 * Unit tests for how the Server tab WRITES a number (Batch 3, plans/server-tab-followups.md) — the
 * pure half; the board, the Summary card, the panels and the dialogs are in Playwright
 * (server-numbers.spec.ts).
 *
 *   - SF15: a measured number keeps two decimals in every unit, seconds and milliseconds included,
 *     and is never zero when it is not; a band's constant is never rounded at all; an actual beside
 *     a band is never written onto it. warn 0.4 / fail 0.6 read "warn > 0 s · fail > 1 s", 3.6 and
 *     4.3 both read "4 s", and 2.95 read "3 s vs > 3 s".
 *   - SF16: the unit a verdict number is in is ServerKpi.verdictUnit()'s — "/min" on a slope, then
 *     " ÷ <rule>" on a ratio — read off a declared assert and a baked entry alike.
 *   - SF17: one spelling of one quantity: grouped on every value, not exact integers only, the
 *     picker's Suggested line written as the board writes a band, axis ticks short enough to fit
 *     and in one prefix per axis — and en-US digits in every reader's locale, on both tabs.
 *
 * Every expected string is spelled out: the formatters write en-US whatever the locale, so nothing
 * here depends on this process's. The German context is what proves it.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;

if (!SK || !SK._formatValue || !SK._formatBand || !SK._describeBand || !SK._verdictUnit
        || !SK._metricVerdictUnit || !SK._suggestedText || !SK._observedText || !SK._axisTick
        || !SK._formatActual || !SK._axisTicker || !SK._noBandText) {
    throw new Error('report.js exposed no WIR.serverKpis._formatValue/_formatActual/_formatBand/'
        + '_verdictUnit/_suggestedText/_noBandText/_axisTick/_axisTicker — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const COMP = { c: { type: 'custom' } };
const POSTGRES = SK.catalogueType('postgres');

function kpi(json) {
    return SK._kpiFromJson(Object.assign({ component: 'c', query: 'q', fail: { gt: 1 } }, json), COMP);
}

// ---------------------------------------------------------------- SF15: measured numbers

test('SF15: seconds keep two decimals — 2.95 s is not 3 s, 3.6 is not 4', () => {
    assert.strictEqual(SK._formatValue(2.95, 's'), '2.95');
    assert.strictEqual(SK._formatValue(3.6, 's'), '3.6');
    assert.strictEqual(SK._formatValue(4.3, 's'), '4.3');
    assert.strictEqual(SK._formatValue(120, 's'), '120');
    // ...rounded to two, as Java's %.2f rounds an actual, apart from its trailing zeros.
    assert.strictEqual(SK._formatValue(2.954, 's'), '2.95');
    assert.strictEqual(SK._formatValue(24.7606, '/s'), '24.76');
});

test('SF15: milliseconds keep two decimals too — 2.95 ms is not 3 ms, and 0.6 ms is not 1', () => {
    // A server millisecond is a custom query's number, not the client's response time: whole ms
    // put "3 ms" beside "fail > 3 ms" on a PASS, and wrote 0.6 as 1 while 0.4 stayed 0.4.
    assert.strictEqual(SK._formatValue(2.95, 'ms'), '2.95');
    assert.strictEqual(SK._formatValue(0.6, 'ms'), '0.6');
    assert.strictEqual(SK._formatValue(0.5, 'ms'), '0.5');
    assert.strictEqual(SK._formatValue(803.4, 'ms'), '803.4');
    assert.strictEqual(SK._formatValue(1250, 'ms'), '1,250');
});

test('SF15: a small value is never zero, on every branch — 0.4 ms is not 0 ms', () => {
    assert.strictEqual(SK._formatValue(0.4, 's'), '0.4');
    assert.strictEqual(SK._formatValue(0.4, 'ms'), '0.4');
    assert.strictEqual(SK._formatValue(-0.4, 'ms'), '-0.4');
    assert.strictEqual(SK._formatValue(0.004, '%'), '0.004');
    assert.strictEqual(SK._formatValue(0.004, 'ms'), '0.004');
    assert.strictEqual(SK._formatValue(0.0000123, ''), '0.000012');
    // Zero is zero, and never "-0".
    assert.strictEqual(SK._formatValue(0, 'ms'), '0');
    assert.strictEqual(SK._formatValue(-0, 's'), '0');
    assert.strictEqual(SK._formatValue(null, 's'), '—');
});

test('SF15: an actual beside its bands is never written onto one it is not on, or across it', () => {
    // A FAIL at 3.004 read "3" beside "breached > 3"; a PASS at 2.996 read "3" beside "≥ 3".
    assert.strictEqual(SK._formatActual(3.004, 's', [3]), '3.004');
    assert.strictEqual(SK._formatActual(2.996, 's', [3]), '2.996');
    assert.strictEqual(SK._formatActual(2.9996, 's', [2.5, 3]), '2.9996');
    assert.strictEqual(SK._formatActual(1000.004, 'msgs', [1000]), '1,000.004');
    // Clear of every band, it is formatValue's two decimals.
    assert.strictEqual(SK._formatActual(2.954, 's', [3, 4]), '2.95');
    assert.strictEqual(SK._formatActual(2.9, 's', [null, undefined, 2.95]), '2.9');
    // ON the band is on the band.
    assert.strictEqual(SK._formatActual(3, 's', [3]), '3');
    // A computed number's float noise is never shown to separate it: 12 significant digits at most
    // below a billion (from there a third decimal at most - see C1(a) below).
    assert.strictEqual(SK._formatActual(59999999.99999998, 'B/min', [60000000]), '60,000,000');
    assert.strictEqual(SK._formatActual(0.0041, 's', [0.004]), '0.0041');
    assert.strictEqual(SK._formatActual(null, 's', [3]), '—');
    assert.strictEqual(SK._formatActual(2.95, 's'), '2.95');
    // Beside bands none of which is 0, a small actual is still never 0. The guard is the band of 0
    // formatActual adds to every set it is handed - since the hoist into WIR.digits, the only
    // place it lives; before it, formatActual began from measuredNumber and could not lose it.
    assert.strictEqual(SK._formatActual(0.004, 's', [1]), '0.004');
    assert.strictEqual(SK._formatActual(-0.004, '', [1]), '-0.004');
});

test('C1(a): from a billion up an actual still gets the digit that keeps it off its band', () => {
    // The digits loop started at max(2, magnitude + 3) and stopped at 12, so from magnitude 9 it
    // started where it stopped and could add nothing: a byte FAIL at 1,000,000,000.004 read
    // "1,000,000,000" beside "> 1,000,000,000 B". Bytes and message counts live here.
    assert.strictEqual(SK._formatActual(1e9 + 0.004, 'B', [1e9]), '1,000,000,000.004');
    // The average of 240 points of a 7.572 GB gauge with one point a byte higher.
    assert.strictEqual(SK._formatActual(7572000000.004167, 'B', [7572000000]), '7,572,000,000.004');
    // The board's own two decimals typed back as a band: the FAIL is above what it printed.
    assert.strictEqual(SK._formatActual(1234567890.1234, 'B', [1234567890.12]), '1,234,567,890.123');
    // Two integers are never confused, and clear of the band it is still two decimals.
    assert.strictEqual(SK._formatActual(7572000000, 'B', [7572000001]), '7,572,000,000');
    assert.strictEqual(SK._formatActual(1234567890.1234, 'B', [2e9]), '1,234,567,890.12');
    // Every magnitude up to where two decimals separate any two doubles. The plan's first cap, 15
    // significant digits, stopped at 1e12: there the third decimal is the 16th digit, and 0.004 is
    // some thirty of a double's steps - a measurement, not noise - yet a terabyte FAIL read on its
    // band. A cap of 13 or 14 would leave 1e10 and 1e11 the same.
    assert.strictEqual(SK._formatActual(1e10 + 0.004, 'B', [1e10]), '10,000,000,000.004');
    assert.strictEqual(SK._formatActual(1e11 + 0.004, 'B', [1e11]), '100,000,000,000.004');
    assert.strictEqual(SK._formatActual(1e12 + 0.004, 'B', [1e12]), '1,000,000,000,000.004');
    assert.strictEqual(SK._formatActual(2.5e12 - 0.004, 'B', [2.5e12]), '2,499,999,999,999.996');
    assert.strictEqual(SK._formatActual(1e13 + 0.004, 'B', [1e13]), '10,000,000,000,000.004');
    // The float noise of a computed number is still not a measurement: never past a third decimal,
    // or 12 significant digits below a billion. One double's step above 1e12 is 0.000122.
    assert.strictEqual(SK._formatActual(1e12 + 0.0001220703125, 'B', [1e12]), '1,000,000,000,000');
    assert.strictEqual(SK._formatActual(59999999.99999998, 'B/min', [60000000]), '60,000,000');
});

test('C1(a): a number too large for two decimals by ×100 is written as it is, never one unit off', () => {
    // Math.round(v * 100) / 100 loses the last digit once v * 100 passes 2^53: 799450271802898
    // printed "799,450,271,802,897.9", and 1e21 printed "999,999,999,999,999,900,000" - which
    // reads as below a band of 1e21 it equals.
    assert.strictEqual(SK._formatValue(799450271802898, 'B'), '799,450,271,802,898');
    assert.strictEqual(SK._formatValue(1e21, 'B'), '1,000,000,000,000,000,000,000');
    assert.strictEqual(SK._formatActual(1e21, 'B', [1e21]), '1,000,000,000,000,000,000,000');
    assert.strictEqual(SK._formatValue(-799450271802898, 'B'), '-799,450,271,802,898');
    // The edge from both sides, 2^53 / 100 = 90,071,992,547,409.9. Just over it the rounding would
    // already print ".05" for ".03"; under it, two decimals as before - the double's own digits
    // would print a third.
    assert.strictEqual(SK._formatValue(90071992547410.03, 'B'), '90,071,992,547,410.03');
    assert.strictEqual(SK._formatValue(50000000000000.125, 'B'), '50,000,000,000,000.12');
    assert.strictEqual(SK._formatValue(10000000000000.123, 'B'), '10,000,000,000,000.12');
    assert.strictEqual(SK._formatValue(90071992547.409, 'B'), '90,071,992,547.41');
});

test('SF17: every value is grouped, not exact integers only — the live slope reads as Java\'s', () => {
    // The mirror's slope is 59,999,999.99999998, which took the ungrouped branch: "60000000 B"
    // beside "fail > 50,000,000 B". Java's round4 is 60000000, which took the grouped one.
    assert.strictEqual(SK._formatValue(59999999.99999998, 'B/min'), '60,000,000');
    assert.strictEqual(SK._formatValue(1234.567, 'msgs'), '1,234.57');
    assert.strictEqual(SK._formatValue(1234.5, ''), '1,234.5');
});

// ---------------------------------------------------------------- SF15: bands

test('SF15: a band is never rounded — 0.4, 0.6, 3.6, 4.3, 2.95, 2.955 are themselves', () => {
    const cases = [[0.4, '0.4'], [0.6, '0.6'], [3.6, '3.6'], [4.3, '4.3'], [2.95, '2.95'],
        [2.955, '2.955'], [2.951, '2.951'], [2.954, '2.954'], [0.001, '0.001'], [1e-7, '0.0000001']];
    cases.forEach(([v, text]) => assert.strictEqual(SK._formatBand(v), text, String(v)));
    assert.strictEqual(SK._describeBand({ gt: 0.4 }, 's'), '> 0.4 s');
    assert.strictEqual(SK._describeBand({ gt: 2.95 }, 's'), '> 2.95 s');
    assert.strictEqual(SK._describeBand({ gt: 3.6 }, 's'), '> 3.6 s');
    assert.strictEqual(SK._describeBand({ lte: 0.6 }, 'ms'), '≤ 0.6 ms');
    // server-tab.spec.ts F5 pins the board's grouping on the same band.
    assert.strictEqual(SK._describeBand({ gt: 10000 }, '% ÷ load:throughput'), '> 10,000 % ÷ load:throughput');
});

test('SF15: a band\'s digits are Predicate.describe\'s, from 1e-3 to 1e7, grouped', () => {
    [0.001, 0.4, 0.6, 2.95, 2.955, 3.6, 4.3, 10, 99.5, 1000, 12345.678, 9999999].forEach((v) => {
        assert.strictEqual(SK._formatBand(v).replace(/,/g, ''), WIR.javaText.trim(v), String(v));
    });
});

test('SF15: a band too small for ICU\'s twenty decimals is written as Java writes it, never 0', () => {
    assert.strictEqual(SK._formatBand(1e-25), WIR.javaText.double(1e-25));
    assert.strictEqual(SK._formatBand(0), '0');
    assert.strictEqual(SK._formatBand(-0), '0');
});

// ---------------------------------------------------------------- SF17: the reader's locale

test('SF17: a German reader gets the same digits — both tabs write en-US, as the cards beside them do', () => {
    // The harness is only worth its word if the context really is German, and ICU really has it.
    assert.strictEqual((1234.5).toLocaleString('de-DE'), '1.234,5',
        'this node has no German ICU data, so the locale case below would prove nothing');
    const de = loadReportJs(undefined, { locale: 'de-DE' });
    const DS = de.serverKpis;
    const DA = de.asserts;
    // The board read "fail > 3,6 s" while its band box held "3.6", and the Summary card "2,95 s"
    // beside the client card's "2.95 %". FormatUtil writes the Thymeleaf cards US-fixed.
    assert.strictEqual(DS._formatValue(1234.567, ''), '1,234.57');
    assert.strictEqual(DS._formatValue(2.95, 's'), '2.95');
    assert.strictEqual(DS._formatBand(3.6), '3.6');
    assert.strictEqual(DS._describeBand({ gt: 10000 }, '% ÷ load:throughput'), '> 10,000 % ÷ load:throughput');
    assert.strictEqual(DS._formatActual(3.004, 's', [3]), '3.004');
    assert.strictEqual(DS._axisTick(1.5e9), '1.5G');
    // The client's formatter, the same rule: "1.250 ms" was ten thousand's spelling one cell away.
    assert.strictEqual(DA._formatValue(1250, 'ms'), '1,250 ms');
    assert.strictEqual(DA._formatValue(10000, ''), '10,000');
    assert.strictEqual(DA._formatValue(2.95, '%'), '2.95 %');
    assert.strictEqual(DA._formatValue(24.7606, '/s'), '24.76 /s');
    assert.strictEqual(DA._formatValue(100000, '/s'), '100,000 /s');
});

// ---------------------------------------------------------------- SF16: the verdict unit

test('SF16: a slope verdict is per minute, a ratio is per unit of its load, and both together', () => {
    assert.strictEqual(SK._verdictUnit(kpi({ unit: 'B', reduce: { kind: 'max' } })), 'B');
    assert.strictEqual(SK._verdictUnit(kpi({ unit: 'B', reduce: { kind: 'slope' } })), 'B/min');
    // No unit: "/min" alone, ServerKpi.verdictUnit()'s spelling (the catalogue's deadlocks unit).
    assert.strictEqual(SK._verdictUnit(kpi({ reduce: { kind: 'slope' } })), '/min');
    assert.strictEqual(SK._verdictUnit(kpi({ unit: 'msgs', reduce: { kind: 'max' }, divideBy: 'load:throughput' })),
        'msgs ÷ load:throughput');
    assert.strictEqual(SK._verdictUnit(kpi({ unit: 'B', reduce: { kind: 'slope' }, divideBy: 'load:throughput' })),
        'B/min ÷ load:throughput');
    assert.strictEqual(SK._verdictUnit(kpi({ reduce: { kind: 'max' }, divideBy: 'load:throughput' })), '÷ load:throughput');
    // An unreadable reducer keeps the declared unit: nothing says it is a rate.
    assert.strictEqual(SK._verdictUnit(kpi({ unit: 'B', reduce: { kind: 'slopee' } })), 'B');
});

test('SF16: a baked entry (chartData.serverKpis) reads the same way, from its wire reduce', () => {
    // What the Summary card draws: the build's entries carry `reduce: {kind}` and `divideBy`.
    assert.strictEqual(SK._verdictUnit({ unit: 'B', reduce: { kind: 'slope' } }), 'B/min');
    assert.strictEqual(SK._verdictUnit({ unit: 'msgs', reduce: { kind: 'max' }, divideBy: 'load:unreachable' }),
        'msgs ÷ load:unreachable');
    assert.strictEqual(SK._verdictUnit({ unit: '%', reduce: { kind: 'slope' }, divideBy: 'load:throughput' }),
        '%/min ÷ load:throughput');
    assert.strictEqual(SK._verdictUnit({ unit: 'B', reduce: null }), 'B');
});

test('SF16: a catalogue metric\'s verdict unit is its unit, per minute on a slope', () => {
    assert.strictEqual(SK._metricVerdictUnit(POSTGRES.metrics['database.size.growth']), 'B/min');
    assert.strictEqual(SK._metricVerdictUnit(POSTGRES.metrics['connections.used.pct']), '%');
    assert.strictEqual(SK._metricVerdictUnit(POSTGRES.metrics['transaction.longest.seconds']), 's');
    assert.strictEqual(SK._metricVerdictUnit(null), '');
});

// ---------------------------------------------------------------- SF17: the picker, the axes

test('SF17: the picker\'s Suggested line is written as the board writes a band, in the line above\'s unit', () => {
    const bands = { failOp: 'gt', failValue: '15000', warnOp: 'gt', warnValue: '12500' };
    assert.strictEqual(SK._suggestedText(bands, 'msgs'), 'fail > 15,000 msgs · warn > 12,500 msgs');
    assert.strictEqual(SK._suggestedText({ failOp: 'lt', failValue: '1', warnOp: 'lt', warnValue: '' }, ''), 'fail < 1');
    // An operator the old line could only write as ">" or "<" is written as the board writes it.
    assert.strictEqual(SK._suggestedText({ failOp: 'gte', failValue: '4.3', warnOp: 'gt', warnValue: '' }, 's'),
        'fail ≥ 4.3 s');
    assert.strictEqual(SK._suggestedText({ failOp: 'gt', failValue: '', warnOp: 'gt', warnValue: '' }, 's'), null);
});

test('SF17: the Suggested slot\'s other branch writes its dropped band as the board does too', () => {
    // noBandText wrote through describePredicate, Java's ungrouped digits: one slot read
    // "fail < -1500" from this branch and "fail < -1,500" from suggestedText.
    const lower = { direction: 'lower', unit: 'brokers', reduce: 'max' };
    assert.strictEqual(SK._noBandText(lower, { observed: -1000, reducer: { kind: 'max' } }),
        'none from this run — fail < -1,500 brokers can only fire on a negative value, so set a band');
    assert.strictEqual(SK._noBandText(lower, { observed: 0, reducer: { kind: 'max' } }),
        'none from this run — fail < 0 brokers can only fire on a negative value, so set a band');
    assert.strictEqual(SK._noBandText(lower, null), 'none until this metric has data');
});

test('SF16/SF17: the run\'s number is in the verdict unit, grouped as its Suggested line', () => {
    const growth = POSTGRES.metrics['database.size.growth'];
    const slope = { kind: 'slope' };
    assert.strictEqual(SK._observedText({ observed: 59999999.99999998, reducer: slope }, growth),
        'slope 60,000,000 B/min');
    // No unit: "/min", as the board writes the same verdict — it was "per minute" here alone.
    assert.strictEqual(SK._observedText({ observed: 5, reducer: slope }, { unit: '' }), 'slope 5 /min');
    assert.strictEqual(SK._observedText({ observed: 2.9, reducer: { kind: 'max' } },
        POSTGRES.metrics['transaction.longest.seconds']), 'peak 2.9 s');
    assert.strictEqual(SK._observedText({ observed: null, reducer: slope }, growth), null);
});

test('SF17: an axis tick is short from a million up, and written as the board writes it below', () => {
    assert.strictEqual(SK._axisTick(6e9), '6G');
    assert.strictEqual(SK._axisTick(1.5e9), '1.5G');
    assert.strictEqual(SK._axisTick(-2e6), '-2M');
    assert.strictEqual(SK._axisTick(2e12), '2T');
    // Past a trillion it does not run long again: "1,000T" was 1e15's tick.
    assert.strictEqual(SK._axisTick(1e15), '1P');
    assert.strictEqual(SK._axisTick(1e18), '1E');
    assert.strictEqual(SK._axisTick(999999), '999,999');
    assert.strictEqual(SK._axisTick(0.25), '0.25');
    assert.strictEqual(SK._axisTick(0), '0');
    assert.strictEqual(SK._axisTick(NaN), '');
});

test('SF17: one axis, one prefix — "0.5M · 1M · 1.5M", never "500,000 · 1M"', () => {
    const ticks = (values, at) => at.map(SK._axisTicker(values));
    // Chosen per tick, one axis read "0 · 500,000 · 1M · 1.5M · 2M · 2.5M".
    assert.deepStrictEqual(ticks([0, 2.4e6], [0, 5e5, 1e6, 1.5e6, 2e6, 2.5e6]),
        ['0', '0.5M', '1M', '1.5M', '2M', '2.5M']);
    // Data reaching 820,000 is ticked to a million: the whole axis is in M.
    assert.deepStrictEqual(ticks([0, 820000], [0, 200000, 600000, 1000000]), ['0', '0.2M', '0.6M', '1M']);
    // Well below a million, no prefix at all.
    assert.deepStrictEqual(ticks([0, 500000], [0, 100000, 500000]), ['0', '100,000', '500,000']);
    assert.deepStrictEqual(ticks([-1, 6e9, null, NaN], [0, 5e8, 6e9]), ['0', '0.5G', '6G']);
    assert.deepStrictEqual(ticks([], [0, 0.25, 12]), ['0', '0.25', '12']);
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
