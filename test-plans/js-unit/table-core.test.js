/**
 * The table toolkit's pure core, WIR.table.view (spec 002, US6, FR-060 to FR-067; tasks T020-T021).
 *
 * Every table of the report goes through one pipeline: filter (search, status chips, the filter bar's
 * ticks), then group, then sort by each column's value within its group (or within the transaction
 * tree), then open, close and fold. These cases hold the rules a reader sees on every table, so the
 * Requests table, the Errors tables, Compare, Asserts, the Server board and the k6 tab cannot answer
 * one question two ways. Where the rows land in a page is the browser's question (Playwright).
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const WIR = loadReportJs();
const T = WIR.table;

const rows = [
    { name: 'Login', status: 'FAIL', p95: 779.72, rate: '8.3/s', rateValue: 8.3, group: 'http_req_duration' },
    { name: 'Browse', status: 'PASS', p95: 12.5, rate: '20.7/min', rateValue: 20.7 / 60, group: 'http_req_duration' },
    { name: 'Search', status: 'PASS', p95: 18.8, rate: '43.0/hour', rateValue: 43 / 3600, group: 'http_reqs' },
    { name: 'Pay', status: 'WARN', p95: null, rate: '1.0/s', rateValue: 1, group: 'http_reqs' },
    { name: 'Admin', status: 'PASS', p95: 105.5, rate: '0.5/s', rateValue: 0.5, group: 'http_req_failed', always: true },
];
const spec = {
    key: (r) => r.name,
    columns: [
        { id: 'name', value: (r) => r.name },
        { id: 'p95', value: (r) => r.p95 },
        { id: 'rate', value: (r) => r.rateValue },
    ],
    search: (r) => r.name + ' ' + r.group,
    status: (r) => r.status,
    nameOf: (r) => r.name,
    groupings: {
        status: { of: (r) => r.status },
        metric: { of: (r) => ({ key: r.group, label: 'metric ' + r.group }) },
    },
};
const names = (v) => Array.from(v.rows.filter((r) => r.kind === 'row'), (r) => r.record.name);
const view = (state, s) => T.view(rows, s || spec, state || {});

test('with no state, every row in the order given, and counts over every row', () => {
    const v = view();
    assert.deepStrictEqual(names(v), ['Login', 'Browse', 'Search', 'Pay', 'Admin']);
    assert.strictEqual(v.total, 5);
    assert.strictEqual(v.matched, 5);
    assert.deepStrictEqual(Object.assign({}, v.counts), { FAIL: 1, PASS: 3, WARN: 1 });
});

test('the search narrows as you type, on the text the table names, whatever the case', () => {
    assert.deepStrictEqual(names(view({ q: 'LOG' })), ['Login']);
    assert.deepStrictEqual(names(view({ q: 'http_reqs' })), ['Search', 'Pay']);
    assert.deepStrictEqual(names(view({ q: '  pay ' })), ['Pay'], 'the ends are trimmed');
});

test('several chips at once, and each chip still counts every row', () => {
    const v = view({ statuses: ['FAIL', 'WARN'] });
    assert.deepStrictEqual(names(v), ['Login', 'Pay']);
    assert.strictEqual(v.matched, 2);
    assert.deepStrictEqual(Object.assign({}, v.counts), { FAIL: 1, PASS: 3, WARN: 1 },
        'a chip that recounts as you filter reads 0 for what you did not pick');
});

test('a table\'s own filters AND with the search and the chips (the Asserts tab\'s More filters)', () => {
    const s = Object.assign({}, spec, { filter: (r) => r.group === 'http_reqs' });
    assert.deepStrictEqual(names(T.view(rows, s, {})), ['Search', 'Pay']);
    assert.deepStrictEqual(names(T.view(rows, s, { statuses: ['WARN'] })), ['Pay']);
    assert.strictEqual(T.view(rows, s, {}).matched, 2);
    assert.strictEqual(T.view(rows, s, {}).total, 5, 'the total is every row');
});

test('the filter bar ticks narrow the rows, and a row tied to no request stays', () => {
    const withWholeRun = rows.concat([{ name: null, status: 'PASS', group: 'http_req_failed' }]);
    const s = Object.assign({}, spec, { key: (r) => r.name || 'whole run' });
    const v = T.view(withWholeRun, s, { names: (n) => n === 'Login' || n === 'Pay' });
    assert.deepStrictEqual(names(v), ['Login', 'Pay', null]);
});

test('sorted by value, never by the text: 8.3/s sorts above 20.7/min', () => {
    assert.deepStrictEqual(names(view({ sort: { col: 'rate', dir: 'desc' } })),
        ['Login', 'Pay', 'Admin', 'Browse', 'Search']);
});

test('a row with no value sorts last, in both directions', () => {
    assert.deepStrictEqual(names(view({ sort: { col: 'p95', dir: 'asc' } })),
        ['Browse', 'Search', 'Admin', 'Login', 'Pay']);
    assert.deepStrictEqual(names(view({ sort: { col: 'p95', dir: 'desc' } })),
        ['Login', 'Admin', 'Search', 'Browse', 'Pay']);
});

test('equal values keep the order given, so repeated clicks cannot shuffle them', () => {
    const same = [{ n: 'b', v: 1 }, { n: 'a', v: 1 }, { n: 'c', v: 1 }];
    const s = { key: (r) => r.n, columns: [{ id: 'v', value: (r) => r.v }] };
    assert.deepStrictEqual(Array.from(T.view(same, s, { sort: { col: 'v', dir: 'desc' } }).rows, (r) => r.key), ['b', 'a', 'c']);
});

test('a table may break ties its own way, in both directions (the Asserts tab: by rule id)', () => {
    const same = [{ n: 'b', v: 1 }, { n: 'a', v: 1 }, { n: 'c', v: 2 }];
    const s = { key: (r) => r.n, columns: [{ id: 'v', value: (r) => r.v }], tie: (x, y) => x.n < y.n ? -1 : x.n > y.n ? 1 : 0 };
    assert.deepStrictEqual(Array.from(T.view(same, s, { sort: { col: 'v', dir: 'asc' } }).rows, (r) => r.key), ['a', 'b', 'c']);
    assert.deepStrictEqual(Array.from(T.view(same, s, { sort: { col: 'v', dir: 'desc' } }).rows, (r) => r.key), ['c', 'a', 'b']);
});

test('one collation for every reader: numbers inside names in order, accents beside their letter', () => {
    const items = ['Item 10', 'item 9', 'Ärger', 'Zebra', 'apple'].map((n) => ({ n }));
    const s = { key: (r) => r.n, columns: [{ id: 'n', value: (r) => r.n }] };
    assert.deepStrictEqual(Array.from(T.view(items, s, { sort: { col: 'n', dir: 'asc' } }).rows, (r) => r.key),
        ['apple', 'Ärger', 'item 9', 'Item 10', 'Zebra']);
});

test('grouped: a header per group carrying counts only, the rows sorted inside it', () => {
    const v = view({ group: 'metric', sort: { col: 'p95', dir: 'desc' } });
    const shape = Array.from(v.rows, (r) => r.kind === 'group' ? '# ' + r.label + ' ' + r.size : '  ' + r.record.name);
    assert.deepStrictEqual(shape, [
        '# metric http_req_duration 2', '  Login', '  Browse',
        '# metric http_req_failed 1', '  Admin',
        '# metric http_reqs 2', '  Search', '  Pay',
    ]);
    const head = v.rows[0];
    assert.deepStrictEqual(Object.assign({}, head.counts), { FAIL: 1, PASS: 1 });
    assert.ok(!('p95' in head) && !('value' in head), 'a group header never adds or averages its rows');
});

test('a group closes and opens, and Expand all / Collapse all have every group key', () => {
    const v = view({ group: 'metric', open: { http_reqs: false } });
    assert.deepStrictEqual(names(v), ['Login', 'Browse', 'Admin']);
    assert.deepStrictEqual(Array.from(v.groups), ['http_req_duration', 'http_req_failed', 'http_reqs']);
    const closed = v.rows.find((r) => r.kind === 'group' && r.key === 'http_reqs');
    assert.strictEqual(closed.open, false);
    assert.strictEqual(closed.size, 2, 'a closed group still counts its rows');
});

test('a grouping can open the groups that hold a failure and close the rest', () => {
    const s = Object.assign({}, spec, { groupings: { metric: {
        of: (r) => r.group, open: (g) => (g.counts.FAIL || 0) > 0 } } });
    const v = T.view(rows, s, { group: 'metric' });
    assert.deepStrictEqual(names(v), ['Login', 'Browse']);
    assert.deepStrictEqual(names(T.view(rows, s, { group: 'metric', open: { http_reqs: true } })),
        ['Login', 'Browse', 'Search', 'Pay'], 'the reader\'s choice wins over the default');
});

test('a grouping may order its groups: failed first', () => {
    const rank = { FAIL: 0, WARN: 1, PASS: 2 };
    const s = Object.assign({}, spec, { groupings: { status: {
        of: (r) => r.status, order: (a, b) => rank[a.key] - rank[b.key] } } });
    const groups = T.view(rows, s, { group: 'status' }).rows.filter((r) => r.kind === 'group').map((r) => r.key);
    assert.deepStrictEqual(Array.from(groups), ['FAIL', 'WARN', 'PASS']);
});

test('a search drops the groups it leaves empty', () => {
    const v = view({ group: 'metric', q: 'login' });
    assert.deepStrictEqual(Array.from(v.rows, (r) => r.kind + ':' + r.key), ['group:http_req_duration', 'row:Login']);
});

test('x hides a row, the table counts it, and Show hidden is a state with none', () => {
    const v = view({ hidden: ['Browse', 'Pay', 'no longer here'] });
    assert.deepStrictEqual(names(v), ['Login', 'Search', 'Admin']);
    assert.strictEqual(v.hidden, 2, 'only the rows this table has');
    assert.strictEqual(names(view({ hidden: [] })).length, 5);
});

test('a hidden row is remembered under the key the table chooses (Asserts: id and occurrence)', () => {
    const s = Object.assign({}, spec, { hideKey: (r) => 'h:' + r.name });
    assert.deepStrictEqual(names(T.view(rows, s, { hidden: ['h:Pay'] })), ['Login', 'Browse', 'Search', 'Admin']);
    assert.strictEqual(T.view(rows, s, { hidden: ['Pay'] }).hidden, 0, 'the row key is not the hide key');
});

test('Show N hidden counts every hidden row, whatever the search leaves out', () => {
    assert.strictEqual(view({ hidden: ['Browse'], q: 'login' }).hidden, 1);
    assert.strictEqual(T.view(treeRows, treeSpec, { group: 'transaction', hidden: ['Open'], q: 'POST' }).hidden, 1);
});

test('folded rows become one line per group, still counted, and open on request', () => {
    const s = Object.assign({}, spec, { fold: (r) => !!r.always && r.status === 'PASS' });
    const v = T.view(rows, s, { group: 'metric' });
    const failed = v.rows.filter((r) => r.group === 'http_req_failed' || r.key === 'http_req_failed');
    assert.deepStrictEqual(Array.from(failed, (r) => r.kind), ['group', 'fold']);
    assert.strictEqual(failed[1].count, 1);
    assert.strictEqual(failed[0].size, 1, 'a folded row counts in its group');
    assert.strictEqual(v.matched, 5);
    const open = T.view(rows, s, { group: 'metric', unfold: { http_req_failed: true } });
    assert.ok(names(open).indexOf('Admin') >= 0);
    assert.deepStrictEqual(names(T.view(rows, s, {})).slice(-1), ['Pay'], 'ungrouped, the fold is the table\'s');
    assert.strictEqual(T.view(rows, s, {}).rows.slice(-1)[0].kind, 'fold');
});

test('a pinned row stays first or last, whatever the filters, and is no chip\'s', () => {
    const withTotals = [{ name: 'Σ Total', status: 'FAIL', p95: 900 }].concat(rows, [{ name: 'Total errors', p95: 1 }]);
    const s = Object.assign({}, spec, {
        pinned: (r) => r.name === 'Σ Total' ? 'first' : r.name === 'Total errors' ? 'last' : null });
    const v = T.view(withTotals, s, { q: 'pay', sort: { col: 'p95', dir: 'desc' }, names: ['Pay'] });
    assert.deepStrictEqual(names(v), ['Σ Total', 'Pay', 'Total errors']);
    assert.strictEqual(v.total, 5);
    assert.deepStrictEqual(Object.assign({}, v.counts), { FAIL: 1, PASS: 3, WARN: 1 });
});

// ----- the transaction tree (Requests, and the k6 timings) -----

const treeRows = ['Whole session', 'Open', 'GET conversation', 'GET stale link', 'Send', 'POST message', 'Login']
    .map((n, i) => ({ name: n, p95: [50, 30, 10, 20, 15, 5, 40][i], status: n === 'GET stale link' ? 'FAIL' : 'PASS' }));
const childrenOf = { 'Whole session': ['Open', 'Send'], 'Open': ['GET conversation', 'GET stale link'],
    'Send': ['POST message'] };
const treeSpec = Object.assign({}, spec, {
    search: (r) => r.name,
    groupings: { transaction: { tree: true } },
    tree: { nameOf: (r) => r.name, childrenOf: childrenOf, open: true },
});
const tree = (v) => Array.from(v.rows, (r) => '  '.repeat(r.depth) + r.record.name);

test('the tree: each transaction followed by its rows, a level deeper each time', () => {
    assert.deepStrictEqual(tree(T.view(treeRows, treeSpec, { group: 'transaction' })), [
        'Whole session', '  Open', '    GET conversation', '    GET stale link', '  Send', '    POST message', 'Login']);
});

test('a sort orders siblings only, so a request never leaves its transaction', () => {
    assert.deepStrictEqual(tree(T.view(treeRows, treeSpec, { group: 'transaction', sort: { col: 'p95', dir: 'desc' } })), [
        'Whole session', '  Open', '    GET stale link', '    GET conversation', '  Send', '    POST message', 'Login']);
});

test('a closed transaction hides its rows, and says it has some', () => {
    const v = T.view(treeRows, treeSpec, { group: 'transaction', open: { Open: false } });
    assert.deepStrictEqual(tree(v), ['Whole session', '  Open', '  Send', '    POST message', 'Login']);
    const open = v.rows.find((r) => r.key === 'Open');
    assert.strictEqual(open.parent, true);
    assert.strictEqual(open.open, false);
    assert.strictEqual(v.rows.find((r) => r.key === 'Login').parent, false);
    assert.deepStrictEqual(Array.from(v.groups), ['Whole session', 'Open', 'Send'], 'Expand all opens every transaction');
});

test('a search keeps a match\'s transactions above it, as context', () => {
    const v = T.view(treeRows, treeSpec, { group: 'transaction', q: 'stale' });
    assert.deepStrictEqual(tree(v), ['Whole session', '  Open', '    GET stale link']);
    assert.deepStrictEqual(Array.from(v.rows, (r) => !!r.context), [true, true, false]);
    assert.strictEqual(v.matched, 1, 'the context rows are not matches');
});

test('a chip keeps its rows\' transactions above them too', () => {
    assert.deepStrictEqual(tree(T.view(treeRows, treeSpec, { group: 'transaction', statuses: ['FAIL'] })),
        ['Whole session', '  Open', '    GET stale link']);
});

test('x on a transaction hides its rows with it', () => {
    const v = T.view(treeRows, treeSpec, { group: 'transaction', hidden: ['Open'] });
    assert.deepStrictEqual(tree(v), ['Whole session', '  Send', '    POST message', 'Login']);
    assert.strictEqual(v.hidden, 1, 'one row was hidden: its rows went with it');
});

test('with no grouping the tree is flat, sorted as one list', () => {
    assert.deepStrictEqual(names(T.view(treeRows, treeSpec, { group: '', sort: { col: 'p95', dir: 'asc' } })),
        ['POST message', 'GET conversation', 'Send', 'GET stale link', 'Open', 'Login', 'Whole session']);
});

test('the rows kept, in the order shown with every group open and nothing folded, are what CSV writes', () => {
    const s = Object.assign({}, spec, { fold: (r) => !!r.always });
    const v = T.view(rows, s, { group: 'metric', open: { http_reqs: false }, hidden: ['Browse'] });
    assert.deepStrictEqual(Array.from(v.kept, (r) => r.name), ['Login', 'Admin', 'Search', 'Pay']);
});

test('an unknown grouping or sort column is no grouping and no sort, never an error', () => {
    assert.deepStrictEqual(names(view({ group: 'nope', sort: { col: 'nope', dir: 'asc' } })),
        ['Login', 'Browse', 'Search', 'Pay', 'Admin']);
});

let failed = 0;
for (const t of tests) {
    try {
        t.fn();
        console.log('  ok   ' + t.name);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + t.name + '\n       ' + String(e.message).split('\n').join('\n       '));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed ? 1 : 0);
