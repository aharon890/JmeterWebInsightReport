# Feature Specification: An organised, interactive k6 tab

**Feature Branch**: `feat/002-interactive-tool-tab`

**Created**: 2026-11-24

**Status**: Draft. The next phase after spec 001: planning starts once spec 001 is released (owner decision 1), and
building waits for the owner's go-ahead (Principle I).

**Input**: the owner, 2026-11-24, after testing spec 001's k6 tab on the petstore k6 run (K2) and the k6 shapes run
(K1), before merging:

- "Thresholds are not grouped ? same for HTTP timings. it looks a very long lists without any organization and
  inability to filter by request / sampler name or any of the functionality like to hide / group by result, threshold
  condition .... etc . the page is very very raw and barren . it is useless for information troubleshooting .. and does
  not fit the report dynamic and interactive nature."
- "so as i see it we have two choices ... 1. continue developing as planned and later to add the features i mentioned
  or 2. add these now and then build on top of it. which one ? since we already have a plan, i think you should do a
  note for next development phase like /speckit-specify for what i mentioned ..etc"

## Owner decisions

1. **Spec 001 ships as planned; this is the next phase** (2026-11-24, the owner's choice 1, which was also the
   recommendation). The k6 tab's numbers are right and tested, so spec 001 is released and perf hub builds on it. This
   spec changes how the tab presents those numbers, not the numbers or the command perf hub calls, so the two can go
   ahead side by side.
2. **Always-true thresholds are folded** (2026-11-24, recommended option chosen). A threshold that holds for any run
   (`max>=0`, `count>=0`, `rate>=0`, `value>=0`) judges nothing; petstore's tests add one per request only so that k6
   keeps that request's numbers. Each group shows them as one line with a chip to show them, so K2's first view lists
   its 9 real thresholds (FR-007).

## What the k6 tab is today (spec 001, US3)

The k6 tab (named after the tool, after Server and before Notes, only on a k6 run) has five sections. Each is one flat
table with no search, sort, grouping, filter, hide or link, and the page's filter bar is hidden on this tab:

| Section | Rows on K2 (petstore checkout smoke) | Rows on K1 (k6 shapes run) |
|---|---|---|
| Counters (iterations, dropped iterations, VUs allocated) | 3 | 3 |
| Thresholds (metric, expression, result; failed first) | 50, of which 41 are always-true thresholds (`max>=0`, `count>=0`, `rate>=0`, `value>=0`) and 1 failed | 3, 1 failed |
| Checks (group, check, passes, fails, pass rate) | none ("No checks ran") | 2 |
| Group duration (k6's columns) | 1 | 2 |
| HTTP timings (one row per request and timing part, k6's columns) | 78 (13 × 6) | 78 |

What a tester cannot do there today: see by how much a threshold failed (the value k6 measured is not shown), see the
thresholds of one request together, set the 41 always-true rows aside, find which request spends its time waiting for
the server, or narrow anything to one request.

## What the standard tools do (the rule: follow the standard tool)

- **k6's own end-of-test summary** groups the thresholds under their metric and writes the measured value beside each
  expression, for example `http_req_duration{name:Login}` then `✗ 'p(95)<=600' p(95)=779.72ms`. Checks are printed
  under their group. The timing parts are one line each with k6's columns (avg, min, med, max, p(90), p(95)).
- **Grafana Cloud k6** (k6's commercial results page) has separate Thresholds, Checks and HTTP views. The HTTP view is
  one row per request with its numbers, a search, and a per-row detail with charts over time. A threshold's view draws
  its metric over time with the limit on it.
- **This report's own tables** already do what the owner asked for elsewhere, and the k6 tab must use the same
  mechanisms, not a second set (workbook rule: no parallel mechanisms):
  - the Requests table: a search as you type, sort by any column, a column chooser, column widths, × to hide a row and
    "Show all", the group tree (a request under its group), the detail panel under a row, CSV download;
  - the Asserts tab: result chips with counts (FAIL, WARN, PASS…) that filter the table, a toolbar search and filters;
  - the Errors tab: groups that open and close, with "Expand all" and "Collapse all";
  - the page's filter bar, shared by Requests, Errors and Timeline.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Find the threshold that failed, and by how much (Priority: P1)

A tester opens the k6 tab after a run and sees at once which threshold failed, the value k6 measured against its
limit, and how far over it was. The thresholds are grouped (by metric, as k6's summary groups them, or by request,
condition or result) and can be narrowed by result and by text.

**Why this priority**: the thresholds are the section a tester opens the tab for, and today the one failure on K2 sits
among 50 flat rows with no measured value, so the tab cannot answer "why did k6 fail this run".

**Independent Test**: open K2's k6 tab. The thresholds read "1 failed · 49 passed", the only open group is
`http_req_duration`, and its first row is Login's `p(95)<=600` with `p(95) = 779.72 ms` and "179.72 ms over (30%)".
Switch the grouping to "Request" and Login's thresholds sit together.

**Acceptance Scenarios**:

1. **Given** K2's report, **When** the k6 tab opens, **Then** a count bar reads 1 failed and 49 passed, a group that
   holds a failed threshold is open, every other group is closed and shows its own counts, and no scrolling is needed
   to read the failed row.
2. **Given** any threshold row, **When** it is shown, **Then** it gives the metric with its tags as k6 wrote it, the
   expression, the value k6 measured for the statistic the expression names (`p(95)`, `rate`, `count`, `max`,
   `value`…) in k6's units (ms for times, a percentage for a rate, as k6's summary writes them), and k6's result; a
   failed row also gives the distance to the limit, in the metric's unit and as a share of the limit.
3. **Given** the grouping control, **When** the tester picks Metric, Request, Condition or Result, **Then** the rows
   regroup that way (Request: by the `name` tag, with the thresholds tied to no single request in their own group;
   Condition: by the expression text, so every `p(95)<=600` sits together; Result: failed, then passed).
4. **Given** the result chips, **When** the tester clicks "failed", **Then** only failed thresholds show, and the chip
   reads as the Asserts tab's chips read.
5. **Given** the search box, **When** the tester types "Login", **Then** only thresholds whose metric, tags or
   expression contain it show, as the Requests table's search narrows its rows.
6. **Given** a threshold of one request, **When** the tester clicks the request's name, **Then** the Requests tab
   opens on that request's row with its detail panel.

---

### User Story 2 - See where each request's time goes (Priority: P1)

A tester sees one row per request with the six timing parts (blocked, connecting, TLS handshaking, sending, waiting,
receiving) side by side for one statistic, with a bar showing each part's share, and can sort to find the request that
waits longest for the server.

**Why this priority**: the timing parts are k6's main troubleshooting measure (is the time in the network, the
connection or the server?), and 78 rows of one part each cannot be compared or sorted.

**Independent Test**: open K2's HTTP timings. There are 13 rows (one per request, under its group, and an "every
request" row first) instead of 78. Sort by waiting, and the request with the longest server wait is first. Switch the
statistic to p(95), and every cell changes to p(95).

**Acceptance Scenarios**:

1. **Given** K2's report, **When** the HTTP timings section shows, **Then** it has one row per request in the Requests
   tab's tree order (a request under its group), plus an "every request" row on top, and columns for the six parts,
   k6's request duration (sending + waiting + receiving) and a bar of the parts' shares.
2. **Given** the statistic control, **When** the tester picks avg, min, med, max, p(90) or p(95) (p(99) too when the
   run's summary lists it), **Then** every part's cell shows that statistic, in ms to the hundredth as today; avg is
   the default.
3. **Given** any column header, **When** clicked, **Then** the rows sort by it within each group, as the Requests
   table sorts.
4. **Given** a request row, **When** expanded, **Then** it shows every statistic of every part for that request (the
   rows today's tab shows for it).
5. **Given** the search box, ×, "Show all" and the column chooser, **Then** they work as they do on the Requests table.

---

### User Story 3 - Checks, group duration and counters, organised (Priority: P2)

Checks read as k6 prints them, under their group, failed first, with a pass-rate bar, result chips and a search.
Group duration follows the Requests tab's group tree. The counters are tiles at the top of the tab, like the Summary
tab's cards.

**Why this priority**: K1 shows the value (a check that always fails, nested groups), but a run without checks gets
nothing from it, so it comes after the two sections every k6 run has.

**Independent Test**: open K1's k6 tab. Checks sit under "Complete Checkout Flow", "server error is 200" first with 0
of 12 passed; the "failed" chip leaves only it. The tiles read 11 iterations, dropped iterations "not measured" (K1's
summary has no such counter) and 3 VUs allocated.

**Acceptance Scenarios**:

1. **Given** checks in nested groups, **When** shown, **Then** each sits under its group path, and the groups open and
   close as the Errors tab's groups do.
2. **Given** the counters, **When** the tab opens, **Then** iterations, dropped iterations and VUs allocated are tiles
   at the top, each with k6's name for it.
3. **Given** a short strip at the top of the tab, **When** the tab opens, **Then** it says how many thresholds and
   checks passed out of how many, and names the failed ones, so the first screen answers "did k6 see a problem".

---

### User Story 4 - One filter for the whole report (Priority: P2)

The page's filter bar, which the Requests, Errors and Timeline tabs share, shows on the k6 tab too and narrows every
section to the requests it names.

**Why this priority**: a tester following one request (Login) through the report should not lose that filter on the k6
tab.

**Independent Test**: on K2, type "Login" in the filter bar on the Requests tab, then open the k6 tab: only Login's
thresholds and timing row show, with the whole-run rows and the counters still there.

**Acceptance Scenarios**:

1. **Given** a filter set on another tab, **When** the k6 tab opens, **Then** its threshold, timing, check and group
   rows are narrowed by it, and the rows tied to no request (whole-run thresholds, counters) stay.
2. **Given** a filter set on the k6 tab, **When** the tester goes back to the Requests tab, **Then** the same filter
   applies there.

---

### User Story 5 - A request's timing parts and limits over time (Priority: P3)

In a request's detail panel (the one the Requests tab opens under a row), the six timing parts are drawn over time,
and a k6 threshold on that request is drawn as a line at its limit, as the report draws an assert's limit today.

**Why this priority**: it answers "did the server wait grow during the run", which a whole-run number cannot. It is
the largest piece and can be cut to a later phase without losing US1-US4.

**Independent Test**: on K2, open Login's detail panel: a chart shows the six parts over the run, and the
`p(95)<=600` limit is a line on the response time chart.

**Acceptance Scenarios**:

1. **Given** a k6 run, **When** a request's detail panel opens, **Then** a chart shows each timing part over time at
   the report's time buckets.
2. **Given** a k6 threshold on that request's duration, **When** its chart is drawn, **Then** the limit is a line on it,
   labelled as k6's threshold (not as an assert).

---

### Edge Cases

- **No thresholds, or no checks**: the section says so in one line, as "No checks ran" does today.
- **A threshold tied to no request** (`http_req_failed` over the run, `dropped_iterations`, a custom metric): grouped
  by request, it sits in a "Not tied to one request" group; the search and the page filter never hide it.
- **Tags other than `name`** (`group`, `scenario`, `status`, `expected_response`, a custom tag): the tags are shown as
  k6 wrote them; grouping by request uses `name` only.
- **A custom metric** (K2's `request_window_ms`): the measured value is shown without a unit when k6's summary gives
  none, and as ms or bytes when it says the metric holds times or data.
- **No measured value** (run data written by spec 001's release, or a summary that lacks the statistic): the cell says
  "not recorded", never 0.
- **Many requests** (hundreds of names): groups start closed except the ones with a failure, and the page stays
  responsive (SC-004).
- **Long request names**: as the Requests table handles them (resizable column, the full name on hover).
- **The always-true thresholds**: see FR-007.
- **A run from another tool** (spec 001's 'other' column, US4): the same controls over that tool's thresholds, checks
  and counters, in its own words; HTTP timings only when its run data carries timing parts.

## Requirements *(mandatory)*

### Functional Requirements

**Thresholds**

- **FR-001**: The thresholds MUST be grouped, with a grouping control offering Metric (the default, as k6's summary
  groups them), Request (the `name` tag), Condition (the expression text) and Result.
- **FR-002**: Each threshold MUST show its metric with tags, its expression, the value k6 measured for the statistic
  the expression names, in k6's units, and k6's result; a failed one MUST also show its distance to the limit, in the
  metric's unit and as a share of the limit. The result stays k6's; the report judges nothing (spec 001, decision 4).
- **FR-003**: A count bar with result chips MUST show how many thresholds failed and passed, and a chip MUST filter to
  its result, through the same mechanism and look as the Asserts tab's chips.
- **FR-004**: Groups MUST open and close, with open-all and close-all; a group holding a failed threshold MUST start
  open and every other group closed; each group's header MUST show its counts.
- **FR-005**: A search box MUST narrow the thresholds by metric, tag or expression text as the tester types.
- **FR-006**: The rows MUST sort by any column within their group.
- **FR-007**: The always-true thresholds (an expression that holds for any run, such as `max>=0`, `count>=0`,
  `rate>=0`, `value>=0`; 41 of K2's 50, which petstore's tests add only so that k6 keeps per-request numbers) MUST be
  folded into one line per group ("41 always-true thresholds") with a chip to show them (owner decision 2). They count
  in the totals as k6 counts them, and none is hidden for good.
- **FR-008**: A threshold's request name MUST link to that request on the Requests tab, with its detail panel open.

**HTTP timings**

- **FR-010**: The HTTP timings MUST be one row per request, in the Requests tab's tree order, plus an "every request"
  row first, with the six parts, k6's request duration and a bar of each part's share as columns.
- **FR-011**: A statistic control MUST switch every cell between avg (the default), min, med, max, p(90) and p(95),
  and p(99) when the run's summary lists it; numbers stay in ms to the hundredth.
- **FR-012**: Search, sort, ×, "Show all" and the column chooser MUST work as on the Requests table, through the same
  mechanisms.
- **FR-013**: A request row MUST expand to every statistic of every part for that request.

**Checks, group duration, counters**

- **FR-020**: Checks MUST sit under their group path, failed first within a group, with passes, fails and a pass-rate
  bar, result chips and a search; groups open and close as the Errors tab's do.
- **FR-021**: Group duration rows MUST follow the Requests tab's group tree.
- **FR-022**: The counters MUST be tiles at the top of the tab, in the Summary tab's card style, under k6's names.
- **FR-023**: A strip at the top MUST give the thresholds and checks passed out of how many, and name the failed ones.

**The whole tab**

- **FR-030**: The page's filter bar MUST show on the k6 tab and narrow every section to the requests it names; rows
  tied to no request MUST stay.
- **FR-031**: Every table on the tab MUST download as CSV with the numbers as shown, as the Requests table does.
- **FR-032**: The tester's choices (grouping, statistic, open groups, hidden rows, sort, columns) MUST be remembered
  for that report in that browser, as the Requests table's sort and columns are, and the page MUST work without them.
- **FR-033**: Every new word MUST be in the words table (`page-words.json`) in the k6 and 'other' columns; a run from
  another tool MUST get the same controls in its own words.
- **FR-034**: Every control MUST work from the keyboard, and every reader MUST be shown the same page whatever their
  browser's language and time zone (`every-reader-same-page.spec.ts`).
- **FR-035**: A JMeter run's report MUST NOT change in any way (no tool tab, the same text on every tab, the goldens
  G1-G13 and `PreChangeBaselineTest` unchanged).

**Over time (P3)**

- **FR-040**: A k6 request's detail panel SHOULD chart its six timing parts over time at the report's time buckets.
- **FR-041**: A k6 threshold on a request's duration SHOULD be drawn as a line at its limit on that request's response
  time chart, labelled as k6's threshold.

**Data**

- **FR-050**: The run data's threshold record MUST be able to carry the value the tool measured for the statistic its
  expression names (from k6's summary), as an optional field. Run data without it MUST still read, and its rows say
  "not recorded". The published run data schema gains the field as an optional, additive change.

### Key Entities

- **Threshold**: a metric with its tags, an expression, the statistic the expression names, the value the tool
  measured for it with its unit, and the tool's result. It may name a request (the `name` tag) or not, and may be
  always-true.
- **Timing row**: a request (with its group path), and for each of the six parts and k6's request duration, the
  statistics k6 reports (avg, min, med, max, p(90), p(95), and p(99) when listed).
- **Check**: a group path, a name, passes and fails.
- **Counter**: a tool's count with its name (iterations, dropped iterations, VUs allocated).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On K2, the failed threshold, its measured value, its limit and the distance between them are on the
  first screen of the k6 tab, with no click and no scrolling.
- **SC-002**: On K2, the HTTP timings take 13 rows instead of 78, and one click (a sort) puts the request with the
  longest server wait first.
- **SC-003**: Typing a request name narrows every section of the tab in one step, on K2 and on the 134,406-request run
  of spec 001's T067.
- **SC-004**: On that 134,406-request run, the k6 tab opens and every control answers in under half a second on the
  owner's PC.
- **SC-005**: In a walk-through on K1 and K2, the owner answers five troubleshooting questions from the k6 tab alone:
  which threshold failed and by how much; which request waits longest for the server; which check failed and in which
  group; how many iterations were dropped; whether Login's time is in connecting or in waiting.
- **SC-006**: Every measured value shown equals k6's own summary to the digits written.
- **SC-007**: JMeter reports are unchanged: the four-report before/after text comparison of spec 001, the goldens and
  `PreChangeBaselineTest` all pass.

## Assumptions

- Spec 001 is merged and released first (as 4.5.0); this phase builds on it and ships as the next minor version.
- k6's summary gives each threshold metric's values (verified on K1, K2 and K3: the failed Login threshold's metric
  carries `p(95) = 779.72`), so the measured value needs no second calculation.
- The controls reuse the report's existing table mechanisms (the Requests table's search, sort, columns, ×, tree and
  detail panel; the Asserts tab's chips; the Errors tab's groups; the page's filter bar), extended where needed, never
  copied.
- Grouping by metric is the default because k6's own summary groups that way; the other groupings are the owner's
  "group by result, threshold condition".
- How the timings bar, the tiles and the strip look is shown to the owner as screenshots of a mock-up during the plan,
  before building (the owner cannot see text sketches).
- Perf hub's spec 010 does not depend on this phase: it shows whatever k6 tab the release it uses has.

## Dependencies

- WIR spec 001 (PR #43) merged and released.

## Out of scope

- Compare for k6 runs (spec 001, decision 6).
- Turning a k6 threshold into an assert from the tab (a possible later idea).
- k6's browser, WebSocket and gRPC metrics.
