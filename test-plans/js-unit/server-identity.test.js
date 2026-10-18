/**
 * Unit tests for a catalogue metric's identity on the page — the mirror of
 * CatalogueMetric.identity, which the mapper, the collector and the payload writer resolve an
 * assert's series by.
 *
 * A metric with a prometheus query is known by it, the component's match expanded, exactly as
 * before. A metric with none — a KPI only an exported file can answer, named per tool under
 * `names` — is known by the neutral `<type>:<metric>`, the component's match in braces when it
 * has one. Java and the page must agree on that string character for character, or the page asks
 * for a series the payload does not hold under that name: the literals below are the ones
 * ServerMetricsMapperTest and CatalogueTest pin.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/**
 * The acme type SourcesAndCollectorTest.acme() builds, with ServerMetricsMapperTest's
 * `reduce: 'avg'` on pool.used.pct, as Catalogue.pageJson() would ship it.
 */
const ACME = {
    order: ['acme'],
    types: {
        acme: {
            type: 'acme', version: 1, exporter: 'acme_exporter',
            metrics: [
                { name: 'conns', queries: { prometheus: 'acme_conns${match}' } },
                { name: 'pool.used.pct', unit: '%', reduce: 'avg',
                    names: { telegraf: null, datadog: { metric: 'acme.pool.pct', kind: 'gauge', scale: 100 } } },
                { name: 'waits', names: { otel: { metric: 'acme.waits', kind: 'counter' } } }
            ]
        }
    }
};

function withAcme() {
    const SK = loadReportJs().serverKpis;
    if (!SK || !SK.setCatalogue || !SK._kpiFromJson) {
        throw new Error('report.js exposed no WIR.serverKpis.setCatalogue/_kpiFromJson — the harness is stale');
    }
    SK.setCatalogue(ACME);
    return SK;
}

function queryOf(SK, metric, match) {
    const component = { type: 'acme' };
    if (match !== undefined) component.match = match;
    const kpi = SK._kpiFromJson({ component: 'db', metric: metric, fail: { gt: 90 } }, { db: component });
    assert.deepStrictEqual(Array.from(kpi.problems), [], metric + ' is refused: ' + kpi.problems.join('; '));
    return kpi.query;
}

test('a metric with no prometheus query is known by <type>:<metric>, the match in braces', () => {
    const SK = withAcme();
    assert.strictEqual(queryOf(SK, 'pool.used.pct', ' instance="db-1" '), 'acme:pool.used.pct{instance="db-1"}');
    assert.strictEqual(queryOf(SK, 'pool.used.pct'), 'acme:pool.used.pct');
    assert.strictEqual(queryOf(SK, 'pool.used.pct', '   '), 'acme:pool.used.pct');
    assert.strictEqual(queryOf(SK, 'waits', 'instance="db-1"'), 'acme:waits{instance="db-1"}');
});

test('the match is trimmed as String.trim trims it, so a no-break space stays — as in Java', () => {
    const SK = withAcme();
    const nbsp = String.fromCharCode(0xA0);
    assert.strictEqual(queryOf(SK, 'pool.used.pct', '\tinstance="db-1"' + nbsp + ' '),
        'acme:pool.used.pct{instance="db-1"' + nbsp + '}');
});

test('a metric with a prometheus query keeps it as its identity, the match expanded', () => {
    const SK = withAcme();
    assert.strictEqual(queryOf(SK, 'conns', 'instance="db-1"'), 'acme_conns{instance="db-1"}');
    assert.strictEqual(queryOf(SK, 'conns'), 'acme_conns');
});

test('the catalogue-supplied unit and reducer still come with a metric that has no query', () => {
    const SK = withAcme();
    const kpi = SK._kpiFromJson({ component: 'db', metric: 'pool.used.pct', fail: { gt: 90 } },
        { db: { type: 'acme' } });
    assert.strictEqual(kpi.unit, '%');
    assert.strictEqual(kpi.reducer && kpi.reducer.kind, 'avg', 'the catalogue\'s reduce');
    assert.strictEqual(kpi.writtenQuery, null, 'the identity is the catalogue\'s, never written back');
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
