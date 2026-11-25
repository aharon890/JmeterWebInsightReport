# Research: an organised, interactive k6 tab

The facts, with line references: `tmp/002-interactive-tool-tab/plan-facts-2026-11-24.md` in the main checkout
(git-ignored). K2's numbers below were read again from `test-plans/run-data/K2` on 2026-11-25.

## R1. Where the measured value comes from

- **Decision**: k6's own `summary.json`, `metrics[m].values[statistic]`, carried in the threshold record.
- **Rationale**: k6 already wrote the number its threshold was judged on (K2's Login: `p(95) = 779.71796`). Working it
  out again from the samples would give the engine's nearest-rank percentile (1031.47 ms on six samples), not the value
  k6 judged, and the row would contradict its own result.
- **Alternatives considered**: computing it from the samples. Rejected for that reason (spec SC-006).

## R2. The record change is a minor format version

- **Decision**: format 1.1 with four optional fields on the threshold record, and an optional `trendStatistics` on the
  run record.
- **Rationale**: the format's contract says a minor version adds optional fields only, and readers of 1.0 files keep
  working. `RunDataSchemaTest` holds the schema to the fields written.

## R3. The tab is rendered by report.js

- **Decision**: a `chartData.tool` block and a `WIR.toolTab` module.
- **Rationale**: grouping and statistic switches rebuild rows. The Asserts tab is the precedent: its state is filtered,
  then sorted, then rendered. The generic table helpers walk static rows and are banned on re-rendered tables (rule 3).
- **Alternatives considered**: Thymeleaf rows for every grouping, hidden by CSS. Rejected: four copies of each row, and
  the sort within a group still needs a script.

## R4. Always-true thresholds

- **Decision**: `<statistic> >= 0` that passed is folded. A failed one never folds.
- **Rationale**: K2 has 41 of them (`max>=0` ×5, `rate>=0` ×12, `count>=0` ×12, `value>=0` ×12). Petstore adds them only
  so that k6 keeps each request's numbers. A custom gauge can go below 0, so a failed `value>=0` must stay in view.

## R5. The time bar shows the average request

- **Decision** (choice 2 A, recommended): the bar draws the average request from start to end: blocked, with connecting
  and TLS inside it, then sending, waiting and receiving.
- **Rationale**: averages add up (k6's duration average is the sum of its three parts' averages on K1, K2 and K3), and
  percentiles do not. A browser's network panel draws a request this way.
- **To check in stage 3**: that k6's blocked contains connecting and TLS for a new connection. K2 agrees: Login's
  average blocked is 3.11 ms against 3.01 ms of connecting, and the setup request's is 2.15 ms against 1.72 ms.
- **Checked 2026-11-25 against k6's source** (`lib/netext/httpext/tracer.go`): blocked is GetConn to GotConn
  (`trail.Blocked = gotConn - getConn`), and on a new connection ConnectStart, ConnectDone and the TLS handshake all
  happen between the two, so blocked contains connecting and TLS; on a reused connection both are 0. Duration is
  `trail.Sending + trail.Waiting + trail.Receiving` exactly. The bar is built as chosen: connecting and TLS inside
  blocked, then sending, waiting and receiving.

## R6. The filter bar

- **Fact**: its Requests row is a list of ticks with no text box, and it narrows charts only.
- **Recommendation** (choice 3 A): the ticks narrow the k6 tab too. It is a new reader of an existing selection, and
  no JMeter report changes. A text box for every tab (C) is a change to three working tabs and belongs in its own spec.

## R7. Remembered choices are per report

- **Decision**: `localStorage` keys under the run's id, as drafts are.
- **Rationale**: the spec asks for it per report. A global key from a k6 report would also reach JMeter reports in the
  same browser, which the facts flagged for `wir_detailCharts`.

## R8. One toolkit for every table (the owner's widening, 2026-11-25)

- **Decision**: `WIR.table`, a pure core with a DOM adapter (rows Thymeleaf wrote) and a state adapter (rows rendered
  from state), one toolbar row, extracted from the Asserts tab's pipeline. Each table that moves deletes what it
  replaced.
- **Rationale**: today each control exists 2 to 5 times (search 5, sort 4, open and close 5, column chooser 2, chips 2
  designs), and they drifted: five defects were found by reading. The workbook forbids a second mechanism beside an
  existing one, so the toolkit must be the existing one, made general.
- **Alternatives considered**: a new k6-only module (the first plan), rejected by the owner's widening; menus on each
  header (Grafana's table) and a "View" drawer, both rejected by the owner (Q1 A).
- **Facts**: `tmp/002-interactive-tool-tab/unified-table-facts-2026-11-25.md` (main checkout, git-ignored).

## R9. What the standard tools do for a table's reader (checked 2026-11-25)

- **JMeter's dashboard**: sort on every header, nothing else in the page; filtering is a property set when the report
  is built.
- **Grafana's table panel**: the author sets grouping, column hiding and column filters. Grafana's dashboard variables
  filter every panel at once, which is the precedent for the ticks narrowing every table (Q4 A).
- **Grafana's alert list and Grafana Cloud k6**: the reader picks a Grouped or List view and quick status filters.
  This is the precedent for a reader's Group by and the status chips.
- **None of them** has a per-row hide or remembers a reader's choices. WIR keeps both, as the owner asked ("hide rows
  and columns") and as spec 002's FR-032 said.

## R10. The rows R07 reads stay in the HTML

- **Decision**: Requests and the Errors tables keep their server-written rows. The toolkit moves and hides them, but
  never renders them.
- **Rationale**: R07 parses `#samplerTable tbody tr[data-sampler]` from the page and holds each cell's text to
  JMeter's Aggregate Report table. Rendering those rows in JavaScript would take the page's own numbers out of that
  check.
