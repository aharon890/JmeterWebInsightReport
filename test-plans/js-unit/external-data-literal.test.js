/**
 * The external-data sidecar, as the browser reads it: an object literal, not JSON (N2).
 *
 * `window.__WIR_DATA__={...};` is loaded with <script src>, so it is evaluated as JavaScript, and
 * in an object literal `"__proto__": v` SETS THE PROTOTYPE instead of adding an own key. A component
 * named __proto__ therefore vanished from the sidecar's payload: its asserts named an undeclared
 * component, and a Download wrote them without it, which the next run refuses. JSON.parse (the
 * inline payload's reader) has no such rule, which is why only the sidecar lost it.
 *
 * Java's ObjectLiteralWriter rewrites the key token to the computed form `["__proto__"]:`, which is
 * an own key. ObjectLiteralWriterTest pins that the writer turns object-literal-input.json into
 * exactly object-literal-expected.js; this file evaluates that very text in a vm, as a browser
 * would, and holds it to what JSON.parse makes of the input. No browser, no fixture.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DIR = path.resolve(__dirname, '../../jmeter-web-insight-report/src/test/resources/template');
const INPUT = fs.readFileSync(path.join(DIR, 'object-literal-input.json'), 'utf8').trim();
const WRITTEN = fs.readFileSync(path.join(DIR, 'object-literal-expected.js'), 'utf8');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

/** What a page's <script src> leaves behind: window.__WIR_DATA__, evaluated in a fresh realm. */
function evaluate(script) {
    const sandbox = { window: {} };
    vm.runInNewContext(script, sandbox, { filename: 'web-insight-data.js' });
    return sandbox.window.__WIR_DATA__;
}

test('the written sidecar evaluates to exactly what JSON.parse makes of the payload', () => {
    // JSON.stringify writes own keys only — "__proto__" included when it is one — so equal text
    // is every key kept, in order, with nothing turned into a prototype.
    assert.strictEqual(JSON.stringify(evaluate(WRITTEN)), JSON.stringify(JSON.parse(INPUT)));
});

test('every "__proto__" key is an own key, at every depth', () => {
    const data = evaluate(WRITTEN);
    assert.ok(own(data, '__proto__'), 'top level');
    assert.ok(own(data.annotationsDocument.serverMetrics.components, '__proto__'), 'a component named __proto__');
    assert.ok(own(data.annotationsDocument.serverMetrics.asserts[0], '__proto__'), 'inside an assert');
    assert.ok(own(data.nested[0], '__proto__'), 'inside an array');
    assert.ok(own(data.nested[0].__proto__[2], '__proto__'), 'two levels down, holding null');
    assert.strictEqual(Object.getPrototypeOf(data.annotationsDocument.serverMetrics.components),
        Object.getPrototypeOf(data.annotationsDocument.serverMetrics.components.db),
        'no object had its prototype replaced');
});

test('"__proto__" inside a string is text, not a key', () => {
    const data = evaluate(WRITTEN);
    assert.deepStrictEqual(JSON.parse(JSON.stringify(data.strings)),
        ['__proto__', '"__proto__": inside a value', 'ends in a backslash\\', '"__proto__']);
    assert.strictEqual(data['"__proto__'], 'a key that only looks like one');
    assert.strictEqual(data.annotationsDocument.serverMetrics.asserts[0].component, '__proto__');
});

test('the computed form is what makes it own — the raw payload as a literal loses the key (control)', () => {
    // The sidecar as it was written before the rewrite. If this ever passed, the test above
    // would prove nothing about the rewrite.
    const raw = evaluate('window.__WIR_DATA__=' + INPUT + ';');
    assert.ok(!own(raw.annotationsDocument.serverMetrics.components, '__proto__'),
        'a bare "__proto__": key set the prototype');
    assert.notStrictEqual(JSON.stringify(raw), JSON.stringify(JSON.parse(INPUT)));
});

test('an escaped spelling of the key is a prototype setter too, and its computed form is not', () => {
    // A string key's value is read after its escapes, so a key that spells an underscore as a
    // unicode escape is still "__proto__" to the literal. Jackson never escapes an ASCII letter or underscore, but the writer decodes the
    // key it reads rather than matching one spelling of it.
    const key = '"\\u005f_proto__"';
    assert.ok(!own(evaluate('window.__WIR_DATA__={' + key + ':{"e":1}};'), '__proto__'));
    assert.ok(own(evaluate('window.__WIR_DATA__={[' + key + ']:{"e":1}};'), '__proto__'));
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
