# Server Tab

> New to server metrics? Start with [Getting Server Data In](14-Server-Data.md): the step-by-step for the JMeter GUI, any terminal, and a script.

> **Where it sits:** after Asserts, before Notes. It is always shown, including on a run that
> declared no server metrics, where it says so and how to add some.

## Expected against actual

A client assert judges what JMeter measured. A **server assert** judges what the system under test
did while JMeter was measuring it: CPU, heap, consumer lag, connection pools. Anything Prometheus
holds, or anything you can export as a CSV.

The model has three parts, and it is the same three parts every time:

| | What it is | Where it comes from |
|---|---|---|
| **Expected** | The components under test, and the server asserts about them: a metric, how to reduce it to one number, and the bands that number must stay inside | You write them, in the `serverMetrics` block of `report-annotations.json` (or in this tab) |
| **Actual** | The metrics, fetched over the run's own window | `wir-collect`, after the run, into `server-metrics.json`. Where it fetches from is a command-line flag, never part of the file |
| **The comparison** | Each server assert's verdict, per component, plus `serverStatus` in the CI/CD JSON and a JUnit suite per component | The report, generated with both files |

You write what you expect. `wir-collect` fetches what happened. The report compares the two.

That order matters. You can write server asserts before anything has been collected. The tab shows
them, the editor works, and each row says it is waiting for its actual half. A report that has no
metrics yet is a good place to write the expectations.

Client and server verdicts are **kept apart on purpose**. The client badge, the gate strip and the
CI/CD JSON's `status` describe the client rules and nothing else. The server side has its own header
chip, its own `serverStatus` and its own JUnit suites. Nothing anywhere folds the two into one word.
A pipeline that wants one answer writes the `and` itself (see [Gating a pipeline](#gating-a-pipeline)).

A minimal file:

```json
{
  "assertions": [
    { "id": "checkout-p95", "target": { "sampler": "POST /api/checkout" },
      "metric": "p95", "fail": { "gt": 800 } }
  ],
  "serverMetrics": {
    "components": {
      "orders-db": { "type": "postgres", "description": "Orders DB",
                     "match": "instance=\"orders-db:5432\"" },
      "api":       { "type": "custom",   "description": "API pods" }
    },
    "asserts": [
      {
        "id": "db-connections",
        "component": "orders-db",
        "metric": "connections.used.pct",
        "warn": { "gt": 70 },
        "fail": { "gt": 90 }
      },
      {
        "id": "api-cpu",
        "description": "CPU of the API pods while the test ran",
        "component": "api",
        "query": "sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"shop\", container=\"api\"}[1m]))",
        "unit": "cores",
        "reduce": { "kind": "max", "over": { "sustainedFor": "1m" } },
        "fail": { "gt": 3.5 }
      }
    ]
  }
}
```

**Components** name what is being measured. `type` is a type from the built-in
[catalogue](15-Server-Catalogue.md#the-catalogue) (`postgres`, `kafka`, `redis`, `host-linux`, `aws-ec2`, `oracle`,
`influxdb2`, `influxdb3`, `jvm`, `http-service`, `kubernetes-pod`, `mysql`) or `custom`.
`description` is the label the card, the header chip, the Summary card and the CI/CD JSON's `label`
show; the id names the JUnit suite and the console line. `match` is optional: a
PromQL label selector that narrows every catalogue query on that component to the right instances.

A server assert names its `component` and **either** a catalogue `metric` **or** its own `query`:

- `db-connections` asks the catalogue for `connections.used.pct`. The query, the unit (`%`), the
  reducer (`max`) and `across` all come from the catalogue, narrowed by the component's `match`.
  Anything the assert writes itself (`unit`, `reduce`, `across`) overrides the catalogue's.
- `api-cpu` writes its own PromQL. That is the escape hatch for anything the catalogue does not
  cover, and the only way on a `custom` component. Read it as: *take each pod's series over the
  run, find the highest level it held for a whole minute, take the worst pod, and fail if that is
  above 3.5 cores.*

Writing both `metric` and `query`, or neither, is refused (`INVALID_RULE`). The `3.5`, the `70` and
the `90` are this example's expectations, not recommendations. The numbers are yours to set.

**Every component can fail the build.** There is no observe-only component. To watch a metric
without failing anything, give its assert a warn band and no fail band: a WARN never moves the exit
code.

**No connection details are in the file.** Not the Prometheus URL, not a token, not a file path.
They are flags to `wir-collect` (see [Where the metrics come from](16-Server-Reference.md#where-the-metrics-come-from)).
The annotations document is written into every report, so anything in it is published.

---

## With a CSV: two commands

When the server data is a file exported from your monitoring tool (Grafana, Datadog, InfluxDB,
CloudWatch, Telegraf, or your own script), `jmeter -g` reads it itself. There is no collector to
run, no `--test-name` to match and no ramp-up to repeat: the run's window, identity and ramp-up are
taken from the JTL and the title of the very report being built. The same two commands work in
Windows PowerShell or cmd, macOS and Linux:

```
jmeter -n -t plan.jmx -l run/results.jtl -q run/run.properties
jmeter -g run/results.jtl -o run/report -q run/run.properties
```

`run/run.properties` holds the settings, so no `-J` argument has to survive a shell's quoting:

```properties
webinsight.report.annotations=C:/runs/checkout/report-annotations.json
webinsight.report.servermetrics=server.csv
# only when the file needs it - each is a collector flag, spelt as the property
# table in Tutorial 16 says (timezone is --time-zone, clockoffset --clock-offset);
# the step and a Grafana wide layout are read from the file when left out:
# webinsight.report.servermetrics.step=1s
# webinsight.report.servermetrics.timezone=Europe/Berlin
# webinsight.report.servermetrics.clockoffset=-30s
# webinsight.report.servermetrics.from=datadog
# webinsight.report.servermetrics.layout=wide
```

- `webinsight.report.servermetrics` takes either a CSV or a `server-metrics.json` payload; which one
  it is is read from the file. A relative path that does not exist where JMeter runs is looked up
  beside the JTL.
- Write paths with **forward slashes**. A properties file reads `\t`, `\f`, `\r` and `\n` in
  `C:\tests\run` as control characters; the plugin warns when a path holds one.
- `-o` must be a folder that does not exist yet, or is empty; never the folder the JTL or the CSV is in.
- The collector's console lines (`wir-collect: OK …`, the run window, unasked series) are printed by
  `jmeter -g`, and a CSV it cannot read is a refused payload: said, and never fatal by default.
- The report is stamped with the CSV's own file time, so rebuilding from the same file keeps the
  Server tab's drafts.
- In the JMeter GUI, the listener's **Server data (CSV)** field and its **Rebuild report with server
  data** button do the second command for the run's own **Results file (JTL)**, and write over the
  report the run wrote. For a JTL written by `-l` or another listener, **Tools › Generate HTML
  report** does it: the results file, the properties file, and a new output folder. See
  [Getting Server Data In](14-Server-Data.md#by-hand-in-the-jmeter-gui).

Prometheus and `--recording` still use the collector, as below.

## The three steps

Server metrics are fetched **after** the run, over the window the JTL recorded. That gives you a
pipeline of three commands. Each one is an ordinary process with an ordinary exit code.

```bash
#!/usr/bin/env bash
set -euo pipefail

JTL=results.jtl
ANNOTATIONS=report-annotations.json

rm -f "$JTL"     # -l appends; a JTL left by the last run widens the window to both runs
rm -rf report    # jmeter -g -o refuses a directory that is not empty

# 1. The run. If the plan carries the Web Insight listener, it writes an interim report that
#    judges the client rules. The server asserts are shown there, waiting for their metrics.
jmeter -n -t plan.jmx -l "$JTL" -Jwebinsight.report.annotations="$ANNOTATIONS"

# 2. The actual: fetch what the server asserts ask for, over the run's own window.
#    Exits 0 whenever it wrote the payload, even if the source was down.
./wir-collect.sh --jtl "$JTL" --annotations "$ANNOTATIONS" --out server-metrics.json \
  --prometheus-url https://prometheus.example.internal/api/v1 --prometheus-token-env PROM_TOKEN

# 3. The comparison: the report that judges both sides, decides the exit code, and is the
#    one to archive.
jmeter -g "$JTL" -o report \
  -Jwebinsight.report.annotations="$ANNOTATIONS" \
  -Jwebinsight.report.servermetrics=server-metrics.json \
  -Jwebinsight.report.junit=true
```

The same steps in a Jenkinsfile, with the token supplied by Jenkins and never written down:

```groovy
stage('Performance') {
  steps {
    withCredentials([string(credentialsId: 'prometheus-token', variable: 'PROM_TOKEN')]) {
      sh '''
        rm -f results.jtl
        rm -rf report
        jmeter -n -t plan.jmx -l results.jtl \
          -Jwebinsight.report.annotations=report-annotations.json
        ./wir-collect.sh --jtl results.jtl --annotations report-annotations.json \
          --out server-metrics.json \
          --prometheus-url https://prometheus.example.internal/api/v1 \
          --prometheus-token-env PROM_TOKEN
        jmeter -g results.jtl -o report \
          -Jwebinsight.report.annotations=report-annotations.json \
          -Jwebinsight.report.servermetrics=server-metrics.json \
          -Jwebinsight.report.junit=true
      '''
    }
  }
  post {
    always {
      junit 'report/web-insight-report.xml'
      archiveArtifacts artifacts: 'report/**, server-metrics.json'
    }
  }
}
```

Points that cost people a build:

- **Step 3 names the annotations file.** `jmeter -g -o` demands an empty output directory, so there
  is nothing beside the report for it to auto-detect. Without
  `-Jwebinsight.report.annotations`, step 3 declares no server asserts and judges nothing.
- **Step 3 is the report that gates.** Pass gating flags such as
  `-Jwebinsight.report.failonassert=true` to step 3 only. On step 1, a client FAIL would stop a
  `set -e` script, or a Jenkins `sh` step, before the server metrics were ever compared.
- **Server data never fails the build by default.** A series that could not be collected
  (SOURCE FAILED), data below the coverage floor, a server assert the file got wrong or a refused
  payload is said on the console (`WEB-INSIGHT-REPORT: SERVER NOT JUDGED`), at the top of the
  Server tab and in the CI/CD JSON, and the exit code stays as the client side decides it. To make
  those fatal, add `webinsight.report.server.failonerror=true`; or gate on the JSON with the
  [STRICT](#gating-a-pipeline) line, which refuses the same things.
- **A title on step 3 means a `--test-name` on step 2.** The run's identity hashes its name, and
  `jmeter -g` names the run by its report title. With `-Jwebinsight.report.title="Checkout soak"` on
  step 3, step 2 needs `--test-name "Checkout soak"`. Otherwise the payload is refused as another
  run's (see [wir-collect](16-Server-Reference.md#wir-collect)).
- **A ramp-up is given to both, in milliseconds.** `wir-collect --rampup 60000` and
  `-Jwebinsight.report.rampup=60000`. If they disagree, the report says so as a finding, because
  the asserts are judged over the payload's window while the charts mark the report's ramp-up.
- **Metrics can be re-collected without re-running the test.** Steps 2 and 3 only read the JTL.
  Re-collect after a Prometheus outage, or after editing the server asserts, and regenerate.

---

## Reading the tab

![Server tab](images/61-server-overview.png)

From the top: the judged window, the Collection line, the actions, one filter row, any findings,
and then one card per component holding its server asserts. The client filter bar (samplers,
metric, granularity, time range) is hidden on this tab, because none of it applies here. It comes
back on the other tabs.

### Before the server data is added

The report written when the run ends (and any `-g` report built without server data) cannot judge
the server asserts yet, so the Server tab says what to do instead, under **Add the server data**:

1. **The window to export**: the run's exact start and end in UTC, in your local time and in epoch
   milliseconds. Export that period from your monitoring tool; a minute either side is fine, because
   rows outside the run are counted and ignored.
2. **The names to use**: one row per series the server asserts need — the `component` and the
   `metric` exactly as the CSV must write them (a catalogue metric's name, or a custom assert's
   query text), the unit, and the name each supported tool's export gives a catalogue metric.
   **Download a CSV template** writes `server-metrics-template.csv`: the header and one row per series
   at the run's start, ready to fill in.
3. **How to add it**: in the JMeter GUI, the listener's **Server data (CSV)** field and **Rebuild
   report with server data** button; for a JTL written by `-l` or another listener, JMeter's
   **Tools › Generate HTML report**; or `jmeter -g <results.jtl> -o <new folder> -q run.properties`
   from any shell.

The console prints the same at the end of the run, as one line starting
`WEB-INSIGHT-REPORT: NEXT`, and the CI/CD JSON carries it for a script: `window` and `serverData`
(see [Export & CI/CD](11-Export-Integration.md)).

### The Summary card

The Summary tab carries a **Server** card under the Asserts card, in the same shape:

![Summary server card](images/59-summary-server-card.png)

- **The verdict the run was gated on**: the worst component's, which is the server verdict. It is
  the build's own word, the CI/CD JSON's `serverStatus` and the header chip's, never worked out
  again on the page.
- **What it covers**: `2 components, 12 server asserts — the worst component is the server verdict`.
- **One count per component**, by its description: `Kafka FAIL`, `DB WARN`. Asserts that name no
  declared component are counted too, as `(no component) NOT EVALUATED`, so a `NOT EVALUATED` word
  always has its entry beside it.
- **Needs looking at**: the three worst server asserts, breaches and checks that never ran, worst
  first, each named as the board names it: the component by its description, then the assert —
  `Kafka · lag-spike`, or for a catalogue metric its label with the id under it
  (`Event bus · Consumer lag, worst partition (msgs)`, `events-consumer-lag-max`). A breach shows
  the actual against the limit (`900 msgs vs > 500 msgs`); a check that never ran shows its reason.
- **Open Server →** opens this tab.

Before anything is collected (step 1's report) the card reads `NOT COLLECTED`, and says how many
server asserts were declared. Over a refused payload it reads `REFUSED`. The header chip says the
same word. On a large report whose data file (`web-insight-data.js` beside the default report,
`<name>-data.js` beside a report you named) did not load, the card reads `UNAVAILABLE` and says the
server verdict in the header came from the run itself and is correct. On step 1's report or over a
refused payload, it still reads `NOT COLLECTED` or `REFUSED`, and says it cannot list what was
declared.
A run that declared no server asserts has no card at all: it has not passed anything, so nothing
claims it did.

### The header chip

Beside the client badge, one chip speaks for the server side:

![Server chip](images/60-server-chip.png)

| Chip | Means |
|---|---|
| `Server · Kafka FAIL · 1 warn · 1 pass` | The worst component (Kafka) and its status, then every other component counted by its state, in the same order (FAIL, NOT EVALUATED, WARN, PASS; only the states that occur), as an alerting tool's summary counts them. A WARN behind a component that judged nothing is never hidden |
| `Server · nothing judged` | The asserts were evaluated, but no component was reported |
| `Server · NOT COLLECTED` | Server asserts are declared and no payload was given yet: the interim report, step 1 |
| `Server · REFUSED` | Server asserts are declared and the payload given was refused. The tab says why. It does not change the exit code unless `webinsight.report.server.failonerror=true` is set |
| *(no chip)* | No server assert is declared: no `serverMetrics` block, or components and no asserts yet |

`NOT COLLECTED` and `REFUSED` are drawn in the neutral style. They are states, not verdicts: the
CI/CD JSON has no `serverStatus` on such a run. JUnit carries a `serverMetrics` suite that says the
same thing (see [Export & CI/CD](11-Export-Integration.md#when-server-asserts-are-declared-and-nothing-was-judged)).

"Worst" is ranked **FAIL > NOT EVALUATED > WARN > PASS**. NOT EVALUATED ranks above WARN because a
component the build depended on that decided nothing is worse news than a warning. Clicking the chip
opens this tab.

The client badge does not change. When no client rule gave a verdict (the file declares none, or
none of them decided anything), it reads `SERVER ASSERTS ONLY` once the server asserts are judged,
and `ASSERTS NOT EVALUATED` before that (step 1's report, or over a refused payload). Either way it
tells you to look at the chip rather than claiming a pass.

The CI/CD JSON's top-level **`serverStatus`** is the same fold, so the chip and the JSON cannot
disagree:

```json
{
  "status": "FAIL",
  "serverStatus": "FAIL",
  "serverComponents": [
    { "id": "kafka", "label": "Kafka", "type": "custom", "collectionId": "df8778b7fa474d95",
      "status": "FAIL", "declared": 4, "evaluated": 3, "passed": 1, "warned": 0, "failed": 1,
      "notJudged": { "total": 2, "disabled": 1, "loadNotAchieved": 1, "…": 0 } },
    { "id": "db", "label": "DB", "type": "custom", "status": "WARN", "…": "…" }
  ],
  "serverAsserts": [
    { "id": "up-fails", "component": "kafka", "query": "up", "unit": "msgs",
      "reduce": { "kind": "max" }, "across": "max", "status": "FAIL", "actual": 49.0,
      "coverage": 1.0, "instances": 1, "warn": { "gt": 10.0 }, "fail": { "gt": 20.0 },
      "breached": { "gt": 20.0 } }
  ]
}
```

`serverStatus` is present exactly when `serverComponents` is. A server assert that names a catalogue
metric also carries `"metric"` in `serverAsserts[]`. The full shape, and the JUnit suites, are in
[Export & CI/CD](11-Export-Integration.md).

### PASS can sit beside a problem, and the exit code reports it

A component's status is the worst of its asserts **that decided something**. That matches the
client gate, where a broken rule contributes nothing to `gate.status`. The consequence: suppose a
component holds one server assert that PASSed, and another whose source failed (`SOURCE FAILED`) or
whose data came in under the floor (`LOW COVERAGE`). The component reads **PASS**, and so do
`serverStatus` and the chip.

The status is the verdict on what was measured. The check that never ran is reported beside it: the
`SERVER NOT JUDGED` console line, the "not judged" box at the top of the Server tab, the Summary
card's "Needs looking at", the JUnit `<failure>` and the STRICT jq line below. It does not change the
exit code unless `webinsight.report.server.failonerror=true` is set, in which case the run exits 1
and the console line reads `CONFIG ERROR`. The DEFAULT line reads that run as a pass; use STRICT to
refuse it from the JSON.

### The header and filters

**Window judged** comes first: the run's first and last sample from the JTL, marked
`ramp-up excluded` when a ramp-up was given and `collection.excludeRampUp` is on (the default).
Before anything is collected, and over a refused payload, it reads `nothing collected yet`.

Under it, the **Collection** is one line, such as
`Collection: metrics PARTIAL · collected 2026-10-03 12:35:40 UTC+03:00`. It opens to the
detail (see [The Collection line and the findings](#the-collection-line-and-the-findings)).

Every time on this tab is in your browser's time zone and says which, as `UTC±hh:mm`. Each one
carries its own offset, so a window from winter and a collection from summer can differ by an
hour in the same zone. The chart ticks are bare times; the window line above them names the offset.

Then the actions:

| Button | Does |
|---|---|
| **+ Add server assert** | Opens the [editor](#the-editor) on a new server assert. Shown once a component is declared |
| **+ Add component** | Opens the [Add component](#adding-a-component) dialog |
| **Generate from this run (N)** | Opens a [review](#generate-from-this-run) of the collected series no server assert judges. Shown only when there is something to seed |
| **Expand all rows** / **Collapse all rows** | Opens every row on the board, or closes them all |
| **Download report-annotations.json** | The whole document, client rules and server asserts together. The same file the Asserts tab downloads |
| **Edit document…** | The whole file as JSON, the same editor as the Asserts tab's, opened over this tab. The way to every `serverMetrics` key no form here edits: `environments`, the run-level `requiresLoad`, a component's id or type. **Apply as draft** makes it the page's document; Escape closes it |
| `N unsaved changes` | The unsaved pill, the same count the Asserts tab shows, a note typed on the Notes tab included. Absent on a page nobody has edited |

When every seed would be stood down by a run-level `requiresLoad` that did not hold, Generate is
replaced by a sentence saying so. Under the actions, a line can say what happened to a draft when
the page was loaded (see [Drafts and saving](#drafts-and-saving)).

Then one filter row:

- **Filter server asserts…** finds a row by its id, its catalogue metric's label or id, its
  description or its component.
- **Status chips** with counts, worded as the Asserts tab's: `All 12`, `FAIL 1`, `WARN 1` and so on,
  one per status on the board. Every reason a server assert was not judged is a chip of its own, as
  on the Asserts tab. The counts follow the other filters, the text you are typing included, so the
  chips always add up to what `All` says. A screen reader hears which chip is on.
- **Clear filters**, shown when any filter is on, as soon as you type.

A filter narrows the board to the cards it found something in. With no filter on, every declared
component has a card, an empty one included.

### Component cards

The board is one card per component, in the file's order. The one exception is an id that is a
whole number, such as `"1"` or `"2"` (but not `"01"`): the page lists those first, in numeric order,
because a browser keeps such keys first in an object, and a file the page saves writes them first
too. The order judges nothing. The run, the CI/CD JSON and JUnit keep the file's order.

![Component card header](images/62-server-component-head.png)

The card's header reads, left to right:

- **The component's id**, then its type and description: `Custom · Kafka`, `Postgres · Orders DB`.
- **Its badge**: the verdict **this run was gated on** (`FAIL`), with the counts in the tooltip.
  The description is already beside it, so the badge does not repeat it; it is the badge's name for
  a screen reader. Click the badge to narrow the board to that card; click it again to release it.
  A component the document declares and this run did not judge reads `NOT IN THIS RUN`.
- **`N server asserts`**.
- **+ metric**, which adds a server assert to this component (see
  [Adding metrics: the picker](#adding-metrics-the-picker)).
- **⋯**, which shows the component's two actions under the header (Escape or a click elsewhere
  hides them):
  - **Edit description / match** opens the component dialog on it. The id and the type stay as
    they are, because every assert on the component is named and collected by them; change those
    with **Edit document…**. A new `match` changes the catalogue queries collected under the
    component, so its asserts read `NEEDS RE-COLLECTION` until you collect again. An emptied field
    is removed from the file.
  - **Delete component** removes the component and every server assert on it, as one draft with an
    **Undo**. The button says how many go with it (`Delete component and its 3 server asserts`).
    Left behind, those asserts would name nothing the file declares: the next run reads them
    `INVALID` under `(no component)` and says `SERVER NOT JUDGED`, and exits 1 only with
    `webinsight.report.server.failonerror=true` (see [Gating a pipeline](#gating-a-pipeline)).

  Only a component the document declares has a ⋯ and a **+ metric**. The `(no component)` card has
  neither. A component the run judged and the document no longer declares (after **Delete
  component**, say) keeps its card for its badge, the verdict this run was gated on, and the card
  says the document no longer declares it.

Once you edit server asserts on the page (or a client rule one of them names in `requiresLoad` or
`divideBy`), a badge whose rows now fold to something else also shows `→ PASS if saved` (or
whatever the rows now add up to), and a note in the header explains the arrow. It reads
`→ ? if saved` when an assert on the component has no verdict on the page (it needs re-collection,
or its series reached the page capped), so the fold cannot be known, and `→ not declared if saved`
on a component the document no longer declares. The badge's tooltip says which. The build's verdict
and the edited one stay visibly separate. A page nobody has edited never shows an arrow, and
neither does a file that declares components and no server asserts yet: the next run would judge
nothing there.

Asserts that name no declared component get a `(no component)` card of their own. They still build,
as the `(no gate)` entry in the CI/CD JSON — `(no gate)` is that entry's id, and
its label, the header chip and the console verdict line all say `(no component)`. Each of them is `INVALID`,
a server assert the file got wrong: the run says `SERVER NOT JUDGED` and exits 0, or 1 with
`webinsight.report.server.failonerror=true`, as the [gating table](#gating-a-pipeline) says. A component with no server asserts yet
says `No server asserts on <id> yet — add one with + metric.`

### The board

![Server asserts board](images/63-server-board.png)

Each card holds a table, with the same columns on every card so they line up:

| Column | What it tells you |
|---|---|
| **Status** | PASS, WARN or FAIL when the server assert judged something, otherwise why not (below). Marked `edited` (changed on the page), `live` (judged again in the page because the assert, a client rule it names, or its component or the block around it was edited; an untouched row's verdict is the build's, and carries no marker) or `from the build` (a capped series, see [Limits](#limits)). The ▸ beside it opens the row |
| **Server assert** | A catalogue assert by its metric's label (`Connections used (%)`), with its id under it, dim: the id names the JUnit testcase and is what a CI log is searched for. A custom assert by its id, with its description under it. An assert written without an id gets one derived from its component and its metric or query, marked `derived id`. `÷ load:throughput` and `needs load:throughput` mark the [advanced keys](#advanced) |
| **Actual** | The one number the bands were compared with |
| **Criteria** | The bands, `warn > 300 msgs · fail > 500 msgs`, plus the band that was breached |
| **Coverage** | How much of the **collected window** has data (below) |
| **Instances** | How many series the query returned, all judged. `21 (20 drawn)` when the chart draws fewer than were judged. A dash exactly when Coverage shows a dash too: nothing was measured. A series that was measured and decided nothing, such as `LOW COVERAGE`, still shows its instances, the count the CI/CD JSON and JUnit give |
| *(actions)* | **Edit**, **Duplicate**, **Disable** / **Enable**, and **Delete**, which offers an **Undo** |

The reducer appears under the name only when the assert chose its own, such as
`max sustained for 5s across max`. A catalogue metric's default reducer, or a plain `max` across
`max`, is not repeated on every row.

The board has no Source or Query column. The collection has one source, the component is the
card, and the resolved query stays in `server-metrics.json` and in the CI/CD JSON's
`serverAsserts[]`.

Rows are **worst first** within each card. A server assert that decided nothing gets a full-width
line under it with the sentence that explains why.

Actual and Criteria are written in the unit the verdict compares in. For a `slope` that is per
minute, `B/min` (`/min` alone when the assert has no unit). For a `divideBy` ratio it is per unit
of the load, the way the chart axis writes it: `% ÷ load:throughput`. A slope ratio is both,
`B/min ÷ load:throughput`. The Summary card, the Per instance panel, Set thresholds, the Generate
review, the editor's band boxes and JUnit write the same unit. The CI/CD JSON's `unit` stays the
declared unit.

A band is written with every digit the file gives it, never rounded: `warn > 0.4 s · fail > 2.955 s`.
A measured number, such as Actual, a chart tooltip or a run's peak, has at most two decimals in
every unit, with trailing zeros dropped (`2.95 s`, `2.5 ms`, `900 msgs`), and never reads `0` when
it is not zero. An Actual that two decimals would
put on a band it did not reach, or across one, gets the digits that keep it on its side:
`3.004 s` beside `breached > 3 s`, not `3 s`. Large numbers are grouped the same way in both:
`60,000,000 B/min` against `fail > 50,000,000 B/min`. Digits are written the same in every
browser language, `,` grouping and `.` as the decimal point, as the Test Summary cards and the
band boxes write them, and as on the Asserts tab. The Asserts tab writes by this same rule,
through the same code: bands as declared, and an Actual on its own side of every band the rule
declares, from its own base of whole `ms` and `s` and two decimals otherwise. The JUnit XML writes
the same digits: `max 3.004 s breached > 3 s` in the failure message and `actual:    3.004 s` in
its body, where two decimals would write `3.00 s`. So does the reason a server assert carries when its
load rule FAILED, which quotes the client's measured load beside the load rule's band:
`load rule 'load:throughput' FAILED: throughput 12.496/s breached < 12.5 /s`, not `12.50/s`.

### Why a server assert was not judged

A server assert has the same four statuses as a client rule. The fourth, NOT_JUDGED, carries a
reason, and the board shows the reason with the sentence that explains it underneath:

| Board reads | Reason | Cause | Fails the run? |
|---|---|---|---|
| `DISABLED` | `DISABLED` | `"enabled": false`. A custom `query` is not collected either; a catalogue metric still is, as every metric of the component is, for the picker | No |
| `UNMATCHED` | `NO_MATCH` | The source answered and the query matched no series (for a CSV: no row of the file names this component and metric — a file whose rows for it are all outside the run, or named with another case, is `SOURCE FAILED` instead, with the reason) | No, it is counted and reported |
| `NO VALUE` | `NO_VALUE` | No point fell inside the judged window, the reducer had nothing to say, or a `divideBy` divisor measured zero | No |
| `INVALID` | `INVALID_RULE` | The file is wrong, or the payload was collected for another version of it. In the file: an unknown component, component type or catalogue metric; `metric` and `query` both or neither; a `custom` component with no `query`; no band at all, or an unreadable one; an unknown reducer, or a tolerance it cannot take; a [refused key](16-Server-Reference.md#retired-and-refused-keys). Against the run: a `requiresLoad` or `divideBy` that names no client rule, or a rule that is not a throughput rule; a `divideBy` rule that matched more than one sampler; a `sustainedFor` longer than one step that is not a whole number of the collection's steps. Against the payload: no series was collected for this assert's query (the file changed after `wir-collect` ran). The sentence names which | No by default; **yes** under `webinsight.report.server.failonerror` |
| `SOURCE FAILED` | `SOURCE_FAILED` | The series could not be fetched: authentication, DNS, timeout, a 5xx, a placeholder the collector could not resolve, a catalogue metric with no Prometheus query asked of Prometheus or a recording, or a CSV row of this series that could not be read | No by default; **yes** under `webinsight.report.server.failonerror` |
| `LOW COVERAGE` | `LOW_COVERAGE` | Data came back, but below the coverage floor | No by default; **yes** under `webinsight.report.server.failonerror` |
| `LOAD NOT ACHIEVED` | `LOAD_NOT_ACHIEVED` | The client load rule it `requiresLoad` FAILED or was not judged, or the rule it `divideBy` was not judged. A `divideBy` rule that FAILED still divides | Not by itself. A load rule that FAILED: that FAIL is the client verdict, and exits 1 only under `failonassert`. A load rule that was not judged: that rule's own reason decides, so an `INVALID` client rule exits 1 under `failonerror`, and a disabled, unmatched or unmeasurable one fails nothing |

On a report with no collected metrics (step 1's report, or one whose payload was refused), a server
assert has no verdict to show:

| Board reads | Means |
|---|---|
| `NOT EVALUATED` | The report has no collected metrics to judge the assert against. On step 1's report the line under the row reads *Not collected yet — run wir-collect with this file against this run's results, then regenerate the report to judge this server assert.* Over a refused payload it reads *Not judged — the server-metrics payload given to this report was refused, so nothing collected answers for this server assert.*, and the tab's notice says why it was refused. What needs no metrics is still decided on such a report: a disabled assert reads `DISABLED`, a refused one `INVALID`, one stood down by its load rule `LOAD NOT ACHIEVED`. A `NOT EVALUATED` row sorts last in its card, and its chip is drawn in the same neutral style as `DISABLED` |

Two more states exist only on the page, while you edit:

| Board reads | Means |
|---|---|
| `NEEDS RE-COLLECTION` | Nothing collected answers for the assert: you changed what it fetches (its component, metric or query, or its component's `match`), or it is new on this page and this collection holds nothing for it. For a catalogue metric that means the collector asked for it and got no data, which usually means the exporter does not publish it. The verdict is withheld, and the reason row says which case it is. Save and re-run `wir-collect` |
| `NOT RE-JUDGED` | The series reached the page capped, and the build's verdict no longer answers for the assert: the assert is new or edited, a client rule it names in `requiresLoad` or `divideBy` was edited, or the block around it changed what it is judged under (the run-level `requiresLoad`, say). The page will not estimate from the thinned points. What needs no points is still decided live: a disabled or refused assert, its load rule, the series' collection status, a `sustainedFor` the step cannot hold. Save and run `jmeter -g` with the same payload |

The report's own reasons and words are shared with the client side. See
[Why a rule was not judged](10-Asserts-Apdex.md#why-a-rule-was-not-judged).

### Coverage is of the collected window

Coverage is the share of expected steps in the window that have a point. It is decided **when
`wir-collect` runs**, over the whole collected window. It is not recomputed when the report is
judged. Every place that shows it says "of the collected window" for that reason.

The judged window can be shorter: a ramp-up is cut from the front. On a run with a long ramp-up,
that makes coverage **optimistic**. A source that died two thirds of the way through can show 90%
coverage of the collected window while the judged window holds much less. When an assert's numbers
look thin, open its row and look at the gaps.

It can be **pessimistic** too. A series that starts late (a rate, which needs two scrapes, or a
histogram before its first request) misses steps in the ramp-up, and those count against the floor
although the verdict never reads them. Such an assert can sit at the floor, or read `LOW COVERAGE`,
with its judged window fully covered. Open the row: when its gaps all lie in the ramp-up, the
judged numbers are whole.

The floor (`collection.coverageFloor`, default `0.9`) is applied at collection time too. Change it
and re-collect: a raised floor applies only from the next collection. The payload does not record
the floor it was collected under. A `LOW COVERAGE` row whose coverage the floor in the file would
now pass says so, and asks you to re-run `wir-collect`.

### Opening a row: two panels

Every row starts **closed**, a FAIL included, so a card reads as a list of verdicts. The ▸ beside
the status (or a click on its name) opens it, and ▾ closes it again. **Expand all rows** in the header
opens every one.

An open row has two panels:

![An open row's two panels](images/64-server-chart.png)

**Value over time** is the collected series, one line per instance, against the run's timeline:

- It is drawn at the **collection's step** by default: every point `wir-collect` fetched. The
  selector beside its title, like the Samplers tab's chart selector, buckets it coarser: any rung
  of the report's ladder (`1s`, `5s`, `10s`, `30s`, `1min`, `5min`, `10min`) above the step and
  shorter than the judged window. A `max` or `min` assert's bucket holds its extreme point, so a
  `max` assert is drawn as a max; every other reducer's bucket holds the average of its points. The choice applies to
  every open row, and nothing else moves it: the Granularity in the client filter bar, which is
  hidden on this tab, does not reach these charts.
- The warn and fail bands are drawn as lines, and the breached stretches are shaded.
- The band lines have handles, `warn ⇕` and `fail ⇕`: drag one to [retune](#drag-to-retune) the
  threshold. The handle names its band, so the line carries no label of its own. A handle stays
  inside the plot, a band at the axis minimum included, and two close bands' handles sit side by
  side rather than on top of each other.
- A server assert that was **not judged** draws no breach shading. Its band lines are dashed and
  muted, so the declared limit stays visible, and the note reads `not judged — <reason>`.
- Gaps in the data are drawn as gaps, not bridged with a straight line.
- Above the draw cap, the extra instances are listed as "and N more instance(s) not drawn". Every instance was still judged.
- A series `wir-collect` read under another tool's name (`--csv FILE --from TOOL`) says which, in the
  note under the panels: `read as postgresql.percent_usage_connections (datadog, gauge, x100)`, the
  scale only when it is not 1. A series read under the catalogue's own name says nothing.
- The value axis stretches to include the bands, so a limit above the data is still on the plot.

**Per instance** is one bar per instance: the assert's own reducer applied to that instance's
points in the judged window, against the warn and fail lines. Its axis is in the unit the verdict
compares in, so a slope's bars are per minute and a `divideBy` ratio's are divided by the load. The
unit is written under the axis. Each line is labelled `warn` or `fail` at its top, the lower band's
label to the left of its line and the higher one's to the right, so two close bands stay readable.
On a row that was not judged the lines are muted and the labels are not. It shows which instance
carried the verdict, and how far the others were from it. It is a picture, not a verdict: the
server assert still has one verdict, taken `across` the instances.

Below about 900px wide the two panels stack, and they are as wide as the card you can see, not as
the board's table; at narrow widths their time labels thin out rather than print over each other.
From 1240px down the four row actions take two lines and the **Server assert** column slims to make
room (about 326px at 1180, 202px at 1024), so a name stays on one or two lines. Below about 1024px
the column stays about 200px wide and the board scrolls sideways inside its card.

For a `slope` assert, or a `divideBy` one whose divisor the page cannot name, Value over time draws
no bands against the series, because the verdict is not taken on that axis. The note under the
panels says why. Per instance still draws a slope's bands, because its bars are the slopes
themselves. A `divideBy` assert with no divisor draws no bands there either.

Axis ticks from a million up are written with their SI prefix (`6G`, `1.5M`), so they fit beside
the plot. Every number the board and the tooltips state is written in full.

### The Collection line and the findings

The Collection line in the header names the collection's one source, `metrics`, its status and
when it was collected. Click it to open the detail:

![Collection detail](images/65-server-collection.png)

| Column | |
|---|---|
| **Source** | `metrics`. Hover it for the backend (`prometheus`, `file` for a recording, or `csv`) and the URL, recording directory or CSV path it was read from. A URL is redacted: userinfo stripped, credential parameters and every secret value masked. A CSV collected with `--from` says so under the id — `read as datadog's export` — because its numbers may have been converted from what the file holds; so does a wide one (`wide CSV`), a long one read with `--csv-columns` and `--component` (`columns time=_time metric=_field value=_value, every row in orders-db`) and a `--time-zone` (`times without a zone read in UTC`) |
| **Status** | `OK`, `PARTIAL` (some series failed and the others came back, e.g. `1 of 8 series failed - first: …`) or `FAILED` (nothing came back) |
| **Step** | The resolution the series were fetched at |
| **Clock offset** | The `--clock-offset` that was applied, `0` when none |

How the metrics were fetched is the collector's business, so the backend and its address are not
on the tab itself; `server-metrics.json` records both. The source's error is printed under its row.
Under the table, the `collectionId`. The time the
metrics were collected is on the Collection line itself, once; hover it for the collector's own UTC
stamp.

The line is **open by default when the source did not answer in full** (any status but `OK`), or
when a series an enabled server assert reads came back with nothing usable (`EMPTY`, `INSUFFICIENT`
or `FAILED`): that is when you need the error. Those series are listed under the table with their
reason. A catalogue metric no assert reads (collected for the picker) that came back unusable is
listed too, marked `(no assert reads it)`, and does not open the line. On a clean collection it
stays one line.

Under the filter row, the findings list what the file got wrong, under a heading such as
`1 configuration problem`. The sentences, their order and the names they are filed under are those
of `jmeter.log` and the CI/CD JSON's `findings[]`. The list describes the document the page holds
now, not only the one the report was generated with:

- A line under the heading says which document it is. On a page nobody has edited it reads
  *In the file this report was generated with.* After a server edit it reads
  *In this draft, as the next run will read it*, followed by how many problems the generated file
  had. A client-only edit does not change it, unless it changes the list itself: a client rule that
  a server assert names in `requiresLoad` or `divideBy` can make that assert refused, or no longer
  refused.
- A problem you fix on the page leaves the list, and one you introduce joins it: a duplicate of a
  refused assert, a warn band the fail band covers, an imported band with a typo in its operator, a
  `defaultEnv` that names no declared environment, or a `coverageFloor` that is not a fraction.
  A draft that fixes everything reads *No configuration problems in this draft*.
- Two findings the page cannot check again: a ramp-up that the payload and the report disagree
  about, and a refused payload. Those are kept as the build found them. If
  `serverMetrics.collection` has changed since, the ramp-up one is marked *as generated*.
- The findings about `environments` are the build's until you change the environments, and are
  checked again after that. The report holds a credential in a var masked, so only the build saw
  the value it refused.
- A row with no verdict on the page still says what no collection can change. A `NEEDS RE-COLLECTION`
  row adds a refusal re-collecting will not answer, such as a `requiresLoad` naming no client rule
  or a `sustainedFor` that no window of the collection's step can span. A capped row is judged on
  everything that does not need its points, so the same refusals appear there too. A
  `NOT RE-JUDGED` row has passed all of them, and adds nothing.

The header chip and the Summary card keep the build's word whatever the draft says.

---

## Writing and tuning server asserts

The usual order: add a component, pick its metrics, set their thresholds. The editor is for
everything else, and for anything the catalogue does not cover.

### Adding a component

**+ Add component** in the header opens the dialog. On a tab with no component yet, the same type
cards are offered inline, under **+ Add your first component**.

![Add component](images/67-server-add-component.png)

| Field | |
|---|---|
| **Type** | One card per type: **Postgres**, **Kafka**, **Redis**, **Linux host**, **AWS EC2**, **Oracle Database**, **InfluxDB 2**, **InfluxDB 3**, **JVM**, **HTTP service**, **Kubernetes pod** and **MySQL**, each naming what it covers, what its metrics need (`Needs postgres_exporter`; Kafka's broker internals also need the JMX exporter with Strimzi's rules; AWS EC2's come from CloudWatch's `AWS/EC2` and `AWS/EBS`, and its ENA allowances from the CloudWatch agent; Oracle's five need [a custom metrics file](15-Server-Catalogue.md#oracle-the-custom-metrics-file); InfluxDB serves its own `/metrics`; JVM and HTTP service read [Micrometer's names](15-Server-Catalogue.md#jvm-and-http-service-micrometer-names); a Kubernetes pod's come from cAdvisor and kube-state-metrics) and, in the dialog, how many catalogue metrics it has, and **Custom**, for anything else, where each assert writes its own query |
| **Id** | The name its server asserts, its card and the JUnit suite use, such as `orders-db` |
| **Description** | The label on its card, the header chip and the Summary card, such as `Orders DB` |
| **Advanced → Match** | Optional: a label selector added to every catalogue query, for when two components of one type share a source. See [`match`](16-Server-Reference.md#components) |

An id another component already has, and a reserved id (`assertions`, `(no gate)`), are each
refused by name. **Add component** stays disabled until the id is usable, an empty one included. Any other
id the run accepts is accepted here too.

**Add component** writes the component into the document's `serverMetrics.components`, as a draft
like any other edit: the pill counts it, and a Save writes it. The board gets its card, and then:

- for a **catalogue** type, the [metric picker](#adding-metrics-the-picker) opens on it;
- for **Custom**, the [editor](#the-editor) opens on it with a custom query.

The component stays when you **Cancel** the picker or leave the editor without saving, and a toast
says what that means: a component with no server assert reads `NOT EVALUATED` on the next run, and
so does the server verdict unless another component FAILs. Its **Undo** takes the component back
out. Focus goes back to the new card's **+ metric**, so you can carry on adding to it; the toast
goes as soon as anything else changes the document, the component's first server assert
included, because its Undo would put back the document as it was before the component and lose
that change too.

### Adding metrics: the picker

**+ metric** on a catalogue component's card opens the metric picker. It is a wide dialog with three
columns:

![Metric picker](images/68-server-picker.png)

- **Filters**: a search box, **Recommended only**, **Has data in this run**, and the type's
  categories. A category's count is how many of its metrics the other filters leave, so with
  **Has data in this run** ticked a category with nothing collected reads `(0)`.
- **The metrics**: the type's catalogue, grouped by category. Each row has ★ when it is
  recommended, this run's sparkline and [the number its verdict would compare](#the-number-from-this-run),
  and a checkbox. A metric already asserted on this component is disabled and marked
  `already asserted`.
- **The detail** of the focused metric: its description, unit, what one line is per, which way is
  worse, what it did in this run (that number, over how many instances the collector returned),
  the suggested band or why there is none (nothing collected, no number, a band from this run
  that could never fire, or no room left below the metric's ceiling), and a small chart of the run. It always describes a metric the list
  shows: when a filter hides the focused one, the first metric listed takes its place.

Below 1000px wide the three columns stack, and the list keeps at least half the window's height,
so several metrics are always in view; the dialog scrolls, and so does the list inside it. At a
phone width the Set thresholds table, wider than the dialog, scrolls sideways inside its own box.

The sparklines and numbers are there because `wir-collect` collects **every catalogue metric** of each
declared component, beside the ones the server asserts ask for, and keeps those that returned data.
So you pick a metric having already seen what it did. A metric with no data says `no data`. This
works for the first assert too: save the file with the component alone, run `wir-collect` over it
and regenerate the report, and the picker has this run's values for every metric the source
returned.

Picked metrics collect as chips in the footer; × on a chip unpicks it. **Custom query…** in the
footer leaves the picker for the editor, on the same component, for anything the catalogue does
not have. **Set thresholds (N)** goes to the second step.

A **custom** component has no catalogue, so its **+ metric** goes straight to the editor.

### Setting thresholds

The second step is one table for everything you picked:

![Set thresholds](images/69-server-thresholds.png)

| Column | |
|---|---|
| **Metric** | The catalogue metric's label |
| **This run** | Its sparkline, folded across instances the way the verdict folds them |
| **Actual** | [This run's number](#the-number-from-this-run), named by what it is: `peak 2.9 s`, `low 97 %`, `slope … B/min`. `NO DATA` when nothing was collected, a dash when the run gives no number |
| **Warn**, **Fail** | An operator and a value each, with the unit the band is compared in (`B/min` for a slope). A box left empty, or holding only spaces, is no band |
| **Verdict** | What the board will call this assert once it is added, as you type: a live verdict, `NEEDS RE-COLLECTION` for a metric this run has no data for, `NOT RE-JUDGED` on a series the page holds capped. Under it, any warning the editor would give the same assert, such as a warn band the fail band already covers. Warnings never block Add. A row Add would refuse says why instead: `needs a band` while it has neither band, `not a number` while a box holds text that is not one |

Three **Prefill** rules fill the bands of every row at once. Each row stays editable afterwards.

| Prefill | Bands |
|---|---|
| **Suggested** (the default) | The catalogue's safe default where the metric has one: deadlocks fail `> 0`, an exporter's `up` fail `< 1`. Otherwise this run's number with headroom, pointed the way the metric gets worse: higher for most metrics, lower for the ones where lower is worse, such as cache hit ratio or the broker count. The fail band is 50% beyond it, the warn band halfway there, and neither past the metric's ceiling (below) |
| **20% headroom** | The fail band 20% beyond this run's number, the warn band 10%, and neither past the metric's ceiling (below) |
| **50% headroom** | The fail band 50% beyond this run's number, the warn band 25%, and neither past the metric's ceiling (below) |

"Beyond" is the way the metric gets worse, so on a metric that is worse lower the bands sit below
the run.

A band that can never fire is never proposed. On a metric that gets worse lower, a run that touched
0 has no headroom below it, and the rules would give `fail < 0`: a broker count that went to 0 would
PASS. Such a row takes the catalogue's safe default instead, under every rule, and without one it is
left empty. So is a metric with no data in this run, which has nothing to take headroom from.

The same holds at the top. The catalogue declares a **ceiling** on the metrics that cannot read
past one: for example 100 on the Postgres percentages (connections used, rolled-back transactions,
cache hit ratio), 1 on a 0/1 flag such as `up`, and 10 s on the InfluxDB latency p95s, whose
histograms stop there. A percentage that can read past 100, such as MySQL's connections used or a
Kubernetes container's CPU against its limit, has none. Upward, the headroom is then a share of the
smaller of this run's number and the room left below the ceiling. Connections used at a 68 % peak gives warn `> 76` and fail `> 84`,
where 50% of the run alone would give fail `> 102`, a band the metric can never reach. Nothing at or
below half the ceiling changes: at 20 % the fail band is still `> 30`. Each band is checked after it
is rounded, and one at or past the ceiling, or one that rounding put on the run itself, is not
proposed. From a 99 % peak there is no room left, so the row is left empty, and the picker's
Suggested line says why: `leaves no room below this metric’s ceiling of 100 %`. A band you type or
drag yourself is judged as written, and one past the ceiling gets a warning, in the editor, after a
drag and among the run's findings, which never blocks anything:

> fail > 150 % can only fire above the ceiling of 100 % the catalogue declares for
> connections.used.pct. If it never reads above its ceiling the band never fires and the server
> assert reports PASS on every run

It is worded *if* because a ceiling can be nominal. A `slope` and a `divideBy` ratio are not in the
metric's unit, so no ceiling bounds them and they get no such warning.

An empty row reads `needs a band`, and **Add N server asserts** is disabled until every row has one.
**Back** returns to the picker with your picks intact.

#### The number from this run

Everything the picker, Set thresholds and Generate show or propose from this run is one number per
metric: the one its verdict would compare. That is the catalogue's reducer and `across` over the
judged window, on every instance the collector returned, so:

- a `max` metric shows its peak, and a `min` one, such as `up` or the cache hit ratio, its low, from
  its worst instance;
- `database.size.growth`, a `slope`, shows and is banded in bytes **per minute**, never the size it
  grew from;
- on a series the page holds [capped](#limits), it is the build's own number for that metric,
  carried in the page for this. The page's thinned copy can miss the instance that decides, and
  holds too few points to recompute a percentile or an average from. When the build had no number, nothing is proposed from the run. Nor is anything when the
  file open on the page judges another window than the one the report was generated with (its
  `collection.excludeRampUp` changed, on a run with a ramp-up): the build's number answers for the
  build's window only.

The server asserts written to the file name the metric and its bands, and nothing else. For
deadlocks, whose catalogue default is "any deadlock fails", Suggested writes:

```json
{ "component": "orders-db", "metric": "deadlocks", "fail": { "gt": 0 } }
```

The catalogue supplies the query, the unit and the reducer, so a newer catalogue improves them
without a change to your file. The verdict column is a guide on this run: the bands are still
yours, and a band taken from one run passes that run by construction.

### The editor

**+ Add server assert** (shown once a component is declared), **Edit** on any row, Custom query… in
the picker and a custom component's **+ metric** all open the same panel. **Edit** titles it with
the assert's name as the board gives it (`Edit server assert — Connections used (%)`, its id under
it):

![Server assert editor](images/66-server-editor.png)

The fields read in the order an assert does: what it judges, then its bands and the verdict they
make, then how the series is reduced, and the details last.

| Field | |
|---|---|
| **Component** | A declared component, shown with its type: `orders-db (Postgres)`, `kafka (custom)`. A component of unknown type reads `(cannot be used)`; one the document no longer declares is marked as such |
| **Metric** | The component's catalogue metrics by label, grouped by category as the picker groups them, then **Custom query…** last for an assert's own `query`. The line under it names the metric id the file will carry (`metric: connections.used.pct`). Picking a metric sets **Reduce** and **Across instances** to the catalogue's. Moving through the list with the arrow keys or by typing does not, until you leave the select on a different metric, so a reducer you chose survives looking. A component with no catalogue (`custom`) has no choice to make, so there is no select: the field says it takes a custom query |
| **Query** | Shown for Custom query… only: the PromQL (or, for a CSV, the text in its `metric` column), with `${env.*}` and `${run.*}` placeholders if you need them. A long query wraps. Enter adds no line break, because a query is its exact text and the payload is searched for it. A catalogue metric's query is the catalogue's, so the field is hidden |
| **Warn**, **Fail** | An operator and a number each, with the unit the band is compared in beside it: the unit, `/min` added for a `slope`, ` ÷ <rule>` for a **Divide by**. Leave one empty for no band, unless the file declared a band there that the run [could not read](#a-band-the-run-could-not-read): then the empty box keeps it, and the row has a **×** |
| **If saved** | The verdict the board would show for this assert if you saved it now, as you type: the chip, the actual against the band it breached, and `judged live` when the page recomputed a verdict (`live` on one that decided nothing, such as `DISABLED`). It is the board's own answer, so it never estimates. On a series the page holds capped it keeps the build's verdict while the assert is as the run judged it (`the build’s verdict — this series is capped here`) and reads `NOT RE-JUDGED` once you change it; a query this run did not collect reads `NEEDS RE-COLLECTION`. While Save is refused there is no verdict, only a pointer to the reason under the form |
| **Reduce** | `max`, `min`, `avg`, `last`, `slope`, `p50`, `p90`, `p95` or `p99`. Another percentile the file carries is offered too, marked `(from the file)` |
| **Tolerance** | Offered on `max` and `min`, and on any assert that already carries one, so a tolerance the run refuses on another reducer can be removed: *sustained for* a duration, or a *breach budget* fraction. A tolerance you choose needs a value: left empty, Save is refused with the run's own sentence. To remove one, set Tolerance to *none*. Erasing its value does not remove it |
| **Across instances** | `max` (the worst instance when higher is worse), `min` (the worst when lower is worse) or `avg`. A custom query starts at `max`; a catalogue metric starts at the catalogue's — `min` on most metrics where lower is worse, such as `up`, a hit ratio or free memory — and only a different choice is written |
| **Unit** | A label shown beside every number. It converts nothing. For a catalogue metric, empty means the catalogue's. For a custom query, empty means no unit, and Save writes no `unit` key unless the file already had one |
| **Requires load**, **Divide by** | See [Advanced](#advanced) |
| **Description**, **Id** | Leave the id empty and the assert gets the id the run derives from the component and the metric or query, as one added with **+ metric** or Generate does. The field shows that id as its placeholder, and it is not written into the file. One another assert already has is refused |

The [Validate query](#validate-query) line sits under Metric (or Query). Enabling and disabling is
not a field: it is **Disable** / **Enable** on the row.

A [refused key](16-Server-Reference.md#retired-and-refused-keys) the assert carries has no field either. The editor lists
it at the top in the run's own words, with a `Remove "<key>" from this server assert` button, so
the assert can be saved without it.

**Save server assert** is disabled, with the sentence, for anything the run would refuse. The editor
and `jmeter -g` share one set of refusals. An id another server assert already has is refused
(`Another server assert already has that id.`). Warnings that do not refuse, such as a warn band
the fail band already covers, are listed above the button. A warning about a run-level key the form
has no field for, such as `serverMetrics.requiresLoad`, says to fix it with **Edit document…** in this
tab's toolbar.

#### A band the run could not read

A band **the file declared and the run could not read** (two operators, an unknown operator, text
where a number belongs, a bare `1e400`) fits in no box. The box shows empty and reads *the file's
band*, and the row shows the band under it as a save will write it back:
`The file's fail band is kept: {"gtt":500}.` Save is refused in the run's own words, and **If saved**
gives no verdict while the band stands:

> fail operator 'gtt' is not one of gt, gte, lt, lte — so this server assert declares a limit it
> cannot apply, and would report PASS without ever testing fail

An empty box there means *the file's band*, not *no band*. Type a number to replace it, or press **×**
on the row to remove it. Picking an operator, or typing a number and clearing it again, leaves the
file's band where it was, so the download never drops a band you did not remove. The Asserts tab's
editor works the same way.

The band is saved back as written, and a bare `1e400` as `"Infinity"`, the text the report itself
writes for it. That holds anywhere in the file, not only in a band: a coverage floor, a component's
description or a key the plugin does not know is saved the same way. So opening the file a report was
generated from is no change, and the next run still refuses the band.

Components are added with **+ Add component**. To change one's description or `match`, use **⋯** on
its card; for its id or type, **Edit document…**.

### Validate query

Under the metric (or the query), one line says what the payload holds for exactly that component
and the query the assert resolves to: for a catalogue metric, the catalogue's query with the
component's `match` expanded. It is an **exact lookup, never an estimate**:

| Line | Means |
|---|---|
| `Collected: 3 instances · coverage 98% of the collected window · status OK` | This query was collected. The verdict is real |
| `Collected: 21 instances (20 drawn) · … · capped in this page, so it is judged but not re-judged here` | Collected, but the page holds a [capped](#limits) copy. The count is the collector's |
| `Collected with status EMPTY: … reads UNMATCHED until it is collected again.` | It was collected and will not be judged on its numbers, with the reason |
| `Not collected. Download the file and re-run wir-collect to fetch it.` | Nothing collected answers for this text. The page will not guess |
| `Collecting again will not help: the query has an invisible character at its start (U+00A0) …` | The query starts or ends with an invisible character that is not trimmed: a no-break or zero-width space, a bidi mark, a soft hyphen, a variation selector, a tag character or a C1 control. The warning names it by its code point. No source returns that series, so delete the character rather than re-collect. Said only when nothing came back for that text: a series that returned points (a source whose name carries the same character, or an emoji that ends in a variation selector) is answered with the `Collected` line instead |
| `Nothing has been collected for this report yet.` | The report has no collected metrics: step 1's report, or a payload that was refused |
| `Write a query, or choose a catalogue metric.` | Custom query… with the Query box still empty: nothing to look up yet |

Editing what an existing server assert fetches turns its row to `NEEDS RE-COLLECTION`. A verdict
computed from the old series under the new query would be confidently wrong, so none is shown.
The row's reason says *Its component or query changed* (or *component or metric*). A server assert
that is new on the page says instead that this collection holds nothing for it. For a catalogue
metric, the collector asked for the whole catalogue, so the reason says this collection *has no
data* for it and the exporter may not publish it.

### Generate from this run

**Generate from this run (N)** offers one server assert for every collected series that no server
assert on the page judges and that came back `OK` or `PARTIAL`. Nothing is added straight away: it opens a review.

![Generate from this run review](images/70-server-generate-review.png)

| Column | |
|---|---|
| *(checkbox)* | Ticked by default. Untick a seed to leave it out |
| **Component** | The component the series was collected for |
| **Measures** | A catalogue metric by its label; a custom series by its query |
| **Warn** | The seeded warn band, if there is one |
| **Fail** | The seeded fail band, with its value editable. Emptied, or left holding only spaces, the seed has no fail band and keeps its warn band |
| **Verdict** | What the board will call the seed once it is added, as you edit its fail value, with any warning the editor would give it |

**Add N server asserts** adds the ticked ones, with any fail value you changed. It is disabled
while nothing is ticked, a fail value is not a number, or a ticked seed would be left with no band
at all. Untick a seed to leave it out.

What a seed is depends on what was collected:

- **A catalogue metric** (collected for the picker) is seeded **as that metric**, with the bands
  [Suggested](#setting-thresholds) would give it, from [this run's number](#the-number-from-this-run). The
  file names the metric, never its PromQL. Its description says
  `seeded from this run — review this threshold before trusting it`. A metric whose run gives no
  band, and has no safe default, is not seeded.
- **A custom series** is seeded as a query on its component, `max`, with a fail band above the
  observed maximum by half its magnitude (900 seeds 1350). On a capped series that is the build's
  maximum. Its description says
  `seeded from the observed maximum (…) — review this threshold before trusting it`.

Each seed is checked before it is offered:

- it would pass a Save, and so the next run;
- judged against this run, it PASSes. On a series the page holds capped, it is held against the
  build's own number for this run instead, and a capped series the build carried no number for is
  not seeded. Such a seed reads `NOT RE-JUDGED` in the review, as it will on the board, and the line
  under it says the number it was held against and whether the fail value now in the box still
  passes it.

So **every seed passes the run it came from, by construction**. Review each one: it is a starting
point, not a judgement about what the system should do. If every candidate would be stood down by a
run-level `requiresLoad` that did not hold, the button is replaced by a sentence saying so.

### Drag to retune

Open a server assert's row and drag a warn or fail line in **Value over time**. Releasing it
commits a draft, the verdict recomputes in the page, and the row is marked `edited` and `live`.
The handle moves the number the verdict is compared with, in the verdict's units: a `divideBy`
assert is drawn divided by its load, so its handle is per unit of load too. A `slope` assert, and a
`divideBy` one whose divisor the page cannot name, have no handles, because their bands are not
drawn against the series. Retune those in the editor.

The handles follow the chart's legend, as the Asserts tab's do. A legend click that rescales the axis
puts each handle back on its line. The lines are drawn with the first instance, so while the legend
hides that instance they are gone and so are the handles.

The usual loop: write a server assert with a first guess, run the three steps, then drag the line to
where the system's real behaviour says it belongs, and save.

### Drafts and saving

Every change is a draft until you save, exactly as on the Asserts tab, and the two tabs share one
document:

- **Drafts persist in your browser**, keyed by the run. A refresh keeps them, and they do not
  follow you into another run's report. The server half is also tied to the collection it was made
  against (see below).
- **A restored draft is announced here.** When a refresh brings server edits back, a line under the
  actions says `Draft restored — N server-assert changes to this report were kept in this browser
  and are not downloaded yet.`, with **Discard drafts**, the same Discard as the Asserts tab's.
- **Server edits from another collection are not restored, and the page says so.** A server draft
  holds only for the collection it was made against. Edits made on the interim report (step 1,
  nothing collected yet) are therefore not brought back on the step-3 report, while the client half
  of the same draft is. A line on this tab says how many were not restored and why, and the Asserts
  tab points here. They are still saved for the report they were made on: open it and download the
  file there. Editing here — a note, a client rule — leaves them saved, and so do **Discard
  drafts**, which discards only this page's own changes, and **Dismiss**, which hides the line for
  this page. The first server-assert edit on this page replaces them, and the line goes. When that
  edit is a **Delete** — a row or a component — or a component left with no server assert, its
  **Undo** puts them back while it is still offered, and the line returns unless you dismissed it.
  Any other server edit — a Disable, a Save, a drag, Generate, an assert added — has no Undo, so
  after one of those they are gone.
- **The unsaved pill is on both tabs**, and both count the same document: `N unsaved changes` in
  this tab's header is the number the Asserts tab shows, a note typed on the Notes tab included.
  A new component counts as a change. A file opened with no `"version"` key reads as the default
  version, so it adds no change of its own.
- **The draft banner lives on the Asserts tab**, and counts server edits too. It adds
  `server asserts: K changed — see the Server tab`, and labels its own verdict
  `Client verdict if saved and re-run`, because it never speaks for the server side.
- **Save from either tab.** **Download report-annotations.json** in this tab's header writes the same
  file as the Asserts tab's: the whole document, client rules and the `serverMetrics` block
  together, with any key this version does not recognise carried through. **Edit document…** is on
  both tabs too. *Copy file* is on the Asserts tab; *Discard drafts* is there and on this tab's
  restored-draft line, and drops this page's drafts on both sides, with an Undo. Server edits held
  for another collection are not this page's, and stay saved.
- **Delete** on a row is a draft too, and offers an **Undo**. Focus moves to the Undo, so the keyboard
  reaches it at once, and the toast stays up while it has focus. Tab or Escape from it goes back to
  the next row's ▸, else the previous row's ▸, else the card's **+ metric**, else
  **+ Add component**. Enter on Undo puts
  the row back with focus on its Delete, and holding Enter down does not press that Delete. The
  Asserts tab's Delete works the same way.
- **An Undo is offered only while nothing else has changed.** Every Undo — Delete on either tab,
  Discard drafts, Delete component, and the empty-component toast — puts back the document as it was
  before its action, so it would also take back any change made after it. The toast therefore goes
  the moment anything changes the document, on either tab, a typed note included; if focus was on
  its Undo, it moves to where Tab would have taken it. Filtering, sorting, expanding rows and
  switching tabs change nothing, and leave the Undo in place.
- **Save** puts focus back on the saved row's **Edit**. When the filters hide the saved row (the
  `FAIL` chip on, the failing assert loosened), focus goes to **Clear filters** instead and a toast
  says the row is hidden; the Asserts tab does the same.
- The saved document **masks every credential shape the file would be refused for**, so a mistake
  in the file is not copied into a download.

**Next-run fidelity**: feed the saved file to `jmeter -g` with the same payload and it reproduces
the verdicts the page showed. The page and the engine are tested against the same set of cases, and
an edited file downloaded from a real page is re-run with `jmeter -g` and compared field by field.

---

## Advanced

These keys are optional and inert when unused. The flow above needs none of them.

### `requiresLoad`: judge only under the load you asked for

A heap limit written for 200 requests a second says nothing about a run that reached 40. Name the
client rule that declares the load, and the server assert is judged only when that rule held:

```json
"serverMetrics": {
  "requiresLoad": "load:throughput",
  "asserts": [ { "id": "heap", "requiresLoad": "load:throughput", "…": "…" } ]
}
```

- The block-level key is the default for every server assert. A per-assert key overrides it.
- It names a client rule **by id**, and the rule's metric must be `throughput`. A `loadValidity`
  block becomes the rule `load:throughput` (see
  [Load validity](10-Asserts-Apdex.md#load-validity-was-the-run-even-valid)).
- Load rule PASS or WARN: the server assert is judged. Load rule FAIL: it is
  `LOAD_NOT_ACHIEVED`, naming the actual and the limit. Load rule not judged (a renamed sampler, say):
  `LOAD_NOT_ACHIEVED`, worded "was not judged" and never "missed".
- Naming no rule, or a rule that is not about throughput, is `INVALID_RULE`. That is checked with
  or without a payload, so a typo shows up on step 1's report and not only at step 3.
- An empty `"requiresLoad": ""` reads as no key at all, not as a rule named nothing: the assert
  takes the block's `requiresLoad` when the block sets one, and is judged without one when it does
  not. So an empty string cannot opt one assert out of the block's default.

### `divideBy`: per unit of load

`"divideBy": "load:throughput"` divides the reduced number by that rule's **measured** value. CPU
cores per request/s and heap bytes per request/s become comparable between runs at different
loads. The rule must be a throughput rule that matches exactly one target, because a rule over many
samplers has many measured values and none of them is *the* divisor. Anything else is
`INVALID_RULE`. A load rule that decided nothing leaves the server assert `LOAD_NOT_ACHIEVED`, and a
measured zero leaves it `NO_VALUE`. An empty `"divideBy": ""` reads as no key at all: the reduced
number is judged undivided, against a band written for a ratio. The board, the Summary card and JUnit write the unit as
`cores ÷ load:throughput`, and JUnit's testcase body names the rule on a `divideBy:` line.

---

## Gating a pipeline

The exit code is the first gate, and it needs nothing extra:

| What happened | Exit with the defaults |
|---|---|
| A component FAILed | 0, or 1 with `-Jwebinsight.report.failonassert=true` |
| A server assert the file got wrong | 0, said as `SERVER NOT JUDGED`; 1 with `webinsight.report.server.failonerror=true` |
| A source failed or coverage fell short | 0, said as `SERVER NOT JUDGED`; 1 with `webinsight.report.server.failonerror=true` |
| A query matched nothing (`NO_MATCH`) | 0, counted and reported |
| A server assert parked with `enabled: false` (`DISABLED`), one with no point in the judged window (`NO_VALUE`), or one whose load rule FAILED or was not judged (`LOAD_NOT_ACHIEVED`) | 0, counted and reported |
| A component with no server asserts yet | 0 |
| No payload given (step 1) | 0. The server asserts are not evaluated and `serverComponents` is omitted. JUnit's `serverMetrics` suite says so with a `<skipped>` |
| A payload given and refused (missing, another run's, unreadable) while an enabled server assert is declared | 0, said as `SERVER NOT JUDGED`; 1 with `webinsight.report.server.failonerror=true`. Either way the ERROR finding `serverMetrics.payload`, and the same sentence as a JUnit `<failure>` |
| The same with no enabled server assert declared | 0, with a warning |

To gate on the CI/CD JSON, copy one of these two lines. Both are tested on every build. Both treat a run with no client rule as passing the client half, and a run with no server block as
passing the server half.

**DEFAULT** refuses a client FAIL and `serverStatus` FAIL, the verdicts. It does not refuse a
component that judged nothing or a check that could not run. A check that could not run
(`INVALID_RULE`, `SOURCE_FAILED`, `LOW_COVERAGE`, or a refused payload) exits 1 only when
`webinsight.report.server.failonerror=true` is set, so pair this line with that, or use STRICT. A query that matched nothing,
a parked assert, a value that could not be measured, a load that was not established and a
component with no asserts exit 0, as the table above says, and neither line refuses them. The four
not-judged reasons are counted in `serverComponents[].notJudged`; a component with no asserts is
counted in none of them, and reads `NOT_EVALUATED` with `declared` 0:

```bash
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL"' report/web-insight-report.json
```

**STRICT** refuses everything DEFAULT refuses, plus every check the file asked for that never ran,
as far as the JSON can show it: `INVALID_RULE` on the client gate and on any server component;
`SOURCE_FAILED` or `LOW_COVERAGE` on any server component (every component builds, and so does the
`(no gate)` entry, so asserts in no declared component are counted); and any ERROR finding, which
covers an annotations file that could not be read and a refused payload a server assert needed. It
is the line to use when server data must have been collected and read: complete as far as the
collection and the file go. A query that matched nothing, an assert with no point in the judged
window, a parked assert and a load that was not established still pass it, as DEFAULT's paragraph
says. It cannot see an artifact that failed to be written. Only the exit
code reports that:

```bash
jq -e '(.gate.failed // 0) == 0 and (.serverStatus // "PASS") != "FAIL" and (.gate.notJudged.invalidRule // 0) == 0 and ([.serverComponents[]? | .notJudged.invalidRule + .notJudged.sourceFailed + .notJudged.lowCoverage] | add // 0) == 0 and ([.findings[]? | select(.severity == "error")] | length) == 0' report/web-insight-report.json
```

The console prints one clause per server component after the client verdict, for a log you grep:
`Server kafka: FAIL — 1 declared, 1 failed`.

---

## Limits

- **Post-run only.** Nothing is fetched or judged while the test runs. The window is the JTL's first
  to last sample, fetched afterwards.
- **One source per collection.** `wir-collect` takes exactly one of `--prometheus-url`,
  `--recording` and `--csv`. Metrics held in two places need exporting into one.
- **Clocks are assumed to agree (NTP), unless you say otherwise.** The window comes from JMeter's
  timestamps and the series from the source's. When the servers' clocks are known to be off, give
  `wir-collect --clock-offset`: a signed duration added to every timestamp the source reports, so
  `-30s` lines up servers whose clocks run 30 s ahead of the load generator. The Collection line
  shows the offset that was applied. Skew between distributed JMeter workers is not corrected.
- **Keep `--step` at or above the scrape interval.** Between scrapes Prometheus repeats the last
  sample, so a step finer than the scrape interval counts those repeats as data. Coverage reads
  higher than the data supports, and `sustainedFor` and `breachBudget` count repeated samples as
  time held. A `sustainedFor` must also be a whole number of steps: at `15s`, `"20s"` can never be
  true, and the assert is `INVALID_RULE`, naming the step. A CSV keeps its own spacing whatever
  `--step` says, so the same holds for the spacing the data really has: 4 s rows collected at `15s`
  under a `sustainedFor` of `30s` are `INVALID_RULE`, naming the measured 4 s spacing (`60s` works).
- **`slope` is per minute.** A least-squares fit over the judged window, in units per minute, so
  `"fail": {"gt": 1048576}` on a bytes series means "growing faster than 1 MiB a minute". Every
  surface writes it per minute: `fail > 1,048,576 B/min` on the board, `fail > 1048576 B/min` in
  JUnit.
- **Coverage is of the collected window**, decided at collection time (above).
- **Prometheus answers a range query of at most 11,000 steps (11,001 points) per series.** Keep the
  run's length divided by `--step` at or under 11,000. A ten-hour soak needs a step of 4 s or more. The collector checks it
  before asking and names the step that fits. A non-200 answer is reported with Prometheus's own
  error first (a 400 names the PromQL parse error), and a 401 or 403 points at
  `--prometheus-token-env`. A run whose window ends after the collecting machine's clock (the JMeter
  host is ahead) is refused rather than waited for, naming the `--clock-offset` to pass.
- **The page holds a capped copy.** Up to 20 instances per series and 4,000 points per instance,
  thinned rather than truncated, with the true counts stated. An instance is thinned by keeping each
  stretch's lowest and highest point, so its line still reaches the spike or the dip a `max` or a
  `min` was judged on. That is all the line promises. At the collection's step, the default, it
  joins the kept points in time order, so it swings between each stretch's low and high rather than
  following the series. At a coarser rung a `max` or `min` assert's bucket takes the highest or
  lowest kept point in it, and any other assert's bucket averages the kept points: an average of
  extremes (the midpoint of a stretch's low and high, where those two are all the bucket holds), not
  the mean of what was collected. The build judges the full payload. A
  capped series keeps the build's verdict on the page (`from the build`), and an edited or new assert
  on one reads `NOT RE-JUDGED`, on the board and in Set thresholds alike. The picker, Set thresholds
  and Generate take the build's own number for such a series, never the thinned copy's; where the
  build has none, nothing is proposed from the run.
- **A capped `across: avg` verdict cannot be re-derived from the page.** It is the average over
  every collected instance, and the page draws at most 20 of them from thinned points, so averaging
  the drawn instances' bars does not give the build's number. The build's actual is the one to read.
- **Thresholds are literal numbers.** No per-environment thresholds. Interpolation reaches the
  `--prometheus-url` value and queries only.
- **One verdict per server assert.** Instances are charted individually and judged through `across`
  (`max`, `min` or `avg`). There is no per-instance verdict.
- **Sources are Prometheus, recordings of it, and CSV files.** No "+ Assert from chart" on server
  charts.

---

## See also

- [Asserts Tab](12-Asserts-Tab.md): the client rules, and the Save and drafts the two tabs share
- [Asserts & Apdex](10-Asserts-Apdex.md): the statuses, the reasons, `failonerror` and `failonassert`
- [Export & CI/CD](11-Export-Integration.md): `serverStatus`, `serverComponents[]`, `serverAsserts[]`
  and the JUnit suites in full
