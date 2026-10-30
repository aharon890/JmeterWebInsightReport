# Timeline Tab

Full time-series visualization of test metrics over the test duration.

![Timeline Overview](images/07-timeline-overview.png)

## Charts

A request counts in the second it **ended**, as JMeter's HTML report keys its over-time charts,
except on **Throughput**, where it counts in the second it **started** — JMeter's Hits per Second.

9 charts are displayed in this default order:

1. **Response Time Over Time** — per sampler + a Max Active Threads overlay (the most threads
   running in the window, every thread group together)
2. **Throughput Over Time** — requests/sec per sampler
3. **Error Rate Over Time** — error % per sampler
4. **Bytes Throughput Over Time** — bytes/sec received (solid) and sent (dashed) per sampler
5. **Connect Time vs Latency vs Processing** — stacked area: connect time, latency after connect,
   and processing (response time after latency)
6. **Response Codes Over Time** — stacked bars, responses per second by code: **every** response,
   successes included, as JMeter's Codes per Second counts them (a transaction's own result is not a
   response). It covers the whole run, so the sampler filter does not narrow it; a caption says so
7. **Response Time Percentiles Comparison** — grouped bars (P50/P90/P95/P99/Max) per sampler
8. **Response Time Scatter Plot** — one point per sampler per time bucket, at that bucket's mean
   response time
9. **Response Time Heatmap** — response time distribution over time

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
| **Hide** | Right of title | Removes the whole chart section; **Show N Hidden Charts** brings it back |
| **Fullscreen** | Right of title | Expands chart to full viewport; click again to exit |
| **PNG export** | Chart toolbox (top-right) | Downloads chart as PNG image |
| **SVG export** | Chart toolbox | Downloads chart as SVG vector |

![Chart Controls](images/08h-chart-controls.png)

### Hide / Show Behavior

1. Click **Hide** on any chart → the chart is removed from the page
2. A **"Show N Hidden Charts"** button appears in the toolbar above the charts
3. Click it → every hidden chart comes back
4. Hidden chart state and chart order are saved to localStorage

## Chart Interactions (ECharts)

The time-series charts support these interactions:

| Interaction | How | Effect |
|-------------|-----|--------|
| **Hover tooltip** | Mouse over data point | Shows exact values at that time |
| **Zoom** | Mouse wheel over the chart | Zooms the time axis |
| **Pan** | Click-drag while zoomed | Scrolls the zoomed view |
| **Time range slider** | Drag handles at bottom | Adjusts visible time range |
| **Legend toggle** | Click legend item | Shows/hides that series |
| **Cross-chart sync** | Hover/zoom any chart | All other charts follow |

The Response Time, Throughput and Error Rate charts add two more:

| Interaction | How | Effect |
|-------------|-----|--------|
| **Drag a threshold line** | Grab the handle at its right end | Retunes that rule as a draft |
| **+ Assert from chart** | Button, then click a level | Opens the editor seeded with it |

### Threshold lines are rules

Every enabled rule judging the charted metric draws its bands as dashed lines — fail in the error
colour, warn in the warn colour — each labelled with the rule id, which band it is, and the limit.
On the Response Time chart the charted metric is the one the filter bar's **Metric** toggle picks
(Mean shows `mean` rules, P95 shows `p95` rules). The strictest fail line is emphasised and the
other fail lines dimmed — five identical lines would suggest five independent limits when only one
can ever be the binding one. Warn lines are never emphasised.

**A line is drawn only when the chart is showing a sampler that rule judges.** Untick a sampler
in the filter bar and the rules about it stop drawing; tick it and they come back. A limit
stretched across a chart that is not showing the sampler it applies to is a limit over a series
it does not judge. A `scope: overall` rule always draws — it genuinely applies to the whole chart,
whatever is on it.

**Drag a line to retune it.** Releasing commits a draft, exactly like typing in the editor.

**+ Assert from chart** arms a crosshair; clicking a level opens the assert editor seeded with
it, targeting the sampler whose series you clicked, or the whole run if you clicked empty grid.
The direction comes from the metric rather than the gesture: on the throughput chart the level
you click becomes a **floor** (`fail <`), because worse means smaller there, and the rounding
goes downward for the same reason — rounding a floor up would move it towards the value it
exists to protect. On a latency chart it is a ceiling, rounded up. The button is there whether
or not any rule exists yet, so the chart can be the way in on a report that declared nothing.

When the run has rules, a thin dotted line marks the **run aggregate**. It is there because these
rules judge an aggregate over the whole run, not the per-bucket series the limit is drawn over —
a threshold floating above a bucketed line invites exactly the wrong reading. Seeing the
aggregate beside the limit makes the real comparison visible.

See [Asserts](12-Asserts-Tab.md).

### Smart Y-Axis Units

Response time axes auto-adapt based on the maximum value:
- < 1,000 → milliseconds (ms)
- 1,000–60,000 → seconds (sec)
- 60,000–3,600,000 → minutes (min)
- \> 3,600,000 → hours (hr)

Tooltips show human-readable durations (e.g., "5m 50s" instead of "350000 ms").

## Response Time Heatmap

![Heatmap](images/08d-chart-heatmap.png)

A special visualization showing response time distribution over time:

- **X-axis:** Time
- **Y-axis:** Response time buckets (0-50ms, 50-100ms, 100-200ms, 200-500ms, 500-1K, 1K-2K, 2K-5K, 5K+)
- **Color:** Light blue (few samples) → Red (many samples)
- **Hover:** the cell's sample count, broken down by sampler when more than one contributed

The heatmap keeps its own 1-second columns: the Granularity selector does not apply to it.

Reveals patterns line charts miss:
- Bimodal distributions (cache hits vs DB queries)
- Response time drift over time
- Outlier bursts

## Metric Toggle Impact

![Metric Toggle](images/06d-metric-toggle.png)

Changing the **Metric** toggle in the filter bar (Mean/P50/P90/P95/P99/Max) affects:
- Chart 1 (Response Time Over Time) — switches the metric displayed, and which rules draw their
  lines on it
- Summary tab chart — same metric applied
- Compare tab's Response Time Comparison — same metric applied

Other charts (Throughput, Error Rate, etc.) are unaffected.

## Granularity Impact

![Granularity](images/06e-granularity.png)

Changing **Granularity** in the filter bar (1s/5s/10s/30s/1min/5min/10min):
- The time-series line charts and the scatter plot re-render with one point per bucket: a
  sample-weighted average for response times, percentiles and error rates, the bucket's rate for
  throughput and bytes, the maximum for Max
- The heatmap, the percentile bars and the response-code bars keep their own resolution
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

When configured, a semi-transparent overlay labelled **Ramp-up** marks the ramp-up period on every time-axis chart (all but the heatmap and the percentile bars) — helping you visually separate the warm-up phase from steady-state data.

**Configure via CLI:**
```bash
-Jwebinsight.report.rampup=30000   # 30 seconds in milliseconds
```

**Default:** `0` (no ramp-up marking)

The overlay spans from the test start to `start + rampup` duration, drawn as a light-colored rectangle behind the chart data.
