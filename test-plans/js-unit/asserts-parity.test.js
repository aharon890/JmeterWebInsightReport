/**
 * The parity vectors, driven through the PRODUCTION parser, in node.
 *
 * `asserts-parity.spec.ts` already runs these cases in a browser — but it builds the
 * evaluator's internal rule shape with a hand-written helper (`ruleFrom`), exactly as the Java
 * side builds it with `AssertionParityTest.rule()`. So the vectors pinned the two EVALUATORS
 * against each other while the two PARSERS were compared by nothing at all. That gap is the
 * bug class the vectors exist to make impossible: a rule read from disk in the browser could be
 * judged differently from the same rule read by the engine, and no test in either suite was
 * looking.
 *
 * This closes the JS half of it. Every vector goes through `ruleFromJson` — the function that
 * actually runs when a tester opens a file in the Asserts tab — and then through the same
 * `evaluate` the browser spec drives. A divergence between the parser and the hand-written
 * helper now fails here, in a tenth of a second, instead of never.
 *
 * It does not replace the browser spec: that one proves the code works when it is loaded from a
 * real generated report, which is a different claim and still worth making.
 */
'use strict';

const assert = require('assert');
const path = require('path');
const { loadReportJs } = require('./harness');

const VECTORS = require(path.resolve(__dirname,
    '../../jmeter-report-core/src/test/resources/assertion/assert-parity-vectors.json'));

const WIR = loadReportJs();
const A = WIR.asserts;

/** The vector file's sampler shape → the payload shape the client evaluator reads. */
function payloadFrom(v) {
    const samplers = v.samplers.map((s) => Object.assign({
        name: s.name,
        count: s.sampleCount,
        errorCount: s.errorCount,
        errorRate: s.errorRate,
        mean: s.mean,
        median: s.median,
        p90: s.p90,
        p95: s.p95,
        p99: s.p99,
        min: s.min,
        max: s.max,
        stdDev: s.stdDev,
        throughput: s.throughput,
        apdex: s.apdex,
        meanLatency: s.meanLatency,
        meanConnectTime: s.meanConnectTime,
        receivedBytesPerSec: s.receivedBytesPerSec,
        sentBytesPerSec: s.sentBytesPerSec
    }, s.parent ? { parent: s.parent } : {}));

    // Top-level only, exactly as OverallMetrics defines the population: a transaction child's
    // time is already inside its parent.
    const topLevel = v.samplers.filter((s) => !s.parent);
    const totalSamples = topLevel.reduce((n, s) => n + s.sampleCount, 0);
    const totalErrors = topLevel.reduce((n, s) => n + s.errorCount, 0);
    const durationSeconds = v.metadata.durationMillis / 1000;

    return {
        samplers,
        errorSummaries: v.errorSummaries || [],
        hiddenSamplers: v.hiddenSamplers,
        overall: {
            totalSamples,
            errorRate: totalSamples ? (totalErrors * 100) / totalSamples : 0,
            duration: durationSeconds,
            maxThreads: v.metadata.totalThreads,
            mean: totalSamples
                ? topLevel.reduce((n, s) => n + s.mean * s.sampleCount, 0) / totalSamples : 0,
            throughput: durationSeconds > 0 ? totalSamples / durationSeconds : 0,
            apdex: totalSamples
                ? Math.round((topLevel.reduce((n, s) => n + s.apdex * s.sampleCount, 0)
                    / totalSamples) * 100) / 100 : 0,
            median: 0, p90: 0, p95: 0, p99: 0
        },
        assertPercentiles: v.extraPercentiles || {}
    };
}

const payload = payloadFrom(VECTORS);
let failed = 0;
let ran = 0;

for (const c of VECTORS.cases) {
    ran++;
    const label = c.rule.id || c.name;
    try {
        // THE POINT OF THIS FILE: the production parser, not a test helper.
        const rule = A._ruleFromJson(c.rule, 0);
        const result = A.evaluate([rule], payload).rules[0];

        assert.strictEqual(result.status, c.expect.status, 'status');
        assert.strictEqual(result.matched, c.expect.matched, 'matched count');

        if (c.expect.notJudged !== undefined) {
            assert.strictEqual(result.notJudged, c.expect.notJudged, 'not-judged reason');
        } else {
            assert.strictEqual(result.notJudged, undefined,
                'a judged rule must carry no reason');
        }
        if (c.expect.unmatched !== undefined) {
            assert.strictEqual(result.unmatched, c.expect.unmatched, 'unmatched');
        }
        /* The name the rule is judged under, whatever the file's spelling (RB8): MetricRef's
           canonical one, which GatePayloadBuilder ships and the runners hold Java to. */
        if (c.expect.metric !== undefined) {
            assert.strictEqual(rule.metric, c.expect.metric, 'the canonical metric');
        }
        if (c.expect.reason !== undefined) {
            assert.ok(String(result.notJudgedDetail).indexOf(c.expect.reason) >= 0,
                'the detail was: ' + result.notJudgedDetail);
        }
        if (c.expect.matches !== undefined) {
            assert.strictEqual(result.results.length, c.expect.matches.length, 'reported matches');
            c.expect.matches.forEach((m, i) => {
                assert.strictEqual(result.results[i].sampler, m.sampler, 'match ' + i + ' sampler');
                if (m.actual === null) {
                    assert.strictEqual(result.results[i].actual, null, 'match ' + i + ' is unanswerable');
                } else {
                    /* AssertionParityTest's tolerance: a vector writes 1.1111 for 10/9. */
                    assert.ok(Math.abs(result.results[i].actual - m.actual) <= 0.0001,
                        'match ' + i + ' actual ' + result.results[i].actual + ' is not ' + m.actual);
                }
                assert.strictEqual(result.results[i].status, m.status, 'match ' + i + ' status');
            });
        }
        /* How much of the fail band the rule used (AF5b): the Budget column's attainmentFor, against
           Attainment.of in AssertionParityTest. */
        if (c.expect.attainment !== undefined) {
            const got = A._attainment(result, payload);
            if (c.expect.attainment === null) {
                assert.strictEqual(got, null, 'no budget applies, so the attainment is null, not zero');
            } else {
                assert.ok(got, 'no attainment');
                const want = c.expect.attainment;
                assert.ok(Math.abs(got.actual - want.actual) <= 0.0001, 'attainment.actual ' + got.actual);
                assert.strictEqual(got.failThreshold, want.failThreshold, 'attainment.failThreshold');
                assert.ok(Math.abs(got.consumed - want.consumed) <= 0.0001, 'attainment.consumed ' + got.consumed);
                assert.strictEqual(got.breached, want.breached, 'attainment.breached');
            }
        }
        console.log('  ok   ' + label);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + label);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}

// The vector file is the contract; an empty one would pass silently and prove nothing.
assert.ok(ran >= 50, 'expected the full vector set, got ' + ran);

/* How a TARGET is read, and so what an id-less rule is called (M7d X, review:C) — MapperParityTest
   runs the same cases through AssertionRuleMapper.readTarget. A server assert names its load rule
   by that id, so a reader that derived 'group-...' for {samplers: []} where Java says
   'assertions[0]' had the two sides disagree about which rule it named. */
let targets = 0;
for (const c of (VECTORS.targetCases || [])) {
    targets++;
    ran++;
    try {
        const rule = A._ruleFromJson(c.rule, c.index || 0);
        assert.strictEqual(rule.target.kind, c.expect.kind, 'target kind');
        assert.strictEqual(rule.id, c.expect.id, 'the id the rule is judged under');
        if (c.expect.problem !== undefined) {
            assert.strictEqual(rule.target.text, c.expect.problem, 'the problem of the target');
            assert.strictEqual(A._whyUnusable(rule), c.expect.problem, 'the INVALID_RULE detail');
        }
        console.log('  ok   target: ' + c.name);
    } catch (e) {
        failed++;
        console.log('  FAIL target: ' + c.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
assert.ok(targets >= 10, 'the target cases are gone: ' + targets);

/* The whole INVALID_RULE sentence of a refusal, character for character (M7d X review follow-up):
   whyUnusable wrote an unreachable band with the table's display formatter, so the board said
   '< -1,234 ms' where the CI/CD JSON said '< -1234.5 ms', and wrote every unreadable band without
   Usability.unreadableBand's ending ('fail gt must be a number, not number' for a bare 1e400).
   MapperParityTest runs the same cases through the mapper and Usability.refusal. The unit and an
   unknown metric's "did you mean" are the page vocabulary's, which this tier has no page to read:
   the vector file's `vocabulary` and `units` stand in for it, each scope's list in
   MetricRef.vocabulary()'s order (the first of two equally near names is the one offered, RB14),
   and MapperParityTest pins both to Java's. */
const scopeVocabulary = (scope) => VECTORS.vocabulary[scope].map((name) => {
    assert.ok(Object.prototype.hasOwnProperty.call(VECTORS.units, name), 'no unit for ' + name);
    return { name, unit: VECTORS.units[name] };
});
const D = loadReportJs({ assertMetrics: { sampler: scopeVocabulary('sampler'),
                                          overall: scopeVocabulary('overall') } }).asserts;
let details = 0;
for (const c of (VECTORS.detailCases || [])) {
    details++;
    ran++;
    try {
        const rule = D._ruleFromJson(c.rule, c.index || 0);
        assert.strictEqual(D._whyUnusable(rule), c.expect.detail, 'the INVALID_RULE detail');
        const result = D.evaluate([rule], payload).rules[0];
        assert.strictEqual(result.notJudged, 'INVALID_RULE', 'refused');
        assert.strictEqual(result.notJudgedDetail, c.expect.detail, 'the detail the evaluator reports');
        console.log('  ok   detail: ' + c.name);
    } catch (e) {
        failed++;
        console.log('  FAIL detail: ' + c.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
assert.ok(details >= 18, 'the detail cases are gone: ' + details);

/* The legacy loadValidity block, migrated into assertions[] as the run migrates it (M5
   exploratory F1) — MapperParityTest runs the same documents through AnnotationLoader's mapper
   and LoadValidityMigration. Compared by value: Java writes every unset field as null, and the
   page writes nothing for it. */
const byValue = (v) => {
    if (v === null || v === undefined) return null;
    if (Array.isArray(v)) return v.map(byValue);
    if (typeof v !== 'object') return v;
    const out = {};
    Object.keys(v).sort().forEach((k) => { if (v[k] !== null && v[k] !== undefined) out[k] = byValue(v[k]); });
    return out;
};
let migrations = 0;
for (const c of (VECTORS.migrationCases || [])) {
    migrations++;
    ran++;
    try {
        const before = JSON.stringify(c.document);
        const entries = A._migrateLoadValidity(c.document);
        /* As documentOf writes it, and as the page carries and downloads it: a non-finite band is
           Jackson's text, "NaN", where JSON.stringify writes null (L3). Copied once more by this
           realm's JSON, so deepStrictEqual compares values and not the report's Array prototype. */
        const migrated = JSON.parse(JSON.stringify(WIR.javaText.jacksonCopy(entries)));
        assert.deepStrictEqual(byValue(migrated), byValue(c.expect.assertions), 'the assertions it ends with');
        assert.strictEqual(JSON.stringify(c.document), before, 'the document it was handed was changed');
        /* The migrated rule's refusal through the page's own parser, in the run's words (L3). */
        if (c.expect.detail !== undefined) {
            const last = entries.length - 1;
            assert.strictEqual(A._whyUnusable(A._ruleFromJson(entries[last], last)), c.expect.detail,
                'the migrated rule\'s INVALID_RULE detail');
        }
        console.log('  ok   migration: ' + c.name);
    } catch (e) {
        failed++;
        console.log('  FAIL migration: ' + c.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
assert.ok(migrations >= 18, 'the migration cases are gone: ' + migrations);

/* A band written as TEXT, read as AssertionRuleMapper reads it (L2, CN25): String.trim and
   Double.parseDouble, NaN and the infinities refused. bandNumber read it with trim() and Number(),
   so "0x10" was a band of 16 here and a CONFIG ERROR in the run. MapperParityTest runs the same
   cases through the mapper. */
let bandTexts = 0;
for (const c of (VECTORS.bandTextCases || [])) {
    bandTexts++;
    ran++;
    try {
        const rule = A._ruleFromJson({ id: 'band-text', target: { sampler: 'GET /api/products' },
            metric: 'mean', fail: { gt: c.text } }, 0);
        if (c.expect.value !== undefined) {
            assert.strictEqual(rule.declarationProblems.length, 0,
                'Java reads this band: ' + rule.declarationProblems.join(' | '));
            assert.ok(rule.fail, 'no fail band');
            assert.strictEqual(rule.fail.op, 'gt');
            assert.strictEqual(rule.fail.value, c.expect.value, 'the band read');
        } else {
            assert.strictEqual(c.expect.refused, true);
            assert.strictEqual(rule.fail, undefined, 'a band was read: ' + JSON.stringify(rule.fail));
            assert.strictEqual(A._whyUnusable(rule), 'fail gt must be a number, not the text "' + c.text
                + '" — so this rule declares a limit it cannot apply, and would report PASS without ever testing fail');
        }
        console.log('  ok   band text: ' + c.name);
    } catch (e) {
        failed++;
        console.log('  FAIL band text: ' + c.name);
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
assert.ok(bandTexts >= 20, 'the band-text cases are gone: ' + bandTexts);

console.log('\n' + (ran - failed) + '/' + ran + ' vectors agree, parsed by ruleFromJson');
process.exit(failed === 0 ? 0 : 1);
