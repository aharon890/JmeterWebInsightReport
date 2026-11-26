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

- [X] T008 Fixtures CMPK (K2 against K2's own run data: build-report twice, no JMeter) and CMPX (K2 against a JMeter
  run of E02's plan at E02's load) in `generate-cmp-fixture.sh` (CMP and CMP31 run exactly as before), in
  `FIXTURE_IDS` and `REQUIRED_FIXTURES`, generated side by side with CMP and CMP31 in R04, and held by R07 (each side:
  K2's by its JMeter-format copy and the run data beside it, CMPX's baseline by its own files). The generator also
  checks the pair reads as it should (`"sameTool":true` or `false` in the payload). `run-all-tests.sh --only` refuses
  the two as it refuses CMP and CMP31.
- [X] T009 Playwright, red then green. `compare-tab.spec.ts`: CMPK (2 tests: judged, thresholds and OK badges, "Request
  Comparison"; Recv KB/s "not measured" with no change) and CMPX (6: title, legend and the connection line; no
  badge, colour or control; "baseline → current" with the grey difference, counts exact; "only in" rows; chart series
  named by tool, the baseline dashed; What Changed without judging). `whole-run-totals.spec.ts`: CMPK joins the
  judged Σ Total loop, CMPX gets its own two-sided Σ Total test, What Changed's Σ rows over all four comparison
  fixtures, and the per-request Compare check reads a two-tool cell's current side. On the stage-2 JAR (today's page)
  10 of the 18 CMPK/CMPX tests failed (among them What Changed's baseline row reading k6's "not measured" for a
  JMeter baseline, a bug the two-tool pair exposed); on the new page 18 of 18 pass, and the every-reader,
  xss-escaping and table-toolbar sweeps pass on both fixtures.
- [X] T010 `report.js`: `comparisonTwoTools`, `pairWords`, `baselineNotMeasured`, `comparisonConnectionNote`; the
  Compare table's two-tool cells (`comparisonPairCellHtml`, `comparisonBaseFigures`), statuses "only in the
  baseline" / "only in this run" and no grouping; the title, legend and notes; no thresholds and no Apply on two
  tools; chart series named by tool; What Changed's per-request lines (`whatChangedRequestText`).
  `sigmaTotalCellTexts` takes whose "not measured" it writes, and `deltaText` is `inlineDelta`'s text, shared by
  both readings. Recv KB/s reads "not measured" on a k6 run's Compare rows (it read 0.00). The template gives the
  table's title an id and adds the legend and the notes; the words table gains 10 keys (`compare*`,
  `whatChangedEach`); report.css the two-tool cell, and a table that takes its cells' widths and scrolls sideways.

## Stage 4: docs and the dated notes

- [X] T011 Tutorial 06 (what a baseline can be; one tool and two tools; a section "A pair of two tools"), 07 (What
  Changed on two tools), 17 (`--baseline` row, and Compare for k6 runs with an example), 18 (run data as a baseline),
  FEATURES (Run Comparison: setup and Two tools; Other load tools: Compare), `build-report --help`'s `--baseline`
  line, and WIR's CLAUDE.md (the four comparison fixtures). `docs-links.test.js`: 274 links pass.
- [X] T012 Spec 001 decision 6, FR-017, its out-of-scope line and `contracts/build-report-cli.md`'s `--baseline` row:
  dated notes (2026-11-26) pointing at spec 004.

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
4. CMPX runs E02's plan itself (`full-load-test.jmx` at E02's load) rather than reading `test-results/E02/results.jtl`:
   R04 generates the comparison fixtures beside the fixture suite that writes E02, so E02's file is not there yet
   when CMPX starts. One more E02-sized JMeter run in R04, side by side, so no longer critical path.
5. A request only one run has shows every figure of that run, `x → —` or `— → x`, not samples and mean alone: every
   cell of a two-tool table then reads "baseline → current" the same way. What Changed writes it as the spec says:
   "only in the baseline (150 samples, mean 107 ms)".
6. The connection-time line is the approved sentence, with its two tools as placeholders in the words table (k6's
   column may not name JMeter, `PageWordsTest`), and it is shown only on a pair of JMeter and k6. FR-005 says "every
   pair of two tools"; of a third tool (run data from another producer) the page does not know how it times a
   connection, so it claims nothing. Reported to the owner as an open question.
7. On a two-tool pair the table's columns take their cells' widths (`table-layout: auto`) and the wrapper scrolls
   sideways, as perf hub's table does: "baseline → current" did not fit the fixed columns of a one-tool table.
