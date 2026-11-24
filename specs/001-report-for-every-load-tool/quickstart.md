# Quickstart: validating "One Web Insight Report for every load tool"

Run from the project root, on Windows (Git Bash) or macOS. Contracts:
[run data](contracts/run-data-format.md), [commands](contracts/build-report-cli.md), [k6 adapter](contracts/k6-adapter.md),
[words](contracts/page-words.md).

## Prerequisites

- JDK 21, Maven, Node and Python 3, as for today's suites. The JMeter 5.6.3 install under `apache-jmeter-5.6.3/`.
- The conformance set committed under `test-plans/run-data/` (plan stage 5).
- The standalone bundle built: `mvn -q package` writes `jmeter-web-insight-report/target/*-standalone.zip`.

## 1. JMeter users see no change (SC-001)

```bash
./test-plans/scripts/run-sanity.sh
./test-plans/scripts/run-regression.sh
```

Expected: both runners' failure counts are 0. R07 holds every fixture, R06 every golden, and no golden is
re-accepted.

## 2. A JTL and its run data give one report (FR-006, FR-013)

```bash
J=apache-jmeter-5.6.3/lib/ext/jmeter-web-insight-report-*.jar
apache-jmeter-5.6.3/bin/jmeter -g test-plans/goldens/plain.jtl -o tmp/qs/jtl -Jwebinsight.report.rundata=true
java -jar $J build-report --run-data tmp/qs/jtl/web-insight-report-run-data.jsonl.gz --out tmp/qs/rundata
```

Expected: the two `web-insight-report.json` files are equal after canonicalising, `runId` included.

## 3. No JMeter on the machine (FR-015)

```bash
mkdir -p tmp/qs/standalone && unzip -q -o jmeter-web-insight-report/target/*-standalone.zip -d tmp/qs/standalone
env -u JMETER_HOME PATH=/usr/bin:/bin "$JAVA_HOME/bin/java" -verbose:class -jar tmp/qs/standalone/jmeter-web-insight-report-*.jar \
    build-report --from k6 --k6-results test-plans/run-data/K1/results.csv.gz \
    --k6-summary test-plans/run-data/K1/summary.json --out tmp/qs/k1 > tmp/qs/k1.classes.log
grep -c 'org.apache.jmeter' tmp/qs/k1.classes.log
```

Expected: the report is written, and the grep count is 0.

## 4. A k6 run follows JMeter's Aggregate Report (SC-002, SC-005)

```bash
./test-plans/scripts/run-run-data.sh      # NEW runner: K1-K3 through the adapter, R07 over each JMeter-format copy
```

Expected:
- every request-name row equals the referee's (100%);
- the CI/CD JSON matches `expected/` (counts, verdicts, every assert);
- K3 (whole seconds, no vu/iter) gives request rows only, and its Notes say why.

## 5. Same server data, same Server tab (SC-003)

```bash
java -jar $J build-report --run-data test-plans/run-data/S1/k6-run-data.jsonl --out tmp/qs/k1s \
    --annotations test-plans/goldens/gate-server.json --servermetrics test-plans/goldens/server-metrics.csv
# the collector is the JAR's default command
java -jar $J --run-data test-plans/run-data/S1/k6-run-data.jsonl \
    --annotations test-plans/goldens/gate-server.json --out tmp/qs/k1s.payload.json \
    --csv test-plans/goldens/server-metrics.csv
```

Expected:
- Built over the same window and server CSV, the k6 report's and the JMeter twin fixture's Server tabs list the same
  components, server asserts, states and server verdict.
- The collected payload is accepted by `build-report --servermetrics` (its runId matches).

## 6. A k6 report reads as k6 (SC-004, FR-007, FR-008)

```bash
cd test-plans/playwright && node node_modules/@playwright/test/cli.js test tests/tool-words.spec.ts tests/tool-tab.spec.ts
```

Expected:
- no JMeter word outside the two exceptions;
- the k6 tab lists K1's thresholds and checks with their pass or fail, the six timing parts per request name, and
  the iterations and dropped iterations;
- bytes read "not measured".

## 7. Size and speed (SC-006, the build-time goal)

```bash
./test-plans/scripts/measure-run-data.sh   # NEW: K2 times 100 (134,400 requests): build time and heap, page open time
```

Expected:
- the build fits `-Xmx512m`;
- the build takes no longer than `jmeter -g` over the same samples, plus 20%;
- the Summary tab draws within 5 s over `file://`.

The figures are written to the run's notes and reported.
