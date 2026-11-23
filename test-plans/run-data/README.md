# Run data fixtures (spec 001)

Committed inputs for the report's tool-neutral path: k6 runs read by `build-report --from k6`, and later run data
files written by hand. No load tool except JMeter runs in the suites (constitution III), so every k6 fixture is
committed data. Each one also carries a JMeter-format copy of its samples, so R07 holds it to JMeter's Aggregate
Report exactly as it holds a JMeter fixture.

| Id | What | Inputs | JMeter-format copy |
|---|---|---|---|
| K1 | Every shape a k6 run writes: a nested group, a 500, a refused connection (status 0), `http.batch`, a redirect, a second pass of a group, an unnamed request, setup and teardown, passing and failing checks, passing and failing thresholds, and one group pass cut off by the test's end | `results.csv.gz`, `summary.json` | `results.jtl`, `in-test-plan.jtl` |
| K2 | petstore-checkout SMOKE, recorded with perf hub's two k6 settings, at the smallest load and on the owner's yes | `results.csv.gz`, `summary.json` | `results.jtl`, `in-test-plan.jtl` |
| K3 | An older k6 file: whole-second times and no vu/iter (perf hub run #66, petstore-browse SMOKE). It gives request rows only | `results.csv.gz`, `summary.json` | `results.jtl`, `in-test-plan.jtl` |

## How they were made

- **K1** is recorded in perf hub's k6 image against local stub servers. No test bed is involved.

  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v "<abs>/K1/record:/w" -v "<abs>/K1:/out" --entrypoint bash \
      perf-hub-k6:2.4.0 /w/record.sh
  ```

  k6 exits 99, because a threshold fails on purpose.
- **The JMeter-format copies** are written by `tools/k6_to_jtl.py`, the converter from perf hub's research. It matched
  JMeter's Aggregate Report in 198 of 198 referee checks. It is kept only to write these copies: an independent
  translation that the report's own k6 adapter must agree with.

  ```bash
  python -I tools/k6_to_jtl.py K1/results.csv.gz K1/results.jtl K1/in-test-plan.jtl
  ```

  `results.jtl` holds every row (the referee's per-request reference), and `in-test-plan.jtl` the top-level rows (its
  whole-run reference, what a listener in the plan receives).

## The pre-change baseline

`jmeter-web-insight-report/src/test/resources/rundata/baseline-4.4.1/` holds the report data both JMeter paths built
before spec 001: twelve inputs, including the listener with response bodies, sub-results and a transaction.
`PreChangeBaselineTest` holds every later build to them. Re-write them only for a change that is meant to move JMeter
users' numbers, which spec 001 is not:

```bash
mvn -q test -pl jmeter-web-insight-report -Dtest=PreChangeBaselineTest -Dwir.baseline.write=true
```
