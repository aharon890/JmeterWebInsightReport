/**
 * The documented jq idioms, evaluated over the golden CI/CD documents where jq is not installed.
 *
 * test-plans/goldens/jq-gate.txt is the one copy of the jq a pipeline gates with, and the JQ LINE
 * IS THE SOURCE OF TRUTH: verify_jq_idioms (scripts/lib/common.sh) runs every line through jq over
 * the expected/G*.json its CASES line names, in S03, R03 and CI. This file exists because jq is
 * not a dependency of the project and a laptop without it would otherwise check nothing: it runs a
 * hand-translated JavaScript copy of each line over the same documents, against the answers the
 * file writes beside it.
 *
 * A translation is only worth anything while it translates the line that is actually there, so
 * each one carries the exact jq text it was written for, and this refuses to pass once the file's
 * line differs from it. Edit a line in jq-gate.txt and this fails until its translation is
 * re-done — the same "two copies, one definition, a check that they agree" the parity vectors use.
 *
 * It also runs selftest_jq_idioms' one-clause mutants, translated: every clause of every line
 * must change the answer over SOME golden, or the goldens cannot see it. Over G1-G4 alone the
 * server half could not be seen at all — G4's client gate FAILs and decides both lines — so a
 * wrong key, a deleted server clause and an inverted failsBuild test all passed the jq check
 * (review of 961f11b). G5-G8 exist for that.
 *
 * No report.js here, and no DOM: this reads JSON files and nothing else.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const GOLDENS = path.join(__dirname, '..', 'goldens');
const IDIOM_FILE = path.join(GOLDENS, 'jq-gate.txt');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// ---------------------------------------------------------------------------
// jq semantics the idioms lean on, and nothing more
// ---------------------------------------------------------------------------

/** `.a.b.c`: null through any missing step, as jq's path on null is null. */
function at(doc, ...keys) {
    let v = doc;
    for (const k of keys) {
        if (v === null || v === undefined) return null;
        if (typeof v !== 'object' || Array.isArray(v)) throw new Error(`jq: cannot index ${typeof v} with "${k}"`);
        v = v[k] === undefined ? null : v[k];
    }
    return v;
}

/** `a // b`: the right side when the left is null or false. */
const alt = (a, b) => (a === null || a === false ? b : a);

/** `.xs[]?`: the elements of an array, the values of an object, nothing at all for anything else. */
function eachOptional(v) {
    if (Array.isArray(v)) return v;
    if (v !== null && typeof v === 'object') return Object.values(v);
    return [];
}

/** `.xs[]`: as above, but jq raises on null — which is the D8 idiom's whole defect. */
function each(v) {
    if (Array.isArray(v)) return v;
    if (v !== null && typeof v === 'object') return Object.values(v);
    throw new Error('jq: Cannot iterate over ' + (v === null ? 'null' : typeof v));
}

/** `[...] | add`: null for an empty list. */
const add = (xs) => (xs.length === 0 ? null : xs.reduce((s, x) => s + x));

/** What `jq -e` reports: true for a truthy last output, false for false/null, 'error' when it raised. */
function jqE(fn, doc) {
    try {
        const out = fn(doc);
        return !(out === null || out === false);
    } catch (e) {
        return 'error';
    }
}

// ---------------------------------------------------------------------------
// The translations — each with the jq text it translates, verbatim. `m` is a mutant: one clause
// broken, exactly as selftest_jq_idioms breaks it in the jq text (MUTANTS below).
// ---------------------------------------------------------------------------

const DEFAULT_JQ = '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"';
const defaultIdiom = (d, m = {}) => {
    if (alt(at(d, 'gate', 'failed'), 0) !== 0) return false;
    if (m.noServerHalf) return true;
    return alt(at(d, m.wrongKey ? 'serverVerdict' : 'serverStatus'), 'PASS') !== 'FAIL';
};

const STRICT_JQ = '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"'
    + ' and (.gate.notJudged.invalidRule // 0) == 0'
    + ' and ([.serverComponents[]? | .notJudged.invalidRule + .notJudged.sourceFailed'
    + ' + .notJudged.lowCoverage] | add // 0) == 0'
    + ' and ([.findings[]? | select(.severity == "error")] | length) == 0';
const strictIdiom = (d, m = {}) => {
    if (!defaultIdiom(d)) return false;
    if (alt(at(d, 'gate', 'notJudged', 'invalidRule'), 0) !== 0) return false;
    const collection = (g) => (m.noCollection ? 0 : at(g, 'notJudged', 'sourceFailed') + at(g, 'notJudged', 'lowCoverage'));
    // Every component builds (C6): no failsBuild test left to exempt a gate's collection failures.
    const counts = (g) => at(g, 'notJudged', 'invalidRule') + collection(g);
    if (alt(add(eachOptional(at(d, 'serverComponents')).map(counts)), 0) !== 0) return false;
    if (m.noFindings) return true;
    return eachOptional(at(d, 'findings')).filter((f) => at(f, 'severity') === 'error').length === 0;
};

const TRANSLATIONS = {
    DEFAULT: { jq: DEFAULT_JQ, fn: defaultIdiom },
    STRICT: { jq: STRICT_JQ, fn: strictIdiom },
};

/**
 * selftest_jq_idioms' mutants, in its order and with its texts: the line, the clause's text, what
 * it becomes, and the translated mutant. Kept textually identical to common.sh so that the two
 * guards are the same guard; the test below checks each text is still in its line.
 */
const MUTANTS = [
    { label: 'wrong-key', base: 'DEFAULT', from: '.serverStatus', to: '.serverVerdict', m: { wrongKey: true } },
    { label: 'no-server-half', base: 'DEFAULT', from: ' and (.serverStatus // "PASS") != "FAIL"', to: '',
        m: { noServerHalf: true } },
    { label: 'no-collection-clause', base: 'STRICT', from: '.notJudged.sourceFailed + .notJudged.lowCoverage', to: '0',
        m: { noCollection: true } },
    { label: 'no-findings-clause', base: 'STRICT',
        from: ' and ([.findings[]? | select(.severity == "error")] | length) == 0', to: '', m: { noFindings: true } },
];

/** The idiom D8 documented, which verify_jq_idioms' selftest feeds back in. */
const d8Idiom = (d) =>
    at(d, 'gate', 'failed') === 0
    && alt(add(each(at(d, 'serverGates')).filter((g) => at(g, 'failsBuild')).map((g) => at(g, 'failed'))), 0) === 0;

// ---------------------------------------------------------------------------
// The file, read the way verify_jq_idioms reads it
// ---------------------------------------------------------------------------

function readFile() {
    let cases = null;
    const idioms = [];
    fs.readFileSync(IDIOM_FILE, 'utf8').split('\n').forEach((raw, i) => {
        const line = raw.replace(/\r$/, '');
        if (!line.trim() || line.startsWith('#')) return;
        if (/^CASES\s/.test(line)) {
            cases = line.trim().split(/\s+/).slice(1);
            return;
        }
        assert.ok(cases, `jq-gate.txt:${i + 1}: an idiom before any CASES line`);
        let rest = line;
        const toks = [];
        for (let k = 0; k <= cases.length; k++) {
            rest = rest.replace(/^\s+/, '');
            const tok = /^\S*/.exec(rest)[0];
            toks.push(tok);
            rest = rest.slice(tok.length);
        }
        const program = rest.replace(/^\s+/, '').replace(/\s+$/, '');
        assert.ok(program, `jq-gate.txt:${i + 1} is not NAME, ${cases.length} answers and a PROGRAM: ${line}`);
        idioms.push({ name: toks[0], expect: toks.slice(1), jq: program, line: i + 1 });
    });
    assert.ok(cases && cases.length, 'jq-gate.txt has no CASES line');
    return { cases, idioms };
}

const FILE = readFile();
const goldens = {};
FILE.cases.forEach((id) => {
    goldens[id] = JSON.parse(fs.readFileSync(path.join(GOLDENS, 'expected', id + '.json'), 'utf8'));
});

/** Every answer a function gives over the file's cases, as the file writes them. */
const answers = (fn) => FILE.cases.map((id) => String(jqE(fn, goldens[id])));

// ---------------------------------------------------------------------------

test('the file holds idioms, every one with a translation and every translation with a line', () => {
    const names = FILE.idioms.map((i) => i.name);
    assert.ok(names.length > 0, 'no idiom in jq-gate.txt');
    assert.deepStrictEqual(names.slice().sort(), Object.keys(TRANSLATIONS).sort());
});

test('each translation is of the line that is in the file now', () => {
    FILE.idioms.forEach((idiom) => {
        assert.strictEqual(idiom.jq, TRANSLATIONS[idiom.name].jq,
            `jq-gate.txt:${idiom.line} (${idiom.name}) is no longer the text its JavaScript translation `
            + 'was written for - translate the new line here, then this passes');
    });
});

test('every idiom answers over every golden what the file writes beside it', () => {
    FILE.idioms.forEach((idiom) => {
        idiom.expect.forEach((want) => {
            assert.ok(want === 'true' || want === 'false', `${idiom.name}: expected answers are true or false, not ${want}`);
        });
        assert.deepStrictEqual(answers(TRANSLATIONS[idiom.name].fn), idiom.expect,
            `${idiom.name} over ${FILE.cases.join(' ')}`);
    });
});

test('every clause of every line changes the answer over some golden (the mutants are refused)', () => {
    const byName = Object.fromEntries(FILE.idioms.map((i) => [i.name, i]));
    MUTANTS.forEach((mu) => {
        const line = byName[mu.base];
        assert.ok(line, `no ${mu.base} line to mutate`);
        assert.ok(line.jq.includes(mu.from),
            `mutant ${mu.label}: '${mu.from}' is no longer in the ${mu.base} line - re-aim it here and in common.sh`);
        const mutant = (d) => TRANSLATIONS[mu.base].fn(d, mu.m);
        assert.notDeepStrictEqual(answers(mutant), line.expect,
            `the goldens cannot see the ${mu.base} clause '${mu.from}': mutant ${mu.label} answers as ${mu.base} does`);
    });
});

test('the mutants are selftest_jq_idioms\' mutants, text for text', () => {
    // One guard in two languages. If common.sh's list moves, this list has to move with it.
    const sh = fs.readFileSync(path.join(__dirname, '..', '..', 'scripts', 'lib', 'common.sh'), 'utf8');
    MUTANTS.forEach((mu) => {
        assert.ok(sh.includes(`labels+=(${mu.label})`), `common.sh has no mutant ${mu.label}`);
        assert.ok(sh.includes(`froms+=('${mu.from}')`), `common.sh's ${mu.label} breaks another text`);
        assert.ok(sh.includes(`tos+=('${mu.to}')`), `common.sh's ${mu.label} breaks it into something else`);
    });
    assert.strictEqual((sh.match(/labels\+=\(/g) || []).length, MUTANTS.length, 'the same number of mutants');
});

test('the goldens can tell the D8 idiom apart: it errors on G3 and is false on G1', () => {
    // The same refusal selftest_jq_idioms demands of the jq check. If the goldens ever stopped
    // discriminating, a broken idiom could be written into the file with answers to match.
    const def = FILE.idioms.find((i) => i.name === 'DEFAULT');
    const col = (id) => FILE.cases.indexOf(id);
    assert.strictEqual(jqE(d8Idiom, goldens.G1), false, 'G1 has no gate block: null == 0');
    assert.strictEqual(def.expect[col('G1')], 'true', 'a gateless run is not refused by DEFAULT');
    assert.strictEqual(jqE(d8Idiom, goldens.G3), 'error', 'G3 has no serverGates: Cannot iterate over null');
    assert.strictEqual(def.expect[col('G3')], 'true');
});

test('the goldens carry the shapes the idioms read', () => {
    // Guards the translations against a golden that silently lost the key an idiom reads.
    assert.ok(!('serverStatus' in goldens.G1) && !('serverComponents' in goldens.G1), 'G1 has no server block');
    assert.ok(!('gate' in goldens.G1), 'G1 has no client gate block');
    assert.strictEqual(goldens.G4.serverStatus, 'FAIL', 'G4 serverStatus (D24)');
    assert.ok(Array.isArray(goldens.G4.serverComponents) && goldens.G4.serverComponents.length > 0);
    assert.ok(!('serverGates' in goldens.G4), 'C7: the array is serverComponents on the wire');
    // G5-G7: no client rule, so the server half alone decides.
    ['G5', 'G7'].forEach((id) => {
        assert.ok(!('gate' in goldens[id]), `${id} declares no client rule`);
        assert.ok(Array.isArray(goldens[id].serverComponents), `${id} was evaluated`);
    });
    assert.strictEqual(goldens.G5.serverStatus, 'FAIL', 'G5: a building gate FAILs');
    assert.strictEqual(goldens.G7.serverStatus, 'PASS', 'G7: a PASS word over a LOW_COVERAGE');
    // G8: refused where a gate builds - no server block, one ERROR finding.
    assert.ok(!('serverStatus' in goldens.G8) && !('serverComponents' in goldens.G8), 'G8 was not evaluated');
    assert.deepStrictEqual((goldens.G8.findings || []).map((f) => f.severity + ' ' + f.ruleId),
        ['error serverMetrics.payload'], 'G8 carries the refused payload as an ERROR finding');
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
