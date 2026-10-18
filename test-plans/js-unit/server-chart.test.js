/**
 * Unit tests for the pure half of the Server tab's charts (WIR.serverKpis).
 *
 * The chart draws per-instance lines, the fail band as a dashed line, and a red wash over the
 * stretches where a line is past that band. The wash is computed by a pure function, so whether
 * it covers the instance that actually breached is a question for node, not for Chromium against
 * a fixture that happens to contain such an instance (E26 does not: its many-instances KPI passes).
 * So are the gap markers, the y-axis bounds that keep a band on the plot, and the cost of the one
 * reducer whose naive form is quadratic.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const path = require('path');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const SK = WIR.serverKpis;

if (!SK || !SK._breachAreasAcross || !SK._bandAxisBounds) {
    throw new Error('report.js exposed no WIR.serverKpis chart seams — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Strip the vm realm off a value before comparing it (see asserts-pure.test.js). */
/* A component every assert here can name: custom, so each writes its own query (C9). Undeclared,
   every assert on it is INVALID_RULE, as it is in Java. */
const COMP = { c: { type: 'custom' } };

const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));
const spans = (areas) => plain(areas).map((a) => [a[0].xAxis, a[1].xAxis]);

const flat = [[0, 10], [1000, 10], [2000, 10], [3000, 10], [4000, 10]];

/* ---- the wash ------------------------------------------------------------------------------ */

test('L9-12: a breach on an instance other than the first is shaded', () => {
    // across: max, fail gt 100. p0 never comes near the band; p1 crosses it from 2000 to 3000.
    // The verdict is FAIL (max across instances is 500). The wash used to be drawn from p0 alone,
    // so this chart showed a FAIL row over an unshaded chart.
    const p1 = [[0, 10], [1000, 10], [2000, 500], [3000, 500], [4000, 10]];
    const areas = SK._breachAreasAcross([flat, p1], { gt: 100 });
    // ...to the point that recovered (R3-10), not to the last breaching one.
    assert.deepStrictEqual(spans(areas), [[2000, 4000]]);
    // ...and the per-instance function, which is all the chart asked before, sees nothing on p0.
    assert.deepStrictEqual(spans(SK._breachAreas(flat, { gt: 100 })), []);
});

test('overlapping stretches from different instances merge into one wash', () => {
    const a = [[0, 10], [1000, 500], [2000, 500], [3000, 10], [4000, 10]];
    const b = [[0, 10], [1000, 10], [2000, 500], [3000, 500], [4000, 10]];
    assert.deepStrictEqual(spans(SK._breachAreasAcross([a, b], { gt: 100 })), [[1000, 4000]]);
});

test('disjoint stretches stay apart, in time order whatever the instance order', () => {
    const late = [[0, 10], [1000, 10], [2000, 10], [3000, 500], [4000, 500]];
    const early = [[0, 500], [1000, 10], [2000, 10], [3000, 10], [4000, 10]];
    // early runs to its recovery at 1000; late is still breaching at the end, so it runs one
    // spacing past its last point.
    assert.deepStrictEqual(spans(SK._breachAreasAcross([late, early], { gt: 100 })),
        [[0, 1000], [3000, 5000]]);
});

test('a downward band shades where any instance dips under it', () => {
    const dip = [[0, 50], [1000, 5], [2000, 50]];
    assert.deepStrictEqual(spans(SK._breachAreasAcross([[[0, 50], [1000, 50], [2000, 50]], dip], { lt: 10 })),
        [[1000, 2000]]);
});

test('nothing breaching, no instances, or no band draws no wash', () => {
    assert.deepStrictEqual(spans(SK._breachAreasAcross([flat, flat], { gt: 100 })), []);
    assert.deepStrictEqual(spans(SK._breachAreasAcross([], { gt: 100 })), []);
    assert.deepStrictEqual(spans(SK._breachAreasAcross([flat], null)), []);
});

test('R3-10: a breach of one sample is a stretch with a width, not a zero-width area', () => {
    // The commonest way a `max` assert fails: one spike. The area used to run from the spike to
    // the spike — [{xAxis:1000},{xAxis:1000}] — which ECharts draws as nothing, beside a FAIL row.
    const areas = spans(SK._breachAreas([[0, 1], [1000, 900], [2000, 1]], { gt: 500 }));
    assert.strictEqual(areas.length, 1);
    assert.ok(areas[0][1] > areas[0][0], `zero-width area ${JSON.stringify(areas[0])}`);
    assert.deepStrictEqual(areas, [[1000, 2000]]);
});

test('R3-10: a stretch still breaching at the end runs one bucket on, clipped to the axis', () => {
    const tail = [[0, 1], [1000, 1], [2000, 900]];
    // The width the chart passes (max of step and bucket) wins over the inferred spacing.
    assert.deepStrictEqual(spans(SK._breachAreas(tail, { gt: 500 }, 5000)), [[2000, 7000]]);
    // Clipped to where the x-axis ends...
    assert.deepStrictEqual(spans(SK._breachAreas(tail, { gt: 500 }, 1000, 2500)), [[2000, 2500]]);
    // ...and where that leaves nothing — the spike IS the last point on the axis — it starts at
    // the point before, so the rise into it is shaded rather than nothing at all.
    assert.deepStrictEqual(spans(SK._breachAreas(tail, { gt: 500 }, 1000, 2000)), [[1000, 2000]]);
});

test('R3-11 + R3-10: a gap marker ends a stretch', () => {
    const gapped = [[0, 900], [1000, 900], [2000, null], [60000, 900]];
    assert.deepStrictEqual(spans(SK._breachAreas(gapped, { gt: 500 }, 1000)),
        [[0, 2000], [60000, 61000]]);
});

/* ---- gaps ---------------------------------------------------------------------------------- */

const outage = [[0, 10], [1000, 12], [2000, 11], [120000, 90], [121000, 91]];

test('R3-11: a two-minute outage breaks the line instead of being drawn as a ramp', () => {
    // Exactly the call the finding probed: bucketed at 1s, no step known.
    const bucketed = plain(SK._aggregateServerPoints(outage, 1000, 'max', 0));
    const inGap = bucketed.filter((p) => p[0] > 2000 && p[0] < 120000);
    assert.deepStrictEqual(inGap, [[3000, null]], JSON.stringify(bucketed));
    // ...and unbucketed, at the source's 1s step — the default when the ladder is per-second.
    const raw = plain(SK._aggregateServerPoints(outage, 0, 'max', 0, 1000));
    assert.deepStrictEqual(raw, [[0, 10], [1000, 12], [2000, 11], [3000, null], [120000, 90], [121000, 91]]);
});

test('R3-11: a series at its own cadence is not broken, and a coarse step is not a gap', () => {
    const regular = [[0, 1], [15000, 2], [30000, 3], [45000, 4]];
    assert.deepStrictEqual(plain(SK._aggregateServerPoints(regular, 0, 'avg', 0, 15000)), regular);
    // Bucketed at 5s, a 15s step leaves two empty buckets between every pair. That is the
    // source's resolution, not a missing scrape, so the unit is max(step, bucket).
    assert.ok(plain(SK._aggregateServerPoints(regular, 5000, 'avg', 0, 15000))
        .every((p) => p[1] !== null));
    // With no step and no bucket there is no cadence to call anything a gap against.
    assert.deepStrictEqual(plain(SK._aggregateServerPoints(outage, 0, 'max', 0)), outage);
});

/** `n` points `step` apart from 0, with the ones at [from, to) taken out (an outage). */
function series(n, step, from, to) {
    const out = [];
    for (let i = 0; i < n; i++) {
        if (from !== undefined && i >= from && i < to) continue;
        out.push([i * step, 50 + (i % 7)]);
    }
    return out;
}

/**
 * ServerMetricsPayloadWriter.thin, as written (F6): the first and last point kept, the points
 * between split into (max - 2) / 2 consecutive buckets, and each bucket's lowest and highest point
 * kept in time order (one when they are one point; a bucket of nothing but NaN keeps its first).
 */
function thin(points, max) {
    const n = points.length;
    if (n <= max) return points;
    const out = [points[0]];
    const interior = n - 2;
    const buckets = Math.floor((max - 2) / 2);
    for (let b = 0; b < buckets; b++) {
        const from = 1 + Math.floor(b * interior / buckets);
        const to = 1 + Math.floor((b + 1) * interior / buckets);
        let lo = -1;
        let hi = -1;
        for (let i = from; i < to; i++) {
            const v = points[i][1];
            if (Number.isNaN(v)) continue;
            if (lo < 0 || v < points[lo][1]) lo = i;
            if (hi < 0 || v > points[hi][1]) hi = i;
        }
        if (lo < 0) {
            if (from < to) out.push(points[from]);
            continue;
        }
        out.push(points[Math.min(lo, hi)]);
        if (lo !== hi) out.push(points[Math.max(lo, hi)]);
    }
    out.push(points[n - 1]);
    return out;
}

const gapsIn = (points) => plain(points).filter((p) => p[1] === null).length;

/** `n` points `step` apart whose thinning buckets (thin's own bounds, for `max`) hold both their
    extremes at their start and at their end in turn: the kept points of two adjacent buckets as
    far apart as thinning puts them. */
function edgeExtremes(n, step, max) {
    const out = [];
    for (let i = 0; i < n; i++) out.push([i * step, 50]);
    const interior = n - 2;
    const buckets = Math.floor((max - 2) / 2);
    for (let b = 0; b < buckets; b++) {
        const from = 1 + Math.floor(b * interior / buckets);
        const to = 1 + Math.floor((b + 1) * interior / buckets);
        if (to - from < 2) continue;
        const at = b % 2 === 0 ? from : to - 2;
        out[at][1] = 10;
        out[at + 1][1] = 90;
    }
    return out;
}

test('R3-11: a thinned instance is spaced by its buckets by construction, not an outage', () => {
    // 7200 points at 1s, thinned to the writer's 4000. Judged against the 1s step alone, every
    // wider spacing was an "outage" — 3200 markers, a chart of dots beside a note saying the series
    // was thinned. The writer says what it thinned from.
    const thinned = thin(series(7200, 1000), 4000);
    assert.ok(thinned.length <= 4000, 'precondition: ' + thinned.length + ' points shipped');
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 1000, 'max', 0, 1000, 7200)), 0);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 0, 'max', 0, 1000, 7200)), 0);
    // Just over the cap (4500 -> 4000), points are still two and more steps apart now and then.
    const barely = thin(series(4500, 1000), 4000);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(barely, 1000, 'max', 0, 1000, 4500)), 0);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(barely, 0, 'max', 0, 1000, 4500)), 0);
});

test('F6: a bucket-thinned instance whose extremes sit at its buckets\' far ends draws no break', () => {
    // The writer keeps each bucket's lowest and highest point, so two kept points of a series that
    // missed nothing can be the FIRST of one bucket and the LAST of the next: 2 x bucket - 1 steps
    // apart, not the every-k-th stride's ceil(stride). Measured against that stride, 1.5 x 2s called
    // every such spacing an outage on a series that missed no scrape.
    // [n, max, whether the far ends are wider than 1.5 x the every-k-th stride's spacing]. Just
    // over the cap they are not (buckets of 2 or 3), and nothing may break there either.
    [[7200, 4000, true], [12007, 4000, true], [4100, 4000, false]].forEach(([n, max, wide]) => {
        const buckets = Math.floor((max - 2) / 2);
        const size = Math.ceil((n - 2) / buckets);
        const raw = edgeExtremes(n, 1000, max);
        const thinned = thin(raw, max);
        assert.ok(thinned.length <= max, n + ': ' + thinned.length + ' points shipped');
        const widest = thinned.slice(1).reduce((w, p, i) => Math.max(w, p[0] - thinned[i][0]), 0);
        // The far ends of two adjacent buckets: wider than the every-k-th stride ever put them.
        if (wide) {
            assert.ok(widest > 1000 * Math.ceil((n - 1) / (thinned.length - 1)) * 1.5,
                n + ': precondition, kept points at most ' + widest + 'ms apart (bucket ' + size + ')');
        }
        assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 1000, 'max', 0, 1000, n)), 0, n + ' at 1s');
        assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 0, 'max', 0, 1000, n)), 0, n + ' unbucketed');
        // ...and a series whose every bucket is flat ships one point per bucket, still unbroken.
        const flatRaw = series(n, 1000).map((p) => [p[0], 7]);
        const flatThinned = thin(flatRaw, max);
        assert.strictEqual(gapsIn(SK._aggregateServerPoints(flatThinned, 1000, 'max', 0, 1000, n)), 0, n + ' flat');
    });
});

test('R3-11: a thinned instance still breaks where the collector really saw nothing', () => {
    // Five minutes missing from the middle of a two-hour 1s series, then thinned.
    const raw = series(7200, 1000, 3000, 3300);
    const thinned = thin(raw, 4000);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 1000, 'max', 0, 1000, raw.length)), 1);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 5000, 'max', 0, 1000, raw.length)), 1);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(thinned, 0, 'max', 0, 1000, raw.length)), 1);
});

test('R3-11: a step just above the bucket rung is the source\'s cadence, not a gap', () => {
    // Bucketed at 10s, a regular 12s series leaves an empty bucket every few points: two bucket
    // starts up to step + bucket apart, which is more than 1.5 x the step. Judged after bucketing,
    // 100 regular points drew 19 markers. The same at 6s on 5s and 1.2s on 1s.
    [[12000, 10000], [6000, 5000], [1200, 1000]].forEach(([step, bucket]) => {
        const regular = series(100, step);
        assert.strictEqual(gapsIn(SK._aggregateServerPoints(regular, bucket, 'avg', 0, step)), 0,
            `${step}ms on ${bucket}ms`);
        // ...and a real outage there is still one break.
        const gapped = series(100, step, 40, 50);
        assert.strictEqual(gapsIn(SK._aggregateServerPoints(gapped, bucket, 'avg', 0, step)), 1,
            `${step}ms on ${bucket}ms, ten scrapes missing`);
    });
});

test('R3-11: a gap shorter than a bucket leaves no bucket empty and draws no break', () => {
    // A 3s hole in a 1s series, bucketed at 10s: every bucket still holds a point, so the line at
    // that resolution has nothing missing to show.
    const holed = series(60, 1000, 21, 24);
    assert.strictEqual(gapsIn(SK._aggregateServerPoints(holed, 10000, 'max', 0, 1000)), 0);
    // Unbucketed, the same hole is a break: 1s on, nothing answered.
    const raw = plain(SK._aggregateServerPoints(holed, 0, 'max', 0, 1000));
    assert.deepStrictEqual(raw.filter((p) => p[1] === null), [[21000, null]]);
});

/* ---- lone points ---------------------------------------------------------------------------- */

test('R3-11: only a point with a gap on both sides is a lone point', () => {
    // One lone scrape turned symbols on for the WHOLE series, so a dense 3600-point line with one
    // island drew a dot on every point. The chart marks these indices and nothing else.
    const mixed = [[0, 1], [1000, 2], [2000, null], [3000, 5], [4000, null], [5000, 1], [6000, 1]];
    assert.deepStrictEqual(plain(SK._islands(mixed)), [3]);
    // At either end, the series' edge is the other gap.
    assert.deepStrictEqual(plain(SK._islands([[0, 1], [1000, null], [2000, 2], [3000, 3]])), [0]);
    assert.deepStrictEqual(plain(SK._islands([[0, 1], [1000, 2], [2000, null], [3000, 3]])), [3]);
    assert.deepStrictEqual(plain(SK._islands([[0, 1]])), [0]);
    assert.deepStrictEqual(plain(SK._islands(flat)), []);
    assert.deepStrictEqual(plain(SK._islands([])), []);
});

/* ---- the y-axis ---------------------------------------------------------------------------- */

test('R3-3: a band above the data widens the axis so its line and handle are on the plot', () => {
    // E26's cpu-headroom: data up to 70%, fail gt 95. The axis was scaled to the data, so the
    // fail line and its handle sat 43px above the canvas.
    const b = plain(SK._bandAxisBounds(40, 70, [95]));
    assert.ok(b.max !== null && b.max >= 95 * 1.05, JSON.stringify(b));
    assert.strictEqual(b.min, null, 'a non-negative series keeps the axis at zero');
    // A seeded assert: fail at 1.5x the observed maximum, by construction above the data.
    const seeded = plain(SK._bandAxisBounds(0, 900, [1350]));
    assert.ok(seeded.max >= 1350 * 1.05, JSON.stringify(seeded));
    // A threshold 300x the data still has to be reachable, at the cost of a flat line.
    assert.ok(plain(SK._bandAxisBounds(0, 35, [10000])).max > 10000);
});

test('R3-3: bands inside the data leave the axis to ECharts', () => {
    // lag-spike: data to 900, warn 300, fail 500 — nothing to widen.
    assert.deepStrictEqual(plain(SK._bandAxisBounds(0, 900, [300, 500])), { min: null, max: null });
    // No band at all (a slope assert, whose bands are not drawn).
    assert.deepStrictEqual(plain(SK._bandAxisBounds(0, 900, [])), { min: null, max: null });
});

test('R3-3: a band just under the top gets headroom, and a negative band gets room below', () => {
    // A band at the data's maximum would sit on the top edge, its handle half off the grid.
    const top = plain(SK._bandAxisBounds(0, 100, [99]));
    assert.ok(top.max > 99, JSON.stringify(top));
    const below = plain(SK._bandAxisBounds(10, 50, [-20]));
    assert.ok(below.min !== null && below.min <= -20, JSON.stringify(below));
    // ...but `lt 0` on a non-negative series does not push the axis below zero.
    assert.strictEqual(plain(SK._bandAxisBounds(0, 50, [0])).min, null);
    // Round numbers, not 104.50000000000001.
    const nice = plain(SK._bandAxisBounds(0, 0.07, [0.095]));
    assert.strictEqual(String(nice.max).length <= 5, true, JSON.stringify(nice));
});

/* ---- the units the bands are drawn in ------------------------------------------------------ */

test('C-7: a slope assert draws no bands — they are per minute, and the line is not', () => {
    const kpi = SK.kpisFromDocument({ components: COMP, asserts: [{
        id: 'heap-growth', component: 'c', query: 'heap', unit: 'MB',
        reduce: { kind: 'slope' }, fail: { gt: 5 } }] })[0];
    const frame = plain(SK._chartFrame(kpi));
    assert.strictEqual(frame.bands, false);
    assert.strictEqual(frame.by, 1);
    assert.ok(/per minute/.test(frame.note), frame.note);
    assert.strictEqual(frame.name, 'MB', 'the axis keeps the series\' unit');
});

test('SF16: a slope note names the verdict\'s unit, a ratio\'s divisor included', () => {
    // It said "in % per minute" beside a board reading "%/min ÷ load:throughput".
    const [ratio, bare] = SK.kpisFromDocument({ components: COMP, asserts: [
        { id: 'cpu-slope-per-rps', component: 'c', query: 'cpu', unit: '%', reduce: { kind: 'slope' },
            divideBy: 'load:throughput', fail: { gt: 5 } },
        { id: 'dl-slope', component: 'c', query: 'dl', reduce: { kind: 'slope' }, fail: { gt: 5 } }] });
    const r = plain(SK._chartFrame(ratio));
    assert.strictEqual(r.note, 'the bands judge the slope of a least-squares fit per minute, in '
        + '%/min ÷ load:throughput, so they are not drawn against the series itself');
    assert.strictEqual(r.name, '%');
    assert.ok(/ in units\/min, /.test(plain(SK._chartFrame(bare)).note), plain(SK._chartFrame(bare)).note);
});

test('C-7: a divideBy assert with no divisor on the page draws no bands on the raw axis', () => {
    // No client rule by that name here (node has no Asserts tab state), so the mirror would not
    // divide either — and a band in ratio units over a raw line is the wrong-units chart.
    const kpi = SK.kpisFromDocument({ components: COMP, asserts: [{
        id: 'lag-per-rps', component: 'c', query: 'lag', reduce: { kind: 'max' },
        divideBy: 'load:throughput', fail: { gt: 60 } }] })[0];
    const frame = plain(SK._chartFrame(kpi));
    assert.strictEqual(frame.bands, false);
    assert.ok(/per unit of the load/.test(frame.note), frame.note);
});

test('C-7: any other assert draws its bands on the series it judges, unscaled', () => {
    const kpi = SK.kpisFromDocument({ components: COMP, asserts: [{
        id: 'cpu', component: 'c', query: 'cpu', reduce: { kind: 'p95' }, fail: { gt: 90 } }] })[0];
    assert.deepStrictEqual(plain(SK._chartFrame(kpi)), { by: 1, bands: true, name: '', note: null });
});

/* ---- the drag handles follow the legend ------------------------------------------------------ */

/*
 * On the bundled ECharts itself, server-side rendered, as asserts-cosmetics.test.js runs the Asserts
 * tab's handles: the event a legend action dispatches is the event under test, and a stand-in chart
 * could only fire what the test already believed it fired. Whether initChartFor binds the watch at
 * all is the browser's question (server-seeding.spec.ts, "REV-15").
 */
const ECHARTS = path.resolve(__dirname,
    '../../jmeter-web-insight-report/src/main/resources/static/js/echarts.min.js');

const T0 = 1700000000000;
const atSeconds = (values) => values.map((v, i) => [T0 + i * 1000, v]);

/**
 * A row's chart as initChartFor draws one: a line per instance, the band lines as the first
 * instance's markLine, a legend because there are two, and the frame of an assert whose bands are
 * drawn. `instance=b` is the tall one: hiding it takes the auto-scaled axis from 0..70 to 0..50.
 */
function serverChart(kpi) {
    const echarts = require(ECHARTS);
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 835, height: 320 });
    const marks = ['warn', 'fail'].filter((kind) => kpi[kind]).map((kind) => ({ yAxis: kpi[kind].value }));
    chart.setOption({
        animation: false,
        legend: { type: 'scroll', bottom: 0 },
        grid: { left: 60, right: 24, top: 24, bottom: 48 },
        xAxis: { type: 'time' },
        yAxis: { type: 'value' },
        series: [
            { name: 'instance=a', type: 'line', data: atSeconds([40, 46, 43, 44]),
                markLine: { silent: true, symbol: 'none', data: marks } },
            { name: 'instance=b', type: 'line', data: atSeconds([61, 65, 63, 62]) }
        ]
    });
    chart.__wirFrame = { by: 1, bands: true, name: '', note: null };
    return chart;
}

/** Each handle's anchor, by its element id: the pixel a drag reads its value from. */
function handlesOn(chart) {
    const graphic = chart.getOption().graphic;
    const elements = graphic && graphic[0] ? graphic[0].elements || [] : [];
    const out = {};
    plain(elements).filter((e) => /^wir-server-handle-/.test(e.id))
        .forEach((e) => { out[e.id] = e.position ? e.position[1] : e.y; });
    return out;
}

/** Where each band's line is on the axis as it now stands, under its handle's id. */
function bandPixels(chart, kpi) {
    const out = {};
    ['warn', 'fail'].filter((kind) => kpi[kind]).forEach((kind) => {
        out['wir-server-handle-' + kpi.key + '-' + kind] =
            chart.convertToPixel({ gridIndex: 0 }, [0, kpi[kind].value])[1];
    });
    return out;
}

function withServerChart(kpi, fn) {
    SK._state.declaredKpis = [kpi];
    const chart = serverChart(kpi);
    try {
        // As initChartFor does: watched from the start, drawn once the grid is laid out.
        SK._watchHandles(chart, kpi.key);
        SK._drawDragHandles(chart, kpi);
        fn(chart);
    } finally {
        // An SSR instance runs zrender's frame loop on a timer: undisposed, node never exits.
        chart.dispose();
        SK._state.declaredKpis = [];
    }
}

const LEGEND_KPI = { key: 'r-legend', id: 'cpu', warn: { op: 'gt', value: 30 }, fail: { op: 'gt', value: 45 } };

test('REV-15: precondition: the bands sit inside the data, so initChartFor leaves the axis to ECharts', () => {
    // A band above the data bounds the axis (R3-3), and a bounded axis does not rescale.
    assert.deepStrictEqual(plain(SK._bandAxisBounds(40, 65, [30, 45])), { min: null, max: null });
});

/* Every legend event the client's handles redraw on, each from the action that fires it. `setup`
   puts the legend where the action has something to change; the action then rescales the axis. */
const LEGEND_ACTIONS = [
    { event: 'legendselectchanged', setup: [], action: { type: 'legendToggleSelect', name: 'instance=b' } },
    { event: 'legendunselected', setup: [], action: { type: 'legendUnSelect', name: 'instance=b' } },
    { event: 'legendselected', setup: [{ type: 'legendUnSelect', name: 'instance=b' }],
        action: { type: 'legendSelect', name: 'instance=b' } },
    { event: 'legendselectall', setup: [{ type: 'legendUnSelect', name: 'instance=b' }],
        action: { type: 'legendAllSelect' } },
    // The carrier hidden first, so Inverse both shows it and hides the tall one.
    { event: 'legendinverseselect', setup: [{ type: 'legendUnSelect', name: 'instance=a' }],
        action: { type: 'legendInverseSelect' } }
];

LEGEND_ACTIONS.forEach(({ event, setup, action }) => {
    test(`REV-15: ${action.type} (${event}) rescales the axis, and the handles go back on their bands`, () => {
        // Hiding or showing a tall instance rescales y to the instances drawn, and fires neither
        // resize nor a tab switch, the only two moments the handles were placed: they stayed at the
        // old pixels, beside lines that had moved, and a drag from one wrote a value nobody saw.
        withServerChart(LEGEND_KPI, (chart) => {
            setup.forEach((a) => chart.dispatchAction(a));
            const before = bandPixels(chart, LEGEND_KPI);
            // Where the setup left them, so the action has to move them: back to where the first
            // draw put them is no evidence of a redraw.
            const carrierShown = chart.getModel().getComponent('legend').isSelected('instance=a');
            assert.deepStrictEqual(handlesOn(chart), carrierShown ? before : {});
            chart.dispatchAction(action);
            const after = bandPixels(chart, LEGEND_KPI);
            const moved = Object.keys(after).map((id) => Math.abs(after[id] - before[id]));
            assert.ok(Math.min.apply(null, moved) > 20,
                `precondition: the axis rescaled (${JSON.stringify(before)} -> ${JSON.stringify(after)})`);
            assert.deepStrictEqual(handlesOn(chart), after);
        });
    });
});

test('REV-15: while the instance carrying the band lines is hidden, so are the handles', () => {
    // The band lines are the first instance's markLine: a legend click that hides it takes the
    // lines with it, and a handle left standing would drag a line that is not drawn.
    const kpi = { key: 'r-carrier', id: 'cpu', fail: { op: 'gt', value: 45 } };
    withServerChart(kpi, (chart) => {
        assert.deepStrictEqual(Object.keys(handlesOn(chart)), ['wir-server-handle-r-carrier-fail']);
        assert.deepStrictEqual(handlesOn(chart), bandPixels(chart, kpi));
        chart.dispatchAction({ type: 'legendToggleSelect', name: 'instance=a' });
        assert.deepStrictEqual(handlesOn(chart), {});
        // Shown again, the handle is back, on its band as the axis now draws it.
        chart.dispatchAction({ type: 'legendToggleSelect', name: 'instance=a' });
        assert.deepStrictEqual(handlesOn(chart), bandPixels(chart, kpi));
        // A row deleted since draws nothing more on a chart about to be disposed: the handle stays
        // where the last draw put it, though the axis has moved under it.
        const left = handlesOn(chart);
        SK._state.declaredKpis = [];
        chart.dispatchAction({ type: 'legendToggleSelect', name: 'instance=b' });
        assert.deepStrictEqual(handlesOn(chart), left);
        assert.notDeepStrictEqual(bandPixels(chart, kpi), left);
    });
});

/* ---- sustainedFor, in one pass -------------------------------------------------------------- */

/** The definition, written independently of report.js: every window rescanned. */
function sustainedReference(points, d, step, high) {
    if (d <= step) {
        return points.reduce((b, p) => (high ? Math.max(b, p[1]) : Math.min(b, p[1])),
            high ? -Infinity : Infinity);
    }
    let best = NaN;
    for (let i = 0; i < points.length; i++) {
        const start = points[i][0];
        let level = points[i][1];
        let last = start;
        for (let j = i; j < points.length && points[j][0] <= start + d; j++) {
            level = high ? Math.min(level, points[j][1]) : Math.max(level, points[j][1]);
            last = points[j][0];
        }
        if (last - start < d) continue;
        best = isNaN(best) ? level : (high ? Math.max(best, level) : Math.min(best, level));
    }
    return best;
}

/** A small deterministic PRNG, so a failing seed can be replayed. */
function rng(seed) {
    let s = seed >>> 0;
    return () => {
        s = (s * 1664525 + 1013904223) >>> 0;
        return s / 4294967296;
    };
}

test('R3-13: the one-pass sustainedFor agrees with the rescan on random irregular series', () => {
    for (let seed = 1; seed <= 300; seed++) {
        const r = rng(seed);
        const step = 1000;
        const points = [];
        let t = 0;
        const n = 5 + Math.floor(r() * 60);
        for (let i = 0; i < n; i++) {
            // Mostly one step, sometimes a missed scrape or three, sometimes a repeated value.
            t += step * (r() < 0.8 ? 1 : 1 + Math.floor(r() * 4));
            const v = r() < 0.2 && points.length ? points[points.length - 1][1] : Math.round(r() * 100);
            points.push([t, v]);
        }
        for (const d of [2000, 3000, 5000, 10000]) {
            for (const high of [true, false]) {
                const want = sustainedReference(points, d, step, high);
                const got = SK._sustained(points, d, step, high);
                assert.ok((isNaN(want) && isNaN(got)) || want === got,
                    `seed ${seed}, d ${d}, ${high ? 'max' : 'min'}: want ${want}, got ${got}`);
            }
        }
    }
});

test('R3-13: one `max sustainedFor 5m` over 20 pods x 3600 points judges in well under 200 ms', () => {
    // The page runs the mirror on every render. Rescanning the window from every point made this
    // one assert cost ~2.4 s per evaluate — seconds per click on a soak run's board.
    const instances = [];
    for (let p = 0; p < 20; p++) {
        const points = [];
        for (let i = 0; i < 3600; i++) points.push([i * 1000, 50 + ((i * 7 + p * 13) % 40)]);
        instances.push({ labels: { pod: 'p' + p }, points });
    }
    const payload = {
        window: { start: 0, end: 3599000 },
        sources: [{ id: 'metrics', type: 'prometheus', status: 'OK', stepMillis: 1000 }],
        series: [{ source: 'metrics', query: 'lag', resolvedQuery: 'lag', status: 'OK', coverage: 1, instances }]
    };
    const kpis = SK.kpisFromDocument({ components: COMP, asserts: [{
        id: 'lag-held', component: 'c', query: 'lag',
        reduce: { kind: 'max', over: { sustainedFor: '5m' } }, fail: { gt: 1000 } }] });
    const config = SK.configFromDocument({});
    SK.evaluate(kpis, config, payload, []);                      // warm the JIT
    const t0 = Date.now();
    const result = SK.evaluate(kpis, config, payload, [])[0];
    const elapsed = Date.now() - t0;
    assert.strictEqual(result.status, 'PASS');
    assert.ok(elapsed < 200, `took ${elapsed} ms`);
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
