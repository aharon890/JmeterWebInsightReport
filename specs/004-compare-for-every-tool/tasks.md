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

- [ ] T004 `ComparisonResultTest`: a pair of two tools has no regression flag; a same-tool pair is unchanged. Red, then
  green.
- [ ] T005 `BuildReportTest`: a baseline on another tool's run is compared (it was left out). Red, then green.
- [ ] T006 `ReportDataSerializerTest`: the payload names the pair's tools. Red, then green.
- [ ] T007 `ReportRebuild.refuseCrossToolBaseline` removed; the pair named in the payload.

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
