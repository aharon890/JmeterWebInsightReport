# Run data: the report for any load tool

Run data is the report's common format. A load tool's run, written as run data, gets the same report a JMeter run gets:
the same tabs, the same numbers (JMeter's Aggregate Report rules), the same asserts and the same Server tab. A tool needs
no change to the report: its producer writes samples, and `build-report` does the rest.

```bash
java -jar jmeter-web-insight-report-<version>.jar build-report --run-data run-data.jsonl.gz --out report/
```

This page is the format. [k6 and other load tools](/Tutorial/17-Other-Load-Tools.md) shows the commands, and how k6's
own files are read with no run data at all.

## The file

- JSON Lines: one JSON object per line, UTF-8 with no BOM, LF line ends. A name ending in `.gz` is gzip-compressed.
- The first line is the `run` record. Every other record can come in any order, so a producer can append as samples
  arrive.
- Every record has a `type`. A reader skips a type or a field it does not know.
- The JSON Schema of one record is
  `https://aharon890.github.io/JmeterWebInsightReport/schema/run-data.schema.json`, also attached to each release.
  Check each line of a producer's output against it.

## A whole run, by hand

This is a run of an imaginary tool, `demo`: two users each pass once through a `Checkout` transaction of two
requests, one `Pay` fails, then each calls `Health` once. It is the fixture `test-plans/run-data/D1`, built from this
page alone.

```json
{"type":"run","formatVersion":"1.0","tool":{"name":"demo","version":"0.1"},"testName":"D1","producer":{"name":"demo-producer","version":"0.1"},"notMeasured":["bytes","sentBytes","latency","connect"]}
{"type":"users","time":1795452400000,"active":2}
{"type":"sample","id":"t1","kind":"transaction","name":"Checkout","start":1795452400000,"wallTime":300,"user":"user 1","iteration":0}
{"type":"sample","parent":"t1","name":"Login","start":1795452400000,"elapsed":40,"success":true,"code":"200","message":"OK","user":"user 1","iteration":0}
{"type":"sample","parent":"t1","name":"Pay","start":1795452400100,"elapsed":60,"success":false,"code":"500","message":"Internal Server Error","user":"user 1","iteration":0}
{"type":"sample","name":"Health","start":1795452401000,"elapsed":5,"success":true,"code":"200","message":"OK","user":"user 1"}
{"type":"check","time":1795452400160,"name":"pay is 200","group":"::Checkout","passed":false,"user":"user 1","iteration":0}
{"type":"threshold","metric":"checkout_time","expression":"p(95)<500","passed":true}
{"type":"counter","name":"iterations","value":2}
```

(The second user's lines are the same with its own times.) `Checkout` gives no `elapsed` and no `success`: the report
takes the sum of its requests' times (100 ms) and fails it because `Pay` failed, as JMeter's Transaction Controller
does. Its idle time is its wall time less that, 200 ms. It is counted once in the whole run, and its requests are its
breakdown.

The report names `demo` and its version in the header, uses neutral words (Requests, User, Max Users), and, because the
run carries a check, a threshold and a counter, adds a `demo` tab after Server. Bytes, latency and connect time read
"not measured": the run record says `demo` does not record them.

## The records

### `run`: the first line, exactly one

| Field | Required | Meaning |
|---|---|---|
| `formatVersion` | yes | `"1.1"` from Web Insight Report 4.6.0 (`"1.0"` before). A 1.x reader reads any 1.x file, and skips a field it does not know. |
| `tool.name` | yes | The tool, lower case: `jmeter`, `k6`, `demo`. It names the tool's tab and decides the page's words. |
| `tool.version` | no | Shown in the header beside the tool's name. |
| `testName` | yes | The report title. The run's identity, `runId`, hashes it, so a different `webinsight.report.title` is refused. |
| `producer.name`, `producer.version` | yes, no | Who wrote the file. |
| `timeResolution` | no | `s` when the tool's file had whole-second times; the report says so in its Notes. Default `ms`. |
| `notMeasured` | no | Sample fields the tool does not record: `bytes`, `sentBytes`, `latency`, `connect`, `user`, `iteration`. Only these read "not measured"; a field simply left out of a sample keeps JMeter's default. |
| `trendStatistics` | no | Since 1.1. The statistics the tool's summary lists for a trend, such as k6's `options.summaryTrendStats`: `["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"]`. The tool's tab offers p(99) only when it is listed. |

### `sample`: one request or transaction

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | The request or transaction name (JMeter's label). |
| `start` | yes | When it started, in epoch milliseconds (13 digits today). Seconds and micro- or nanoseconds are refused. |
| `elapsed` | for a request | The tool's own duration, ms. A transaction may leave it out: it takes the sum of its direct children's. |
| `success` | for a request | The tool's own verdict. A transaction may leave it out: it fails when a child failed. |
| `id`, `parent` | no | `parent` names the `id` of the sample it ran inside. A `parent` that names nothing is reported, and the sample is counted at the top level. |
| `link` | no | `transaction` (default) or `subresult`: a transaction's child, or a request's own sub-result (a redirect). |
| `kind` | no | `request` (default) or `transaction`. A transaction is not a sample in the Errors tab and has no response code. |
| `wallTime`, `idle` | no | A transaction's whole duration, think time included, or its idle time directly. |
| `code`, `message`, `failureMessage` | no | Response code and message; JMeter's first failed assertion. |
| `user`, `iteration` | no | Who ran it and which pass of that user. |
| `topLevel` | no | Whether a listener in the test plan received it on its own. Default: true when it has no `parent`. |
| `samples`, `errors` | no | JMeter's statistical rows. Default 1, and 1 error for a failed sample. |
| `bytes`, `sentBytes`, `latency`, `connect`, `threads`, `url`, `method` | no | As JMeter records them. |
| `timings` | no | The tool's timing parts in ms, to the hundredth: `blocked`, `connecting`, `tlsHandshaking`, `sending`, `waiting`, `receiving`. Shown on the tool's tab. |
| `responseBody` | no | A failed sample's body, exactly as the report carries it: failed samples only, cut at `webinsight.error.body.maxsize`. |

### The tool's own records

| Record | Fields | Where it shows |
|---|---|---|
| `users` | `time`, `active` | The active users series and the Max Users (Max VUs) tile. JMeter carries this as `threads` on each sample. |
| `check` | `time`, `name`, `group`, `passed`, `user`, `iteration` | The tool's tab: passes and fails per group and check. |
| `threshold` | `metric`, `expression`, `passed`; since 1.1, optional: `statistic`, `measuredValue`, `metricType`, `metricContains` | The tool's tab, as the tool judged it. `statistic` is the one the expression names (`p(95)`), `measuredValue` the value the tool judged it on (k6's summary), `metricType` `trend`, `rate`, `counter` or `gauge`, and `metricContains` `time`, `data` or `default`. A value the tool did not give is left out, never written as 0. |
| `counter` | `name`, `value` | The tool's tab. |

None of them is ever a verdict. The report's verdict is the test's asserts, whatever the tool's own checks said.

## What each field means per tool

| | JMeter | k6 |
|---|---|---|
| A request's `elapsed` | The sample's elapsed time, connect included | `http_req_duration`: sending + waiting + receiving, without blocked, connecting and TLS |
| A request's `success` | The sample's success flag, an assertion failure included | Not `http_req_failed` (k6's expected responses). A failed check does not fail a request. |
| A transaction | A Transaction Controller's sample, elapsed as written | One pass of a `group()`, as a Transaction Controller with "Generate parent sample" on and timers off. Its elapsed is the sum of its requests'; its wall time is `group_duration`. |
| `start` | The row's timestamp | The row's time less `http_req_duration` (k6 stamps a request at its end) |
| Bytes, latency, connect | Measured | Not measured per request (`notMeasured`) |

## Versions

This release writes run data `1.1` and reads `1.x`. Web Insight Report 4.5.x wrote `1.0`; 1.1 added a threshold's
`statistic`, `measuredValue`, `metricType` and `metricContains`, and the run record's `trendStatistics`, all optional.
A minor version only adds optional fields or record types, which an older 1.x reader skips: 4.5.x reads a 1.1 file and
shows its thresholds without their measured values. A major version (2.0) is refused with a sentence naming the version found and the versions
read; no numbers are shown.

## Reading order and memory

`build-report` reads the file twice. The first pass indexes every sample with an `id`, and adds up each parent's
children; the second feeds every record, in file order, to the same statistics engine the JMeter paths use. Memory grows
with the samples that have an `id`, not with all of them. A `.gz` file is decompressed twice.

## Server data

Server data is not in run data. The collector takes the run's window, identity and test name from the run data, and the
report pairs the collected data with the run by its `runId`, as for a JMeter run:

```bash
java -jar jmeter-web-insight-report-<version>.jar --run-data run-data.jsonl.gz \
     --annotations report-annotations.json --out server-metrics.json --csv server.csv
java -jar jmeter-web-insight-report-<version>.jar build-report --run-data run-data.jsonl.gz \
     --annotations report-annotations.json --servermetrics server-metrics.json --out report/
```

`build-report --servermetrics server.csv` also collects a CSV in-process.

## JMeter runs as run data

`-Jwebinsight.report.rundata=true` (off by default) writes a JMeter run's run data beside its report, as
`<report name>-run-data.jsonl.gz`, from the listener or from `jmeter -g`. A report built from it with `build-report` is
the original report: the same CI/CD JSON, `runId` included, and the same Errors tab, response bodies included.

## Holding a new producer to JMeter's numbers

The project's regression step R08 (`test-plans/scripts/run-run-data.sh`) holds every producer to JMeter's own Aggregate
Report. To add a producer's run:

1. Put its run data in `test-plans/run-data/<id>/run-data.jsonl`, with a JMeter-format copy of the same samples:
   `results.jtl` (every row) and `in-test-plan.jtl` (the rows a listener in the plan would receive: no transaction's
   children).
2. Run `./test-plans/scripts/run-run-data.sh --accept`, read every number of `expected/<id>.json` against the copy,
   and commit it.
3. From then on R08 builds the report with `build-report --run-data`, holds each row's text to the Aggregate Report
   table JMeter's own classes draw from the copy (the fields `notMeasured` names read "not measured"), and holds the
   CI/CD JSON to `expected/`.
