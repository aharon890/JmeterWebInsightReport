# Data Model: run data (the common format), version 1.0

The wire format is in [contracts/run-data-format.md](contracts/run-data-format.md). This page lists the entities, their
fields, validation, and what each field means per tool.

## Entities

### Run (`run` record, first line, exactly one)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `formatVersion` | string `"<major>.<minor>"` | yes | Run data version. Version 1.0 readers read `1.x`. |
| `tool.name` | string | yes | The tool that recorded the samples: `jmeter`, `k6`, later `gatling`. |
| `tool.version` | string | no | The tool's version, as the tool reports it. |
| `testName` | string | yes | The report title. It enters the run's identity, as today. |
| `producer.name`, `producer.version` | string | yes | Who wrote the file: `jmeter-web-insight-report` and its version (the plugin, or the k6 adapter). |

### Sample (`sample` record, one or more)

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | when it has children | Unique in the file. |
| `parent` | string | no | The `id` of the transaction it ran inside. |
| `kind` | `request` \| `transaction` | no, default `request` | A transaction is one pass of a group or Transaction Controller. |
| `name` | string | yes | The request or transaction name (JMeter's label). |
| `start` | integer, epoch ms | yes | When it started. |
| `elapsed` | integer ms | yes for a request; optional for a transaction | The tool's own duration (Per-tool meaning). If a transaction leaves it out, the engine uses the sum of its direct children (research R4). |
| `wallTime` | integer ms | no (transaction only) | The transaction's whole duration, think time included. The engine sets idle time = wallTime − elapsed. |
| `idle` | integer ms | no | JMeter's IdleTime, as written. |
| `success` | boolean | yes for a request; optional for a transaction | The tool's own verdict on the sample (Per-tool meaning). A transaction that leaves it out fails when any direct child failed, as JMeter's Transaction Controller does. |
| `code` | string | no | Response code, or the tool's error code. |
| `message` | string | no | Response message. |
| `failureMessage` | string | no | JMeter's first failed assertion's message. |
| `user` | string | no | Who ran it: JMeter's thread name, or `VU <n>` for k6. |
| `iteration` | integer | no | The pass of that user (k6's `iter`). |
| `topLevel` | boolean | no, default: true when `parent` is absent | Whether a listener received it on its own (JMeter's Aggregate Report TOTAL). |
| `samples`, `errors` | integer | no, default 1 and (success ? 0 : 1) | JMeter's statistical rows (SampleCount, ErrorCount). |
| `bytes`, `sentBytes`, `latency`, `connect` | integer | no | Absent means not measured, never 0. |
| `threads` | integer | no | Active threads when it ended (JMeter's allThreads). |
| `url`, `method` | string | no | As recorded. |
| `timings` | object of integer ms | no | k6's `blocked`, `connecting`, `tlsHandshaking`, `sending`, `waiting`, `receiving`. |

### Active users (`users` record, zero or more)

`time` (epoch ms) and `active` (integer): k6's `vus`. JMeter carries it on each sample as `threads`, as today.

### A tool's own measures (zero or more; never judged)

| Record | Fields | k6 source |
|---|---|---|
| `check` | `time`, `name`, `group` (path), `passed`, `user`, `iteration` | each `checks` row |
| `threshold` | `metric` (with its tag filter), `expression`, `passed` | summary.json, `metrics.<m>.thresholds` |
| `counter` | `name`, `value` | summary.json: `dropped_iterations.count`, `iterations.count` |

## Relationships

- A sample's `parent` names a transaction sample in the same file. The engine records it as `recordTransaction(parent
  name, child name)`, so the Samplers tree and "also inside" work as for JMeter.
- The run's window, identity and every figure come from the samples through `SampleFeed` and `LiveStatisticsEngine`.
  No record carries a figure.
- Server data is not in run data. Its payload or CSV is paired with the run by `runId` (research R12).

## Validation

- The `run` record is first, and its major version is one this reader reads. Anything else is refused, and the
  message names the version found and the versions read (FR-011).
- A record that is not valid JSON, or a sample missing a required field, is skipped and counted. The first ones are
  reported with their line numbers, as `JtlParser` reports skipped JTL records. A file with no usable sample is
  refused.
- A `parent` that names no transaction sample in the file is reported, and the sample is fed as top-level with no
  parent.
- Unknown record types and unknown fields are skipped in silence (forward reading, research R9).
- Times are integers in epoch milliseconds. A 10-digit time (seconds) or a 16-digit or longer one (µs/ns) is refused
  by line, as the server CSV refuses them.

## Per-tool meaning (documented in the format docs, FR-010)

| | JMeter | k6 |
|---|---|---|
| `elapsed` of a request | the sample's elapsed time (connect included) | `http_req_duration`: sending + waiting + receiving, without blocked, connecting and TLS |
| `success` of a request | the sample's success flag, an assertion failure included | not `http_req_failed` (k6's expected responses). A failed check does not fail a request. |
| a transaction | Transaction Controller sample, elapsed as written | one pass of a `group()`, elapsed by the engine's rule, wallTime = `group_duration` |
| `start` | the row's timeStamp (start) | the row's time minus `http_req_duration` (k6 stamps at the end) |
| bytes, latency, connect | measured | not measured per request (k6 has `data_*` per iteration, and connecting is a timing part) |
