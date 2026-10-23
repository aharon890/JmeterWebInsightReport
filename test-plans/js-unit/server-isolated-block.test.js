/**
 * A serverMetrics block whose shape does not bind reaches the page, and the page still starts.
 *
 * AnnotationLoader isolates such a block (`"asserts": {}`) from the client half: the client rules
 * load, the block is refused as one block problem, and its keys ride ServerMetricsJson.unreadable
 * verbatim, so GatePayloadBuilder.documentOf ships it as annotationsDocument.serverMetrics. The
 * Server tab's readDeclared used to parse it like any other block, `{}.map` threw inside
 * WIR.serverKpis.init, and the page stopped before WIR.document.start(): no Server tab, no drafts,
 * no document lifecycle for the Asserts tab either (PR #19 review F1).
 *
 * It is read now as the restore and replace paths read it (blockShapeProblem): as declaring
 * nothing, with the block itself kept, so a Download writes it back as the file had it.
 */
'use strict';

const assert = require('assert');
const { loadReportPage, loadCatalogue } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

/* AnnotationLoaderTest's case: one client rule beside a block whose asserts is an object. */
const CLIENT_RULE = { id: 'p95', metric: 'p95', fail: { gt: 1000 } };
const BLOCK = { components: { db: { type: 'custom' } }, asserts: {} };

function page(block) {
    return loadReportPage({
        runId: 'server-isolated-block',
        annotationsDocument: plain({ version: '1.0', assertions: [CLIENT_RULE], serverMetrics: block }),
        samplers: [],
        overall: { totalSamples: 0 },
        serverCatalogue: loadCatalogue()
    });
}

test('F1: init and the document lifecycle survive a block whose asserts is an object', () => {
    let WIR;
    assert.doesNotThrow(() => { WIR = page(BLOCK); });
    assert.strictEqual(WIR.serverKpis._state.declaredKpis.length, 0,
        'a block that does not bind declares no server assert');
});

test('F1: the block is kept, so a Download writes it back as the file had it', () => {
    const WIR = page(BLOCK);
    assert.deepStrictEqual(plain(WIR.serverKpis._state.block), BLOCK);
    assert.deepStrictEqual(JSON.parse(WIR.document.text()).serverMetrics, BLOCK);
});

test('F1: a list of asserts beside a malformed part of the block is written back, not emptied', () => {
    const block = {
        components: 'db',
        asserts: [{ id: 'lag', component: 'db', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 5 } }]
    };
    const WIR = page(block);
    assert.strictEqual(WIR.serverKpis._state.declaredKpis.length, 0);
    assert.deepStrictEqual(JSON.parse(WIR.document.text()).serverMetrics, block);
});

test('F1: Discard drafts after a client edit re-reads the block as declaring nothing, and draws', () => {
    // discardDrafts drops the drafts and then re-reads the loaded document's server half; that
    // re-read parsed the isolated block and threw after the drafts were already gone.
    const WIR = page(BLOCK);
    WIR.asserts._retune('p95', 500);
    assert.doesNotThrow(() => WIR.asserts.discardDrafts());
    assert.doesNotThrow(() => WIR.serverKpis.reread());
    assert.strictEqual(WIR.serverKpis._state.declaredKpis.length, 0);
    assert.deepStrictEqual(JSON.parse(WIR.document.text()).serverMetrics, BLOCK);
    // So does a block whose assert entry is null.
    const nullEntry = { components: { db: { type: 'custom' } }, asserts: [null] };
    const W2 = page(nullEntry);
    assert.doesNotThrow(() => W2.serverKpis.reread());
});

test('F1: after a Discard the mirror judges with the settings init read, not the unreadable block\'s', () => {
    // Init reads such a block's collection settings as the defaults (nothing in it is judged); the
    // re-read took them from the block, so a Discard moved the window and the floor the mirror used.
    const block = { components: { db: { type: 'custom' } },
        collection: { excludeRampUp: false, coverageFloor: 0.5 }, asserts: {} };
    const WIR = page(block);
    const atInit = plain(WIR.serverKpis._state.config);
    WIR.asserts._retune('p95', 500);
    WIR.asserts.discardDrafts();
    assert.deepStrictEqual(plain(WIR.serverKpis._state.config), atInit);
    assert.notStrictEqual(atInit.coverageFloor, 0.5);
});

test('F1: the refusal of such a block says what the plugin does with it', () => {
    const WIR = page(BLOCK);
    assert.strictEqual(WIR.serverKpis._blockShapeProblem(BLOCK),
        '"serverMetrics.asserts" must be a list of server assert objects — otherwise the plugin judges'
        + ' nothing in the serverMetrics block.');
});

test('F1: a block that binds is still read', () => {
    const WIR = page({
        components: { db: { type: 'custom' } },
        asserts: [{ id: 'lag', component: 'db', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 5 } }]
    });
    assert.strictEqual(WIR.serverKpis._state.declaredKpis.length, 1);
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
