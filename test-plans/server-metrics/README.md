# E26 — the Server tab's fixture

Committed inputs for the one scenario that is built entirely from them. `run-all-tests.sh` drives
`jmeter -g test-plans/goldens/plain.jtl -o test-results/E26` with the annotations and payload
here, and `server-tab.spec.ts` asserts on the result.

| File | What it is |
|---|---|
| `e26-server.json` | the annotations: two client load rules, four near-band client rules, two components, twelve server asserts (`serverMetrics.asserts`) |
| `server-metrics.json` | the collected payload, produced by `wir-collect --csv` from `e26-metrics.csv` |
| `e26-metrics.csv` | the server data, one row per point: `time,component,metric,instance,value` |

## Why it is all committed

A server report needs three things to agree: a JTL, an annotations file, and a payload collected
against **that JTL's run identity**. Two facts make a live run impossible here:

- `RunIdentity` fingerprints `testName|startTime|durationMillis|sampleCount|errorCount`, so a
  timer-driven run has a new `runId` every execution, and `ServerMetricsPayloadLoader` refuses a
  payload that names a different one. A committed payload can never be paired with a live run.
- Collecting at run time would not rescue it. A committed `file` recording carries fixed epoch
  timestamps (`1700000000…`) that share no milliseconds with a live window, so coverage would be
  0 and every server assert would land `EMPTY` or `INSUFFICIENT`.

So E26 is `-g -o` over a committed JTL. Every number the spec asserts on — actuals, coverage
percentages, instance counts, reason sentences — is a constant.

**Do not pass `-Jwebinsight.report.title` to E26.** The report title *is* the run's test name on
the `-g -o` path and is hashed into `runId`, so setting it silently withholds the whole server
block from the report the spec then opens.

## What it covers

Two components (`kafka` and `db`, both `custom`), and a server assert for each of `PASS`, `WARN`,
`FAIL` and six of the seven `NOT_JUDGED` reasons — `DISABLED`, `NO_MATCH`, `INVALID_RULE`,
`SOURCE_FAILED`, `LOW_COVERAGE`, `LOAD_NOT_ACHIEVED`. Only `NO_VALUE` is absent.

Three of the series exist to exercise the *tab* rather than the evaluator:

- `lag` spikes over its fail band for a stretch **in the middle** of the window, so the chart's
  `markArea` has a start and an end to get right. A shaded region running to either edge means the
  breach detection lost the recovery.
- `many` carries **21** instances against the payload writer's cap of 20, so the tab has to say
  "and 1 more instance(s) not drawn" rather than showing a subset as the whole.
- `up` has one row and it is **unreadable on purpose** — a time with no zone — so its series is
  `FAILED`, its server assert lands `SOURCE_FAILED`, and the source reads `PARTIAL`. It used to
  be a second source on `127.0.0.1:9`; one collection has one backend now
  (plans/server-metrics-backends.md §8a, §8b), so a failure has to be a series' rather than a
  source's.

A series is its component and its query, so `cpu` and `lag`, which asserts on both components
ask for, are written once per component, with the same values.

Four client rules sit near their bands, for `client-numbers.spec.ts`: a mean whose display
rounding would put it on or across the band it is judged against. Three are within a hundredth of
theirs; the whole-run mean is 0.09 under `gt 235`, which whole-ms rounding puts on it.

| Rule | Mean (Java's double) | Band | Status |
|---|---|---|---|
| `checkout-mean-on-band` | POST /api/checkout, 373.8625 | `fail gt 373.86` | FAIL |
| `run-mean-whole-ms` | the whole run, 234.91166666666666 | `fail gt 235` | PASS |
| `search-mean-gte-on-band` | GET /api/search, 211.405 | `fail gte 211.41` | PASS |
| `products-mean-warns` | GET /api/products, 119.4675 | `warn gt 119.46`, `fail gt 1000` | WARN |

The spec holds every Asserts-tab surface to the digits rule (an actual is never written on a band
it is not on, or across one; a band is written as declared) and the Budget bar to Java's
`attainment.breached`. `products-mean-warns` fails at 1000 rather than somewhere unreachable because
a chart widens its axis to the largest band it draws, and a fail line at 100000 would flatten every
mean onto the floor. No server assert names these rules, so adding them moved neither `runId` nor
any `collectionId`: the committed payload is accepted as it was, with nothing re-collected.

## Regenerating

Only needed when `e26-server.json`'s `serverMetrics` block or `e26-metrics.csv` changes. Its client
`assertions` are not collected, and move neither `runId` nor any `collectionId`.

A re-collection moves pins in `test-plans/playwright/tests/server-tab.spec.ts` beside the payload:
the `collectionId` and the raw `collectedAt` title (both hashed from or stamped at collection time),
the three per-zone collected-at strings in the "judged window in <zone>" table, and the SOURCE_FAILED
sentence of line 1328's zone-less time. That sentence was reworded in 3.1.54 to name
`--time-zone` ("... or write epoch milliseconds, or give the file's zone with --time-zone"); the
committed payload still carries the older wording, which the spec pins as it is.

Set `JMETER_HOME` to the JMeter that `build-and-deploy.sh` deployed this tree's JAR into (it prints
the path). `wir-collect.sh` tries `$JMETER_HOME`, then `jmeter` on `PATH`, and the checkout's
`apache-jmeter-*` only last, so on a machine with another JMeter on `PATH` it would run that
JMeter's plugin JAR, an older collector, and that is the payload you would commit.

```bash
JMETER_HOME=/path/to/the/deployed/apache-jmeter ./scripts/wir-collect.sh \
  --jtl         test-plans/goldens/plain.jtl \
  --annotations test-plans/server-metrics/e26-server.json \
  --csv         test-plans/server-metrics/e26-metrics.csv \
  --step        1s \
  --out         test-plans/server-metrics/server-metrics.json \
  --no-wait
```

It exits 0. Several series are deliberately not judgeable, and its summary line says how many
("N not judgeable"). The payload records each status and the report decides what it means (D27), so
the exit code says only that the file was written. It used to exit 2 here.

`nothing` has no rows on purpose — a query the file declares and the CSV does not answer is what
makes the `NO_MATCH` server assert reachable.

This whole directory is in the fixture stamp (`_fixture_stamp_files` in `scripts/lib/common.sh`),
so editing anything here invalidates every Playwright fixture and forces a regeneration. That is
deliberate: these files decide what E26's report looks like, exactly as `src/main` does.
