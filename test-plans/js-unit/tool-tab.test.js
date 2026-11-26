/**
 * The k6 tab's pure helpers (WIR.toolTab, spec 002, FR-002, task T041): a threshold's measured value in k6's units,
 * how far a failed one is past its limit, and k6's group path as a reader reads it. The expression itself is read
 * once, in Java (ThresholdExpression, k6's own grammar), and handed to the page as operator and limit.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const WIR = loadReportJs();
const tab = WIR.toolTab;

const t = (o) => Object.assign({ passed: true, statistic: 'value', metricType: 'gauge', metricContains: 'default' }, o);

test('a time in ms to two decimals, as k6\'s summary writes p(95)=779.72ms', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: 779.71796, statistic: 'p(95)', metricType: 'trend', metricContains: 'time' })),
        '779.72 ms');
});

test('a rate metric as a percentage', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: 0.18181818181818182, statistic: 'rate', metricType: 'rate' })),
        '18.18%');
});

test('a counter as its count, or per second for its rate', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: 6, statistic: 'count', metricType: 'counter' })), '6');
    assert.strictEqual(tab.formatMeasured(t({ measured: 0.16435803085270773, statistic: 'rate', metricType: 'counter' })),
        '0.16/s');
});

test('data in B, kB or MB', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: 104, metricType: 'counter', statistic: 'count', metricContains: 'data' })), '104 B');
    assert.strictEqual(tab.formatMeasured(t({ measured: 3800, metricType: 'counter', statistic: 'count', metricContains: 'data' })), '3.8 kB');
    assert.strictEqual(tab.formatMeasured(t({ measured: 2500000, metricType: 'counter', statistic: 'count', metricContains: 'data' })), '2.5 MB');
});

test('a custom gauge\'s bare number, a timestamp included', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: 1795456091718 })), '1795456091718');
    assert.strictEqual(tab.formatMeasured(t({ measured: 3.14159 })), '3.14');
});

test('a value the run data does not have reads "not recorded", never 0', () => {
    assert.strictEqual(tab.formatMeasured(t({ measured: null })), 'not recorded');
    assert.strictEqual(tab.formatMeasured(t({})), 'not recorded');
});

test('a failed upper limit: how far over, in the unit and as a share of the limit', () => {
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 779.71796, operator: '<=', limit: 600,
        statistic: 'p(95)', metricType: 'trend', metricContains: 'time' })), '179.72 ms over (30%)');
});

/* A rate's limit and value are percentages already, so how far past is a difference of two percentages: points, with
   no share of the limit. K1's http_req_failed rate<0.01 read "rate = 18.18%, 17.18% over (1718%)", two percentages
   side by side that read like a contradiction (owner, 2026-11-27: "Fix the 2 report wordings"). */
test('a failed rate threshold: how far past in points, with no share of the limit', () => {
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 0.18181818181818182, operator: '<', limit: 0.01,
        statistic: 'rate', metricType: 'rate' })), '17.18 points over');
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 0.9, operator: '>=', limit: 0.95,
        statistic: 'rate', metricType: 'rate' })), '5.00 points under');
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 0.02, operator: '==', limit: 0,
        statistic: 'rate', metricType: 'rate' })), '2.00 points off');
});

test('a counter\'s rate per second is no percentage: its distance keeps the unit and the share', () => {
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 0.5, operator: '<', limit: 0.4,
        statistic: 'rate', metricType: 'counter' })), '0.10/s over (25%)');
});

test('a failed lower limit is under, an equality is off, and a limit of 0 has no share', () => {
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 40, operator: '>=', limit: 50,
        statistic: 'count', metricType: 'counter' })), '10 under (20%)');
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 3, operator: '==', limit: 0,
        statistic: 'count', metricType: 'counter' })), '3 off');
});

test('no distance for a threshold that passed, one with no value, or a != that failed', () => {
    assert.strictEqual(tab.distanceText(t({ passed: true, measured: 5, operator: '<', limit: 10 })), '');
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: null, operator: '<', limit: 10 })), '');
    assert.strictEqual(tab.distanceText(t({ passed: false, measured: 7, operator: '!=', limit: 7 })), '');
});

test('k6\'s group path as a reader reads it', () => {
    assert.strictEqual(tab.groupText('::Complete Checkout Flow::Inner Payment'), 'Complete Checkout Flow › Inner Payment');
    assert.strictEqual(tab.groupText(''), '');
});

let failed = 0;
for (const c of tests) {
    try {
        c.fn();
        console.log('  ok   ' + c.name);
    } catch (e) {
        failed++;
        console.log('  FAIL ' + c.name + '\n       ' + String(e.message).split('\n').join('\n       '));
    }
}
console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
process.exit(failed ? 1 : 0);
