/**
 * The Server tab's not-judged banner says what webinsight.report.server.failonerror does to the rows
 * it counts, and nothing more.
 *
 * It counts every row that decided nothing for a configuration or collection error (INVALID_RULE,
 * SOURCE_FAILED, LOW_COVERAGE) and every UNMATCHED one (NO_MATCH), and it ended "…=true makes them
 * fatal" whatever the mix. Java's NotJudged.isConfigError() excludes NO_MATCH, so the flag does
 * nothing for an UNMATCHED row: an operator who set it on a run whose only unjudged row was
 * UNMATCHED still exited 0 (PR #19/#20 review, finding 3).
 */
'use strict';

const assert = require('assert');
const { loadReportPage, loadCatalogue } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

function banner(reasons) {
    const WIR = loadReportPage({
        runId: 'server-problems',
        samplers: [],
        overall: { totalSamples: 0 },
        serverCatalogue: loadCatalogue()
    });
    const st = WIR.serverKpis._state;
    st.evaluated = true;
    st.kpis = reasons.map((r, i) => ({ id: 'k' + i, status: 'NOT_JUDGED', notJudged: r }));
    return WIR.serverKpis._problemsHtml();
}

test('configuration and collection errors only: the flag makes them fatal', () => {
    const html = banner(['SOURCE_FAILED', 'INVALID_RULE', 'LOW_COVERAGE']);
    assert.ok(html.includes('3 server asserts were not judged'), html);
    assert.ok(html.includes('webinsight.report.server.failonerror=true makes them fatal.'), html);
});

test('UNMATCHED only: never fatal, and said so', () => {
    const html = banner(['NO_MATCH']);
    assert.ok(html.includes('1 server assert was not judged'), html);
    assert.ok(!html.includes('makes them fatal'), html);
    assert.ok(html.includes('An UNMATCHED assert never fails the build, not even under '
        + 'webinsight.report.server.failonerror=true.'), html);
});

test('a mix: the flag is said to make only the errors among them fatal', () => {
    const html = banner(['NO_MATCH', 'SOURCE_FAILED', 'NO_MATCH']);
    assert.ok(html.includes('3 server asserts were not judged'), html);
    assert.ok(html.includes('makes the 1 configuration or collection error among them fatal; '
        + 'an UNMATCHED one never is.'), html);
});

test('rows that decided nothing for another reason are not counted', () => {
    assert.strictEqual(banner(['DISABLED', 'LOAD_NOT_ACHIEVED']), '');
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
        console.log('       ' + (e && e.message ? e.message.split('\n').slice(0, 3).join(' | ') : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
