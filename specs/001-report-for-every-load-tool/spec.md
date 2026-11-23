# Feature Specification: One Web Insight Report for every load tool

**Feature Branch**: `feat/001-report-for-every-load-tool`

**Created**: 2026-11-23

**Status**: Draft. The direction and decisions were agreed by the owner on 2026-11-23, and a cross-spec review was
applied the same day. Building waits for the owner's go-ahead (Principle I) and for the constitution amendment in
Dependencies.

**Input**: the owner, 2026-11-23, in a design discussion that started from perf hub showing this report for k6 runs:

- "K6 is the one that should write such a report with his own KPIs, with his own graphs. If K6 uses JMeter, then K6
  becomes obsolete and hard to maintain, and there is no point to use K6. ... The whole point of the plugin is not to
  use two tools."
- "I actually wanted to add later on another tools like Gatling, and I would like the report to be a unified experience
  no matter the tool. ... I want a unified report experience per test."
- "mostly this is due to the standardization I want for the server tab, which is for the backend asserts."
- "I think both 1 and 2. This way, k6 tests still get the report without perf hub, so is jmeter via plugin. But for perf
  hub, unified engine."
- On the proposal below: "This sounds much better to me."

## Owner decisions (2026-11-23)

1. **Two layers, one report.** Each load tool has its own plugin that writes the report without perf hub: JMeter through
   this plugin, and k6 through a k6 plugin (a later project). Perf hub builds the report with this report's one engine.
   It starts with k6 runs (perf hub spec 010), and its JMeter runs follow in a later perf hub spec. Every layer shows the
   same report.
2. **The numbers and the verdict are worked out once, by this report's engine.** A producer writes a run's samples in one
   common format, and nothing else: this plugin, a k6 plugin, or perf hub for any tool (refined by decision 8: k6's
   samples are written by this report's own k6 adapter, which perf hub calls). Every figure (counts,
   percentiles, rates, the whole-run row, transactions, time buckets, the run's identity) is worked out by this report's
   engine from those samples. The same data always gives the same report.
3. **One method of numbers for every tool:** JMeter's Aggregate Report (Principle II), applied to k6 and to any later
   tool, so a JMeter run and its k6 twin are measured the same way.
4. **The verdict is the test's asserts, for every tool.** The client asserts and the server asserts of the test's
   annotations file give the client verdict and the server verdict. They stay two verdicts, with no rollup, as today. A
   tool's own checks (k6's thresholds and checks, JMeter's assertions) never decide a verdict directly.
5. **The Server tab first.** Server data and server asserts are tied to the run through the common format, not through
   a JMeter results file, and are judged the same way for every tool. This is the main reason for the change.
6. **Compare for non-JMeter runs, and across tools, is later, not in this version** (answered 2026-11-23).
7. **A non-JMeter run uses its tool's own words** (answered 2026-11-23). On a k6 run:

   | JMeter's word | k6 run |
   |---|---|
   | Samplers | Requests |
   | Thread | VU |
   | Max Threads | Max VUs |
   | Transaction | Group |

   JMeter runs keep today's words.
8. **The k6 translation lives in this report, as an input adapter** (answered 2026-11-23, during the plan). The report's
   command reads k6's own results file (and its end-of-test summary) and turns it into the common format. Perf hub and a
   k6 user without perf hub both call that command, so there is one k6 translation, tested here against JMeter's
   Aggregate Report. Perf hub writes no common format of its own.
9. **JMeter runs write the run's data file only when a setting asks** (answered 2026-11-23):
   `webinsight.report.rundata=true`. Off by default, so a JMeter user sees no change.
10. **A tool's own measures get a tab named after the tool** (answered 2026-11-23): a "k6" tab after Server and before
    Notes, only on k6 runs. JMeter reports don't change.

## What was checked (2026-11-23)

The research record is in the perf hub folder, `tmp/k6-web-insight-report/` (git-ignored there):
`k6-native-research.md`, the agents' output beside it, and `two-specs-review-2026-11-23.md`.

- **What already works without JMeter's classes.** `jmeter-report-core` imports nothing of JMeter. In the plugin module
  only six files do: the listener, its GUI, the `-g` exporter and the rebuild helpers. Two ties remain:
  - the generator reads `webinsight.compare.*` through `org.apache.jmeter.util.JMeterUtils::getProperty`
    (`WebInsightReportGenerator.java:930`), whose absence is an Error nothing catches;
  - the shaded JAR carries no Jackson, SLF4J or Commons Math 3. They are `provided`, from JMeter's `lib/`.
- **What the collector reads from a JMeter results file.** `wir-collect --jtl` reads it for two things: the run's
  window, and the counts its identity (`RunIdentity`) hashes. The test's name comes from `--test-name` (or the report
  title on `jmeter -g`), and the ramp-up from `--rampup`. `RunIdentity` hashes the test name, the start, the duration and
  the top-level sample and error counts.
- **The page is not data-driven yet.** The Thymeleaf template writes the header, the tiles, the Samplers rows, the
  Errors tables and the Notes on the server, and `report.js` reads the Samplers table back from the page. About 65% of
  `report.js` is the client and server assert machinery, kept equal to the Java engine by parity tests. The page's data
  has no version or schema.
- **The words are JMeter's:** the header "JMeter <version>", Sampler, Max Threads, Transaction Controller, Thread, and the
  footer.
- **A k6 run's samples fit this report's model.** This was checked in perf hub during planning. With millisecond times,
  each request's virtual user and pass, and a group pass written as JMeter writes a Transaction Controller (the sum of
  its requests' times, the rest as idle time), a k6 run converted into rows this report reads correctly. WIR's referee
  then matched JMeter's Aggregate Report in 198 of 198 checks.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The same Server tab and server verdict, whatever the load tool (Priority: P1)

A tester runs the same test with JMeter and with k6. The backend is measured for both runs, and judged by the same
server asserts. Each run's Server tab shows the same components and server asserts, judged by the same rules over that
run's own window.

**Why this priority**: the owner's main reason for the change. Server asserts must not depend on which tool drove the
load.

**Independent Test**: build two reports from two runs' data in the common format, one written as a JMeter run's and one
as a k6 run's, with the same window, the same server data and the same annotations. Compare their Server tabs.

**Acceptance Scenarios**:

1. **Given** a run's data in the common format and an annotations file with server asserts, **When** server data is
   collected, **Then** the collector takes the run's window, identity and test name from that data, with no JMeter
   results file.
2. **Given** two runs' data from different tools, with the same window and the same server data, **When** their reports
   are built, **Then** both Server tabs show the same components and server asserts, the same states and the same server
   verdict.
3. **Given** a JMeter run as today, **When** its report is built, **Then** its Server tab is unchanged: every existing
   server fixture passes, and `wir-collect --jtl` and the Rebuild button work as today.

---

### User Story 2 - One report from any producer's data (Priority: P1)

Whoever writes a run's samples, the report built from them is the same:
- this plugin inside JMeter;
- this report's k6 adapter, run by perf hub or by a k6 user (decision 8);
- later, a k6 plugin inside k6.

A JMeter user notices no change.

**Why this priority**: it is what makes "both layers, one report" true. Without it, a k6 run's report would differ
between standalone k6 and perf hub.

**Independent Test**: write one run's samples twice, through this plugin and through a second producer of the same
samples (a test producer), build both reports, and compare. Hold every JMeter fixture to today's figures.

**Acceptance Scenarios**:

1. **Given** a JMeter run, **When** its report is built, **Then** every number is the same as today: the Aggregate Report
   reference (R07) holds for every fixture, through both the listener and the export path.
2. **Given** the same samples written by two producers, **When** both reports are built, **Then** their numbers, verdicts
   and text are identical.
3. **Given** a k6 run's data, **When** its report is built, **Then** each request-name row equals JMeter's Aggregate Report
   computed on the same samples (the referee).
4. **Given** a data file of a format version this report doesn't read, **When** a report is built from it, **Then** the
   report names the version it found and the versions it reads, and shows no numbers.

---

### User Story 3 - The report names the tool, uses its words and shows its own measures (Priority: P2)

A k6 run's report says k6, uses k6's words, and shows k6's own measures beside the common ones. A JMeter run's report
stays exactly as today.

**Why this priority**: the owner asked for k6's "own KPIs, own graphs". It doesn't block the Server tab, but a report
that calls a k6 run a JMeter run would be wrong.

**Independent Test**: build a report from a k6 run's data and from a JMeter run's data, and read each header, label and
tool section.

**Acceptance Scenarios**:

1. **Given** a k6 run's data, **When** its report opens, **Then** the header names k6 and its version, and the labels use
   decision 7's words (Requests, VU, Max VUs, Group).
2. **Given** a k6 run's data, **When** the reader opens the k6 section, **Then** it shows:
   - k6's thresholds and checks, each with its own pass or fail;
   - k6's own group duration (wall time, think time included);
   - the six HTTP timing parts (blocked, connecting, TLS, sending, waiting, receiving);
   - iterations and dropped iterations.
3. **Given** a measure that a tool doesn't provide (bytes per request for k6), **When** the report shows that column,
   **Then** it says "not measured", not 0.

---

### User Story 4 - A new tool by writing samples only (Priority: P3)

A developer adds a load tool (Gatling, for example) by writing its runs' samples in the common format, with no change to
the report.

**Why this priority**: the owner plans more tools. It is the last step, and it costs nothing more if Stories 1-3 hold.

**Independent Test**: following only the documentation, write a small run's samples for an imaginary tool and build its
report.

**Acceptance Scenarios**:

1. **Given** the documented format and its examples, **When** a developer writes a run's samples for a new tool, **Then**
   the report shows it with the common tabs and the tool named in the header, with no change to the report.
2. **Given** a producer's output, **When** the conformance set is run on it, **Then** it compares that producer's report
   with the expected CI/CD JSON (counts, verdicts, every assert) and with each row's text.

---

### Edge Cases

- **Groups and transactions:** one pass of a tool's group is a transaction sample. Its elapsed time is the sum of its
  requests' elapsed times, and the rest of its wall time is idle time, as JMeter's Transaction Controller records it by
  default. It is counted once in the whole-run figures. The tool's own group duration (k6's `group_duration`, think time
  included) is shown in the tool's section.
- **What a failed request is,** and how long a request took, are the tool's own, written in the samples:
  - **JMeter:** the sample's success flag (an assertion failure included, as JMeter's Aggregate Report counts it) and
    its elapsed time.
  - **k6:** `http_req_failed` (k6's expected responses) and `http_req_duration` (sending, waiting and receiving, without
    connecting and TLS). A failed k6 check doesn't fail a request.

  The error-rate asserts judge that.
- **Older data:** whole-second times, or no virtual-user numbers. The report uses what it has: no transactions without
  virtual users, and times rounded to the second. It says so in its notes.
- **Large runs:** the external data file, as today (Principle IV: no network). The run's identity keeps the page and its
  data file together.
- **The run's window and identity:** the window runs from the first sample's start to the last sample's end, whatever
  the tool. The identity is worked out from the samples (FR-013), never written by a producer.
- **Server clock:** server data whose clock differs from the load generator's uses the existing clock-offset setting,
  whatever the tool.
- **Request names:** an annotations file names requests the way the test does. A k6 test and its JMeter twin use the
  same names.
- **The verdict:** a k6 run whose thresholds fail but whose test asserts pass shows PASS, with the failed thresholds
  marked in the k6 section. The tool's own exit code is the tool's business.

## Requirements *(mandatory)*

### Functional Requirements

**The format and the engine**

- **FR-001**: There MUST be one common format for a run's data, documented and versioned. It carries:
  - the producing tool and its version, and the test's name (the report title);
  - every sample the tool recorded: a request, or one pass of a transaction or group. Each has its name, its start and
    elapsed time in milliseconds, success or failure with its code and message, the virtual user (thread) and pass that
    ran it, the sample it ran inside, and whether a listener received it on its own (top level). Its bytes, latency and
    connect time are included when the tool measures them, and are absent, never 0, when it doesn't;
  - active users over time;
  - the tool's own measures (for k6: thresholds and checks with their results, group durations, the timing parts,
    iterations);
  - server data, when it was collected.
- **FR-002**: The report MUST be built from that data, the test's annotations file, an optional baseline and the report
  properties, and nothing else. Every figure MUST be worked out by this report's one engine from the samples: counts,
  percentiles, rates, the whole-run row, transaction figures, time buckets and the run's identity. A producer's own code
  MUST NOT work them out. If the plan has producers aggregate for size, they MUST use this report's own aggregation,
  not code of their own. The same data MUST give the same report, with the same numbers, verdicts and text, whichever
  producer wrote it.
- **FR-003**: Numbers MUST follow JMeter's Aggregate Report rules for every tool (Principle II), with exact percentiles.

**The verdict and the Server tab**

- **FR-004**: The client verdict and the server verdict MUST be the test's asserts (the annotations file) for every tool.
  They MUST stay two verdicts, with no rollup, as today. A tool's own checks MUST appear in that tool's section and MUST
  NOT decide a verdict directly. What counts as a failed request is the tool's own (Edge Cases).
- **FR-005**: Server data collection and server asserts MUST take the run's window, identity and test name from the common
  format, with no JMeter results file required. JMeter runs' Server tabs MUST stay as they are, and so MUST
  `wir-collect --jtl`, `jmeter -g` with server data, the Rebuild button, and the CI/CD JSON's `window` and `testName`.

**JMeter runs and the words**

- **FR-006**: This plugin's two paths (the listener and the export path) MUST hand their samples to the engine in the
  common format's sample model, and MUST write the run's data file when `webinsight.report.rundata=true` (decision 9).
  JMeter users MUST see the same reports as today: every measure where it is, every fixture, R07 and every existing gate.
- **FR-007**: The report MUST name the producing tool and its version. On a non-JMeter run, these MUST NOT appear: "JMeter
  <version>", Sampler, Max Threads, Transaction Controller, Thread. Two exceptions: the product's name, and the one
  sentence saying the numbers follow JMeter's Aggregate Report rules. The labels MUST be the tool's own words (decision
  7). A JMeter run's report MUST keep today's words.
- **FR-008**: On a non-JMeter run, the report MUST show the tool's own measures in a tab named after the tool (decision
  10): for k6,
  thresholds and checks, group durations, the six HTTP timing parts, iterations and dropped iterations. A measure the
  tool doesn't provide MUST read "not measured", not 0.

**Packaging, documentation and versions**

- **FR-009**: The report MUST stay one self-contained file that opens with no server or network, with the external data
  file for large runs as today (Principle IV).
- **FR-010**: The common format MUST be documented in `docs/` with examples, including per tool what a sample's elapsed
  time and a failed request are (Edge Cases). A conformance set MUST check any producer against the expected CI/CD JSON
  (counts, verdicts, every assert) and each row's text, as R07 holds a JMeter fixture.
- **FR-011**: Data of a format version this report doesn't read MUST be refused with a message naming the version found and
  the versions read, and no numbers shown.
- **FR-012**: Every report property MUST stay settable per run, whoever produces the data (Principle VI). A producer
  outside JMeter passes them directly, over the defaults.
- **FR-013**: The run's identity MUST be worked out by the engine from the run's data by one documented rule, never written
  by a producer.
  - The rule is today's `RunIdentity` material: the test name, the start, the duration, and the top-level sample and error
    counts. A JMeter run therefore keeps the id it has today, and every committed payload still matches.
  - Server data MUST NOT enter the identity.
  - The same data MUST give the same id, and different runs MUST give different ids, for every producer.
- **FR-014**: Each version of this report MUST read every earlier released version of the common format. The documentation
  MUST say which versions it reads and which it writes.
- **FR-015**: The report MUST be buildable, and server data collectable, by a producer outside JMeter: with no JMeter
  classes, with its own Jackson, SLF4J and Commons Math 3 (or the host's, as stated), and with the report properties read
  from the producer rather than from `JMeterUtils`.
- **FR-016**: The report's command MUST read k6's own results file (with millisecond times and each request's virtual
  user and pass) and, when given, k6's end-of-test summary, and build the report from them through the common format
  (decision 8). The same files MUST give the same report whoever runs the command.

### Key Entities

- **A run's data (the common format)**: the run's samples, and what the report needs beside them, written by a producer
  (FR-001). It is versioned.
- **Producer**: whatever writes a run's data: this plugin (in JMeter), this report's input adapters (k6 now, Gatling
  later), or a k6 plugin (in k6, later).
- **The engine**: this report's code that works out every figure, the identity and both verdicts from a run's data. It is
  the same for every producer.
- **The test's asserts**: the annotations file. Its client and server asserts judge every run of the test, whatever the
  tool.
- **A tool's own checks**: k6's thresholds and checks, JMeter's assertions. They never decide a verdict directly. What
  counts as a failed request is the tool's own, written in the samples (Edge Cases).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every existing fixture and gate passes unchanged: sanity, regression, R07 and the goldens. JMeter users see no
  change, and the counts are reported.
- **SC-002**: In the conformance set, a k6 run's report matches JMeter's Aggregate Report for the same samples in 100% of
  request-name rows (the referee).
- **SC-003**: The same server data with two producers' run data gives identical Server tabs: components, server asserts,
  states and server verdict (100%).
- **SC-004**: A k6 run's report shows none of FR-007's JMeter words outside its two exceptions, checked by a test.
- **SC-005**: A second producer is added without any change to the engine or the page. The k6 adapter, run over the
  conformance set's committed k6 results, gives reports that match the expected ones in 100% of rows and verdicts. Perf
  hub (its spec 010) confirms it later, as perf hub's own check.
- **SC-006**: A report of 134,400 requests (100 times perf hub's run #52) shows its Summary tab with every chart drawn
  within 5 seconds, opened over `file://` in Playwright's Chromium on the owner's PC. It is no slower than today's report
  of the same size measured the same way before the change. Both figures are reported.

## Assumptions

- **Where the engine runs for each producer.** Decided in the plan (research R1): the Java engine, for every producer,
  with the page kept as it is. CI outputs (JUnit XML, the CI JSON, exit codes on a failed assert) need the verdicts
  without a browser, and with the k6 adapter in this report nothing needs the page to work out figures. A k6 user needs
  a Java runtime to run the command. FR-002 holds: one definition of every figure.
- **Numbers.** JMeter's Aggregate Report rules are the one method (Principle II). A k6 run's p95 can therefore differ
  slightly from k6's own end-of-test summary, which uses interpolation. k6's own report stays available wherever it is
  shown today.
- **The first non-JMeter producer is this report's own k6 adapter** (decision 8), from k6's own results file with
  millisecond times and each request's virtual user. Perf hub calls it (its spec 010). A k6 plugin that writes the data
  inside k6 is a later, separate project, and is optional now that the command reads k6's file.
- **Out of scope:**
  - Compare for non-JMeter runs and across tools (decision 6, a later version);
  - the k6 plugin itself;
  - a Gatling producer;
  - renaming the project away from "Jmeter";
  - any change in perf hub.

## Dependencies

- **A constitution amendment, approved by the owner, before building** (`/speckit-constitution`, on its own branch):
  - **Technical Constraints:** "Target: Apache JMeter 5.6.3. Two ingestion paths ... feed one generator". The common
    format becomes the one input, with producers outside JMeter.
  - **Principle II:** its rules apply to every tool's data. Its title, "The Report Follows JMeter", stays true for the
    numbers.
  - **Principle III:**
    - a non-JMeter fixture is committed data (no tool runs in the suites), with a JMeter-format copy of its samples for
      R07;
    - the conformance set joins the gates.
  - **Principle IV:** the engine, the page and the format are used by producers outside this JAR. The packaging brings
    Jackson, SLF4J and Commons Math 3 (or names the host's), and reads report properties from a source other than
    `JMeterUtils` (FR-015).
  - **Principle V:** the format's version is a data-contract version, separate from `<revision>`.
  - **Principle VI:** the configuration precedence for producers outside JMeter: properties passed by the producer,
    over the defaults.
  - Level: **MAJOR, 2.0.0**, chosen by the owner on 2026-11-23. Drafted on branch
    `docs/constitution-report-for-every-load-tool`, with a new Principle VII, "One Engine for Every
    Load Tool".
- **Consumers:** perf hub (spec 010, the first caller of the k6 adapter), k6 users without perf hub, a later k6 plugin,
  and a Gatling adapter.
