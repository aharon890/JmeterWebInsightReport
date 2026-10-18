/**
 * WIR.document.replace() and the slices' appliers (N1, N5), driven through the page's own lifecycle
 * (harness.loadReportPage) with no browser.
 *
 * N1. Every Server-tab write (Edit description / match, + Add component and its Undo, Delete
 * component and its Undo) goes through WIR.document.replace(). The client slice's applier set
 * state.rules to the document's rules re-parsed and judged by nobody, so every client row read NOT
 * EVALUATED, the sampler badges and the Summary strip's "Worst breaches" went, and the toolbar kept
 * the last gate. Only the Asserts tab's own callers (Edit document…, Open file…) recomputed after
 * it. The applier owns its slice's derived state now: it re-judges when the client block changed,
 * and leaves the slice exactly as it was when the block did not.
 *
 * N5. A changed client block came back with every NEW/EDITED marker gone (ruleFromJson strips
 * draftState), so "Drafts only" disagreed with the unsaved pill. The applier marks each rule
 * against the rules the page shipped with, the comparison draftCount makes — rules sharing an id
 * paired by occurrence, as loadedEntryFor pairs them.
 *
 * What holds the GUARD itself (re-judge only a changed block) is the identity test: an applier that
 * re-judged on every write also passes the DISABLED + EDITED test, because N5 re-marks the rule from
 * the file, and differs from the guard only in building new rule objects and a draft gate where
 * there was none.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage, loadCatalogue } = require('./harness');

const A0 = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const RULES = [
    { id: 'a-fails', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 } },
    { id: 'a-passes', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 100 } },
    { id: 'a-warns', target: { sampler: 'A' }, metric: 'p95', warn: { gt: 2 }, fail: { gt: 100 } }
];
const BLOCK = {
    components: { db: { type: 'custom', description: 'Orders DB' }, mq: { type: 'custom' } },
    asserts: [
        { id: 'up', component: 'db', query: 'up', reduce: { kind: 'max' }, fail: { gt: 1 } },
        { id: 'lag', component: 'mq', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 5 } }
    ]
};

/** A page as the build writes it: the client rules arrive judged, as Java judged them. */
function page(rules = RULES, block = BLOCK, extra = {}) {
    const chartData = {
        runId: 'document-replace',
        annotationsDocument: JSON.parse(JSON.stringify(Object.assign({ assertions: rules, serverMetrics: block }, extra))),
        samplers: [{ name: 'A', count: 10, p95: 5, mean: 3 }],
        overall: { totalSamples: 10 },
        serverCatalogue: loadCatalogue()
    };
    const judged = A0.evaluate(rules.map((r, i) => A0._ruleFromJson(r, i)), chartData);
    chartData.assertions = JSON.parse(JSON.stringify(judged.rules));
    chartData.gate = JSON.parse(JSON.stringify(judged.gate));
    return loadReportPage(chartData);
}

/** What the table draws for each rule (status, reason, marker), copied out of the vm realm. */
function rows(WIR) {
    return JSON.parse(JSON.stringify(WIR.asserts._state.rules.map((r) => [r.id,
        r.status === undefined ? null : r.status, r.notJudged || null, r.draftState || null])));
}

function draftGate(WIR) {
    return JSON.stringify(WIR.asserts._state.draftGate);
}

/** The rules "Drafts only" shows: the ones carrying a marker. */
function marked(WIR) {
    return JSON.parse(JSON.stringify(WIR.asserts._state.rules
        .filter((r) => r.draftState).map((r) => [r.id, r.draftState])));
}

/**
 * JSON with every object's keys sorted. A snapshot is compared on what it says: the order an
 * evaluator happens to write a rule's keys in is not state, and a test that fails on it pins the
 * evaluator's layout rather than the applier.
 */
function canonical(value) {
    return JSON.stringify(value, (k, v) => (v && typeof v === 'object' && !Array.isArray(v)
        ? Object.keys(v).sort().reduce((out, key) => { out[key] = v[key]; return out; }, {})
        : v));
}

/** Where a document holds null: what a non-finite number becomes when JSON.stringify writes it. */
function nullPaths(value, at = '', out = []) {
    if (value === null) out.push(at);
    else if (value && typeof value === 'object') Object.keys(value).forEach((k) => nullPaths(value[k], at + '.' + k, out));
    return out;
}

const SHIPPED = [
    ['a-fails', 'FAIL', null, null],
    ['a-passes', 'PASS', null, null],
    ['a-warns', 'WARN', null, null]
];

test('the page starts judged (precondition)', () => {
    assert.deepStrictEqual(rows(page()), SHIPPED);
});

test('for every registered slice, replace() of the document\'s own text leaves its snapshot saying exactly what it said', () => {
    const WIR = page();
    WIR.asserts.toggleEnabled(WIR.asserts._keyOf('a-passes'));
    WIR.serverKpis.toggleEnabled(WIR.serverKpis._keyOf('lag'));
    const snapshots = () => WIR.document._slices.map((s) => s.name + '=' + canonical(s.snapshot ? s.snapshot() : null));
    const before = snapshots();
    const gate = draftGate(WIR);
    assert.ok(WIR.document._slices.length >= 2, 'both authors registered');
    assert.strictEqual(WIR.document.replace(JSON.parse(WIR.document.text()), WIR.document.source()), null);
    assert.deepStrictEqual(snapshots(), before,
        'a slice dropped derived state on a replace() that changed nothing it owns');
    assert.strictEqual(draftGate(WIR), gate, 'the draft gate moved on a replace() that changed no rule');
});

test('replace() of the document as the file on disk writes it — a bare 1e400 where documentOf ships "Infinity" — leaves every slice\'s snapshot as it was (S1)', () => {
    // documentOf writes an overflowed band as the text "Infinity"; the file the team keeps writes
    // the 1e400 Jackson overflowed, which JSON.parse hands over as Infinity. The server slice kept
    // that number, JSON.stringify wrote it as null, and the report's own file read as a change.
    const block = JSON.parse(JSON.stringify(BLOCK));
    block.asserts.push({ id: 'huge', component: 'db', query: 'q', reduce: { kind: 'max' },
        warn: { gt: 1 }, fail: { gt: 'Infinity' } });
    const WIR = page(RULES, block);
    WIR.serverKpis.toggleEnabled(WIR.serverKpis._keyOf('lag'));
    const snapshots = () => WIR.document._slices.map((s) => s.name + '=' + canonical(s.snapshot ? s.snapshot() : null));
    const before = snapshots();
    const count = WIR.document.count();
    const text = WIR.document.text();
    const onDisk = JSON.parse(text.replace('"Infinity"', '1e400'));
    assert.strictEqual(onDisk.serverMetrics.asserts[2].fail.gt, Infinity, 'precondition: the bare number');
    assert.strictEqual(WIR.document.replace(onDisk, WIR.document.source()), null);
    assert.deepStrictEqual(snapshots(), before, 'the server slice holds something else for the same file');
    assert.strictEqual(WIR.document.count(), count);
    assert.strictEqual(WIR.document.text(), text);
});

test('S1, the whole document: a bare 1e400 outside any assert — a component\'s description, an environment\'s var, the coverage floor, a key nobody models in the block, in a client rule and in the document — is read as documentOf ships it', () => {
    // The test above holds an assert's entry. Everywhere else the document was adopted as JSON.parse
    // handed it over, so the same Infinity was downloaded as null in each of these, and the report's
    // own file read as a change (Batch M's review, over real -g runs: Java ships each as "Infinity").
    const block = JSON.parse(JSON.stringify(BLOCK));
    block.components.db.description = 'Infinity';
    block.environments = { prod: { vars: { ceiling: 'Infinity' } } };
    block.collection = { coverageFloor: 'Infinity' };
    block._ceiling = 'Infinity';
    const rules = JSON.parse(JSON.stringify(RULES));
    rules[0]._limit = 'Infinity';
    const WIR = page(rules, block, { _ceiling: 'Infinity' });
    const count = WIR.document.count();
    const text = WIR.document.text();
    // The block the Server tab judges with (its coverage floor, the frame a baked verdict is checked
    // against), not only the one it writes.
    const judgedWith = canonical(WIR.serverKpis._state.block);
    assert.strictEqual(text.split('"Infinity"').length - 1, 6, 'precondition: the page writes each one as documentOf ships it');
    const onDisk = JSON.parse(text.split('"Infinity"').join('1e400'));
    assert.strictEqual(onDisk._ceiling, Infinity, 'precondition: the bare number');
    assert.strictEqual(WIR.document.replace(onDisk, 'own.json'), null);
    const shipped = nullPaths(JSON.parse(text));
    const nulled = nullPaths(JSON.parse(WIR.document.text())).filter((p) => !shipped.includes(p));
    assert.strictEqual(nulled.length, 0, 'written as null: ' + nulled.join(', '));
    assert.strictEqual(WIR.document.text(), text);
    assert.strictEqual(WIR.document.count(), count, 'the report\'s own file read as a change');
    assert.strictEqual(WIR.document.countOf('server'), 0, 'the Server tab counted the block around its asserts');
    assert.strictEqual(canonical(WIR.serverKpis._state.block), judgedWith,
        'the Server tab judges the opened file with another block than the shipped one');
});

test('a write that leaves the client block as it was leaves the client slice untouched: the same rule objects, the same draft gate', () => {
    // The guard N1 chose over re-judging on every write. Re-judged, a server-only write built new
    // rule objects, and a page nobody had edited gained a draft gate it never had.
    for (const edited of [false, true]) {
        const WIR = page();
        if (edited) WIR.asserts.toggleEnabled(WIR.asserts._keyOf('a-passes'));
        const rules = WIR.asserts._state.rules.slice();
        const gate = WIR.asserts._state.draftGate;
        if (!edited) assert.strictEqual(gate, null, 'precondition: a page nobody edited has no draft gate');
        const untouched = (what) => {
            const now = WIR.asserts._state.rules;
            assert.ok(now.length === rules.length && now.every((r, i) => r === rules[i]),
                what + (edited ? ' (edited page)' : ' (pristine page)') + ': the client rules were rebuilt');
            assert.strictEqual(WIR.asserts._state.draftGate, gate,
                what + (edited ? ' (edited page)' : ' (pristine page)') + ': the draft gate was replaced');
        };
        WIR.serverKpis.editComponent('db');
        WIR.serverKpis.dialogSet('description', 'Orders DB (renamed)');
        WIR.serverKpis.dialogSaveComponent();
        untouched('Edit description');
        WIR.serverKpis.deleteComponent('mq');
        untouched('Delete component');
        assert.strictEqual(WIR.document.replace(JSON.parse(WIR.document.text()), WIR.document.source()), null);
        untouched('replace() of the document\'s own text');
    }
});

test('a disabled, edited rule keeps DISABLED and EDITED across every Server-tab write', () => {
    const WIR = page();
    WIR.asserts.toggleEnabled(WIR.asserts._keyOf('a-fails'));
    const edited = [
        ['a-fails', 'NOT_JUDGED', 'DISABLED', 'edited'],
        ['a-passes', 'PASS', null, null],
        ['a-warns', 'WARN', null, null]
    ];
    assert.deepStrictEqual(rows(WIR), edited, 'precondition: disabled, EDITED');
    const gate = draftGate(WIR);

    WIR.serverKpis.editComponent('db');
    WIR.serverKpis.dialogSet('description', 'Orders DB (renamed)');
    WIR.serverKpis.dialogSaveComponent();
    assert.strictEqual(JSON.parse(WIR.document.text()).serverMetrics.components.db.description, 'Orders DB (renamed)');
    assert.deepStrictEqual(rows(WIR), edited, 'Edit description: the client rows were re-parsed and judged by nobody');
    assert.strictEqual(draftGate(WIR), gate);

    WIR.serverKpis.deleteComponent('mq');
    assert.deepStrictEqual(Object.keys(JSON.parse(WIR.document.text()).serverMetrics.components), ['db']);
    assert.deepStrictEqual(rows(WIR), edited, 'Delete component');

    WIR.serverKpis.addComponent('custom');
    WIR.serverKpis.dialogSet('id', 'fresh');
    WIR.serverKpis.dialogAddComponent();
    assert.ok(Object.keys(JSON.parse(WIR.document.text()).serverMetrics.components).indexOf('fresh') >= 0);
    assert.deepStrictEqual(rows(WIR), edited, '+ Add component');
    assert.strictEqual(draftGate(WIR), gate);
});

test('a changed client block comes back judged through replace() alone, with no caller recompute', () => {
    const WIR = page();
    const doc = JSON.parse(WIR.document.text());
    doc.assertions[1].fail = { gt: 2 };                 // a-passes now fails too
    assert.strictEqual(WIR.document.replace(doc, null), null);
    assert.deepStrictEqual(rows(WIR).map((r) => r.slice(0, 2)), [['a-fails', 'FAIL'], ['a-passes', 'FAIL'], ['a-warns', 'WARN']],
        'a replace() caller that does not recompute was handed rules nobody judged');
    assert.strictEqual(WIR.asserts._state.draftGate && WIR.asserts._state.draftGate.status, 'FAIL');
});

test('control: a retired key written through replace() still makes the rule INVALID_RULE', () => {
    // The comparison that decides "changed" is taken BEFORE the document is adopted. ruleToJson
    // passes unknown keys through from the adopted document, so compared after it the rule would
    // carry `observe` on both sides, compare equal, and keep a verdict Java refuses.
    const WIR = page();
    const doc = JSON.parse(WIR.document.text());
    doc.assertions[1].observe = true;
    assert.strictEqual(WIR.document.replace(doc, null), null);
    assert.deepStrictEqual(rows(WIR)[1].slice(0, 3), ['a-passes', 'NOT_JUDGED', 'INVALID_RULE']);
});

test('N5: Edit document… that retunes one rule marks exactly that rule EDITED, and "Drafts only" agrees with the pill', () => {
    const WIR = page();
    WIR.asserts.openDocument();
    const doc = JSON.parse(WIR.document.text());
    doc.assertions[2].fail = { gt: 50 };
    WIR.asserts.setDocumentText(JSON.stringify(doc, null, 2));
    WIR.asserts.applyDocument();
    assert.deepStrictEqual(marked(WIR), [['a-warns', 'edited']]);
    assert.strictEqual(WIR.document.count(), 1);
    assert.strictEqual(marked(WIR).length, WIR.document.count(), '"Drafts only" and the pill disagree');
});

test('N5: a changed client block keeps the markers of the edits it still carries, and marks an added rule NEW', () => {
    const WIR = page();
    WIR.asserts.toggleEnabled(WIR.asserts._keyOf('a-fails'));
    const doc = JSON.parse(WIR.document.text());
    doc.assertions[2].fail = { gt: 50 };
    doc.assertions.push({ id: 'b-new', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 } });
    assert.strictEqual(WIR.document.replace(doc, null), null);
    assert.deepStrictEqual(marked(WIR), [['a-fails', 'edited'], ['a-warns', 'edited'], ['b-new', 'new']]);
    assert.strictEqual(marked(WIR).length, WIR.document.count());
    // ...and a document that puts an edited rule back as the file had it leaves it unmarked.
    const back = JSON.parse(WIR.document.text());
    back.assertions[0] = JSON.parse(JSON.stringify(RULES[0]));
    assert.strictEqual(WIR.document.replace(back, null), null);
    assert.deepStrictEqual(marked(WIR), [['a-warns', 'edited'], ['b-new', 'new']]);
    assert.strictEqual(rows(WIR)[0][1], 'FAIL', 'the rule enabled again is judged again');
});

test('N5: rules sharing an id are marked by occurrence — retuning another rule marks neither, retuning one marks only it', () => {
    // Two id-less rules on one sampler and metric both derive `a-p95`; Java and the page judge each
    // on its own. Keyed by id alone, each was compared against the LAST loaded `a-p95`, so the first
    // read EDITED whenever any rule in the block changed.
    const DUPS = [
        { target: { sampler: 'A' }, metric: 'p95', warn: { gt: 2 } },
        { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 100 } },
        { id: 'other', target: { sampler: 'A' }, metric: 'mean', fail: { gt: 100 } }
    ];
    const byIndex = (WIR) => JSON.parse(JSON.stringify(WIR.asserts._state.rules
        .map((r, i) => [i, r.id, r.draftState || null]).filter((m) => m[2])));
    for (const [at, band] of [[2, { gt: 50 }], [1, { gt: 60 }], [0, { gt: 3 }]]) {
        const WIR = page(DUPS);
        assert.deepStrictEqual(WIR.asserts._state.rules.map((r) => r.id), ['a-p95', 'a-p95', 'other'], 'precondition: one derived id, twice');
        WIR.asserts.openDocument();
        const doc = JSON.parse(WIR.document.text());
        doc.assertions[at][at === 0 ? 'warn' : 'fail'] = band;
        WIR.asserts.setDocumentText(JSON.stringify(doc, null, 2));
        WIR.asserts.applyDocument();
        assert.deepStrictEqual(byIndex(WIR), [[at, at === 2 ? 'other' : 'a-p95', 'edited']],
            'retuning rule ' + at + ' marked another rule sharing an id');
    }
});

test('CN21: a band that is not an object refuses the whole document, and no slice moves', () => {
    // Java refuses the file outright ("Cannot deserialize value of type LinkedHashMap"), so the
    // page refuses it too — every slice, not only the client's, stays as it was. It used to read
    // the band as absent and judge the rule on the other one.
    const WIR = page();
    const snapshots = () => WIR.document._slices.map((s) => s.name + '=' + canonical(s.snapshot ? s.snapshot() : null));
    const before = snapshots();
    const text = WIR.document.text();
    const doc = JSON.parse(text);
    doc.assertions[2].fail = 'soon';
    doc.serverMetrics.asserts[0].fail = { gt: 7 };   // a good edit beside it is refused with it
    assert.strictEqual(WIR.document.replace(doc, 'opened.json'),
        '"assertions[2].fail" must be an object such as {"gt": 80}.');
    assert.deepStrictEqual(snapshots(), before, 'a refused document moved a slice');
    assert.strictEqual(WIR.document.text(), text);
    assert.deepStrictEqual(rows(WIR), SHIPPED);
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
        console.log('       ' + (e && e.message ? e.message.split('\n').slice(0, 4).join(' | ') : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
