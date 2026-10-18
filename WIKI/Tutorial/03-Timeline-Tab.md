# Timeline Tab

Full time-series visualization of test metrics over the test duration.

![Timeline Overview](images/07-timeline-overview.png)

## Charts

9 charts are displayed in this default order:

1. **Response Time Over Time** — per sampler + active threads overlay
2. **Throughput Over Time** — requests/sec per sampler
3. **Error Rate Over Time** — error % per sampler
4. **Bytes Throughput** — received/sent KB/s per sampler
5. **Connect Time vs Latency** — stacked area breakdown
6. **Response Codes Over Time** — stacked bars by HTTP status code
7. **Percentiles Comparison** — grouped bars (P50/P90/P95/P99/Max)
8. **Scatter Plot** — individual data points for outlier detection
9. **Heatmap** — response time distribution over time

### Chart Examples

**Response Time Over Time:**

![Response Time Chart](images/08-chart-response-time.png)

**Error Rate Over Time:**

![Error Rate Chart](images/08c-chart-error-rate.png)

**Response Codes Over Time:**

![Response Codes](images/08e-chart-response-codes.png)

**Response Time Percentiles Comparison:**

![Percentiles](images/08g-chart-percentiles.png)

**Scatter Plot:**

![Scatter Plot](images/08f-chart-scatter.png)

**Response Time Heatmap:**

![Heatmap](images/08d-chart-heatmap.png)

## Chart Controls

Every chart has these controls in its header:

| Control | Location | Behavior |
|---------|----------|----------|
| **Drag handle** (☰) | Left of title | Drag to reorder charts; order saved to localStorage |
| **Hide** | Right of title | Hides the chart section; text changes to "Show" |
| **Fullscreen** | Right of title | Expands chart to full viewport; click again to exit |
| **PNG export** | Chart toolbox (top-right) | Downloads chart as PNG image |
| **SVG export** | Chart toolbox | Downloads chart as SVG vector |

![Chart Controls](images/08h-chart-controls.png)

### Hide / Show Behavior

1. Click **Hide** on any chart → chart section collapses, button text changes to "Show"
2. A **"Show N Hidden Charts"** link appears in the toolbar above the charts
3. Click **"Show Hidden Charts"** → all hidden charts restore, all buttons revert to "Hide"
4. Hidden chart state is saved to localStorage

## Chart Interactions (ECharts)

All charts support these interactions:

| Interaction | How | Effect |
|-------------|-----|--------|
| **Hover tooltip** | Mouse over data point | Shows exact values at that time |
| **Zoom** | Click-drag on chart | Zooms into selected time range |
| **Pan** | Shift + drag | Scrolls the zoomed view |
| **Time range slider** | Drag handles at bottom | Adjusts visible time range |
| **Legend toggle** | Click legend item | Shows/hides that series |
| **Cross-chart sync** | Hover/zoom any chart | All other charts follow |
| **Drag a threshold line** | Grab the handle at its right end | Retunes that rule as a draft |
| **+ Assert from chart** | Button, then click a level | Opens the editor seeded with it |

### Threshold lines are rules

Every declared rule judging the charted metric draws its `fail` band as a dashed line labelled
with the rule id. The strictest is emphasised and the rest dimmed — five identical lines would
suggest five independent limits when only one can ever be the binding one.

**A line is drawn only when the chart is showing a sampler that rule judges.** Untick a sampler
in the filter bar and the rules about it stop drawing; tick it and they come back. A limit
stretched across a chart that is not showing the sampler it applies to is a limit over a series
it does not judge, and on a ten-sampler plan with a p95 rule each it produced ten lines, nine of
them about nothing on screen. A `scope: overall` rule always draws — it genuinely applies to the
whole chart, whatever is on it.

**Drag a line to retune it.** Releasing commits a draft, exactly like typing in the editor.

**+ Assert from chart** arms a crosshair; clicking a level opens the assert editor seeded with
it, targeting the sampler whose series you clicked, or the whole run if you clicked empty grid.
The direction comes from the metric rather than the gesture: on the throughput chart the level
you click becomes a **floor** (`fail <`), because worse means smaller there, and the rounding
goes downward for the same reason — rounding a floor up would move it towards the value it
exists to protect. On a latency chart it is a ceiling, rounded up. The button is there whether
or not any rule exists yet, so the chart can be the way in on a report that declared nothing.

A thin dotted line marks the **run aggregate**. It is there because these rules judge the
whole-run aggregate, not the per-bucket series the limit is drawn over — a threshold floating
above a bucketed line invites exactly the wrong reading. Seeing the aggregate beside the limit
makes the real comparison visible.

See [Asserts](12-Asserts-Tab.md).

### Smart Y-Axis Units

Response time axes auto-adapt based on the maximum value:
- < 1,000 → milliseconds (ms)
- 1,000–60,000 → seconds (s)
- 60,000–3,600,000 → minutes (min)
- \> 3,600,000 → hours (hr)

Tooltips show human-readable durations (e.g., "5m 50s" instead of "350000 ms").

## Response Time Heatmap

![Heatmap](images/08d-chart-heatmap.png)

A special visualization showing response time distribution over time:

- **X-axis:** Time
- **Y-axis:** Response time buckets (0-50ms, 50-100ms, 100-200ms, 200-500ms, 500-1K, 1K-2K, 2K-5K, 5K+)
- **Color:** Light blue (few samples) → Red (many samples)

Reveals patterns line charts miss:
- Bimodal distributions (cache hits vs DB queries)
- Response time drift over time
- Outlier bursts

## Metric Toggle Impact

![Metric Toggle](images/06d-metric-toggle.png)

Changing the **Metric** toggle in the filter bar (Mean/P50/P90/P95/P99/Max) affects:
- Chart 1 (Response Time Over Time) — switches the metric displayed
- Summary tab chart — same metric applied

Other charts (Throughput, Error Rate, etc.) are unaffected.

## Granularity Impact

![Granularity Dropdown](images/06e-granularity-dropdown.png)

Changing **Granularity** in the filter bar (1s/5s/10s/30s/1min/5min/10min):
- All time-series charts re-render with averaged data points
- Higher granularity = smoother lines, fewer points
- Underlying data stays at 1-second resolution
- Default auto-detected based on test duration:
  - < 5 min → 1s
  - 5–15 min → 10s
  - 15–30 min → 30s
  - 30–60 min → 1min
  - 1–2 hours → 5min
  - \> 2 hours → 10min

## Ramp-Up Marking

When configured, a semi-transparent overlay marks the ramp-up period on all timeline charts — helping you visually separate the warm-up phase from steady-state data.

**Configure via CLI:**
```bash
-Jwebinsight.report.rampup=30000   # 30 seconds in milliseconds
```

**Default:** `0` (no ramp-up marking)

The overlay spans from the test start to `start + rampup` duration, drawn as a light-colored rectangle behind the chart data.
