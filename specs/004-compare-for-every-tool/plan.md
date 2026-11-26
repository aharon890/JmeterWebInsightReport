# Plan: Compare for every load tool, and across tools (spec 004)

**Status**: Draft with the spec, for the owner's approval. No code before it.

## Approach

One change in the reader, one in the page, nothing in the engine:

1. **Read any baseline** (`core`): a `BaselineReader` chooses by the file's first bytes. A JMeter CSV results file
   (`timeStamp,` header, or no header with a numeric first cell) or XML (`<testResults`) goes to `JtlParser` as today;
   run data (`.jsonl`, or gzip whose first line is a run data header) goes to `RunDataReader`, the reader
   `build-report --run-data` uses. Anything else: no Compare, one WARNING line naming the file (User Story 3).
   `WebInsightReportGenerator` (lines 185-201) calls it instead of `parser.parse(...)`.
2. **Drop the refusal**: `ReportRebuild.refuseCrossToolBaseline` and its call go; spec 001 decision 6 and FR-017
   get a dated note.
3. **Name the pair**: the Compare payload gains `baselineTool` and `currentTool` (`TestMetadata.toolName`, empty =
   JMeter) and `sameTool`. Same tool: today's payload and page, byte for byte apart from the two names.
4. **Two tools on the page** (`report.js`, the Compare tab and What Changed): the title, the "baseline → current"
   cells with a grey difference, no badges, colours or threshold controls, the "only in the baseline / this run"
   rows, the connection-time line, and What Changed without the regressed and improved lists. The regression flags
   are not computed for the pair (`ComparisonResult` gets the flag, so the page and What Changed read one answer).
   The words come from `page-words.json` (the current run's column; the baseline named by its tool).
5. **build-report** `--baseline` help and docs (FR-006).

## Tests, red first

- `BaselineReaderTest`: a JTL CSV, a JTL XML, run data plain and gzipped, a k6 run's run data, and a file that is
  none of them (refused with the sentence); chosen by content, not by name.
- `ComparisonResultTest`: a pair of two tools has no regression flags; a same-tool pair is unchanged.
- Fixtures: **CMPK** (K2 against K2's own run data: User Story 1) and **CMPX** (K2 against E02's JTL: User Story 2),
  made by `generate-cmp-fixture.sh` beside CMP and CMP31, added to `FIXTURE_IDS` and `REQUIRED_FIXTURES`.
- Playwright: `compare-tab.spec.ts` gains the k6 pair (badges, colours and words) and the two-tool pair (title, two
  numbers per cell, no badge, colour or control, the connection line, "only in" rows); `whole-run-totals.spec.ts`
  holds CMPK's and CMPX's Σ Total rows to each run's own; `every-reader-same-page.spec.ts` covers both.
- Unchanged: goldens (R06), R07, R08, `PreChangeBaselineTest`, CMP and CMP31.
- Gate: `./test-plans/scripts/run-regression.sh` green; the release tier before a release.

## Stages

1. The reader and its tests (no visible change). 2. The refusal dropped, the pair named, `ComparisonResult`'s flag.
3. The page and What Changed, with CMPK and CMPX. 4. Docs (Tutorials 06, 17, 18, FEATURES, `--help`) and the dated
notes. 5. Regression green, PR; release 4.8.0 when the owner says; perf hub's runner image after it.

## Risks

- A JTL with no header and an odd first column: the reader keeps `JtlParser`'s own rules for it, so whatever it reads
  today it still reads.
- Request names that differ between a JMeter plan and its k6 twin read as "only in"; stated in the docs.
