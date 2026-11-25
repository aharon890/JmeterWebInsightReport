# Reports for k6 and other load tools

The same report, with the same numbers, verdicts and Server tab, for a run of any load tool. JMeter keeps its
plugin. A k6 run gets its report from the plugin JAR with **no JMeter at all**: `build-report` reads k6's own files.
A tool that writes **run data**, the common format below, gets it the same way.

Every number follows JMeter's Aggregate Report rules, whatever tool recorded the samples. A k6 run and its JMeter twin
are therefore measured the same way. A k6 run's p95 can differ slightly from k6's own end-of-test summary, which uses
interpolation where this report counts exactly.

## The command: build-report

```bash
java -jar jmeter-web-insight-report-<version>.jar build-report --run-data run-data.jsonl.gz --out report/
java -jar jmeter-web-insight-report-<version>.jar build-report --from k6 \
     --k6-results results.csv.gz --k6-summary summary.json --out report/
```

| Option | What it does |
|---|---|
| `--run-data FILE` | Builds from run data (`.jsonl` or `.jsonl.gz`), of any tool |
| `--from k6 --k6-results FILE` | Builds from k6's `--out csv` file (`.csv` or `.csv.gz`) |
| `--k6-summary FILE` | k6's end-of-test summary (handleSummary's data): thresholds and counters |
| `--write-run-data` | With `--from k6`, also keeps the run data beside the report |
| `--out FOLDER` | Where the report goes; created if missing |
| `--annotations FILE` | The test's asserts (else `report-annotations.json` in `--out`, as on `jmeter -g`) |
| `--servermetrics FILE` | Server data: a payload `wir-collect` wrote, or a CSV read in-process |
| `--baseline FILE` | The Compare tab's baseline, on JMeter runs only |
| `--property KEY=VALUE` | Any `webinsight.*` setting; repeatable; wins over `--properties` |
| `--properties FILE` | A properties file of `webinsight.*` settings |

The outputs and the exit codes are those of `jmeter -g`:
- `0` when the report was written, whatever its verdict.
- `1` when nothing could be written.
- `1` also under `webinsight.report.failonerror` (default `true`) for a configuration error,
  `webinsight.report.server.failonerror` for a server one, and `webinsight.report.failonassert` for a FAIL.

`build-report --help` lists every option.

**The title is the run's test name.** The run's identity (`runId`) hashes it:
- With `--run-data`, the file's own test name is the title, and a different `webinsight.report.title` is refused.
- With `--from k6`, `webinsight.report.title` names the run (else the default title).

**Compare** for runs of other tools, and across tools, comes later. A baseline on a k6 run is left out with a sentence.

### With no JMeter installed

Unzip `jmeter-web-insight-report-<version>-standalone.zip`: the plugin JAR, and a `lib/` holding the libraries it
takes from JMeter's `lib/` inside JMeter (Jackson, Commons Math 3, SLF4J). Run the JAR from that folder:
`java -jar jmeter-web-insight-report-<version>.jar build-report ...`. It needs a Java 21 runtime, and nothing else.

## k6

Give k6 these two settings, so every request carries its millisecond time, its virtual user and its pass:

```bash
export K6_CSV_TIME_FORMAT=unix_milli
export K6_SYSTEM_TAGS=proto,subproto,status,method,url,name,group,check,error,error_code,tls_version,scenario,service,expected_response,vu,iter
k6 run --out csv=results.csv.gz test.js
```

An older file, with whole-second times or no `vu` and `iter`, still builds a report. It gives request rows only, with
no groups, and `build-report` says so on the console.

**What each k6 measure becomes:**

| Measure | In the report |
|---|---|
| A request's time | `http_req_duration`: sending, waiting and receiving. Connecting and TLS are not included, unlike JMeter's elapsed. |
| A failed request | `http_req_failed` (k6's expected responses). A failed check does not fail a request. |
| A `group()` pass | A transaction: its time is the sum of its requests', as JMeter's Transaction Controller counts it with "Generate parent sample" on. k6's own group duration, think time included, is kept as the pass's wall time. |
| Bytes, latency, connect | Not measured per request by k6. Their cells read "not measured", never 0, and the Bytes and Connect/Latency charts say so. |
| Checks and thresholds | Shown on the k6 tab, each with k6's own result. They never decide the verdict: the test's asserts do. |

**k6's words.** A k6 run's report uses k6's words: Requests (not Samplers), VU (not Thread), Max VUs, and Group (not
Transaction). The header names k6, and its version when k6's files give one. Two things keep JMeter's name: the
product's own name, and the sentence saying the numbers follow JMeter's Aggregate Report rules. A JMeter run's report
reads exactly as before.

**The k6 tab** comes after Server, on k6 runs only. It shows what k6's own end-of-test summary shows:

| Section | What it holds |
|---|---|
| Counters | `iterations`, `dropped_iterations` and `vus_max` (the VUs k6 allocated) from `summary.json`; "not measured" without it. Max VUs on the Summary tab is the most VUs active at once, k6's `vus`. |
| Thresholds | Every threshold, with its metric and expression, as k6 judged it: the failed ones first, then by metric. |
| Checks | Passes, fails and pass rate per group and check. |
| Group duration | Each group's passes on their wall time, think time included (`group_duration`). |
| HTTP timings | The six parts (`http_req_blocked`, `_connecting`, `_tls_handshaking`, `_sending`, `_waiting`, `_receiving`) over every request and per request, to the hundredth of a millisecond. |

Its columns are k6's: avg, min, med, max, p(90), p(95). A percentile is computed the Aggregate Report's way, the value
some request really had, where k6's own summary interpolates between two values, so the two can differ by less than one
step. An older k6 file with whole-second times is said in the Notes tab.

## Server data for any tool

The Server tab works the same for every tool: the collector takes the run's window, identity and test name from the
run.

```bash
java -jar jmeter-web-insight-report-<version>.jar --run-data run-data.jsonl.gz \
     --annotations report-annotations.json --out server-metrics.json --csv server.csv
java -jar jmeter-web-insight-report-<version>.jar --k6-results results.csv.gz --test-name "<the report title>" \
     --annotations report-annotations.json --out server-metrics.json --csv server.csv
```

`build-report --servermetrics server.csv` also collects a CSV in-process, as `jmeter -g` does.

## Run data, the common format

The whole format, with a run written by hand: [Run data](/Tutorial/18-Run-Data.md).

One JSON record per line (JSON Lines), UTF-8, gzip when the name ends in `.gz`. The first line is the `run` record.
Every other record comes in any order:

```json
{"type":"run","formatVersion":"1.0","tool":{"name":"k6","version":"2.4.0"},"testName":"checkout (k6)","producer":{"name":"jmeter-web-insight-report"}}
{"type":"sample","id":"t1","kind":"transaction","name":"Complete Checkout Flow","start":1700000000000,"wallTime":2350}
{"type":"sample","parent":"t1","name":"Login","start":1700000000000,"elapsed":41,"success":true,"code":"200"}
{"type":"users","time":1700000000000,"active":1}
```

- **A producer writes samples, never figures.** A transaction that gives no `elapsed` or `success` takes them from its
  children: the sum of their times, and a failure when any of them failed. Every count, percentile, rate and the run's
  identity are the report's own.
- **Versions.** This release reads `1.x` and writes `1.1` (4.5.x wrote `1.0`; 1.1 adds a threshold's measured
  value). A later minor version only adds fields or record types, which this one skips. A major version it does not read is refused with a sentence naming both.
- **Server data** is not in run data: its payload or CSV is paired with the run by `runId`, as for a JMeter run.

**JMeter runs** write their run data beside the report with `-Jwebinsight.report.rundata=true` (off by default), as
`<report name>-run-data.jsonl.gz`. A report rebuilt from it with `build-report` is the original one. The file carries
the failed samples' response bodies exactly as the report does, cut at `webinsight.error.body.maxsize`.
