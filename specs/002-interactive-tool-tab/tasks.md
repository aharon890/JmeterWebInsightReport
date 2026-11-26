# Tasks: One table experience for every tab, and an organised, interactive k6 tab

**Input**: [plan.md](plan.md) (approved 2026-11-25), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/run-data-1.1.md](contracts/run-data-1.1.md), [quickstart.md](quickstart.md).

**Tests**: required. Principle III: each phase's test tasks come first and are handed over red; a mutation of the
fix must turn them red again.

**Organization**: by the plan's ten stages, the build order, one commit or more per stage. The stages go to `main` in
three pull requests, so `main` stays releasable and no table is left on two mechanisms: stages 1-4 (the k6 tab, with
the toolkit and Asserts under it), stages 5-7 (the other tables), stages 8-10 (the ticks, US5, the close). A task names
the story it serves (US1-US6) or none.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no unfinished dependency).

## Path conventions

- `core/` = `jmeter-report-core/src/main/java/com/jmeterwebinsightreport/core/`
- `core-test/` = `jmeter-report-core/src/test/java/com/jmeterwebinsightreport/core/`
- `plugin/` = `jmeter-web-insight-report/src/main/java/com/jmeterwebinsightreport/`
- `plugin-test/` = `jmeter-web-insight-report/src/test/java/com/jmeterwebinsightreport/`
- `resources/` = `jmeter-web-insight-report/src/main/resources/`
- `report.js` = `resources/static/js/report.js`; `PW/` = `test-plans/playwright/tests/`; `JSU/` = `test-plans/js-unit/`

## How the tests run (the owner, 2026-11-25)

This is feature development, not a test campaign: "stop running regressions on idle". While a stage is built, only the
tests of what it touches run: `mvn test` for Java, the js-unit tests, and the Playwright specs of the touched tabs
(rebuild and regenerate only the fixtures those specs open). The full `./test-plans/scripts/run-regression.sh` runs
once per pull request, at the gate before the push, and must finish within 15 minutes (the owner, the same day). Its
counts go into the pull request. Every pull request holds the goldens G1-G13, R07, R08 and `PreChangeBaselineTest`
unchanged (FR-035, SC-007). After the merge, `./scripts/build-and-deploy.sh` and `./scripts/check-deploy-freshness.sh`.

---

## Stage 1: Data (FR-050, FR-010, FR-011)

**Goal**: the threshold record carries k6's measured value; the engine has p(99) and k6's request duration; K2 is a
fixture.

- [X] T001 [P] [US1] Red: `core-test/adapter/k6/K6ResultsTest` — K2's Login threshold record carries
  `statistic` `p(95)`, `measuredValue` 779.71796, `metricType` `trend`, `metricContains` `time`; K1's
  `http_req_failed` `rate<0.01` carries `rate`, k6's rate, `rate`, `default`; a `count>=0` carries `count`; a summary
  metric without `values` (or without that statistic) leaves all four out, never 0. The run record carries K2's
  `options.summaryTrendStats` as `trendStatistics`, and K1's (none) leaves it out.
- [X] T002 [P] [US1] Red: the expression parser, `core/rundata/ThresholdExpression` (statistic, operator, limit) in
  `core-test/rundata/ThresholdExpressionTest`: every operator k6 accepts (`<`, `<=`, `>`, `>=`, `==`, `===`, `!=`),
  k6's spacing variants (`p(95) < 600`, `p( 95 )<600`), `avg`, `med`, `min`, `max`, `count`, `rate`, `value`,
  `p(99.9)`, and an expression it cannot read (statistic and limit absent, never guessed).
- [X] T003 [P] [US1] Red: `RunDataSchemaTest` sets the four threshold fields and `trendStatistics`;
  `RunDataReaderTest` reads a 1.0 file (no fields: absent) and a 1.1 file (fields read back exactly); the writer
  writes `"formatVersion": "1.1"`; a 1.0 reader rule (`RunDataFormat.reads`) still reads 1.1.
- [X] T004 [P] [US2] Red: `core-test/model/ToolMeasuresTest` — each `Trend` answers p(99) through
  `ResponseTimeDistribution.getPercentile(99)`; a seventh trend, duration, is fed sending + waiting + receiving per
  sample; on K1, K2 and K3 the duration's average equals the sum of the three parts' averages to the hundredth.
- [X] T005 [US1] `core/rundata/ToolRecords.Threshold` gains `statistic`, `measuredValue`, `metricType`,
  `metricContains`; `RunHeader` gains `trendStatistics`; `RunDataWriter.threshold` and `header` write them only when
  present; `RunDataReader` reads them; `RunDataFormat.VERSION` = `1.1`.
- [X] T006 [US1] `K6Results.toRunData` reads the summary before the header (for `trendStatistics`);
  `writeSummary` fills the four fields from `metrics[m].type`, `.contains` and `.values[statistic]`.
- [X] T007 [P] [US1] Both schema copies, `resources/static/schema/run-data.schema.json` and
  `docs/schema/run-data.schema.json`: the optional fields, identical (`JSU/run-data-schema-published.test.js`).
- [X] T008 [US2] `core/model/ToolMeasures`: p99 on every `Trend`, the duration trend. A JMeter run still has no
  measures, so nothing moves on JMeter.
- [X] T009 K2 becomes a fixture: its `run_test` line in `test-plans/scripts/run-all-tests.sh`, `FIXTURE_IDS` in
  `scripts/lib/common.sh`, `REQUIRED_FIXTURES` in `test-plans/playwright/global-setup.ts`, and an R07 case beside K1's.
- [X] T010 [P] Docs: the run data page and `specs/001-.../contracts/run-data-format.md` say what 1.1 adds; the
  `test-plans/run-data/README.md` names K2 as a fixture.
- [X] T011 Stage 1 commit (6389fd5); its gate is pull request 1's, after stage 4. Done: PR #49 (5830da6).

## Stage 2: The toolkit's pure core (US6, FR-060, FR-063, FR-064)

- [X] T020 [US6] Red: `JSU/table-core.test.js` over `WIR.table.view(records, spec, state)`: search, several chips at
  once (each chip counting every row), the ticks (rows tied to no request stay), group by a key (a group header carries
  counts only), sort by value never by text (`8.3/s` above `20.7/min`), sort inside a group and inside the tree
  (`samplerTreeOrder`), fold, open and closed groups, hidden rows counted, one fixed collation (an English and a German
  reader get one order).
- [X] T021 [US6] `WIR.table.view` in `report.js`, pure, reached from `JSU/harness.js`.
- [X] T022 [P] [US6] SC-010's synthetic run: `test-plans/run-data/tools/gen-many-names.js` writes k6 files with 300+
  request names and about 1,200 thresholds, from a fixed seed. Not a fixture; used in stage 10.
- [X] T023 Commit; the gate is pull request 1's, after stage 4.

## Stage 3: Asserts moves onto the toolkit (US6, FR-061, FR-062, FR-064, FR-065, FR-068)

- [X] T030 [US6] Red, in `PW/asserts-filter-sort.spec.ts` and a new `PW/table-toolbar.spec.ts` (one shared test per
  table, extended stage by stage, SC-008): the Asserts toolbar row holds search, chips, Columns, "Show N hidden" and
  CSV; × on a rule row hides it and "Show N hidden" restores it; headers are buttons, sorted from the keyboard, with
  `aria-sort` on the sorted column only; the view (sort, hidden rows by id and occurrence, hidden columns) is
  remembered under `wir_view_<runId>` and another report opens with its own; the header Status popover is gone.
- [X] T031 [US6] `WIR.table.mount(tableId, spec)`: the toolbar renderer, header buttons, the state adapter, CSV
  as shown (`buildCsvFromArrays`, `downloadCsvFile`), the per-report memory record.
- [X] T032 [US6] The chip renderer moves from `WIR.asserts` to `WIR.status`, through `chipToken` (rule 2).
- [X] T033 [US6] Asserts on the toolkit: its private sort, filter and chip code and the popover are deleted;
  `wir_assertsSort` (global) moves into the per-report record. `JSU/dead-code.test.js` stays green.
- [X] T034 `verify_report_js_rules` rule 1 matches `WIR.table` too.
- [X] T035 [P] Docs: `docs/Tutorial/12-Asserts-Tab.md`, and its screenshots.
- [X] T036 Commit; the gate is pull request 1's, after stage 4. Done: PR #49 (5830da6).

## Stage 4: The k6 tab (US1, US2, US3, FR-001 to FR-013, FR-020 to FR-023, FR-031 to FR-034)

- [X] T040 [US1] Read k6's tracer source for blocked, connecting and TLS; record in research.md R5 whether blocked
  contains connecting and TLS on a new connection. The bar (choice 2 A) is built only if it holds; otherwise the parts
  are drawn side by side.
- [X] T041 [P] [US1] Red: `JSU/tool-tab.test.js` — the page's reading of an expression equals Java's (the same cases as
  T002), always-true (`<statistic> >= 0` that passed; a failed one never), the distance past the limit in the unit
  and as a share (none when the limit is 0), k6's units (ms to two decimals, a rate as a percentage, a counter as a
  count or `/s`, data as B, kB or MB, a custom gauge as the bare number).
- [X] T042 [P] [US1] Red: `plugin-test/.../BuildReportK6Test` and `BuildReportOtherToolTest` read the `chartData.tool`
  block (thresholds with request, statistic, operator, limit, measured, unit, passed, alwaysTrue; timings with
  duration and p99; checks with group paths; group durations; counters; trendStatistics), not table ids in the HTML.
- [X] T043 [US1] Red: `PW/tool-tab.spec.ts` on K1 and K2 — US1 scenarios 1-6 (K2: "1 failed · 49 passed",
  `http_req_duration` open and every other group closed, Login's `p(95) = 779.72 ms` and "179.72 ms over (30%)" on the
  first screen, Group by Request, the failed chip, search, the link to the Requests row with its detail panel); US2
  scenarios 1-5 (13 rows under their groups, the statistic switch, sort by waiting, a row's expansion); US3 scenarios
  1-3 (K1's checks under "Complete Checkout Flow", failed first; the tiles; the strip). Dropped iterations read "0".
- [X] T044 [US1] `plugin/report/template/ToolTab` writes `chartData.tool` (data-model.md); the hard-coded English
  moves into `resources/templates/page-words.json`, jmeter, k6 and other columns (`PageWordsTest`).
- [X] T045 [US1] `WIR.toolTab` on the toolkit's state adapter: thresholds (groupings Metric, Request, Condition,
  Result; the fold; chips; search; sort; ×; Columns; CSV), timings (statistic switch, tree order, the bar, the
  expansion), checks, group durations, tiles, strip; `#tab-tool` keeps a mount point. The tab's choices join its
  remembered view (FR-032).
- [X] T046 [US1] `openRequestRow(name)` (FR-008): switch to Requests, show the row, open its detail, scroll to it.
- [X] T047 [P] `PW/every-reader-same-page.spec.ts`: `'tool'` in `TABS`, the k6 CSVs in its CSV map, the grouped
  states opened.
- [X] T048 [P] `PW/tool-words.spec.ts` covers the new words on K1 and K2.
- [X] T049 `verify_report_js_rules`: rule 1 matches `WIR.toolTab`; rule 3 lists the k6 tables.
- [X] T050 [P] Spec text corrected (plan "Spec text corrected"): US3's dropped iterations, SC-002, SC-006.
- [X] T051 [P] Docs: the k6 page, with screenshots of K2's tab.
- [X] T052 Pull request 1 (stages 1-4): the full regression within 15 minutes, push, PR, merge. Done: PR #49,
  merged (5830da6), released in 4.6.0 (PR #53).

## Stage 5: Compare and the Errors tables (US6, FR-060 to FR-066, FR-071 defects 2-5)

- [X] T060 [US6] Red, one test per defect: Compare's Apply keeps the search, hidden rows and sort (2); a saved Compare
  column order keeps each cell under its header (3); after a column drag, Columns hides the column it names (4); an
  open Error Details row leaves with a search that hides its row (5).
- [X] T061 [US6] Red: `PW/table-toolbar.spec.ts` for Error Summary, Error Details and Compare: chips (Compare's
  REGRESSION / NEW / REMOVED / OK), Group by (Errors: none, sampler + type of error, error class, response code;
  Compare: status), CSV as shown.
- [X] T062 [US6] Error Summary and Error Details on the DOM adapter (rows stay in the HTML, FR-070; the Total stays
  last over the rows shown); the Errors groups become Group by on Error Details; Compare on the state adapter (Σ Total
  first).
- [X] T063 [US6] Delete `filterGenericTable`, `sortGenericTable`, `hideTableRow`, `showAllTableRows`,
  `toggleGenericColumnMenu`, `applyGenericColumnVisibility`, `filterErrorGroups` and the "By time / Grouped" switch.
- [X] T064 Rule 3 rewritten: no `style.display` on a table row outside `WIR.table` (with stage 6: the Requests walkers still set it until they move).
- [X] T065 [P] `PW/csv-export.spec.ts`, `errors-tab.spec.ts`, `error-details-grouping.spec.ts`, `compare-tab.spec.ts`
  move with the controls; `whole-run-totals.spec.ts` skips `tr.wir-group-row`.
- [X] T066 [P] Docs: `05-Errors-Tab.md`, `06-Compare-Tab.md`.
- [X] T067 Commit; the gate is pull request 2's, after stage 7.

## Stage 6: Requests (US6, FR-064, FR-069, FR-070, FR-071 defect 1)

- [X] T070 [US6] Red: Throughput sorts by value (defect 1); each transaction has a ▸ that opens and closes its
  requests; Expand all / Collapse all replace "Show transaction details"; Group by none, transaction, assert status,
  Apdex rating; chips; × and "Show N hidden"; CSV as shown; the view remembered per report.
- [X] T071 [US6] Requests on the DOM adapter; companion rows (`.sampler-detail-row`, `.wir-judging-row`) travel with
  their row; Σ Total first; `WIR.asserts` decorations kept.
- [X] T072 [US6] Delete `sortTable`, `arrangeSamplerRows`, `applySamplerRowVisibility`, `hideRow`, `showAllRows`,
  `initColumnMenu`, `applyColumnVisibility` and the transaction switch.
  Done but for the switch (2026-11-26): Expand all / Collapse all **are** the switch now, one state
  (`showTransactionChildren`) with two controls, and the filter bar keeps its checkbox. It is the only control the
  Timeline and Errors tabs have for a transaction's requests (4.2.35 moved it there for that reason), so deleting it
  would bring back the bug that fix closed. A transaction's ▸ opens that one in the table only. Also deleted:
  `filterSamplerTable`, `isSamplerRowSuppressed`, `samplerRowNamed`, `toggleColumnMenu`, `showAllColumns`,
  `updateHiddenRowsBtn`, `exportSamplerStatsCsv`, `syncSamplerCompanionRows`, `parseNumericCell`, `findHeaderByColId`,
  `measuredOr`, `COLUMN_NAMES`, `hiddenColumns`, `hiddenRowCount` and their styles. The CSV now holds what is shown
  (FR-066), so it no longer carries Std Dev, Avg Connect and Avg Latency; said in `04-Samplers-Tab.md`.
- [X] T073 [P] The Requests specs move (`samplers-tab`, `sampler-table-walkers`, `sampler-tree`,
  `transaction-details-switch`, `table-sort-and-toast`, `ui-interactions`, `csv-export`); R07 unchanged.
- [X] T074 [P] Docs: `04-Samplers-Tab.md`; CLAUDE.md's sampler-table paragraphs.
- [X] T075 Commit; the gate is pull request 2's, after stage 7.

## Stage 7: The Server board (US6, FR-062, FR-063)

- [X] T080 [US6] Red: the board's chips work as every other table's (several at once, counts over every row);
  Group by component (the default) or status; sort by header.
- [X] T081 [US6] The board on the state adapter; its one-at-a-time chips (`filterChipsHtml`) deleted; open charts
  kept across a re-render; rule 4 holds (the search reports through the delegated listener).
  Done 2026-11-26: every toolbar's search and Group by now report through one listener of WIR.table's on the
  document (`data-wir-table-search`, `data-wir-table-group`), so no toolbar carries `oninput`/`onchange` and the
  Server tab keeps its one handler shape; its sweeps take the toolkit's literal-only calls (`TOOLKIT_CALL`), as the
  Asserts sweep did. A card is its component's group (▾, Expand all / Collapse all); Status and None draw one
  table. The component badge stays the board's own filter on top (`spec.filter`). The chips count every row
  (FR-062), which retires U6's "the counts follow the other filters" and the "All N" chip.
- [X] T082 [P] `PW/server-board.spec.ts` SF40 and SF41 move (and server-tab, server-identity, keyboard-focus,
  client-fixes, xss-escaping, asserts-filter-sort, whose chips are scoped to their tab now); docs `13-Server-Tab.md`.
- [X] T083 Pull request 2 (stages 5-7): the full regression, push, PR, merge. PR #50, merged 2026-11-26 as 9d32714:
  regression 8/8 with 1260 browser tests in 12m29s; CI, CodeQL and the cross-OS smoke green; no open alerts.

## Stage 8: The filter bar's ticks on every table (US4, FR-030, FR-067)

- [X] T090 [US4] Red: unticking a request removes its rows from Requests, Errors, Compare, Asserts and every k6
  section; the Σ Total row, whole-run thresholds and counters stay; ticks set on one tab apply on the next; the bar
  shows on the k6 tab.
- [X] T091 [US4] One reader, `getVisibleSamplerNames`, feeds every table's filter; the CSS rule hiding the bar on the k6
  tab goes.
  Done 2026-11-26. `tableRequestFilter` reads `getVisibleSamplerNames` through the Errors charts' own rule
  (`errorsShownFor`: a request inside a transaction follows its transaction while the switch is off), and
  `WIR.table.setNameFilter` hands the one test to every table after each chart refresh. Three technical calls:
  (1) a sampler the bar unticks by default (`hiddenSamplers`: utility samplers, no samples) stays in the tables,
  because JMeter's Aggregate Report lists every sampler (the workspace rule: JMeter's answer decides); a name the
  bar does not list (a REMOVED Compare row) stays too. (2) On the k6 tab only the bar's Samplers row shows: its chart
  controls and time range narrow no k6 number. (3) The bar took the failed threshold's row off a 720 px first
  screen, so the strip (FR-023) now names each failed threshold with what k6 measured and how far past its limit,
  and SC-001 is held on the strip.
- [X] T092 [P] `PW/filter-controls.spec.ts:394` and `csv-export.spec.ts` change with it; docs `08-Filter-Bar.md`
  (and 01, 05, 17).
- [X] T093 Commit; the gate is pull request 3's.

## Stage 9: Over time (US5, P3, FR-040, FR-041)

- [X] T100 [US5] Measure first: the payload the six parts add per one-second bucket on the 134,406-request run. If it
  pushes a typical k6 run over `externalDataThreshold`, record that and move US5 to a later phase.
  Measured 2026-11-26: K2 repeated to an hour (2,358 copies, 24 at a time: 127,335 requests, 3,686 one-second
  buckets, 12 requests with parts). The payload is 4,399,306 bytes today; the six parts at two decimals add
  1,139,434 (+25.9%), 5,538,740 in all, under the 10,000,000 threshold. A typical run (K2, 36 s) adds a few KB.
  US5 stays in this phase.
- [X] T101 [US5] Red: K2's Login detail panel charts the six parts over time; the `p(95)<=600` limit is a line on the
  percentiles chart, labelled as k6's threshold, not draggable.
- [X] T102 [US5] The parts per bucket in `perSamplerSeries` for a tool run only; a detail renderer; a k6 mark-line
  source apart from `WIR.asserts`; rule 5 holds.
  Done 2026-11-26: `ToolMeasures.meanPart` keys each request's parts by the second it ended (`endOf`), as the time
  buckets key a sample; the k6 detail panel's third slot is the parts chart (k6 measures no bytes per request).
  FR-041 says the response time chart and T101 the percentiles chart: the limit is drawn on both, by one function
  (`toolDurationLimits`), silent, so no tooltip and no drag.
- [X] T103 Commit; the gate is pull request 3's.

## Stage 10: Close

- [X] T110 Docs: `docs/FEATURES.md`, and every page above with screenshots from `docs/Tutorial/take-screenshots.js`.
  Done 2026-11-26: FEATURES.md (one toolbar for every table, the ticks, the k6 tab, CSV of what is shown); 59 shots
  retaken, three new k6 ones (71-73). Two finds on the way, fixed with a test: the sort arrow on every header made the
  Requests table 1388 px in a 1286 px card at 1400 px (4.5.1: 1292), so the arrow is on the sorted column only, as
  JMeter's Aggregate Report draws it; and a detail chart drew nothing for a second with one request (K2's Login), so a
  lone point is a dot.
- [X] T111 SC-004 and SC-010 timed in the browser (the 134,406-request run, and T022's run).
  Timed 2026-11-26, PC quiet (CPU 14%), Chromium over file://, each control from the call to the painted frame (two
  animation frames, so 33 ms is the floor), median of three: SC-004 on K2 repeated to an hour (127,335 requests),
  every tab opens and every control on every k6 table and the Requests table answers in 35 ms at worst; SC-010 on
  gen-many-names.js's run (320 names, 1,280 thresholds), 84 ms at worst (the k6 tab's first open). Both under 500 ms.
- [X] T112 SC-007: spec 001's four-report comparison, under its new definition.
  Done 2026-11-26: E02, E31, an E26-shaped report (plain.jtl's run with E26's annotations and CSV) and a Compare
  report, each built from the same run data by the 4.5.1 release and by this build, every tab opened: the header,
  the Summary cards, the Notes tab and every row of the Samplers, Error Summary, Error Details, Compare, Asserts and
  Server board tables identical, and the rows shown in the same order. One table is gone, as US6 says: the Errors
  tab's grouped view (now Error Details' Group by). Goldens, R07 and PreChangeBaselineTest green in the gates.
- [X] T113 SC-005: the owner's walk-through on K1, K2 and E02.
  Closed 2026-11-26: the owner chose "I check it, you accept". Claude answered the five questions from the k6 tab alone
  (K1, K2 built with the released 4.7.0 JAR; the tab's text read in Chromium), each from one place, each equal to k6's
  own summary: the failed threshold and by how much (K2 strip: Login p(95)<=600, 779.72 ms, 179.72 ms over (30%));
  the request that waits longest (K2 timing table, Waiting: Restore Stock (cleanup) 532.87 ms; of the repeated ones,
  Login 182.63); the failed check and its group (K1 Checks, grouped: "server error is 200" in Complete Checkout Flow,
  0 of 12); dropped iterations (K2 counters: 0); Login connecting or waiting (K2 Login row: waiting 182.63 of 182.78
  ms, connecting 3.01). One finding logged in EXPLORATORY-LOG.md (a rate threshold's "17.18% over (1718%)").
  Waiting for the owner (2026-11-26): five questions from the k6 tab alone, on 4.6.0.
- [X] T114 Release 4.6.0 with the release tier (`./test-plans/scripts/run-release.sh`).
  Released 2026-11-26: https://github.com/aharon890/JmeterWebInsightReport/releases/tag/v4.6.0 (tag on 48ce3d2, PR #53).
  Release tier 14/14 in 14m06s, 1269 browser tests; seven assets, as `_perf_release` lists them.

## Analysis (2026-11-25, before building)

Every FR and SC maps to a task above. Three findings, each settled here as a technical call inside the approved design:

- **A1, SC-003 against FR-061.** SC-003 asks that typing a request name narrows every section of the k6 tab in one
  step, while FR-061 gives each table its own toolbar search. Settled: the toolkit's `spec.searchGroup` links the
  searches of one tab's tables, so a name typed in any k6 section's box narrows every section, and each box shows the
  same text. Tested in T043.
- **A2, what is remembered.** data-model.md lists the chips among the remembered choices; FR-068 does not, and the
  Asserts tab deliberately does not remember its filters today. Settled: FR-068 wins. Sort, grouping, open groups,
  hidden rows, hidden columns and the k6 statistic are remembered; search text and chips are not. data-model.md is
  corrected in stage 4 (T050).
- **A3, the run data version on JMeter's side.** `-g -Jwebinsight.report.rundata=true` writes run data through the
  same writer, so it moves to 1.1 too, with no new field on a JMeter run. R08 compares CI/CD JSON, which carries no
  format version, so it holds unchanged.

## Dependencies

Stage 1 before stage 4 (the data the tab reads). Stage 2 before stages 3-8 (the core). Stage 3 before stage 4 (the
toolbar and state adapter the k6 tab is built on). Stages 5, 6 and 7 after 3, in any order. Stage 8 after 4-7 (every
table reads the ticks). Stage 9 after 4. Stage 10 last.
