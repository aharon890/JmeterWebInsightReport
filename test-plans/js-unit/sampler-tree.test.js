/**
 * The Samplers table's tree order (samplerTreeOrder in report.js).
 *
 * With Transaction Details on, a transaction's requests used to stay where the alphabetical order put
 * them and only gained an indent, so each looked like it belonged to the row above it: on
 * tails-conversation-flows the stale-link request sat under "Edit and pin my message" and "Open the
 * conversation" under "Login". Now each transaction is followed by its own rows, in the order the run
 * first saw them; a sort orders the top-level rows, and each transaction's rows among themselves.
 * Where the rows land in the page is the browser's question (samplers-tab.spec.ts).
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const scope = loadReportScope();
const order = (names, childrenOf, compare) =>
    // Array.from: the report runs in its own context, whose arrays deepStrictEqual would not take
    Array.from(scope.samplerTreeOrder(names, childrenOf, compare), (r) => '  '.repeat(r.depth) + r.name);

// The rows as Java sends them, alphabetical; the hierarchy with each transaction's rows first seen order
const names = ['Edit and pin', 'GET conversation', 'GET stale link', 'Login', 'Open the conversation',
    'POST message', 'Send and reply', 'Whole session'];
const childrenOf = {
    'Whole session': ['Open the conversation', 'Send and reply'],
    'Open the conversation': ['GET conversation', 'GET stale link'],
    'Send and reply': ['POST message'],
};

test('each transaction is followed by its own rows, in the order first seen, a level deeper each time', () => {
    assert.deepStrictEqual(order(names, childrenOf), [
        'Edit and pin',
        'Login',
        'Whole session',
        '  Open the conversation',
        '    GET conversation',
        '    GET stale link',
        '  Send and reply',
        '    POST message',
    ]);
});

test('a sort orders the top-level rows, and each transaction\'s rows among themselves', () => {
    const value = { 'Edit and pin': 3, 'Login': 9, 'Whole session': 5, 'Open the conversation': 1,
        'Send and reply': 2, 'GET conversation': 8, 'GET stale link': 4, 'POST message': 7 };
    const descending = (a, b) => value[b] - value[a];
    assert.deepStrictEqual(order(names, childrenOf, descending), [
        'Login',
        'Whole session',
        '  Send and reply',
        '    POST message',
        '  Open the conversation',
        '    GET conversation',
        '    GET stale link',
        'Edit and pin',
    ]);
});

test('a row whose parent is not in the table is a top-level row', () => {
    assert.deepStrictEqual(order(['A', 'B'], { Gone: ['A'] }), ['A', 'B']);
});

test('a name listed under two transactions goes under the first that lists it, once', () => {
    // Java lists a name under one parent only; a payload that lists it twice still gets one row
    assert.deepStrictEqual(order(['A', 'B', 'X'], { A: ['X'], B: ['X'] }), ['A', '  X', 'B']);
});

test('a loop in the hierarchy cannot hide a row or recurse forever', () => {
    const got = order(['A', 'B', 'C'], { A: ['B'], B: ['A'] });
    assert.strictEqual(got.length, 3, 'every row once: ' + got.join(' | '));
    assert.deepStrictEqual(got.map((r) => r.trim()).sort(), ['A', 'B', 'C']);
});

test('no hierarchy keeps the order it was given', () => {
    assert.deepStrictEqual(order(['C', 'A', 'B'], {}), ['C', 'A', 'B']);
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
