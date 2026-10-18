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
| Regression | `./test-plans/scripts/run-regression.sh` | ~2 min, ~4½ min when fixtures are stale | Everything above, plus regenerating the report fixtures when they no longer match the source, and R06 — the golden artifacts (see [The golden harness](#the-golden-harness-r06)) |

Regression skips fixture regeneration when the provenance stamps show the fixtures
were built from exactly the source now in the tree — so a change touching no plugin
source costs the same as sanity. `--force-fixtures` regenerates regardless.

The stamp hashes every file under both modules' `src/main`, so "did this change what
a report says?" is answered by content, not by a list someone has to maintain. When
regeneration *is* skipped, regression still runs the live E24 smoke — without it a
skipped cycle never launches JMeter and could not notice the plugin failing to load.

Sanity deliberately does **not** regenerate. If your fixtures are stale it fails
stage S05 and points at regression rather than testing an old build.

### What the automated suites leave out

They run the JMeter scenarios the Playwright specs actually open (E02, E05-E12, E19, E25,
E26) plus CMP — the authoritative list is `FIXTURE_IDS` in `scripts/lib/common.sh`, which
is where to check rather than here. The remaining scenarios in `run-all-tests.sh` are
validated only by "an HTML and a JSON file appeared" — except E20, which asserts the
external-data payload was written and referenced. Run the full set, plus the config, error
and Docker suites, before a release; the full set is now ~4½ minutes.

Three easy ones to get wrong. **E19 is a fixture**, not a deleted stress test:
`external-data.spec.ts` opens it to assert the payload was written to a sibling `.js` and
referenced by `<script src>` — the one product promise no inline-payload fixture can
exercise. **E25** is the one that renders every rule state in a single run (PASS, WARN, FAIL,
and NOT_JUDGED for DISABLED, NO_MATCH and INVALID_RULE), with deliberately extreme thresholds
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
on, and it renders every state the tab has — two `custom` components, `kafka` and `db`, and a
server assert for PASS, WARN, FAIL and the `NOT_JUDGED` reasons. Its `SOURCE_FAILED` comes from
one CSV row whose time has no zone: that series fails, the others come back, and the source reads
`PARTIAL`. Beside its two client load rules it carries four near-band client rules — means the
client's rounding put on or across their bands, three within a hundredth and the whole-run mean
0.09 under its whole-ms band — that `client-numbers.spec.ts` holds every Asserts-tab surface and the
Budget bar to; no server assert names them, so `runId` and every `collectionId` are unchanged by
them. That directory is in the fixture stamp, so editing the CSV regenerates every fixture. It runs with
`failonerror=false`, because one of its asserts is an `INVALID_RULE` on purpose.

**Never pass `-Jwebinsight.report.title` to E26** (or to anything built from its inputs, such as
C38). On the `-g -o` path the title is the run's test name and is hashed into `runId`, so setting
it makes the committed payload another run's — the server block is withheld, JMeter still exits 0
under E26's `failonerror=false`, and only the runner's check that `serverGates` reached the page
stands between that and every Server-tab spec opening a report with nothing on it.

What *was* deleted is E18 (100 threads, 120s) and the old stress scenario that used to hold
the E19 id (20 threads, 60s). They cost ~3½ minutes between them to assert a file existed.
No spec opened either, and since no test plan contains an HTTP sampler the thread counts
generated no load — only more rows, and never enough to reach the 10 MB
`webinsight.report.externaldata.threshold` that would have made the volume mean something.
E20 covers that path deliberately instead, and the E19 id was reused for the listener half.

Scenario durations are never shortened to save time: several specs assert on chart
granularity and bucket counts, which are derived from test duration, so a shortened
E02 would change what the fixtures mean and break specs for reasons unrelated to
the code under test.

### Automatic execution

```bash
./scripts/install-hooks.sh
```

Sets `core.hooksPath` to `.githooks/` and populates `origin/HEAD`. `pre-push`
enforces exactly one rule, and runs no tests:

| Push target | Behaviour |
|-------------|-----------|
| primary branch | **refused** — main changes via a pull request, never a direct push |
| anything else | allowed — the full regression is expected to have already run, before the push |

The full regression runs *before* a push, driven by the `run-tests` skill
(`.claude/skills/run-tests/`) — Claude runs it automatically when finishing a
feature or fix. ~1½ min normally, ~5½ when a source change genuinely invalidated
the fixtures. Without Claude, run `./test-plans/scripts/run-regression.sh` by hand.

Overrides: `WIR_ALLOW_MAIN_PUSH=1`, `git push --no-verify`.

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
names golden CI/CD documents (G1–G5, G7, G8 — G6 was retired with observe-only gates) and each idiom line states the answer it must give over each
one. `verify_jq_idioms` runs every line with `jq -e` over those goldens and fails on any answer
that differs; `selftest_jq_idioms` feeds it the idiom that used to be documented (it errored on
every client-only run) and a one-clause mutant of each clause of the real lines, and demands a
refusal for each — so the goldens cannot stop discriminating without saying which clause went
blind. Both open with `command -v jq || return 0`, as `verify_shaded_jar` does with `jar`: on a
machine without jq they print that they skipped and pass. CI's runner has jq, so that is where
they are enforced; locally, `test-plans/js-unit/jq-gate.test.js` runs a JavaScript translation of
each line over the same goldens and refuses to pass once a line in the file no longer matches the
text it was translated from.

**`regression.yml` stays deleted, deliberately.** Even with the fixture stage parallelised, a
full regression took **15m48s** on a GitHub runner against ~1½ minutes locally, because the
scenarios are wall-clock bound and a shared 2-core runner cannot overlap them the way a
developer machine can. That tier runs where it is fast — before the push, via the `run-tests`
skill — and no required status check may be slower than the fast job.

The consequence worth knowing: nothing that needs JMeter, a fixture or a browser runs on the
server, and the pre-push hook still runs no tests. A push made without the regression suite
reaches the remote with only the fast tier behind it. `./scripts/install-hooks.sh` is still
not optional: without it, nothing blocks a direct push to main.

The branch is never hardcoded — it is resolved from `origin/HEAD`, falling back to
asking the remote, so a rename or a fork with a differently-named primary branch
needs no edit.

## Prerequisites

- Java 21+ (the build targets 21; a newer JDK works)
- Maven 3.8+
- JMeter 5.6.3 (macOS: `brew install jmeter`)
- Node.js 18+ (for Playwright)
- Docker (for container tests)
- Python 3 (for data integrity + large JTL generation)

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

## 2. Java Unit/Integration Tests

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

**Test classes:**

| Module | Class | Tests | Covers |
|--------|-------|-------|--------|
| core | JtlParserTest | 24 | JTL parsing, edge cases, transaction controllers |
| core | LiveStatisticsEngineTest | 51 | Aggregation, Apdex, body truncation, thread safety |
| core | ComparisonResultTest | 13 | Regression detection, custom thresholds |
| core | ComparisonThresholdsTest | 6 | Threshold defaults, isEnabled() |
| core | TDigestPercentileCalculatorTest | 11 | Percentiles, CDF for Apdex |
| core | FormatUtilTest | 9 | Duration, bytes, percentage formatting |
| core | SamplerFilterTest | 7 | Include/exclude patterns |
| report | AnnotationLoaderTest | 11 | JSON loading, validation, thresholds |
| report | ChartDataBuilderTest | 9 | Histogram, response codes, heatmap |
| report | CiCdJsonSerializerTest | 14 | JSON output, Apdex, asserts |
| report | ReportDataSerializerTest | 18 | Chart data serialization |
| report | ThymeleafReportRendererTest | 9 | HTML rendering |
| report | JunitXmlWriterTest | 15 | XML structure, assert failures |
| report | WebInsightDataExporterTest | 7 | -g -o collision check, filename resolution |

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
recording the md5 of **every file under both modules' `src/main`** — not a hand-picked list.
It used to name five rendering files, which meant a change to `core/stats` left every stamp
matching: regeneration was skipped and every browser test asserted against the previous
build's numbers, green. Widening it also made the check ~29x faster, because the hashes are
now computed in one pass and compared as whole listings. Playwright checks the stamps before
it starts and **refuses to run** if anything has changed since the fixtures were generated,
naming the file that moved. That includes a file *added* under a stamped directory, an
untracked one too: Playwright and the runners compare the same listing whole. To override, set
`WIR_SKIP_FIXTURE_CHECK=1`.

A stamp is written only beside a report the run itself wrote. Every scenario in
`run-all-tests.sh` and the CMP script empty their output directory before running, believe the
directory over `rm` (a report still there afterwards, as a Windows lock leaves it, is a FAIL
that is neither run over nor stamped), and stamp only when the report exists afterwards, whatever
JMeter's exit code. A scenario whose JMeter renders nothing is therefore a FAIL with no report and
no stamp. It used to pass on the previous run's report, freshly stamped, and the browser tests
then checked the previous build.

The stamp also records which JAR rendered the report: for every resource under the modules'
`src/main/resources` (the template, `report.js`, `report.css`, ECharts, the schema, the server
catalogue; not `META-INF/`, which the shade plugin rewrites), the md5 of that file inside the
plugin JAR that was in `lib/ext`. A fixture whose JAR does not hold the tree's copy of each is
refused, naming the files, as "rendered by a JAR whose static/css/report.css is not the tree's (…)
— redeploy and regenerate". A stamp written before the record existed counts as stale. The runners
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

| File | Tests | What it tests |
|------|-------|---------------|
| header-global | 13 | Dark mode, colorblind, badges, persistence |
| tab-navigation | 9 | Tab switching, Filters dropdown, row collapse |
| summary-tab | 16 | Metric cards, Apdex, chart, Top 5, Copy Summary |
| timeline-tab | 8 | 9 charts, hide/show, fullscreen |
| samplers-tab | 18 | Sort, search, columns, rows, detail expand, TC toggle |
| errors-tab | 15 | Error charts, tables, expand, sampler filter |
| filter-controls | 28 | Palette, line, metric, granularity, sampler filter, color picker |
| asserts-apdex | 15 | Assert badges, Apdex display, annotations |
| asserts-chart-lines | 16 | Threshold markLine per metric, label text |
| asserts-tab | 20 | Gate strip, rules table, findings |
| asserts-charts | 27 | Rule lines, target/visibility filtering, drag handles, placement direction |
| asserts-seeding | 38 | ⊕ seeding, sampler badges, per-cell verdicts, the wizard |
| asserts-editor | 30 | The editor form, validation, live preview |
| asserts-filter-sort | 40 | Filtering and sorting the rules table |
| asserts-save | 21 | Draft model, write-back, round-trip |
| asserts-parity | 46 | The client evaluator against `assert-parity-vectors.json` |
| csv-export | 11 | CSV download content, hidden data included |
| compare-tab | 15 | Compare charts, table, badges, metric/filter interaction |
| flow-investigate | 1 | E2E: slow sampler investigation |
| flow-customize | 2 | E2E: presentation setup + sampler filtering |
| ui-interactions | 18 | localStorage, echarts, TC hierarchy, notes, fullscreen |
| advanced-interactions | 10 | Drag-drop, zoom, legend, sync, Y-axis units |
| remaining-gaps | 17 | Compare thresholds, error JSON formatting, annotations |

**Screenshots & Traces:** captured for failures only. Capturing them for every test
produced a 397 MB report directory that CI then uploaded on every run, green or red —
and a passing test's trace is never opened. Set `WIR_FULL_TRACE=1` to capture
everything while chasing something locally.

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

`./scripts/list-scenarios.sh` generates the full list from the runners themselves (`--text`,
`--csv`, `--count`); trust it over this table.

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

C33 through C41 are the newest and the least obvious. **C33** holds the line between a
configuration error and a verdict — a rule that could not run exits non-zero, a gate that
genuinely FAILED exits 0 — and its fourth run exists solely so this never becomes "anything red
fails the build". **C34** proves the five `-Jwebinsight.compare.*` properties reach the compare
verdict at all; they were documented in three places while nothing executed a single one of
them, so the whole layering could have been deleted with every suite green. **C35** runs the
three server-metrics steps through real processes — `wir-collect.sh --recording` over a recorded
Prometheus response, then `jmeter -g -o` with `-Jwebinsight.report.servermetrics` — and proves a
collected `server-metrics.json` is embedded for the run it was collected against and refused for
any other: the payload carries the run identity, so a stale one cannot quietly become this run's
evidence. The refusal is a CONFIG ERROR, a non-zero exit and an ERROR finding in the CI/CD JSON,
because a declared server assert needed the payload. It also asserts the collector's console said
the recording was read (`wir-collect: OK app/up coverage=`): since the collector exits 0 for an empty
series, the exit code alone no longer proves it. **C36** collects through the CSV backend
(`--csv`) and pins the `failonassert` fold: a component's FAIL exits 0 by default and 1 under the
flag, and is a JUnit `<failure>` in that component's suite. **C37** pins the other half of D4 —
`--prometheus-url` on a dead port: the collector exits 0 because it wrote the payload (D27), the
payload records the source `FAILED`, and step 3 is a CONFIG ERROR with the assert counted as
`sourceFailed` and written as a JUnit `<failure>`. C37 also runs the three documented steps as one
`set -e` script, with `jmeter` on `PATH`, `JMETER_HOME` unset and a lone copy of
`wir-collect.sh`; the script must reach step 3's report (a collector that exited non-zero stopped
it before the only report that gates), and its step 3 passes
`-Jwebinsight.report.failonerror=false`, which is how a pipeline that wants the report and its own
jq gate keeps a dead source from ending the script. **C38** is the save promise end to end: the document the page saves gates
the next `jmeter -g` exactly as the page showed it — the page's save re-judged by `jmeter -g`. `save-from-page.js` runs the report's own
`report.js` in node over E26's page, saves an untouched document, one after a scripted session
(a client drag, a server drag whose rounding crosses the measured value, an editor retune, a park,
an add), and one where Generate from this run seeded an assert on a freed series, and each is re-judged by
`jmeter -g` over the same committed payload and diffed against the page field by field — every
server assert, every server component, the client gate. A fourth document is an untouched Download
of a page built from G3's `gate-broken.json`, six of whose seven rules Java refuses (the seventh is
the PASS control), re-judged with each refused rule's sentence: a no-op Save once dropped a band the
run could not read, and the next run passed the rule (C-1, client half). It needs `node` on `PATH`.
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
file never parses, so nothing reaches the evaluator, and every "the rule itself is unreadable"
failure lived outside the suite until N08. It asserts the report and the CI/CD JSON are still
written, that the three bad rules are counted INVALID, and that the one healthy rule beside them
is still judged. N09 runs the same rules through the `-g -o` exporter, where the failure
mode is different — an exception propagates and JMeter exits 1 rather than quietly producing
no report — so it asserts the exit code as well as the artifacts.

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

**Scenarios:** Basic generation, custom title/filename, annotations via -J, baseline via -J, JUnit XML, collision detection (existing report skipped).

## 7. Data Integrity Verification

Cross-validates plugin output against JMeter's native statistics:

```bash
# Generate native report from JTL
jmeter -g test-results/E01/results.jtl -o test-results/E01/native-report

# Compare
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

There is no G6: it was the observe-only gate, retired with `failsBuild` when every component
became one that builds, and case ids are not reused.

G4, G5 and G7 read the committed payload `test-plans/goldens/server-metrics.json`, collected with
`wir-collect --csv` against `plain.jtl` under the default title from the committed
`test-plans/goldens/server-metrics.csv`; `test-plans/goldens/server-metrics.md` says how to
regenerate it. G5, G7 and G8 declare no client rule on purpose: in G4 the client gate already
FAILs, so the server half of the jq idioms was run over no document where it mattered. The same goldens are what
`verify_jq_idioms` runs the documented jq lines over.

Three semantics bite immediately:

- **It compares the `.json` and `.xml` only, never the HTML.** The comparison block lives only in
  the HTML, which is why a baseline-JTL case was removed after its first run. It does not cover the
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

## Run Everything

To run all tests in sequence:

```bash
# 1. Build + deploy
./scripts/build-and-deploy.sh

# 2. JUnit
mvn test

# 3. JMeter execution tests (generates reports for Playwright)
./test-plans/scripts/run-all-tests.sh

# 4. Playwright (needs reports from step 3)
cd test-plans/playwright && npm test

# 5. Config + error handling tests
./test-plans/scripts/run-config-tests.sh
./test-plans/scripts/run-error-tests.sh

# 6. Golden artifacts (R06)
./test-plans/scripts/run-goldens.sh
```

## Cleaning Test Data

```bash
rm -rf test-results/ test-plans/runs/ test-plans/playwright/test-results/
```

All test output is gitignored and regenerated by running the tests.
