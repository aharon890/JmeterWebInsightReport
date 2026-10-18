/**
 * Unit tests for the pure half of WIR.asserts.
 *
 * Every case here could only have been written as a Playwright test before, which meant booting
 * Chromium against a 1.7 MB generated fixture to ask what a parser does with `{gt: 1, lt: 2}`.
 * In practice that meant they were not written, and the defects that reached production were the
 * ones a pure-function test catches instantly: a chip class assembled with the wrong separator,
 * a band parser that trusted whatever the first key of the object happened to be, a rounding
 * rule that could not express 0.0015.
 *
 * The rule for what belongs here: no DOM, no fixture, no browser. The harness refuses anything
 * that reaches for `document`, by name, so the boundary is enforced rather than remembered.
 */
'use strict';

const assert = require('assert');
const { loadReportJs, loadReportPage } = require('./harness');

const A = loadReportJs().asserts;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/**
 * Strip the realm off a value before comparing it.
 *
 * report.js runs inside a `vm` context, so an object it builds has that context's
 * Object.prototype rather than this file's. `deepStrictEqual` compares prototypes, so it
 * rejects two structurally identical objects with "same structure but not reference-equal" —
 * a true statement about realms and a useless one about parsing. The round-trip compares the
 * data, which is the only thing under test here.
 */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const sameData = (actual, expected, message) =>
    assert.deepStrictEqual(plain(actual), plain(expected), message);

// ---------------------------------------------------------------------------
// bandFromJson — the browser's half of a two-parser contract
// ---------------------------------------------------------------------------

/** Parse a band and return both halves of the answer. */
function band(json, which) {
    const problems = [];
    const parsed = A._bandFromJson(json, which || 'fail', problems);
    return { parsed, problems };
}

test('a well-formed band parses to an op and a number', () => {
    const { parsed, problems } = band({ gt: 800 });
    sameData(parsed, { op: 'gt', value: 800 });
    sameData(problems, []);
});

test('an absent band is not a problem — one band is enough', () => {
    sameData(band(undefined).problems, []);
    sameData(band(null).problems, []);
    sameData(band({}).problems, []);
    assert.strictEqual(band({}).parsed, undefined);
});

test('two operators are refused rather than guessed at', () => {
    // The browser used to take Object.keys(band)[0] and evaluate it, so `{gt: 100, lt: 50}`
    // silently became `gt 100` -- a live threshold the author never wrote -- while the engine
    // called the same file a configuration error.
    const { parsed, problems } = band({ gt: 100, lt: 50 });
    assert.strictEqual(parsed, undefined);
    assert.strictEqual(problems.length, 1);
    assert.ok(problems[0].includes('2 operators'), problems[0]);
    assert.ok(problems[0].includes('exactly one is allowed'), problems[0]);
});

test('the retired `outside` operator names its replacement', () => {
    const { parsed, problems } = band({ outside: [1, 2] });
    assert.strictEqual(parsed, undefined);
    assert.ok(problems[0].includes('no longer an operator'), problems[0]);
    assert.ok(problems[0].includes('two rules'),
        'a message that does not say what to write instead cannot be acted on');
});

test('an unknown operator lists the ones that exist', () => {
    const { problems } = band({ between: 5 });
    assert.ok(problems[0].includes("'between'"), problems[0]);
    assert.ok(problems[0].includes('gt, gte, lt, lte'), problems[0]);
});

test('an operator is read as Predicate.Op.fromKey reads it: trimmed, in any case', () => {
    // Java trims and lower-cases the key, so "GT" and " gt " are gt in the run, and the page
    // refused both — a rule the build judged read INVALID on Open file and Edit document. A
    // detailCase is a refusal, so the accepted spellings are pinned here rather than in the
    // vectors; the refused one ("constructor") is a detailCase.
    const rule = A._ruleFromJson({ id: 'r', target: { sampler: 'A' }, metric: 'p95',
        warn: { ' gt ': 650 }, fail: { GT: 800 } }, 0);
    sameData(rule.warn, { op: 'gt', value: 650 });
    sameData(rule.fail, { op: 'gt', value: 800 });
    sameData(rule.declarationProblems, []);
    assert.strictEqual(A._whyUnusable(rule), null, 'a band Java reads is a band here');
    sameData(band({ Lte: 5 }).parsed, { op: 'lte', value: 5 });
});

test('a key every object inherits is not an operator', () => {
    // A plain-object lookup found BAND_OPS.constructor and took it for an operator no measurement
    // can breach: PASS on the page, a CONFIG ERROR in the run. JSON.parse makes "__proto__" an own
    // key, which an object literal would not.
    ['constructor', 'toString', 'valueOf', 'hasOwnProperty', '__proto__'].forEach((key) => {
        const { parsed, problems } = band(JSON.parse('{"' + key + '": 100}'));
        assert.strictEqual(parsed, undefined, key + ' was taken for an operator');
        assert.strictEqual(problems[0], "fail operator '" + key + "' is not one of gt, gte, lt, lte — so"
            + ' this rule declares a limit it cannot apply, and would report PASS without ever testing fail');
    });
});

test('a non-numeric threshold says what it got instead', () => {
    assert.ok(band({ gt: 'soon' }).problems[0].includes('the text "soon"'));
    assert.ok(band({ gt: true }).problems[0].includes('a boolean'));
    assert.ok(band({ gt: [1] }).problems[0].includes('a list'));
    assert.ok(band({ gt: { v: 1 } }).problems[0].includes('an object'));
    assert.ok(band({ gt: null }).problems[0].includes('null'));
});

test('a quoted number is accepted, matching the Java mapper', () => {
    // Friendlier than failing a build over a pair of quotes, and there is no ambiguity about
    // what was meant. asNumber does the same on the other side.
    sameData(band({ gt: '800' }).parsed, { op: 'gt', value: 800 });
    sameData(band({ lt: ' 0.5 ' }).parsed, { op: 'lt', value: 0.5 });
});

test('a quoted non-number is still refused', () => {
    assert.strictEqual(band({ gt: '1,000' }).parsed, undefined,
        'a thousands separator is not a number on either side');
    assert.strictEqual(band({ gt: '' }).parsed, undefined);
});

test('the band name appears in its own message', () => {
    assert.ok(band({ gt: 1, lt: 2 }, 'warn').problems[0].startsWith('warn '));
    assert.ok(band({ gt: 1, lt: 2 }, 'fail').problems[0].startsWith('fail '));
});

// ---------------------------------------------------------------------------
// ruleFromJson — the whole second parser
// ---------------------------------------------------------------------------

test('a rule with a retired key is refused and the key is named', () => {
    // `observe` meant "report this and never gate". Dropping it while keeping the bands leaves
    // a rule that gates, so a file written to be silent becomes the one thing turning the badge
    // red.
    const rule = A._ruleFromJson(
        { id: 'r', target: { sampler: 'A' }, metric: 'p95', observe: true, fail: { gt: 1 } }, 0);
    assert.strictEqual(rule.declarationProblems.length, 1);
    assert.ok(rule.declarationProblems[0].includes('observe'), rule.declarationProblems[0]);
});

test('`tags` is deliberately NOT retired', () => {
    // Ignoring it runs every rule instead of a subset: more checking, never less. Refusing it
    // would break the unknown-key promise for no safety gain.
    const rule = A._ruleFromJson(
        { id: 'r', target: { sampler: 'A' }, metric: 'p95', tags: ['smoke'], fail: { gt: 1 } }, 0);
    sameData(rule.declarationProblems, []);
});

test('an id-less rule is named the way Java derives it, and is marked underived', () => {
    // AssertionRule.deriveId: target.slug() + '-' + metric. The positional label is what the
    // mapper gives a rule whose TARGET could not be read, and only that one. Every id-less rule
    // used to get 'assertions[i]' here, so a server KPI requiring 'run-throughput' — judged in
    // CI — was INVALID_RULE on the board (R1-3).
    const rule = A._ruleFromJson({ target: { sampler: 'A' }, metric: 'p95' }, 2);
    assert.strictEqual(rule.id, 'a-p95');
    assert.strictEqual(rule.idDeclared, false,
        'writing a derived name into the file adds a key the tester never typed');
    assert.strictEqual(A._ruleFromJson({ target: { scope: 'overall' }, metric: 'throughput' }, 0).id,
        'run-throughput');
    // Java's String.replace replaces every occurrence; JavaScript's replace('.', '') only the first.
    assert.strictEqual(A._ruleFromJson({ target: { samplers: ['A', 'B'] }, metric: 'a.b.c' }, 0).id,
        'a-b-abc');
    // An empty list is not a form (AssertionRuleMapper.readTarget), so that rule's target cannot be
    // read and it keeps the positional label — this line pinned 'group-abc', the mirror's own wrong
    // answer, until M7d X; the targetCases vectors now pin it on both sides.
    assert.strictEqual(A._ruleFromJson({ target: { samplers: [] }, metric: 'a.b.c' }, 0).id,
        'assertions[0]');
    assert.strictEqual(A._ruleFromJson({ target: { match: 'regex:GET regex:/x' }, metric: 'p95' }, 0).id,
        'get-x-p95');
});

test('an id-less rule whose target cannot be read keeps the positional label', () => {
    const rule = A._ruleFromJson({ target: {}, metric: 'p95' }, 2);
    assert.strictEqual(rule.id, 'assertions[2]');
    assert.strictEqual(rule.idDeclared, false);
});

test('"id": null is no id — it is how the page\'s own annotationsDocument writes an id-less rule', () => {
    // R2-3: String(null) made it "null", declared, so it was written back as "id": "null".
    const rule = A._ruleFromJson({ id: null, target: { scope: 'overall' }, metric: 'throughput' }, 0);
    assert.strictEqual(rule.id, 'run-throughput');
    assert.strictEqual(rule.idDeclared, false);
    assert.strictEqual(A._ruleFromJson({ id: '   ', target: { sampler: 'GET /api/products' },
        metric: 'throughput' }, 0).id, 'get-api-products-throughput');
});

test('a declared id is kept and marked declared', () => {
    const rule = A._ruleFromJson({ id: ' checkout-p95 ', target: { sampler: 'A' }, metric: 'p95' }, 0);
    assert.strictEqual(rule.id, 'checkout-p95');
    assert.strictEqual(rule.idDeclared, true);
});

test('every target form is recognised, and an unreadable one is marked invalid', () => {
    const kind = (t) => A._ruleFromJson({ id: 'r', target: t, metric: 'p95' }, 0).target.kind;
    assert.strictEqual(kind({ sampler: 'A' }), 'sampler');
    assert.strictEqual(kind({ samplers: ['A', 'B'] }), 'samplers');
    assert.strictEqual(kind({ match: 'GET *' }), 'match');
    assert.strictEqual(kind({ scope: 'overall' }), 'overall');
    assert.strictEqual(kind({}), 'invalid');
    assert.strictEqual(kind({ nonsense: 1 }), 'invalid');
});

test('enabled defaults to true and only an explicit false parks a rule', () => {
    const enabled = (v) => A._ruleFromJson(
        { id: 'r', target: { sampler: 'A' }, metric: 'p95', enabled: v }, 0).enabled;
    assert.strictEqual(enabled(undefined), true);
    assert.strictEqual(enabled(true), true);
    assert.strictEqual(enabled(false), false);
});

// ---------------------------------------------------------------------------
// whyUnusable — the client half of "can this rule decide anything"
// ---------------------------------------------------------------------------

/** Build an internal-shape rule the way ruleFromJson does, then ask why it cannot run. */
function why(json) {
    return A._whyUnusable(A._ruleFromJson(json, 0));
}

test('a well-formed rule is usable', () => {
    assert.strictEqual(why({ id: 'r', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1 } }),
        null);
});

test('a rule with no bands cannot decide anything', () => {
    const reason = why({ id: 'r', target: { sampler: 'A' }, metric: 'p95' });
    assert.ok(reason && reason.includes('no warn or fail band'), reason);
});

test('a band no measurement can breach refuses the rule', () => {
    // The incident the check is named after: a loadValidity tolerance of 20, meant as 20%,
    // shipped as `lt -19` and reported PASS on every run.
    const reason = why({ id: 'r', target: { sampler: 'A' }, metric: 'p95', fail: { lt: -19 } });
    assert.ok(reason && reason.includes('can never fire'), reason);
    assert.ok(reason.includes('0.2, not 20'),
        'the message is written for the mistake that produces this');
});

test('lt zero is unreachable and lte zero is not', () => {
    const rule = (b) => ({ id: 'r', target: { sampler: 'A' }, metric: 'errorRate', fail: b });
    assert.ok(why(rule({ lt: 0 })), 'nothing is negative, so `lt 0` can never fire');
    assert.strictEqual(why(rule({ lte: 0 })), null,
        'a metric that measured exactly zero breaches `lte 0`, which counts and rates do');
    assert.strictEqual(why(rule({ lt: 0.001 })), null,
        "the smallest real threshold in this repo's own fixtures");
});

test('nothing is claimed about upward bands', () => {
    // "1000000 ms is surely impossible" is a guess about the system under test.
    assert.strictEqual(
        why({ id: 'r', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 1e6 } }), null);
});

test('a declaration problem refuses the rule even when a good band survived', () => {
    // hasBands() stays true through the surviving warn band, which is exactly how the declared
    // fail threshold used to vanish in silence.
    const reason = why({
        id: 'r', target: { sampler: 'A' }, metric: 'p95',
        warn: { gt: 1 }, fail: { gt: 100, lt: 50 }
    });
    assert.ok(reason && reason.includes('2 operators'), reason);
});

test('an unreadable target is reported before anything below it', () => {
    const reason = why({ id: 'r', target: {}, metric: 'p95x' });
    assert.ok(reason && !reason.includes('unknown metric'),
        'a second complaint about the metric would bury the real fault: ' + reason);
});

// ---------------------------------------------------------------------------
// The shape Java ships for a band it refused — a contract guard, on the page's own lifecycle
// ---------------------------------------------------------------------------

/*
 * A band the mapper could not read (a bare 1e400 among them) reaches the page as a rule with that
 * band ABSENT, the other one intact, and the whole sentence in declarationProblems — GatePayloadTest
 * pins that Java ships it so. What stops the page judging the surviving band on its own is
 * whyUnusable reading declarationProblems, and no fixture ships one (E25's INVALID rule is an unknown
 * metric), so without this nothing held it: the same rule without the field is PASS, on load and
 * after any edit. Green when written; the red-first pin for this half is GatePayloadTest. Driven
 * through harness.loadReportPage — init, an unrelated edit, the draft recompute — because it is the
 * recompute that used to re-judge a refused rule.
 */
test('a band Java refused stays refused on load and after an unrelated edit, beside a readable one', () => {
    const ending = ' — so this rule declares a limit it cannot apply, and would report PASS without ever testing ';
    const FAIL_REFUSED = 'fail gt must be a number, not the number Infinity' + ending + 'fail';
    const WARN_REFUSED = 'warn gt must be a number, not the number Infinity' + ending + 'warn';
    const target = { kind: 'sampler', text: 'A', sampler: 'A' };
    /* GatePayloadBuilder.rulesOf, for a refused rule: no band where the problem was. */
    const refused = (id, bands, problem) => Object.assign({ id, description: '', target, metric: 'p95',
        unit: 'ms', enabled: true }, bands, { declarationProblems: [problem], status: 'NOT_JUDGED',
        idDeclared: true, unmatched: false, matched: 0, notJudged: 'INVALID_RULE', notJudgedDetail: problem,
        results: [] });
    const assertions = [
        refused('fail-refused', { warn: { op: 'gt', value: 1 } }, FAIL_REFUSED),
        refused('warn-refused', { fail: { op: 'gt', value: 100000 } }, WARN_REFUSED),
        { id: 'other', description: '', target, metric: 'p95', unit: 'ms', enabled: true,
            fail: { op: 'gt', value: 100 }, status: 'PASS', idDeclared: true, unmatched: false, matched: 1,
            results: [{ sampler: 'A', actual: 5, status: 'PASS' }] }
    ];
    const run = { samplers: [{ name: 'A', count: 10, p95: 5, mean: 3 }], overall: { totalSamples: 10 } };
    const chartData = Object.assign({
        runId: 'refused-band-contract',
        /* documentOf: the file as Jackson bound it — an overflowed literal is its text by then. */
        annotationsDocument: { assertions: [
            { id: 'fail-refused', target: { sampler: 'A' }, metric: 'p95', warn: { gt: 1 }, fail: { gt: 'Infinity' } },
            { id: 'warn-refused', target: { sampler: 'A' }, metric: 'p95', warn: { gt: 'Infinity' }, fail: { gt: 100000 } },
            { id: 'other', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 100 } }] },
        assertions,
        gate: plain(A.evaluate(assertions, run).gate)
    }, run);

    const WIR = loadReportPage(chartData);
    const verdicts = () => plain(WIR.asserts._state.rules.slice(0, 2)
        .map((r) => [r.id, r.status, r.notJudged, r.notJudgedDetail]));
    const expected = [['fail-refused', 'NOT_JUDGED', 'INVALID_RULE', FAIL_REFUSED],
        ['warn-refused', 'NOT_JUDGED', 'INVALID_RULE', WARN_REFUSED]];

    sameData(verdicts(), expected, 'on load');
    WIR.asserts.toggleEnabled(WIR.asserts._keyOf('other'));
    assert.ok(WIR.asserts._state.draftGate, 'precondition: the edit recomputed a draft gate');
    sameData(verdicts(), expected, 'after an unrelated edit, re-judged by the page');
    assert.strictEqual(WIR.asserts._state.draftGate.notJudged.invalidRule, 2,
        'both refused rules counted as the run counts them: ' + JSON.stringify(WIR.asserts._state.draftGate));
    assert.strictEqual(WIR.asserts._state.draftGate.passed, 0, 'and neither is a pass');
});

/*
 * A sampler literally named "Whole run" is not the whole run (CORE-1, the page's half). The page
 * filed every match under its display label, and the whole run's label is "Whole run": so the
 * sampler table's badge for that sampler took the scope-overall rule's FAIL, and a capped rule about
 * the sampler was completed by judging the whole run's p95 instead of the sampler's.
 */
function wholeRunNamedPage(rules, cap) {
    const run = {
        samplers: [{ name: 'Whole run', count: 10, p95: 5, mean: 3 }, { name: 'A', count: 10, p95: 5, mean: 3 }],
        overall: { totalSamples: 20, throughput: 1, p95: 500 }
    };
    const judged = A.evaluate(rules.map((r, i) => A._ruleFromJson(r, i)), run);
    const assertions = plain(judged.rules);
    if (cap) cap(assertions);
    return loadReportPage(Object.assign({
        runId: 'whole-run-named',
        annotationsDocument: { assertions: rules },
        assertions,
        gate: plain(judged.gate)
    }, run));
}

test('a sampler named "Whole run" keeps its own badge beside a scope-overall rule (CORE-1)', () => {
    const WIR = wholeRunNamedPage([
        { id: 'named', target: { sampler: 'Whole run' }, metric: 'p95', fail: { gt: 100 } },
        { id: 'run', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 100 } }]);
    sameData(WIR.asserts._state.rules.map((r) => r.status), ['PASS', 'FAIL'], 'precondition');
    const entry = WIR.asserts._verdictsBySampler()['Whole run'];
    assert.ok(entry, 'the sampler is judged');
    assert.strictEqual(entry.worst, 'PASS', 'the sampler took the whole run\'s FAIL');
    sameData(entry.rules.map((p) => p.rule.id), ['named']);
});

test('a capped rule about a sampler named "Whole run" is completed from the sampler, not the run (CORE-1)', () => {
    /* GatePayloadBuilder caps results; matchesOf completes the omitted matches with judgeMatch. */
    const WIR = wholeRunNamedPage([
        { id: 'named', target: { sampler: 'Whole run' }, metric: 'p95', fail: { gt: 100 } }],
    (assertions) => { assertions[0].results = []; });
    const entry = WIR.asserts._verdictsBySampler()['Whole run'];
    assert.ok(entry, 'the completed match is indexed');
    assert.strictEqual(entry.worst, 'PASS', 'completed against the whole run\'s p95 of 500');
    assert.strictEqual(entry.rules[0].match.actual, 5, 'the sampler\'s own p95');
});

/*
 * The page's half of REV-6. JUnit's sampler line writes a metric's number beside the bands of every
 * rule that judged it on that sampler (SamplerVerdicts.Evidence.bands()); the cell marker's tooltip
 * wrote the deciding rule's actual beside that rule's bands alone. Two rules on one sampler's p95,
 * one FAILing it over gt 119.36 and one PASSing it under gt 119.361, measured the same 119.36094...:
 * beside the deciding rule's band alone it read "119.4 ms", above the gt 119.361 the other rule
 * PASSed it under.
 */
test('a cell judged by two rules writes its number beside the bands of both (REV-6, the page)', () => {
    const rules = [
        { id: 'other', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 119.361 } },
        { id: 'gate', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 119.36 } }];
    const run = {
        samplers: [{ name: 'A', count: 10, p95: 119.36094674556213, mean: 100 }],
        overall: { totalSamples: 10, throughput: 1, p95: 119.36094674556213 }
    };
    const judged = A.evaluate(rules.map((r, i) => A._ruleFromJson(r, i)), run);
    const WIR = loadReportPage(Object.assign({
        runId: 'cell-two-rules',
        annotationsDocument: { assertions: rules },
        assertions: plain(judged.rules),
        gate: plain(judged.gate)
    }, run));
    sameData(WIR.asserts._state.rules.map((r) => r.status), ['PASS', 'FAIL'], 'precondition');
    const title = WIR.asserts._cellTitle('A', 'p95');
    assert.ok(title, 'the cell is judged');
    assert.ok(title.includes(' · gate · 119.3609 ms vs > 119.36 ms · 2 rules judge this cell'), title);
    // One rule alone keeps its own digits: beside gt 119.36 only, whole ms would sit below it.
    assert.strictEqual(WIR.asserts._formatActual(119.36094674556213, 'ms', [null, 119.36]), '119.4 ms');
});

/*
 * The judging row under the same sampler — opened from its verdict badge — is the cell title's pair
 * (Tutorial 12, client-numbers.spec.ts). Each of its lines wrote its rule's actual beside that rule's
 * bands alone, so the two p95 lines read "119 ms · fail > 119.361 ms" and "119.4 ms · fail > 119.36
 * ms": one measured p95 spelled two ways in one <tr>, the 119.4 above the band on the line above it,
 * and neither the cell title's 119.3609. Every line now folds the bands of every rule judging its
 * cell (the sampler and that line's metric) and no other: a mean rule's band beside them, which
 * would take the p95 to a fifth decimal, is not the p95 cell's.
 */
test('the judging row writes each line beside the bands of every rule judging that cell (REV-6, the page)', () => {
    // mean's unit is the vocabulary's, as the report ships it (a percentile is ms without one).
    const vocabulary = { sampler: [{ name: 'mean', unit: 'ms' }], overall: [{ name: 'mean', unit: 'ms' }] };
    const V = loadReportJs({ assertMetrics: vocabulary }).asserts;
    const rules = [
        { id: 'other', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 119.361 } },
        { id: 'gate', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 119.36 } },
        { id: 'avg', target: { sampler: 'A' }, metric: 'mean', fail: { gt: 119.36094 } }];
    const run = {
        samplers: [{ name: 'A', count: 10, p95: 119.36094674556213, mean: 100 }],
        overall: { totalSamples: 10, throughput: 1, p95: 119.36094674556213 }
    };
    const judged = V.evaluate(rules.map((r, i) => V._ruleFromJson(r, i)), run);
    const WIR = loadReportPage(Object.assign({
        runId: 'judging-two-rules',
        assertMetrics: vocabulary,
        annotationsDocument: { assertions: rules },
        assertions: plain(judged.rules),
        gate: plain(judged.gate)
    }, run));
    sameData(WIR.asserts._state.rules.map((r) => r.status), ['PASS', 'FAIL', 'PASS'], 'precondition');
    sameData(WIR.asserts._judgingLines('A'), [
        { status: 'PASS', id: 'other', text: 'p95 = 119.3609 ms · fail > 119.361 ms' },
        { status: 'FAIL', id: 'gate', text: 'p95 = 119.3609 ms · fail > 119.36 ms' },
        { status: 'PASS', id: 'avg', text: 'mean = 100 ms · fail > 119.36094 ms' }]);
    // The cell title and the judging row write the p95 one way.
    assert.ok(WIR.asserts._cellTitle('A', 'p95').includes(' 119.3609 ms vs '));
    // A sampler no rule judges has no lines: the row says so in words.
    sameData(WIR.asserts._judgingLines('B'), []);
});

test('the "Judges sampler" filter keeps the whole run apart from a sampler named "Whole run" (REV-4)', () => {
    // Its values were the resolved LABELS, so the run and the sampler were one option, and choosing
    // it showed the rules about both. Keyed by OVERALL_KEY now, labelled "Whole run" on screen only.
    const WIR = wholeRunNamedPage([
        { id: 'named', target: { sampler: 'Whole run' }, metric: 'p95', fail: { gt: 100 } },
        { id: 'run', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 100 } }]);
    sameData(WIR.asserts._filterOptions('sampler'), [
        { value: '__overall__', label: 'Whole run' },
        { value: 'Whole run', label: 'Whole run' }]);
    WIR.asserts.setFilter('sampler', '__overall__');
    sameData(WIR.asserts._visible(), ['run']);
    WIR.asserts.setFilter('sampler', 'Whole run');
    sameData(WIR.asserts._visible(), ['named']);
});

// ---------------------------------------------------------------------------
// chipClass and formatValue — the two that shipped broken
// ---------------------------------------------------------------------------

test('every status chip class is a single CSS class', () => {
    // `NO VALUE`.toLowerCase() is `no value`, which as a class attribute is TWO classes, and
    // neither of them exists in the stylesheet. Two call sites built it by hand that way while
    // chipClass three lines above did the replace correctly.
    // The three server-only display values are the same multi-word shape as NO VALUE — one
    // of them has two spaces — so they are the cases a hand-built .toLowerCase() would break.
    ['PASS', 'WARN', 'FAIL', 'NO VALUE', 'UNMATCHED', 'DISABLED', 'INVALID',
        'SOURCE FAILED', 'LOW COVERAGE', 'LOAD NOT ACHIEVED'].forEach((s) => {
        const cls = A._chipClass(s);
        assert.ok(!/\s\s|wir-chip-\S*\s+\S/.test(cls.replace('wir-chip ', '')),
            'class for ' + s + ' splits into more than one: ' + cls);
        assert.ok(!cls.includes(' value'), 'NO VALUE lost its dash: ' + cls);
    });
});

test('formatValue keeps thresholds that are smaller than the rounding grain', () => {
    // A throughput rule at `lt 0.0015` exists in this repo's fixtures. Rounding to two decimal
    // places renders it as 0, which is three orders of magnitude away and reads as a rule that
    // was never set.
    const shown = A._formatValue(0.0015, '/s');
    assert.ok(!/^0(\s|$)/.test(shown), 'a small threshold rendered as zero: ' + shown);
});

test('formatValue renders an unmeasurable value as a dash rather than a number', () => {
    [null, undefined, NaN].forEach((v) => {
        const shown = A._formatValue(v, 'ms');
        assert.ok(!/\d/.test(shown), 'an absent value rendered as a number: ' + shown);
    });
});

test('the literal wir-chip- appears only where the class is assembled', () => {
    // The structural half of the NO VALUE defect. Two sites built the class by hand with a bare
    // toLowerCase(), producing `wir-chip-no value` — two classes, neither in the stylesheet —
    // while chipClass three functions away did the replace correctly. A grep-shaped rule is the
    // only thing that stops a third site being written the same way.
    const fs = require('fs');
    const src = fs.readFileSync(require('./harness').REPORT_JS, 'utf8');
    const offenders = src.split('\n')
        .map((line, i) => ({ line, n: i + 1 }))
        .filter(({ line }) => line.includes("wir-chip-'") || line.includes('wir-chip-"'))
        .filter(({ line }) => !line.includes('chipToken('));
    assert.deepStrictEqual(offenders.map((o) => o.n), [],
        'a wir-chip- class built without chipToken(): line(s) '
        + offenders.map((o) => o.n + ' -> ' + o.line.trim()).join(' | '));
});

// ---------------------------------------------------------------------------
// jsArg — the escape that guards every inline handler
// ---------------------------------------------------------------------------

test('jsArg survives every breakout shape a rule id can carry', () => {
    // A rule id crosses TWO parsers on its way into an inline onclick: the HTML attribute
    // parser reads it first, then the JS one. esc() escapes for the first and leaves the single
    // quote alone, which is how `x');alert(1)//` became a live handler on the broken-rule
    // banner — in a self-contained page that gets emailed and published to CI artifact stores.
    //
    // The claim asserted here is the same one xss-escaping.spec.ts makes structurally: whatever
    // goes in, what comes out is ONE well-formed string literal with nothing after it.
    const ONE_LITERAL = /^'(?:[^'\\]|\\.)*'$/;
    [
        "x');alert(1)//",
        "x\\');alert(1)//",
        'plain-id',
        '"double"',
        "line\nbreak",           // JtlParser really can produce these from quoted CSV labels
        'tab\there',
        '</script><script>alert(1)</script>',
        '&#39;;alert(1);//',
        ' paragraph-separator'
    ].forEach((hostile) => {
        const out = A._jsArg(hostile);
        assert.ok(ONE_LITERAL.test(out), 'not a single literal for ' + JSON.stringify(hostile)
            + ': ' + out);
        assert.ok(!/[\r\n]/.test(out),
            'a raw newline in a quoted attribute leaves an unterminated string: ' + out);
    });
});

// ---------------------------------------------------------------------------
// R2-1, client half (M7d X): the evaluator judges Java's precision, the page shows 2dp
// ---------------------------------------------------------------------------

test('a rule is judged on the exact figure, not the 2dp one the tables show', () => {
    // ReportDataSerializer carries every figure a client rule can name at 2dp for display, and the
    // ones round2 changed again under `exact`. Java judges 12.496 against `lt 12.5` and FAILs it;
    // judged at 2dp it sat ON the limit and passed, so a rule re-judged after an edit flipped.
    const data = {
        samplers: [{ name: 'A', count: 10, errorCount: 0, mean: 12.5, p95: 20, exact: { mean: 12.496, p95: 19.996 } }],
        overall: { totalSamples: 10, mean: 12.5, errorRate: 0, duration: 60, exact: { mean: 12.496, duration: 59.996 } }
    };
    const rules = [
        A._ruleFromJson({ id: 'mean', target: { sampler: 'A' }, metric: 'mean', fail: { lt: 12.5 } }, 0),
        A._ruleFromJson({ id: 'p95', target: { sampler: 'A' }, metric: 'p95', fail: { gte: 20 } }, 1),
        A._ruleFromJson({ id: 'run-mean', target: { scope: 'overall' }, metric: 'mean', fail: { lt: 12.5 } }, 2),
        A._ruleFromJson({ id: 'run-duration', target: { scope: 'overall' }, metric: 'duration', fail: { lt: 60 } }, 3)
    ];
    const result = A.evaluate(rules, data).rules;
    assert.deepStrictEqual(result.map((r) => r.status), ['FAIL', 'PASS', 'FAIL', 'FAIL']);
    assert.strictEqual(result[0].results[0].actual, 12.496, 'the actual is the exact figure');
    assert.strictEqual(data.samplers[0].mean, 12.5, 'the payload the charts read is not touched');
});

test('a rule-named quantile shipped as null is NO_VALUE, not a measured 0 (L16)', () => {
    /* GatePayloadBuilder.judged writes a quantile the digest could not answer as null. Read as a
       number, null is 0 — `null < 5` is true — so a p85 rule over it FAILed `lt 5` and PASSed
       `gt 5` here while Java said NO_VALUE. Both directions, sampler and whole run. */
    const data = {
        samplers: [{ name: 'A', count: 10, errorCount: 0, mean: 12.5 }],
        overall: { totalSamples: 10, mean: 12.5, errorRate: 0, duration: 60 },
        assertPercentiles: { A: { 85: null }, __overall__: { 85: null } }
    };
    const rules = [
        A._ruleFromJson({ id: 'lt', target: { sampler: 'A' }, metric: 'p85', fail: { lt: 5 } }, 0),
        A._ruleFromJson({ id: 'gt', target: { sampler: 'A' }, metric: 'p85', fail: { gt: 5 } }, 1),
        A._ruleFromJson({ id: 'run', target: { scope: 'overall' }, metric: 'p85', fail: { lt: 5 } }, 2)
    ];
    const result = A.evaluate(rules, data).rules;
    result.forEach((r) => {
        assert.strictEqual(r.status, 'NOT_JUDGED', r.id + ' was judged ' + r.status + ' on a null quantile');
        assert.strictEqual(r.notJudged, 'NO_VALUE', r.id);
        assert.strictEqual(r.results[0].actual, null, r.id + ': the actual is unanswerable, not 0');
    });
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
