# E27 — the Server tab's catalogue fixture

Committed inputs, built like E26 (`test-plans/server-metrics/README.md` explains why a server
fixture has to be): `jmeter -g test-plans/goldens/plain.jtl -o test-results/E27` with the
annotations and payload here.

| File | What it is |
|---|---|
| `e27-server.json` | the annotations: two **catalogue** components — `orders-db` (`postgres`) and `events` (`kafka`) — and one server assert each |
| `e27-metrics.csv` | the server data, one row per point: `time,component,metric,instance,value`. Every series has 49 points at 1 s, 22:13:20 to 22:14:08 UTC on 2023-11-14, which is `plain.jtl`'s window, and each series is one block of rows in time order |
| `server-metrics.json` | the payload, collected by `wir-collect --csv` from the CSV |
| `e27-grafana.csv` | C39's input, not E27's: three of the CSV's series in the shape of Grafana's **wide** download (Series joined by time, Formatted data off) — a quoted `"Time"` column of epoch milliseconds, then `orders-db/connections.used.pct/pg-1` and `events/consumer.lag.max` named by the header convention, `orders-db:5432 deadlocks` named by the map below, and `orders-db:5432 pg_stat_activity_count`, a raw exporter metric nothing maps. The lag cell at 22:13:30 is empty. Committed LF, as every text file is; C39 feeds a CRLF copy, as Grafana writes it |
| `e27-grafana-columns.csv` | C39's `--columns` map: `orders-db:5432 deadlocks` is `orders-db`/`deadlocks`/`pg-1`, and `Replication lag` names a column the export does not have |
| `e27-influx2.csv` | C40's input, not E27's: two of the CSV's `orders-db` series on `pg-1` in the shape of InfluxDB 2's **annotated CSV** (`influx query --raw`) — `#group`/`#datatype`/`#default` lines, a leading empty column, `_time`/`_value`/`_field` and a `host` tag, no component column. `connections.used.pct` is one block, under a header with InfluxDB's `_start`/`_stop` range columns; `deadlocks` is a second block after a blank line, without them and with an extra `region` tag, so each column it reads is at another position. Committed LF, as every text file is; C40 feeds a CRLF copy, as InfluxDB writes it, and reads it with `--csv-columns time=_time,value=_value,metric=_field,instance=host --component orders-db` |
| `e27-datadog.csv` | C41's input, not E27's: four of the CSV's series in the long layout under **Datadog's** names, read with `--from datadog` against `e27-server.json` — `postgresql.percent_usage_connections` as a 0-1 fraction (E27's `connections.used.pct` on `pg-1` ÷ 100; the catalogue's scale 100 brings it back), `kafka.consumer_lag` (E27's `consumer.lag.max`, as it is), `postgresql.deadlocks` as lock/s (E27's one deadlock is 0.05/s at 22:13:50; scale 60 reads 3 a minute), and `up` under the catalogue's own name, because Datadog has no single metric for it (its entry is `null`) |

## What it covers

E26's components are all `custom`; this one is about the **catalogue**:

- **D1**: the collector fetches every catalogue metric of each component beside the asserted ones
  and keeps those with data. The CSV carries seven such metrics (`up`, `deadlocks`, `cache.hit.pct`,
  `transaction.longest.seconds` and `database.size.growth` for `orders-db`; `brokers` and
  `messages.in.rate` for `events`), so the payload holds nine series and the metric picker has this
  run's values for them.
- **A slope**: `database.size.growth` is the catalogue's one `slope` metric, compared in units per
  **minute**. Both instances grow steadily: `pg-1` from 5,000,000,000 B by 1,000,000 B/s, and `pg-2`
  from 2,000,000,000 B by 200,000 B/s. That is 60,000,000 and 12,000,000 B/min. The level is in the
  billions and the compared value in the tens of millions, so a surface that reads the level where
  it should read the slope is wrong by two orders of magnitude, and it shows.
- **An instance that went down**: `up` on `pg-1` is 0 for one point, at 22:13:44 (mid-window), and
  1 everywhere else. `pg-2` is 1 throughout. That one point is what the catalogue's `fail < 1`
  invariant on `up` is about, and a fold across the two instances must not hide it.
- Neither is asserted in `e27-server.json`. Both are collected beside the two asserts (D1), so
  adding them moved no verdict in the CI/CD JSON, only its `collectionId`.
- The picker's "Has data in this run" filter, sparklines and this run's numbers (each the number
  its verdict would compare: a peak, a low, a slope per minute); the thresholds step's
  suggested bands — a catalogue safe default where one exists (`deadlocks` fails above 0, and
  this run had one, so it reads FAIL), otherwise the run with headroom in the direction the metric
  gets worse — and NO DATA for a picked metric with none (`replication.lag.seconds`).
- Generate seeding a catalogue metric as its `metric`, never as its PromQL.

## Regenerating

Only needed when `e27-server.json` or `e27-metrics.csv` changes. With `JMETER_HOME` set to the
JMeter `build-and-deploy.sh` deployed this tree's JAR into:

```bash
JMETER_HOME=/path/to/the/deployed/apache-jmeter ./scripts/wir-collect.sh \
  --jtl         test-plans/goldens/plain.jtl \
  --annotations test-plans/server-catalogue/e27-server.json \
  --csv         test-plans/server-catalogue/e27-metrics.csv \
  --step        1s \
  --out         test-plans/server-catalogue/server-metrics.json \
  --no-wait
```

Never pass `--test-name` or `-Jwebinsight.report.title`: the title is hashed into `runId`, and the
payload is refused against any other.
