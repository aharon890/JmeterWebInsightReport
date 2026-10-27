# Server Data Reference

The collector, `wir-collect`, for Prometheus and recorded responses, and every key of the
`serverMetrics` block and the payload. For a CSV, [Getting Server Data In](14-Server-Data.md) is all
most runs need; the [Server tab](13-Server-Tab.md) explains what the report shows.

## wir-collect

Step 2. It reads the JTL for the window and the run's identity, reads the `serverMetrics` block,
fetches every distinct component and query that an enabled server assert names from the one source
its flags give, and writes one payload. Beside those it fetches every catalogue metric of each
component of a catalogue type and keeps the ones that returned data, which is what the
[metric picker](13-Server-Tab.md#adding-metrics-the-picker) shows. That works before the first server assert: a
file that declares catalogue components and no assert yet is collected too.

```text
usage: wir-collect --jtl <results.jtl> --annotations <report-annotations.json> --out <server-metrics.json> (--prometheus-url URL [--prometheus-token-env NAME] | --recording DIR | --csv FILE [--from TOOL] [--layout wide [--columns MAP]] [--csv-columns SPEC] [--component ID] [--time-zone ZONE]) [--step 15s] [--clock-offset 0s] [--env NAME] [--test-name NAME] [--rampup MILLIS] [--max-wait 10m] [--no-wait]
```

| Option | |
|---|---|
| `--jtl FILE` | The finished run. Its first and last sample are the window |
| `--annotations FILE` | The file that declares `serverMetrics`: components and server asserts |
| `--out FILE` | Where the payload is written, e.g. `server-metrics.json` |
| `--prometheus-url URL` | A Prometheus HTTP API, e.g. `https://prometheus.example/api/v1` |
| `--prometheus-token-env NAME` | With `--prometheus-url`: the environment variable holding a bearer token |
| `--recording DIR` | Recorded Prometheus responses, replayed instead of a live server |
| `--csv FILE` | A CSV export, one row per point: `time,component,metric,instance,value` |
| `--from TOOL` | With `--csv`: the file was exported from `telegraf`, `datadog`, `otel` or `cloudwatch`. Its `metric` column holds that tool's names, and a value read under one is converted to the catalogue's unit. See [Where the metrics come from](#where-the-metrics-come-from) |
| `--layout long\|wide` | With `--csv`: `long` (the default) is one row per point; `wide` is a time column and one column per series, the shape of Grafana's CSV download. See [From Grafana](#from-grafana-the-wide-layout) |
| `--columns MAP` | With `--layout wide`: a CSV of `column,component,metric,instance` naming each export column by its exact header |
| (no `--layout`) | A header whose first column is `Time` and whose every other column is `component/metric` or `component/metric/instance` is read as wide, and the console says `layout wide, read from the header`; `--layout long` reads it otherwise. Any other header is long |
| `--csv-columns SPEC` | With a long `--csv`: the file's names for the columns, as `key=column` pairs — `time`, `component`, `metric`, `instance`, `value`; a key not given keeps its own name. `metric=_measurement+_field` joins two columns as `<measurement>.<field>`. See [From InfluxDB 2](#from-influxdb-2-csv-columns-and-a-fixed-component) |
| `--component ID` | With a long `--csv`: every row belongs to component `ID`, for an export with no component column whose asserts are all on `ID` — an assert on any other component finds no rows and reads `UNMATCHED`. Refused beside `--csv-columns component=` (exit 1). Beside a component column of the file's own, the file is refused and every series fails ([below](#from-influxdb-2-csv-columns-and-a-fixed-component)) |
| `--time-zone ZONE` | With `--csv`: the zone of a time written with none — an IANA id (`Europe/Berlin`), `UTC`, or an offset (`+03:00`). Without it such a time is refused |
| `--step DURATION` | The resolution asked for, and the coverage grid. Default: with `--csv`, the interval measured from the file's rows as the collector reads them (each instance's most frequent gap between rows with a value, within the run's window, taken when it repeats at least 3 times and is at least half the gaps; the widest over the series the asserts read, so a coarser series in the file that no assert reads does not set it — with no assert yet, over the declared components' catalogue metrics), printed as `wir-collect: step 5s, measured from the file's rows`; with no measurable interval, and for Prometheus and `--recording`, `15s`. A measured interval coarser than `15s` that would leave fewer than 10 points in the run is not used, and the console says so (`step 10m measured from the file's rows is too coarse for this 35m run (fewer than 10 points) - collecting at 15s; …`) |
| `--clock-offset DURATION` | Added to every timestamp the source reports: `-30s` lines up servers whose clocks run 30 s ahead. Default `0` |
| `--env NAME` | The `serverMetrics.environments` entry to resolve `${env.*}` from. Default: the file's `defaultEnv` |
| `--test-name NAME` | The same text as `-Jwebinsight.report.title` when step 3 sets one. Default: `JMeter Web Insight Report` |
| `--rampup MILLIS` | Leave the first MILLIS of the run out of the judged window. The same number as `-Jwebinsight.report.rampup` |
| `--max-wait DURATION` | How long a source may take to settle. Default `10m` |
| `--no-wait` | Take what the source holds now. Right for a re-collection long after the run, a recording or a CSV |
| `--help`, `-h` | The options, the exit codes and the plain `java -jar` command |

Exactly one of `--prometheus-url`, `--recording` and `--csv` is required; two are refused.
`--key=value` works too. An unknown or repeated option is refused, because a misspelt
`--test_name` that was quietly ignored would change the run's identity. `--recording` and `--csv`
paths are relative to where the command runs, and are written into the payload as typed.

**Settling.** Straight after a run, Prometheus may not yet hold the last scrapes. Unless told
`--no-wait`, the collector polls until each series reaches the end of the window and two polls
agree, for up to `--max-wait`, then takes what arrived. The coverage floor decides what that was
worth.

**Exit codes.**

| Code | Means |
|---|---|
| `0` | The payload was written, **whatever each series' status**. A failed source, a bad CSV row or an empty query is recorded in the payload, and step 3 decides what it means |
| `1` | Nothing was written: bad arguments (no source flag, or two), an unreadable or empty JTL, an unreadable annotations file, a file with nothing to collect (no server assert and no component of a catalogue type), an undeclared `--env` or `--component`, an unwritable `--out`, a ramp-up as long as the run, or (from `wir-collect.sh`) no `java`, no JMeter install, or not exactly one plugin JAR in `lib/ext` |

There is no other code, on purpose. A collector that exited non-zero on a dead source would
stop a `set -e` script or a Jenkins `sh` step before step 3, and step 3 is the only report that
gates: the collector would be deciding what a collection failure means, which is the report's call.

A file with catalogue components and no server assert writes a payload of those components'
catalogue metrics and exits 0, under the same rule. If the source holds none of them, the payload
has no series and the summary line says so. A `custom` component alone still exits 1: it has no catalogue, so there is nothing to
fetch until an assert writes a query. A file whose every assert is disabled writes an empty payload
too, and its summary says no enabled server assert had a query to collect.

Each series gets one ASCII line, for grepping a CI log, followed by a summary. A series is named
by its component and what the file asked for: `orders-db/connections.used.pct` for a catalogue
metric, `api/<query>` for a custom one, so two components asking one query print two lines.
`coverage=` is of the collected window, as everywhere else. The payload is written with LF line
endings on every OS.

```text
wir-collect: OK api/sum by (pod) (rate(container_cpu_usage_seconds_total{...}[1m])) coverage=100% of the collected window
wir-collect: wrote /ci/ws/server-metrics.json - run 967a1b397b7fb1d2, collection eab10c9be95a3345, 1 series, 0 not judgeable
```

Typography gets an ASCII spelling (an em dash prints as `-`), and anything else outside ASCII its
Java escape — a backslash, `u` and four hex digits — so a query that a no-break space pads reads `EMPTY bus/\u00a0lag` rather than looking like the plain-space
padding the query is trimmed of. A tab or a line break inside a multi-line query prints as `\t` or
`\n`, so one series stays one line. Such a series also gets a `WARNING` line naming the character,
and saying so when it comes from an `${env.*}` value rather than the file. It gets none when it
came back `OK`, `PARTIAL` or `INSUFFICIENT`: the source answered the text as written, character
and all:

```text
wir-collect: EMPTY bus/\u00a0lag coverage=0% of the collected window
wir-collect: WARNING bus/\u00a0lag: the query has an invisible character at its start (U+00A0) that is not trimmed like a plain space, so no series will match it - delete it (it comes from a value ${env.q} interpolates)
```

### Where the metrics come from

**`--prometheus-url URL`** queries a live Prometheus. The token is never on the command line:
`--prometheus-token-env PROM_TOKEN` names the environment variable that holds it. A URL with
userinfo (`https://bob:secret@…`) is refused. A query parameter whose name contains `token`, `key`,
`secret`, `password`, `passwd`, `auth`, `credential` or `sig` (`api_key`, `access_token`) must take
its value from `${secret.NAME}`,
an environment variable the collector resolves and masks wherever the URL is written. The URL may
also carry `${env.*}` placeholders.

**`--recording DIR`** replays recorded Prometheus `query_range` responses, one file per query,
named by the first 16 hex characters of the SHA-256 of the resolved query text:
`<digest>.json`, where the digest is `printf '%s' 'up' | sha256sum | cut -c1-16`. A missing file
reads as `EMPTY`, an unreadable one as `FAILED`. Use it for offline replay and for repeatable
tests.

**`--csv FILE`** reads an export from anything that is not Prometheus. The format:

```text
time,component,metric,instance,value
2026-10-02T14:02:15+03:00,orders-db,connections.used.pct,pg-1,41.5
2026-10-02T14:02:15+03:00,api,queue-depth,,17
```

- **Long format**: one row per point. A header row is required; columns are found by name, in any
  order, and extra columns are ignored. Quoted cells and a UTF-8 BOM are allowed.
- **`time`**: ISO 8601 **with** an offset or `Z`, or epoch milliseconds. A time with no zone is
  refused, never guessed, unless `--time-zone` says which zone the file's times are in. An all-digit
  time below 100000000000 (before 1973) is refused as epoch seconds — `time "1759406535" looks like
  epoch seconds - this reader takes epoch milliseconds` — because read as milliseconds it lands in
  January 1970, outside the run, and the series would come back `EMPTY` with nothing to say why.
  An all-digit time of 15 digits or more is refused the same way, as epoch microseconds or
  nanoseconds.
- **`component`**: a component id from the file. A series is its component and its query, so two
  components of one type are told apart by this column alone.
- **`metric`**: a catalogue metric's name (`connections.used.pct`), or a custom assert's `query`
  text exactly as written.
- **`instance`**: optional. Empty is one instance; a value becomes the chart label
  `instance=<value>`.
- **`value`**: a number with `.` as the decimal point and no thousands separator. Empty or `NaN` is
  a gap, not zero. Without `--from` it is the metric's value in the catalogue's unit and nothing is
  converted, so a counter must be exported as the rate the catalogue's query would have computed.
- Rows more than one step outside the run's window are dropped. **A series whose every row falls
  outside the run is said**, not dropped in silence: `server.csv has 62 rows for db/connections and
  none inside the run: they run 2026-10-20T23:21:44Z .. 2026-10-20T23:22:45Z, after the run (…). They
  start 3h late, which is usually a time zone: were local times written with Z? Give the file's zone
  with --time-zone, or move the rows with --clock-offset -3h` — the series is `SOURCE FAILED` with
  that sentence on the console and the Server tab. A shift of seconds is named as clock skew, with
  the `--clock-offset` that moves it; rows before and after the run with none inside it (an export
  of two other runs) are named as such, with `export the run's window`. Nothing is ever shifted
  without being asked. Rows inside the run whose every value is empty or `NaN` are said as that; under
  `--from`, rows that hold numbers a counter conversion leaves nothing of (no row before the run to
  take a rate from) are said as that, with `export from one step before the run`.
- **The names must match exactly.** `component` and `metric` are compared as written; a row whose
  names differ from the annotations only by case or by `.`, `_` and `-` (`App` for `app`,
  `cpu_pct` for `cpu.pct`) fails the series with a sentence naming both spellings, and every
  component/metric pair in the file that nothing asked for is listed on the console.
- **A row with more cells than the header is refused by line**: an unquoted `90,9` is two cells,
  and read cell by cell it would be 90. Quote such a value, or write it with a `.`. A row that
  stops before the value column, and a quoted cell that never closes, are refused the same way.
- A repeated time with another value keeps the later row and says so on the console (two hosts
  exported without an `instance` column, or an export appended twice).
- A `;` or tab delimiter and a UTF-16 file are refused by name; export comma-separated UTF-8.
- **A bad row fails only the series it belongs to**, naming the line — e.g.
  `metrics.csv line 1328: time "2023-11-14 22:13:20" has no time zone - …` — and the source
  reads `PARTIAL`. A missing required column fails the whole file, quoting the header it has.
- A `metric` cell that is a name padded with an invisible character a plain trim leaves (a
  no-break, zero-width or bidi character) is a bad row of the series that name asks for, not a
  series of its own: `m.csv line 3: metric "connections.used.pct" has an invisible character at
  its end (U+00A0) that is not trimmed like a plain space, so no row written that way is read as
  this series - delete it`.
- A file another tool wrote under other column names, or with no component column, is read with
  `--csv-columns` and `--component`: see [From InfluxDB 2](#from-influxdb-2-csv-columns-and-a-fixed-component).

Whichever flag is used, the payload names the collection's one source `metrics`.

#### The reader options as properties

`jmeter -g` and the GUI's **Rebuild report with server data** read a CSV themselves, and take the
reader options as JMeter properties. Three of them are not spelled as the flag is:

| Property | Flag |
|---|---|
| `webinsight.report.servermetrics.step` | `--step` |
| `webinsight.report.servermetrics.clockoffset` | `--clock-offset` |
| `webinsight.report.servermetrics.timezone` | `--time-zone` |
| `webinsight.report.servermetrics.from` | `--from` |
| `webinsight.report.servermetrics.layout` | `--layout` |
| `webinsight.report.servermetrics.columns` | `--columns` |
| `webinsight.report.servermetrics.csvcolumns` | `--csv-columns` |
| `webinsight.report.servermetrics.component` | `--component` |
| `webinsight.report.servermetrics.env` | `--env` |

The reader's sentences name the flag on both paths: under `jmeter -g`, `move the rows with
--clock-offset -3h` means `webinsight.report.servermetrics.clockoffset=-3h`. A property spelt as
the flag (`webinsight.report.servermetrics.time-zone`) does nothing, and the console's WARNING says
so, with `did you mean webinsight.report.servermetrics.timezone?`.

#### `--from`: a file exported from another tool

Every monitoring tool names a metric its own way and hands over its own kind of number: Telegraf
and OpenTelemetry export raw cumulative counters, Datadog some metrics as rates per second, CloudWatch
a sum per period, and a fraction where the catalogue reads a percentage. A value read as it stands
would be judged on the wrong number, and pass or fail without a word. `--from TOOL` says which tool
the file came from — `telegraf`, `datadog`, `otel` or `cloudwatch`, and only with `--csv`:

```bash
./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv datadog-export.csv --from datadog
```

The `metric` column then holds that tool's names, written as [the catalogue writes them for that
tool](15-Server-Catalogue.md#which-name-each-tool-uses) — `postgresql.deadlocks` from Telegraf or Datadog,
`AWS/EC2:CPUCreditBalance` from CloudWatch — and the export holds the series each name's note
describes. The catalogue says, for each metric, what each tool calls it, what kind of number that
is, and the scale to apply. For a catalogue metric the collector looks, in this order:

1. **Under the tool's name** for it, and converts each value to the catalogue's unit.
2. **Under the catalogue's own name** (`connections.used.pct`), and reads the values as they are. This
   is the way in for a series you computed yourself, already in the catalogue's unit.
3. **Neither**: when the catalogue says the tool has no single metric for it, the series fails with
   `telegraf has no single metric for postgres.connections.used.pct - put the computed series in the
   file under connections.used.pct`; when the catalogue does not name it in that tool's export at
   all, with `the catalogue does not name … in otel's export - put the series in the file under …`.
   A tool that names it but is missing from the file is a missing series, as without `--from`.

How a value is converted, by the kind of number the tool hands over:

| Kind | Conversion |
|---|---|
| `gauge` | Used as it is |
| `rate` | Already per second; used as it is |
| `counter` | A raw cumulative count, turned into a rate per second between consecutive values and stamped at the later one. A drop is a restart, counted from zero; the first value gives no point, and a gap is skipped. It is converted before the run's window is applied, so the run's first rate comes from the row just before the run |
| `sum-per-period` | Divided by the export's period: the most common spacing between an instance's rows, or `--step` for an instance with one row |

Then the value is multiplied by the **scale**: 100 for a 0–1 fraction the catalogue reads as %, 60
for a rate per second the catalogue reads per minute, 0.001 for milliseconds read as seconds.

A custom assert's `query` is never one of a tool's names: it is looked up and read exactly as
without `--from`. The payload records the tool on the source and, on every series read under the
tool's name, that name, its kind and the scale, and the report shows both: the Collection panel's
source row says the file was read as the tool's export, and an open row's note says
`read as postgresql.percent_usage_connections (datadog, gauge, x100)`. A collection without `--from`
writes neither. The tool enters the `collectionId` only when given: the same file read as a tool's
export is a different collection, with different numbers.

#### From Grafana: the wide layout

Grafana sits in front of Prometheus, InfluxDB, CloudWatch, Datadog and the rest, so its CSV download
is the export most people can produce. It is **wide**: a time column, then one column per series.

**Exporting it.** On the panel: menu → **Inspect** → **Data** → **Data options**:

- **Series joined by time**: on. Every series goes into one file, a column each.
- **Formatted data**: **off**. Off, the time is epoch milliseconds and each value a plain number. On,
  the time has no zone (it is the dashboard's, or the browser's) and a value may carry a unit
  (`41.5%`, `1.20 K`), which is refused.
- **Download CSV**, not **Download for Excel**: that one is UTF-16 and tab-separated, and is refused
  by name.

**Naming the columns.** A header is free text chosen by whoever built the dashboard, and a raw
exporter metric is not a catalogue metric (`connections.used.pct` and `connections.active` are both
computed from `pg_stat_activity_count`), so nothing is guessed. Set the panel's **legend** to the
component and the metric before exporting, `component/metric` or `component/metric/instance`:

```text
"Time","orders-db/connections.used.pct/pg-1","orders-db/connections.used.pct/pg-2","events/consumer.lag.max"
1700000000000,40,12,100
1700000001000,43,,112
```

With a Prometheus legend, that is `orders-db/connections.used.pct/{{instance}}`. Each part is
trimmed; the instance becomes the chart label `instance=<value>`, and without one the column is one
instance with no label.

**`--columns MAP`**, for a legend you cannot change, or a metric name with a `/` in it: a custom
assert's query, or a CloudWatch name read with `--from cloudwatch` whose namespace is one of AWS's
(`AWS/EC2:CPUCreditBalance`; the agent's `CWAgent:` names have none). The legend convention splits a
header at every `/`, so `web/AWS/EC2:CPUCreditBalance` reads as component `web`, metric `AWS` and
instance `EC2:CPUCreditBalance`, and a header with four parts is not read at all. The map is a CSV
that names each export column by its exact header text, and its `metric` cell holds the name whole.
Its columns are found by name, in any order:

```text
column,component,metric,instance
orders-db:5432 deadlocks,orders-db,deadlocks,pg-1
web CPUCreditBalance,web,AWS/EC2:CPUCreditBalance,
```

A map entry wins over the header convention. An entry for a column the export does not have maps
nothing, and the console says so.

```bash
./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv grafana.csv --layout wide --columns columns.csv --step 15s
```

- **The time column** is the one whose header is `Time` (any case), or else the first.
- **A column neither names is ignored and listed**, one line for all of them:
  `wir-collect: 1 column not mapped to a component and metric: "orders-db:5432 pg_stat_activity_count"`.
- **So is a column that names a series nothing asks for** — a typo in the legend's component, or a
  header that only looks like `component/metric` (a label-set legend with a path in it, an expression
  with a `/`, a CloudWatch name in an `AWS/` namespace):
  `wir-collect: 1 column names a component and metric the annotations do not ask for:
  "order-db/connections.used.pct/pg-1"`. Without it the only trace is the series it was meant for,
  `EMPTY` further down.
- **An empty cell or `NaN` is a gap**, never a zero. Joined by time is an outer join, so an empty cell
  is a series with no point at another series' timestamp.
- **A cell that is not a plain number fails its series**, naming the line. One that looks formatted
  says so: `value "41.5%" is not a plain number - it looks formatted, with a unit or a thousands
  separator: export it with Formatted data off`.
- **Two columns naming the same series and instance** fail that series, naming both columns.
- A `;` or tab delimiter is refused by name; an older Grafana's `sep=,` first line is skipped.
- `--from TOOL` works on a wide file exactly as on a long one: a column may name the tool's metric.
  A legend can carry a name with no `/` in it (Datadog's, OpenTelemetry's, Telegraf's, CloudWatch's
  `CWAgent:` ones). A CloudWatch name in an AWS namespace (`AWS/EC2:`, `AWS/RDS:`) has one, so a
  column holding one is named through `--columns`.

**`--time-zone ZONE`** reads a time written with no zone (`2026-10-02 14:02:15`, a `T` or a space,
seconds and a fraction optional) in that zone: an IANA id (`Europe/Berlin`), `UTC`, or an offset
(`+03:00`). It works in both layouts. Use it for a file whose times carry no zone, such as an export
made with Formatted data on (whose values must still be plain numbers), and set it to the dashboard's
zone. A time with its own offset, and epoch milliseconds, are what they say whatever it is. A time
the zone's daylight-saving change repeats or skips (02:30 on the night the clocks go back happens
twice) is refused rather than resolved to one of its moments: give the offset the file was written
in instead (`+02:00`), or export epoch milliseconds.

**`--step`: match the panel's interval.** Grafana exports the points the panel drew, at its
interval (`$__interval`: typically 15 s to 60 s on a one-hour run), not the raw scrape. Without
`--step` the collector measures that interval from the file and uses it, and says so. Coverage
counts one slot per `--step`, so a 60 s export read at an explicit `--step 15s` covers a quarter of
the slots at most and reads `LOW_COVERAGE`. The collector says so for every CSV series whose points are more
than 1.5 steps apart, and for one further apart than a step at all when that left it short of the
coverage floor (20 s rows in 15 s slots cover three in four):

```text
wir-collect: orders-db/connections.used.pct points are 60s apart but --step is 15s - coverage counts 15s slots; pass --step 60s
```

The payload records `"layout": "wide"` and the `"timeZone"` on the source, each only when given, and
the Collection panel says both beside the source's id — `wide CSV, times without a zone read in UTC`.
A long collection with no zone writes neither. Each enters the `collectionId` only when given.

#### From InfluxDB 2: CSV columns and a fixed component

InfluxDB 2 writes one format from all three of its paths — `influx query --raw`, the API
(`POST /api/v2/query` with `Accept: application/csv`) and a cell's CSV download — its **annotated
CSV**. It is long, one row per point, but its columns have their own names (`_time`, `_value`,
`_measurement`, `_field`, and one per tag), and it names no component:

```text
#group,false,false,true,true,false,false,true,true,true,true
#datatype,string,long,dateTime:RFC3339,dateTime:RFC3339,dateTime:RFC3339,double,string,string,string,string
#default,mean,,,,,,,,,
,result,table,_start,_stop,_time,_value,_field,_measurement,host,region
,,0,2022-12-31T05:41:24Z,2023-01-31T05:41:24.001Z,2023-01-01T00:52:00Z,15.43,mem,m,A,east
```

```bash
influx query --raw 'from(bucket: "telegraf")
  |> range(start: 2026-10-02T14:00:00Z, stop: 2026-10-02T15:00:00Z)
  |> filter(fn: (r) => r._measurement == "postgresql" and r._field == "numbackends")
  |> filter(fn: (r) => r.host == "orders-db" and r.db == "orders")' > influx.csv

./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv influx.csv --csv-columns time=_time,value=_value,metric=_measurement+_field,instance=host \
       --component orders-db
```

- **`--csv-columns SPEC`** names the file's column for each of the reader's own, as `key=column`
  pairs: `time`, `component`, `metric`, `instance`, `value`. A key not given keeps its own name, and a
  column is matched as a header always is, trimmed and in any case. `metric=_measurement+_field` joins
  two columns into `<measurement>.<field>` — `postgresql.numbackends`, the name Telegraf gives a metric
  it writes to InfluxDB; a row with either half empty names no series. Only `metric` joins. An unknown
  key, a key given twice or a key with no column is refused, naming it.
- **One row per instance per time.** InfluxDB writes one table per tag set, and Telegraf's
  `postgresql` measurement has one per database (its `db` tag) as well as per `server` and `host`.
  Mapped to `instance=host`, every database's rows land on one instance, and a repeated timestamp keeps
  the last row read: one database's number, whichever table came last, and nothing says so. Filter the
  query down to one series per instance, as the `db` filter above does, or map `instance` to the tag
  that tells the tables apart (`instance=db`, one instance per database).
- **The metric is the file's name for it.** `postgresql.numbackends` (the backends connected to one
  database) is not what any catalogue metric means, so the assert that reads it is a custom one whose
  `query` is that name (`"query": "postgresql.numbackends"`). A catalogue assert reads the rows named
  by the catalogue's own name (`connections.active`).
- **`--component ID`** puts every row in that component. It is for a file whose asserts are all on
  that one component: a payload is one collection, and step 3 reads one, so an assert on any other
  component finds no rows and reads `UNMATCHED` — counted and reported, never judged. A file that
  covers several components needs a component column, or `--csv-columns component=<tag>` naming a tag
  whose values are the component ids. An id the annotations do not declare is refused (exit 1), and so
  is `--component` beside `--csv-columns component=`: the component is said in one place. Beside a
  component column of the file's own, the file is refused instead: every series fails, naming both,
  the payload is written with the source `FAILED`, and `wir-collect` exits 0, like any other source
  that could not be read.
- **`--from telegraf`** reads a catalogue metric under Telegraf's name for it, converted to the
  catalogue's unit ([which name each tool uses](15-Server-Catalogue.md#which-name-each-tool-uses)). `metric=_measurement+_field`
  joins exactly that name: rows of `postgresql`'s `deadlocks` field read `postgresql.deadlocks`, a
  raw counter, which `deadlocks` turns into deadlocks per minute; summed per server first, as its
  note says, since Telegraf writes one series per database. Where Telegraf has no single metric for a
  KPI (`connections.used.pct` is a ratio, and `connections.active` needs `inputs.postgresql_extensible`),
  the catalogue says `null`, and the assert reads the computed series only under the catalogue's own
  name — `numbackends` alone answers no catalogue metric. A custom assert's query is read as written
  either way.
- **The annotations need no flag.** A line whose first cell starts with `#` is skipped, the leading
  empty column maps nothing, and each block — a table whose columns differ starts one after a blank
  line, with fresh annotations and a header of its own — is read by its own header: the line after
  `#group`, `#datatype` or `#default`, or, after a blank line, a line that names the time column. Any
  other `#` line is a comment and starts nothing. `_start` and `_stop` are the query's range, not the
  point: leave them unmapped. Times are RFC 3339 with a `Z`.
- **A column the spec maps and the header lacks fails the file**, naming it and quoting the header:
  `influx.csv line 4: the header has no column "_tme" (--csv-columns time) - the header is: "",
  "result", "table", ...`. An export handed over with no flags is refused the same way, and the
  sentence names both flags.

#### From InfluxDB 3

InfluxDB 3 writes no annotations: `influxdb3 query --format csv` (or `/api/v3/query_sql` with
`format=csv`) writes the SQL result's columns, as many fields side by side as the `SELECT` names.
Shape the SQL long, in the reader's own column names, and `--csv-columns` is not even needed:

```bash
influxdb3 query --database telegraf --format csv \
  "SELECT time, 'postgresql.numbackends' AS metric, host AS instance, numbackends AS value
     FROM postgresql
    WHERE host = 'orders-db' AND db = 'orders'
      AND time >= '2026-10-02T14:00:00Z' AND time < '2026-10-02T15:00:00Z'" > influx3.csv

./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv influx3.csv --component orders-db --time-zone UTC
```

- **`--time-zone UTC` is required.** InfluxDB 3 writes `time` in UTC with no zone
  (`2025-01-20T17:01:40.690067`), and a zone-less time is refused unless told — never guessed.
- **`--component`** stands in for the component column the result does not have, for a file whose
  asserts are all on that component, as above.
- **One row per instance per time**, as above: `db = 'orders'` keeps one database's rows. Without it,
  every database's rows land on one instance and the last one read wins; select `db AS instance`
  instead to keep them apart.
- `metric` is whatever the assert names: a custom assert's query text, as here
  (`"query": "postgresql.numbackends"`), or a catalogue metric's own name (`'connections.active' AS
  metric`) for a column that measures what that metric means. `UNION ALL` of one `SELECT` per metric
  puts several in one file.

#### From Dynatrace

The metrics API v2, `GET /api/v2/metrics/query` with `Accept: text/csv; header=present`, is long, with
the metric id, a column per dimension, a time with no zone (UTC) and the value:

```bash
curl -s -H "Authorization: Api-Token $DT_TOKEN" -H "Accept: text/csv; header=present" \
  "https://<environment>.live.dynatrace.com/api/v2/metrics/query?metricSelector=builtin:host.cpu.usage&from=2026-10-02T14:00:00Z&to=2026-10-02T15:00:00Z&resolution=1m" \
  > dynatrace.csv

./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv dynatrace.csv --csv-columns metric=metricId,instance=dt.entity.host \
       --component web --time-zone UTC --step 60s
```

```text
metricId,dt.entity.host,time,value
builtin:host.cpu.usage,HOST-0956C3557E9109C1,2026-10-02 14:00:00,19.0
```

- **Pass `resolution=1m`** (or finer). The default is about 120 points per series over the whole range,
  whatever its length, and coverage counts one slot per `--step`: set `--step` to the resolution.
- `time` and `value` keep their own names; the spec maps the metric id and the dimension that is the
  instance. `--time-zone UTC`, because the CSV's times carry no zone.
- The metric is Dynatrace's id, so the assert that reads it is a custom one whose `query` is that id
  (`"query": "builtin:host.cpu.usage"`).

The payload records `"columns"` — the spec in one spelling, its keys in the order time, component,
metric, instance, value — and `"component"` on the source, each only when given, and the Collection
panel says both beside the source's id: `columns time=_time metric=_measurement+_field instance=host
value=_value, every row in orders-db`. Each enters the `collectionId` only when given.

#### From CloudWatch

`aws cloudwatch get-metric-data` (AWS CLI version 2) returns every query's points as JSON, and the
few lines of Node.js below write them as the long CSV. Each query's `Label` names its series,
`component|metric|instance`: with `--from cloudwatch` the metric is the catalogue's CloudWatch name
for it, `<Namespace>:<MetricName>` ([which name each tool uses](15-Server-Catalogue.md#which-name-each-tool-uses)), and a
KPI computed with metric math is labelled with the catalogue's own name. For an `aws-ec2` component
`web` — the instance's status check, a gp2 volume's burst balance, and `ebs.iops` computed for that
volume — `cloudwatch-queries.json`:

```json
[
  { "Id": "status", "Label": "web|AWS/EC2:StatusCheckFailed|i-0abc12345def67890",
    "MetricStat": { "Metric": { "Namespace": "AWS/EC2", "MetricName": "StatusCheckFailed",
      "Dimensions": [ { "Name": "InstanceId", "Value": "i-0abc12345def67890" } ] },
      "Period": 60, "Stat": "Maximum" } },
  { "Id": "burst", "Label": "web|AWS/EBS:BurstBalance|vol-0123456789abcdef0",
    "MetricStat": { "Metric": { "Namespace": "AWS/EBS", "MetricName": "BurstBalance",
      "Dimensions": [ { "Name": "VolumeId", "Value": "vol-0123456789abcdef0" } ] },
      "Period": 60, "Stat": "Minimum" } },
  { "Id": "reads", "ReturnData": false,
    "MetricStat": { "Metric": { "Namespace": "AWS/EBS", "MetricName": "VolumeReadOps",
      "Dimensions": [ { "Name": "VolumeId", "Value": "vol-0123456789abcdef0" } ] },
      "Period": 60, "Stat": "Sum" } },
  { "Id": "writes", "ReturnData": false,
    "MetricStat": { "Metric": { "Namespace": "AWS/EBS", "MetricName": "VolumeWriteOps",
      "Dimensions": [ { "Name": "VolumeId", "Value": "vol-0123456789abcdef0" } ] },
      "Period": 60, "Stat": "Sum" } },
  { "Id": "iops", "Label": "web|ebs.iops|vol-0123456789abcdef0",
    "Expression": "(reads + writes) / PERIOD(reads)" }
]
```

`cloudwatch-to-csv.js` needs Node.js and nothing else:

```js
// cloudwatch-to-csv.js FILE...: aws cloudwatch get-metric-data output -> wir-collect's long CSV.
// Each query's Label names its series: component|metric|instance.
const fs = require('fs');
const cell = v => /[",\r\n]/.test(String(v)) ? '"' + String(v).replace(/"/g, '""') + '"' : String(v);
const rows = ['time,component,metric,instance,value'];
for (const file of process.argv.slice(2)) {
  for (const r of JSON.parse(fs.readFileSync(file, 'utf8')).MetricDataResults) {
    const [component, metric, instance = ''] = r.Label.split('|');
    if (!metric) throw new Error(file + ': query ' + r.Id + ' is labelled "' + r.Label + '", not component|metric|instance');
    r.Timestamps.forEach((t, i) => rows.push([t, component, metric, instance, r.Values[i]].map(cell).join(',')));
  }
}
process.stdout.write(rows.join('\n') + '\n');
```

```bash
aws cloudwatch get-metric-data --metric-data-queries file://cloudwatch-queries.json \
    --start-time 2026-10-02T14:00:00Z --end-time 2026-10-02T15:00:00Z \
    --scan-by TimestampAscending --output json > cloudwatch.json
node cloudwatch-to-csv.js cloudwatch.json > cloudwatch.csv

./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv cloudwatch.csv --from cloudwatch --step 60s --no-wait
```

```text
time,component,metric,instance,value
2026-10-02T14:00:00+00:00,web,AWS/EC2:StatusCheckFailed,i-0abc12345def67890,0
```

- **The label is the mapping.** CloudWatch hands each query's `Label` back with its points; `|`
  separates the parts because a CloudWatch name has a `:` in it, and one in an AWS namespace a `/`
  as well. A query with
  `"ReturnData": false` only feeds an expression and writes no rows. A query left without a label
  gets CloudWatch's default one, and the script stops on it, naming the query.
- **The times are read as written.** The CLI version 2 prints a timestamp in ISO 8601 with its offset
  (`2026-10-02T14:00:00+00:00`; `cli_timestamp_format = iso8601` is its default), which `--csv` reads
  as it stands. `--start-time` is inclusive and `--end-time` exclusive: give the run's window. The CLI
  fetches every page of a long answer itself.
- **`--step` is the period**, and one collection has one: 60 s here, because status checks and
  `AWS/EBS` are 1-minute data. `cpu.credit.balance` is 5-minute data whatever the monitoring, so a
  file that asserts on it is exported with `"Period": 300` throughout and collected at `--step 300s`.
- **Read under its CloudWatch name**, `AWS/EC2:StatusCheckFailed` and `AWS/EBS:BurstBalance` are
  converted as the catalogue says (both gauges, used as they are); `ebs.iops` is read under the
  catalogue's own name, as it is — the [metric-math KPIs](15-Server-Catalogue.md#aws-ec2-four-kpis-computed-with-metric-math)
  are computed in the request, as here.
- **Through Grafana's CloudWatch data source**, a name in an AWS namespace cannot be a legend: the
  [header convention](#from-grafana-the-wide-layout) splits on `/`, so
  `web/AWS/EC2:StatusCheckFailed/i-0abc12345def67890` is not a component, a metric and an instance,
  and the column is listed as not mapped. (The agent's `CWAgent:` names have no `/`, and a legend
  can carry them.) Name such a column in a `--columns` map, whose `metric` cell holds the name
  whole, and pass `--from cloudwatch` beside it:

  ```text
  column,component,metric,instance
  StatusCheckFailed i-0abc12345def67890,web,AWS/EC2:StatusCheckFailed,i-0abc12345def67890
  ```

#### From Datadog

Datadog's `GET /api/v1/query` returns one query's points as JSON — Datadog now recommends
`/api/v2/query/timeseries` over it, but still documents it — and the few lines of Node.js below write
them as the long CSV. One request per metric, written for what the catalogue's note for it asks
([which name each tool uses](15-Server-Catalogue.md#which-name-each-tool-uses)):

```bash
curl -s -G "https://api.datadoghq.com/api/v1/query" \
     -H "Accept: application/json" -H "Authorization: Bearer ${DD_BEARER_TOKEN}" \
     --data-urlencode "from=1790949600" --data-urlencode "to=1790953200" \
     --data-urlencode "query=max:postgresql.percent_usage_connections{host:orders-db} by {host}.rollup(max, 60)" \
     > connections.json
node datadog-to-csv.js orders-db postgresql.percent_usage_connections=connections.json > datadog.csv

./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
       --csv datadog.csv --from datadog --step 60s --no-wait
```

`datadog-to-csv.js` needs Node.js and nothing else:

```js
// datadog-to-csv.js COMPONENT METRIC=FILE...: GET /api/v1/query responses -> wir-collect's long CSV.
// Each FILE is one query's response, written under METRIC; a series' tag_set is its instance.
const fs = require('fs');
const cell = v => /[",\r\n]/.test(String(v)) ? '"' + String(v).replace(/"/g, '""') + '"' : String(v);
const [component, ...pairs] = process.argv.slice(2);
const rows = ['time,component,metric,instance,value'];
for (const pair of pairs) {
  const metric = pair.slice(0, pair.indexOf('=')), file = pair.slice(pair.indexOf('=') + 1);
  const response = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (response.status !== 'ok') throw new Error(file + ': ' + (response.error || JSON.stringify(response.errors || response)));
  for (const s of response.series) {
    for (const [t, v] of s.pointlist) {
      if (v !== null) rows.push([t, component, metric, s.tag_set.join(','), v].map(cell).join(','));
    }
  }
}
process.stdout.write(rows.join('\n') + '\n');
```

```text
time,component,metric,instance,value
1790949600000,orders-db,postgresql.percent_usage_connections,host:orders-db,0.4
```

- **The host is your site's**: `api.datadoghq.com` for an account on `app.datadoghq.com`,
  `api.datadoghq.eu` for `app.datadoghq.eu`; Datadog's API reference lists one per site. The token is
  a personal or service access token with the `timeseries_query` permission.
- **`from` and `to` are the run's window in seconds since the epoch** (14:00 to 15:00 UTC here). Each
  point comes back as `[milliseconds, value]`, and the script writes the milliseconds as the time,
  which `--csv` reads as epoch milliseconds. The script leaves out a point whose value is `null`: a
  gap, never a zero.
- **`.rollup(<aggregator>, 60)` sets the spacing to `--step`.** Without it the API picks the interval
  from the range (20 s for an hour), and coverage counts one slot per `--step`. The aggregator says
  what each interval keeps, and it follows the `kind` in the catalogue's Datadog entry for the metric:
  - a `gauge` keeps the end its asserts judge: `max` for one that fails high, as
    `connections.used.pct` here, `min` for one that fails low, as `memory.available.pct`.
  - a `rate` takes `avg`. An Agent check's `rate()` is stored as a gauge, the change per second over
    each check interval, 15 s by default. One deadlock reads 1/15 a second in its interval and 0 in
    the other three, so `avg` gives the minute its 1 deadlock, and `max` would give it 4.
  - a `sum-per-period`, a count per check interval, takes `sum`: the minute's count, which
    `--from datadog` divides by the 60 s between points.
  - a `counter`, a raw total, takes `max`: a total that only climbs is highest at the interval's last
    point, and `--from datadog` takes the rate between consecutive points.
- **The note's fold is the query's.** `one series per db tag: export the sum by host` is
  `sum:postgresql.deadlocks{host:orders-db} by {host}.rollup(avg, 60)`, a `rate`. A series'
  `tag_set`, from `by {host}`, becomes its instance, `host:orders-db`.
- **The metric is the name the script is given**, the catalogue's Datadog name for it, and
  `--from datadog` converts what is read under it: `postgresql.percent_usage_connections` is a 0–1
  fraction, read ×100 as `connections.used.pct`. Several responses go in one file:
  `node datadog-to-csv.js orders-db postgresql.percent_usage_connections=connections.json postgresql.deadlocks=deadlocks.json`.

### Why `--test-name` must match the report title

The payload carries the run's `runId`, a hash of the run that includes its name. The report refuses
a payload whose `runId` is not its own, because drawing another run's metrics under this run's
headings is exactly what the id exists to prevent. The tab then says the payload was refused, and
why.

`jmeter -g` names a run by its report title, `JMeter Web Insight Report` unless
`-Jwebinsight.report.title` says otherwise. `wir-collect` defaults to the same text. Set a title on
step 3 and step 2 needs the same text in `--test-name`. Feeding step 3 a different JTL from the one
step 2 read has the same effect.

### Running it from a release

Deploy the release's JAR to `$JMETER_HOME/lib/ext` as usual. The JAR runs the collector itself, with
the same command on Windows (PowerShell or cmd), macOS and Linux; it finds JMeter from its place in
`lib/ext`, and refuses a `lib/ext` that holds two plugin JARs. A plugin JAR run from anywhere else
(a release folder) is the collector that runs, against the JMeter `JMETER_HOME` names (else the one
`jmeter` on `PATH` belongs to), whatever version that JMeter's `lib/ext` holds. `--help` prints the line with your JAR's real name:

```
java -jar <JMETER_HOME>/lib/ext/jmeter-web-insight-report-X.Y.Z.jar --jtl results.jtl ...
```

**The wrapper scripts** are attached to the release beside the JAR, and are in its source archive,
under `scripts/`:

- **`wir-collect.sh`** is self-contained. Copy it beside a Jenkinsfile, onto a CI image or into
  `$JMETER_HOME/bin`, and it needs nothing else. Run it as `bash wir-collect.sh ...`: a file
  downloaded from a release has no executable bit, so `./wir-collect.sh` needs a `chmod +x` first.
  It finds JMeter from `JMETER_HOME`, then from `jmeter` on `PATH` (symlinks followed, so
  Homebrew's wrapper resolves), honours `JAVA_HOME`, refuses a `lib/ext` holding two plugin JARs,
  and builds the right classpath under Git Bash, MSYS2 and Cygwin as well.
- **`wir-collect.bat`** is one `java` line, no `bash`, for cmd and PowerShell. It runs the plugin
  JAR it finds beside itself, else in `..\lib\ext` (the `.bat` copied into `$JMETER_HOME\bin`),
  else in `%JMETER_HOME%\lib\ext`, so in `bin` or beside the JAR it needs no `JMETER_HOME`; a JAR
  beside it that is not in a `lib\ext` finds JMeter from `JMETER_HOME`, else from `jmeter` on
  `PATH` (as `wir-collect.sh` does), and says so when neither is there. Two
  plugin JARs beside it are refused, naming both, as two in `lib\ext` are. With
  no plugin JAR in any of the three, Java prints
  `Could not find or load main class com.jmeterwebinsightreport.WirCollectLauncher`.

**On a machine with more than one JMeter**, set `JMETER_HOME` to the one that holds the plugin JAR
you mean to collect with. Without it the script, and a plugin JAR run from outside `lib/ext` (by
`java -jar` or the `.bat`), take the JMeter that `jmeter` on `PATH` belongs to; the script then
collects with whichever plugin JAR that one holds.

---

## Reference

### The `serverMetrics` block

```json
"serverMetrics": {
  "components":   { "<id>": { "type": "postgres", "description": "…", "match": "…" } },
  "environments": { "<NAME>": { "vars": { "<key>": "<value>" } } },
  "defaultEnv":   "<NAME>",
  "requiresLoad": "<client rule id>",
  "collection":   { "coverageFloor": 0.9, "excludeRampUp": true },
  "asserts":      [ { … } ]
}
```

Unknown keys are ignored and carried through a Save untouched. A few keys are refused instead,
each with a sentence naming what to write ([below](#retired-and-refused-keys)). A container of the wrong shape (`"asserts": {}`,
`"warn": 640`, `"reduce": "max"`, a `null` entry in `asserts`) costs the whole `serverMetrics` block at
load: nothing in it is judged and the run says `SERVER NOT JUDGED`, while the client asserts are read
and judged as usual. Elsewhere a written `null` means absent.

Durations are written `500ms`, `30s`, `2m`, `1h`: lowercase, no space, never a bare number, because a
bare number is ambiguous between seconds and milliseconds.

### Components

| Key | | Default |
|---|---|---|
| `type` | `postgres`, `kafka`, `redis`, `host-linux`, `aws-ec2`, `oracle`, `influxdb2`, `influxdb3`, `jvm`, `http-service`, `kubernetes-pod` or `mysql` ([the catalogue](15-Server-Catalogue.md#the-catalogue)), or `custom` | required |
| `description` | The label on its card, the header chip, the Summary card and the CI/CD JSON's `label`. The JUnit suite is named by the id | the id |
| `match` | A PromQL label selector, expanded into a catalogue query's `${match}` / `${andMatch}` slots. Read trimmed of surrounding spaces; an invisible character at either end (a no-break or zero-width space, a bidi or tag character, a C1 control) is not trimmed, goes into every catalogue query of the component as it is, and raises a warning under the component that names it by its code point. A `custom` component's asserts write their own query, so its match is never expanded and never warned about | — |

A component with no type or an unknown type is a configuration error, and every server assert on
it is `INVALID_RULE`, with a sentence naming the component, its type and the types there are.
`assertions` and `(no gate)` are reserved ids: the JUnit report already names suites that. There is
no implicit component — every server assert names a declared one.

### Credentials stay out of the file

The annotations document is written into every report and sidecar. Connection details are
`wir-collect` flags, so the file has no place for a credential at all. What is checked:

- an `environments.*.vars` value holding a URL with userinfo or a credential parameter is refused;
- a `sources` block is refused whatever it holds, and never read.

The page and the download mask each of those shapes too. A credential in a shape none of them
recognises, such as text in a `description` or a bare value in a var a query uses, is not detected,
and it is published as written. On the command line, see
[Where the metrics come from](#where-the-metrics-come-from).

### Environments and interpolation

```json
"environments": {
  "TEST":    { "vars": { "cluster": "eu-test" } },
  "STAGING": { "vars": { "cluster": "eu-stg" } }
},
"defaultEnv": "TEST"
```

`wir-collect --env STAGING` picks one, `defaultEnv` otherwise, and the choice is stamped into the
payload. Placeholders are resolved in **queries and the `--prometheus-url` value only**:

| Placeholder | Resolves to |
|---|---|
| `${env.NAME}` | the selected environment's `vars.NAME`. `${env.name}` is the environment's own name, unless its `vars` declare a `name` |
| `${run.startTs}`, `${run.endTs}` | the window, epoch milliseconds |
| `${run.runId}`, `${run.testName}` | the run's identity and name, for a query that selects the test's own traffic by label |
| `${secret.NAME}` | the environment variable `NAME`. The `--prometheus-url` value only, and refused in a query, which is written as resolved |

An unknown name, or anything else shaped like a placeholder (`${evn.x}`, `${ENV.x}`, a nested one),
is refused. Left in place it would reach Prometheus as literal text, match nothing and read as
`NO_MATCH`. Bands are never interpolated: a threshold is a literal number. A `defaultEnv` that names
no declared environment is a configuration error.

### Server asserts

| Key | | Default |
|---|---|---|
| `id` | Names the JUnit testcase and every message | derived from the component and the metric or query |
| `description` | Free text | — |
| `enabled` | `false` parks it: `DISABLED`, never judged. A custom `query` is not collected; a catalogue `metric` still is, as every metric of the component is, for the picker | `true` |
| `component` | A declared component id | required |
| `metric` | A catalogue metric of the component's type. Supplies the query, `unit`, `reduce` and `across` | one of `metric` or `query` |
| `query` | PromQL (or a CSV `metric` text), with `${env.*}` / `${run.*}`. Required on a `custom` component. Read trimmed of surrounding spaces, tabs and newlines, as every other text field is; an invisible character at either end (a no-break or zero-width space, a bidi or tag character, a C1 control) is not trimmed and raises a warning, because nothing will match it. One that an `${env.*}` value brings is invisible to the file's checks, so `wir-collect` prints the warning for the query it resolved | one of `metric` or `query` |
| `unit` | The series' unit: what the collected values are in, written beside the numbers. Converts nothing. A verdict's number is written in it with `/min` added for a `slope` (`B/min`, or `/min` with no unit) and ` ÷ <rule>` for a `divideBy` (`% ÷ load:throughput`), wherever a verdict's number is written: the board, the Summary card, the Per instance panel, the picker, Set thresholds, the Generate review, the editor and JUnit; the CI/CD JSON keeps it as declared. So a slope's `unit` is `B`, never `B/min`, which would read `B/min/min` | the catalogue's, for a `metric` |
| `reduce.kind` | `max`, `min`, `avg`, `last`, `pN` (0 < N < 100, e.g. `p95`, `p99.9`, nearest-rank), `slope` (least squares, **per minute**) | the catalogue's for a `metric`; otherwise required |
| `reduce.over` | `max` and `min` only, one of: `{"sustainedFor": "30s"}`, the highest (lowest) level held for at least that long; `{"breachBudget": 0.02}`, the level exceeded at most that fraction of the time | — |
| `across` | How instances fold into one number: `max` (the worst instance when higher is worse), `min` (the worst when lower is worse) or `avg` | the catalogue's for a `metric` (`min` on most metrics where lower is worse, such as `up` or a hit ratio, else `max`); otherwise `max` |
| `warn`, `fail` | One operator each, `gt`, `gte`, `lt` or `lte`, and a literal number. `fail` is tested first | at least one |
| `requiresLoad` | A client throughput rule id ([Advanced](13-Server-Tab.md#advanced)). An empty string reads as no key, so it takes the block's | the block's `requiresLoad` |
| `divideBy` | A client load rule id ([Advanced](13-Server-Tab.md#advanced)). An empty string reads as no key: the number is judged undivided | — |

`metric` and `query` together, or neither, is `INVALID_RULE`.

A band takes **one** operator. `outside`, `min` and `max` are not operators: a corridor is two server
asserts, or two bands in opposite directions, so each says which end was missed. `sustainedFor` must
be a whole number of the collection's steps, and of the data's own spacing when that differs.
One no longer than a step is the plain reducer.
`"0s"` is refused: a level held for no time is the reducer without a tolerance, so remove
`reduce.over` instead.
`breachBudget` is a fraction: 2% is `0.02`.

These shapes are **warnings, not refusals**, because the verdicts are still correct: a warn band the
fail band already covers; a downward band at or below zero on a reducer other than `slope` (a gauge
can be negative, so the report does not guess); an upward band at or past a catalogue metric's
[ceiling](13-Server-Tab.md#setting-thresholds), unless the assert is a `slope` or a `divideBy` ratio; and a `query` or
a component's `match` with an invisible character at either end. Two server asserts under one id is
a warning too.

### Collection

| Key | | Default |
|---|---|---|
| `collection.coverageFloor` | Fraction 0..1. Below it a series is `INSUFFICIENT` → `LOW_COVERAGE`. Applied by `wir-collect`. A bad value warns and keeps the default | `0.9` |
| `collection.excludeRampUp` | Cut `[start, start + --rampup]` from the judged window | `true` |

### Retired and refused keys

A file that carries one of these keys means something by it, so ignoring the key would change what
is judged. Each is refused with a sentence naming what to write instead.

**In the block**: refused, so nothing it says is judged (exit 1 only under
`webinsight.report.server.failonerror=true`):

| Key | Write instead |
|---|---|
| `sources` | Nothing in the file: where the metrics come from is a `wir-collect` flag (`--prometheus-url`, `--recording`, `--csv`) |
| `gates` | `components`, and a `component` on each server assert |
| `kpis` | `asserts`. Nothing under `kpis` is read |
| `packs` | `packs` is not a key: declare each server assert under `asserts`. The [catalogue](15-Server-Catalogue.md#the-catalogue) is the built-in set of metrics |

**On a component**:

| Key | Write instead |
|---|---|
| `failsBuild` | Nothing: every component can fail the build. To watch a metric without failing it, give it only a warn band |

**In a server assert**: `INVALID_RULE`:

| Key | Write instead |
|---|---|
| `source` | Nothing: name the `component` the assert judges |
| `gate` | `component` |
| `assert` | `"reduce": {"kind": …}` and `warn`/`fail` bands |
| `range` | Two bands in opposite directions, on two server asserts |
| `warnAt` | The warn band you mean, `"warn": {"gt": 640}`. There is no implicit 80% |
| `onMissing` | Nothing: a missing series is NOT_JUDGED with its reason, and the reason decides |
| `onLoadShortfall` | `"requiresLoad": "<load rule id>"` |
| `loadValidity` | Declare load once, as a client rule, and reference it with `requiresLoad` |
| `required` | Nothing: every server assert can fail the build; one to watch gets a warn band and no fail band |
| `scope` | Nothing: one window, the run's, minus the ramp-up with `collection.excludeRampUp` |
| `overlayOn` | Nothing: which chart a series overlays is chosen in the report |
| `sustainedFor`, `breachBudget`, `over` at the top level | Under `reduce.over`: `"reduce": {"kind": "max", "over": {"sustainedFor": "30s"}}` |

### The payload, `server-metrics.json`

Written by `wir-collect` (LF line endings on every OS), read by `-Jwebinsight.report.servermetrics`.
You rarely need to open it, but it is plain JSON:

```json
{
  "schema": "wir-server-metrics/1",
  "runId": "967a1b397b7fb1d2",
  "collectionId": "eab10c9be95a3345",
  "environment": "TEST",
  "collectedAt": "2026-08-22T14:30:47.216764100Z",
  "window": { "start": 1700000000026, "end": 1700000048490, "rampUpEnd": 1700000010026 },
  "sources": [
    { "id": "metrics", "type": "prometheus", "status": "OK", "stepMillis": 15000,
      "clockOffsetMs": 0, "resolvedUrl": "https://prom-test.example/api/v1" }
  ],
  "series": [
    { "source": "metrics", "component": "api", "query": "node_load1{cluster=\"${env.cluster}\"}",
      "resolvedQuery": "node_load1{cluster=\"eu-test\"}", "status": "OK", "coverage": 1.0,
      "instances": [ { "labels": { "instance": "api-1:9100" },
                       "points": [ [1700000000000, 0.42], [1700000015000, 0.57] ] } ] }
  ]
}
```

| Field | |
|---|---|
| `runId` | The run the payload belongs to. Must equal the report's, or the payload is refused |
| `collectionId` | A hash of the source, the resolved queries, the window and `collectedAt`. Stamped into each `serverComponents[]` entry. A page's server-assert drafts are tied to it: a draft made against another collection is not restored ([Drafts and saving](13-Server-Tab.md#drafts-and-saving)) |
| `window` | Epoch milliseconds. `rampUpEnd` only when `--rampup` was given |
| `sources[]` | The collection's one source, `metrics`: `type` is `prometheus`, `file` (`--recording`) or `csv`. `status` is `OK`, `PARTIAL` (some series failed, others came back) or `FAILED` (nothing came back) |
| `sources[].resolvedUrl`, `error` | The URL, recording directory or CSV path. A URL is redacted: userinfo stripped, credential parameters and every secret value masked |
| `sources[].from`, `layout`, `timeZone`, `columns`, `component` | How a CSV was read: `--from`, `--layout wide`, `--time-zone`, `--csv-columns` and `--component`, each only when given |
| `series[]` | One per distinct component and query. `component` says which component it was collected for, and `metric` which catalogue metric it is, when it is one |
| `series[].readAs` | Only on a series read under a tool's name (`--from`): the tool, its name for the metric, the kind of number and the scale applied |
| `series[].status` | `OK`, `PARTIAL` (gaps, above the floor), `INSUFFICIENT` (below it → `LOW_COVERAGE`), `EMPTY` (→ `NO_MATCH`), `FAILED` (→ `SOURCE_FAILED`). A series with no status is read as `NOT_REQUESTED` (→ `INVALID_RULE`): nothing in the collected file asked for it. `wir-collect` always writes one |
| `points` | `[timestamp, value]`, the timestamp a whole epoch millisecond on the JTL's clock |

The report refuses a payload that is missing, unparseable, of another `schema`, of another `runId`,
or carries a series status it does not know. A refused payload is not embedded. A server assert
whose query is not in an accepted payload (the file changed after collection) is `INVALID_RULE`:
re-collect.

---
