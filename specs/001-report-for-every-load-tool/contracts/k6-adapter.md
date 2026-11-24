# Contract: k6's files to run data

Reference implementation: the research's converter `k6_to_jtl_v3.py`, in perf hub's
`tmp/k6-web-insight-report/shapes/`. On the shapes test it matched JMeter's Aggregate Report in 198 of 198 referee
checks. The Java adapter keeps its rules, writes run data instead of a JTL, and leaves the transaction arithmetic to
the engine (research R4).

## Inputs

- **results CSV.** One row per metric sample. Columns are found by name: `metric_name`, `timestamp`, `metric_value`,
  the tags, and `metadata` (`vu=..&iter=..`, in any order).
  - `timestamp` must be `unix_milli`. A 10-digit time means whole seconds: an older file, read as such.
  - k6 stamps a request's rows at its end.
- **summary.json** (optional). `metrics.<metric>{<tags>}.thresholds.<expression>.ok`, `metrics.dropped_iterations`,
  `metrics.iterations`, and `root_group` (checks with passes and fails, used only to cross-check the check rows).

## Requests

k6 writes a request's rows together, one after another. The adapter joins them by adjacency: consecutive rows with the
same (timestamp, name, method, url, vu, iter) form one request, and a second `http_req_duration` for the same key starts
a new one. So two `http.batch` requests ending in the same millisecond, and an older file with no vu/iter and whole
seconds, still split correctly. The window is one request: nothing older is held.

| Run data field | From |
|---|---|
| `name` | tag `name` (k6 defaults it to the URL) |
| `elapsed` | `http_req_duration` value |
| `start` | timestamp − elapsed |
| `success` | `http_req_failed` == 0 |
| `code` | tag `status`; when it is 0, `error_code` (for example `1212`) |
| `message` | tag `error` |
| `user`, `iteration` | `VU <vu>`, `iter` from `metadata` |
| `method`, `url` | tags |
| `timings` | `http_req_blocked`, `_connecting`, `_tls_handshaking`, `_sending`, `_waiting`, `_receiving`, to the hundredth of a ms |
| `parent` | the current pass of the innermost group in the `group` tag path (`::A::B`) of that vu and iter |

- Each redirect hop is its own request, as k6 records it.
- Setup and teardown rows, which have no `vu` and an empty scenario, are kept as requests of user `setup`/`teardown`
  with no group.
- Requests inside a group pass have `topLevel` false, and requests outside any group are top-level. That is a
  Transaction Controller with "Generate parent sample" on, the mode of petstore's checkout JMeter plan, so the whole-run
  figures match the JMeter twin's.
- The `run` record gets `tool` {`k6`, the version from summary.json's state, when present}, and `notMeasured`
  `bytes`, `sentBytes`, `latency`, `connect`. An older file (10-digit times, no `metadata`) also gets
  `timeResolution` `s` and adds `user`, `iteration` to `notMeasured`, and no group passes are built.

## Group passes (transactions)

- A pass is opened by its first request, per vu, iter and group path, and closed by its `group_duration` row (stamped
  at the group's end).
- The pass becomes a `transaction` sample with `wallTime` = `group_duration`, `start` = end − wallTime, no `elapsed`
  and no `success` (the engine's rules, research R4), and a parent for nested groups.
- A pass with no request (only think time) is kept, with no children: elapsed 0.

## The tool's own records

| Record | From |
|---|---|
| `users` | `vus` rows |
| `check` | `checks` rows: tag `check`, value 1 or 0, tag `group` |
| `threshold` | summary.json, every metric with thresholds: `metric` with its tag filter, `expression`, `passed` = `ok` |
| `counter` | summary.json: `dropped_iterations`, `iterations`, `vus_max` |

`data_sent`, `data_received` and `iteration_duration` are per iteration, not per request. They are not written, and
the report says "not measured" for bytes.
