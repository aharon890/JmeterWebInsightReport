/**
 * save-from-page.js compare — C38's field-by-field diff of what the page showed against what
 * `jmeter -g` wrote — over two tiny hand-made documents, so the comparison's own rules are pinned
 * without a JMeter run.
 *
 * The rule pinned here: the CI/CD JSON's reserved "(no gate)" entry (ServerGateEvaluation.NO_GATE_ID,
 * A1-6) is not compared against the page's gate strip, which deliberately has no badge for it — it
 * lists the gates the document DECLARES. Comparing it made C38 report a diff for any saved document
 * holding an assert that named no declared gate (review of 961f11b). The asserts themselves are
 * still compared, one by one; and a real disagreement on a declared gate is still a diff.
 *
 * And a client rule's sentence where it is INVALID_RULE (Batch K's review): C38's broken case
 * re-judges G3's refused rules, and a Save that drops a refused band from a rule the next run then
 * refuses on other grounds changes nothing on the wire but that sentence.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const SCRIPT = path.join(__dirname, '..', 'scripts', 'save-from-page.js');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const notJudged = (over) => Object.assign({
    total: 0, disabled: 0, noMatch: 0, noValue: 0, invalidRule: 0, sourceFailed: 0, lowCoverage: 0,
    loadNotAchieved: 0
}, over || {});

/** One declared gate that PASSes on one assert, and one assert that named a gate never declared. */
function documents() {
    const kafka = { status: 'PASS', declared: 1, evaluated: 1, passed: 1, warned: 0, failed: 0, notJudged: notJudged() };
    const reason = "gate 'kafak' is not declared under serverMetrics.gates (declared: kafka)";
    const page = {
        kpis: [
            { id: 'lag', status: 'PASS', notJudged: null, detail: null, actual: 100, coverage: 1, instances: 1, breached: null },
            { id: 'typo', status: 'NOT_JUDGED', notJudged: 'INVALID_RULE', detail: reason, actual: null, coverage: null,
                instances: 0, breached: null }
        ],
        gates: [{ id: 'kafka', would: 'PASS', unknown: false, summary: kafka }],
        client: { gate: null, rules: [] }
    };
    const ci = {
        serverStatus: 'NOT_EVALUATED',
        serverComponents: [
            Object.assign({ id: 'kafka', label: 'Kafka', type: 'custom', collectionId: 'c0ffee' }, kafka),
            Object.assign({ id: '(no gate)', label: '(no component)', collectionId: 'c0ffee' }, {
                status: 'NOT_EVALUATED', declared: 1, evaluated: 1, passed: 0, warned: 0, failed: 0,
                notJudged: notJudged({ total: 1, invalidRule: 1 })
            })
        ],
        serverAsserts: [
            { id: 'lag', status: 'PASS', actual: 100, coverage: 1, instances: 1 },
            { id: 'typo', status: 'NOT_JUDGED', notJudged: 'INVALID_RULE', reason, actual: null, coverage: null, instances: 0 }
        ]
    };
    return { page, ci };
}

function compare(page, ci) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wir-c38-'));
    try {
        const pageFile = path.join(dir, 'edited', 'page-verdicts.json');
        fs.mkdirSync(path.dirname(pageFile));
        fs.writeFileSync(pageFile, JSON.stringify(page));
        const ciFile = path.join(dir, 'ci.json');
        fs.writeFileSync(ciFile, JSON.stringify(ci));
        const r = spawnSync(process.execPath, [SCRIPT, 'compare', pageFile, ciFile], { encoding: 'utf8' });
        return { status: r.status, out: (r.stdout || '') + (r.stderr || '') };
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

test('the reserved "(no gate)" entry is not a gate the page is expected to show', () => {
    const { page, ci } = documents();
    const r = compare(page, ci);
    assert.strictEqual(r.status, 0, r.out);
    assert.ok(/1 server gates/.test(r.out), r.out);
});

test('...while a declared gate the page reads differently is still a diff', () => {
    const { page, ci } = documents();
    ci.serverComponents[0].status = 'FAIL';
    const r = compare(page, ci);
    assert.strictEqual(r.status, 1, r.out);
    assert.ok(r.out.includes('serverComponents kafka'), r.out);
});

test('...and so is the orphaned assert itself, which is compared by position like any other', () => {
    const { page, ci } = documents();
    ci.serverAsserts[1].notJudged = 'NO_MATCH';
    const r = compare(page, ci);
    assert.strictEqual(r.status, 1, r.out);
    assert.ok(r.out.includes('serverAsserts[1] typo notJudged'), r.out);
});

test('a client rule refused for another sentence is a diff, though it is INVALID_RULE on both sides', () => {
    // C38's broken case (C-1): a Save that drops the one band of G3's retired-outside-operator leaves
    // it INVALID_RULE — for having no band — and only the sentence says the band went. The other
    // NOT_JUDGED reasons are not compared, because the wire writes a phrase where the page holds none.
    const outside = 'fail outside is no longer an operator — so this rule declares a limit it cannot apply';
    const { page, ci } = documents();
    page.client.rules = [
        { id: 'retired-outside-operator', status: 'NOT_JUDGED', notJudged: 'INVALID_RULE', reason: outside },
        { id: 'unmatched', status: 'NOT_JUDGED', notJudged: 'NO_MATCH', reason: null }
    ];
    ci.assertions = [
        { id: 'retired-outside-operator', status: 'NOT_JUDGED', notJudged: 'INVALID_RULE', reason: outside },
        { id: 'unmatched', status: 'NOT_JUDGED', notJudged: 'NO_MATCH', reason: 'the target matched no sampler' }
    ];
    assert.strictEqual(compare(page, ci).status, 0, 'the same sentence, or a NO_MATCH phrase, was called a diff');
    ci.assertions[0].reason = 'no band: a rule with neither warn nor fail can never decide anything';
    const r = compare(page, ci);
    assert.strictEqual(r.status, 1, r.out);
    assert.ok(r.out.includes('assertions[0] retired-outside-operator reason'), r.out);
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
