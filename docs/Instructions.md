# How to Run All Tests

The plugin has six test layers: Java unit tests, `report.js` unit tests, Playwright browser
specs, JMeter scenario runs, config/error runs, and Docker runs. This page describes how to run
each.

Test counts are deliberately not written down. Every hardcoded one on this page has been wrong
within a month of being typed, and a stale count reads like a verified figure. Each runner
prints its own real total — `mvn test` prints Surefire's, and `playwright_test_count` in
`scripts/lib/common.sh` asks Playwright.

All scripts are portable bash and run on macOS, Linux, WSL2 and Git Bash. The
`.bat` files are thin wrappers that call the matching `.sh`.

## Start here — the two automated suites

Most of the time you want one of these rather than an individual layer. Both exit
with the number of failed stages.

| Suite | Command | Time | What it covers |
|-------|---------|------|----------------|
| Sanity | `./test-plans/scripts/run-sanity.sh` | ~2 min | Build + shade guard, S02 "Unit tests (Java + report.js)" — every `mvn test` plus the `test-plans/js-unit/` suite in node, S03 static checks (shell syntax, fixture-list agreement, script modes, line endings, NUL bytes, the `report.js` source rules, the release tier, the jq idioms, and the self-tests that feed each guard a known defect), a live JMeter run, the whole Playwright suite against existing fixtures |
| Regression | `./test-plans/scripts/run-regression.sh` | ~2 min, ~5 min when fixtures are stale | Everything above, plus regenerating the report fixtures when they no longer match the source, and R06 — the golden artifacts (see [The golden harness](#the-golden-harness-r06)) |

Regression skips fixture regeneration when the provenance stamps show the fixtures
were built from exactly the source now in the tree — so a change touching no plugin
source costs the same as sanity. `--force-fixtures` regenerates regardless.

The stamp hashes every file under both modules' `src/main`, plus the committed inputs of the two
server fixtures (`test-plans/server-metrics/` and `test-plans/server-catalogue/`), so "did this
change what a report says?" is answered by content, not by a list someone has to maintain. When
regeneration *is* skipped, regression still runs the live E24 smoke — without it a
skipped cycle never launches JMeter and could not notice the plugin failing to load.

Sanity deliberately does **not** regenerate. If your fixtures are stale it fails
stage S05 and points at regression rather than testing an old build.

### What the automated suites leave out

They run the JMeter scenarios the Playwright specs actually open (E02, E05-E12, E19, E25,
E26, E27) plus CMP — the authoritative list is `FIXTURE_IDS` in `scripts/lib/common.sh`, which
is where to check rather than here. The remaining scenarios in `run-all-tests.sh` are
validated only by "an HTML and a JSON file appeared" — except E20, which asserts the
external-data payload was written and referenced. Run the full set, plus the config, error,
Docker, title/JUnit and scenario-family runners (see [Run Everything](#run-everything)), before a
release; the full set is ~5 minutes.

Three easy ones to get wrong. **E19 is a fixture**, the listener half of external-data mode:
`external-data.spec.ts` opens it to assert the payload was written to a sibling `.js` and
referenced by `<script src>` — the one product promise no inline-payload fixture can
exercise. **E25** is the one that renders every rule state but `NO_VALUE` in a single run (PASS,
WARN, FAIL, and NOT_JUDGED for DISABLED, NO_MATCH and INVALID_RULE), with deliberately extreme thresholds
so those states land identically on any machine. (E10, E11 and E12 carry `assertions` arrays
too.)

**E26 is the Server tab's fixture, and the one scenario built entirely from committed
inputs** — `-g -o` over the goldens' `test-plans/goldens/plain.jtl`, with its own annotations
(`e26-server.json`), server data (`e26-metrics.csv`) and the payload `wir-collect --csv`
collected from it (`server-metrics.json`) under `test-plans/server-metrics/` — its `README.md`
says how to regenerate the payload. That is forced, not convenient: a live run's `runId` is a
fingerprint of the run, so the report refuses a committed payload against it, and the committed
CSV's fixed timestamps share no milliseconds with a live window, so collecting at run time
would put coverage at zero. Every number on the tab is therefore a constant a spec can assert
on, and it renders every state a judged board can show but one — two `custom` components, `kafka`
and `db`, and a server assert for PASS, WARN, FAIL and six of the seven `NOT_JUDGED` reasons
(`NO_VALUE` is the missing one). Being a step-3 report, it cannot show the interim or refused
states (`NOT EVALUATED` rows, `Server · NOT COLLECTED` / `REFUSED`). Its `SOURCE_FAILED` comes from
one CSV row whose time has no zone: that series fails, the others come back, and the source reads
`PARTIAL`. Beside its two client load rules it carries four near-band client rules — means the
client's rounding put on or across their bands, three within a hundredth and the whole-run mean
0.09 under its whole-ms band — that `client-numbers.spec.ts` holds every Asserts-tab surface and the
Budget bar to; no server assert names them, so `runId` and every `collectionId` are unchanged by
them. That directory is in the fixture stamp, so editing the CSV regenerates every fixture. It runs with
`failonerror=false`, because one of its asserts is an `INVALID_RULE` on purpose.

**E27 is E26's twin for catalogue components**, built the same way from the committed inputs under
`test-plans/server-catalogue/`: two catalogue components, `orders-db` (`postgres`) and `events`
(`kafka`), one assert each, and every other catalogue metric the committed CSV carries collected
beside them — which is what the metric picker, its thresholds step and Generate read. It also runs
with `failonerror=false`, and the runner checks that `serverCatalogue` and an unasserted catalogue
metric reached the page.

**Never pass `-Jwebinsight.report.title` to E26 or E27** (or to anything built from their inputs,
such as C38). On the `-g -o` path the title is the run's test name and is hashed into `runId`, so setting
it makes the committed payload another run's — the server block is withheld, JMeter still exits 0
under E26's `failonerror=false`, and only the runner's check that `serverGates` reached the page
stands between that and every Server-tab spec opening a report with nothing on it.

There is no stress scenario that only adds threads, and no E18. No test plan contains an HTTP
sampler, so a higher thread count generates no load — only more rows, and never enough to reach
the 10 MB `webinsight.report.externaldata.threshold` that would make the volume mean something.
E20 forces the external-data path instead (`-g -o`), and E19 is its listener half.

Scenario durations are never shortened to save time: several specs assert on chart
granularity and bucket counts, which are derived from test duration, so a shortened
E02 would change what the fixtures mean and break specs for reasons unrelated to
the code under test.

### Automatic execution

```bash
./scripts/install-hooks.sh
```

Sets `core.hooksPath` to `.githooks/` and populates `origin/HEAD`. Two hooks each
enforce one rule, and neither runs tests:

| Hook | Behaviour |
|------|-----------|
| `pre-commit` | a commit **on** the primary branch is **refused**, so the change is still only in the working tree and the fix is one `git switch -c`. It stands down on a detached HEAD and inside a merge, rebase, cherry-pick or revert |
| `pre-push` | a push that touches the primary branch is **refused** — main changes via a pull request, never a direct push. Any other push is allowed; the full regression is expected to have already run, before the push |

The full regression runs *before* a push, driven by the `run-tests` skill
(`.claude/skills/run-tests/`) — Claude runs it automatically when finishing a
feature or fix. ~2 min normally, ~5 when a source change genuinely invalidated
the fixtures. Without Claude, run `./test-plans/scripts/run-regression.sh` by hand.

Overrides: `WIR_ALLOW_MAIN_COMMIT=1`, `WIR_ALLOW_MAIN_PUSH=1`, or git's own `--no-verify`.

**CI runs the fast half, and only that.** `.github/workflows/ci.yml` runs on every push and
pull request, in about a minute and a half: `mvn -B clean verify` (so a broken shade
relocation fails there too), the `report.js` unit tests via `run_js_unit_tests`, and the
static checks — `verify_shaded_jar`, a `bash -n` sweep over every runner,
`verify_script_modes`, `verify_line_endings`, `verify_no_nul_bytes`, `verify_report_js_rules`,
`verify_jq_idioms`, the fixture-list agreement check, and the `selftest_*` guards. All of it
is machine-independent work, which is the entire criterion for what belongs there.
`ci.yml` itself is the list; trust it over this paragraph.

**The jq idiom check.** The jq lines a pipeline gates with — a DEFAULT and a STRICT line — live
in exactly one file, `test-plans/goldens/jq-gate.txt`, which the docs copy from. Its `CASES` line
names golden CI/CD documents (G1–G5, G7 and G8) and each idiom line states the answer it must
give over each one. `verify_jq_idioms` runs every line with `jq -e` over those goldens and fails on
any answer that differs; `selftest_jq_idioms` feeds it a known-wrong idiom (one that errors on a
client-only run), a malformed line, and a one-clause mutant of each clause of the real lines, and
demands a refusal for each — so the goldens cannot stop discriminating without saying which clause went
blind. Both open with `command -v jq || return 0`, as `verify_shaded_jar` does with `jar`: on a
machine without jq they print that they skipped and pass. CI's runner has jq, so that is where
they are enforced; locally, `test-plans/js-unit/jq-gate.test.js` runs a JavaScript translation of
each line over the same goldens and refuses to pass once a line in the file no longer matches the
text it was translated from.

**There is deliberately no `regression.yml`.** Even with the fixture stage parallelised, a
full regression took **15m48s** on a GitHub runner against ~1½ minutes locally, because the
scenarios are wall-clock bound and a shared 2-core runner cannot overlap them the way a
developer machine can. That tier runs where it is fast — before the push, via the `run-tests`
skill — and no required status check may be slower than the fast job.

The consequence worth knowing: nothing that needs JMeter, a fixture or a browser runs on the
server, and the pre-push hook still runs no tests. A push made without the regression suite
reaches the remote with only the fast tier behind it. `./scripts/install-hooks.sh` is still
not optional: without it, nothing blocks a commit on main or a direct push to it.

The branch is never hardcoded — it is resolved from `origin/HEAD`, falling back to
asking the remote, so a rename or a fork with a differently-named primary branch
needs no edit.

## Prerequisites

- Java 21+ (the build targets 21; a newer JDK works)
- Maven 3.8+
- JMeter 5.6.3 (macOS: `brew install jmeter`)
- Node.js 18+ (for Playwright and the `report.js` unit tests, which every suite runs)
- Docker (for container tests)
- Python 3 (for the golden harness, data integrity and large JTL generation)

## 1. Build & Deploy Plugin

Before running JMeter execution tests, build the plugin and deploy to local JMeter:

```bash
./scripts/build-and-deploy.sh
```

This does four things:
1. Builds both Maven modules.
2. Removes older plugin JARs from `$JMETER_HOME/lib/ext/`, which is one flat classpath, so two
   builds side by side means nobody chooses which one JMeter loads. If one cannot be removed (on
   Windows, usually because VS Code's Java language server holds it open) the deploy **fails**,
   naming the file and printing the PowerShell line that moves it out:
   `Move-Item -LiteralPath '<jar>' -Destination $env:TEMP -Force`. Run it, then deploy again.
3. Copies the shaded JAR into `$JMETER_HOME/lib/ext/`.
4. Registers the `-g -o` CLI exporter in `$JMETER_HOME/bin/reportgenerator.properties`
   (without this, `jmeter -g … -o …` produces only JMeter's own dashboard).

Only `jmeter-web-insight-report-*.jar` is deployed — it's a shaded fat JAR containing all dependencies.

**Environment detection.** Nothing is hardcoded. `JMETER_HOME` resolves in this
order: the env var → a project-local `apache-jmeter-*/` → `jmeter` on PATH
(following symlinks, so Homebrew's wrapper resolves to its `libexec`). The
plugin version is parsed from the parent `pom.xml`. Override as needed:

```bash
JMETER_HOME=/opt/apache-jmeter-5.6.3 ./scripts/build-and-deploy.sh
```

## 2. Java and `report.js` Unit Tests

```bash
mvn test
```

Or by module:

```bash
# Core only
mvn test -pl jmeter-report-core

# Report module only (install core first)
mvn install -N -q && mvn install -pl jmeter-report-core -DskipTests -q
mvn test -pl jmeter-web-insight-report

# Specific test class
mvn test -pl jmeter-report-core -Dtest=JtlParserTest
```

**Some of the test classes** (`mvn test` runs every class under both modules' `src/test/java`):

| Module | Class | Covers |
|--------|-------|--------|
| core | JtlParserTest | JTL parsing, edge cases, transaction controllers |
| core | LiveStatisticsEngineTest | Aggregation, Apdex, body truncation, thread safety |
| core | ComparisonResultTest | Regression detection, custom thresholds |
| core | ComparisonThresholdsTest | Threshold defaults, isEnabled() |
| core | TDigestPercentileCalculatorTest | Percentiles, CDF for Apdex |
| core | FormatUtilTest | Duration, bytes, percentage formatting |
| core | SamplerFilterTest | Include/exclude patterns |
| core | AssertionParityTest | The Java evaluator against `assert-parity-vectors.json` |
| core | UsabilityTest | Which client rules can decide anything (`INVALID_RULE`) |
| core | DigitsTest | How a number is written beside a band, against `digits-vectors.json` |
| core | KpiUsabilityTest | Which server asserts can decide anything |
| core | CatalogueTest | The server metric catalogue's type files |
| core | CsvSourceTest | The collector's `--csv` backend and its per-line errors |
| core | RunIdentityTest | `runId`: same JTL, same id; distinct runs, distinct ids |
| report | AnnotationLoaderTest | JSON loading, validation, thresholds |
| report | AnnotationsSchemaTest | The shipped JSON Schema against the beans the loader binds |
| report | MapperParityTest | The production rule mapper against the parity vectors |
| report | ServerParityTest | The server evaluator against `server-parity-vectors.json` |
| report | ExitPolicyTest | `failonerror` / `failonassert` on both ingestion paths |
| report | ComparisonThresholdResolverTest | `webinsight.compare.*` layering |
| report | ChartDataBuilderTest | Histogram, response codes, heatmap |
| report | CiCdJsonSerializerTest | JSON output, Apdex, asserts |
| report | ReportDataSerializerTest | Chart data serialization |
| report | ThymeleafReportRendererTest | HTML rendering |
| report | JunitXmlWriterTest | XML structure, assert failures |
| report | WebInsightDataExporterTest | -g -o collision check, filename resolution |
| report | WirCollectTest | The `wir-collect` collector end to end over the goldens JTL |

### `report.js` unit tests

The pure half of `report.js` — parsers, formatters, the client and server evaluators — runs in
node, with no browser, no fixture and no build, in about a tenth of a second:

```bash
# One file
node test-plans/js-unit/asserts-parity.test.js

# All of them, as S02/R02 and CI run them (fails loudly if node is missing)
bash -c '. scripts/lib/common.sh && run_js_unit_tests'
```

`run_js_unit_tests` runs every `test-plans/js-unit/*.test.js`.

## 3. Playwright Browser Tests

```bash
cd test-plans/playwright
npm ci               # First time only — installs exactly the lockfile
npm test             # Run all tests (headless)
npm run test:ui      # Interactive UI mode
```

Use `npm ci`, not `npm install`: the lockfile is tracked, and `ci` installs precisely
what it pins, so the Playwright you run is the one recorded rather than whatever the
`^1.58.0` range resolves to today.

Do not reach for `npx playwright test` — under Git Bash npx fails to resolve the local
install, and `.bin/playwright` is a POSIX shim node tries to require as a module. The
`npm run` scripts above call `node node_modules/@playwright/test/cli.js` directly, which
works on every platform.

**Requirements:** JMeter execution tests must run first to generate the HTML reports that Playwright tests against.

The specs load `test-results/<ID>/<ID>-report.html`, and `test-results/` is
gitignored — so rebuilding the plugin does **not** refresh them. After any change
to the report output you must regenerate the fixtures, or the specs will keep
asserting against a report from an older build:

```bash
./test-plans/scripts/run-all-tests.sh          # E02, E05-E12 and the rest
./test-plans/scripts/generate-cmp-fixture.sh   # CMP — needed by the Compare tab specs
```

`CMP` is not produced by `run-all-tests.sh`: it needs the same plan run twice so
the second run has a baseline to compare against, which is what the second script
does.

You do not have to remember this. Each generated report carries a `.fixture-stamp`
recording the md5 of **every file under both modules' `src/main`**, and of the server fixtures'
committed inputs under `test-plans/server-metrics/` and `test-plans/server-catalogue/` — not a
hand-picked list, so a change to `core/stats` invalidates the fixtures as surely as one to
`report.js`. The hashes are computed in one pass and compared as whole listings. Playwright
checks the stamps before it starts and **refuses to run** if anything has changed since the fixtures were generated,
naming the file that moved. That includes a file *added* under a stamped directory, an
untracked one too: Playwright and the runners compare the same listing whole. To override, set
`WIR_SKIP_FIXTURE_CHECK=1`.

A stamp is written only beside a report the run itself wrote. Every scenario in
`run-all-tests.sh` and the CMP script empty their output directory before running, believe the
directory over `rm` (a report still there afterwards, as a Windows lock leaves it, is a FAIL
that is neither run over nor stamped), and stamp only when the report exists afterwards, whatever
JMeter's exit code. A scenario whose JMeter renders nothing is therefore a FAIL with no report and
no stamp, never a pass on the previous run's report under a fresh stamp.

The stamp also records which JAR rendered the report: for every resource under the modules'
`src/main/resources` (the template, `report.js`, `report.css`, ECharts, the schema, the server
catalogue; not `META-INF/`, which the shade plugin rewrites), the md5 of that file inside the
plugin JAR that was in `lib/ext`. A fixture whose JAR does not hold the tree's copy of each is
refused, naming the files, as "rendered by a JAR whose static/css/report.css is not the tree's (…)
— redeploy and regenerate". A stamp without that record counts as stale. The runners
also count every fixture stale while `lib/ext` holds more than one plugin JAR, because JMeter's
classpath is flat and nobody chooses which one loads, and `SKIP_BUILD=1` runs refuse to start on
two JARs. Where `unzip` is not installed, the JAR comparison is skipped and the run says so.

Only the Java is beyond it: a JAR that differs from the tree in its classes alone still stamps
current, because compiled classes cannot be compared with sources. Rebuild and redeploy after a
Java change before regenerating.

The check is on content rather than timestamps deliberately — an mtime comparison
would call every fixture stale after any rebuild, even one that changed nothing, and
a check that cries wolf just teaches people to bypass it permanently.

**Spec files:**

Every file under `test-plans/playwright/tests/`; `playwright_test_count` gives the number of tests.

| File | What it tests |
|------|---------------|
| header-global | Dark mode, colorblind, badges, persistence |
| tab-navigation | Tab switching, Filters dropdown, row collapse |
| summary-tab | Metric cards, Apdex, chart, Top 5, Copy Summary |
| verdict-block | The Summary tab's Copy Verdict |
| timeline-tab | 9 charts, hide/show, fullscreen |
| chart-aggregation | Metric-aware bucketing at coarser granularity, the Σ Total line |
| client-time-axis | Client chart time labels at a phone's width |
| samplers-tab | Sort, search, columns, rows, detail expand, TC toggle |
| sampler-table-walkers | A row's chart panel and judging row stay with it through sort, filter and toggles |
| column-resize | Sampler table default column layout, resizing, auto-fit |
| table-sort-and-toast | Sampler table text sort, column order restore, copy feedback |
| errors-tab | Error charts, tables, expand, sampler filter |
| error-details-grouping | The Error Details "By time" / "Grouped" toggle |
| error-truncation-note | Truncation captions on the error charts |
| filter-controls | Palette, line, metric, granularity, sampler filter, color picker |
| asserts-apdex | Assert badges, Apdex display, annotations |
| asserts-chart-lines | Threshold markLine per metric, label text |
| asserts-tab | Gate strip, rules table, findings |
| asserts-charts | Rule lines, target/visibility filtering, drag handles, placement direction |
| asserts-seeding | ⊕ seeding, sampler badges, per-cell verdicts, the wizard |
| asserts-wizard-dialog | The Generate wizard as a keyboard dialog |
| asserts-editor | The editor form, validation, live preview |
| asserts-filter-sort | Filtering and sorting the rules table |
| asserts-save | Draft model, write-back, round-trip, the document editor |
| asserts-parity | The client evaluator against `assert-parity-vectors.json` |
| client-numbers | A client number beside its band on every Asserts-tab surface and the Budget bar (E26) |
| client-fixes | Client-side asserts fixes, and Undo offered only while the document is as its action left it |
| value-formatting | How a threshold is written into a cell |
| server-tab | The Server tab's board, judged window and charts (E26) |
| server-board | The component board, the Summary server card, Generate with a review |
| server-components | A component the mapper refuses, one the draft no longer declares, one edited or removed on its card |
| server-editor | The server assert editor |
| server-picker | + Add component, the metric picker and its thresholds step (E27) |
| server-seeding | Generate from this run, retuning from the chart |
| server-drafts | The document assembler: both blocks, the unsaved count, drafts |
| server-findings | The findings pane on each tab |
| server-identity | Id-less and duplicate-id server asserts, capped series, retired keys |
| server-numbers | How the Server tab writes a number |
| server-layout | The Server tab at narrow widths and in both themes |
| server-dialogs | Server tab dialogs and the keyboard (E27) |
| server-parity | The browser's server evaluator against `server-parity-vectors.json` |
| keyboard-focus | Focus survives a re-render; a focused select is never rebuilt |
| retired-key-focus | Focus after Remove on a retired key, in both editors |
| csv-export | CSV download content, hidden data included |
| compare-tab | Compare charts, table, badges, metric/filter interaction |
| external-data | The external-data payload: loaded from its sibling `.js`, and the right one (E19) |
| xss-escaping | Escaping JTL-derived text: chart tooltips, clipboard HTML, inline handlers |
| print-and-a11y | Printing realises the charts on tabs that were never opened |
| flow-investigate | E2E: slow sampler investigation |
| flow-customize | E2E: presentation setup + sampler filtering |
| ui-interactions | localStorage, echarts, TC hierarchy, notes, fullscreen |
| advanced-interactions | Drag-drop, zoom, legend, sync, Y-axis units |
| remaining-gaps | Compare thresholds, error JSON formatting, annotations |

**Screenshots & Traces:** captured for failures only — a passing test's trace is never
opened, and capturing every test's fills hundreds of megabytes. Set `WIR_FULL_TRACE=1` to
capture everything while chasing something locally.

```bash
node node_modules/@playwright/test/cli.js show-report ../../test-results/playwright-report
```

## 4. JMeter Execution Tests

```bash
# Full suite
./test-plans/scripts/run-all-tests.sh

# Single test
jmeter -n -t test-plans/quick-smoke-test.jmx \
  -Jthreads_browse=5 -Jduration=30 -Jrampup=5 \
  -Jwebinsight.report.output=test-results/E01 \
  -Jwebinsight.report.filename=E01-report.html
```

**Options of `run-all-tests.sh`:**

| Option | Effect |
|--------|--------|
| `--suite fixtures` | Only the scenarios Playwright opens (`FIXTURE_IDS` minus CMP, which `generate-cmp-fixture.sh` owns) |
| `--suite smoke` | One 5s scenario — proves the plugin loads in JMeter |
| `--suite full` | Every scenario (the default) |
| `--only E26,E27` | Only the ids listed. The other fixtures stay stale, so Playwright refuses to start until they are regenerated or `WIR_SKIP_FIXTURE_CHECK=1` is set. An id the suite never ran fails the run |
| `WIR_JOBS=N` | Scenarios run concurrently, 4 at a time by default; `WIR_JOBS=1` runs them one by one |
| `SKIP_BUILD=1` | Skips the build and deploy and uses the plugin JAR already in `lib/ext` (refused unless exactly one plugin JAR is there) |

**Test scenarios:**

| ID | Scenario | JMX | Duration |
|----|----------|-----|----------|
| E01 | Happy path smoke | quick-smoke-test | 30s |
| E02 | Full load (20+15+10+5 threads) | full-load-test | 120s |
| E03 | Error grouping | error-grouping-test | 30s |
| E04 | Single sampler | single-sampler-test | 30s |
| E05 | Many samplers (20) | many-samplers-test | 30s |
| E06 | Zero errors | zero-errors-test | 30s |
| E07 | 100% errors | all-errors-test | 30s |
| E08 | Long names | long-names-test | 20s |
| E09 | CLI properties | quick-smoke-test | 30s |
| E10 | Asserts pass | asserts-scenario-test + asserts-pass.json | 30s |
| E11 | Asserts fail | asserts-scenario-test + asserts-strict.json | 30s |
| E12 | Full annotations | quick-smoke-test + full-annotations.json | 30s |
| E13 | Markers only | quick-smoke-test + markers-only.json | 30s |
| E15 | Response codes | response-code-grouping-test | 20s |
| E19 | External data mode (listener) | single-sampler-test, `-Jwebinsight.report.externaldata=true` | 20s |
| E20 | External data mode (-g -o, generated JTL) | generate-large-jtl.py, forced external mode | ~25s |
| E22 | Report stress | report-stress-test | 60s |
| E24 | Minimal data | single-sampler-test (1 thread, 5s) | 5s |
| E25 | Assertion rules | asserts-scenario-test + asserts.json — every rule state in one run | 30s |
| E26 | Server metrics | no JMX: `-g -o` over `goldens/plain.jtl` + `server-metrics/` (annotations, CSV data, payload), no report title | — (not a timed run) |
| E27 | Server metrics, catalogue | no JMX: `-g -o` over `goldens/plain.jtl` + `server-catalogue/` (two catalogue components), no report title | — (not a timed run) |

The `run_test` lines in `run-all-tests.sh`, and its E20, E26 and E27 blocks, are the list; trust
them over this table. `./scripts/list-scenarios.sh` (`--text`, `--csv`, `--count`) generates the
list of every scenario a runner records by name, so it shows E20, E26 and E27 from this runner but
not the scenarios launched through `run_test`, and it lists an ID once even where two runners use
it (the family runners reuse C01–C08 and E01–E07).

## 5. Configuration & Runtime Tests

```bash
# Config tests (defaults, paths, JUnit XML, Apdex, ramp-up)
./test-plans/scripts/run-config-tests.sh

# Error handling tests
./test-plans/scripts/run-error-tests.sh
```

C14 waits 61s for the `${timestamp}` placeholder to roll over to a new minute.
Shorten that for a quick local run with `TIMESTAMP_WAIT=5`.

**Config tests (C01-C41 plus C17b; C19 runs JMeter twice, once with `failonerror=false`, and
records one stage):** defaults
(output dir, filename, title, JSON), the `${timestamp}` placeholder, relative and auto-created
paths, special characters, JUnit XML on and off, custom Apdex, ramp-up marking, and from C16 up
the harder configuration edges: property precedence, two reports in one directory, a GUI-set
filename reaching the exporter, a rejected filename, an unwritable CI/CD JSON, a disabled
listener, reserved dashboard filenames and the announced output paths.

C33 through C41 are the least obvious. **C33** holds the line between a
configuration error and a verdict — a rule that could not run exits non-zero, a gate that
genuinely FAILED exits 0 — and its fourth run exists solely so this never becomes "anything red
fails the build". **C34** proves the five `-Jwebinsight.compare.*` properties reach the compare
verdict at all: the property names, spelled as documented, surviving a real JMeter process, which
`ComparisonThresholdResolverTest` cannot see. **C35** runs the
three server-metrics steps through real processes — `wir-collect.sh --recording` over a recorded
Prometheus response, then `jmeter -g -o` with `-Jwebinsight.report.servermetrics` — and proves a
collected `server-metrics.json` is embedded for the run it was collected against and refused for
any other: the payload carries the run identity, so a stale one cannot quietly become this run's
evidence. The refusal is an ERROR finding in the CI/CD JSON and a `SERVER NOT JUDGED` line with
exit 0 by default; under `webinsight.report.server.failonerror=true` it is a CONFIG ERROR and a
non-zero exit, and C35 pins both. It also asserts the collector's console said
the recording was read (`wir-collect: OK app/up coverage=`): the collector exits 0 for an empty
series too, so the exit code alone does not prove it. **C36** collects through the CSV backend
(`--csv`) and pins the `failonassert` fold: a component's FAIL exits 0 by default and 1 under the
flag, and is a JUnit `<failure>` in that component's suite. **C37** pins a collection failure —
`--prometheus-url` on a dead port: the collector exits 0 because it wrote the payload, the
payload records the source `FAILED`, and step 3 says `SERVER NOT JUDGED` with exit 0 (a CONFIG ERROR
and exit 1 under `webinsight.report.server.failonerror=true`), with the assert counted as
`sourceFailed` and written as a JUnit `<failure>`. C37 also runs the three documented steps as one
`set -e` script, with `jmeter` on `PATH`, `JMETER_HOME` unset and a lone copy of
`wir-collect.sh`; the script must reach step 3's report (a collector that exited non-zero would
stop it before the only report that gates), and its step 3 passes
`-Jwebinsight.report.failonerror=false`, which is how a pipeline that wants the report and its own
jq gate keeps a dead source from ending the script. **C38** is the save promise end to end: the document the page saves gates
the next `jmeter -g` exactly as the page showed it — the page's save re-judged by `jmeter -g`. `save-from-page.js` runs the report's own
`report.js` in node over E26's page, saves an untouched document, one after a scripted session
(a client drag, a server drag whose rounding crosses the measured value, an editor retune, a park,
an add), and one where Generate from this run seeded an assert on a freed series, and each is re-judged by
`jmeter -g` over the same committed payload and diffed against the page field by field — every
server assert, every server component, the client gate. A fourth document is an untouched Download
of a page built from G3's `gate-broken.json`, six of whose seven rules Java refuses (the seventh is
the PASS control), re-judged with each refused rule's sentence: a no-op Save must not drop a band
the run could not read, or the next run would pass the rule (C-1, client half). It needs `node` on
`PATH`.
**C39** collects a Grafana export as it is downloaded — `wir-collect.sh --csv --layout wide` over a
CRLF copy of the committed `test-plans/server-catalogue/e27-grafana.csv` with E27's annotations and
the `e27-grafana-columns.csv` map — and checks the payload in node, field by field: two columns read
by their `component/metric[/instance]` headers, one by the map, an empty cell kept a gap; and the
console: the unmapped column listed, the map's entry for a column the export lacks listed, and, at
`--step 250ms` over rows a second apart, the line naming the `--step` that matches. It needs `node`
on `PATH` too. **C40** collects an InfluxDB 2 export as `influx query --raw` writes it —
`wir-collect.sh --csv --csv-columns time=_time,value=_value,metric=_field,instance=host --component
orders-db` over a CRLF copy of the committed `test-plans/server-catalogue/e27-influx2.csv`, an
annotated CSV (`#group`/`#datatype`/`#default` lines, the leading empty column) of two of E27's
`orders-db` series in two blocks whose headers differ, one with an extra tag — and checks the payload in node: both
series and their points, E27's other assert `EMPTY`, and the source recording the `columns` and the
`component` it was read with. It needs `node` on `PATH` as well. **C41** collects a Datadog export
by Datadog's names — `wir-collect.sh --csv --from datadog` over the committed
`test-plans/server-catalogue/e27-datadog.csv` with E27's annotations — and checks the payload in
node: `postgresql.percent_usage_connections`, a 0-1 fraction, read x100 as `connections.used.pct`
(0.4 is 40); `postgresql.deadlocks`, lock/s, read x60 as `deadlocks` (0.05/s is 3 a minute);
`kafka.consumer_lag` read as it is; each series recording the name, kind and scale it was read as
and every point back to E27's own value; and `up`, which Datadog has no single metric for, read
under the catalogue's own name with no `readAs`. It needs `node` on `PATH` too.

**Error handling (N01-N09):** zero samples, unparseable annotations, missing baseline, long
title, empty sampler name, report overwrite, empty title property, and — N08 — a *well-formed*
annotations file whose rules are individually broken. N02 and N08 are not the same test: N02's
file never parses, so nothing reaches the evaluator, while N08's file parses and each of its five
broken rules fails at a different point (an uncompilable pattern, a bad selector key, an unknown
`class=` token, an unknown metric, an unreadable target). N08 asserts that the run exits non-zero
(rules the file got wrong are a configuration error under the default `failonerror`), that the
report and the CI/CD JSON are still written, that the five bad rules are counted as
`INVALID_RULE` (`gate.notJudged.invalidRule` is 5), that the one healthy rule beside them is still
judged, and that no stack trace reaches either log. N09 runs the same rules through the `-g -o`
exporter, over a JTL with errors of every class, and asserts the same, so the two ingestion paths
cannot answer differently for one annotations file.

## 6. `-g -o` CLI Pipeline Tests

Tests generating Web Insight Report from existing JTL files (no listener):

```bash
# Basic
jmeter -g results.jtl -o report-dir/

# With properties
jmeter -g results.jtl -o report-dir/ \
  -Jwebinsight.report.title="From JTL" \
  -Jwebinsight.report.annotations=/path/to/annotations.json
```

**Scenarios:** Basic generation, custom title/filename, annotations via -J, baseline via -J, JUnit XML, collision detection (a report the listener already wrote for the same run is skipped; another run's report is replaced). `run-family-cd-tests.sh` runs the exporter alone and both paths at once (`-n -t … -e -o`).

## 7. Data Integrity Verification

Cross-validates plugin output against JMeter's native statistics:

```bash
# Generate native report from JTL
jmeter -g test-results/E01/results.jtl -o test-results/E01/native-report

# Compare (python3; python or py on Windows)
python3 test-plans/scripts/verify-data-integrity.py \
  test-results/E01/E01-report.json \
  test-results/E01/native-report/statistics.json
```

**Tolerances:** Sample count (exact), mean (±1ms), P95/P99 (±5%), throughput (±2%),
error rate (±0.1pp). Every metric listed here is actually compared — the script's
tolerance table and its comparison map are kept in step.

## 8. Docker Tests

```bash
./test-plans/scripts/run-docker-tests.sh
```

The script builds the plugin, builds the image, and runs these two scenarios in
containers — they are NOT part of `run-all-tests.sh`:

| ID | Scenario | JMX | Duration |
|----|----------|-----|----------|
| E16 | Docker basic | docker-test | 30s |
| E17 | Docker full load | full-load-test | 120s |

`docker/Dockerfile.jmeter` contains only JMeter — the plugin is bind-mounted at
run time, so a code change needs no image rebuild.

To build the image by hand:

```bash
docker build -t jmeter-webinsight-test:5.6.3 -f docker/Dockerfile.jmeter docker/
```

To run a single container test by hand, note the JAR path must be expanded by
the shell — a glob inside quotes is passed to Docker literally and silently
bind-mounts a *directory* instead of the plugin:

```bash
JAR=$(ls jmeter-web-insight-report/target/jmeter-web-insight-report-*.jar)
docker run --rm \
  -v "$PWD/$JAR:/opt/apache-jmeter-5.6.3/lib/ext/plugin.jar" \
  -v "$PWD/test-plans/docker-test.jmx:/test/docker-test.jmx" \
  -v "$PWD/test-results/docker/E16:/results" \
  jmeter-webinsight-test:5.6.3 \
  -n -t /test/docker-test.jmx -l /results/results.jtl \
  -Jwebinsight.report.output=/results -Jthreads=10 -Jduration=30
```

Two platform differences the runner handles and a hand run does not:

- **Git Bash on Windows** rewrites every argument that looks like a POSIX path
  (`/results`, `/test/docker-test.jmx`) into a Windows path before Docker sees it. Prefix
  the command with `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` and give the host side of
  each `-v` as `$(cygpath -m "$PWD")/…`, as `run-docker-tests.sh` does.
- **Linux** keeps the host's ownership on a bind mount, so the container's user must match
  yours or `/results` is not writable: add `--user "$(id -u):$(id -g)"`. Docker Desktop
  (Windows, macOS) translates the mount and needs no `--user`.

## The golden harness (R06)

```bash
./test-plans/scripts/run-goldens.sh            # compare
./test-plans/scripts/run-goldens.sh --accept   # regenerate the baselines
```

The one stage that makes a **silently moved verdict** loud. Every other tier asserts what someone
thought to assert; this asserts that the same fixed input keeps producing the same `.json` and
`.xml`. It drives a committed JTL, `test-plans/goldens/plain.jtl`, through the `-g -o` exporter
once per case, canonicalises the CI/CD JSON and the JUnit XML, and diffs them against
`test-plans/goldens/expected/G*.json` and `G*.xml`. Because the input is fixed, every number is a
constant, and a diff means the code moved a status, a count, an attainment number, a finding
message, a wire name or a key order on an unedited file. It is stage R06, the last stage of
`run-regression.sh`, and needs JMeter with the plugin deployed and Python (it refuses loudly
without either — a golden check that cannot run must not look like one that passed).

| Case | Annotations (in `test-plans/goldens/`) | What it pins |
|---|---|---|
| G1 | none | no gate, no server block |
| G2 | `gate-mixed.json` | a client gate that FAILs, beside an invalid rule |
| G3 | `gate-broken.json` | a client gate that PASSes with every other rule `INVALID_RULE` |
| G4 | `gate-server.json` + payload | client FAIL beside a `kafka` and a `db` component; `serverStatus` FAIL from `kafka` |
| G5 | `gate-server-fail.json` + payload | no client rule; the `kafka` component FAILs |
| G7 | `gate-server-coverage.json` + payload | no client rule; one `db` component reads PASS over a `LOW_COVERAGE` assert — `serverStatus` PASS over a config error |
| G8 | `gate-server-fail.json`, payload missing | refused where a server assert needed it: no server block, one ERROR finding |
| G9 | `gate-digits.json` | client rules only, each band within a hundredth of plain.jtl's measured value: every client `actual` and JUnit number stays on its own side of every band its rule declares (`119.361` beside `"threshold": 119.36`, not `119.36`) |

There is no G6, and case ids are not reused.

G4, G5 and G7 read the committed payload `test-plans/goldens/server-metrics.json`, collected with
`wir-collect --csv` against `plain.jtl` under the default title from the committed
`test-plans/goldens/server-metrics.csv`; `test-plans/goldens/server-metrics.md` says how to
regenerate it. G5, G7 and G8 declare no client rule on purpose: in G4 the client gate already
FAILs and decides both jq idioms on its own, so without them the server half of the idioms would
run over no document where it decides anything. The same goldens are what
`verify_jq_idioms` runs the documented jq lines over.

Three semantics bite immediately:

- **It compares the `.json` and `.xml` only, never the HTML.** The comparison block lives only in
  the HTML, which is why no case runs against a baseline JTL. It does not cover the
  live-listener path either, which is not reproducible.
- **`--accept` regenerates the baselines, and it is a deliberate act.** Commit them **with** the
  change that caused them and say which in the message: the diff then appears in the pull request
  that caused it, which is the review. `--accept` exits non-zero when a case failed to regenerate,
  rather than reporting a partial set as a clean accept.
- **A comparison that passes while `expected/` is dirty in the working tree is a FAIL** — the
  baselines were regenerated rather than the code fixed. The check reads `git status --porcelain`,
  not `git diff`, because a first-ever baseline is untracked and `git diff` cannot see it. So
  re-accepted goldens must be committed before R06 can go green.

`selftest_goldens_accept` (S03/R03 and CI) runs the real runner against a stub JMeter that writes
nothing and demands that `--accept` refuse it.

## Run Everything

To run all tests in sequence:

```bash
# 1. Build + deploy
./scripts/build-and-deploy.sh

# 2. JUnit
mvn test

# 3. JMeter execution tests (generates reports for Playwright)
./test-plans/scripts/run-all-tests.sh
./test-plans/scripts/generate-cmp-fixture.sh

# 4. Playwright (needs reports from step 3)
(cd test-plans/playwright && npm test)

# 5. Config + error handling tests
./test-plans/scripts/run-config-tests.sh
./test-plans/scripts/run-error-tests.sh

# 6. Golden artifacts (R06)
./test-plans/scripts/run-goldens.sh

# 7. Docker (E16, E17)
./test-plans/scripts/run-docker-tests.sh

# 8. Title/JUnit and the scenario families
./test-plans/scripts/run-title-junit-tests.sh   # T-series: the report title and JUnit XML, -J and JMX
./test-plans/scripts/run-family-ab-tests.sh     # A/B: a shared output directory after several runs
./test-plans/scripts/run-family-cd-tests.sh     # C/D: the -g exporter, and both paths at once (-n -t -e -o)
./test-plans/scripts/run-family-e-tests.sh      # E: two JMeter processes writing into one directory
./test-plans/scripts/run-family-fh-tests.sh     # F/H: external-data mode, negative and edge inputs
./test-plans/scripts/run-family-g-tests.sh      # G: Docker containers sharing one host mount
./test-plans/scripts/run-family-ij-extra-tests.sh  # I/J: title and JUnit rows on the -g and both-paths runs
```

The config, error, title/JUnit and family runners do not build or deploy: they run against the
plugin step 1 put in `lib/ext`. The Docker runners build the JAR they mount.

## Cleaning Test Data

```bash
rm -rf test-results/ test-plans/runs/ test-plans/playwright/test-results/
```

All test output is gitignored and regenerated by running the tests.
