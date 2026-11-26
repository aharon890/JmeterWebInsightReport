/**
 * Spec 004: a pair of two tools writes each cell as "baseline → current" with the change under it. A throughput's
 * change is written as the Aggregate Report writes a rate, per second, minute or hour as its size asks, like the two
 * numbers above it: 13.2/min → 13.8/min changed by +0.6/min. It read "+0.0/s" (found on perf hub's runs #82 and #83,
 * a JMeter and a k6 SMOKE of petstore-checkout, 2026-11-26). A pair of one tool keeps its superscript as it was.
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const scope = loadReportScope();
scope.globalChartData = {
    hasComparison: true, sameTool: false, baselineTool: 'JMeter', currentTool: 'k6',
    metadata: { toolName: 'k6', notMeasured: [] }, comparisonDiffs: []
};

function pairDelta(baseRate, currRate) {
    const d = {
        name: 'Login', baseSamples: 6, currSamples: 6, baseErrors: 0, currErrors: 0,
        baseMean: 29, currMean: 8, deltaMean: -21, baseMedian: 10, currMedian: 8, deltaMedian: -2,
        baseP90: 11, currP90: 8, deltaP90: -3, baseP95: 122, currP95: 9, deltaP95: -113,
        baseP99: 122, currP99: 9, deltaP99: -113, baseMin: 8, currMin: 5, deltaMin: -3, baseMax: 122, currMax: 9,
        deltaMax: -113, baseThroughput: baseRate, currThroughput: currRate, deltaThroughput: currRate - baseRate,
        deltaSamples: 0, deltaErrors: 0, deltaErrorRate: 0
    };
    const html = scope.comparisonRowHtml(d, ['name', 'throughput']);
    const cell = html.match(/data-col-id="throughput"[\s\S]*?<\/td>/);
    assert.ok(cell, 'the throughput cell is drawn');
    const delta = cell[0].match(/class="cmp-pair-delta">([^<]*)</);
    return delta ? delta[1] : null;
}

let passed = 0;
function check(name, fn) {
    fn();
    passed++;
    console.log('  ok  ' + name);
}

check('a change of a few per minute is written per minute', () => {
    assert.strictEqual(pairDelta(13.2 / 60, 13.8 / 60), '+0.6/min');
});
check('a fall is written with its sign', () => {
    assert.strictEqual(pairDelta(13.8 / 60, 13.2 / 60), '-0.6/min');
});
check('a change of many per second stays per second', () => {
    assert.strictEqual(pairDelta(50, 111.1), '+61.1/s');
});
check('no change reads 0', () => {
    assert.strictEqual(pairDelta(0.25, 0.25), '0');
});

console.log(`compare-pair-throughput: ${passed} passed`);
