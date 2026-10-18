/**
 * Dead code in the page (S4-dead-code): what the report ships and nothing reaches. Static — it
 * reads report.js, report.css, report.html and the plugin's Java sources as text, and runs nothing.
 *
 * Dead page code is not inert to a reader of it. `.wir-legacy-head` read as evidence a deleted panel
 * still existed; two init functions ran on every load to render Markdown into elements the template
 * stopped writing long ago; a port of java.net.URI's parser — 300 lines, for sources a file has not
 * been able to declare since C8 — sat under a comment saying it would go "with the Server-tab
 * rewrite", which shipped without it.
 *
 * Three questions, each answered for the whole file rather than for a list of names:
 *
 *  - every function report.js declares is named somewhere besides its own declaration — code, not
 *    a comment — or by the template (an inline handler). A cluster that only calls itself loses its
 *    entry points here first, and then the next layer, so it cannot survive a pass;
 *  - every id report.js looks up by a literal name is one the template writes, or one report.js
 *    itself writes or builds — never only looked up;
 *  - every class report.css styles is named by the template, report.js or the Java that renders the
 *    page, or is built from one of the prefixes the page concatenates a status or a type onto.
 *
 * What it does not try: a class or id built from a prefix it was not told about, a function reached
 * only through a string (there is none), or CSS custom properties. Nor a function whose name another
 * module declares too (REV-13): names are counted across the whole of report.js, not per module, so
 * where WIR.asserts and WIR.serverKpis each declare one — drawDragHandles, commitDrag, closeEditor,
 * formatValue — the calls of either copy keep the other alive, and a copy nothing calls any more is
 * not flagged.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const MAIN = path.resolve(__dirname, '../../jmeter-web-insight-report/src/main');
const read = (p) => fs.readFileSync(path.join(MAIN, p), 'utf8');
const JS = read('resources/static/js/report.js');
const CSS = read('resources/static/css/report.css');
const HTML = read('resources/templates/report.html');

function javaSources(dir, out) {
    fs.readdirSync(dir).forEach((f) => {
        const p = path.join(dir, f);
        if (fs.statSync(p).isDirectory()) javaSources(p, out);
        else if (f.endsWith('.java')) out.push(fs.readFileSync(p, 'utf8'));
    });
    return out;
}
const JAVA = javaSources(path.join(MAIN, 'java'), []).join('\n');

/**
 * The source with its comments blanked and its strings kept: a scanner, not a regex, because a
 * string holds comment-like text — an editor placeholder "/*   or   regex:^POST /(a|b)" opened a
 * "comment" a regex closed 1,285 characters later, and code inside it read as unused. A `/` is a
 * regex literal where an expression starts (after an operator, a bracket, a comma or `return`), and
 * division otherwise.
 */
function stripComments(src) {
    let out = '';
    let i = 0;
    let lastSignificant = '';
    let lastWord = '';
    const n = src.length;
    while (i < n) {
        const c = src[i];
        const next = src[i + 1];
        if (c === '/' && next === '*') {
            const end = src.indexOf('*/', i + 2);
            const stop = end < 0 ? n : end + 2;
            out += src.slice(i, stop).replace(/[^\n]/g, ' ');
            i = stop;
            continue;
        }
        if (c === '/' && next === '/') {
            let end = src.indexOf('\n', i);
            if (end < 0) end = n;
            out += ' '.repeat(end - i);
            i = end;
            continue;
        }
        if (c === '\'' || c === '"' || c === '`') {
            let j = i + 1;
            while (j < n && src[j] !== c) j += src[j] === '\\' ? 2 : 1;
            out += src.slice(i, j + 1);
            i = j + 1;
            lastSignificant = c;
            lastWord = '';
            continue;
        }
        if (c === '/' && (/[(,=:[!&|?{};+\-*%<>~^]/.test(lastSignificant) || lastSignificant === ''
                || lastWord === 'return' || lastWord === 'typeof')) {
            let j = i + 1;
            let inClass = false;
            while (j < n && (inClass || src[j] !== '/') && src[j] !== '\n') {
                if (src[j] === '\\') j++;
                else if (src[j] === '[') inClass = true;
                else if (src[j] === ']') inClass = false;
                j++;
            }
            out += src.slice(i, j + 1);
            i = j + 1;
            lastSignificant = '/';
            lastWord = '';
            continue;
        }
        out += c;
        if (!/\s/.test(c)) {
            if (/[\w$]/.test(c)) {
                lastWord = (/[\w$]/.test(lastSignificant) ? lastWord : '') + c;
                lastSignificant = c;
            } else {
                lastSignificant = c;
                lastWord = '';
            }
        }
        i++;
    }
    return out;
}
const JS_CODE = stripComments(JS);
const CSS_CODE = CSS.replace(/\/\*[\s\S]*?\*\//g, ' ');

const escape = (s) => s.replace(/[-$]/g, '\\$&');
const count = (text, name, extra) => (text.match(new RegExp('(^|[^\\w$' + (extra || '') + '])' + escape(name)
    + '(?![\\w$' + (extra || '') + '])', 'g')) || []).length;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('every function report.js declares is called, passed or exported somewhere', () => {
    const names = new Set();
    const re = /function\s+([A-Za-z_$][\w$]*)\s*\(/g;
    let m;
    while ((m = re.exec(JS))) names.add(m[1]);
    assert.ok(names.size > 500, 'read ' + names.size + ' functions: is this still report.js?');
    const dead = [...names].filter((n) => count(JS_CODE, n) <= 1 && count(HTML, n) === 0);
    assert.deepStrictEqual(dead, [], 'declared and never named again: ' + dead.join(', '));
});

test('every id report.js looks up by name is one something writes', () => {
    const looked = new Set();
    const re = /getElementById\('([\w-]+)'\)/g;
    let m;
    while ((m = re.exec(JS_CODE))) looked.add(m[1]);
    assert.ok(looked.size > 50, 'read ' + looked.size + ' lookups');
    const orphans = [...looked].filter((id) => {
        if (new RegExp('\\sid="' + escape(id) + '"').test(HTML)) return false;
        /* Written by report.js: in markup it builds, or as a property or attribute. */
        const lookups = (JS_CODE.match(new RegExp("getElementById\\('" + escape(id) + "'\\)", 'g')) || []).length;
        return count(JS_CODE, id, '-') <= lookups;
    });
    assert.deepStrictEqual(orphans, [], 'looked up and never written: ' + orphans.join(', '));
});

/* The prefixes the page concatenates a status, a kind or a type onto: 'wir-chip-' + status and the
   like in report.js, th:classappend="'badge-' + ${marker.type}" in the template. A class under one
   of them is reached by construction. */
const BUILT_PREFIXES = ['badge-', 'assert-badge-', 'error-class-', 'wir-chip-', 'wir-gate-', 'wir-count-',
    'wir-finding-', 'wir-kind-', 'wir-validate-'];

test('the scanner blanked comments only — what is left still compiles, line for line', () => {
    assert.strictEqual(JS_CODE.length, JS.length);
    assert.strictEqual(JS_CODE.split('\n').length, JS.split('\n').length);
    new (require('vm').Script)(JS_CODE, { filename: 'report.js without comments' });
});

test('the dynamic prefixes are still built — or this test is excusing classes nothing makes', () => {
    const unbuilt = BUILT_PREFIXES.filter((p) => !(new RegExp(escape(p) + "' \\+").test(JS_CODE)
        || new RegExp("'" + escape(p) + "' \\+").test(HTML)));
    assert.deepStrictEqual(unbuilt, [], 'no longer built: ' + unbuilt.join(', '));
});

test('every class report.css styles is one the page can carry', () => {
    const classes = new Set();
    const selectors = CSS_CODE.replace(/\{[^{}]*\}/g, '{}');
    (selectors.match(/\.[A-Za-z_][\w-]*/g) || []).forEach((c) => classes.add(c.slice(1)));
    assert.ok(classes.size > 300, 'read ' + classes.size + ' classes');
    const dead = [...classes].filter((c) => !BUILT_PREFIXES.some((p) => c.indexOf(p) === 0)
        && count(JS_CODE, c, '-') === 0 && count(HTML, c, '-') === 0 && count(JAVA, c, '-') === 0);
    assert.deepStrictEqual(dead, [], 'styled and never carried: ' + dead.join(', '));
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
