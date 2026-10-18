/**
 * The rows that belong to a sampler row (NEW-judging-row-blind, NEW-double-detail-row) — the walk
 * itself, in node, over plain rows.
 *
 * A sampler row can be followed by its chart panel (.sampler-detail-row) and by the rules judging
 * it (.wir-judging-row), in either order. Every walker of the sampler table knew about the first
 * and none about the second: the sort and the search treated a judging row as a sampler of its own,
 * hiding the Sampler column hid its only cell, and toggleSamplerDetail looked only at the NEXT row
 * for an open panel, so with the judging row in between it built a second one (and toggleJudgingRules
 * the mirror image). They all go through these helpers now.
 *
 * What is here is the walk — which rows belong to which, and what display they take — which needs
 * nothing of a page. That the table's own walkers use it (sort, search, column hide, ×, the two
 * toggles) is a browser question: sampler-table-walkers.spec.ts. The column tools (auto-fit and the
 * two reorder walks, NIT-4) are here too, over plain rows with cells: which rows they touch needs no
 * page either.
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const CLASS = {
    sampler: [],
    detail: ['sampler-detail-row'],
    judging: ['wir-judging-row'],
    total: ['total-sampler-row']
};

/** A row as the walk reads it: its classes, its display, and the row after it. */
function row(kind, name) {
    const classes = new Set(CLASS[kind]);
    return {
        name: name || kind,
        style: { display: '' },
        classList: { contains: (c) => classes.has(c) },
        nextElementSibling: null
    };
}

/** A tbody over `rows`, in order, each linked to the next. */
function tbody(rows) {
    rows.forEach((r, i) => { r.nextElementSibling = rows[i + 1] || null; });
    return { rows };
}

/* Array.from, not rows.map: the list comes from report.js's realm, and deepStrictEqual compares
   prototypes. */
const names = (rows) => Array.from(rows, (r) => r.name);

test('a judging row belongs to its sampler as a chart panel does; the Σ Total row does not', () => {
    const scope = loadReportScope();
    assert.strictEqual(scope.isSamplerCompanionRow(row('judging')), true, 'the judging row was a sampler of its own');
    assert.strictEqual(scope.isSamplerCompanionRow(row('detail')), true);
    assert.strictEqual(scope.isSamplerCompanionRow(row('sampler')), false);
    assert.strictEqual(scope.isSamplerCompanionRow(row('total')), false);
    assert.strictEqual(scope.isSamplerCompanionRow(null), false);
});

test('a row\'s companions are the rows after it in either order, up to the next sampler', () => {
    const scope = loadReportScope();
    const a = row('sampler', 'A');
    const b = row('sampler', 'B');
    const c = row('sampler', 'C');
    tbody([a, row('judging', 'A:judging'), row('detail', 'A:detail'),
        b, row('detail', 'B:detail'), row('judging', 'B:judging'), c]);
    assert.deepStrictEqual(names(scope.samplerCompanionRows(a)), ['A:judging', 'A:detail']);
    assert.deepStrictEqual(names(scope.samplerCompanionRows(b)), ['B:detail', 'B:judging']);
    assert.deepStrictEqual(names(scope.samplerCompanionRows(c)), [], 'the last row has none');
});

test('the open chart panel is found past the judging row, so a toggle closes it (NEW-double-detail-row)', () => {
    // toggleSamplerDetail read only row.nextElementSibling: with the judging row in between it
    // decided there was no panel and built a second one, leaking the first one's charts.
    const scope = loadReportScope();
    const a = row('sampler', 'A');
    const judging = row('judging', 'A:judging');
    const detail = row('detail', 'A:detail');
    tbody([a, judging, detail, row('sampler', 'B'), row('detail', 'B:detail')]);
    assert.strictEqual(scope.samplerCompanionRow(a, 'sampler-detail-row'), detail);
    assert.strictEqual(scope.samplerCompanionRow(a, 'wir-judging-row'), judging);
    // ...and never another sampler's: B's panel is not A's.
    const lone = row('sampler', 'A');
    tbody([lone, row('sampler', 'B'), row('detail', 'B:detail')]);
    assert.strictEqual(scope.samplerCompanionRow(lone, 'sampler-detail-row'), null);
});

test('every companion takes its owner\'s display — a judging row is never searched by its own text', () => {
    const scope = loadReportScope();
    const a = row('sampler', 'A');
    const b = row('sampler', 'B');
    const aJudging = row('judging');
    const aDetail = row('detail');
    const bJudging = row('judging');
    const body = tbody([a, aJudging, aDetail, b, bJudging]);
    // The search hid A and showed B; the companions are as they were left.
    a.style.display = 'none';
    bJudging.style.display = 'none';
    scope.syncSamplerCompanionRows(body);
    assert.deepStrictEqual([aJudging, aDetail, bJudging].map((r) => r.style.display), ['none', 'none', '']);
});

test('the sync says when a chart panel came back into view, so its charts are resized', () => {
    const scope = loadReportScope();
    const a = row('sampler', 'A');
    const detail = row('detail');
    const body = tbody([a, row('judging'), detail]);
    detail.style.display = 'none';
    a.style.display = 'none';
    assert.strictEqual(scope.syncSamplerCompanionRows(body), false, 'still hidden: nothing to resize');
    a.style.display = '';
    assert.strictEqual(scope.syncSamplerCompanionRows(body), true, 'shown again: ECharts sized at 0 must resize');
    assert.strictEqual(detail.style.display, '');
    assert.strictEqual(scope.syncSamplerCompanionRows(body), false, 'already on screen');
});

/*
 * NIT-4: the column tools walked the companion rows as if they were sampler rows. autoFitColumn
 * measured every row's cell at the column's index, so an open chart panel's or judging row's one
 * spanning cell sized the Sampler column (to the 800px cap); reorderColumns skipped the chart panel
 * and the Σ Total row but not the judging row, and applyColumnOrder the chart panel alone, so a
 * column move shuffled a judging row's cells. A companion row has no columns of its own.
 */

/** A row with cells, recording every move made in it (moveCell's insertBefore). */
function cellRow(kind, cellCount, name) {
    const r = row(kind, name);
    r.moves = 0;
    r.cells = [];
    for (let i = 0; i < cellCount; i++) {
        r.cells.push({ name: (name || kind) + ':' + i, style: { display: '' }, w: 0 });
    }
    r.insertBefore = (cell, ref) => {
        r.moves++;
        r.cells.splice(r.cells.indexOf(cell), 1);
        const at = ref === null ? r.cells.length : r.cells.indexOf(ref);
        r.cells.splice(at, 0, cell);
    };
    return r;
}

/** A table over a header of `n` columns, tagged with data-col-id as tagColumnIds tags them. */
function table(n, bodyRows) {
    const header = cellRow('sampler', n, 'th');
    header.cells.forEach((c, i) => {
        c.getAttribute = (a) => (a === 'data-col-id' ? String(i) : null);
        c.cellIndex = i;
        c.scrollWidth = 40;
        c.style = { width: '', minWidth: '', maxWidth: '' };
    });
    return {
        id: 'samplerTable',
        style: { width: '' },
        classList: { contains: () => false, add() { }, remove() { } },
        tHead: { rows: [header] },
        tBodies: [{ rows: bodyRows }]
    };
}

/* A sampler row, its open chart panel and its judging row — each companion with as many cells as a
   sampler row, so a walker that does not skip it visibly moves them. */
function bodyWithCompanions() {
    return [cellRow('sampler', 3, 'A'), cellRow('detail', 3, 'A:detail'), cellRow('judging', 3, 'A:judging'),
        cellRow('sampler', 3, 'B')];
}

test('NIT-4: a column dragged to a new place moves no companion row\'s cells', () => {
    const scope = loadReportScope();
    const body = bodyWithCompanions();
    scope.reorderColumns(table(3, body), 0, 2, 'wir_test_order');
    assert.deepStrictEqual(body.map((r) => r.moves), [1, 0, 0, 1], 'moves per row: ' + body.map((r) => r.name).join(', '));
    assert.deepStrictEqual(names(body[2].cells), ['A:judging:0', 'A:judging:1', 'A:judging:2']);
});

test('NIT-4: a saved column order is applied to the sampler rows only', () => {
    const scope = loadReportScope();
    const body = bodyWithCompanions();
    scope.applyColumnOrder(table(3, body), [2, 0, 1]);
    assert.deepStrictEqual(names(body[0].cells), ['A:2', 'A:0', 'A:1'], 'precondition: the sampler row moved');
    assert.deepStrictEqual(body.map((r) => r.moves > 0), [true, false, false, true]);
});

test('NIT-4: auto-fit sizes a column to its sampler rows, never to an open panel\'s spanning cell', () => {
    const scope = loadReportScope();
    /* The measuring and the storing are the browser's: what is decided here is which cells count. */
    scope.measureCellContent = (cell) => cell.w;
    scope.freezeColumnWidths = () => { };
    scope.saveColumnWidths = () => { };
    scope.updateResetWidthsBtn = () => { };
    const body = [cellRow('sampler', 3, 'A'), cellRow('detail', 1, 'A:detail'), cellRow('judging', 1, 'A:judging'),
        cellRow('sampler', 3, 'B')];
    body[0].cells[0].w = 80;
    body[3].cells[0].w = 120;
    body[1].cells[0].w = 1500;   // the chart panel: one cell across the whole table
    body[2].cells[0].w = 1100;   // the rules judging A, likewise
    const t = table(3, body);
    scope.autoFitColumn(t, t.tHead.rows[0].cells[0]);
    // The widest sampler cell and the padding (was 800px, the cap: the panel's cell had won).
    assert.strictEqual(t.tHead.rows[0].cells[0].style.width, '144px');
});

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
