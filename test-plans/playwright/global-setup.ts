import * as path from 'path';

/**
 * Refuse to run when the report fixtures were produced by different source than the
 * working tree currently holds.
 *
 * These tests do not generate anything. They open HTML reports that a JMeter run wrote
 * earlier into test-results/, and test-results/ is gitignored — so the fixtures are
 * whatever happens to be on this machine. Change the report template or report.js,
 * rebuild, forget to regenerate, and every spec happily asserts against the OLD report
 * and passes. A green run then means "the previous build was fine", which is
 * indistinguishable from "your change works" right up until it isn't.
 *
 * The check is on CONTENT, not timestamps. Comparing fixture mtimes against the built
 * JAR sounds simpler, but it calls everything stale after any rebuild — including one
 * that changed nothing — and after every branch switch. A check that cries wolf just
 * teaches people to set the bypass flag permanently, which is worse than no check.
 *
 * Each fixture carries a .fixture-stamp written by scripts/lib/common.sh listing the
 * md5 of every file that decides what a report looks like. Here fixture-stamp.js hashes the
 * same directories again and compares the listing whole, so a file changed, deleted or added
 * since is caught, and the failure names it.
 *
 * That listing is the TREE when the stamp was written, which is not what rendered the
 * report. So the stamp also records, for every resource the shade copies byte for byte
 * (the template, report.js, report.css, ...), its md5 inside the plugin JAR that was in
 * lib/ext, and a fixture whose JAR does not hold the tree's copy of each is refused too.
 *
 * All of that — reading the stamp, judging each fixture and wording the refusal — lives in
 * fixture-stamp.js, so the node unit tier runs it (js-unit/fixture-stamp.test.js) and holds
 * it to common.sh's copy; see there for why the records exist. This file keeps the list of
 * fixtures and what to tell the person who has to regenerate them.
 */

// Plain CommonJS, so the node unit tier can load it without a transpiler.
const { assessFixtures } = require('./fixture-stamp');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const RESULTS_DIR = path.join(PROJECT_ROOT, 'test-results');

/**
 * Report IDs the specs load. Keep in step with the tests/helpers.ts callers — an ID
 * missing from this list is simply unchecked, not an error.
 */
const REQUIRED_FIXTURES = [
  'E02', 'E05', 'E06', 'E07', 'E08', 'E09', 'E10', 'E11', 'E12', 'E19', 'E25', 'E26', 'E27', 'E28', 'E29', 'CMP',
];

const REGENERATE_HINT = [
  '  ./scripts/build-and-deploy.sh                  # first, when a fixture names its JAR',
  '  ./test-plans/scripts/run-all-tests.sh          # every fixture above except CMP',
  '  ./test-plans/scripts/generate-cmp-fixture.sh   # CMP (not covered by the above)',
].join('\n');

export default function globalSetup(): void {
  if (process.env.WIR_SKIP_FIXTURE_CHECK) {
    console.warn('[fixtures] WIR_SKIP_FIXTURE_CHECK set — provenance check skipped.');
    return;
  }

  const { problems, unchecked } = assessFixtures(PROJECT_ROOT, RESULTS_DIR, REQUIRED_FIXTURES) as
    { problems: string[]; unchecked: string[] };

  // Once, however many fixtures it covers: a skipped comparison, not a stale fixture.
  if (unchecked.length > 0) {
    console.warn(`[fixtures] ${unchecked.join(', ')} stamped where unzip was not on PATH, so which JAR rendered them is not checked.`);
  }

  if (problems.length === 0) return;

  const lines = ['Report fixtures are not usable, so these tests would be testing nothing.', '', ...problems];

  lines.push(
    '',
    'These specs open pre-generated reports; they do not build one. Running them now',
    'would assert against output from a previous version of the report and very likely',
    'pass, which tells you nothing about the code you just changed.',
    '',
    'Regenerate:',
    REGENERATE_HINT,
    '',
    'To run anyway (you know the fixtures are fine): WIR_SKIP_FIXTURE_CHECK=1',
  );

  throw new Error(lines.join('\n'));
}
