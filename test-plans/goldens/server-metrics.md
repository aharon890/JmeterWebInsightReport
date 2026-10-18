`server-metrics.csv` is the server data for G4, G5 and G7, in the CSV backend's long format
(`plans/server-metrics-backends.md` §5): one row per point, `time,component,metric,instance,value`.

- `up` — one point a second over plain.jtl's window, values 1..50, for both `kafka` and `db`
- `sparse` — three points on `db`, so coverage sits under the floor

A series is its component and its query, so `up` is written once per component whose asserts
ask for it. `nothing` has no rows on purpose: a query the file declares and the CSV does not
answer is what makes the `NO_MATCH` server assert in `gate-server.json` reachable.

## Regenerating `server-metrics.json`

The payload beside this file is collected from the CSV, and it is a **committed constant** — the
baselines of G4, G5 and G7 are diffed against what it produces (G5 and G7 declare their own
asserts, but only over series `gate-server.json` already collects; a server assert over a series
this payload does not carry would be refused, not judged). To rebuild it, with `JMETER_HOME` set
to the JMeter `build-and-deploy.sh` deployed this tree's JAR into — `wir-collect.sh` tries the
variable, then `jmeter` on `PATH`, and the checkout's `apache-jmeter-*` only last, so another
JMeter on `PATH` would collect with an older JAR:

```bash
JMETER_HOME=/path/to/the/deployed/apache-jmeter ./scripts/wir-collect.sh \
  --jtl         test-plans/goldens/plain.jtl \
  --annotations test-plans/goldens/gate-server.json \
  --csv         test-plans/goldens/server-metrics.csv \
  --step        1s \
  --out         test-plans/goldens/server-metrics.json \
  --no-wait
```

Do not pass `--test-name`. The default is what makes the payload's `runId` equal the one
`plain.jtl` produces under the exporter's own derivation, and a payload whose `runId` does not
match is refused rather than embedded — G4, G5 and G7 would then assert against reports with no
server block at all.

Regenerating always changes the payload, because `collectionId` is a hash that includes
`collectedAt`. So any rebuild is necessarily followed by:

```bash
./test-plans/scripts/run-goldens.sh --accept   # then commit expected/G4.*, G5.* and G7.* with it
```

which is the review point: the new `collectionId` reaches `serverComponents[].collectionId` in the
baseline, and that diff is supposed to be seen rather than absorbed.
