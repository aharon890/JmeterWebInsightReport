# Phase 3 — Chart-authored asserts & server-metric KPIs

**Status:** direction note (full plan comes with the Server Metrics integration).
**Companions:** [`Server Metrics Integration.dc.html`](../Server%20Metrics%20Integration.dc.html) (canvas, in this handoff directory), `Asserts Prototype.dc.html` → Timeline tab (interactive demo of the chart interactions), phase-1/2 plans.

---

## 1. Goal

Author and tune asserts where the evidence lives — on the charts — and extend the same gate to
backend metrics (CPU, consumer lag, heap, DB), so one verdict covers client and server.

## 2. Chart interactions (started in phase 2, primary surface in phase 3)

- **Lines are rules.** Every rule targeting the charted metric draws its band; the old static
  threshold lines disappear into this.
- **Drag to retune** — ECharts `graphic` drag handle bound to the rule's band; live verdict while
  dragging; release = draft edit (same unsaved/save model as the editor).
- **Place to create** — "+ Assert from chart" → click a level → editor opens seeded with
  target/metric/value from the chart context (series clicked ⇒ that sampler; overall chart ⇒
  `scope: overall`).
- Phase-2 semantics: chart-authored rules gate the **whole-run aggregate**; the chart shows the
  line over the series precisely so the aggregate-vs-buckets distinction is visible, not hidden.

## 3. Schema extension — the time axis becomes assertable

Rules gain an optional `over` clause folding a time series into the scalar the predicates judge:

```json
{
  "id": "api-cpu-sustained",
  "target": { "source": "prometheus", "metric": "container_cpu_usage_seconds" },
  "fail": { "gt": 85 },
  "over": { "sustainedFor": "60s" }
}
```

- `over.sustainedFor: "60s"` — breach only when the condition holds continuously ≥ 60s (a 1-second
  CPU spike must not fail a build).
- `over.window: "30s", agg: "avg" | "max" | "p95"` — rolling-window aggregate.
- **Absent `over` = whole-run aggregate** — v2 rules are unchanged; full backward compatibility.
- Same predicates, same warn/fail bands, same onMissing/observe/enabled/tags, same outputs
  (`gate`, `assertions[]`, JUnit). Statuses gain **INCONCLUSIVE** (metric source silent ≠ PASS),
  per the Server Metrics design.
- Targets gain `source` (prometheus/datadog/elastic/derived) alongside sampler/match/scope. JMeter
  time-series rules (`"scope": "overall", "metric": "throughput", "over": {…}`) use the same
  clause — catching the "aggregate passes but RPS dipped for 90s" case the Timeline demo shows.

## 4. Prior art — Taurus passfail

Taurus (bzt) validates the model: its criteria are subject (avg-rt, p90, failures, hits) +
label + condition (`>` `<`) + threshold + optional timeframe, e.g. `avg-rt of IndexPage>150ms
for 10s, stop as failed`. Mapping:

| Taurus | Ours |
|---|---|
| `for 10s` — condition sustained over timeframe | `over: {"sustainedFor": "10s"}` |
| `within` / `over` — sum/avg across the window | `over: {"window": …, "agg": "avg"\|"max"\|"p95"}` |
| no timeframe | no `over` — whole-run aggregate (same default) |
| `MonitoringCriteria` (server/cpu > 95 for 20s) | phase-3 `source` targets |
| exact `label` only | glob/regex `match` + `exclude` (ours is wider) |
| binary fail, `stop`/`continue` action | warn+fail bands, observe mode (ours is wider) |

**Open decision (phase 3+): act on breach mid-run.** Taurus evaluates at runtime and can
auto-stop a failing test. We evaluate post-run — but `LiveStatisticsEngine` already streams
aggregates during the run, so "on sustained breach: stop the test / inject a timeline marker"
is feasible as an opt-in (`"onBreach": "stop"`). Default out: a report generator must never
kill a run by surprise.

## 5. Why this order

Phase 1 fixes the rule model; phase 2 builds see-tune-save on aggregates; phase 3 reuses all of
it — the `over` clause is additive, the chart interactions are already proven on client metrics,
and the server-metrics collector (separate CI step, aligned timeline) plugs its series into the
same evaluator, gate and GUI.
