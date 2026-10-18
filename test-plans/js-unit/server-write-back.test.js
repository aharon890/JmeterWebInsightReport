/**
 * What the Server tab writes back for a band the file declared and the run could not read, driven
 * through the page's own lifecycle (harness.loadReportPage) and read back from WIR.document.text(),
 * the Download's text. The server half of asserts-write-back.test.js's C-1.
 *
 * B4 (CN18). A refused band is carried on the parsed assert (C-1, `unreadable`) and written back
 * verbatim, and the editor's empty box over it means the file's band. But setField released the
 * carry on ANY touch of that band's controls: pick another operator over the empty box, or type a
 * number and clear it, and Save was offered — the Download then dropped the band, and the next run
 * went from INVALID_RULE and exit 1 to WARN or PASS and exit 0. Every refused shape, warn bands too.
 * The client's rule since Batch K is the server's now: an operator picked or a number typed and
 * cleared leaves the file's band, a number the page reads replaces it, and only × (removeBand)
 * releases it. The editor's DOM — the ×, the band shown, the "If saved" line — is server-drafts.spec.ts.
 *
 * S1 (CN19). An opened file's bare 1e400 is Infinity by the time JSON.parse hands it over, and was
 * carried as that number, which JSON.stringify writes as null: the Download wrote {"gt": null}, and
 * opening the very file the report was generated from read as a change against documentOf's
 * {"gt": "Infinity"}. A document is now read as GatePayloadBuilder.documentOf ships it
 * (kpisFromDocument, through WIR.javaText.jacksonCopy, the client's declaredBand's own reading).
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage, loadCatalogue } = require('./harness');

const SK0 = loadReportJs().serverKpis;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const COMPONENTS = { kafka: { type: 'custom', description: 'Kafka' } };

/** One custom assert on kafka, with the bands given. */
function assertWith(id, warn, fail) {
    const a = { id: id, component: 'kafka', query: id, reduce: { kind: 'max' } };
    if (warn !== undefined) a.warn = warn;
    if (fail !== undefined) a.fail = fail;
    return a;
}

/**
 * A page as the build writes it for a file whose serverMetrics block is `block`: annotationsDocument
 * is the block as documentOf re-serialises it, so an overflowed number in it is already the text
 * "Infinity". No payload: the interim report's shape, which the editor and the Download work on.
 */
function page(block) {
    return loadReportPage({
        runId: 'server-write-back',
        annotationsDocument: JSON.parse(JSON.stringify({ serverMetrics: block })),
        samplers: [],
        overall: { totalSamples: 0 },
        serverCatalogue: loadCatalogue()
    });
}

/** One assert as the next Download writes it. */
function written(WIR, id) {
    return JSON.parse(WIR.document.text()).serverMetrics.asserts.find((a) => a.id === id);
}

/** The mapper mirror's sentence for an assert carrying `band` as `which` — what Java prints. */
function refusalOf(which, band) {
    const other = which === 'warn' ? { gt: 100000 } : { gt: 1 };
    const json = which === 'warn' ? assertWith('x', band, other) : assertWith('x', other, band);
    return SK0._kpiFromJson(json, COMPONENTS).problems[0];
}

/* Every way ServerMetricsMapper.readBand refuses a band whose JSON is an object (any other shape
   refuses the whole file), as documentOf ships it: a bare 1e400 is the text "Infinity" by then. The
   __proto__ key is built by JSON.parse, as a file read builds it. */
const REFUSED = [
    ['an unknown operator', { gtt: 500 }],
    ['text', { gt: 'abc' }],
    ['an overflowed number, as documentOf writes it', { gt: 'Infinity' }],
    ['two operators', { gt: 1, lt: 2 }],
    ['the retired outside', { outside: [1, 2] }],
    ['null', { gt: null }],
    ['a boolean', { gt: true }],
    ['a unit suffix', { gt: '50ms' }],
    ['an operator every object inherits', { constructor: 100 }],
    ['an operator that names the prototype', JSON.parse('{"__proto__": 5}')],
    ['an operator padded with a no-break space', { [String.fromCharCode(0xa0) + 'gt']: 100 }]
];

/* The touches that used to release a carried band, each leaving its box empty. */
const TOUCHES = [
    ['an operator picked over the empty box', (S, which) => S.set(which + '.op', 'lt')],
    ['a number typed and cleared again', (S, which) => { S.set(which + '.value', '7'); S.set(which + '.value', ''); }],
    ['a box left holding only spaces', (S, which) => S.set(which + '.value', '   ')],
    ['every operator in turn, then a number typed and cleared', (S, which) => {
        ['gte', 'lt', 'lte', 'gt'].forEach((op) => S.set(which + '.op', op));
        S.set(which + '.value', '900');
        S.set(which + '.value', '');
    }]
];

/** The file with `band` as lag's `which`, the other band readable. */
function blockWith(which, band) {
    const lag = which === 'warn' ? assertWith('lag', band, { gt: 500 }) : assertWith('lag', { gt: 300 }, band);
    return { components: COMPONENTS, asserts: [lag, assertWith('ok', undefined, { gt: 95 })] };
}

// ---------------------------------------------------------------------------
// B4 — only × releases a carried band
// ---------------------------------------------------------------------------

['fail', 'warn'].forEach((which) => {
    REFUSED.forEach(([shape, band]) => {
        TOUCHES.forEach(([touch, apply]) => {
            test('B4: ' + touch + ' keeps an unreadable ' + which + ' band (' + shape + '), and Save is refused', () => {
                const WIR = page(blockWith(which, band));
                const S = WIR.serverKpis;
                const text = WIR.document.text();
                S.edit(S._keyOf('lag'));
                apply(S, which);
                S.applyEditor();
                assert.deepStrictEqual(plain(written(WIR, 'lag')[which]), plain(band),
                    'the Save dropped the file\'s ' + which + ' band, so the next run judges the assert on the other band alone');
                assert.strictEqual(WIR.document.count(), 0, 'a refused Save counted as a change');
                assert.strictEqual(WIR.document.text(), text);
                // Still open, still refusing in the mapper's words.
                assert.strictEqual(S._editorProblem(), refusalOf(which, band));
                assert.deepStrictEqual(plain(S._editorKpi().unreadable[which]), plain(band));
                S.closeEditor();
                assert.strictEqual(WIR.document.text(), text, 'Cancel wrote');
            });
        });
    });
});

test('B4: an unrelated field touched is refused for the carried band, and Cancel leaves it verbatim', () => {
    const WIR = page(blockWith('fail', { gtt: 500 }));
    const S = WIR.serverKpis;
    const text = WIR.document.text();
    S.edit(S._keyOf('lag'));
    S.set('description', 'touched something else');
    assert.strictEqual(S._editorProblem(), "fail operator 'gtt' is not one of gt, gte, lt, lte — so this server"
        + ' assert declares a limit it cannot apply, and would report PASS without ever testing fail');
    S.applyEditor();
    S.closeEditor();
    assert.strictEqual(WIR.document.text(), text);
    assert.strictEqual(WIR.document.count(), 0);
});

test('B4: a number the page reads, typed over a carried band, replaces it under the operator picked', () => {
    for (const which of ['fail', 'warn']) {
        const WIR = page(blockWith(which, { gtt: 500 }));
        const S = WIR.serverKpis;
        S.edit(S._keyOf('lag'));
        S.set(which + '.op', which === 'fail' ? 'gte' : 'gt');
        S.set(which + '.value', which === 'fail' ? '700' : '150');
        assert.strictEqual(S._editorProblem(), null);
        S.applyEditor();
        assert.deepStrictEqual(written(WIR, 'lag')[which], which === 'fail' ? { gte: 700 } : { gt: 150 });
        assert.strictEqual(WIR.document.count(), 1);
    }
});

test('B4: text typed over a carried band is refused for the text, and clearing it brings the file\'s band back', () => {
    const WIR = page(blockWith('fail', { gtt: 500 }));
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('fail.value', '9oo');
    assert.ok(/fail gt must be a number, not the text "9oo"/.test(S._editorProblem()), S._editorProblem());
    S.set('fail.value', '');
    assert.ok(/fail operator 'gtt'/.test(S._editorProblem()), S._editorProblem());
});

test('B4: × releases a carried band, and with its box left empty the Save writes no band of that kind', () => {
    for (const which of ['fail', 'warn']) {
        const WIR = page(blockWith(which, { gt: 1, lt: 2 }));
        const S = WIR.serverKpis;
        S.edit(S._keyOf('lag'));
        S.set(which + '.op', 'lt');                       // touched first: × still releases it
        S.removeBand(which);
        assert.strictEqual(S._editorKpi().unreadable[which], undefined);
        assert.strictEqual(S._editorProblem(), null, 'the other band is readable, so the assert can be saved');
        S.applyEditor();
        const lag = written(WIR, 'lag');
        assert.strictEqual(lag[which], undefined, 'the author removed it');
        assert.deepStrictEqual(lag[which === 'fail' ? 'warn' : 'fail'], which === 'fail' ? { gt: 300 } : { gt: 500 });
        assert.strictEqual(WIR.document.count(), 1);
    }
});

test('B4: × empties the box too, so text typed over the carried band is not what a Save then writes', () => {
    const WIR = page(blockWith('fail', { gtt: 500 }));
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('fail.op', 'lte');
    S.set('fail.value', '9oo');
    S.removeBand('fail');
    assert.strictEqual(S._editorProblem(), null, 'the text stayed in the box and refused the Save');
    assert.strictEqual(S._editorKpi().fail, null);
    S.applyEditor();
    assert.strictEqual(written(WIR, 'lag').fail, undefined);
    // The operator picked stays for the next band typed.
    S.edit(S._keyOf('lag'));
    S.set('fail.op', 'lte');
    S.removeBand('fail');
    S.set('fail.value', '40');
    S.applyEditor();
    assert.deepStrictEqual(written(WIR, 'lag').fail, { lte: 40 });
});

test('B4: × then a number declares the new band', () => {
    const WIR = page(blockWith('warn', { gtt: 1 }));
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.removeBand('warn');
    S.set('warn.value', '150');
    S.applyEditor();
    assert.deepStrictEqual(written(WIR, 'lag').warn, { gt: 150 });
});

test('B4: × releases its own band only, and an assert left with no band at all is refused for that', () => {
    const WIR = page({ components: COMPONENTS, asserts: [assertWith('lag', { gtt: 1 }, { gt: 'abc' })] });
    const S = WIR.serverKpis;
    const text = WIR.document.text();
    S.edit(S._keyOf('lag'));
    S.removeBand('fail');
    assert.strictEqual(S._editorProblem(), refusalOf('warn', { gtt: 1 }), 'the warn band is still carried');
    S.applyEditor();
    S.removeBand('warn');
    assert.strictEqual(S._editorProblem(), 'no warn or fail band — the server assert can never decide anything');
    S.applyEditor();
    S.closeEditor();
    assert.strictEqual(WIR.document.text(), text, 'a refused editor wrote');
    assert.strictEqual(WIR.document.count(), 0);
});

test('B4: the carry is the opening\'s — a second Edit of the same row starts from the file\'s band again', () => {
    const WIR = page(blockWith('fail', { gtt: 500 }));
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.removeBand('fail');
    S.closeEditor();
    S.edit(S._keyOf('lag'));
    assert.deepStrictEqual(plain(S._editorKpi().unreadable.fail), { gtt: 500 }, '× outlived the Cancel');
    assert.ok(/fail operator 'gtt'/.test(S._editorProblem()));
});

test('B4: a readable band is never carried, so an emptied box is no band (control)', () => {
    const WIR = page(blockWith('fail', { gt: 500 }));
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('fail.value', '');
    S.applyEditor();
    assert.strictEqual(written(WIR, 'lag').fail, undefined);
    assert.deepStrictEqual(written(WIR, 'lag').warn, { gt: 300 });
});

// ---------------------------------------------------------------------------
// S1 — a document is read as documentOf ships it
// ---------------------------------------------------------------------------

/** One assert parsed from a document and written back, as a Download writes it. */
function roundTrip(entry) {
    const [kpi] = SK0.kpisFromDocument({ components: COMPONENTS, asserts: [entry] });
    return { kpi, written: plain(SK0._kpiToJson(kpi)) };
}

test('S1: a bare 1e400 band is carried as documentOf\'s text "Infinity", -1e400 as "-Infinity"', () => {
    for (const [value, text] of [[Infinity, 'Infinity'], [-Infinity, '-Infinity']]) {
        for (const which of ['warn', 'fail']) {
            const entryOf = (v) => (which === 'warn' ? assertWith('huge', { gt: v }, { gt: 5 })
                : assertWith('huge', { gt: 5 }, { gt: v }));
            const { kpi, written: out } = roundTrip(entryOf(value));
            assert.deepStrictEqual(out[which], { gt: text },
                'JSON.stringify wrote the carried Infinity as null: ' + JSON.stringify(out[which]));
            // Read exactly as the report's own document reads it: the same sentence, the same text.
            const shipped = roundTrip(entryOf(text));
            assert.deepStrictEqual(plain(kpi.problems), plain(shipped.kpi.problems));
            assert.deepStrictEqual(out, shipped.written);
        }
    }
});

test('S1: a "1e400" or "NaN" the file wrote as text stays that text', () => {
    for (const text of ['1e400', 'NaN', '-Infinity', 'Infinity']) {
        assert.deepStrictEqual(roundTrip(assertWith('t', { gt: 5 }, { gt: text })).written.fail, { gt: text });
    }
});

test('S1: an overflowed number anywhere in an assert is read as documentOf ships it', () => {
    // KpiJson binds reduce and the bands as Map<String, Object> and keeps the unknown keys, so
    // Jackson writes each overflowed Double back as its text.
    const entry = assertWith('t', undefined, { gt: 5 });
    entry.reduce = { kind: 'max', over: { breachBudget: Infinity } };
    entry._limit = -Infinity;
    const { kpi, written: out } = roundTrip(entry);
    assert.deepStrictEqual(out.reduce, { kind: 'max', over: { breachBudget: 'Infinity' } });
    assert.strictEqual(out._limit, '-Infinity');
    assert.ok(/breachBudget/.test(kpi.problems[0]), 'still refused: ' + kpi.problems[0]);
});

/** E26's lag-spike with a bare 1e400 fail, as documentOf ships it: the text "Infinity". */
const SHIPPED = { components: COMPONENTS, asserts: [assertWith('lag', { gt: 300 }, { gt: 'Infinity' }), assertWith('ok', undefined, { gt: 95 })] };

/** The same file as it sits on disk, opened: JSON.parse turns its 1e400 into Infinity. */
function onDisk(text) {
    const doc = JSON.parse(text.replace('"Infinity"', '1e400'));
    assert.strictEqual(doc.serverMetrics.asserts[0].fail.gt, Infinity, 'precondition: the file holds the number');
    return doc;
}

test('S1: opening the report\'s own file, which wrote a bare 1e400, is no change and writes "Infinity"', () => {
    const WIR = page(SHIPPED);
    const text = WIR.document.text();
    for (let lap = 1; lap <= 2; lap++) {
        assert.strictEqual(WIR.document.replace(onDisk(text), 'own.json'), null);
        assert.strictEqual(WIR.document.count(), 0, 'lap ' + lap + ': opening the report\'s own file counted as a change');
        assert.strictEqual(WIR.document.hasDrafts(), false, 'lap ' + lap + ': the pill would show');
        assert.deepStrictEqual(written(WIR, 'lag').fail, { gt: 'Infinity' });
        assert.strictEqual(WIR.document.text(), text);
    }
});

test('S1: Duplicate and Disable of an opened bare 1e400 keep "Infinity"', () => {
    const WIR = page(SHIPPED);
    const S = WIR.serverKpis;
    assert.strictEqual(WIR.document.replace(onDisk(WIR.document.text()), 'own.json'), null);
    S.duplicate(S._keyOf('lag'));
    assert.deepStrictEqual(written(WIR, 'lag-2').fail, { gt: 'Infinity' }, 'the copy wrote null');
    S.toggleEnabled(S._keyOf('lag'));
    assert.deepStrictEqual(written(WIR, 'lag').fail, { gt: 'Infinity' }, 'Disable wrote null');
    assert.strictEqual(WIR.document.count(), 2);
});

// ---------------------------------------------------------------------------
// RB9 — a Save writes no unit nobody typed
// ---------------------------------------------------------------------------

/* editorKpi wrote the Unit box for every custom query, and the box is '' for "no unit", so any Save
   of a custom assert whose file wrote no unit added "unit": "" — a key nobody typed, on the assert
   the edit was about. A unit is a label and converts nothing, and Java reads an empty one as none, so
   no verdict moved; the file did. The Unit is written when it holds something or the file wrote one. */

test('RB9: a Save of another field writes no unit on a custom assert whose file wrote none', () => {
    const WIR = page({ components: COMPONENTS, asserts: [assertWith('lag', { gt: 300 }, { gt: 500 })] });
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('description', 'x');
    S.applyEditor();
    const lag = written(WIR, 'lag');
    assert.strictEqual(lag.description, 'x');
    assert.ok(!Object.prototype.hasOwnProperty.call(lag, 'unit'), 'a unit nobody typed: ' + JSON.stringify(lag));
    assert.strictEqual(WIR.document.count(), 1);
});

test('RB9: a unit the file wrote is kept by a Save of another field, an empty one included', () => {
    for (const unit of ['ms', '', ' ']) {
        const lag = assertWith('lag', { gt: 300 }, { gt: 500 });
        lag.unit = unit;
        const WIR = page({ components: COMPONENTS, asserts: [lag] });
        const S = WIR.serverKpis;
        S.edit(S._keyOf('lag'));
        S.set('description', 'x');
        S.applyEditor();
        assert.strictEqual(written(WIR, 'lag').unit, unit, 'the file\'s unit ' + JSON.stringify(unit));
    }
});

test('RB9: a unit typed is written, and clearing one the file wrote writes it empty', () => {
    const WIR = page({ components: COMPONENTS, asserts: [assertWith('lag', { gt: 300 }, { gt: 500 })] });
    const S = WIR.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('unit', 'msg/s');
    S.applyEditor();
    assert.strictEqual(written(WIR, 'lag').unit, 'msg/s');
    S.edit(S._keyOf('lag'));
    S.set('unit', '');
    S.applyEditor();
    assert.strictEqual(written(WIR, 'lag').unit, '', 'the file wrote one, so the edit writes its value');
});

test('RB9: a new custom assert writes a unit only when one is typed', () => {
    const WIR = page({ components: COMPONENTS, asserts: [assertWith('lag', { gt: 300 }, { gt: 500 })] });
    const S = WIR.serverKpis;
    for (const [id, unit] of [['bare', ''], ['with-unit', 'B']]) {
        S.addKpi();
        S.set('id', id);
        S.set('query', 'q_' + id);
        if (unit) S.set('unit', unit);
        S.set('fail.value', '5');
        assert.strictEqual(S._editorProblem(), null, S._editorProblem());
        S.applyEditor();
        const added = written(WIR, id);
        assert.ok(added, id + ' was not written');
        if (unit) assert.strictEqual(added.unit, unit);
        else assert.ok(!Object.prototype.hasOwnProperty.call(added, 'unit'), 'a unit nobody typed: ' + JSON.stringify(added));
    }
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
