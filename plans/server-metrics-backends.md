# Server metrics: where `wir-collect` fetches from — Prometheus, InfluxDB 3, CSV file

Status: **proposal, not approved.** Written 2026-10-03 from Aaron's request the same day.
Companion to `plans/server-metrics-components.md` (C1–C9, what is declared) and
`plans/server-component-types.md` (which types). This one is **how the numbers are fetched**.

## 1. The principle: many backends in, one file out

```
                 ┌─ Prometheus adapter ──┐
 annotations ──► │  InfluxDB 3 adapter   │ ──► server-metrics.json ──► report (jmeter -g -o)
 (components,    │  CSV file adapter     │     one normalised shape:
  metrics)       └───────────────────────┘     series → instances → (time, value) points
                        wir-collect
```

- The **report never changes per backend.** It reads `server-metrics.json`, whose shape already is
  backend-neutral: per assert, the resolved query, its instances, and each instance's points. Every
  adapter's only job is to produce that shape for the run's window.
- Everything after the adapter is shared and already exists: the window clip, coverage, the point
  cap, `runId`/`collectionId`, redaction, the payload writer.
- Aaron's case: server metrics land in **InfluxDB** (beside JMeter's backend-listener data), so
  InfluxDB is the store `wir-collect` reads them from. The client side still comes from the JTL —
  the backend listener's JMeter measurements in InfluxDB are not read.

## 2. Backends in v1

| Backend | Status | Talks to | Query language |
|---|---|---|---|
| `prometheus` | exists | `/api/v1/query_range` | PromQL |
| `influxdb` | **new** | InfluxDB **3** (Core / Enterprise) `/api/v3/query_sql` | SQL |
| `csv` | **new** | a file on disk | none — rows are already the data |

Older InfluxDB (1.x, 2.x/Flux) is out, as Aaron asked. The existing Prometheus *recording* replay
(today's `file` source) stays only as a test fixture of the Prometheus adapter; the user-facing
"from file" is the CSV backend.

## 3. The catalogue becomes type × backend

A catalogue metric is one *meaning* with one query per backend. The meaning (label, unit, reduce,
direction, gauge or counter) is shared; only the fetch differs:

```json
"connections.used.pct": {
  "label": "Connections used (%)", "unit": "%", "kind": "gauge",
  "reduce": "max", "across": "max", "direction": "gt",
  "prometheus": "100 * sum by (instance) (pg_stat_activity_count${match}) / max by (instance) (pg_settings_max_connections${match})",
  "influxdb":   { "table": "postgresql", "value": "numbackends", "instance": "server" }
}
```

**What the InfluxDB side assumes.** Server metrics reach InfluxDB through **Telegraf**, InfluxData's
own agent, whose input plugins write fixed tables and fields (`inputs.postgresql` writes table
`postgresql` with `numbackends`, `deadlocks`, `blks_hit`, `blks_read`…). That is the "published
API" for the InfluxDB side of each type, the way `postgres_exporter` is for Prometheus.

There is a second common way, and it matters which one Aaron's systems use (Q2): Telegraf's
`inputs.prometheus` **scraping the same exporters** and writing their metric names into InfluxDB.
Then the InfluxDB queries reuse the Prometheus metric names and the catalogue needs one query
generator, not two hand-written lists.

A metric a backend cannot answer (e.g. `max_connections` is not in `inputs.postgresql`) is simply
absent for that backend: the editor does not offer it, and a file declaring it gets a clear refusal.

## 4. InfluxDB 3 adapter

**Connection** (C8 — flags only, never in the annotations file):

```
wir-collect --backend influxdb --influx-url https://influx.example:8181 \
            --influx-db servers --influx-token-env INFLUX_TOKEN  ...
```

**Request.** `POST /api/v3/query_sql`, header `Authorization: Bearer <token>`, body
`{"db": "...", "q": "...", "format": "jsonl", "params": {...}}`. JSONL streams, so a large window is
not held in memory twice. The window bounds go in as `params`, never pasted into the SQL text.

**The query a catalogue entry generates** (one series per instance, bucketed to the step):

```sql
SELECT date_bin(INTERVAL '15 seconds', time) AS time,
       server                                AS instance,
       max(numbackends)                      AS value
FROM postgresql
WHERE time >= $start AND time <= $end
GROUP BY 1, 2
ORDER BY 2, 1
```

**Contract for every InfluxDB query**, catalogue or custom: it must return a `time` column, a
numeric `value` column, and optionally `instance`. Anything else is refused with a sentence naming
the missing column — the same rule the escape hatch (C9) uses, so a custom SQL query is checked
exactly like a catalogue one.

**Counters.** PromQL computes rates (`rate(...)`) inside Prometheus; SQL does not do that the same
way. So the catalogue marks each metric `gauge` or `counter`, and **for InfluxDB and CSV the
collector turns a counter into a per-second rate itself** — per instance, difference over time,
counter resets handled. One definition of "rate" for every non-Prometheus backend, testable without
a database.

**Out of v1** (Q4): InfluxDB Cloud Serverless/Dedicated, which do not offer `/api/v3/query_sql` —
they need the InfluxQL `/query` endpoint or Flight SQL.

**Testing.** Like Prometheus today: recorded JSONL responses replayed in unit tests and goldens, no
live database in CI. A Docker-tier scenario against the official `influxdb3-core` image can come
later beside E16/E17.

## 5. CSV file backend — the format

For anyone whose metrics are in some other tool: export them, shape them as below, collect.

**Why CSV, and why "long".** Every tool can produce CSV, people can read and fix it, and it diffs
well in git. The *long* shape — one row per point — is chosen over the *wide* one (one column per
series, what Grafana's "Download CSV" gives) because it carries any number of components, metrics
and instances in one file without header conventions. A Grafana-wide converter can come later.

```csv
time,component,metric,instance,value
2026-10-02T14:02:15+03:00,orders-db,connections.used.pct,pg-1,41.5
2026-10-02T14:02:30+03:00,orders-db,connections.used.pct,pg-1,44.0
2026-10-02T14:02:15+03:00,events,consumer.lag.max,orders-0,120
2026-10-02T14:02:15+03:00,checkout-app,queue-depth,,17
```

| Column | Required | Rule |
|---|---|---|
| `time` | yes | ISO 8601 **with** an offset or `Z`, or epoch milliseconds. A time with no zone is refused — a silent zone guess shifts the whole window |
| `component` | yes | a declared component id |
| `metric` | yes | a catalogue metric of that component's type, **or** the assert id of a custom assert |
| `instance` | no | empty means one instance |
| `value` | yes | a number, `.` as decimal point, no thousands separator. Empty or `NaN` is a gap, not zero |

- UTF-8, comma-separated, header row required, column order free, extra columns ignored.
- The **raw values** go in the file; the report still reduces them (max, p95, slope…), so the CSV
  backend is judged exactly like a fetched one. A counter row is the raw counter; the collector
  converts it (§4).
- Rows outside the run's window are dropped, and coverage is computed from what is left — as for
  every backend.
- Errors name the line: `line 214: time "2026-10-02 14:02" has no time zone`.

```
wir-collect --backend csv --csv server-metrics.csv  ...
```

The suites' fixtures (E26, the goldens) could move to CSV: one readable file instead of a folder of
recorded Prometheus responses. Recommend doing that — it makes the test inputs reviewable.

## 6. Which backend serves which component

| Option | How | For |
|---|---|---|
| **A** | one backend per collection: `--backend influxdb` | Aaron's case — everything is in InfluxDB. Simplest |
| **B** | A plus overrides: `--backend influxdb --backend-for events=prometheus` | a mixed estate (Kafka in Prometheus, DB in InfluxDB) |

Recommend **B**: it is A with one optional flag, and needs no new file. Either way it stays out of
the annotations file (C8).

## 7. Rules shared by every adapter (so they cannot drift)

1. Window: the JTL's first to last sample, minus `--rampup`; the adapter asks for that window and
   the collector clips again.
2. Instances: identified by the `instance` value (Prometheus: the series labels; InfluxDB: the tag
   the catalogue names; CSV: the column).
3. Points: sorted by time; duplicate timestamps for one instance keep the last; NaN is a gap.
4. Counters → per-second rate in the collector (InfluxDB, CSV), reset-aware.
5. Coverage, point cap and thinning: unchanged, after the adapter.
6. The resolved query is recorded per series — PromQL, the SQL, or `csv:<file>` — so the report's
   expanded row says where every number came from.

## 8. Work

| | Scope | Gate |
|---|---|---|
| B1 | `SeriesSource` contract written down; shared normalisation (§7) pulled out of `PrometheusSource`; counter→rate | unit tests, Prometheus goldens unmoved |
| B2 | CSV backend + format errors; E26 and goldens inputs converted to CSV | R06 re-accepted, C35–C37 |
| B3 | InfluxDB 3 adapter + recorded-JSONL tests | unit tests |
| B4 | Catalogue gains the `influxdb` column for postgres and kafka (Telegraf) | catalogue test: every template expands |
| B5 | `--backend`, `--backend-for`, `--influx-*`, `--csv`; Tutorial 13 | C-scenarios, docs |

B1–B5 sit inside the components redesign (N2 in `server-metrics-components.md`), not after it.

## 8a. Decisions (Aaron, 2026-10-03)

- **CSV file first.** Aaron's InfluxDB pipeline is still being built, so the CSV backend is the
  first new one, ahead of InfluxDB. Order of work: B1 (shared normalisation), **B2 (CSV)**, B4 for
  the CSV/catalogue side, B5 flags. **B3 (InfluxDB 3) waits for Q2** — the InfluxDB column of the
  catalogue cannot be written until it is known how the metrics reach InfluxDB.
- **Q1, left to Claude: option A for now** — one backend per collection (`--backend csv`). With
  CSV first and Prometheus the only live backend, a mixed estate is not yet a real case; the
  `--backend-for` override (option B) is added with the second live backend, and nothing in A
  stands in its way.
- **Q3: yes by default** — the E26 and golden inputs move to CSV in B2, which is what makes the
  CSV backend tested from day one.

## 8b. Step 2 implementation notes (Claude, 2026-10-03, after Aaron's go)

Choices made while building B1/B2, each a narrowing of §4–§7 rather than a new direction:

- **Source status PARTIAL** (Aaron, 2026-10-03): with one source per collection, the first failed
  query no longer marks the source FAILED. FAILED means nothing came back (unusable source, a CSV
  whose header cannot be read, every series failed); PARTIAL means some series failed and others
  did not, with "N of M series failed" and the first error.
- **A series is identified by component and query.** Two components of one type with no `match`
  resolve to the same catalogue query; in Prometheus that is redundant, in a CSV the `component`
  column is the only thing telling them apart. The payload's series carry `component`, the
  collector dedups by component and query, and both evaluators match on component when the series
  carries one — a payload without it (every one collected before this) still matches as before.
- **A CSV value is the metric's value, in the catalogue's unit.** §4/§5 had the CSV carry raw
  counters for the collector to rate. Several catalogue metrics are ratios of two counters
  (`rollback.ratio.pct`, `cache.hit.pct`), which one value per row cannot carry, so the CSV holds
  what the metric means — the %, the per-second rate — and nothing is converted. Counter→rate
  moves to B3 (InfluxDB), where the rows really are raw.
- **A custom assert's CSV rows are named by its `query`**, not its id (§5 said id). The query is
  the series' identity on every backend, an id may be derived, and two asserts on one series must
  read one set of rows. For a CSV-backed custom component the query is simply the name used in
  the `metric` column, e.g. `"query": "queue-depth"`.
- **Format errors fail the series the row belongs to**, named by line: a row whose `component` and
  `metric` name a declared series but whose time or value cannot be read makes that series FAILED
  (`line 214: time "2026-10-02 14:02" has no time zone`), so the source reads PARTIAL. A missing
  required column fails the whole file. Rows naming nothing declared are ignored: an export may
  carry more than the file asks for.
- **`--recording` (Prometheus replay) stays**, user-facing as well as a fixture of the Prometheus
  adapter; C35 keeps it. E26, the goldens and C36 move to `--csv`.

## 9. Questions for Aaron

- ~~Q1~~ A for now → §8a. ~~Q3~~ yes → §8a.
- **Q2 — still open, not blocking:** how do server metrics reach your InfluxDB? Telegraf input plugins (`inputs.postgresql`,
  `inputs.jolokia2` for Kafka…), Telegraf scraping Prometheus exporters, or something else? This
  decides the InfluxDB column of the catalogue.
- **Q3 — CSV for the test fixtures too** (§5)?
- **Q4 — InfluxDB Cloud** (Serverless/Dedicated) needed in v1, or Core/Enterprise only?

## Sources

- InfluxDB 3 Core, query with the HTTP API (`/api/v3/query_sql`, `db`, `q`, `params`, `format`
  json|jsonl|csv|pretty|parquet, Bearer token):
  https://docs.influxdata.com/influxdb3/core/query-data/execute-queries/influxdb-v3-api/
- InfluxDB 3 query interfaces across editions (v1 `/query` InfluxQL, Flight SQL; Cloud Serverless
  has no `/api/v2/query`): https://docs.influxdata.com/influxdb3/cloud-serverless/query-data/execute-queries/v1-http/
