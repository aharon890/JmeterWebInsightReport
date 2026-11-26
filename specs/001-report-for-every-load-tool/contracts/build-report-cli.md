# Contract: the JMeter-free commands

Both run from the plugin JAR with `java -jar`. The launcher finds Jackson, SLF4J and Commons Math 3 in this order:

1. the JAR's own `lib/ext`;
2. a `lib/` folder beside the JAR (the standalone bundle), so the bundle works even with a wrong `JMETER_HOME` or
   another `jmeter` on `PATH`;
3. `JMETER_HOME`;
4. `jmeter` on `PATH`.

`--help` on each command lists every option and the exit codes, in the same change that adds them (Principle VI).

## build-report

```text
java -jar jmeter-web-insight-report-<v>.jar build-report
     (--run-data FILE | --from k6 --k6-results FILE [--k6-summary FILE])
     --out DIR
     [--annotations FILE] [--servermetrics FILE] [--baseline FILE]
     [--property key=value]... [--properties FILE]
     [--write-run-data]
```

| Option | Meaning |
|---|---|
| `--run-data FILE` | Build from run data (`.jsonl` or `.jsonl.gz`). |
| `--from k6 --k6-results FILE` | Build from k6's results CSV (`.csv` or `.csv.gz`), written with `K6_CSV_TIME_FORMAT=unix_milli` and `vu`, `iter` in `K6_SYSTEM_TAGS`. Older files give request rows only, and the report says so. |
| `--k6-summary FILE` | k6's end-of-test summary (handleSummary's data, or `--summary-export`): thresholds, checks, dropped iterations. Without it the k6 tab says they were not given. |
| `--out DIR` | The output folder. It is created if missing. Today's outputs go there: the page, its data file for large runs, the CI/CD JSON, and JUnit when `webinsight.report.junit=true`. |
| `--annotations FILE` | The test's asserts. Otherwise it is auto-detected in `--out` as on `-g` today. |
| `--servermetrics FILE` | A server payload, or a CSV collected in-process, exactly as `webinsight.report.servermetrics` on `-g`. |
| `--baseline FILE` | The Compare tab's baseline, as today (a JTL), on JMeter runs. On any other tool a baseline, given or found in `--out`, is refused with a sentence naming decision 6 (Compare across tools comes later), and the report is built without Compare. *Note, 2026-11-26: spec 004 replaced this: a baseline is a JMeter results file or run data of any tool, read by its content, on a run of any tool.* |
| `--property key=value`, `--properties FILE` | Any `webinsight.*` setting. `--property` wins over the file, and the file over the defaults. |
| `--write-run-data` | With `--from k6`, also write the run data beside the report (`<report name>-run-data.jsonl.gz`). |

**Exit codes**: as `jmeter -g` today.
- 0: the report was written, whatever the verdict.
- 1: nothing was written (a usage or input fault).
- Non-zero: under `webinsight.report.failonerror` (default true), `failonassert` and `server.failonerror`, with the
  same console markers (`WEB-INSIGHT-REPORT: ...`).

**Console**: ASCII, the same `WEB-INSIGHT-REPORT:` lines as `-g`, plus one line naming the tool and the format
version read.

**Test name**: with `--run-data`, the run record's `testName`. A `webinsight.report.title` property that differs is
refused (it would change `runId`). With `--from k6`, the `webinsight.report.title` property, else WIR's default
title. `wir-collect --from k6` takes `--test-name`, which must be the same text.

**CI/CD JSON**: a non-JMeter run's JSON adds `"tool": {"name": ..., "version": ...}`. A JMeter run's JSON is unchanged
(decision 12).

## wir-collect --run-data

`wir-collect` takes exactly one of `--jtl FILE` and `--run-data FILE`; every other option is unchanged.
- With `--run-data`, the window, identity and test name come from the run data, through the same engine.
- With `--from k6 --k6-results FILE`, they come from k6's file through the adapter, the same as `build-report`.

Exit codes are unchanged (D27): 0 when the payload was written, 1 otherwise.

## Unchanged

- `jmeter -n`, `jmeter -g`, the Rebuild button and `wir-collect --jtl`.
- The CI/CD JSON's `window` and `testName`, which petstore's and tails' scripts read.
