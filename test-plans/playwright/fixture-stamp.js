/**
 * Whether a fixture can be tested against, for global-setup.ts: reading its .fixture-stamp, judging
 * it against the tree, and wording the refusal. Here rather than in global-setup.ts so that
 * test-plans/js-unit/fixture-stamp.test.js can run all of it, which is why this is plain CommonJS
 * rather than TypeScript: the node unit tier runs it with no transpiler and no browser.
 *
 * A stamp is written by write_fixture_stamp in scripts/lib/common.sh:
 *
 *   <relative path> <TAB> <md5>                        one line per file of the tree
 *   ...
 *   @deployed:<entry> <TAB> <md5 | none | unavailable> <TAB> <the JAR's name, or why no md5>
 *
 * The listing is the tree when the stamp was written: every file under STAMP_ROOTS. It is compared
 * WHOLE, so a file changed, deleted or added under those directories since stamping makes the
 * fixture stale. This module used to look each stamp line up in the tree instead, and a file added
 * since was never visited: Playwright called that fixture "ok" while the shell called it stale (F10).
 *
 * The records are what rendered the report: for each resource the listing names
 * (<module>/src/main/resources/<entry>, except META-INF/, which the shade plugin writes rather than
 * copies), the md5 of that entry inside the plugin JAR that was in lib/ext. A stamp is written only
 * beside a report the run itself wrote, into a directory emptied first (F3), so the JAR it names is
 * the one that rendered that report. They exist because N6's regression (2026-10-03) ran on two
 * plugin JARs, JMeter loaded the older one's report.js, and every fixture carried a sentence one
 * edit behind the tree under a stamp that said current. The first record held report.js alone, so a
 * JAR whose template or CSS was not the tree's still stamped current; every byte-copied resource is
 * compared now. A JAR's classes cannot be compared with sources, so a JAR differing only in its Java
 * is not caught here.
 *
 * common.sh's fixtures_current reads the same listing over the same directories, compared whole,
 * and the same records (_read_fixture_stamp, _check_stamp_jar), with the same rules and sentences;
 * fixture-stamp.test.js holds the directories, constants and sentences to common.sh's copy.
 */
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const JAR_PREFIX = '@deployed:';
const RESOURCES = '/src/main/resources/';
const SHADE_OWNED = 'META-INF/';

/**
 * The directories a stamp's listing covers, relative to the project root: what
 * _fixture_stamp_files in common.sh hands to `find ... -type f`. The listing is compared whole,
 * as fixtures_current compares it, so a file added under one of these after stamping is as stale
 * as one changed or deleted. fixture-stamp.test.js holds this to common.sh's find.
 */
const STAMP_ROOTS = [
  'jmeter-report-core/src/main',
  'jmeter-web-insight-report/src/main',
  'test-plans/server-metrics',
  'test-plans/server-catalogue',
  'test-plans/server-fixtures',
];

const SENTENCE = {
  before: 'stamped before stamps recorded which JAR rendered the report — regenerate',
  unknown: (info) => `stamped when ${info || 'no JAR could be read'}, so what rendered it is unknown — redeploy and regenerate`,
  differ: (entries, info) =>
    `rendered by a JAR whose ${entries.join(', ')} ${entries.length === 1 ? 'is' : 'are'} not the tree's (${info || 'unnamed'}) — redeploy and regenerate`,
};

/** The JAR entry a listing path is compared on, or null when it is not a compared resource. */
function entryOf(relPath) {
  const at = relPath.indexOf(RESOURCES);
  if (at <= 0 || relPath.slice(0, at).includes('/')) return null;
  const entry = relPath.slice(at + RESOURCES.length);
  return entry && !entry.startsWith(SHADE_OWNED) ? entry : null;
}

/**
 * Split a stamp's text into its tree entries and its JAR records (a Map from entry to
 * { md5, info }, empty when the stamp predates them). A line beginning "@" is a record, never a
 * file; one in an older shape (the first version's single "@deployed-report.js") is skipped.
 */
function parseStamp(text) {
  const entries = [];
  const records = new Map();
  for (const line of String(text).split('\n')) {
    if (!line.trim()) continue;
    const [relPath, recorded, info] = line.split('\t');
    if (relPath.startsWith('@')) {
      if (relPath.startsWith(JAR_PREFIX)) {
        records.set(relPath.slice(JAR_PREFIX.length), { md5: (recorded || '').trim(), info: (info || '').trim() });
      }
      continue;
    }
    if (!relPath || !recorded) continue;
    entries.push({ path: relPath, md5: recorded.trim() });
  }
  return { entries, records };
}

/**
 * Whether the records say the fixture was rendered by a JAR holding the tree's copy of every
 * resource the listing names, judged against the stamp's own listing (the caller has already
 * compared that with the tree). Resources are visited in listing order: the first with no record,
 * or with no md5, decides; every one whose md5 is not the tree's is named.
 * { ok: true } when it was; { ok: true, unchecked: true } when unzip was missing at stamp time, so
 * only this comparison is skipped; { ok: false, reason } otherwise.
 */
function judgeJar(entries, records) {
  let any = false;
  let unchecked = false;
  const differ = [];
  let differInfo = '';
  for (const e of entries) {
    const entry = entryOf(e.path);
    if (!entry) continue;
    any = true;
    const rec = records.get(entry);
    if (!rec) return { ok: false, reason: SENTENCE.before };
    if (rec.md5 === 'unavailable') { unchecked = true; continue; }
    if (!/^[0-9a-f]{32}$/.test(rec.md5)) return { ok: false, reason: SENTENCE.unknown(rec.info) };
    if (rec.md5 !== e.md5) {
      differ.push(entry);
      if (!differInfo) differInfo = rec.info;
    }
  }
  // A listing that names no resource has nothing the records could vouch for.
  if (!any) return { ok: false, reason: SENTENCE.before };
  if (differ.length > 0) return { ok: false, reason: SENTENCE.differ(differ, differInfo) };
  return unchecked ? { ok: true, unchecked: true } : { ok: true };
}

function md5(file) {
  return crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
}

/**
 * The tree now, as _current_source_hashes lists it: a Map from '/'-joined path to md5 for every
 * regular file under STAMP_ROOTS (`find -type f`: no symlink, no directory; a missing root adds
 * nothing). One hashing pass, shared by every fixture. A file that cannot be read is left out, as
 * files_md5 leaves it out, so it reads as changed.
 */
function currentTree(projectRoot) {
  const tree = new Map();
  const walk = (rel) => {
    let dirents;
    try { dirents = fs.readdirSync(path.join(projectRoot, rel), { withFileTypes: true }); } catch (e) { return; }
    for (const d of dirents) {
      const child = rel + '/' + d.name;
      if (d.isDirectory()) walk(child);
      else if (d.isFile()) {
        try { tree.set(child, md5(path.join(projectRoot, child))); } catch (e) { /* left out */ }
      }
    }
  };
  STAMP_ROOTS.forEach(walk);
  return tree;
}

/**
 * One fixture's verdict: { kind: 'missing' | 'unstamped' }, { kind: 'changed', files },
 * { kind: 'jar', reason }, or { kind: 'ok', jarUnchecked }. `tree` is currentTree(projectRoot),
 * passed in by assessFixtures so the tree is hashed once for all of them.
 */
function checkFixture(projectRoot, resultsDir, id, tree = currentTree(projectRoot)) {
  const report = path.join(resultsDir, id, `${id}-report.html`);
  if (!fs.existsSync(report)) return { kind: 'missing' };

  const stampFile = path.join(resultsDir, id, '.fixture-stamp');
  // Fixtures generated before stamping existed, or by hand. Treat as unusable rather
  // than assume they are fine — "we cannot tell" is exactly the state this guards.
  if (!fs.existsSync(stampFile)) return { kind: 'unstamped' };

  const stamp = parseStamp(fs.readFileSync(stampFile, 'utf8'));
  // The listing compared whole, as fixtures_current compares it: a file changed, deleted or
  // renamed since, and a file added since, which a per-line lookup of the stamp never sees.
  const stamped = new Set();
  const changed = [];
  for (const entry of stamp.entries) {
    stamped.add(entry.path);
    if (tree.get(entry.path) !== entry.md5) changed.push(entry.path);
  }
  for (const rel of [...tree.keys()].sort()) {
    if (!stamped.has(rel)) changed.push(rel);
  }
  if (changed.length > 0) return { kind: 'changed', files: changed };
  // The tree matches; now whether the JAR that rendered it held the tree's resources. Against the
  // stamp's own listing, which the loop above has just shown is the tree's.
  const jar = judgeJar(stamp.entries, stamp.records);
  if (!jar.ok) return { kind: 'jar', reason: jar.reason };
  return { kind: 'ok', jarUnchecked: !!jar.unchecked };
}

/**
 * Every fixture in `ids`, folded: `problems` is the lines that say which are unusable and why
 * (empty when all are usable), `unchecked` the ids whose JAR could not be checked.
 */
function assessFixtures(projectRoot, resultsDir, ids) {
  const missing = [];
  const unstamped = [];
  const changed = new Map();
  const wrongJar = new Map();
  const unchecked = [];
  const tree = currentTree(projectRoot);

  for (const id of ids) {
    const verdict = checkFixture(projectRoot, resultsDir, id, tree);
    if (verdict.kind === 'missing') missing.push(id);
    else if (verdict.kind === 'unstamped') unstamped.push(id);
    else if (verdict.kind === 'changed') changed.set(id, verdict.files);
    else if (verdict.kind === 'jar') wrongJar.set(id, verdict.reason);
    else if (verdict.jarUnchecked) unchecked.push(id);
  }

  const problems = [];
  if (missing.length > 0) {
    problems.push(`  Missing:            ${missing.join(', ')}`);
  }
  if (unstamped.length > 0) {
    problems.push(`  No provenance:      ${unstamped.join(', ')}`);
    problems.push('                      (generated before stamping existed — age unknowable)');
  }
  if (changed.size > 0) {
    problems.push('  Generated by older source:');
    for (const [id, files] of changed) {
      problems.push(`    ${id.padEnd(6)} ${files.map((f) => path.basename(f)).join(', ')}`);
    }
  }
  if (wrongJar.size > 0) {
    problems.push("  Not shown to be rendered by the tree's JAR:");
    for (const [id, reason] of wrongJar) {
      problems.push(`    ${id.padEnd(6)} ${reason}`);
    }
  }
  return { problems, unchecked };
}

module.exports = {
  JAR_PREFIX, RESOURCES, SHADE_OWNED, STAMP_ROOTS, SENTENCE,
  entryOf, parseStamp, judgeJar, currentTree, checkFixture, assessFixtures,
};
