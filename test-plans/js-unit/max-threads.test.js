/**
 * The threads line over the response times (T8 of the Aggregate Report check).
 *
 * The engine keeps the most threads seen in each second (every thread group together, the Max Threads
 * tile's count); above 1 s the page averaged those maxima, and the line was called "Active Threads"
 * beside JMeter's Active Threads Over Time, which draws each thread group's mean. JMeter has no
 * all-groups line, so this one is named for what it shows and a window takes its seconds' maximum.
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('T8: the line is named Max Active Threads', () => {
    const scope = loadReportScope();
    assert.strictEqual(scope.MAX_THREADS_SERIES, 'Max Active Threads');
});

test('T8: a 10 s window takes its seconds\' maximum, not the mean of their maxima', () => {
    const scope = loadReportScope();
    const t0 = 1700000000000;   // on the 10 s grid
    const ts = [t0, t0 + 1000, t0 + 2000, t0 + 10000];
    const threads = [2, 10, 4, 7];
    scope.chartGranularity = 10000;
    const points = Array.from(scope.zipTimeSeries(ts, threads, scope.aggOptsForMetric('max')), (p) => Array.from(p));
    scope.chartGranularity = 1000;
    assert.deepStrictEqual(points, [[t0, 10], [t0 + 10000, 7]], 'was (2 + 10 + 4) / 3 = 5.33 for the first window');
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
