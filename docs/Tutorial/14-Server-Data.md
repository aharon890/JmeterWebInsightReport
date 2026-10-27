# Getting Server Data In

The report compares what your servers **should** do (server asserts in `report-annotations.json`)
with what they **did** (a CSV exported from your monitoring tool, or Prometheus). This page is the
how-to; [Server](13-Server-Tab.md) is the reference for reading and editing the tab.

```
 YOU PROVIDE                    THE PLUGIN DOES                         YOU GET
 -----------                    ---------------                         -------
 a test plan (.jmx)  ---------> runs the test                    -----> results.jtl
                                                                        a first report: "Add the
                                                                        server data" - the window to
                                                                        export, the names to use
 report-annotations.json
 a CSV from monitoring -------> jmeter -g reads the CSV, matches  -----> the report, Server tab judged
   (same time window)           it to the run, judges the asserts
```

Two things decide whether it works, and the report tells you both:

1. **The time window.** The CSV must cover the run. The first report prints the run's start and
   end in UTC, in your time and in epoch milliseconds.
2. **The names.** Each CSV row names a `component` and a `metric` exactly as the annotations file
   does. The first report lists them, and offers a CSV template.

Server data never fails the build by default: the client report and its verdict always stand, and
every server problem is said on the page, the console and the JSON
(`webinsight.report.server.failonerror=true` makes them fatal).

## The CSV

```text
time,component,metric,instance,value
2026-10-20T20:21:44Z,app,cpu.pct,,35.0
2026-10-20T20:21:44Z,db,connections,,20
```

- `time`: ISO 8601 with `Z` or an offset, or epoch milliseconds.
- `component`, `metric`: as the annotations file writes them (a catalogue metric's name, or a custom
  assert's query text).
- `instance`: optional; `value`: a number with a `.` decimal point.
- A row is matched by its `component` and `metric` names, never by the component's `match` or the
  query: after editing a component's `match`, export the file again. The old file still reads, and
  is judged as the data the old `match` selected.

Grafana's "Series joined by time" download, InfluxDB 2's annotated CSV and exports in Datadog's,
Telegraf's, OpenTelemetry's or CloudWatch's own metric names are read too: see
[Where the metrics come from](16-Server-Reference.md#where-the-metrics-come-from) for the option each needs.

## By hand, in the JMeter GUI

1. Add the **Web Insight Report** listener and set its **Annotations File** and its **Results file
   (JTL)**, such as `C:/runs/checkout/results.jtl` — the rebuild reads the run's JTL. When the
   file exists already, JMeter asks whether to append or overwrite; choose overwrite for a new run.
2. Run the test. Open the report it wrote and go to the **Server** tab: under **Add the server
   data** it shows the window to export and the names to use, and **Download a CSV template**.
3. Export that window from your monitoring tool as a CSV, and save it beside the JTL.
4. In the listener, set **Server data (CSV)** to that file: type the path, or use **Browse...**. The
   line under the field shows what it found, such as
   `Resolves to C:/runs/checkout/server.csv · found, 2.1 MB`.
5. Click **Rebuild report with server data**. A dialog shows the verdict, the collector's own lines
   (each series and its coverage, rows outside the run, near-miss names, the step measured from the
   file) and every problem or warning. **Open report** opens the report in your browser; **Copy**
   copies the dialog's text.

The rebuild writes over the report the run wrote: the same file, in the same folder. It is built from
the JTL, as `jmeter -g` builds it, and a JTL holds no response bodies, so the rebuilt report's error
details carry none. Each click reads the CSV and the annotations file again, so after a new export or
an edit to the asserts, click it again.

What the button reads:

- **The run's own settings**: the results file, the output folder, the report name and the title the
  last run in this JMeter session used. A Report Title edited since the run applies from the next run.
  After JMeter restarts, or when the last run wrote no report, there is no run to read them from: every
  field is read as it is now, and the dialog says so.
- **Server data (CSV) and Annotations File**: a field unchanged since the run reads as the run read
  it, so a test-plan variable in it works. An edited field is read at the click, with JMeter functions
  and properties evaluated, such as `${__P(runs.dir)}/server.csv`. A `${name}` it cannot resolve then
  is refused, naming it: a test-plan variable exists only while a test runs, so use `${__P(name)}`.
- **`webinsight.report.servermetrics`**, when JMeter was started with it (`-J`, `-q` or
  `user.properties`), wins over the field. The line under the field then reads
  `Overridden by webinsight.report.servermetrics = D:/other/server.csv - this is the file Rebuild reads`.
- **The reader options** (`webinsight.report.servermetrics.timezone`, `.clockoffset` and the rest)
  come from JMeter's properties, as for `jmeter -g`: see
  [The reader options as properties](16-Server-Reference.md#the-reader-options-as-properties).

The field also takes the `server-metrics.json` payload `wir-collect` writes. The button is refused
while a test is running, because the JTL is still being written. A CSV saved before the run ended is
probably the previous run's export, and the line under the field warns. A large CSV is read in the
JMeter GUI's own memory, about ten times the file's size. At the end of a run, the console's
`WEB-INSIGHT-REPORT: NEXT` line names the file the button would read (`webinsight.report.servermetrics`,
else the **Server data (CSV)** field) and the button.

### A JTL written by `-l` or another listener

The button rebuilds the report of the listener's own **Results file (JTL)**. For a JTL written by
`-l` or by another listener, use JMeter's **Tools › Generate HTML report**:

1. Write `run.properties` beside the JTL and the CSV, with forward slashes in the paths:

   ```properties
   jmeter.reportgenerator.exporter.webinsight.classname=com.jmeterwebinsightreport.report.exporter.WebInsightDataExporter
   webinsight.report.annotations=C:/runs/checkout/report-annotations.json
   webinsight.report.servermetrics=C:/runs/checkout/server.csv
   ```

2. **Tools › Generate HTML report**: the results file, `run.properties` as the user.properties
   file, and a new output folder. Click **Generate report**.
3. Open `web-insight-report.html` in that folder.

JMeter's dialog says "Report created !" whatever the server side found; the report's Server tab says
the rest.

Write the paths in `run.properties` in full. A relative path is read against JMeter's working folder,
which under the GUI's dialog is JMeter's own `bin/`; a relative `webinsight.report.servermetrics` that
is not found there is also looked for beside the results file. The output folder must be new or empty
(JMeter refuses one that holds anything), so give each report its own.

## By hand, from a terminal (Windows, macOS, Linux)

The same two commands on every platform — Windows PowerShell or cmd, macOS Terminal, a Linux shell:

```
jmeter -n -t plan.jmx -l run/results.jtl -q run/run.properties
jmeter -g run/results.jtl -o run/report -q run/run.properties
```

- `jmeter` is JMeter's own launcher: on your `PATH`, or called by its path
  (`C:\apache-jmeter-5.6.3\bin\jmeter.bat`, `~/apache-jmeter-5.6.3/bin/jmeter`; with Homebrew on
  macOS the plugin JAR goes in `$(brew --prefix jmeter)/libexec/lib/ext`).
- Keep the settings in `run.properties`, not in `-J` arguments: Windows PowerShell splits an unquoted
  `-Jwebinsight.report.x=y` at the first dot (the plugin catches it and says so). A relative CSV name
  is found beside the JTL.
- Between the two commands the first prints `WEB-INSIGHT-REPORT: NEXT — …` with the window and the
  names: export the CSV then.
- `-o` must be a folder that does not exist yet (or is empty), never the run folder itself.
- Open the report: double-click it, `start run\report\web-insight-report.html` (Windows),
  `open run/report/web-insight-report.html` (macOS), `xdg-open …` (Linux).

**Exit codes on Windows.** `jmeter.bat` returns 0 even when the run failed. When a script must gate on
the exit code (a client FAIL under `failonassert`, a configuration error), call JMeter as
`java -jar <jmeter>/bin/ApacheJMeter.jar -g …` — the same text on every OS, with the real exit code —
or gate on the JSON.

## Automated: a script or webhook

The contract is files and one command; the plugin needs no daemon or endpoint.

```bash
RUN=/ci/runs/$BUILD_ID
jmeter -n -t plan.jmx -l "$RUN/results.jtl" -q "$RUN/run.properties"
# the window, from the first report's JSON
read START END < <(jq -r '.window | "\(.startMillis) \(.endMillis)"' "$RUN/web-insight-report.json")
# what to export, under which names
jq -r '.serverData.series[] | "\(.component) \(.metric)"' "$RUN/web-insight-report.json"
# your export, one minute either side; write then rename, so a half-written file is never read
my-export --from $((START-60000)) --to $((END+60000)) > "$RUN/.server.csv.part" && mv "$RUN/.server.csv.part" "$RUN/server.csv"
# build into a fresh folder, then swap it in; serialise rebuilds of one run
flock "$RUN/.rebuild.lock" sh -c "jmeter -g '$RUN/results.jtl' -o '$RUN/report.new' -q '$RUN/run.properties' \
  && rm -rf '$RUN/report' && mv '$RUN/report.new' '$RUN/report'"
```

- `window` and `serverData` are in every CI/CD JSON; `serverData.state` reads `NOT_COLLECTED` until
  the data is added, then `COLLECTED`.
- Re-running the last step (data landed late, export fixed) is safe, and keeps the Server tab's drafts
  when the CSV did not change.
- A ready-made version of this script, for bash and for PowerShell, is in the examples bundle
  (`examples/server-data/hooks/` in the repository, `server-data-example-X.Y.Z.zip` on a release):
  `after-run.sh <first-report.json> <results.jtl> <new-output-folder>`, with your export command in
  `EXPORT_CMD` (called as `EXPORT_CMD <startMillis> <endMillis> server.csv`; it is split on
  whitespace, so a path containing spaces is not supported). It needs no `jq`, and the
  PowerShell one runs JMeter as `java -jar …/ApacheJMeter.jar`, so the exit code is JMeter's.
- **A title computed at run time** (a `${__time()}` or `${__groovy(…)}` in the plan's report title)
  exists only in the first JVM. `jmeter -g` runs in another one and names the run otherwise: another
  `runId`, so a separately collected `server-metrics.json` is refused as another run's, and the Server
  tab's drafts are not restored. Pass the first report's own title, and the ramp-up it judged by:

  ```bash
  TITLE=$(jq -r .testName "$RUN/web-insight-report.json")
  RAMPUP=$(jq '(.window.rampUpEndMillis // .window.startMillis) - .window.startMillis' "$RUN/web-insight-report.json")
  jmeter -g "$RUN/results.jtl" -o "$RUN/report.new" -q "$RUN/run.properties" \n    -Jwebinsight.report.title="$TITLE" -Jwebinsight.report.rampup="$RAMPUP"
  ```
- Gate with the DEFAULT or STRICT line of the [Server tab's gating section](13-Server-Tab.md#gating-a-pipeline).
- Prometheus: run `wir-collect --prometheus-url …` in place of the export and name its
  `server-metrics.json` in `webinsight.report.servermetrics` (see [wir-collect](16-Server-Reference.md#wir-collect)).

## Lining up the data with the run

The run's window is from its earliest sample's start to its latest sample's end. A row more than one
`step` outside it is not used. What happens when the times do not line up:

| The CSV | What the report says | Fix |
|---|---|---|
| covers the run | judged | — |
| starts or ends inside the run | `LOW COVERAGE` (coverage under the floor) | export the whole window |
| is entirely outside the run, by a whole number of hours | `SOURCE FAILED`: "…none inside the run … They start 3h late, which is usually a time zone" | write the zone (`Z` for UTC, or `+03:00`), or set `webinsight.report.servermetrics.timezone`, or move it with `.clockoffset` |
| is entirely outside the run, by seconds | `SOURCE FAILED`, naming the shift as clock skew | `webinsight.report.servermetrics.clockoffset=-30s` |
| is from another day | `SOURCE FAILED`: "The file covers another period" | export the run's window |
| has rows before and after the run and none inside it | `SOURCE FAILED`: "…none inside the run … before and after the run" | export the run's window |
| has times with no zone | `SOURCE FAILED` naming the line | add the zone, or `.timezone` |
| has epoch seconds, microseconds or nanoseconds | `SOURCE FAILED` naming the unit | epoch **milliseconds** |

A wide export (say, "last 24 hours") whose local times are written as `Z` is the one mistake no check
can see: it covers the run, on the wrong hour. Compare the **Window judged** line on the Server tab
with the times your monitoring tool shows.

## Troubleshooting by symptom

| You see | Why | Fix |
|---|---|---|
| Server tab: **Add the server data** / chip `Server · NOT COLLECTED` | this report was built without server data | follow the panel. After **Rebuild report with server data**, reload the same report (or click **Open report**); after Tools › Generate HTML report or `jmeter -g`, open the one in the new output folder |
| the line under **Server data (CSV)** reads `Overridden by webinsight.report.servermetrics = … - this is the file Rebuild reads` | JMeter was started with that property (`-J`, `-q` or `user.properties`), and it wins over the field | remove it there and restart JMeter, or point it at this run's file |
| the line under **Server data (CSV)** warns the file was saved before this run ended | it is probably the previous run's export | export this run's window again |
| **Rebuild report with server data** refuses a `${name}` | a test-plan variable in a field edited since the run, or in any field after JMeter restarted (there is no run to read it from); it exists only while a test runs. `${timestamp}` in Report Filename is the plugin's own and is left alone | `${__P(name)}`, with the property set when JMeter starts |
| **Rebuild report with server data** refuses `the backslash before ${…} stops JMeter evaluating it` | a `\` right before `${` is an escape to JMeter's function parser, so the function never runs | write the path with `/`, as the refusal shows it |
| `UNMATCHED` — "no row of the CSV names component db, metric connections" | no row carries that pair | the names table under **Add the server data**; the console lists the pairs the file does carry |
| `SOURCE FAILED` — "no row names component app, metric cpu.pct; 62 rows name App/cpu.pct" | a case or `.`/`_`/`-` difference | write the names exactly as the annotations do |
| `INVALID` — "sustainedFor 30s is not a whole number of the data's measured 4s spacing" | the export's interval does not divide the `sustainedFor` | a `sustainedFor` that is a multiple of both the interval and the step (`60s` for 4 s rows at 15 s) |
| console: `WARNING component db: every catalogue metric it asks for (3) came back empty` | nothing in the source answers for that component | its `match`, or whether its exporter is collected at all |
| `SOURCE FAILED` — "…has 6 cells where the header has 5" | an unquoted `,` inside a value (`90,9`) | a `.` decimal point, or quote the cell |
| `SOURCE FAILED` — "…none inside the run…" | the times miss the run | [Lining up](#lining-up-the-data-with-the-run) |
| chip `Server · REFUSED` | the payload belongs to another run, or the CSV could not be read | the tab says which |
| the report says nothing was declared, and the console printed `CONFIG ERROR — JMeter received a property named "webinsight"` | PowerShell split an unquoted `-J` | put the settings in `run.properties`, or quote each `-J` |
| `WARNING — … holds a control character` | a single backslash in a properties-file path | forward slashes |
| JMeter: "Cannot write to '…' as folder is not empty" | Tools › Generate HTML report's output folder, or `jmeter -g`'s `-o`, already holds files (the Rebuild button writes over its report and needs no new folder) | a new folder |
| only JMeter's own dashboard (`index.html`) in the output folder | the Web Insight exporter is not registered | the `jmeter.reportgenerator.exporter.webinsight.classname` line in `run.properties` |
