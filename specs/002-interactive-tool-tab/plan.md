# Implementation Plan: One table experience for every tab, and an organised, interactive k6 tab

**Branch**: `feat/002-interactive-tool-tab` | **Date**: 2026-11-25 (revised the same day) | **Spec**: [spec.md](spec.md)

**Status**: Revised for the owner's widening. Waiting for the owner's go-ahead on this revision (Principle I).

**Input**:
- the spec (revised), constitution 2.0.0;
- the facts gathered read-only, both git-ignored in the main checkout's `tmp/002-interactive-tool-tab/`:
  `plan-facts-2026-11-24.md` (the k6 tab) and `unified-table-facts-2026-11-25.md` (every table: the capability
  matrix, the toolkit's shape, the gates, the standard tools).

## The owner's decisions (2026-11-25)

- **The first plan.** "all 3 are ok, but i want the page to be interactive, like in the other report tabs ... a
  unifying ui/ux for all report tabs. the logic itself for all 3 is ok". So the data and engine design below stands,
  and the tables are unified.
- **The k6 tab's layout** (https://claude.ai/artifact/1LrUgn9nqBeug5jcgHWWoX):
  - 1A, thresholds in groups like k6's summary;
  - 2A, numbers plus a bar of the average request;
  - 3A, the request ticks narrow the k6 tab.
- **The shared toolbar** (https://claude.ai/artifact/Nhk6xBsrSSwcT7b3qpzpoh):
  - Q1 A: one toolbar row above every table;
  - Q2 A: Group by status, plus Apdex rating, error class and code, and Compare's statuses. Numbers are never grouped;
  - Q3 A: CSV exports what is shown;
  - Q4 A: the request ticks narrow every tab's tables.
- **Decided on the mock-up page, from earlier answers and the standard tools; the owner raised no objection:**
  - Asserts-style chips everywhere;
  - each table's view remembered per report;
  - a ▸ per transaction;
  - keyboard sort;
  - the Server cards as "group by component";
  - no number moves.

## Summary

One table toolkit, `WIR.table`, serves every table of the report.
- It is extracted from the Asserts tab's own state pipeline, not written beside it. Each table that moves onto it
  deletes the code it replaced: five search implementations, four sorts, five open/close mechanisms, two column
  choosers.
- It draws one toolbar row (search, status chips, Group by, Expand / Collapse all, Columns, "Show N hidden", CSV) and
  sortable header buttons.
- It reads the filter bar's request ticks.
- It remembers each table's view per report.

The k6 tab is the first new table built on it. Its module, `WIR.toolTab`, renders from a `chartData.tool` block that
Java writes from the run's tool records and measures:
- the thresholds, each now with the value k6 measured;
- the timing parts per request, with p(99) and k6's request duration;
- the checks with their group paths;
- the counters.

A small change to the run data lets the threshold record carry the measured value. On a JMeter report every number,
row text and verdict stays as it is; the table controls change, as the owner asked.

## Technical Context

- **Language/Version**: Java 21 (core and report modules), plain JavaScript in `report.js`, Thymeleaf, CSS.
- **Primary dependencies**: none new. ECharts 5.5.0 (already bundled) for US5 only.
- **Storage**: run data (`.jsonl.gz`, format 1.0 to 1.1, optional fields only); `localStorage` for the tab's choices.
- **Testing**:
  - `mvn test`;
  - js-unit (node) for the pure helpers;
  - Playwright on K1 and K2;
  - the goldens G1-G13, `PreChangeBaselineTest`, R07 and R08;
  - the regression, and the release tier at release.
- **Target platform**: the report page in any browser, built by `build-report --from k6` or `--run-data`.
- **Performance goals**: every table opens and every control answers in under half a second on the owner's PC, on a
  synthetic run with 300 or more request names and about 1,200 thresholds (SC-004, SC-010). The 134,406-request run
  has only 12 names, so it cannot show this.
- **Constraints**:
  - every number, row text and verdict unchanged on JMeter reports (FR-035): the goldens, R07 and
    `PreChangeBaselineTest` hold, unchanged;
  - Requests' and the Errors tables' rows stay in the HTML, because R07 reads them (FR-070);
  - numbers written as JMeter and k6 write them (Principle II);
  - every new word in all three columns of the words table (`PageWords` refuses a column that differs).
- **Scale/Scope**: about 2,000 lines of table code across six places in report.js, about 300 Playwright tests in the
  files that pin today's controls, and 6 docs pages with their screenshots.

## Constitution Check (2.0.0)

| Principle | Check | Result |
|---|---|---|
| I. Go-ahead | Plan only. Building starts on the owner's go | Pass |
| II. The report follows JMeter (and the standard tool) | The k6 tab follows k6's own summary: its grouping, its units, the measured value beside each threshold. The thresholds stay k6's verdicts, not this report's. No new statistic is invented: p(99) and the duration trend use the engine's existing percentile method. The toolbar follows the standard tools where they have an answer: sort on headers (JMeter's dashboard), a search plus quick status choices and a Grouped / List view (Grafana's alert list, Grafana Cloud k6), every panel narrowed by one page filter (Grafana's variables), an export of what is shown (Grafana, Datadog). A group header never sums or averages percentiles | Pass |
| III. Tests are the gate, read as counts | Red-first tests per stage; counts reported; K2 becomes a fixture so a real failed threshold is tested | Pass |
| IV. One self-contained artifact | All rendering inside the page; no new library | Pass |
| V. Branch-first, one version, clean text | This branch; one version raise, 4.6.0 (a feature); LF files | Pass |
| VI. Every setting reachable | No new report property. The tab's choices are page state, remembered per report. User-visible behaviour documented in `docs/` (the k6 page) in the same change | Pass |
| VII. One engine for every load tool | The tool's section only. Thresholds and checks never decide a verdict. The run data change is additive (format 1.1, optional fields), so 1.0 files still read. "JMeter users see no change": every fixture, R07 and the number gates hold as before; the table controls change because the owner asked for it (decision 3), and the browser tests that pin today's controls move with them, each named in stage order | Pass, noted |

Re-checked after the design: unchanged.

## Design

### 1. Data: the measured value of a threshold (FR-050)

- The threshold record gains four optional fields. They are named in full, as the run data's other fields are:
  - `statistic`: the statistic the expression names, such as `p(95)`, `rate`, `count`, `max` or `value`;
  - `measuredValue`: k6's own number for it, from `summary.json` `metrics[m].values[statistic]`;
  - `metricType`: `trend`, `rate`, `counter` or `gauge`;
  - `metricContains`: `time`, `data` or `default`.
- `K6Results.writeSummary` fills them. `RunDataWriter` and `RunDataReader` carry them. A record without them reads as
  before, and the tab says "not recorded" (never 0).
- The run record gains an optional `trendStatistics` list, from k6's `options.summaryTrendStats`, so p(99) shows only
  when the run's summary lists it.
- `RunDataFormat.VERSION` becomes 1.1, under the format's own rule that a minor version adds optional fields only. Both
  copies of `run-data.schema.json` gain the fields. `RunDataSchemaTest` sets them.

### 2. Data: timings per request (FR-010, FR-011)

- `ToolMeasures.Trend` gains p99, through `ResponseTimeDistribution.getPercentile(99)`, the engine's one percentile
  method (nearest rank).
- A seventh trend, k6's request duration, is fed sending + waiting + receiving per sample. That is k6's own definition,
  and K1, K2 and K3's summaries agree with it.
- A JMeter run still has no measures, so nothing moves on JMeter.

### 3. Rendering: `chartData.tool` and `WIR.toolTab`

- `ToolTab` writes a `tool` block into chartData: thresholds, timing rows, checks, group durations, counters and the
  words. The panel `#tab-tool` keeps its server-written counters and a mount point.
- `WIR.toolTab` renders the rest through the toolkit's state adapter (section 4), as the Asserts tab will:
  state, then filter, then group, then sort, then rows.
- **Why not server-rendered rows.** Switching the grouping (Metric, Request, Condition, Result) and the statistic
  rebuilds the rows.
- **What follows:**
  - `BuildReportK6Test` and `BuildReportOtherToolTest` read the `tool` block instead of grepping table ids in the HTML;
  - the block counts toward `externalDataThreshold`. It is small: 7 trends × 8 statistics per request.

### 4. The table toolkit, `WIR.table` (US6, FR-060 to FR-071)

**Shape**: one pure core, two small adapters, one toolbar.

```
WIR.table.view(records, spec, state)   pure: filter (search, chips, ticks) -> group -> sort by value within group
                                       (tree through samplerTreeOrder) -> fold; node-tested in js-unit
WIR.table.mount(tableId, spec)         the toolbar, the header buttons, the memory; returns {apply, state, csv, reset}
spec: id, source 'dom' | 'state', key(row), columns [{id, label, value(row), hideable, csv(row)}], search(row),
      status {of(row), order}, groupings [{id, label, of(row)}], tree, companions, pinned {first, last},
      render(rows) for state tables, after()
```

- **The DOM adapter** (Requests, Error Summary, Error Details) keeps the rows Thymeleaf wrote. They stay in the HTML
  for R07 (FR-070).
  - It moves the existing `<tr>`s with their companion rows (the chart panel, the judging rows, the error detail).
  - It sets `display`.
  - It inserts group header rows as `tr.wir-group-row`, which every walker skips, as companions are skipped today.
  - It generalises `arrangeSamplerRows` and `applySamplerRowVisibility`.
- **The state adapter** (Asserts, Compare, the Errors groups, the Server board, the k6 tables) runs the Asserts tab's
  pipeline: filter and sort the state, then render (`applySort(applyFilters(...))`).
- **The toolbar** (Q1 A): search · status chips with counts · Group by · Expand all / Collapse all while grouped ·
  then, on the right, Columns ▾ · "Show N hidden" · CSV. It renders through `WIR.status` chip tokens (rule 2) and one
  `jsArg` call per inline handler (rules 1 and 4).
- **Sorting** sorts by the column's value, never its text (FR-064; it fixes Throughput's text sort). Each header is a
  `<button>`, with `aria-sort` on the sorted column only (WAI-ARIA's sortable table). Names compare with one fixed
  collation, not the browser's language, so `every-reader-same-page` stays equal.
- **Chips** (FR-062): several at once, each counting all rows, as the Asserts tab's do. The chip renderer moves out of
  `WIR.asserts` into `WIR.status`.
- **Group by** (FR-063, Q2 A), per table:

  | Table | Group by |
  |---|---|
  | Requests | none, transaction (the tree, default), assert status, Apdex rating |
  | Errors | none, sampler + type of error (today's Grouped view), error class, response code |
  | Compare | none, REGRESSION / NEW / REMOVED / OK |
  | Asserts | none, status |
  | Server board | component (its cards, the default), status |
  | k6 thresholds | metric (default), request, condition, result |

  A group header shows counts only (FR-063).
- **The filter bar's ticks** (FR-067, Q4 A): one reader, `getVisibleSamplerNames`, feeds every table's filter. The CSS
  rule that hides the bar on the k6 and Server tabs goes. The Σ Total row, whole-run thresholds and counters stay.
- **CSV** (FR-066, Q3 A): `table.csv('shown')` writes the rows and columns on screen, as formatted, through the existing
  `buildCsvFromArrays` and `downloadCsvFile`. Today's exports, which write every row from chartData, are replaced.
- **Memory** (FR-068): one `localStorage` record per report, `wir_view_<runId>`, holding `{tableId: state}`, inside
  try/catch, the same shape as the drafts key. The rows of Asserts and Server are remembered by id and occurrence, as
  `draftCount` pairs rules, because their page keys do not survive a reload. Widths and order keep their global keys.
- **Link to a request** (FR-008): `openRequestRow(name)` switches to Requests, shows the row, opens its detail and
  scrolls to it.
- **Static rules**: rule 1 matches `WIR.table` and `WIR.toolTab` too. Rule 3 becomes "no `style.display` on a table
  row outside `WIR.table`", once the generic helpers are deleted.

### 5. The thresholds (US1)

- **Always-true** (FR-007): an expression `<statistic> >= 0` that passed. It folds into one line per group, with a
  chip to show the rows. A failed threshold never folds, so a custom gauge that went below 0 still shows. The folded
  rows count in the totals.
- **Measured value**, in k6's units:
  - time: ms with two decimals, as k6's summary writes `p(95)=779.72ms`;
  - rate metric: a percentage with two decimals;
  - counter: the count, or `/s` for its rate;
  - data: B, kB or MB, as k6 writes them;
  - a custom gauge with `metricContains: default`: the bare number.
- **Distance to the limit**: the limit is parsed from the expression (`<`, `<=`, `>`, `>=`, `==`, `!=`). The distance
  is measured past the limit, in the metric's unit, and as a share of the limit. There is no share when the limit is 0.
  The parser is a pure function, tested in js-unit, including every operator and k6's spacing variants.
- **Grouping**: Metric is the default: the metric without its tags, the tags shown in the row. Request uses the `name`
  tag, with "Not tied to one request" for the rest. Condition uses the expression text. Result puts failed first.
- **The layout** (1A): a header bar per group with its counts, a group with a failure open, the toolbar above.

### 6. The timings (US2)

- One row per request, in `samplerTreeOrder` (k6 groups are the transactions), plus "Every request" first.
- Columns: blocked, connecting, TLS, sending, waiting, receiving, duration, at the chosen statistic. The choices are
  avg (the default), min, med, max, p(90) and p(95), plus p(99) when the run lists it.
- A row expands to every statistic of every part, as a companion row.
- **Choice 2 A**: a bar of the average request from start to end: blocked, with connecting and TLS drawn inside it,
  then sending, waiting and receiving. The bar stays on the average, because percentiles of the parts don't add up.
  - k6's blocked contains connecting and TLS when a request opens a new connection. K2's numbers agree: Login's
    average blocked is 3.11 ms with 3.01 ms of connecting.
  - The k6 stage checks this against k6's tracer source before the bar is built. If it does not hold, the bar shows
    the parts side by side instead.

### 7. Checks, group duration, counters, strip (US3)

- Checks sit under their group path from `summary.json`'s `root_group`, failed first, with a pass-rate bar, chips and a
  search. Group duration follows the tree.
- The counters are already Summary-style tiles. They get their words from the words table.
- **The strip**: "Thresholds: 1 of 50 failed: Login p(95)<=600 · Checks: none ran".
- **Dropped iterations stay "0" when the summary has none.** k6 writes that counter only when it drops an iteration.
  The spec's US3 says "not measured", which is corrected in the same change.

### 8. The filter bar (US4)

The k6 tab reads the ticks through the toolkit, as every table does (section 4). The tab's statistic switch joins its
remembered view.

### 9. Remembered choices (FR-032, FR-068)

The toolkit's per-report record holds the k6 tables' views too. A k6-only key can never reach a JMeter report, which
answers the `wir_detailCharts` risk in the facts.

### 10. Over time (US5, P3)

- For a tool run only, the six parts are added per one-second bucket to `perSamplerSeries`, so JMeter emits nothing
  new.
- The detail panel gets a renderer for them.
- A k6 limit line on the detail panel's percentiles chart, labelled as k6's threshold: a non-draggable source, kept
  apart from `WIR.asserts`.
- The payload added on the 134,406-request run is measured before this stage is kept. If it pushes a typical k6 run
  over `externalDataThreshold`, the stage moves to a later phase, as the spec allows.

### 11. Words (FR-033)

Every new word is in `page-words.json` in the jmeter, k6 and other columns: `PageWords` refuses a column whose keys
differ. The column headings that `ToolTab` hard-codes today move into the table.

## Stages, each tested red first before the next

Each stage passes the regression and goes to main by pull request, so main stays releasable and no table is left on
two mechanisms. Every stage holds the goldens, R07 and `PreChangeBaselineTest` unchanged. The release, 4.6.0, comes
after the last stage.

1. **Data**:
   - the threshold record's four fields, format 1.1, both schemas, `trendStatistics`;
   - p99 and the duration trend;
   - K2 becomes a fixture: the `run_test` line, `FIXTURE_IDS`, `REQUIRED_FIXTURES` and an R07 case;
   - tests: `RunDataSchemaTest`, `K6ResultsTest`, `ToolMeasuresTest`, R08.
2. **The toolkit's pure core**: filter, group, sort by value, tree, fold, in js-unit, next to `sampler-tree.test.js`.
   No page change yet. Also a synthetic run with 300+ request names and about 1,200 thresholds, for SC-010.
3. **Asserts moves onto the toolkit**, the first table, because its pipeline is the model.
   - The chip renderer moves to `WIR.status`.
   - Asserts gains × and Columns, and one status control: the chips. The header popover goes.
   - `asserts-filter-sort.spec.ts` (41 tests) proves nothing else moved.
4. **The k6 tab** (US1, US2, US3) on the toolkit's state adapter.
   - The k6 tracer check comes before the timing bar.
   - 'tool' joins `every-reader-same-page.spec.ts`'s tabs and its CSV map.
   - `tool-tab.spec.ts` covers K1 and K2; `tool-words.spec.ts` covers the new words.
5. **Compare and the Errors tables** move.
   - The generic helpers are deleted: `filterGenericTable`, `sortGenericTable`, `hideTableRow`, `showAllTableRows`,
     `toggleGenericColumnMenu`, `applyGenericColumnVisibility`, `filterErrorGroups`.
   - "By time / Grouped" becomes Group by.
   - Defects 2-5 (FR-071) are each red first, then fixed.
   - Rule 3 is rewritten.
6. **Requests** moves, on the DOM adapter.
   - `sortTable`, `arrangeSamplerRows`, `applySamplerRowVisibility`, `hideRow`, `showAllRows`, `initColumnMenu` and
     `applyColumnVisibility` are replaced.
   - Each transaction gets a ▸, and "Show transaction details" becomes Expand all / Collapse all.
   - Defect 1 (Throughput's text sort) is red first, then fixed.
   - `whole-run-totals.spec.ts` skips group header rows.
7. **The Server board** moves: its chips take the one design, and its cards are "group by component".
8. **The filter bar's ticks on every table** (US4, FR-067). `filter-controls.spec.ts:394` and `csv-export.spec.ts`,
   which pin today's opposite, change with it.
9. **US5**, measured first, kept or moved to a later phase.
10. **Close**:
    - the docs: the k6 page, and the Requests, Errors, Compare, Filter bar, Asserts and Server pages, with their
      screenshots;
    - SC-004 and SC-010 timed in the browser;
    - spec 001's four-report comparison, run under its new definition (SC-007);
    - the owner's walk-through (SC-005) on K1, K2 and E02;
    - release 4.6.0 with the release tier.

## Spec text corrected in the same change

- US3: K1's dropped iterations read "0", not "not measured" (section 7).
- FR-031: the Requests CSV writes every row, so "as shown" is new for these tables.
- FR-032: the Requests table's sort is not remembered today, and what is remembered is global. Here it is per report.
- FR-041: no detail chart draws a limit today. The k6 line is a new source.
- SC-002: K2 has 12 request names, so 13 rows, plus 3 group rows (setup, Complete Checkout Flow, teardown).
- SC-006: holds the thresholds' measured values to k6's summary. Per-request timings are nearest-rank, as the engine
  computes every percentile, not k6's interpolation.
- FR-035 and SC-007 (done in the revised spec): "unchanged in any way" became "every number, row text and verdict
  unchanged; the table controls change as US6 lists".

## Risks

- **Scope.** This is the biggest change to the page since the Asserts tab: 10 stages, about 300 browser tests to move.
  Each stage merges on its own, so a stop part-way leaves main green, with some tables moved and the rest as today.
- **Open charts in a re-rendered table.** A redraw would destroy a chart open in a row (the Server board rebuilds them
  per keystroke today). The state adapter keeps open companion rows across a re-render, and the search is debounced.
- **The rows R07 reads.** Requests and the Errors tables keep their rows in the HTML, with today's cells. A selection
  box or a JS-rendered row would break R07, so neither is planned.
- **Readers in other languages.** Names are sorted with one fixed collation, so an English and a German reader see the
  same order.

## Artifacts

- [research.md](research.md): the decisions above, with the alternatives
- [data-model.md](data-model.md): the threshold record 1.1 and the `tool` block
- [contracts/run-data-1.1.md](contracts/run-data-1.1.md): the format change
- [quickstart.md](quickstart.md): checking it on K1 and K2
