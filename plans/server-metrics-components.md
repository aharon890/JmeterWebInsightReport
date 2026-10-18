# Server metrics: components instead of sources and gates

Status: **proposal, not approved.** Nothing here is implemented. `feat/server-metrics-v1` is pushed
and **must not merge to `main`** until Aaron approves it explicitly — and this redesign lands first.

Written 2026-10-02, from Aaron's answers the same day.

## 1. Why

The branch ships three concepts where Aaron meant one:

| Today (3.1.22) | What it is | Aaron's model |
|---|---|---|
| `sources` | how `wir-collect` connects (Prometheus URL, auth, `file` recording) | plumbing — not something a report author declares per assert |
| `gates` | a named group of asserts with `failsBuild` | the **component** |
| `asserts[].query` | raw PromQL the author writes | picked from the **component type's catalogue** |

The report never queries anything; it reads `server-metrics.json`. So "where the data came from" is
the collector's business, and what the author declares is **what is being tested**: a Postgres, a
Kafka, later an Oracle or a Kafka on AWS. Each type has its own metrics, so the type decides what
can be asserted.

This was already the direction on 2026-08-21 (one server gate in v1, catalogue per backend in the
plan — `tmp/server-metrics-implementation-plan.md` §0.2 and §8). The branch built named gates and
free-form queries instead.

## 2. Decisions (Aaron, 2026-10-02)

| # | Decision |
|---|---|
| C1 | **Component replaces source + gate.** One concept: an id, a type, a description, `failsBuild`. |
| C2 | **Redesign before merge.** `feat/server-metrics-v1` does not reach `main` with `sources`/`gates`. Nothing to migrate: no release has shipped them. |
| C3 | **First types: `postgres` and `kafka`.** Oracle, Kafka-on-AWS (MSK) and the rest follow one at a time, each a catalogue entry. |
| C4 | **`failsBuild` lives on the component only.** No per-assert observe-only. PASS/WARN/FAIL on the assert is enough. |
| C5 | **Two instances of one type are rare** and must not cost every user a field. See §4. |
| C6 | **No `failsBuild` at all** (Q2). Every component builds; WARN is how you "just watch". Supersedes C4. Observe-only gates, their JUnit `<skipped>` path (D29) and G6 go. |
| C7 | **`serverGates[]` → `serverComponents[]`** in the CI/CD JSON (Q3). |
| C9 | **Keep the escape hatch** (Q1). An assert gives `metric` (catalogue) or `query` (custom), never both; the editor shows "Custom query…" as one option under the metric dropdown; a `custom` component type exists for things that are no known technology. See §6. |
| C8 | **No connection details in the annotations file** (Q4). The report reads JSON, not a live source; the URL and the token's environment variable are `wir-collect` flags only. |

## 3. The file

```json
"serverMetrics": {
  "components": {
    "orders-db": { "type": "postgres", "description": "Orders DB" },
    "events":    { "type": "kafka",    "description": "Event bus" }
  },
  "asserts": [
    { "component": "orders-db", "metric": "connections.used.pct", "fail": { "gt": 85 } },
    { "component": "events",    "metric": "consumer.lag.max",     "warn": { "gt": 640 }, "fail": { "gt": 800 } }
  ]
}
```

- **`components`** — the key is the id; `description` is the display name ("Oracle PROD"); `type`
  picks the catalogue. Every component builds (C6). It is what the header chip, the badges,
  `serverStatus`'s fold and the JUnit suites are about.
- **`asserts`** — `component` + `metric` (a catalogue name) + bands. The catalogue supplies the
  query, unit, `reduce` and `across`; the assert may still override `reduce` (e.g. `last` instead
  of `max`). The author never writes PromQL for a catalogued metric.
- **No connection block** (C8). `wir-collect --prometheus-url URL --prometheus-token-env VAR`
  says where to fetch from; `--recording DIR` replays saved responses instead. The annotations file
  describes what is tested, never where the numbers live.
- **`environments` / `--env` stay** — they are how PROD and DEV differ (`${env.*}` in selectors),
  which is the commonest "two of the same" case and needs no second component.

## 4. Two instances of one type (C5)

Aaron's suggestion: use the component id and description ("Oracle PROD", "Oracle DEV").

That covers **naming** — and it is what the proposal does: the id is the key, the description is
the label. What it cannot cover is **filtering**: the description is free text for people, and the
collector needs a Prometheus label value to tell two databases apart in one Prometheus. So:

- **PROD vs DEV** — not two components. One component, and `--env PROD` / `--env DEV` choose which
  database the queries reach (the `environments` block already does this).
- **Two databases in the same run** (orders-db and users-db, both Postgres, same Prometheus) — the
  rare case. One optional key on the component, **absent by default**:
  `"match": "instance=\"orders-db:5432\""`. Every catalogue query carries a `${match}` slot that
  expands to the selector, or to nothing when the key is absent. The editor shows it collapsed
  under "Advanced"; no column, no field in the common case.

## 5. The catalogue

A JSON resource per type inside the JAR, `servermetrics/catalogue/<type>.json`, versioned
(`postgres@1`). Each metric: `name`, `label`, `query` template (with `${match}` and `${env.*}`
slots), `unit`, `reduce`, `across`, band direction, and the exporter it assumes. Default bands only
where the number is an invariant (under-replicated partitions > 0, deadlocks > 0); everything else
is seeded from the run and dragged, as "Generate from sources" does today.

**A metric's query never changes inside a version.** Otherwise upgrading the JAR would move a
verdict on an unedited file (review §5.2). A fix is `postgres@2`. The payload already records each
`resolvedQuery`, so the report shows exactly what was asked.

### `postgres@1` (postgres_exporter) — from the 08-21 catalogue §8.4

| metric | measures | reduce | direction |
|---|---|---|---|
| `up` | exporter can reach the database | min | `lt 1` (invariant) |
| `connections.active` | `pg_stat_activity_count{state="active"}` | max | gt |
| `connections.used.pct` | backends / `max_connections` | max | gt |
| `locks.waiting` | waiting exclusive locks | max | gt |
| `deadlocks` | `increase(pg_stat_database_deadlocks[window])` | last | `gt 0` (invariant) |
| `cache.hit.ratio` | blks_hit / (hit + read) | min | lt |
| `replication.lag.seconds` | replica lag | max | gt |
| `transaction.longest.seconds` | longest open transaction | max | gt |

### `kafka@1` (kafka_exporter for consumer metrics, JMX exporter for broker metrics; Strimzi ships both) — from §8.1

| metric | measures | reduce | direction |
|---|---|---|---|
| `consumer.lag.max` | worst partition lag of a consumer group | max, sustained 30s | gt |
| `consumer.lag.final` | lag left at the end of the run | last | gt |
| `partitions.under_replicated` | under-replicated partitions | max | `gt 0` (invariant) |
| `partitions.offline` | offline partitions | max | `gt 0` (invariant) |
| `controller.active` | active controller count | min | `lt 1` (invariant) |
| `messages.in.rate` | messages in per second | max | — (seeded) |

Kafka's broker metric **names depend on the JMX exporter's rules file**; the catalogue assumes the
Strimzi/standard rules and says so. A cluster with other rules needs the escape hatch (§6) or a
`kafka-<variant>` type later — the same mechanism that will carry `kafka-msk`.

Consumer group and topic come from `${env.*}` or `match`, not from new keys.

## 5a. Keeping the catalogue current (Aaron's question, 2026-10-03)

1. **Provenance per type.** Each catalogue file names the published list it was written from and
   that list's version (`"source": "AWS MSK metrics-details, Kafka 3.6+"`,
   `"source": "postgres_exporter v0.x"`).
2. **Drift check, not auto-update.** A script per type fetches the upstream list (docs table,
   exporter definitions, or a `/metrics` scrape of a reference instance), diffs it against the
   catalogue and reports added / renamed / removed. Run before a release, or a monthly GitHub job
   that opens an issue. It never writes the catalogue: every new metric needs a human-written
   category, description, direction and recommended flag.
3. **Versioning.** Adding metrics to `kafka@1` is compatible. A rename or removal is `kafka@2`; files
   written against `@1` keep resolving against `@1` (MSK renamed metrics in Kafka 3.6.0 — a real case).
4. **Discovery in the picker.** `wir-collect` lists the metric names that actually exist for a
   component (Prometheus series names, InfluxDB fields). The picker shows **"Found in your data, not
   in the catalogue (N)"**; choosing one adds a prefilled custom query (C9). A stale catalogue
   therefore never blocks a user — it only lacks curation until the drift check catches up.
5. **Organisation catalogues.** A user-supplied catalogue file extends or overrides the built-in one
   for in-house metrics (§5, "both").

## 6. Metrics the catalogue does not have — the escape hatch (decided: keep, C9)

What this is: the catalogue will never hold everything — an application's own gauges (queue depth,
cache size, business counters), or a Postgres metric we did not list. **Catalogue only** means such
a metric cannot be asserted at all until a JAR ships it. **An escape hatch** means an assert may
give its own `"query"` instead of `"metric"`, as every assert does today:

```json
{ "component": "orders-db", "query": "pg_stat_user_tables_n_dead_tup{relname=\"orders\"}",
  "unit": "rows", "reduce": { "kind": "max" }, "fail": { "gt": 100000 } }
```

plus a `"type": "custom"` component for things that are not a known type at all (the app itself).
**Recommend: keep it.** It is the code that exists today, so it costs nothing, and without it the
first unlisted metric blocks a user until a release.

## 7. What changes, what stays

**Stays:** reducers and bands, `across`, coverage, `requiresLoad`/`divideBy`, the payload's
`runId`/`collectionId` and the loader's refusals, the exit policy (`failonerror`, `failonassert`),
the `serverStatus` fold (now over every component), the charts, drafts and `WIR.document`.
**Removed** by C6: `failsBuild`, observe-only, JUnit `<skipped>` for server suites, the
`failsBuild` clause of the STRICT jq line, golden G6.

**Changes:**

| Area | Change |
|---|---|
| core model | `ServerGate` → `ServerComponent` (+ `type`, `match`); `ServerSource` collapses to one connection; `ServerKpi` gains `component` + `metric`, `query` optional |
| catalogue | new: `core/servermetrics/catalogue/`, loader, two JSON resources, a `CatalogueTest` that every query template expands |
| mapper / usability | `ServerMetricsMapper` resolves `type`+`metric` → query at load; `KpiUsability` refuses an unknown type, unknown metric, `metric` and `query` both or neither; retired keys `sources`, `gates`, `assert.source`, `assert.gate` refused naming the replacement (as `kpis` is today) |
| collector | `wir-collect --prometheus-url`, `--prometheus-token-env`; `--recording DIR` replaces the `file` source (the suites' deterministic input) |
| outputs | CI/CD JSON `serverGates[]` → `serverComponents[]` (+ `type`); JUnit suite `<testName> - <componentId>`. **Open — Q3** |
| Server tab | badges per component; editor: pick component → metric dropdown from its catalogue → bands; "+ Add component" (type picker); Generate seeds from a type's catalogue |
| parity | server vectors rewritten for components; JS mirror reads the catalogue (shipped in the page payload, not a second copy) |
| fixtures | E26 annotations + recordings, goldens G4–G8 re-accepted, jq-gate lines if the JSON key moves |
| docs | Tutorial 13 rewritten around components; CLAUDE.md "Server asserts" section |

## 7a. Implementation notes (as being built, 2026-10-03)

**1a — done (`15a8aaf`).** Catalogue in core (`core/servermetrics/catalogue/`, resources under
`com/jmeterwebinsightreport/…` for the shaded-JAR allowlist), `postgres@1` and `kafka@1`;
`ServerGate` gains `type`/`match` (a component IS a gate internally, as `ServerKpi` kept its name);
`ServerKpi` gains `metric`. Additive; nothing reads them yet.

**1b — the format switch.** One rule decides most of the design: **the KPI's query string stays
the identity of its series, whatever the backend.** For a catalogue metric the mapper resolves it
from the catalogue's Prometheus template with the component's match expanded; for a custom assert
it is what was written. The collector keys every series by that string (a CSV backend will look its
rows up by component + metric and still write the series under it), so the payload, the evaluator,
the page and Validate query need no backend awareness.

- **Mapper.** `components` → `ServerGate.component(...)` (always building). Every assert gets
  `source` = the one collection source id (`ServerSource.COLLECTION_ID`) and `gate` = its
  component. `unit`, `reduce` and `across` default from the catalogue; a value written on the
  assert overrides.
- **Refused (INVALID_RULE on the assert):** no `component`; an undeclared component; a component
  of unknown type; `metric` and `query` both or neither; `metric` on a `custom` component; a
  metric the type does not have (naming the ones it does). A custom assert still needs `reduce`.
- **Refused (config-level):** a component with an unknown `type`; retired block keys `sources`
  (connection details are `wir-collect` flags, C8) and `gates` (now `components`); retired assert
  keys `source` and `gate`; `failsBuild` on a component (C6). `sources` keeps its typed binding in
  `ServerMetricsJson` only so `publishable()` still masks a credential left in a retired block.
- **Collector.** The source comes from flags, not the file: `--prometheus-url URL
  [--prometheus-token-env VAR]` or `--recording DIR`, with `--step` (default 15s). It is injected
  as the config's one source under `COLLECTION_ID`. CSV (`--csv`) is step 2.
- **Page.** The catalogue ships in the page payload; the mirror parses the same format and refuses
  in the same words (vectors first). The editor gets a Component select and a Metric select with
  "Custom query…"; the full picker is N4.
- **Fixtures.** E26 and goldens G4–G8 rewritten as components; G6 (observe-only) goes with C6;
  goldens re-accepted in the same commit.

## 8. Milestones

| | Scope | Gate |
|---|---|---|
| N1 | Model + catalogue + mapper + usability (Java only); vectors first | `mvn test`, `ServerParityTest` |
| N2 | Collector: one connection, `--recording`, `match`/`${env.*}` expansion | C35–C37 updated, `wir-collect` unit tests |
| N3 | Outputs + goldens + jq lines | R06 re-accepted and committed with the change |
| N4 | Server tab: components, catalogue-driven editor, Generate | E26 regenerated, server specs, `asserts-parity`/`server-parity` specs |
| N5 | Docs + screenshots | link check, jq verbatim check |
| N6 | Full regression + config once, then Aaron's go for push, then his go for merge | — |

## 9. Open questions for Aaron

- ~~Q1~~ keep the escape hatch → C9. ~~Q2~~ drop `failsBuild` → C6. ~~Q3~~ rename → C7. ~~Q4~~ flags only → C8. (Aaron, 2026-10-02)
