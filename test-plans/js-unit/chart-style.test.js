/**
 * Unit tests for the chart helpers both tabs share (Batch 6, plans/server-tab-followups.md) — the
 * pure half. What a chart actually paints, measured off the canvas, is in Playwright
 * (server-layout.spec.ts, asserts-charts.spec.ts).
 *
 *   - SF37: every band colour reaches 4.5:1 against the chart it is drawn on, in light and dark and
 *     in colour-blind mode, so one colour serves a label as well as a line; colour-blind mode's warn
 *     and fail stay apart under simulated deuteranopia and protanopia; a handle's border at its
 *     alpha reaches 3:1; and every palette, in its dark variant, keeps its series at 3:1 on a dark
 *     chart — high-contrast's blue, purple, crimson, olive, dark green and maroon did not, nor
 *     Okabe-Ito's blue and black. The light palettes are untouched: they are what the reader picked.
 *   - SF35: layoutHandles keeps a handle at the axis minimum inside the plot, and moves only its
 *     rect, never its anchor; two close Per instance labels sit on opposite sides of their lines.
 *   - U10a / L18: layoutChartLabels puts the Asserts tab's rule labels and the run aggregate's beside
 *     the handles, never under one or on each other, inside the plot, and drops none.
 *   - SF38: a stamp's zone is "UTC±hh:mm", from that instant's own offset.
 *   - SF42: Value over time's granularity rungs are spelled as the client filter bar's are.
 *
 * The backgrounds are the ones a chart sits on now that it is transparent (SF37): white in light
 * mode; #111827 behind a Server-tab card and #1e293b behind a report section in dark.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const CS = WIR.chartStyle;
const SK = WIR.serverKpis;

if (!CS || !CS.bandColorFor || !CS.withAlpha || !CS.paletteFor || !CS.palettes || !CS.darkPalettes
        || !CS.layoutHandles || !CS.zoneLabel || !SK || !SK._instanceLabelAligns) {
    throw new Error('report.js exposed no WIR.chartStyle.{bandColorFor, withAlpha, paletteFor, palettes,'
        + ' darkPalettes, layoutHandles, zoneLabel} or WIR.serverKpis._instanceLabelAligns — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
/* A sandbox object, as this realm's: deepStrictEqual compares prototypes. */
const plain = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v));

// ---------------------------------------------------------------- WCAG arithmetic

function rgb(color) {
    let m = /^#([0-9a-f]{6})$/i.exec(color);
    if (m) return [0, 2, 4].map((i) => parseInt(m[1].substr(i, 2), 16)).concat(1);
    m = /^rgba?\(([^)]+)\)$/i.exec(color);
    if (m) {
        const p = m[1].split(',').map(Number);
        return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
    }
    throw new Error('not a colour: ' + color);
}
const lin = (v) => { const x = v / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
const lum = (c) => 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]);
/** Contrast of a colour (alpha composited) against an opaque background. */
function ratio(color, background) {
    const c = rgb(color);
    const b = rgb(background);
    const painted = [0, 1, 2].map((i) => c[i] * c[3] + b[i] * (1 - c[3]));
    const la = lum(painted);
    const lb = lum(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

const LIGHT = ['#ffffff'];
const DARK = ['#111827', '#1e293b'];

// ---------------------------------------------------------------- SF37: band colours

test('SF37: every band colour reads at 4.5:1 on its chart — light, dark and colour-blind', () => {
    for (const dark of [false, true]) {
        for (const cb of [false, true]) {
            for (const kind of ['fail', 'warn']) {
                const c = CS.bandColorFor(kind, dark, cb);
                for (const bg of dark ? DARK : LIGHT) {
                    assert.ok(ratio(c, bg) >= 4.5, `${kind} ${dark ? 'dark' : 'light'}${cb ? ' cb' : ''}: `
                        + `${c} on ${bg} is ${ratio(c, bg).toFixed(2)}:1`);
                }
            }
        }
    }
});

/* Machado, Oliveira & Fernandes 2009, severity 1.0, applied in linear RGB; distance is CIE76 ΔE in
   Lab (D65). The simulation the review measured the colour-blind pair with. */
const CVD = {
    deuteranopia: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
    protanopia: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
};
const srgbLin = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
const srgbOut = (x) => { const c = Math.max(0, Math.min(1, x)); return 255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055); };
function simulate(color, m) {
    const l = rgb(color).slice(0, 3).map(srgbLin);
    return m.map((r) => srgbOut(r[0] * l[0] + r[1] * l[1] + r[2] * l[2]));
}
function lab(c) {
    const [r, g, b] = c.map(srgbLin);
    const f = (t) => (t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116);
    const X = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
    const Y = f(0.2126 * r + 0.7152 * g + 0.0722 * b);
    const Z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
    return [116 * Y - 16, 500 * (X - Y), 200 * (Y - Z)];
}
const deltaE = (a, b) => { const A = lab(a), B = lab(b); return Math.hypot(A[0] - B[0], A[1] - B[1], A[2] - B[2]); };

test('SF37: colour-blind mode draws warn and fail apart for a deuteranope and a protanope, in both themes', () => {
    // The mode exists for these readers, and its fail had been darkened to #c2410c for 4.5:1 beside
    // an amber warn of #b45309: ΔE 0.7 under deuteranopia — one colour on every line, wash and handle.
    for (const dark of [false, true]) {
        const warn = CS.bandColorFor('warn', dark, true);
        const fail = CS.bandColorFor('fail', dark, true);
        for (const kind of Object.keys(CVD)) {
            const d = deltaE(simulate(warn, CVD[kind]), simulate(fail, CVD[kind]));
            assert.ok(d >= 40, `${dark ? 'dark' : 'light'} cb, ${kind}: warn ${warn} and fail ${fail} are ΔE ${d.toFixed(1)}`);
        }
    }
    // The control: the pair it replaces, under the same simulation.
    assert.ok(deltaE(simulate('#b45309', CVD.deuteranopia), simulate('#c2410c', CVD.deuteranopia)) < 2);
});

test('control: the contrast measure reproduces the ratios the review reported — #f0a020 was 2.15:1 on white', () => {
    // A check of the meter, not of report.js: nothing here reads a colour the product draws. It
    // proves the arithmetic above is the one that found the defect.
    assert.ok(ratio('#f0a020', '#ffffff') < 3);
    assert.ok(ratio('#dc3545', '#111827') < 4.5);
    assert.ok(ratio('rgba(239,68,68,0.55)', '#ffffff') < 3);
    assert.ok(ratio('rgba(245,158,11,0.55)', '#ffffff') < 3);
});

test('SF37: a handle border at its 0.9 alpha reaches 3:1 in every theme — raising 0.55 alone left light warn at 2:1', () => {
    for (const dark of [false, true]) {
        for (const cb of [false, true]) {
            for (const kind of ['fail', 'warn']) {
                const stroke = CS.withAlpha(CS.bandColorFor(kind, dark, cb), 0.9);
                for (const bg of dark ? DARK : LIGHT) {
                    assert.ok(ratio(stroke, bg) >= 3, `${kind} ${dark ? 'dark' : 'light'}${cb ? ' cb' : ''}: `
                        + `${stroke} on ${bg} is ${ratio(stroke, bg).toFixed(2)}:1`);
                }
            }
        }
    }
    // The fix the verify round refuted: the old warn colour at 0.9 is still under 3:1 on white.
    assert.ok(ratio('rgba(245,158,11,0.9)', '#ffffff') < 3);
});

test('SF37: colour-blind mode draws fail in orange, as its --color-error is', () => {
    assert.strictEqual(CS.bandColorFor('fail', false, true), '#c2410c');
    assert.strictEqual(CS.bandColorFor('fail', true, true), '#fb923c');
    assert.notStrictEqual(CS.bandColorFor('fail', false, true), CS.bandColorFor('fail', false, false));
});

test('withAlpha writes an rgba() a canvas takes', () => {
    assert.strictEqual(CS.withAlpha('#dc2626', 0.9), 'rgba(220,38,38,0.9)');
    assert.strictEqual(CS.withAlpha('#FBBF24', 0.14), 'rgba(251,191,36,0.14)');
});

// ---------------------------------------------------------------- SF37: palettes

test('SF37: every palette keeps its series at 3:1 on a dark chart', () => {
    for (const name of Object.keys(CS.palettes)) {
        CS.paletteFor(name, true).forEach((c, i) => {
            for (const bg of DARK) {
                assert.ok(ratio(c, bg) >= 3, `${name}[${i}] ${c} on ${bg} is ${ratio(c, bg).toFixed(2)}:1`);
            }
        });
    }
});

test('SF37: the light palettes are what the reader picked, unchanged', () => {
    for (const name of Object.keys(CS.palettes)) {
        assert.strictEqual(CS.paletteFor(name, false), CS.palettes[name]);
    }
    assert.strictEqual(CS.palettes['okabe-ito'][0], '#E69F00');
    assert.strictEqual(CS.palettes['high-contrast'][0], '#0000FF');
    // The default palette passes on dark as it is, so it has no dark variant.
    assert.strictEqual(CS.paletteFor('default', true), CS.palettes['default']);
});

test('SF37: a dark variant swaps only what fails, so the series keep their order and their hues', () => {
    for (const name of Object.keys(CS.darkPalettes)) {
        const light = CS.palettes[name];
        const dark = CS.darkPalettes[name];
        assert.strictEqual(dark.length, light.length, name);
        light.forEach((c, i) => {
            const passes = DARK.every((bg) => ratio(c, bg) >= 3);
            if (passes) assert.strictEqual(dark[i], c, `${name}[${i}] passes on dark and was swapped anyway`);
        });
        assert.strictEqual(new Set(dark.map((c) => c.toLowerCase())).size, dark.length, `${name} repeats a colour`);
    }
});

// ---------------------------------------------------------------- SF35: handle layout

/** A chart that has laid out one grid: [x, y, width, height]. */
function chartWithGrid(x, y, width, height, canvasWidth) {
    return {
        getWidth: () => canvasWidth,
        getModel: () => ({ getComponent: () => ({ coordinateSystem: { getRect: () => ({ x, y, width, height }) } }) })
    };
}

test('SF35: a handle at the axis minimum stays inside the plot; only its rect moves, never its anchor', () => {
    // E26's cpu-headroom dragged to fail 0: the band's pixel IS the plot's bottom edge (212), and the
    // server's fixed rect, centred on it, ran 205..219.
    const chart = chartWithGrid(60, 24, 751, 188, 835);
    const [placed] = CS.layoutHandles(chart, [{ entry: { kind: 'fail' }, y: 212 }]);
    const top = 212 + placed.shapeY;
    assert.ok(top >= 24 && top + 14 <= 212, `rect ${top}..${top + 14} against the plot 24..212`);
    // At the top too.
    const [high] = CS.layoutHandles(chart, [{ entry: { kind: 'warn' }, y: 24 }]);
    assert.ok(24 + high.shapeY >= 24, 'a handle at the axis maximum stays under the top edge');
    // A band in the middle of the plot keeps its rect centred on it, in fail's outer column.
    const [mid] = CS.layoutHandles(chart, [{ entry: { kind: 'fail' }, y: 120 }]);
    assert.strictEqual(mid.shapeY, -7);
    assert.strictEqual(mid.x, Math.floor(60 + 751 - 4 - 74));
});

test('SF35: two handles at one height never overlap — the second moves a column in', () => {
    const chart = chartWithGrid(60, 24, 751, 204, 835);
    const placed = CS.layoutHandles(chart, [
        { entry: { kind: 'fail' }, y: 100 },
        { entry: { kind: 'fail' }, y: 103 },
    ]);
    assert.notStrictEqual(placed[0].x, placed[1].x);
    assert.ok(Math.abs(placed[0].x - placed[1].x) >= 74);
});

// ---------------------------------------------------------------- U10a / L18: chart labels

/* E25's Timeline plot, as asserts-cosmetics.test.js lays one out. */
const PLOT = { x: 60, y: 40, width: 745, height: 270 };
const boxHits = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

test('U10a: a label whose own side of its line is free goes there, against its edge', () => {
    const [above, below, left] = plain(CS.layoutChartLabels(PLOT, [], [
        { text: 'p95-api fail > 1,000 ms', lineY: 150, side: 'above', align: 'right', edge: 700 },
        { text: 'p95-api warn > 800 ms', lineY: 220, side: 'below', align: 'right', edge: 700 },
        { text: 'run aggregate 412 ms', lineY: 260, side: 'above', align: 'left', edge: 65 }
    ]));
    assert.strictEqual(above.y + above.h, 149, 'a fail label sits on its line');
    assert.strictEqual(above.x + above.w, 700, 'right-aligned to its edge');
    assert.strictEqual(below.y, 221, 'a warn label hangs under its line');
    assert.strictEqual(left.x, 65);
    assert.strictEqual(left.y + left.h, 259);
});

test('U10a: a label is never put under an obstacle — a handle on its line moves it to the nearest free height', () => {
    // A handle centred on the line, reaching left past where the label's right edge would be.
    const handle = { x: 600, y: 143, w: 74, h: 14 };
    const [box] = plain(CS.layoutChartLabels(PLOT, [handle], [
        { text: 'p95-api fail > 1,000 ms', lineY: 150, side: 'above', align: 'right', edge: 640 }
    ]));
    assert.ok(!boxHits(box, handle), JSON.stringify(box) + ' under the handle');
    // The nearest free height is just above the handle, not across the plot.
    assert.strictEqual(box.y + box.h, handle.y - 2);
});

test('U10a: two rules on one value get two labels, stacked on their side — never one on the other', () => {
    const boxes = plain(CS.layoutChartLabels(PLOT, [], [
        { text: 'tp-floor fail < 5 /s', lineY: 200, side: 'above', align: 'right', edge: 700 },
        { text: 'tp-floor-too fail < 5 /s', lineY: 200, side: 'above', align: 'right', edge: 700 }
    ]));
    assert.ok(!boxHits(boxes[0], boxes[1]), JSON.stringify(boxes));
    // The first keeps its place; the second takes the free height nearest its own.
    assert.strictEqual(boxes[0].y + boxes[0].h, 199);
    assert.ok(Math.abs(boxes[1].y - boxes[0].y) <= boxes[0].h + 2, 'the second went far: ' + JSON.stringify(boxes));
});

test('U10a: a label stays inside the plot, at either edge, and a crowded chart drops none', () => {
    const [top, bottom] = plain(CS.layoutChartLabels(PLOT, [], [
        { text: 'at the top fail > 9', lineY: PLOT.y + 3, side: 'above', align: 'right', edge: 900 },
        { text: 'at the floor warn < 1', lineY: PLOT.y + PLOT.height - 2, side: 'below', align: 'left', edge: 0 }
    ]));
    assert.strictEqual(top.y, PLOT.y);
    assert.ok(top.x + top.w <= PLOT.x + PLOT.width, 'past the right edge: ' + JSON.stringify(top));
    assert.strictEqual(bottom.y + bottom.h, PLOT.y + PLOT.height);
    assert.strictEqual(bottom.x, PLOT.x);
    // Forty labels on one line of a 270 px plot cannot all be free; every one is still placed.
    const many = [];
    for (let i = 0; i < 40; i++) many.push({ text: 'rule-' + i + ' fail > 5', lineY: 150, side: 'above', align: 'right', edge: 700 });
    const placed = plain(CS.layoutChartLabels(PLOT, [], many));
    assert.strictEqual(placed.length, 40);
    placed.forEach((b) => assert.ok(b.y >= PLOT.y && b.y + b.h <= PLOT.y + PLOT.height, JSON.stringify(b)));
});

test('U10a: a label is as wide as its text — a longer text is never narrower', () => {
    const short = CS.labelTextWidth('fail > 5');
    const long = CS.labelTextWidth('slow-api-p95 fail > 1,000 ms');
    assert.ok(short > 0 && long > short, short + ' / ' + long);
    assert.strictEqual(CS.labelTextWidth(''), 1);
    assert.strictEqual(CS.labelTextWidth(null), 1);
});

// ---------------------------------------------------------------- SF35: Per instance labels

test('SF35: two bands\' Per instance labels sit on opposite sides of their lines, so "wafail" cannot print', () => {
    // An upward pair, warn under fail: warn's label to the left of its line, fail's to the right.
    assert.deepStrictEqual(plain(SK._instanceLabelAligns(480, 500)), { warn: 'right', fail: 'left' });
    // A downward pair, fail under warn: the other way round.
    assert.deepStrictEqual(plain(SK._instanceLabelAligns(50, 10)), { warn: 'left', fail: 'right' });
    // One band keeps its label centred on its line.
    assert.deepStrictEqual(plain(SK._instanceLabelAligns(null, 500)), { warn: 'center', fail: 'center' });
    assert.deepStrictEqual(plain(SK._instanceLabelAligns(480, null)), { warn: 'center', fail: 'center' });
});

// ---------------------------------------------------------------- SF42: one ladder, one spelling

test('SF42: the Server tab spells a granularity rung as the client filter bar does — 1min, never 1m', () => {
    // The two selects offer one ladder; Value over time wrote formatDurationMs's "1m" and "10m"
    // beside the client select's "1min" and "10min". The client's options are the template's.
    const fs = require('fs');
    const path = require('path');
    const html = fs.readFileSync(path.resolve(__dirname,
        '../../jmeter-web-insight-report/src/main/resources/templates/report.html'), 'utf8');
    const select = /<select[^>]*id="granularitySelect"[^>]*>([\s\S]*?)<\/select>/.exec(html);
    assert.ok(select, 'the client Granularity select is in the template');
    const options = [...select[1].matchAll(/<option value="(\d+)">([^<]+)<\/option>/g)];
    assert.ok(options.length >= 5, `${options.length} client options`);
    for (const [, value, text] of options) {
        assert.strictEqual(SK._granularityLabel(Number(value)), text, `${value} ms`);
    }
    // A collection step off the ladder keeps its own spelling.
    assert.strictEqual(SK._granularityLabel(15000), '15s');
});

// ---------------------------------------------------------------- SF38: zones

test('SF38: a stamp\'s zone is UTC±hh:mm from that instant\'s own offset', () => {
    const at = (minutesEast) => ({ getTimezoneOffset: () => -minutesEast });
    assert.strictEqual(CS.zoneLabel(at(0)), 'UTC+00:00');
    assert.strictEqual(CS.zoneLabel(at(330)), 'UTC+05:30');
    assert.strictEqual(CS.zoneLabel(at(180)), 'UTC+03:00');
    assert.strictEqual(CS.zoneLabel(at(-240)), 'UTC-04:00');
    assert.strictEqual(CS.zoneLabel(at(-570)), 'UTC-09:30');
    assert.strictEqual(CS.zoneLabel(at(345)), 'UTC+05:45');
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
