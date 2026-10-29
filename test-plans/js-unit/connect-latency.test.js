/**
 * The Connect / Latency / Processing chart above 1 s (T4 of the Aggregate Report check).
 *
 * Each second's breakdown is a count-weighted mean over the samplers shown. Folding seconds into a
 * larger window has to weigh each second by those same samplers' counts; it weighed them by the
 * bucket's count of every sample, hidden samplers and transaction children included, so a second
 * the shown samplers barely touched counted as much as their busiest. The chart itself is the
 * browser's question; this holds the numbers it is handed.
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// Two seconds. "Shown" sends 9 requests in the first (connect 10 ms) and 1 in the second (connect
// 100 ms); "Hidden" sends 1 and 99, and is not on the chart.
const perSampler = {
    Shown: { connectTime: [10, 100], latency: [20, 120], responseTime: [30, 130], throughput: [9, 1] },
    Hidden: { connectTime: [5, 5], latency: [6, 6], responseTime: [7, 7], throughput: [1, 99] },
};

test('T4: a component\'s weight per second is the shown samplers\' count', () => {
    const scope = loadReportScope();
    const b = scope.connectLatencyBreakdown(perSampler, ['Shown'], 2);
    assert.strictEqual(b.hasData, true);
    assert.deepStrictEqual(Array.from(b.connect), [10, 100]);
    assert.deepStrictEqual(Array.from(b.connectWeights), [9, 1]);
    assert.deepStrictEqual(Array.from(b.latency), [10, 20], 'latency net of connect');
    assert.deepStrictEqual(Array.from(b.processing), [10, 10], 'elapsed net of latency');
});

test('T4: a 10 s window weighs each second by those counts, not by every sample of the run', () => {
    const scope = loadReportScope();
    const ts = [1700000000000, 1700000001000];
    const b = scope.connectLatencyBreakdown(perSampler, ['Shown'], 2);
    const folded = scope.aggregateTimeSeries(ts, b.connect, 10000, { mode: 'wavg', counts: b.connectWeights });
    assert.strictEqual(folded[0][1], (10 * 9 + 100 * 1) / 10, 'the shown requests\' own mean: 19 ms');
    // What the bucket's total count (10, 100) gave: (10 * 10 + 100 * 100) / 110 = 91.8 ms.
    const allSamples = scope.aggregateTimeSeries(ts, b.connect, 10000, { mode: 'wavg', counts: [10, 100] });
    assert.notStrictEqual(folded[0][1], allSamples[0][1]);
});

test('T4: no breakdown for samplers that reported none', () => {
    const scope = loadReportScope();
    const b = scope.connectLatencyBreakdown({ Jsr: { responseTime: [5], throughput: [3] } }, ['Jsr'], 1);
    assert.strictEqual(b.hasData, false);
    assert.strictEqual(b.connect[0], null);
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
