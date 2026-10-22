# Server data example

A complete run with server data, the same on Windows, macOS and Linux. Run it from this folder, with
the plugin JAR in JMeter's `lib/ext` and Node.js for the demo CSV.

| File | What it is |
|---|---|
| `plan.jmx` | 60 s, 5 users, three simulated calls (no HTTP); carries the Web Insight listener |
| `report-annotations.json` | the expected: components `app` and `db`, three server asserts |
| `run.properties` | the settings for both commands |
| `gen-csv.js` | stands in for your monitoring tool: writes `server.csv` covering the run's window |

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

See [Getting Server Data In](../../docs/Tutorial/14-Server-Data.md) for the GUI and a script.
