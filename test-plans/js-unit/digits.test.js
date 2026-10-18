/**
 * digits-vectors.json, through the page's WIR.digits.beside, in node.
 *
 * The same file is run against Java's Digits.beside by DigitsTest (jmeter-report-core). Java writes
 * a client actual into the CI/CD JSON and JUnit; the page writes the same actual on both tabs; and
 * two copies of one rounding rule drift between the runs that test them. So neither owns it: the
 * vectors do, compared with ===, never a tolerance — the whole question is which side of a band the
 * written number is on.
 *
 * Beside the vectors, the few things only the page's copy has to answer: a band that is not a finite
 * number (an absent warn band is handed in as null) separates nothing, and both tabs' formatters
 * write the number this module chooses.
 *
 * The rule for what belongs here is asserts-pure.test.js's: no DOM, no fixture, no browser.
 */
'use strict';

const assert = require('assert');
const path = require('path');
const { loadReportJs } = require('./harness');

const VECTORS = require(path.resolve(__dirname,
    '../../jmeter-report-core/src/test/resources/assertion/digits-vectors.json'));

/* The definition has 34 cases; a file that lost some must not pass on what is left. Four are
   exact binary ties, which only Java can get wrong (toPrecision and Math.round are fixed by the
   spec, a RoundingMode or a Math.rint is not): they are what holds Digits to this side. Eight are
   at four decimals, the CI/CD JSON's server actual (S2), two of them the cap, which four places
   reach from 1e7 up. */
const FLOOR = 34;

const WIR = loadReportJs();
const D = WIR.digits;

if (!D || !D.beside || !D.measured || !D.enDigits || !D.formatBand) {
    throw new Error('report.js exposed no WIR.digits.beside/measured/enDigits/formatBand — '
        + 'the harness is stale, or the module moved');
}

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// ---------------------------------------------------------------- the vectors

test('the vectors file carries the whole definition', () => {
    assert.ok(VECTORS.cases.length >= FLOOR, 'digits-vectors.json carries ' + VECTORS.cases.length
        + ' cases, fewer than the ' + FLOOR + ' that define the rule');
});

VECTORS.cases.forEach((c) => {
    test('vector: ' + c.name, () => {
        const got = D.beside(c.value, c.decimals, c.bands);
        assert.ok(got === c.expect, 'wrote ' + got + ', expected ' + c.expect);
    });
});

/* L12: how a band's own constant is written, against WIR.javaText.trim — DigitsTest runs the same
   cases against Predicate.trim. */
test('the vectors file carries the band write cases', () => {
    assert.ok((VECTORS.bandWriteCases || []).length >= 19,
        'digits-vectors.json carries ' + (VECTORS.bandWriteCases || []).length + ' band write cases');
});

(VECTORS.bandWriteCases || []).forEach((c) => {
    test('band written: ' + c.name, () => {
        assert.strictEqual(WIR.javaText.trim(c.value), c.expect);
    });
});

test('a band that is not a finite number is its own word, as Double.toString writes it', () => {
    assert.strictEqual(WIR.javaText.trim(NaN), 'NaN');
    assert.strictEqual(WIR.javaText.trim(Infinity), 'Infinity');
    assert.strictEqual(WIR.javaText.trim(-Infinity), '-Infinity');
    // A duration and a percentile key keep Durations.trim's notation, which a band no longer shares.
    assert.strictEqual(WIR.javaText.durationsTrim(0.0005), '5.0E-4');
    assert.strictEqual(WIR.javaText.durationsTrim(1e19), '9223372036854775807');
    assert.strictEqual(WIR.javaText.durationsTrim(97.5), '97.5');
});

// ---------------------------------------------------------------- the page's own questions

test('a band that is not a finite number separates nothing', () => {
    const v = 119.36094674556213;
    assert.strictEqual(D.beside(v, 2, [null, undefined, NaN, Infinity, -Infinity, '119.36']), 119.36);
    // Beside a finite band, that band alone decides.
    assert.strictEqual(D.beside(v, 2, [null, 119.36]), 119.361);
    assert.strictEqual(D.beside(v, 2), 119.36, 'no bands at all is the base');
});

test('measured is beside with only the never-zero band', () => {
    [0.004, 0.0000123, 0.3, -0.4, 2.954, 373.8625, 799450271802898, 0].forEach((v) => {
        [0, 2].forEach((decimals) => {
            assert.strictEqual(D.measured(v, decimals), D.beside(v, decimals, [0]), v + ' at ' + decimals);
        });
    });
    assert.strictEqual(D.measured(0.004, 2), 0.004);
    assert.strictEqual(D.measured(0.3, 0), 0.3);
    assert.strictEqual(D.measured(373.8625, 0), 374);
});

test('enDigits writes every digit en-US, and formatBand never rounds a band', () => {
    assert.strictEqual(D.enDigits(1234.5678), '1,234.5678');
    assert.strictEqual(D.enDigits(-0), '0');
    assert.strictEqual(D.enDigits(1e-21), '1.0E-21', 'below ICU\'s 20 decimals, as Java writes it');
    assert.strictEqual(D.formatBand(373.86), '373.86');
    assert.strictEqual(D.formatBand(10000), '10,000');
    assert.strictEqual(D.formatBand(null), '—');
    assert.strictEqual(D.formatBand(NaN), '—');
});

test('both tabs write the number this module chooses', () => {
    const SK = WIR.serverKpis;
    const A = WIR.asserts;
    [2.954, 0.004, 0.0000123, 1234.567, 799450271802898, 59999999.99999998].forEach((v) => {
        assert.strictEqual(SK._formatValue(v, ''), D.enDigits(D.measured(v, 2)), 'server formatValue(' + v + ')');
    });
    assert.strictEqual(SK._formatActual(3.004, 's', [3]), D.enDigits(D.beside(3.004, 2, [3, 0])));
    // The client keeps its own precision and spelling around the same number.
    assert.strictEqual(A._formatValue(373.8625, 'ms'), D.enDigits(D.measured(373.8625, 0)) + ' ms');
    assert.strictEqual(A._formatActual(373.8625, 'ms', [374]), D.enDigits(D.beside(373.8625, 0, [374, 0])) + ' ms');
    assert.strictEqual(A._describeBand({ op: 'gt', value: 373.86 }, 'ms'), '> ' + D.formatBand(373.86) + ' ms');
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
