# Compare Tab

Baseline vs current run comparison with regression detection.

## Setup

**Auto-detection:** Place a previous run's JTL file as `baseline.jtl` in the report output directory. The plugin auto-detects it and enables the Compare tab.

```bash
# Example: copy previous results as baseline
cp previous-results.jtl /output/dir/baseline.jtl

# Run new test — Compare tab will appear in the report
jmeter -n -t test.jmx -Jwebinsight.report.output=/output/dir
```

**Explicit path via CLI:** Use `-Jwebinsight.report.baseline` to point to a specific file:

```bash
jmeter -n -t test.jmx \
  -Jwebinsight.report.baseline=/path/to/previous-results.jtl
```

This is useful when the baseline JTL is stored in a different location (e.g., a CI artifact from the previous build), and it is the way to give `jmeter -g results.jtl -o dir` a baseline, since `-o` takes an empty directory.

When a baseline is loaded, the **Compare** tab button becomes visible in the tab navigation (it is hidden by default when no baseline exists).

![Compare Overview](images/18-compare-overview.png)

## Comparison Charts

### Response Time Comparison

- **Current run:** solid lines per sampler
- **Baseline run:** dashed lines per sampler
- Both aligned by elapsed time (starting from 0)
- Respects the **Metric toggle** (Mean/P50/P90/P95/P99/Max), the Σ Total line included
- Respects the **Sampler filter** (deselected samplers hidden)

### Throughput Comparison

- Same current (solid) vs baseline (dashed) layout for throughput
- Per-sampler series

With **Σ Total** checked in the filter bar, both charts add a thicker total line: solid for the
current run, dashed for the baseline.

Both charts draw the runs' 1-second data: the filter bar's **Granularity** setting does not apply
to them.

Both charts support: drag-to-reorder, fullscreen, hide/show, zoom, cross-chart sync, dark mode, PNG/SVG export.

## Sampler Comparison Table

![Comparison Table](images/18c-compare-table.png)

| Column | Description |
|--------|-------------|
| **Sampler** | Request label |
| **Status** | Badge: OK, REGRESSION, NEW or REMOVED |
| **Samples** | Current count with delta (superscript) |
| **Errors** | Current count with delta |
| **Error %** | Current rate with delta |
| **Mean (ms)** | Current value with delta |
| **Median (ms)** | Current value with delta |
| **P90 (ms)** | Current value with delta |
| **P95 (ms)** | Current value with delta |
| **P99 (ms)** | Current value with delta |
| **Min (ms)** | Current value with delta |
| **Max (ms)** | Current value with delta |
| **Throughput** | Current value with delta |
| **Recv KB/s** | Current value with delta |

### Delta Values

Each metric shows a superscript delta — the current value minus the baseline's, in the column's
own unit:
- **Green** (or blue in colorblind mode) — improvement: lower on Errors, Error % and every time
  column (Mean to Max, Min included), higher on Throughput and Recv KB/s
- **Red** (or orange in colorblind mode) — regression: the opposite direction on those columns
- **Neutral** (no colour) — Samples: more samples means a longer or busier run, not a better or
  worse one
- Format: "+120" or "-1.2s" on a time column, "+0.5%" on Error % (percentage points), "-3.4/s" on
  Throughput. No delta is shown only when the change is exactly zero; a change smaller than the
  column's precision still shows, as "+0" or "+0.0/s"

### Status Badges

The badges are outlined, so they read as a comparison rather than an assert verdict.

| Badge | Color | Condition |
|-------|-------|-----------|
| **OK** | Green | No regression detected |
| **REGRESSION** | Red | Any enabled threshold crossed — by default P95 increased > threshold OR error rate increased > threshold |
| **NEW** | Blue | Sampler exists in current but not in baseline |
| **REMOVED** | Red, dimmed | Sampler ran in the baseline but not in this run; its row shows dashes. Counts as a regression, whatever the thresholds |

### Table Controls

Same as Samplers tab: sortable columns, drag-to-reorder, column resize, column toggle, row hide, search filter, CSV download (`comparison.csv`, with each delta in a column of its own).

## Regression Thresholds

Thresholds that determine what constitutes a "regression":

> **Informational only.** A REGRESSION badge decides nothing outside the page: the comparison is
> not in the CI/CD JSON or the JUnit XML, and it never changes the exit code. To fail a build on
> a slower run, declare a rule on the [Asserts tab](12-Asserts-Tab.md).

| Metric | Default | Meaning |
|--------|---------|---------|
| **P95 % change** | 10% | P95 increase > 10% → REGRESSION |
| **Error rate change** | 2% | Error rate increase > 2pp → REGRESSION |
| **Mean % change** | Disabled (-1) | Not checked by default |
| **P99 % change** | Disabled (-1) | Not checked by default |
| **Throughput % drop** | Disabled (-1) | Not checked by default; when set, a throughput *decrease* past it is a regression |

### Configuration

**Via `report-annotations.json`:**
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

**Via JMeter CLI properties:**

| Property | Default |
|----------|---------|
| `webinsight.compare.p95.threshold` | `10` (%) |
| `webinsight.compare.errorrate.threshold` | `2` (percentage points) |
| `webinsight.compare.mean.threshold` | `-1` (disabled) |
| `webinsight.compare.p99.threshold` | `-1` (disabled) |
| `webinsight.compare.throughput.threshold` | `-1` (disabled) |

```bash
-Jwebinsight.compare.p95.threshold=15
-Jwebinsight.compare.errorrate.threshold=3
-Jwebinsight.compare.mean.threshold=20
-Jwebinsight.compare.p99.threshold=25
-Jwebinsight.compare.throughput.threshold=10
```

Set any threshold to **-1** to disable that check. CLI properties override annotation values.

### Live Threshold Controls

![Threshold Controls](images/18d-threshold-controls.png)

The Compare tab includes UI controls to adjust thresholds on-the-fly:
- Input fields for each threshold: *P95 increase >*, *Error rate increase >*, *Mean increase >*,
  *P99 increase >*, *Throughput decrease >* (`-1` = disabled)
- **"Apply"** button to re-evaluate regression flags client-side, by the same rules the report
  was generated with: a removed sampler stays a regression, and a metric whose baseline was 0
  counts any increase as +100%
- Useful for exploring what thresholds make sense for your application
- Changes are local (do not modify the annotations file)

## What Changed summary

Auto-generated text in the **Notes tab** when baseline comparison exists:
- Overall line: the average P95 change and error-rate change across samplers (improved, stable or
  degraded)
- Regressed samplers with their P95 % change (the top three, then "…and N more"); a removed
  sampler is listed as "(removed)"
- Improved samplers — P95 down by more than 5 % — likewise
- New samplers not in baseline

Rebuilt each time you press **Apply** on the Compare tab's threshold controls.
