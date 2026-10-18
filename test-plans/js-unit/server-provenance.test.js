/**
 * Unit tests for where a server number came from (`wir-collect --csv FILE --from TOOL`) — the pure
 * half of the Server tab's provenance lines.
 *
 *   - The Collection panel's source row says the file was read as the tool's export, from the
 *     payload source's `from`; that it was a wide CSV (`--layout wide`, Grafana's export), from its
 *     `layout`; and which zone its zone-less times were read in (`--time-zone`), from its `timeZone`;
 *     and for a long file another tool wrote, which of its columns was which (`--csv-columns`) and the
 *     one component its rows were read as (`--component`), from its `columns` and `component`.
 *   - An open row's note says which of the tool's names its series was read under, what kind of
 *     number that is and the scale it was multiplied by — `read as <metric> (<tool>, <kind>, x<scale>)`,
 *     the scale only when it is not 1 — from the payload series' `readAs`. A series read under the
 *     catalogue's own name (the escape hatch) has no `readAs`, and says nothing.
 *
 * Both are file-derived text, so both are escaped. Without --from neither appears: a page built
 * from an older payload reads as it did.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;

if (!SK || !SK._readAsText || !SK._seriesNote || !SK._collectionHtml || !SK._state || !SK._kpiFromJson) {
    throw new Error('report.js exposed no WIR.serverKpis._readAsText/_seriesNote/_collectionHtml'
        + ' — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const KPI = SK._kpiFromJson({ component: 'c', query: 'q', fail: { gt: 1 } }, { c: { type: 'custom' } });

function series(readAs) {
    const s = { source: 'metrics', component: 'c', query: 'q', resolvedQuery: 'q', status: 'OK', coverage: 1,
        instanceCount: 1, instances: [{ labels: {}, pointCount: 1, points: [[1700000000000, 1]] }] };
    if (readAs !== undefined) s.readAs = readAs;
    return s;
}

function source(from) {
    const s = { id: 'metrics', type: 'csv', status: 'OK', stepMillis: 15000, clockOffsetMs: 0, resolvedUrl: 'export.csv' };
    if (from !== undefined) s.from = from;
    return s;
}

// ---------------------------------------------------------------- the series

test('a series read under a tool\'s name says which, with its kind and its scale', () => {
    assert.strictEqual(SK._readAsText(series({ tool: 'datadog', metric: 'postgresql.percent_usage_connections',
        kind: 'gauge', scale: 100 })), 'read as postgresql.percent_usage_connections (datadog, gauge, x100)');
    assert.strictEqual(SK._readAsText(series({ tool: 'cloudwatch', metric: 'Requests', kind: 'sum-per-period',
        scale: 60 })), 'read as Requests (cloudwatch, sum-per-period, x60)');
    assert.strictEqual(SK._readAsText(series({ tool: 'cloudwatch', metric: 'Latency', kind: 'gauge', scale: 0.001 })),
        'read as Latency (cloudwatch, gauge, x0.001)');
});

test('a scale of 1 is not shown: nothing was multiplied', () => {
    assert.strictEqual(SK._readAsText(series({ tool: 'telegraf', metric: 'postgresql_xact_commit', kind: 'counter',
        scale: 1 })), 'read as postgresql_xact_commit (telegraf, counter)');
});

test('a series read under the catalogue\'s own name, or collected without --from, says nothing', () => {
    assert.strictEqual(SK._readAsText(series()), null);
    assert.strictEqual(SK._readAsText(series(null)), null);
    assert.strictEqual(SK._readAsText(null), null);
});

test('an open row\'s note carries it, escaped, and only when the series has one', () => {
    const html = SK._seriesNote(KPI, series({ tool: 'datadog', metric: 'a<b>&"c', kind: 'gauge', scale: 100 }), null);
    assert.ok(html.indexOf('read as a&lt;b&gt;&amp;&quot;c (datadog, gauge, x100)') >= 0, html);
    assert.ok(html.indexOf('<b>') < 0, html);
    const plain = SK._seriesNote(KPI, series(), null);
    assert.ok(plain.indexOf('read as') < 0, plain);
});

// ---------------------------------------------------------------- the Collection panel

test('the Collection panel\'s source row says the file was read as the tool\'s export', () => {
    SK._state.metrics = { collectionId: 'c0ffee', sources: [source('datadog')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('read as datadog\'s export') >= 0, html);
});

test('the tool is escaped like every other file-derived word', () => {
    SK._state.metrics = { sources: [source('<i>x</i>')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('read as &lt;i&gt;x&lt;/i&gt;\'s export') >= 0, html);
    assert.ok(html.indexOf('<i>') < 0, html);
});

test('a collection without --from says nothing of the kind', () => {
    SK._state.metrics = { sources: [source()] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('read as') < 0, html);
    assert.ok(html.indexOf('>metrics<') >= 0, html);
});

// ---------------------------------------------------------------- --layout wide, --time-zone

function wideSource(layout, timeZone, from) {
    const s = source(from);
    if (layout !== undefined) s.layout = layout;
    if (timeZone !== undefined) s.timeZone = timeZone;
    return s;
}

test('a wide CSV says so, with the zone its zone-less times were read in', () => {
    SK._state.metrics = { sources: [wideSource('wide', 'UTC')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('wide CSV, times without a zone read in UTC') >= 0, html);
});

test('each is said only when the collection had it', () => {
    SK._state.metrics = { sources: [wideSource('wide')] };
    let html = SK._collectionHtml();
    assert.ok(html.indexOf('wide CSV') >= 0, html);
    assert.ok(html.indexOf('times without a zone') < 0, html);
    // A long file with a zone: the zone alone.
    SK._state.metrics = { sources: [wideSource(undefined, '+03:00')] };
    html = SK._collectionHtml();
    assert.ok(html.indexOf('times without a zone read in +03:00') >= 0, html);
    assert.ok(html.indexOf('wide CSV') < 0, html);
});

test('beside --from, on the one line under the source id', () => {
    SK._state.metrics = { sources: [wideSource('wide', 'Europe/Berlin', 'datadog')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('read as datadog\'s export, wide CSV, times without a zone read in Europe/Berlin') >= 0, html);
    assert.strictEqual(html.split('wir-read-as').length - 1, 1, html);
});

test('the layout and the zone are escaped like every other file-derived word', () => {
    SK._state.metrics = { sources: [wideSource('<b>x</b>', '<i>z</i>')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('&lt;b&gt;x&lt;/b&gt; CSV') >= 0, html);
    assert.ok(html.indexOf('read in &lt;i&gt;z&lt;/i&gt;') >= 0, html);
    assert.ok(html.indexOf('<b>') < 0 && html.indexOf('<i>') < 0, html);
});

test('a payload that names the long layout says nothing of it', () => {
    SK._state.metrics = { sources: [wideSource('long')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('CSV') < 0, html);
    assertNoReadAs(html);
});

function assertNoReadAs(html) {
    assert.ok(html.indexOf('wir-read-as') < 0, html);
}

// ---------------------------------------------------------------- --csv-columns, --component

function mappedSource(columns, component) {
    const s = source();
    if (columns !== undefined) s.columns = columns;
    if (component !== undefined) s.component = component;
    return s;
}

test('a long CSV whose columns were named by --csv-columns, and whose rows by --component, says both', () => {
    SK._state.metrics = { sources: [mappedSource('time=_time,metric=_field,value=_value', 'orders-db')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('columns time=_time metric=_field value=_value, every row in orders-db') >= 0, html);
});

test('each only when the collection had it, beside the zone', () => {
    SK._state.metrics = { sources: [mappedSource(undefined, 'orders-db')] };
    let html = SK._collectionHtml();
    assert.ok(html.indexOf('every row in orders-db') >= 0, html);
    assert.ok(html.indexOf('columns ') < 0, html);
    const zoned = mappedSource('metric=metricId,instance=dt.entity.host', 'web');
    zoned.timeZone = 'UTC';
    SK._state.metrics = { sources: [zoned] };
    html = SK._collectionHtml();
    assert.ok(html.indexOf('columns metric=metricId instance=dt.entity.host, every row in web,'
        + ' times without a zone read in UTC') >= 0, html);
    SK._state.metrics = { sources: [mappedSource()] };
    assertNoReadAs(SK._collectionHtml());
});

test('the columns and the component are escaped like every other file-derived word', () => {
    SK._state.metrics = { sources: [mappedSource('metric=<b>x</b>', '<i>db</i>')] };
    const html = SK._collectionHtml();
    assert.ok(html.indexOf('columns metric=&lt;b&gt;x&lt;/b&gt;') >= 0, html);
    assert.ok(html.indexOf('every row in &lt;i&gt;db&lt;/i&gt;') >= 0, html);
    assert.ok(html.indexOf('<b>') < 0 && html.indexOf('<i>') < 0, html);
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
