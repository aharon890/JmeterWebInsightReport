/**
 * The SERVER parity vectors, driven through the production browser mirror, in node.
 *
 * ServerKpiEvaluator judges a server KPI in Java; WIR.serverKpis judges it again in the browser
 * so a threshold can be dragged and the verdict recompute as you type. Two implementations of
 * one rule set drift between the runs that test them, so the definition sits in neither:
 * server-parity-vectors.json holds it, ServerParityTest runs it in Java, this runs it in node,
 * and server-parity.spec.ts runs it against a real generated report. A case only one side
 * passes is exactly the bug the file exists to catch.
 *
 * This tier is the cheap one — no browser, no fixture, no build, a tenth of a second — and it
 * is the one CI runs, because `run_js_unit_tests` loops every *.test.js in this directory. It
 * drives the PRODUCTION parsers (`kpisFromDocument`, not a hand-written helper), because the
 * client side already learned that pinning two evaluators against each other while both suites
 * build the rule shape with a test helper leaves the two PARSERS compared by nothing at all.
 *
 * It must not be weaker than ServerParityTest. Both of that suite's coverage tests are ported
 * below, and a floor guard stops an emptied or renamed `cases` array passing in silence.
 */
'use strict';

const assert = require('assert');
const path = require('path');
const { loadReportJs } = require('./harness');

const VECTORS = require(path.resolve(__dirname,
    '../../jmeter-report-core/src/test/resources/servermetrics/server-parity-vectors.json'));

const WIR = loadReportJs();
const A = WIR.asserts;
const SK = WIR.serverKpis;

if (!SK || !SK.evaluate) {
    throw new Error('report.js exposed no WIR.serverKpis.evaluate — the harness is stale');
}

/* ---- the client gate the server KPIs resolve requiresLoad/divideBy against ---- */

/* The page's shape, built here: ReportDataSerializer writes most sampler figures at 2dp for the
   tables and charts, and every one round2 changed again, exact, under `exact`, which the client
   evaluator lays over them (R2-1, both halves). Throughput is exact in place. This tier builds its
   own payload, so it cannot see the serializer round anything — ReportDataSerializerTest pins what
   the page carries, and server-parity.spec.ts that E26's throughput is exact. What it does pin is
   that the evaluator judges the exact figures it is handed: 'load:edge' FAILs on 12.496 here only
   because the overlay is read. */
const round2 = (x) => Math.round(x * 100) / 100;

/** A figure as the page carries it: 2dp in place, and the exact value beside it when they differ. */
function carried(target, exact, key, value) {
    target[key] = round2(value);
    if (round2(value) !== value) exact[key] = value;
}

/** The vector file's sampler shape → the payload shape the client evaluator reads. */
function clientPayload(v) {
    const samplers = v.samplers.map((s) => {
        const out = { name: s.name, count: s.sampleCount, errorCount: s.errorCount,
            throughput: s.throughput, apdex: s.apdex };
        const exact = {};
        ['errorRate', 'mean', 'median', 'p90', 'p95', 'p99', 'min', 'max', 'stdDev', 'meanLatency',
            'meanConnectTime', 'receivedBytesPerSec', 'sentBytesPerSec']
            .forEach((k) => carried(out, exact, k, s[k]));
        if (Object.keys(exact).length) out.exact = exact;
        return out;
    });
    const totalSamples = v.samplers.reduce((n, s) => n + s.sampleCount, 0);
    const totalErrors = v.samplers.reduce((n, s) => n + s.errorCount, 0);
    const durationSeconds = v.metadata.durationMillis / 1000;
    return {
        samplers,
        errorSummaries: [],
        overall: {
            totalSamples,
            errorRate: totalSamples ? (totalErrors * 100) / totalSamples : 0,
            duration: durationSeconds,
            maxThreads: v.metadata.totalThreads,
            mean: totalSamples
                ? v.samplers.reduce((n, s) => n + s.mean * s.sampleCount, 0) / totalSamples : 0,
            throughput: durationSeconds > 0 ? totalSamples / durationSeconds : 0,
            apdex: totalSamples
                ? Math.round((v.samplers.reduce((n, s) => n + s.apdex * s.sampleCount, 0)
                    / totalSamples) * 100) / 100 : 0,
            median: 0, p90: 0, p95: 0, p99: 0
        },
        assertPercentiles: {}
    };
}

// Live client results, not the baked ones: uncapped, full precision, and re-decided whenever a
// client rule is retuned — which is the whole promise a mirror exists to keep.
const clientRules = VECTORS.clientRules.map((r, i) => A._ruleFromJson(r, i));
const clientResults = A.evaluate(clientRules, clientPayload(VECTORS)).rules;

/** The block a case is judged under: the file's, plus that case's own overrides. */
function configFor(c) {
    const block = {
        collection: {
            coverageFloor: VECTORS.block.coverageFloor,
            excludeRampUp: VECTORS.block.excludeRampUp
        }
    };
    if (c && c.config) {
        if (c.config.excludeRampUp !== undefined) block.collection.excludeRampUp = c.config.excludeRampUp;
        if (c.config.coverageFloor !== undefined) block.collection.coverageFloor = c.config.coverageFloor;
        if (c.config.requiresLoad !== undefined) block.requiresLoad = c.config.requiresLoad;
    }
    return SK.configFromDocument(block);
}

/** The block's declared components — the file's, with a case's own merged over them by id. The
    same object ServerParityTest declares. */
function componentsFor(c) {
    return Object.assign(JSON.parse(JSON.stringify(VECTORS.components)),
        c && c.components ? JSON.parse(JSON.stringify(c.components)) : {});
}

/** One KPI, through the production parser — never a hand-written shape. */
function parseKpi(json, c) {
    return SK.kpisFromDocument({ components: componentsFor(c), asserts: [json] })[0];
}

let failed = 0;
let ran = 0;
let gates = 0;

function near(actual, expected, tolerance, what) {
    assert.ok(Math.abs(actual - expected) <= tolerance,
        `${what}: expected ${expected}, got ${actual}`);
}

/* ---- every case ---------------------------------------------------------- */

for (const c of VECTORS.cases) {
    ran++;
    const label = c.kpi.id || c.name;
    try {
        const kpi = parseKpi(c.kpi, c);
        const result = SK.evaluate([kpi], configFor(c), VECTORS.payload, clientResults)[0];
        checkResult(result, c.expect);
        console.log('  ok   ' + label);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + label);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}

/* ---- every document case: several KPIs in one block ----------------------- */
//
// Two KPIs sharing an id, written twice or derived twice from one query. Java accepts both and
// judges each on its own, in order — the board used to key verdicts by id, so the second
// overwrote the first.

/* A document case's block: the file's components, its asserts, and the case's own block keys
   (environments, defaultEnv, collection) — what ServerParityTest sets over the same document. */
function documentBlock(d) {
    return Object.assign({ components: componentsFor(d), asserts: d.asserts },
        d.block ? JSON.parse(JSON.stringify(d.block)) : {});
}

let documents = 0;
let documentFindingCases = 0;
for (const d of (VECTORS.documentCases || [])) {
    documents++;
    try {
        const kpis = SK.kpisFromDocument(documentBlock(d));
        const results = SK.evaluate(kpis, configFor(null), VECTORS.payload, clientResults);
        assert.strictEqual(results.length, d.expect.length, 'one result per declared KPI');
        d.expect.forEach((expect, i) => checkResult(results[i], expect));
        /* What only a set raises: a shared id is a WARNING (R2-2/R3-8), then each assert's own,
           as Java orders its WARNING findings - the mapper's, then KpiUsability's per assert. */
        if (d.warnings !== undefined) {
            const raised = SK.duplicateIdFindings(kpis).map((f) => ({ id: f.ruleId, message: f.message }));
            kpis.forEach((k) => SK._kpiWarnings(k).forEach((m) => raised.push({ id: k.id, message: m })));
            assert.deepStrictEqual(JSON.parse(JSON.stringify(raised)), d.warnings,
                'the WARNING findings of the document');
        }
        /* EVERY finding, in Java's phases and under Java's ruleIds (SF18), each mistake once
           (SF21): the live findings list the Server tab draws, through the PRODUCTION builder,
           over the verdicts the mirror just judged — what ServerGateEvaluation.findings() is for
           the same file, and what the CI/CD JSON's findings[] carries. */
        if (d.findings !== undefined) {
            assert.ok(typeof SK.documentFindings === 'function', 'report.js exposes no WIR.serverKpis.documentFindings');
            documentFindingCases++;
            const listed = SK.documentFindings(documentBlock(d), kpis, results, [], null).map((f) => ({
                severity: String(f.severity).toUpperCase(), id: f.ruleId, message: f.message }));
            assert.deepStrictEqual(JSON.parse(JSON.stringify(listed)), d.findings, 'the findings of the document, in order');
        }
        console.log('  ok   document: ' + d.name);
    } catch (e) {
        failed++;
        console.log('  FAIL document: ' + d.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
/* The phase-order, one-finding-per-mistake and block-frame cases must all have run here. */
if (documentFindingCases < 4) {
    failed++;
    console.log('  FAIL only ' + documentFindingCases + ' document case(s) carry findings - the SF18/SF21 cases are gone');
}

/* ---- every block case: what a BLOCK says beside its asserts (D25, D26) ------- */
//
// A leftover `kpis` key and a `packs` key are config-level ERRORs the mapper records under
// serverMetrics, and the asserts beside them still map. blockProblems is the mirror of that list —
// the page shows it for the document it now holds, so an imported file that still says `kpis` is
// refused on screen, not first by the next run. Neither is a whole-file shape error: Jackson takes
// any value under an unknown key, so the document check must let both through.

let blocks = 0;
for (const b of (VECTORS.blockCases || [])) {
    blocks++;
    try {
        const block = { components: componentsFor(null) };
        Object.keys(b.block).forEach((k) => { block[k] = b.block[k]; });
        assert.ok(typeof SK.blockProblems === 'function', 'report.js exposes no WIR.serverKpis.blockProblems');
        assert.deepStrictEqual(JSON.parse(JSON.stringify(SK.blockProblems(block))), b.expect.problems,
            'the block\'s config-level problems');
        assert.deepStrictEqual(JSON.parse(JSON.stringify(SK.kpisFromDocument(block).map((k) => k.id))),
            b.expect.ids, 'the asserts that still map');
        // The components' own refusals, filed under serverMetrics.components.<id> on both sides.
        assert.deepStrictEqual(JSON.parse(JSON.stringify(SK.componentProblems(block))),
            b.expect.componentProblems || [], 'the config-level problems of the components');
        assert.strictEqual(SK._blockShapeProblem(block), null, 'a retired block key is not a whole-file refusal');
        console.log('  ok   block: ' + b.name);
    } catch (e) {
        failed++;
        console.log('  FAIL block: ' + b.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}

/** One judged KPI against what the vector expects of it — the single and the document cases. */
function checkResult(result, expect) {
    /* The id a KPI is judged under: the derived one when the file names none. It names the row,
       the editor's target and every finding, so "" for every id-less KPI collapsed them into one
       row on the board. */
    if (expect.id !== undefined) {
        assert.strictEqual(result.id, expect.id, 'the id this KPI is judged under');
        assert.strictEqual(result.kpi.id, expect.id, 'the parsed KPI carries the same id');
    }

    assert.strictEqual(result.status, expect.status,
        `status${result.notJudgedDetail ? ' (' + result.notJudgedDetail + ')' : ''}`);

    if (expect.notJudged !== undefined) {
        assert.strictEqual(result.notJudged, expect.notJudged, 'not-judged reason');
    } else {
        assert.strictEqual(result.notJudged, null, 'a judged KPI must carry no reason');
    }

    if (expect.actual !== undefined) {
        near(result.actual, expect.actual, 1e-6, 'actual');
    } else if (expect.notJudged !== undefined) {
        assert.ok(isNaN(result.actual), 'a not-judged KPI has no number');
    }

    if (expect.reason !== undefined) {
        assert.ok(result.notJudgedDetail, 'no detail given');
        assert.ok(result.notJudgedDetail.indexOf(expect.reason) >= 0,
            `detail was: ${result.notJudgedDetail}`);
    }
    /* The whole sentence, where every character is the contract - the numbers in it above all. */
    if (expect.detail !== undefined) {
        assert.strictEqual(result.notJudgedDetail, expect.detail, 'detail');
    }

    if (expect.breached !== undefined) {
        const op = Object.keys(expect.breached)[0];
        assert.ok(result.breached, 'no band fired');
        assert.strictEqual(result.breached.op, op, 'band operator');
        near(result.breached.value, expect.breached[op], 1e-9, 'band value');
    } else if (result.status === 'PASS') {
        assert.strictEqual(result.breached, null, 'a PASS names no band');
    }

    if (expect.instances !== undefined) {
        assert.strictEqual(result.instances, expect.instances, 'instances');
    }
    if (expect.coverage !== undefined) {
        near(result.coverage, expect.coverage, 1e-9, 'coverage');
    }
    /* KpiUsability's WARNINGs, which the editor and a drag show (C-11). A warning decides
       nothing, so only its sentence can drift — the list is compared whole, order included. */
    if (expect.warnings !== undefined) {
        // Through JSON: an array made in report.js's vm realm is never deepStrictEqual to one made
        // here, whatever it holds.
        assert.deepStrictEqual(JSON.parse(JSON.stringify(SK._kpiWarnings(result.kpi))),
            expect.warnings, 'WARNING sentences');
    }
}

/* ---- every gate case ----------------------------------------------------- */

const caseById = {};
VECTORS.cases.forEach((c) => { if (c.kpi.id !== undefined) caseById[c.kpi.id] = c; });

for (const g of (VECTORS.gateCases || [])) {
    gates++;
    try {
        // `kpiIds`, not `ruleIds` — the client file's key. Copying that loop unchanged gives an
        // empty list and a test that passes while asserting nothing.
        const kpis = g.kpiIds.map((id) => {
            assert.ok(caseById[id], `gate case names an unknown KPI id: ${id}`);
            assert.ok(!caseById[id].config,
                `a gate case cannot combine cases with their own config: ${id}`);
            assert.ok(!caseById[id].components,
                `a gate case cannot combine cases with their own components: ${id}`);
            return parseKpi(caseById[id].kpi, null);
        });
        const results = SK.evaluate(kpis, configFor(null), VECTORS.payload, clientResults);

        // Folded by the PRODUCTION fold — WIR.asserts.gateOf, which the Server tab's gate strip
        // folds its live rows through for the would-be verdict (R3-5) — not by one written here.
        // A fold the test owns pins the test's arithmetic against GateSummary, and the page's
        // against nothing.
        const folded = JSON.parse(JSON.stringify(A.gateOf(results)));
        const reasons = {};
        Object.keys(folded.notJudged).forEach((k) => {
            if (k !== 'total' && folded.notJudged[k]) reasons[k] = folded.notJudged[k];
        });

        // A gate of nothing but NOT_JUDGED KPIs has decided nothing. Reporting PASS would be
        // exactly the lie the engine exists to stop.
        assert.strictEqual(folded.status, g.expect.status === null ? null : g.expect.status, 'gate status');
        assert.strictEqual(folded.declared, g.expect.declared, 'declared');
        assert.strictEqual(folded.evaluated, g.expect.evaluated, 'evaluated');
        assert.strictEqual(folded.passed, g.expect.passed, 'passed');
        assert.strictEqual(folded.warned, g.expect.warned, 'warned');
        assert.strictEqual(folded.failed, g.expect.failed, 'failed');
        assert.strictEqual(folded.notJudged.total, g.expect.notJudged.total, 'notJudged total');
        Object.keys(g.expect.notJudged).forEach((k) => {
            assert.strictEqual(folded.notJudged[k], g.expect.notJudged[k], `reason count ${k}`);
        });

        // Both closed arithmetics, exactly as ServerParityTest asserts them.
        assert.strictEqual(
            g.expect.passed + g.expect.warned + g.expect.failed + g.expect.notJudged.total,
            g.expect.declared, 'declared == passed + warned + failed + notJudged');
        let summed = 0;
        Object.keys(g.expect.notJudged).forEach((k) => {
            if (k !== 'total') summed += g.expect.notJudged[k];
        });
        assert.strictEqual(summed, g.expect.notJudged.total, 'notJudged == sum of its reasons');

        Object.keys(reasons).forEach((k) => {
            assert.strictEqual(reasons[k], g.expect.notJudged[k] || 0, `reason count ${k}`);
        });
        console.log('  ok   gate: ' + g.name);
    } catch (e) {
        failed++;
        console.log('  FAIL gate: ' + g.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}

/* ---- coverage of the vector file itself ---------------------------------- */
//
// Ported from ServerParityTest, and for its stated reason: coverage is what rots. The three
// server-only reasons in particular have no other vector file to live in — the client file
// deliberately refuses them.

try {
    const statuses = new Set();
    const reasons = new Set();
    const kinds = new Set();
    const ops = new Set();
    let sustained = false, budget = false, divide = false, requires = false, avgAcross = false;

    for (const c of VECTORS.cases) {
        statuses.add(c.expect.status);
        if (c.expect.notJudged) reasons.add(c.expect.notJudged);
        ['warn', 'fail'].forEach((band) => {
            if (c.kpi[band]) ops.add(Object.keys(c.kpi[band])[0]);
        });
        if (c.kpi.reduce) {
            const kind = String(c.kpi.reduce.kind || '');
            kinds.add(kind.charAt(0) === 'p' && kind !== 'p' ? 'p' : kind);
            sustained = sustained || !!(c.kpi.reduce.over && c.kpi.reduce.over.sustainedFor);
            budget = budget || !!(c.kpi.reduce.over && c.kpi.reduce.over.breachBudget);
        }
        divide = divide || c.kpi.divideBy !== undefined;
        requires = requires || c.kpi.requiresLoad !== undefined;
        avgAcross = avgAcross || c.kpi.across === 'avg';
    }

    ['PASS', 'WARN', 'FAIL', 'NOT_JUDGED'].forEach((s) =>
        assert.ok(statuses.has(s), `no server parity vector produces ${s}`));
    ['DISABLED', 'NO_MATCH', 'NO_VALUE', 'INVALID_RULE',
        'SOURCE_FAILED', 'LOW_COVERAGE', 'LOAD_NOT_ACHIEVED'].forEach((r) =>
        assert.ok(reasons.has(r), `no server parity vector produces ${r}`));
    ['gt', 'gte', 'lt', 'lte'].forEach((op) =>
        assert.ok(ops.has(op), `no server parity vector uses the ${op} operator`));
    ['max', 'min', 'avg', 'last', 'p', 'slope'].forEach((k) =>
        assert.ok(kinds.has(k), `no server parity vector uses the ${k} reducer`));
    assert.ok(sustained && budget, 'both tolerances must be exercised');
    assert.ok(divide && requires && avgAcross,
        'divideBy, requiresLoad and across: avg must be exercised');
    console.log('  ok   the vector file covers every status, reason, operator and reducer');
} catch (e) {
    failed++;
    console.log('  FAIL the vector file covers every status, reason, operator and reducer');
    console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
}

/* ---- the reason phrases --------------------------------------------------- */
//
// The load-rule sentences are built from NotJudged.phrase() in Java and from REASON_PHRASE here,
// so the two texts are part of the parity contract and not a translation. They had already
// drifted on DISABLED ("was parked" in Java, "parked" here) before anything compared them. The
// Java runner checks the same map against NotJudged.

try {
    const phrases = VECTORS.reasonPhrases || {};
    const pinned = Object.keys(phrases).filter((k) => k !== '_comment');
    assert.ok(pinned.length >= 7, `only ${pinned.length} reason phrases are pinned`);
    pinned.forEach((k) => assert.strictEqual(WIR.status.REASON_PHRASE[k], phrases[k], `phrase for ${k}`));
    Object.keys(WIR.status.REASON_PHRASE).forEach((k) =>
        assert.ok(pinned.indexOf(k) >= 0, `REASON_PHRASE.${k} is pinned by no vector`));
    console.log('  ok   every reason phrase matches the vector file');
} catch (e) {
    failed++;
    console.log('  FAIL every reason phrase matches the vector file');
    console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
}

/* A1-9: the other two copies of NotJudged in WIR.status — the key a recomputed gate counts a
   reason under (countKey) and whether it is a config error (isConfigError). ServerParityTest
   checks the same two entries against the enum. */
try {
    const keys = VECTORS.reasonCountKeys || {};
    const pinned = Object.keys(keys).filter((k) => k !== '_comment');
    assert.ok(pinned.length >= 7, `only ${pinned.length} reason count keys are pinned`);
    pinned.forEach((k) => assert.strictEqual(WIR.status.REASON_KEY[k], keys[k], `count key for ${k}`));
    Object.keys(WIR.status.REASON_KEY).forEach((k) =>
        assert.ok(pinned.indexOf(k) >= 0, `REASON_KEY.${k} is pinned by no vector`));
    const configErrors = VECTORS.configErrorReasons || [];
    assert.ok(configErrors.length >= 1, 'the vector file pins no config-error reasons');
    pinned.forEach((k) => assert.strictEqual(WIR.status.isConfigErrorReason(k),
        configErrors.indexOf(k) >= 0, `config error for ${k}`));
    console.log('  ok   every reason count key and config-error flag matches the vector file');
} catch (e) {
    failed++;
    console.log('  FAIL every reason count key and config-error flag matches the vector file');
    console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
}

/* F8: the INVISIBLE_PADDING WARNING's class, code point by code point — what ServerParityTest
   sweeps in Java, here through the page's own door: kpiWarnings, which the editor's footer, a drag
   and the parity cases ask, with no reducer so that only the query is looked at. Every code point
   (surrogates aside), at both ends, against `invisibleEnds.ranges` - nothing at or below U+0020 is
   listed: javaTrim has taken it off before anything asks, and "not trimmed like a plain space" is
   no sentence for a plain space. A listed one must be NAMED by its code point, which a page reading
   UTF-16 units gets wrong for everything past the BMP (U+E0020 read as U+DC20). */
try {
    const section = VECTORS.invisibleEnds && VECTORS.invisibleEnds.ranges;
    assert.ok(Array.isArray(section), 'the vector file has no invisibleEnds.ranges section');
    const ranges = section.map((r) => {
        const ends = String(r).split('-').map((h) => parseInt(h, 16));
        return [ends[0], ends[ends.length - 1]];
    });
    assert.ok(ranges.length >= 20, `invisibleEnds.ranges shrank to ${ranges.length}`);
    ranges.forEach((r, i) => {
        assert.ok(r[0] > 0x20 && r[0] <= r[1], `a bad range: ${section[i]}`);
        assert.ok(i === 0 || ranges[i - 1][1] + 1 < r[0],
            `the ranges must be ascending, disjoint and maximal: ${section[i]}`);
    });
    const listed = (cp) => ranges.some((r) => cp >= r[0] && cp <= r[1]);
    [0xA0, 0x200B, 0xFEFF].forEach((cp) => assert.ok(listed(cp),
        `U+${cp.toString(16).toUpperCase()} left the class Q2 began with`));
    const warned = (query) => {
        const w = SK._kpiWarnings({ query: query, reducer: null });
        return w.length ? w[0] : null;
    };
    const wrong = [];
    for (let cp = 0; cp <= 0x10FFFF; cp++) {
        if (cp >= 0xD800 && cp <= 0xDFFF) continue;
        const ch = String.fromCodePoint(cp);
        const name = 'U+' + cp.toString(16).toUpperCase().padStart(4, '0');
        const atEnd = warned('x' + ch);
        const atStart = warned(ch + 'x');
        const right = listed(cp)
            ? atEnd !== null && atEnd.indexOf('its end (' + name + ')') >= 0
                && atStart !== null && atStart.indexOf('its start (' + name + ')') >= 0
            : atEnd === null && atStart === null;
        if (!right && wrong.length < 40) wrong.push(name + (listed(cp) ? ' (listed, not warned)' : ' (warned, not listed)'));
    }
    assert.ok(wrong.length === 0, 'the page\'s invisible class is not the vector file\'s '
        + 'invisibleEnds.ranges (first 40 shown): ' + wrong.join(', '));
    console.log('  ok   every code point is an invisible end exactly when invisibleEnds.ranges lists it');
} catch (e) {
    failed++;
    console.log('  FAIL every code point is an invisible end exactly when invisibleEnds.ranges lists it');
    console.log('       ' + (e && e.message ? e.message.split('\n').slice(0, 6).join(' | ') : e));
}

/* The sweep skips U+D800-U+DFFF, so the other half of reading an end as Java does — a surrogate
   half that is not one of a pair is the unit itself — is held here, by ServerParityTest's cases:
   firstCodePoint joining a lone high half to the letter after it agreed with Java on every case
   and every code point above. */
try {
    const cases = VECTORS.invisibleEnds && VECTORS.invisibleEnds.loneSurrogates;
    assert.ok(Array.isArray(cases) && cases.length >= 4, 'invisibleEnds.loneSurrogates shrank');
    const wrong = [];
    cases.forEach((c) => {
        const query = c.units.map((u) => String.fromCharCode(parseInt(u, 16))).join('');
        const w = SK._kpiWarnings({ query: query, reducer: null });
        const got = w.length ? w[0] : null;
        if (got !== c.warning) wrong.push(c.name + ': ' + JSON.stringify(got));
    });
    assert.ok(wrong.length === 0, 'read unlike Java: ' + wrong.join(' | '));
    console.log('  ok   a lone surrogate half at an end is read as Java reads it');
} catch (e) {
    failed++;
    console.log('  FAIL a lone surrogate half at an end is read as Java reads it');
    console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
}

/* A floor, because an emptied or renamed `cases` array would otherwise pass in silence — every
   loop above would simply not run, and a green tick would mean nothing at all. */
try {
    assert.ok(ran >= 55, `only ${ran} cases ran — the vector file shrank or was renamed`);
    assert.ok(gates >= 3, `only ${gates} gate cases ran`);
    assert.ok(documents >= 4, `only ${documents} document cases ran`);
    assert.ok(blocks >= 8, `only ${blocks} block cases ran`);
    console.log('  ok   the vector file still carries its cases');
} catch (e) {
    failed++;
    console.log('  FAIL the vector file still carries its cases');
    console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
}

const total = ran + gates + documents + blocks + 6;
console.log(`\n${total - failed}/${total} passed`);
if (failed) process.exit(1);
