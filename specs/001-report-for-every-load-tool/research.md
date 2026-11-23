# Research: One Web Insight Report for every load tool

**Date**: 2026-11-23. Earlier research is in perf hub's git-ignored `tmp/k6-web-insight-report/`:
`k6-native-research.md`, `research-agents-2026-11-23.md` and `two-specs-review-2026-11-23.md`. It was done for perf
hub spec 010, and its findings are not repeated here. The facts below were checked in this tree on 2026-11-23.

## What the code already does

- **One engine, fed sample by sample.** `LiveStatisticsEngine` builds `ReportData` (`buildReportData`). Both JMeter
  paths call the same methods:
  - `recordSample(label, elapsed, success, bytes, sentBytes, start, end, connect, latency, samples, errors, topLevel)`;
  - `recordTransaction(parent, child)`;
  - `recordError(...)`, `recordResponseCode(...)`, `updateActiveThreads(...)` and `recordTransactionRow(...)`.

  The JTL path calls them in `JtlParser.ParsedSample.feed` (JtlParser.java:347), and the listener at
  WebInsightReportListener.java:491-566.
- **The collector and the identity read only `ReportData`.**
  - `WirCollect` takes the window from `TestMetadata` start and end, and the identity from `RunIdentity.of(data)`
    (WirCollect.java:193-234).
  - `RunIdentity.fingerprint` hashes the test name, the start, the duration and the top-level sample and error counts
    (RunIdentity.java:109-121).

  Any feeder that fills the same engine gets the same window and identity rule for free.
- **The rebuild is one service.** `ReportRebuild.parse(File jtl, ReportConfiguration, Function<String,String> props)`,
  `readCompanionFiles(...)` (the in-process CSV collection included) and `generateAndAnnounce(...)` already take
  properties as a function. The JMeter-free command can call them as the exporter does.
- **Two JMeter ties remain in the build path.**
  - `WebInsightReportGenerator.java:930` passes `org.apache.jmeter.util.JMeterUtils::getProperty` to
    `ComparisonThresholdResolver`. Without JMeter on the class path that is a `NoClassDefFoundError`, and the resolver
    only catches `RuntimeException` (:74).
  - The JMeter version is read in `ReportRebuild.java:171` and `WebInsightReportListener.java:638`.
- **The libraries match.** WIR's parent pom pins Jackson 2.16.1, Commons Math 3 3.6.1 and SLF4J 1.7.36 as `provided`,
  the very jars `apache-jmeter-5.6.3/lib/` holds.
  - `WirCollectLauncher` already runs `WirCollect` over the plugin JAR plus `<JMETER_HOME>/lib/*.jar`, and
    ApacheJMeter_core is in `lib/ext`, not `lib/`.
  - So the collector already runs with no JMeter class loaded.
- **k6's files (perf hub run #66, `perf-hub-k6:2.4.0`).**
  - `results.csv` has one row per metric sample: `http_req_duration`, the six timing parts, `http_req_failed`,
    `http_reqs`, `checks`, `group_duration`, `iteration_duration`, `iterations`, `vus`, `data_sent`/`data_received`.
    It carries tags, and with `K6_SYSTEM_TAGS` including `vu` and `iter` it also carries a `metadata` column.
  - `summary.json` (handleSummary's data) has every metric's thresholds with their results, `root_group` with its
    checks' passes and fails, and `dropped_iterations`.

## Decisions

### R1. Where the engine runs

- **Decision:** the Java engine (`LiveStatisticsEngine` → `ReportData` → `WebInsightReportGenerator`), for every
  producer. The page keeps its current split: Thymeleaf plus `report.js`, with the browser mirrors held to Java by the
  parity vectors.
- **Rationale:**
  - The CI outputs (CI/CD JSON, JUnit, exit codes) need the verdicts without a browser.
  - The engine exists, and R07 holds it to JMeter.
  - With the k6 adapter in WIR (decision 8), nothing needs the page to compute figures.
- **Alternatives considered:**
  - A data-driven page that computes everything: a second engine in JavaScript, beside parity tests that already
    cost 65% of `report.js`.
  - A Go or JavaScript engine for k6: a third copy.

### R2. The common format: JSON Lines

- **Decision:** "run data" is JSON Lines (one JSON object per line, UTF-8, LF), optionally gzip-compressed (`.gz`).
  - The first record is `run`. Then come `sample`, `users`, `check`, `threshold` and `counter` records, in any order.
  - Version 1.0. See contracts/run-data-format.md.
- **Rationale:**
  - It streams both ways: a listener appends, a reader never holds the file.
  - It is the shape of k6's own `--out json` (NDJSON of typed records).
  - Jackson's streaming parser reads it with no new library.
  - Every record names its type, so a reader skips what it doesn't know (forward reading).
- **Alternatives considered:**
  - JMeter's CSV JTL with extra columns. Nesting has to be inferred (`TransactionRows`), there is no place for a tool's
    own records, and it ties the format to JMeter's columns.
  - One JSON document: not streamable at 134,400 samples.
  - Protobuf or Avro: a new library and a schema compiler, for no gain at these sizes.

### R3. One feed into the engine

- **Decision:** `SampleFeed.feed(engine, RunSample)` is the only code that turns a sample into engine calls.
  - `JtlParser.ParsedSample.feed` builds a `RunSample` and calls it.
  - The listener builds `RunSample`s from a `SampleResult` and its sub-results, then calls it.
  - The run-data reader calls it.
  - Nesting: JTL rows still get their parent from `TransactionRows`, and run data names its parent explicitly.
- **Rationale:** the constitution's "one engine" (VII) and the workbook's "no parallel mechanisms". A JTL and its run
  data cannot give different numbers if one function feeds both.
- **Alternatives considered:** a separate `RunDataParser` calling the engine its own way. That makes two feeds to
  keep equal: the drift R07 was written against.

### R4. Transactions, and what is a figure

- **Decision:**
  - A `sample` record of kind `transaction` may leave out `elapsed`. The engine then takes the sum of its direct
    children's elapsed times, as JMeter's Transaction Controller does by default.
  - The producer may give `wallTime` (k6's group duration). The engine sets idle time = wallTime − elapsed, and the
    k6 tab shows wall-time statistics.
  - A transaction that gives no `success` fails when any direct child failed, as the Transaction Controller does.
  - JMeter's transactions keep their stated elapsed and idle time.
- **Rationale:** spec FR-002 says a producer works out no figure. A group pass's elapsed time is a rule, so it lives in
  the engine. The research's converter (v3) showed the rule nests and counts correctly (198 of 198 referee checks).
- **Alternatives considered:** the producer computes elapsed. That puts the rule in every adapter.

### R5. Properties without JMeter

- **Decision:**
  - `ReportConfiguration` carries the property function its caller built: `JMeterUtils::getProperty` in JMeter, and the
    command's `--property`/`--properties` over the defaults outside it.
  - The generator passes that function to `ComparisonThresholdResolver` and never calls `JMeterUtils` itself.
  - The tool version comes from the run data's `run` record, or from `JMeterUtils.getJMeterVersion()` on the JMeter
    paths only.
- **Rationale:** FR-015 and Principle VI. This is the only `JMeterUtils` use on the build path (checked above).
- **Alternatives considered:** a catch of `NoClassDefFoundError`. That hides the setting rather than reading it.

### R6. Libraries outside JMeter

- **Decision:** the launcher looks for its libraries in this order:
  1. the JAR's own `lib/ext` (today's order);
  2. `JMETER_HOME`;
  3. `jmeter` on `PATH`;
  4. **new:** a `lib/` folder beside the JAR.

  A release also attaches `jmeter-web-insight-report-<v>-standalone.zip`: the same JAR, plus `lib/` with
  jackson-databind/core/annotations 2.16.1, commons-math3 3.6.1, slf4j-api 1.7.36 and slf4j-simple 1.7.36.
- **Rationale:**
  - It is the same JAR, not a second build (Principle IV).
  - SLF4J stays unrelocated: inside JMeter the logs must reach JMeter's log, so the JAR can't bundle it.
  - Perf hub can use either the shared JMeter volume (option 1) or the bundle.
- **Alternatives considered:**
  - Relocating Jackson, Commons Math and SLF4J into the JAR. Logging inside JMeter would be lost, and the JAR grows by
    about 4 MB.
  - A second "engine" JAR. That is two artifacts of one engine.

### R7. The JMeter-free command

- **Decision:** `java -jar <jar> build-report ...` (contracts/build-report-cli.md). It runs `ReportRebuild`'s parse,
  companions and generate over run data or k6 files.
  - The launcher dispatches on the first argument: `build-report`, else today's collector, so existing `java -jar`
    lines keep working.
  - `wir-collect` gains `--run-data FILE`, exactly one of `--jtl` and `--run-data`.
  - Exit codes follow the existing policy: `failonerror`, `failonassert` and `server.failonerror` mean what they mean
    on `-g`.
- **Rationale:** `ReportRebuild` is already "the one rebuild" that the Rebuild button and `-g` share.
- **Alternatives considered:** a separate main class and script per command. That means two launchers and two library
  searches.

### R8. The k6 adapter (decision 8)

- **Decision:** `core/adapter/k6`. It streams k6's CSV and turns each request's rows into one `RunSample`, keyed by
  (vu, iter, request end, name, method, url):
  - elapsed = `http_req_duration`;
  - end = the row's time, and start = end − elapsed;
  - success = not `http_req_failed`;
  - code from `status`, plus `error_code` when present;
  - the six timing parts as the sample's timings.

  Group passes become transaction samples, with parents from the `group` tag path and `group_duration` as wallTime.
  `checks` rows become check records. `summary.json` gives threshold records and the dropped-iterations counter. The
  mapping is in contracts/k6-adapter.md.
- **Rationale:** one k6 translation for perf hub and standalone k6 users (decision 8). It is the research's reference
  converter (`k6_to_jtl_v3.py`, 198 of 198 against JMeter's referee), moved to Java and to run data.
- **Alternatives considered:** perf hub writes run data. That makes two k6 translations once a standalone k6 path
  exists (the owner chose against it on 2026-11-23).

### R9. Versions

- **Decision:**
  - `RunDataFormat.VERSION = "1.0"`, and `READS = {"1.0"}`.
  - The `run` record carries `formatVersion`.
  - A minor version adds only optional fields or record types. A reader skips unknown record types and fields, and
    refuses an unknown major version with the FR-011 message.
  - The JSON Schema `static/schema/run-data.schema.json` is published in `docs/schema/` and held to the Java records
    by a test, as `AnnotationsSchemaTest` holds the annotations schema.
- **Rationale:** FR-011, FR-014 and Principle V: one place for the version, separate from `<revision>`.

### R10. Conformance fixtures

- **Decision:** `test-plans/run-data/` holds committed k6 inputs (K1 shapes, K2 petstore-checkout SMOKE with both k6
  settings, K3 old-format whole seconds). For each it also holds:
  - the expected CI/CD JSON;
  - the expected run data the adapter writes;
  - a JMeter-format copy for R07.

  The copies are written by the reference converter, committed with the converter script as their provenance, so
  that two independent translations must agree. A JMeter fixture's run data (from `rundata=true`) is held to its JTL
  report.
- **Rationale:** Principle III (no tool but JMeter runs in the suites; R07 for every fixture) and SC-002/SC-005.
- **Note:** K2 needs a fresh k6 run with both settings, at the smallest load and after the owner's yes (a test bed
  run). The shapes run that already exists has both settings.

### R11. The page

- **Decision:**
  - `TestMetadata.tool` (`name`, `version`).
  - One words table in the template context (contracts/page-words.md): JMeter's words by default, k6's on a k6 run.
  - A `k6` tab after Server on k6 runs only.
  - Missing measures (bytes and latency for k6) read "not measured" instead of 0.
  - The header names the tool and its version.
- **Rationale:** decisions 7 and 10, FR-007 and FR-008. Thymeleaf already renders the header and tables, so the words
  change at one place.

### R12. Server data stays in its payload

- **Decision:** server data keeps today's payload file (or CSV collected in-process), matched to the run by `runId`.
  Run data does not embed it. FR-001's "server data, when it was collected" is met by that pairing, which the format
  docs state.
- **Rationale:** no parallel mechanism. The payload, its `collectionId` and its refusal rules are already tested to
  the byte.
- **Alternatives considered:** embedding the payload in run data. That gives two places for one payload and changes
  the committed payload tests.
