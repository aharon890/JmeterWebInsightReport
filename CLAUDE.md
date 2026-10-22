# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Versions 2.x and 3.x were developed in a private repository, imported here as a snapshot for 4.0.0
without its history. A commit hash this file cites from before that import (`5874e10`, `fa687dc`
and the like) names a commit in that private history, not in this one.

## Writing code is a separate decision from being allowed to

**Do not start implementing until you have been told to implement.** Plan, design and
research are their own deliverables and they end by *reporting back*, not by opening an
editor. A finished plan is a finished turn.

This is not about permissions, and that is the whole point. Auto-accept mode is on in this
project so that MCP servers and the rest of the tooling are not throttled by a prompt on
every call — it is a statement about *tool latency*, not a standing approval to write code.
Treating a permission mode as consent is precisely the failure this rule exists to stop:
the research finishes, the plan is agreed with nobody, and an implementation nobody asked
for is already on disk under an auto-accepted edit.

So:

- Asked to investigate, review, compare, estimate, or "look into" something → answer, and
  stop. Propose the change; do not make it.
- Asked to plan or design → produce the plan (see the plan-file convention below) and stop,
  even when the plan is obvious and the edit would take one line.
- Asked to fix, implement, apply, or "go ahead" → implement.
- A verified bug found mid-investigation is still an investigation result. Report it.

When it is genuinely ambiguous, ask. One question costs a round trip; an unrequested
refactor costs a review.

## Project Overview

A JMeter plugin that turns test results into a single self-contained interactive HTML
report. Two Maven modules:

- `jmeter-report-core` — models, JTL parser, statistics engine, assertion engine, baseline comparison, and the server-assert model, evaluator and collection (`core/servermetrics/`)
- `jmeter-web-insight-report` — the JMeter plugin: listener, `-g -o` exporter, Thymeleaf template, report JS/CSS, and the `wir-collect` collector (`WirCollect`)

`jmeter-web-insight-report-*.jar` is a shaded fat JAR containing core, Thymeleaf and
T-Digest. It is the **only** artifact that gets deployed — copying
`jmeter-report-core-*.jar` alongside it puts duplicate classes on JMeter's flat
`lib/ext` classpath and which copy wins becomes non-deterministic.

## Build & Deploy

```bash
./scripts/build-and-deploy.sh
```

Builds both modules, copies the shaded JAR to `$JMETER_HOME/lib/ext/`, purges older
plugin JARs from that directory, and registers the `-g -o` exporter in
`reportgenerator.properties`. A stale JAR that cannot be removed **fails the deploy**, naming the
file: the directory is listed again after the purge rather than trusting `rm`. On Windows the VS
Code Java language server is usually holding it, and PowerShell's
`Move-Item -LiteralPath <jar> -Destination <elsewhere> -Force` works where `rm` fails. It used to
count the attempt as a removal and return 0, and a whole regression ran green on two JARs.

Environment is auto-detected — nothing is hardcoded. `JMETER_HOME` resolves as: env var
→ project-local `apache-jmeter-*/` → `jmeter` on `PATH` (symlinks followed, so Homebrew's
wrapper resolves to its `libexec`). Version is parsed from the parent `pom.xml`. Override
via env var:

```bash
JMETER_HOME=/opt/apache-jmeter-5.6.3 ./scripts/build-and-deploy.sh
```

Bump `<revision>` and redeploy for every change you want to see in a real JMeter run —
`lib/ext` should hold exactly one plugin JAR.

## Testing

Two automated suites. Both exit with their failure count.

```bash
./test-plans/scripts/run-sanity.sh       # ~2 min  — run constantly while working
./test-plans/scripts/run-regression.sh   # ~2 min, ~5 when fixtures are stale
```

Regression regenerates the report fixtures only when their provenance stamps no
longer match the tree, so a change that touched no plugin source costs the same as
sanity. Force it with `--force-fixtures`.

The stamp hashes **every file under both modules' `src/main`**, plus `test-plans/server-metrics`,
`test-plans/server-catalogue` and `test-plans/server-fixtures` (E26's, E27's, E28's and E29's inputs), not a hand-picked
list. It used to name five rendering files, which meant a change to `core/stats`
left every stamp matching — regeneration was skipped and every browser test
asserted against numbers from the previous build, green. Widening it also made the
check ~29x faster, because the hashes are now computed in one pass and compared as
whole listings instead of one `md5` process per file per fixture.

Regression always runs the live smoke suite, E24 and E30, whether or not it regenerates — otherwise
a run that skips regeneration never launches JMeter at all, and could not notice that the plugin no
longer loads in `lib/ext`. E30 is the live server-data flow: a `jmeter -n` run with the listener (its
interim report must print NEXT and read NOT_COLLECTED), `scripts/lib/example-server-metrics.js` writing
demo data for that run's own window, and `jmeter -g` reading the CSV; every component must be judged.
C43 (config tier) runs the examples bundle's hooks, `examples/server-data/hooks/after-run.sh` and
`.ps1`, against the stand-in export tool; cross-os.yml runs them on all three OSes.

The suites cover the JMeter scenarios Playwright actually opens (E02, E05-E12, E19, E25-E29)
plus CMP — the same list as `FIXTURE_IDS` in `common.sh`, which is where to check rather than
here. E19 is easy to forget: `external-data.spec.ts` opens it to assert the payload was written
to a sibling `.js` and referenced by `<script src>`, which is the one product promise no
inline-payload fixture can exercise.

**E26 is the one built entirely from committed inputs** — `-g -o` over the goldens' `plain.jtl`
with its own annotations (`e26-server.json`), server data (`e26-metrics.csv`) and the payload
`wir-collect --csv` collected from it, under `test-plans/server-metrics/`.
That is forced rather than convenient: a live run's `runId` is a fingerprint of the run, so
`ServerMetricsPayloadLoader` refuses a committed payload against it, and the committed CSV's
fixed timestamps share no milliseconds with a live window, so collecting at run time would
put coverage at zero. It renders every state the Server tab has — two `custom` components,
`kafka` and `db`, and a server assert for `PASS`, `WARN`, `FAIL` and six of the seven `NOT_JUDGED`
reasons; its `SOURCE_FAILED` comes from a CSV row with no time zone, so that one series fails and
the source reads `PARTIAL`. Beside its two client load rules it carries four near-band client
rules, means the client's rounding put on or across their bands — three within a hundredth, the
whole-run mean 0.09 under its whole-ms band (a FAIL on `gt 373.86`, a PASS under `gt 235` and
under `gte 211.41`, a WARN over `warn gt 119.46`), which `client-numbers.spec.ts` holds every
Asserts-tab surface and the Budget bar to; they name no server assert, so `runId` and every
`collectionId` are what they were without them.
Its directory is in the fixture stamp, so editing the CSV regenerates every fixture. Never
pass `-Jwebinsight.report.title` to it: the title is the test name on that path and is hashed
into `runId`, so setting it gets the committed payload refused as `FOREIGN_RUN` — the Server tab
then says it was refused and why, nothing on it is judged, and the run is a CONFIG ERROR.
E10, E11, E12 and E25 all carry an `assertions` array (`asserts-pass.json`,
`asserts-strict.json`, `full-annotations.json`, `asserts.json`). E25 is the one that renders
every rule state in a single run — PASS, WARN, FAIL, and NOT_JUDGED for each of DISABLED,
NO_MATCH and INVALID_RULE — and its thresholds are deliberately extreme so those states land
identically on any machine; the scenarios run 4-up, and a
realistic limit would make a browser assertion depend on how loaded the runner was. The
other 8 in `run-all-tests.sh` (E01, E03, E04, E13, E15, E20, E22, E24) assert only that a
report file appeared — E20 additionally that the external-data payload was written and
referenced — so they are manual-tier.
Run them before a release:

```bash
./test-plans/scripts/run-all-tests.sh      # E01-E13, E15, E19, E20, E22, E24-E29
./test-plans/scripts/run-config-tests.sh   # C01-C43 plus C17b, C19 records once
./test-plans/scripts/run-error-tests.sh    # N01-N09
./test-plans/scripts/run-docker-tests.sh   # E16, E17
```

Those four are **not** the release tier. `test-plans/scripts/` ships **14** `run-*.sh`, and
**11** of them are the release tier (the other three are sanity, regression and goldens) — the
other seven are the six `run-family-*` scripts and `run-title-junit-tests.sh`, and they carry
their own A/B, C/D, E, F/H, G, I/J and T ranges.

Do not maintain that list here. Every range above has already drifted once (`18`, `C01-C15`,
`N01-N07`), and a hand-kept copy of something executable is wrong within a month while
reading as authoritative the whole time. **`./scripts/list-scenarios.sh` generates the answer**
from the runners' own `record` calls and cannot drift — `--text`, `--csv`, `--count`. Each
runner also prints its own total. Trust those over this page, and fix this page when they
disagree.

`run-all-tests.sh` runs scenarios **concurrently** — `WIR_JOBS`, default 4,
`WIR_JOBS=1` for strict serial. The fixture set drops from ~8.6 min to ~2.5, the
critical path being E02 on its own. This is safe because no test plan contains an
HTTP sampler: they are timer-driven simulations with no shared target, each owning
its own `test-results/<ID>/`, and the assert scenarios sit ~100x from their thresholds
(E10 passes at mean ≤ 3000ms, E11 fails at ≤ 30ms), so contention cannot flip a
verdict. Scenario durations are never shortened per suite: several specs assert on
chart granularity and bucket counts derived from test duration, so a shortened E02
would change what the
fixtures mean.

### Individual layers

Test counts are deliberately not written down here. Every hardcoded one in this file
has been wrong within a month of being typed, and a stale count reads like a verified
figure. The runners print the real number — `playwright_test_count` in `common.sh`
asks Playwright — and `mvn test` prints its own.

```bash
# Java — both modules (~5s)
mvn test

# One module (report module needs core installed first)
mvn test -pl jmeter-report-core
mvn install -N -q && mvn install -pl jmeter-report-core -DskipTests -q
mvn test -pl jmeter-web-insight-report

# One class / one method
mvn test -pl jmeter-report-core -Dtest=JtlParserTest
mvn test -pl jmeter-report-core -Dtest=JtlParserTest#parseValidJtl_returnsCorrectSampleCount

# report.js unit tests — pure functions in node, no browser, no fixture, no build (~0.1s)
node test-plans/js-unit/asserts-pure.test.js
node test-plans/js-unit/asserts-parity.test.js
# the way S02/R02 and CI run them (loops every *.test.js, fails loudly if node is missing):
bash -c '. scripts/lib/common.sh && run_js_unit_tests'

# Playwright — the browser suite (~90s)
cd test-plans/playwright
node node_modules/@playwright/test/cli.js test
node node_modules/@playwright/test/cli.js test tests/samplers-tab.spec.ts
node node_modules/@playwright/test/cli.js test -g "a dragged width survives"

# CMP comparison fixture — NOT produced by run-all-tests.sh, needed by compare-tab.spec.ts
./test-plans/scripts/generate-cmp-fixture.sh
```

`npx playwright test` does **not** work under Git Bash — npx fails to resolve the
local install and `.bin/playwright` is a POSIX shim node tries to require as a
module. Go through `node .../cli.js`, or use `run_playwright` from `common.sh`.

Docs links: `test-plans/js-unit/docs-links.test.js` resolves every docs link the way Docsify does
(CI runs it with the other js-unit tests); `node scripts/check-docs-site.js` crawls the site in a
browser, served locally or, with `--live`, on GitHub Pages.

See [docs/Instructions.md](docs/Instructions.md) for the full matrix.

### The gate: the run-tests skill, plus a one-rule hook

```bash
./scripts/install-hooks.sh    # points core.hooksPath at .githooks/
```

**CI covers the fast tier, and only that.** `.github/workflows/ci.yml` runs `mvn verify`, the
`report.js` unit tests and the static checks on every push and pull request, in about a
**minute** — the step list in `ci.yml` is the list of checks; do not copy a count of them here.

The reasoning that once deleted every workflow is unchanged and still right: even after the
fixture stage was parallelised, a full regression took **15m48s** on a GitHub runner versus
~1½ minutes here, because the scenarios are wall-clock bound and a shared 2-core runner
cannot overlap them the way a developer machine can. But every word of that is about the
fixture/browser tier. It says nothing about unit tests and greps, which run at the same speed
on any machine — deleting those was collateral, and restoring them closes the
"any manual `git push` reaches the remote untested" hole for everything that needs no JMeter,
no fixture and no browser.

**Two standing conditions**, written into `ci.yml` itself, keep this from becoming the thing
that decision rejected: `regression.yml` **stays deleted**, and **no required status check may
be slower than that job**. The wall-clock-bound tier runs locally, before the push, via the
`run-tests` skill. CI must never become something people wait on.

The division of labour:

| Mechanism | Job |
|---|---|
| `run-tests` skill (`.claude/skills/run-tests/`) | run the **full regression** before every push — Claude does this automatically when finishing a feature or fix |
| `dev-cycle` skill (`.claude/skills/dev-cycle/`) | finish a change: bump, build+deploy, refresh the example, test, commit on a branch |
| `explore-report` skill (`.claude/skills/explore-report/`) | drive a browser session against a generated report, then turn findings into specs |
| `prune-actions` skill (`.claude/skills/prune-actions/`) | delete old Actions runs, keeping the newest 10, and clear Actions caches. Dry run unless `--yes`. Written when this project's private predecessor was the account's entire Actions storage bill (Aug 2026); Actions on a public repository are free, so it is housekeeping now |
| `.github/workflows/ci.yml` | on every push and PR: `mvn verify`, the `report.js` unit tests, and the static checks (`verify_shaded_jar`, `bash -n`, `verify_script_modes`, `verify_line_endings`, `verify_no_nul_bytes`, `verify_report_js_rules`, `verify_fixture_lists`, `verify_jq_idioms`, the `selftest_*` guards). About a minute. Never launches JMeter, a fixture or a browser |
| `.github/workflows/cross-os.yml` | on pull requests touching the plugin or the docs (and by hand): the operator's two commands with a `-q` file over the goldens' CSV, on **Windows, macOS and Linux**, plus the `java -jar ApacheJMeter.jar` form. **Not a required check** — it downloads JMeter on three runners, so it must never be something people wait on |
| `pre-commit` hook | **refuse** a commit *on* the primary branch; it runs no tests |
| `pre-push` hook | **refuse** any push that touches the primary branch; it runs no tests |
| `Stop` hook → `scripts/check-deploy-freshness.sh` | **warn** when the JAR in `lib/ext` is not the code in the tree. Never blocks |

Both hooks deliberately do nothing else. Main changes via a pull request, never a
direct push — that is the one rule that must hold no matter who is pushing, so it
lives in the deterministic hooks. Everything test-shaped is the skill's or CI's job.

The residual trade-off is now narrow but real: a push made without the skill still reaches
the remote with **the fixture and browser tiers unrun**, and CI structurally cannot catch
that — it never starts JMeter or Chromium. What it does catch is every Java test, every
`report.js` unit test and every static check.

The branch is resolved from `origin/HEAD` (which `install-hooks.sh` sets), never
hardcoded. Escape hatches: `WIR_ALLOW_MAIN_COMMIT=1`, `WIR_ALLOW_MAIN_PUSH=1`, or
`--no-verify`.

`pre-commit` exists as well as `pre-push` because by the time a push is refused the
commits are already on the primary branch and have to be moved off it — and that
recovery is where work gets lost. Refusing at commit time leaves the change in the
working tree, and the fix is one `git switch -c`. It stands down inside a merge,
rebase, cherry-pick or revert: those are git's commits, not the author's.

Because the hooks are the only enforcement that exists without Claude in the loop, a
hook that does not run is the worst failure mode in the repo — hence
`verify_script_modes`, below.

### Claude as an active tester

Playwright asserts what someone already thought to assert. A browser session run by
Claude covers what a per-surface assertion structurally cannot: whether every surface
describes the same run in the same words, whether the copy claims something the data
does not support, and whatever is being built right now and has no spec yet.

**One rule makes it worth doing: Claude explores, automation pins.** Every session
ends in a Playwright spec or a line in `test-plans/EXPLORATORY-LOG.md` saying why not.
Without that, the same defects are rediscovered every few months and nothing
accumulates. It is slow and non-deterministic, so it **never gates anything.**

The capability boundaries are the design, not a detail. `.claude/agents/`:
`report-explorer` has **no `Edit`/`Write`** — an explorer that can edit fixes what it
should report, and then no spec is ever written; `spec-author` writes **only** under
`test-plans/playwright/`, so a spec can never be made to pass by changing the product;
`design-scout` is read-only everywhere.

The Chrome extension **refuses `file://` URLs**, so `./scripts/serve-reports.sh` is
required rather than convenient. E19/E20 load their payload as a sibling `.js` — over
`http://` that takes a route no real user takes, so a degraded-payload finding on those
two is unverified until checked over `file://`.

**When the extension is not connected** (`list_connected_browsers` still empty on the second
call), the session does not wait for it: it drives headless Chromium through the Playwright
install under `test-plans/playwright/`, over **copies** of the fixtures, which it opens over
`file://` directly — so the E19/E20 caveat does not arise. The M5 Server-tab session ran that way
(the 2026-10-02 entry in `EXPLORATORY-LOG.md`). The rule is the same either way: every finding
ends as a spec or a log line.

### Playwright tests do not generate anything

The specs open pre-generated reports at `test-results/<ID>/<ID>-report.html` over
`file://`. `test-results/` is gitignored, so the fixtures are whatever this machine last
produced. **Rebuilding is not regenerating** — change `report.js` or the template, rebuild,
forget to rerun `run-all-tests.sh`, and every spec asserts against the previous build's
output and passes.

`global-setup.ts` guards this: each fixture carries a `.fixture-stamp` (written by
`write_fixture_stamp` in `scripts/lib/common.sh`) listing md5s of the files that decide
what a report looks like. Content hashes, not mtimes, so a no-op rebuild or a branch
switch does not cry wolf. On mismatch the run refuses to start and names the changed file.
`WIR_SKIP_FIXTURE_CHECK=1` bypasses it — only when you know the fixtures are current.
Both readers compare the listing **whole**, so a file added under a stamped directory since is
as stale as one changed or deleted: a direct Playwright run refuses while a stray untracked file
sits there, as the shell always did.

A stamp is written **only beside a report the run itself wrote, in a directory emptied first**:
`run_test_jmeter`, every `run-all-tests.sh` scenario (E20, E26 and E27, which `rm -rf` their own
directory for `-g -o`, included) and `generate-cmp-fixture.sh` remove what they are about to
write, believe the directory over `rm` — a report still there afterwards (a Windows lock) is a
FAIL that neither runs nor stamps — and stamp only when the report is there afterwards. JMeter's
exit code is never the signal (E25 runs with `failonerror=false`, a FAIL verdict exits 0, a config
error exits 1 beside a real report). It used to stamp whatever was there, so a regeneration whose
JMeter rendered nothing passed on the previous cycle's report under a fresh stamp naming the new
JAR, and R05 tested the old build, green.

That listing is the tree when the stamp was written, not what rendered the report. So the stamp
also ends with one `@deployed:<entry>` record per resource the listing names — everything under
either module's `src/main/resources` except `META-INF/`, which the shade plugin writes rather than
copies — holding the md5 of that entry inside the plugin JAR that was in `lib/ext`. Each is
compared with the tree's line for the same file: a different md5 is a fixture rendered by another
build ("rendered by a JAR whose static/css/report.css is not the tree's (…) — redeploy and
regenerate"), `none` means no JAR, several JARs, or an entry missing from the JAR, and a resource
with no record means the stamp predates the record and is stale. The first version recorded
`report.js` alone, so a JAR whose template or CSS was not the tree's stamped current.
`fixtures_current` also refuses while `lib/ext` holds more than one plugin JAR, and so does
`require_deployed_plugin` — the `SKIP_BUILD=1` path's check, which has no purge — before anything
runs. Where `unzip` is missing, only the JAR comparison is skipped, and the run says so. What is
**not** caught: a JAR that differs from the tree only in its Java, because classes cannot be
compared with sources (`check-deploy-freshness.sh` warns on that, by mtime). The whole verdict —
reading the stamp, judging each fixture, wording the refusal — is `test-plans/playwright/fixture-stamp.js`,
plain CommonJS so `js-unit/fixture-stamp.test.js` can run it over a scratch tree and hold its
stamped directories, constants and sentences to common.sh's copy; `global-setup.ts` keeps only
the fixture list and the hint.

### Static checks (S03 / R03)

Both suites run the same static block — `bash -n` over every runner, `verify_fixture_lists`,
`verify_script_modes`, `verify_line_endings`, `verify_no_nul_bytes`, `verify_report_js_rules`,
`verify_release_tier`, `verify_jq_idioms`, and the `selftest_*` guards that feed the guardrails a
known defect and demand a refusal.
Don't trust a count written here; the S03/R03 blocks in the two runners are the list. The
checks sit in **both** tiers rather than whichever tier a given change happens to get.

`ci.yml` runs the same block except `verify_release_tier`, plus `verify_shaded_jar`, calling the helpers in `common.sh`
rather than reimplementing them. That shared definition is the point, not the redundancy:
`verify_shaded_jar` opens with `command -v jar || return 0`, so it passes without checking
anything on a machine with no JDK tools on `PATH`, and CI is the one place `jar` is
guaranteed. `verify_jq_idioms` is the same shape over `jq`: it runs every line of
`test-plans/goldens/jq-gate.txt` — the one copy of the jq a pipeline gates with — over the
golden CI/CD documents its `CASES` line names, and skips without `jq`;
`test-plans/js-unit/jq-gate.test.js` runs a JavaScript translation of each line where `jq` is
absent. `selftest_jq_idioms` breaks each clause of each line in turn and demands a refusal, so
the goldens cannot go blind to a clause in silence — over G1–G4 alone they were blind to the
whole server half, because G4's client gate FAILs and decides both lines; G5, G7 and G8 are
server-only (G6, the observe-only gate, was retired with `failsBuild`, and ids are not reused).

The failures these catch are all silent in the wild:

- a syntax error means the next runner cannot start at all
- `FIXTURE_IDS` drifting from `REQUIRED_FIXTURES` leaves a fixture either generated
  and never asserted on, or demanded and never generated
- a hook committed `100644` is skipped **without a word** on Linux and macOS — which
  disables the only gate this project has. Windows sets `core.filemode=false`, so the
  bit never comes from the filesystem and must be written into the index deliberately
  (`git update-index --chmod=+x`). The check reads the index, not the working tree.
- a CRLF blob hides behind git's stat cache: the file reads clean until something touches
  it, and then staging it diffs the whole file. See the line-endings note under Conventions
- a raw NUL byte makes GNU grep treat the rest of the file as binary, so every grep-shaped
  guard over it stops seeing lines past the byte and says nothing. Two NULs pasted into
  `report.js` hid four Server-tab handlers from the `jsArg` rule. Write `\u0000`
- a documented jq line nothing runs is wrong the day it is written: the one first copied into
  the plan and a javadoc errored ("Cannot iterate over null") on every client-only run and read
  false on every server-only one
- a stale plugin JAR the deploy could not remove used to be reported removed, so the suite ran
  on two JARs and called it a pass. `selftest_purge_old_plugin_jars` deploys into a scratch
  `lib/ext` with `rm` made to fail, and then made to return 0 and leave the file, and demands the
  refusal both times; `selftest_fixture_stamps` demands that a fixture another JAR rendered is
  called stale, whichever resource differs, and so is one whose tree gained or lost a file since
- a fixture regeneration whose JMeter wrote nothing used to pass on the previous cycle's report:
  only `results.jtl` was cleared, `validate_results` asks only whether the files exist, and the
  stamp was written regardless, naming the JAR now in `lib/ext`. `selftest_failed_regeneration`
  hands `run_test_jmeter`, `run-all-tests.sh` (E05 with a stale `report-annotations.json`, E19
  with its sidecar) and `generate-cmp-fixture.sh` the old output and a stub JMeter that writes no
  report, and demands a non-zero exit and nothing left behind. Then it makes `rm` fail under
  `run_test_jmeter` (and return 0 leaving the file), E05, E26, E27 and CMP, and demands each
  refuses to run over the old report: JMeter never called, the old stamp untouched, every scenario
  a FAIL. Last, a stub that does write a report must get its stamp. The stub exits 0 having written
  nothing and 1 beside a report, so a producer keyed on JMeter's exit code fails it. It first
  proves a child shell resolves `RESULTS_DIR` to its scratch directory, because those runners now
  `rm -rf` what they regenerate
- the example script read its verdict off the page: `refresh-report-example.sh` took the first
  `ASSERTS [A-Z]*` in the HTML, which since 5874e10 is a report.css comment every report inlines, so
  it printed `ASSERTS NOT` whatever the gate said and its no-gate guard matched the same comment on a
  report with no gate (S5). It reads `gate.status` from the CI/CD JSON now (`report_gate_verdict`,
  node), and `selftest_report_gate_verdict` hands it a page carrying that comment above a PASS
  badge, each verdict in the JSON beside it, and every run with no client gate, which it must
  refuse. The example it publishes is the `-g -o` report of the three server-assert steps, run over
  demo server data `scripts/lib/example-server-metrics.js` writes from the run's JTL, so the same
  selftest holds `report_server_verdicts` (a verdict for `serverStatus`, every declared component
  and every server assert, each way of missing one refused) and `report_local_path` (the page names
  no local directory: an absolute `--csv` once put the checkout's `D:/...` path into
  `reportExample/` through the source's `resolvedUrl`), and refuses an example script that judges
  anything inline

## Architecture

### Two ingestion paths, one generator

Both produce a `ReportData` and hand it to `WebInsightReportGenerator`:

| Path | Entry point | Source of stats |
|------|-------------|-----------------|
| Listener (GUI or `-n -t`) | `WebInsightReportListener extends ResultCollector` | `LiveStatisticsEngine` fed per-sample from `sampleOccurred` |
| CLI `jmeter -g x.jtl -o dir` | `WebInsightDataExporter extends AbstractDataExporter` | `JtlParser` re-parses the JTL (JMeter's own `SampleContext` is discarded — it lacks the fidelity the report needs) |

Both paths can be active at once, so the exporter checks whether the listener already
wrote a report and skips if so. That check must derive the expected filename via
`WebInsightReportGenerator.resolveReportFilename` / `reportFilenameGlob`, not from the raw
property — the generator resolves `${timestamp}`, appends a missing `.html`, and rejects
names containing path separators, so a naive derivation misses in exactly the
configurations the check exists for and both generators run.

`ReportData` (`core/model/`) is the contract between the two halves: change it and both
ingestion paths plus every serializer must follow.

### Listener concurrency

`WebInsightReportListener` is one instance shared by all sampler threads, and by RMI
worker threads in distributed mode. `statsEngine`/`configuration` are `volatile`, written
under `synchronized (this)` in `testStarted` and read lock-free in `sampleOccurred`.
`createConfiguredEngine(config)` deliberately *returns* the engine, and fills a local
`ReportConfiguration` the caller hands in, rather than assigning either field — so nothing in
configuration setup can reach a half-built engine or configuration through a field, and
`testStarted` publishes both once they are complete (the configuration used to be assigned
empty and then populated, RPT-13). Preserve that shape when adding config.

`activeHostCount` tracks started-but-not-ended hosts: in distributed mode
`testStarted`/`testEnded` fire once per remote host on this single instance, so the engine
is created on the first start and the report generated only after the last end.

### Rendering: server-side and client-side, split down the middle

`ThymeleafReportRenderer` renders `templates/report.html`, inlining `report.css`,
`report.js` and `echarts.min.js` via `th:utext`. Chart data is injected as a JS object
literal. The result is one HTML file with no network dependencies — that self-containment
is a hard product promise, which is why `readClasspathResource` throws instead of
returning `""` on a missing resource (an empty ECharts bundle silently flips the template
to a CDN `<script src>`).

Eight tabs, in this order: `summary`, `timeline`, `samplers`, `errors`, `compare`, `asserts`,
`server`, `notes`. The **server** tab sits after Asserts and before Notes (D20), and is the only
one of the three optional tabs shipped *visible* — Compare and Asserts are `display:none` in the
template and revealed by JS, which is how the Asserts tab once vanished on a run whose payload
failed to load, leaving a FAIL badge and no way to find out why. A pane that can always say
something honest has no reason to hide: with no `serverMetrics` block, on an interim report
(nothing collected yet) or over a refused payload, the tab says which, and why. Its module is
`WIR.serverKpis` — the board, the charts, the editor and a mirror that re-judges as you type; see
"Server asserts: expected vs actual" below.

The **asserts** tab sits after Compare and before Server and is **always reachable**. It used to be
hidden until a rule existed, which is defensible for a verdict and wrong for a workshop: the
tab is where you go to *declare*, so hiding the door until someone had already walked through
it left the ⊕ as the only way in. What is still withheld from a gateless run is the *claim* —
the header badge and the gate strip say nothing, because a run that declared nothing has not
passed anything. Header cards, metadata, **the samplers and errors tables** and the notes tab
are Thymeleaf-rendered, and so is the header's server chip (its word is chosen in Java, see
below); charts, filters, the whole asserts and server tabs and all interaction are built by
`report.js` at runtime. When adding a field, decide which side owns it — both sides render some
of the same data.

**The user-facing word is "Asserts", everywhere.** The tab, the header badge
(`ASSERTS PASS`/`WARNING`/`FAIL`) and the gate strip. There is one mechanism and one word for it.

The CI/CD JSON emits `assertsEvaluated`, `assertViolations` and a per-sampler `assertStatus`, and
the JUnit failure message is `ASSERT FAIL:`. `CiCdJsonSerializerTest` sweeps the whole document
for any key beginning `sla` and fails on a match, so the retired vocabulary cannot creep back in
under a name the test did not think to list. The `badge-pass`/`badge-warn`/`badge-fail` CSS
classes stay: they are internal, load-bearing for the browser specs, and renaming them would be
pure churn.

**On the server side the word is "server assert(s)", never "KPI"** (D25) — in the UI, the
console, the docs and on the wire: the annotations key is `serverMetrics.asserts`, the CI/CD JSON
array is `serverAsserts[]`, the button is "+ Add server assert". "KPI" could equally describe a
client metric. The internal names keep it — `ServerKpi`, `KpiResult`, `KpiUsability`,
`WIR.serverKpis` — for exactly the reason `badge-pass` keeps its name. The same sweep guards it:
`CiCdJsonSerializerTest` fails on any JSON key containing `kpi`, and a leftover `kpis` key in a
file is refused like any retired key, naming `asserts`. `serverMetrics` itself keeps its name: it
is the block that declares the components the *actual* data is about as well as the asserts about
them, and the property and payload (`webinsight.report.servermetrics`, `server-metrics.json`) are
about metrics, not asserts. What an assert is grouped under is a **component** on every surface
(C7: the JSON array is `serverComponents[]`); internally a component is still a `ServerGate`,
judged by `ServerGateEvaluation`, and the page's chart-data keys stay `serverGates`/`serverKpis`.

That split used to be documented as "the sampler/error tables are built by report.js". They
are not, and never were: they are `th:each` loops in `report.html`. It mattered when the
asserts work needed a ⊕ on every metric cell and a live-updating badge — both are injected
into the server-rendered table by `report.js` rather than added as template columns, because
twelve more columns would have to be kept in step with the tab that re-renders them.

It matters again for **which table helper to reach for**. `filterGenericTable` and
`sortGenericTable` walk `tbody tr` and set `style.display`, which is right for the
Thymeleaf-rendered tables because their rows are static. The Assertion Rules table is not:
`renderTable()` rebuilds it on every edit, toggle, duplicate, delete, drag-retune and wizard
apply, and it emits two extra row types (`.wir-match-row`, `.wir-followup-row`) that belong to
the rule row above them. Its filtering and sorting therefore run on `state.rules` *before*
`.map(ruleRow)` — `applySort(applyFilters(...))` — and a display filter bolted on would orphan
the detail rows and be erased by the next render. Same reason its "judges sampler" filter
re-resolves targets rather than reading `rule.results`: `GatePayloadBuilder` caps that at
`MAX_MATCHES_PER_RULE` (200) while `recomputeDraftGate` re-evaluates uncapped, so it answers
differently before and after an unrelated edit.

The sampler table is Thymeleaf-rendered and still not static: a click on a row inserts its chart
panel (`.sampler-detail-row`) and a click on its verdict badge the rules judging it
(`.wir-judging-row`), both directly under the row, in either order. They belong to the row above
them, so the table's own walkers (`sortTable`, `filterSamplerTable`, `applyColumnVisibility`,
`hideRow`, `showAllRows`, Transaction Details, the two toggles) read them through `isSamplerCompanionRow` /
`samplerCompanionRows` / `syncSamplerCompanionRows` and never as rows of their own. They knew only
the chart panel once, so a sort stranded the judging row and a toggle built a second panel.

**A rule row is addressed by its key, never its id.** Java judges two rules under one id (two
id-less rules on one sampler and metric both derive `a-p95`), so every handler on a row — Edit,
Duplicate, Disable, Delete, the matches toggle, the broken banner's Fix — and the chart's drag
handles (their commit and their ECharts id, `wir-handle-<key>-<kind>`) name the row KEY the page
mints (`r1`, `r2`; `data-rule-key` on the `<tr>`), as the Server board's rows have since R1-2.
Keyed by id, the two rows were one row to every button, and one handle on the chart; the per-render
worst-match memo is keyed by row too, and the bold "binding" fail line is the very rule. A key is the
page's: `ruleToJson` writes none, it rides the draft record and an Undo's capture, and a block that
replaced the rules takes the old keys by id occurrence. Specs and js-unit name a rule by id and
turn it into a key with `WIR.asserts._keyOf(id)`; `_retune(id, …)` and `_handleValue` do that
themselves. `draftCount` pairs rules sharing an id by occurrence, as `markDrafts` and
`loadedEntryFor` do.

**`Usability` is the single answer to "can this rule decide anything".** Both callers read
it: `AssertionEvaluator.whyUnusable` asks `Usability.refusal(rule)` — non-null means the rule
is `NOT_JUDGED / INVALID_RULE` and decides nothing in either direction — and
`AssertionValidator` asks `Usability.check(rule)` for every problem, as findings. The
invariant, pinned by `UsabilityTest`: `refusal(r) != null` exactly when `check(r)` contains an
`ERROR`, and the `INVALID_RULE` detail is that entry's own sentence.

It exists because the question was answered twice and the two drifted. A `loadValidity`
tolerance of 20, meant as 20%, shipped as `lt -19` and reported PASS on every run; a check was
written for it, put in the validator, and `whyUnusable` never asked the validator anything —
the bug outlived its own fix. **Findings do not gate.** Put a new check in `Usability` or it
will be inert. Severity carries the whole "this is wrong" versus "this cannot run"
distinction: a warn band the fail band already covers is a WARNING, because the rule's
verdicts are still correct and refusing it would turn two swapped numbers into a non-zero exit.
The server side has its own, under the same contract and for the same reason:
`core/servermetrics/KpiUsability`, pinned by `KpiUsabilityTest`.

**The quality gate.** `core/assertion/` holds the rule model, evaluator and validator.
**One place per side, and only one:** `report/gate/GateEvaluation` judges the client rules,
`report/gate/ServerGateEvaluation` judges the `serverMetrics` block. Everything downstream —
the HTML badge, the payload, the CI/CD JSON, the JUnit XML, both exit flags — reads those two
evaluations and never re-judges anything. A run declares assertions or it declares nothing.

There is no rollup across the two sides and there is not meant to be: the header carries the
client badge plus a server chip, the JSON carries `status` beside `serverStatus` and `gate`
beside `serverComponents[]`, and a pipeline that wants one answer writes the `and` itself (the
idioms are in `test-plans/goldens/jq-gate.txt`, run over the goldens). The chip and `serverStatus`
are one fold, `ServerGateEvaluation.worstBuildingGate()`: FAIL > NOT_EVALUATED > WARN > PASS over
the components — every one of which builds (C6), so the method's name is older than the rule.
What the two sides *do* share is the arithmetic —
one `GateSummary.of(List<? extends JudgedResult>)` counts both — so `gate` and every entry of
`serverComponents[]` carry the same block of keys and read with one idiom.

**Four shapes now refuse a rule that used to be judged**, all `NOT_JUDGED / INVALID_RULE`,
so all of them are `failonerror` territory rather than verdicts:

- **one readable band beside one unreadable one** — `fail: {gt: 100, lt: 50}`. A rule that
  declares a limit it cannot apply would otherwise report PASS without ever testing it.
- **a band that is not a finite number** — a bare `1e400`, which Jackson overflows to Infinity
  inside the parser, and a `loadValidity` whose migration computes `lt NaN` or `lt ±Infinity`
  (a `"NaN"` tolerance, an overflowed target). `gt Infinity` PASSed every run with exit 0 and
  `lt Infinity` FAILed every run. `AssertionRuleMapper` reads a band through
  `ServerMetricsMapper.asNumber`, guarded on both arms, and says "not the number Infinity". A
  `loadValidity` target or tolerance that is not a finite number is refused in a sentence naming
  that field — `loadValidity.target must be a finite number, not NaN` — not in the band reader's
  words about a band nobody wrote (L3): `LoadValidityMigration` still builds the rule, never clamps
  the value, and leaves the refusal on the migrated entry (`AssertionConfig.bandRefusal`, no JSON
  property), where `readBand` asks first. A `-Infinity` target is refused too, no longer read as a
  target at or below zero that declares nothing. The page's `migrateLoadValidity` mirrors it, reading
  both fields as Jackson binds a `Double` (`jacksonDouble`: `"NaN"`, `"Infinity"` and `"INF"` text,
  then `Double.parseDouble`) (CN26).
- **a band that can never fire** — a downward band at or below zero, `fail: {lt: -19}`.
  Narrow on purpose: every metric here is a duration, count, rate or score and none can be
  negative, so `lt 0` is unreachable whatever the run did. Nothing is claimed about upward
  bands on the client. The server side has one exception, and it is a WARNING, not a refusal
  (F2b): an upward band past a catalogue metric's declared `ceiling` — `gt` at or past it, `gte`
  past it, `fail: {gt: 150}` on `connections.used.pct` — is `KpiUsability`'s `BAND_ABOVE_CEILING`,
  mirrored by the page's `kpiWarnings` in its words and pinned by the server parity vectors. It is
  judged as written and worded conditionally ("If it never reads above its ceiling…"), because
  some ceilings are nominal (that 100 is a share of a setting); a `slope` and a `divideBy` ratio
  are exempt, since neither is in the metric's unit. `LoadValidityMigration` no longer clamps
  tolerance at 1, because that clamp was authoring this exact shape.
- **a retired rule-level key** — currently `observe` and `onMissing` only, listed in
  `AssertionRuleMapper`. `tags` is deliberately **not** retired and is still ignored in
  silence: an unknown key is not an error, a key that used to mean something is.

All but the unreachable band are not derivable from a finished rule — by then an unreadable band is
indistinguishable from an absent one and a retired key has been swallowed by the unknown-key
policy — so the mapper records them at load time and they ride the model as
`AssertionRule.declarationProblems()`. `GatePayloadBuilder` ships them to the browser as
`declarationProblems`, and `report.js` reads the first as the rule's reason.

An unreadable band therefore arrives **absent**, and the page carries what the file declared on the
rule itself (`unreadable`): `withDeclaredBands` takes it from `annotationsDocument` at load, and
`ruleFromJson` takes it on Edit document… and Open file…. `ruleToJson` writes it back verbatim
whenever the rule has no band of that kind, as the Server tab's `canonicalJson` writes a KPI's (C-1).
A Download used to write bands from the model only, so a no-op Save turned `warn {gt: 100}` beside an
unreadable fail, a CONFIG ERROR, into a rule the next run judged WARN with exit 0. The editor refuses
to save the band away unseen. An empty box over a carried band means the file's band, so an operator
picked or a number typed and cleared still refuses, and only × removes it. The Server tab's editor
holds the same rule: its `setField` released a carried band on any touch of the band's controls, so
either gesture offered Save and the Download dropped the band (CN18, B4). Its band row now shows the
carried band as a Save writes it back, with a × of its own (`removeBand`). A bare `1e400` is written
back as `documentOf`'s `"Infinity"` text, through `WIR.javaText.jacksonCopy`, and so is every other
non-finite number an opened document carries: `WIR.document.replace` adopts the document through it,
the client's `declaredBand` reads a band through it, and the server slice reads its block and each
assert through it. Without it JSON.parse's Infinity was written as null — an opened file's server
band downloaded as `{"gt": null}`, a coverage floor or an unknown key likewise, and the report's own
file read as a change (CN19, S1). A slice still parses the document as it was handed over, so the
Asserts tab refuses an opened bare `1e400` band as `the number Infinity` and the Server tab as
`the text "Infinity"`, the next run's words over the Download. A legacy `loadValidity` band that
migrated to NaN is written as its `"NaN"` text, so the next run refuses it as text where this one
named the `loadValidity` field, as it does `"Infinity"`. A draft record written before the carry
existed gets the band back on restore (`withCarriedBands`).

What else the file wrote and the model has no field for rides the rule the same way (CN24): its
unknown and retired keys (`extra`) and a target nobody could read (`declaredTarget`), taken from its
entry once — by `ruleFromJson`, or by `withDeclaredKeys` at load — and never looked up by id at
write time. Looked up by id, a Duplicate's copy and an editor Save under a new id wrote neither, so
a copy of a rule carrying `"observe": true` read INVALID here while the next run judged it. The
editor carries them through every Save, refuses a retired one in the mapper's words and offers to
remove it (`dropKey`); a draft record without `keysCarried` gets them back on restore
(`withCarriedKeys`).

**The golden harness is `test-plans/scripts/run-goldens.sh` (R06).** It drives a committed,
fixed JTL (`test-plans/goldens/plain.jtl`) through the `-g -o` exporter with eight annotation
cases — G1 none, G2 `gate-mixed.json`, G3 `gate-broken.json`, G4 `gate-server.json` (client rules
plus a `kafka` and a `db` component) over a committed `server-metrics.json` payload, collected
with `wir-collect --csv` from the committed `server-metrics.csv` (how to rebuild it is in
`test-plans/goldens/server-metrics.md`), and three server-only shapes over the same payload: G5
`gate-server-fail.json` (`kafka` FAILs), G7 `gate-server-coverage.json` (one `db` component PASSes
over a LOW_COVERAGE) and G8, G5's file with the payload missing; and G9 `gate-digits.json`, client
rules only, whose bands sit within a hundredth of `plain.jtl`'s measured values (the one golden
that can see a number written on or across its band: every other one's nearest client actual is
24 away). G6, the observe-only gate, was retired with `failsBuild` (C6) and its id is not reused —
and diffs the canonicalised `.json` and `.xml` against `test-plans/goldens/expected/`. Because the
input is fixed, every number is a constant and a diff means the code moved a status, a count, a
message, a wire name or a key order on an unedited file. `--accept` regenerates the baselines; commit
them **with** the change that caused them and say which in the message. Three semantics bite
immediately: it compares the `.json` and `.xml` only, never the HTML (the comparison block lives
only there, which is why the *first* G4 — `gate-mixed` against a baseline JTL — was removed; the
G4 that exists now is the server-metrics case and has nothing to do with it); a comparison that
passes while `expected/` is **dirty in the working tree is a FAIL** — "the baselines were
regenerated rather than the code fixed" — so re-accepted goldens must be committed before R06
can go green, and that check reads `git status --porcelain`, not `git diff`, because a
first-ever baseline is untracked and `git diff` cannot see it; and `--accept` exits non-zero on
a partial set. It deliberately does not cover the live-listener path, which is not reproducible.

This paragraph used to say no such harness existed and that the substitute was to regenerate
every fixture and diff the `.json` with `runId` stripped. That substitute could never have
worked — the fixtures are timer-driven runs, so every measured number moves on every execution —
which is why it was never run. The `runId` point still stands for any comparison someone does
make: it is a fingerprint of the run, so it is the one field to exclude.

Adding a field to the CI/CD JSON is still a deliberate decision, because it is a consumed
contract — and it now fails R06 until the goldens are re-accepted, which is the review
mechanism. That the assert fields *are* emitted, in the right place, is asserted by
`CiCdJsonSerializerTest` and `JunitXmlWriterTest`.

**The evaluator exists twice, and neither copy owns the definition.** The Java engine
(`core/assertion/AssertionEvaluator`) judges the run; a mirror inside `report.js` judges it
again in the browser, so a threshold can be dragged and the verdict recompute as you type,
and so the file you save gates the next run to the numbers you were shown. Two
implementations of one rule set drift between the runs that test them, so the definition sits
in neither: `jmeter-report-core/src/test/resources/assertion/assert-parity-vectors.json`
holds it. In Java, `AssertionParityTest` runs `cases` and `gateCases` on rules built by hand, so it
never reaches a parser — a case's `expect.attainment`, the Budget column's numbers, held there
against `Attainment.of` and on the page against `attainmentFor` (AF5b) — and `MapperParityTest` runs
`cases`, `targetCases`, `detailCases`, `migrationCases` and `bandTextCases` (a band the file holds
as text) through the production mapper — binding each vector from its tree
(`treeToValue`), never from its text, because a text round trip writes an overflowed `1e400` back
as the quoted `"Infinity"` and the mapper would refuse it as text, for the wrong reason and in the
wrong words. On the page's side `test-plans/js-unit/asserts-parity.test.js` (node, no browser, no
fixture) runs those same five through `ruleFromJson`, and `asserts-parity.spec.ts`, against a real
report, runs `cases` and `gateCases` on hand-built rules and the other four through
`ruleFromJson`. A case only one side passes is precisely the bug the file exists to catch. Where a
case has an `expect.metric`, it is the canonical name `MetricRef` gives the file's spelling, and
all four runners hold their side to it (RB8): the page reads a metric name as `MetricRef.parse`
does — trimmed as `String.trim` trims, a percentile through `Double.parseDouble`'s whole grammar
(`p95d`, `p+95`, `p8.5E1`, `p0x55p0`, Java's own `p1.0E-4`) — and names it as
`canonicalPercentileName` does, `p1.0E-4` for `p0.0001`. It read `/^p\d+(\.\d+)?$/` and called
every other spelling INVALID_RULE after an edit, on a rule the run judged.

The server evaluator is mirrored the same way, with its own vectors:
`jmeter-report-core/src/test/resources/servermetrics/server-parity-vectors.json`, run by
`ServerParityTest` (through the real `ServerMetricsMapper`, not a helper — a vector parsed by a
helper pins the helper), `test-plans/js-unit/server-parity.test.js` and `server-parity.spec.ts`.
The mirror reads a source, a band and a number as the mapper does, check for check and in its
words; `WIR.javaText` is the one copy of Java's number formatters both tabs write through, and of
`Double.parseDouble`'s grammar both read through (`parseDouble`, hex floats rounded as Java rounds
them): the Server tab's `javaNumber`, and the Asserts tab's band reader `bandNumber`, its
`parseMetric` and its legacy `loadValidity` reader `jacksonDouble`. `bandNumber` read a quoted band
with `trim()` and `Number()`, so `"0x10"` was a band of 16 on the page and a refusal in the run, and
an unedited editor Save turned that INVALID row into a verdict (L2, CN25); the vectors'
`bandTextCases` hold both readers to one grammar.

**How a number is written beside a band is defined once as well.** An actual is never written on a
band it is not on, or across one, and a band is never rounded. In a sentence a band is written in
plain digits, with no E notation and no saturation at 2^63: an integral band below 2^63 is every
digit of its exact value, and any other band, an integral one from 2^63 included, is
`Double.toString`'s shortest digits written out — 1e23 is `100000000000000000000000`, not the
nearest double's exact `99999999999999991611392` (L12: `Predicate.trim` and `WIR.javaText.trim`,
held by `digits-vectors.json`'s `bandWriteCases`; a duration and a percentile key keep
`Durations.trim`'s notation, and the tables' grouped `formatBand` matches it within 20 decimals below
2^53 and from 2^63, and between them only where a double's shortest digits are its exact ones —
2^53+2 alike, 2^62 not). `WIR.digits` (between `WIR.javaText` and `WIR.document`) is the
page's one copy — `beside`, `measured`, `enDigits`, `formatBand` — and both tabs write through
it: the Server tab's formatters delegate to it, and `WIR.asserts.formatActual` keeps the client's
base precision (whole ms and s, two decimals otherwise) and adds digits only where that base would
put the actual on or across a band the rule declares, warn and fail alike. `core/assertion/Digits` is its Java
twin, and five Java surfaces write through it: the CI/CD JSON's client `results[].actual` and
`attainment.actual` at two decimals (`CiCdJsonSerializer.actualOf`, with no band of 0 — a 7.7e-6/s
that no band separates from 0 stays `0.0`), its `serverAsserts[].actual` at four (`serverActualOf`,
the same rule at the field's own four places, also with no band of 0), its `attainment.consumed` at
four beside the line at 1.0 (`consumedOf`, L14: round4 wrote a FAIL 1.0000067 of its budget and a
PASS 0.99998 of it both as `1.0`; the page's Budget label, `budgetLabel`, follows the same rule
beside 100% at whole percents — 0.9876 is `99%` there), JUnit's client and server numbers at two
(`JunitXmlWriter.formatValue`, never zero, and round2's number at a decimal tie; on the sampler
suite beside the bands of every rule that judged that metric on that sampler, REV-6, as the
samplers table's cell tooltip and every line of its judging row write it on the page, `cellBands`)
and the LOAD_NOT_ACHIEVED
sentence's quoted load (`ServerKpiEvaluator.formatValue`, the same number and
spelling, mirrored by `javaMeasured`). `samplers[]` and the top-level `errorRate` keep round2 — no
band stands beside them — so a rule's actual can differ from its sampler's row in the third
decimal. A server actual within 0.00005 of its band (3.00004 under `gt 3`) is 3.00004 in the JSON
as in JUnit; round4 wrote it 3.0, on the band it breached (S2). One that no band separates from 0
is still `0.0` in the JSON, where JUnit prints it. Neither copy owns the rule:
`jmeter-report-core/src/test/resources/assertion/digits-vectors.json` does, read by `DigitsTest` and
`test-plans/js-unit/digits.test.js` and compared with `==`, never a tolerance. One stated exception:
float noise. When even the cap misleads — a T-Digest p90 of 1587.0000000000002 (seen on one E10
run; E10 is a live run and declares no such rule) FAILs a `gt 1587` by 2e-13 — the capped number is
written, and the verdict is the evaluator's to answer for. The cap is twelve significant digits or a
third decimal, so from 1e7 up the JSON's four places already reach it and `serverAsserts[].actual`
is round4's number there (`digits-vectors.json` pins both edges). The page
gets each client match's actual as Java's double (`GatePayloadBuilder.judged`), not two decimals of
it, because the Budget bar judges that number: at `round2` a FAIL on its own band drew green until
an unrelated edit re-judged the run, and Discard drafts drew it green again. A NaN — a rule-named
quantile the digest could not answer — is shipped as null, which the page reads as NO_VALUE;
`round2` made it a measured 0 (L16).

So touching either side's judging, band parsing, rule mapping, or how a number is written beside a
band (`digits-vectors.json`), means adding the vector first. `report.js`'s `bandFromJson`/`ruleFromJson` refuse what the Java mapper refuses and
`validateEditor` refuses an unreachable band, for the same reason. (The top-level chart
formatter is `formatChartValue`; `WIR.asserts` has its own `formatValue`, which is why the
outer one was renamed.)

**Never read `rule.results` to answer a question about a sampler.** `GatePayloadBuilder` caps it
at `MAX_MATCHES_PER_RULE` (200) and `RuleResult.cappedTo` keeps the **worst** by severity, so a
blanket rule over 500 samplers carries no result for the ~300 that passed — while
`recomputeDraftGate` re-evaluates client-side uncapped, so the array silently becomes complete
after any unrelated edit. Anything keyed on it answers differently before and after. The sampler
badges did exactly this and left passing rows unbadged until an edit made 300 badges appear at
once. Go through `verdictsBySampler()`, which resolves targets itself and completes a capped set
with `judgeMatch` — the same function the evaluator uses, so a badge and a verdict cannot be
computed by two different rules. `resolvedNames` is the same reasoning for filtering.

**A chart rule line is filtered by target *and* by the sampler filter.** `rulesForChart` matches
the metric and then `judgesVisibleSampler`; a rule about a sampler the filter bar has hidden
draws nothing, because a limit over a series it does not judge is worse than no limit at all. A
`scope: overall` rule always draws. Matching on metric alone drew every rule on every chart —
ten lines on a ten-sampler plan, nine of them about nothing on screen.

**Escaping.** Thymeleaf `th:text` escapes, `th:utext` does not. `report.js` writes
`innerHTML` in a great many places — count them with
`grep -cE '\.innerHTML\s*\+?=' jmeter-web-insight-report/src/main/resources/static/js/report.js`
rather than trusting a number written here — and anything derived from JTL content (sampler
labels, URLs, response bodies, error messages) must go through `escapeHtml()`.
`xss-escaping.spec.ts` covers this. Every inline handler in the two editing modules is one
`onclick="WIR.asserts.x('literal')"` or `WIR.serverKpis.x(...)` call with its arguments through
`jsArg`, and the server module has no other event attribute (its editor binds a delegated
listener); `verify_report_js_rules` refuses the rest statically, and the spec has a second,
E26-loading sweep because E25 carries no server block.

**Large reports.** Above `externalDataThreshold` the chart payload is written as
`window.__WIR_DATA__ = {...}` to a sibling `.js` and loaded via `<script src>` — not
`fetch()`, which is CORS-blocked on `file://`. The name is **derived from the resolved HTML
name** by `WebInsightReportGenerator.externalDataFilename`: only the default
`web-insight-report.html` keeps the literal `web-insight-data.js` (E20 and the docs assert
that exact string); any configured report name gets `<name>-data.js`. It used to be a
constant, and two large reports in one directory then shared one payload — the second run
won, so the first report drew run 2's charts above run 1's own header cards and sampler
table, and the template's presence-only guard could not see it. Forced mode
(`-Jwebinsight.report.externaldata=true`) streams straight to disk rather than
materializing the payload as a String inside the JMeter JVM.

The sidecar is evaluated as JavaScript, not parsed as JSON, and the two differ on one key: in an
object literal `"__proto__": v` sets the prototype instead of adding a key, so a component named
`__proto__` vanished from a sidecar report (and its asserts from the next Download). Both paths
therefore write it through `template/ObjectLiteralWriter.assignment`, which writes a `__proto__`
key in its computed form `["__proto__"]:` and every other byte as the JSON had it (N2). The inline
payload is a string the template hands to `JSON.parse`, and needs nothing.

### Server asserts: expected vs actual

The model is the one Aaron set (D23): the annotations file declares **server asserts** — the
expected — the collector fetches what the servers actually did over the run's window, and the
report compares the two. Three steps, and only the third gates:

```bash
jmeter -n -t plan.jmx -l results.jtl     # interim report: the client gate only, nothing collected yet
./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --prometheus-url https://prometheus.example/api/v1     # or --recording DIR, or --csv FILE
jmeter -g results.jtl -o report -Jwebinsight.report.annotations=report-annotations.json \
       -Jwebinsight.report.servermetrics=server-metrics.json
```

Step 2 takes exactly one of `--prometheus-url URL [--prometheus-token-env NAME]`, `--recording DIR`
or `--csv FILE`, plus `--step` (default `15s`) and `--clock-offset` (a signed duration added to every
timestamp, for servers whose clocks are known to be off). Where the metrics come from is **never** in the
file (C8): the annotations file says *what* is expected of *which component*, the collector's
command line says where to fetch it, and a report reads only the payload the collector wrote.
Step 3 needs both properties. `-g -o` demands an empty output directory, so there is nothing to
auto-detect, and the payload is **never** auto-detected on either path — a wrong path must be
loud rather than silently absent. `requiresLoad` and `divideBy` are optional, advanced keys,
inert when unused; the core flow is the three steps above.

**Where it lives.** `core/servermetrics/` is the model and the judging — `ServerKpi`,
`ServerGate`, `Reducer` (incl. `slope`, in units per **minute**), `Across`, `KpiResult`,
`CollectionStatus` (what happened to a *series*, as distinct from what an assert decided),
`KpiUsability`, and `ServerKpiEvaluator`, the server `AssertionEvaluator`.
`core/servermetrics/catalogue/` is the metric catalogue: `Catalogue` reads the built-in type files
under `src/main/resources/com/jmeterwebinsightreport/core/servermetrics/catalogue/` (`index.json`,
`postgres.json`, `kafka.json`, `redis.json`, `host-linux.json`, `aws-ec2.json`, `oracle.json`,
`influxdb2.json`, `influxdb3.json`, `jvm.json`, `http-service.json`, `kubernetes-pod.json`,
`mysql.json`), and the page receives the same files as
`chartData.serverCatalogue`, so the browser and the mapper resolve a catalogue metric from one copy.
A Prometheus-backed type is a JSON file and a line in `index.json`, no Java and no `report.js`.
Some `oracle` metrics read series only the custom metrics TOML in Tutorial 15 (`15-Server-Catalogue.md`) defines, and
`CatalogueTest` derives those series' names from that block by the exporter's naming rule, so the
tutorial and the queries cannot drift apart — which is also why `mvn test` in `jmeter-report-core`
needs the repository's `docs/` beside it.
Beside `queries`, a metric may carry `names` — per tool in `Catalogue.TOOLS` (telegraf, datadog, otel,
cloudwatch) the metric in that tool's export, its `kind`, `scale` and an optional one-line `note`
saying which series to export (`ToolName`), or `null` for "this tool has no single metric: export the
computed series under our name". Each tool's name has one shape, written once in `Catalogue`'s
javadoc and held by `CatalogueTest`: telegraf `<measurement>.<field>`, datadog and otel the metric's
own name, cloudwatch `<Namespace>:<MetricName>` with the statistic and dimension in the note. A name
ships only once it was verified against the tool's docs or source (each type's `source` says which,
and when); an unverified one is left out, never guessed. Two metrics of one type that name one tool
metric must read the same series, since a file holds one series per instance under a name. One
with no prometheus query is known by the neutral `<type>:<metric>{match}`
(`CatalogueMetric.identity`, mirrored by the page's `catalogueIdentity`) and needs some tool's name —
or, where every tool it declares is `null`, a description that ends by saying how to compute it,
`No single <Tool> metric: <what to combine, and how>, and export the result as <name>.` (`CatalogueTest`
pins that wording; `aws-ec2`'s EBS IOPS, EBS throughput, EBS limit-exceeded and ENA allowance KPIs are
the ones, each a CloudWatch metric-math expression); an assert on it is FAILED (SOURCE_FAILED) under
Prometheus or `--recording` with "has no prometheus query", and only `--csv` collects it — every
`aws-ec2` metric is one, and so are the four `kafka` ones the type's exporters cannot answer (the two
request latency p99s, `request.handler.idle`, `consumer.lag.seconds.max`).
`core/servermetrics/collect/` is the collection: `Collector` assembles a payload from a mapped
block, the run's window and identity and the one source the command line named, which the payload
calls `metrics` — `PrometheusSource` (live), `FileSource` (`--recording`: replays recorded
range-query responses, one `<first 16 hex of sha256(query)>.json` per query) or `CsvSource`
(`--csv`). A series is identified by its component **and** its query — two components of one type
with no `match` resolve to one query, and in a CSV only the component tells their rows apart — so
payload series carry `component`. The source's status is `OK`, `PARTIAL` (some series failed,
others came back: "1 of 8 series failed - first: ...") or `FAILED` (nothing came back).
`Redaction` masks every secret by value, because the collector is the one place that knows which
values were secrets. In the report module, `annotation/ServerMetricsMapper`
reads the block, `servermetrics/ServerMetricsPayloadLoader` reads the payload,
`gate/ServerGateEvaluation` judges, and `template/ServerMetricsPayloadWriter` puts the series into
the chart data. The merge is in `WebInsightReportGenerator`, so both ingestion paths have it.

**The collector.** `WirCollect` is a `main` inside the shaded JAR. Jackson and slf4j are JMeter's,
so it runs over the plugin JAR plus JMeter's `lib/*`. Since decision 13 the JAR's `Main-Class` is
`WirCollectLauncher`, a JDK-only class that finds JMETER_HOME from the JAR's own place in `lib/ext`
(else the `JMETER_HOME` variable; set-but-wrong is an error), refuses no or several plugin JARs in
`lib/ext` with a sentence, and invokes `WirCollect.main` through a `URLClassLoader` over plugin +
`lib/*.jar` with the platform loader as parent (in-process: no second JVM, no Windows argument
mangling). So `java -jar <JMETER_HOME>/lib/ext/jmeter-web-insight-report-X.Y.Z.jar ...` is the one
command for every OS. `scripts/wir-collect.sh` stays the bash wrapper, self-contained (it does not
source `common.sh`) and still what C35-C41 run; `wir-collect.bat` is one line,
`java -cp "%JMETER_HOME%\lib\ext\*" com.jmeterwebinsightreport.WirCollectLauncher %*` — no bash,
so no WSL trap, and no logic (the wildcard only finds the launcher, which re-checks the JAR count).
`--help` (or `-h`) prints the options, the exit codes and the `java -jar` line. A release attaches
`wir-collect.sh` and `wir-collect.bat` beside the JAR (and the annotations JSON Schema) —
`_perf_release`, step 6.

It exits **0 whenever it wrote the payload**, whatever each series' status, and **1** when nothing
was written — a usage, input or environment fault. There is no other code (D27). It used to exit
2 on a series that was not judgeable, which stopped a Jenkins `sh` step, or any `set -e` script,
before step 3 — the only report that gates — so a collection failure was decided by the collector
instead of the report (it first bit as an observe-only gate breaking the build it had promised
never to break; observe-only is retired, the reasoning is not). The payload records what happened
and the report decides what it means. Console lines are ASCII, because they are for `grep` in a
CI log, and the payload is written with LF line endings on every OS. `--test-name` must be the
same text as `-Jwebinsight.report.title` whenever step 3 sets one: `runId` hashes it.

**The CSV backend (`--csv`).** Long format, one row per point, header required, columns found by
name in any order, extra columns ignored, quoted cells and a UTF-8 BOM allowed:
`time,component,metric,instance,value`. `time` is ISO 8601 **with** an offset or `Z`, or epoch
milliseconds — a time with no zone is refused, never guessed. `metric` is a catalogue metric's name
or a custom assert's `query` text. `instance` is optional (empty is one instance; a value becomes
the chart label `instance=<value>`). `value` uses `.` as the decimal point and no thousands
separator; empty or `NaN` is a gap, not zero; without `--from` it is the metric's value in the
catalogue's unit, with no counter conversion. Rows more than one step outside the run window are
dropped. A bad row fails **only the series it belongs to**, naming the line
(`e26-metrics.csv line 1328: time "2023-11-14 22:13:20" has no time zone - ...`), so the source
reads `PARTIAL`; a missing required column fails the whole file. A `metric` cell that is a name
padded with an invisible character `String.trim` leaves (a no-break, zero-width or bidi character,
`KpiUsability`'s INVISIBLE_PADDING class) is such a bad row of the series that name asks for: it
used to be read as a series nobody asked for, and the one asked for came back empty, naming no
line (F8-residual). A component's `match` with such an end is a WARNING under the component.

**`jmeter -g` reads a CSV itself (P4, decision C).** When `webinsight.report.servermetrics` names a file
whose first character is not `{`, `WebInsightDataExporter.collectServerCsvIfGiven` runs
`WirCollect.run` in-process — the collector's own validation, refusals and console lines, no second
grammar — into a temp payload, with `--test-name` = the report title, `--rampup` = the configured
ramp-up and `--collected-at` = the CSV's mtime (so a rebuild keeps the collectionId and the drafts).
Reader options are `webinsight.report.servermetrics.<step|clockoffset|timezone|from|layout|columns|
csvcolumns|component|env>`. A collection the collector refuses becomes `ReportConfiguration
.serverDataProblem` → a refused (UNREADABLE) payload, never an ExportException (decision B). A relative
path missing in the cwd is looked up beside the JTL. Exporter only: the listener runs before the data
exists, and ignores `webinsight.report.servermetrics` altogether (an INFO line), so one `run.properties`
serves both commands — it used to read it and refuse it as another run's, so a shared file made every
interim report read REFUSED, with no NEXT line. A -g run that brings server data is never skipped by `thisRunAlreadyReported` (it used to be,
silently, over the listener's interim report); any skip prints `WEB-INSIGHT-REPORT: SKIPPED`. This
reverses "a report reads only the payload the collector wrote": for files it is two steps, not three.
Golden G10 = G4's inputs given as the CSV; equal to G4 but for collectionId, which the canonicaliser
drops for a CSV case (`--volatile-collection`). Prometheus and `--recording` keep wir-collect.

**E28 and E29 are real pages in the shapes the in-page fakes imitated** (`server-fixtures.spec.ts`). E28 is
`jmeter -g` reading a committed CSV over `plain.jtl` with one assert per misalignment (a 3 h shift, a
near-miss name, blank values, 4 s rows under `sustainedFor 30s`, an unquoted `90,9`) beside one that lines
up, plus `E28/interim/` and `E28/refused/` of the same file. E29 is generated at fixture time
(`gen-soak.js`, a fixed seed, ~25 MB CSV): four hours, 24 instances at 1 s, so the page draws 20, thins
to 4000 points and must still reach the 3-second spike the build judged, from a forced sidecar.

**A CSV's step and wide layout are measured when not given (decision 12).** The wide layout is read off
the header alone (`collect/CsvShape.looksWide`: Grafana's `Time,component/metric,...`, not with
`--csv-columns` or `--component`), because the layout is part of what the file reads as. The step is
measured from the reader's own index, under every flag given (`CsvSource.measuredStepMillis`) — no second
CSV grammar: each instance's `Coverage.mostCommonSpacing` over its rows with a value inside the run window
(plus one step), taken when that gap repeats at least 3 times, then the widest over every instance, as
`Coverage.spacingMillis` takes it. The index is keyed without the step, so `WirCollect.measureCsv` hands
the collection the reader that measured, and the file is read once. A measured step coarser than 15 s that
leaves fewer than 10 points in the run is not adopted, and said (F5). `measureCsv` puts either into the
options only when the flag was absent and prints `step 5s, measured from the file's rows` / `layout wide,
read from the header`, naming the override; nothing measured says nothing. Both paths (`wir-collect`,
`jmeter -g`) get it. Prometheus and `--recording` keep 15 s. E28 sets 15 s explicitly to keep its H11 case.

**The committed payloads cannot drift.** `WirCollectTest.theCommittedPayloadsAreWhatTodaysCollectorWritesFromTheirCsv`
re-collects E26's, E27's and the goldens' CSVs with today's collector and compares every series and
source (only `collectionId`, `collectedAt` and the CSV's recorded path may differ): a change to what
the collector writes fails `mvn test` until the payload is re-collected (the command is in each
README) and committed with it. Re-collecting moves the E26 pins in `server-tab.spec.ts` (collectionId,
collectedAt, the three per-zone times) and the goldens' `collectionId`.

**Prometheus says what went wrong.** A run longer than 11,000 points at `--step` is refused before
asking, naming the step that fits; a non-200 answer leads with Prometheus's own error (a 400's PromQL
parse error); a 401/403 names `--prometheus-token-env`; a window that ends after the collecting
machine's clock is refused rather than waited for (`PrometheusSource.httpFailure`, `MAX_POINTS_PER_SERIES`).

**The CSV explains itself (P3, research H2-H4).** The silent cases the 2026-10 research found are all
said now, in `CsvSource`: a series whose rows exist but all fall outside the window is FAILED with
both spans and the shift (`outsideWindow`: a whole number of quarter hours within ±14 h is named as a
time zone with `--time-zone`/`--clock-offset`, seconds as clock skew, anything larger as another
period) — it used to be EMPTY → NO_MATCH "the query matched no series" and pass every gate; a name
that differs only by case or `.`/`_`/`-` is FAILED naming both spellings (`nearMiss`); rows inside the
run with no value are FAILED as such; a row with more cells than the header (an unquoted `90,9`), one
that stops before the value, an unclosed quote, a 15+-digit epoch (µs/ns), a `;`/tab delimiter and a
UTF-16 long file are refused by line or by name; a repeated time with another value is a console
WARNING (`duplicateNotes`). `wir-collect` prints the run window, the long file's unasked series
(`seriesNotAskedFor`), blank-cell gaps instead of advising a coarser `--step`, refuses a missing
`--csv` with exit 1, and turns an OutOfMemoryError into a sentence. A CSV source's `resolvedUrl` is its
file name only, and its NO_MATCH reason reads "no row of the CSV names component X, metric Y" on both
sides (`ServerKpiEvaluator.collectionDetail`, report.js `collectionDetail`). The Collection panel opens
on any EMPTY/INSUFFICIENT/FAILED series and lists them. The tests that used to pin the silence
(`CsvSourceTest` out-of-window, the DST case) now pin the sentence.

`--from TOOL` (telegraf, datadog, otel, cloudwatch; `--csv` only) reads a file exported from that
tool: a catalogue metric is looked up under the tool's name first and converted by the catalogue's
`names` entry (`collect/Conversion`: `gauge`, `counter`, `rate` or `sum-per-period`, times `scale`,
per instance and before the window is clipped, so a counter's first rate uses the row before the
run), then under the catalogue's own name, unconverted. With neither, a tool the catalogue declares
`null` for, or does not name, fails the series by name. A custom query is never converted. The
payload records `sources[].from` and `series[].readAs`, and the tool enters the `collectionId`
only when present, so a collection without `--from` keeps its id.

`--layout wide` (`--csv` only; `collect/WideCsv`) reads Grafana's "Series joined by time" download
into the same index as a long file: a `Time` column, then one column per series named
`component/metric[/instance]` by its header or by a `--columns` map of exact header text; a column
neither names is ignored and listed on the console, never inferred from a raw metric's name, as is
one naming a series nothing asks for (a typo in the legend's component), and UTF-16 (Download for
Excel), a `;` delimiter and a formatted cell are refused by name. `--time-zone` reads a zone-less
time in either layout (without it the refusal now names the flag; a time the zone's daylight-saving
change repeats or skips is refused), an all-digit time below 1e11 is refused as epoch seconds, and
the collector prints a line for every CSV series whose points are more than 1.5 steps apart, or
more than one when that left it INSUFFICIENT. `sources[].layout` (`wide` only) and `timeZone` enter
the payload and the `collectionId` only when given, so a long collection keeps its id.

A long file another tool wrote is read with `--csv-columns SPEC` (`collect/CsvColumns`: `key=column`
pairs for `time`, `component`, `metric`, `instance`, `value`; `metric=_measurement+_field` reads
`<measurement>.<field>`) and `--component ID` (every row that component's, refused beside a component
column; every other component's asserts then read UNMATCHED, so it suits a file whose asserts are all
on one), and InfluxDB v2's annotated CSV with no flag: `#` lines skipped, a block's header the line
after `#group`/`#datatype`/`#default` or, after a blank line, a line naming the time column.
`sources[].columns` (normalised) and `component` enter the payload and the `collectionId` only when
given, like `layout`; C40 collects an InfluxDB 2 export of E27's data, and C41 a Datadog export of it
by Datadog's names (`--from datadog`: a 0-1 gauge x100, a per-second rate x60, and a KPI Datadog has
no single metric for read under the catalogue's own name).

**The `serverMetrics` block and the `server-metrics.json` payload.** The block declares
`components` — `{"orders-db": {"type": "postgres", "description": "Orders DB", "match":
"instance=\"orders-db:5432\""}}`, where `type` is a catalogue type (`postgres`, `kafka`, `redis`,
`host-linux`, `aws-ec2`, `oracle`, `influxdb2`, `influxdb3`, `jvm`, `http-service`, `kubernetes-pod`,
`mysql`) or `custom`, `description` is the label, and the optional `match` is a PromQL label selector expanded
into the catalogue query's `${match}` / `${andMatch}` slots — `environments` for `${env.*}`
interpolation in queries, and the `asserts`. Each assert names its `component` and **either** a
catalogue `metric` (`"metric": "connections.used.pct"`: query, unit, reduce and across come from
the catalogue, and whatever the assert writes overrides unit/reduce/across) **or** its own `query`
— the escape hatch, required on a `custom` component; both or neither is `INVALID_RULE`. Beside
that: `reduce`/`over`/`across`, `warn`/`fail` bands as the client's, `unit`, `enabled`. There is no
implicit component: an assert must name a declared one. **Every component fails the build** (C6);
there is no `failsBuild` and no observe-only, and a metric that should only be watched gets a warn
band and no fail band. Retired keys are refused with a sentence naming the replacement — block keys
`sources`, `gates`, `kpis`, `packs`; assert keys `source`, `gate`, `onMissing`, `required` and a
top-level `sustainedFor`; `failsBuild` on a component. The shipped JSON Schema
(`static/schema/report-annotations.schema.json`) declares the block, and `AnnotationsSchemaTest`
holds every object's keys there to the Jackson bean the loader binds (`ServerMetricsJson`,
`ReportAnnotations` and their nested classes; `reduce` and `over` to an explicit list, having no
bean), offering no retired key: a key added to one side only fails `mvn test`, and so does a
committed annotations file carrying a key the schema does not declare, bar the deliberate ones the
test names. Its `reduce.kind` and `sustainedFor` patterns are held to `Reducer.parse` and
`Durations.parseMillis` over their edges, and `reduce` requires `kind`: a written `reduce` replaces
the catalogue's whole, so one without a kind is `INVALID_RULE`. The
payload carries the page's `runId` plus a `collectionId` — a hash of the source, the resolved
queries, the window and the collection time, stamped into `serverComponents[]` — and
`ServerMetricsPayloadLoader` refuses a payload of another run outright, not embedded and not drawn
under a banner, for `RunIdentity`'s reason. A missing or refused payload is a configuration error
whenever an enabled server assert is declared; with none it is a warning. Series enter the chart-data map beside the client charts, so they count toward
`externalDataThreshold`, with an instance cap per series and a point cap per instance — thinned,
not truncated, the true counts carried. A capped instance is thinned by bucket, each bucket's lowest
and highest point kept (`ServerMetricsPayloadWriter.thin`, F6), so the drawn line reaches the spike
a `max` judged and the dip a `min` judged; every k-th point used to drop whichever fell between two
strides. That is all a capped line promises (REV-10, a stated limit). At the collection step, the
page's default, it joins the kept points in time order, each stretch's low and high, so it swings
between them rather than following the series, whatever the reducer. At a coarser rung a `max` or
`min` bucket takes the extreme kept point in it, and every other reducer's averages the kept points
(`bucketServerPoints`' `avg`): an average of extremes, the mid-range only where a bucket holds one
stretch's pair, never the collected mean. The verdict is the build's either way. The page's gap
markers measure a thinned instance against that shape (`gapUnit`: two kept
points of a series that missed nothing can be the first of one bucket and the last of the next), and
`server-chart.test.js` mirrors `thin` to hold them together. Coverage is a collect-time property **of the collected
window**, never recomputed at judge time (D28), which is why every surface says so and why a long
ramp-up makes it optimistic.

**`serverStatus`, and the one fold the chip reads.** The CI/CD JSON's top-level `serverStatus`
(D24) sits beside the client `status` and is never folded into it; it is present exactly when
`serverComponents[]` is. Its value is the worst status over **all** components — FAIL >
NOT_EVALUATED (a component that judged nothing) > WARN > PASS — and NOT_EVALUATED when none was
reported. `ServerGateEvaluation.worstBuildingGate()` is that fold, and the only one:
`serverStatus()` and `headerChip()` both read it, so the JSON's word and the header's cannot be
chosen by two rules (a component whose source died used to lose the header word to a sibling's
PASS). The chip's word is the worst component's; every other declared component is "N more", a
pointer to the tab. The page is handed the same word as `chartData.serverVerdict` (status, the
chip's entry and label, and the reserved entry when there is one), so the Summary card prints it
rather than folding again. Asserts that name no declared component are reported as one more
component, the reserved `(no gate)` entry, in the fold and in `serverComponents[]` (where it has no
`type`), as JUnit always had them. `(no gate)` is its id on the wire — the JSON's `id`, the JUnit
suite's name — and nothing else: everywhere a reader is shown it, the JSON's `label` included, it is
`(no component)` (`ServerGateEvaluation.NO_GATE_LABEL`, SF22). Declared and judged against nothing,
the chip names that state instead, in the neutral style and the Summary card's words: `Server ·
NOT COLLECTED` on the interim report, `Server · REFUSED` over a refused payload (SF47). Those are
states, not verdicts — `serverStatus` stays absent on both — and the chip is absent only when no
server assert is declared. It used to be absent on both, by a decision `headerChip`'s javadoc
recorded, and was then silent about the one CONFIG ERROR a refused run exits 1 over.

**Server data never changes the exit code by default (decision B, 2026-10-21).** Aaron: "the server
tab is a bonus, not the basic jmeter report". `ReportArtifacts.hasConfigProblem()` is split into
`hasClientConfigProblem()` (a client rule that cannot run, an unreadable annotations file — what
`failonerror` acts on) and `hasServerConfigProblem()` (`fatalConfigErrors` and a refused payload —
what `webinsight.report.server.failonerror`, default **false**, acts on; `ExitPolicy.failOnServerError`,
`NonZeroExit.FAIL_ON_SERVER_ERROR`). With the flag off a server problem prints
`WEB-INSIGHT-REPORT: SERVER NOT JUDGED`, never `CONFIG ERROR` — that word keeps meaning "this exits
non-zero"; with it on, the 4.0.x fold comes back exactly, which is how C35 and C37 keep their exit-1
assertions as `-strict` sub-runs. JUnit is unchanged (Aaron, decision 3): server collection and config
faults stay `<failure>`. A `serverMetrics` block whose shape does not bind (`"asserts": {}`) costs the
server side only: `AnnotationLoader` re-reads the file without it and carries it, keys verbatim, as
`ServerMetricsJson.unreadable`, which the mapper refuses as one block problem. A JSON *syntax* error
still loses both sides. `PropertyCheck` (both paths) makes a property named exactly `webinsight` —
PowerShell's split of an unquoted `-Jwebinsight.report.x=y` — a client CONFIG ERROR, and warns on an
unknown `webinsight.*` name and on a path a properties file mangled.

One thing that looks like a bug and is not. A component whose judged asserts PASS beside a
SOURCE_FAILED or LOW_COVERAGE one reads PASS — and so do `serverStatus` and the chip (G7 pins
exactly this). The status is the verdict; a configuration or collection error is reported beside it
(SERVER NOT JUDGED, the Server tab's not-judged box, JUnit, STRICT), exactly as a broken client rule
contributes nothing to `gate.status`, and making only the server side differ would give the two
statuses different meanings. In JUnit a server FAIL, and a SOURCE_FAILED, LOW_COVERAGE or `INVALID_RULE`
(D4), is a `<failure>`; the other NOT_JUDGED reasons are `<skipped>`, and WARN is `<system-out>`.
(D29 — an observe-only gate's FAIL and collection failures as `<skipped>`, so Jenkins' `junit`
step would not mark the build UNSTABLE — is retired with observe-only, C6.) With server asserts
declared and nothing judged there are no component suites, so the XML gets one
`<testName> - serverMetrics` suite (SF47): its `serverMetrics.payload` testcase is a `<failure>`
carrying the JSON's ERROR finding when a refused payload was needed, and a `<skipped>` on the
interim report — no payload is not an error, and a failure there would mark every step-1 build
UNSTABLE — plus one `<failure>` per assert the file makes unrunnable, as step 3 writes it
(`ServerGateEvaluation.unrunnableResultsWithUnknownTypes()`: the asserts on a component of unknown
type included, which the CONFIG ERROR count takes once, at the component). G8 pins the refused shape.

**The jq a pipeline gates with lives in one file**, `test-plans/goldens/jq-gate.txt`, and
documentation copies a line from it verbatim — never a line retyped. DEFAULT is the verdicts: the
client half passes a run that declared no client rule, the server half fails only on
`serverStatus` FAIL; pair it with `failonerror` and `server.failonerror`. STRICT is DEFAULT plus every check the file asked
for that never ran, as far as the JSON can show it — `INVALID_RULE` anywhere, SOURCE_FAILED or
LOW_COVERAGE in any component (every one builds, so there is nothing to exempt), and any ERROR
finding — and is meant for
`failonerror=false`. How it is checked is under Static checks.

**The page: `WIR.serverKpis`.** The Server tab's module: the component badges, the board, the
charts, the Collection panel, the editor (Component and Metric selects, with "Custom query…" for
an assert's own `query`), Validate query — an exact lookup of the series by component and
resolved query against the collected payload, never an estimate (D22) — Generate from this run and
drag-to-retune, with Generate opening a review before it adds anything. "+ Add component" writes a
component into the document, and "+ metric" on a catalogue component opens the metric picker and
its thresholds step (prefilled from the catalogue's safe default or this run); the file then names
the `metric` and its bands only. "This run" is one number wherever the picker, Set thresholds or
Generate shows or proposes it — `runOf`'s `observed`, what Java would compare: the catalogue's
reducer and across over the judged window, through the mirror's `reduceAcross` on an uncapped
series and Java's shipped `judgedActuals[metric]` on a capped one (per metric: two catalogue
metrics can share one series), never the page's thinned copy and never a slope's level. A build
number answers only for the window the build judged, so a file that moves `excludeRampUp` withholds
it. A band Java would say can never fire is not proposed, nor an upward one at or past a catalogue
metric's declared `ceiling` (`CatalogueMetric.ceiling()`: 100 on the Postgres percentages, 1 on
`up`; read and checked at load, judged by nothing, and warned about as `BAND_ABOVE_CEILING` when a
typed or dragged band goes past it), nor one that rounding put on the run; upward,
the headroom is capped at the same share of the room left below the ceiling. Set thresholds and
Generate's review judge a draft through the board's own `rowsFor`/`rowDisplay` (`draftVerdicts`), never
`evaluateKpis` directly, so neither can call an assert something the board will not — and so does
the editor's "If saved" line (`editorVerdict`, under the edited row's own key, so a capped series
keeps the build's verdict until edited and then reads NOT RE-JUDGED). A component card's ⋯ edits
its description and match, or deletes it **with every assert naming it**, through
`WIR.document.replace` as "+ Add component" writes; an assert left on a deleted component would be
the `(no gate)` entry and a CONFIG ERROR. The Summary
tab carries a server card under the Asserts card, read from the verdict the run was gated on.
A server assert is named by one rule wherever a reader sees it, `assertNaming` (Q11): a catalogue
assert by its metric's label with its id as a dim line under it (the id is the JUnit testcase
name), a custom one by its id; the wire keeps ids. So a spec finds a board row by its key
(`_keyOf`), not by the text of `.wir-kpi-name`, which is the id only for a custom assert.
Its mirror judges uncapped series as you edit. A capped series keeps Java's **baked** verdict,
because thinned points cannot be re-judged honestly, and once its assert (or a client rule it
names) is edited that verdict is withheld — NOT RE-JUDGED — rather than shown beside a threshold
it was not taken against. A row that decided nothing (NOT_JUDGED) draws its band lines muted and
**no breach wash**: a row that decided nothing must not look like a breach.

**`WIR.document` owns the annotations file, and neither tab does.** Each module registers a
**slice** — `WIR.asserts` owns `assertions` and `testNotes`, `WIR.serverKpis` owns
`serverMetrics` — with the keys it owns, how to build, baseline, count, snapshot, restore and
discard them; whatever no slice claims is carried through verbatim. Save, Open file…, the unsaved
pill and the drafts all go through it. A draft is one `localStorage` record per run holding every
slice, restored **once, after every slice has initialised** — restoring inside
`WIR.asserts.init`, before the server slice existed, is why server drafts once never survived a
reload — and a server snapshot taken against another collection is stale and not restored. Not
restored is not unsaid: a slice's `held` counts the edits a stale snapshot carried, and
`WIR.document.dropped()` is what both tabs announce (the interim report's server edits on the
step-3 report), as `restoredCount()` is what the Server tab's "Draft restored" line reads. A held
snapshot is not this page's draft: every persist writes it back while its slice has no edit of its
own here, **Discard drafts included** — Discard drops this page's slices and keeps the held one and
the reader's Dismiss (F9) — and the first edit of that slice here replaces it. An Undo's `capture()`
is this page's own record (`buildRecord(true)`) and the held map **kept apart**, because every
restore refuses a stale snapshot; `reinstate` restores the one and holds the other again (F1). The
component Undos put the document back first (`restoreDocument`), so the frame matches, then
reinstate the capture taken beside it. The
Asserts-tab draft banner counts both slices but speaks for the client verdict only: server changes
add "server asserts: K changed — see the Server tab", and no server verdict is folded into the
client sentence (D24). The document editor is the Asserts module's, opened from either toolbar's
Edit document…; its host moves into the pane that opened it.

**An applier leaves its slice consistent, by itself** (`replace()`'s contract, N1): primary and
derived state, from its own block and the run alone, and it never renders or persists — the caller
does both. The client applier decides "changed" by comparing the incoming `assertions` with
`state.rules.map(ruleToJson)` in `prepare`, **before** the adopt (after it, `ruleToJson` passes the
new document's unknown keys through and a retired key compares equal). Unchanged — every Server-tab
component write — leaves the rules exactly as they were, the same objects, Java's results and the
NEW/EDITED markers included; changed, it marks each rule against `state.loadedRules` (N5, rules
sharing an id paired by occurrence, as `loadedEntryFor` pairs them) and calls `recomputeDraftGate`.
No caller recomputes after `replace()`: the Server tab's four component writes never did, and every
client row read NOT EVALUATED under a header saying FAIL. The server applier (`adoptBlock`) drops
the rows and seeds the last render cached, which were the old block's.
`js-unit/document-replace.test.js` holds every registered slice to the same snapshot (keys sorted:
meaning, not an evaluator's key order) across `replace()` of its own text, and the client slice to
the very same rule objects and draft gate across a write that left its block as it was — the one
thing that tells the guard from an applier re-judging on every write.

**An Undo is offered only while the document is as its action left it** (F5). Every Undo restores a
document captured before its action. `WIR.document` keeps a `revision`, bumped by `persist()`
(before its early return), `replace()` and `discard()`; `showUndoToast` stamps it, the toast is
withdrawn the moment it moves (`withdrawStaleUndoToast`, focus to the toast's `back()`, never
`<body>`), and a click after a move writes nothing. Filters, sorts, expands and tab switches never
persist, so they keep the Undo. This replaced SF44's per-component owner (`hideUndoToast`), which
covered one edit on one of the five toasts.

"Unchanged" is decided on **meaning**, not on text. The page ships Java's re-serialisation of the
file (`GatePayloadBuilder.documentOf`: model key order, `version` and `testNotes` filled in, absent
record fields written as `null`), so a text comparison read the very file the report was generated
from as 14 unsaved changes. A record's key order and null-versus-absent are not edits; a map keeps
its keys and an array keeps its order, so reordering asserts still is one. A band is a map, on both
tabs (`MAP_PATHS`: `assertions[].warn`/`fail`, `serverMetrics.asserts[].warn`/`fail` — Jackson binds
each to a `Map`), so `{"gt": null}`, `{"lt": null}` and `{}` are three bands: read as a record all
three meant `{}`, and an operator change on a refused band counted 0 changes on a rule marked EDITED
(CN22). An absent top-level key
reads as its default on every comparison, the blocks no slice owns included — a file with no
`version` is version 1.0, not a change. Compare document content through `WIR.document.meaning`.

### Outputs

`<name>.html`, `<name>.json` (CI/CD summary with the assert verdict), `<name>.xml` (JUnit, when
`-Jwebinsight.report.junit=true`), and `web-insight-data.js` for large runs. The `.json`
and `.xml` names are derived from the resolved HTML name.

**P2 (the run tells the operator).** Every `.json` carries `window` (ISO and epoch-ms start/end, the
collector's edges) and, whenever server asserts are declared, `serverData` {state, series[]} — both
from `ServerGateEvaluation.requiredSeries()`, which the console's `WEB-INSIGHT-REPORT: NEXT` line
(`ReportArtifacts.nextStepsLine`, both paths, stdout and INFO) reads too. chartData carries
`runWindow`; the interim Server tab's `finishHtml()` prints the window, the names table (with each
tool's catalogue name) and a CSV template written by its own writer (`csvTemplateText`, never
`csvQuote`, which corrupts `=`/`+`/`-`/`@` cells). Adding `window` moved every golden once.

On the client side the `.json` carries `assertViolations[]` (every sampler that did not pass; the
whole run's entry keeps `"sampler": "Whole run"` and adds `"scope": "overall"`, CORE-1, because a
sampler can be called that — `SamplerVerdicts.Verdict.isWholeRun()`), `gate` and `assertions[]`,
whose `attainment` (`actual`, `failThreshold`, `consumed`, `breached`) describes one match. **There
is one definition of a rule's worst match, `RuleResult.worstJudged()`** (L13): the highest severity,
then the furthest past the band that status was decided against — a FAIL's fail band, a WARN's warn
band, a PASS's fail band (else its warn band) — the earliest on a tie (NIT-2: it took the fail band's
direction for every status, so a corridor's worst WARN was the one nearest to passing).
The JUnit message, `Attainment.of`, the LOAD_NOT_ACHIEVED sentence and, on the page, `worstMatch`
(the Actual and Budget cells) and the server mirror's `worstJudged` all read it. Severity first,
because a WARN message naming a PASS contradicts itself; where a rule's bands point one way it is
just the worst value, which is what `attainment` used to take on its own while the message named the
first FAIL. The `.xml`'s client lines put a space between a number and its unit (L10:
`mean 610.50 ms on GET /a breached > 20 ms`), and the per-sampler suite names every failing metric
(L11): p95, p99, mean and errorRate in their legacy words, any other from the match its status came
from (`SamplerVerdicts.Verdict.evidence`) — never a bare `ASSERT FAIL:`.

When server asserts were evaluated, the `.json` also carries `serverStatus`, `serverComponents[]`
(one entry per component: `id`, `label`, `type`, `collectionId`, `status`, `declared`,
`evaluated`, `passed`, `warned`, `failed` and `notJudged{...}` — the same block of counts as
`gate`; the reserved `(no gate)` entry has no `type`, and its `label` is `(no component)`) and
`serverAsserts[]` (one per assert, with its `component`, its `metric` when it names one, `query`,
`reduce`, `actual`, `coverage` and
`instances`); the `.xml` carries one `<testsuite name="<testName> - <componentId>">` per
component, whose testcase body lists `component:`, `metric:` (when named), `query:`, `reduce:`
(when the reducer parsed), `divideBy:` (for a ratio) and `criteria:`, and the root counts stay the
sampler suite's. When
server asserts were declared and nothing was judged (interim, or a refused payload), the `.json`
carries none of that and the `.xml` carries one `<testName> - serverMetrics` suite instead (above). A verdict
number there is in `ServerKpi.verdictUnit()` — the declared unit, `/min` added for a `slope`,
` ÷ <rule>` for a `divideBy` — which the page mirrors; the JSON's `unit` stays the declared one
(SF16). `server-metrics.json` is not an output: the
collector writes it, and the report reads it.

### The publish path

All four are staged, then renamed into place together. `AtomicFileWriter.stage` writes
`.<target>.<owner>-<pid>-<nonce>.tmp` beside the target — the **owner** is a sanitised,
filename-safe host/container token, and it is in the name because a pid only means anything
inside one PID namespace: two containers sharing a bind mount were both pid 296-308, and each
swept the other's *live* staging files. The sweep never asks about the pid in a name whose owner
is not this JVM's: such a file is removed only once it is past the six-hour grace below
(`AtomicFileWriter.isStaleStaging`). `ArtifactPublication` collects the set and
`publish()` moves them, payload before the HTML that loads it. The set is **not** atomic — no
filesystem replaces N files at once — so `PublishLock` (a `FileChannel.lock` on
`<outputDir>/.web-insight-publish.lock`, plus a `ReentrantLock` per path because `FileLock` is
held by the JVM, not the thread) closes it in practice, and `RunIdentity` covers the residue.
A new artifact goes through `publication.stage(...)`, never a direct write.

Three things that look like bugs and are not: the lock file is never deleted (unlinking it while
another process holds that inode gives two holders); the sweep predicate is **process liveness
first, not age** — an age-only rule deletes a live writer's staging file on a slow disk — with a
six-hour `PID_REUSE_GRACE_MILLIS` backstop *underneath* it, which only ever fires on debris
whose pid the OS has since recycled onto something unrelated, and which no legitimate staging
file outlives; and a lock that cannot be taken warns and publishes anyway (refusing to write a
report over a mutual-exclusion optimisation is the worse outcome, and identity still catches a
mixed set).

The price of the first is that `.web-insight-publish.lock` stays in the output directory, and
`rm -rf dir/*` does not match dotfiles — so a directory that looks empty can still be refused by
`jmeter -g -o`, which requires an empty one. `rm -rf dir` rather than `dir/*`. It cannot move to
the system temp directory: two containers sharing a bind mount share the output directory and
nothing else, which is the deployment the lock was measured against. Staging debris is different
and *is* swept — `sweepStaleStaging` lists `.*.tmp` across the whole directory rather than only
the current target's name, because a `${timestamp}` target is a different name every run and
nothing would ever have collected the previous one's.

`RunIdentity.of(reportData)` — 16 hex, SHA-256, explicit UTF-8 — is in all four artifacts and is
the contract *same JTL ⇒ same runId; distinct executions ⇒ distinct runId*. Both ingestion paths'
exists-policy and the template's payload check key on it, so a metadata change that breaks the
contract breaks both; that is what `RunIdentityTest` fails loudly about. In the template the
check is **fatal**: a foreign payload withholds every chart rather than drawing it under a
banner.

`failonerror` now defaults to **true** and covers a partial failure (report written, `.json` or
`.xml` missing). The scenario matrix moved with it in the same commit: C19 and C27 assert a
non-zero status by default, and C19's second run passes `failonerror=false` — the pair still
proves the flag does something, approached from the other side. The exit-0 pins that remain are
exemptions rather than stragglers and each defends itself in a comment: C21, where a rejected
filename falls back to the default and the full set is still produced, and B12, where a disabled
listener plans no artifact at all. Nothing was attempted, so there is nothing to fail on — do not
"fix" either to match C19.

It also covers **a gate that could not be built from what the file declared** — a rule naming an
unknown metric, an unreadable target, no bands, or an annotations file that would not load. That
is a *configuration error, not a verdict*, and the distinction is the whole point: a broken rule
contributes nothing to `gate.status` (it is `NOT_JUDGED / INVALID_RULE`) and still exits the
process non-zero, because the file asked for a check that never ran. A gate that genuinely
**FAILED** exits 0 — that is a working gate returning an answer, and `failonassert` is how you
ask for it to be fatal. **C33 run 4 is the only thing holding that line**; without it this
change degrades into "anything red fails the build", which is not what was agreed.

`ReportArtifacts.hasConfigProblem()` is deliberately *not* folded into `anyFailed()`: that one
means "a planned artifact failed" and builds `incompleteReason()` from artifact names. Both
ingestion paths go through `NonZeroExit.arrange(flag, reason)` rather than throwing — the
exporter *can* throw and JMeter would exit 1 on its own, but that renders a typo'd metric name
as twelve frames of JMeter internals, and a stack trace the suite has to expect is one
`log_has_stacktrace` can no longer notice. The `CONFIG ERROR` marker prints whether or not the
flag is on: the opt-out buys back the exit code and nothing else.

**`report/generator/ExitPolicy` owns both exit flags, on both paths.** `failOnError` (default
**true**) and `failOnAssert` (default **false**) each read the property, apply the default and
emit the unrecognised-value warning in one place; a path passes only its own reporter — a
logger on the listener, stderr on the exporter — which is the one thing that legitimately
differs between them. It exists because the duplication delivered exactly the failure it had
been warned about: `failonassert` was read on the listener path and **not read at all** by the
`-g -o` exporter, so one annotations file and one FAIL verdict exited 1 under `-n -t` and 0
under `-g -o`, and nothing noticed because the scenario covering the flag only runs the
listener. A path can no longer forget a flag without deleting a call. Spell the properties as
`NonZeroExit.FAIL_ON_ERROR` / `FAIL_ON_SERVER_ERROR` / `FAIL_ON_ASSERT`, never as literals at a call
site. `failOnServerError` (default **false**) is the third: it alone decides whether a server-side
config problem exits non-zero.

Only `true` and `false` are accepted, so `failonerror=0` is read as saying nothing and the
default applies — which for this one property is the *failing* direction, unlike `junit` and
`externaldata` where an unrecognised value falls through to off. That asymmetry is why
`BooleanSetting.warningFor` takes the consequence clause from its caller instead of writing one
sentence for every flag.

### Configuration precedence

`-J` properties override GUI fields, which override defaults. Comparison thresholds layer
further: defaults → `report-annotations.json` → `-J` properties, in `ComparisonThresholdResolver`.
Annotations and `baseline.jtl` are auto-detected in the output directory when not configured
explicitly. The server-metrics payload is not: it comes from `-Jwebinsight.report.servermetrics`
only.

That resolver is extracted rather than inlined for two reasons, and the second is the real one.
It used to be a private method with **one `try` around all five `webinsight.compare.*` reads and
a silent catch**, so a malformed *first* value abandoned the other four — you asked for five
thresholds, got none, and were told nothing. Each property is now parsed independently and a bad
one — a non-finite one included: `Infinity`, `NaN` and an overflowing `1e400` all parse (L5) —
warns and keeps the configured-or-default value. And being a class means it can be tested:
the layering had no coverage at all, so the whole thing could have been deleted with every suite
green. `ComparisonThresholdResolverTest` pins the layering; **C34** pins the property *names*
surviving a real JMeter process, which a unit test cannot see.

## Conventions

**Version lives in one place** — `<revision>` in the parent `pom.xml`. Both module poms
use `${revision}` in their `<parent>` block and the shell scripts parse that property.
Never hardcode a version in a module pom. The flatten plugin rewrites the placeholder in
installed/deployed poms, since `${revision}` only exists inside this build.

**Shading is an allowlist, not a blocklist.** Every bundled third-party library needs a
`<relocation>` under `com.jmeterwebinsightreport.shaded` — the fat JAR lands in JMeter's
flat `lib/ext` classloader and would otherwise collide with another plugin's copy.
`verify_shaded_jar` fails the build on any class outside
`com/jmeterwebinsightreport/|static/|templates/|META-INF/`, so a new dependency added
without a relocation is caught at build time. `jmeter-report-core` is deliberately *not*
relocated — the listener and exporter class names appear in JMX files and
`reportgenerator.properties`.

**Scripts: `.sh` is the logic, `.bat` is a thin wrapper.** Never add logic to a `.bat`.
Shared helpers live in `scripts/lib/common.sh`, which is sourced by every runner and
resolves platform differences (BSD vs GNU `stat`/`md5`, missing `readlink -f`) once. The one
deliberate exception is `scripts/wir-collect.sh`: it is a product script, not a runner, and
must work as a lone copy beside a released JAR, so it carries its own `JMETER_HOME` resolution.

**Every text blob is LF, and `verify_line_endings` keeps it that way.** 26 files —
`report.js`/`.css`/`.html`, two Java sources, both grouping `.jmx` files, all three poms,
most of `docs/` — predated `.gitattributes` and kept CRLF blobs for as long as nobody
checked. `eol=` governs the **working tree only**; the blob is LF regardless, which is why
the `.bat` files pinned `eol=crlf` are CRLF on disk and LF in git.

That mattered because git hides a CRLF blob behind its stat cache: the file looks clean
until something touches it, and then the clean filter normalizes the checked-out copy to
LF, it stops matching the blob, and it reads as permanently modified. Staging it turned a
158-line change into an 11,566-line diff. A CRLF blob and a clean working tree cannot
coexist under `eol=lf` — discipline could not fix it, only LF blobs could.

Nothing special is required when editing these files now. If a CRLF blob ever gets back in
— `git hash-object --no-filters` and `git update-index` will do it — S03/R03 fails with
`[EOL]` and names the file. Fix with `git add --renormalize <file>`.

## Plans

`plans/` is a working folder — design notes and to-do lists, kept for the thinking, not as a
tracked part of the delivery flow. Nothing enforces anything about it: no static check reads
the folder, there is no archive step, and a status header on a plan is a note the author left,
not a claim the tree verifies. Treat a plan as what was believed when it was written — the
runners, the tests and git history are the record of what actually shipped.

(A `verify_plan_headers` check was once documented here as if it existed; it was never
implemented, and the description was removed rather than the check built. Policing a
scratch folder is not worth a gate.)

## Key Directories

```
docs/                        Docsify documentation site
docs/README.md               Its homepage; index.html sets relativePath: true, and _sidebar.md links are root-absolute (it renders on every page)
docs/schema/                 Published copy of the annotations schema; js-unit/schema-published.test.js holds it identical to the JAR's
docs/Instructions.md         Full test execution guide
docs/FEATURES.md             Feature list (E-numbered, referenced by tests)
docs/TEST-COVERAGE-GAPS.md   Coverage tracker
test-plans/*.jmx             Scenario test plans
test-plans/annotations/      Assert/annotation fixtures used by E10-E13
test-plans/goldens/          R06: plain.jtl, the G1-G10 annotation cases (no G6; G10 = G4 through the in-process CSV route), server-metrics.csv, expected/, jq-gate.txt
test-plans/server-metrics/   E26's committed annotations, CSV server data and payload
test-plans/server-catalogue/ E27's: two catalogue components (postgres, kafka) with D1 data, the picker's fixture
test-plans/server-fixtures/  E28 (a CSV that does not line up, plus real interim and refused pages) and E29 (a generated 4 h soak)
test-plans/playwright/tests/ Browser specs; helpers.ts resolves fixture paths
test-plans/scripts/          Runners (.sh = logic, .bat = wrapper) + Python verifiers
test-plans/js-unit/          report.js unit tests in node — harness.js + *.test.js, no browser
test-results/                Generated output and Playwright fixtures (gitignored)
scripts/lib/common.sh        Shared bash library
scripts/list-scenarios.sh    Generates the scenario list from the runners; --text/--csv/--count
scripts/wir-collect.sh       The collector (step 2); self-contained, .bat is its wrapper
.githooks/pre-push           Refuses a direct push to the primary branch. Runs NO tests
.github/workflows/ci.yml     Fast tier: mvn verify + report.js unit tests + the static checks
docker/                      Dockerfile for containerized JMeter
```

The suite tiers live in `common.sh` (`FIXTURE_IDS`, `fixtures_current`) and
`run-all-tests.sh` (`should_run`). `FIXTURE_IDS` must stay in step with
`REQUIRED_FIXTURES` in `test-plans/playwright/global-setup.ts` — they are the same
list from two directions, and a fixture added to one but not the other is either
never generated or never checked. `should_run` derives its tier from `FIXTURE_IDS`,
so a new fixture needs three edits, not four: the `run_test` line, `FIXTURE_IDS`,
and `REQUIRED_FIXTURES`.
