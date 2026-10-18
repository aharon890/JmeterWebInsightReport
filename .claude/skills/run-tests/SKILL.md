---
name: run-tests
description: Run the automation test suites after developing a feature, fixing a bug, or before pushing. Picks sanity vs regression, regenerates report fixtures only when they are stale, and reports real pass/fail. Use when asked to test, verify, or validate a change, or when finishing a feature or bugfix.
---

# Running the automation tests

Two automated tiers. Both are fast because the expensive stage — regenerating
report fixtures from real JMeter runs — is skipped when the fixtures still match
the source in the tree.

| Suite | Command | Measured |
|---|---|---|
| Sanity | `./test-plans/scripts/run-sanity.sh` | **1m54s** |
| Regression | `./test-plans/scripts/run-regression.sh` | **~2 min** fixtures current, **~4½ min** when stale |

(Measured on the development machine, 12 Playwright workers. CI is slower.)

Regeneration used to cost ~11 minutes; scenarios now run 4-up (`WIR_JOBS`, default 4)
so the fixture stage is bounded by E02 alone at ~2½ min. `WIR_JOBS=1` forces serial
if a result looks suspicious and you want concurrency off the table.

Both exit with the number of failed stages. Both print a summary table.

## Which to run

**Sanity** while iterating. Build + shaded-JAR guard, the Java tests **and the `report.js`
node unit layer** (S02 is literally "Unit tests (Java + report.js)" — `run_js_unit_tests` in
`common.sh` runs `test-plans/js-unit/` in about a tenth of a second, no browser, no fixture),
shell syntax, the fixture-list agreement check, a live JMeter run, and the browser suite
against existing fixtures.

**No test counts are written down here, deliberately.** Every one that ever was went stale
within weeks — this paragraph claimed "224 Java tests" and "275 browser tests" while the tree
had 594 and 568. The runners print the real numbers: `mvn test` prints the Java total per
module, and the S05/R05 stage labels come from `playwright_test_count` in `common.sh`
(`node node_modules/@playwright/test/cli.js test --list` if you want it on its own).

**Regression before every push, no exceptions.** Also whenever the change could
alter what a report looks like *or says*. It does everything sanity does — including
the live JMeter smoke, which it skipped until recently, making the "full" tier
quietly weaker than the fast one — and additionally regenerates the fixtures when
they are stale.

Sanity does not regenerate fixtures. If yours are stale it fails stage S05 and
says so rather than testing an old build — that is the check working, not a bug.

**Run regression when the change touched anything under either module's
`src/main`.** You do not have to reason about which packages matter: the fixture
stamp hashes all of it, so `run-regression.sh` regenerates exactly when a report
could differ and skips when it cannot. (This list used to be five rendering files,
which meant a `core/stats` change silently reused fixtures built by the old engine.)

For a change confined to `WIKI/**`, `*.md` or scripts, sanity is enough while
iterating — the pre-push run is still regression, which costs the same as sanity
when the fixtures are current.

**CI covers the fast tier and only that, and the pre-push hook runs no tests** — the
hook's only job is refusing a direct push to the primary branch. `ci.yml` runs `mvn
verify`, the `report.js` unit tests and five static checks in about 55 seconds, and
never starts JMeter, a fixture or a browser. The wall-clock-bound tier was left out
deliberately: a full run cost 15m48s on a shared runner against ~1½ minutes locally.
Running the full regression before a push is therefore *this skill's* job and nothing
else's: a green regression here is the last word, "I'll let CI catch it" buys the unit
tests and the greps and nothing that needs a fixture or a browser, and a
push made without it reaches the remote with nothing having checked it.

## Not in the automated tiers

Deliberately excluded — they add minutes of wall-clock and are validated only by
"an HTML and a JSON file appeared", which the fixture scenarios already establish.
Run them manually before a release, or when you touched the area they cover:

```bash
./test-plans/scripts/run-all-tests.sh      # all 19 scenarios incl. E20 external-data (~4½ min)
./test-plans/scripts/run-config-tests.sh   # config/property handling; ./scripts/list-scenarios.sh --count
./test-plans/scripts/run-error-tests.sh    # N01-N09 error handling
./test-plans/scripts/run-docker-tests.sh   # E16, E17 containerized
```

`run-all-tests.sh` takes `--suite fixtures|smoke|full` (default `full`), and `--only E26,E27` to
narrow it to the fixtures one spec opens while fixing. The rest stay stale, so the spec runs with
`WIR_SKIP_FIXTURE_CHECK=1`, and the gate (`run-regression.sh`) regenerates the whole set.

## Running one test while fixing a failure

```bash
mvn test -pl jmeter-report-core -Dtest=JtlParserTest
mvn test -pl jmeter-report-core -Dtest=JtlParserTest#parseValidJtl_returnsCorrectSampleCount

cd test-plans/playwright
node node_modules/@playwright/test/cli.js test tests/samplers-tab.spec.ts
node node_modules/@playwright/test/cli.js test -g "a dragged width survives"
```

Use `node node_modules/@playwright/test/cli.js`, not `npx playwright` — under Git
Bash npx does not resolve the local install and `.bin/playwright` is a POSIX shim
that node tries to require as a module. Both fail before running anything. The
runners go through `run_playwright` in `common.sh`, which handles this.

## Where results land

- `test-results/playwright-report/` — HTML report, screenshots, traces
- `test-results/test-execution-report.csv` — per-scenario JMeter results
- `test-results/<ID>/jmeter.log` — why a scenario produced no report

## Traps that produce a false green

**Rebuilding is not regenerating.** `mvn package` updates the JAR; only a JMeter
run updates the fixtures under `test-results/`.

**Never set `WIR_SKIP_FIXTURE_CHECK=1` to get past a stale-fixture failure.** It
exists for when you already know the fixtures are current. Using it to silence a
real mismatch makes all 275 browser tests assert against the previous build and
pass, which tells you nothing.

**Nothing at push time runs the tests.** The pre-push hook only refuses direct
pushes to the primary branch. Pushing without a green regression from this skill
puts unchecked code on the remote, and there is no later gate that catches it —
so never treat "the push went through" as evidence anything passed.

**Long runs need background execution.** A regeneration cycle is ~4½ minutes. Do not
poll with `pgrep` — Git Bash does not have it, so an `until pgrep` loop exits
immediately and reports a still-running job as finished.

## Reporting back

Name which suite ran and what it excluded. Quote failing output rather than
summarizing it. A change to `report.js` verified only with `mvn test` has not been
verified — say so instead of implying coverage the run did not provide.
