/**
 * The Errors tab's grouped view (E2 of the Aggregate Report check).
 *
 * Its groups were built from the detail records alone, which are the first 500 errors: past 500 the
 * counts and the last seen were the first 500's and the view did not say so (S38: 3,559 errors, its
 * groups added up to 500). Counts and first/last seen now come from the error summaries, which count
 * every error; the records are the occurrences listed under a group. The rendering is the browser's
 * question (error-details-grouping.spec.ts).
 */
'use strict';

const assert = require('assert');
const { loadReportScope } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const summaries = [
    { samplerName: 'Pay', responseCode: '500', responseMessage: 'Internal', occurrenceCount: 900, firstTimestamp: 1000, lastTimestamp: 90000 },
    { samplerName: 'Cart', responseCode: '503', responseMessage: 'Busy', occurrenceCount: 40, firstTimestamp: 70000, lastTimestamp: 80000 },
];
const records = [
    { sampler: 'Pay', code: '500', message: 'Internal', timestamp: 1000 },
    { sampler: 'Pay', code: '500', message: 'Internal', timestamp: 2000 },
];

test('E2: a group counts every error and spans every error, not the kept records', () => {
    const scope = loadReportScope();
    const groups = scope.errorGroupsFrom(records, summaries);
    const pay = groups.find((g) => g.sampler === 'Pay');
    assert.strictEqual(pay.count, 900);
    assert.strictEqual(pay.occurrences.length, 2, 'the kept records are what is listed under it');
    assert.strictEqual(pay.first, 1000);
    assert.strictEqual(pay.last, 90000, 'not the last kept record\'s 2000');
});

test('E2: a group none of whose errors were kept is still listed', () => {
    const scope = loadReportScope();
    const groups = scope.errorGroupsFrom(records, summaries);
    assert.strictEqual(groups.length, 2);
    const cart = groups.find((g) => g.sampler === 'Cart');
    assert.strictEqual(cart.count, 40);
    assert.strictEqual(cart.occurrences.length, 0);
    assert.strictEqual(cart.last, 80000);
});

test('E2: without summaries the records are grouped as before', () => {
    const scope = loadReportScope();
    const groups = scope.errorGroupsFrom(records, undefined);
    assert.strictEqual(groups.length, 1);
    assert.strictEqual(groups[0].count, 2);
    assert.strictEqual(groups[0].first, 1000);
    assert.strictEqual(groups[0].last, 2000);
});

test('E2: the By-time list says it holds the first 500 of N', () => {
    const scope = loadReportScope();
    assert.strictEqual(scope.keptErrorsNote(500, 3559),
        'The first 500 of 3559 errors, kept in detail. The Error Summary, the charts and the Grouped counts include every error.');
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
