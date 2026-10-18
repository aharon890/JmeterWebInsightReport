/**
 * Unit tests for the numbers the Server tab takes from a run to propose a band (Batch 2,
 * plans/server-tab-followups.md) — the pure half; the picker, Set thresholds and the review are
 * in Playwright (server-picker.spec.ts, server-seeding.spec.ts).
 *
 *   - SF8: on a capped series the number is the build's for the metric asked about
 *     (`judgedActuals[metric]`), never the page's thinned copy and never another metric's; with no
 *     build number, or over a window the build did not judge, there is none.
 *   - SF9: a slope metric's number is its slope per minute, never the level it was fitted to.
 *   - SF10: a band Java says can never fire (`lt 0` on a metric whose run touched 0) is never
 *     proposed; the safe default stands in, and without one the row asks.
 *   - SF12: one reader for a band box — blank or spaces is no band, never 0.
 *   - F2: a percentage proposes inside 0-100. A catalogue metric that declares a `ceiling` is never
 *     proposed a band at or past it, nor one the rounding put on the run; the headroom is the
 *     smaller of the run's share and the same share of the room left below the ceiling, so nothing
 *     at or below half of it moves. With no room left the row asks, and says why.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadCatalogue, loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;

if (!SK || !SK._observedOfSeries || !SK._prefillBands || !SK._bandBox || !SK._kpiFromJson) {
    throw new Error('report.js exposed no WIR.serverKpis._observedOfSeries/_prefillBands/_bandBox'
        + ' — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const POSTGRES = SK.catalogueType('postgres');
const KAFKA = SK.catalogueType('kafka');
const COMPONENTS = { 'orders-db': { type: 'postgres' }, events: { type: 'kafka' } };

/** The assert the file would hold for a bare catalogue metric, read by the mapper's parser. */
function bare(component, metric) {
    return SK._kpiFromJson({ component: component, metric: metric }, COMPONENTS);
}

/** One instance: `count` points a second apart from t0, value(i). */
function instance(label, count, value) {
    const points = [];
    for (let i = 0; i < count; i++) points.push([1700000000000 + i * 1000, value(i)]);
    return { labels: { instance: label }, points: points };
}

const WINDOW = [-Infinity, Infinity];
const STEP = 1000;

/** A run as runOf hands one to prefillBands: what it would compare, in its reducer. */
function run(observed, reducer) {
    return { observed: observed, reducer: reducer, points: [], top: 0, bottom: 0, instances: 1, drawn: 1 };
}

// ---------------------------------------------------------------- SF8

test('SF8: a capped series is the build\'s number, however low the page\'s copy reads', () => {
    const kpi = bare('orders-db', 'transaction.longest.seconds');
    const series = {
        instancesCapped: true, instanceCount: 21, judgedActuals: { 'transaction.longest.seconds': 120 },
        instances: [instance('pg-1', 49, (i) => 0.5 + (i % 25) / 10)]       // at most 2.9
    };
    assert.strictEqual(SK._observedOfSeries(kpi, series, WINDOW, STEP), 120);
    // The bands then move from 120, so they cannot FAIL the run they were suggested from.
    const bands = plain(SK._prefillBands(POSTGRES.metrics['transaction.longest.seconds'],
        run(120, kpi.reducer), 'suggested'));
    assert.deepStrictEqual(bands, { warnOp: 'gt', warnValue: '150', failOp: 'gt', failValue: '180' });
});

test('SF8: a series capped on points alone is the build\'s number too', () => {
    const kpi = bare('orders-db', 'transaction.longest.seconds');
    const inst = instance('pg-1', 10, () => 1);
    inst.pointsCapped = true;
    assert.strictEqual(SK._observedOfSeries(kpi,
        { judgedActuals: { 'transaction.longest.seconds': 7 }, instances: [inst] }, WINDOW, STEP), 7);
});

test('SF8: two metrics sharing one capped series each read their own build number, never the other\'s', () => {
    // Kafka's consumer.lag.total (max) and consumer.lag.final (last) are one query: the collector
    // writes one series, recorded as one of them. Read as one number, total showed final's 100
    // where the build compares 10,000, and its band FAILed the run it was suggested from.
    const total = bare('events', 'consumer.lag.total');
    const final = bare('events', 'consumer.lag.final');
    assert.strictEqual(total.query, final.query, 'one query for both');
    const inst = () => [instance('g-1', 10, () => 40)];
    const both = { metric: 'consumer.lag.final', instancesCapped: true, instanceCount: 21, instances: inst(),
        judgedActuals: { 'consumer.lag.total': 10000, 'consumer.lag.final': 100 } };
    assert.strictEqual(SK._observedOfSeries(total, both, WINDOW, STEP), 10000);
    assert.strictEqual(SK._observedOfSeries(final, both, WINDOW, STEP), 100);
    // A build number for the recorded metric only is not the other metric's: none for it.
    const one = { metric: 'consumer.lag.final', instancesCapped: true, instanceCount: 21, instances: inst(),
        judgedActuals: { 'consumer.lag.final': 100 } };
    assert.strictEqual(SK._observedOfSeries(total, one, WINDOW, STEP), null);
    assert.strictEqual(SK._observedOfSeries(final, one, WINDOW, STEP), 100);
});

test('SF8: a capped series judged over another window than the build\'s has no build number', () => {
    // The build excluded a ramp-up (state.excludeRampUp, from the shipped file); a file opened on
    // the page that keeps it judges [start, end] instead, and the build's number answers for
    // [rampUpEnd, end] only. Shown and seeded over the new window, its bands FAILed that run.
    const kpi = bare('orders-db', 'transaction.longest.seconds');
    const series = { instancesCapped: true, instanceCount: 21, instances: [instance('pg-1', 10, () => 3)],
        judgedActuals: { 'transaction.longest.seconds': 120 } };
    const state = SK._state;
    const saved = { metrics: state.metrics, excludeRampUp: state.excludeRampUp };
    try {
        state.metrics = { window: { start: 1000, end: 61000, rampUpEnd: 31000 } };
        state.excludeRampUp = true;
        assert.strictEqual(SK._observedOfSeries(kpi, series, [31000, 61000], STEP), 120, 'the build\'s window');
        assert.strictEqual(SK._observedOfSeries(kpi, series, [1000, 61000], STEP), null, 'the ramp-up kept');
        // A custom seed's judgedMax answers for the same window, and is withheld the same way.
        assert.strictEqual(SK._buildNumber(7, [31000, 61000]), 7);
        assert.strictEqual(SK._buildNumber(7, [1000, 61000]), null);
        // With no ramp-up in the payload the flag moves nothing, and the number holds.
        state.metrics = { window: { start: 1000, end: 61000, rampUpEnd: null } };
        assert.strictEqual(SK._observedOfSeries(kpi, series, [1000, 61000], STEP), 120, 'no ramp-up');
    } finally {
        state.metrics = saved.metrics;
        state.excludeRampUp = saved.excludeRampUp;
    }
});

test('SF8: a capped series the build shipped no number for has none, and proposes nothing from the run', () => {
    const kpi = bare('orders-db', 'transaction.longest.seconds');
    const series = { instancesCapped: true, instanceCount: 21, instances: [instance('pg-1', 10, () => 3)] };
    assert.strictEqual(SK._observedOfSeries(kpi, series, WINDOW, STEP), null);
    // No safe default on this metric: the row is left to ask, under every rule.
    ['suggested', 'headroom20', 'headroom50'].forEach((rule) => {
        assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics['transaction.longest.seconds'],
            run(null, kpi.reducer), rule)), { warnOp: 'gt', warnValue: '', failOp: 'gt', failValue: '' }, rule);
    });
    // A metric with one keeps it, under every rule.
    ['suggested', 'headroom20', 'headroom50'].forEach((rule) => {
        assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics.deadlocks, run(null, null), rule)),
            { warnOp: 'gt', warnValue: '', failOp: 'gt', failValue: '0' }, rule);
    });
});

test('SF8: an uncapped series is the mirror\'s fold, with the catalogue\'s reducer and across', () => {
    // up: min per instance, then min across (SF2) — the database that went down, not its twin.
    const kpi = bare('orders-db', 'up');
    const series = {
        instances: [instance('pg-1', 10, (i) => (i === 4 ? 0 : 1)), instance('pg-2', 10, () => 1)]
    };
    assert.strictEqual(SK._observedOfSeries(kpi, series, WINDOW, STEP), 0);
});

// ---------------------------------------------------------------- SF9

test('SF9: a slope metric is its slope per minute, not its level', () => {
    const kpi = bare('orders-db', 'database.size.growth');
    assert.strictEqual(kpi.reducer.kind, 'slope');
    // E27's two databases: 1,000,000 B/s and 200,000 B/s, from levels in the billions.
    const series = {
        instances: [
            instance('pg-1', 49, (i) => 5000000000 + i * 1000000),
            instance('pg-2', 49, (i) => 2000000000 + i * 200000)
        ]
    };
    const observed = SK._observedOfSeries(kpi, series, WINDOW, STEP);
    assert.ok(Math.abs(observed - 60000000) < 1e-3, 'the worst database grows 60,000,000 B/min, read ' + observed);
    const bands = plain(SK._prefillBands(POSTGRES.metrics['database.size.growth'], run(observed, kpi.reducer),
        'suggested'));
    assert.deepStrictEqual(bands, { warnOp: 'gt', warnValue: '75000000', failOp: 'gt', failValue: '90000000' });
});

// ---------------------------------------------------------------- SF10

test('SF10: a lower metric whose run touched 0 is never prefilled lt 0, and with no safe default asks', () => {
    const kpi = bare('events', 'brokers');
    ['suggested', 'headroom20', 'headroom50'].forEach((rule) => {
        assert.deepStrictEqual(plain(SK._prefillBands(KAFKA.metrics.brokers, run(0, kpi.reducer), rule)),
            { warnOp: 'lt', warnValue: '', failOp: 'lt', failValue: '' }, rule);
    });
});

test('SF10: ...and with a safe default takes it, under every rule — up FAILs at 0 rather than PASSing lt 0', () => {
    const kpi = bare('orders-db', 'up');
    ['suggested', 'headroom20', 'headroom50'].forEach((rule) => {
        assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics.up, run(0, kpi.reducer), rule)),
            { warnOp: 'lt', warnValue: '', failOp: 'lt', failValue: '1' }, rule);
    });
});

test('SF10: a lower metric with room below it keeps its headroom (cache.hit.pct at 97)', () => {
    const kpi = bare('orders-db', 'cache.hit.pct');
    assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics['cache.hit.pct'], run(97, kpi.reducer), 'suggested')),
        { warnOp: 'lt', warnValue: '73', failOp: 'lt', failValue: '49' });
    assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics['cache.hit.pct'], run(97, kpi.reducer), 'headroom20')),
        { warnOp: 'lt', warnValue: '87', failOp: 'lt', failValue: '78' });
});

test('SF10: the test is Java\'s, so a slope — which can be negative — is not refused lt 0', () => {
    const slope = SK._kpiFromJson({ component: 'c', query: 'q', reduce: { kind: 'slope' }, fail: { gt: 1 } },
        { c: { type: 'custom' } }).reducer;
    const lower = { direction: 'lower', unit: 'B' };
    assert.deepStrictEqual(plain(SK._prefillBands(lower, run(0, slope), 'suggested')),
        { warnOp: 'lt', warnValue: '', failOp: 'lt', failValue: '0' });
});

test('SF10: an upward metric at 0 still fails on any rise (gt 0), which can fire', () => {
    const kpi = bare('orders-db', 'transaction.longest.seconds');
    assert.deepStrictEqual(plain(SK._prefillBands(POSTGRES.metrics['transaction.longest.seconds'],
        run(0, kpi.reducer), 'headroom20')), { warnOp: 'gt', warnValue: '', failOp: 'gt', failValue: '0' });
});

// ---------------------------------------------------------------- F2

const RULES = ['suggested', 'headroom20', 'headroom50'];
const empty = (op) => ({ warnOp: op, warnValue: '', failOp: op, failValue: '' });
const prefill = (m, observed, reducer, rule) => plain(SK._prefillBands(m, run(observed, reducer), rule));

test('F2: the catalogue declares exactly the ceilings CatalogueTest lists — 1 on a 0/1 flag, 100 on a share, 10 s on a histogram p95', () => {
    // Read through the page's own catalogue (what chartData.serverCatalogue hands it), over every
    // type the index lists — so a ceiling added anywhere else fails here until it is decided on.
    const found = {};
    loadCatalogue().order.forEach((key) => {
        const type = SK.catalogueType(key);
        Object.keys(type.metrics).forEach((name) => {
            if (Object.prototype.hasOwnProperty.call(type.metrics[name], 'ceiling')) {
                found[key + '.' + name] = type.metrics[name].ceiling;
            }
        });
    });
    // The same set, and the same reasons, as CatalogueTest.theBoundedMetricsDeclareTheirCeilingAndNoOtherDoes.
    assert.deepStrictEqual(found, {
        'postgres.up': 1,
        'postgres.connections.used.pct': 100,
        'postgres.rollback.ratio.pct': 100,
        'postgres.cache.hit.pct': 100,
        'kafka.network.processor.idle': 100,
        'redis.up': 1,
        'redis.clients.connected.pct': 100,
        'redis.keyspace.hit.pct': 100,
        'redis.cpu.main.pct': 100,
        'redis.replication.link.up': 1,
        'host-linux.cpu.busy.pct': 100,
        'host-linux.cpu.iowait.pct': 100,
        'host-linux.cpu.steal.pct': 100,
        'host-linux.memory.available.pct': 100,
        'host-linux.disk.busy.pct': 100,
        'host-linux.filesystem.free.pct': 100,
        'host-linux.tcp.retransmit.pct': 100,
        'host-linux.fd.used.pct': 100,
        'aws-ec2.ebs.burst.balance.pct': 100,
        'aws-ec2.ebs.limit.exceeded': 1,
        'aws-ec2.status.check.failed': 1,
        'oracle.up': 1,
        'oracle.sessions.used.pct': 100,
        'oracle.processes.used.pct': 100,
        'oracle.tablespace.used.pct': 100,
        'oracle.cache.buffer.hit.pct': 100,
        'influxdb2.up': 1,
        'influxdb2.write.latency.p95': 10,
        'influxdb2.write.errors.pct': 100,
        'influxdb2.query.latency.p95': 10,
        'influxdb2.query.errors.pct': 100,
        'influxdb3.up': 1,
        'influxdb3.write.latency.p95': 10,
        'influxdb3.write.errors.pct': 100,
        'influxdb3.query.latency.p95': 10,
        'influxdb3.query.errors.pct': 100,
        'influxdb3.query.permit.wait.p95': 10,
        'jvm.heap.used.pct': 100,
        'jvm.cpu.process.pct': 100,
        'http-service.errors.5xx.pct': 100,
        'kubernetes-pod.cpu.throttled.pct': 100,
        'kubernetes-pod.memory.limit.used.pct': 100,
        'mysql.up': 1,
        'mysql.buffer.pool.hit.pct': 100
    });
});

test('F2: connections.used.pct at E27\'s 68 is proposed inside 100 — 76/84, and 71/74 at 20%', () => {
    // Headroom from the run alone gave fail > 102: a band the metric can never reach, on a row
    // that then reads PASS forever. The room left below 100 is 32, so the headroom is 50% of that.
    const m = POSTGRES.metrics['connections.used.pct'];
    const r = bare('orders-db', 'connections.used.pct').reducer;
    assert.deepStrictEqual(prefill(m, 68, r, 'suggested'), { warnOp: 'gt', warnValue: '76', failOp: 'gt', failValue: '84' });
    assert.deepStrictEqual(prefill(m, 68, r, 'headroom50'), { warnOp: 'gt', warnValue: '76', failOp: 'gt', failValue: '84' });
    assert.deepStrictEqual(prefill(m, 68, r, 'headroom20'), { warnOp: 'gt', warnValue: '71', failOp: 'gt', failValue: '74' });
});

test('F2: rollback.ratio.pct at 70 is proposed 78/85, under its ceiling', () => {
    const m = POSTGRES.metrics['rollback.ratio.pct'];
    assert.deepStrictEqual(prefill(m, 70, bare('orders-db', 'rollback.ratio.pct').reducer, 'suggested'),
        { warnOp: 'gt', warnValue: '78', failOp: 'gt', failValue: '85' });
});

test('F2: from 99 up there is no room below the ceiling — the row asks, and says why', () => {
    // 99 + 0.5 rounds to 100, which can never fire; 99.3's warn of 99.475 rounds to 99, which
    // WARNs on the run it came from. Both are checked after rounding, so neither is proposed.
    const m = POSTGRES.metrics['connections.used.pct'];
    const r = bare('orders-db', 'connections.used.pct').reducer;
    [99, 99.3, 100, 103].forEach((observed) => {
        RULES.forEach((rule) => {
            assert.deepStrictEqual(prefill(m, observed, r, rule), empty('gt'), observed + ' ' + rule);
        });
        const text = SK._noBandText(m, run(observed, r));
        assert.ok(/leaves no room below this metric’s ceiling of 100 %, so set a band$/.test(text),
            observed + ': ' + text);
        assert.ok(!/negative/.test(text), observed + ' is not a negative-only band: ' + text);
    });
    assert.strictEqual(SK._noBandText(m, run(99, r)),
        'none from this run — peak 99 % leaves no room below this metric’s ceiling of 100 %, so set a band');
});

test('F2: a sweep of 0-100 by 0.01 proposes no band at or past a ceiling and none on the run, and moves nothing at or below half of it', () => {
    let checked = 0;
    let swept = 0;
    loadCatalogue().order.forEach((key) => {
        const type = SK.catalogueType(key);
        Object.keys(type.metrics).forEach((name) => {
            const m = type.metrics[name];
            if (m.direction !== 'higher' || typeof m.ceiling !== 'number') return;
            const ceiling = m.ceiling;
            swept += ceiling * 100 + 1;
            const unbounded = Object.assign({}, m);
            delete unbounded.ceiling;
            const r = SK._kpiFromJson({ component: 'c', metric: name }, { c: { type: key } }).reducer;
            // A safe default is an invariant (status.check.failed fails above 0), proposed whatever
            // the run did: it may sit on the run, and only the ceiling holds it.
            const safe = m.safeDefault ? prefill(m, null, r, 'suggested') : null;
            for (let i = 0; i <= ceiling * 100; i++) {
                const x = i / 100;
                RULES.forEach((rule) => {
                    const b = prefill(m, x, r, rule);
                    const invariant = safe !== null && JSON.stringify(b) === JSON.stringify(safe);
                    [b.warnValue, b.failValue].forEach((text) => {
                        if (text === '') return;
                        const v = Number(text);
                        assert.ok(v < ceiling, `${key}.${name} at ${x} (${rule}): ${v} is at or past the ceiling`);
                        assert.ok(invariant || v > x || (x === 0 && v === 0),
                            `${key}.${name} at ${x} (${rule}): ${v} is not beyond the run`);
                    });
                    if (x <= ceiling / 2) {
                        assert.deepStrictEqual(b, prefill(unbounded, x, r, rule),
                            `${key}.${name} at ${x} (${rule}) moved, though it is at or below half the ceiling`);
                    }
                    checked++;
                });
            }
        });
    });
    // Every upward metric with a ceiling - postgres connections.used.pct and rollback.ratio.pct,
    // and the newer types' percentages and flags - every 0.01 up to it, under three rules.
    assert.ok(swept >= 2 * 10001, 'the sweep reached postgres\'s two upward percentages at least');
    assert.strictEqual(checked, swept * 3);
});

test('F2: a metric with no ceiling is untouched — an unbounded peak still takes its full headroom', () => {
    // transaction.longest.seconds has no ceiling: 120 s gives 150/180, as SF8 pins.
    const m = POSTGRES.metrics['transaction.longest.seconds'];
    assert.strictEqual(m.ceiling, undefined);
    assert.deepStrictEqual(prefill(m, 120, bare('orders-db', 'transaction.longest.seconds').reducer, 'suggested'),
        { warnOp: 'gt', warnValue: '150', failOp: 'gt', failValue: '180' });
    // A lower metric's ceiling is not its worse side: cache.hit.pct keeps SF10's 73/49 at 97.
    assert.deepStrictEqual(prefill(POSTGRES.metrics['cache.hit.pct'], 97, bare('orders-db', 'cache.hit.pct').reducer,
        'suggested'), { warnOp: 'lt', warnValue: '73', failOp: 'lt', failValue: '49' });
});

test('F2: the ceiling sentence is for an upward band only — cache.hit.pct at 0 keeps SF10\'s', () => {
    // cache.hit.pct declares a ceiling of 100 on its BETTER side and has no safe default, so a run
    // at 0 drops `lt 0` (SF10) and reaches noBandText. What stopped it is the bottom, not the top:
    // "low 0 % leaves no room below this metric's ceiling of 100 %" would be false.
    const m = POSTGRES.metrics['cache.hit.pct'];
    const r = bare('orders-db', 'cache.hit.pct').reducer;
    RULES.forEach((rule) => assert.deepStrictEqual(prefill(m, 0, r, rule), empty('lt'), rule));
    const text = SK._noBandText(m, run(0, r));
    assert.strictEqual(text, 'none from this run — fail < 0 % can only fire on a negative value, so set a band');
    assert.ok(!/ceiling/.test(text), text);
});

// ---------------------------------------------------------------- SF12

test('SF12: a band box holding nothing or only spaces is no band, never 0', () => {
    ['', ' ', '   ', '\t', null, undefined].forEach((text) => {
        assert.deepStrictEqual(plain(SK._bandBox('lt', text)), { empty: true, number: null, json: null },
            JSON.stringify(text));
    });
});

test('SF12: a number is read in the mapper\'s grammar, trimmed', () => {
    assert.deepStrictEqual(plain(SK._bandBox('gt', ' 4.3 ')), { empty: false, number: 4.3, json: { gt: 4.3 } });
    assert.deepStrictEqual(plain(SK._bandBox('lt', '0')), { empty: false, number: 0, json: { lt: 0 } });
    // Not a number: kept as the text, so kpiFromJson refuses it in the mapper's words.
    assert.deepStrictEqual(plain(SK._bandBox('gt', '9oo')), { empty: false, number: null, json: { gt: '9oo' } });
    assert.deepStrictEqual(plain(SK._bandBox('gt', '50ms')), { empty: false, number: null, json: { gt: '50ms' } });
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
