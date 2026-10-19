# To do

Open work as of 4.0.4. Line numbers are at 4.0.1 unless marked otherwise; `report.js` is
`jmeter-web-insight-report/src/main/resources/static/js/report.js`.

## Decide

1. **Header badge.** Should it speak for the client rules only? Today the same file reads
   `ASSERTS NOT EVALUATED` on the interim report and `SERVER ASSERTS ONLY` at step 3. It also reads
   `SERVER ASSERTS ONLY` when client rules exist but none decided anything.
2. **Server `NOT EVALUATED` row** (interim/refused). It sorts last, below DISABLED, and looks exactly
   like DISABLED. Should it rank and look like NEEDS RE-COLLECTION instead?
3. **A component with no asserts** makes `serverStatus` NOT_EVALUATED, which outranks WARN and PASS.
   Keep that?
4. **One spelling per state** on the page and console: WARN/WARNING, NO VALUE/"not measurable",
   component id/label. The JSON/JUnit wording stays.
5. **Duplicate rule ids.** A duplicate id is an ERROR finding but not a config error, so STRICT
   refuses it while DEFAULT + failonerror passes. Two id-less rules deriving one id: rename, CONFIG
   ERROR, or warning?
6. **Appended JTL** (`-l` reused): warning only, or CONFIG ERROR?
7. **Jackson alerts.** Jackson is pinned to 2.16.1, JMeter 5.6.3's own `provided` copy, and
   Dependabot ignores it (`.github/dependabot.yml`). Its alerts are dismissed as "not shipped";
   revisit only when the JMeter version moves.
8. **spec-author agent:** narrow `Bash(node:*)` to the Playwright CLI?
9. **Config tier:** parallelise it (medium work)?
10. **Kafka's CSV-only KPIs.** Star the three `recommended: false` ones (`kafka.json:36`, `:248`,
    `:264`)?
11. **A warn/fail pair that can never PASS** (`warn lt 100` with `fail gt 50`): WARNING or
    INVALID_RULE?
12. **AF1 vs AF3:** which goes first, if either is scheduled.

## Bugs

High:
- **Raw `NOT_JUDGED` text** for a match the run could not measure. Five places use `chipLabel(m.status)`
  instead of `displayStatus`:
  - match rows (report.js:11497)
  - editor preview rows (:13913)
  - cell tooltip (:14376)
  - Samplers badge (:14614): unstyled, and shown where Java shows no badge
  - judging row (:14684)
- **A NOT RE-JUDGED row's chart shows a breach**: a red wash, full-strength bands and "judged: …".
  The cause is that `undecided` checks only `NOT_JUDGED` (report.js:19206, 19839, 20143).

Medium:
- Budget is memoised by rule id (report.js:10360), so two rules sharing an id show one Budget.
- The Asserts toolbar counts leave out NO VALUE, so the parts don't add up to "declared"
  (report.js:8946).
- An INVALID client rule still draws the bold fail line on the Timeline (report.js:15404).
- The Summary chart loses "+ Assert from chart" after a palette/theme change: no `attachChart` in
  `initSummaryResponseTimeChart`. Found by reading, not seen in a browser.
- With Σ Total checked, the error charts ignore the sampler filter.
- The page does not type-check `loadValidity.target`/`tolerance` (a boolean, object or array)
  the way Java does.
- Wide-CSV headers (`WideCsv.java`) skip the invisible-padding check a long file's `metric`
  cell gets.
- The server mirror judges at load even when the Server tab is never opened. Measure a soak
  report before changing it.

Low:
- Errors tab: the By-time view's Time column uses the JVM time zone; the Grouped view uses the
  browser's.
- Errors and Compare: a hidden chart is not remembered across a reload.
- Wording:
  - report.html:746 says the error-rate threshold is "%"; it is percentage points.
  - ServerMetricsMapper.java:323-326 names the retired `auth.<name>Env` and `${secret.NAME}`.
  - A refused payload is called "nothing collected" in Validate query, the toolbar and the chart
    note.
  - `→ ? if saved` uses a bare `?` as a status word.
  - The NO VALUE sentence prints raw epoch milliseconds (`ServerKpiEvaluator.describeWindow`).
  - On a run that declared no rules, a missing sidecar still says "the assert rules … could not be
    loaded" (report.html:988-992).
- Shown nowhere: Apdex T is in chartData but not on the page.
- `artifacts.xml.outcome` always reads `PLANNED`, because the JSON is staged before the XML.
- Server tab:
  - Its filter chips are never coloured: `.wir-filter-chip` (report.css:3522) overrides
    `.wir-chip-*`.
  - An active status filter loses its chip when its count drops to 0 (report.js:18290).
  - `wir-count-not_evaluated` has no CSS.
  - The Summary server card counts components one way in its sub-line and another in its chips.
  - The `(no component)` card has no badge.
  - Validate query ignores DISABLED, INVALID and LOAD NOT ACHIEVED.
- Worst-match ties differ from Java for integer-like sampler names (`Object.keys` order,
  report.js:9904).
- Hiding `series[0]` in a legend removes the threshold lines, wash and handles. Put them on a
  silent carrier series instead.
- The G2 golden labels `offgrid-percentile` NO_VALUE, but it records FAIL.
- Latent: the Asserts tab sort/filter drop a status that has no rank (`compareBy` `|| 0`,
  `presentStatuses`). Port the Server board's fallback.
- Dead code:
  - `visibleKpis`
  - "Server · nothing judged" and the chip's "NOT JUDGED" arm. Remove their docs lines with them.
- `prune-actions.sh`: the caches listing ignores the exit code and is not paginated (the runs
  listing was fixed in 4.0.4).

## Docs

- README:
  - the "Single-quote it" paragraph appears twice (lines 261 and 279);
  - the reportExample link opens 2.7 MB of HTML source on GitHub;
  - there is no link to the docs site.
- Tutorial 13 "From Datadog" shows only the Bearer PAT, which Datadog marks Preview. Datadog
  documents `DD-API-KEY` + `DD-APPLICATION-KEY` for this endpoint.
- Tutorial 12 says a sampler no rule judges has no badge. That is the intended behaviour; the page
  breaks it (the raw `NOT_JUDGED` bug above).
- Stale comments:
  - AssertionValidator.java:22-24
  - the header comments of `run-all-tests.sh`, `run-regression.sh` and `run-title-junit-tests.sh`
  - `run-sanity.sh:16` (the pre-push hook consumes no exit code; it runs no tests) and `:56-57`
    ("the only place they still run": `ci.yml` runs them too)
  - `ci.yml:4` ("three cheap consistency guards"; it runs many more) and `:16-18`, which quotes a
    CLAUDE.md sentence that no longer exists
  - the hook headers (`.githooks/pre-commit:14`, `.githooks/pre-push:7`), which say there is no CI
- `list-scenarios.sh` misses the `run_test` scenarios.
- `plans/asserts-future.md` and `plans/stabilization-2026-08-09.md` list finished work as open.

## Tests

- **Interim and refused reports.** No fixture is one. Add two `-g -o` variants of E26: one with no
  payload, one with a refused payload. Today these states are tested only on rewritten copies.
- **NO VALUE**, client and server: no fixture reaches it.
- **Rendered but never asserted:**
  - E27: `SERVER ASSERTS ONLY`, the chip WARN, source OK
  - E25/E26: the sampler WARN badge
  - E26: the UNMATCHED and SOURCE FAILED row chips
  - E10: the Summary card PASS
  - C35/G8: the refused row's sentence
- **Weak assertions:**
  - server-tab.spec.ts:397-399 (`indexOf` passes when the chip is missing)
  - client-fixes:196 and asserts-seeding:263 (regexes accept any verdict)
  - asserts-chart-lines:238 and :248 (match the whole table's HTML)
  - server-board:193-200 and verdict-block.spec:86-99 (compare the page with itself)
  - summary-tab.spec.ts:170-176 (asserts nothing)
- **Goldens hold none of:** top-level WARN, NOT_EVALUATED, NO_VALUE, SOURCE_FAILED, a load rule that
  was not judged, `(no gate)`, the interim `serverMetrics` suite (its `<skipped>` testcase; G2 and
  G4 do have `<skipped>` cases).
- **js-unit:** nothing tests the sort/filter/count functions, or checks that every CSS class the
  code builds has a style.

## Prevent

- Make one list of display states the source: `WIR.status.STATES`, with `DISPLAY`, `SORT_RANK`,
  `STATUS_ORDER` and `CHIP_LABEL` derived from it.
- Add a js-unit test that holds the code, the CSS, the marked docs tables and the specs to that list,
  in both directions, and a Java twin for the JSON/JUnit tables.
- A docs pass starts by listing the states from the code and looking for each one in the docs.

## Housekeeping (needs your go)

- Delete the remote branches `chore/release-4.0.0`, `chore/release-4.0.1` and
  `docs/status-vocabulary`.
- `git fetch --prune` clears the four stale local remote refs: `origin/gitImprovements` and the
  three Dependabot ones (Thymeleaf 3.1.5, jackson-databind 2.18.11 and 2.22.0).

## Parked ideas

- Client:
  - time windows
  - sustained breach
  - baseline-relative bands
  - `fasterThan:N`
  - off-grid percentile preview
  - distributed clocks
  - burn-rate
  - payload scale
- Server:
  - ramp-down exclusion
  - pre-test/cool-down
  - coverage over the judged window
  - run-over-run baseline
  - `mustHaveData`
  - `serverMetrics.load`
  - per-instance verdicts
  - per-environment thresholds
  - distinct exit codes
  - "+ Assert from chart"
  - server verdicts on other tabs
  - per-slice Discard
  - `minPoints`
  - a `nan` policy
- Types:
  - rabbitmq
  - sqlserver
  - mongodb
  - ALB / aws-rds / aws-sqs
  - elasticsearch/OpenSearch
  - dotnet
  - nodejs
  - gRPC
  - kafka-msk
- Catalogue:
  - drift check
  - discovery
  - organisation catalogue
  - versioning
- Collector:
  - `wir-collect --report`
  - multi-file collection, plus reading a Prometheus `query_range` file
- Code:
  - merge the two table-helper families
  - a sampler named "Whole run" shares the whole run's label in the sampler filter
