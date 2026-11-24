# Data Model: run data (the common format), version 1.0

The wire format is in [contracts/run-data-format.md](contracts/run-data-format.md). This page lists the entities, their
fields, validation, and what each field means per tool. Revised on 2026-11-23 after `/speckit-analyze`.

## Entities

### Run (`run` record, first line, exactly one)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `formatVersion` | string `"<major>.<minor>"` | yes | Run data version. Version 1.0 readers read `1.x`. |
| `tool.name` | string | yes | The tool that recorded the samples: `jmeter`, `k6`, later `gatling`. Lower case. It names the tool's tab. |
| `tool.version` | string | no | The tool's version, as the tool reports it. |
| `testName` | string | yes | The report title. It enters the run's identity, as today. With `--run-data` it is the title, and a `webinsight.report.title` property that differs is refused with a sentence naming both, because it would change `runId`. With `--from k6` it is the `webinsight.report.title` property, else WIR's default title, as on `-g`. |
| `producer.name`, `producer.version` | string | yes | Who wrote the file: `jmeter-web-insight-report` and its version (the plugin, or the k6 adapter). |
| `timeResolution` | `ms` \| `s` | no, default `ms` | `s` when the tool's file had whole seconds (an older k6 file). The report says so in its Notes. |
| `notMeasured` | list of Sample field names | no, default empty | Fields the tool does not record, for example `bytes`, `sentBytes`, `latency`, `connect` for k6, and `user`, `iteration` for an older k6 file. Only these read "not measured" on the page. A JMeter JTL with a missing column keeps today's defaults (0 or -1). |

### Sample (`sample` record, one or more)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | when another sample names it as parent | Unique in the file. |
| `parent` | string | no | The `id` of the sample it ran inside: a transaction, or the request whose sub-result it is. |
| `link` | `transaction` \| `subresult` | no, default `transaction` | How it nests under its parent, as the engine's `recordTransaction(parent, child, transaction)` takes it. A JMeter request's sub-results (a redirect's `Login-0`) are `subresult`. |
| `kind` | `request` \| `transaction` | no, default `request` | How it is counted. `transaction` is counted as a transaction row: not in the Errors tab's sample count, no response code. On JMeter it is `transaction` exactly when the row carries "Number of samples in transaction". A controller cut off by the test's end is `request`, and can still be a `parent` with link `transaction` (TransactionRows' rule). On other tools `kind` decides alone. |
| `name` | string | yes | The request or transaction name (JMeter's label). |
| `start` | integer, epoch ms | yes | When it started. |
| `elapsed` | integer ms | yes for a request; optional for a transaction | The tool's own duration (Per-tool meaning). If a transaction leaves it out, the engine uses the sum of its direct children with link `transaction` (research R4). |
| `idle` | integer ms | no | JMeter's IdleTime, as written. When both `idle` and `wallTime` are given, `idle` wins. |
| `wallTime` | integer ms | no (transaction only) | The transaction's whole duration, think time included. Without `idle`, the engine sets idle time = wallTime − elapsed. |
| `success` | boolean | yes for a request; optional for a transaction | The tool's own verdict on the sample (Per-tool meaning). A transaction that leaves it out fails when any direct child failed, as JMeter's Transaction Controller does. |
| `code` | string | no | Response code, or the tool's error code. |
| `message` | string | no | Response message. |
| `failureMessage` | string | no | JMeter's first failed assertion's message (the error type, E1). |
| `user` | string | no | Who ran it: JMeter's thread name, or `VU <n>` for k6. |
| `iteration` | integer | no | The pass of that user (k6's `iter`). |
| `topLevel` | boolean | no, default: true when `parent` is absent, else false | Whether a listener received it on its own (JMeter's Aggregate Report TOTAL). JMeter writes it explicitly. |
| `samples`, `errors` | integer | no, default 1 and (success ? 0 : 1) | JMeter's statistical rows (SampleCount, ErrorCount). |
| `bytes`, `sentBytes`, `latency`, `connect` | integer | no | Absent: the JMeter feeders keep today's defaults, and the page says "not measured" only for fields in `Run.notMeasured`. |
| `threads` | integer | no | Active threads when it ended (JMeter's allThreads). Fed to the engine only when above 0, as the JTL path does today. |
| `url`, `method` | string | no | As recorded. |
| `timings` | object of numbers, ms to the hundredth | no | k6's `blocked`, `connecting`, `tlsHandshaking`, `sending`, `waiting`, `receiving`. Kept to 0.01 ms, because most of them are below a millisecond: whole milliseconds read 0 for four of the six on K1 (revised 2026-11-23, US3). |
| `responseBody` | string | no | A failed sample's response body, exactly as the report carries it: failed samples only, cut at `webinsight.error.body.maxsize` (decision 11). Only JMeter's listener path has bodies: a JTL and k6 have none. |

**Response bodies (decision 11):** run data carries exactly the bodies the report carries, so a report rebuilt from a
listener run's file shows the same Errors tab, bodies included.

### Active users (`users` record, zero or more)

`time` (epoch ms) and `active` (integer): k6's `vus`. Each one is fed to the engine's active threads
(`updateActiveThreads(active, time)`), so Max VUs and the active-users series work as for JMeter. JMeter carries this
as `Sample.threads`.

### A tool's own measures (zero or more; never judged)

| Record | Fields | k6 source |
|---|---|---|
| `check` | `time`, `name`, `group` (path), `passed`, `user`, `iteration` | each `checks` row |
| `threshold` | `metric` (with its tag filter), `expression`, `passed` | summary.json, `metrics.<m>.thresholds` |
| `counter` | `name`, `value` | summary.json: `dropped_iterations.count`, `iterations.count`, `vus_max.max` |

## Relationships

- A sample's `parent` names any sample with that `id`. The engine gets
  `recordTransaction(parent name, child name, link == transaction)`, so the Samplers tree and "also inside" work as
  for JMeter.
- The run's window, identity and every figure come from the samples through `SampleFeed` and `LiveStatisticsEngine`.
  No record carries a figure.
- Server data is not in run data. Its payload or CSV is paired with the run by `runId` (research R12).

## Reading order and memory (U1)

The reader reads the file twice, as `JtlParser` already reads a JTL once for transaction names before parsing it.

1. **Pass 1** indexes every sample with an `id`: its name, kind and link. For each transaction without `elapsed` or
   `success`, it also keeps the sum of its direct children's elapsed times and whether any child failed. Nested groups
   resolve bottom-up at the end of the pass.
2. **Pass 2** feeds every record in file order through `SampleFeed`.

Memory grows with the number of samples that have an `id`, not with all samples. A `.gz` file is decompressed twice.

## Validation

- The `run` record is first, and its major version is one this reader reads. Anything else is refused, and the
  message names the version found and the versions read (FR-011).
- A record that is not valid JSON, or a sample missing a required field, is skipped and counted. The first ones are
  reported with their line numbers, as `JtlParser` reports skipped JTL records. A file with no usable sample is
  refused.
- A `parent` that names no sample in the file is reported, and the sample is fed as top-level with no parent.
- Unknown record types and unknown fields are skipped in silence (forward reading, research R9).
- Times are integers in epoch milliseconds. A 10-digit time (seconds) or a 16-digit or longer one (µs/ns) is refused
  by line, as the server CSV refuses them.

## Per-tool meaning (documented in the format docs, FR-010)

| | JMeter | k6 |
|---|---|---|
| `elapsed` of a request | the sample's elapsed time (connect included) | `http_req_duration`: sending + waiting + receiving, without blocked, connecting and TLS |
| `success` of a request | the sample's success flag, an assertion failure included | not `http_req_failed` (k6's expected responses). A failed check does not fail a request. |
| a transaction | Transaction Controller sample, elapsed as written | one pass of a `group()`, modelled as a Transaction Controller with "Generate parent sample" on and timers off (as petstore's checkout plan). Elapsed comes from the engine's rule, and wallTime = `group_duration`. |
| `start` | the row's timeStamp (start) | the row's time minus `http_req_duration` (k6 stamps at the end) |
| bytes, latency, connect | measured | not measured per request (`Run.notMeasured`). k6 has `data_*` per iteration, and connecting is a timing part. |
