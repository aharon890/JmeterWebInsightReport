# Implementation Plan: One Web Insight Report for every load tool

**Branch**: `feat/001-report-for-every-load-tool` | **Date**: 2026-11-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-report-for-every-load-tool/spec.md`

**Status**: Approved by the owner on 2026-11-23, with the names table below ("Approve, open 3 PRs"). Nothing is
built (Principle I): next come `/speckit-tasks` and `/speckit-analyze`, and code only on the owner's go. Building also
waits for the constitution amendment 2.0.0 (branch `docs/constitution-report-for-every-load-tool`) to be merged.

## Summary

The owner wants one report per test whatever the load tool, above all one Server tab and one set of server asserts
(spec, Input). The research found that the engine this needs already exists: both JMeter paths feed one statistics
engine sample by sample (`LiveStatisticsEngine.recordSample(..., topLevel)`), and the collector and the run's identity
read only the `ReportData` that engine builds. So the plan adds **a third feeder, not a second engine**:

1. **A common format for a run's samples**, "run data": JSON Lines, one record per line, versioned and documented, with
   explicit parents instead of inferred nesting.
2. **One feed** from that sample model into the engine, used by the JTL parser, the listener and the run-data reader
   alike.
3. **A command that needs no JMeter**, `build-report`, plus `wir-collect --run-data`. Both run from the same JAR, with
   the three libraries JMeter used to lend either taken from a JMeter install or from a bundle beside the JAR.
4. **A k6 input adapter in WIR** (decision 8): `build-report --from k6` reads k6's results file and end-of-test summary.
   Perf hub and a standalone k6 user both call it.
5. **The page in the tool's words**, with a "k6" tab for k6's own measures (decisions 7 and 10).

JMeter users see no change: the JMeter paths only route through the shared sample model, and the run-data file is
written only on request (decision 9).

## Technical Context

**Language/Version**: Java 21 (`release 21`), the plugin's existing build. JavaScript (ES2017, no build step) in
`report.js`. Bash 3.2-safe scripts.

**Primary Dependencies**: Unchanged, at the versions JMeter 5.6.3 ships:
- Jackson 2.16.1;
- Commons Math 3 3.6.1;
- SLF4J 1.7.36;
- Thymeleaf (shaded);
- ECharts 5.5.0 (inlined).

No new library. Run data is read and written with Jackson's streaming API, and `.gz` with `java.util.zip`.

**Storage**: Files only: run data `*.jsonl` or `*.jsonl.gz`, k6's `results.csv[.gz]` and `summary.json`, and today's
outputs.

**Testing**:
- JUnit 5 (`mvn test`) and the `report.js` js-unit tests;
- Playwright over fixtures;
- R07 (`AggregateReportReferee.java` and `verify-data-integrity.py`);
- R06 goldens;
- the config, error and title families.

New: a conformance set (committed k6 results, their expected CI/CD JSON, and a JMeter-format copy of their samples
for R07).

**Target Platform**: JMeter 5.6.3 (`lib/ext`), and any JRE 21 with no JMeter: perf hub's backend container, a CI
machine, a k6 user's machine. Windows, macOS (Apple Silicon) and Linux.

**Project Type**: A library with a CLI: the JMeter plugin, the core library, and the `java -jar` commands.

**Performance Goals**:
- `build-report --from k6` over 134,400 requests (100 times perf hub's run #52) builds within the time today's
  `jmeter -g` takes over the same samples as a JTL, plus 20%. Both are measured.
- The page meets SC-006.

**Constraints**:
- The run-data reader and the k6 adapter stream. Neither holds a whole file in memory.
- A build of 134,400 requests stays within `-Xmx512m`: perf hub's backend heap is the first host (perf hub spec 010,
  FR-011).
- The report stays one self-contained HTML, with no network.

**Scale/Scope**: Runs up to 134,400 requests are measured, and larger runs stream. One tool adapter (k6) now.
Gatling is later, by the same contract.

## Constitution Check

*Against the amended constitution 2.0.0 (pending merge). Re-checked after Phase 1 design: still passes, no deviation.*

| Principle | How this plan meets it | Gate |
|---|---|---|
| I. Go-ahead | This plan writes only under `specs/`. Code starts with `/speckit-implement`, on the owner's go. | PASS |
| II. Follows JMeter | Every tool's samples go through the one engine, so the Aggregate Report rules apply (R07 over the k6 fixtures' JMeter-format copy). A tool's own elapsed time and failure are stated per tool in the format docs (data-model.md, "Per-tool meaning"). The words table (decision 7) is in contracts/page-words.md. | PASS |
| III. Tests are the gate | Each stage is red-first. Non-JMeter fixtures are committed data with a JMeter-format copy for R07. The conformance set joins regression. Counts are read from each runner. | PASS |
| IV. One artifact | Inside JMeter the shaded JAR stays the only deployed file. Outside JMeter the same JAR runs, with the libraries from a JMeter install or from the standalone bundle's `lib/` (research R6). `JMeterUtils` leaves the generator (R5). | PASS |
| V. Branch, one version | `feat/001-report-for-every-load-tool`. The format version is one constant, `RunDataFormat.VERSION`, written into the schema by the build (R9). | PASS |
| VI. Every setting | `build-report` takes every `webinsight.*` property as `--property key=value` and from `--properties FILE`, over the defaults (R5). The docs ship in the same change. | PASS |
| VII. One engine | Producers write samples only (data-model.md). Transactions' figures, identity and window are the engine's. Verdicts are the annotations file's. A tool's checks are shown in its tab and never judged. | PASS |
| Technical Constraints | The plugin's two paths and the run-data reader feed one engine through one feed (R3). | PASS |

## Project Structure

### Documentation (this feature)

```text
specs/001-report-for-every-load-tool/
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R12
├── data-model.md        # Phase 1: run data records, fields, per-tool meaning
├── quickstart.md        # Phase 1: validation scenarios
├── contracts/
│   ├── run-data-format.md      # the common format (JSON Lines), version 1.0
│   ├── build-report-cli.md     # java -jar ... build-report, and wir-collect --run-data
│   ├── k6-adapter.md           # how k6's files become run data
│   └── page-words.md           # JMeter's words and each tool's
└── checklists/requirements.md
```

### Source Code (repository root)

```text
jmeter-report-core/src/main/java/com/jmeterwebinsightreport/core/
├── rundata/                     # NEW: the common format
│   ├── RunDataFormat.java       #   VERSION, the versions read, record type names
│   ├── RunSample.java           #   one sample (request, or one pass of a transaction or group)
│   ├── RunHeader.java           #   the run record: tool, tool version, test name, format version
│   ├── ToolRecords.java         #   check, threshold and counter records (a tool's own measures)
│   ├── RunDataReader.java       #   streams JSON Lines (.gz too) into a RunDataSink
│   ├── RunDataWriter.java       #   writes the same records (the JMeter paths with rundata=true, the adapter)
│   └── SampleFeed.java          #   THE feed: RunSample -> LiveStatisticsEngine, used by every path
├── parser/JtlParser.java        # ParsedSample.feed routes through SampleFeed (no behaviour change)
├── parser/TransactionRows.java  # unchanged: still infers nesting for JTLs, which carry no parents
├── stats/LiveStatisticsEngine.java  # + transaction elapsed from children when a producer leaves it out
├── model/TestMetadata.java      # + tool {name, version}; jmeterVersion kept for JMeter runs
└── model/ToolMeasures.java      # NEW: aggregates of a tool's own records (k6 tab)

jmeter-report-core/src/main/java/com/jmeterwebinsightreport/core/adapter/k6/   # NEW (decision 8)
├── K6ResultsReader.java         # k6 CSV (unix_milli, vu/iter metadata) -> RunSample stream
└── K6SummaryReader.java         # k6 summary.json -> threshold, check and counter records

jmeter-web-insight-report/src/main/java/com/jmeterwebinsightreport/
├── WirCollectLauncher.java      # + dispatch: build-report | collect (default); + lib/ beside the JAR
├── BuildReport.java             # NEW: the JMeter-free command; calls ReportRebuild's parse/companions/generate
├── WirCollect.java              # + --run-data FILE (exactly one of --jtl, --run-data)
└── report/
    ├── exporter/ReportRebuild.java          # + parse(RunData) beside parse(JTL)
    ├── generator/WebInsightReportGenerator.java  # comparison thresholds from config's props, not JMeterUtils
    ├── listener/WebInsightReportListener.java    # SampleResult -> RunSample -> SampleFeed; rundata=true writes
    └── template/                                 # tool words; k6 tab data
jmeter-web-insight-report/src/main/resources/
├── templates/report.html        # words through one table; the tool's tab
├── static/js/report.js          # the k6 tab's rendering
└── static/schema/run-data.schema.json   # NEW: published, held to the Java model by a test

test-plans/run-data/             # NEW: conformance set (committed k6 results, expected JSON, JMeter-format copies)
docs/                            # run data format, build-report, k6 page; Instructions; FEATURES
assembly/standalone.xml          # NEW: web-insight-report-<v>-standalone.zip (the JAR + lib/)
```

**Structure Decision**: The existing two modules. The format, the feed and the k6 adapter go into
`jmeter-report-core`, which has no JMeter import. The command and the page go into the plugin module. There is no new
module and no second engine.

## Stages (each ends green: the module's tests, then sanity, then regression)

1. **One feed.** Add `RunSample` and `SampleFeed`, and route `JtlParser.ParsedSample.feed` and the listener through
   them.
   - Red first: a test that feeds one JTL both ways and compares the `ReportData`.
   - Gate: every fixture, R07, the goldens and `RunIdentity` unchanged (SC-001).
2. **Run data.** Add the format (contracts/run-data-format.md), the reader, the writer and `webinsight.report.rundata`.
   - Red first: the round trip. A JTL → run data → report gives the same CI/CD JSON, `runId` included, as JTL →
     report, over every golden input and E31's shape.
3. **Transactions from parents.** The engine derives a transaction's elapsed time from its direct children when the
   producer leaves it out; the rest of its wall time is idle (spec, Edge Cases).
   - Red first: the shapes fixture's nested groups.
4. **No JMeter.** Add `build-report`, `wir-collect --run-data`, properties without `JMeterUtils`, the launcher's
   `lib/` beside the JAR, and the standalone bundle.
   - Red first: a config scenario that runs both commands with `JMETER_HOME` unset and only the bundle's `lib/`, then
     asserts that no class of `org.apache.jmeter` was loaded (`-verbose:class`) (FR-015).
5. **The k6 adapter.** Add `build-report --from k6`.
   - Conformance fixtures: K1, the shapes test (every k6 shape: groups nested, redirects, status 0, setup and
     teardown); K2, petstore-checkout SMOKE with both settings; K3, an old-format k6 file with whole seconds and no
     vu/iter, for the "older data" edge.
   - Each fixture has its JMeter-format copy, written by the research's reference converter (`k6_to_jtl_v3.py`, an
     independent implementation), so R07 holds it (SC-002, SC-005).
6. **The tool's words and tab.** Add `TestMetadata.tool`, the words table (contracts/page-words.md), the k6 tab, and
   "not measured" cells.
   - Red first: a Playwright spec that sweeps a k6 report for JMeter's words outside the two exceptions (SC-004), and
     the k6 tab's numbers against the adapter's inputs.
7. **The Server tab from run data.**
   - Red first: a committed server CSV aligned to a k6 fixture's window, built through `build-report` and through
     `wir-collect --run-data` + `build-report --servermetrics`. The Server tab is compared with a JMeter fixture's
     over the same window and server data (SC-003).
8. **Docs, schema and measurement.**
   - The format's docs and JSON Schema (FR-010, FR-014), the `build-report` page, the k6 page, FEATURES, Instructions
     and CLAUDE.md.
   - SC-006 and the build-time goal measured on the owner's PC and reported.

Release: a new MINOR version (JMeter behaviour unchanged, new inputs and commands). The number and timing are the
owner's call at release time.

## Names for the owner to approve with this plan

| Thing | Proposed name |
|---|---|
| The common format | **run data** (file `<report name>-run-data.jsonl.gz`) |
| JMeter setting that writes it | `webinsight.report.rundata` (`true`/`false`, default `false`) |
| The JMeter-free command | `java -jar jmeter-web-insight-report-<v>.jar build-report ...` |
| Its main options | `--run-data FILE`, `--from k6`, `--k6-results FILE`, `--k6-summary FILE`, `--out DIR`, `--annotations FILE`, `--servermetrics FILE`, `--property key=value`, `--properties FILE` |
| The collector's new option | `wir-collect --run-data FILE` (beside `--jtl FILE`) |
| The bundle for machines with no JMeter | `jmeter-web-insight-report-<v>-standalone.zip` (the JAR plus `lib/`) |
| The k6 tab | `k6` |

## Complexity Tracking

No violation to justify.
