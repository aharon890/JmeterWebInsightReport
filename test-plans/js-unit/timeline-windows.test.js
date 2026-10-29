/**
 * The Timeline's windows above 1 s (T2 and T3 of the Aggregate Report check).
 *
 * JMeter's charts put a request in the window that is a multiple of the granularity counted from the
 * epoch (TimeStampKeysSelector: time - time % granularity), draw the point at the window's start, and
 * divide a rate by the window's width (TimeRateAggregator). aggregateTimeSeries opened every series'
 * first window at the run's first second and drew at the middle: a late sampler's first point sat at
 * run start + half a window however late it began, and the last point fell past the axis. What the
 * chart then draws is the browser's question; these hold the numbers it is handed.
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const T0 = 1700000003000;   // the run's first second: 3 s into a 10 s window
const seconds = (n) => Array.from({ length: n }, (_, i) => T0 + i * 1000);

test('T2: windows are multiples of the granularity from the epoch, drawn at their start', () => {
    const scope = loadReportScope();
    const ts = seconds(25);                                    // ...003000 to ...027000
    const out = scope.aggregateTimeSeries(ts, ts.map(() => 5), 10000, { mode: 'avg' });
    assert.deepStrictEqual(Array.from(out, (p) => p[0]), [1700000000000, 1700000010000, 1700000020000]);
    out.forEach((p) => assert.strictEqual(p[1], 5));
});

test('T2: a sampler that starts late is drawn in its own window, not at the run start', () => {
    // S17's shape: the run starts at T0, the sampler's first request is 240 s later.
    const scope = loadReportScope();
    const ts = seconds(300);
    const values = ts.map((t, i) => (i >= 240 ? 2 : null));
    const out = scope.aggregateTimeSeries(ts, values, 10000, { mode: 'avg' });
    const firstRequest = T0 + 240 * 1000;
    assert.strictEqual(out[0][0], Math.floor(firstRequest / 10000) * 10000, 'the window the first request fell in');
    assert.ok(firstRequest - out[0][0] < 10000, 'never a whole window before the sampler started');
});

test('T2: the last point sits inside the run, where the axis ends', () => {
    const scope = loadReportScope();
    const ts = seconds(95);
    const out = scope.aggregateTimeSeries(ts, ts.map(() => 1), 60000, { mode: 'avg' });
    assert.ok(out[out.length - 1][0] <= ts[ts.length - 1], 'drawn at or before the last second');
});

test('T2: the axis opens at the first window when windows are coarser than 1 s', () => {
    const scope = loadReportScope();
    scope.globalChartData = { timestamps: seconds(25) };
    scope.chartGranularity = 10000;
    assert.strictEqual(scope.timelineXAxis().min, 1700000000000);
    scope.chartGranularity = 1000;
    assert.strictEqual(scope.timelineXAxis().min, T0, 'at 1 s the first second, as before');
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
