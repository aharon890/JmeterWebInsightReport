/**
 * Unit tests for what the server-assert editor and Generate from sources refuse, warn about and
 * write (M7c group D) — the pure half; the typing, the board and the toast are in Playwright.
 *
 *   - R1-5: a Save stopped at the KPI's own refusal (KpiUsability), and the two refusals the
 *     evaluator makes at run time from the REST of the file went straight past it: a sustainedFor
 *     that is not a whole number of the source's step, and a requiresLoad/divideBy the client rule
 *     set cannot satisfy. Both are INVALID_RULE at the next run — a config error, exit 1.
 *   - C-8 / R1-9: a seed "passes on its own run by construction" — except when the series is
 *     negative (1.5 x -10 = -15, and `gt -15` FAILs at -10), or names a source the file no longer
 *     declares.
 *   - C-13: a breach budget of 0.07 printed as 7.000000000000001%.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;

if (!SK || !SK._saveRefusal || !SK._seedThreshold || !SK._percentText || !SK._kpiWarnings) {
    throw new Error('report.js exposed no WIR.serverKpis._saveRefusal/_seedThreshold/_percentText'
        + '/_kpiWarnings — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const BLOCK = {
    components: {
        c: { type: 'custom' }
    }
};

/* Collections taken at a 1s step and at the collector's default, as the page holds them. The
   step is wir-collect's --step now (C8), so the page reads it off what was collected. */
const AT_1S = { sources: [{ id: 'metrics', stepMillis: 1000 }] };
const AT_DEFAULT = { sources: [{ id: 'metrics', stepMillis: 15000 }] };

/** One KPI through the production parser, against BLOCK's components. */
function kpi(json) {
    return SK._kpiFromJson(Object.assign({ id: 'k', component: 'c', query: 'q',
        reduce: { kind: 'max' }, fail: { gt: 1 } }, json), BLOCK.components);
}

/** A client rule result, the shape WIR.asserts.evaluate returns. */
function rule(id, metric, extra) {
    return Object.assign({ id: id, metric: metric, status: 'PASS', matched: 1, results: [] }, extra);
}

// ---------------------------------------------------------------- C-13

test('C-13: a breach budget reads as the percentage written, not 0.07 * 100', () => {
    assert.strictEqual(0.07 * 100 === 7, false, 'the noise this test exists for is gone from IEEE');
    assert.strictEqual(SK._percentText(0.07), '7');
    assert.strictEqual(SK._percentText(0.57), '57', '0.57 * 100 is 56.99999999999999');
    assert.strictEqual(SK._percentText(0.333), '33.3');
    assert.strictEqual(SK._percentText(0.005), '0.5');
    assert.strictEqual(SK._percentText(0), '0');
    assert.strictEqual(SK._percentText(1), '100');
    // Plain notation, the way Java's BigDecimal.toPlainString writes it.
    assert.strictEqual(SK._percentText(0.000001), '0.0001');
    assert.strictEqual(SK._percentText(0.000000001), '0.0000001');
});

// ---------------------------------------------------------------- C38

test('C38: a load rule\'s measurement is quoted as ServerKpiEvaluator.formatValue writes it', () => {
    // Each right-hand side is what ServerKpiEvaluator.formatValue(d) returned on JDK 21.0.6 on
    // 2026-10-07, called reflectively with no band (a band of 0 only): round2's number through
    // Digits.beside, spelled %.2f when it has at most two decimals and with every digit otherwise.
    // It was String.format(Locale.US, "%.2f", d) until Q19, which rounds HALF_UP on the shortest
    // decimal digits: the ties moved to round2's number (1.005 and -1.005 to '1.00' and '-1.00',
    // 9.995 to '9.99'), and a value two decimals wrote as zero is never zero ('0.0000001',
    // '-0.001', '0.0005' were '0.00' and '-0.00'). 100000 is the one the board's toLocaleString
    // got wrong.
    [
        [1.005, '1.00'], [24.765, '24.77'], [0.125, '0.13'], [2.675, '2.68'], [1.115, '1.12'],
        [0.005, '0.01'], [0.015, '0.02'], [1e-7, '0.0000001'], [123456.785, '123456.79'],
        [24.76060606, '24.76'], [-1.005, '-1.00'], [-0.001, '-0.001'], [0.0005, '0.0005'],
        [12345678.5, '12345678.50'], [9.995, '9.99'], [99.995, '100.00'], [0.045, '0.05'],
        [12.5, '12.50'], [100000, '100000'], [0, '0'], [-0, '0'], [-7, '-7'],
        [1.5e21, '1500000000000000000000.00'], [7.7e-7, '0.00000077'],
    ].forEach(([value, java]) => {
        assert.strictEqual(SK._javaMeasured(value), java, 'formatValue(' + value + ')');
    });
    assert.strictEqual(SK._javaMeasured(NaN), '-');
    assert.strictEqual(SK._javaMeasured(Infinity), 'Infinity');
});

test('Q19: a load rule\'s measurement is never quoted on or across a band the rule declares', () => {
    // The same probe, with the load rule's bands: the digits that keep the number on its side of
    // each, as the CI/CD JSON and JUnit write the rule's actual. %.2f quoted 12.496 under lt 12.5
    // as '12.50' and G9's 8.29892736363825 under lt 8.3 as '8.30', on the band each had FAILed.
    [
        [12.496, [12.5], '12.496'], [8.29892736363825, [8.3], '8.299'],
        [8.29892736363825, [null, 8.3], '8.299'], [8.29892736363825, [8.3, 1], '8.299'],
        [24.760647078243647, [100000], '24.76'], [12.5, [50], '12.50'],
        [0.0000077, [0.001], '0.0000077'], [2.996, [3], '2.996'], [3.004, [3], '3.004'],
    ].forEach(([value, bands, java]) => {
        assert.strictEqual(SK._javaMeasured(value, bands), java,
            'formatValue(' + value + ', ' + JSON.stringify(bands) + ')');
    });
});

// ---------------------------------------------------------------- C-8

test('C-8: a seed sits ABOVE what it was taken from, whatever the sign', () => {
    assert.strictEqual(SK._seedThreshold(900), 1350, 'the documented 1.5x for a positive series');
    assert.strictEqual(SK._seedThreshold(-10), -5, '1.5x of -10 is -15, and gt -15 FAILs at -10');
    assert.strictEqual(SK._seedThreshold(0), 0, 'an all-zero series seeds gt 0: passes, fails on any rise');
    // The seed is `fail: {gt: threshold}`: it must not fire on the value it was derived from.
    for (let x = -2000; x <= 2000; x += 0.37) {
        assert.ok(!(x > SK._seedThreshold(x)), `gt ${SK._seedThreshold(x)} fires on its own ${x}`);
    }
    [0.004, -0.004, 6.66, -6.66, 1e-9, -1e-9].forEach((x) => {
        assert.ok(!(x > SK._seedThreshold(x)), `gt ${SK._seedThreshold(x)} fires on its own ${x}`);
    });
});

// ---------------------------------------------------------------- R1-5: the step

test('R1-5: the step the next collection uses is the one this collection was taken at, or 15s', () => {
    assert.strictEqual(SK._declaredStepOf(AT_1S), 1000);
    assert.strictEqual(SK._declaredStepOf(null), 15000, 'nothing collected: ServerSource.DEFAULT_STEP_MILLIS');
    assert.strictEqual(SK._declaredStepOf({ sources: [] }), 15000);
});

test('R1-5: a sustainedFor no window of the collection\'s step can span is refused before a Save', () => {
    const odd = kpi({ reduce: { kind: 'max', over: { sustainedFor: '1500ms' } } });
    const refusal = SK._saveRefusal(odd, BLOCK, [], AT_1S);
    /* The collection's step, and the flag that sets it (U8d) — "the source's 1s steps" sent a
       reader looking for a source the file no longer has (C8). ServerKpiEvaluator's words. */
    assert.ok(refusal && refusal.indexOf("1.5s is not a whole number of the collection's 1s steps") >= 0,
        'refusal was: ' + refusal);
    assert.ok(refusal.indexOf('or collect with a finer wir-collect --step') >= 0, 'refusal was: ' + refusal);
    assert.ok(refusal.indexOf("the source's") < 0, 'refusal was: ' + refusal);

    assert.strictEqual(SK._saveRefusal(kpi({ reduce: { kind: 'max', over: { sustainedFor: '2s' } } }),
        BLOCK, [], AT_1S), null);
    // Java's own example: 20s on a 15s Prometheus step — the default when none is declared.
    const twenty = kpi({ reduce: { kind: 'max', over: { sustainedFor: '20s' } } });
    assert.ok(/15s steps/.test(SK._saveRefusal(twenty, BLOCK, [], AT_DEFAULT) || ''));
    // A span no longer than one step reduces to the plain extreme, which is always answerable.
    assert.strictEqual(SK._saveRefusal(kpi({
        reduce: { kind: 'max', over: { sustainedFor: '10s' } } }), BLOCK, [], AT_DEFAULT), null);
});

// ---------------------------------------------------------------- R1-5: the client rules

test('R1-5: a load reference the evaluator refuses is refused before a Save', () => {
    const rules = [
        rule('load:throughput', 'throughput'),
        rule('load:per-sampler', 'throughput', { matched: 3 }),
        rule('p95-rule', 'p95'),
        rule('load:missed', 'throughput', { status: 'FAIL' })
    ];
    const several = SK._saveRefusal(kpi({ divideBy: 'load:per-sampler' }), BLOCK, rules);
    assert.ok(several && several.indexOf('matched 3 samplers') >= 0, 'refusal was: ' + several);
    const unknown = SK._saveRefusal(kpi({ requiresLoad: 'load:typo' }), BLOCK, rules);
    assert.ok(unknown && unknown.indexOf('not a declared client rule') >= 0, 'refusal was: ' + unknown);
    const wrongMetric = SK._saveRefusal(kpi({ requiresLoad: 'p95-rule' }), BLOCK, rules);
    assert.ok(wrongMetric && wrongMetric.indexOf('must name a throughput rule') >= 0,
        'refusal was: ' + wrongMetric);

    // A load rule that FAILED is a verdict about the run, not a defect in the file.
    assert.strictEqual(SK._saveRefusal(kpi({ requiresLoad: 'load:missed' }), BLOCK, rules), null);
    // requiresLoad on a several-sampler rule is fine: it asks whether the load held, not for one number.
    assert.strictEqual(SK._saveRefusal(kpi({ requiresLoad: 'load:per-sampler' }), BLOCK, rules), null);
    assert.strictEqual(SK._saveRefusal(kpi({ divideBy: 'load:throughput' }), BLOCK, rules), null);
});

test('R1-5: the KPI\'s own refusal still comes first, in the mapper\'s words', () => {
    const r = SK._saveRefusal(kpi({ component: 'gone' }), BLOCK, []);
    assert.ok(r && r.indexOf("component 'gone' is not declared") === 0, 'refusal was: ' + r);
});

// ---------------------------------------------------------------- R1-5: the window

test('R1-5: a sustainedFor longer than the judged window is said, never refused', () => {
    const config = SK.configFromDocument({ collection: { excludeRampUp: false } });
    const payload = { window: { start: 0, end: 30000 } };
    const long = kpi({ reduce: { kind: 'max', over: { sustainedFor: '1m' } } });
    const w = SK._windowWarning(long, config, payload);
    assert.ok(w && w.indexOf('longer than the window this run is judged over (30s)') >= 0, 'was: ' + w);
    assert.strictEqual(SK._saveRefusal(long, BLOCK, []), null, 'a longer run may span it');
    assert.strictEqual(SK._windowWarning(kpi({ reduce: { kind: 'max', over: { sustainedFor: '10s' } } }),
        config, payload), null);
    assert.strictEqual(SK._windowWarning(long, config, null), null, 'nothing collected: nothing to compare');
    // A measured window is quoted to the second: E26's read "(48464ms)" (review:D).
    const measured = SK._windowWarning(long, config, { window: { start: 0, end: 48464 } });
    assert.ok(measured && measured.indexOf('judged over (48s)') >= 0, 'was: ' + measured);
});

// ---------------------------------------------------------------- C-11

test('C-11: the warnings follow KpiUsability.check\'s early returns', () => {
    assert.deepStrictEqual(plain(SK._kpiWarnings(kpi({ fail: undefined, warn: undefined }))), [],
        'no band: refused as NO_BANDS, nothing to warn about');
    assert.deepStrictEqual(plain(SK._kpiWarnings(kpi({ reduce: { kind: 'maxx' }, fail: { lt: 0 } }))), [],
        'no reducer: nothing below it can be trusted');
    // A corridor written the long way is two directions, never "covered".
    assert.deepStrictEqual(plain(SK._kpiWarnings(kpi({ warn: { gt: 900 }, fail: { lt: 5 } }))), []);
    // lte 0 can be reached by a zero; lt 0 cannot.
    assert.deepStrictEqual(plain(SK._kpiWarnings(kpi({ fail: { lte: 0 } }))), []);
    assert.strictEqual(SK._kpiWarnings(kpi({ fail: { lt: 0 } })).length, 1);
});

// ---------------------------------------------------------------- F8 review

/* Validate query is an exact lookup (D22). F8 widened the invisible-end class to variation
   selectors and tags, which a visible character can end in: "disk" and a red heart in its emoji
   presentation ends in U+FE0F. A CSV whose metric cell carries the same text matches it, the
   series comes back OK and the assert is judged on it, and Validate still answered "Collecting
   again will not help ... no series will match it" — before it had looked. The lookup answers
   first wherever the series returned points; the padding sentence is for a series that returned
   none, or was never collected. */
test('F8 review: Validate answers with the series it found before it calls a padded query hopeless', () => {
    const NBSP = String.fromCharCode(0xA0);
    const HEART = 'disk ' + String.fromCodePoint(0x2764, 0xFE0F);
    const state = SK._state;
    const saved = state.metrics;
    const series = (query, status) => ({
        source: 'metrics', component: 'c', query: query, resolvedQuery: query, status: status,
        coverage: status === 'INSUFFICIENT' ? 0.5 : 1, instanceCount: 1,
        instances: [{ labels: {}, points: [[1000, 1], [2000, 2]] }]
    });
    const at = (query, status) => {
        state.metrics = { window: { start: 1000, end: 2000 }, sources: [{ id: 'metrics', stepMillis: 1000 }],
            series: status ? [series(query, status)] : [] };
        return plain(SK._validateQuery('metrics', query, 'c'));
    };
    try {
        const hopeless = 'Collecting again will not help: the query has an invisible character at its ';
        [HEART, NBSP + 'lag'].forEach((q) => {
            ['OK', 'PARTIAL'].forEach((status) => {
                const v = at(q, status);
                assert.strictEqual(v.tone, 'ok', status + ' ' + JSON.stringify(q) + ': ' + v.text);
                assert.ok(v.text.indexOf('Collected: 1 instance') === 0 && v.text.indexOf('status ' + status) > 0,
                    status + ' ' + JSON.stringify(q) + ': ' + v.text);
            });
            const low = at(q, 'INSUFFICIENT');
            assert.ok(low.text.indexOf('Collected with status INSUFFICIENT: ') === 0,
                'points came back, too few of them: ' + low.text);
        });
        // A series that returned nothing, or none at all: the padding is the likeliest reason, and
        // re-running wir-collect over the same text cannot change it.
        [['EMPTY', 'start (U+00A0)'], ['FAILED', 'start (U+00A0)'], [null, 'start (U+00A0)']].forEach((c) => {
            const v = at(NBSP + 'lag', c[0]);
            assert.strictEqual(v.tone, 'warn', c[0] + ': ' + v.text);
            assert.ok(v.text.indexOf(hopeless + c[1]) === 0, c[0] + ': ' + v.text);
        });
        assert.ok(at(HEART, 'EMPTY').text.indexOf(hopeless + 'end (U+FE0F)') === 0);
        // An unpadded query that was never collected is still sent to wir-collect.
        assert.ok(at('lag', null).text.indexOf('Not collected.') === 0);
    } finally {
        state.metrics = saved;
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
        console.log('       ' + (e && e.message ? e.message.split('\n')[0] : e));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed === 0 ? 0 : 1);
