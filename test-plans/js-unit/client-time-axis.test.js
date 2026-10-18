/**
 * The client charts' time axis (XLOG-1).
 *
 * The Timeline tab's charts, the Summary tab's response-time chart and the error timeline drew their
 * time labels with no hideOverlap, so at 390px they printed over one another ("22:1226:2…") — what
 * the Server tab's charts stopped doing in SF35 (EXPLORATORY-LOG's SF35 row: "the client Timeline's
 * overprint at 390 predates B6 and stays"). They take one axis now, timelineXAxis, which hides a
 * label that would land on another, as the Server tab's do.
 *
 * Two halves: the axis itself, read through the page's own builder; and every time axis report.js
 * writes with the client's formatter, read as text, so a chart that builds its own axis again — the
 * way all three did — cannot come back without the option. That labels do not overlap when drawn is
 * the browser's question: client-time-axis.spec.ts.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const { loadReportScope, REPORT_JS } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('XLOG-1: the client time axis hides a label that would print over another', () => {
    const scope = loadReportScope();
    assert.strictEqual(typeof scope.timelineXAxis, 'function', 'no shared client time axis');
    scope.globalChartData = { timestamps: [1700000000000, 1700000030000, 1700000600000] };
    const axis = scope.timelineXAxis();
    assert.strictEqual(axis.type, 'time');
    assert.strictEqual(axis.axisLabel.formatter, scope.formatTimeAxis, 'the clock / elapsed formatter');
    assert.strictEqual(axis.axisLabel.hideOverlap, true);
    // The run, end to end: what every client time chart has always been bounded to.
    assert.strictEqual(axis.min, 1700000000000);
    assert.strictEqual(axis.max, 1700000600000);
});

test('XLOG-1: with no run on the page the axis is unbounded, as it was', () => {
    const scope = loadReportScope();
    scope.globalChartData = {};
    const axis = scope.timelineXAxis();
    assert.strictEqual(axis.min, undefined);
    assert.strictEqual(axis.max, undefined);
    assert.strictEqual(axis.axisLabel.hideOverlap, true);
});

test('XLOG-1: every time axis written with the client formatter hides overlapping labels', () => {
    // Each `type: 'time'` object literal, up to its closing brace at the same depth.
    const source = fs.readFileSync(REPORT_JS, 'utf8');
    const axes = [];
    const re = /\{\s*type:\s*'time'/g;
    let m;
    while ((m = re.exec(source))) {
        let depth = 0;
        let end = m.index;
        for (; end < source.length; end++) {
            if (source[end] === '{') depth++;
            else if (source[end] === '}' && --depth === 0) break;
        }
        axes.push({ line: source.slice(0, m.index).split('\n').length, text: source.slice(m.index, end + 1) });
    }
    const client = axes.filter((a) => /formatter:\s*formatTimeAxis\b/.test(a.text));
    assert.ok(client.length >= 2, 'found ' + client.length + ' time axes with formatTimeAxis: is this still report.js?');
    const bare = client.filter((a) => !/hideOverlap:\s*true/.test(a.text)).map((a) => 'line ' + a.line);
    assert.deepStrictEqual(bare, [], 'a time axis that prints its labels over one another');
    // ...and the three that built their own take the shared one.
    const uses = (source.match(/xAxis:\s*timelineXAxis\(\)/g) || []).length;
    assert.strictEqual(uses, 3, 'finalizeTimelineChart, the Summary chart and the error timeline: ' + uses);
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
