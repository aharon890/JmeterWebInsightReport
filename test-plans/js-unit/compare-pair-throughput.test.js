/**
 * A Compare row's throughput change is written as the Aggregate Report writes a rate, per second, minute or hour as
 * the size of the cell's two rates asks, like the numbers it sits beside: 13.2/min → 13.8/min changed by +0.6/min.
 * Spec 004 fixed it for a pair of two tools, the grey difference under "baseline → current", which read "+0.0/s"
 * (found on perf hub's runs #82 and #83, a JMeter and a k6 SMOKE of petstore-checkout, 2026-11-26). A pair of one tool
 * kept "/s" at one decimal in its coloured superscript, so 13.2/min beside a baseline of 13.8/min read "-0.0/s"; it
 * takes the same rule now, with its colour (owner, 2026-11-27: "Fix the 2 report wordings").
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

/* A compared row of Login, whose throughput went from baseRate to currRate per second. A pair of two tools carries the
   baseline's figures beside this run's; a pair of one tool, as Java ships it, this run's figures and the changes. */
function row(baseRate, currRate, twoTools) {
    const d = {
        name: 'Login', isNew: false, removed: false, regression: false, currSamples: 6, currErrors: 0,
        currMean: 8, deltaMean: -21, currMedian: 8, deltaMedian: -2, currP90: 8, deltaP90: -3, currP95: 9,
        deltaP95: -113, currP99: 9, deltaP99: -113, currMin: 5, deltaMin: -3, currMax: 9, deltaMax: -113,
        currThroughput: currRate, deltaThroughput: currRate - baseRate, deltaSamples: 0, deltaErrors: 0, deltaErrorRate: 0
    };
    if (twoTools) {
        Object.assign(d, { baseSamples: 6, baseErrors: 0, baseMean: 29, baseMedian: 10, baseP90: 11, baseP95: 122,
            baseP99: 122, baseMin: 8, baseMax: 122, baseThroughput: baseRate });
    }
    return d;
}

function throughputCell(scope, d) {
    const html = scope.comparisonRowHtml(d, ['name', 'throughput']);
    const cell = html.match(/data-col-id="throughput"[\s\S]*?<\/td>/);
    assert.ok(cell, 'the throughput cell is drawn');
    return cell[0];
}

const pairScope = loadReportScope();
pairScope.globalChartData = {
    hasComparison: true, sameTool: false, baselineTool: 'JMeter', currentTool: 'k6',
    metadata: { toolName: 'k6', notMeasured: [] }, comparisonDiffs: []
};

/* The grey difference under a two-tool pair's "baseline → current". */
function pairDelta(baseRate, currRate) {
    const delta = throughputCell(pairScope, row(baseRate, currRate, true)).match(/class="cmp-pair-delta">([^<]*)</);
    return delta ? delta[1] : null;
}

const oneToolScope = loadReportScope();
oneToolScope.globalChartData = {
    hasComparison: true, sameTool: true, baselineTool: 'JMeter', currentTool: 'JMeter',
    metadata: {}, comparisonDiffs: []
};

/* A one-tool pair's superscript beside this run's figure: its class (the colour) and its text, or null for none. */
function superscript(baseRate, currRate) {
    const cell = throughputCell(oneToolScope, row(baseRate, currRate, false));
    const sup = cell.match(/<sup class="([^"]*)">([^<]*)<\/sup>/);
    return sup ? { cls: sup[1], text: sup[2] } : null;
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

/* A pair of one tool: Java rounds each rate per second to two decimals, so 13.2/min is 0.22/s and 13.8/min 0.23/s;
   the baseline's rate is this run's less the change, as Apply reads it. */
check('one tool: a rise of a few per minute is written per minute, green', () => {
    assert.deepStrictEqual(superscript(0.22, 0.23), { cls: 'delta-good', text: '+0.6/min' });
});
check('one tool: a fall of a few per minute is written per minute, red', () => {
    assert.deepStrictEqual(superscript(0.23, 0.22), { cls: 'delta-bad', text: '-0.6/min' });
});
check('one tool: the unit is the larger rate\'s, a fall from 1.2/s to 54/min written per second', () => {
    assert.deepStrictEqual(superscript(1.2, 0.9), { cls: 'delta-bad', text: '-0.3/s' });
});
check('one tool: a change of many per second stays per second', () => {
    assert.deepStrictEqual(superscript(24.91, 25.38), { cls: 'delta-good', text: '+0.5/s' });
});
check('one tool: a rate per hour is written per hour', () => {
    assert.deepStrictEqual(superscript(0.01, 0.0125), { cls: 'delta-good', text: '+9.0/hour' });
});
check('one tool: no change, no superscript', () => {
    assert.strictEqual(superscript(0.25, 0.25), null);
});
check('one tool: the figure beside it is unchanged, this run\'s rate as the Samplers table writes it', () => {
    const cell = throughputCell(oneToolScope, row(0.22, 0.23, false));
    assert.ok(cell.indexOf('>13.8/min<sup') >= 0, 'was: ' + cell);
});

console.log(`compare-pair-throughput: ${passed} passed`);
