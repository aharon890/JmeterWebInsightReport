/**
 * The Server tab's "live" marker (LIVE-MARKER), driven through the page's own lifecycle
 * (harness.loadReportPage) with no browser.
 *
 * Every row whose series reached the page uncapped is re-judged by the mirror, and every such row
 * said "live" under its chip — edited or not. On a page nobody had touched, every row of an
 * uncapped payload read "live": a word that told the reader nothing (the mirror answers to the
 * parity vectors, so an untouched row's verdict IS the build's) and suggested the verdict was
 * somehow provisional. It is said now only where the verdict can differ from the one the build was
 * gated on: the assert was edited, or what it is judged under moved — a client rule it names
 * (requiresLoad, divideBy), or the block around it (judgedFrameOf: the run-level requiresLoad, its
 * component, the environments).
 *
 * `live` itself, on _board(), keeps its meaning — "judged here, not borrowed from the build" —
 * which server-identity.spec.ts and save-from-page.js read. The marker is `markedLive`.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage, loadCatalogue } = require('./harness');

const A0 = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const SAMPLERS = [{ name: 'A', count: 600, p95: 50, mean: 30, throughput: 1 }];
const OVERALL = { totalSamples: 600, p95: 50, mean: 30, throughput: 1, duration: 600 };

/* A client load rule the server asserts can name. */
const CLIENT = [{ id: 'load', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 0.5 } }];

const ASSERTS = [
    { id: 'lag', component: 'db', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 500 } },
    { id: 'cpu', component: 'db', query: 'cpu', reduce: { kind: 'max' }, fail: { gt: 90 } },
    { id: 'lag-per-req', component: 'db', query: 'lag', reduce: { kind: 'max' }, divideBy: 'load',
        fail: { gt: 1000 } }
];

function points(value) {
    const out = [];
    for (let t = 0; t <= 600000; t += 15000) out.push([t, value]);
    return out;
}

function series(query, value) {
    return {
        source: 'metrics', component: 'db', query, resolvedQuery: query, status: 'OK', coverage: 1,
        instances: [{ labels: { pod: 'a' }, points: points(value) }]
    };
}

/** A step-3 page: every series collected uncapped, every assert judged by the build. */
function page() {
    const document = { assertions: CLIENT, serverMetrics: { components: { db: { type: 'custom' } }, asserts: ASSERTS } };
    const chartData = {
        runId: 'live-marker',
        annotationsDocument: JSON.parse(JSON.stringify(document)),
        samplers: SAMPLERS,
        overall: OVERALL,
        serverCatalogue: loadCatalogue(),
        serverEvaluated: true,
        serverFindings: [],
        serverMetrics: {
            schema: 'wir-server-metrics/1', runId: 'live-marker', collectionId: 'c0', collectedAt: '2026-10-10T00:00:00Z',
            window: { start: 0, end: 600000 },
            sources: [{ id: 'metrics', type: 'csv', status: 'OK', stepMillis: 15000, clockOffsetMs: 0 }],
            series: [series('lag', 300), series('cpu', 40)]
        }
    };
    const rules = CLIENT.map((r, i) => A0._ruleFromJson(r, i));
    rules.forEach((r) => { delete r.unreadable; });
    const evaluated = A0.evaluate(rules, chartData);
    chartData.assertions = plain(evaluated.rules);
    chartData.gate = plain(evaluated.gate);
    /* What Java judged: each assert's verdict, as the board would show it untouched. */
    chartData.serverKpis = ASSERTS.map((a) => ({ id: a.id, status: 'PASS' }));
    return loadReportPage(chartData);
}

const board = (W) => plain(W.serverKpis._board());
const rowOf = (W, id) => board(W).find((r) => r.id === id);
const marked = (W) => board(W).filter((r) => r.markedLive).map((r) => r.id);

test('precondition: every row is judged here (live) and passes', () => {
    const W = page();
    assert.deepStrictEqual(board(W).map((r) => [r.id, r.display, r.live]),
        [['lag', 'PASS', true], ['cpu', 'PASS', true], ['lag-per-req', 'PASS', true]]);
});

test('an untouched page marks no row "live" — its verdicts are the build\'s', () => {
    const W = page();
    assert.deepStrictEqual(marked(W), []);
    board(W).forEach((r) => {
        const html = W.serverKpis._rowHtml(r.key);
        assert.ok(html.indexOf('wir-live') < 0, r.id + ' says live: ' + html.slice(0, 300));
    });
});

test('an edited assert is marked "live", and only it', () => {
    const W = page();
    const S = W.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('fail.value', '200');
    S.applyEditor();
    assert.strictEqual(rowOf(W, 'lag').display, 'FAIL', 'precondition: the edit re-decided the row');
    assert.deepStrictEqual(marked(W), ['lag']);
    assert.ok(S._rowHtml(S._keyOf('lag')).indexOf('<div class="wir-dim wir-live">live</div>') >= 0);
    assert.ok(S._rowHtml(S._keyOf('cpu')).indexOf('wir-live') < 0);
});

test('an edit put back is no edit — the marker goes with it', () => {
    const W = page();
    const S = W.serverKpis;
    S.edit(S._keyOf('lag'));
    S.set('fail.value', '200');
    S.applyEditor();
    S.edit(S._keyOf('lag'));
    S.set('fail.value', '500');
    S.applyEditor();
    assert.deepStrictEqual(marked(W), []);
});

test('a client rule the assert names, retuned, marks that assert "live" — and no other', () => {
    const W = page();
    W.asserts._retune('load', 0.25, 'fail');
    assert.deepStrictEqual(marked(W), ['lag-per-req']);
});

test('the run-level requiresLoad, set in the block, marks every assert it now stands on', () => {
    const W = page();
    const doc = JSON.parse(W.document.text());
    doc.serverMetrics.requiresLoad = 'load';
    assert.strictEqual(W.document.replace(doc, W.document.source()), null);
    W.serverKpis.rerender();
    assert.deepStrictEqual(marked(W), ['lag', 'cpu', 'lag-per-req']);
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
