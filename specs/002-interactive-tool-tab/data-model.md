# Data model: an organised, interactive k6 tab

## Threshold record (run data 1.1)

| Field | Since | Required | Meaning |
|---|---|---|---|
| `metric` | 1.0 | yes | the metric with its tags as k6 wrote it, e.g. `http_req_duration{name:Login}` |
| `expression` | 1.0 | yes | e.g. `p(95)<=600` |
| `passed` | 1.0 | yes | k6's result |
| `statistic` | 1.1 | no | the statistic the expression names: `p(95)`, `rate`, `count`, `max`, `value`... |
| `measuredValue` | 1.1 | no | k6's number for that statistic, from its summary |
| `metricType` | 1.1 | no | `trend`, `rate`, `counter`, `gauge` |
| `metricContains` | 1.1 | no | `time`, `data`, `default` |

A 1.0 record has none of the four. The tab then writes "not recorded" in the Measured column, never 0.

## Run record (run data 1.1)

| Field | Since | Required | Meaning |
|---|---|---|---|
| `trendStatistics` | 1.1 | no | k6's `options.summaryTrendStats`, e.g. `["avg","min","med","max","p(90)","p(95)","p(99)","count"]`. p(99) is offered when it is listed |

## ToolMeasures (engine, per request name and for every request)

Seven trends: blocked, connecting, TLS handshaking, sending, waiting, receiving, and duration (sending + waiting +
receiving per sample). Each trend has count, avg, min, med, max, p90, p95 and p99, in hundredths of a ms. Every
percentile is nearest-rank.

## `chartData.tool` (written by `ToolTab`, read by `WIR.toolTab`)

```
tool: {
  name, version,                         // "k6", "2.4.0"
  words: { ... },                        // the tool's column of page-words.json for this tab
  counters: [ {key, value} ],            // iterations, dropped iterations, VUs allocated
  thresholds: [ {metric, tags, request, expression, statistic, operator, limit,
                 measured, unit, passed, alwaysTrue} ],
  trendStatistics: [ ... ],              // the statistics the switch offers
  timings: [ {request, groupPath, parts: {blocked: {avg, min, med, max, p90, p95, p99}, ..., duration: {...}}} ],
  timingsEveryRequest: { parts: {...} },
  checks: [ {groupPath, name, passes, fails} ],
  groupDurations: [ {groupPath, avg, min, med, max, p90, p95} ]
}
```

`request` is the `name` tag, or null for a threshold tied to no single request. `operator` and `limit` are parsed
once in Java from the expression. The page's js-unit tests hold its own reading of an expression to the same results.

## Page state (`WIR.toolTab` through `WIR.table`, remembered per run id)

Remembered (FR-068): grouping · statistic · open groups · hidden rows · sort column and direction · hidden columns.
Not remembered, as the Asserts tab's filters never were: the chips and the search text (analysis finding A2,
corrected 2026-11-25).
