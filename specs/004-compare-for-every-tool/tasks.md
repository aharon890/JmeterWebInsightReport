# Tasks: Compare for every load tool, and across tools (spec 004)

Progress log of the build, stage by stage as `plan.md` lists them. Each test was written first and seen failing on the
code before the change (red), then passing after it (green). A deviation from the plan is stated with its reason.

## Stage 1: the reader (no visible change)

- [X] T001 `BaselineReaderTest` (core): a JTL CSV, a JTL XML, a header-less CSV, run data plain and gzipped, a k6
  run's run data, five files that are none of them (refused with the sentence), each chosen by content and not by
  name. Red (the class did not exist), then green: 7 tests.
- [X] T002 `BuildReportTest.aBaselineThatIsRunDataIsCompared`: red on today's code (the JTL parser read the gzip and
  found no timeStamp column, so no Compare), then green.
- [X] T003 `BaselineReader` (core/comparison), and `WebInsightReportGenerator` reads the baseline through it. A file
  that is neither is one WARNING log line, "Baseline not read, comparison skipped: <file> is neither a JMeter results
  file (...) nor run data (...)", beside today's "Baseline JTL not found" line (H03 greps that one; unchanged).
  `RunDataReader` gained `reportOf` (the run's report data with its tool named), which `ReportRebuild.parseRunData`
  now calls too, so there is one such step; and it opens gzip by its magic bytes instead of by the `.gz` name.
  Java: 808 + 1123 tests, 0 failures (2 skipped, as before).

## Stage 2: the refusal dropped, the pair named, `ComparisonResult`'s flag

- [X] T004 `ComparisonResultTest`: `aPairOfTwoToolsIsComparedWithoutJudging` (P95 up 100%, errors up 9 points and a
  request only the baseline ran: no row and not the whole run flagged, both ways round) and
  `aPairOfOneToolIsJudgedAsBefore` (k6 against k6, whatever the case of the name, and JMeter against JMeter). Red (no
  `isSameTool`), then green.
- [X] T005 `BuildReportTest.aBaselineOnAnotherToolsRunIsCompared` replaces `aBaselineOnAnotherToolsRunIsLeftOut`: a k6
  run with a JMeter results file as its baseline is compared, as a pair of two tools. Red (the "is left out" line),
  then green.
- [X] T006 `ReportDataSerializerTest`: `aPairOfTwoToolsIsNamedByItsToolsAndCarriesBothSidesWithoutThresholds` and
  `aPairOfOneToolKeepsItsPayloadWithTheToolNamed`. Red (no tool names), then green.
- [X] T007 `ReportRebuild.refuseCrossToolBaseline` and its call removed. `ComparisonResult` knows the pair
  (`isSameTool`, `getBaselineTool`, `getCurrentTool`; a tool's name compared as the words table reads it, JMeter for
  none, any case) and a row of a pair of two tools is never a regression. The payload gains `baselineTool`,
  `currentTool` (the words table's `tool` word: "JMeter", "k6"), `sameTool`, and, beside any run another tool
  recorded, `baselineNotMeasured`. A pair of two tools also carries the baseline's figures on every row both runs
  have, and no `comparisonThresholds`. Java: 810 + 1125, 0 failures.

## Stage 3: the page and What Changed, with CMPK and CMPX

- [ ] T008 Fixtures CMPK and CMPX in `generate-cmp-fixture.sh`, `FIXTURE_IDS`, `REQUIRED_FIXTURES`, R04 and R07.
- [ ] T009 Playwright: `compare-tab.spec.ts` (the k6 pair, the two-tool pair), `whole-run-totals.spec.ts` (CMPK's and
  CMPX's Σ Total rows). Red on today's page, then green.
- [ ] T010 `report.js`, the template, the words and the CSS.

## Stage 4: docs and the dated notes

- [ ] T011 Tutorials 06, 17, 18, FEATURES, `build-report --help`'s `--baseline` line.
- [ ] T012 Spec 001 decision 6 and FR-017: dated notes pointing at spec 004.

## Stage 5: regression, PR

- [ ] T013 `./test-plans/scripts/run-regression.sh` green; push; PR (base main). No merge, no version bump, no release.

## Deviations from the plan

1. Stage 1, before writing code: `origin/main` (PR #58, the judge's words on a k6 run) was merged into the branch
   on the coordinator's instruction (2026-11-26), so the build and the gate run on the merged tree. No conflict.
2. `RunDataReader.open` reads gzip by its magic bytes, not by the `.gz` name. Needed for FR-001 ("chosen by content,
   never by name") on a gzipped baseline with any name; a `.jsonl.gz` file reads as before.
3. The payload's `baselineTool` and `currentTool` are the words table's `tool` word ("JMeter", "k6", or the tool's own
   name), not the raw `toolName` the plan named: the page shows them, and this way it needs no second words column.
   A pair of two tools ships no `comparisonThresholds` (it is not judged), and a JMeter pair no `baselineNotMeasured`,
   so a JMeter pair's payload differs from before only by `baselineTool`, `currentTool` and `sameTool`.
