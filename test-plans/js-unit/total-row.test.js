/**
 * The Samplers table's Σ Total row (B1 of the Aggregate Report check).
 *
 * JMeter's Aggregate Report computes its TOTAL row with a calculator of its own, fed every sample, so
 * its rate is all samples over the run's window. The row used to add up the rows' own rates, each
 * over that sampler's own window: P17 read 41.69/s where JMeter's TOTAL is 11.31/s. The row now takes
 * every whole-run figure from chartData.overall (OverallMetrics, the Summary tiles' numbers), its
 * counts, min and max included: the whole run is counted per sample. Adding up the rows that are no
 * transaction's child left out every sample of a name that also ran inside a transaction
 * (tails-conversation-flows, 2026-11). The rows stay the fallback for a payload without those fields.
 * That the row renders is the browser's question (samplers-tab.spec.ts).
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// Two samplers: "Home" ran the whole 100 s at 10/s, "Late" its last 10 s at 10/s. The run's rate is
// 1,100 samples over 100 s = 11/s; the sum of the rows' rates is 20/s.
const payload = () => ({
    samplers: [
        { name: 'Home', count: 1000, errorCount: 10, mean: 40, min: 5, max: 900, throughput: 10, receivedBytesPerSec: 10240, apdex: 0.99 },
        { name: 'Late', count: 100, errorCount: 0, mean: 400, min: 300, max: 600, throughput: 10, receivedBytesPerSec: 102400, apdex: 0.5 },
        { name: 'Late child', count: 100, errorCount: 7, mean: 1, min: 1, max: 2000, throughput: 10, receivedBytesPerSec: 999, apdex: 0, parent: 'Late' },
    ],
    overall: { mean: 72.73, median: 30, p90: 400, p95: 500, p99: 800, throughput: 11, receivedBytesPerSec: 20480, sentBytesPerSec: 1024, apdex: 0.95 },
});
const isChild = (name) => name === 'Late child';

test('B1: the rate, KB/s, mean and Apdex are the whole run\'s, not sums or means of the rows', () => {
    const scope = loadReportScope();
    const t = scope.totalRowFigures(payload(), isChild);
    assert.strictEqual(t.throughput, 11, 'all samples over the run, not 10 + 10');
    assert.strictEqual(t.receivedKBs, 20, '20,480 B/s over the run, not 10 + 100 KB/s');
    assert.strictEqual(t.sentKBs, 1, 'the Sent KB/s column (B8), over the run as well');
    assert.strictEqual(t.mean, 72.73);
    assert.strictEqual(t.apdex, 0.95, 'from the counts (B4), not a mean of the rounded scores');
    assert.deepStrictEqual([t.median, t.p90, t.p95, t.p99], [30, 400, 500, 800]);
});

test("the counts, errors, min and max are the whole run's too, counted per sample", () => {
    // "Open" ran on its own 16 times (5 failed) and inside "Whole" 4 times, so its row is a child row
    // of 20; the whole run is Whole 4 + Open 16 + Heartbeat 20 = 40 samples, 5 failed
    const scope = loadReportScope();
    const t = scope.totalRowFigures({
        samplers: [
            { name: 'Heartbeat', count: 20, errorCount: 0, min: 3, max: 9 },
            { name: 'Whole', count: 4, errorCount: 0, min: 40, max: 50 },
            { name: 'Open', count: 20, errorCount: 5, min: 10, max: 70, parent: 'Whole' },
        ],
        overall: { totalSamples: 40, totalErrors: 5, min: 3, max: 70, mean: 20 },
    }, (name) => name === 'Open');
    assert.strictEqual(t.samples, 40, "not 24: the rows leave out Open's 16 runs on its own");
    assert.strictEqual(t.errors, 5, 'not 0');
    assert.strictEqual(t.errorPct, 5 / 40 * 100);
    assert.strictEqual(t.min, 3);
    assert.strictEqual(t.max, 70);
});

test("B1: without the whole run's counts, they come from the top-level rows", () => {
    const scope = loadReportScope();
    const t = scope.totalRowFigures(payload(), isChild);
    assert.strictEqual(t.samples, 1100);
    assert.strictEqual(t.errors, 10, 'the transaction child\'s errors are inside its parent');
    assert.strictEqual(t.errorPct, 10 / 1100 * 100);
    assert.strictEqual(t.min, 5);
    assert.strictEqual(t.max, 900, 'the child\'s 2,000 ms is not a top-level sample');
});

test('B1: no rows reads zero, as the empty row always did', () => {
    const scope = loadReportScope();
    const t = scope.totalRowFigures({ samplers: [] }, () => false);
    assert.deepStrictEqual([t.samples, t.errors, t.errorPct, t.min, t.max], [0, 0, 0, 0, 0]);
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
