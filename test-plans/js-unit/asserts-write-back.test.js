/**
 * What a Save writes back for a client rule the file carried — the unknown keys and the unreadable
 * target it is matched to in the loaded document (loadedEntryFor), driven through the page's own
 * lifecycle (harness.loadReportPage) and read back from WIR.document.text(), the Download's text.
 *
 * Two defects of the same lookup (review:C, M7d X):
 *  - two id-less rules that derive ONE id (Java flags that as a duplicate) both resolved to the LAST
 *    entry carrying it, so the first rule's Save wrote the second rule's unknown keys over its own;
 *  - every lookup re-parsed every id-less entry, and a draft count writes every rule twice — about
 *    0.13 s per count at 300 id-less rules, on every render.
 *
 * And the band the file declared that Java could not read (C-1, the client half, Batch K). It
 * arrives absent from the payload, its sentence in declarationProblems, and ruleToJson wrote bands
 * from the model only — so a Download with nothing edited turned `warn {gt: 100}` beside an
 * unreadable fail, an INVALID_RULE and exit 1, into a rule judged on warn alone that can never
 * FAIL: the next run read WARN and exited 0, a silent pass made by Save. The server's half is
 * server-document.test.js; the page's is asserts-save.spec.ts.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage } = require('./harness');

const A0 = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** A Java payload rule ships what the evaluator uses: no unknown keys, no declared target, no band
    it could not read — the page takes those from the document at load (withDeclaredKeys,
    withDeclaredBands), so a test that let ruleFromJson's carry ride the payload proves nothing. */
function asShipped(rule) {
    delete rule.unreadable;
    delete rule.extra;
    delete rule.declaredTarget;
    return rule;
}

/** A page whose file declares `entries`, with the payload's rules parsed the way Java names them. */
function page(entries) {
    const doc = { assertions: entries };
    return loadReportPage({
        runId: 'write-back',
        annotationsDocument: doc,
        assertions: entries.map((r, i) => asShipped(A0._ruleFromJson(r, i))),
        samplers: [{ name: 'A', count: 10, p95: 5 }],
        overall: { totalSamples: 10 }
    });
}

test('two id-less rules that derive one id each keep their own entry on a Save', () => {
    const WIR = page([
        { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 }, team: 'first' },
        { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 2 }, team: 'second' }
    ]);
    const written = JSON.parse(WIR.document.text()).assertions;
    assert.deepStrictEqual(written.map((r) => r.team), ['first', 'second'],
        'matched by id alone, both rules took the last entry\'s unknown keys');
    assert.strictEqual(WIR.document.count(), 0, 'an unedited file is no change');
});

test('a written id shared by two rules is matched by occurrence too', () => {
    const WIR = page([
        { id: 'dup', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 }, note: 'one' },
        { id: ' dup ', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 2 }, note: 'two' }
    ]);
    const written = JSON.parse(WIR.document.text()).assertions;
    assert.deepStrictEqual(written.map((r) => r.note), ['one', 'two']);
});

test('the loaded entries are read once per document, not once per rule written', () => {
    // Pinned by identity, not by a clock: a wall-clock bound in a required CI check flakes on a
    // shared runner (review low). The table is derived once and handed back unchanged by every
    // lookup a Save and a count make; re-deriving it per rule was the ~0.13 s per count.
    const entries = [];
    for (let i = 0; i < 300; i++) entries.push({ target: { sampler: 'S' + i }, metric: 'p95', fail: { gt: 1 } });
    const WIR = page(entries);
    const first = WIR.asserts._loadedEntriesById();
    assert.strictEqual(Object.keys(first).length, 300, 'every id-less entry is filed under its derived id');
    WIR.document.text();
    WIR.document.count();
    assert.strictEqual(WIR.asserts._loadedEntriesById(), first,
        'a Save and a count re-derived the loaded entries instead of reading them once');
});

// ---------------------------------------------------------------------------
// C-1 — an unreadable band is written back, never dropped
// ---------------------------------------------------------------------------

const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const TARGET = { sampler: 'A' };
/* Usability.unreadableBand's ending, which AssertionRuleMapper puts on every refused band. */
const ENDING = (which) => ' — so this rule declares a limit it cannot apply, and would report PASS without'
    + ' ever testing ' + which;
const GTT_REFUSAL = "fail operator 'gtt' is not one of gt, gte, lt, lte" + ENDING('fail');
/* The editor's refusal is the same sentence, capitalised as its other refusals are. */
const GTT_PROBLEM = GTT_REFUSAL.charAt(0).toUpperCase() + GTT_REFUSAL.slice(1);

/**
 * A page as the build writes it for a file declaring `entries`: annotationsDocument is the file as
 * GatePayloadBuilder.documentOf re-serialises it (`doc`, the entries themselves unless a test says
 * otherwise), and each payload rule carries the bands JAVA read — a band it could not read is
 * absent, with the sentence in declarationProblems. Java ships no `unreadable`, so none is left on
 * the payload: a test that let ruleFromJson's carry ride into the payload would pass without the
 * page carrying anything itself. `asJava`, when given, makes a parsed rule what Java read where the
 * page's parser reads it differently (plan L2).
 */
function javaPage(entries, doc, asJava) {
    const chartData = {
        runId: 'unreadable-band',
        annotationsDocument: JSON.parse(JSON.stringify({ assertions: doc || entries })),
        samplers: [{ name: 'A', count: 10, p95: 50, mean: 3 }, { name: 'B', count: 10, p95: 50, mean: 3 }],
        overall: { totalSamples: 20 }
    };
    const parsed = entries.map((r, i) => asShipped(A0._ruleFromJson(r, i)));
    if (asJava) parsed.forEach(asJava);
    chartData.assertions = JSON.parse(JSON.stringify(A0.evaluate(parsed, chartData).rules));
    chartData.gate = JSON.parse(JSON.stringify(A0.evaluate(parsed, chartData).gate));
    chartData.assertions.forEach((r) => {
        assert.strictEqual(r.unreadable, undefined);
        assert.strictEqual(r.extra, undefined);
        assert.strictEqual(r.declaredTarget, undefined);
    });
    return loadReportPage(chartData);
}

function written(WIR) {
    return JSON.parse(WIR.document.text()).assertions;
}

/** Nothing pending anywhere: no count, no pill (hasDrafts), no NEW/EDITED marker. */
function untouched(WIR, why) {
    assert.strictEqual(WIR.document.count(), 0, why + ': counted as an unsaved change');
    assert.strictEqual(WIR.document.hasDrafts(), false, why + ': the pill would show');
    assert.deepStrictEqual(plain(WIR.asserts._state.rules.filter((r) => r.draftState).map((r) => r.id)), [],
        why + ': a rule was marked');
}

/* The unreadable fail band beside a readable warn, and a readable rule beside it. */
const LAG = [{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } },
    { id: 'ok', target: { sampler: 'B' }, metric: 'p95', fail: { gt: 1000 } }];

test('C-1: a no-op save keeps an unreadable fail band beside a readable warn band', () => {
    const WIR = javaPage([{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }]);
    const rule = WIR.asserts._state.rules[0];
    assert.strictEqual(rule.fail, undefined, 'the payload carries no band Java could not read');
    assert.strictEqual(WIR.asserts._whyUnusable(rule), GTT_REFUSAL);
    const [lag] = written(WIR);
    assert.deepStrictEqual(lag.warn, { gt: 30 });
    assert.deepStrictEqual(lag.fail, { gtt: 90 },
        'the unreadable fail band was dropped, so the rule can now never FAIL');
    untouched(WIR, 'an unedited file');
});

/* Every way AssertionRuleMapper.readBand refuses a band, as documentOf ships it (a bare 1e400 is
   the text "Infinity" by then). The __proto__ key is built by JSON.parse, as a file read builds
   it: as a literal it would set the object's prototype and be no key at all. */
const REFUSED = [
    ['text', { gt: 'soon' }],
    ['an overflowed number, as documentOf writes it', { gt: 'Infinity' }],
    ['two operators', { gt: 1, lt: 2 }],
    ['the retired outside', { outside: [1, 2] }],
    ['an unknown operator', { gtt: 900 }],
    ['an operator every object inherits', { constructor: 100 }],
    ['an operator that names the prototype', JSON.parse('{"__proto__": 5}')],
    ['an operator padded with a no-break space', { '\xa0gt': 100 }],
    ['null', { gt: null }]
];

REFUSED.forEach(([shape, band]) => {
    test('C-1: an unreadable fail band (' + shape + ') beside a readable warn is written back verbatim', () => {
        const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: band }]);
        assert.ok(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), 'Java refuses this band');
        assert.deepStrictEqual(written(WIR)[0].fail, band);
        untouched(WIR, shape);
    });
    test('C-1: an unreadable warn band (' + shape + ') beside a readable fail is written back verbatim', () => {
        const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', warn: band, fail: { gt: 1000 } }]);
        assert.deepStrictEqual(written(WIR)[0].warn, band);
        assert.deepStrictEqual(written(WIR)[0].fail, { gt: 1000 });
        untouched(WIR, shape);
    });
});

test('C-1: an id-less rule and two rules sharing an id each keep their own band', () => {
    const WIR = javaPage([
        { target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 1 } },
        { id: 'dup', target: TARGET, metric: 'mean', warn: { gt: 30 }, fail: { gt: 'one' } },
        { id: 'dup', target: { sampler: 'B' }, metric: 'mean', warn: { gt: 30 }, fail: { gt: 'two' } }
    ]);
    assert.deepStrictEqual(written(WIR).map((r) => r.fail), [{ gtt: 1 }, { gt: 'one' }, { gt: 'two' }]);
    untouched(WIR, 'an unedited file');
});

test('C-1: the rule is still refused, and its band still written, after Edit document… of its own text', () => {
    const WIR = javaPage([{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }]);
    const before = WIR.asserts._state.rules[0];
    const text = WIR.document.text();
    assert.strictEqual(WIR.document.replace(JSON.parse(text)), null);
    assert.strictEqual(WIR.asserts._state.rules[0], before, 'N1: an unchanged block keeps the very same rule');
    assert.strictEqual(WIR.document.text(), text);
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), GTT_REFUSAL);
    untouched(WIR, 'Edit document… applied unchanged');
});

test('C-1: two laps through Open file… and the draft record neither drop the band nor make it judgeable', () => {
    const WIR = javaPage([{ id: 'ok', target: TARGET, metric: 'p95', fail: { gt: 1000 } }]);
    // Open file… of a document that declares it: the parse path, ruleFromJson.
    const opened = { assertions: [{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }] };
    assert.strictEqual(WIR.document.replace(opened, 'opened.json'), null);
    const first = written(WIR);
    assert.deepStrictEqual(first[0].fail, { gtt: 90 }, 'Open file… dropped it');
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), GTT_REFUSAL);
    // The draft record: this page's own record, restored as a reload restores it.
    WIR.document.reinstate(WIR.document.capture());
    assert.deepStrictEqual(written(WIR), first, 'the draft record dropped it');
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), GTT_REFUSAL);
    // And the text it writes, opened again.
    assert.strictEqual(WIR.document.replace(JSON.parse(WIR.document.text()), 'again.json'), null);
    assert.deepStrictEqual(written(WIR), first);
});

test('C-1: opening the very file with a bare 1e400 is no change, and the band is written as Java writes it', () => {
    // The file says 1e400; JSON.parse makes it Infinity, Jackson made it Infinity and documentOf
    // shipped it as the text "Infinity". Carried as the number, JSON.stringify writes null — a change
    // nobody made, on the file the report was generated from.
    const fileText = '{"assertions": [{"id": "k", "target": {"sampler": "A"}, "metric": "p95",'
        + ' "warn": {"gt": 30}, "fail": {"gt": 1e400}}]}';
    const asShipped = [{ id: 'k', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gt: 'Infinity' } }];
    const WIR = javaPage(asShipped, asShipped);
    assert.strictEqual(WIR.document.replace(JSON.parse(fileText), 'K.json'), null);
    assert.deepStrictEqual(written(WIR)[0].fail, { gt: 'Infinity' });
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]),
        'fail gt must be a number, not the number Infinity' + ENDING('fail'));
    untouched(WIR, 'the report\'s own file, opened');
});

/* The editor: a carried band is never saved away unseen. */

test('C-1: the editor refuses to save a carried band away, and writing the band replaces it', () => {
    const WIR = javaPage([{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }]);
    const A = WIR.asserts;
    A.edit(A._keyOf('lag'));
    A.setField('description', 'touched something else');
    assert.strictEqual(A._editorProblem(), GTT_PROBLEM);
    A.saveEditor();
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 }, 'Save took the band away unseen');
    assert.strictEqual(written(WIR)[0].description, undefined, 'a refused Save wrote something');
    A.setBand('fail', 'value', '700');
    assert.strictEqual(A._editorProblem(), null);
    A.saveEditor();
    assert.deepStrictEqual(written(WIR)[0].fail, { gt: 700 });
    assert.strictEqual(A._whyUnusable(A._state.rules[0]), null, 'the written band is the rule\'s now');
    assert.strictEqual(WIR.document.count(), 1);
});

test('C-1: picking an operator for a carried band is not a band — the box is still empty, and the file\'s band stands', () => {
    const WIR = javaPage(LAG);
    const A = WIR.asserts;
    A.edit(A._keyOf('lag'));
    A.setBand('fail', 'op', 'lt');
    assert.strictEqual(A._editorProblem(), GTT_PROBLEM, 'an operator picked over an empty box was taken for "no band"');
    A.saveEditor();
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 }, 'an operator change dropped the band');
    untouched(WIR, 'a refused Save');
});

test('C-1: a carried band\'s box typed into and cleared again holds the file\'s band', () => {
    const WIR = javaPage(LAG);
    const A = WIR.asserts;
    A.edit(A._keyOf('lag'));
    A.setBand('fail', 'value', '7');
    assert.strictEqual(A._editorProblem(), null, 'a number the page can read replaces the band');
    A.setBand('fail', 'value', '');
    assert.strictEqual(A._editorProblem(), GTT_PROBLEM, 'an emptied box was taken for "no band"');
    A.saveEditor();
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 }, 'type-then-clear dropped the band');
    untouched(WIR, 'a refused Save');
});

test('C-1: a carried warn band shows as a band, and removing it is the author\'s choice', () => {
    const WIR = javaPage([{ id: 'w', target: TARGET, metric: 'p95', warn: { gtt: 50 }, fail: { gt: 1000 } }]);
    const A = WIR.asserts;
    A.edit(A._keyOf('w'));
    assert.ok(A._editorProblem().indexOf("Warn operator 'gtt'") === 0, A._editorProblem());
    A.removeBand('warn');
    assert.strictEqual(A._editorProblem(), null);
    A.saveEditor();
    assert.strictEqual(written(WIR)[0].warn, undefined);
    assert.strictEqual(WIR.document.count(), 1);
});

test('C-1: Duplicate, Disable, a dragged warn band, Delete with Undo, and Discard all keep a carried band', () => {
    // Duplicate: the copy is the rule, band and refusal included.
    let WIR = javaPage(LAG);
    let A = WIR.asserts;
    A.duplicateRule(A._keyOf('lag'));
    let w = written(WIR);
    assert.deepStrictEqual(w.map((r) => r.id), ['lag', 'ok', 'lag-copy']);
    assert.deepStrictEqual(w[2].fail, { gtt: 90 }, 'Duplicate dropped the band from the copy');
    assert.strictEqual(A._whyUnusable(A._state.rules[2]), GTT_REFUSAL);
    assert.strictEqual(WIR.document.count(), 1);

    // Disable, then enable again: back to the file.
    WIR = javaPage(LAG);
    A = WIR.asserts;
    A.toggleEnabled(A._keyOf('lag'));
    assert.deepStrictEqual(written(WIR)[0],
        { id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 }, enabled: false });
    A.toggleEnabled(A._keyOf('lag'));
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 });
    assert.strictEqual(WIR.document.count(), 0);

    // The readable warn band dragged: the carried fail band and the refusal stay.
    WIR = javaPage(LAG);
    A = WIR.asserts;
    A._retune('lag', 40, 'warn');
    assert.deepStrictEqual(written(WIR)[0].warn, { gt: 40 });
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 });
    assert.strictEqual(A._whyUnusable(A._state.rules[0]), GTT_REFUSAL);

    // Delete, then Undo: the capture holds the band.
    WIR = javaPage(LAG);
    const before = WIR.document.capture();
    WIR.asserts.deleteRule(WIR.asserts._keyOf('lag'));
    assert.deepStrictEqual(written(WIR).map((r) => r.id), ['ok']);
    WIR.document.reinstate(before);
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 });
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), GTT_REFUSAL);
    untouched(WIR, 'an undone delete');

    // An edit elsewhere, then Discard drafts: back to the file, band and all.
    WIR = javaPage(LAG);
    A = WIR.asserts;
    A.edit(A._keyOf('ok'));
    A.setBand('fail', 'value', '123');
    A.saveEditor();
    assert.strictEqual(WIR.document.count(), 1);
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 });
    A.discardDrafts();
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 });
    untouched(WIR, 'after Discard drafts');
});

test('C-1: a readable band is written from the model, so a cleared band stays cleared', () => {
    // Carried on the rule, never looked up by id at write time: a band the editor cleared must not
    // come back from the file under the same id.
    const WIR = javaPage([{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gt: 90 } }]);
    const A = WIR.asserts;
    A.edit(A._keyOf('lag'));
    A.removeBand('warn');
    A.saveEditor();
    assert.strictEqual(written(WIR)[0].warn, undefined);
    assert.deepStrictEqual(written(WIR)[0].fail, { gt: 90 });
});

test('C-1: a rule added under a deleted rule\'s id does not inherit its unreadable band', () => {
    const WIR = javaPage([{ id: 'lag', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }]);
    const A = WIR.asserts;
    A.deleteRule(A._keyOf('lag'));
    A.add();
    A.setField('id', 'lag');
    A.setBand('fail', 'value', '700');
    A.saveEditor();
    assert.deepStrictEqual(written(WIR).map((r) => [r.id, r.warn, r.fail]), [['lag', undefined, { gt: 700 }]]);
});

test('C-1: a band Java read is carried by nobody, so a box the author emptied is no band', () => {
    // withDeclaredBands carries only a band Java returned nothing for. Carrying a readable one as well
    // changes no Download — the model's band is written first — but the editor would then take an
    // emptied box for "the file's band" and write it back, while the page judged the rule without it.
    // × releases a carried band, so the cleared-with-× guard above cannot see this.
    const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gt: 90 } }]);
    const A = WIR.asserts;
    assert.strictEqual(A._state.loadedRules[0].unreadable, undefined, 'a readable rule was given a carried band');
    assert.strictEqual(A._state.rules[0].unreadable, undefined, 'a readable rule was given a carried band');
    A.edit(A._keyOf('r'));
    A.setBand('fail', 'value', '');
    assert.strictEqual(A._editorProblem(), null);
    A.saveEditor();
    assert.strictEqual(written(WIR)[0].fail, undefined, 'the emptied box wrote the file\'s band back');
    assert.strictEqual(A._state.rules[0].fail, undefined);
    assert.strictEqual(A._state.rules[0].unreadable, undefined);
});

/* Texts Double.parseDouble refuses and Number() read — "0x10" as 16, "0b11" as 3, "0o17" as 15, a
   no-break space trimmed away — each the band a file declared and the run refused (L2). */
const NUMBER_ONLY = ['0x10', '0b11', '0o17', '\xa0100'];

NUMBER_ONLY.forEach((text) => {
    test('CN25: an unedited editor Save of {"gt": ' + JSON.stringify(text) + '} keeps the row INVALID', () => {
        // The band Java refused, carried under an empty box (C-1). The editor re-read the carried band
        // through bandFromJson, whose text arm was trim() and Number(): "0x10" read as 16, so the form
        // raised no refusal, Save went ahead, and the saved rule — no fail band of its own, the carried
        // one read without a problem — was judged on warn alone. A row the run calls INVALID_RULE and
        // exits 1 over turned into a page verdict on a Save that changed nothing about the band.
        const JAVA = 'fail gt must be a number, not the text ' + JSON.stringify(text) + ENDING('fail');
        const WIR = javaPage([{ id: 'hex', target: TARGET, metric: 'p95', warn: { gt: 30 }, fail: { gt: text } }],
            undefined, (r) => { delete r.fail; r.declarationProblems = [JAVA]; });
        const A = WIR.asserts;
        assert.strictEqual(A._state.rules[0].notJudged, 'INVALID_RULE');
        assert.deepStrictEqual(written(WIR)[0].fail, { gt: text });
        untouched(WIR, 'an unedited file');

        // Opened and saved with nothing touched, then with something else touched.
        A.edit(A._keyOf('hex'));
        assert.strictEqual(A._editorProblem(), JAVA.charAt(0).toUpperCase() + JAVA.slice(1),
            'the editor read the band Java refuses, so it refused nothing');
        A.saveEditor();
        A.setField('description', 'touched something else');
        A.saveEditor();
        assert.strictEqual(A._state.rules[0].notJudged, 'INVALID_RULE', 'a Save turned the row into a verdict');
        assert.strictEqual(A._whyUnusable(A._state.rules[0]), JAVA);
        assert.deepStrictEqual(written(WIR)[0].fail, { gt: text });
        assert.strictEqual(written(WIR)[0].description, undefined, 'a refused Save wrote something');
        untouched(WIR, 'a refused Save');

        // And the same rule through Open file… on a page that did not ship it: the page's own parser
        // refuses it, in Java's words.
        const other = javaPage([{ id: 'ok', target: TARGET, metric: 'p95', fail: { gt: 1000 } }]);
        assert.strictEqual(other.document.replace({ assertions: [{ id: 'hex', target: TARGET, metric: 'p95',
            warn: { gt: 30 }, fail: { gt: text } }] }, 'opened.json'), null);
        assert.strictEqual(other.asserts._whyUnusable(other.asserts._state.rules[0]), JAVA);
        assert.strictEqual(other.asserts._state.rules[0].notJudged, 'INVALID_RULE');
        assert.deepStrictEqual(written(other)[0].fail, { gt: text });
    });
});

test('CN25: a band text Java reads and Number() refused is a band on the page too', () => {
    // "1d" and "0x1p3": Double.parseDouble reads 1 and 8, Number() refused both — so Open file… of a
    // file the run judged read the rule INVALID here.
    const WIR = javaPage([{ id: 'ok', target: TARGET, metric: 'p95', fail: { gt: 1000 } }]);
    const A = WIR.asserts;
    assert.strictEqual(WIR.document.replace({ assertions: [
        { id: 'suffix', target: TARGET, metric: 'p95', fail: { gt: '1d' } },
        { id: 'hexf', target: TARGET, metric: 'p95', fail: { gt: '0x1p3' } }] }, 'opened.json'), null);
    assert.deepStrictEqual(plain(A._state.rules.map((r) => [r.id, A._whyUnusable(r), r.fail])),
        [['suffix', null, { op: 'gt', value: 1 }], ['hexf', null, { op: 'gt', value: 8 }]]);
    assert.deepStrictEqual(A._state.rules.map((r) => r.status), ['FAIL', 'FAIL']);
});

// ---------------------------------------------------------------------------
// CN26 — a legacy loadValidity block opened into a report is refused as the run refuses it
// ---------------------------------------------------------------------------

test('CN26: an opened loadValidity block whose target or tolerance is not a finite number is refused in the run\'s words, and written back so the next run refuses it', () => {
    // jacksonDouble read the text "NaN" as no value, where Jackson binds NaN and the run refuses the
    // migrated rule: a "NaN" tolerance migrated to a judged `lt target` (1 change, EDITED), and a "NaN"
    // target migrated nothing, so the Download dropped the load check and the next run read WARN with
    // rc 0. A -Infinity target declared nothing on either side; it is refused on both now (L3).
    const OK = { id: 'ok', target: TARGET, metric: 'p95', fail: { gt: 1000 } };
    [
        [{ target: 1000, fail: { tolerance: 'NaN' } }, 'loadValidity.fail.tolerance must be a finite number, not NaN', 'NaN'],
        [{ target: 'NaN', fail: { tolerance: 0.1 } }, 'loadValidity.target must be a finite number, not NaN', 'NaN'],
        [{ target: '-Infinity', fail: { tolerance: 0.1 } }, 'loadValidity.target must be a finite number, not -Infinity', '-Infinity']
    ].forEach(([block, problem, band]) => {
        const shape = JSON.stringify(block);
        const WIR = javaPage([OK]);
        const A = WIR.asserts;
        assert.strictEqual(WIR.document.replace({ assertions: [OK], loadValidity: block }, 'opened.json'), null, shape);
        const load = A._state.rules.filter((r) => r.id === 'load:throughput')[0];
        assert.ok(load, shape + ': the block migrated to no rule, so a Download drops the load check');
        assert.strictEqual(A._whyUnusable(load), problem + ENDING('fail'), shape);
        assert.strictEqual(load.notJudged, 'INVALID_RULE', shape + ': the page judged a rule the run refuses');
        const doc = JSON.parse(WIR.document.text());
        assert.strictEqual(doc.loadValidity, undefined, shape + ': the block is consumed, as the run consumes it');
        assert.deepStrictEqual(doc.assertions.map((r) => r.id), ['ok', 'load:throughput'], shape);
        assert.deepStrictEqual(doc.assertions[1].fail, { lt: band },
            shape + ': the band is not written as the run computed it, so the next run would not refuse it');
    });
});

// ---------------------------------------------------------------------------
// CN24 — a rule's unknown keys and its declared target ride the rule, not its id
// ---------------------------------------------------------------------------

/* AssertionRuleMapper's sentence for a retired `observe`, as the page and the run both write it. */
const OBSERVE = '"observe" is no longer a rule modifier. It meant "report this and never gate", and a rule'
    + ' that keeps its bands without it does gate — so leaving it in place would silently invert what the'
    + ' rule was written to do. Delete the bands to stop the rule judging, or delete "observe" to let it judge.';

const OBSERVED = { id: 'obs', target: TARGET, metric: 'p95', fail: { gt: 1000 }, observe: true, team: 'payments' };

test('CN24: Duplicate writes the copy with the rule\'s unknown and retired keys, so the run refuses the copy too', () => {
    // ruleToJson looked a rule's unknown keys up in the file by id, and the copy's id is new: it was
    // written without `observe`, while its cloned declarationProblems kept it INVALID here — the next
    // run judged a rule this page called a configuration error.
    const WIR = javaPage([OBSERVED]);
    const A = WIR.asserts;
    assert.strictEqual(A._whyUnusable(A._state.rules[0]), OBSERVE);
    untouched(WIR, 'an unedited file');
    A.duplicateRule(A._keyOf('obs'));
    const w = written(WIR);
    assert.deepStrictEqual(w[1], { id: 'obs-copy', target: TARGET, metric: 'p95', fail: { gt: 1000 },
        observe: true, team: 'payments' }, 'the copy lost the keys its original carries');
    assert.deepStrictEqual(w[0], OBSERVED);
    assert.strictEqual(A._whyUnusable(A._state.rules[1]), OBSERVE);
    assert.strictEqual(WIR.document.count(), 1);
});

test('CN24: Duplicate writes the copy with the target its original declared and nobody could read', () => {
    [
        [{ sampler: 'A', match: 'B*' }, 'two forms'],
        [{ scope: 'everything' }, 'a scope that is not overall'],
        [null, 'a null target'],
        [undefined, 'no target at all']
    ].forEach(([target, shape]) => {
        const entry = { id: 'bad', metric: 'p95', fail: { gt: 1 } };
        if (target !== undefined) entry.target = target;
        const WIR = javaPage([entry]);
        const A = WIR.asserts;
        const refusal = A._whyUnusable(A._state.rules[0]);
        assert.ok(refusal, shape + ': the target is readable');
        untouched(WIR, shape + ', unedited');
        A.duplicateRule(A._keyOf('bad'));
        const copy = written(WIR)[1];
        assert.strictEqual(copy.id, 'bad-copy', shape);
        assert.strictEqual(Object.prototype.hasOwnProperty.call(copy, 'target'), target !== undefined,
            shape + ': the copy\'s target key');
        assert.deepStrictEqual(copy.target, target, shape + ': the copy was written with another target');
        assert.strictEqual(A._whyUnusable(A._state.rules[1]), refusal, shape);
    });
});

test('CN24: an editor Save under a new id keeps the rule\'s unknown keys', () => {
    const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', fail: { gt: 1000 }, team: 'payments', ticket: 7 }]);
    const A = WIR.asserts;
    A.edit(A._keyOf('r'));
    A.setField('id', 'renamed');
    A.saveEditor();
    assert.deepStrictEqual(written(WIR), [{ id: 'renamed', target: TARGET, metric: 'p95', fail: { gt: 1000 },
        team: 'payments', ticket: 7 }], 'a rename lost the keys the form has no field for');
});

test('CN24: a retired key keeps the editor refusing, and Remove is the way out', () => {
    // The form has no field for `observe`, so it rode through every Save while the editor's rule —
    // built without it — was judged here: a verdict on the page, a CONFIG ERROR on the next run.
    const WIR = javaPage([OBSERVED]);
    const A = WIR.asserts;
    A.edit(A._keyOf('obs'));
    assert.strictEqual(A._editorProblem(), OBSERVE);
    A.setField('description', 'touched something else');
    A.saveEditor();
    assert.deepStrictEqual(written(WIR), [OBSERVED], 'a Save the form refused wrote something');
    untouched(WIR, 'a refused Save');
    assert.strictEqual(A._editorRetiredKeys().join(), 'observe');
    A.dropKey('observe');
    assert.strictEqual(A._editorProblem(), null);
    assert.deepStrictEqual(plain(A._editorRetiredKeys()), []);
    A.saveEditor();
    assert.deepStrictEqual(written(WIR), [{ id: 'obs', description: 'touched something else', target: TARGET,
        metric: 'p95', fail: { gt: 1000 }, team: 'payments' }]);
    assert.strictEqual(A._whyUnusable(A._state.rules[0]), null);
    assert.strictEqual(A._state.rules[0].status, 'PASS');
    assert.strictEqual(WIR.document.count(), 1);
});

test('CN24: a rule added under a deleted rule\'s id does not inherit its unknown keys', () => {
    const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', fail: { gt: 1000 }, team: 'payments', observe: true }]);
    const A = WIR.asserts;
    A.deleteRule(A._keyOf('r'));
    A.add();
    A.setField('id', 'r');
    A.setBand('fail', 'value', '700');
    assert.strictEqual(A._editorProblem(), null);
    A.saveEditor();
    assert.deepStrictEqual(written(WIR), [{ id: 'r', target: TARGET, metric: 'p95', fail: { gt: 700 } }],
        'the new rule took the deleted one\'s keys from the file');
    assert.strictEqual(A._whyUnusable(A._state.rules[0]), null);
});

test('CN24: an unknown key changed by Open file… is a change, and its rule EDITED', () => {
    // Both sides of the comparison looked the keys up in the document now adopted, so a file whose
    // only difference was a rule's unknown key read as no change while its Download wrote it.
    const WIR = javaPage([{ id: 'r', target: TARGET, metric: 'p95', fail: { gt: 1000 }, team: 'payments' }]);
    assert.strictEqual(WIR.document.replace({ assertions: [{ id: 'r', target: TARGET, metric: 'p95',
        fail: { gt: 1000 }, team: 'checkout' }] }, 'opened.json'), null);
    assert.strictEqual(written(WIR)[0].team, 'checkout');
    assert.strictEqual(WIR.document.count(), 1);
    assert.deepStrictEqual(plain(WIR.asserts._state.rules.map((r) => r.draftState)), ['edited']);
    WIR.asserts.discardDrafts();
    assert.strictEqual(written(WIR)[0].team, 'payments');
    untouched(WIR, 'after Discard drafts');
});

test('CN24: a key whose name every object inherits, or that names the prototype, is carried as any other', () => {
    const entry = JSON.parse('{"id": "r", "target": {"sampler": "A"}, "metric": "p95", "fail": {"gt": 1000},'
        + ' "constructor": 1, "__proto__": {"x": 2}, "toString": "t"}');
    const WIR = javaPage([entry]);
    const w = written(WIR)[0];
    assert.deepStrictEqual(Object.keys(w), ['id', 'target', 'metric', 'fail', 'constructor', '__proto__', 'toString']);
    assert.strictEqual(w.constructor, 1);
    untouched(WIR, 'an unedited file');
    WIR.asserts.duplicateRule(WIR.asserts._keyOf('r'));
    assert.deepStrictEqual(Object.keys(written(WIR)[1]),
        ['id', 'target', 'metric', 'fail', 'constructor', '__proto__', 'toString']);
});

test('CN24: a draft record written before rules carried their keys gets them back on restore', () => {
    // A record from a build that looked the keys up by id restores every rule without them; restored
    // as they are, the Download would drop every rule's unknown keys and its unreadable target.
    const LOADED = [{ id: 'r', target: TARGET, metric: 'p95', fail: { gt: 1000 }, team: 'payments' },
        { id: 'bad', target: { sampler: 'A', match: 'B*' }, metric: 'p95', fail: { gt: 1 } }];
    let WIR = javaPage(LOADED);
    WIR.asserts.edit(WIR.asserts._keyOf('r'));
    WIR.asserts.setBand('fail', 'value', '900');
    WIR.asserts.saveEditor();
    const captured = JSON.parse(WIR.document.capture());
    const slice = captured.record.slices.asserts;
    assert.ok(slice.rules.some((r) => r.extra) && slice.rules.some((r) => r.declaredTarget),
        'the capture carries nothing to take out');
    delete slice.keysCarried;
    slice.rules.forEach((r) => { delete r.extra; delete r.declaredTarget; });

    WIR = javaPage(LOADED);
    WIR.document.reinstate(JSON.stringify(captured));
    assert.deepStrictEqual(written(WIR), [Object.assign({}, LOADED[0], { fail: { gt: 900 } }), LOADED[1]]);
    assert.strictEqual(WIR.document.count(), 1);
});

test('C-1: a rule whose only bands are unreadable is refused for them, not for having none', () => {
    // G3's retired-outside-operator has its fail band and nothing else. Asked first whether the rule
    // has a band, the editor told its author to add one to a rule that has one, and the preview said
    // "No band yet" (emptyPreviewReason: asserts-save.spec.ts reads that line).
    [
        [{ fail: { outside: [1, 2] } }, 'Fail outside is no longer an operator'],
        [{ warn: { gt: 'x' }, fail: { outside: [1, 2] } }, 'Warn gt must be a number, not the text "x"']
    ].forEach(([bands, refusal]) => {
        const entry = Object.assign({ id: 'only', target: TARGET, metric: 'p95' }, bands);
        const WIR = javaPage([entry]);
        const A = WIR.asserts;
        A.edit(A._keyOf('only'));
        A.setField('description', 'touched something else');
        const problem = A._editorProblem() || '';
        assert.ok(problem.indexOf(refusal) === 0, JSON.stringify(bands) + ': ' + problem);
        A.saveEditor();
        assert.deepStrictEqual(written(WIR), [entry], JSON.stringify(bands) + ': a refused Save wrote');
        untouched(WIR, JSON.stringify(bands) + ', a refused Save');
    });
});

test('C-1: a draft record written before rules carried their band gets it back on restore', () => {
    // A 3.1.46 record restores every rule without `unreadable`. The row still read INVALID —
    // declarationProblems ride the record — but every carried rule counted as a change and the
    // Download dropped its band, so a reload brought the silent pass back. Here as a reload takes it:
    // a fresh page, the record restored through the path WIR.document.start() takes.
    const record = (WIR, edit) => {
        const captured = JSON.parse(WIR.document.capture());
        const rules = captured.record.slices.asserts.rules;
        assert.ok(rules.some((r) => r.unreadable), 'the capture carries no band to take out');
        rules.forEach((r) => { delete r.unreadable; if (edit) edit(r); });
        return JSON.stringify(captured);
    };
    let WIR = javaPage(LAG);
    WIR.asserts.edit(WIR.asserts._keyOf('ok'));
    WIR.asserts.setBand('fail', 'value', '123');
    WIR.asserts.saveEditor();
    const old = record(WIR);

    WIR = javaPage(LAG);
    WIR.document.reinstate(old);
    assert.deepStrictEqual(written(WIR)[0].fail, { gtt: 90 }, 'the restored rule\'s Download dropped the band');
    assert.deepStrictEqual(written(WIR)[1].fail, { gt: 123 }, 'the edit itself was not restored');
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), GTT_REFUSAL);
    assert.strictEqual(WIR.document.count(), 1, 'one edit, counted as more');
    assert.deepStrictEqual(plain(WIR.asserts._state.rules.filter((r) => r.draftState).map((r) => r.id)), ['ok']);

    // A rule the old editor saved holds only what the editor wrote: no refusal, and the band gone.
    // That was an edit, shown EDITED and judged on the page, and it is not second-guessed.
    WIR = javaPage(LAG);
    const saved = record(WIR, (r) => {
        if (r.id !== 'lag') return;
        delete r.declarationProblems;
        r.description = 'saved by 3.1.46';
        r.draftState = 'edited';
    });
    WIR = javaPage(LAG);
    WIR.document.reinstate(saved);
    assert.strictEqual(written(WIR)[0].fail, undefined, 'a band came back over the author\'s edit');
    assert.strictEqual(WIR.asserts._whyUnusable(WIR.asserts._state.rules[0]), null);
    assert.strictEqual(WIR.document.count(), 1);
});

// ---------------------------------------------------------------------------
// CN21 — a band that is not an object refuses the document, as Java refuses the file
// ---------------------------------------------------------------------------

/*
 * Jackson binds a rule's warn and fail to a Map, so `"fail": "soon"`, [], 5, "" or true refuses the
 * whole annotations file (AnnotationLoader: "Cannot deserialize value of type LinkedHashMap"), and
 * the run judges nothing. Open file… and Edit document… read such a band as no band and judged the
 * rule on its other one — PASS or WARN on the page for a file the next run cannot load — and a
 * Download then wrote the rule without it. null and {} are no band on both sides.
 */
const CN21_RULES = [{ id: 'r', target: { sampler: 'A' }, metric: 'p95', warn: { gt: 1 }, fail: { gt: 100 } }];

for (const [which, bad] of [['fail', 'soon'], ['fail', []], ['fail', 5], ['warn', ''], ['warn', true]]) {
    test('CN21: a ' + which + ' band of ' + JSON.stringify(bad) + ' refuses the opened document and leaves the page as it was', () => {
        const WIR = page(CN21_RULES);
        const before = WIR.document.text();
        const opened = JSON.parse(JSON.stringify({ assertions: CN21_RULES }));
        opened.assertions[0][which] = bad;
        const problem = WIR.document.replace(opened, 'opened.json');
        assert.strictEqual(problem, '"assertions[0].' + which + '" must be an object such as {"gt": 80}.',
            'the document was taken: ' + problem);
        assert.strictEqual(WIR.document.text(), before, 'a refused document changed the Download');
        assert.strictEqual(WIR.document.count(), 0);
        assert.deepStrictEqual(plain(WIR.asserts._state.rules[0].fail), { op: 'gt', value: 100 });
    });
}

test('CN21: null and {} are no band on both sides, and are taken', () => {
    for (const none of [null, {}]) {
        const WIR = page(CN21_RULES);
        const opened = JSON.parse(JSON.stringify({ assertions: CN21_RULES }));
        opened.assertions[0].fail = none;
        assert.strictEqual(WIR.document.replace(opened, 'opened.json'), null, JSON.stringify(none));
        assert.strictEqual(WIR.asserts._state.rules[0].fail, undefined, JSON.stringify(none) + ' is no band');
    }
});

/*
 * REV-2: the legacy loadValidity block is bound to a bean, and so are its warn and fail, so a block
 * or band that is not an object refuses the whole file (AnnotationLoaderTest). The page read such a
 * block, or such a band, as absent — migrateLoadValidity skips what it cannot read — and took the
 * document: no load rule, or one on its other band, for a file the next run cannot load. null is no
 * block and no band on both sides.
 */
const LEGACY = { target: 10, warn: { tolerance: 0.2 }, fail: { tolerance: 0.5 } };

for (const [bad, problem] of [
    ['soon', '"loadValidity" must be an object such as {"target": 50, "fail": {"tolerance": 0.2}}.'],
    ['', '"loadValidity" must be an object such as {"target": 50, "fail": {"tolerance": 0.2}}.'],
    [5, '"loadValidity" must be an object such as {"target": 50, "fail": {"tolerance": 0.2}}.'],
    [true, '"loadValidity" must be an object such as {"target": 50, "fail": {"tolerance": 0.2}}.'],
    [[], '"loadValidity" must be an object such as {"target": 50, "fail": {"tolerance": 0.2}}.'],
    [Object.assign({}, LEGACY, { fail: 'soon' }), '"loadValidity.fail" must be an object such as {"tolerance": 0.2}.'],
    [Object.assign({}, LEGACY, { fail: [] }), '"loadValidity.fail" must be an object such as {"tolerance": 0.2}.'],
    [Object.assign({}, LEGACY, { warn: 5 }), '"loadValidity.warn" must be an object such as {"tolerance": 0.2}.'],
    [Object.assign({}, LEGACY, { warn: '' }), '"loadValidity.warn" must be an object such as {"tolerance": 0.2}.'],
    [Object.assign({}, LEGACY, { warn: true }), '"loadValidity.warn" must be an object such as {"tolerance": 0.2}.']
]) {
    test('REV-2: a loadValidity of ' + JSON.stringify(bad) + ' refuses the opened document and leaves the page as it was', () => {
        const WIR = page(CN21_RULES);
        const before = WIR.document.text();
        const opened = JSON.parse(JSON.stringify({ assertions: CN21_RULES, loadValidity: bad }));
        assert.strictEqual(WIR.document.replace(opened, 'opened.json'), problem);
        assert.strictEqual(WIR.document.text(), before, 'a refused document changed the Download');
        assert.strictEqual(WIR.asserts._state.rules.length, 1, 'a load rule was migrated from a file the run cannot load');
    });
}

test('REV-2: a null block, and a null band beside a readable one, are taken, as Java takes them', () => {
    for (const [block, rules] of [[null, 1], [{}, 1], [Object.assign({}, LEGACY, { warn: null }), 2],
        [Object.assign({}, LEGACY, { fail: null }), 2]]) {
        const WIR = page(CN21_RULES);
        const opened = JSON.parse(JSON.stringify({ assertions: CN21_RULES, loadValidity: block }));
        assert.strictEqual(WIR.document.replace(opened, 'opened.json'), null, JSON.stringify(block));
        assert.strictEqual(WIR.asserts._state.rules.length, rules, JSON.stringify(block));
    }
});

// ---------------------------------------------------------------------------
// N2-nested-proto, the client half — a rule's own "__proto__" key survives a Save
// ---------------------------------------------------------------------------

/*
 * Java keeps a rule's unknown keys (AssertionConfig's @JsonAnySetter/@JsonAnyGetter), "__proto__"
 * among them, documentOf ships them, and JSON.parse makes "__proto__" an OWN key of the entry.
 * ruleToJson carried every unknown key through with `out[k] = entry[k]` — which, for "__proto__",
 * sets the prototype of the rule it writes instead of adding a key — so the Download dropped it,
 * unedited and edited alike, and counted no change. The server slice's copies went through putOwn
 * in the same batch; this was the one left. `x-custom` is the control: always kept.
 */
for (const key of ['x-custom', '__proto__']) {
    test('N2-nested-proto: ' + key + ' on a client rule is in the Download, unedited and after an edit', () => {
        const entry = JSON.parse('{"id":"r","target":{"sampler":"A"},"metric":"p95","fail":{"gt":100},'
            + JSON.stringify(key) + ':{"keep":"me"},"team":"x"}');
        assert.ok(Object.prototype.hasOwnProperty.call(entry, key), 'precondition: an own key of the entry');
        const WIR = page([entry]);
        const holds = (what) => {
            const written = JSON.parse(WIR.document.text()).assertions[0];
            assert.ok(Object.prototype.hasOwnProperty.call(written, key), what + ': the Download dropped '
                + JSON.stringify(key) + ' — it wrote ' + JSON.stringify(Object.keys(written)));
            assert.deepStrictEqual(plain(written[key]), { keep: 'me' }, what + ': the value moved');
            assert.strictEqual(written.team, 'x', what + ': the other unknown key');
            return written;
        };
        holds('unedited');
        assert.strictEqual(WIR.document.count(), 0, 'an unedited file is no change');
        WIR.asserts.toggleEnabled(WIR.asserts._keyOf('r'));
        assert.strictEqual(holds('edited').enabled, false, 'precondition: the edit landed');
        assert.strictEqual(WIR.document.count(), 1, 'one change: the toggle');
    });
}

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
