/**
 * The Server tab's findings pane, for a COMPONENT whose type the mapper refuses — driven through
 * the page's own lifecycle (harness.loadReportPage) and read through WIR.serverKpis._findings(),
 * what findingsHtml renders.
 *
 * ServerMetricsMapper files an unknown component type twice: once under
 * serverMetrics.components.<id> (the component's own sentence), and once per ASSERT naming it,
 * under the assert's label — "component 'x' has type 'y', which is not one of …". The pane answers
 * both live for the block the page holds: a type fixed in the page must not leave N stale
 * per-assert refusals beside rows now judged live, and one broken in the page must raise every
 * per-assert finding Java would. (Before C8 this pinned the same for an unusable SOURCE.)
 */
'use strict';

const assert = require('assert');
const { loadReportPage, loadCatalogue } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const VOCABULARY = loadCatalogue().order.concat(['custom']).join(', ');

/** The pane's findings, copied into this realm: the page runs in its own vm context, and
    deepStrictEqual refuses an Array from another realm however equal its contents. */
function findingsOf(WIR) {
    return JSON.parse(JSON.stringify(WIR.serverKpis._findings()));
}

/** A page generated from `block`, carrying the findings the build baked. */
function page(block, bakedFindings) {
    return loadReportPage({
        runId: 'server-findings',
        annotationsDocument: { serverMetrics: block },
        serverFindings: bakedFindings || [],
        serverCatalogue: loadCatalogue(),
        samplers: [],
        overall: { totalSamples: 0 }
    });
}

const ASSERTS = [
    { id: 'named', component: 'db', query: 'up', reduce: { kind: 'max' }, fail: { gt: 1 } },
    { component: 'db', query: 'lag', reduce: { kind: 'max' }, fail: { gt: 1 } },
    { id: 'elsewhere', component: 'other', query: 'up', reduce: { kind: 'max' }, fail: { gt: 1 } }
];

const PER_ASSERT = "component 'db' has type 'nosuchdb', which is not one of " + VOCABULARY;

test('a component type broken in the file raises one finding per assert on it, under the label Java uses', () => {
    const WIR = page({
        components: { db: { type: 'nosuchdb' }, other: { type: 'custom' } },
        asserts: ASSERTS
    });
    const perAssert = findingsOf(WIR).filter((f) => /has type '/.test(f.message));
    assert.deepStrictEqual(perAssert.map((f) => [f.severity, f.ruleId, f.message]), [
        ['error', 'named', PER_ASSERT],
        // An id-less assert is labelled by its position, as ServerMetricsMapper.readKpi labels it.
        ['error', 'serverMetrics.asserts[1]', PER_ASSERT]
    ]);
    const byComponent = findingsOf(WIR).filter((f) => f.ruleId === 'serverMetrics.components.db');
    assert.deepStrictEqual(byComponent.map((f) => f.message), ["type 'nosuchdb' is not one of " + VOCABULARY]);
});

test('a baked per-assert refusal goes once the component the page holds has a known type', () => {
    // The build judged a file whose `db` was 'nosuchdb'; the page now holds one where it is custom.
    const WIR = page({
        components: { db: { type: 'custom' }, other: { type: 'custom' } },
        asserts: ASSERTS
    }, [
        { severity: 'error', ruleId: 'serverMetrics.components.db', message: "type 'nosuchdb' is not one of " + VOCABULARY },
        { severity: 'error', ruleId: 'named', message: PER_ASSERT },
        { severity: 'error', ruleId: 'serverMetrics.asserts[1]', message: PER_ASSERT },
        { severity: 'error', ruleId: 'elsewhere', message: "reduce.kind 'maxx' is not a reducer" }
    ]);
    /* SF18: EVERY per-assert finding is answered for the block the page holds — `elsewhere` reads
       max here, so the build's 'maxx' sentence about it goes too. It used to stay ("the build's other
       findings stay"), which is how a reducer fixed in the editor kept its refusal above a row judged
       FAIL live. Moved deliberately. */
    assert.deepStrictEqual(findingsOf(WIR), [], 'every finding is answered for the block the page holds');
});

// ---------------------------------------------------------------- SF18: the list is live

const COMPONENTS = { db: { type: 'custom' } };

test('SF18: a problem the page holds and the build never saw is said, in the mapper\'s words', () => {
    // An imported band with a typo'd operator: INVALID on the board, and nothing above it said so.
    const WIR = page({
        components: COMPONENTS,
        asserts: [{ id: 'imported-bad', component: 'db', query: 'lag', reduce: { kind: 'max' }, fail: { gtt: 9 } }]
    });
    assert.deepStrictEqual(findingsOf(WIR), [{ severity: 'error', ruleId: 'imported-bad',
        message: "fail operator 'gtt' is not one of gt, gte, lt, lte — so this server assert declares a limit it "
            + 'cannot apply, and would report PASS without ever testing fail' }]);
});

test('SF18: a duplicate of a refused assert is refused under its own id, once', () => {
    const typo = { component: 'db', query: 'lag', reduce: { kind: 'maxx' }, fail: { gt: 1 } };
    const WIR = page({
        components: COMPONENTS,
        asserts: [Object.assign({ id: 'typo-reducer' }, typo), Object.assign({ id: 'typo-reducer-2' }, typo)]
    }, [{ severity: 'error', ruleId: 'typo-reducer',
        message: "reduce.kind 'maxx' is not a reducer — valid here: max, min, avg, last, slope, pN (e.g. p95)" }]);
    assert.deepStrictEqual(findingsOf(WIR).map((f) => f.ruleId), ['typo-reducer', 'typo-reducer-2'],
        'one finding per assert: the mapper\'s, and the run-time refusal of the same sentence is not a second');
});

test('SF18: a warn the fail band covers is a WARNING under the assert\'s id, after the mapper\'s', () => {
    const WIR = page({
        components: COMPONENTS,
        asserts: [
            { id: 'covered', component: 'db', query: 'cpu', reduce: { kind: 'max' }, warn: { gt: 99 }, fail: { gt: 95 } },
            { component: 'db', query: 'up', reduce: { kind: 'max' }, fail: { gtt: 1 } }
        ]
    });
    assert.deepStrictEqual(findingsOf(WIR).map((f) => [f.severity, f.ruleId]), [
        ['error', 'serverMetrics.asserts[1]'],          // the mapper's, under the position
        ['warning', 'covered']                          // then KpiUsability's, under the id
    ]);
    assert.strictEqual(findingsOf(WIR)[1].message,
        'warn can never fire: fail > 95 is tested first and already covers warn > 99. Swapped values?');
});

test('SF18: a refusal only the run can make — a requiresLoad naming no client rule — comes last', () => {
    const WIR = page({
        components: COMPONENTS,
        asserts: [
            { id: 'needs-load', component: 'db', query: 'cpu', reduce: { kind: 'max' }, fail: { gt: 9 },
                requiresLoad: 'load:nope' },
            { id: 'no-bands', component: 'db', query: 'cpu', reduce: { kind: 'max' } }
        ]
    });
    const listed = findingsOf(WIR);
    assert.deepStrictEqual(listed.map((f) => f.ruleId), ['no-bands', 'needs-load'],
        'KpiUsability\'s phase before the run-time refusals, whatever the order of the asserts');
    assert.strictEqual(listed[0].message, 'no warn or fail band — the server assert can never decide anything');
    assert.ok(/load:nope/.test(listed[1].message), listed[1].message);
});

test('SF18: what the page cannot re-check is the build\'s, kept in the phase Java files it in', () => {
    const WIR = page({
        components: COMPONENTS,
        environments: { prod: { vars: { x: null } } },
        asserts: [{ id: 'typo', component: 'db', query: 'lag', reduce: { kind: 'maxx' }, fail: { gt: 1 } }]
    }, [
        { severity: 'error', ruleId: 'typo', message: 'a baked sentence the page answers for itself' },
        { severity: 'warning', ruleId: 'serverMetrics.payload', message: 'the payload finding' },
        { severity: 'warning', ruleId: 'serverMetrics.collection', message: 'the payload was collected with a ramp-up of 0 ms' },
        { severity: 'warning', ruleId: 'serverMetrics.environments.prod', message: 'vars.x is null and was ignored' }
    ]);
    assert.deepStrictEqual(findingsOf(WIR).map((f) => [f.ruleId, f.message]), [
        // the mapper's block-level findings come before any assert's
        ['serverMetrics.environments.prod', 'vars.x is null and was ignored'],
        ['typo', "reduce.kind 'maxx' is not a reducer — valid here: max, min, avg, last, slope, pN (e.g. p95)"],
        // the ramp-up, then the payload, after KpiUsability's
        ['serverMetrics.collection', 'the payload was collected with a ramp-up of 0 ms'],
        ['serverMetrics.payload', 'the payload finding']
    ]);
    assert.ok(findingsOf(WIR).every((f) => !f.asGenerated), 'serverMetrics.collection has not changed');
});

test('SF18 review: the environments are the build\'s while the draft holds the ones it read, and read again once they change', () => {
    // The shipped document carries a credential var masked (ServerMetricsJson.publishable):
    // `bob:pw@prom.corp` ships as `***@prom.corp`, which the mapper's check no longer reads as one.
    // The build read the clear value, so its sentence stands while the environments are as shipped.
    const CRED = 'vars.url carries credentials in a URL (user:password@…) — environments are written into every '
        + 'report and sidecar, so a literal here would be published. Put the credential in auth.<name>Env, or in '
        + 'the source url as ${secret.NAME}';
    const WIR = page({
        components: COMPONENTS,
        environments: { prod: { vars: { url: '***@prom.corp' } } },
        defaultEnv: 'prod',
        asserts: [{ id: 'cpu', component: 'db', query: 'cpu', reduce: { kind: 'max' }, fail: { gt: 90 } }]
    }, [{ severity: 'error', ruleId: 'serverMetrics.environments.prod', message: CRED }]);
    assert.deepStrictEqual(findingsOf(WIR).map((f) => [f.severity, f.ruleId, f.message]),
        [['error', 'serverMetrics.environments.prod', CRED]]);

    // Changed, they are read again, as the next run reads the saved file — whose value is the mask.
    const doc = JSON.parse(WIR.document.text());
    doc.serverMetrics.environments.prod.vars.ns = null;
    doc.serverMetrics.environments.stage = { vars: { push: 'https://push.corp/api?token=abc' } };
    assert.strictEqual(WIR.document.replace(doc, WIR.document.source()), null, 'the document was refused');
    assert.deepStrictEqual(findingsOf(WIR).map((f) => [f.severity, f.ruleId, f.message.slice(0, 50)]), [
        ['warning', 'serverMetrics.environments.prod', 'vars.ns is null and was ignored'],
        ['error', 'serverMetrics.environments.stage', "vars.push carries a credential in the URL paramete"]
    ]);
});

test('SF18 review: a baked ramp-up finding is marked as generated once serverMetrics.collection has changed', () => {
    const block = {
        components: COMPONENTS,
        collection: { excludeRampUp: true },
        asserts: [{ id: 'cpu', component: 'db', query: 'cpu', reduce: { kind: 'max' }, fail: { gt: 90 } }]
    };
    const RAMP = 'the payload was collected with a ramp-up of 0 ms';
    const WIR = page(block, [{ severity: 'warning', ruleId: 'serverMetrics.collection', message: RAMP }]);
    assert.deepStrictEqual(findingsOf(WIR), [{ severity: 'warning', ruleId: 'serverMetrics.collection', message: RAMP }]);

    const doc = JSON.parse(WIR.document.text());
    doc.serverMetrics.collection = { excludeRampUp: false };
    assert.strictEqual(WIR.document.replace(doc, WIR.document.source()), null, 'the document was refused');
    const after = findingsOf(WIR);
    assert.strictEqual(after.length, 1);
    assert.strictEqual(after[0].message, RAMP, 'the build\'s sentence is kept — the page cannot judge a ramp-up');
    assert.strictEqual(after[0].asGenerated, true, 'it is about a serverMetrics.collection the draft has changed');
});

test('SF18 review: a defaultEnv the draft points at no declared environment is said, though the build never saw it', () => {
    const WIR = page({
        components: COMPONENTS,
        environments: { prod: { vars: { ns: 'shop' } } },
        defaultEnv: 'prod',
        asserts: [{ id: 'cpu', component: 'db', query: 'cpu', reduce: { kind: 'max' }, fail: { gt: 90 } }]
    });
    assert.deepStrictEqual(findingsOf(WIR), []);
    const doc = JSON.parse(WIR.document.text());
    doc.serverMetrics.defaultEnv = 'staging';
    doc.serverMetrics.collection = { coverageFloor: 2 };
    assert.strictEqual(WIR.document.replace(doc, WIR.document.source()), null, 'the document was refused');
    assert.deepStrictEqual(findingsOf(WIR).map((f) => [f.severity, f.ruleId, f.message]), [
        ['error', 'serverMetrics.defaultEnv', "'staging' is not one of the declared environments: prod"],
        ['warning', 'serverMetrics.collection', 'coverageFloor must be a fraction 0..1, not the number 2 — using the default 0.9']
    ]);
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
