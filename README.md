# JMeter Web Insight Report

![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Java](https://img.shields.io/badge/java-21%2B-orange)
![Release](https://img.shields.io/github/v/release/aharon890/JmeterWebInsightReport)
![Build](https://img.shields.io/github/actions/workflow/status/aharon890/JmeterWebInsightReport/ci.yml?branch=main&label=build)

A JMeter plugin that generates modern, interactive web reports from your load test results — a powerful alternative to JMeter's built-in dashboard.

## Modules

| Module | Description |
|--------|-------------|
| `jmeter-report-core` | Shared models, statistics engine, assertion engine, server-metrics model and collection, baseline comparison |
| `jmeter-web-insight-report` | Web Insight Report generator plugin, and the `wir-collect` server-metrics collector |

## Building

```bash
mvn clean package
```

The deployable plugin JAR is produced at:
```
jmeter-web-insight-report/target/jmeter-web-insight-report-<version>.jar
```

`<version>` is the `<revision>` property from the parent `pom.xml` — the one
place the suite version is defined.

## Installation

### Download (recommended)

1. Download `jmeter-web-insight-report-X.Y.Z.jar` from the
   [latest release](https://github.com/aharon890/JmeterWebInsightReport/releases/latest).
2. Copy it into `$JMETER_HOME/lib/ext/`, removing any older `jmeter-web-insight-report-*.jar`
   first. One plugin JAR belongs there, never two.
3. To generate the report with `jmeter -g results.jtl -o dir`, register the exporter by adding one
   line to `$JMETER_HOME/bin/reportgenerator.properties`:

   ```properties
   jmeter.reportgenerator.exporter.webinsight.classname=com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter
   ```

   The listener (GUI and `jmeter -n -t`) needs no registration.

Each release also carries `wir-collect.sh` and `wir-collect.bat`, the server-metrics collector
(see [Server asserts](#server-asserts)), and `report-annotations.schema.json`,
which gives an editor completion and typo checking for the annotations file.

### Build from source

The quickest route builds both modules, installs the plugin, and registers the
`-g -o` CLI exporter in one step:

```bash
./scripts/build-and-deploy.sh
```

It auto-detects `JMETER_HOME` (env var → a project-local `apache-jmeter-*/` →
`jmeter` on `PATH`); override it explicitly if you need a specific install:

```bash
JMETER_HOME=/opt/apache-jmeter-5.6.3 ./scripts/build-and-deploy.sh
```

To install by hand, copy **only** the shaded fat JAR:

```bash
cp jmeter-web-insight-report/target/jmeter-web-insight-report-*.jar $JMETER_HOME/lib/ext/
```

> **Do not also copy `jmeter-report-core-*.jar`.** The fat JAR already contains
> it. Adding the plain core JAR puts duplicate classes on JMeter's flat
> `lib/ext` classpath, so which copy wins becomes non-deterministic.

## Report Output Configuration

### GUI

The **Web Insight Report** listener has three configurable fields:

| Field | Description | Default |
|-------|-------------|---------|
| Report Title | Title shown in the report header | `JMeter Web Insight Report` |
| Report Filename | Output filename | `web-insight-report.html` |
| Output Directory | Where report files are written | Resolved — see [Where the report goes by default](#where-the-report-goes-by-default) |

### CLI Properties

All settings can be controlled via JMeter properties (`-J` flags):

```bash
jmeter -n -t test.jmx \
  -Jwebinsight.report.title="Checkout Load Test" \
  -Jwebinsight.report.filename='report_${timestamp}.html' \
  -Jwebinsight.report.output=/results/reports
```

Precedence: **`-J` property > GUI field > default.**

| Property | Description | Default |
|----------|-------------|---------|
| `webinsight.report.title` | Report title | `JMeter Web Insight Report` |
| `webinsight.report.filename` | Report filename. The `.json`, `.xml` and `-data.js` names are derived from it | `web-insight-report.html` |
| `webinsight.report.output` | Output directory path. Set explicitly, it is never relocated — it fails instead | Resolved — see [below](#where-the-report-goes-by-default) |
| `webinsight.report.junit` | Also write a JUnit XML summary | `false` |
| `webinsight.report.annotations` | Path to `report-annotations.json` (asserts, server asserts, markers, notes) | auto-detected in the output directory |
| `webinsight.report.servermetrics` | Path to the `server-metrics.json` payload `wir-collect` wrote — the *actual* numbers the server asserts are judged against. Pass it to the `-g -o` step only: a listener run is new, so a payload named there is always another run's and is refused. See [Server asserts](#server-asserts) | none — never auto-detected |
| `webinsight.report.baseline` | Path to a baseline JTL, which enables the Compare tab | auto-detected as `baseline.jtl` in the output directory |
| `webinsight.report.rampup` | Ramp-up duration in ms, for the timeline overlay | none |
| `webinsight.report.externaldata` | Force the chart payload into a separate `.js` file | `false` |
| `webinsight.report.externaldata.threshold` | Payload size (chars) above which that happens automatically. Values ≤ 0 are rejected with a warning | `10000000` |
| `webinsight.report.failonerror` | Exit non-zero when the plugin could not do what it was told: the artifact set is incomplete (the report itself, or its `.json`/`.xml` sibling, could not be written), **or** the gate could not be built from what the annotations file declared — a rule or server assert that cannot run; a series that could not be collected (SOURCE FAILED) or data below the coverage floor on any server component; a refused server-metrics payload when an enabled server assert is declared. **On by default**; `=false` restores exit 0 and nothing else — every marker still prints | `true` |
| `webinsight.report.failonassert` | Exit non-zero when the client gate's verdict is `FAIL`, or any server component FAILed. **Off by default and staying that way** — a verdict is yours to act on. Fires on `FAIL` only, never on `WARN` or a rule that did not judge | `false` |
| `webinsight.apdex.threshold` | Apdex satisfied threshold in ms | `500` |
| `webinsight.error.body.maxsize` | Response-body characters kept per error record. Negative values are rejected | `16384` |
| `webinsight.compare.p95.threshold` | Compare tab: % P95 change that counts as a regression | `10` |
| `webinsight.compare.p99.threshold` | Compare tab: % P99 change | — |
| `webinsight.compare.mean.threshold` | Compare tab: % mean change | — |
| `webinsight.compare.errorrate.threshold` | Compare tab: absolute error-rate change in points | `2` |
| `webinsight.compare.throughput.threshold` | Compare tab: % throughput change | — |

### Boolean properties accept only `true` or `false`

`junit`, `externaldata` and `failonerror` are parsed case-insensitively as exactly those two
words. Not `yes`, not `1`, not `on` — JMeter's own `getPropertyAsBoolean` is
`Boolean.parseBoolean`, so a wider vocabulary here would mean the same value did two different
things in two fields of one test plan.

Anything else **warns and is treated as unset**, so the GUI/JMX field decides. Treating it as
`false` would still void a ticked checkbox, just noisily. Previously
`-Jwebinsight.report.junit=yes` matched neither branch: it did not enable JUnit XML and it did
not fall through to the box that was ticked, with no message on any channel.

### Where the report goes by default

With no output directory configured, the plugin takes the first of these that passes a real
probe write, and logs which one it chose:

1. the directory of the results file (the listener's own filename field, or the JTL being
   exported under `-g`)
2. the process working directory
3. the directory of the `.jmx`
4. `~/.jmeter-web-insight-report`
5. the system temp directory

The results file outranks the working directory and the `.jmx` does not, which is less arbitrary
than it looks: a results filename is you naming a place for this run's output, while the `.jmx`'s
directory is only where a file happened to be opened from. Ranking the plan higher put reports
next to the plans instead of next to the run.

It used to be the working directory, full stop — which in GUI mode is normally
`$JMETER_HOME/bin`, read-only on most managed installs, and in a container may not be writable
by the run user. A default that commonly fails is not a default. The `-g -o` exporter used to
fall back to `$JMETER_HOME/bin/report-output`, inside the installation; it now uses the same
chain, starting from the directory of the JTL being exported.

The probe is a real create-and-delete, not `File.canWrite()` — on Windows that method reflects
only the DOS read-only attribute and returns true for an ACL-denied directory, so the fail-fast
check never fired and a 1.5 MB report rendered before anything noticed.

**An explicitly configured directory is never relocated.** It hard-fails instead: silently
moving a path someone asked for means the report is not where they put it and the overwrite
target drifts between runs.

### Reserved report filenames

Under `-e -o dir` or `-g -o dir` the plugin writes into the same directory as JMeter's own
HTML dashboard, so `index` and `statistics` are rejected — a report named `index.html` would
replace the dashboard's entry point, and `statistics` would put our summary on top of
JMeter's `statistics.json`. Both fall back to the default name with a warning, and the report
is still produced. Path separators, `..`, and characters outside
`letters / digits / . / _ / - / space` are rejected the same way, as are three names that are
legal on Linux and broken on Windows — so one configuration cannot behave differently per
platform:

- a **trailing dot or space** (Windows strips both silently, so the file is not where you asked)
- names longer than **180 characters** (most filesystems cap one path component at 255 *bytes*,
  and the `.json`, `.xml` and `-data.js` siblings are derived from this name)
- **Windows device names** — `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, including
  `CON.html` and anything else up to the first dot

### Finding out what was written

Every run prints the verdict, the resolved path of each artifact, plus one greppable line:

```
Asserts: PASS — 3 declared, 3 passed; Server kafka: FAIL — 4 declared, 1 passed, 1 failed, …
Web Insight Report generated:
  HTML : /out/web-insight-report.html
  JSON : /out/web-insight-report.json
  XML  : disabled
  DATA : inline
WEB-INSIGHT-REPORT: OK /out/web-insight-report.html
```

The verdict line has one clause per side and never a rollup: the client gate, then one
`Server <component>` clause per server component.

These markers are what a pipeline should grep rather than parsing the block above them or reading
`jmeter.log`:

| Marker | Stream | Means |
|---|---|---|
| `WEB-INSIGHT-REPORT: OK <path>` | stdout | the report was published |
| `WEB-INSIGHT-REPORT: INCOMPLETE — <reason>` | stderr | the report exists but a sibling does not; the reason names the artifact |
| `WEB-INSIGHT-REPORT: FAILED <reason>` | stderr | nothing usable was produced; the exception class is named |
| `WEB-INSIGHT-REPORT: CONFIG ERROR — <reason>` | stderr | every artifact is there, but the gate is not the one your file declared; the reason says how many checks never ran and why, a refused server-metrics payload included |
| `WEB-INSIGHT-REPORT: ASSERT FAIL — <verdict line>` | stderr | `failonassert=true` and the client gate or a server component said FAIL (`-g -o` only; the listener writes it to `jmeter.log`) |

`INCOMPLETE`, `FAILED` and `CONFIG ERROR` exit non-zero by default;
`-Jwebinsight.report.failonerror=false` restores exit 0. The one exception is a `-g -o` run that
fails outright: that exception has always propagated out of the exporter and still does.

`CONFIG ERROR` is the one that arrives *with* a complete, readable report — the report is where
you diagnose the config, and its Asserts and Server tabs say which rule or server assert could not
run, and why. It is a configuration error rather than a verdict: a broken rule contributes nothing
to `gate.status` or `serverStatus`, and a gate that genuinely **FAILED** exits 0 unless you asked
for `failonassert`. See [Exit codes and the console
markers](docs/Tutorial/11-Export-Integration.md#exit-codes-and-the-console-markers) for the full
list, server side included.

The `.json` also carries an `artifacts` object listing the absolute path and outcome of every
file, so a pipeline never has to reconstruct the names itself.

### Seeing the plugin's log output

JMeter's default `log4j2.xml` has **no Console appender** — the Root logger writes only to
`jmeter.log` and the GUI panel. So the terminal shows the lines above and nothing else. Three
ways to see more:

```bash
# 1. Keep JMeter's log for the run, instead of letting the next run truncate it
#    (the file appender is append="false").
jmeter -n -t plan.jmx -j artifacts/jmeter.log ...

# 2. Raise the level for this plugin only.
jmeter -n -t plan.jmx -Lcom.jmeterwebinsightreport=DEBUG ...
```

3. For CI, add a Console appender to `$JMETER_HOME/bin/log4j2.xml` so warnings reach stdout:

```xml
<Console name="console" target="SYSTEM_OUT">
  <PatternLayout><pattern>%d %p %c{1.}: %m%n</pattern></PatternLayout>
</Console>
<!-- ...then add <AppenderRef ref="console"/> inside <Root> -->
```

### Timestamp in Filename

Use `${timestamp}` in the filename to auto-insert `yyyyMMdd_HHmmss`:

```bash
-Jwebinsight.report.filename='report_${timestamp}.html'
```

Single-quote it: unquoted, the shell expands `${timestamp}` to nothing before
JMeter ever sees it, and the report lands as `report_.html`.

Produces: `report_20260321_165234.html` and `report_20260321_165234.json`

Seconds, not minutes: JMeter takes several seconds just to start, so two back-to-back short runs
land in the same minute and the second silently overwrites the first — with `${timestamp}` being
the documented answer to "how do I keep my reports".

### Docker / CI Example

```bash
jmeter -n -t /tests/load-test.jmx \
  -l /results/results.jtl \
  -Jwebinsight.report.output=/results \
  -Jwebinsight.report.filename='report_${timestamp}.html'
```

Single-quote it: unquoted, the shell expands `${timestamp}` to nothing before
JMeter ever sees it, and the report lands as `report_.html`.

## Annotations & Asserts

Place a `report-annotations.json` file **in the report output directory** — that is the only
place auto-detection looks. Or point at it from anywhere with
`-Jwebinsight.report.annotations=/path/to/report-annotations.json`.

An auto-detected file is announced at **WARN**, not INFO. It is an *input* being read out of an
*output* directory, so in a shared directory someone else's thresholds silently decide your
run's gate verdict — and a wrong verdict looks exactly like a right one.

> Earlier versions of this page said "next to the JTL file (or in JMeter's `bin/` directory)".
> Neither is searched. The `bin/` half appeared to work only because the output directory
> defaults to the working directory and JMeter is conventionally launched from `bin/` — set an
> output directory and it stops. A missing annotations file means no asserts, so the quality
> gate silently stops gating; the plugin now warns when a configured file is absent.

```json
{
  "testNotes": "## Objective\nValidate checkout under 500 concurrent users.",
  "assertions": [
    { "id": "checkout-p95",
      "target": { "sampler": "POST /api/checkout" },
      "metric": "p95", "warn": { "gt": 1500 }, "fail": { "gt": 2000 } },
    { "id": "api-errors",
      "target": { "match": "POST /api/*" },
      "metric": "errorRate", "fail": { "gt": 5 } },
    { "id": "run-throughput",
      "target": { "scope": "overall" },
      "metric": "throughput", "fail": { "lt": 30 } }
  ],
  "samplerNotes": {
    "POST /api/checkout": "Includes 2s payment processing delay"
  },
  "timelineMarkers": [
    { "timestamp": 1773702727000, "label": "DB Failover", "type": "warning", "description": "Primary DB switched to replica" }
  ]
}
```

- **Asserts** — the quality gate. Any metric, any percentile (p50–p99.99), per sampler, per
  pattern, or for the whole run, each with its own warn/fail bands. Only what you declare ever
  decides the verdict. The report shows every rule with its verdict, lets you tune a limit
  against the observed number with the verdict recomputing as you type, and saves the updated
  file for the next run — see [docs/Tutorial/10-Asserts-Apdex.md](docs/Tutorial/10-Asserts-Apdex.md)
  and [12-Asserts-Tab.md](docs/Tutorial/12-Asserts-Tab.md).

  You can also author them without writing JSON at all: press the ⊕ beside any number in the
  Samplers table, click a level on a timeline chart, or run **Generate from run…**. Every metric
  cell a rule already judges shows its verdict, so you can see what is gated before adding to it.
- **Server asserts** — a `serverMetrics` block in the same file: what the *server* should look like
  under this load (CPU, consumer lag, pool usage…), judged against metrics collected after the run.
  See [Server asserts](#server-asserts) below.
- **Test notes** — Markdown rendered in the Notes tab and Summary tab.
- **Sampler notes** — info icon with tooltip next to sampler names in the table.
- **Timeline markers** — vertical dashed lines on all charts at annotated timestamps.

A release attaches `report-annotations.schema.json`, this file's JSON Schema. Associate it with your
annotations file in your editor for completion and typo squiggles on every key, `serverMetrics`
included.

### Baseline Comparison

Rename a previous run's `results.jtl` to `baseline.jtl` in the same directory. The plugin auto-detects it and adds a **Compare** tab with:
- Current vs baseline response time overlay chart
- Per-sampler diff table with regression detection (>10% p95 increase or >2% error rate increase)

### Server asserts

The client asserts judge what JMeter measured. **Server asserts** judge what the servers did under
that load: you write what you expect (the *server asserts*, in the annotations file),
`wir-collect` fetches what happened (the *server metrics*, from Prometheus, a recording of it, or a
CSV export), and the report compares the two. The result is its own tab, its own header chip and
its own status — client and server are judged separately and never rolled into one word.

```json
"serverMetrics": {
  "components": {
    "orders-db": { "type": "postgres", "description": "Orders DB",
                   "match": "instance=\"orders-db:5432\"" },
    "api":       { "type": "custom", "description": "API" }
  },
  "asserts": [
    { "id": "db-connections", "component": "orders-db", "metric": "connections.used.pct",
      "warn": { "gt": 70 }, "fail": { "gt": 90 } },
    { "id": "api-cpu", "component": "api",
      "query": "max(rate(process_cpu_seconds_total{job=\"api\"}[1m]))",
      "reduce": { "kind": "p95" }, "warn": { "gt": 0.7 }, "fail": { "gt": 0.9 } }
  ]
}
```

A **component** is what is measured: a built-in catalogue type — `postgres`, `kafka`, `redis`,
`host-linux` (a Linux machine through node_exporter), `aws-ec2` (CloudWatch's view of an EC2
instance: CPU credits, EBS burst balance, IOPS, throughput and limits, ENA network allowances,
status checks), `oracle` (the Oracle Database Metrics
Exporter), `influxdb2` or `influxdb3` (InfluxDB's own `/metrics`), `jvm` and `http-service` (a Java
service's JVM and its request timer, through Micrometer and Spring Boot Actuator), `kubernetes-pod`
(cAdvisor and kube-state-metrics) or `mysql` (mysqld_exporter) — or `custom`, with an optional PromQL
`match` selector that narrows the catalogue's queries to its instances. A server assert names its
`component` and either a catalogue `metric` — query, unit and reducer come from the catalogue — or
its own `query`, which a `custom` component requires. Every component can fail the build; to only
watch a metric, give it a warn band and no fail band. The catalogue also knows each metric's name
in Telegraf, Datadog, OpenTelemetry and CloudWatch, so `wir-collect --csv FILE --from TOOL` reads a
file exported from any of them and converts it to the catalogue's unit.

**Where the metrics come from is never in the file.** It is a flag to `wir-collect`, exactly one of
`--prometheus-url URL` (with `--prometheus-token-env NAME` naming the variable that holds the
token), `--recording DIR`, or `--csv FILE [--from TOOL] [--layout wide] [--csv-columns SPEC]
[--component ID]` (one row per point: `time,component,metric,instance,value`; `--from` reads another
tool's metric names and units, `--layout wide` reads Grafana's CSV download, a time column and one
column per series, and `--csv-columns` and `--component` read a long export whose columns have other
names and no component, such as InfluxDB's annotated CSV or Dynatrace's).

The metrics exist only after the run, so the flow is three steps:

```bash
# 1. the run — its report, if the listener is in the plan, is the interim one (client rules only)
jmeter -n -t plan.jmx -l results.jtl

# 2. collect the server metrics over the run's window, into one payload
./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json \
  --prometheus-url https://prometheus.example:9090/api/v1 --prometheus-token-env PROM_TOKEN

# 3. the report that judges the server asserts and decides the exit code
jmeter -g results.jtl -o report \
  -Jwebinsight.report.annotations=report-annotations.json \
  -Jwebinsight.report.servermetrics=server-metrics.json
```

- Step 3 names the annotations file explicitly: `-g -o` requires an empty output directory, so
  there is nothing for it to auto-detect.
- `wir-collect` exits **0 whenever it wrote the payload**, whatever each series' status — a dead
  source or a query that matched nothing is recorded in the payload, and step 3 decides what it
  means under `failonerror`. It exits 1 only when nothing was written (a usage, input or
  environment fault), so under `set -e` step 3 always gets to run.
- If step 3 sets `-Jwebinsight.report.title`, pass the same text to `wir-collect --test-name`: the
  run's identity hashes the title, and a payload from a different identity is refused. Pass the
  same ramp-up to both, too (`--rampup` and `-Jwebinsight.report.rampup`, in milliseconds); a
  mismatch is a warning, and the payload's window is the one judged.
- A file written for the earlier design — `sources`, `gates`, an assert's `source` or `gate`, a
  component's `failsBuild` — is refused with a sentence naming the replacement, never half-read.

The full walk-through — reading the Server tab, the editor, Validate query, the reference for
every key — is [Tutorial 13 — Server tab](docs/Tutorial/13-Server-Tab.md).

#### Running `wir-collect` from a release

A release attaches `wir-collect.sh` and its `wir-collect.bat` wrapper beside the plugin JAR (an
older release attached the JAR alone, and both scripts are in its source archive). `wir-collect.sh`
is self-contained: copy it anywhere, set `JMETER_HOME`, and run it as `bash wir-collect.sh ...` —
a downloaded release asset has no executable bit, so `./wir-collect.sh` needs a `chmod +x` first.
Neither script is needed, though: the collector is a `main` inside the plugin JAR, and it is
**not** `java -jar` — Jackson and slf4j come from JMeter, so the classpath is the plugin JAR plus
JMeter's `lib/*`:

```bash
# Linux, macOS
java -cp "$JMETER_HOME/lib/ext/jmeter-web-insight-report-X.Y.Z.jar:$JMETER_HOME/lib/*" com.jmeterwebinsightreport.WirCollect --jtl results.jtl ...
```

```bat
REM Windows cmd.exe (the separator is ';')
java -cp "%JMETER_HOME%\lib\ext\jmeter-web-insight-report-X.Y.Z.jar;%JMETER_HOME%\lib\*" com.jmeterwebinsightreport.WirCollect --jtl results.jtl ...
```

Replace `X.Y.Z` with the JAR's version, or run it once with `--help`: that prints the options, the
exit codes and these two lines with the actual JAR name filled in.

**On Windows, use the `cmd.exe` line.** `wir-collect.bat` only hands its arguments to
`wir-collect.sh`, so it needs a `bash` on `PATH` — Git Bash's or MSYS2's — **ahead of
`System32`**, whose `bash.exe` is a different program that will not find your Windows
`JMETER_HOME`. Under Git Bash itself, `./scripts/wir-collect.sh` works as on Linux.

### CI/CD Integration

Every report generation produces `web-insight-report.json`:

```json
{
  "runId": "3f2a7c19b40de85a",
  "testName": "Checkout Flow Load Test",
  "status": "PASS",
  "serverStatus": "FAIL",
  "assertsEvaluated": true,
  "totalSamples": 847293,
  "errorRate": 0.12,
  "samplers": [...],
  "assertViolations": [...],
  "gate": {...},
  "assertions": [...],
  "serverComponents": [...],
  "serverAsserts": [...],
  "findings": [...]
}
```

`status` is the client verdict and `serverStatus` the server one — the worst status over all
server components, `FAIL` > `NOT_EVALUATED` > `WARN` > `PASS`. They are never folded into
one word; `serverStatus`, `serverComponents[]` and `serverAsserts[]` are present only on a report
that judged server asserts against a payload.

Use this in Jenkins/GitLab/GitHub Actions for automated pass/fail decisions. The line to gate a
pipeline on reads both sides and holds on every shape of run, with or without client rules or a
server block:

```bash
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"' web-insight-report.json
```

It refuses a verdict — a breached client rule, or a server component FAILing — and leaves
configuration errors to the exit code (`failonerror`, on by default). For `failonerror=false` there
is a STRICT line that also refuses every check that never ran. Both are copied from
[`test-plans/goldens/jq-gate.txt`](test-plans/goldens/jq-gate.txt), the one copy, which the build
runs over the golden documents; [Tutorial 11](docs/Tutorial/11-Export-Integration.md#gating-on-the-gate)
explains each, along with `serverComponents[]`, `serverAsserts[]` and the JUnit suites.

### Run identity

`runId` is 16 hex characters derived from the run itself — test name, start time, duration, and
the top-level sample and error counts, hashed with SHA-256 over their UTF-8 bytes. **All four
artifacts carry it**: the HTML, `web-insight-data.js`, the `.json` (first key), and the JUnit XML
(a `<property name="webinsight.runId">` in every testsuite). So does the `server-metrics.json`
payload `wir-collect` writes, and the report refuses a payload whose `runId` is not its own.

The contract is: **same JTL ⇒ same runId; distinct executions ⇒ distinct runId.** A `jmeter -g`
re-export of a run's own JTL therefore derives the same id as the listener that ran it, which is
what lets both ingestion paths answer "is the report already sitting here mine?" instead of
guessing from a filename.

Filenames bind the four artifacts together, and filenames are not unique per run. A job handed a
`.json` out of a shared output directory can now check it against the HTML beside it.

### Output Files

| File | Description |
|------|-------------|
| `web-insight-report.html` | Self-contained interactive Web Insight Report |
| `web-insight-report.json` | Machine-readable CI/CD summary |
| `web-insight-report.xml` | JUnit XML (when enabled via `-Jwebinsight.report.junit=true`) |
| `web-insight-data.js` | External chart data (only for large tests >10MB) |
| `.web-insight-publish.lock` | Empty dotfile. Two runs sharing an output directory take a lock on it while renaming their artifacts into place, so one cannot interleave its four files with the other's. **Deliberately never deleted** — removing the name while another process holds a lock on that inode lets a third create a fresh file and lock that instead, which is two holders and exactly what the lock prevents. Harmless to delete when nothing is running; it will reappear |

## Documentation

See [docs/Tutorial](docs/Tutorial/README.md) for full documentation with screenshots covering every tab, feature, and control.

See [docs/FEATURES.md](docs/FEATURES.md) for the feature list.

[`reportExample/web-insight-report.html`](reportExample/web-insight-report.html) is a report the
current build generated, beside the [annotations file](reportExample/report-annotations.json) that
produced it. `./scripts/refresh-report-example.sh` regenerates both with the three steps above: a
20-sampler run, `wir-collect --csv`, then `jmeter -g -o`. Its Server tab is judged against **demo
data** the script generates from the run's own load, not against a measured server.

## Requirements

- Java 21+
- Apache JMeter 5.6.3+
- Maven 3.8+

## License

Apache License 2.0 — see [LICENSE](LICENSE).
