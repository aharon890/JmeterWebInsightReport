/**
 * The Asserts tab's surfaces that told a reader one thing in two ways (track b6), driven through the
 * page's own lifecycle (harness.loadReportPage) with no browser:
 *
 *  - L9 / CN13: the Summary strip's counts were the gated run's while its breach list followed the
 *    draft, so a loosening edit read "2 failed" over one FAIL row. Both halves read the run the
 *    build was gated on now, with "Draft differs — see Asserts" while a client draft exists.
 *  - L17 / CN15: Save with nothing changed tagged the row EDITED and toasted "Rule updated — draft"
 *    while the unsaved count stayed 0. An unchanged rule — by the file's meaning — is left as it was.
 *  - CN23: the editor preview's chip printed the raw NOT_JUDGED where the table writes INVALID or
 *    DISABLED; it goes through displayStatus, as the table does.
 *  - AF4b-grain: a drag landed on a hundredth whatever the axis, so a band below 0.01 could not be
 *    dragged at all — E25's `lt 0.001` throughput rule became `lt 0` (which can never fire) the
 *    moment its handle was released. The grain is what one pixel spans, never coarser than the
 *    hundredth; a handle released on its own pixel changes nothing; and a drag that would land a
 *    downward band on 0 is refused, as the editor refuses it.
 *  - U10a (client) / L18 / CN16: the rule labels sat at the right end of their lines, under the
 *    handles and on each other where two rules share a value, and the run aggregate's label sat on a
 *    band's line beside the band's own: one visible line, two labels. They are laid out with the
 *    handles now (WIR.chartStyle.layoutChartLabels, chart-style.test.js holds the pure half), laid
 *    out again on every legend selection that rescales the axis, and gone with the series that
 *    carries their lines — checked on the bundled ECharts, server-side rendered.
 *
 * The rule for what belongs here is asserts-write-back.test.js's: what the page DECIDED — a model,
 * a verdict, a toast's text, the elements handed to a chart — never what it drew.
 */
'use strict';

const assert = require('assert');
const path = require('path');
const { loadReportJs, loadReportPage } = require('./harness');

const A0 = loadReportJs().asserts;
const CS = loadReportJs().chartStyle;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
/* A page object from the vm context has that context's Object.prototype: compare data, not realms. */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

const SAMPLERS = [
    { name: 'A', count: 10, p95: 50, mean: 30, throughput: 5 },
    { name: 'B', count: 10, p95: 80, mean: 40, throughput: 5 }
];
const OVERALL = { totalSamples: 20, p95: 70, mean: 35, throughput: 10 };

/**
 * A page as the build writes it for a file declaring `entries`: each payload rule carries the verdict
 * Java took (the evaluator the parity vectors hold to Java's), and `gate` is that run's gate.
 */
function javaPage(entries, toasts, overall) {
    const chartData = {
        runId: 'cosmetics',
        annotationsDocument: JSON.parse(JSON.stringify({ assertions: entries })),
        samplers: SAMPLERS,
        overall: overall || OVERALL
    };
    const parsed = entries.map((r, i) => A0._ruleFromJson(r, i));
    parsed.forEach((r) => { delete r.unreadable; });
    const evaluated = A0.evaluate(parsed, chartData);
    chartData.assertions = JSON.parse(JSON.stringify(evaluated.rules));
    chartData.gate = JSON.parse(JSON.stringify(evaluated.gate));
    return loadReportPage(chartData, undefined, { toasts: toasts || [] });
}

// ---------------------------------------------------------------------------
// L9 / CN13 — the Summary strip speaks for the gated run, both halves
// ---------------------------------------------------------------------------

const STRIP = [
    { id: 'a-p95', target: { sampler: 'A' }, metric: 'p95', fail: { gt: 10 } },    // FAIL, 50 > 10
    { id: 'b-p95', target: { sampler: 'B' }, metric: 'p95', fail: { gt: 20 } },    // FAIL, 80 > 20
    { id: 'a-mean', target: { sampler: 'A' }, metric: 'mean', fail: { gt: 100 } }  // PASS
];

const DRAFT_NOTE = 'Draft differs — see Asserts';

test('L9: as shipped, the strip counts two FAILs and lists both, worst first, and says no draft', () => {
    const W = javaPage(STRIP);
    const strip = plain(W.asserts._summaryStrip());
    assert.deepStrictEqual(strip.counts, [{ n: 2, label: 'failed' }, { n: 1, label: 'passed' }]);
    // a-p95 is 400% past its line, b-p95 300%.
    assert.deepStrictEqual(strip.breaches, [{ id: 'a-p95', status: 'FAIL' }, { id: 'b-p95', status: 'FAIL' }]);
    assert.strictEqual(strip.draftDiffers, false);
    assert.ok(W.asserts._gateStripHtml().indexOf(DRAFT_NOTE) < 0, 'a note with no draft');
});

test('L9: a loosening edit leaves both halves on the gated run, and the strip says a draft differs', () => {
    const W = javaPage(STRIP);
    const before = plain(W.asserts._summaryStrip());
    W.asserts._retune('a-p95', 1000);   // loosened: the draft PASSes it
    assert.strictEqual(W.asserts._state.rules[0].status, 'PASS', 'precondition: the draft passes a-p95');
    const after = plain(W.asserts._summaryStrip());
    assert.deepStrictEqual(after.counts, before.counts, 'the counts moved with the draft');
    // Was [b-p95] alone: the list followed the draft, so "2 failed" stood over one FAIL row.
    assert.deepStrictEqual(after.breaches, before.breaches, 'the breach list followed the draft');
    assert.strictEqual(after.draftDiffers, true);
    const html = W.asserts._gateStripHtml();
    assert.ok(html.indexOf(DRAFT_NOTE) >= 0, 'no draft note: ' + html);
    // The rule listed is quoted against the band the run was judged by, not the draft's 1,000.
    assert.ok(html.indexOf('&gt; 10') >= 0 || html.indexOf('> 10') >= 0, html);
    assert.ok(html.indexOf('1,000') < 0, 'the draft band reached the strip: ' + html);
});

test('L9: a tightening edit adds no row to the strip — it is the run as gated', () => {
    const W = javaPage(STRIP);
    W.asserts._retune('a-mean', 1);   // tightened: the draft FAILs a-mean
    const strip = plain(W.asserts._summaryStrip());
    assert.deepStrictEqual(strip.breaches.map((b) => b.id), ['a-p95', 'b-p95']);
    assert.strictEqual(strip.draftDiffers, true);
});

test('L9: an edit put back, or discarded, is no draft — the note goes', () => {
    const W = javaPage(STRIP);
    W.asserts._retune('a-p95', 1000);
    W.asserts._retune('a-p95', 10);
    assert.strictEqual(plain(W.asserts._summaryStrip()).draftDiffers, false, 'put back by hand');
    W.asserts._retune('a-p95', 1000);
    W.asserts.discardDrafts();
    assert.strictEqual(plain(W.asserts._summaryStrip()).draftDiffers, false, 'discarded');
    assert.ok(W.asserts._gateStripHtml().indexOf(DRAFT_NOTE) < 0);
});

test('L9: a note changed in the document is no client draft — it judges nothing', () => {
    // The strip speaks for the client verdict; only a rule can move it. The notes are this slice's
    // too (testNotes), so the unsaved count moves while the strip's note does not.
    const W = javaPage(STRIP);
    const doc = JSON.parse(W.document.text());
    doc.testNotes = 'a note nobody judges';
    assert.strictEqual(W.document.replace(doc, W.document.source()), null);
    assert.ok(W.document.count() > 0, 'precondition: the note is an unsaved change');
    assert.strictEqual(plain(W.asserts._summaryStrip()).draftDiffers, false);
    assert.ok(W.asserts._gateStripHtml().indexOf(DRAFT_NOTE) < 0);
});

// ---------------------------------------------------------------------------
// L17 / CN15 — Save with nothing changed changes nothing
// ---------------------------------------------------------------------------

test('L17: Save with nothing changed marks nothing, says nothing, and keeps the rule Java judged', () => {
    const toasts = [];
    const W = javaPage(STRIP, toasts);
    const shipped = W.asserts._state.rules[0];
    W.asserts.edit(W.asserts._keyOf('a-p95'));
    W.asserts.saveEditor();
    assert.strictEqual(W.asserts._editorRule(), null, 'the editor stayed open');
    assert.strictEqual(W.asserts._state.rules[0].draftState, undefined, 'tagged ' + W.asserts._state.rules[0].draftState);
    assert.deepStrictEqual(toasts, [], 'toasted ' + JSON.stringify(toasts));
    assert.strictEqual(W.document.count(), 0);
    assert.strictEqual(W.asserts._state.rules[0], shipped, 'the rule was swapped for a re-judged copy');
});

test('L17: an id-less rule saved unchanged marks nothing, says nothing, and keeps declaring no id', () => {
    // The form shows the id Java derives (a-p95); saved as it stands, that id is not an edit
    // (sameRule's idDeclared branch). Written into the rule, it was one: EDITED, a toast, 1 change,
    // and a Download that declared an id the file never had (REV-12).
    const toasts = [];
    const W = javaPage([{ target: { sampler: 'A' }, metric: 'p95', fail: { gt: 10 } }], toasts);
    const shipped = W.asserts._state.rules[0];
    assert.strictEqual(shipped.idDeclared, false, 'precondition: the file declares no id');
    W.asserts.edit(shipped.key);
    assert.strictEqual(W.asserts._editorRule().id, shipped.id, 'precondition: the form shows the derived id');
    W.asserts.saveEditor();
    assert.strictEqual(W.asserts._editorRule(), null, 'the editor stayed open');
    assert.strictEqual(W.asserts._state.rules[0].draftState, undefined, 'tagged ' + W.asserts._state.rules[0].draftState);
    assert.deepStrictEqual(toasts, [], 'toasted ' + JSON.stringify(toasts));
    assert.strictEqual(W.document.count(), 0);
    assert.strictEqual(W.asserts._state.rules[0], shipped, 'the rule was swapped for a re-judged copy');
    assert.ok(!('id' in JSON.parse(W.document.text()).assertions[0]), 'the Download declares an id');
});

test('L17: a real change is still EDITED, and toasted', () => {
    const toasts = [];
    const W = javaPage(STRIP, toasts);
    W.asserts.edit(W.asserts._keyOf('a-p95'));
    W.asserts.setBand('fail', 'value', '15');
    W.asserts.saveEditor();
    assert.strictEqual(W.asserts._state.rules[0].draftState, 'edited');
    // The page has no table here, so the toast adds "hidden by the current filters"; its head is
    // the decision.
    assert.strictEqual(toasts.filter((t) => t.indexOf('Rule updated — draft') === 0).length, 1, JSON.stringify(toasts));
    assert.strictEqual(W.document.count(), 1);
});

test('L17: a description typed and cleared again is no change', () => {
    const toasts = [];
    const W = javaPage(STRIP, toasts);
    W.asserts.edit(W.asserts._keyOf('b-p95'));
    W.asserts.setField('description', 'temporary');
    W.asserts.setField('description', '');
    W.asserts.saveEditor();
    assert.strictEqual(W.asserts._state.rules[1].draftState, undefined);
    assert.deepStrictEqual(toasts, []);
});

test('L17: a rule already edited, saved again unchanged, stays EDITED and says nothing new', () => {
    const toasts = [];
    const W = javaPage(STRIP, toasts);
    W.asserts._retune('a-p95', 12);
    const heard = toasts.length;
    W.asserts.edit(W.asserts._keyOf('a-p95'));
    W.asserts.saveEditor();
    assert.strictEqual(W.asserts._state.rules[0].draftState, 'edited', 'the earlier edit was forgotten');
    assert.strictEqual(W.asserts._state.rules[0].fail.value, 12);
    assert.strictEqual(toasts.length, heard, 'toasted ' + JSON.stringify(toasts.slice(heard)));
    assert.strictEqual(W.document.count(), 1);
});

// ---------------------------------------------------------------------------
// CN23 — the preview chip is the table's word
// ---------------------------------------------------------------------------

/** The text and class of #editorPreviewVerdict in the preview's HTML. */
function previewChip(html) {
    const m = /<span class="([^"]*)" id="editorPreviewVerdict">([^<]*)<\/span>/.exec(html || '');
    assert.ok(m, 'no preview chip in ' + html);
    return { cls: m[1], text: m[2] };
}

test('CN23: a rule that cannot run previews INVALID, as its row says — never NOT_JUDGED', () => {
    const W = javaPage([{ id: 'lag', target: { sampler: 'A' }, metric: 'p95', warn: { gt: 30 }, fail: { gtt: 90 } }]);
    W.asserts.edit(W.asserts._keyOf('lag'));
    const chip = previewChip(W.asserts._editorPreview());
    assert.strictEqual(chip.text, 'INVALID');
    assert.ok(/\bwir-chip-invalid\b/.test(chip.cls), chip.cls);
});

test('CN23: a rule switched off previews DISABLED', () => {
    const W = javaPage(STRIP);
    W.asserts.edit(W.asserts._keyOf('a-p95'));
    W.asserts.setField('enabled', false);
    const chip = previewChip(W.asserts._editorPreview());
    assert.strictEqual(chip.text, 'DISABLED');
    assert.ok(/\bwir-chip-disabled\b/.test(chip.cls), chip.cls);
});

test('CN23: a verdict is still its own word', () => {
    const W = javaPage(STRIP);
    W.asserts.edit(W.asserts._keyOf('a-p95'));
    assert.strictEqual(previewChip(W.asserts._editorPreview()).text, 'FAIL');
    W.asserts.edit(W.asserts._keyOf('a-mean'));
    assert.strictEqual(previewChip(W.asserts._editorPreview()).text, 'PASS');
});

// ---------------------------------------------------------------------------
// AF4b-grain — a drag below a hundredth
// ---------------------------------------------------------------------------

test('AF4b: a drag lands on the grain one pixel spans — finer than a hundredth where the axis is', () => {
    const A = loadReportJs().asserts;
    assert.strictEqual(A._draggableValue(0.00123456, 0.00003), 0.00123);
    assert.strictEqual(A._draggableValue(0.00042, 0.0004), 0.0004);
    assert.strictEqual(A._draggableValue(0.0042, 0.003), 0.004);
    // Never coarser than the hundredth it always was: a wide axis lands where it did.
    assert.strictEqual(A._draggableValue(558.8234, 4444), 558.82);
    assert.strictEqual(A._draggableValue(558.8234, 0.5), 558.82);
    // No axis to measure (a test seam, a scripted retune): the hundredth.
    assert.strictEqual(A._draggableValue(558.8234), 558.82);
    assert.strictEqual(A._draggableValue(0.0042), 0);
    // Zero is a threshold; a negative is not.
    assert.strictEqual(A._draggableValue(0, 0.001), 0);
    assert.strictEqual(A._draggableValue(-0.5, 0.001), null);
    assert.strictEqual(A._draggableValue(Infinity, 0.001), null);
});

const RUN_THROUGHPUT = [{ id: 'run-throughput', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 0.001 } }];

test('AF4b: E25\'s lt 0.001 throughput rule, dragged on an axis that fine, keeps a band below a hundredth', () => {
    const W = javaPage(RUN_THROUGHPUT);
    W.asserts._retune('run-throughput', 0.00042, 'fail', 0.0001);
    // Was 0: Math.round(0.042) / 100 — `lt 0`, a rule that can never fire.
    assert.strictEqual(W.asserts._state.rules[0].fail.value, 0.0004);
    assert.strictEqual(W.asserts._state.rules[0].draftState, 'edited');
});

test('AF4b: a handle released on its own pixel changes nothing — the band below a hundredth survives', () => {
    const toasts = [];
    const W = javaPage(RUN_THROUGHPUT, toasts);
    // E25's throughput axis: about 0.033 /s a pixel. The handle sits on 0.001's pixel.
    W.asserts._retune('run-throughput', 0.0011, 'fail', 0.033);
    assert.strictEqual(W.asserts._state.rules[0].fail.value, 0.001, 'an unmoved handle rewrote its band');
    assert.strictEqual(W.asserts._state.rules[0].draftState, undefined);
    assert.strictEqual(W.document.count(), 0);
    assert.deepStrictEqual(toasts, []);
});

test('AF4b: the drag preview names the band the release will keep', () => {
    const toasts = [];
    const W = javaPage(RUN_THROUGHPUT, toasts);
    W.asserts._previewDrag('run-throughput', 0.0011, 'throughput', 'fail', 0.033);
    assert.deepStrictEqual(toasts, ['run-throughput fail → 0.001'], 'was "→ 0": the band a drop would have written');
});

test('AF4b: a drag that would land a downward band on 0 is refused, and says why', () => {
    const toasts = [];
    const W = javaPage([{ id: 'floor', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 0.5 } }], toasts);
    W.asserts._retune('floor', 0.004, 'fail', 0.033);
    assert.strictEqual(W.asserts._state.rules[0].fail.value, 0.5, 'the chart authored `lt 0`');
    assert.strictEqual(W.document.count(), 0);
    assert.strictEqual(toasts.length, 1, JSON.stringify(toasts));
    assert.ok(toasts[0].indexOf('floor not retuned — fail can never fire') === 0, toasts[0]);
    // An upward band at 0 is a threshold ("no errors at all"), and still lands.
    const up = javaPage([{ id: 'errs', target: { scope: 'overall' }, metric: 'errorRate', fail: { gt: 5 } }]);
    up.asserts._retune('errs', 0.004, 'fail', 0.033);
    assert.strictEqual(up.asserts._state.rules[0].fail.value, 0);
});

// ---------------------------------------------------------------------------
// U10a (client) / L18 / CN16 — the labels are laid out with the handles
// ---------------------------------------------------------------------------

const RECT = { x: 60, y: 40, width: 745, height: 270 };

/** A laid-out ECharts instance over a linear axis [lo, hi], recording what the page set. */
function fakeChart(lo, hi) {
    const set = [];
    const toPixel = (v) => RECT.y + RECT.height - (v - lo) / (hi - lo) * RECT.height;
    return {
        set,
        isDisposed: () => false,
        getWidth: () => 835,
        getHeight: () => 400,
        getModel: () => ({ getComponent: () => ({ coordinateSystem: { getRect: () => RECT } }) }),
        convertToPixel: (finder, point) => [0, toPixel(point[1])],
        convertFromPixel: (finder, point) => [0, lo + (RECT.y + RECT.height - point[1]) / RECT.height * (hi - lo)],
        setOption: (option) => set.push(option),
        graphic() {
            const last = set.filter((o) => o.graphic).pop();
            return last ? last.graphic : [];
        }
    };
}

const LABEL_H = 14;

function boxesOf(graphic) {
    /* This realm's arrays, as `plain` makes them: the page's are the vm context's. */
    const elements = plain(graphic);
    const handles = elements.filter((e) => /^wir-handle-/.test(e.id)).map((e) => ({
        id: e.id, x: e.position[0] + e.shape.x, y: e.position[1] + e.shape.y, w: e.shape.width, h: e.shape.height
    }));
    const labels = elements.filter((e) => /^wir-chart-label-/.test(e.id)).map((e) => ({
        id: e.id, text: e.style.text, x: e.x, y: e.y, w: CS.labelTextWidth(e.style.text), h: LABEL_H
    }));
    return { handles, labels };
}

function overlap(a, b) {
    const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    return w > 0 && h > 0 ? w * h : 0;
}

/* Two whole-run rules on one value (E25's p95 chart had two), a third rule's warn and fail, and the
   run aggregate at 10 /s. */
const ON_ONE_VALUE = [
    { id: 'tp-floor', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 5 } },
    { id: 'tp-floor-too', target: { scope: 'overall' }, metric: 'throughput', fail: { lt: 5 } },
    { id: 'tp-banded', target: { scope: 'overall' }, metric: 'throughput', warn: { lt: 8 }, fail: { lt: 2 } }
];

test('U10a: every rule label is drawn beside the handles — none under one, none on another', () => {
    const W = javaPage(ON_ONE_VALUE);
    const chart = fakeChart(0, 12);
    W.asserts._drawDragHandles(chart, 'throughput');
    const { handles, labels } = boxesOf(chart.graphic());
    assert.strictEqual(handles.length, 4, 'one handle per band');
    // One label per band, carrying the text its line's markLine label always carried.
    const formatters = plain(W.asserts.markLines('throughput')).map((d) => d.label.formatter)
        .filter((t) => t.indexOf('run aggregate') !== 0);
    assert.deepStrictEqual(labels.filter((l) => l.text.indexOf('run aggregate') !== 0).map((l) => l.text).sort(),
        formatters.sort());
    const problems = [];
    labels.forEach((l, i) => {
        handles.forEach((h) => {
            const a = overlap(l, h);
            if (a > 0) problems.push(`"${l.text}" under ${h.id} (${a.toFixed(1)}px²)`);
        });
        labels.slice(i + 1).forEach((m) => {
            const a = overlap(l, m);
            if (a > 0) problems.push(`"${l.text}" on "${m.text}" (${a.toFixed(1)}px²)`);
        });
        if (l.y < RECT.y - 0.5 || l.y + l.h > RECT.y + RECT.height + 0.5) {
            problems.push(`"${l.text}" at ${l.y}..${l.y + l.h} outside the plot ${RECT.y}..${RECT.y + RECT.height}`);
        }
    });
    assert.deepStrictEqual(problems, []);
});

test('U10a: a line\'s own markLine label is not drawn — the layout pass draws it, once', () => {
    const W = javaPage(ON_ONE_VALUE);
    const lines = plain(W.asserts.markLines('throughput'));
    assert.ok(lines.length >= 5, 'four bands and the aggregate');
    lines.forEach((d) => {
        assert.strictEqual(d.label.show, false, `"${d.label.formatter}" is drawn twice`);
        assert.ok(typeof d.label.formatter === 'string' && d.label.formatter.length > 0, 'the formatter stays');
    });
});

test('L18: the run aggregate on a band\'s line keeps its label off the band\'s, on the side its value is on', () => {
    // The aggregate at 4.98 /s on the 5 /s floor: one visible line. Its label went at the line's top
    // left, the band's at its top right — one line, two labels on one side of it.
    const W = javaPage(ON_ONE_VALUE.slice(0, 1), [], { totalSamples: 20, p95: 70, mean: 35, throughput: 4.98 });
    const chart = fakeChart(0, 12);
    W.asserts._drawDragHandles(chart, 'throughput');
    const { labels } = boxesOf(chart.graphic());
    const band = labels.find((l) => l.text.indexOf('tp-floor fail') === 0);
    const aggregate = labels.find((l) => l.text.indexOf('run aggregate') === 0);
    assert.ok(band && aggregate, 'labels: ' + labels.map((l) => l.text).join(' | '));
    const lineY = chart.convertToPixel({}, [0, 5])[1];
    const above = (l) => l.y + l.h <= lineY + 0.5;
    assert.notStrictEqual(above(aggregate), above(band), 'both labels on one side of the line');
    // 4.98 is under the band: its label is under the line.
    assert.strictEqual(above(aggregate), false);
    assert.strictEqual(overlap(band, aggregate), 0);
});

test('L18: an aggregate away from every band keeps its label above its own line, at the left', () => {
    const W = javaPage(ON_ONE_VALUE.slice(0, 1));
    const chart = fakeChart(0, 12);
    W.asserts._drawDragHandles(chart, 'throughput');
    const aggregate = boxesOf(chart.graphic()).labels.find((l) => l.text.indexOf('run aggregate') === 0);
    const lineY = chart.convertToPixel({}, [0, 10])[1];
    assert.ok(aggregate.y + aggregate.h <= lineY + 0.5 && aggregate.y + aggregate.h >= lineY - 3,
        `the aggregate label at ${aggregate.y} against its line at ${lineY}`);
    assert.ok(aggregate.x <= RECT.x + 8, 'not at the left: ' + aggregate.x);
});

test('U10a: a chart whose rules draw no band here still names the run aggregate', () => {
    // A rule on another metric: no line, no handle, but the aggregate is marked on every rule chart.
    const W = javaPage([{ id: 'p95-only', target: { scope: 'overall' }, metric: 'p95', fail: { gt: 1000 } }]);
    const chart = fakeChart(0, 12);
    W.asserts._drawDragHandles(chart, 'throughput');
    const { handles, labels } = boxesOf(chart.graphic());
    assert.strictEqual(handles.length, 0);
    assert.deepStrictEqual(labels.map((l) => l.text), ['run aggregate 10']);
});

/*
 * The labels were markLine labels, which ECharts moves with their lines whatever rescales the axis.
 * Laid out as graphic text they sit at pixels, and only a redraw moves them — and a legend click
 * rescales the value axis with no dataZoom and no resize (the review of this track). So these run on
 * the bundled ECharts itself, server-side rendered, with the page's own wiring: the event a legend
 * click dispatches is the event under test, and a stand-in chart could only fire what the test
 * already believed it fired.
 */
const ECHARTS = path.resolve(__dirname,
    '../../jmeter-web-insight-report/src/main/resources/static/js/echarts.min.js');

/** A throughput chart as the Timeline tab builds one: series[0] carries the rule lines. */
function realChart(W) {
    const echarts = require(ECHARTS);
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 835, height: 400 });
    chart.setOption({
        animation: false,
        legend: { data: ['Total', 'burst'], bottom: 10 },
        grid: { left: 60, right: 30, top: 40, bottom: 90 },
        xAxis: { type: 'category', data: ['a', 'b', 'c', 'd'] },
        yAxis: { type: 'value' },
        series: [
            { name: 'Total', type: 'line', data: [9, 11, 10, 10],
                markLine: { silent: false, symbol: 'none', data: plain(W.asserts.markLines('throughput')) } },
            // The tall one: hiding it takes the axis from about 0..40 down to about 0..12.
            { name: 'burst', type: 'line', data: [10, 40, 25, 30] }
        ]
    });
    return chart;
}

/** Each label's id and box, as last handed to the chart. */
function labelsOn(chart) {
    const graphic = chart.getOption().graphic;
    const elements = graphic && graphic[0] ? graphic[0].elements || [] : [];
    return plain(elements).filter((e) => /^wir-chart-label-/.test(e.id))
        .map((e) => ({ id: e.id, text: e.style.text, x: e.x, y: e.y }));
}

function withRealChart(entries, fn) {
    const W = javaPage(entries);
    const chart = realChart(W);
    try {
        fn(W, chart);
    } finally {
        // An SSR instance runs zrender's frame loop on a timer: undisposed, node never exits.
        chart.dispose();
    }
}

const LEGEND_ACTIONS = [
    { what: 'a legend click', action: { type: 'legendToggleSelect', name: 'burst' } },
    { what: 'Inverse', action: { type: 'legendInverseSelect' } },
    { what: 'a scripted unselect', action: { type: 'legendUnSelect', name: 'burst' } }
];

LEGEND_ACTIONS.forEach(({ what, action }) => {
    test(`U10a: ${what} that rescales the axis lays the labels out again, on their lines`, () => {
        withRealChart(ON_ONE_VALUE.slice(0, 1), (W, chart) => {
            W.asserts._addDragHandles(chart, 'throughput-chart', 'throughput');
            const before = labelsOn(chart);
            assert.ok(before.some((l) => l.id === 'wir-chart-label-' + W.asserts._keyOf('tp-floor') + '-fail'), JSON.stringify(before));
            const lineBefore = chart.convertToPixel({ gridIndex: 0 }, [0, 5])[1];

            chart.dispatchAction(action);
            if (action.type === 'legendInverseSelect') {
                // Inverse hid the carrier and showed nothing new: put the carrier back, burst hidden.
                chart.dispatchAction({ type: 'legendToggleSelect', name: 'Total' });
            }
            const lineAfter = chart.convertToPixel({ gridIndex: 0 }, [0, 5])[1];
            assert.ok(Math.abs(lineAfter - lineBefore) > 50,
                `precondition: the axis rescaled (the 5 /s line ${lineBefore} -> ${lineAfter})`);

            const after = labelsOn(chart);
            // What a draw from scratch puts there now, on the rescaled axis.
            W.asserts._drawDragHandles(chart, 'throughput');
            const fresh = labelsOn(chart);
            assert.deepStrictEqual(after, fresh,
                'the labels stayed where the old axis put them: ' + JSON.stringify(after));
            const band = fresh.find((l) => l.id === 'wir-chart-label-' + W.asserts._keyOf('tp-floor') + '-fail');
            assert.ok(band.y < lineAfter && band.y > lineAfter - 20,
                `"${band.text}" at ${band.y}, its line at ${lineAfter}`);
        });
    });
});

test('U10a: hiding the series that carries the rule lines takes their labels and handles with them', () => {
    withRealChart(ON_ONE_VALUE.slice(0, 1), (W, chart) => {
        W.asserts._addDragHandles(chart, 'throughput-chart', 'throughput');
        const ids = () => {
            const graphic = chart.getOption().graphic;
            return plain(graphic && graphic[0] ? graphic[0].elements || [] : []).map((e) => e.id).sort();
        };
        const shown = ids();
        // By the row's key, as every handle is (rowKeys).
        const k = W.asserts._keyOf('tp-floor');
        assert.deepStrictEqual(shown, ['wir-chart-label-' + k + '-fail', 'wir-chart-label-run-aggregate',
            'wir-handle-' + k + '-fail'].sort());
        chart.dispatchAction({ type: 'legendToggleSelect', name: 'Total' });
        // Its markLine went with it: a label or a handle left behind names a line that is not drawn.
        assert.deepStrictEqual(ids(), []);
        chart.dispatchAction({ type: 'legendToggleSelect', name: 'Total' });
        assert.deepStrictEqual(ids(), shown);
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
