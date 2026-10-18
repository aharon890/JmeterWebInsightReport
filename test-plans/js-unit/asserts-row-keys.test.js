/**
 * Every Asserts-table row is addressed by a KEY the page mints, never by its rule's id
 * (client-id-keyed-handlers) — driven through the page's own lifecycle (harness.loadReportPage),
 * with no browser.
 *
 * Java judges two rules under one id: two id-less rules on one sampler and metric both derive
 * `a-p95`, and each is judged on its own. The table's buttons were keyed by that id, so the two
 * rows were one row to every one of them: Disable on either flipped both, Delete removed both,
 * Duplicate copied the last whichever was pressed, Edit opened the last and Save replaced the last,
 * and the matches toggle opened both. The server board moved to per-row keys long ago (R1-2); this
 * holds the client table to the same rule, and draftCount to pairing by occurrence as markDrafts
 * pairs (N5).
 *
 * The rows are read out of the table body the tab renders (_rulesBody), and a button is "clicked" by
 * running its onclick text — the one WIR call it carries — so what is tested is the handler a reader
 * actually presses.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage, loadCatalogue } = require('./harness');

const A0 = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/* Two id-less rules that derive one id, `a-p95` — the first FAILs (p95 5 > 1), the second PASSes —
   and a third with an id of its own. */
const TWINS = [
    { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 } },
    { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 100 } },
    { id: 'a-mean', target: { sampler: 'A' }, metric: 'mean', fail: { gt: 100 } }
];

/** A page as the build writes it: the client rules arrive judged, as Java judged them. */
function page(rules = TWINS) {
    const chartData = {
        runId: 'asserts-row-keys',
        annotationsDocument: JSON.parse(JSON.stringify({ assertions: rules })),
        samplers: [{ name: 'A', count: 10, p95: 5, mean: 3 }],
        overall: { totalSamples: 10 },
        serverCatalogue: loadCatalogue()
    };
    const judged = A0.evaluate(rules.map((r, i) => A0._ruleFromJson(r, i)), chartData);
    chartData.assertions = JSON.parse(JSON.stringify(judged.rules));
    chartData.gate = JSON.parse(JSON.stringify(judged.gate));
    return loadReportPage(chartData);
}

/** An attribute's value as the HTML parser hands it over. */
function decode(s) {
    return s.replace(/&quot;/g, '"').replace(/&#39;/g, '\'').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&');
}

function attr(tag, name) {
    const m = new RegExp(' ' + name + '="([^"]*)"').exec(tag);
    return m ? decode(m[1]) : null;
}

/**
 * The table's rule rows, in the order drawn: each row's id, key, and every onclick in it by the
 * button's first class (the matches toggle is wir-link wir-matches-btn: keyed by its second).
 */
function rows(WIR) {
    return WIR.asserts._rulesBody().split('<tr ').filter((s) => /^class="wir-rule-row/.test(s)).map((tr) => {
        const head = tr.slice(0, tr.indexOf('>'));
        const buttons = {};
        tr.replace(/<button type="button" class="([^"]*)"[^>]*? onclick="([^"]*)"/g, (all, cls, handler) => {
            const name = cls.split(' ').filter((c) => c !== 'wir-link')[0];
            buttons[name] = decode(handler);
            return all;
        });
        return { id: attr(head, 'data-rule-id'), key: attr(head, 'data-rule-key'), buttons };
    });
}

/* The rows as drawn are SORTED (by severity, then id), so a test names the row it presses by its
   rule: the n-th drawn row judged under `id`. The first a-p95 drawn is the FAIL (fail > 1). */
function rowOf(WIR, id, n = 0) {
    const found = rows(WIR).filter((r) => r.id === id)[n];
    assert.ok(found, 'no row ' + n + ' for ' + id + ' in ' + JSON.stringify(rows(WIR).map((r) => r.id)));
    return found;
}

/** The rows' keys in declaration order — the order a recompute, a Delete and an Undo keep. */
function keysInOrder(WIR) {
    return JSON.parse(JSON.stringify(WIR.asserts._state.rules.map((r) => r.key)));
}

/** Press a button: run the one WIR call its onclick carries, as the browser would. */
function press(WIR, handler) {
    assert.match(handler, /^WIR\.asserts\.[A-Za-z]+\('(?:[^'\\]|\\.)*'\)$/, 'not one literal call: ' + handler);
    new Function('WIR', handler)(WIR);
}

/** [id, enabled, fail band, draftState] per rule, copied out of the page's realm. */
function state(WIR) {
    return JSON.parse(JSON.stringify(WIR.asserts._state.rules.map((r) => [r.id, r.enabled !== false,
        r.fail ? r.fail.value : null, r.draftState || null])));
}

test('precondition: two rules judged under one derived id', () => {
    const W = page();
    assert.deepStrictEqual(W.asserts._state.rules.map((r) => r.id), ['a-p95', 'a-p95', 'a-mean']);
    assert.strictEqual(W.asserts._draftCount(), 0);
});

test('every row carries a key of its own, and every handler on it names that key and nothing else', () => {
    const W = page();
    const drawn = rows(W);
    assert.strictEqual(drawn.length, 3);
    const keys = drawn.map((r) => r.key);
    assert.ok(keys.every((k) => typeof k === 'string' && k !== ''), 'a row with no key: ' + JSON.stringify(keys));
    assert.strictEqual(new Set(keys).size, 3, 'two rows share a key: ' + JSON.stringify(keys));
    drawn.forEach((r) => {
        ['wir-edit-btn', 'wir-dup-btn', 'wir-toggle-btn', 'wir-del-btn', 'wir-matches-btn'].forEach((b) => {
            assert.strictEqual(r.buttons[b] !== undefined, true, r.id + ' has no ' + b);
            assert.ok(r.buttons[b].endsWith('(\'' + r.key + '\')'), b + ' on ' + r.key + ' is ' + r.buttons[b]);
        });
    });
    // _keyOf answers with the first rule DECLARED under the id, which is the first drawn here too.
    assert.strictEqual(W.asserts._keyOf('a-p95'), keysInOrder(W)[0], '_keyOf answers with the first rule carrying the id');
    assert.strictEqual(W.asserts._keyOf('a-p95'), rowOf(W, 'a-p95').key);
    assert.strictEqual(W.asserts._keyOf('a-mean'), rowOf(W, 'a-mean').key);
    assert.strictEqual(W.asserts._keyOf('nobody'), null);
});

test('Disable on the first of two rules sharing an id disables that row alone', () => {
    const W = page();
    press(W, rowOf(W, 'a-p95', 0).buttons['wir-toggle-btn']);
    // Was [false, false, true]: toggleEnabled('a-p95') flipped every rule called a-p95.
    assert.deepStrictEqual(state(W).map((r) => r[1]), [false, true, true]);
    assert.deepStrictEqual(state(W)[1], ['a-p95', true, 100, null], 'the twin was touched');
    assert.strictEqual(W.asserts._draftCount(), 1);
});

test('Delete on the second of two rules sharing an id deletes that row alone', () => {
    const W = page();
    press(W, rowOf(W, 'a-p95', 1).buttons['wir-del-btn']);
    // Was []-plus-a-mean: deleteRule('a-p95') removed both.
    assert.deepStrictEqual(state(W), [['a-p95', true, 1, null], ['a-mean', true, 100, null]]);
    assert.strictEqual(W.asserts._draftCount(), 1);
});

test('Duplicate on the first of two rules sharing an id copies the first, under a key of its own', () => {
    const W = page();
    press(W, rowOf(W, 'a-p95', 0).buttons['wir-dup-btn']);
    const now = state(W);
    assert.strictEqual(now.length, 4);
    // Was 100: duplicateRule('a-p95') copied the last rule called a-p95.
    assert.deepStrictEqual(now[3], ['a-p95-copy', true, 1, 'new']);
    const keys = rows(W).map((r) => r.key);
    assert.strictEqual(new Set(keys).size, 4, 'the copy took its source\'s key: ' + JSON.stringify(keys));
});

test('Edit on the first of two rules sharing an id opens the first, and Save replaces the first', () => {
    const W = page();
    press(W, rowOf(W, 'a-p95', 0).buttons['wir-edit-btn']);
    // Was 100: edit('a-p95') opened the last rule called a-p95.
    assert.strictEqual(W.asserts._editorRule().fail.value, 1, 'the editor opened another row');
    W.asserts.setBand('fail', 'value', '2');
    W.asserts.saveEditor();
    // Was the second replaced and the first untouched.
    assert.deepStrictEqual(state(W), [['a-p95', true, 2, 'edited'], ['a-p95', true, 100, null],
        ['a-mean', true, 100, null]]);
    assert.strictEqual(W.asserts._draftCount(), 1);
});

test('the matches toggle on the first of two rules sharing an id opens that row\'s matches alone', () => {
    const W = page();
    const first = rowOf(W, 'a-p95', 0);
    press(W, first.buttons['wir-matches-btn']);
    const body = W.asserts._rulesBody();
    // Was 2: state.expandedRule held the id, and both rows called a-p95 drew their matches.
    assert.strictEqual(body.split('class="wir-match-row"').length - 1, 1, 'matches drawn under more than one row');
    // ...and under the row that was pressed: the next row drawn after it is its match row.
    const next = body.indexOf('<tr ', body.indexOf('data-rule-key="' + first.key + '"'));
    assert.ok(body.startsWith('<tr class="wir-match-row"', next), 'the matches are not under the pressed row');
});

test('a key survives a recompute, and an Undo puts the row back under its own key', () => {
    const W = page();
    const before = keysInOrder(W);
    const drawn = () => rows(W).map((r) => r.key).sort();
    press(W, rowOf(W, 'a-mean').buttons['wir-toggle-btn']);
    assert.deepStrictEqual(keysInOrder(W), before, 'a recompute moved a key');
    assert.deepStrictEqual(drawn(), before.slice().sort());
    const captured = W.document.capture();
    press(W, rowOf(W, 'a-p95', 0).buttons['wir-del-btn']);
    assert.deepStrictEqual(keysInOrder(W), before.slice(1));
    assert.deepStrictEqual(drawn(), before.slice(1).sort());
    W.document.reinstate(captured);
    assert.deepStrictEqual(keysInOrder(W), before, 'the Undo gave the row another key');
    assert.deepStrictEqual(drawn(), before.slice().sort());
});

test('a key is the page\'s, never the file\'s', () => {
    const W = page();
    press(W, rowOf(W, 'a-p95', 0).buttons['wir-toggle-btn']);
    const text = W.document.text();
    assert.ok(!/"key"/.test(text), 'a row key was written to the file');
    assert.deepStrictEqual(JSON.parse(text).assertions.map((a) => Object.keys(a).sort()),
        [['enabled', 'fail', 'metric', 'target'], ['fail', 'metric', 'target'], ['fail', 'id', 'metric', 'target']]);
});

test('a restored draft whose rules carry no key, or a key another rule holds, gets a key of its own for each', () => {
    const W = page();
    const slice = W.document._slices.filter((s) => s.name === 'asserts')[0];
    const saved = JSON.parse(JSON.stringify(slice.snapshot()));
    const kept = saved.rules[2].key;
    delete saved.rules[0].key;                 // a record written before rows had keys
    saved.rules[1].key = kept;                 // a key another rule holds
    assert.strictEqual(slice.restore(saved), true);
    const keys = rows(W).map((r) => r.key);
    assert.ok(keys.every((k) => typeof k === 'string' && k !== ''), JSON.stringify(keys));
    assert.strictEqual(new Set(keys).size, 3, 'two rows share a key after a restore: ' + JSON.stringify(keys));
    // A rule minted after the restore takes none of the restored keys.
    press(W, rowOf(W, 'a-mean').buttons['wir-dup-btn']);
    assert.strictEqual(new Set(rows(W).map((r) => r.key)).size, 4);
});

/** A chart as far as the drag handles use one: pixels are 300 - value, and setOption is kept. */
function fakeChart() {
    return {
        last: null,
        isDisposed() { return false; },
        on() { },
        getZr() { return { on() { } }; },
        getWidth() { return 800; },
        getHeight() { return 300; },
        getModel() { throw new Error('no laid-out grid'); },
        convertToPixel(finder, v) { return [0, 300 - v[1]]; },
        convertFromPixel(finder, p) { return [0, 300 - p[1]]; },
        setOption(option) { this.last = option; },
        getOption() { return { graphic: [{ elements: (this.last && this.last.graphic) || [] }] }; }
    };
}

test('two rules sharing an id on one chart each get a drag handle, and each handle retunes its own row', () => {
    // ECharts keys a graphic element by its id, and the handles were `wir-handle-<rule id>-<kind>`:
    // two id-less run-mean rules, both `run-mean`, drew ONE fail handle between them — the second
    // replacing the first — so the first could not be dragged at all. (Run-scoped, because a
    // sampler rule draws only while the sampler filter shows its sampler, and this page has none.)
    const W = page([
        { target: { scope: 'overall' }, metric: 'mean', fail: { gt: 100 } },
        { target: { scope: 'overall' }, metric: 'mean', fail: { gt: 200 } }
    ]);
    const chart = fakeChart();
    W.asserts.attachChart(chart, 'response-time-chart', 'responseTime');
    // The handles, not the band labels drawn beside them (U10a), which are keyed by row too.
    const handles = chart.last.graphic.filter((g) => /^wir-handle-/.test(g.id));
    const ids = JSON.parse(JSON.stringify(handles.map((h) => h.id)));
    assert.strictEqual(ids.length, 2, JSON.stringify(ids));
    assert.strictEqual(new Set(ids).size, 2, 'two handles share an ECharts id: ' + JSON.stringify(ids));
    const labels = chart.last.graphic.filter((g) => /^wir-chart-label-/.test(g.id)).map((g) => g.id);
    assert.strictEqual(new Set(labels).size, labels.length, 'two labels share an ECharts id: ' + JSON.stringify(labels));
    // Each handle is its own rule's: the one drawn at 100 retunes the first, and the twin is untouched.
    const first = handles.filter((h) => h.position[1] === 300 - 100)[0];
    assert.ok(first, 'no handle at the first rule\'s band');
    first.ondragend.call({ y: 300 - 150 });
    assert.deepStrictEqual(state(W), [['run-mean', true, 150, 'edited'], ['run-mean', true, 200, null]]);
    // The test seam still names a rule by id: the first row judged under it.
    W.asserts.attachChart(chart, 'response-time-chart', 'responseTime');
    assert.strictEqual(W.asserts._handleValue(chart, 'run-mean', 'responseTime'), 150);
});

test('of two rules sharing an id on one chart, only the strictest fail line is drawn boldly', () => {
    // The binding fail line was picked by id, so both run-mean lines were "the strictest": two bold
    // lines, and the reader could not tell which limit binds.
    const W = page([
        { target: { scope: 'overall' }, metric: 'mean', fail: { gt: 200 } },
        { target: { scope: 'overall' }, metric: 'mean', fail: { gt: 100 } }
    ]);
    const lines = JSON.parse(JSON.stringify(W.asserts.markLines('responseTime')
        .filter((l) => l.label && /^run-mean fail/.test(l.label.formatter))
        .map((l) => [l.yAxis, l.lineStyle.width])));
    assert.deepStrictEqual(lines, [[200, 1], [100, 1.8]]);
});

test('two rules sharing an id are each a rule the chart speaks about (REV-11)', () => {
    // rulesForChart de-duplicated by rule id, so of two run-mean rules each drawing its own line
    // the seam listed one, and the strictest-first order lost the other.
    const W = page([
        { target: { scope: 'overall' }, metric: 'mean', fail: { gt: 200 } },
        { target: { scope: 'overall' }, metric: 'mean', warn: { gt: 50 }, fail: { gt: 100 } }
    ]);
    const rules = W.asserts._rulesForChart('responseTime');
    assert.strictEqual(rules.length, 2, JSON.parse(JSON.stringify(rules.map((r) => r.key))).join(', '));
    assert.deepStrictEqual(JSON.parse(JSON.stringify(rules.map((r) => r.key))),
        JSON.parse(JSON.stringify(W.asserts._state.rules.map((r) => r.key))), 'each row once, in order');
    assert.deepStrictEqual(JSON.parse(JSON.stringify(W.asserts._strictestFirst('responseTime'))), ['run-mean', 'run-mean']);
});

test('two rules sharing an id are each measured from their own worst match', () => {
    // worstMatch memoised per render by rule id, so the second a-p95 read the first's worst match:
    // a FAIL, measured against the second rule's fail band (> 100) instead of the warn band (> 4)
    // it was judged WARN by — a margin of -0.95 for a rule past its line by a quarter.
    const W = page([
        { target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 } },
        { target: { sampler: 'A' }, metric: 'p95', warn: { gt: 4 }, fail: { gt: 100 } }
    ]);
    const rules = W.asserts._state.rules;
    assert.deepStrictEqual(JSON.parse(JSON.stringify(rules.map((r) => [r.id, r.status]))),
        [['a-p95', 'FAIL'], ['a-p95', 'WARN']], 'precondition');
    assert.strictEqual(W.asserts._breachMargin(rules[0]), 4);
    assert.strictEqual(W.asserts._breachMargin(rules[1]), 0.25);
});

test('draftCount pairs rules sharing an id by occurrence, as markDrafts does', () => {
    // Keyed by id with the last rule winning, an edit to the FIRST of two rules called a-p95 was
    // compared against nothing it had changed: 0 unsaved changes beside an EDITED row.
    const W = page();
    const rules = W.asserts._state.rules;
    rules[0] = Object.assign({}, rules[0], { fail: { op: 'gt', value: 3 } });
    assert.strictEqual(W.asserts._draftCount(), 1, 'an edit to the first of two rules sharing an id');
    const V = page();
    V.asserts._state.rules = V.asserts._state.rules.slice(0, 1).concat(V.asserts._state.rules.slice(2));
    assert.strictEqual(V.asserts._draftCount(), 1, 'the second of two rules sharing an id deleted');
    const U = page();
    U.asserts._state.rules = U.asserts._state.rules.concat([Object.assign({}, U.asserts._state.rules[0])]);
    assert.strictEqual(U.asserts._draftCount(), 1, 'a third rule called a-p95 added');
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
