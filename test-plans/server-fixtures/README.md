# Server-data fixtures E28 and E29

Inputs for two Playwright fixtures `run-all-tests.sh` builds with `jmeter -g`, reading the CSV
itself. This directory is in the fixture stamp, so editing anything here regenerates every fixture.

| File | What it is |
|---|---|
| `e28-server.json` | E28's annotations: one custom component `app`, one assert per way an export goes wrong, plus one that lines up |
| `gen-e28.js` | writes `e28-metrics.csv`; its header comment maps each series to the assert and the verdict it gets |
| `e28-metrics.csv` | E28's server data over the goldens' `plain.jtl` window, committed (`node gen-e28.js > e28-metrics.csv`) |
| `e29-server.json` | E29's annotations: component `fleet`, a spike (FAIL), a sustained level (PASS), a slope (WARN) |
| `gen-soak.js` | writes E29's `soak.jtl` and `soak.csv` (four hours, 24 instances at 1 s, a fixed seed) into `test-results/E29-inputs/` at fixture time; not committed, because the CSV is ~25 MB |

E28 is three pages: `E28/E28-report.html` (the misaligned CSV, collected at an explicit 15 s step -
the step measured from this file would be 1 s), and beside it
`E28/interim/` (the same annotations, no server data) and `E28/refused/` (E26's payload under
another title, so its run id differs). E29 is written with the sidecar forced, so its server data is
read from `E29-report-data.js`. `server-fixtures.spec.ts` asserts on all four pages.
