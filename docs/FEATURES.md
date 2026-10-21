# JMeter Web Insight Report — Feature Guide

A single self-contained web report generated from JMeter test results. No server, no CDN, no internet required — just open in any browser.

---

## Report Structure

The report is organized into **8 tabs**:

| Tab | Purpose |
|-----|---------|
| **Summary** | At-a-glance metrics, quality gate strip and server card, response time chart, top 5 slowest samplers, Copy Verdict and Copy Summary |
| **Timeline** | Full time-series charts — response time, throughput, error rate, bytes, connect/latency, response codes, percentile comparison, scatter plot |
| **Samplers** | Detailed statistics table with sorting, filtering, column control, inline detail expansion, per-cell assert verdicts |
| **Errors** | Error breakdown charts, error timeline, error detail records with expandable response bodies |
| **Compare** | Baseline vs current run overlay and regression detection |
| **Asserts** | The client quality gate — rules table, editor, wizard, findings banner, save/round-trip. **Always reachable**, whether or not the run declared a rule |
| **Server** | Server asserts — the values you expect from the servers, compared with the metrics `wir-collect` fetched for the run: one badge per component, a board of asserts, charts, an editor. **Shipped visible**: on a run that declared nothing it says so, and on the interim report it says what was declared and not yet collected. See [Server tab](Tutorial/13-Server-Tab.md) |
| **Notes** | Test notes (Markdown), timeline annotations, what changed against the baseline |

---

## Interactive Charts (Apache ECharts)

The time-series charts support:
- **Hover tooltips** — exact values at any data point
- **Zoom** — mouse wheel over the chart to zoom into a time range
- **Pan** — click-drag a zoomed chart to scroll the view without losing zoom level
- **Time range slider** — draggable slider at bottom of each chart
- **Legend toggle** — click legend items to show/hide individual series
- **Legend truncation** — sampler names longer than 30 characters are cut to 27 and an ellipsis in chart legends; hover tooltip shows full name
- **Hide/show charts** — hide individual charts via "Hide" button on any tab (Timeline, Errors, Compare); "Show N Hidden Charts" restores them
- **Drag-and-drop reorder** — drag charts by grip handle to rearrange them on the Timeline, Errors and Compare tabs; layout persisted per tab in localStorage
- **Cross-chart synchronization** — hover/zoom one chart, all others follow via echarts.connect()
- **Timer/Clock toggle** — switch between wall-clock time (14:30:50) and elapsed timer (1:20:15) on all timeline charts. Adaptive precision: seconds shown when zoomed in, hidden for long tests. Time Range selector always shows full date+time.
- **Smart Y-axis units** — response time axes auto-adapt: ms for fast responses, sec/min/hr for slow ones. Unit determined by the max value in the chart. Tooltips also show human-readable durations (e.g., `5m 50s` instead of `350000 ms`).
- **PNG export** — download button (the download icon in the chart's toolbox) on each chart
- **SVG export** — download button next to PNG for vector graphics
- **CSV export** — download button on every data table (see [Table CSV Export](#table-csv-export))
- **Fullscreen** — expand any chart to full viewport
- **Ramp-up marking** — semi-transparent overlay on the ramp-up period (when configured)

### Available Charts

**Timeline tab:**
- Response Time Over Time (per sampler, with active threads overlay)
- Throughput Over Time (per sampler)
- Error Rate Over Time (per sampler)
- Bytes Throughput Over Time (received/sent bytes per sampler)
- Connect Time vs Latency vs Processing (stacked area breakdown)
- Response Codes Over Time (stacked bar by response code of the failed samples, built from the first 500 error records)
- Response Time Percentiles Comparison (grouped bar: p50/p90/p95/p99/max per sampler)
- Response Time Scatter Plot (one point per sampler per time bucket, at that bucket's mean response time)
- Response Time Heatmap (response time distribution over time — see below)

**Error tab:**
- Error Breakdown — pie chart by HTTP status code
- Error Breakdown — bar chart by sampler
- Error Timeline — scatter plot of individual errors over time

**Compare tab:**
- Response Time Comparison — current vs baseline overlay
- Throughput Comparison — current vs baseline overlay

**Per-sampler detail view (inline in Samplers tab, configurable via dropdown):**
- Response Time Histogram (distribution bins)
- Per-Sampler Response Time Over Time
- Per-Sampler Bytes (received/sent)
- Throughput Over Time
- Error Rate Over Time
- Connect Time & Latency
- Percentiles Over Time (P50/P90/P95/P99)

---

## Sampler Filtering

A persistent **filter bar** appears below the tabs on every tab but Server, whose charts it does not affect:

- **Sampler checkboxes** — toggle each sampler on/off; affects the charts on every tab the bar is shown on
- **All / None** — quick toggle buttons
- **Deselected samplers** appear dimmed (45% opacity) with hidden checkmark
- **Auto-hide utility samplers** — JSR223, Debug, BeanShell, and JMeter internal samplers (\_\_*) are unchecked by default
- **Auto-hide 0-request samplers** — samplers with no requests are unchecked by default
- **Sampler name truncation** — long names truncated with ellipsis + hover tooltip in filter bar

When samplers are deselected:
- Their lines disappear from all time-series charts
- Their errors disappear from the error charts (see [Error Deep-Dive](#error-deep-dive)) — unless
  Σ Total is ticked, in which case the error charts count every sampler's errors; the two error
  tables keep every row
- The Summary tab's response time chart follows; the header and summary cards and the Samplers table always cover every sampler

### Filter Presets
Save and load filter configurations:
- **Save** — saves current sampler visibility, metric, palette, line thickness, and hidden columns to localStorage
- **Presets dropdown** — select a saved preset to load it
- **Del** — removes the selected preset
- **Default** — the dropdown's first entry; resets sampler visibility, metric, palette, line thickness and columns to their initial state

### Time Range Selector
A read-only time range display in the filter bar shows the current zoomed range:
- Automatically syncs with chart zoom/pan state
- **Reset** button restores all charts to full time range

### Collapsible Filter Rows
A **"Filters ▾"** dropdown in the tab navigation bar with per-row visibility toggles:
- ☑ Palette / Line / Metric (the row that also holds Granularity)
- ☑ Time Range / Presets
- ☑ Samplers

Uncheck any row to fully collapse it and reclaim vertical screen space. Each row is independently toggleable. State persists in localStorage. Hidden rows don't affect active settings — all filters remain applied.

---

## Color Customization

### Palette Selection
Three built-in palettes available via toggle buttons:
- **Default** — standard ECharts palette
- **Okabe-Ito** — colorblind-friendly palette (deuteranopia/protanopia safe)
- **High Contrast** — maximum visual separation

### Per-Sampler Color Override
Click the **color dot** next to any sampler name in the filter bar to open a color picker:
- Grid of swatches from all palettes
- Native color input (`<input type="color">`) for any custom color
- Custom colors persist in **localStorage** across page reloads; choosing a palette clears them

### Line Thickness
Toggle between **Thin** (1px), **Medium** (2px), and **Thick** (3px) line widths for all chart lines.

All color and thickness settings persist in localStorage.

---

## Percentile Toggle

Switch the response time charts between metrics:
- **Mean** — average response time
- **P50** — 50th percentile (median)
- **P90** — 90th percentile
- **P95** — 95th percentile
- **P99** — 99th percentile
- **Max** — maximum response time

The selected metric applies to the Summary and Timeline response time charts and to the Compare
tab's Response Time Comparison. Per-sampler percentiles are computed per 1-second time bucket.

### Granularity Control

A dropdown in the filter bar lets you adjust the time-series aggregation level: **1s, 5s, 10s, 30s, 1min, 5min, 10min**. Higher granularity smooths charts by folding data points into larger buckets — a sample-weighted average for response times, percentiles and error rates, the bucket's rate for throughput and bytes, the maximum for Max — useful for long tests where 1-second resolution produces too many points.

**Auto-detection:** The default granularity adapts to test duration:
- < 5 minutes → 1s (raw)
- 5-15 minutes → 10s
- 15-30 minutes → 30s
- 30-60 minutes → 1min
- 1-2 hours → 5min
- 2+ hours → 10min

Changing granularity instantly re-renders the time-series charts; the heatmap, the percentile bars, the response-code bars and the Compare tab's charts keep their own resolution. The underlying data remains at 1-second resolution — only the display aggregation changes.

---

## Response Time Heatmap

A heatmap visualization at the bottom of the Timeline tab showing response time distribution over time:
- **X-axis:** time, one column per time bucket (a bucket in which nothing ran may have no column) — the Granularity selector does not apply to it
- **Y-axis:** response time buckets — 0-50ms, 50-100ms, 100-200ms, 200-500ms, 500-1K, 1K-2K, 2K-5K, 5K+
- **Color intensity:** number of samples in each bucket (light blue = few, red = many)

Reveals patterns that line charts miss:
- Bimodal distributions (fast cache hits + slow DB queries in different buckets)
- Response time shifts over time (e.g., requests gradually moving to a higher bucket)
- Outlier bursts (a cluster of slow requests appearing in a higher bucket)

**Per-sampler tooltip:** When more than one sampler contributed to a cell, hovering it shows the per-sampler breakdown (which samplers contributed how many samples to that bucket).

**Sampler filter integration:** Respects the sampler filter bar — deselecting a sampler removes its data from the heatmap. Transaction children excluded when toggle is off.

All standard chart features: drag-to-reorder, hide/show, fullscreen, PNG/SVG export, zoom slider, dark/light mode.

---

## Samplers Tab

### Statistics Table
Full table with columns: Sampler, Samples, Errors, Error %, Mean, Median, P90, P95, P99, Min, Max, Throughput, Recv KB/s, Apdex.

- **Sortable** — click any column header (ascending/descending)
- **Searchable** — text filter box above the table
- **Column visibility** — "Columns" dropdown to show/hide any column
- **Drag-and-drop column reorder** — drag column headers to rearrange; order persisted in localStorage
- **Column resize** — drag the divider on the right edge of any column header. Double-click a divider to fit the column to its widest value. Widths persist in localStorage and are keyed to the column, so they survive a reorder or a hide/show. Once any column has been resized a "Reset Widths" button appears in the toolbar to return every column to its automatic width. Also available on the error tables and the Compare table.
- **Row hiding** — click the X button on any row to hide it; "Show N Hidden Rows" button to restore
- **Assert badges** — PASS/WARN/FAIL text badge next to each sampler name, the worst verdict of
  the rules judging that sampler. Click it to list every rule that judged the row, with the
  number and the limit.
- **Per-metric assert markers** — every metric cell a rule judges carries its own verdict
  (`✓` PASS, `!` WARN, `✕` FAIL, `?` not measurable), so the table shows *what is gated and how
  it did*, not just a row-level answer. Clicking a marker edits the rule that decided it; the
  tooltip of the ⊕ beside it then reads "Add another assert on …", because the likeliest mistake
  here is a second rule for something already covered.
  Glyph as well as colour, so the distinction survives the colourblind palettes and print.
  Hovering names the rule, the value and the limit.
- **⊕ seeding** — every assertable metric cell offers a ⊕ that opens the editor pre-filled from
  the number you are looking at.
- **Sampler notes** — blue (i) icon with tooltip when notes are defined in annotations

### Virtual "Σ Total" Sampler
A synthetic aggregate row at the top of the statistics table, shown when **Σ Total** is ticked in
the filter bar (it is unticked by default):
- Covers every top-level sampler, whatever the filter bar shows: summed counts and throughput, the
  overall error rate, the whole run's merged percentiles, and sample-weighted Mean and Apdex
- Toggled on/off via the filter bar like any sampler
- Included in the time-series charts when ticked, as a dashed line; on the Compare tab the current
  run's Σ Total line is solid and the baseline's dashed, and both follow the Metric toggle

### Click-to-Expand Detail View
- **Expand indicator** — chevron (▼) on each row hints at expandable detail; flips ▲ and turns blue when expanded
- **Long name truncation** — the sampler column is capped at 520px (with a 260px floor), and a longer name ends in an ellipsis; hover tooltip shows full name
- **Compact name cell** — expand icon, sampler name, and hide button merged into a single cell

Click any sampler row to expand an inline detail panel with **configurable mini-charts**:
- **7 chart types** available via dropdown selector on each slot:
  - Response Time Distribution (histogram)
  - Response Time Over Time
  - Bytes (Recv / Sent)
  - Throughput Over Time
  - Error Rate Over Time
  - Connect Time & Latency (3-line: connect, latency, response time)
  - Percentiles Over Time (4-line: P50, P90, P95, P99)
- **3 default slots** — histogram, response time, bytes
- **+ Add Chart / × Remove Chart** buttons in the toolbar — adds or removes a 4th slot across all expanded rows
- **Global sync** — changing a dropdown or adding/removing a slot applies to all currently expanded sampler rows
- **Persistent** — chart configuration saved in localStorage, survives page refresh

### Transaction Controller Hierarchy
Transaction Controllers are detected from the sample tree the listener receives — a Transaction
Controller's sample and the sub-samples inside it — never guessed from sampler names. A JTL does not
keep that tree, so in a `-g` report every sampler is top-level:
- **Default view:** Only parent transactions and standalone samplers are shown — matches JMeter's native Aggregate Report. Child samplers are hidden from the table, filter bar, and charts to avoid double-counting.
- **"Transaction Details" toggle:** Button in the Samplers tab toolbar. When activated, child samplers appear indented under their parent with full statistics and mini-charts. The toggle also reveals child sampler checkboxes in the filter bar.
- **Σ Total always correct:** The total row always sums parents + standalone samplers only, regardless of toggle state. Parents already aggregate their children, so children are never double-counted.
- **Parent indicators:** Transaction parents display a ▶ triangle icon
- **Child indentation:** Children are visually indented in both the table and filter bar
- **Hierarchy detection:** Only real parent/child pairs from the sample tree are grouped, so REST-style names that share a prefix are never nested by accident. A sampler's own sub-samples (embedded resources, redirects) are nested so their time is not counted twice, but only a Transaction Controller is shown as a transaction

---

## Error Deep-Dive

The Errors tab provides four views:

1. **Error Breakdown** — side-by-side:
   - Pie/donut chart by HTTP status code (fixed color per code: 500=red, 401=blue, etc.), each
     slice labelled with its error class (`500 · HTTP`)
   - Bar chart by sampler (uses sampler palette colors)
2. **Error Timeline** — scatter plot showing individual error occurrences over time, colored by
   sampler. The report keeps the details of the first 500 failed samples; past that the timeline
   plots those 500 and says so under the chart, with the time of the last one plotted
3. **Error Summary Table** — grouped by sampler + response code, with the error class, count and
   percentages, counting every error
   - Search filter, column toggle, row hide
   - A long sampler name ends in an ellipsis, with the full text in a native title tooltip
   - **⊕ on the Class cell** — opens the assert editor with an `errorRate:class=…` rule for that
     sampler and class, seeded from the observed rate
4. **Error Details Table** — the first 500 error records, with time, sampler, code, class,
   message and thread. A **By time / Grouped** switch beside the heading picks the view, and the
   choice is remembered: *By time* is one row per error, *Grouped* one row per sampler and
   response code, most frequent first, with first and last seen — click a group to list each
   occurrence's time and thread, or use Expand All / Collapse All
   - Search filter, column toggle, row hide
   - A long sampler name or message ends in an ellipsis, with the full text in a native title tooltip

### Expandable Error Rows
In the By time view, click any error detail row to expand and see:
- **Request URL** — the URL that failed. Listener mode reads it from the sample; JTL mode reads a
  `URL` column when the file has one (JMeter writes it under `jmeter.save.saveservice.url=true`).
- **Response Body** — the error response, with JSON auto-formatting and **Copy**, **Wrap** and
  **Raw** buttons. **Listener mode only** — the JTL carries no response bodies, so a `-g` report
  shows code, message, thread and URL but no body. Truncated to 16,384 characters by default,
  configurable via `-Jwebinsight.error.body.maxsize`.

Request headers are not captured in either mode.

Note: URL and response body are captured in listener mode. When parsing JTL files, URL is captured if the `URL` column is present; response body is not available in JTL format.

The error charts respect the sampler filter while Σ Total is unticked (ticked, they count every
sampler's errors) — deselecting a sampler hides its errors from all three, from the two breakdown charts only while the run kept every error's details: past 500
errors they count every error, the filter not applied, and their caption says so. The two error
tables do not: they list every error whatever the filter shows, and so does the Error Summary's
Total row. A table's search narrows it.

---

## Asserts — the quality gate

> **One word, on purpose.** The tab, the header badge and the gate strip all say **Asserts**,
> and the CI/CD JSON emits **`assertsEvaluated`**, **`assertViolations`** and a per-sampler
> **`assertStatus`**. Note that JMeter's own **Assertions** (Response Assertion, JSON Assertion)
> are a different feature entirely.
>
> **Two sides, judged separately.** This section is the *client* side: rules over what JMeter
> measured. The *server* side — **server asserts** over metrics fetched from Prometheus, a
> recording or a CSV export — has its own tab, its own components, its own header chip and its own top-level
> `serverStatus` in the CI/CD JSON. Nothing anywhere rolls the two into one verdict; a pipeline
> that wants one answer writes the `and` itself (see [Gating a pipeline with jq](#gating-a-pipeline-with-jq)).

### Assertions — the declarative quality gate

A tester declares exactly which checks decide a run:

```json
{
  "assertions": [
    { "id": "checkout-p95",
      "target": { "sampler": "POST /api/checkout" },
      "metric": "p95", "warn": { "gt": 650 }, "fail": { "gt": 800 } },
    { "id": "run-throughput",
      "target": { "scope": "overall" },
      "metric": "throughput", "fail": { "lt": 30 } }
  ]
}
```

- **Targets** — one sampler, several, a glob or regex pattern with excludes, or the whole run.
- **Metrics** — any of the 13 per-sampler statistics, any percentile (`p50`, `p99.9`, any `pN`
  above 0 and below 100), or 7 whole-run figures. Includes `latency` (time to first byte) and
  `connectTime`, which `elapsed` cannot see: a connect-time regression is the signature of a TLS,
  DNS or pool problem while the total response time barely moves. Byte rates are named in kilobytes, matching the column they sit
  under, so a rule judges the number the report shows.
- **Predicates** — `gt`, `gte`, `lt`, `lte`. One threshold each, so every rule can show actual
  against expected. The tester writes the direction.
- **Error-classified metrics** — `errorRate` and `errorCount` take an optional selector, so a
  rule can name *which* failures it means: `errorRate:class=transport` (no HTTP response at
  all), `class=protocol` (an HTTP failure), `class=nonProtocol` (a 2xx JMeter marked failed —
  an Assertion or script), or `code=` for the response code itself, a glob where `*` is the
  only special character, `regex:` for the rest. A 2% error rate that is all 404s from a stale
  data file is not the same run as 2% connection resets under load, and one scalar could not
  tell them apart. A selector that cannot be read — unknown key, unknown class, uncompilable
  pattern — makes the rule NOT_JUDGED / INVALID_RULE at load with a sentence naming the fix;
  it never widens quietly to "all errors". `code=` is case-sensitive. Full grammar:
  [Gating one kind of error](Tutorial/10-Asserts-Apdex.md#gating-one-kind-of-error).
- **`errorRate` and `errorCount` include HTTP 200s.** An error is any sample JMeter marked
  `success = false`, which covers assertion and script failures as well as HTTP ones. That is
  deliberate: a gate ignoring assertion failures would pass a run where every response was wrong.
  See [What `errorRate` counts](Tutorial/10-Asserts-Apdex.md#metrics).
- **`enabled: false`** parks a rule without deleting it. It is the only modifier: a rule
  either judges or it does not.
- **An unknown key is not an invalid file.** This plugin parses one specific shape; a key outside
  it — a field from a newer version, a note added by hand, something another tool writes — is
  ignored in silence and **preserved** when the report saves the file back, per-rule keys
  included. It cannot change a verdict by definition, and a key mistyped from one of ours still
  surfaces through its consequence: `"faill"` leaves the rule with no band, which is
  `INVALID_RULE`, which is loud.
- **`-Jwebinsight.report.failonassert=true`** makes a FAIL verdict exit non-zero. **Default off**,
  and it stays off — deciding what a FAIL means is the tester's. It folds the client gate's FAIL
  **and a FAIL on any server component** — every component can fail the build. It does not fire on WARN, or on `NOT_JUDGED` for any
  reason. Read the same way on both ingestion paths.
- **A rule the file got wrong exits non-zero anyway, under `failonerror`** — and this is not the
  same statement as the one above. `INVALID_RULE` is not a verdict and never moves `gate.status`;
  it means the gate was not built from what the file declared, which is the plugin failing at its
  job rather than the run failing at its targets. `CONFIG ERROR` on stderr, every artifact still
  written, `=false` to opt out. A rule that merely **matched nothing** is not covered: that one
  can legitimately be a plan running a subset.
- **An Annotations File field in the listener GUI**, so a GUI run can name its asserts file.
  `-J` overrides it; left empty, the file is auto-detected.
- **Only what is declared is judged.** A run with no rules reports `assertsEvaluated: false` — it
  was reported, not judged.

### Evaluation — four statuses, three of which are verdicts

Full reference with the meaning and the fix for each:
[Asserts & Apdex → The statuses](Tutorial/10-Asserts-Apdex.md#the-statuses).

| Gating | Never gates |
|---|---|
| **PASS** · **WARN** · **FAIL** | **NOT_JUDGED**, carrying a reason |

- **A status is a judgement, and only a comparison makes one.** PASS, WARN and FAIL come from
  one function testing a declared band against a measured number. Everything else — a parked
  rule, a target that matched nothing, a metric this run cannot produce, a rule the file got
  wrong — is `NOT_JUDGED` with a `reason`: `DISABLED`, `NO_MATCH`, `NO_VALUE`, `INVALID_RULE`.
  Three more reasons belong to server asserts only and never appear on a client rule:
  `SOURCE_FAILED` (the series could not be collected: the source refused or never answered it, a
  placeholder could not be resolved, a catalogue metric has no Prometheus query, or a CSV row of it
  could not be read; the reason says which), `LOW_COVERAGE` (data arrived below the coverage
  floor, measured over the collected window), `LOAD_NOT_ACHIEVED` (the load it depends on was not
  established: the client load rule it names in `requiresLoad` FAILED or was not judged at all, or
  the one it names in `divideBy` was not judged; a `divideBy` rule that FAILED still divides). One vocabulary
  for both kinds of gate, so a server assert and a client rule that matched nothing say the same
  word.
- **`NOT_JUDGED` never moves the verdict in either direction.** It cannot fail a run and cannot
  pass one. A mistyped metric name never turns the verdict to FAIL when no measured number
  exceeded anything; it is a configuration error, reported as one.
- **A broken rule is loud, and countable on its own.** The finding names it with a
  "did you mean", the JUnit `<failure>` carries the sentence, and the CI/CD JSON publishes
  `gate.notJudged.invalidRule`. Strictness is the pipeline's opt-in: the STRICT line under
  [Gating a pipeline with jq](#gating-a-pipeline-with-jq). (A bare `.gate.failed == 0` reads false
  on every run that declared no client rule, because the `gate` block is written only when one
  was; both lines there read it as `(.gate.failed // 0)`.)
- **Two arithmetics, both closed.** `passed + warned + failed + notJudged.total == declared`,
  and `disabled + noMatch + noValue + invalidRule + sourceFailed + lowCoverage + loadNotAchieved
  == notJudged.total`, so a pipeline can check the sums itself. The three
  server-only keys are always present — zero on a run with no server metrics — because a key
  that appears only when non-zero is a key a sum check cannot rely on.
- **Every declared rule is counted**, including one whose target is missing, empty, written in
  two forms at once, or scoped to something other than `overall`. Such a rule is `INVALID_RULE`,
  and it is in `gate.declared`, in `assertions[]` and in the JUnit suite, so a file of three rules
  reports three.
- **FAIL is tested before WARN**, so a value past both bands is a failure, not a warning.
- **One fold, used twice.** Rule status is the worst JUDGED match; the run verdict is the
  worst judged rule — the same function both times, so a badge and the row it summarises
  cannot be computed by two different rules. A rule that measured eleven samplers and could not
  measure a twelfth reports the worst of the eleven, so a genuine `FAIL` among them stands.
- **Nothing gated ≠ passed.** A gate of nothing but NOT_JUDGED rules reports NOT EVALUATED
  (`gate.status` `NOT_EVALUATED`). A run with no rules at all has no gate: the header reads
  NO ASSERTS DECLARED, and the JSON reports `assertsEvaluated: false` unless server asserts were
  judged. Either way the top-level `status` is `UNKNOWN`. It was reported, not judged.
- **There is deliberately no "inconclusive".** In CI a fifth status must resolve to pass or
  fail anyway — as a failure it is FAIL with extra words, as a pass it is the silent green this
  feature exists to prevent. Where the honest answer is "the question was wrong", the report
  states it as a fact about the run, not as a verdict that withholds judgement.

### Display
- **Header badge** — the client gate's verdict (ASSERTS PASS / ASSERTS WARNING / ASSERTS FAIL;
  when no client rule decided anything, NO ASSERTS DECLARED, ASSERTS NOT EVALUATED, or SERVER
  ASSERTS ONLY on a run whose server asserts alone were judged), never a draft, and never moved by
  a server assert
- **Server chip** — beside the badge on a run with server asserts, and a link to the Server tab.
  It names the worst component, ranked FAIL > NOT EVALUATED > WARN > PASS — the same fold
  as the JSON's `serverStatus`, so the two cannot disagree — and counts the other declared
  components: `Server · Kafka FAIL · 2 more`. With no component reported it reads
  `Server · nothing judged`. With server asserts declared and nothing judged it says which, in
  the Summary card's words and the neutral style: `Server · NOT COLLECTED` on the interim report,
  which has collected nothing yet, and `Server · REFUSED` when the payload given was refused. No
  chip at all on a run that declared no server assert
- **Summary gate strip** — verdict, counts, and the three worst breaches with actual vs limit
- **Asserts tab** — every rule, its verdict, its matches; the editor; drafts; saving
- **Budget column** — how much of each fail band the measured value used: `24%`, `212%`, `<1%`
  when a real budget rounds away, and **empty** when there is no budget to measure. Those last
  two are deliberately different: "used almost none of it" and "has none of it" are opposite
  facts. The bar caps at 100%, the number does not, and the colour comes from the status — so the
  most useful thing it says is *96%, and still PASS*. Only a value exactly on the line reads
  `100%`; one a hair off it reads `100.001%` or `99.998%`, on its own side. It describes the rule's
  worst match — the most severe status first, then the one furthest past the band that status was
  decided against — so a corridor rule (`warn < 10 · fail > 100`) with a WARN at 5 reads `5%`,
  though a PASS at 60 came nearer the fail line. Recomputed in the page, so it follows a
  dragged threshold instead of going stale
- **Broken-rule banner** — every rule the file got wrong, at the top of the tab, not
  dismissible, each with the sentence naming the problem and a **Fix** button. A broken rule
  produces this banner, never a verdict
- **Sampler table** — the worst verdict of the rules judging each sampler, clickable for detail;
  a per-metric verdict marker on every cell a rule judges; ⊕ on every metric cell to seed a rule
  from that number
- **Summary cards** — beside the whole-run P95 and P99 cards, the worst verdict of every rule on
  that metric, whatever it targets (not a verdict on the whole-run figure itself)
- **Chart threshold lines** — every rule on the charted metric, labelled by rule id, draggable
  to retune, with the run aggregate marked beside them. A line is drawn only when the chart is
  showing a sampler the rule judges — a limit on a sampler the filter bar has hidden is a limit
  over a series it does not judge. A `scope: overall` rule always draws: it applies to the whole
  chart whatever is on it.

### Authoring — six ways in
- **Asserts tab** — `+ Add assert` opens the editor on a blank rule.
- **⊕ on a metric cell** — seeds the editor from the number you are looking at, with bands
  already placed around it. A cell that is already judged shows its verdict marker beside the ⊕,
  and clicking the marker edits the existing rule rather than starting a duplicate.
- **`Generate from run…`** — the wizard: pick samplers × metrics, set tolerance and where warn
  sits, and it writes a starter gate that passes as written. It states how many rules a
  selection makes, because samplers × metrics is a multiplication people do not do in their
  heads.
- **`+ Assert from chart`** — on the Summary tab's response time chart or the Timeline tab's
  Response Time, Throughput and Error Rate charts, click a level and the rule is seeded from
  it. Clicking a series line targets that sampler; clicking empty grid targets the whole run.
  The direction comes from the metric, not the gesture: throughput and apdex seed as *fail
  below*, everything else as *fail above*. Available whether or not any rule exists yet, so the
  chart can be the way in on a report that declared nothing.

- **`Edit document…`** — the whole `report-annotations.json` as JSON, validated as you type.
  Reaches every block the rules table does not own: `samplerNotes`, `timelineMarkers`,
  `comparisonThresholds`, `version`, and any key this build does not model.
- **`Open file…`** — load a `report-annotations.json` from disk and evaluate it against the run
  already on screen, without running JMeter again. A strip names what you are
  looking at. With unsaved changes the file is held back rather than applied, naming what is at
  stake: *would replace 3 unsaved changes you have not downloaded*, with **Discard my drafts and
  load the file** and **Keep my drafts**.

Every one of these produces a **draft**. The header badge and gate strip keep showing the verdict
the run was actually gated on; the tab shows what it *would* be if the drafts were saved and the
test re-run, labelled as such. Nothing changes on disk until you download
`report-annotations.json`.

**One file, two slices.** Client rules and server asserts live in the same annotations file, and
every way of saving it — the Asserts tab's save menu, the Notes tab's *Save Annotations* — writes
**both**: the rules, the `serverMetrics` block, and everything else in the file. The Server tab's
*Download* and *Edit document…* are the same file and the same editor. Drafts persist per run
(and, for server asserts, per collection), one record holding both slices, and **Discard drafts**
drops both of this page's slices; server edits held for another collection stay saved. A refresh
that restores server edits says so on the Server tab, with that Discard; one that cannot restore
them, because they were made against another collection (the interim report's, say), says how many
were not restored, on both tabs; the first server edit on this page replaces them, and when that
edit is a Delete (or a component left empty) its Undo saves them again. An Undo is offered only until the next change to the document, on either tab,
since it would take that change back too. The unsaved-changes pill counts both, on both tabs, a note included. When
server asserts have unsaved changes the draft banner says so — `· server asserts: K changed — see
the Server tab` — and its verdict reads *Client verdict if saved and re-run*: a server edit cannot
move the client verdict, and the banner does not fold a server verdict into it. The Server tab's
component badges keep the verdict the run was gated on and add `→ … if saved` where an edit would
change it.

### Apdex Score

The industry-standard Apdex (Application Performance Index) is computed per sampler and overall:

**Apdex = (Satisfied + Tolerating/2) / Total**, per sampler, to two decimals; the overall score
is the top-level samplers' scores weighted by sample count
- Satisfied: a successful sample with response time <= T
- Tolerating: a successful sample with response time > T and <= 4T
- Frustrated: response time > 4T, or a failed sample whatever its time

Score from 0 to 1, color-coded:
- \> 0.94 = Excellent (green)
- \> 0.85 = Good (blue)
- \> 0.70 = Fair (amber)
- \> 0.50 = Poor (orange)
- <= 0.50 = Unacceptable (red)

**Configuration:** Default T = 500 ms. Override via `-Jwebinsight.apdex.threshold=300` — that is
the only source; the property is what both ingestion paths consult.

**Display:** Apdex card in Summary tab + Apdex column in Samplers table + `apdex` field in CI/CD JSON.

---

## Server asserts — the server side of the gate

You write what you **expect** from the servers; the report is fed what they **actually** did; the
two are compared. Expected is a `serverMetrics` block in the same `report-annotations.json`;
actual is a payload fetched after the run by `wir-collect`, from Prometheus, from recorded
Prometheus range-query responses, or from a CSV export.

```json
{
  "serverMetrics": {
    "components": {
      "orders-db": { "type": "postgres", "description": "Orders DB", "match": "instance=\"orders-db:5432\"" },
      "kafka":     { "type": "kafka",    "description": "Kafka" }
    },
    "asserts": [
      { "id": "db-connections", "component": "orders-db", "metric": "connections.used.pct",
        "warn": { "gt": 70 }, "fail": { "gt": 90 } },
      { "id": "consumer-lag", "component": "kafka", "metric": "consumer.lag.max",
        "warn": { "gt": 1000 }, "fail": { "gt": 5000 } }
    ]
  }
}
```

The file says **what** is expected of **which component** — never where the metrics come from;
that is a `wir-collect` flag.

**Three steps**, because the metrics can only be fetched once the run's window is known:

```bash
jmeter -n -t plan.jmx -l results.jtl                       # 1. the run (an interim report)
./wir-collect.sh --jtl results.jtl \
    --annotations report-annotations.json --out server-metrics.json \
    --prometheus-url https://prometheus.example/api/v1    # 2. fetch the actual values
jmeter -g results.jtl -o report \
    -Jwebinsight.report.annotations=report-annotations.json \
    -Jwebinsight.report.servermetrics=server-metrics.json  # 3. the report that judges and gates
```

`wir-collect.sh` and `wir-collect.bat` are attached to every release beside the plugin JAR (in a
checkout they are under `scripts/`); they find JMeter through `JMETER_HOME` or `jmeter` on the
`PATH`.

Step 3 is the report that judges the server asserts and decides the exit code. `wir-collect` is
a class inside the plugin JAR, run with `java -cp`, never `java -jar` — Jackson and slf4j come
from JMeter, so the classpath is the plugin JAR plus JMeter's `lib/*`. Without the wrapper scripts
(`wir-collect.sh`, `wir-collect.bat`):

```bash
# Linux, macOS
java -cp "$JMETER_HOME/lib/ext/jmeter-web-insight-report-X.Y.Z.jar:$JMETER_HOME/lib/*" com.jmeterwebinsightreport.WirCollect --jtl results.jtl ...
```

```bat
rem Windows cmd.exe (the separator is ';')
java -cp "%JMETER_HOME%\lib\ext\jmeter-web-insight-report-X.Y.Z.jar;%JMETER_HOME%\lib\*" com.jmeterwebinsightreport.WirCollect --jtl results.jtl ...
```

`wir-collect --help` prints both lines with the real JAR name, the options and the exit codes. If
step 3 sets `-Jwebinsight.report.title`, step 2 must pass the same text as `--test-name`: the
title is hashed into the run's identity, and a payload collected for another identity is refused.
A ramp-up is given to both the same way, in milliseconds: `--rampup` and
`-Jwebinsight.report.rampup`. It is cut from the window the server asserts are judged over
(`serverMetrics.collection.excludeRampUp`, on by default), and the report says so in a finding
when the two disagree.

- **Where the metrics come from — exactly one `wir-collect` flag.** `--prometheus-url URL`
  (with `--prometheus-token-env NAME` for a bearer token), `--recording DIR` (recorded Prometheus
  range-query responses, one `<first 16 hex of sha256(query)>.json` per query), or
  `--csv FILE [--from TOOL] [--layout wide [--columns MAP]] [--csv-columns SPEC] [--component ID]
  [--time-zone ZONE]` (a CSV export, one row per point: `time,component,metric,instance,value`;
  `--from` reads a Telegraf, Datadog, OTel or CloudWatch export by that tool's names and converts it
  to the catalogue's unit, `--layout wide` reads Grafana's CSV download, a time column and one
  column per series (`--columns` maps a column by its exact header), `--csv-columns` and
  `--component` read a long export whose columns have other names and no component — InfluxDB 2's
  annotated CSV, blocks and all, InfluxDB 3 and Dynatrace — and `--time-zone` names the zone of a
  time written with none). `--step` sets the resolution and the coverage grid (default `15s`), and
  `--clock-offset` shifts every timestamp for servers whose clocks are known to be off. `--env NAME`
  picks the `serverMetrics.environments` entry `${env.*}` placeholders are resolved from (default:
  the file's `defaultEnv`), `--max-wait` sets how long a source may take to settle (default `10m`),
  and `--no-wait` takes what the sources hold now. Connection details are never in the file.
- **Components.** `serverMetrics.components` names what is being measured: a `type` from the
  built-in catalogue (`postgres`, `kafka`, `redis`, `host-linux`, `aws-ec2`, `oracle`, `influxdb2`,
  `influxdb3`, `jvm`, `http-service`, `kubernetes-pod`, `mysql`) or `custom`, a
  `description` used as its label, and an optional `match` — a PromQL label selector the
  catalogue's queries are narrowed by. Each
  component is one badge, one verdict and one JUnit suite, and **every component can fail the
  build**: to only watch a metric, give its assert a warn band and no fail band.
- **Catalogue metric or own query.** A server assert names its `component` and either a catalogue
  `metric` — whose query, unit, reducer and `across` come from the catalogue, and a `unit`,
  `reduce` or `across` the assert writes overrides the catalogue's — or its own `query`, the
  escape hatch and the only way on a `custom` component. Both, or neither, is `INVALID_RULE`.
- **Reducing a series to one number.** `reduce.kind` is `max`, `min`, `avg`, `last`, `pN`
  (0 < N < 100, e.g. `p95`, `p99.9`) or `slope` (least squares, in units per minute); a `reduce`
  written on an assert replaces the catalogue's whole, so it always names `kind`. On `max` and
  `min`, `reduce.over` takes one of `sustainedFor` (the highest, or lowest, level held for at least
  that long: `30s`) or `breachBudget` (the level exceeded at most that fraction of the window:
  `0.02`). `across` (`max`, `min`, `avg`) folds the instances into one number.
- **The rest of the block.** `environments` names sets of variables for `${env.NAME}` in queries,
  and `defaultEnv` the one used when `wir-collect` is given no `--env`. `collection.coverageFloor`
  (default `0.9`) is the share of the collected window a series must cover, below which its assert
  is `LOW_COVERAGE`; `collection.excludeRampUp` (default `true`) leaves the ramp-up out of the
  judged window. `requiresLoad` (on the block, as a default, or on an assert) names a client
  throughput rule that must PASS or WARN for the assert to be judged, and `divideBy` names one whose
  measured value the assert's number is divided by, per unit of load. Both are optional.
- **The catalogue's types.** `postgres` (postgres_exporter), `kafka` (kafka_exporter and the JMX
  exporter with Strimzi's rules; its request latencies, request-handler idle and lag in seconds have
  no Prometheus query under those, so they are collected from a `--csv` export), `redis` (redis_exporter), `host-linux` (node_exporter: CPU busy
  and steal, memory, swap, OOM kills, disk latency, filesystems, network), `aws-ec2` (CloudWatch
  `AWS/EC2` and `AWS/EBS`, and the CloudWatch agent's `ethtool` for ENA: CPU credits, EBS burst
  balance, IOPS, throughput and limits, ENA network allowances, status checks; no Prometheus query, so collected from a `--csv` export, and four of
  them computed with CloudWatch metric math as their descriptions say), `oracle` (the Oracle Database Metrics Exporter 2.x: sessions and
  processes against their limits, CPU, wait classes, locks, tablespaces; five metrics need the
  custom metrics file in the Server tab tutorial), `influxdb2` / `influxdb3` (InfluxDB's own
  `/metrics`: write and query latency and errors, rejected points, and each engine's own health
  signals, under one set of ids for the metrics both share), `jvm` (Micrometer through Spring Boot
  Actuator: heap against its maximum, live heap growth, GC pauses, threads, process CPU),
  `http-service` (Micrometer's `http.server.requests`: request rate, 5xx share, mean, slowest and —
  with the request histogram switched on — p95 response time), `kubernetes-pod` (cAdvisor and
  kube-state-metrics, per container: CPU and memory against their limits, CPU throttling, restarts,
  OOM-kill restarts) and `mysql` (mysqld_exporter, MariaDB included: connections against
  `max_connections`, refused connections, threads running, row lock waits, slow queries, buffer
  pool hits, replica lag). Each metric says what it means, its unit, how it is reduced,
  which way is worse and, per tool (Telegraf, Datadog, OpenTelemetry, CloudWatch), the tool's name
  for it, its kind of number, a scale and a note on which series to export — or `null` where the
  tool has no single metric, and the computed series goes in the file under the catalogue's name.
- **The CSV format.** One row per point under a header, columns found by name in any order:
  `time,component,metric,instance,value`. `time` is ISO 8601 with an offset or `Z`, or epoch
  milliseconds — a time with no zone is refused, never guessed, unless `--time-zone` names the
  zone. `metric` is a catalogue metric's name or a custom assert's `query` text; `instance` may be
  empty; an empty or `NaN` value is a gap, not zero. A bad row fails only the series it belongs
  to, naming the line, and the collection reads `PARTIAL`. Full rules: [Server tab](Tutorial/13-Server-Tab.md).
- **Keys this format does not take are refused, not ignored.** `sources`, `gates`, `kpis` and
  `packs` in the block, `source`, `gate`, `onMissing`, `required` and the rest of the
  [refused assert keys](Tutorial/13-Server-Tab.md#retired-and-refused-keys) on an assert (and
  `sustainedFor`, `breachBudget` or `over` written at its top level instead of under
  `reduce.over`), and `failsBuild` on a component are each refused with a sentence naming what to
  write instead, so a file carrying one cannot quietly judge less than it says.
- **Statuses.** The same four as the client side, and the same reasons, plus three that belong to
  server asserts only: `SOURCE_FAILED`, `LOW_COVERAGE` and `LOAD_NOT_ACHIEVED`. Coverage is
  measured over the **collected window**.
- **Separate from the client verdict, everywhere.** The Server tab, the header chip, the Summary
  tab's server card and `serverStatus` in the CI/CD JSON. The client badge never moves for a
  server assert.
- **Authored in the report.** *+ Add server assert*, *+ Add component*, and on a catalogue
  component *+ metric*, a picker that proposes thresholds from the catalogue's default or this run;
  an editor that shows the verdict a Save would make as you type (the board's own answer: never
  an estimate on a capped series), a *Validate query* status line under the query field (an exact
  lookup against what was collected, never an estimate), *Generate from this run*, drag-to-retune on the
  charts, and a component's description and match edited, or the component deleted with its
  asserts, from its card's **⋯** — all drafts with an Undo where they remove something, saved with
  the client rules in the one file.

Full walkthrough: [Server tab](Tutorial/13-Server-Tab.md).

---

## Annotations & Notes

### Setup
Place a `report-annotations.json` file in the **report output directory** — the only location
auto-detection searches — or pass `-Jwebinsight.report.annotations=<path>` to use one from
anywhere:

```json
{
  "version": "1.0",
  "testNotes": "## Test Objective\nValidate checkout under 500 users...",
  "timelineMarkers": [
    { "timestamp": 1773702727000, "label": "Test Start", "type": "info", "description": "Ramp-up begins" },
    { "timestamp": 1773702740000, "label": "DB Failover", "type": "warning", "description": "Primary DB switched" }
  ],
  "samplerNotes": {
    "POST /api/checkout": "Includes 2s payment processing delay"
  },
  "assertions": [ ... ]
}
```

### Features
- **Test Notes** — rendered as Markdown (headers, bold, italic, lists, code blocks)
- **Timeline markers** — vertical dashed lines at annotated timestamps on the Summary tab's response time chart and the Timeline tab's Response Time, Throughput and Error Rate charts; color-coded by type (info=blue, warning=amber, error=red, deployment=blue, custom=purple)
- **Sampler notes** — info icon next to sampler names in the table, tooltip on hover
- **In-browser editor** — the Notes tab's notes are always editable, with a Preview toggle and a "Save Annotations" button that downloads the whole annotations file, rules and server asserts included, for future runs

---

## Run Comparison

### Setup
Place a previous run's JTL as `baseline.jtl` in the output directory. The plugin auto-detects it on the next run. Or point `-Jwebinsight.report.baseline` at a JTL anywhere — the way to give `jmeter -g … -o` a baseline, since `-o` takes an empty directory. The Compare tab appears only when a baseline was loaded.

### Comparison Charts
- **Response Time Comparison** — per-sampler current (solid) vs baseline (dashed) lines, aligned by elapsed time starting at 0. Respects sampler filter and metric selector (Mean/P50/P90/P95/P99/Max).
- **Throughput Comparison** — same per-sampler current vs baseline layout for throughput.
- Both charts support fullscreen, hide/show, zoom, cross-chart sync, and dark mode.

### Custom Regression Thresholds
Regression detection thresholds are configurable. Default: P95 increase >10% or error rate increase >2 percentage points.

**Configuration via `report-annotations.json`:**
```json
{
  "comparisonThresholds": {
    "p95PctChange": 15,
    "errorRateChange": 3,
    "meanPctChange": 20,
    "p99PctChange": -1,
    "throughputPctChange": -1
  }
}
```

**Configuration via JMeter properties:**
```bash
-Jwebinsight.compare.p95.threshold=15
-Jwebinsight.compare.errorrate.threshold=3
-Jwebinsight.compare.mean.threshold=20
```

**Supported metrics** (set to -1 to disable):
- `p95PctChange` — P95 response time % increase (default: 10)
- `errorRateChange` — absolute error rate increase, in percentage points (default: 2)
- `meanPctChange` — mean response time % increase (default: disabled)
- `p99PctChange` — P99 response time % increase (default: disabled)
- `throughputPctChange` — throughput % decrease (default: disabled)

**Live UI controls** in the Compare tab — adjust thresholds and click "Apply" to re-evaluate regression flags client-side without regenerating the report. Useful for exploring what thresholds make sense for your application.

### Sampler Comparison Table
- All sampler statistics columns: Samples, Errors, Error %, Mean, Median, P90, P95, P99, Min, Max, Throughput, Recv KB/s
- Current values with inline superscript deltas (red/green, or blue/orange in colorblind mode)
- Sortable columns, drag-to-reorder, column toggle, row hide, search filter
- Status badges, outlined so they read as a comparison rather than an assert verdict: OK, REGRESSION
  (any enabled threshold crossed), NEW (not in the baseline), and REMOVED (ran in the baseline but
  not in this run; its row shows dashes, and it counts as a regression)

### "What Changed" Summary
Auto-generated text summary in the **Notes tab** when baseline comparison exists. Shows:
- Overall P95 and error rate trend direction
- List of regressed samplers (with % change)
- List of improved samplers (with % change)
- New samplers not in baseline

Updates live when regression thresholds are adjusted via the Compare tab controls.

---

## Smart Defaults

The report auto-detects and handles common patterns:
- **Auto-hide utility samplers** — samplers matching JSR223*, Debug*, BeanShell*, __* are unchecked in the filter bar by default, so they are off the charts; the Samplers table still lists them (tick them to chart them)
- **Auto-hide empty samplers** — samplers with 0 requests are unchecked the same way
- **Transaction Controller detection** — parent/child relationships taken from the sample tree the listener receives (a `-g` report has none, so every sampler is top-level); children collapsed by default
- **Ramp-up period marking** — when configured, a semi-transparent overlay marks the ramp-up phase on the time-axis charts of the Summary, Timeline and Errors tabs (not the heatmap or the percentile bars)

---

## CI/CD Integration

### Machine-Readable JSON Output
Every report generation produces a `web-insight-report.json` alongside the web report:

```json
{
  "runId": "3f2a7c19b40de85a",
  "testName": "Checkout Flow Load Test",
  "status": "FAIL",
  "assertsEvaluated": true,
  "duration": 75000,
  "totalSamples": 847293,
  "totalErrors": 1017,
  "errorRate": 0.12,
  "samplers": [
    { "name": "POST /checkout", "sampleCount": 42364, "mean": 891, "p95": 2340,
      "p99": 3980, "errorRate": 0.8, "throughput": 564.9, "apdex": 0.82, "assertStatus": "FAIL" }
  ],
  "assertViolations": [
    { "sampler": "POST /checkout", "status": "FAIL" },
    { "sampler": "Whole run", "scope": "overall", "status": "WARN" }
  ],
  "gate": {
    "status": "FAIL", "declared": 13, "evaluated": 12,
    "passed": 8, "warned": 1, "failed": 1,
    "notJudged": { "total": 3, "disabled": 1, "noMatch": 1, "noValue": 0, "invalidRule": 1,
                   "sourceFailed": 0, "lowCoverage": 0, "loadNotAchieved": 0 }
  },
  "assertions": [
    { "id": "checkout-p95", "target": "POST /checkout", "targetKind": "sampler",
      "metric": "p95", "unit": "ms", "status": "FAIL", "matched": 1,
      "attainment": { "actual": 2340, "failThreshold": 800, "consumed": 2.925, "breached": true },
      "results": [ { "sampler": "POST /checkout", "actual": 2340, "status": "FAIL",
                     "predicate": "gt", "threshold": 800 } ] },
    { "id": "search-tail", "target": "GET /search*", "targetKind": "match",
      "metric": "p99.99.9", "unit": "", "status": "NOT_JUDGED",
      "notJudged": "INVALID_RULE", "reason": "unknown metric 'p99.99.9'", "matched": 0,
      "results": [] }
  ],
  "findings": [
    { "severity": "error", "ruleId": "search-tail",
      "message": "unknown metric 'p99.99.9'. Valid here: sampleCount, errorCount, … p99, p99.9" }
  ],
  "generatedAt": "2026-08-10 09:14:22 UTC",
  "artifacts": {
    "outputDirectory": "/out",
    "html": { "path": "/out/web-insight-report.html", "outcome": "WRITTEN" },
    "json": { "path": "/out/web-insight-report.json", "outcome": "WRITTEN" },
    "data": { "path": "/out/web-insight-data.js", "outcome": "SKIPPED", "detail": "inline" }
  }
}
```

`gate`, `assertions` and `assertViolations` appear only when a client rule was declared, and
`findings` only when there is something to report — but `findings` appears even with no `gate`
block, which is the case that most needs it. The keys above are real and in the order written; the
two arrays are abridged here.

**On a run with server asserts**, three more keys, written only once the server asserts were
evaluated (so not on the interim report, and not when the payload was refused):

```json
{
  "status": "FAIL",
  "serverStatus": "FAIL",
  "serverComponents": [
    { "id": "kafka", "label": "Kafka", "type": "custom", "collectionId": "df8778b7fa474d95",
      "status": "FAIL", "declared": 4, "evaluated": 3, "passed": 1, "warned": 0, "failed": 1,
      "notJudged": { "total": 2, "disabled": 1, "noMatch": 0, "noValue": 0, "invalidRule": 0,
                     "sourceFailed": 0, "lowCoverage": 0, "loadNotAchieved": 1 } }
  ],
  "serverAsserts": [
    { "id": "up-fails", "component": "kafka", "query": "up", "unit": "msgs",
      "reduce": { "kind": "max" }, "across": "max", "status": "FAIL", "actual": 49.0,
      "coverage": 1.0, "instances": 1, "warn": { "gt": 10.0 }, "fail": { "gt": 20.0 },
      "breached": { "gt": 20.0 } }
  ]
}
```

- **`serverStatus`** sits beside the client `status`, which it never changes: the worst status
  over **all** components, ranked `FAIL`, then `NOT_EVALUATED` (a component that judged nothing),
  then `WARN`, then `PASS`. Present exactly when `serverComponents` is.
- **`serverComponents[]`** carries the same block of counts as `gate`, from the same arithmetic,
  plus the component's `id`, `label` (its `description`), `type` and the payload's `collectionId`.
  Asserts that named no declared component are reported under a reserved `"(no gate)"` entry,
  which has no `type` and whose `label` is `"(no component)"`, the name the header chip, the
  console and the Server tab give it.
- **`serverAsserts[]`** — one entry per server assert: what it asked (`component`, `metric` when
  it names a catalogue metric, `query`, `reduce`, `across`, bands), what was measured
  (`actual`, `coverage` of the collected window, `instances`) and its status, with `notJudged` and
  a `reason` when it decided nothing.

The example above is abridged.

### Gating a pipeline with jq

Two lines. Both are tested on every build, and both hold on a client-only, a server-only and a
mixed run.

**DEFAULT** — the verdicts. A run that declared no client rule passes the client half; the server
half refuses only `serverStatus` `FAIL`. A component that judged nothing (`NOT_EVALUATED`)
is not refused here. When it judged nothing because a check could not run — `INVALID_RULE`,
`SOURCE_FAILED`, `LOW_COVERAGE`, or a refused payload an enabled server assert needed — the run
exits 1 through `failonerror`: pair this line with `failonerror` (the default), or use STRICT. A
query that matched nothing (`NO_MATCH`), a parked assert (`DISABLED`), a value that could not be
measured (`NO_VALUE`), a load that was not established (`LOAD_NOT_ACHIEVED`) and a component with
no asserts exit 0, and neither line refuses them. The four not-judged reasons are counted in
`serverComponents[].notJudged`; a component with no asserts is counted in none of them — it reads
`NOT_EVALUATED` with `declared` 0.

```bash
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"' web-insight-report.json
```

**STRICT** — DEFAULT, and also no check the file asked for that never ran, as far as the JSON can
show it: `INVALID_RULE` on the client gate or any server component; `SOURCE_FAILED` or
`LOW_COVERAGE` on any server component (every one builds, so none is exempt); and any ERROR
finding (an annotations file that could not be read, a refused payload a server assert needed).
Meant for `failonerror=false`. What it cannot see is an artifact that failed to be written — the
exit code reports that, and no JSON can.

```bash
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL" and (.gate.notJudged.invalidRule // 0) == 0 and ([.serverComponents[]? | .notJudged.invalidRule + .notJudged.sourceFailed + .notJudged.lowCoverage] | add // 0) == 0 and ([.findings[]? | select(.severity == "error")] | length) == 0' web-insight-report.json
```

`serverStatus` is the verdict, not the health of the check: a component whose judged asserts
PASS while another of its asserts is `LOW_COVERAGE` reads `PASS`, on a run that exits 1 under
`failonerror`. The status is the verdict, a configuration or collection error is the exit code,
and STRICT is the line that refuses both.

Use this JSON in Jenkins/GitLab/GitHub Actions pipelines for automated pass/fail decisions.

### Run Identity

`runId` is 16 hex characters — SHA-256 over the run's own fingerprint (test name, start time,
duration, top-level sample and error counts), taken over the UTF-8 bytes so a Linux container
re-exporting a JTL captured on a Windows workstation derives the same value.

**Every artifact carries it:** the HTML, `web-insight-data.js`, the `.json` (as its first key)
and the JUnit XML (as a `<property>` in every testsuite).

The contract is **same JTL ⇒ same runId; distinct executions ⇒ distinct runId**, and two things
depend on it:

- **The report refuses a foreign chart payload.** See [External Data Mode](#external-data-mode).
- **Both ingestion paths decide "is the report already here mine?" by identity**, not by
  filename, so the listener and the `-g -o` exporter give the same answer in the same directory.
  A filename cannot tell "the listener in this very run already wrote this" from "somebody else's
  report is in the way". The name is how candidates are *found*, not how the decision is *made*.
  Anything that cannot say which run produced it — no `.json`, a truncated one, one with no
  `runId` — counts as not ours and is overwritten (with a log line naming it, whenever a `.json`
  was there to ask), because an overwrite is a far better failure than a run that produces
  nothing and says nothing.

### Exit Codes and Console Markers

`failonerror` covers everything the plugin was told to do and could not:

- an incomplete artifact set — the report written but its `.json` or `.xml` missing — and, on a
  listener run, a total failure to produce anything;
- **a gate that could not be built from what the file declared** — a rule naming an unknown
  metric, an unreadable target, no bands at all, or an annotations file that would not load;
- on the server side, **a server assert that could not run** (`INVALID_RULE` — a defect in the
  file), and **a collection that failed** — `SOURCE_FAILED`, `LOW_COVERAGE`, or a payload that was
  missing or refused (another run's, unreadable) while an enabled server assert is declared.

**This is on by default**; `-Jwebinsight.report.failonerror=false` makes these exit 0. The
opt-out buys back the exit code and nothing else — every marker still prints. One exception: on
`jmeter -g … -o`, a report that could not be written at all exits 1 whatever `failonerror` says,
because JMeter itself fails the report-generation step.

It does **not** cover the verdict. A FAIL gate is a working gate returning an answer, and exits 0
unless `failonassert` asks otherwise. The line is between "the plugin did not check what you
asked" and "the plugin checked, and here is the answer".

| What happened | Exit, by default | The flag that changes it |
|---|---|---|
| Client gate FAIL, or FAIL on any server component | 0 | `failonassert=true` → 1 |
| WARN, or `NOT_JUDGED` as `DISABLED`, `NO_MATCH`, `NO_VALUE` or `LOAD_NOT_ACHIEVED` | 0 | none |
| `INVALID_RULE` on a client rule, or an annotations file that is not JSON | 1 | `failonerror=false` → 0 |
| `INVALID_RULE` on a server assert, `SOURCE_FAILED` / `LOW_COVERAGE`, a `serverMetrics` block that does not read, or a missing or refused payload while a server assert is declared | 0, said as `SERVER NOT JUDGED` | `webinsight.report.server.failonerror=true` → 1 |
| An unquoted `-Jwebinsight.report.x=y` split by PowerShell into a property named `webinsight` | 1 | `failonerror=false` → 0 |
| A refused payload with no enabled server assert declared | 0 (a WARNING finding) | none |
| The report written but its `.json` or `.xml` not | 1 | `failonerror=false` → 0 |
| No report written at all | 1 | listener run: `failonerror=false` → 0; `-g -o`: none |

There is no observe-only component: a metric you only want to watch gets a warn band and no fail
band, and WARN never moves the exit code.

`wir-collect`, the step that fetches the server metrics, has its own rule: it exits **0 whenever
it wrote the payload**, whatever each series' status, and 1 only for a usage, input or
environment fault. The payload records what failed; the report decides what that means.

It is on by default because, off, a pipeline shaped `jmeter -n -t … && publish-report` stays
green when the report it publishes was never written.

Six greppable markers, one line each:

| Marker | Stream | Means |
|---|---|---|
| `WEB-INSIGHT-REPORT: OK <path>` | stdout | the report was published |
| `WEB-INSIGHT-REPORT: INCOMPLETE — <reason>` | stderr | the report exists, a sibling does not; the reason names the artifact |
| `WEB-INSIGHT-REPORT: FAILED <reason>` | stderr | nothing usable was produced; the exception class is named |
| `WEB-INSIGHT-REPORT: CONFIG ERROR — <reason>` | stderr | every artifact is there, but the client gate is not the one the file declared (or, under `webinsight.report.server.failonerror=true`, a server check never ran); always an exit 1 unless `failonerror=false` |
| `WEB-INSIGHT-REPORT: SERVER NOT JUDGED — <reason>` | stderr | server checks the file asked for never ran (a failed collection, coverage under the floor, a server assert that cannot run, a refused payload). The report and the client verdict are complete; the exit code is unchanged unless `webinsight.report.server.failonerror=true`, which prints this as `CONFIG ERROR` instead |
| `WEB-INSIGHT-REPORT: WARNING — <reason>` | stderr | a `webinsight.*` property nothing reads (with the nearest known name), or a path mangled by single backslashes in a properties file |
| `WEB-INSIGHT-REPORT: ASSERT FAIL — <verdict line>` | stderr | `failonassert=true` and a gate said FAIL; `-g -o` only (a listener run logs `webinsight.report.failonassert=true and a gate said FAIL — this run will exit 1` to `jmeter.log` instead) |
| `WEB-INSIGHT-REPORT: exiting 1 because <flag>=true` | stderr | printed as the process exits 1 over a flag, naming `webinsight.report.failonerror`, `webinsight.report.server.failonerror` or `webinsight.report.failonassert`. Not printed when a `-g -o` run exits 1 over an artifact that was not written: JMeter reports that step's failure itself |

`CONFIG ERROR` deliberately arrives with a complete report rather than instead of one — the
report is where you diagnose the config, and its Asserts tab opens on a banner naming each rule
that could not run. Both ingestion paths raise it the same way, without a stack trace: a typo'd
metric name is the user's input, not the plugin crashing.

### JUnit XML Output

Optional JUnit XML report for native CI/CD test result integration. Disabled by default.

**Enable via:**
- GUI checkbox: "Generate JUnit XML" in listener settings
- CLI property: `-Jwebinsight.report.junit=true`

**Output:** `web-insight-report.xml` (or matching the HTML filename). Each top-level sampler = test case, assert breaches = test failures with metric details. If nothing was declared, all test cases pass.

The failure message text is `ASSERT FAIL: …`, and CI jobs grep it. It names every failing metric:
p95, p99, mean and errorRate in a fixed wording (`P95 611ms exceeds threshold`), any other
by its measurement and the band it breached (`p50 119.361 ms breached > 119.36 ms`).

**The suites.** The sampler suite, then the client-rule suite and one suite per server component:

| `<testsuite name=…>` | When | One testcase per |
|---|---|---|
| `<testName>` | always | top-level sampler |
| `<testName> - assertions` | a client rule was declared | client rule |
| `<testName> - <componentId>` | server asserts were evaluated, one suite per component | server assert |
| `<testName> - (no gate)` | a server assert named no declared component | server assert |
| `<testName> - serverMetrics` | server asserts were declared and nothing was judged (the interim report, or a refused payload) | the payload's state (`serverMetrics.payload`: a `<failure>` when a refused payload was needed, else `<skipped>`), plus a `<failure>` per server assert the file makes unrunnable |

The root `<testsuites>` `tests`/`failures` counts are the **sampler suite's only**; each other
suite carries its own counts. In a server suite a FAIL, and a check that could not
run — `SOURCE_FAILED`, `LOW_COVERAGE`, `INVALID_RULE` — is a `<failure>`; the other `NOT_JUDGED`
reasons are `<skipped>`, and a WARN is a `<system-out>`. Each testcase body names the assert's
`component:`, its `metric:` when it names a catalogue metric, then `query:`, `reduce:` (when the reducer could be read), `divideBy:`
when it is a ratio, and `criteria:`. Numbers are in the unit the verdict compares: `B/min` for a
`slope`, `msgs ÷ load:throughput` for a `divideBy`.

Every testsuite opens with the run identity:

```xml
<testsuite name="Load Test" tests="5" failures="1" time="75.0">
  <properties>
    <property name="webinsight.runId" value="3f2a7c19b40de85a"/>
  </properties>
  <testcase .../>
```

**Placement is load-bearing, not cosmetic.** Surefire's `surefire-test-report.xsd` and the
Windyroad `JUnit.xsd` that Azure DevOps names as its JUnit schema both model `testsuite` as an
`xs:sequence` with `properties` *ahead of* `testcase`, and Jenkins' xUnit plugin validates
against an XSD from 2.0.0 on — so a block in the wrong place is a rejected file, which means a
silently missing quality gate. It is written into *every* suite because Windyroad's schema omits
`minOccurs` on `properties`, making it effectively required in every one. And it is a
`<property>` rather than an attribute on `<testsuite>`, because no JUnit schema declares
`xs:anyAttribute` there.

Works with Jenkins, GitHub Actions, Azure DevOps, GitLab CI, and any tool that consumes JUnit XML.

### Table CSV Export

Every data table in the report has a **CSV download icon** (↓) in the top-right corner of its section header, matching the chart toolbox icon pattern:

| Table | Tab | CSV Filename |
|-------|-----|-------------|
| Top 5 Slowest Samplers (by P95) | Summary | `top5-slowest-samplers.csv` |
| Sampler Statistics | Samplers | `sampler-statistics.csv` |
| Error Summary | Errors | `error-summary.csv` |
| Error Details | Errors | `error-details.csv` |
| Sampler Comparison | Compare | `comparison.csv` |

**Key behavior:**
- Exports **all data** from the underlying data model — hidden rows, hidden columns, filtered samplers, and collapsed children are all included
- CSV files include UTF-8 BOM for correct Excel handling
- Hover tooltip: "Download as CSV"
- Icon styled for both light and dark modes

### Copy Summary for Confluence/SharePoint
The "Copy Summary" button on the Summary tab copies a clean HTML table to your clipboard, formatted for pasting into:
- Confluence pages
- SharePoint documents
- Email reports
- Wiki pages

It holds the summary cards and the top 5 slowest samplers, and no verdict. **Copy Verdict**, beside
it, copies the verdict as a few plain lines for a chat or an email: the test name and generation
time, the Asserts verdict with its counts, the server chip's word when server asserts are declared,
and the report's file name.

---

## External Data Mode

For very large tests (data exceeding ~10MB), the report automatically switches to external data mode:
- Chart data is written to a separate `.js` file, named after the report:
  `<report-basename>-data.js`, or `web-insight-data.js` when the report keeps its default
  name. Derived rather than fixed, so two reports in one directory cannot share one payload.
- The report loads it with a `<script src>` tag, which sets `window.__WIR_DATA__`. A
  `fetch()` would be blocked by CORS on `file://` and would force you to serve the report
  over HTTP; the script tag works when the file is simply opened.
- Both the report and the payload carry a run id, and **a mismatch is fatal to the charts**. The
  report names both runs, replaces every chart pane with "Chart withheld", and draws nothing.
  The summary, sampler and error tables are server-rendered from this run's own data, so they
  stay and are correct.

  Withholding is deliberate: a chart from another run is *wrong output*, and a banner above a
  plausible-looking chart is read by nobody once the numbers below it look reasonable. A
  **missing** payload is a different case and has its own message — nothing is wrong there, only
  absent, so the page still renders what it has.
- Missing or foreign, the rules, the server asserts and their verdicts travelled in that file, so
  the Asserts and Server tabs and both Summary cards say **UNAVAILABLE** and why (the Summary
  server card only when the header carries a server chip), and the header's verdicts — rendered
  from the run itself — stand. The page then offers **no Download** of `report-annotations.json`:
  it never received the annotations, and a file saved from it would replace yours with an empty
  one. Drafts are kept per run (by the page's own run id), never under a shared key.
- This keeps the HTML file lightweight while preserving all interactivity
- Configurable via `-Jwebinsight.report.externaldata=true` (force) and
  `-Jwebinsight.report.externaldata.threshold=<chars>` (auto-switch point, default
  10000000; values ≤ 0 are rejected with a warning and the default kept)

---

## Dark Mode

Toggle via the crescent/sun icon in the header. Full dark theme for all components:
- Charts reinitialize with ECharts dark theme, drawn on the card's own background
- A palette colour under 3:1 on the dark card is swapped for a lighter one of its hue (High Contrast
  and Okabe-Ito); assert lines, drag-handle borders and the Server tab's band labels keep 3:1 and
  4.5:1 in both themes
- All UI elements, tables, cards, badges, detail panels adapt
- Assert badges and per-cell verdict markers use dark-appropriate colors, and the cell markers
  carry a glyph as well as a colour so they survive the colourblind palettes and print
- Persists in localStorage

---

## Colorblind Mode

Toggle via the eye icon in the header. Swaps all semantic colors from green/red to blue/orange:
- Pass/OK badges → blue, Fail/Regression badges → orange
- Comparison table deltas → blue (improvement) / orange (regression)
- Error cards, error codes → orange tones
- Assert fail lines and their drag handles on the charts, client and server → orange; warn lines
  and handles → fuchsia, so warn and fail stay apart for a red-green colour-blind reader
- Works independently with dark mode (4 combinations: light, dark, light+cb, dark+cb)
- Persists in localStorage
- Chart line palettes not affected (use Okabe-Ito palette option separately for colorblind-friendly chart colors)

---

## JMeter Integration

### Two Report Generation Paths

| Path | How | JMX change? | Error bodies? |
|------|-----|-------------|---------------|
| **Listener** | Add Web Insight Report listener to JMX | Yes (one-time) | Full (response body, URL) |
| **CLI `-g -o`** | `jmeter -g results.jtl -o report/` | None | Partial (no response body — not in JTL CSV) |

Both paths produce the same interactive Web Insight Report. The listener path captures richer error detail because it has access to the live `SampleResult` object during test execution. The CLI path is ideal for CI/CD pipelines and Docker environments where you can't or don't want to modify the test plan.

### CLI Report Generation (`-g -o`)

Generate a Web Insight Report from an existing JTL file — no test plan changes required:

```bash
jmeter -g results.jtl -o report-dir/
```

The JTL must be in JMeter's CSV format (the default); an XML-format JTL is refused, and an XML
`baseline.jtl` is skipped with a warning (no comparison).

**Setup:** Add one line to `$JMETER_HOME/bin/reportgenerator.properties`:
```properties
jmeter.reportgenerator.exporter.webinsight.classname=com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter
```

The Web Insight Report will be generated alongside JMeter's built-in HTML dashboard in the same output directory. Our report is named `web-insight-report.html` (distinct from JMeter's `index.html`).

**With `-J` properties:**
```bash
jmeter -g results.jtl -o report-dir/ \
  -Jwebinsight.report.title="My Load Test" \
  -Jwebinsight.report.filename='report_${timestamp}.html' \
  -Jwebinsight.report.baseline=/path/to/baseline.jtl \
  -Jwebinsight.report.annotations=/path/to/report-annotations.json \
  -Jwebinsight.report.rampup=30000
```

**Collision detection:** The CLI exporter skips generation only when the report already in the
output directory is **provably this same run's** — its `.json` sibling declares a matching
`runId`, which happens when the listener wrote it during the same execution. Anything else,
including a report that cannot say which run produced it, is overwritten; when its `.json` is
there but names another run, or none, `jmeter.log` says so. Skipping on a name match alone would
let a run produce no report at all and still exit 0.

**Note on error details:** When generating from JTL, error records carry the response code, message, thread, and URL — but not the response body, because JTL CSV does not store it. The report shows an informational message when expanding error details that lack this data.

**Request headers are not captured in either mode.**

### As a Listener
Add **Web Insight Report** listener to your test plan (GUI or non-GUI mode). Auto-detects `report-annotations.json` and `baseline.jtl` in the output directory. Works with `-n` (non-GUI) mode for CI/CD — the listener fires at test end and generates the full report with complete error details.

### GUI Configuration

**Output Directory field:**

| Value | Resolves to |
|-------|-------------|
| *(empty)* | The first of these it can actually write to (each is probed with a real write, and the log names the one chosen): the directory of the listener's own results file — the `filename` property in the `.jmx`, which this listener's GUI does not show and `-l` does not set, so it is normally empty — then the working directory, then the test plan's directory, then `~/.jmeter-web-insight-report`, then a `web-insight-report` folder in the system temp directory |
| `/var/reports` or `D:\reports` | That path (absolute) |
| `reports` | `{working dir}/reports` (relative) |
| `../reports` | `{working dir}/../reports` (relative) |

A `jmeter -g … -o DIR` run writes to `DIR`. `report-annotations.json` and `baseline.jtl` are
auto-detected in that output directory only; with `-g -o` (which needs an empty directory) pass
them with `-Jwebinsight.report.annotations` / `-Jwebinsight.report.baseline`.

The chain applies to the **default only**. An explicitly configured directory is never
relocated — it hard-fails, because a report that is silently not where you put it is its own
bug. Each candidate is tested with a real create-and-delete probe, not `File.canWrite()`, which
on Windows reflects only the DOS read-only attribute and returns true for an ACL-denied
directory.

The working directory alone would be a poor default. In GUI mode it is normally
`$JMETER_HOME/bin`, read-only on most managed installs; in a container it may not be writable by
the run user. A default that commonly fails is not a default.

The working directory sits between the results file and the `.jmx` rather than at either end. A
results filename is a statement of intent — you named a directory and put this run's output in it
— so it outranks wherever the JVM happened to be launched. The `.jmx`'s directory is not a
statement of anything, and ranking it higher would move reports next to the test plans.

**Report Filename field:**

| Value | Produces |
|-------|---------|
| *(empty)* | `web-insight-report.html` + `.json` |
| `my-report.html` | `my-report.html` + `my-report.json` |
| `report_${timestamp}.html` | `report_20260324_185043.html` + `.json` |
| `my-report` | `my-report.html` + `my-report.json` (`.html` is appended when missing) |

A name must be a plain file name: letters, digits, dot, underscore, hyphen and space only, no
path separators, not ending in a dot or a space, and at most 180 characters (not counting
`${timestamp}`). `index` and `statistics` (the names JMeter's own dashboard writes) and Windows
device names (`CON`, `NUL`, `COM1` …) are refused too. A name that breaks a rule is ignored with
a WARN in `jmeter.log`, and the default `web-insight-report.html` is used.

**Report Title field:**

| Value | Result |
|-------|--------|
| *(empty)* | `JMeter Web Insight Report` (default) |
| `Checkout Load Test` | Custom title in report header |

### CLI Properties

All settings can be overridden via JMeter `-J` properties. CLI properties take precedence over GUI fields.

**Report output:**

| Property | Default |
|----------|---------|
| `webinsight.report.output` | Resolved through the chain above (listener runs only; `-g -o` writes to `-o`'s directory) |
| `webinsight.report.filename` | `web-insight-report.html` |
| `webinsight.report.title` | `JMeter Web Insight Report` |
| `webinsight.report.baseline` | Auto-detect `baseline.jtl` |
| `webinsight.report.annotations` | Auto-detect `report-annotations.json` (at WARN — someone else's rules in a shared directory would otherwise decide your gate verdict silently) |
| `webinsight.report.rampup` | `0` (ms, overlay on charts) |
| `webinsight.report.junit` | `false` |
| `webinsight.report.failonerror` | `true` — an incomplete artifact set, or a gate that could not be built from the file, exits non-zero |
| `webinsight.report.failonassert` | `false` — `true` exits non-zero on a client FAIL or a FAIL on any server component |
| `webinsight.report.servermetrics` | none, never auto-detected — the `server-metrics.json` payload `wir-collect` wrote for this run. Pass it to the `-g -o` step only: a listener run is always a new run, so a payload named there is always another run's and is refused (a CONFIG ERROR when a server assert is declared) |
| `webinsight.report.externaldata` | `false` — `true` always writes the chart data to a sibling `-data.js` (see [External Data Mode](#external-data-mode)) |
| `webinsight.report.externaldata.threshold` | `10000000` (characters) — the size above which the chart data goes to the sibling file |

`junit`, `externaldata`, `failonerror` and `failonassert` accept only `true` or `false`,
case-insensitively. Anything else warns and is treated as **unset**: on a listener run `junit`
then falls back to the GUI checkbox, so `-Jwebinsight.report.junit=yes` warns rather than
silently voiding a ticked box, and on `-g -o`, which has no checkbox, it is off; the others fall
back to their defaults — `failonerror=0` leaves the default `true` in force.

**Metrics & thresholds:**

| Property | Default |
|----------|---------|
| `webinsight.apdex.threshold` | `500` (ms) |
| `webinsight.error.body.maxsize` | `16384` (characters) |

**Comparison thresholds** (set to -1 to disable):

| Property | Default |
|----------|---------|
| `webinsight.compare.p95.threshold` | `10` (%) |
| `webinsight.compare.errorrate.threshold` | `2` (%) |
| `webinsight.compare.mean.threshold` | `-1` (disabled) |
| `webinsight.compare.p99.threshold` | `-1` (disabled) |
| `webinsight.compare.throughput.threshold` | `-1` (disabled) |

**Full CLI example:**
```bash
jmeter -n -t test.jmx \
  -Jwebinsight.report.output=/results/reports \
  -Jwebinsight.report.filename='report_${timestamp}.html' \
  -Jwebinsight.report.title=CheckoutLoadTest \
  -Jwebinsight.apdex.threshold=300 \
  -Jwebinsight.report.junit=true \
  -Jwebinsight.compare.p95.threshold=15
```

**Path resolution** follows JMeter conventions: relative paths resolve from the working directory (where `jmeter` was launched from), not from the JMX file location. Directories are auto-created if they don't exist.

### Data Captured in Listener Mode
Connect time, latency and the request URL come from the sample in listener mode, and from the
JTL's `Connect`, `Latency` and `URL` columns under `-g` when the file has them. One thing only
the listener captures:
- **Response body** — for error records (truncated to 16,384 characters by default, configurable via `webinsight.error.body.maxsize`)

### Distributed Testing

The listener supports distributed runs started as one test — `jmeter -r`, `-R host1,host2`,
or **Remote Start All** in the GUI. In those modes the controller shares a single test tree,
so one listener instance sees every host: it counts hosts as they start and generates one
combined report after the last one ends.

**Use Remote Start All, not per-host Remote Start.** Starting workers individually from the
GUI clones the test tree per action, so each clone gets its own listener with its own host
count. Each one then generates a report covering only its own host, and they overwrite each
other at the same output path — leaving a report that silently contains a fraction of the
load. Give each host a distinct `-Jwebinsight.report.output` if you must start them
separately.

If a worker dies without reporting `testEnded` — a crashed JVM, a dropped RMI connection —
the controller never reaches zero active hosts and **no report is generated**. The first time a
host ends while others are still running, the listener logs a WARN naming how many it is still
waiting on, so this shows up in `jmeter.log` rather than looking like a hang.

### Which samplers the headline numbers cover

The summary tiles, the merged percentiles and the Apdex score are computed over **top-level
samplers**: transaction-controller children are excluded, because their time is already
counted inside their parent.

Auto-hidden utility samplers (names starting `JSR223`, `Debug` or `BeanShell`, case-insensitively,
and JMeter internals starting `__`) are
*not* excluded — they are unchecked in the filter bar but still counted in the tiles, so a
slow setup sampler does move the headline P95. They are, however, excluded from any blanket
pattern rule, so they cannot fail a build on their own; a rule that names them explicitly still
applies.

### Output Files

Names shown for the default report filename. Set `-Jwebinsight.report.filename=myreport.html`
and every sibling follows it: `myreport.json`, `myreport.xml`, `myreport-data.js`.

| File | Description |
|------|-------------|
| `web-insight-report.html` | Self-contained interactive Web Insight Report |
| `web-insight-report.json` | CI/CD machine-readable summary, including an `artifacts` manifest of every path written |
| `web-insight-report.xml` | JUnit XML, when `-Jwebinsight.report.junit=true` |
| `web-insight-data.js` | External chart data (only in external data mode) |
| `.web-insight-publish.lock` | Empty dotfile, always present after a run; `rm -rf dir/*` does not remove it. See below |

### How the artifacts land

All four are written to per-process staging files named `.<target>.<owner>-<pid>-<nonce>.tmp`
beside their target, where the owner is the host's (or container's) name, and only then renamed
into place — payload first, then the HTML that loads it, so
a reader opening the directory mid-publish never finds a page whose `<script src>` points at a
file that is not there yet.

This does **not** make the set atomic; no filesystem replaces N files at once. It shrinks the
window from "the whole render" — seconds to minutes on a large run — to four renames. Run
identity covers the residue: a set that does get mixed says so instead of being believed.

The four renames are taken under a lock on `.web-insight-publish.lock` in the output directory,
so two runs sharing one directory cannot interleave. If the lock cannot be taken — an
unsupported filesystem, a holder past the ten-second timeout — publishing proceeds anyway and
logs a WARN saying the guard was not in force. A lock that silently does nothing would be worse
than no lock.

**The lock file is deliberately never deleted.** Removing the name while another process holds a
lock on that inode lets a third process create a fresh file and lock *that* instead — two
holders, the one outcome the lock exists to prevent. It is an empty dotfile in a directory that
already contains the report.

Because it is a dotfile, `rm -rf dir/*` leaves it behind, and `jmeter -g … -o dir` — which needs
an empty directory — then refuses a directory that looks empty. Clear an output directory with
`rm -rf dir` instead.

Staging debris left by a killed writer is swept on the next run by **process liveness**, not by
age: an age predicate would delete a live writer's staging file on a slow disk. A staging file
whose pid still resolves to a running process is only removed after six hours, as a backstop
against pid reuse. So is one another host or container wrote: its pid means nothing here, so
only age can speak for it.

---

## Technology

| Component | Technology |
|-----------|-----------|
| Plugin | Java (JMeter listener) |
| Percentiles | T-Digest (streaming) |
| Charts | Apache ECharts 5.5.0 |
| Templating | Apache Thymeleaf |
| Annotations | Jackson JSON |
| Output | Self-contained HTML |
