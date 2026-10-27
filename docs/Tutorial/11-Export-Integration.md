# Export & CI/CD Integration

## Table CSV Export

Every data table in the report has a **CSV download button** (↓ icon):

| Table | Tab | Filename |
|-------|-----|----------|
| Top 5 Slowest Samplers | Summary | `top5-slowest-samplers.csv` |
| Sampler Statistics | Samplers | `sampler-statistics.csv` |
| Error Summary | Errors | `error-summary.csv` |
| Error Details | Errors | `error-details.csv` |
| Sampler Comparison | Compare | `comparison.csv` |

**Key behavior:**
- Exports **all data** — hidden rows, hidden columns, filtered samplers all included
- CSV files include UTF-8 BOM for correct Excel/Google Sheets handling
- Hover tooltip: "Download as CSV"

## Copy Summary

The **"Copy Summary"** button on the Summary tab copies a clean HTML table to the clipboard. Paste directly into:
- Confluence pages
- SharePoint documents
- Email (Outlook, Gmail)
- Wiki pages

The copied tables hold the Summary tab's metric cards and the top 5 slowest samplers. Neither
verdict is in them: **Copy Verdict**, beside it, copies the verdict as a few plain lines for a chat
or an email — the test name and generation time, the Asserts verdict with its counts, the server
chip's word when server asserts are declared, a "Draft differs" line when your rule edits would
change the file, and the report's file name.

## Chart PNG/SVG Export

Every chart has export buttons in its toolbox (top-right corner):
- **PNG** (camera icon) — raster image, good for documents
- **SVG** (image icon) — vector image, good for scaling/printing

## Machine-Readable JSON

Every report writes a CI/CD JSON beside its HTML, named after it: `web-insight-report.json` by
default, `<name>.json` for a report named `<name>.html` with `-Jwebinsight.report.filename`. A
`${timestamp}` in that name is resolved once per run, so the HTML, the `.json`, the JUnit `.xml`
and the chart-data `<name>-data.js` of one run always share it.

The example is one run, the same run as the JUnit XML example further down. It is abridged: its
two samplers are shown in full, two of its six client rules are shown, and the server entries are
described in their own section.

```json
{
  "runId": "3f2a7c19b40de85a",
  "testName": "Checkout Load Test",
  "status": "FAIL",
  "serverStatus": "FAIL",
  "assertsEvaluated": true,
  "duration": 75000,
  "totalSamples": 87364,
  "totalErrors": 339,
  "errorRate": 0.39,
  "samplers": [
    {
      "name": "GET /api/products",
      "sampleCount": 45000,
      "mean": 120.4,
      "p95": 412.0,
      "p99": 655.0,
      "errorRate": 0.0,
      "throughput": 600.0,
      "apdex": 0.97,
      "assertStatus": "PASS"
    },
    {
      "name": "POST /checkout",
      "sampleCount": 42364,
      "mean": 891.0,
      "p95": 2340.0,
      "p99": 3980.0,
      "errorRate": 0.8,
      "throughput": 564.85,
      "apdex": 0.72,
      "assertStatus": "FAIL"
    }
  ],
  "assertViolations": [
    { "sampler": "POST /checkout", "status": "FAIL" },
    { "sampler": "Whole run", "scope": "overall", "status": "WARN" }
  ],
  "gate": {
    "status": "FAIL", "declared": 6, "evaluated": 5,
    "passed": 1, "warned": 1, "failed": 2,
    "notJudged": {
      "total": 2, "disabled": 1, "noMatch": 0, "noValue": 0, "invalidRule": 1,
      "sourceFailed": 0, "lowCoverage": 0, "loadNotAchieved": 0
    }
  },
  "assertions": [
    {
      "id": "checkout-p95",
      "description": "Payment gateway budget, contract §4.2",
      "target": "POST /checkout", "targetKind": "sampler",
      "metric": "p95", "unit": "ms",
      "status": "FAIL", "matched": 1,
      "attainment": { "actual": 2340.0, "failThreshold": 1000.0, "consumed": 2.34, "breached": true },
      "results": [
        { "sampler": "POST /checkout", "actual": 2340.0,
          "status": "FAIL", "predicate": "gt", "threshold": 1000.0 }
      ]
    },
    {
      "id": "run-throughput",
      "target": "Whole run", "targetKind": "overall",
      "metric": "throughput", "unit": "/s",
      "status": "WARN", "matched": 1,
      "attainment": { "actual": 1164.85, "failThreshold": 1000.0, "consumed": 0.8585, "breached": false },
      "results": [
        { "sampler": "Whole run", "actual": 1164.85,
          "status": "WARN", "predicate": "lt", "threshold": 1200.0 }
      ]
    }
  ],
  "serverComponents": [ "… one entry per server component, see below" ],
  "serverAsserts": [ "… one entry per server assert, see below" ],
  "findings": [
    { "severity": "error", "ruleId": "checkout-p95x",
      "message": "unknown metric 'p95x' — did you mean 'p95'?. Valid here: sampleCount, errorCount, … p99, p99.9" }
  ],
  "generatedAt": "2026-03-28 14:30:00 UTC",
  "artifacts": {
    "outputDirectory": "/out",
    "html": { "path": "/out/web-insight-report.html", "outcome": "WRITTEN" },
    "json": { "path": "/out/web-insight-report.json", "outcome": "WRITTEN" },
    "xml": { "path": "/out/web-insight-report.xml", "outcome": "PLANNED" },
    "data": { "path": "/out/web-insight-data.js", "outcome": "SKIPPED", "detail": "inline" }
  }
}
```

The keys come in that order: `status` and `serverStatus` side by side near the top, the client
`gate` and `assertions[]`, then the server `serverComponents[]` and `serverAsserts[]`, then
`findings[]`, `generatedAt` (with the generating machine's time zone) and `artifacts`, the path of
every file the run planned — the JUnit XML only when it is enabled, the chart-data file `SKIPPED`
when the data stayed inline. The server keys are described in [Server asserts in the
JSON](#server-asserts-in-the-json) below.

`artifacts.xml` reads `PLANNED` in this file even on a run that wrote the XML: the JSON is
written before the XML, so it cannot report the XML's outcome. Do not read it as a failure. Whether
the XML was written is reported by the exit code and the `INCOMPLETE` marker (see [Exit codes and
the console markers](#exit-codes-and-the-console-markers)).

Units and populations: `duration` is in milliseconds. `errorRate`, at the top and on each sampler,
is a percentage — `0.39` is 0.39%, not 39%. `totalSamples` and `totalErrors` count top-level
samplers only (a transaction's children are not counted again), so they match the report's header
cards. Every number the JSON writes as a double keeps its decimal point: an integral `891` is
written `891.0`.

**Every rule is the same kind of rule.** A `loadValidity` block is folded into `assertions[]` at
load as an ordinary rule, so there is one kind of rule, one place it lives, and nothing to
distinguish. The file then also carries a WARNING finding saying the block was migrated into
`assertions[]` and under which id; saving the file from the Asserts tab writes the rule there, and
the finding no longer appears.

A rule that decided nothing carries **`notJudged`** and a **`reason`** saying which kind of
nothing and why:

```json
{ "id": "renamed-checkout", "status": "NOT_JUDGED", "notJudged": "NO_MATCH",
  "reason": "matched nothing in this run", "matched": 0 }
```

| `notJudged` | Meaning | Whose problem |
|---|---|---|
| `DISABLED` | `enabled: false` | nobody's — it was parked |
| `NO_MATCH` | the target selected nothing | the **file**, usually a renamed sampler |
| `NO_VALUE` | the run cannot produce this metric | the **run**, not the file |
| `INVALID_RULE` | unknown metric, unreadable target, no bands; for a server assert also an unknown component, a `requiresLoad`/`divideBy` naming no throughput rule, a `sustainedFor` the collection's step cannot hold, or a query the payload never collected ([Tutorial 13](13-Server-Tab.md#why-a-server-assert-was-not-judged) lists them all) | the **file** |
| `SOURCE_FAILED` | *server asserts only* — the series could not be collected: the metric source refused it or never answered (auth, timeout, DNS, 5xx), a placeholder could not be resolved, a catalogue metric has no Prometheus query, or a `--csv` row of it could not be read; the `reason` says which | **DevOps** — credentials or the endpoint; for a CSV row, whoever exported the file |
| `LOW_COVERAGE` | *server asserts only* — data covered less of the collected window than the coverage floor | whoever owns the collector's wait, step or floor |
| `LOAD_NOT_ACHIEVED` | *server asserts only* — the load rule it names in `requiresLoad` FAILED or was not judged, or the rule it names in `divideBy` was not judged: the load it depends on was not established | a FAILED load rule: the **test** — re-run it. A load rule that was not judged: whatever that rule's own reason says (a renamed sampler, a parked rule, a broken file) |

Coverage is measured by `wir-collect` over the window it **collected**, and is not recomputed when
the report judges. With a long ramp-up excluded from the judged window, the figure can therefore
read higher than the coverage of the part that was judged.

The three server-only reasons are counted on every gate, as zero on a run with no server
metrics, so `gate.notJudged` has one shape whether or not the run collected anything.


### Gating on the gate

`status` is one word, and one word cannot tell "two rules warned" from "two rules could not run
at all" — a difference that decides whether a build should stop. The `gate` block, and on a run
with server asserts `serverStatus` and `serverComponents[]`, are what a pipeline should read.

There are two lines. Both are tested on every build over CI/CD documents of every shape (a run
with no rules, client-only runs, server-only runs, a PASS over a coverage failure, a refused
payload), so they hold on any run.

```bash
# DEFAULT — the verdicts
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"' web-insight-report.json

# STRICT — the verdicts, and every check the file asked for that never ran
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL" and (.gate.notJudged.invalidRule // 0) == 0 and ([.serverComponents[]? | .notJudged.invalidRule + .notJudged.sourceFailed + .notJudged.lowCoverage] | add // 0) == 0 and ([.findings[]? | select(.severity == "error")] | length) == 0' web-insight-report.json
```

**DEFAULT refuses a verdict.** A client rule whose fail band was breached, or `serverStatus` FAIL —
a server component FAILed. It passes a run that declared no client rule (`gate` is absent)
and a run with no server block (`serverStatus` is absent). It does **not** refuse a
server component that judged nothing (`NOT_EVALUATED`). When that is because a check could not run
— `INVALID_RULE`, `SOURCE_FAILED`, `LOW_COVERAGE`, or a refused payload an enabled server assert
needed — it is a server data problem, not a verdict. It is said on the console as `SERVER NOT JUDGED`
and does not change the exit code unless `webinsight.report.server.failonerror=true` is set. Pair
DEFAULT with that flag, or use STRICT. A query that matched nothing
(`NO_MATCH`), a parked assert (`DISABLED`), a value that could not be measured (`NO_VALUE`), a load
that was not established (`LOAD_NOT_ACHIEVED`) and a component with no asserts are not configuration
errors: they exit 0 and neither line refuses them. The four not-judged reasons are counted in
`serverComponents[].notJudged`. A component with no asserts is counted in none of them: it reads
`NOT_EVALUATED` with `declared` 0.

**STRICT refuses everything DEFAULT does, and every check the file asked for that never ran, as
far as the JSON can show it:**

- `INVALID_RULE` on the client gate and on **any** server component — a defect in the file;
- `SOURCE_FAILED` and `LOW_COVERAGE` on **any** server component — every component can fail the
  build, and so does the `(no gate)` entry, so asserts that named no declared component are counted;
- any **ERROR finding** — which is how the two configuration errors that leave no gate to count
  reach the JSON: an annotations file that could not be read (finding `annotations`), and a refused
  payload that a server assert needed (finding `serverMetrics.payload`).

STRICT is meant for `failonerror=false`. What it cannot see is an artifact that failed to be
written, which the exit code reports and no JSON can.

The lines below are **diagnostics, not gate lines**: they print, they decide nothing, and unlike
DEFAULT and STRICT they are not run over every shape of document on each build. Each is null-safe
on a run that lacks the array:

```bash
# What actually broke, client side
jq -r '.assertions[]? | select(.status=="FAIL") |
       "\(.id): \(.metric) \(.attainment.actual) vs \(.attainment.failThreshold)"' \
   web-insight-report.json

# What actually broke, server side
jq -r '.serverAsserts[]? | select(.status=="FAIL") |
       "\(.component)/\(.id): \(.reduce.kind) \(.actual) \(.unit) breached \(.breached)"' \
   web-insight-report.json

# What could not run, and why
jq -r '.findings[]? | "\(.severity) \(.ruleId): \(.message)"' web-insight-report.json
```

**Only a breached fail band is a `failed`.** A rule the file got wrong is counted under
`notJudged.invalidRule` and does **not** move the verdict — nothing was measured, so there is no
performance judgement to make.

**You do not need a `jq` for that one.** A rule that could not run already exits the process
non-zero, under `failonerror` (on by default), before your pipeline reads this file — it is a
configuration error rather than a verdict. A `FAIL` verdict exits 0 unless you set
`failonassert=true`. The expressions above are for deciding what a *verdict* should do to your
build, which is the part that is yours.

Two arithmetics, both closed, so a job can check the gate's own sums rather than trust that every
rule landed somewhere. They hold on `gate` and on every `serverComponents[]` entry alike — the server
side counts with the same block:

```
passed + warned + failed + notJudged.total       == declared
disabled + noMatch + noValue + invalidRule
  + sourceFailed + lowCoverage + loadNotAchieved == notJudged.total
```

`assertions[]` is emitted in declaration order, so two runs' gates diff line by line. Every match
is carried, passing ones included — a verdict alone does not say which of eight matched samplers
breached or how close the others were. Each match carries `sampler`, `actual` and `status`;
`predicate` and `threshold` appear only on a WARN or FAIL match, and name the band it crossed.
`actual` is `null` for a match this run could not measure.

**How an `actual` is written.** `results[].actual`, and `attainment.actual` where a rule carries
one, have two decimals plus whatever digits keep the number on its own side of every band the rule
declares, warn and fail alike. Digits are added only where two decimals would put it on a band it
is not on, or across one:

| Measured | Rule | `actual` |
|---|---|---|
| 119.36094674556213 ms, FAIL | `fail > 119.36` | `119.361`, not `119.36` beside `"threshold": 119.36` |
| 214.7455621301775 ms, PASS | `fail ≥ 214.75` | `214.746`, not `214.75` beside `"breached": false` |
| 8.29892736363825 /s, FAIL | `fail < 8.3` | `8.299`, not `8.3` |
| 610.5 ms, FAIL | `fail > 500` | `610.5`: clear of its band, so two decimals |

**`attainment`** is how much of its fail band a rule used — present when the rule has a fail band
and measured something, and absent where the ratio below would divide by zero: an upward fail band
at 0 (`gt 0`), or a measured 0 under a downward one. It describes one match, the rule's **worst judged match**: the highest
status first (a FAIL before a WARN before a PASS), then the one furthest past the band that status
was decided against (a FAIL's fail band, a WARN's warn band), the earliest on an exact tie. That is
the match the JUnit failure message names and the report's Asserts tab shows in its Actual and
Budget cells, so all three describe the same sampler.
Severity comes first because a WARN's message naming a PASS would contradict itself; where the warn
and fail bands point the same way it is simply the worst value. `consumed` is `actual / failThreshold`
for an upward band and `failThreshold / actual` for a downward one, so 1.0 is the fail band's line:
above it is past the line, below it is short of it. It is written beside that line as an `actual` is beside its band: four
places, plus whatever digits keep it on its own side of 1.0. A FAIL 0.0007% past its line is
`1.00001`, and a PASS 0.002% short of it `0.99998` — never `1.0`, which is the value exactly on the
line (a `gte` band met to the digit, which is a breach). `breached` says which, without the
arithmetic.

`attainment.actual` is the same number as the match it describes. A rule's `actual` can differ
from the same sampler's `samplers[]` figure in the third decimal: `samplers[]` and the top-level
`errorRate` are two decimals, because no band stands beside them. There is no never-zero guard
here, so a value that no band separates from zero, such as 0.0000077 /s under `fail < 0.001`, is
written `0.0`. The JUnit XML writes that value as `0.0000077`.

There is one exception. The digits stop at a cap: twelve significant digits, or three decimals
for a value of 1e8 or more. When even the cap cannot separate a value from its band, the capped
number is written, and it can sit on the band or across it. In practice that is float noise: a
T-Digest percentile of 993.2499999999998 that PASSes `gte 993.25` by 2e-13 is written `993.25`.
That verdict was decided by noise in the evaluator, and more digits would not make it any more
meaningful. A band typed with twelve or more significant digits can meet the cap as well.

**This file is uncapped; the HTML is not.** A rule matching 500 samplers carries all 500 here,
because a machine-readable contract should not be truncated under you. The report keeps the worst
200 of each and marks the entry `resultsCapped: true`, because those are the breaches someone
opened the page to see. The two artifacts carry different sets on purpose, and each says which.

**`findings[]`** is what the loader and validator could not accept — an unknown metric with a
suggestion, an unreadable target, an annotations file that would not load. It is present **even
when `gate` is absent**, which is the case that most needs it: a typo'd
`-Jwebinsight.report.annotations` path declares no rules at all, and without findings that report
is indistinguishable from one that never wanted a gate.

**Three keys carry the verdict outside the `gate` block:**

| Key | What it is |
|---|---|
| `assertsEvaluated` | Was a gate declared and run at all |
| `assertViolations` | The samplers that did not pass, and the whole run when a `scope: "overall"` rule did not |
| `samplers[].assertStatus` | One sampler's verdict |

The whole run's `assertViolations` entry carries **`"scope": "overall"`** beside `"sampler": "Whole
run"`. The name is display text, and a sampler can be called "Whole run"; read `scope` to find the
run-level violation. A sampler's entry has no `scope` key.

**No gate declared?** `gate`, `assertions` and `assertViolations` are absent, no sampler carries
an `assertStatus`, and `assertsEvaluated` is `false`. A run that was reported rather than judged
never looks like a passing one.

`gate` and `assertions` describe the **client** rules only. A run that declares server asserts and
no client rules therefore carries no `gate` block and no `assertViolations` (and a top-level
`status` of `UNKNOWN`) and, once the server asserts were judged against a payload, still reports
`assertsEvaluated: true` — something *was* judged, and its verdicts are in `serverStatus`,
`serverComponents[]` and `serverAsserts[]`. The question the field answers is "did any gate,
either side, run", not "is there a `gate` block".

**A gate that ran is `assertsEvaluated: true` even when it judged nothing.** Rules were declared
— they just all came back `NOT_JUDGED`, whether from a renamed sampler, a metric this run could
not produce or a rule the file got wrong. `assertsEvaluated: false` means no gate ran on either
side: there are no rules — which includes an annotations file that could not be read at all: it
declares nothing, and says so as an ERROR finding (`annotations`) — or the file declares server
asserts only and none was judged, on the interim report or over a refused payload.

In that case `gate.status` is **`NOT_EVALUATED`**, which is not `PASS`. A run that judged nothing
has not passed anything. The top-level `status` carries the same verdict as `gate.status`, with
one difference: where nothing was judged it reads `UNKNOWN`, so read `gate.status` to tell that
case apart.

**The verdict does not change JMeter's exit code unless you ask.** By default a FAIL gate does
not fail the process: gating on the verdict is your pipeline's reading of these artifacts. What
*does* exit non-zero by default is the plugin failing to produce them; see [Exit codes and the
console markers](#exit-codes-and-the-console-markers) below.

If you want the verdict itself to stop the run:

```bash
jmeter -n -t plan.jmx -l results.jtl -Jwebinsight.report.failonassert=true

# with server asserts, on the report that judges them (step 3 — see Tutorial 13)
jmeter -g results.jtl -o report -Jwebinsight.report.annotations=report-annotations.json \
  -Jwebinsight.report.servermetrics=server-metrics.json -Jwebinsight.report.failonassert=true
```

**Default off, and it stays off** — deciding what a FAIL means is yours to make. It fires only on
`FAIL`: not on `WARN`, because a warn band exists to say "look at this" without stopping anything,
and not on `NOT_JUDGED` for any reason, because a pipeline that wants a broken rule to be fatal has
`gate.notJudged.invalidRule` and can say so itself. The report, the JSON and the JUnit XML are all
still written when it fires — failing a build must not cost you the artifact that explains why.

It reads both sides: the client gate's `FAIL`, or a `FAIL` on any server component — every
component can fail the build. Each side is still judged
on its own; the flag is the one place the two are read together, and it says which said FAIL.

### Server asserts in the JSON

Three keys, all present only on a report that judged server asserts against a collected payload —
step 3 of the three-step flow in [Tutorial 13 — Server tab](13-Server-Tab.md). The interim `-n -t`
report (no payload yet) and a report whose payload was refused carry **none** of them: a pipeline
reading them sees no server block, rather than an empty one it might sum.

**`serverStatus`** is the server side's own word, beside the client `status` and never folded into
it — there is no rollup of client and server anywhere. It is present exactly when
`serverComponents` is, and it is the worst status over **all** components, in this rank:

| Rank | `serverStatus` | Means |
|---|---|---|
| worst | `FAIL` | a component's fail band was breached |
| | `NOT_EVALUATED` | a component judged nothing — every assert in it was `NOT_JUDGED`, whatever the reason, or it declares no asserts at all — or no component was reported |
| | `WARN` | a component's warn band was crossed, nothing failed |
| best | `PASS` | every component passed |

`NOT_EVALUATED` ranks above `WARN` because a component that decided nothing is a check the build
depended on that never ran, and below `FAIL` because a measured breach is still the worse news.
Every component counts. The header chip in the HTML uses the
same fold.

**The status is the verdict; a configuration or collection error is the exit code.** A
component whose judged asserts PASS while another of its asserts is `SOURCE_FAILED` or `LOW_COVERAGE`
reads `PASS` — and so does `serverStatus` — and the check that never ran is reported beside it
(`SERVER NOT JUDGED`, the Server tab, JUnit, STRICT); it exits 1 only under
`webinsight.report.server.failonerror=true`. That is the
client side's rule too (a broken rule contributes nothing to `gate.status`), and making only the
server word carry config errors would make the two statuses mean different things. STRICT, above,
is the jq line that refuses both.

**`window`** is on every document: the run's exact start and end — `start` and `end` in ISO 8601 UTC,
`startMillis` and `endMillis` in epoch milliseconds, and `rampUpEndMillis` when a ramp-up is set. It
is the period a script exports server data for:

```bash
jq -r '.window | "(.startMillis) (.endMillis)"' report/web-insight-report.json
```

**`serverData`** is present whenever the annotations declare server asserts: `state` (`COLLECTED`,
`NOT_COLLECTED` or `REFUSED`) and `series[]`, one `{component, metric, unit}` per enabled server
assert, the names a CSV must carry. On the report written when the run ends it is the one place a
script can read what to export and that it is still to do.

**`serverComponents[]`** is one entry per declared component, in declaration order: the
component's `id`, its `label` (the `description`, or the id), its `type` (a catalogue type —
`postgres`, `kafka`, `redis`, `host-linux`, `aws-ec2`, `oracle`, `influxdb2`, `influxdb3`, `jvm`,
`http-service`, `kubernetes-pod`, `mysql` — or `custom`), the `collectionId` of the
payload it was judged over, and then **the same block the
client `gate` carries** — so one arithmetic and one idiom read both sides.

Asserts that name no declared component are reported in one more entry, **`"(no gate)"`**, which
has no `type`. Its `id` is that reserved word, and its `label` is `"(no component)"`, the name the
header chip, the console and the Server tab give it. Such an assert, unless parked, is a defect in the file (`INVALID_RULE`),
fatal only under `webinsight.report.server.failonerror=true`, so it must be counted somewhere a pipeline sums
`serverComponents[].notJudged.invalidRule`; the JUnit XML carries the same entry as a suite.
`(no gate)` and `assertions` are reserved, so no declared component can take either id.

```json
"serverComponents": [
  { "id": "kafka", "label": "Kafka", "type": "custom", "collectionId": "df8778b7fa474d95",
    "status": "FAIL", "declared": 4, "evaluated": 3, "passed": 1, "warned": 0, "failed": 1,
    "notJudged": { "total": 2, "disabled": 1, "noMatch": 0, "noValue": 0, "invalidRule": 0,
                   "sourceFailed": 0, "lowCoverage": 0, "loadNotAchieved": 1 } },
  { "id": "db", "label": "DB", "type": "custom", "collectionId": "df8778b7fa474d95",
    "status": "WARN", "declared": 6, "evaluated": 6, "passed": 2, "warned": 1, "failed": 0,
    "notJudged": { "total": 3, "disabled": 0, "noMatch": 1, "noValue": 0, "invalidRule": 1,
                   "sourceFailed": 0, "lowCoverage": 1, "loadNotAchieved": 0 } }
]
```

**`serverAsserts[]`** is one entry per server assert, in declaration order: what was declared, what
it decided, and the one number it decided on. `component` names its component; `metric` appears
when the assert names a catalogue metric, and `query` is then the catalogue's query with the
component's `match` expanded.

```json
"serverAsserts": [
  { "id": "up-fails", "description": "a real breach",
    "component": "kafka", "query": "up", "unit": "msgs",
    "reduce": { "kind": "max" }, "across": "max",
    "status": "FAIL", "actual": 49.0, "coverage": 1.0, "instances": 1,
    "warn": { "gt": 10.0 }, "fail": { "gt": 20.0 }, "breached": { "gt": 20.0 } },
  { "id": "up-sustained", "component": "db", "query": "up", "unit": "",
    "reduce": { "kind": "max", "over": { "sustainedFor": "10s" } }, "across": "max",
    "status": "PASS", "actual": 39.0, "coverage": 1.0, "instances": 1,
    "fail": { "gt": 1000.0 } },
  { "id": "sparse-low-coverage", "component": "db", "query": "sparse", "unit": "",
    "reduce": { "kind": "max" }, "across": "max",
    "status": "NOT_JUDGED", "notJudged": "LOW_COVERAGE",
    "reason": "coverage 6% of the collected window is below the floor 90%",
    "actual": null, "coverage": 0.0612, "instances": 1, "fail": { "gt": 1.0 } }
]
```

- **`reduce` is structured**, in the grammar the annotations file writes it in — `{"kind": "p95"}`,
  `{"kind": "max", "over": {"sustainedFor": "30s"}}`, `{"kind": "max", "over": {"breachBudget":
  0.02}}` — never the display sentence ("max sustained for 10s") the JUnit XML and the console
  show. A consumer does not parse English, and the object round-trips into a file unchanged. It is
  `null` when the file's reducer could not be read (that assert is `INVALID_RULE`).
- `requiresLoad` and `divideBy` appear only when the assert declares them. `unit` is always the
  declared unit, the series' own. For a `divideBy` assert `actual` and the bands are per unit of
  the load rule's measured value, and for a `slope` they are per minute; the page and the JUnit XML
  write that unit as `% ÷ load:throughput`, `B/min` or `B/min ÷ load:throughput`. `reduce.kind`
  and `divideBy` say the same to a machine, so there is no second unit field.
- `actual` is the reduced number, folded across instances by `across`, to 4 places, plus whatever
  digits keep it on its own side of every band the assert declares, warn and fail alike — the
  client `actual`'s rule at four places instead of two. A FAIL at 3.00004 over `fail > 3` is
  `3.00004`, not `3.0` on the band it breached, and a PASS at 2.99996 under `fail ≥ 3` is
  `2.99996`. As for a client `actual` there is no never-zero guard: a value that no band separates
  from zero is written `0.0`. The client's cap holds here too, twelve significant digits, which
  four places already are from ten million up: there the number is the four-place one, on or
  across its band only by float noise's margin. `null` when the
  assert judged nothing. `coverage` is a fraction of the **collected** window, 0 to 1, and `null`
  when nothing was collected for the assert (it was parked, could not run, or the load it depends
  on was not established). `instances`
  is how many instances `across` folded (0 when nothing was read). Per-instance series are chart
  data in the HTML, not here.
- `warn` and `fail` echo the declared bands, thresholds always as doubles (`20` reads `20.0`);
  `breached` is the band that was crossed. A not-judged assert carries `notJudged` and its `reason`,
  as a client rule does.

## Which run produced this file

`runId` is the first key in the JSON for a reason: everything below it is only meaningful once
you know which execution it describes. It is 16 hex characters, SHA-256 over the run's own
fingerprint, and **all four artifacts carry it** — the HTML, `web-insight-data.js`, this JSON,
and the JUnit XML.

The contract is **same JTL ⇒ same runId; distinct executions ⇒ distinct runId**, so a
`jmeter -g` re-export derives the same id as the listener that ran the test.

The server-metrics payload `wir-collect` writes carries it too, derived from the same JTL, and the
report refuses a payload whose `runId` is not its own. The test name is part of the hash, which is
why `wir-collect --test-name` must be the same text as `-Jwebinsight.report.title` when step 3 sets
one.

The four artifacts of one run are bound together by *filename*, and filenames are not unique per
run. A job handed a `.json` out of a shared output directory could not tell whether it belonged
to the HTML sitting beside it. `runId` settles that. Three things a pipeline can do with it
(diagnostics, not gate lines — none of them decides a verdict):

```bash
# 1. Archive under an id that cannot collide with a concurrent shard's
RUN_ID=$(jq -r '.runId' out/web-insight-report.json)
mv out "artifacts/$RUN_ID"

# 2. Prove the JSON you are gating on describes the HTML you are publishing
grep -q "$RUN_ID" out/web-insight-report.html || { echo "artifact set is mixed"; exit 1; }

# 3. Correlate the JUnit XML your CI ingested with the report a human will open
xmllint --xpath 'string(//property[@name="webinsight.runId"]/@value)' out/web-insight-report.xml
```

## Exit codes and the console markers

The plugin exits non-zero when **it could not do what it was told**. These count:

| | Example |
|---|---|
| it could not produce a complete artifact set | the report itself, or its `.json` or `.xml` sibling, could not be written |
| it could not build the gate your file declared | a rule naming an unknown metric; an annotations file that would not load at all; a server assert that cannot run (`INVALID_RULE`) or that names no declared component (unless it is parked); a component with an unknown `type`; a key `serverMetrics` does not take, refused with a sentence naming its replacement (`sources`, `gates`, `packs`, `kpis`, `failsBuild` on a component); a `requiresLoad` or `divideBy` naming no client rule, or one that is not about throughput |
| a check a server component depended on never ran | a series that could not be collected (`SOURCE_FAILED`) or data below the coverage floor (`LOW_COVERAGE`); a payload named by `-Jwebinsight.report.servermetrics` that is missing, unreadable, another schema's or another run's, when an enabled server assert is declared |

**This is on by default**; `-Jwebinsight.report.failonerror=false` exits 0 instead, and buys back
the exit code *only* — every marker, finding and banner still appears.

One exception, on `jmeter -g -o` only: a report that could not be written at all (the `FAILED`
marker) exits 1 whatever `failonerror` says, because the failure reaches JMeter as an export error
and JMeter exits 1 on its own. On that path a missing `.json` or `.xml` beside a written report
(`INCOMPLETE`) and a configuration error (`CONFIG ERROR`) honour the flag. On the listener path
every case above honours it.

**Server data never changes the exit code by default.** The server tab is a bonus on the client
report: a server assert the file got wrong, a series that could not be collected, coverage under
the floor and a refused payload are said on stderr as `WEB-INSIGHT-REPORT: SERVER NOT JUDGED — …`,
at the top of the Server tab, in the CI/CD JSON and in JUnit, and the exit code is the client
side's. `webinsight.report.server.failonerror=true` makes them fatal (exit 1, marked `CONFIG
ERROR`). A `serverMetrics` block whose shape does not read (`"asserts": {}`) costs the server side
only: the client rules in the same file are read and judged as usual. An annotations file that is
not JSON at all loses both sides, and is a client `CONFIG ERROR`. Also never fatal:

- **No payload at all.** The interim `-n -t` report, before `wir-collect` has run, judges the
  client rules only; the server asserts are simply not evaluated.
- **A query that matched no series** (`NO_MATCH`) — on either side, a skip and never fatal.
- **A refused payload when nothing needed it** — no enabled server assert declared. It is a
  WARNING finding, and the exit is unaffected.
- **A WARN.** Every component can fail the build; a metric you only want to watch gets a warn band
  and no fail band, and a WARN never moves the exit code.
- **`wir-collect` itself** exits 0 whenever it wrote the payload, whatever each series' status:
  the payload records them, and step 3 decides what they mean. It exits 1 only for a usage, input
  or environment fault, when nothing was written.

It is on by default because the alternative exits 0 on every failure mode: a pipeline shaped
`jmeter -n -t plan.jmx && publish-report` would stay green with nothing to publish, and CI could
not detect a missing report without parsing a log file it was never given.

**What is *not* covered: the verdict.** A FAIL gate — client or server — is a successful run of a
working gate, and exits 0 unless you ask otherwise with `failonassert`, which reads the client gate
and every server component. The line is between "the plugin did not check what you asked" and
"the plugin checked, and here is the answer" — the first is the plugin's problem, the second is
yours.

The markers, one line each, fixed prefix — grep these rather than parsing the summary block or
reading `jmeter.log`, which by default has no Console appender at all:

| Marker | Stream | Means |
|---|---|---|
| `WEB-INSIGHT-REPORT: OK <path>` | stdout | the report was published |
| `WEB-INSIGHT-REPORT: INCOMPLETE — <reason>` | stderr | the report exists, a sibling does not; the reason names the artifact and why |
| `WEB-INSIGHT-REPORT: FAILED <reason>` | stderr | nothing usable was produced; the exception class is named |
| `WEB-INSIGHT-REPORT: CONFIG ERROR — <reason>` | stderr | the artifacts are all there, but the gate is not the one your file declared; the reason says how many checks never ran and why. Printed for the client side, and for the server side under `webinsight.report.server.failonerror=true`; it always means the run exits non-zero (unless `failonerror=false`) |
| `WEB-INSIGHT-REPORT: NEXT — <what to do>` | stdout | enabled server asserts are declared and no server data was given to this report: the window to export, the component/metric names, and the command that adds it |
| `WEB-INSIGHT-REPORT: SERVER NOT JUDGED — <reason>` | stderr | server checks the file asked for never ran — a failed collection, coverage under the floor, a server assert that cannot run, a refused payload. The report and the client verdict are complete and the exit code is unchanged |
| `WEB-INSIGHT-REPORT: WARNING — <reason>` | stderr | a `webinsight.*` property nothing reads (a typo, with the nearest name), or a path a properties file mangled (single backslashes) |
| `WEB-INSIGHT-REPORT: ASSERT FAIL — <verdict line>` | stderr | `failonassert=true` and the client gate or a server component said FAIL. `-g -o` only; the listener writes the same fact to `jmeter.log` |
| `WEB-INSIGHT-REPORT: exiting 1 because <flag>=true` | stderr | printed as the process exits, naming the flag that turned this run into exit 1: `webinsight.report.failonerror`, `webinsight.report.server.failonerror` or `webinsight.report.failonassert`. Printed only when the plugin arranges the exit itself — on the listener path, for every case; on `-g -o`, for a configuration error and for `failonassert`. An artifact failure on `-g -o` (`FAILED`, or `INCOMPLETE` under `failonerror`) reaches JMeter as an export error, and JMeter exits 1 without this line |

`CONFIG ERROR` is the one that comes with a complete, readable report. That is deliberate: the
report is the artifact you debug the configuration *from*: the Asserts tab opens on a red banner
naming each rule that could not run, and the Server tab says why each server assert decided nothing,
or why the payload was refused.

The verdict line that opens the summary block every run prints carries one clause per side,
never a rollup:
`Asserts: FAIL — …` for the client gate, then one `Server <component>: …` clause per server
component, e.g. `Server kafka: FAIL — 3 declared, 2 passed, 1 failed`. With server asserts declared
and nothing judged it names the state in the header chip's word: `Server: NOT COLLECTED — 12 server
assert(s) declared, no payload given` on the interim report, `Server: REFUSED — …, the payload given
was refused` over a refused payload.

```bash
set -o pipefail   # without it the pipeline's status is tee's, not JMeter's
jmeter -n -t plan.jmx -l out/results.jtl -Jwebinsight.report.output=out | tee run.log
# the exit code tells you whether; the marker tells you WHERE
REPORT=$(sed -n 's/^WEB-INSIGHT-REPORT: OK //p' run.log)
```

A run that gates on the `.json` or the `.xml` should treat `INCOMPLETE` as fatal even though the
HTML exists — the file it was going to read is the one that is missing.

## JUnit XML Output

Optional JUnit XML for native CI/CD integration. Disabled by default.

**Enable:** `-Jwebinsight.report.junit=true` or GUI checkbox.

**Output:** `<name>.xml` beside `<name>.html` — `web-insight-report.xml` under the default report
name.

Two testsuites — one testcase per top-level sampler in the first, one per declared client rule in
the second (written only when a client rule is declared) — plus one per server component on a
report that judged server asserts:

| Suite `name` | `classname` | One testcase per |
|---|---|---|
| `<testName>` | `performance` | top-level sampler |
| `<testName> - assertions` | `assertions` | declared client rule |
| `<testName> - <componentId>` | `server-metrics` | server assert on that component |
| `<testName> - (no gate)` | `server-metrics` | server assert that named no declared component — only when there is one |
| `<testName> - serverMetrics` | `server-metrics` | the payload's state, and each server assert the file makes unrunnable — only when server asserts are declared and nothing was judged ([below](#when-server-asserts-are-declared-and-nothing-was-judged)) |

The root `<testsuites>` element's `tests` and `failures` count the **sampler suite only** — one
test per top-level sampler, however many rules or server asserts the file declares.

The example is the run the JSON example above describes, with its server suites left out (one is
shown under [Server component suites](#server-component-suites)):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="Checkout Load Test" tests="2" failures="1" time="75.0">

  <!-- One testcase per top-level sampler. The message names every metric that failed
       the sampler: p95, p99, mean and errorRate in their fixed wording, any other by its
       measurement and the band it breached. A scope: "overall" rule judges the whole run,
       not a sampler, so it is never named here: run-throughput below is in the assertions
       suite only. -->
  <testsuite name="Checkout Load Test" tests="2" failures="1" time="75.0">
    <properties>
      <property name="webinsight.runId" value="3f2a7c19b40de85a"/>
    </properties>
    <testcase name="GET /api/products" time="0.120" classname="performance">
    </testcase>
    <testcase name="POST /checkout" time="0.891" classname="performance">
      <failure message="ASSERT FAIL: P95 2340ms exceeds threshold; p50 812.50 ms breached &gt; 800 ms">
P95: 2340ms (FAIL)
p50: 812.50 ms (FAIL)
      </failure>
    </testcase>
  </testsuite>

  <!-- One testcase per declared rule, run-level rules included. -->
  <testsuite name="Checkout Load Test - assertions" tests="6" failures="3" skipped="1" time="0.0">
    <properties>
      <property name="webinsight.runId" value="3f2a7c19b40de85a"/>
    </properties>
    <testcase name="checkout-p95" time="0.000" classname="assertions">
      <failure message="Assertion &apos;checkout-p95&apos;: p95 2340 ms on POST /checkout breached &gt; 1000 ms - Payment gateway budget, contract §4.2">
target:   POST /checkout
metric:   p95
criteria: warn &gt; 650 ms  ·  fail &gt; 1000 ms
  [FAIL] POST /checkout = 2340 ms
      </failure>
    </testcase>
    <testcase name="checkout-p50" time="0.000" classname="assertions">
      <failure message="Assertion &apos;checkout-p50&apos;: p50 812.50 ms on POST /checkout breached &gt; 800 ms">
target:   POST /checkout
metric:   p50
criteria: fail &gt; 800 ms
  [FAIL] POST /checkout = 812.50 ms
      </failure>
    </testcase>
    <testcase name="products-p95" time="0.000" classname="assertions">
    </testcase>
    <testcase name="run-throughput" time="0.000" classname="assertions">
      <system-out>WARN: Assertion &apos;run-throughput&apos;: throughput 1164.85 /s on Whole run breached &lt; 1200 /s</system-out>
    </testcase>
    <testcase name="parked-rule" time="0.000" classname="assertions">
      <skipped message="rule was parked with enabled: false"/>
    </testcase>
    <testcase name="checkout-p95x" time="0.000" classname="assertions">
      <failure message="rule could not run: unknown metric &apos;p95x&apos; — did you mean &apos;p95&apos;?">
target:   POST /checkout
metric:   p95x
criteria: fail &gt; 1000
problem:  rule could not run: unknown metric &apos;p95x&apos; — did you mean &apos;p95&apos;?
      </failure>
    </testcase>
  </testsuite>
</testsuites>
```

A rule's message ends with ` - <description>` when the rule declares one, as `checkout-p95`'s does.
The `failures` count of the assertions suite is the two breached rules plus the one that could
not run; the parked rule is its one `skipped`.

Each number printed beside a band follows the rule for a client `actual` above: two decimals, plus
the digits that keep it off a band it is not on, or on its own side of one. This covers the
failure message, every `[STATUS] sampler = value` line, and a server assert's message and
`actual:` line, which take two decimals here where the JSON's `serverAsserts[].actual` takes four.
For example,
`p50 119.361 ms on GET /api/products breached > 119.36 ms`, where two decimals printed `119.36`.
Unlike the JSON, a value here is never printed as `0` when it is not zero. At a decimal tie the
number is the one the JSON writes for a client `actual`: 1.005, whose double sits just below the
tie, prints `1.00`, not `1.01`.

A number and its unit are separated by a space, as the band beside them is: `610.50 ms`,
`8.25 /s`, `2.50 %`. A unitless metric (apdex) has none. One sentence is a stated exception: the
`LOAD_NOT_ACHIEVED` reason quotes the load rule's measurement with its unit attached and only the
band spaced, `load rule 'load:throughput' FAILED: throughput 24.76/s breached < 100000 /s`, in the
`<skipped>` message here and in the JSON's `reason` alike. A pipeline matching that sentence should
expect `24.76/s`. A rule that judged several samplers is
reported by its **worst judged match** — the match the JSON's `attainment` describes (see above) —
so a rule failing on two samplers names the slower one, not the first.

**The sampler suite names every failing metric.** p95, p99, mean and errorRate have fixed wording
(`P95 611ms exceeds threshold`), which a pipeline can grep. Any other metric that failed a
sampler is named by the measurement that failed it, beside the band it breached:
`ASSERT FAIL: p50 119.361 ms breached > 119.36 ms; throughput 8.299 /s breached < 8.3 /s`. Two rules failing one
metric on one sampler measure the same value, which is named once, beside the band of the first rule
in the file to fail it — and written with the digits that keep it on its own side of every band
that judged that metric on that sampler, passing rules' included: a p50 of 214.7456 that fails
`> 119.36` and passes `≥ 214.75` reads `214.746`, not `214.75`. The failure body lists every
metric judged on the sampler the same way, the four with fixed wording first: `p50: 119.361 ms (FAIL)`.
Only rules that judged the sampler itself count: a `scope: "overall"` rule judges the whole run
and is reported in the assertions suite alone.

**Why two.** One testcase per rule is the right shape for a rule engine — and a
`scope: "overall"` rule has no sampler to hang off, so only a rule suite can carry the gate's
broadest checks. The per-sampler suite answers a different question, one testcase per top-level
sampler, with samplers no rule judges appearing as passing tests, so its `tests=` and testcase
names stay the same however the rules change. Each suite keeps the shape its consumer reads.

A **warning** is recorded as `<system-out>`, not a failure — a build that stopped on a warn band
would make warn bands pointless.

**A rule that decided nothing is `<skipped>` — unless the file got it wrong.** Parked, unmatched,
or a metric this run could not produce: those are skips, and a dashboard should not paint them
red. A rule that could not run at all is a `<failure>`.

That split is a practical judgement as much as a principled one. Most CI dashboards render a skip
as a grey line nobody clicks, so a broken rule written as a skip would be the one state that needs
someone to open the file and the one state nobody sees.

Either way the message is never just the word — it carries the sentence naming the problem, so a
CI report showing only this XML still says what to fix:

```xml
<failure message="rule could not run: unknown metric &apos;p95x&apos; — did you mean &apos;p95&apos;?">
target:   POST /checkout
metric:   p95x
criteria: fail &gt; 1000
problem:  rule could not run: unknown metric &apos;p95x&apos; — did you mean &apos;p95&apos;?
</failure>
<skipped message="rule was parked with enabled: false"/>
```

So the two counts are:

```
configErrors == gate.notJudged.invalidRule + gate.notJudged.sourceFailed + gate.notJudged.lowCoverage
failures     == gate.failed + configErrors
skipped      == gate.notJudged.total - configErrors
```

`configErrors` is the set of reasons that mean *the file asked for a check that never ran* — a
broken rule, and for server asserts a series that could not be collected or data below the coverage floor.
Which reasons count is decided once, in the engine, not by a subtraction at each output: the
JUnit `<failure>`, the `failonerror` exit and this arithmetic all read the same decision.

A config error also fails the JMeter process itself under `failonerror`, which is on by default,
so in practice CI sees it twice. A breached fail band is a `<failure>` here and exits 0 unless
`failonassert=true`.

### Server component suites

Every component's suite is written exactly as the client suite is, by the same decision: a
breached fail band and a check that never ran (`SOURCE_FAILED`, `LOW_COVERAGE`, `INVALID_RULE`) are
`<failure>`, the other not-judged reasons are `<skipped>`, a warning is `<system-out>`. The
arithmetic above holds for it with `serverComponents[i]` in place of `gate`. Every component can
fail the build, so none is exempt.

Each testcase body names the assert's `component:`, its `metric:` when it names a catalogue metric,
then the `query:` that was run, `reduce:` (left out when the reducer could not be read — the
`problem:` line says why), `divideBy:` when it is a ratio, and `criteria:`, and what was measured. The failure message, `criteria:` and `actual:` are in the unit the verdict compares:
`B/min` for a `slope`, `msgs ÷ load:throughput` for a `divideBy`.

```xml
  <testsuite name="Checkout Load Test - kafka" tests="4" failures="1" skipped="2" time="0.0">
    <properties>
      <property name="webinsight.runId" value="3f2a7c19b40de85a"/>
    </properties>
    <testcase name="up-fails" time="0.000" classname="server-metrics">
      <failure message="Server assert &apos;up-fails&apos;: max 49 msgs breached &gt; 20 msgs - a real breach">
component: kafka
query:     up
reduce:    max across max
criteria:  warn &gt; 10 msgs  ·  fail &gt; 20 msgs
actual:    49 msgs
coverage:  100% of the collected window
instances: 1
      </failure>
    </testcase>
    <!-- … -->
  </testsuite>
```

The `reduce:` line in the body is the display sentence (`max sustained for 10s across max`); a
consumer that needs the reducer reads the structured `reduce` object in the JSON instead. The
`coverage:` line is of the window the collector fetched, not the one the verdict was taken over, and
says so; an assert that names no component at all reads `component: (no component)`.

### When server asserts are declared and nothing was judged

The interim report (step 1, before `wir-collect` has run) and a report whose payload was refused
(missing, another run's, unreadable) have no component suites, because no component was judged.
They get one suite instead, `<testName> - serverMetrics`, that says why:

- **`serverMetrics.payload`**, the payload's state. A refused payload that the declared asserts
  depended on is a `<failure>` whose message is the CI/CD JSON's `serverMetrics.payload` ERROR
  finding, word for word, so the `junit` step sees it; the run exits 1 for it only under
  `webinsight.report.server.failonerror=true`. On the interim report it is a `<skipped>` that says to run `wir-collect`: no payload is
  not an error, and a `<failure>` there would mark every step-1 build unstable. A refused payload
  nothing depended on (every assert disabled) is `<skipped>` with its WARNING.
- **One `<failure>` per server assert the file makes unrunnable whatever is collected** (an
  unreadable reducer, a `requiresLoad` naming no client rule, a component of unknown type): the
  testcase step 3 writes for it, message and body alike. A component of unknown type counts once in
  the console's `SERVER NOT JUDGED` line, but each assert on it gets its own `<failure>`, as at step 3.

The example is the same JTL exported again with its payload missing, so it carries the same
`runId`:

```xml
  <testsuite name="Checkout Load Test - serverMetrics" tests="1" failures="1" skipped="0" time="0.0">
    <properties>
      <property name="webinsight.runId" value="3f2a7c19b40de85a"/>
    </properties>
    <testcase name="serverMetrics.payload" time="0.000" classname="server-metrics">
      <failure message="the server-metrics payload does not exist: &apos;server-metrics.json&apos; — no server assert was judged. The server asserts this run declares depend on it, so this is a configuration error">
payload:   refused (MISSING)
asserts:   3 declared, none judged
      </failure>
    </testcase>
  </testsuite>
```

A run that declares no server assert gets no server suite. On all of these the CI/CD JSON carries
no `serverStatus` and no `serverComponents[]`.

**Why `<properties>` sits where it does, in every suite.** There is no real JUnit XML schema —
it is a de-facto format with materially different parsers — but the two strict ones that exist
agree here: Surefire's `surefire-test-report.xsd` and the Windyroad `JUnit.xsd` that Azure
DevOps names as its JUnit schema both model `testsuite` as an `xs:sequence` with `properties`
*ahead of* `testcase`. Jenkins' xUnit plugin validates against an XSD from 2.0.0 on, so a block
in the wrong place is not "ignored by a lenient parser" — it is a rejected file, and a rejected
file means a quality gate that silently stopped running. It goes in *every* suite — the sampler
suite, the assertions suite and one per server component — because
Windyroad's schema omits `minOccurs` on `properties`, making it effectively required in every
one, and it is a `<property>` rather than an attribute on `<testsuite>` because no JUnit schema
declares `xs:anyAttribute` there.

Works with: Jenkins, GitHub Actions, Azure DevOps, GitLab CI, CircleCI, and any tool consuming JUnit XML.

### A minimal pipeline

Run the test, generate the report with `-g -o` into an empty directory, publish the XML and keep
the whole directory. With server asserts, put the collector step between the two `jmeter` lines
and pass `-Jwebinsight.report.servermetrics` to the second — [Tutorial 13](13-Server-Tab.md#the-three-steps)
has that version of the Jenkinsfile.

Jenkins:

```groovy
stage('Performance') {
  steps {
    sh '''
      rm -f results.jtl
      rm -rf report
      jmeter -n -t plan.jmx -l results.jtl
      jmeter -g results.jtl -o report \
        -Jwebinsight.report.annotations=report-annotations.json \
        -Jwebinsight.report.junit=true
    '''
  }
  post {
    always {
      junit 'report/web-insight-report.xml'
      archiveArtifacts artifacts: 'report/**'
    }
  }
}
```

GitHub Actions, gating on the verdict with the DEFAULT line above:

```yaml
- name: Load test and report
  run: |
    rm -f results.jtl
    rm -rf report
    jmeter -n -t plan.jmx -l results.jtl
    jmeter -g results.jtl -o report \
      -Jwebinsight.report.annotations=report-annotations.json \
      -Jwebinsight.report.junit=true
- name: Gate on the verdict
  run: jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"' report/web-insight-report.json
- name: Keep the report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: web-insight-report
    path: report/
```

`rm -f results.jtl` matters because `-l` appends to an existing JTL, and `rm -rf report` because
`-g -o` requires an empty output directory. On GitLab CI, name the XML under
`artifacts: reports: junit:` to have it shown on the merge request.

## External Data Mode

For very large tests (chart data above the threshold, 10 MB by default):
- Chart data is written to a separate **`.js`** file — `<report-basename>-data.js`, or
  `web-insight-data.js` when the report keeps its default name
- The report loads it with a **`<script src>`** tag, which sets `window.__WIR_DATA__`. Not
  `fetch()`: that is blocked by CORS on `file://`, so a fetch would force you to serve the
  report over HTTP. The script tag works when the file is simply double-clicked.
- Keep the `.js` file next to the HTML. The report checks that the payload carries its own
  `runId`, and **a mismatch is fatal to the charts**: it names both runs, replaces every chart
  pane with "Chart withheld", and draws nothing. The summary, sampler and error tables are
  server-rendered from this run's own data, so they remain and are correct.

  No charts beat stale charts under a banner. A chart from another run is *wrong output*, and a
  banner above a plausible-looking chart is read by nobody once the numbers below it look
  reasonable. If you have a report in this state, regenerate it, or give each run its own output
  directory.

  A **missing** payload is a different failure and keeps its own message: nothing is wrong there,
  only absent, so the page still renders what it has.

  Either way the asserts, the server asserts and their verdicts were in that file. The Asserts and
  Server tabs and the Summary's Asserts card (and its server card, on a run with a server chip in
  the header) say **UNAVAILABLE** and why, the header keeps the verdicts the
  run was judged by, and the page offers no Download of `report-annotations.json` — a file saved
  from a page that never received the annotations would replace yours with an empty one.
- HTML file stays lightweight while preserving all interactivity
- Controlled by two real properties:

  | Property | Meaning |
  |---|---|
  | `-Jwebinsight.report.externaldata=true` | force external mode regardless of size |
  | `-Jwebinsight.report.externaldata.threshold=<chars>` | size above which it switches automatically (default 10000000; values ≤ 0 are rejected with a warning) |

## Two Report Generation Paths

| Path | Command | Error Detail | JMX Change |
|------|---------|-------------|------------|
| **Listener** | Add listener to test plan | Full (URL, response body) | One-time |
| **CLI `-g -o`** | `jmeter -g results.jtl -o report/` | Partial (no response body) | None |

Both produce the same interactive report. The listener captures richer error detail during live execution.

**Server asserts are judged on the `-g -o` path.** The metrics are collected after the run ends,
so the listener's report is the interim one: it judges the client rules, and its server asserts
are not evaluated yet. The listener ignores `webinsight.report.servermetrics` when the run ends
(`jmeter.log` says so), so one `run.properties` can serve both commands. Under `-n -t … -e -o`, where
JMeter builds the reports straight after the run, a server data file it names is never read: no
export of this run can exist yet, so the exporter keeps the listener's report and says the file was
not read (`WEB-INSIGHT-REPORT: SKIPPED`). In the JMeter GUI, the
listener's **Rebuild report with server data** button runs the `-g -o` step over the listener's own
results file: it reads `webinsight.report.servermetrics`, else the listener's **Server data (CSV)**
field, and writes over the interim report. See [Getting Server Data In](14-Server-Data.md).

> **The `-g -o` exporter is registered machine-wide, not per test plan.** Installing it
> ([Tutorial 1](01-Overview.md)) adds this line to `$JMETER_HOME/bin/reportgenerator.properties`:
>
> ```properties
> jmeter.reportgenerator.exporter.webinsight.classname=com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter
> ```
>
> That is what makes
> `jmeter -g old.jtl -o out` work at all — but it also means **every** `-g`/`-e` run on that
> machine emits a Web Insight report, including other people's test plans that have never heard
> of this plugin. Remove that line from `reportgenerator.properties` to opt out.
>
> With no `-o` and no `output_dir` property, the exporter takes the first writable of: the
> directory of the JTL being exported → the working directory → `~/.jmeter-web-insight-report` →
> system temp, and says which it chose. There is no fixed default folder inside the JMeter
> installation: that is read-only on most managed installs, and a collision point every `-g` run
> on the machine would share. An `-o` or `output_dir`
> you set explicitly is never relocated; it fails instead. The intent line logged at the start of
> generation always prints the resolved directory absolutely, so check there if a report is not
> where you expected.
>
> When both paths are active (`-n -t … -e -o dir`), the listener writes first and the exporter
> skips — but only when the report already there is **provably the same run's**, which it decides
> by comparing `runId` against the `.json` sibling, not by matching a name. A name cannot tell
> "the listener in this very run already wrote this" from "somebody else's report is in the way",
> and those two need opposite answers. The name is still how candidates are *located* (a
> `${timestamp}` filename was stamped at the listener's second, not the exporter's), and the
> exporter still prefers the filename the listener actually resolved over the raw `-J` value.
> Anything that cannot say which run produced it is overwritten, with a line saying so.
