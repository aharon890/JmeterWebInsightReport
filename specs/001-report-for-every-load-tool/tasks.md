# Tasks: One Web Insight Report for every load tool

**Input**: Design documents from `specs/001-report-for-every-load-tool/`. Read [plan.md](plan.md), [spec.md](spec.md),
[research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/) and [quickstart.md](quickstart.md).

**Tests**: Required. Constitution Principle III says a change is pinned by a test that fails first, so each phase's test
tasks come before its implementation and are handed over red.

**Organization**: by user story (spec.md), after a foundational phase that every story needs.

**Revised 2026-11-23** after `/speckit-analyze`: 27 findings, plus owner decisions 11 and 12. The finding ids (C1,
I1, G1 and so on) are in brackets where a task answers one.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no unfinished dependency).
- **[Story]**: the user story it serves (US1-US4).

## Path conventions

- `core/` = `jmeter-report-core/src/main/java/com/jmeterwebinsightreport/core/`
- `core-test/` = `jmeter-report-core/src/test/java/com/jmeterwebinsightreport/core/`
- `plugin/` = `jmeter-web-insight-report/src/main/java/com/jmeterwebinsightreport/`
- `plugin-test/` = `jmeter-web-insight-report/src/test/java/com/jmeterwebinsightreport/`
- `resources/` = `jmeter-web-insight-report/src/main/resources/`

Every phase ends with `mvn test`, then `./test-plans/scripts/run-sanity.sh`, then `./test-plans/scripts/run-regression.sh`.
Each runner's counts are read and written into the phase's commit message.

Rules for new scripts: every new `.sh` is committed executable (`git update-index --chmod=+x`), is bash 3.2-safe and
GNU/BSD-neutral, and has its `.bat` wrapper. User-visible behaviour is documented in `docs/` in the same phase, and
each command's `--help` changes in the task that adds the option (Principle VI).

---

## Phase 1: Setup

**Purpose**: the committed inputs every later test reads.

- [ ] T001 Create `test-plans/run-data/` with a `README.md` saying what each fixture is, where it came from and how to
  rebuild it.
- [ ] T002 [P] Commit the reference converter as test provenance: `test-plans/run-data/tools/k6_to_jtl.py`. It comes from
  perf hub's `tmp/k6-web-insight-report/shapes/k6_to_jtl_v3.py`, the version that matched the referee 198 of 198.
  - Give it a header naming its origin and its one use: writing the JMeter-format copies.
  - Extend it to also write `in-test-plan.jtl`: the top-level rows only, R07's whole-run reference [G2].
- [ ] T003 [P] Commit fixture K1 (the shapes run, recorded with both k6 settings) in `test-plans/run-data/K1/`:
  `results.csv.gz`, `summary.json` and `shapes.js`, plus `results.jtl` and `in-test-plan.jtl` written by T002 [G2].
- [ ] T004 [P] Commit fixture K3 (an older k6 file: whole seconds, no vu/iter, from perf hub run #66) in
  `test-plans/run-data/K3/`: `results.csv.gz`, `summary.json`, `results.jtl` and `in-test-plan.jtl`.
- [ ] T005 Record fixture K2 (petstore-checkout SMOKE with both k6 settings) into `test-plans/run-data/K2/`, **only
  after the owner's yes and at the smallest load**. Commit the same four files. Until then, the tasks that use K2 run on
  K1 and K3 only.
- [ ] T006 Capture the pre-change outputs [U6]. With today's build, write `ReportData` dumps and CI/CD JSON into
  `test-plans/run-data/baseline-4.4.1/`, for `goldens/plain.jtl`, E02, E31's shape,
  `core-test` resources `transaction-controllers.jtl` and `missing-optional-columns.jtl`, and the JtlParserTest
  cut-off cases (`JtlParserTest.java:732`, `:751`). Also write one listener run's dump (a failed sample with a response
  body, sub-results, and a transaction). Every later phase is held to these.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: plan stages 1-4. One feed, run data, the transaction rules, and running without JMeter.

**Checkpoint**: every fixture, R06, R07 and every `runId` are unchanged (SC-001), and `build-report --run-data` builds
a report on a machine with no JMeter.

### Tests first (red)

- [ ] T007 [P] `core-test/rundata/SampleFeedTest.java`: every input of T006 through the new feed gives the captured
  `ReportData` and CI/CD JSON. That covers both JTL paths and the listener: response bodies in the Errors tab,
  sub-results nested as `subresult`, cut-off controllers as requests with transaction children, and threads fed only
  when above 0 on the JTL path [U6, I1, I3].
- [ ] T008 [P] `core-test/rundata/RunDataReaderTest.java`:
  - reads `.jsonl` and `.jsonl.gz`;
  - refuses a first record that is not `run`, and a major version it does not read, with the FR-011 message
    (data-model.md `Run.formatVersion`);
  - skips and reports a bad line by number;
  - skips unknown types and fields;
  - refuses 10-digit and 16-digit-or-longer times (data-model.md `Sample.start`);
  - feeds a sample whose `parent` names no sample as top-level, and reports it (data-model.md `Sample.parent`);
  - feeds `users` records to the active threads (data-model.md `Active users`) [G1];
  - reads child-before-parent and nested-group orders the same as parent-first (data-model.md "Reading order and
    memory") [U1].
- [ ] T009 [P] `core-test/rundata/RunDataRoundTripTest.java`: every T006 input, JTL → `RunDataWriter` → `RunDataReader`,
  gives the same CI/CD JSON as the JTL alone, `runId` included (FR-006, FR-013) [I1].
- [ ] T010 [P] `core-test/stats/TransactionRulesTest.java`:
  - a transaction with no `elapsed` gets the sum of its direct `transaction`-linked children (data-model.md
    `Sample.elapsed`, `Sample.link`);
  - `wallTime` minus that is its idle time, unless `idle` is given (data-model.md `Sample.wallTime`, `Sample.idle`);
  - it fails when a child failed and it gives no `success` (data-model.md `Sample.success`);
  - it is counted once in the whole run;
  - a `kind` `transaction` sample with no message is kept out of the Errors tab's sample count and of Codes per Second
    (data-model.md `Sample.kind`) [I2];
  - nested groups and a pass with no request are included.
- [ ] T011 [P] `plugin-test/report/generator/ComparisonPropertiesTest.java`: with a baseline configured (the only path
  that reaches `WebInsightReportGenerator.java:930`) and no JMeter class on the class path, the generator reads
  `webinsight.compare.*` from the configuration's property function (research R5) [A2].
- [ ] T012 [P] `plugin-test/WirCollectLauncherTest.java`, new cases:
  - `build-report` dispatch, with the collector still the default;
  - `lib/` beside the JAR found second, also with a wrong `JMETER_HOME` set and a non-JMeter `jmeter` on `PATH`
    (contracts/build-report-cli.md) [U4].
- [ ] T013 [P] `plugin-test/BuildReportTest.java`:
  - `build-report --run-data` writes the page, the CI/CD JSON and, with `webinsight.report.junit=true`, JUnit into
    `--out`;
  - `--property` wins over `--properties`, which wins over the defaults;
  - the exit codes follow `failonerror`, `failonassert` and `server.failonerror`;
  - a `webinsight.report.title` that differs from the run record's `testName` is refused (data-model.md
    `Run.testName`) [A1];
  - a baseline on a non-JMeter run is refused with its sentence, and the report is built without Compare (FR-017) [A2];
  - a non-JMeter run's JSON has `tool`, and a JMeter run's JSON has no `tool` key (decision 12) [I4];
  - `--help` lists every option.
- [ ] T014 [P] `plugin-test/report/RunDataSettingTest.java` (`webinsight.report.rundata`) [G3]:
  - default off writes nothing, on both paths;
  - on, the listener and `-g` each write `<report name>-run-data.jsonl.gz` through the publication set;
  - the file never contains a response body (decision 11) [I3];
  - concurrent listener threads write one well-formed file;
  - in distributed mode the controller writes one file;
  - a failed write is a failed artifact under `failonerror`;
  - the name is in `PropertyCheck.KNOWN`.
- [ ] T015 Config scenario C44 in `test-plans/scripts/run-config-tests.sh` [G6]:
  - unzip the standalone bundle;
  - with `JMETER_HOME` unset and JMeter not on `PATH`, under `-verbose:class`, run `build-report --run-data`,
    `build-report --from k6` (K1), `wir-collect --run-data` and `wir-collect --from k6`;
  - require their outputs, and no `org.apache.jmeter` class loaded (FR-015).

### Implementation

- [ ] T016 `core/rundata/RunSample.java` and `core/rundata/SampleFeed.java` (research R3):
  - the sample model with every field of data-model.md `Sample`: `id`, `parent`, `link`, `kind`, `name`, `start`,
    `elapsed`, `idle`, `wallTime`, `success`, `code`, `message`, `failureMessage`, `user`, `iteration`, `topLevel`,
    `samples`, `errors`, `bytes`, `sentBytes`, `latency`, `connect`, `threads`, `url`, `method`, `timings`;
  - an in-memory-only response body (decision 11);
  - the one feed into `LiveStatisticsEngine`.
- [ ] T017 `core/stats/LiveStatisticsEngine.java`: the methods that test `isTransactionRow(message)` (:202-204, :260,
  :289, :517, :741) take the transaction flag from the caller instead. The JTL path and the listener pass the marker
  test, and `SampleFeed` passes `kind == transaction` [I2].
- [ ] T018 Route `core/parser/JtlParser.java` `ParsedSample.feed` through `SampleFeed`, setting `kind` from the marker
  and `link` from `TransactionRows.Nesting` [I1]. Depends on T016 and T017.
- [ ] T019 Route `plugin/report/listener/WebInsightReportListener.java` (the sample and its sub-results, :491-566,
  response bodies :524-549) through `SampleFeed` [I1, I3]. Depends on T016 and T017.
- [ ] T020 [P] `core/rundata/RunDataFormat.java` (`VERSION = "1.0"`, the versions read, the record type names) and
  `core/rundata/RunHeader.java`, with data-model.md `Run.formatVersion`, `Run.tool.name`, `Run.tool.version`,
  `Run.testName`, `Run.producer.name`, `Run.producer.version`, `Run.timeResolution` and `Run.notMeasured` [U2].
- [ ] T021 [P] `core/rundata/ToolRecords.java`: data-model.md `Active users` (`time`, `active`) and "A tool's own
  measures" (`check`, `threshold`, `counter`, with their fields).
- [ ] T022 `core/rundata/RunDataReader.java` (data-model.md "Reading order and memory", "Validation") [U1, G1]:
  - two passes with Jackson streaming, `.gz` decompressed twice;
  - pass 1 indexes every `id` (name, kind, link) and works out the children's elapsed sum and failure for each
    transaction without them;
  - pass 2 feeds every record in file order: `users` → `updateActiveThreads`, and `Sample.parent` → the parent's name
    and `Sample.link`.
  - Depends on T016, T020 and T021.
- [ ] T023 `core/rundata/RunDataWriter.java`: writes the records in contracts/run-data-format.md's order, never a body.
  Depends on T020 and T021.
- [ ] T024 `core/stats/LiveStatisticsEngine.java`: a transaction's elapsed time, idle time and success from its
  children when not given (data-model.md `Sample.elapsed`, `Sample.idle`, `Sample.wallTime`, `Sample.success`;
  research R4). Depends on T016.
- [ ] T025 `webinsight.report.rundata` in both JMeter paths, default `false` (decision 9):
  - `plugin/report/listener/WebInsightReportListener.java` uses one synchronised writer, published at test end;
  - `plugin/report/exporter/WebInsightDataExporter.java`;
  - `ArtifactPublication` gets the file, `PropertyCheck.KNOWN` the name;
  - document it in `docs/` [G3].
- [ ] T026 `plugin/report/generator/WebInsightReportGenerator.java` and `ReportConfiguration`: pass the configuration's
  property function to `ComparisonThresholdResolver`, so no `JMeterUtils` is on the build path (research R5). In
  `plugin/report/exporter/ReportRebuild.java:171`, read the JMeter version only on the JMeter paths.
- [ ] T027 `plugin/report/exporter/ReportRebuild.java`: `parse(RunData…)` beside `parse(File jtl, …)`. The tool and its
  version come from the `run` record (data-model.md `Run.tool.name`, `Run.tool.version`). A non-JMeter run's baseline
  is refused (FR-017).
- [ ] T028 `plugin/report/template/CiCdJsonSerializer.java`: `"tool": {"name", "version"}` only when `tool.name` is
  not `jmeter` (decision 12). No golden moves [I4].
- [ ] T029 `plugin/BuildReport.java`: the `build-report` command over `ReportRebuild` (contracts/build-report-cli.md),
  with `--run-data`, the test name rule (data-model.md `Run.testName`) and `--help`. `--from k6` comes in US2. Document
  it in a `docs/` page "build-report".
- [ ] T030 `plugin/WirCollectLauncher.java`: dispatch `build-report`, keep the collector as the default, and look in
  `lib/` beside the JAR second (contracts/build-report-cli.md) [U4].
- [ ] T031 `assembly/standalone.xml` and the module pom: build `jmeter-web-insight-report-<v>-standalone.zip` [L1-4].
  - It holds the JAR plus `lib/` with Jackson 2.16.1 (databind, core, annotations), commons-math3 3.6.1, slf4j-api
    1.7.36 and slf4j-simple 1.7.36. slf4j-simple is a new dependency, used only by the assembly.
  - `verify_shaded_jar` stays unchanged, and the shaded JAR's contents do not change.

---

## Phase 3: User Story 1 - the same Server tab and server verdict, whatever the tool (P1) MVP

**Goal**: server data is collected and judged from run data, with no JMeter results file (FR-005).

**Independent test**: build two reports from hand-written run data, one with `tool.name` `jmeter` and one with `k6`,
over the same window and server CSV. The two Server tabs are identical (SC-003).

- [ ] T032 [P] [US1] `test-plans/run-data/S1/`: `jmeter.jsonl` and `k6.jsonl`, written by hand to the same window, with
  the same `server.csv` and `server-asserts.json` (one component, one PASS, one FAIL and one NOT_JUDGED assert) [L1-2].
- [ ] T033 [P] [US1] `plugin-test/WirCollectTest.java`: `--run-data` gives the window, `runId` and test name from the
  run data; exactly one of `--jtl` and `--run-data` is accepted; `--jtl` and `--help` are unchanged except for the new
  line.
- [ ] T034 [P] [US1] Golden G13 in `test-plans/scripts/run-goldens.sh`: S1's `k6.jsonl` through `build-report
  --servermetrics server.csv`. Its `.json`, `.xml` and `.server.json` are compared with `jmeter.jsonl`'s. They are equal
  except for `tool` (absent on the JMeter one), `runId` and `testName`.
- [ ] T035 [US1] `plugin/WirCollect.java`: `--run-data FILE` through `RunDataReader`, plus its `--help` line. Depends on
  T022.
- [ ] T036 [US1] `plugin/BuildReport.java`: `--servermetrics` (a payload or a CSV) and `--annotations`, through
  `ReportRebuild.readCompanionFiles`. The in-process CSV collection is handed the parsed `ReportData`, not a `--jtl`
  (ReportRebuild.java:314, WirCollect.java:163), with the report title as test name, as `-g` does [L1-3].
- [ ] T037 [US1] Accept G13's baselines with `run-goldens.sh --accept` and commit them with T035-T036. Document
  `wir-collect --run-data` in the server-data docs.

---

## Phase 4: User Story 2 - one report from any producer's data (P1)

**Goal**: the k6 adapter (decision 8), and the conformance set that holds every producer, the JMeter plugin included,
to JMeter's Aggregate Report.

**Independent test**: K1-K3 through `build-report --from k6`, and E02/E31 through `rundata=true` and `build-report
--run-data`. Every row equals the referee's (SC-002), and the CI/CD JSON equals `expected/` (SC-005).

- [ ] T038 [P] [US2] `core-test/adapter/k6/K6ResultsReaderTest.java` over K1:
  - one sample per request, fields per contracts/k6-adapter.md "Requests";
  - adjacency joins, with two `http.batch` requests ending in the same millisecond [A4];
  - group passes as transactions with `topLevel` false children;
  - redirect hops, status 0 with `error_code` 1212, and setup/teardown rows;
  - group passes not in the Errors tab or Codes per Second [I2];
  - `users` from `vus` rows [G1].
- [ ] T039 [P] [US2] `core-test/adapter/k6/K6SummaryReaderTest.java`: threshold, check and counter (`vus_max`
  included) records from K1's `summary.json` (contracts/k6-adapter.md "The tool's own records").
- [ ] T040 [P] [US2] `core-test/adapter/k6/OlderK6FileTest.java` over K3:
  - whole seconds and no vu/iter give request rows only and no transactions;
  - `run.timeResolution` is `s`, and `notMeasured` includes `user` and `iteration` (data-model.md `Run.timeResolution`,
    `Run.notMeasured`);
  - same-second requests split by adjacency [A4, U2].
- [ ] T041 [US2] `test-plans/scripts/run-run-data.sh` (+x, `.bat` twin), step R08 of `run-regression.sh`:
  - **k6:** each K fixture through `build-report --from k6 --write-run-data`.
  - **JMeter [C1]:** E02 and E31 through `jmeter -g … -Jwebinsight.report.rundata=true`, then `build-report --run-data`
    on the file written.
  - For both:
    - run R07's referee over the JMeter-format copy (`results.jtl` and `in-test-plan.jtl`);
    - compare the row text with `verify-data-integrity.py --report-html`;
    - diff the CI/CD JSON against `test-plans/run-data/<id>/expected/` (JMeter: against the `-g` report itself), failing
      when `expected/` is dirty, as R06 does.
- [ ] T042 [US2] `test-plans/scripts/verify-data-integrity.py`: a third stated exception. A column the run's
  `notMeasured` names reads "not measured" where JMeter's table shows a number [I5].
- [ ] T043 [US2] `core/adapter/k6/K6ResultsReader.java`: streams k6's CSV (`.gz` too), joins each request's rows by
  adjacency, opens and closes group passes, and sets the run record's `tool`, `timeResolution` and `notMeasured`
  (contracts/k6-adapter.md). Depends on T016 and T020.
- [ ] T044 [US2] `core/adapter/k6/K6SummaryReader.java`: threshold, check and counter records. Depends on T021.
- [ ] T045 [US2] `plugin/BuildReport.java`: `--from k6 --k6-results FILE [--k6-summary FILE] [--write-run-data]`, with
  the test name from `webinsight.report.title`, else the default (data-model.md `Run.testName`) [A1], and `--help`.
  Depends on T029, T043 and T044.
- [ ] T046 [US2] `plugin/WirCollect.java`: `--from k6 --k6-results FILE` with `--test-name`, giving the same window and
  identity as `build-report`, plus `--help`. Depends on T043.
- [ ] T047 [US2] `plugin-test/BuildReportK6Test.java` [G4]:
  - K1 with `webinsight.report.externaldata=true` writes and references the sidecar data file (FR-009);
  - a K1 copy whose summary marks a threshold failed, under annotations that pass, gives the client verdict PASS with
    the threshold marked failed (decision 4).
- [ ] T048 [US2] Write `test-plans/run-data/K1/expected/` and `K3/expected/` with `run-run-data.sh --accept`, read every
  number against the referee before committing, and commit them. K2's follow T005.
- [ ] T049 [US2] Add R08 to `test-plans/scripts/run-regression.sh` and its summary. Add a `docs/` page "k6" with the two
  k6 settings, the summary, the test name, and how k6's own numbers can differ from the report's.

---

## Phase 5: User Story 3 - the report names the tool, uses its words and shows its own measures (P2)

**Goal**: decisions 7 and 10. contracts/page-words.md on k6 runs, and the tool's tab.

**Independent test**: K1's report. There are no JMeter words outside the two exceptions (SC-004), and the k6 tab shows
K1's thresholds, checks, wall times, timing parts, iterations and dropped iterations. Every JMeter fixture reads as
today.

- [ ] T050 [US3] Inventory, before any words change: list every user-visible "JMeter", "Sampler", "Thread" and
  "Transaction" in `resources/templates/report.html` and `resources/static/js/report.js` (about 273 "Sampler", 22
  "Thread" and 32 "JMeter" in report.js, e.g. "Max Active Threads" at report.js:48). Add each one shown on a k6 run to
  contracts/page-words.md [U3].
- [ ] T051 [US3] Add K1 as a Playwright fixture [G2, C2]:
  - generated by `build-report --from k6` in `run-all-tests.sh`;
  - listed in `FIXTURE_IDS` (`scripts/lib/common.sh`) and `REQUIRED_FIXTURES` (`test-plans/playwright/global-setup.ts`);
  - R07's `_check` wired to its `results.jtl` and `in-test-plan.jtl`;
  - `test-plans/run-data` added to the fixture stamp in `common.sh` (`write_fixture_stamp`), in
    `test-plans/playwright/fixture-stamp.js`, and in `js-unit/fixture-stamp.test.js`.
- [ ] T052 [P] [US3] `test-plans/playwright/tests/tool-words.spec.ts`: sweep K1's report, every tab, for T050's list
  outside the two exceptions. Every JMeter fixture keeps its words.
- [ ] T053 [P] [US3] `test-plans/playwright/tests/tool-tab.spec.ts` [U5, G1]:
  - K1's `k6` tab: thresholds, checks and counters against `summary.json`;
  - distributions (wall time, timing parts) against the adapter's records, computed exactly;
  - Max VUs equals summary.json's `vus_max`;
  - bytes read "not measured" (FR-008);
  - no tool tab on any JMeter fixture.
- [ ] T054 [P] [US3] `core-test/model/ToolMeasuresTest.java`: check pass/fail per name and group; threshold results;
  wall time per group and the six timing parts per request name, in k6's summary columns (avg, min, med, max, p(90),
  p(95)) through the engine's exact aggregator; iterations and dropped iterations [U5].
- [ ] T055 [US3] `core/model/TestMetadata.java` gets `tool` (data-model.md `Run.tool.name`, `Run.tool.version`) and
  `notMeasured` (data-model.md `Run.notMeasured`). `core/model/ToolMeasures.java` aggregates the tool's records and
  timings through the existing distribution code (no second percentile routine). Depends on T021.
- [ ] T056 [US3] `resources/templates/report.html` and `plugin/report/template/` [U2]:
  - one words table in the context (contracts/page-words.md with T050's additions);
  - the header's tool and version;
  - "not measured" cells only for data-model.md `Run.notMeasured` (`Sample.bytes`, `Sample.sentBytes`,
    `Sample.latency`, `Sample.connect`);
  - a Notes line for `Run.timeResolution` `s`.
- [ ] T057 [US3] `resources/templates/report.html` and `resources/static/js/report.js`: a tab named after `tool.name`,
  after Server, only when the tool is not `jmeter`, with the k6 content on k6 runs (decision 10) [I8].
  - Text goes through `escapeHtml`, and `verify_report_js_rules` passes.
  - Document the tab on the "k6" docs page.
- [ ] T058 [US3] `resources/static/js/report.js`: every T050 occurrence reads the words table, including the Samplers
  tree, the transaction switch ("Show group details" on k6 runs) and "Max Active Threads".

---

## Phase 6: User Story 4 - a new tool by writing samples only (P3)

**Goal**: the format is documented, published and checkable by any producer (FR-010, FR-014).

**Independent test**: a hand-written run data file for an imaginary tool `demo`, written from the docs alone, builds a
report with the common tabs, `demo` in the header, and a `demo` tab only if it carries tool records.

- [ ] T059 [P] [US4] `plugin-test/report/RunDataSchemaTest.java` [C3]:
  - every record's fields in `resources/static/schema/run-data.schema.json` are held to the Java records, as
    `AnnotationsSchemaTest` holds the annotations schema;
  - its `formatVersion` pattern is held to `RunDataFormat.VERSION` and `READS`;
  - a js-unit test holds `docs/schema/run-data.schema.json` identical to it, like `schema-published.test.js`.
- [ ] T060 [P] [US4] `test-plans/run-data/D1/`: the `demo` tool's run data and expected JSON, run by R08. Depends on
  T041 [I7].
- [ ] T061 [US4] `resources/static/schema/run-data.schema.json` and `docs/schema/run-data.schema.json` (research R9).
- [ ] T062 [US4] A `docs/` page "Run data":
  - the format;
  - the per-tool meaning table (data-model.md "Per-tool meaning"), including the Transaction Controller mode [A3];
  - the versions read and written;
  - reading order and memory;
  - the pairing with server data (research R12);
  - no response bodies (decision 11);
  - how to run R08's conformance on a new producer.

  Add it to `docs/_sidebar.md`.

---

## Phase 7: Polish and cross-cutting

- [ ] T063 [P] `docs/FEATURES.md` and `docs/Instructions.md`: run data, `build-report`, R08, C44 and the K fixtures.
- [ ] T064 [P] `CLAUDE.md`, to stay consistent with constitution 2.0.0 (Governance):
  - "Two ingestion paths, one generator" becomes three feeders and one feed, with the explicit transaction flag;
  - the collector's libraries and the launcher's order;
  - R08 and its stated "not measured" exception [I5];
  - the K fixtures and the stamp.
- [ ] T065 [P] `.claude/skills/_perf_release/SKILL.md`: attach the standalone zip and the run data schema.
- [ ] T066 [P] `.github/workflows/cross-os.yml`: `build-report --from k6` over K1 on Windows, macOS and Linux, from the
  standalone bundle.
- [ ] T067 `test-plans/scripts/measure-run-data.sh` (+x, `.bat` twin): K2 (or K1) repeated to 134,400 requests [G5].
  - Build time against `jmeter -g` over the JMeter-format copy, and against the 4.4.1 JAR over the same samples.
  - The heap under `-Xmx512m`.
  - The Summary tab's draw time over `file://`, for the new report and for 4.4.1's.

  Report the figures (plan's Performance Goals, SC-006).
- [ ] T068 Run quickstart.md scenarios 1-7, write each result into the PR, and run the full release tier
  (`run-all-tests.sh`, `run-config-tests.sh`, `run-error-tests.sh`) before the owner's release decision.

---

## Dependencies and execution order

- **Setup (T001-T006)** first. T006 runs on today's build, before any code changes. T005 waits for the owner's yes and
  is not on the critical path.
- **Foundational (T007-T031)** blocks every story.
  - T016 and T017 come before T018, T019, T022 and T024.
  - T020 and T021 come before T022 and T023.
  - T026 comes before T027, then T029, T030 and T031, then C44 (T015).
- **US1 (T032-T037)** and **US2 (T038-T049)** need only Foundational, and can run in parallel. US1 is the MVP: the
  owner's main reason.
- **US3 (T050-T058)** needs US2's adapter (K1 as a fixture).
- **US4 (T059-T062)** needs Foundational and T041 (R08).
- **Polish (T063-T068)** comes last. T067 needs US2.

## Parallel examples

- Foundational tests: T007-T014 together (separate files).
- US2 tests: T038, T039 and T040 together.
- US3 tests: T052, T053 and T054 together, after T050 and T051.
- Docs and CI: T063-T066 together.

## Implementation strategy

1. **MVP = Foundational + US1.** A run's Server tab is judged from run data, whatever wrote it. Stop and show the owner.
2. **US2** gives k6 runs their whole report through `build-report --from k6`. Perf hub spec 010 can start its plan.
3. **US3**, then **US4**, then Polish and the release decision (the owner's).

Each phase is red first, then green, then sanity and regression, then a commit with the counts. The owner merges.
