/**
 * A board row's key follows its assert through a replaced document (F4): Delete component, a
 * reorder or a rename through Edit document…, a sibling deleted while the survivor is retuned, an
 * Undo — driven through the page's own lifecycle (harness.loadReportPage), with no browser.
 *
 * Every Server-tab component write re-adopts the whole serverMetrics block, and assignKeys hands
 * the re-read asserts their row keys. It paired by id occurrence alone: the j-th assert called X
 * took the j-th key called X. So when an earlier same-id sibling on another component was deleted,
 * or moved behind the survivor, the survivor landed on the sibling's key. It then read EDITED, its
 * own shipped key counted as a deletion, the unsaved count was off by one, and the build's verdict
 * was withheld (NOT RE-JUDGED on a capped series, NOT EVALUATED on a page with no payload). The
 * wrong keys went into the draft and survived a reload.
 *
 * It pairs on content first now: the board's text, then the shipped text, then id plus component,
 * then id alone (the old rule, as the last fallback) — each of the last two over the board's ids
 * first and then the file's.
 *
 * The page here: a shipped file whose two server asserts share the id `lag`, on components `a` and
 * `b` — Java judges both and raises one shared-id WARNING (ServerMetricsMapper.sharedIds). The
 * baked verdicts line up one per assert, so a row on its own key shows the build's PASS; on a
 * sibling's key it reads EDITED and loses it.
 */
'use strict';

const assert = require('assert');
const { loadReportPage, loadCatalogue } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const LAG_A = { id: 'lag', component: 'a', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 500 } };
const UP_B = { id: 'up', component: 'b', query: 'up', reduce: { kind: 'max' }, fail: { gt: 1 } };
const LAG_B = { id: 'lag', component: 'b', query: 'lag', reduce: { kind: 'p50' }, fail: { gt: 5000 } };

function page() {
    const asserts = [LAG_A, UP_B, LAG_B];
    return loadReportPage({
        runId: 'server-row-keys',
        annotationsDocument: JSON.parse(JSON.stringify({
            serverMetrics: { components: { a: { type: 'custom' }, b: { type: 'custom' } }, asserts }
        })),
        serverKpis: asserts.map((a) => ({ id: a.id, status: 'PASS' })),
        serverFindings: [],
        serverCatalogue: loadCatalogue(),
        samplers: [],
        overall: { totalSamples: 0 }
    });
}

/** deepStrictEqual that names both sides in one line, so a red run says what it got. */
function same(actual, expected, why) {
    assert.deepStrictEqual(actual, expected, (why ? why + ': ' : '') + 'expected ' + JSON.stringify(expected)
        + ', received ' + JSON.stringify(actual));
}

/** [key, component, display, edited] per row, copied out of the page's realm. */
function rows(WIR) {
    return JSON.parse(JSON.stringify(WIR.serverKpis._board().map((r) => [r.key, r.gate, r.display, r.edited])));
}

/** Edit document…: the document as the page would write it, edited, then applied whole. */
function replace(WIR, edit) {
    const doc = JSON.parse(WIR.document.text());
    edit(doc.serverMetrics);
    assert.strictEqual(WIR.document.replace(doc, WIR.document.source()), null);
    WIR.serverKpis.rerender();
}

test('shipped: three rows on k0..k2, each with its baked verdict, nothing unsaved', () => {
    const W = page();
    same(rows(W), [['k0', 'a', 'PASS', false], ['k1', 'b', 'PASS', false], ['k2', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 0);
});

test('Delete component keeps a surviving assert that shares an id on its own row (F4)', () => {
    const W = page();
    W.serverKpis.deleteComponent('a');
    // Was ['k0', 'b', 'NOT_EVALUATED', true]: the survivor took the deleted sibling's key.
    same(rows(W), [['k1', 'b', 'PASS', false], ['k2', 'b', 'PASS', false]]);
    // One assert deleted and the components map changed. Was 3: k0 "edited" as well as k2 "deleted".
    assert.strictEqual(W.document.countOf('server'), 2);
});

test('...and putting the document back is nothing unsaved, every row on its key again', () => {
    const W = page();
    const before = JSON.parse(W.document.text());
    W.serverKpis.deleteComponent('a');
    assert.strictEqual(W.document.replace(before, W.document.source()), null);
    W.serverKpis.rerender();
    same(rows(W).map((r) => r[0]), ['k0', 'k1', 'k2']);
    assert.strictEqual(W.document.count(), 0);
});

test('a reorder through Edit document… moves rows, it edits none — and is one change (F4)', () => {
    const W = page();
    replace(W, (b) => { b.asserts = [b.asserts[2], b.asserts[1], b.asserts[0]]; });
    // Was ['k0', 'b', NOT_EVALUATED, true] ... ['k2', 'a', NOT_EVALUATED, true], and a count of 2.
    same(rows(W), [['k2', 'b', 'PASS', false], ['k1', 'b', 'PASS', false], ['k0', 'a', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 1, 'the reorder is still one change (an array keeps its order)');
});

test('renaming an assert onto a sibling\'s id leaves the sibling its row (F4)', () => {
    const W = page();
    replace(W, (b) => { b.asserts[1].id = 'lag'; });
    // Was: the renamed `up` took k2 (the 2nd "lag" key), and LAG_B became a new, EDITED row.
    same(rows(W).map((r) => [r[0].charAt(0) === 'n' ? 'new' : r[0], r[3]]),
        [['k0', false], ['new', true], ['k2', false]]);
    assert.strictEqual(W.document.countOf('server'), 2, 'k1 deleted, one assert added');
});

test('a sibling deleted and the survivor retuned in one apply: the survivor is edited against ITS entry', () => {
    // Pass 3, id plus component: the retuned survivor matches no text, and by id alone it took the
    // deleted sibling's k0.
    const W = page();
    replace(W, (b) => { b.asserts = [b.asserts[1], Object.assign({}, b.asserts[2], { fail: { gt: 6000 } })]; });
    same(rows(W).map((r) => [r[0], r[3]]), [['k1', false], ['k2', true]]);
    // ...so retuning it back makes it the shipped assert again, with the build's verdict.
    replace(W, (b) => { b.asserts[1].fail = { gt: 5000 }; });
    same(rows(W), [['k1', 'b', 'PASS', false], ['k2', 'b', 'PASS', false]]);
});

test('an edit put back while a new same-id assert is inserted ahead of it: the put-back is the shipped row again', () => {
    // Pass 2, the shipped text: the board has the retuned text, so only the file's tells the put-back
    // assert from the new one — by id and component, the new one came first and took the key.
    const W = page();
    replace(W, (b) => { b.asserts[2].fail = { gt: 6000 }; });
    same(rows(W).map((r) => [r[0], r[3]]), [['k0', false], ['k1', false], ['k2', true]], 'precondition');
    replace(W, (b) => {
        const fresh = { id: 'lag', component: 'b', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 1 } };
        b.asserts = [b.asserts[0], b.asserts[1], fresh, Object.assign({}, b.asserts[2], { fail: { gt: 5000 } })];
    });
    same(rows(W).map((r) => [r[0].charAt(0) === 'n' ? 'new' : r[0], r[2], r[3]]),
        [['k0', 'PASS', false], ['k1', 'PASS', false], ['new', 'NOT_EVALUATED', true], ['k2', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 1, 'one assert added');
});

test('two same-id asserts on one component, both edited, then reordered: each keeps its own row', () => {
    // Pass 1, the board's text: by id and component the two are the same, and pairing them in
    // document order swapped their rows.
    // Two applies, one edit each: made in ONE apply, the two edits leave nothing that pairs them but
    // their position — the residual assignKeys' docblock names.
    const W = page();
    replace(W, (b) => { b.asserts[0].component = 'b'; });
    replace(W, (b) => { b.asserts[2].fail = { gt: 6000 }; });
    const before = rows(W);
    same(before.map((r) => [r[0], r[1], r[3]]), [['k0', 'b', true], ['k1', 'b', false], ['k2', 'b', true]], 'precondition');
    replace(W, (b) => { b.asserts = [b.asserts[2], b.asserts[1], b.asserts[0]]; });
    same(rows(W), [before[2], before[1], before[0]]);
});

test('an assert moved to another component and retuned in one apply is one edit on its own row', () => {
    // Pass 4, the id alone: nothing else about it is as it was.
    const W = page();
    replace(W, (b) => { b.asserts[1].component = 'a'; b.asserts[1].fail = { gt: 2 }; });
    same(rows(W).map((r) => [r[0], r[1], r[3]]), [['k0', 'a', false], ['k1', 'a', true], ['k2', 'b', false]]);
    assert.strictEqual(W.document.countOf('server'), 1);
});

/*
 * Passes 3 and 4 each run over the board's ids first, for every incoming assert, and only then over
 * the shipped file's. The cases below each tell one of those four sweeps from the others: with any
 * one dropped, or the board's and the file's run in the other order, or both in one sweep, a row
 * lands on another key (review D, the pins and correctness lenses).
 */

/** Keys with every `n<seq>` read as 'new', so a row made here compares without its sequence. */
const keyed = (r) => [r[0].charAt(0) === 'n' ? 'new' : r[0]].concat(r.slice(1));

test('a row renamed in the editor keeps its key when a new assert with its old id is written ahead of it', () => {
    // The board holds k1 as `up2`, the file shipped it as `up`. Offered in one sweep with the board,
    // the file's `up` matched the NEW assert first, by id and component, and the renamed row became
    // a new one — so putting it back as shipped read EDITED, NOT_EVALUATED, and counted 2.
    const W = page();
    W.serverKpis.edit('k1');
    W.serverKpis.set('id', 'up2');
    W.serverKpis.applyEditor();
    same(rows(W).map((r) => [r[0], r[3]]), [['k0', false], ['k1', true], ['k2', false]], 'precondition: renamed on its own row');
    replace(W, (b) => {
        b.asserts[1].fail = { gt: 9 };
        b.asserts.splice(1, 0, { id: 'up', component: 'b', query: 'up', reduce: { kind: 'max' }, fail: { gt: 3 } });
    });
    same(rows(W).map(keyed).map((r) => [r[0], r[3]]), [['k0', false], ['new', true], ['k1', true], ['k2', false]]);
    replace(W, (b) => { b.asserts[2] = JSON.parse(JSON.stringify(UP_B)); });
    same(rows(W).map(keyed), [['k0', 'a', 'PASS', false], ['new', 'b', 'NOT_EVALUATED', true],
        ['k1', 'b', 'PASS', false], ['k2', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 1, 'one assert added');
});

test('a row renamed in the editor, then renamed back and retuned in one apply, is one edit (guard)', () => {
    // Keeping the file's ids out of pass 3 for every key the board still holds would fix the case
    // above and break this one: the board's `up2` matches nothing, and only the file has `up` on k1.
    const W = page();
    W.serverKpis.edit('k1');
    W.serverKpis.set('id', 'up2');
    W.serverKpis.applyEditor();
    replace(W, (b) => { b.asserts[1].id = 'up'; b.asserts[1].fail = { gt: 7 }; });
    same(rows(W).map((r) => [r[0], r[3]]), [['k0', false], ['k1', true], ['k2', false]]);
    assert.strictEqual(W.document.countOf('server'), 1);
});

test('pass 3 on the board: an assert moved on the board, retuned as its new sibling is deleted, keeps its key', () => {
    // On the board k0 is `lag` on b; in the file it is `lag` on a, and k2 is `lag` on b. Offered the
    // file's first, the moved assert took the deleted sibling's k2.
    const W = page();
    replace(W, (b) => { b.asserts[0].component = 'b'; });
    replace(W, (b) => { b.asserts = [Object.assign({}, b.asserts[0], { fail: { gt: 600 } }), b.asserts[1]]; });
    same(rows(W), [['k0', 'b', 'NOT_EVALUATED', true], ['k1', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 2, 'k0 edited, k2 deleted');
});

test('pass 3 on the file: both same-id asserts deleted, then re-added retuned in reverse, each on its own key', () => {
    // The board has no `lag` left, so only the file's component tells the two apart; by id alone
    // they swapped keys.
    const W = page();
    replace(W, (b) => { b.asserts = [b.asserts[1]]; });
    replace(W, (b) => {
        b.asserts = [b.asserts[0], Object.assign({}, LAG_B, { fail: { gt: 6000 } }), Object.assign({}, LAG_A, { fail: { gt: 600 } })];
    });
    same(rows(W), [['k1', 'b', 'PASS', false], ['k2', 'b', 'NOT_EVALUATED', true], ['k0', 'a', 'NOT_EVALUATED', true]]);
    assert.strictEqual(W.document.countOf('server'), 2);
});

test('pass 4 on the file: an assert deleted, then re-added moved and retuned, is its shipped row edited (guard)', () => {
    // The old id-only rule got this right; a pass 4 over the board alone gave it a new key, and the
    // count read 2 — one deletion and one addition — where one edit is what happened.
    const W = page();
    replace(W, (b) => { b.asserts = [b.asserts[0], b.asserts[2]]; });
    replace(W, (b) => { b.asserts.splice(1, 0, Object.assign({}, UP_B, { component: 'a', fail: { gt: 2 } })); });
    same(rows(W), [['k0', 'a', 'PASS', false], ['k1', 'a', 'NOT_EVALUATED', true], ['k2', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 1);
});

test('pass 4 on the board: an assert made here, then moved and retuned, keeps its key', () => {
    // The file never had it, so only the board's id finds it.
    const W = page();
    replace(W, (b) => { b.asserts.push({ id: 'x', component: 'a', query: 'x', reduce: { kind: 'max' }, fail: { gt: 1 } }); });
    const made = W.serverKpis._board()[3].key;
    assert.ok(/^n\d+$/.test(made), 'precondition: a row made here, keyed ' + made);
    replace(W, (b) => { b.asserts[3] = { id: 'x', component: 'b', query: 'x', reduce: { kind: 'max' }, fail: { gt: 2 } }; });
    assert.strictEqual(W.serverKpis._board()[3].key, made);
    assert.strictEqual(W.document.countOf('server'), 1, 'one assert added');
});

test('pass 4 on the board before the file: a renamed row moved and retuned keeps its key from a new assert with its old id', () => {
    // Pass 3 pairs neither: the new `up` is on a, the file's on b, and the renamed row left b. By id
    // alone, offered the file's `up` first, the new assert took k1 and the renamed row a new key.
    const W = page();
    W.serverKpis.edit('k1');
    W.serverKpis.set('id', 'up2');
    W.serverKpis.applyEditor();
    replace(W, (b) => {
        b.asserts[1] = Object.assign({}, b.asserts[1], { component: 'a', fail: { gt: 9 } });
        b.asserts.splice(1, 0, { id: 'up', component: 'a', query: 'up', reduce: { kind: 'max' }, fail: { gt: 3 } });
    });
    same(rows(W).map(keyed).map((r) => [r[0], r[1], r[3]]),
        [['k0', 'a', false], ['new', 'a', true], ['k1', 'a', true], ['k2', 'b', false]]);
});

test('pass 3 on the file before pass 4 on the board: a deleted assert re-added retuned is its own row, not a sibling\'s', () => {
    // k0 deleted; then k2 deleted and k0 re-added retuned in one apply. The board still offers k2 by
    // id, but the file has `lag` on a under k0 — and an id on its own component is the closer match.
    const W = page();
    replace(W, (b) => { b.asserts = [b.asserts[1], b.asserts[2]]; });
    replace(W, (b) => { b.asserts = [Object.assign({}, LAG_A, { fail: { gt: 600 } }), b.asserts[0]]; });
    same(rows(W), [['k0', 'a', 'NOT_EVALUATED', true], ['k1', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 2, 'k0 edited, k2 deleted');
});

test('pass 1 finishes before pass 2: a copy of a shipped assert written ahead of its edited row is the new one', () => {
    // The board's text is matched for every incoming assert before the file's is for any: the edited
    // row is the same assert the board has, the copy is one more. Checked per assert, board then
    // file, the copy took k0 and the build's PASS, and the edited row became the new one.
    const W = page();
    replace(W, (b) => { b.asserts[0].fail = { gt: 5000 }; });
    replace(W, (b) => { b.asserts.unshift(JSON.parse(JSON.stringify(LAG_A))); });
    same(rows(W).map(keyed), [['new', 'a', 'NOT_EVALUATED', true], ['k0', 'a', 'NOT_EVALUATED', true],
        ['k1', 'b', 'PASS', false], ['k2', 'b', 'PASS', false]]);
    assert.strictEqual(W.document.countOf('server'), 2);
});

test('a no-op apply re-keys nothing, edited rows included (guard: green before and after)', () => {
    // Every Server-tab write re-adopts the whole block, so a no-op apply has to be the identity on
    // keys — which the board's text, matched first, makes it. Before the file's: a new assert written
    // exactly as the file shipped an edited one has the file's text, and read in the other order it
    // took that row from the edited one.
    const W = page();
    replace(W, (b) => { b.asserts[0].fail = { gt: 5000 }; b.asserts[2].fail = { gt: 500 }; });
    replace(W, (b) => { b.asserts.push(JSON.parse(JSON.stringify(LAG_A))); });
    const keys = rows(W).map((r) => r[0]);
    same(keys.map((k) => (k.charAt(0) === 'n' ? 'new' : k)), ['k0', 'k1', 'k2', 'new'], 'precondition');
    replace(W, () => { });
    same(rows(W).map((r) => r[0]), keys);
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
        console.log('       ' + (e && e.message ? e.message.split('\n').slice(0, 6).join(' | ') : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
