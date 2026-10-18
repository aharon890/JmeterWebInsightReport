# Getting Started

## What Is Web Insight Report?

Web Insight Report is an **Apache JMeter plugin** that generates modern, interactive web reports from your load test results — a powerful alternative to JMeter's built-in HTML dashboard.

**Why use it instead of the native report?**

| | Native JMeter Report | Web Insight Report |
|---|---|---|
| Interactivity | Static charts, no filtering | Zoom, pan, filter, sort, fullscreen, drag-reorder |
| Sampler filtering | None | Per-sampler toggle, color picker, presets |
| Error analysis | Basic table | Charts (pie, bar, timeline), expandable error rows with response bodies |
| Asserts / Pass-Fail | Not supported | Declarative rules with PASS/WARN/FAIL verdicts, authored and tuned in the report itself, Apdex score |
| Server metrics | Not supported | Server asserts — the values you expect from the servers, compared with the metrics fetched for the run (Prometheus, a recording of it, or a CSV export), on their own tab and with their own status |
| Baseline comparison | Not supported | Side-by-side comparison with regression detection |
| Dark mode | No | Full dark theme + colorblind mode |
| Annotations | No | Markdown notes, timeline markers, verdict, sampler notes |
| CI/CD integration | No | JSON summary + JUnit XML output |
| Chart export | No | PNG, SVG, CSV per chart/table |
| Customization | Limited | Palette, line thickness, metric toggle, granularity, presets |

The report is a **single self-contained HTML file** — no server, no CDN, no internet required. Just open it in any browser.

## Requirements

- Apache JMeter **5.6.3+**
- Java **11+**

## Installation

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Copy JARs to JMeter

```bash
cp jmeter-report-core/target/jmeter-report-core-1.1.0.jar $JMETER_HOME/lib/ext/
cp jmeter-web-insight-report/target/jmeter-web-insight-report-1.1.0.jar $JMETER_HOME/lib/ext/
```

### 3. Use in JMeter

**GUI mode:** Add the **Web Insight Report** listener to your test plan (Add → Listener → Web Insight Report).

**CLI mode (non-GUI):** The listener works with `-n` flag — the report generates automatically when the test ends.

```bash
jmeter -n -t test.jmx \
  -Jwebinsight.report.output=/results \
  -Jwebinsight.report.filename=report_${timestamp}.html \
  -Jwebinsight.report.title="My Load Test"
```

**From existing JTL (no test plan change):**

```bash
jmeter -g results.jtl -o report-dir/
```

See [Export & CI/CD](11-Export-Integration.md) for setup details on the `-g -o` path.

## Quick Configuration

| Property | Default |
|----------|---------|
| `webinsight.report.title` | `JMeter Web Insight Report` |
| `webinsight.report.output` | Working directory |
| `webinsight.report.filename` | `web-insight-report.html` |
| `webinsight.apdex.threshold` | `500` (ms) |
| `webinsight.report.junit` | `false` |
| `webinsight.report.servermetrics` | none — the payload `wir-collect` wrote, for server asserts |

Place `report-annotations.json` in the output directory for asserts, notes, and timeline markers. Place `baseline.jtl` for run comparison. Server asserts take two more steps after the run — fetch the metrics, then generate the report that judges them — see [Server tab](13-Server-Tab.md).

---

![Summary Overview](images/01-summary-overview.png)

## Report Layout

The report has 3 main areas:

### Header
![Header](images/02-header.png)

- **Report title** — configurable via GUI or `-Jwebinsight.report.title`
- **Subtitle** — shows "Generated: YYYY-MM-DD HH:mm:ss UTC±hh:mm" in your browser's time zone
  (hover it for the time as the generating machine wrote it, which is what the CI/CD JSON's
  `generatedAt` carries), JMeter version, and test duration
- **Colorblind toggle** (eye icon) — swaps green/red to blue/orange
- **Dark mode toggle** (moon/sun icon) — full dark theme
- **Status badge** — shows overall test status:
  - `ASSERTS PASS` / `ASSERTS WARNING` / `ASSERTS FAIL` (when the run declared asserts)
  - `NO ASSERTS DECLARED` (nothing was declared — reported, not judged)
  - `ASSERTS NOT EVALUATED` (rules were declared and none of them judged anything — a renamed
    sampler, a metric this run could not produce, everything parked)

  The badge answers one question: what did the declared asserts decide. It does **not** report
  errors — an error count is not a verdict, and errors have their own header card and tab.
- **Server chip** — beside the badge, on a run that declares server asserts. It speaks for the
  server side, which is judged separately and never folded into the badge:
  - `Server · Kafka FAIL · 2 more` — the worst component (FAIL, then NOT EVALUATED, then WARN,
    then PASS), and how many other components the Server tab shows
  - `Server · nothing judged` — the server asserts were evaluated, but no component was reported
  - `Server · NOT COLLECTED` — the interim report: nothing has been collected yet
  - `Server · REFUSED` — the server-metrics payload given to the report was refused

  Click it to open the Server tab.

### Tab Navigation

Eight tabs organize the report:

| Tab | Purpose |
|-----|---------|
| **Summary** | At-a-glance metrics, response time chart, top 5 slowest, notes preview |
| **Timeline** | Full time-series charts (9 chart types) |
| **Samplers** | Statistics table with sorting, filtering, inline detail charts |
| **Errors** | Error breakdown charts and detail tables |
| **Compare** | Baseline vs current comparison (only visible when `baseline.jtl` exists) |
| **Asserts** | Every declared rule and its verdict; author, tune and save them. Always reachable — it is where you go to *declare*, so hiding it until something had been declared left no way in |
| **Server** | Server asserts — expected server metrics against what was collected for the run, by gate. Always shown; on a run that declared none it says so (see [Server tab](13-Server-Tab.md)) |
| **Notes** | Test notes (Markdown), timeline markers, verdict |

A **"Filters ▾"** dropdown next to the tabs controls which filter bar rows are visible.

### Filter Bar

![Filter Bar](images/06-filter-bar.png)

A persistent bar below the tabs with 3 collapsible rows:

- **Row 1:** Palette (Default/Okabe-Ito/High Contrast), Line thickness (Thin/Medium/Thick), Metric (Mean/P50/P90/P95/P99/Max), Granularity (1s to 10min)
- **Row 2:** Time range display (wall-clock/elapsed toggle), Presets (save/load/delete)
- **Row 3:** Sampler checkboxes (select/deselect individual samplers, All/None buttons, color dots)

All filter settings affect all tabs globally. Settings persist in browser localStorage.

## Output Files

Each report generation produces:

| File | Description |
|------|-------------|
| `web-insight-report.html` | Self-contained interactive report |
| `web-insight-report.json` | Machine-readable CI/CD summary |
| `web-insight-report.xml` | JUnit XML (when enabled) |
|  `web-insight-data.js` | External chart data (large tests only) |
