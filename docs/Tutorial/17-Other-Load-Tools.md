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

**The k6 tab** comes after Server, on k6 runs only. It shows what k6's own end-of-test summary shows, organised so the
first screen answers "did k6 see a problem, and where":

| Section | What it holds |
|---|---|
| Strip | One line: how many thresholds and checks failed out of how many, naming the failed ones with what k6 measured and how far past the limit (`Thresholds: 1 of 50 failed: Login p(95)<=600 (p(95) = 779.72 ms, 179.72 ms over (30%))`). |
| Counters | `iterations`, `dropped_iterations` (0 when k6 dropped none: k6 writes it only when it drops one) and `vus_max` (the VUs k6 allocated) from `summary.json`; "not measured" without it. Max VUs on the Summary tab is the most VUs active at once, k6's `vus`. |
| Thresholds | Every threshold as k6 judged it, grouped by metric as k6's summary groups them (or by request, condition or result). Each row gives the metric with its tags, the expression, the value k6 measured for the statistic the expression names, in k6's units (`p(95) = 779.72 ms`, a rate as a percentage), and, when it failed, how far past its limit (`179.72 ms over (30%)`). A group holding a failure opens; the others show their counts. Thresholds that hold for any run (`max>=0`, `count>=0`, `rate>=0`, `value>=0`, which some tests add only so that k6 keeps a request's numbers) fold into one line per group, still counted; a failed one never folds. A request's name opens its row on the Requests tab, with its detail panel. |
| HTTP timings | One row per request, under its group as on the Requests tab, with an "every request" row first: the six parts (blocked, connecting, TLS, sending, waiting, receiving) and k6's request duration (sending + waiting + receiving), at the statistic chosen (avg by default; min, med, max, p(90), p(95), and p(99) when the run's summary lists it), and a bar of the average request from start to end: blocked, with connecting and TLS inside it on a new connection, then sending, waiting and receiving. A row opens to every statistic of every part. |
| Checks | Under their group path, failed first, with passes, fails and a pass-rate bar. |
| Group duration | Each group's passes on their wall time, think time included (`group_duration`), under its parent group. |

Every section is a table with the report's one toolbar (search, status chips, Group by, Expand all and Collapse all,
Columns, × and "Show N hidden", CSV), as every table of the report has. One search narrows every section at once. The
grouping, the statistic and the rest of the view are remembered for the report in this browser.

The filter bar's request checkboxes show above the tab, its Samplers row only (its chart controls and time range
narrow no k6 number). Untick a request and its thresholds, timing, check and group rows leave the tab, as its rows
leave the Requests and Errors tabs; the whole-run thresholds, the "every request" row and the counters stay. The
checkboxes are one choice for the report: set here, they hold on every tab. See [the Filter Bar](08-Filter-Bar.md).

A percentile is computed the Aggregate Report's way, the value some request really had, where k6's own summary
interpolates between two values, so the two can differ by less than one step. A threshold's measured value is k6's own,
from its summary (run data 1.1). Run data written by 4.5.x has none, and the cell reads "not recorded". An older k6 file
with whole-second times is said in the Notes tab.

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
