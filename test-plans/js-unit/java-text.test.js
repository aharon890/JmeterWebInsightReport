/**
 * WIR.javaText.parseDouble and stringTrim: reading a text the way Java reads it (RB8), in node.
 *
 * MetricRef.parse reads a percentile through Double.parseDouble, and ServerMetricsMapper.asNumber
 * reads a quoted band through it, so the page's two readers of those — parseMetric on the Asserts
 * tab, javaNumber on the Server tab — go through one copy of Java's grammar: a sign, digits with a
 * point anywhere, an exponent, an f/F/d/D suffix, NaN and Infinity, and a hex float rounded as
 * FloatingDecimal.parseHexString rounds it. The parity vectors hold the percentile spellings on all
 * four runners (assert-parity-vectors.json, RB8 (a)); this holds the grammar's edges, which no
 * percentile reaches: the hex float's rounding at both ends of the range, the exponent past int's,
 * and what the two trims keep.
 *
 * Every expected value here is Double.parseDouble's on JDK 21. When this reader was written (3.1.50)
 * 20,000 generated strings went through it and through Java, and every double agreed bit for bit,
 * and every percentile MetricRef.parse named; the ones below are the edges of that run.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const { loadReportJs } = require('./harness');

const WIR = loadReportJs();
const J = WIR.javaText;

if (!J || !J.parseDouble || !J.stringTrim) {
    throw new Error('report.js exposed no WIR.javaText.parseDouble/stringTrim — the harness is stale');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

/** Bit for bit: -0 is not 0, and NaN is NaN. */
const same = (text, expected) => {
    const actual = J.parseDouble(text);
    assert.ok(Object.is(actual, expected),
        JSON.stringify(text) + ' read ' + String(Object.is(actual, -0) ? '-0' : actual)
        + ', Java reads ' + String(Object.is(expected, -0) ? '-0' : expected));
};

test('a decimal in every spelling Java reads', () => {
    same('95', 95);
    same('95d', 95);
    same('95F', 95);
    same('+95', 95);
    same('-95', -95);
    same('8.5E1', 85);
    same('.95e2', 95);
    same('95.', 95);
    same('1.e1', 10);
    same('+.5', 0.5);
    same('00095', 95);
    same('1e05', 100000);
    same('99.99999999999999', 99.99999999999999);
    same('4.9e-324', Number.MIN_VALUE);
    same('1e99999999999', Infinity);
    same('1e-99999999999', 0);
    same('-0', -0);
});

test('NaN and Infinity are read as Java spells them, and no other way', () => {
    same('NaN', NaN);
    same('-NaN', NaN);
    same('Infinity', Infinity);
    same('-Infinity', -Infinity);
    same('nan', null);
    same('infinity', null);
    same('NaNd', null);
    same('Infinityf', null);
});

test('what Double.parseDouble throws on is null', () => {
    ['', '.', '+', '-', 'e5', '.e5', '1e', '1e+', '95dd', '95x', '1.5.2', '1e5.5', '1e+-5', '95 d', '1_000',
        '0b11', '0o7', '0x10', '0x', '0xp1', '0x.p1', '0x1p', '0x55p0dd', '\u0669\u0665'].forEach((text) => {
        same(text, null);
    });
    assert.strictEqual(J.parseDouble(95), null, 'only a text is parsed');
    assert.strictEqual(J.parseDouble(null), null);
});

test('String.trim: a control character is trimmed, a no-break space is not', () => {
    same(' 95 ', 95);
    same('\t95\n', 95);
    same('\u000195\u001f', 95);
    same('\u00a095', null);
    same('95\u00a0', null);
    same('\u2028' + '95', null);
    assert.strictEqual(J.stringTrim('\u0001 p95\t'), 'p95');
    assert.strictEqual(J.stringTrim('\u00a0p95'), '\u00a0p95');
});

test('a hex float is its significand times two to its exponent', () => {
    same('0x55p0', 85);
    same('0X55P0', 85);
    same('0x55p0d', 85);
    same('0x1.86p6', 97.5);
    same('0x.8p1', 1);
    same('0x1.p1', 2);
    same('-0x1p4', -16);
    same('+0x1p4', 16);
    same('0x00000000000001p0', 1);
});

test('a hex float rounds to the nearest double, ties to even, at 53 bits and below them', () => {
    // At the top of the percentile range: the last double below 100 is 0x1.8ffffffffffffp6.
    same('0x1.8ffffffffffff7p6', 99.99999999999999);
    same('0x1.8ffffffffffff8p6', 100);      // the tie, to the even neighbour
    same('0x1.8ffffffffffff9p6', 100);
    // At the bottom, where a double has fewer than 53 bits: rounded from the significand's own bits.
    same('0x1p-1074', Number.MIN_VALUE);
    same('0x1p-1075', 0);                   // exactly half the smallest double: even is 0
    same('-0x1p-1075', -0);
    same('0x1.8p-1075', Number.MIN_VALUE);
    same('0x1.0000000000001p-1075', Number.MIN_VALUE);
    same('0x1p-1076', 0);
    // At the top of the range.
    same('0x1.fffffffffffff7p1023', Number.MAX_VALUE);
    same('0x1.fffffffffffff8p1023', Infinity);
    same('0x1p1024', Infinity);
});

test('an exponent past int\'s range is answered by the two signs, as Integer.parseInt fails', () => {
    same('0x1p2147483647', Infinity);
    same('0x1p2147483648', Infinity);
    same('0x1p-2147483648', 0);
    same('-0x1p2147483648', -Infinity);
    same('0x0p99999999999', 0);
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
