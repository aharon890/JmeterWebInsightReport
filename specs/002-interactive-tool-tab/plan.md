# Implementation Plan: An organised, interactive k6 tab

**Branch**: `feat/002-interactive-tool-tab` | **Date**: 2026-11-25 | **Spec**: [spec.md](spec.md)

**Status**: Waiting for the owner. Three layout choices are open (below), shown as mock-ups at
https://claude.ai/artifact/1LrUgn9nqBeug5jcgHWWoX. Building waits for the choices and the go-ahead (Principle I).

**Input**: the spec, constitution 2.0.0, and the facts gathered read-only on 2026-11-24
(`tmp/002-interactive-tool-tab/plan-facts-2026-11-24.md` in the main checkout, git-ignored; line references to the
spec 001 tree, which main now holds).

## Summary

The k6 tab becomes a page module, `WIR.toolTab`, rendered by report.js from a `chartData.tool` block. Java writes the
block from the run's tool records and measures:
- the thresholds, each now with the value k6 measured;
- the timing parts per request, now with p(99) and k6's request duration;
- the checks with their group paths;
- the counters.

The module groups, filters, sorts, folds and remembers, through the mechanisms the Asserts, Requests and Errors tabs
already have. A small change to the run data lets the threshold record carry the measured value. JMeter reports do not
change.

## Open choices for the owner (mock-ups on the page above)

| # | Question | Options | Recommended |
|---|---|---|---|
| 1 | How the thresholds are laid out | A: groups with headers, like k6's own summary. B: one tree table, like the Requests tab. C: a card per group | A |
| 2 | How each request's time is shown | A: numbers that follow the statistic switch, plus a bar of the average request from start to end. B: numbers only, with a "% waiting" column | A |
| 3 | The page's filter bar on the k6 tab | A: the filter bar's request ticks narrow the k6 tab too. B: leave it for a later phase. C: a text box in the filter bar for every tab | A |

The answers and their date go in this section. The design below assumes the recommended options and names what changes
under the others.

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
- **Performance goals**: on the 134,406-request run (spec 001's T067), the tab opens and every control answers in under
  half a second on the owner's PC (SC-004).
- **Constraints**: JMeter reports unchanged to the byte (FR-035). Numbers written as JMeter and k6 write them
  (Principle II). Every new word in all three columns of the words table (`PageWords` refuses a column that differs).
- **Scale/Scope**: up to hundreds of request names and thresholds (synthetic summary for the check).

## Constitution Check (2.0.0)

| Principle | Check | Result |
|---|---|---|
| I. Go-ahead | Plan only. Building starts on the owner's go | Pass |
| II. The report follows JMeter (and the standard tool) | The k6 tab follows k6's own summary: its grouping, its units, the measured value beside each threshold. The thresholds stay k6's verdicts, not this report's. No new statistic is invented: p(99) and the duration trend use the engine's existing percentile method | Pass |
| III. Tests are the gate, read as counts | Red-first tests per stage; counts reported; K2 becomes a fixture so a real failed threshold is tested | Pass |
| IV. One self-contained artifact | All rendering inside the page; no new library | Pass |
| V. Branch-first, one version, clean text | This branch; one version raise, 4.6.0 (a feature); LF files | Pass |
| VI. Every setting reachable | No new report property. The tab's choices are page state, remembered per report. User-visible behaviour documented in `docs/` (the k6 page) in the same change | Pass |
| VII. One engine for every load tool | The tool's section only. Thresholds and checks never decide a verdict. The run data change is additive (format 1.1, optional fields), so 1.0 files still read | Pass |

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
  words. The panel `#tab-tool` keeps its server-written counters and a mount point. The module renders the rest and
  re-renders on every control, as the Asserts tab does: state, then filter, then sort, then rows.
- **Why not server-rendered rows.** Switching the grouping (Metric, Request, Condition, Result) and the statistic
  rebuilds the rows. The generic helpers walk static rows only, and `verify_report_js_rules` rule 3 bans them on
  re-rendered tables.
- **What follows:**
  - `BuildReportK6Test` and `BuildReportOtherToolTest` read the `tool` block instead of grepping table ids in the HTML;
  - rules 1 and 3 of `verify_report_js_rules` cover the new module and its table ids;
  - the block counts toward `externalDataThreshold`. It is small: 7 trends × 8 statistics per request.

### 4. The shared mechanisms, reused, not copied

| Need | Existing mechanism | Change |
|---|---|---|
| Result chips (FR-003) | `WIR.asserts`' `statusChips` and `WIR.status` tokens | The chip renderer moves to `WIR.status` and both tabs call it. Several chips at once; counts cover every threshold |
| Groups that open and close (FR-004, FR-020) | the Errors tab's groups | The open and close and the Expand all / Collapse all logic take a container. Groups hold real rows |
| Search, sort within a group (FR-005, FR-006, FR-012) | the Asserts tab's filter-then-sort over state | The same order of steps in `WIR.toolTab`. The tree order comes from `samplerTreeOrder`, which is pure |
| Column widths and order | `RESIZABLE_TABLES`, `initColumnDragDropForTable` | The new table ids are added |
| × and "Show all" | the Requests tab's hidden-rows pattern | Hidden rows are part of the module's state, so a re-render keeps them |
| CSV (FR-031) | `buildCsvFromArrays`, `downloadCsvFile` | One export per table, of the rows as shown |
| Link to a request (FR-008) | `switchTab`, `toggleSamplerDetail` | A new `openRequestRow(name)`: switch tab, unhide, open the detail, scroll |

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
- **The layout** follows choice 1 (A: header bars; B: parent rows in one table; C: cards).

### 6. The timings (US2)

- One row per request, in `samplerTreeOrder` (k6 groups are the transactions), plus "Every request" first.
- Columns: blocked, connecting, TLS, sending, waiting, receiving, duration, at the chosen statistic. The choices are
  avg (the default), min, med, max, p(90) and p(95), plus p(99) when the run lists it.
- A row expands to every statistic of every part, as a companion row.
- **Choice 2 A**: a bar of the average request from start to end: blocked, with connecting and TLS drawn inside it,
  then sending, waiting and receiving. The bar stays on the average, because percentiles of the parts don't add up.
  - k6's blocked contains connecting and TLS when a request opens a new connection. K2's numbers agree: Login's
    average blocked is 3.11 ms with 3.01 ms of connecting.
  - Stage 3 checks this against k6's tracer source before the bar is built. If it does not hold, the bar shows the
    parts side by side instead.
- **Choice 2 B**: no bar, and a "% waiting" column.

### 7. Checks, group duration, counters, strip (US3)

- Checks sit under their group path from `summary.json`'s `root_group`, failed first, with a pass-rate bar, chips and a
  search. Group duration follows the tree.
- The counters are already Summary-style tiles. They get their words from the words table.
- **The strip**: "Thresholds: 1 of 50 failed: Login p(95)<=600 · Checks: none ran".
- **Dropped iterations stay "0" when the summary has none.** k6 writes that counter only when it drops an iteration.
  The spec's US3 says "not measured", which is corrected in the same change.

### 8. The filter bar (US4), by choice 3

- **A**: the filter bar's Requests row shows on the k6 tab. Its ticks narrow every section to the ticked requests, as
  they narrow the charts. Rows tied to no request stay. CSS today hides the bar on this tab, so that rule goes.
- **B**: no change. The tab's own searches only.
- **C**: a new text box in the filter bar, read by every tab. This changes the Requests, Errors and Timeline tabs, and
  every JMeter report with them. It needs its own spec.

### 9. Remembered choices (FR-032)

The grouping, statistic, open groups, hidden rows, sort and columns are kept in `localStorage` under the run's id, as
drafts are, inside try/catch. The page works without them. A k6-only key can never reach a JMeter report, which
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

1. **Data**: the threshold record's four fields, format 1.1, both schemas, `trendStatistics`, p99 and the duration
   trend. K2 becomes a fixture: the `run_test` line, `FIXTURE_IDS`, `REQUIRED_FIXTURES` and an R07 case.
   `RunDataSchemaTest`, `K6ResultsTest`, `ToolMeasuresTest`, R08. The goldens and `PreChangeBaselineTest` unchanged.
2. **Shared pieces**: the chip renderer moved, groups taking a container, `openRequestRow`, the `tool` block. js-unit for
   the expression parser, always-true, distance and units. The Asserts and Errors specs unchanged.
3. **US1, US2, US3** in that order. The k6 tracer check comes before the timing bar. 'tool' joins
   `every-reader-same-page.spec.ts`'s TABS and its CSV map. `tool-tab.spec.ts` grows to K1 and K2. `tool-words.spec.ts`
   covers the new words.
4. **US4**, by choice 3.
5. **US5**, measured first, kept or moved.
6. **Close**:
   - the k6 docs page;
   - SC-004 on the 134,406-request run, timed in the browser;
   - the regression green, then the owner's walk-through (SC-005) on K1 and K2.
   - Release 4.6.0 with the release tier.

## Spec text corrected in the same change

- US3: K1's dropped iterations read "0", not "not measured" (section 7).
- FR-031: the Requests CSV writes every row, so "as shown" is new for these tables.
- FR-032: the Requests table's sort is not remembered today, and what is remembered is global. Here it is per report.
- FR-041: no detail chart draws a limit today. The k6 line is a new source.
- SC-002: K2 has 12 request names, so 13 rows, plus 3 group rows (setup, Complete Checkout Flow, teardown).
- SC-006: holds the thresholds' measured values to k6's summary. Per-request timings are nearest-rank, as the engine
  computes every percentile, not k6's interpolation.

## Artifacts

- [research.md](research.md): the decisions above, with the alternatives
- [data-model.md](data-model.md): the threshold record 1.1 and the `tool` block
- [contracts/run-data-1.1.md](contracts/run-data-1.1.md): the format change
- [quickstart.md](quickstart.md): checking it on K1 and K2
