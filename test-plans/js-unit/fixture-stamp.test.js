/**
 * The fixture stamp, as global-setup.ts reads it (test-plans/playwright/fixture-stamp.js).
 *
 * A stamp lists the tree when it was written, and then records, for every resource the shade copies
 * byte for byte, its md5 inside the plugin JAR that was in lib/ext, so a fixture rendered by another
 * build is refused rather than tested (N6, 2026-10-03: two JARs in lib/ext, JMeter loaded the older
 * one's report.js, and every fixture stamped "current"). The first record held report.js alone, and a
 * JAR whose template or CSS was not the tree's still stamped current.
 *
 * Two readers judge a stamp: fixture-stamp.js for Playwright, and fixtures_current in
 * scripts/lib/common.sh (_read_fixture_stamp, _check_stamp_jar). Both compare the listing WHOLE, over
 * the same directories: a file changed, deleted or added under them since stamping is stale. The
 * module used to look each stamp line up instead, so a file added since read "ok" to Playwright while
 * the shell called it stale (F10). The third part of this file reads common.sh and fails when the
 * shell's copy walks other directories or says something the module does not;
 * selftest_fixture_stamps in common.sh pins the shell's behaviour. The second part runs the module's
 * whole verdict over a scratch tree laid out under the real stamped directories, because
 * global-setup.ts keeps nothing but the fixture list and the hint.
 *
 * No report.js here, and no DOM.
 */
'use strict';

const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const stamp = require('../playwright/fixture-stamp.js');
const COMMON_SH = fs.readFileSync(path.join(__dirname, '..', '..', 'scripts', 'lib', 'common.sh'), 'utf8');
const MODULE_SRC = fs.readFileSync(path.join(__dirname, '..', 'playwright', 'fixture-stamp.js'), 'utf8');
const GLOBAL_SETUP = fs.readFileSync(path.join(__dirname, '..', 'playwright', 'global-setup.ts'), 'utf8');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const JS = 'static/js/report.js';
const CSS = 'static/css/report.css';
const HTML = 'templates/report.html';
const TREE = {
  [JS]: 'e69c2486462bb8541435654281f068e6',
  [CSS]: '7a844803d6ed41a6e29c9517973378a1',
  [HTML]: 'cf2aac33df303aab7649acfaf436b425',
};
const OTHER = '0123456789abcdef0123456789abcdef';
const RES = 'jmeter-web-insight-report/src/main/resources/';
const listing = [
  'jmeter-report-core/src/main/java/A.java\taaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  RES + 'META-INF/services/org.apache.jmeter.gui.JMeterGUIComponent\tdddddddddddddddddddddddddddddddd',
  RES + CSS + '\t' + TREE[CSS],
  RES + JS + '\t' + TREE[JS],
  RES + HTML + '\t' + TREE[HTML],
  'test-plans/server-metrics/README.md\tbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
].join('\n');
const record = (entry, md5, info) => [stamp.JAR_PREFIX + entry, md5, info].join('\t');
/** Records for every resource in `listing`: the tree's md5 unless `over` names another. */
const records = (info, over = {}) => [CSS, JS, HTML].map((e) => record(e, over[e] || TREE[e], info)).join('\n');
const judge = (text) => {
  const s = stamp.parseStamp(text);
  return stamp.judgeJar(s.entries, s.records);
};
const J30 = 'jmeter-web-insight-report-3.1.30.jar';
const J29 = 'jmeter-web-insight-report-3.1.29.jar';

// ---------------------------------------------------------------------------
// what a stamp says
// ---------------------------------------------------------------------------

test('a resource is compared on its JAR entry; META-INF, sources and test-plans are not', () => {
  assert.strictEqual(stamp.entryOf(RES + JS), JS);
  assert.strictEqual(stamp.entryOf('jmeter-report-core/src/main/resources/com/x/catalogue/kafka.json'), 'com/x/catalogue/kafka.json');
  assert.strictEqual(stamp.entryOf(RES + 'META-INF/services/x'), null, 'the shade writes META-INF, it does not copy it');
  assert.strictEqual(stamp.entryOf('jmeter-report-core/src/main/java/A.java'), null);
  assert.strictEqual(stamp.entryOf('test-plans/server-metrics/README.md'), null);
  assert.strictEqual(stamp.entryOf('a/b/src/main/resources/x.js'), null, 'only a module at the top of the tree');
});

test('records are not tree entries, and the first record shape is neither', () => {
  const s = stamp.parseStamp(listing + '\n' + records(J30) + '\n@deployed-report.js\t' + TREE[JS] + '\t' + J30 + '\n');
  assert.strictEqual(s.entries.length, 6);
  assert.ok(s.entries.every((e) => !e.path.startsWith('@')));
  assert.deepStrictEqual([...s.records.keys()], [CSS, JS, HTML]);
  assert.deepStrictEqual(s.records.get(JS), { md5: TREE[JS], info: J30 });
});

test("a JAR holding the tree's copy of every resource: current", () => {
  assert.deepStrictEqual(judge(listing + '\n' + records(J30)), { ok: true });
});

test("N6: a JAR whose report.js is not the tree's is refused, naming the JAR and the cause", () => {
  assert.deepStrictEqual(judge(listing + '\n' + records(J29, { [JS]: OTHER })), {
    ok: false,
    reason: "rendered by a JAR whose static/js/report.js is not the tree's (jmeter-web-insight-report-3.1.29.jar) — redeploy and regenerate",
  });
});

test("a JAR whose report.css alone is not the tree's is refused (the report.js-only record called it current)", () => {
  assert.deepStrictEqual(judge(listing + '\n' + records(J29, { [CSS]: OTHER })), {
    ok: false,
    reason: "rendered by a JAR whose static/css/report.css is not the tree's (jmeter-web-insight-report-3.1.29.jar) — redeploy and regenerate",
  });
});

test('every resource that differs is named, in listing order', () => {
  assert.deepStrictEqual(judge(listing + '\n' + records(J29, { [HTML]: OTHER, [CSS]: OTHER })), {
    ok: false,
    reason: "rendered by a JAR whose static/css/report.css, templates/report.html are not the tree's (jmeter-web-insight-report-3.1.29.jar) — redeploy and regenerate",
  });
});

test('no records, the first record shape, or one resource unrecorded: stamped before the record', () => {
  const before = { ok: false, reason: 'stamped before stamps recorded which JAR rendered the report — regenerate' };
  assert.deepStrictEqual(judge(listing + '\n'), before);
  assert.deepStrictEqual(judge(listing + '\n@deployed-report.js\t' + TREE[JS] + '\t' + J30), before);
  assert.deepStrictEqual(judge(listing + '\n' + record(CSS, TREE[CSS], J30) + '\n' + record(JS, TREE[JS], J30)), before);
});

test('"none" (two JARs, no JAR, an entry missing) is a fixture whose renderer is unknown', () => {
  assert.deepStrictEqual(judge(listing + '\n' + records('lib/ext held 2 plugin JARs (a.jar, b.jar)', { [CSS]: 'none', [JS]: 'none', [HTML]: 'none' })), {
    ok: false,
    reason: 'stamped when lib/ext held 2 plugin JARs (a.jar, b.jar), so what rendered it is unknown — redeploy and regenerate',
  });
  assert.strictEqual(judge(listing + '\n' + records(J30, { [HTML]: 'none' })).ok, false, 'one entry missing from the JAR');
  assert.deepStrictEqual(judge(listing + '\n' + [CSS, JS, HTML].map((e) => stamp.JAR_PREFIX + e + '\tnone').join('\n')), {
    ok: false, reason: 'stamped when no JAR could be read, so what rendered it is unknown — redeploy and regenerate',
  }, 'no info field');
});

test('"unavailable" (no unzip at stamp time) skips only this comparison', () => {
  const all = { [CSS]: 'unavailable', [JS]: 'unavailable', [HTML]: 'unavailable' };
  assert.deepStrictEqual(judge(listing + '\n' + records('unzip was not on PATH', all)), { ok: true, unchecked: true });
});

test('a listing that names no resource cannot vouch for its JAR', () => {
  assert.strictEqual(judge('a/B.java\tcccccccccccccccccccccccccccccccc\n' + record(JS, TREE[JS], 'x.jar')).ok, false);
});

// ---------------------------------------------------------------------------
// the whole verdict, over a scratch tree: what global-setup throws on
// ---------------------------------------------------------------------------

const md5 = (text) => crypto.createHash('md5').update(text).digest('hex');

const A_JAVA = 'jmeter-report-core/src/main/java/A.java';
const REPORT_JS = RES + JS;

/**
 * A scratch project with one source and two resources, and a results directory. They sit under the
 * directories a stamp covers, because the module walks those to compare the listing whole: a file
 * anywhere else is in no listing, and a stamp naming one would read as a file deleted since.
 * `tree` is the listing as the shell writes it, sorted.
 */
function scratch() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wir-stamp-'));
  const write = (rel, text) => {
    fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
    fs.writeFileSync(path.join(root, rel), text);
  };
  const remove = (rel) => fs.rmSync(path.join(root, rel));
  const files = {
    [A_JAVA]: 'class A {}\n',
    [REPORT_JS]: 'window.WIR = {};\n',
    [RES + 'META-INF/services/x']: 'A\n',
  };
  for (const [rel, text] of Object.entries(files)) write(rel, text);
  const tree = Object.keys(files).sort().map((rel) => rel + '\t' + md5(files[rel])).join('\n');
  const results = path.join(root, 'results');
  const fixture = (id, stampText, { report = true } = {}) => {
    fs.mkdirSync(path.join(results, id), { recursive: true });
    if (report) fs.writeFileSync(path.join(results, id, id + '-report.html'), '<html></html>');
    if (stampText !== null) fs.writeFileSync(path.join(results, id, '.fixture-stamp'), stampText);
  };
  const js = md5(files[REPORT_JS]);
  return { root, results, tree, js, fixture, write, remove };
}

/** `tree` with A.java's md5 replaced: a source changed since stamping. */
const withChangedA = (tree) => tree.replace(/^(jmeter-report-core\/src\/main\/java\/A\.java\t)[0-9a-f]+/m, '$1' + OTHER);

test('checkFixture: each verdict, and a stamp naming another JAR is a "jar" verdict', () => {
  const s = scratch();
  try {
    s.fixture('E26', s.tree + '\n' + record(JS, s.js, J30) + '\n');
    s.fixture('E25', s.tree + '\n' + record(JS, OTHER, J29) + '\n');
    s.fixture('E12', s.tree + '\n');
    s.fixture('E11', s.tree, { report: false });
    s.fixture('E10', null);
    s.fixture('E09', withChangedA(s.tree) + '\n' + record(JS, s.js, J30));
    s.fixture('E08', s.tree + '\n' + record(JS, 'unavailable', 'unzip was not on PATH'));
    const v = (id) => stamp.checkFixture(s.root, s.results, id);
    assert.deepStrictEqual(v('E26'), { kind: 'ok', jarUnchecked: false });
    assert.deepStrictEqual(v('E25'), {
      kind: 'jar', reason: "rendered by a JAR whose static/js/report.js is not the tree's (jmeter-web-insight-report-3.1.29.jar) — redeploy and regenerate",
    });
    assert.deepStrictEqual(v('E12'), { kind: 'jar', reason: 'stamped before stamps recorded which JAR rendered the report — regenerate' });
    assert.deepStrictEqual(v('E11'), { kind: 'missing' });
    assert.deepStrictEqual(v('E10'), { kind: 'unstamped' });
    assert.deepStrictEqual(v('E09'), { kind: 'changed', files: [A_JAVA] });
    assert.deepStrictEqual(v('E08'), { kind: 'ok', jarUnchecked: true });
  } finally {
    fs.rmSync(s.root, { recursive: true, force: true });
  }
});

test('assessFixtures: usable fixtures give no problem lines; every other kind is named, the JAR ones with their reason', () => {
  const s = scratch();
  try {
    s.fixture('E26', s.tree + '\n' + record(JS, s.js, J30) + '\n');
    s.fixture('E08', s.tree + '\n' + record(JS, 'unavailable', 'unzip was not on PATH'));
    assert.deepStrictEqual(stamp.assessFixtures(s.root, s.results, ['E26', 'E08']), { problems: [], unchecked: ['E08'] });

    s.fixture('E25', s.tree + '\n' + record(JS, OTHER, J29) + '\n');
    s.fixture('E12', s.tree + '\n');
    s.fixture('E11', s.tree, { report: false });
    s.fixture('E10', null);
    s.fixture('E09', withChangedA(s.tree) + '\n' + record(JS, s.js, J30));
    const got = stamp.assessFixtures(s.root, s.results, ['E26', 'E25', 'E12', 'E11', 'E10', 'E09', 'E08']);
    assert.deepStrictEqual(got.unchecked, ['E08']);
    assert.deepStrictEqual(got.problems, [
      '  Missing:            E11',
      '  No provenance:      E10',
      '                      (generated before stamping existed — age unknowable)',
      '  Generated by older source:',
      '    E09    A.java',
      "  Not shown to be rendered by the tree's JAR:",
      "    E25    rendered by a JAR whose static/js/report.js is not the tree's (jmeter-web-insight-report-3.1.29.jar) — redeploy and regenerate",
      '    E12    stamped before stamps recorded which JAR rendered the report — regenerate',
    ]);
  } finally {
    fs.rmSync(s.root, { recursive: true, force: true });
  }
});

test('the listing is compared whole: a file added or deleted under the stamped directories since stamping is stale, and named', () => {
  const s = scratch();
  try {
    s.fixture('E26', s.tree + '\n' + record(JS, s.js, J30) + '\n');
    const v = () => stamp.checkFixture(s.root, s.results, 'E26');

    // Outside the stamped directories: in no listing, so no change.
    s.write('jmeter-report-core/src/test/java/ATest.java', 'class ATest {}\n');
    s.write('test-plans/annotations/extra.json', '{}\n');
    assert.deepStrictEqual(v(), { kind: 'ok', jarUnchecked: false });

    // Added under them since stamping: a source, a resource (which the JAR comparison, iterating
    // the stamp's own listing, never visits either) and an E27 input. A per-line lookup of the
    // stamp visits none of the three and called this fixture "ok"; the shell called it stale.
    const added = [
      'jmeter-report-core/src/main/java/B.java',
      RES + 'templates/fragment.html',
      'test-plans/server-catalogue/e27-extra.csv',
    ];
    s.write(added[0], 'class B {}\n');
    s.write(added[1], '<p></p>\n');
    s.write(added[2], 'time,component,metric,instance,value\n');
    const got = v();
    assert.deepStrictEqual(got, { kind: 'changed', files: added },
      'three files added since stamping must make the fixture stale, named in listing order; got ' + JSON.stringify(got));
    assert.deepStrictEqual(stamp.assessFixtures(s.root, s.results, ['E26']), {
      problems: ['  Generated by older source:', '    E26    B.java, fragment.html, e27-extra.csv'],
      unchecked: [],
    });

    // Gone again, the stamp's own listing is the tree once more.
    added.forEach(s.remove);
    assert.deepStrictEqual(v(), { kind: 'ok', jarUnchecked: false });

    // Deleted since stamping.
    s.remove(A_JAVA);
    assert.deepStrictEqual(v(), { kind: 'changed', files: [A_JAVA] }, 'a file deleted since stamping must make the fixture stale, named');
  } finally {
    fs.rmSync(s.root, { recursive: true, force: true });
  }
});

test('global-setup throws on whatever assessFixtures reports, for the REQUIRED_FIXTURES list', () => {
  // global-setup.ts is TypeScript and runs only under Playwright, so this reads it: everything it
  // decides is in fixture-stamp.js, and what is left is this call and this refusal.
  assert.ok(/require\('\.\/fixture-stamp'\)/.test(GLOBAL_SETUP), 'global-setup.ts no longer loads fixture-stamp.js');
  assert.ok(/assessFixtures\(PROJECT_ROOT, RESULTS_DIR, REQUIRED_FIXTURES\)/.test(GLOBAL_SETUP),
    'global-setup.ts no longer assesses REQUIRED_FIXTURES');
  assert.ok(/if \(problems\.length === 0\) return;/.test(GLOBAL_SETUP), 'global-setup.ts no longer returns only when nothing is wrong');
  assert.ok(/\.\.\.problems/.test(GLOBAL_SETUP) && /throw new Error\(lines\.join\('\\n'\)\)/.test(GLOBAL_SETUP),
    'global-setup.ts no longer throws the problems');
});

// ---------------------------------------------------------------------------
// the shell reads the same records in the same words
// ---------------------------------------------------------------------------

/** The value a common.sh top-level assignment `NAME='value'` gives. */
const shellConst = (name) => {
  const m = COMMON_SH.match(new RegExp('^' + name + "='([^']*)'$", 'm'));
  assert.ok(m, 'common.sh no longer assigns ' + name + " as NAME='value'");
  return m[1];
};

test("the module walks exactly the directories common.sh's _fixture_stamp_files hands to find", () => {
  // The words between `find \` and `-type f`, one directory per line. A reshaped command must fail
  // here with the parser named, never pass on an empty or partial list.
  const fn = COMMON_SH.match(/^_fixture_stamp_files\(\) \{\n([\s\S]*?)\n\}$/m);
  assert.ok(fn, 'common.sh no longer defines _fixture_stamp_files: the parser needs updating');
  const find = fn[1].match(/\bfind \\\n([\s\S]*?)\n[ \t]*-type f\b/);
  assert.ok(find, "could not parse the find in common.sh's _fixture_stamp_files: the parser needs updating");
  const roots = find[1].split(/\s+/).filter((w) => w && w !== '\\');
  assert.ok(roots.length > 0 && roots.every((w) => /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/.test(w)),
    "_fixture_stamp_files' find is no longer a plain list of directories (" + roots.join(' ') + '): the parser needs updating');
  assert.deepStrictEqual(stamp.STAMP_ROOTS, roots,
    'fixture-stamp.js STAMP_ROOTS and common.sh _fixture_stamp_files walk different directories');
});

test('common.sh writes and reads the same record prefix, resource root and shade-owned directory', () => {
  assert.strictEqual(shellConst('_STAMP_JAR_PREFIX'), stamp.JAR_PREFIX);
  assert.strictEqual(shellConst('_STAMP_RESOURCES'), stamp.RESOURCES);
  assert.strictEqual(shellConst('_STAMP_SHADE_OWNED'), stamp.SHADE_OWNED);
});

test("common.sh's _check_stamp_jar gives the module's sentences", () => {
  const fn = COMMON_SH.match(/^_check_stamp_jar\(\) \{\n([\s\S]*?)\n\}$/m);
  assert.ok(fn, 'common.sh no longer defines _check_stamp_jar');
  const body = fn[1];
  // The fixed text of each sentence; the interpolations differ in syntax between the two.
  const fixed = [
    stamp.SENTENCE.before,
    'stamped when ', ', so what rendered it is unknown — redeploy and regenerate', 'no JAR could be read',
    'rendered by a JAR whose ', " not the tree's (", ') — redeploy and regenerate', 'unnamed',
  ];
  for (const text of fixed) {
    assert.ok(MODULE_SRC.includes(text), 'fixture-stamp.js no longer says: ' + text);
    assert.ok(body.includes(text), 'common.sh _check_stamp_jar does not say: ' + text);
  }
  assert.ok(/verb=are/.test(body) && /verb=is/.test(body), 'common.sh no longer says "is" for one resource and "are" for several');
  assert.ok(/\^\[0-9a-f\]\{32\}\$/.test(body), 'common.sh no longer tests for a 32-hex md5');
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
