# Server metrics — delivery plan

> **In progress.** Milestone status is the checklist below — update it as each milestone lands.
> Source design: `plans/server-metrics-design-v2.md`, as amended by the review in
> `plans/server-metrics-design-v2-review.md` (2026-08-21). Where the two disagree, the review
> wins on everything **Aaron has not decided himself**. Its §7 was a list of *recommendations*
> ("Your open decisions"), and M1–M7 were built treating them as decided; a parallel session the
> same night had recorded Aaron's own answers (`tmp/server-metrics-implementation-plan.md` v3 §0.1)
> and they never reached this file. The **checkpoint of 2026-10-02** (D23–D29, below) is his
> reconciliation of the two and overrides both where they disagree. Its §9 list is what v1 ships,
> **minus packs** (D26).
> Canvas §1–§6 (`plans/design_handoff_asserts/Server Metrics Integration.dc.html`) stand only
> where the review did not narrow them (§4.1–§4.7, §5.1–§5.3, §6.4 all narrow something).
>
> **Vocabulary note (D25).** From M7d on, the user-facing word is **server assert(s)**: the file's
> `serverMetrics.kpis` becomes `serverMetrics.asserts` and the JSON's `serverKpis` becomes
> `serverAsserts`. This plan was written before that and says "KPI" throughout; read it as "server
> assert". Internal identifiers (`ServerKpi`, `KpiResult`, `WIR.serverKpis`) keep their names, as
> the `badge-pass` CSS classes did — they are not user-facing and renaming them is pure churn.

## Progress

| | Milestone | Status |
|---|---|---|
| M1 | Reason vocabulary — `NotJudged` + 3, one config-error decision, goldens re-accepted | ✅ done — 3.1.3, commit `7165a68`, regression 6/6 (568 browser tests, R06 green) |
| M2 | Server KPI model in core — `ServerKpi`, `ServerGate`, `Judged` seam, mapper, usability, status→reason | ✅ done — 3.1.4, reviewed (3 lenses, all applied), 705 Java tests, regression 6/6 |
| M3 | Collection — payload schema, `file` + Prometheus sources, `wir-collect`, `collectionId`, generator merge | ✅ done — 3.1.5, reviewed (3 lenses, 44 findings incl. the `${secret.*}`-in-URL leak, all applied), 748 Java tests, config 36/36 (C35 new), regression 6/6 |
| M4 | Evaluation — reducer × band, `across`, `requiresLoad`, per-gate summaries, exit fold, JSON/JUnit, parity vectors | ✅ done — 3.1.7, commits `20da9de` + `c81101e`, reviewed (3 lenses + 3 verifiers, 38 findings, 0 blockers, all applied or recorded), 838 Java tests, 63 parity vectors, config 38/38 (C36/C37 new), regression 6/6 (568 browser, G1–G3 byte-identical) |
| M5 | Server tab (read-only) + header chip + fixture E26 + XSS guards widened | 🔶 built, reopened under M7c (no review, no explore session) — 3.1.8, commit `ab3382a`, 842 Java tests, regression 6/6 (580 browser tests, +12 for the tab), goldens unmoved |
| M6 | Browser mirror + `WIR.document` assembler + server drafts | 🔶 built, reopened under M7c (no review; end-to-end gate never built; drafts do not survive a reload) — 3.1.10, commits `b5122a5` (mirror) + the assembler; 66 parity cases green in Java, node and the browser; regression 6/6 (588 browser tests) |
| M7 | Authoring — KPI editor, Validate query, Generate from sources, chart retuning | 🔶 built — 3.1.12, commits `0b271a1` (editor) + `04ffef3` (M7b); regression 6/6 (605 browser tests). **Not reviewed, gates partly unmet** — see §M5–M7 "As built"; Add from pack moved to v2 (D26) |
| M7c | Review of M5–M7 (3 lenses) + fix pass — the verified defects of the 2026-10-02 plan review, the M6 end-to-end gate that was never built, a server-drafts reload spec | ✅ fix pass done — 3.1.13, workflow `wf_f3b51549-617` (6 groups × implement → adversarial review → repair, 15 agents), commits `8984d45`…`25758ef`; 924 Java tests, 675 browser tests, config 39/39 (**C38 new: the M6 gate** — it found a real locale bug on its first run), goldens unmoved after A. The M5 explore session runs after M7d, on the final UI. Residue → M7d group X |
| M7d | Checkpoint decisions — `serverStatus` (D24), "server asserts" rename (D25), packs → v2 (D26), lenient collector exit (D27), JUnit observe gates (D29), the jq idiom, and the M7c residue | ✅ done — 3.1.19, workflow `wf_117de833-cb4` (4 groups, 10 agents; reviews of S and X asked for repairs), commits `3c1e6b4`…`f0def30`; 1063 Java tests, 695 browser tests, config 39/39, goldens G1–G8. The review of X found a **pre-existing secret leak** (a password holding `@` reached `resolvedUrl`) — fixed. review:W's lows → M8 item 8 |
| M8 | Documentation (scope rewritten 2026-10-02) | ✅ done — 3.1.22, commits `bb18830` (docs) + `9dd1202` (screenshots, `reportExample/`); Tutorial 13 new with seven E26 screenshots, link check 0 dead of 155, every jq line verbatim from `jq-gate.txt`. See §M8 "As built" |
| M9 | Land — sync with `main`, every runner, push, one PR | 🔶 every runner run on 3.1.22 (2026-10-02): regression 6/6 (717 browser tests, R06 green), config 39/39, all-tests 20/20, error 9/9, docker 2/2, family-ab 23/24, family-cd 13/14, family-e 7/7, family-fh 9/10, family-g 5/5, family-ij-extra 8/8, title-junit 29/29. The three non-passes all predate the branch: A04 is BLOCKED by construction, C06 is a recorded DIFF, and H02 asserts a log line main reworded in `17a6211`. As of the last fetch, `origin/main` has no commit this branch lacks. **Push and the PR are pending Aaron** |

M5, M6 and M7 were marked ✅ on 2026-08-23 without the multi-lens review M2–M4 each had (the
"commit early, review as a follow-up" rule of 2026-08-22 turned into "no review"), and with two gate
items unmet — M5's explore-report session and M6's download → `jmeter -g` → same-verdicts test. The
2026-10-02 plan review (`tmp/server-metrics-plan-review-2026-10-02.md`) found three high-severity
defects in that code. M5/M6 are therefore re-opened under M7c rather than left green.

## Decisions taken (review §7, plus the ones the review added)

| # | Decision | Where it bites |
|---|---|---|
| D1 | **One `server` gate by default, `failsBuild: true`.** Implicit only while `serverMetrics.gates` is absent; once `gates` is declared an unnamed KPI is `INVALID_RULE`. | M2 mapper |
| D2 | **Reason vocabulary, not a fifth status.** `NotJudged` gains `SOURCE_FAILED`, `LOW_COVERAGE`, `LOAD_NOT_ACHIEVED`; the four existing reasons are reused verbatim. `gate.notJudged` gains three always-present keys (additive, never a reshaped block). | M1 |
| D3 | **Config errors are decided once**, by `NotJudged.isConfigError()`: `INVALID_RULE`, `SOURCE_FAILED`, `LOW_COVERAGE`. They are `<failure>` in JUnit and fail the process under `failonerror` — for the two server-only ones, subject to the owning gate's `failsBuild` (D4). `NO_MATCH` is **not** fatal on either side (symmetry with `asserts-config-errors-fail.md` §1). `LOAD_NOT_ACHIEVED` is neither flag — the load rule already carries the FAIL, or, when it decided nothing (D6), its own reason carries whatever is fatal about it (`INVALID_RULE` on the client) and nothing is fatal where nothing is wrong (`NO_MATCH`, `DISABLED`). Its phrase is "the load it depends on was not established" since M7c (A1-2): "the run missed its load target" was false for the second branch. | M1, M4 |
| D4 | **`failsBuild: false` silences the exit for that gate's collection failures** (review §2.3 option A). `INVALID_RULE` is always fatal. The marker always prints. An observe gate whose KPIs all failed collection reads `NOT_EVALUATED`, never PASS. | M4 |
| D5 | **Reducer × band grammar.** `reduce: {kind: max\|min\|avg\|last\|pN\|slope, over?: {sustainedFor} \| {breachBudget}}`, `across: max\|avg`, `warn`/`fail` bands as the client, `divideBy: "<load rule id>"` for ratio. `slope` is a linear regression over the window in **units per minute**. Deleted: `assert.type`, `range`, `warnAt`, `onMissing`, `required`, `onLoadShortfall`, per-KPI `loadValidity`, `scope`, `overlayOn` (retired KPI keys, `INVALID_RULE` naming the replacement); `deltaPre`/`deltaBaseline` are simply not reducer kinds (`UNKNOWN_REDUCER`). | M2, M4 |
| D6 | **`requiresLoad: "<client rule id>"` by reference** (run-level default `serverMetrics.requiresLoad`). Load FAIL → dependents `LOAD_NOT_ACHIEVED`; load NOT_JUDGED → dependents `LOAD_NOT_ACHIEVED` naming the load rule and its own reason, worded "was not judged", never "missed" (a renamed load sampler therefore stands the dependents down without failing the build — the client NO_MATCH and each KPI's detail say so); WARN/PASS → judge. Naming no rule, or a non-throughput rule → `INVALID_RULE`, decided with or without a payload (A1-3). | M4 |
| D7 | **A server KPI is its own final type** with a `Judged {id, description, enabled}` seam shared with `AssertionRule`, and a `JudgedResult {judged, status, notJudged, notJudgedDetail}` seam shared by `RuleResult` and `KpiResult`, so one `GateSummary.of(List<? extends JudgedResult>)` serves both. KPIs never enter `AssertionRule`, `MetricRef`, `AssertionTarget` or `SamplerVerdicts`. | M2 |
| D8 | **Wire shape.** `gate` unchanged in meaning and position. `serverGates[i] = {id, label, failsBuild, collectionId, …gateBlock(GateSummary.of(kpiResults))}` — same block, same arithmetic. *(The jq idiom first written here errored on every client-only run and read false on every server-only one; since M7d the idioms live in one file, `test-plans/goldens/jq-gate.txt`, a DEFAULT and a STRICT line, each run over the goldens its `CASES` line names (G1–G8) by `verify_jq_idioms` (S03/R03/CI) and by `jq-gate.test.js` — copy from there.)* `assertsEvaluated` = any gate ran; `assertsDeclared` = any gate declared. JUnit: one `<testsuite name="<testName> - <gateId>">` per server gate; root counts stay sampler-suite only. | M4 |
| D9 | **One window**, `[startTime, endTime]`, optionally excluding `[startTime, rampUpEnd]` when `rampup` is set; "window used" on every KPI row; alignment by timestamp through the same bucket ladder as the client charts. A ratio KPI's `divideBy` names a load rule and divides by **that rule's measured value over its own target** (per-sampler or whole-run, as the rule says) — never a per-bucket value; whole-run `overall.throughput` is simply what a run-level `load:throughput` rule measures. Pre/cool-down windows are v2+. | M3, M4 |
| D10 | **Sources: Prometheus + `file`.** `file` reads a recorded range-query response — the offline replay path for users and the deterministic fixture for the suites. Secrets by reference only (`auth` keys end in `Env`; a literal token is `INVALID_RULE`); resolved queries are stored with userinfo stripped and secret params masked; a serializer test proves a canary under `sources[].auth` never reaches HTML, `.js` or JSON. Datadog/Elastic/Grafana are v2+. | M3 |
| D11 | **Three-step choreography.** `-n -t` interim report (client gate only, exit informational) → `wir-collect` writes `server-metrics.json` beside the JTL → `jmeter -g results.jtl -o <fresh dir> -Jwebinsight.report.annotations=<file> -Jwebinsight.report.servermetrics=<path>` is the report Jenkins archives (the annotations property is required: `-g -o` demands an empty output directory, so there is nothing to auto-detect). The merge is in `WebInsightReportGenerator`, shared by both paths; `run-context.json` is dropped (the collector derives window and `runId` from the JTL through core). `wir-collect` is a `main` inside the shaded JAR, launched by `scripts/wir-collect.sh`. | M3 |
| D12 | **Identity.** The payload carries the page's `runId` (mismatch → refused by `ServerMetricsPayloadLoader` as a config problem and **not embedded**; M5 may raise `wirBanner` over the withheld tab, never `wirIdentityMismatch`) **plus** `collectionId = hash(sources, resolved queries, window, collectedAt)`; server drafts key on `runId+collectionId`; `collectionId` is stamped into `serverGates[]`. `RunIdentity`'s contract is untouched. | M3, M6 |
| D13 | **Distributed clock: closed.** Window = `[metadata.startTime, metadata.endTime]` as the JTL records them; worker skew out of scope (NTP assumed, documented); per-source `clockOffsetMs` stays and is always shown. | M3 |
| D14 | **Payload.** Series enter the chart-data map beside `buildChartDataMap` (`ServerMetricsPayloadWriter.addTo`: counted by the threshold, present in inline, external and streamed outputs) with an explicit instance cap per series and a point cap per instance, thinned not truncated, the true counts carried; `wirBanner` hoisted out of the external-only conditional; a unit test that a server block pushes a small threshold into external mode. Downsampling to the report bucket ladder happens when the charts are drawn. | M3 (entry, caps, threshold test, banner hoist), M5 (ladder) |
| D15 | **Packs are authoring-only.** "Add from pack" materialises KPIs into `kpis[]` with a `pack: "kafka@1"` stamp; the evaluator reads `kpis[]` only; a `packs` key at load is a config-level ERROR under `serverMetrics` pointing at the editor (exit 1 under `failonerror` from M4); the KPIs beside it still map. *(Superseded by D26: packs are v2, "Add from pack" was never built, and the `packs` refusal no longer points at an editor.)* | M2 (refusal); the authoring half is v2 (D26) |
| D16 | **`WIR.document` assembler**, owned by neither tab: modules register a slice `(name, build, baseline, discard)`; both Save buttons, the import path and the unsaved pill read it; one draft record per `runId(+collectionId)` holding both slices; Discard per slice. The "no diff on an unedited file" round-trip test covers both. | M6 |
| D17 | **Header: client badge unchanged + one server chip** naming the worst *building* gate and counting the rest ("Server · kafka FAIL · 2 more"). Non-building gates contribute to the count, never the word. No rollup verdict anywhere, and nothing new in the JSON. Per-gate badges live in the tab. | M5 |
| D18 | **Environment-dependent thresholds are out of v1.** Interpolation (`${env.*}`, `${run.*}`, `${secret.*}`) in source URLs and queries only (there is no `headers` key in v1; auth is the `auth.*Env` references); `warn`/`fail` are literal numbers. `serverMetrics.environments.<NAME>.vars` and `defaultEnv` (canvas §2) are carried by M2's mapper for M3 to resolve; the selected environment is stamped into the payload. | M2 (carried), M3 |
| D19 | **Per-instance KPIs are display-only** (charted, "and N more"); v1 judges `across: max\|avg` over the reduced series. Server results never enter `SamplerVerdicts`. | M4, M5 |
| D20 | **Server tab is the 8th tab, after Asserts.** Its module is `WIR.serverKpis`; every inline handler is one `WIR.serverKpis.x('literal')` call; `xss-escaping.spec.ts` and `verify_report_js_rules` widen to `WIR\.(asserts\|serverKpis)\.` and `#tab-asserts, #tab-server` in the commit that adds the tab. Samplers-table column, timeline-marker breaches and Compare overlays are cut. | M5 |
| D21 | **Parity is a fixture, not a promise.** `server-parity-vectors.json` is written before the browser mirror, run by `ServerParityTest` (Java), `server-parity.test.js` (node) and `server-parity.spec.ts` (browser). `AssertionParityTest`'s reason guard is split by `NotJudged.serverOnly()`. | M4, M6 |
| D22 | **Validate query is an exact lookup** of `(source, resolved query)` against the collected series keys — matches and coverage for a collected query, "needs re-collection" for anything else, never an estimate. Editing `source`/`query` withholds the verdict. | M7 |

## Checkpoint 2026-10-02 — Aaron's decisions (override D1–D22 where they disagree)

Asked five questions after the plan review; his answers, and how each is applied:

| # | Decision | Applied as | Where |
|---|---|---|---|
| D23 | **The model is expected vs actual.** "User sets backend asserts (expected), then the report is fed with server metrics (actual results) and these two are compared." | That is the core flow, and the docs lead with it. `requiresLoad` and `divideBy` (D6, D9) **stay** as optional, advanced keys — built, tested, and inert when unused — and are documented after the core flow, not in it. His 08-21 "per-side `serverMetrics.load`" is not built; it is in the v2 backlog. | M8 |
| D24 | **Client and server statuses are managed separately.** | The CI/CD JSON gains a top-level **`serverStatus`** beside the client `status` (unchanged): present exactly when `serverGates` is, value = the worst status over **building** gates — `FAIL` > `NOT_EVALUATED` (a building gate that judged nothing, including one refused by a config error) > `WARN` > `PASS`; `NOT_EVALUATED` when there is no building gate. The header chip uses the same fold, so a building gate that exits 1 can no longer lose the header word to another gate's PASS (review L3-6). Still no rollup of client + server anywhere. | M7d |
| D25 | **A more specific word than "KPI"** (which could equally describe a client metric). | **"Server assert(s)"** in the UI, the docs, the console and the wire: annotations `serverMetrics.kpis` → **`serverMetrics.asserts`**, CI/CD JSON `serverKpis[]` → **`serverAsserts[]`**, "+ Add KPI" → "+ Add server assert". `serverMetrics` keeps its name — it is the block that holds the *actual* data's sources, the window and the asserts about it, and the property/payload (`webinsight.report.servermetrics`, `server-metrics.json`) are about metrics, not asserts. Nothing has been released, so there is no migration; a leftover `kpis` key is refused like any retired key, naming `asserts`. A test sweeps the CI/CD JSON for any key containing `kpi`, as `CiCdJsonSerializerTest` already does for `sla`. | M7d |
| D26 | **Packs move to v2.** (He was not sure what a pack was; they are one-click bundles of server asserts for a component — Kafka, JVM, Postgres.) | §9's "packs as authoring" is cut from v1. The `packs` config error stays (a file carrying one still means something v1 does not do) but stops pointing at an "Add from pack" button that does not exist. Tutorial 13 carries a short "asserts for common components" section with example queries instead. The research for v2 (sources, licences, exporter variants, seeding direction) is in the plan review §5. | M7d, M8 |
| D27 | **Lenient on missing data — harden later.** | A server `NO_MATCH` stays non-fatal (D3 unchanged). **`wir-collect` exits 0 whenever it wrote the payload**, whatever the series' statuses — the payload records them and the report decides what they mean (the intent §M3 already stated). Exit 1 stays for usage, input and environment faults. The previous exit 2 stopped a Jenkins `sh` step before step 3, the only report that gates. | M7d |
| D28 | **Coverage stays a collect-time property of the collected window** (the M4 "left open" item, never decided in M5). | Not recomputed at judge time. Every place that shows coverage says "of the collected window", and the docs say why a long ramp-up makes it optimistic. Recomputing over the judged window is v2. | M7c, M8 |
| D29 | **An observe-only gate never turns a build red or yellow** (follows from D4 and D27; the JUnit channel was never decided). | An observe-only gate's FAIL, and its collection failures, are written as `<skipped>` carrying the verdict and detail, not `<failure>` — Jenkins' junit step marks a build UNSTABLE on any failure, which contradicted the `system-out` line saying the gate never fails the build. Building gates are unchanged. | M7d |

## Branch & merge protocol

One branch, `feat/server-metrics-v1`, one commit per milestone, each milestone's gate green before
its commit. Main changes by pull request only (the hooks refuse anything else); whether the PR is
per milestone or per phase is Aaron's call at the time. Every milestone that changes plugin
behaviour bumps `<revision>` (PATCH) and redeploys, so `lib/ext` holds exactly one plugin JAR.

## Milestones

### M1 — Reason vocabulary *(done, 3.1.3)*

`NotJudged` gains `SOURCE_FAILED`, `LOW_COVERAGE`, `LOAD_NOT_ACHIEVED` (appended — the original
four keep their wire position) plus three decisions made once by exhaustive switch: `countKey()`,
`label()`, `isConfigError()`, `serverOnly()`. `GateSummary.configErrors()` replaces
`invalidRule()` at the JUnit writer, the `failonerror` record and the console line, so a reason
cannot land in `<skipped>` by subtraction. `GateEvaluation.logSummary` and
`ReportArtifacts.verdictLine` iterate `NotJudged.displayOrder()` (config errors first, then the
order the console always had); the Summary strip is a hand-kept mirror of that order, like
`REASON_KEY`. Two stated behaviour changes on existing runs, both unobservable to every suite
and runner: the log line now uses `label()`'s words and lists only non-zero reasons. `AssertionParityTest`'s reason guard is split by side;
`GateOutputTest` demands every key present. `report.js` mirrors the vocabulary whole
(`REASON_KEY`, `REASON_LABEL`, `REASON_PHRASE`, `SORT_RANK`, `emptyNotJudged()`), and
`asserts-parity.spec.ts` sums whatever keys the gate carries. Goldens G1–G3 re-accepted; docs'
seven-key arithmetic; CLAUDE.md's stale "no golden harness" paragraph corrected.

Gate: `mvn test`, js-unit, `run-goldens.sh`, `run-regression.sh`.

### M2 — Server KPI model in core

`core/servermetrics/`: `ServerGate {id, label, failsBuild}`; `ServerKpi` (D5 fields,
`declarationProblems()` like the client; `requiresLoad`/`divideBy` carried verbatim and **not
validated until M4**, which resolves them against the client rules); `ServerMetricsConfig {gates,
gatesDeclared, sources, kpis, environments, defaultEnv, requiresLoad, coverageFloor, excludeRampUp,
problems}`; `ServerMetricsMapper` reading the typed `serverMetrics` block with the client mapper's
discipline — unknown keys ignored, the nine retired canvas keys (below) recorded as declaration
problems; `KpiUsability` (the single answer to "can this KPI run": unknown/unusable source, missing
query, undeclared gate, unknown reducer, bad tolerance, unreadable band, retired key — and two
WARNINGS that never refuse: warn covered by fail, a negative-only band on a non-slope reducer) —
**findings do not gate**; `CollectionStatus {OK, PARTIAL, INSUFFICIENT, EMPTY, FAILED,
NOT_REQUESTED}` with `toReason()` as an exhaustive switch (`FAILED→SOURCE_FAILED`, `EMPTY→NO_MATCH`,
`INSUFFICIENT→LOW_COVERAGE`, `OK`/`PARTIAL`→null, **`NOT_REQUESTED→INVALID_RULE`** — a KPI over a
series the collector never asked for is the file and the collection disagreeing, which is a config
error and therefore always fatal under `failonerror`, `failsBuild` notwithstanding) and a
`CollectionStatusTest` like `UsabilityTest`. Pure library, no wiring, no sources.

Gate: `mvn test` — every new type exercised; the invariant-bearing ones (`KpiUsability`,
`CollectionStatus`, `Reducer`/`Durations`, `KpiResult`) in their own class, the rest through
`ServerMetricsMapperTest`.

**The seam, concretely.** `core/assertion/Judged` (`id()`, `description()`, `enabled()`) implemented
by `AssertionRule` and `ServerKpi`; `core/assertion/JudgedResult` (`judged()`, `status()`,
`notJudged()`, `notJudgedDetail()`) implemented by `RuleResult` and `KpiResult`.
`GateSummary.of(List<? extends JudgedResult>)` counts through the interface; `results()` returns
`List<JudgedResult>` and the five client consumers (`SamplerVerdicts`, `GateEvaluation`,
`CiCdJsonSerializer`, `JunitXmlWriter`, `GatePayloadBuilder`) plus the tests switch to
`ruleResults()`, a typed filter — so a client gate is untouched in behaviour and a server gate
cannot be fed to a client renderer by accident. One gate holds one concrete kind; nothing builds
a mixed one. `KpiResult`'s factories are **public** so M4's report-module tests can build a
`SOURCE_FAILED` result without an evaluator and prove the JUnit `<failure>` choice.

**The KPI shape the mapper accepts** (`serverMetrics` becomes a typed block on `ReportAnnotations`
with `@JsonAnySetter` capture at every level, so unknown keys still round-trip):

```json
"serverMetrics": {
  "gates":   { "kafka": { "label": "Kafka", "failsBuild": true } },
  "sources": { "prom": { "type": "prometheus", "url": "https://prom-${env.name}.corp/api/v1",
                         "auth": { "bearerTokenEnv": "PROM_TOKEN" }, "step": "15s", "clockOffsetMs": 0 },
               "rec":  { "type": "file", "path": "server-metrics/recorded" } },
  "environments": { "TEST": { "vars": { "name": "test", "cluster": "eu-test" } } },
  "defaultEnv": "TEST",
  "requiresLoad": "load:throughput",
  "collection": { "coverageFloor": 0.9, "excludeRampUp": true },
  "kpis": [ { "id": "kafka.consumer.lag", "description": "…", "enabled": true,
              "source": "prom", "query": "…", "unit": "msgs",
              "reduce": { "kind": "max", "over": { "sustainedFor": "30s" } },
              "across": "max", "requiresLoad": "load:throughput",
              "warn": { "gt": 640 }, "fail": { "gt": 800 }, "gate": "kafka", "pack": "kafka@1" } ]
}
```

`reduce.kind ∈ max|min|avg|last|pN|slope`; `over` is `{sustainedFor: "<duration>"}` or
`{breachBudget: <fraction>}`, never both; `across ∈ max|avg` (default `max`); `gate` defaults to
`server` only while `gates` is absent (an empty `gates: {}` counts as declared). Durations are
the Prometheus grammar — `30s`, `2m`, `500ms`, `1h`, lowercase, no inner space, never a bare
number. A written `null` means absent (the block is `NON_NULL` on write-back); a written `false`,
`0` or `""` survives. The nine retired canvas keys on a KPI (`assert`, `range`, `warnAt`,
`onMissing`, `onLoadShortfall`, `loadValidity`, `required`, `scope`, `overlayOn`) plus a misplaced
KPI-level `sustainedFor`/`breachBudget`/`over` are recorded as declaration problems,
`INVALID_RULE`, with the replacement named — because ignoring any of them changes what the KPI
judges. A top-level `packs` key is a config-level problem (counted with the gate's config errors
in M4, so it exits 1 under `failonerror`). An `auth` value whose key does not end in `Env`, a
URL carrying userinfo, a URL query parameter with a credential's name (`api_key`, `token`, `sig`…
— `Redaction`'s name rule) whose value is anything but `${secret.NAME}`, or a source-level key
that looks like a secret (`token`, `password`, `apiKey`, `authorization`…) makes the **source**
unusable — a config-level problem too, so it counts even when no KPI names the source — and every
KPI on it `INVALID_RULE` with that sentence. An `environments.*.vars` value carrying either URL
shape (userinfo with a password half, or a credential parameter) is a config-level problem on the
environment. The refusal tells the author; the published document additionally **masks** every
shape the mapper refuses as a credential (`ServerMetricsJson.publishable()`): a non-`Env` auth
value to `***`, a URL's userinfo to `***@`, a credential parameter's value to `***`, the value of
a secret-looking source-level key to `***`, the same URL shapes inside a var, and any var a
credential position reaches through `${env.*}`. That is the whole promise: a secret written **in
one of those shapes** reaches no artifact. A credential in a shape none of them recognises — a bare
value in a var that a query uses, text in a `description` — is not detected and is published as
written; credentials belong in `auth.*Env` and `${secret.*}` only. (Until M7c only the auth value
was masked — L9-2 — and the other shapes were refused and published at once; the first M7c pass
still missed the query parameter and the vars.) The one fault the mapper does not collect: a wrong-shaped container
(`"kpis": {}`, `"warn": 640`, `"reduce": "max"`) fails Jackson before the mapper runs and is a
whole-file load problem, as the client's `warn`/`fail` maps already are.

**Unreachable bands on a server KPI are a WARNING, not a refusal.** The client check assumes
every metric is non-negative, which is true of durations and counts and false of a Prometheus
gauge in general; refusing `fail: {lt: 0}` on a `slope` KPI would be wrong, and refusing it on a
gauge would be a guess about the system under test. The verdicts are correct either way —
severity is the distinction, as for `warnCoveredByFail`.

### M3 — Collection

`server-metrics.json` schema (the canvas never gave one), as built: `{schema, runId, collectionId,
environment, collectedAt, window{start,end,rampUpEnd?}, sources[{id,type,status,stepMillis,
clockOffsetMs,resolvedUrl?,error?}], series[{source, query, resolvedQuery, status, coverage?, error?,
instances[{labels, points[[ts,value]…]}]}]}` — `ts` a whole epoch-millisecond, never a double.
`SeriesSource` interface; `FileSource` (a recorded Prometheus `query_range` response per query, keyed
by resolved query); `PrometheusSource` over `java.net.http.HttpClient` (no new dependency, nothing
for `verify_shaded_jar` to allow) with the canvas §3 settle loop (floor max(2×step, 30 s), poll,
stable-for-2, `maxWait` then return what arrived — the floor decides PARTIAL/INSUFFICIENT);
`Redaction` (by value first, by shape second); `WirCollect` `main` in `com.jmeterwebinsightreport` +
`scripts/wir-collect.sh` (JMeter's `lib/` on the classpath); `-Jwebinsight.report.servermetrics=<path>`
read by `WebInsightReportGenerator`; `collectionId`; series into the chart-data map with instance and
point caps; `wirBanner` hoisted; the external-mode threshold test.

Gate: `mvn test` (HTTP layer against `com.sun.net.httpserver`; payload round-trip; redaction canaries;
threshold flip), **C35** proving the property, the script's classpath and the `runId` agreement
survive a real JMeter process.

**As built** (2026-08-22; the "Concretely" below was written before the code and corrected after the
three-lens review — where the two disagreed the code is described here):

- Package is `core/servermetrics/collect/`, not `payload/`: the payload beans, the sources, the
  collector and the pure helpers (`Coverage`, `Redaction`, `Interpolation`) in one place.
- `SeriesSource.collect(…) → Answer{instances | error}`; the **collector** assigns the
  `CollectionStatus` (FAILED / EMPTY / by coverage against the floor), never the source.
- The settle loop's cap returns what arrived, unlabelled; an **empty** answer settles after
  `stableFor` identical polls instead of waiting out `maxWait`; a poll that fails after an earlier
  one answered returns that answer. The window is requested in the source's clock
  (`start − clockOffsetMs`, `end − clockOffsetMs`); `parse` adds the offset back.
- `wir-collect` options are exactly `--jtl --annotations --out --env --test-name --rampup
  --max-wait --no-wait` (`--key=value` accepted); an unknown or repeated option exits 1 with the
  usage line — a misspelt `--test_name` would otherwise change the run's identity in silence.
  Exit 1 for usage/input/environment faults, 2 when any series is not judgeable, 0 otherwise.
  *(Exit 2 was retired in M7d, D27: 0 whenever the payload was written.)*
  A relative `file` source `path` resolves against the annotations file's directory.
- `scripts/wir-collect.sh` needs only the **deployed** JAR (no `require_deployed_plugin`, which
  demands this checkout's `target/`); refuses a `lib/ext` holding two plugin JARs; builds a
  Windows classpath by converting each path with one `cygpath -w` call and joining with `;`
  (joining a POSIX list with `;` defeats MSYS's own conversion — C35's first failure; `cygpath -wp`
  over the list splits a `D:/…` `JMETER_HOME` on its drive colon); honours `JAVA_HOME`.
- **Redaction is by value first.** `Interpolation.Result.secretsUsed()` hands back every
  `${secret.*}` value substituted into a URL; the collector masks those and the source's
  `auth.*Env` values (`Redaction.values`) in `resolvedUrl` and in every error text before a record
  is written, then applies the name rule (`Redaction.url`: userinfo, params matching
  `(?i)token|key|secret|password|passwd|auth|credential|sig`). The review found the name rule
  alone let `https://h/${secret.T}/api?t=${secret.T}` into every HTML report.
- `ServerSource.AUTH_KEYS` / `authProblem` own the auth vocabulary (`bearerTokenEnv`, or
  `usernameEnv` + `passwordEnv`); the mapper refuses anything else at load (INVALID_RULE), the
  reader asks the same function again.
- Anything still shaped like a placeholder after resolution (`${evn.x}`, `${ENV.x}`, nested) is
  refused, not sent to the source as a literal.
- `ServerMetricsPayloadLoader.read(file, runId)`: missing / unparseable / other schema / other
  `runId` / a series status this version does not know → a problem, payload not embedded.
  `ReportArtifacts.configProblemReason()` names every recorded problem, not the first.
- Canaries: `ReportDataSerializerTest` (a literal under `sources[].auth` reaches neither the
  inline chart JSON nor the external `.js` content — the CI/CD JSON carries no annotations, so
  there is nothing to assert there); `SourcesAndCollectorTest` (a `${secret.*}` and a basic-auth
  value reach the HTTP request and nothing in the written payload: not `resolvedUrl`, not a source
  or series error, not a response body that echoes them).
- `PrometheusSource.live()` is exercised against `com.sun.net.httpserver` (header, Accept,
  query string, a real socket); the settle loop against an injected `Http`.
- Console lines from `wir-collect` are ASCII (they are for `grep` in a CI log, written in the
  platform charset); the payload and the report are UTF-8 as before.
- C35 owns its JTL, fingerprints the query through `common.sh`'s new `sha256_hex` (`sha256sum` on
  GNU, `shasum -a 256` on macOS), and captures the collector's exit status before the `if`.

**Concretely** (decided 2026-08-22 after reading both ingestion paths, the serializer and the pom):

- **Property.** `webinsight.report.servermetrics=<path>` read in `WebInsightReportListener` (after
  `annotations`, same `-J` → GUI-less pattern) and `WebInsightDataExporter` (after `annotations`),
  into `ReportConfiguration.serverMetricsFile`. No auto-detection: under `-g -o` the output
  directory must be empty, so there is nothing to detect in.
- **Payload model** in core, `core/servermetrics/collect/`: plain Jackson beans (core has
  `jackson-databind` at compile scope). `ServerMetricsPayload {schema: "wir-server-metrics/1",
  runId, collectionId, environment, collectedAt, window{start, end, rampUpEnd}, sources[],
  series[]}`; `SourceRecord {id, type, status, stepMillis, clockOffsetMs, resolvedUrl, error}`;
  `SeriesRecord {source, query, resolvedQuery, status, coverage, error, instances[]}`;
  `InstanceRecord {labels{}, points[[ts, value]…]}` (`Point {long ts, double value}`, written as
  an array). Series are keyed by `(source, resolvedQuery)` — the exact lookup "Validate query"
  (D22) will do.
- **Sources.** `SeriesSource` interface with `collect(ServerSource, resolvedQuery, window) →
  Answer`; the collector assigns the status. `FileSource` reads `<path>/<16-hex fingerprint of
  resolvedQuery>.json` in Prometheus `query_range` response shape (`data.result[].metric` →
  labels, `values` → points); a missing file is `EMPTY`, an unreadable one `FAILED`.
  `PrometheusSource` over `java.net.http.HttpClient` (JDK 11; nothing new for
  `verify_shaded_jar`): GET `<url>/query_range?query&start&end&step` in the source's clock,
  bearer/basic from the named env var, the canvas §3 settle loop (floor `max(2×step, 30s)`, poll
  every 15 s, last point must reach `window.end`, two identical polls, `maxWait` 10 m then
  return what arrived). Coverage = fraction of expected steps in the window that have a point,
  after `clockOffsetMs`.
- **`wir-collect`.** `com.jmeterwebinsightreport.WirCollect` `main` in the report module (inside
  the shaded JAR): `--jtl <results.jtl> --annotations <file> --out <server-metrics.json>
  [--env NAME] [--test-name NAME] [--rampup ms] [--max-wait 10m] [--no-wait]`. Parses the JTL
  through core (`JtlParser` → `TestMetadata.startTime/endTime`, `RunIdentity.of`), maps
  `serverMetrics`, resolves `${env.*}`, `${run.*}` (`startTs, endTs, runId, testName`) and
  `${secret.*}` in URLs and queries only, collects each distinct `(source, resolvedQuery)` once,
  writes the payload through `AtomicFileWriter`. Exit 0 when every requested series is
  `OK`/`PARTIAL`, 2 otherwise (the status is in the file either way; the report, not the
  collector, decides what a failure means), 1 when nothing could be collected at all.
  *(Superseded by D27 in M7d: exit 2 is retired, 0 whenever the payload was written, 1 when
  nothing was.)*
  `scripts/wir-collect.sh` puts `$JMETER_HOME/lib/*` and the one plugin JAR in `lib/ext` on the
  classpath — Jackson and slf4j are `provided` by JMeter, which is why it cannot be `java -jar`.
- **Redaction.** Every secret value the collector resolved is masked by value wherever it
  appears; then `resolvedUrl` is written with userinfo stripped and any query parameter whose
  name matches `(?i)token|key|secret|password|passwd|auth|credential|sig` masked to `***`;
  `resolvedQuery` is written as resolved — queries carry labels, not secrets, and `${secret.*}`
  is refused inside a query (a secret belongs in a header). Two canaries: the serializer test
  plants a literal under `sources[].auth` and proves it reaches neither the inline chart JSON nor
  the external `.js`; the collector test plants `${secret.*}` and auth env values and proves they
  reach the HTTP request and nothing in the written payload.
- **`collectionId`** = `RunIdentity.digest(material)` (the existing private SHA-256/16-hex made
  public under that name; `fingerprint` was already taken by the package-private run material)
  over `sourceId|type|redactedUrl` for every source, every resolved query, the window and
  `collectedAt`. **The test name is part of `runId`** — the `-g` exporter names a run by its
  report title (`ReportConfiguration.getReportTitle()`, default `"JMeter Web Insight Report"`),
  so `wir-collect --test-name` defaults to that literal and must match `-Jwebinsight.report.title`
  when one is set. Stamped into the payload; M4 stamps it into
  `serverGates[]`; M6 keys drafts on `runId + collectionId`.
- **Generator merge.** `WebInsightReportGenerator` step 1b: `ServerMetricsPayloadLoader.read(file,
  runId)` → `Result{payload, problem}`, same contract as `AnnotationLoader` (missing configured
  file / unparseable / wrong `schema` / unknown series status → a problem recorded by
  `ReportArtifacts.recordServerMetricsProblem`, which is a config error). A payload whose `runId`
  ≠ this run's is **refused**: problem recorded, payload not embedded — a foreign payload drawn
  under this run's headings is the bug `RunIdentity` exists to stop, and a banner over withheld
  charts is the honest rendering (M5). The accepted payload enters the chart-data map as
  `chartData.serverMetrics` through `ServerMetricsPayloadWriter.addTo` (counted by the
  external-data threshold; present in inline, external and streamed outputs alike because all
  three go through that map) with a per-instance point cap of 4 000 (thinned, first and last
  kept) and a per-series instance cap of 20, both logged when they bite and both stated in the
  data. Downsampling to the bucket ladder is M5's concern when the charts are drawn.
- **Not in M3**: judging (M4), the Server tab (M5), `environments` selection by `-J` beyond
  `--env`/`defaultEnv` (M3 reads both, stamps the chosen one into the payload).

### M4 — Evaluation and outputs

`ServerKpiEvaluator`: window (D9), coverage floor, reducers, `over`, `across`, `requiresLoad` (D6),
`divideBy`; per-gate `GateSummary`; `ServerGateEvaluation` beside `GateEvaluation`, read by the
payload, the CI/CD JSON (`serverGates[]`, D8), the JUnit writer (suite per gate) and the exit fold
(non-zero iff any `failsBuild` gate FAILED under `failonassert`; config errors under `failonerror`
per D3/D4). `assertsDeclared`/`assertsEvaluated` redefined. `server-parity-vectors.json` +
`ServerParityTest` (D21). `CiCdJsonSerializerTest`'s `sla` sweep extended to a call that carries a
server gate. Goldens gain **G4**: gate-mixed + a `file` source payload.

Gate: `mvn test`, `run-goldens.sh` (G4 accepted), `run-config-tests.sh` with new C-stages for the
fold (a `failsBuild:false` FAIL exits 0; a `failsBuild:true` FAIL exits 1 under `failonassert`; a
`SOURCE_FAILED` on a building gate exits 1 under `failonerror` and 0 on an observe gate).

**Concretely** (decided 2026-08-22 after reading `ExitPolicy`, `NonZeroExit` and both paths'
`failOnAssertIfAsked`):

- **No payload is not an error.** KPIs declared and no `-Jwebinsight.report.servermetrics` given
  is exactly step 1 of the choreography (D11): the server gates are **not evaluated**, `serverGates`
  is omitted, and one log line says "N server KPIs declared, no payload given — run wir-collect and
  regenerate with -Jwebinsight.report.servermetrics". Making it `SOURCE_FAILED` would exit 1 on
  every interim report by default. A payload that is *configured* and unusable is the config
  problem M3 already records.
- **`ServerKpiEvaluator`** (core), one KPI at a time, in this order — the client evaluator's order
  with the collection step added: `enabled: false` → DISABLED; `KpiUsability.refusal` →
  INVALID_RULE; `requiresLoad` (KPI's, else the run-level default): names no client rule or a rule
  whose metric is not `throughput` → INVALID_RULE; that rule FAIL → LOAD_NOT_ACHIEVED (detail:
  actual vs limit); that rule NOT_JUDGED → the same reason, detail naming the rule; then the series
  by `(source, query-as-written)` — absent → `NOT_REQUESTED` → INVALID_RULE ("collected against an
  older file; re-collect"); `CollectionStatus.toReason()` non-null → that reason (detail: the
  error, or the coverage figure); then the number, then `fail` before `warn` via `Predicate.breaches`,
  exactly as the client.
- **The number.** Window = payload's, minus `[start, rampUpEnd]` when `collection.excludeRampUp`
  (default true) and the run declared a ramp-up. Per instance over the points in the window:
  `max|min|avg|last` as named; `pN` nearest-rank; `slope` least-squares in units **per minute**.
  Then `across`: `max` or `avg` of the per-instance numbers. `over` is allowed on `max` and `min`
  only (elsewhere a new `KpiUsability` refusal, BAD_TOLERANCE): `sustainedFor d` on `max` is the
  highest level held for at least `d` — `max over t of min(v on [t, t+d])` — and on `min` the
  mirror; `breachBudget b` on `max` is the `(1−b)` quantile of the points (the level exceeded at
  most `b` of the time), on `min` the `b` quantile. A `d` no longer than one step is the plain
  reducer. `divideBy`: the reduced number divided by the named load rule's measured value (its
  `worstJudged().actual()`); the rule missing or NOT_JUDGED → INVALID_RULE / inherited, as above.
  All of this is what `server-parity-vectors.json` pins, vector first.
- **`ServerGateEvaluation`** (report/gate), built at step 4b beside `GateEvaluation`: maps the
  block, evaluates every KPI, groups `KpiResult`s by `kpi.gate()` into one `GateSummary.of` per
  declared gate (every declared gate gets an entry, empty ones included, so a gate whose KPIs all
  vanished reads NOT_EVALUATED rather than disappearing), and carries the null-gate KPIs and the
  block-level problems as `configErrors` of the block. It is read by the payload
  (`serverGates[]`, `serverKpis[]`, `serverFindings[]` — M5's inputs), `CiCdJsonSerializer`
  (`serverGates[i] = {id, label, failsBuild, collectionId, …gateBlock}` plus `serverKpis[]` in
  declaration order with `id, gate, source, query, unit, status, notJudged, reason, actual,
  coverage, instances, warn, fail`), `JunitXmlWriter` (one `<testsuite name="<testName> - <gateId>">`
  per gate, `classname="server-metrics"`, `<failure>`/`<skipped>` by `NotJudged.isConfigError()`
  exactly as the client suite; root counts unchanged) and `ReportArtifacts`.
- **The fold** lives in `ReportArtifacts`, not in the two paths: `recordServerGates(evaluation)`
  makes `hasConfigProblem()` true when any **building** gate has `configErrors() > 0` or the block
  has any, and adds `serverFailsBuild()` — any building gate whose `status()` is FAIL. Both
  `failOnAssertIfAsked` implementations test `gate().status() == FAIL || serverFailsBuild()`; the
  `CONFIG ERROR` and `ASSERT FAIL` markers name the gate. An observe-only gate contributes to
  neither, whatever it says (D4). `assertsEvaluated` = client gate ran **or** any server gate was
  evaluated; `assertsDeclared` (the header) = either declared. The console verdict line gains one
  clause per server gate: `Server kafka: FAIL — 3 declared, 2 passed, 1 failed`.
- **Fixtures.** G4 = `gate-mixed.json` + a `serverMetrics` block over a committed
  `test-plans/goldens/server-metrics/` recording and a committed `server-metrics.json` collected
  against `plain.jtl` under the default title (its `runId` is a constant, so the golden is). C36/C37
  pin the fold through a real process. The parity file, `ServerParityTest`, and the guard that every
  server-only `NotJudged` reason has a vector.

**As built** (2026-08-22, commits `20da9de` + `c81101e`; the "Concretely" above was written
before the code, and where the two disagree the code is described here):

- **The evaluator's order gained one step and moved another.** `divideBy` is resolved beside
  `requiresLoad`, *before* the series is looked up, not beside the division it feeds. Written the
  other way it made a typo'd rule id fatal on a run that carried data and invisible on one that
  did not — a defect in the file whose reporting depended on the weather.
- **`divideBy` refuses a multi-match rule.** D9 defines the divisor as the rule's measured value
  over its own target, and a rule matching many samplers has many of those; `worstJudged()` would
  pick the first when the rule passed and the worst when it failed. `INVALID_RULE` naming the
  count instead.
- **`sustainedFor` is point semantics, consistently.** A window counts when its data spans `d` —
  last point at `t + d` or later — matching the closed interval the value loop already used. The
  first cut allowed the span a free `step`, which only ever mattered at the tail of a series, so
  a two-sample spike at the end reported as "held for 30s" where the same spike mid-series did
  not. Tightening it made `sustainedFor` unanswerable when `d` is not a whole number of steps, so
  that is now **`INVALID_RULE` naming the step** rather than NO_VALUE: at 15s resolution
  `sustainedFor: "20s"` can never be true whatever the run did, and a rule that decides nothing
  while exiting 0 is the shape this project keeps closing.
- **`findByQuery` falls back to the resolved query.** The collector dedups by resolved text and
  stores only the first KPI's raw text, so two KPIs whose different raw queries resolve alike
  shared one record and the second was permanently `NOT_REQUESTED`.
- **The coverage floor stays a collect-time decision** (M3's, `Collector.statusFor`). The
  evaluator no longer prints a floor it did not apply: when the file's current floor no longer
  explains the payload's status the sentence says so and asks for a re-collect, instead of
  rendering "coverage 40% is below the floor 30%". **Left open:** coverage is measured over the
  payload's whole window while the verdict is taken over the judged window, so a run with a long
  ramp-up and a source that died late can report 90% coverage over a window that carries half
  that. Recomputing it at judge time is a D9/M3 decision, not an M4 fix — carried to M5.
- **`KpiUsability.loaderReported()` is per instance, not per `Reason`.** `BAD_TOLERANCE` is
  raised from both the mapper (an `over` it could not read) and `check()` (one it read and the
  reducer does not accept); deciding by reason alone silenced the second entirely.
- **The fold is two questions, not one.** `fatalConfigErrors()` is what `failonerror` acts on;
  `reportableConfigErrors()` is what the `CONFIG ERROR` marker prints. D4's "the marker always
  prints" had not been built, so an observe gate whose source died was indistinguishable on the
  console from one that passed. Both ingestion paths print independently now — the listener had
  kept the `if/else` chain the exporter dropped in M3.
- **Orphans are counted by reason**, through `GateSummary.of(orphans).configErrors()`, so a
  parked orphan is not a fatal config error; they also get their own JUnit suite,
  `<testName> - (no gate)`, because they are fatal and were reaching no suite at all — the XML
  read green while the process exited 1. `assertions` and `(no gate)` are reserved gate ids.
- **Without a payload, an unrunnable KPI still counts.** Step 1 and step 3 of the choreography
  must agree about the file. Source faults are excluded, being counted once already at block
  level.
- **The evaluator's own `INVALID_RULE`s reach `findings[]`**, deduped by KPI id and sentence
  against what the loader said — three shapes are decided against the run and not against the
  KPI, and the client side would have reported all three.
- **The header badge has three states**, not two: a server-only run renders `SERVER ASSERTS ONLY`
  rather than `ASSERTS NOT EVALUATED` over its own `assertsEvaluated: true`. The badge withholds
  the verdict; M5's chip (D17) is what carries it.
- **`NonZeroExit.arrange` registers one shutdown hook per JVM**, however many reasons it is
  given, now that two branches can both fire.
- **Not applied, deliberately:** the em-dash/ASCII split between `KpiUsability`'s sentences and
  the evaluator's. Both reach one wire field, so the finding is real, but the client side uses em
  dashes throughout and the Windows console mangles them there too — it is a repo-wide question
  about one convention, not M4's to settle in one direction for one class.

### M5 — Server tab (read-only), header chip, fixture

Tab 8 after Asserts; toolbar with one badge per gate, counts, source/gate/status filters; KPI board
(worst first, reason phrases, "window used", coverage); overlay charts with `markArea` breaches and
"and N more" instances; collection status panel (per source: status, step, `clockOffsetMs`, redacted
URL); header server chip (D17, new Thymeleaf context variables). XSS guards widened (D20). Fixture
**E26** ("Server metrics") with a `file` source, wired into `run_test`, `FIXTURE_IDS`,
`REQUIRED_FIXTURES`; the recording directory added to the fixture stamp. `tab-navigation.spec.ts`
updated; new `server-tab.spec.ts`.

Gate: `run-regression.sh` + `server-tab.spec.ts` + an explore-report session pinned to specs.

### M6 — Browser mirror, document assembler, drafts

`WIR.serverKpis` evaluator mirroring M4 over the payload series (same vectors: `server-parity.test.js`,
`server-parity.spec.ts`); `WIR.document` (D16) with `WIR.asserts` and `WIR.serverKpis` registered as
slices, both Save buttons and `loadDocumentText` routed through it; server drafts keyed
`runId+collectionId`; the unsaved pill reads the assembler; round-trip test on an unedited file
with both blocks.

Gate: `run-regression.sh` + `server-drafts.spec.ts` + an end-to-end "download → `jmeter -g` with the
same payload → same verdicts".

### M7 — Authoring

`+ Add KPI` slide-over (source, query, unit, reducer with kind-dependent sub-form, across, bands,
gate, requiresLoad); **Validate query** (D22); "needs re-collection" state that withholds the verdict
when source/query changed; Generate from sources (pack-default band seeded from the observed value
for every collected series with no KPI); Add from pack (D15, `pack:` stamp, per-source query
variants); `+ Assert from chart` and drag-to-retune on server overlay charts.

Gate: `run-regression.sh` + `server-editor.spec.ts` + `server-seeding.spec.ts`; `xss-escaping`
vectors for a query containing `</script>`, quotes and `${`.

### M5–M7 — As built

Written 2026-10-02, from the commit messages (`ab3382a`, `b5122a5`, `fe13431`, `0b271a1`,
`04ffef3`) and the plan review; until then these decisions lived only in commit messages. The
progress table used to point at an "M7 As built" section that did not exist.

- **M5.** The Server tab ships *visible*: Compare and Asserts hide until JS reveals them, and the
  Asserts tab once vanished on a payload failure. The status vocabulary moved out of `WIR.asserts` to
  **`WIR.status`**, and `jsArg` moved to top level beside `escapeHtml` (one vocabulary, one escaper).
  The header chip is computed in **`ServerGateEvaluation.headerChip()`**, not in Thymeleaf. **E26** is
  built entirely from committed inputs under `test-plans/server-metrics/` (a live runId refuses a
  committed payload, and a committed recording shares no milliseconds with a live window), and that
  directory is in the fixture stamp. `refreshAllCharts` gained a server branch; it used to dispose
  every chart and rebuild a hardcoded list. The xss guard got a second, E26-loading sweep rather than
  a widened selector, because E25 has no server block. **Gate item not met:** no explore-report session
  (`EXPLORATORY-LOG.md` was not touched on the branch).
- **M6.** The mirror judges uncapped series. A capped series keeps Java's **BAKED** verdict, because
  thinned points cannot be re-judged honestly. `WIR.document` registers two slices and owns
  Save/import/the pill. **Not built:** D16's *Discard per slice* (one Discard, on the Asserts tab,
  drops every slice) and any Save/Discard/pill on the Server tab itself. **Gate item not met:** the
  download → `jmeter -g` → same-verdicts test. The "end-to-end twin" that exists re-judges only the
  unedited file. **Defect:** drafts are restored inside `WIR.asserts.init`, before
  `WIR.serverKpis.init`, so server drafts never survive a reload (M7c).
- **M7.** Editor, Validate query (D22) and drag-to-retune as specified. The staleness rule changed in
  M7b from "declared before and query changed" to **`nothingCollectedFor()`**: the question is
  whether the payload holds a series for what the assert asks *now*, not what the edit history was.
  Generate from sources seeds `max` with a fail band at 1.5 × the observed maximum, and every seeded
  assert says in its description that it passes on its own run by construction. The chart draws
  from the *declared* assert, so a handle drags the number the verdict is taken against.
  **Cut:** Add from pack (now v2, D26) and **"+ Assert from chart" on server overlay charts**, which
  the M7b commit did not list. Recorded here for the first time.

### M7c — Review of M5–M7 and the fix pass

A 3-lens review of M5–M7 (authoring and document integrity; judging consistency across Java, the
mirror, the chip and the JSON; rendering, security and test quality), each lens with an adversarial
verifier, as M2–M4 had. Its findings, plus the verified defects of the 2026-10-02 plan review, are
fixed here. Each fix ships with the test that would have caught it.

Fix list (IDs are the plan review's, in `tmp/plan-review-2026-10-02-findings-index.txt`; `R*` are
the M5–M7 review's, added when it lands):

- **C-1** Save drops an unreadable band, turning an `INVALID_RULE` into an assert that can never FAIL.
  Preserve the declared band verbatim when the parsed one is null; pin it with a node unit test and a
  round-trip vector.
- **L3-1 / C-2** Draft restore and the baseline run before the server slice exists. Restore and
  baseline after *every* slice has initialised; pin it with a reload spec on E26.
- **L9-2** `publishable()` masks only `auth`. Strip userinfo from every source URL and mask refused
  source-level secret keys in the published document; extend the canaries to `user:pw@` and `"token"`.
  (Review follow-up: also `?api_key=` parameters and credential URLs held in `environments.*.vars`.)
- **A1-5** Raw NUL bytes in `report.js` (and `Collector.java`) blind the grep guards. Use the `\u0000`
  escape, and add a static check that refuses NUL bytes in tracked text files.
- **A1-1 / M8-01** The documented jq idiom fails on client-only and server-only runs. Replace it
  everywhere (javadoc included) with one that holds on all four goldens, and pin it with a test that
  evaluates it on G1–G4.
- **M8-02 / L9-9** `wir-collect` must be runnable from a release: a self-contained `wir-collect.sh`
  (no `common.sh`), a `.bat` wrapper, and the plain `java -cp` invocation documented.
- **C-3 / L3-4** The browser parser diverges from `ServerMetricsMapper` on about five shapes. Make the
  mirror refuse what the mapper refuses, and add a parity vector for each shape.
- **C-4 / L3-5** An edited assert on a capped series shows the old baked verdict beside the new
  threshold. Withhold the verdict instead (D22's rule).
- **C-5 / L3-3** The mirror judges against the loaded client rules, not the Asserts-tab drafts. Read
  the live client draft.
- **C-6, C-7** Chart container ids collide for added, seeded or renamed asserts, and slope and
  `divideBy` charts draw bands, shading and handles in the wrong units.
- **L9-4 / L3-12** A refused payload is described as "no payload was given". Say why it was refused.
- **A1-3** A typo in `requiresLoad`/`divideBy` is silent on the interim report and fatal at step 3.
  Check it without a payload too.
- **A1-2** `LOAD_NOT_ACHIEVED` on a load rule that was never *measured* reads "missed its load
  target: … matched nothing". Word it for that case.
- **L9-5** Ramp-up from `wir-collect --rampup` is never reconciled with the report's; a ramp-up
  longer than the run is accepted.
- **C-8** Generate from sources seeds a FAIL on a negative series and duplicate ids for one query on
  two sources.
- **C-9, C-13, L9-12** The interim tab says "0 declared"; a breach budget renders as
  `7.000000000000001%`; the instance cap keeps the first 20 instances, not the worst.
- **M6 gate:** a scenario that downloads the edited document, runs `jmeter -g` with the same payload,
  and asserts the CI/CD JSON verdicts equal the page's.
- **M5 gate:** an explore-report session on E26, pinned to specs or logged in `EXPLORATORY-LOG.md`.

**The M5–M7 review** (2026-10-02, workflow `wf_21264a36-aaf`: 3 lenses, 37 findings, none refuted;
index in `tmp/m5-m7-review-index.txt`) adds:
- **Two highs:**
  - R1-1/R3-1: the server-assert editor cannot be typed into. Every keystroke rebuilds the panel,
    and every spec drove it through `page.evaluate(set(...))`.
  - R1-2/R2-2/R3-8: id-less and duplicate-id asserts collide in the browser. Java derives
    `slug(source)-slug(query)`. As a result, Edit overwrites another assert, Delete removes all of
    them, and Save drops their `pack` and unknown keys.
- **Mediums:**
  - Client-rule ids are derived differently in the two places (R1-3/R2-3).
  - The mirror rounds throughput to 2 places (R2-1).
  - `ServerParityTest` parses vectors with a hand-written helper rather than the mapper, so a vector
    pins the helper (R2-5). *(Done in M7c group A, `8984d45`: ServerParityTest is mapper-driven in
    the report module, with the guard that INVALID_RULE reaches an ERROR finding.)*
  - The draft record lacks the server block's sources and gates (R1-12), and the banner counts one
    slice only (R1-4/R3-6).
  - The editor saves shapes Java refuses (R1-5).
  - Opening a second chart blanks the first, and every commit leaks an ECharts instance (R3-2).
  - The y-axis ignores the bands (R3-3).
  - Gate badges stay baked while the rows beneath them are live (R3-5).
  - A retired key survives every save (R1-11).
- **About 20 lows.**

The fixes are split into six work groups, whose briefs are in `tmp/m7c-group-{A..F}.md`, plus
`tmp/m7c-group-M7D.md` for M7d.

Left open by group E, by decision:
- **The client rule-line charts still scale their y-axis to the data** (R3-3's client half). On
  Summary and Timeline, a passing client rule's line and its drag handle can sit above the canvas,
  so the threshold can be dragged only once it has been breached. The fix is the Server tab's:
  widen the axis with `bandAxisBounds()` over the rule values drawn on that chart. It was left out
  because those are `WIR.asserts`' charts, not the Server tab's. Follow-up item, not v2. *(Done in
  M7d X: `ruleAxisMax` / `widenForRuleLines`, pinned in `asserts-charts.spec.ts`.)*
- **The mirror still judges once at page load, even if the Server tab is never opened** (R3-13's
  "skip until first shown"). With the one-pass `sustained()` and one evaluate per render, that
  costs a single evaluate, and deferring it would add a "not judged yet" state to everything that
  reads the rows. Not done; reopen it if a soak run's load time says otherwise.

Gate: `mvn test`, js-unit, `run-regression.sh` 6/6, `run-config-tests.sh` all green; goldens
re-accepted only with the change that moved them.

**As built (2026-10-02).** Workflow `wf_f3b51549-617`: six groups, each implemented and committed,
then adversarially reviewed, then repaired when the review found a medium or high. Reviews of A, B
and E asked for repairs; C, D and F passed with lows only.

| Group | Commits | What changed |
|---|---|---|
| A — Java + collector | `8984d45`, `e73afb3` | every refused credential shape masked (incl. `?api_key=` and `vars`); load references checked without a payload; a refused payload says why on the page; `--rampup` reconciled; NUL check; parity runs through the real mapper; deciding instances kept under the cap |
| B — document | `88d5a31`, `48ef000` | drafts restore after both slices; the record carries the document and is dropped on a new collection or a changed shipped file; one count everywhere; Apply is all-or-nothing; booleans read as Jackson reads them; nothing unreadable is repaired on save |
| C — identity + parity | `c401fac` | one assert is one row whatever its id (stable row key); the mirror refuses what the mapper refuses; full-precision throughput; capped series withheld (NOT RE-JUDGED) |
| D — editor | `78980b2` | typeable editor; Save refuses what the run refuses; authoring before collection (D23); Generate from sources validated and seeded from the true maximum |
| E — charts | `d284ac3`, `5fba51f` | charts in the verdict's units; no blanking or leaks; bands and handles on the plot; gaps as gaps; palette; live gate badges ("if saved") |
| F — end-to-end | `25758ef` | **C38**: the page's saved document re-judged by `jmeter -g`, compared field by field. First run: the mirror wrote a load-rule number in the reader's locale (`100,000`) where Java writes `100000` — fixed, with vectors |

What M7c left open is collected in `tmp/m7d-group-X.md` and is M7d's group X — chiefly the
**source** usability checks in the mirror (unknown type, literal secret, bad step, `clockOffsetMs`,
file path/auth, URL userinfo), which Java still refuses at generation, so nothing is misjudged; the
page just does not warn while editing.

### M7d — Checkpoint decisions

`serverStatus` (D24) in the CI/CD JSON, plus the chip fold; the "server asserts" rename (D25) across
the mapper, the serializer, the JUnit writer, `report.js` strings, fixtures (`e26-server.json`,
`gate-server.json`, the parity vectors' KPI JSON), specs and goldens, with a `kpi` key sweep in
`CiCdJsonSerializerTest`; the `packs` message (D26); the collector's exit code (D27, C37 updated);
JUnit `<skipped>` for observe-only gates (D29, C36 updated).

Four sequential groups, each implemented, adversarially reviewed and repaired as M7c's were:

- **R — rename.** 3.1.14. D25 everywhere, a leftover `kpis` key refused naming `asserts`, the `kpi`
  key sweep; D26's `packs` message.
- **S — statuses and the wire.** D24 `serverStatus` and the chip fold; the chip links to the tab
  (L3-6); `reduce` emitted as the structured grammar, not prose (A1-12); a `(no gate)` entry in
  `serverGates[]` for asserts that name no gate, mirroring JUnit (A1-6); D29's `<skipped>`, with
  `INVALID_RULE` staying a `<failure>` because it is fatal whatever `failsBuild` says.
  - **A1-7, decided lenient (D27's spirit):** a missing or refused payload is a configuration error
    only when at least one declared gate builds; with every gate observe-only it is a WARNING.
  - **The jq idiom (A1-1/M8-01).** One null-safe line, kept in one file that the docs copy, and
    evaluated over G1–G4 by node and by `jq` where it exists (`command -v jq || return 0`, as
    `verify_shaded_jar` does with `jar`). Product call: a run with no client gate passes the client
    half (`(.gate.failed // 0) == 0`); the server half fails only on `serverStatus == "FAIL"`.
    *Corrected 2026-10-03 (server-tab-followups SF7):* this used to say a building gate that judged
    nothing "already exits 1 through `failonerror`". Only some do — INVALID_RULE, SOURCE_FAILED,
    LOW_COVERAGE and a refused payload an enabled assert needed. NO_MATCH, NO_VALUE, DISABLED,
    LOAD_NOT_ACHIEVED and a component with no asserts exit 0, and neither jq line refuses them.
  - *Review follow-up (S):* G1–G4 could not see the server half (G4's client gate FAILs), so G5–G8
    were added — server-only runs where one clause decides — and `selftest_jq_idioms` now breaks
    every clause in turn and demands a refusal. STRICT also refuses any ERROR finding, and a refused
    payload a building gate needs is now one (`serverMetrics.payload`), so the fatal refusal reaches
    the JSON. A refused payload is fatal only when an *enabled assert* sits in a building gate.
  - **Kept as D24 states it (orchestrator call, 2026-10-02; review of S, low):** a building gate
    whose judged asserts PASS while another of its asserts is SOURCE_FAILED/LOW_COVERAGE reads PASS —
    and so do `serverStatus` and the chip — on a run that exits 1 under `failonerror` (G7 pins exactly
    this shape). D24 ranks only "a building gate that judged nothing" as NOT_EVALUATED, and the client
    side is the same by design (a broken rule contributes nothing to `gate.status`); changing only the
    server side would make the two statuses mean different things. Tutorial 13 must say it: the
    status is the verdict, a configuration or collection error is the exit code, and STRICT is the jq
    line that refuses both. Reopen if Aaron wants the server word to carry config errors.
- **W — the collector.** D27's exit code (C37 updated); `wir-collect.sh` self-contained (no
  `common.sh`) with a thin `wir-collect.bat` (L9-9/M8-02).
  - *As built (3.1.17):*
    - `WirCollect` returns 0 once the payload is written, 1 otherwise; there is no 2. The summary
      line still counts "N not judgeable".
    - `--help` (also `-h`) prints the options, the exit codes and the plain `java -cp` line for
      Linux/macOS and for `cmd.exe`. It names the JAR the class was loaded from.
    - `wir-collect.sh` resolves `JMETER_HOME` from the variable, then from `jmeter` on PATH
      (symlinks followed, Homebrew `libexec` probed), then from a checkout's `apache-jmeter-*`
      beside the script's parent. That last probe matches nothing outside this repository; it
      keeps the READMEs' `./scripts/wir-collect.sh` working on a box whose JMeter is project-local.
      A Windows-form `JMETER_HOME` is converted, and the classpath is `<jar>` plus the JVM wildcard
      `lib/*`, with `;` under Git Bash, MSYS2 and Cygwin.
    - C37 now also runs the three steps as one `set -e` script, with `jmeter` on PATH,
      `JMETER_HOME` unset and a lone copy of the script, for an observe-only gate with a dead
      source. It must reach step 3's report and exit 0.
    - Not done, for M8 or M9: the release still attaches only the JAR. The scripts reach a release
      user through the source archive or the `--help` line; attaching them is `_perf_release`'s
      call.
- **X — the M7c residue** (`tmp/m7d-group-X.md`): source usability in the mirror, the number
  formatting of the mirror's sentences, the client-rule target reader, and the review lows worth
  fixing.
  - *As built (3.1.18):*
    - The mirror reads a source as `ServerMetricsMapper.readSource` does, check for check and in its
      words — including a port of `java.net.URI`'s parser, so `${env.U}@host` (throws, so refused by
      its `@`) lands where Java lands it. *(As first built it also pinned `https://a@b@host` as
      usable — "registry-based, no userinfo" — which was Java's security gap reproduced, not parity;
      see the review follow-up below.)* An assert on a refused source is INVALID on the
      board, the editor refuses it and marks the source, and the findings pane answers for the
      sources the page holds. The parity vectors gained per-case `sources` (all three runners
      declare the same top-level `sources` now), an exact `detail`, document-level `warnings` and
      block-level `sourceProblems`.
    - Numbers: `javaDouble` / `trimNumber` / `jsonNumberText` write Double.toString, Predicate.trim
      and the mapper's `describe` respectively. **Product call:** Java's `describe` now quotes a file
      number by its VALUE (an integral one below 1e15 as a long, else Double.toString) — `2.0` reads
      "the number 2" — because JSON.parse cannot tell `2.0` from `2` and the review's own example
      could not be fixed on the page side. Goldens did not move.
    - `ruleFromJson` reads a target as `AssertionRuleMapper.readTarget` does (`targetCases` vectors,
      run by MapperParityTest, node and the browser); the asserts-pure line that pinned `group-abc`
      for `samplers: []` now says `assertions[0]`.
    - A capped assert's baked verdict is withheld when the client rule it names (requiresLoad,
      divideBy, or the block's requiresLoad) was edited — `WIR.asserts.ruleEdited`.
    - Two server asserts judged under one id: a WARNING finding (lenient — refuses nothing, exits
      0), mirrored live on the Server tab.
    - Lows: Generate from sources says why it is unavailable when the run-level requiresLoad
      failed; option labels are null-prototype; the interim report claims no header verdict; a
      retired-key-only block points at no button it does not draw; `loadedEntryFor` derives the
      loaded ids once per document and pairs shared ids by occurrence; the client evaluator reads an
      `exact` block (what round2 changed) laid over the 2dp figures (R2-1's client half, and the
      off-grid percentiles in full); C38 gained a seeded case and a drag whose rounding crosses the
      measured value; the judged-window spec pins literal timestamps in two zones; the editor's
      too-long-window warning quotes the window to the second, and a drag's toast shows every
      warning it raised; `verify_report_js_rules` rule 4 refuses every event attribute but onclick
      in the server module (onkeyup and onblur passed it).
    - R3-3's client half: the four rule-line charts widen their value axis to the rule lines
      (and give the room back when the lines go).
    - Review:R follow-ups: 15 more vectors compare whole every sentence the D25 rename touched
      (no query, no bands, an unreadable operator, `outside`'s hint, a series never collected) and
      every remaining retired per-assert key; the "no reader is shown KPI" sweep now visits the
      editor's refusals, stale and withheld rows, the retired-key editor, a dropped block and a
      refused payload.
    - Not done here (review:W's lows, appended to the brief after X started): the `.bat`/WSL bash
      note, C35's recording assertion, the checkout JMETER_HOME order, `--help` in option-value
      position, a missing `java` exiting 127, plan M3/D11 wording, WirCollect's non-ASCII finding
      lines. **Owner: M8** (item 8 below lists them), since each is either documentation or a
      one-line collector change best made beside the `wir-collect` docs.
  - *Review follow-up (3.1.19):*
    - **Security (was open, pre-existing, not introduced by X):** a password holding an `@`
      (`https://bob:p@ssw0rd@prom.corp`) passed `readSource` — the second `@` makes `java.net.URI`
      read the authority as registry-based, so `getUserInfo()` is null — and `Redaction.url`
      returned it whole into the payload's `resolvedUrl` and every report embedding it; only the
      published document copy was masked. Now `readSource` refuses any `@` in the authority, read
      textually (`ServerMetricsJson.authorityHasUserInfo`, the span `publishable()` already masks),
      and `Redaction.url` strips up to the authority's LAST `@` on every path (its textual fallback
      cut at the first, leaving `ssw0rd@`). The mirror refuses by the same span. `java.net.URI`
      throwing `NumberFormatException` for an IPv4 byte past an int inside an IPv6 literal no longer
      escapes `readSource` (it failed the whole annotations load); the mirror walks
      `parseIPv6Reference` instead of checking the literal's characters.
    - Numbers, the rest of the class: a non-text `reduce.kind` is quoted by value (a number) or
      named (a list, an object, a boolean) on both sides, never `String.valueOf`/`String()`; a
      NUMBER in a text field (`source`, `gate`, `type`, a target's `scope`…) is read by its value on
      both sides — **product call:** `AnnotationLoader.newObjectMapper()` binds it through
      `numberText`, so `"source": 2.0` is the source `"2"` (Jackson kept the literal `"2.0"`, which
      the page, reading through JSON.parse, cannot reproduce). The client's unreachable-band
      refusal prints its limit as `Predicate.describe` does (`< -1234.5 ms`, not the table's
      `< -1,234 ms`), with new `detailCases` vectors on all three runners. `WIR.javaText` is the one
      copy of the Java formatters both tabs use; `Double.MIN_VALUE` reads `4.9E-324`.
    - Lows: the findings pane answers the per-assert "source 'x' cannot be used" findings live too;
      `verify_report_js_rules` rules 1 and 4 are case-insensitive about the attribute name; the
      write-back test pins the cached entries by identity, not by a wall clock.
    - Still not done: integer-like source ids (`"1"`) are listed by `Object.keys` before the others,
      where Java's `LinkedHashMap` keeps the file's order — so "declared sources: …" and the
      findings' order can differ for such a file. Fixing it needs an order-preserving parse of the
      document in the page; recorded, not scheduled.

Gate: as M7c, plus R06 re-accepted in the same commit (G4 moves: `serverStatus`, `serverAsserts`,
observe-gate JUnit).

### M8 — Documentation

*Rewritten 2026-10-02.* The original list was partly done already: M1 brought the reason vocabulary
into FEATURES.md, Tutorials 10 and 11 and UPGRADING-ASSERTS; `c81101e` documented C35–C37; and
`ab3382a` fixed CLAUDE.md's tab count. Part of it was wrong: its jq line was a no-op, and
"FEATURES.md E-numbers" assumed numbering that file does not have. And it missed the documents a
user actually needs. In order of what a user needs:

1. **`WIKI/Tutorial/13-Server-Tab.md`** (new), led by D23's model: you write server asserts
   (expected), `wir-collect` fetches the metrics (actual), and the report compares them.
   - The three steps, as a copy-pasteable Jenkins/shell recipe.
   - Reading the tab: gates, the chip, `serverStatus`, reasons, "window used", and coverage "of the
     collected window" (D28).
   - The editor, Validate query, Generate from sources, drag-to-retune, Save/drafts.
   - Asserts for common components: example node and JVM queries, with no recommended thresholds
     (D26).
   - Advanced: `requiresLoad`, `divideBy`, named gates and `failsBuild`.
   - Limits: post-run only; NTP assumed (D13); `step` ≥ scrape interval; slope is per **minute**.
2. **A reference for the `serverMetrics` block and the `server-metrics.json` payload.** Sources and
   auth by reference only; environments and interpolation; `asserts` with
   reduce/over/across/bands/unit/gate/enabled; `gates`; `excludeRampUp`; and the retired and refused
   keys with what to write instead. It lives in Tutorial 13's appendix, or in
   `WIKI/SERVER-ASSERTS-REFERENCE.md` if it outgrows that.
3. **`wir-collect`**: arguments, exit codes (D27), running it from a release (the `java -cp` line and
   the wrappers), and why `--test-name` must match `-Jwebinsight.report.title`.
4. **`11-Export-Integration.md`**: `serverStatus`, `serverGates[]`, `serverAsserts[]`, the JUnit
   suite names and observe-gate `<skipped>` (D29), and the DEFAULT and STRICT jq lines (M7d),
   copied from `test-plans/goldens/jq-gate.txt` — the one copy, run over the goldens by
   `verify_jq_idioms` — with the comment above them that says what each one refuses.
5. **The statements the server work made false** (plan review M8-05):
   - testsuite counts, exit-code tables, `failonassert`, badge states and Saving/Drafts in README,
     FEATURES, and Tutorials 07, 10, 11 and 12
   - `_sidebar.md`, `01-Overview.md`, the docsify home table
   - README properties (`webinsight.report.servermetrics`)
   - `Instructions.md`: E26, plus a golden-harness (R06) section, which does not exist yet
6. **CLAUDE.md**: `core/servermetrics/`, the collector, `WIR.document`, `WIR.serverKpis` vs the user
   word, `serverStatus`, and the fact that the Server tab ships visible.
7. **Screenshots and `reportExample/`**, refreshed. `reportExample/` has had no Server tab since M3.
8. **review:W's lows** (M7d, assigned here): tell Windows release users to use the cmd.exe
   `java -cp` line from `--help` (or run the `.bat` only where Git's bash precedes System32) and
   drop "WSL" from the `.bat` comment; add `assert_contains "$C35_DIR/collect.log" "wir-collect: OK
   rec up"` to C35 and reword the "exited 2" remark in `common.sh`; say in the READMEs to set
   `JMETER_HOME` when regenerating committed payloads (or probe the checkout first only inside one);
   recognise `--help`/`-h` only in option position; `die` with a clear message when `java` is
   missing instead of exiting 127; annotate the plan's M3 "Concretely" exit-2 text and add the
   annotations property to D11's step 3; and make WirCollect's finding lines ASCII (or say they are
   not).
   **Code half ✅ done** — M7e (3.1.20, `34110e9`): `--help`/`-h` only in option position, a
   missing `java` dies with a sentence, every collector console line ASCII, C35's `assert_contains
   ... "wir-collect: OK rec up"`, the `common.sh` "exited 2" remark, the `.bat` WSL comment, the M3
   exit-2 annotation and D11's step 3. M5 calls (3.1.22): `--help` lists `-h`, and
   `wir-collect.sh` takes `JRE_HOME` after `JAVA_HOME`, as `bin/jmeter` does. **Documentation half ✅ done** — M8
   (3.1.22, `bb18830`): the Windows `cmd.exe` `java -cp` line and the `.bat` caveat in README and
   Tutorial 13, and the `JMETER_HOME` note in both payload READMEs. See §M8 "As built".

Dropped from the original list as low value: the phase-3 retraction line and the canvas file fixes.
`plans/` is a scratch folder (CLAUDE.md, "Plans").

Gate: `run-sanity.sh`, and a link check over `WIKI/` and `README.md` written as a one-off node
script. Nothing in the repo checks links, so "no dead links" needs something that actually checks
them.

**As built (3.1.22).** Items 1–7 written by four doc writers in parallel, then one gate pass:

- **Tutorial 13** is new and leads with D23's expected-against-actual model; the `serverMetrics`
  and payload reference (item 2) and `wir-collect` (item 3) live in its appendix, so there is no
  separate `SERVER-ASSERTS-REFERENCE.md`. It carries seven screenshots (`images/60`–`66`, from E26),
  captured by a new E26 section in `take-screenshots.js`, so they refresh with the rest.
- **Tutorial 11** has `serverStatus`, `serverGates[]`, `serverAsserts[]`, the per-gate JUnit
  suites and observe-gate `<skipped>`, and the DEFAULT and STRICT lines.
- **Item 5**: README, FEATURES, Tutorials 01, 07, 10 and 12, UPGRADING-ASSERTS, `_sidebar.md`, the
  tutorial index, and `Instructions.md` (E26, and an R06 golden-harness section).
- **Item 6**: CLAUDE.md. **Item 7**: every screenshot in the script recaptured, and `reportExample/`
  refreshed. It has the Server tab now, but empty: `example.json` declares no `serverMetrics`, and
  the refresh script runs the listener only, so a populated example needs the three steps in
  `refresh-report-example.sh` (left open). The asserts-tab shots `50`–`56` are not in the script
  and were not recaptured.
- **Item 8's documentation half**: the Windows `cmd.exe` `java -cp` line, and the `.bat` caveat, in
  README and Tutorial 13; and the `JMETER_HOME` note in both payload READMEs
  (`test-plans/server-metrics/`, `test-plans/goldens/server-metrics/rec/`). The first is inside
  the fixture stamp, so that edit regenerated every fixture.
- **Checks**: the link checker (relative files and `#anchors`, GitHub slugs, fenced code
  skipped, self-tested on planted dead links) found 0 dead links in 155 checked over 20 files.
  Every documented gate line equals a `jq-gate.txt` program character for character (7 copies).
  Every `webinsight.*` property the docs name is in the code, and so is every `wir-collect` option
  and exit code (0 and 1, D27) and every console marker. "KPI" survives only as the retired `kpis`
  key.
- **One correction from the screenshots**: the board marks **every** row the page re-judged
  `live`, edited or not. The docs said "after an edit". The docs now say what the code does.
  Whether an unedited row should say `live` at all is a product call (see M9).

### M9 — Land

1. Merge `origin/main` into the branch: PR #83 (Java 21) is two commits ahead.
2. Bump and deploy.
3. Run **all eleven runners**, not just the automated tiers. `run-all-tests`, `run-error-tests`,
   `run-docker-tests`, the six `run-family-*` runners and `run-title-junit-tests` have never run on
   this branch.
4. Write this plan's final progress rows.
5. Push the branch and open **one PR**, merged with a merge commit; the milestone commits are the
   review units. Merging is Aaron's call.

## v2 backlog — one list

Everything deliberately left out of v1, from the review's §9 cut list, the decisions above, and the
2026-10-02 research (plan review §5):

- **Windows:** pre-test and cool-down windows; `deltaPre`. Ramp-*down* exclusion comes first, as the
  cheapest.
- **Coverage:** recompute it over the judged window (D28); `lookback_delta=<step>`, so a slot counts
  only a fresh sample.
- **Baselines:** judge against previous runs (`deltaBaseline`, Keptn-style: the last N passing runs'
  `serverAsserts[].actual`). Needs a fingerprint of each assert's definition and a `NO_BASELINE`
  reason. Apply the same to the client gate.
- **Missing data:** harden the D27 leniency. A per-assert `mustHaveData`, default true on building
  gates (what Flagger, Iter8, Keptn, SRG and Kayenta all do), plus Kayenta's "too much undecided
  cannot read PASS".
- **Load:** the per-side server load check (`serverMetrics.load {target, tolerance}`, Aaron
  2026-08-21) as an alternative to `requiresLoad`.
- **Reducers and folds:** ~~`across: min`~~ (shipped in 3.1.31, server-tab-followups SF2: the
  catalogue declares it on postgres `up` and `cache.hit.pct`, and an assert may write it), or a
  warning on a downward band under `across: max`; per-instance judging; `minPoints` for `slope` and `pN`; a NaN policy (`nan: gap|zero`); a
  `divideBy` warning when used with `slope`.
- **Collection:** split windows over Prometheus's 11,000-points-per-series limit; Datadog, Elastic and
  Grafana sources.
- **Thresholds:** environment-dependent thresholds (D18).
- **Packs (D26):** node@1, then jvm@1 with 3 exporter variants, then postgres@1, then kafka@1
  (invariants and lag). Queries are adapted from Apache-2.0 sources with a NOTICE file. Bands ship
  only for invariants; everything else is seeded from the run. This needs `wir-collect --pack`, so
  unclaimed series are collected, and direction-aware seeding.
- **Generated asserts:** a learning state (observe-only until N runs).
- **Exit codes:** opt-in distinct exit codes for "gate failed" vs "gate could not run".
- **UI:** "+ Assert from chart" on server charts; Discard per slice; Save/Discard on the Server tab;
  a samplers-table column; timeline-marker breaches; Compare overlays.

## Verification

```bash
mvn test                                    # per milestone
bash -c '. scripts/lib/common.sh && run_js_unit_tests'
./test-plans/scripts/run-goldens.sh         # M1, M4 (accept only with the change that moved them)
./test-plans/scripts/run-regression.sh      # per milestone from M5
./test-plans/scripts/run-config-tests.sh    # M4, end
./test-plans/scripts/run-all-tests.sh       # M5 (E26), end

# the feature, end to end (from M4)
jmeter -n -t plan.jmx -l results.jtl -Jwebinsight.report.failonassert=false
./scripts/wir-collect.sh --jtl results.jtl --annotations report-annotations.json --out server-metrics.json
jmeter -g results.jtl -o out -Jwebinsight.report.servermetrics=server-metrics.json \
       -Jwebinsight.report.annotations=report-annotations.json -Jwebinsight.report.junit=true
jq '.gate, .serverGates' out/web-insight-report.json
```
