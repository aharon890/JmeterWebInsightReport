/**
 * The marks the Asserts tab puts on the build's findings (findingMark, SF18), driven through the
 * page's own lifecycle (harness.loadReportPage) with no browser. The rest of findingMark's contract —
 * fixed, edited and gone on a rule the tab edits — is server-findings.spec.ts's, over E25.
 *
 *  - NIT-3: the legacy loadValidity block's migration notice (GAP-1). GateEvaluation files it as a
 *    WARNING under the id of the rule the block became, on a run whose file carried a block that
 *    became a rule (GateEvaluationTest pins when). findingMark read every warning as a NO_MATCH, so
 *    editing load:throughput marked the notice "edited — the next run checks it again" and nothing
 *    ever marked it fixed. The next run files it again over the file this report was generated with,
 *    block and all, whatever the rule says; a draft's Download writes the migrated rule into
 *    assertions[] and never the block. So it stands while this page has no client draft and is fixed
 *    once it has one — the list's own scope line, "each marked where this draft has fixed…"; never
 *    edited, never gone. The page's document is no witness: the run consumed the block and
 *    documentOf writes none back, so it never carries one, and keyed on it the notice read fixed on
 *    every shipped report from the first load.
 *  - REV-1: a rule renamed in the draft is matched to the file's rule as both are written but for
 *    their ids (renamedRule), and the comparison copy assigned each key, so a "__proto__" key set
 *    the copy's prototype instead and dropped out of the comparison: a rule renamed with only its
 *    "__proto__" value changed read as the file's rule, renamed, and its finding as edited.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage } = require('./harness');

const A0 = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const SAMPLERS = [{ name: 'A', count: 10, p95: 50, mean: 30, throughput: 5 }];
const OVERALL = { totalSamples: 10, p95: 50, mean: 30, throughput: 0.0019 };

/**
 * A page as the build writes it for a file declaring `entries`, with `findings` as the build filed
 * them: annotationsDocument is the document after the migration (documentOf writes no loadValidity
 * block — the run consumed it), and each payload rule carries the verdict the evaluator took.
 */
function page(entries, findings) {
    const chartData = {
        runId: 'asserts-findings',
        annotationsDocument: JSON.parse(JSON.stringify({ assertions: entries })),
        samplers: SAMPLERS,
        overall: OVERALL,
        assertFindings: findings
    };
    const judged = A0.evaluate(entries.map((r, i) => A0._ruleFromJson(r, i)), chartData);
    chartData.assertions = JSON.parse(JSON.stringify(judged.rules));
    chartData.gate = JSON.parse(JSON.stringify(judged.gate));
    return loadReportPage(chartData);
}

// ---------------------------------------------------------------------------
// NIT-3 — the migration notice
// ---------------------------------------------------------------------------

/* What LoadValidityMigration migrates E25's block into, and the notice GateEvaluation files for it. */
const MIGRATED = {
    id: 'load:throughput',
    description: 'Load validity — asked for 0.002 throughput. A run that never reached its target load cannot '
        + 'validate a threshold written for that load.',
    target: { scope: 'overall' },
    metric: 'throughput',
    warn: { lt: 0.0015 },
    fail: { lt: 0.001 }
};
const OTHER = { id: 'a-p95', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 100 } };
const NOTICE = {
    severity: 'warning',
    ruleId: 'load:throughput',
    message: "the legacy loadValidity block was migrated into assertions[] as 'load:throughput'. It is now an "
        + 'ordinary rule you can edit, disable or delete from the Asserts tab. Save the file from that tab to '
        + 'make the change permanent — until you do, it is re-created on every run.'
};
/* The block itself, as the file the report was generated with wrote it. */
const BLOCK = { target: 0.002, warn: { tolerance: 0.25 }, fail: { tolerance: 0.5 } };

function shipped() {
    return page([OTHER, MIGRATED], [NOTICE]);
}

/** Open a document on the page, as Open file… and Edit document… do. */
function open(WIR, doc) {
    const problem = WIR.document.replace(JSON.parse(JSON.stringify(doc)), 'opened.json');
    assert.strictEqual(problem, null, 'the document was refused: ' + problem);
}

test('NIT-3: on the page as the build shipped it — no draft — the notice stands', () => {
    // The next run reads the file this report was generated with, block and all, and files it again.
    // Was 'fixed', struck through from the first load: the page's document carries no block (the run
    // consumed it), which says nothing about the file the next run reads.
    const WIR = shipped();
    assert.strictEqual(WIR.document.countOf('asserts'), 0, 'precondition: no client draft');
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), null);
});

test('NIT-3: editing the migrated rule marks the notice fixed, never edited', () => {
    const WIR = shipped();
    WIR.asserts._retune('load:throughput', 0.0012, 'fail', 0.0001);
    assert.strictEqual(WIR.asserts._state.rules[1].draftState, 'edited', 'precondition: the rule is edited');
    // Was 'edited': read as a NO_MATCH finding about the rule, which the next run checks again. The
    // draft's Download writes the rule into assertions[] and no block, so no next run files it.
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), 'fixed');
    assert.ok(!('loadValidity' in JSON.parse(WIR.document.text())), 'the Download writes no block');
});

test('NIT-3: deleting the migrated rule settles the notice as fixed, not gone', () => {
    const WIR = shipped();
    WIR.asserts.deleteRule(WIR.asserts._keyOf('load:throughput'));
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), 'fixed');
});

test('NIT-3: an edit of any other rule fixes it as well — the rule is not what is checked', () => {
    const WIR = shipped();
    WIR.asserts._retune('a-p95', 120, 'fail', 1);
    assert.strictEqual(WIR.document.countOf('asserts'), 1, 'precondition: one client edit');
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), 'fixed');
});

test('NIT-3: Discard drafts stands it again', () => {
    const WIR = shipped();
    WIR.asserts._retune('load:throughput', 0.0012, 'fail', 0.0001);
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), 'fixed', 'precondition');
    WIR.asserts.discardDrafts();
    assert.strictEqual(WIR.document.countOf('asserts'), 0, 'precondition: the draft is gone');
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), null);
});

test('NIT-3: the file the report was generated with, opened again, is no draft and leaves it standing', () => {
    const WIR = shipped();
    open(WIR, { assertions: [OTHER], loadValidity: BLOCK });
    assert.ok(WIR.asserts._keyOf('load:throughput'), 'precondition: the page migrated the block, as the run does');
    assert.strictEqual(WIR.document.countOf('asserts'), 0, 'precondition: the same rules as the build');
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), null);
    // An edit is a draft whose Download carries the rule and no block.
    WIR.asserts._retune('load:throughput', 0.0012, 'fail', 0.0001);
    assert.strictEqual(WIR.asserts._findingMark(NOTICE), 'fixed');
    assert.ok(!('loadValidity' in JSON.parse(WIR.document.text())), 'the Download writes no block');
});

test('NIT-3: only the notice itself is read this way — another warning under the id keeps its marks', () => {
    // A NO_MATCH-shaped warning filed under the migrated rule's id: it is about the rule, and an edit
    // of the rule is what it is marked by.
    const other = { severity: 'warning', ruleId: 'load:throughput', message: 'target Whole run matched nothing' };
    const WIR = shipped();
    assert.strictEqual(WIR.asserts._findingMark(other), null, 'an unedited rule');
    WIR.asserts._retune('load:throughput', 0.0012, 'fail', 0.0001);
    assert.strictEqual(WIR.asserts._findingMark(other), 'edited');
    // And the notice's sentence under another id is not the notice.
    const elsewhere = Object.assign({}, NOTICE, { ruleId: 'a-p95' });
    assert.strictEqual(WIR.asserts._findingMark(elsewhere), null);
});

// ---------------------------------------------------------------------------
// REV-1 — a renamed rule is compared with its "__proto__" key
// ---------------------------------------------------------------------------

/* An entry carrying an own "__proto__" key, as JSON.parse and Java both keep one. */
function withProto(id, value) {
    return JSON.parse('{"id":' + JSON.stringify(id) + ',"target":{"sampler":"Gone"},"metric":"p95",'
        + '"fail":{"gt":100},"__proto__":' + JSON.stringify(value) + '}');
}

const UNMATCHED = { severity: 'warning', ruleId: 'r', message: 'target Gone matched nothing in this run' };

test('REV-1: a rule renamed and given another "__proto__" value is not the file\'s rule renamed', () => {
    const WIR = page([withProto('r', { keep: 'one' })], [UNMATCHED]);
    assert.strictEqual(WIR.asserts._state.loadedRules[0].notJudged, 'NO_MATCH', 'precondition');
    const doc = JSON.parse(WIR.document.text());
    doc.assertions = [withProto('r2', { keep: 'two' })];
    open(WIR, doc);
    assert.ok(Object.prototype.hasOwnProperty.call(JSON.parse(WIR.document.text()).assertions[0], '__proto__'),
        'precondition: the draft writes its "__proto__" key');
    // Was 'edited': both copies lost "__proto__", so the rule read as the file's, renamed.
    assert.strictEqual(WIR.asserts._findingMark(UNMATCHED), 'gone');
});

test('REV-1: renamed with its "__proto__" value as the file had it, it is the file\'s rule, renamed', () => {
    const WIR = page([withProto('r', { keep: 'one' })], [UNMATCHED]);
    const doc = JSON.parse(WIR.document.text());
    doc.assertions = [withProto('r2', { keep: 'one' })];
    open(WIR, doc);
    // Renamed and still unmatched: the next run files the warning again under r2.
    assert.strictEqual(WIR.asserts._findingMark(UNMATCHED), 'edited');
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
