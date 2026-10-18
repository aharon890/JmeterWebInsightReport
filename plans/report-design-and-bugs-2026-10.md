# Report design pass and bug list — Asserts tab, Server tab, editors

Status: **Group A fixed in 3.1.23** on `fix/client-assert-surfaces` (pinned in
`client-fixes.spec.ts`; U10 moved to the redesign). Group B and the design: not started.
Originally: **findings + proposal, not approved, nothing fixed.** Written 2026-10-02 from two sessions
on the 3.1.22 fixtures (E10, E11, E25, E26): a bug hunt (report-explorer) and a design review
(design-scout). Companion to `plans/server-metrics-components.md` (C1–C9), which this design
assumes. Aaron's own bug list is still to be merged in (§0).

Spot-checked in source before writing: B2 (`report.js:7706`), B3 (`report.js:7836`),
B10 (`ThymeleafReportRenderer.java:224`). The rest are as observed in the browser.

Every finding still has to end as a Playwright spec or a line in `test-plans/EXPLORATORY-LOG.md`
(CLAUDE.md, "Claude as an active tester"). Not done yet.

## 0. Aaron's list

_To merge: what he has seen, with steps. Anything here not in §1–§2 gets reproduced and added._

## 1. Bugs

Split by whether the component redesign removes the surface. **Group A survives the redesign** —
it is in the client Asserts tab, the Summary, or the shared editor machinery — and can be fixed
independently. **Group B** is in Server-tab code the redesign rewrites; each is a requirement for
the new design rather than a patch to the old.

### Group A — fix regardless of the redesign

| # | Sev | Where | Bug | Cause |
|---|---|---|---|---|
| B1 | major | Asserts editor | A non-numeric warn (`5o0`, `abc`) is accepted with no message, and Save silently **drops the warn band**. The server editor and the Java mapper refuse the same input. | client editor band validation |
| B2 | major | Asserts table "Actual", Summary "Worst breaches" | Shows the **first** matched sampler's value, not the worst: E11 `default:p95` Actual 100 ms while Budget says worst 1,972 ms. Contradicts the Budget column beside it. | `worstMatch()` ranks by status only, first wins on a tie; also reads `rule.results` (capped — CLAUDE.md says never) |
| B3 | major | Summary "Worst breaches" | A WARN row is shown against the **fail** limit it did not cross (`596 ms vs > 999,999 ms`). | `breachRow()` uses `rule.fail \|\| rule.warn` |
| B4 | major | Summary "Worst breaches" | Not ordered by breach size: E11 lists a 200% breach first and leaves a 4,816% one out of the top three. Asserts tab orders the same rules differently. | sort by status rank only, no tiebreak |
| B8 | minor | Asserts editor preview | Both bands empty → preview says "Nothing matched", though the sampler matched. Footer is right. | preview reason |
| B10 | minor | Summary card | "Avg P95/P99" shows the whole-run percentile (1,833 ms), not an average (884 ms); its FAIL badge is about a different number (one sampler's rule). | label vs `overall.getP95()` |
| U2 | major | both tabs | No confirmation or undo anywhere: "Discard drafts" threw away 6 changes (mostly server) in one click; server Delete is instant. `report.js` has no `confirm(`. | — |
| U3 | minor | both editors | "warn can never fire": client **blocks** Save, server allows it. CLAUDE.md says it is a WARNING — the client is wrong. Neither flags `warn < 100` + `fail > 50` (can never PASS). | two validators |
| U4 | minor, a11y | both editors | Escape doesn't close; focus doesn't enter the dialog; Tab escapes behind the scrim; Cancel drops focus to `<body>`; no `aria-modal`/`aria-labelledby`; server Warn/Fail inputs unlabelled. | shared slide-over |
| U5 | minor | Asserts editor | CRITERIA hint squeezed to 63 px and overflows the panel; editor body scrolls sideways. | CSS |
| U9a | minor | header | Server chip is a button to its tab; `ASSERTS FAIL` badge is an inert span. Make both buttons. | template |
| U9b | minor | Asserts | Count line omits `1 disabled` (Summary shows it) and counts the invalid rule as "evaluated" under a "1 rule could not run" banner. | count line |
| U9c | minor | ⊕ wizard | Cancel leaves you on Asserts, not the Samplers tab you came from. | wizard |
| U10 | cosmetic | charts | fail label and handle overlap; dark-mode series/handle contrast; y-axis stretched to the fail line squashes the data; Collection time has no timezone. | charts/CSS |

### Group B — Server-tab code the redesign rewrites (carry over as requirements)

| # | Sev | Bug today | Requirement for the new design |
|---|---|---|---|
| U1 | major | Server tab has no Save, no unsaved pill, no "draft restored" notice; the Asserts tab says "see the Server tab", which has no save control. | Save/Download + unsaved pill on **both** tabs, through `WIR.document`. |
| B5 | major | Validate says "20 instances" for a series the board shows as 21 (20 drawn). D22 promises an exact lookup. | Count from the true instance count, not the thinned list. |
| B6 | minor | The red finding banner stays after the draft fixes the problem. | Findings say whether they describe the run as generated or the draft, and clear on the draft. |
| B7 | minor | "sustained for" / "breach budget" with an empty value saves as no tolerance, silently. | Refuse an empty tolerance value. |
| B9 | minor | `slope` actual and bands shown without "/min". | Units of a slope read per minute everywhere. |
| U6 | minor | Status chip counts ignore the Gate filter; the empty result offers no "clear filters". | One filter row, counts that follow the other filters, a clear action. |
| U7 | minor | A query with surrounding spaces isn't trimmed: shows `lag`, status NEEDS RE-COLLECTION beside a collected `lag`. | Trim custom queries (escape hatch, C9). |
| U8 | minor | Client error wording reused on the server: "would report PASS" when Save is already disabled; tolerance-as-percentage advice; `0s` "not a duration". | Server-specific messages. |
| U9d | minor | Generate from sources drops seeds into the first gate with no review and no unit. | Seeding has a review step and carries the catalogue's unit. |

What works and should be kept (verified): drag-to-retune, the "→ X if saved" projection incl.
"→ ? if saved" on capped series, NOT RE-JUDGED, client-rule edits re-judging dependent server
asserts, chip/badge agreement, ids holding quotes or HTML. No console errors; no injected HTML
executed.

## 2. Design problems (design review)

1. **Empty Server tab is a dead end** — text only, no button; the Asserts tab offers "+ Add your
   first assert".
2. **The client filter bar sits above the Server tab** — Samplers, Metric P95, Granularity do
   nothing there and look as if they should.
3. **Filter chrome**: three segmented groups + 12 status buttons wrap to 2–3 lines (~140 px) and
   duplicate the gate badges.
4. **The board is 11 columns and flat**: two are plumbing (Source, Query); it scrolls at 1024 px,
   hiding the actions; one gate's asserts are scattered by the severity sort.
5. **Server editor: 13 flat fields**, Id first and required with a value-looking placeholder,
   PromQL needed, bands and gate below the fold, no preview. The client editor has sections,
   auto-id and a live PREVIEW.
6. **Generate from sources never appeared** on E26: it seeds only collected-but-unclaimed series,
   and the collector collects only what is claimed.
7. **Inconsistent words**: `FAIL (1)` vs `FAIL 1`; "Server assert" / gate label / "Gate" for
   overlapping things.
8. **No server card on the Summary tab**; the client has one.

## 3. Proposed design

Assumes C1–C9: components replace sources and gates, **every component builds** (no observe-only,
C6), catalogue metrics with "Custom query…" as the escape hatch (C9), no connection details in the
file (C8).

### Server tab

```
Server                                         [Download annotations] [● 1 unsaved]
Window judged 00:13:20 → 00:14:08 · collected 02:43 IDT · 1 metric not collected ▸

┌ orders-db  Postgres · Orders DB                    FAIL   4 asserts  [+ metric] [⋯]┐
│ Status  Metric                    Actual    Criteria             Cov   Inst         │
│ FAIL    Connections used (%)      91 %      fail > 85 %          100%  1   Edit ⋯   │
│ PASS    Deadlocks                 0         fail > 0             100%  1   Edit ⋯   │
│   ▸ click a row: chart (drag the bands), resolved query, reducer                    │
└─────────────────────────────────────────────────────────────────────────────────────┘
┌ events     Kafka · Event bus                       WARN   2 asserts  [+ metric] [⋯]┐
└─────────────────────────────────────────────────────────────────────────────────────┘
[+ Add component]          Filter: [text…]  [All 12] [FAIL 1] [WARN 1] [Not judged 5 ▾]
```

- **One card per component** replaces the gate badges and the Gate/Source filters. Failing cards
  open by default.
- **Six columns.** Source and Query leave; Reduce shows only when overridden. The resolved query
  sits in the expanded row beside the chart.
- **Rows are minimized by default, as on the Samplers tab** (Aaron, 2026-10-03): a ▾ beside the
  status badge opens chart panels under that row only — "Value over time" (with the band lines,
  draggable) and "Per instance". Each panel has a selector like the Samplers tab's.
- **No provenance in the GUI** (Aaron, 2026-10-03): no catalogue version, resolved query, backend
  or reducer panel on the Server tab. The report works with the final metrics it is given; how they
  were fetched is the collector's business. The resolved query stays in `server-metrics.json` and
  the CI/CD JSON for troubleshooting, and is never drawn. An "Expand all rows" button sits in the toolbar. No row
  opens by itself, not even a FAIL.
- **One filter row**, shaped like the Asserts tab's: text filter, `FAIL 1` chips, Not judged
  broken down under ▾.
- **Save / Download / unsaved pill on this tab** (U1).
- **Collection** becomes one status line, expandable to per-metric errors.
- **The client filter bar is hidden on this tab** (Time range is the only candidate to keep).

### Add flow

```
+ Add component                                (slide-over)
  TYPE         [Postgres] [Kafka] [Custom]     ← cards, each naming its exporter
  ID           orders-db        DESCRIPTION  Orders DB
  ▸ Advanced   match  instance="orders-db:5432"   ← collapsed, absent by default (§4 of the components plan)
  [Cancel] [Add component]  → opens "+ metric" on the new card

+ metric                                       (slide-over, same skeleton as the Asserts editor)
  TARGET     orders-db · Postgres              (shown, not asked)
  METRIC     [search the catalogue……………]
             Connections used (%)   max   gt
             Deadlocks              last  gt 0   ← invariant: band prefilled
             Cache hit ratio        min   lt
             ───────────
             Custom query…                       ← escape hatch: swaps in Query + Unit + Reduce + Validate
  CRITERIA   + warn band    FAIL if [>] [85] %   (the Asserts tab's band widget)
  PREVIEW    PASS · actual 91 %   — or "not collected yet: re-run wir-collect"
  DETAILS ▸  id (auto: orders-db.connections.used.pct), description, reduce override,
             requires load, divide by, enabled
  [Cancel] [Save]
```

- **Large catalogues** (Aaron, 2026-10-03 — MSK alone publishes ~100 metrics over four monitoring
  levels): the single-metric radio list is replaced by two steps.
  1. **Pick metrics** — a wide dialog, three columns: filters (search; "Recommended only";
     "Has data in this run"; categories with counts), the metric list grouped by category (★
     recommended, a sparkline of this run, the peak value, a checkbox — several can be chosen), and
     a detail pane for the focused metric (description, unit, one line per…, worse when, which
     monitoring level it needs, its chart in this run, the suggested band). Selected metrics sit as
     removable chips in the footer. "Custom query…" stays one link in that footer.
  2. **Set thresholds** — one table for everything picked: this run's sparkline and peak, warn and
     fail prefilled from the run (a prefill rule applied to all rows, editable per row), a live
     verdict per row, and rows without data marked NO DATA. "Add N asserts" is disabled until every
     row is complete.
  The catalogue therefore carries, per metric: category, description, unit, dimension ("one line
  per broker"), direction, required level, and a recommended flag.
- **Empty state**: "+ Add your first component" with the type cards inline.
- **Delete** asks, or offers undo — the same on both tabs (U2).

### One product, two tabs

- **One editor skeleton** (TARGET / METRIC / CRITERIA / PREVIEW / DETAILS), one band widget, one
  validator for what blocks Save (U3), one keyboard/focus behaviour (U4).
- **One table toolbar** and one chip wording.
- **Save and the unsaved pill on both tabs**, through `WIR.document`.
- **A server card on Summary**, mirroring the Asserts card.
- **Header**: both badges are buttons to their tabs; the server chip names the worst component.

## 4. Decisions for Aaron

**Answered 2026-10-03:** D1 → **(b) the whole catalogue** of each declared component is collected;
D2 → undo (shipped in 3.1.23); D3 → Group A fixed first (3.1.23); D4 → **hide the client filter
bar entirely** on the Server tab. Step 4 is delivered as three local commits — 4a the board,
4b Add component + metric picker + thresholds, 4c Generate review, Summary server card,
screenshots — with one report at the end.

- **D1 — what the collector fetches.** (a) Only the metrics you chose — today's behaviour. Then
  "seed from catalogue" can only propose metrics whose data is not there yet. (b) The whole catalogue
  for each declared component — a type has 6–8 metrics, so it is cheap, and the report can then
  offer "these metrics were also collected — add an assert?" with real values and suggested bands.
  **Recommend (b).**
- **D2 — Delete and Discard: confirm or undo?** Recommend undo (a toast with "Undo" for a few
  seconds): it never interrupts the common case.
- **D3 — Group A timing.** Fix Group A now, on this branch, before the redesign (they are client
  bugs, independent of it) — or batch it with the redesign? Recommend now: B1–B4 make the client
  verdict surfaces show wrong numbers today.
- **D4 — the client filter bar on the Server tab**: hide it entirely, or keep Time range only?
