# Server data example

A complete run with server data, the same on Windows, macOS and Linux. Run it from this folder, with
the plugin JAR in JMeter's `lib/ext` and Node.js for the demo CSV.

| File | What it is |
|---|---|
| `plan.jmx` | 60 s, 5 users, three simulated calls (no HTTP); carries the Web Insight listener |
| `report-annotations.json` | the expected: components `app` and `db`, three server asserts |
| `run.properties` | the settings for both commands |
| `gen-csv.js` | stands in for your monitoring tool: writes `server.csv` covering the run's window |
| `fake-export.js` | the same stand-in, called the way a tool's export is: `node fake-export.js <startMillis> <endMillis> server.csv` |
| `hooks/after-run.sh`, `hooks/after-run.ps1` | after a run: read the window from the first report's `.json`, call your export, build the report with it |

```
jmeter -n -t plan.jmx -l results.jtl -q run.properties
node gen-csv.js results.jtl server.csv
jmeter -g results.jtl -o report -q run.properties
```

Delete `results.jtl` and `report` before running again (JMeter appends to a JTL, and `-o` needs an
empty folder). Then open `report/web-insight-report.html` and the **Server** tab:
`app-cpu-max` PASS, `app-mem-max` WARN, `db-connections-p95` FAIL.

The report written by the first command opens on **Add the server data**: the window to export and
the names to use. With your own servers, export that window from your monitoring tool as
`server.csv` instead of running `gen-csv.js`.

## As a script

After the first command, the hooks do the rest. Linux or macOS:

```
bash hooks/after-run.sh web-insight-report.json results.jtl report
```

Windows PowerShell (JMeter is run as `java -jar`, so the exit code is JMeter's):

```
$env:JMETER_HOME = 'C:/apache-jmeter-5.6.3'
powershell -ExecutionPolicy Bypass -File hooks/after-run.ps1 web-insight-report.json results.jtl report
```

Point `EXPORT_CMD` at your tool's export, called as `<EXPORT_CMD> <startMillis> <endMillis>
server.csv`; it defaults to `node fake-export.js`. `EXPORT_CMD` is split on whitespace, so a path
containing spaces is not supported: put the tool on `PATH`, or in a folder whose path has none.

See [Getting Server Data In](../../docs/Tutorial/14-Server-Data.md) for the GUI and more.
