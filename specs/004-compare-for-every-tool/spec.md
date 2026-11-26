# Feature Specification: Compare for every load tool, and across tools

**Feature Branch**: `feat/004-compare-for-every-tool`

**Created**: 2026-11-26

**Status**: Draft, for the owner's approval. Nothing is built before it.

**Input**: the owner's answer of 2026-11-26 to "What should the report's Compare do?": **"k6, and across tools"**. It
lifts spec 001's decision 6 ("Compare for non-JMeter runs, and across tools, is later", 2026-11-23) and its FR-017
(a baseline on a non-JMeter run is refused). perf hub's spec 011 (merged as perf hub PR #39) settled how a JMeter run
and its k6 twin are compared on perf hub's own Compare page (owner's decision 1 of the k6 review, option A); this spec
brings the same rules inside the report.

## Why

A k6 run's report has no Compare tab: a baseline given to it is left out with a sentence. So a k6 user cannot see
what changed since the last run inside the report, and nobody can set a JMeter run beside its k6 twin there. Both
runs already reach the report as the same model (one feed, constitution VII), so the comparison itself is tool-blind;
what is missing is a way to read a non-JMeter baseline, and rules for a pair of two tools.

## Today (research, 2026-11-26)

- The baseline is a JMeter results file only: `webinsight.report.baseline` or `baseline.jtl` in the output folder,
  parsed by `JtlParser` (`WebInsightReportGenerator` lines 185-201). `build-report --baseline FILE` passes the same
  property.
- On a run of another tool, `ReportRebuild.refuseCrossToolBaseline` drops the baseline with a WARNING line.
- `ComparisonResult` compares two `ReportData`, whatever produced them. Its regression flags reach the Compare tab and
  the Notes tab's What Changed only; the CI/CD JSON and JUnit carry nothing of the comparison.
- perf hub already forwards a runner's `webinsight.report.baseline` to a k6 run's report build (`K6ReportSettings`,
  `BASELINE`), and every perf hub k6 run keeps its run data beside its report
  (`report/web-insight-report-run-data.jsonl.gz`, from `--write-run-data`). A JMeter run writes run data with
  `-Jwebinsight.report.rundata=true`.

## User Story 1 - A k6 run compared with an earlier k6 run (Priority: P1)

**Independent Test**: build K2's report with a baseline of K2's own run data (or another k6 run's): the Compare tab
appears exactly as on a JMeter pair, with the k6 run's words.

**Acceptance Scenarios**:

1. **Given** a k6 run and an earlier k6 run's run data as the baseline, **When** the report is built, **Then** the
   Compare tab and What Changed appear, with the regression thresholds, colours and badges a JMeter pair has, in k6's
   words (Requests, VUs).
2. **Given** the same two runs, **Then** every number in the Compare table equals each run's own Requests table row,
   and the Σ Total row each run's own Σ Total row (as `whole-run-totals.spec.ts` holds the JMeter pairs today).
3. **Given** a columns the tool does not measure (k6: Recv and Sent KB/s), **Then** both sides read "not measured",
   never 0 and never a difference.

## User Story 2 - A JMeter run and a k6 run compared, without judging (Priority: P1)

**Independent Test**: build K2's report with E02's results file as the baseline, and E02's with K2's run data: each
Compare tab is titled by its two tools and judges nothing.

**Acceptance Scenarios**:

1. **Given** a pair of two tools, **Then** the Compare tab is titled "<baseline tool> against <this run's tool>"
   (e.g. "JMeter against k6"), each cell shows the baseline's number, an arrow and this run's number with the
   difference in grey, and there is no REGRESSION, OK, NEW or REMOVED badge, no green or red, and no threshold
   controls.
2. **Given** that pair, **Then** one line under the table says that k6's times leave out opening a connection and
   JMeter's include it, so a request that opens one (often the first of each session) reads lower under k6.
3. **Given** that pair, **Then** requests only one run has are listed as "only in the baseline" or "only in this run",
   with samples and mean, instead of NEW and REMOVED.
4. **Given** that pair, **Then** the Notes tab's What Changed lists the two runs' Σ Total rows and each request's
   change, with no "regressed" or "improved" list.
5. **Given** that pair, **Then** the Compare charts overlay both runs as today (the baseline dashed), with the tool
   in each series' name.

## User Story 3 - Any baseline the run has (Priority: P2)

1. **Given** a baseline that is run data (`.jsonl` or `.jsonl.gz`, of any tool), **Then** it is read with the run
   data reader; **given** a JMeter results file, with `JtlParser`, as today. The kind is read from the file's first
   bytes, never from its name.
2. **Given** a file that is neither, **Then** the report is built without Compare and one WARNING line names the
   file and what it expected, as a missing baseline is reported today.
3. **Given** a JMeter pair, **Then** nothing changes: every golden, R07, `PreChangeBaselineTest` and the CMP and
   CMP31 fixtures pass unchanged.

## Requirements

- **FR-001**: A baseline MAY be a JMeter results file or run data of any tool; the reader is chosen by content.
- **FR-002**: `refuseCrossToolBaseline` is removed; spec 001's decision 6 and FR-017 get a dated note pointing here.
- **FR-003**: A pair of one tool keeps today's Compare tab and What Changed exactly, in that tool's words.
- **FR-004**: A pair of two tools is compared without judging (User Story 2): no regression flag is computed for
  display, no threshold control is shown, and What Changed lists changes without judging them.
- **FR-005**: The connection-time line shows for every pair of two tools; the columns a tool does not measure read
  "not measured" on its side.
- **FR-006**: `build-report --baseline` and its help text accept run data; Tutorial 06 (Compare), 17 (other tools)
  and 18 (run data) and FEATURES say what a baseline can be and how a pair of two tools reads.
- **FR-007**: The CI/CD JSON and JUnit stay as they are: the comparison is not in them today, and this spec adds
  nothing to them.

## Success Criteria

- **SC-001**: K2 against a k6 baseline shows the Compare tab with every number equal to each run's own tables.
- **SC-002**: E02 against K2, and K2 against E02, show the two-tool Compare: no badge, colour or threshold control.
- **SC-003**: Every JMeter fixture, golden and R07 reference is unchanged.
- **SC-004**: On perf hub, a k6 runner that names an earlier k6 run's run data as `webinsight.report.baseline` gets
  a Compare tab (checked once, at light load, after the release, with the owner's OK).

## Owner decisions for this spec

1. 2026-11-26: Compare for k6 runs **and** across tools (lifts spec 001 decision 6).
2. Open: how a pair of two tools reads (see the approval page): without judging, as perf hub's Compare (recommended).
3. Open: what a baseline file can be: a JMeter results file or run data of any tool (recommended), or also k6's own
   `results.csv.gz`.

## Assumptions

- Request names match between a JMeter plan and its k6 twin, as in petstore; a name that differs is "only in" one run.
- perf hub needs no change for User Story 1: its runners already pass `webinsight.report.baseline`. Choosing a
  baseline run automatically is perf hub's own later spec, not this one.
