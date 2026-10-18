# Review: server-metrics design v2 vs the canvas, against the requirement

**Status:** review, 2026-08-21. Complete. Every code claim below was verified against HEAD by
read-only agents and survived an adversarial pass from three lenses (code facts, requirement
fidelity, operational simplicity); file:line citations are theirs. One of my earlier claims was
refuted and is corrected here (JUnit root, §1.4).

Inputs: `plans/server-metrics-design-v2.md`, `plans/design_handoff_asserts/Server Metrics
Integration.dc.html`, the requirement as you stated it, CLAUDE.md, and the shipped code.

## 0. Verdict

v2 gets the three big calls right — named server gates with `failsBuild`, no combined verdict,
INCONCLUSIVE → `NOT_JUDGED` + reason — and the separate-editor decision is correctly argued.

It cannot be built as written. Six promises in it are false against the tree (§1–§4), three of
its "unchanged from the canvas" sections rest on things the report does not have (§5), and the
requirement itself contradicts two decisions already recorded in the repo (§2.1, §2.2). None of
this is fatal; all of it is a decision you have to make rather than one the implementer can.

---

## 1. Wire shape and contracts

### 1.1 "`gate` byte-for-byte unchanged" is impossible with the fixed reason list

`CiCdJsonSerializer.notJudgedBlock` iterates `NotJudged.values()` (`CiCdJsonSerializer.java:228-235`)
and `countKey()` is a no-default switch that refuses to compile until a key is chosen
(`NotJudged.java:78-87`). The moment `SOURCE_FAILED`, `LOW_COVERAGE`, `LOAD_NOT_ACHIEVED` exist,
every run's `gate.notJudged` — server metrics or not — carries three always-zero keys, in the
CI/CD JSON **and** the HTML payload (`GatePayloadBuilder.gateOf` shares the block, `:176-178`).

**There is a golden harness now** — `test-plans/scripts/run-goldens.sh` (R06), `G1–G3.json/.xml`,
canonicaliser keeps key order deliberately. CLAUDE.md's "there is no golden-fixture harness" is
stale. The goldens pin the five-key `notJudged` block (`G2.json:65-71`), so this change fails R06
until `--accept`.

Hand-listed sites that must change or silently misbehave:
- `GateEvaluation.logSummary` (`:163-168`) — compiles, omits the new reasons from the one log line.
- `JunitXmlWriter` (`:193-194`) — `skipped = notJudged − invalidRule`: every new reason lands in
  `<skipped>`, invisible in Jenkins, unless told otherwise.
- `report.js` — `REASON_KEY` (`:6045-6048`), `EMPTY_GATE` (`:6053-6056`), `evaluateAll`
  (`:6683-6696`): an unmapped reason does `g.notJudged[undefined]++` in the recomputed draft gate.
- `AssertionParityTest.java:381-384` demands a vector per `NotJudged` constant — the **client**
  vector file would be forced to carry reasons the client evaluator cannot produce. Split the guard.
- `WIKI/FEATURES.md:363-364`, `Tutorial/11-Export-Integration.md:107-109,148,359-360`,
  `GateOutputTest:176-179` — all hand-sum four keys.

**Recommend:** drop "byte-for-byte"; state "additive: existing keys keep meaning and position,
`gate.notJudged` gains three always-present keys so the sum invariant holds". Re-accept goldens in
the same commit. The alternative (a server-only reason enum) keeps the bytes but forfeits the
one-vocabulary benefit that is the whole point of §2.3 — not worth it.

### 1.2 `serverGates[].counts {pass,warn,fail}` is a second vocabulary

Shipped block: `status/declared/evaluated/passed/warned/failed/notJudged{total,…}`
(`CiCdJsonSerializer.java:203-213`). v2 §1.4 invents `counts{pass,warn,fail,notJudged}` plus a
*sparse* `notJudged{sourceFailed}` — which breaks the sum-to-total bookkeeping `countKey()` exists
to protect. `GateSummary.of` reads only `status()`/`notJudged()` and its javadoc invites "a future
metric source" (`GateSummary.java:65-68`).

**Recommend:** `serverGates[i] = {id, label, failsBuild, …gateBlock(GateSummary.of(kpiResults))}`.
One arithmetic, one jq idiom:
`.gate.failed == 0 and ([.serverGates[] | select(.failsBuild) | .failed] | add // 0) == 0`.
*(Superseded, M7d: this idiom errors on a client-only run and is false on a server-only one. Use
`test-plans/goldens/jq-gate.txt`, which is evaluated over the goldens.)*
Note: an all-`NOT_JUDGED` server gate reads `status: "NOT_EVALUATED"`, same as the client.

### 1.3 `assertsEvaluated` and the header badge are client-only

`assertsDeclared = evaluation.hasGate()` (`ThymeleafReportRenderer.java:175-182`);
`assertsEvaluated` (`CiCdJsonSerializer.java:103-108`) reads the client gate. A file declaring only
`serverMetrics.kpis` renders **NO ASSERTS DECLARED** and emits `assertsEvaluated: false` while a
server gate FAILs. Define them ("any gate declared / any gate ran") or add a sibling, and give the
header a fallback when only server gates exist.

### 1.4 JUnit — correction

My earlier note that "one `<testsuite>` per gate changes the root" was **wrong**: the root is
already `<testsuites>` wrapping the sampler suite and `<testName> - assertions`
(`JunitXmlWriter.java:107-113, 139-141`). Adding a suite per gate is the existing precedent. What
*does* need stating: suite name `<testName> - <gateId>` (reserve `assertions` for the client);
root `tests/failures` count the sampler suite only today — keep it so and say so; and which of the
new reasons is a `<failure>` vs `<skipped>` — decide it, don't let the subtraction decide.

---

## 2. Exit semantics — where the requirement collides with the tree

### 2.1 `NO_MATCH` under `failonerror` reverses a recorded decision

`plans/asserts-config-errors-fail.md` §1: "`NO_MATCH` does **not** fail. A sampler that only runs
in some environments is legitimate… failing on it would strand those users."
`ReportArtifacts.hasConfigProblem()` = `invalidRules > 0 || loadProblem != null` (`:184-186`) —
nothing else. The retired-`onMissing` message says an unmatched target "never fails the run on its
own" (`AssertionRuleMapper.java:267-271`).

The requirement puts server `NO_MATCH` under `failonerror`. The canvas's own multi-environment
feature (§2, kept) is exactly the protected case: a Kafka query matching no series in DEV because
the topic is not deployed there. v2 §2.3's "four reasons reused verbatim because the meaning is
genuinely identical" is then false for `NO_MATCH` — same word, different exit per side.

**Recommend:** symmetry. Server `NO_MATCH` is counted, printed, not fatal (jq opt-in, as the
client). `failonerror` covers `INVALID_RULE`, `SOURCE_FAILED`, `LOW_COVERAGE`. If you want server
`NO_MATCH` fatal, the design has to argue why a query matching nothing is a file defect when a
sampler matching nothing is not — and then apply it to both sides.

### 2.2 `LOAD_NOT_ACHIEVED` re-implements `loadValidity`

Load validity already ships as an ordinary **client rule**: `LoadValidityMigration` turns the
block into `id: load:<metric>`, scope overall, `fail: {lt: target×(1−tol)}` (`:56-105`), mapped and
judged with every other rule (`GateEvaluation.java:104-134`), folded into `gate.status`, and
`failOnAssertIfAsked` fires on FAIL (`WebInsightReportListener.java:596-609`,
`WebInsightDataExporter.java:238-248`). **A load shortfall is a client FAIL that exits 1 under
`failonassert` today.** E25's `asserts.json:169-177` still carries the block.

The canvas's per-KPI `loadValidity{metric,sampler,target,tolerance}` + `onLoadShortfall` is a
second implementation (and repeats target/tolerance on every KPI). v2 §5.2's recommendation —
`failonerror` — would make the same measured fact exit 0 via the client rule and exit 1 via the
dependent KPIs, under the flag reserved for "the file asked for a check that never ran". C33 run 4
(`run-config-tests.sh:1118-1134`) pins the principle: a FAIL verdict exits 0 unless `failonassert`.

**Recommend — open decision 2 closes as "neither flag":**
- One declaration of load: the client rule. A KPI may carry `requiresLoad: "<client rule id>"`
  (run-level default `serverMetrics.requiresLoad`).
- Load rule **FAIL** → dependents `NOT_JUDGED / LOAD_NOT_ACHIEVED`. Load rule **NOT_JUDGED**
  (zero samples is `NO_MATCH` today, `AssertionEvaluator.java:94-97`) → dependents inherit
  NOT_JUDGED, detail names the load rule — a load that could not be judged has not been
  established. **WARN/PASS** → judge.
- `requiresLoad` naming no declared rule, or a non-throughput rule → `INVALID_RULE`.
- No new exit mapping. `scaleBand`/`assertAnyway`/per-KPI `loadValidity` are deleted — rescaling a
  threshold by achieved/target is a verdict-moving transform that never appears in the file.

### 2.3 `failsBuild: false` vs `failonerror` — observe-only does not exist as written

v2 §2.6 routes `SOURCE_FAILED`/`LOW_COVERAGE` through `failonerror` (default true,
`ExitPolicy.java:49-53`) with no reference to the owning gate's `failsBuild`. Canvas §7 promised
"adopt server KPIs in observe-only mode first". Day one: a rotated Datadog token on a
`failsBuild:false` gate → `CONFIG ERROR` → exit 1.

Two options, and the requirement's unqualified sentence is literally option B:

| | A — `failsBuild:false` silences exit for that gate's collection failures | B — collection failures are global config errors |
|---|---|---|
| Inside the requirement as written | no (narrows the sentence) | yes |
| Observe-only adoption path | exists | does not — say so explicitly |
| `INVALID_RULE` | always fatal (file defect) | always fatal |
| Marker / findings | always printed | always printed |
| Escape hatch | one boolean per gate | `failonerror=false` (also disarms client typos) |

**Recommend A.** An observe gate whose KPIs all fail collection reads `NOT_EVALUATED`, never PASS
— silence cannot go green without the exit code, which removes the argument for making it fatal
on an observe gate. Either way, write the rule into §2.6.

---

## 3. The model

### 3.1 `Usability` covers half the question — and the type decision is forced

`Usability.check(AssertionRule)` is a pure function of one rule; `refusal()` is the sole producer
of `INVALID_RULE` (`Usability.java:174-236`, `AssertionEvaluator.java:75-78`). `NO_MATCH` and
`NO_VALUE` come from the **evaluator**, from data (`:106-116, :153-157`). So "server KPIs go
through `Usability`" is right for what was *written* (unknown source, unknown reducer, missing
query, undeclared gate, unreadable band) and structurally cannot cover `SOURCE_FAILED` /
`NO_MATCH` / `LOW_COVERAGE`. v2 §2.5 already has that mapping; it needs a home — one exhaustive
switch, no default, like `countKey()` — and a test like `UsabilityTest`. Do not call it "a second
answer to can-this-rule-decide"; it answers a different question.

The type question is not academic: `AssertionRule` is `final` with no interface
(`AssertionRule.java:12`); `MetricRef.parse` is a closed vocabulary that refuses any server metric
name (`:126-149`); every `switch` over `AssertionTarget.Kind` has a `default` arm that would route
a new kind into `NO_MATCH` silently (`AssertionTarget.java:172-173`, `GatePayloadBuilder.java:304-306`,
`report.js:6218`); every `RuleResult` factory is package-private and takes an `AssertionRule`
(`RuleResult.java:83-118`).

**Recommend:** a server KPI is its own final type. Add a minimal `Judged {id, description}` seam so
`RuleResult`/`GateSummary.of` serve both. Do **not** force KPIs into
`AssertionRule`/`MetricRef`/`AssertionTarget` — each would need an extension point the default
arms currently hide.

### 3.2 The KPI grammar: collapse nine types into reducer × band

Facts: `onMissing` is in `RETIRED_RULE_KEYS` → `INVALID_RULE` (`AssertionRuleMapper.java:260-273`,
mirrored `report.js:8530-8539`). The client has **no** runtime "warn at 80%": `warn()` is nullable
and the evaluator judges only declared bands; the only 0.8 is `seedBands()` in the editor
(`report.js:9527-9529`) — an authoring seed. The canvas's `range {min,max}` is the corridor the
project deleted ("write them as two", `Predicate.java:14-17`; `outside` refused,
`AssertionRuleMapper.java:179-185`). `required` is undefined. v2 §3.2 still lists nine types,
`warnAt`, `onMissing`, `onLoadShortfall` as editor fields — v2 is internally inconsistent with its
own §4.

**Recommend** (inside the freedom — the nine types are a canvas §5 decision, and v2 marks §5 revised):

```json
{
  "id": "kafka.consumer.lag",
  "description": "Consumer lag — orders",
  "enabled": true,
  "source": "prom",
  "query": "max by (partition) (kafka_consumergroup_lag{cluster=\"${env.cluster}\"})",
  "unit": "msgs",
  "reduce": { "kind": "max", "over": { "sustainedFor": "30s" } },
  "across": "max",
  "requiresLoad": "load:throughput",
  "warn": { "gt": 640 },
  "fail": { "gt": 800 },
  "gate": "kafka"
}
```

`reduce.kind ∈ max|min|avg|last|pN|slope|deltaPre`, `over ∈ {sustainedFor} | {breachBudget}`,
`divideBy: "<load rule id>"` for ratio. `range` = two KPIs or opposite-direction bands, exactly as
the corridor migration did. Deleted: `assert.type`, `warnAt`, `onMissing`, `required`,
`onLoadShortfall`, per-KPI `loadValidity`, `deltaBaseline` (see §5.3).

Reuse that is real: `bandFromJson`/`bandToJson`/`breaches` are pure; `warnCoveredByFail` as-is;
the parity-vector *schema*. Reuse that is not: `validateEditor` is bound to the client editor
object (`report.js:8768-8802`) — extract its band checks into a pure function; and
**`Usability.addIfUnreachable` assumes every metric is non-negative** (`:317-336`) — a slope or
delta KPI with `fail: {lt: 0}` is legitimate and would be refused as `INVALID_RULE`. Gate that check
on the reducer's sign.

### 3.3 Per-instance KPIs are not matches

Reusing `RuleResult` matches for "one KPI, N pods" inherits: the 200-cap with browser re-judging
(needs every instance series in the payload); worst-of-N with no quorum; and `SamplerVerdicts`
keyed by `match.samplerName()` (`:61-92`) — pod names would leak into `assertViolations`,
per-sampler `assertStatus` and the sampler JUnit suite. **Recommend:** server results never enter
`SamplerVerdicts`; v1 judges `across: max|avg` over the reduced series; per-instance is
display-only (charted, "and N more"), with an explicit per-KPI instance cap in the payload.

---

## 4. Pipeline — what "unchanged from the canvas" actually inherits

### 4.1 CI choreography is missing, and step 1 never reaches step 2

- Exporter skips when a `.json` sibling carries the same `runId`
  (`WebInsightDataExporter.java:154-167, 468-481`); no force flag. `-o` refuses any non-empty dir
  before any exporter runs (`run-family-cd-tests.sh:37-41`). So "merges the payload if present" into
  the existing report is impossible either way — a **fresh directory** is mandatory.
- Listener generates synchronously inside `testEnded` (`WebInsightReportListener.java:401-548`); no
  hook, executor or delay anywhere in `src/main`. The `-n -t` report **cannot** carry server gates.
- Output-dir auto-detection (the `baseline.jtl` precedent) is unreachable under `-g -o`
  (`run-error-tests.sh:524-549`) → the payload path needs an explicit `-J` property.
- Step 1 exits non-zero on a client FAIL under `failonassert`, or on any config problem by default
  — a `set -e` pipeline never reaches the collector.
- `webinsight.report.resolved.filename` is a same-JVM handshake; a separate `-g` process looks for
  the `-J`/default name, not a GUI-configured one.

**Recommend — write this into v2 §1:**
1. `jmeter -n -t … -l results.jtl` — interim report, client gate only, exit informational
   (`failonassert=false` here, or tolerate).
2. `wir-collect` writes `server-metrics.json` beside the JTL.
3. `jmeter -g results.jtl -o <fresh dir> -Jwebinsight.report.servermetrics=<path>` — the only
   report Jenkins archives, the only exit code that gates (client + server fold).
Wire the merge into `WebInsightReportGenerator` (shared by both paths), not the exporter.

### 4.2 `run-context.json` has no producer

Canvas step 1 has the listener writing `run-context.json` (start/end, ramp bounds, env, runId,
achieved throughput). Nothing writes it; `ReportArtifacts.Kind` is `{HTML, JSON, XML}`
(`:42-45`) — a fourth file is outside the manifest, the staging/publish path, `failonerror`'s
partial-failure coverage and the sweep; it does not exist on the `-g`-only path at all; the plugin
reads no `env` property. **Recommend:** drop it. `wir-collect` derives window and runId from the JTL
via the core it already depends on (`JtlParser → ReportData → TestMetadata`, `RunIdentity.of`) —
identical for `-n -t`, `-g`-only and a crashed test. Environment and ramp-up are collector flags
mirroring the same `-J` values.

### 4.3 Identity: same JTL ⇒ same `runId`, so re-collection is invisible

`RunIdentity` is a fingerprint of the run, not the data (`RunIdentity.java:60-67, 109-122`). The
interim report, the canonical report and any re-collected payload share one `runId`; the template
check (`report.html:871`), the exporter skip and the asserts draft store (`report.js:8060-8062`) all
treat a stale or foreign server payload as "this run", and server drafts made on the interim report
surface on the canonical one. **Recommend:** the server payload carries `runId` (must equal the
page's; reuse `wirBanner`, not `wirIdentityMismatch`, which wipes every chart) **plus**
`collectionId = hash(sources, resolved queries, window, collectedAt)`; key server drafts on
`runId+collectionId`; stamp `collectionId` into `serverGates[]`. `RunIdentity`'s contract stays.

### 4.4 Distributed clock — already decided by the code

`testStartTime` (controller clock) is assigned and never read (`WebInsightReportListener.java:73`);
window and `runId` come from worker-stamped sample timestamps (`LiveStatisticsEngine.java:278-288`).
**Close the open question:** window = `[metadata.startTime, metadata.endTime]` as the JTL records
them; worker skew out of scope (NTP assumed, documented); per-source `clockOffsetMs` stays. Also: a
worker whose `testEnded` never arrives leaves `activeHostCount > 0` and **no listener report at
all** (`:404-421`) — the canvas's "a crashed test still reports" is true only for the `-g` path.

### 4.5 Packaging

No `main` class anywhere; the shaded JAR's manifest has no `Main-Class`; Jackson is `provided`
(`pom.xml:85-90`) so `java -cp plugin.jar` cannot parse JSON; JDK 11 (`pom.xml:42`) gives
`java.net.http.HttpClient` with no dependency and no relocation. **Recommend:** a `main` in
`com.jmeterwebinsightreport` inside the shaded JAR, launched by `scripts/wir-collect.sh` with
JMeter's `lib/` on the classpath — one artifact, nothing new for `verify_shaded_jar` to allow.

### 4.6 Payload entry point and size

Only two of four serializer overloads add the gate/comparison blocks (`ReportDataSerializer.java:62-71,
135-154` vs `:76-87`); the external-mode decision measures `chartDataJson.length()` in UTF-16 chars
against `externalDataThreshold = 10_000_000` (`WebInsightReportGenerator.java:272-282`,
`ReportConfiguration.java:21`); the identity/banner helpers exist only under `th:if externalDataMode`
(`report.html:840-967`). The canvas's own estimate (~10 MB for 2 h × 30 × 20 @ 15 s) sits on the
threshold, so mid-size soaks flip to external mode — which only E19 exercises. **Recommend:** series
enter via `buildChartDataMap` (counted, present in all outputs); downsample to the report bucket
ladder on write with a stated per-series budget; hoist `wirBanner` out of the external-only
conditional; unit-test that a server block pushes a small threshold into external mode.

### 4.7 Secrets are inlined into the HTML by an existing, tested path

`GatePayloadBuilder.java:394` puts the whole annotations document — unknown keys included — into the
payload, and `GatePayloadTest.java:214-219` **pins** that `serverMetrics.prometheus` URL survives
into it; `buildDocumentFrom` copies it into the download. Source URLs and `auth` under
`serverMetrics` in the same file land in every HTML and sidecar. **Recommend:** secret-by-reference
only (`auth` keys must end in `Env`; literal token/password keys are a config error); strip URL
userinfo and mask secret query params when the collector writes resolved queries; a serializer test
that a canary in `serverMetrics.sources[].auth` never appears in HTML, `.js` or JSON.

---

## 5. Three "unchanged" canvas sections that are not standing on anything

### 5.1 Canvas §3/§4 — the report has no steady-state / pre-window / cool-down

The only ramp field is `rampUpEnd = startTime + webinsight.report.rampup`
(`ReportDataSerializer.java:579-585`) from a property no fixture sets; no `rampDown`/`steadyState`
symbol exists. Buckets are 1 s keyed by sample second, absent (not zero) for idle seconds
(`LiveStatisticsEngine.java:89-93`); client windows are anchored at the first sample with a
1s/10s/30s/1m/5m/10m ladder (`report.js:347-355`). Per-bucket percentiles come from a 128-value
random reservoir (`SamplerBucketAccumulator.java:14,45`) — non-deterministic above 128 samples/s, so
a per-bucket ratio against client values cannot be pinned. And windows/baselines are the advanced
features you parked on 2026-08-07.

**Recommend v1:** one window, `[startTime, endTime]`, optionally excluding `[startTime, rampUpEnd]`
when `rampup` is set; "window used" on every KPI row; alignment by **timestamp**, never index,
rendered through the same `aggregateTimeSeries` windows as the client charts. Ratio divides by
whole-run `overall.throughput`, never a per-bucket value. Pre/cool-down windows, `deltaPre`,
`deltaBaseline` → v2+, and §4's table says so instead of "unchanged".

### 5.2 Canvas §6 packs — runtime import moves verdicts on an unedited file

`"packs": ["kafka"]` resolved at load makes the judged KPI set a function of the JAR version; the
project already lived this once (`LoadValidityMigration` javadoc `:9-26`: a derived rule could not
be edited, disabled or deleted). v2 §3.4's "Generate from sources" *is* the authoring path — yet §4
keeps §6 "unchanged", so the design currently has both. Packs are PromQL; sources are
Prometheus/Datadog/Elastic with three query languages. **Recommend:** packs are authoring-only —
"Add from pack" materialises KPIs into `kpis[]` with a `pack: "kafka@1"` stamp and per-source
query variants chosen at materialisation; the evaluator reads `kpis[]` only; a `packs` key at load
is a config error pointing at the editor; pack updates are an explicit, diffable editor action.

### 5.3 Canvas §8's three "elsewhere" items contradict the shipped UI

- *Samplers table gets a server-KPI column* — it is a Thymeleaf table with 14 literal `sortTable(i)`
  indices and index-persisted order/width (`report.html:421-434`, `report.js:4938-4947`). A column
  breaks saved layouts; a per-row badge injected like the assert badge does not.
- *Breaches become timeline markers* — markers are static document annotations
  (`report.js:2043-2070`, shipped from the file). Render breaches as `markArea` on the KPI's own
  overlay chart instead.
- *Compare tab overlays baseline server series / `deltaBaseline`* — the baseline is a re-parsed JTL
  with no server payload and no path to any gate (`GateEvaluation.of` takes no comparison). Cut
  from v1 with the baseline-retention question.

---

## 6. GUI

### 6.1 "Existing write-back already covers them" is false for drafts

`buildDocumentFrom` rebuilds from `WIR.asserts`'s private `docBase()` and copies `serverMetrics`
verbatim from there (`report.js:8589-8618`); `persistDrafts` stores only `{rules, testNotes}`
(`:8150-8153`); both Save entry points (Asserts Download and Notes-tab "Save Annotations",
`report.html:792`) call `WIR.asserts.download()`; `discardDrafts` clears `docBase` wholesale
(`:8208-8227`). A `WIR.serverKpis` module with its own draft state has **no hook into the saved
document**: edits do not reach Download, do not survive refresh, and are discarded by the other
tab's Discard. Worse, `hasDrafts` is whole-document text equality, so a server edit that mutated
`docBase` in place would flip the asserts pill to "0 unsaved changes" — a UI lie before data loss.

**Recommend:** a document assembler owned by neither tab (`WIR.document`): modules register a
slice `(name, build, baseline, discard)`; the assembler copies unknown keys, calls each slice;
both Save buttons, the import path (`loadDocumentText`, `:9138-9153`, must hand `serverMetrics` to
the server module) and the unsaved pill read it. One draft record per `runId(+collectionId)`
holding both slices; Discard per slice. Replace §3.4's sentence with this; the "no diff on an
unedited file" round-trip test covers both slices.

### 6.2 Header: the "server rollup" is a combined verdict

v2 §1.2: the fold is "never materialised as a verdict a human reads"; §5.3 then recommends a header
badge computed as worst-across-server-gates — on the most-read surface in the product. It ignores
`failsBuild`: a `db` gate with `failsBuild:false` and FAIL yields a red SERVER FAIL beside an exit 0
and a green Jenkins. The header badge is Thymeleaf (`report.html:29-34, 68-69`), never touched by
`report.js`, so any option needs new context variables — equal cost, pick on readability.

**Recommend:** client badge unchanged; one **server chip** naming the worst *building* gate and
counting the rest — "Server · kafka FAIL · 2 more" / "Server · 3 gates PASS" — linking to the tab.
Non-building gates contribute to the count, never the word. Per-gate badges live in the tab.

### 6.3 Both XSS guards are scoped to `WIR.asserts` by name

`xss-escaping.spec.ts:236,256` matches `WIR\.asserts\.` and sweeps `#tab-asserts [onclick]` only;
`verify_report_js_rules` (`common.sh:801`) greps the same literal. A second module rendering source
labels, instance names, query text and gate labels through `innerHTML` starts in the guards' blind
spot. **Recommend:** write the one-literal-call invariant into the design for the new module; widen
both guards to `WIR\.(asserts|serverKpis)\.` and `#tab-asserts, #tab-server` in the commit that adds
the tab; XSS vectors for a query containing `</script>`, quotes and `${`.

### 6.4 Environment-dependent thresholds — decision, not a fact

Canvas §2 (on your unchanged list) says the environment "selects … KPI thresholds" and `${run.*}` is
"usable inside thresholds". `asNumber` refuses a non-numeric band (`AssertionRuleMapper.java:275-289`),
`bandNumber` mirrors it (`report.js:8502-8511`), `bandToJson` writes the parsed number — so an
interpolated threshold is `INVALID_RULE` today and cannot round-trip: one drag in the editor
replaces the variable with whatever it resolved to in that environment.

**Recommend v1:** interpolation in source URLs, headers and queries only; `warn`/`fail` literal
numbers. If per-environment thresholds are genuinely needed: a per-env literal override block on
the KPI (still numbers, still diffable), shown read-only as "resolved from TEST" — never string
interpolation in a number slot. Either way the selected environment must be stamped into the
artifacts or the saved file and the judged thresholds diverge silently. This narrows canvas §2; say
so.

### 6.5 Fixtures and "Validate query" need a deterministic source

No HTTP mock exists anywhere; scenarios are timer-driven; `_fixture_stamp_files` hashes `src/main`
only (`common.sh:439-443`) — a recorded input under `test-plans/` is outside the stamp. The parity
suites v2 §3.5 demands would need a live Prometheus or assert forever against a stale recording.
**Recommend:** a first-class `file` source type (reads a recorded query response) — the offline
replay path for users *and* the deterministic fixture; add the recording directory to the stamp;
one fixture ID in `FIXTURE_IDS`/`REQUIRED_FIXTURES`; unit-test the HTTP layer against the JDK's
`com.sun.net.httpserver`. "Validate query" = exact lookup of `(source, resolved query)` against
collected series keys — matches/coverage for a collected query, "needs re-collection" for anything
else, never an estimate.

---

## 7. Your open decisions

1. **Default gate** — single `server`, `failsBuild: true` (a silent default-false is the A-series
   enemy). Implicit only when `serverMetrics.gates` is absent; once `gates` is declared, an unnamed
   KPI is a config error.
2. **`LOAD_NOT_ACHIEVED` exit** — neither flag; §2.2.
3. **Header with N gates** — no rollup; §6.2.
4. **Canvas six** — baseline retention: defer with `deltaBaseline`; Grafana: Prometheus-native first
   (packs are PromQL), Grafana proxy second; payload cap: §4.6; run tagging: provide `${run.runId}`
   as a query variable, build nothing; distributed clock: closed, §4.4; pack ownership: §5.2.
5. **Added: `failsBuild:false` vs `failonerror`** — §2.3, recommend A.
6. **Added: server `NO_MATCH`** — §2.1, recommend symmetry with the client.

## 8. Changes outside the code that v2 does not list

- Re-accept goldens G1–G3 (JSON and XML) in the commit that adds the reasons.
- `WIKI/FEATURES.md`, `Tutorial/11-Export-Integration.md`, `10-Asserts-Apdex.md`: seven-reason
  arithmetic, `serverGates`, JUnit suite names, the jq line.
- `CiCdJsonSerializerTest`'s `sla` sweep runs only the 3-arg serialize (`:100-127`) — extend to a
  6-arg call with a server gate so the sweep actually sees `serverGates`.
- `phase-3-chart-and-server-asserts.md` §3 still says "statuses gain INCONCLUSIVE" plus `source`
  targets and `over` clauses v2 does not carry — retraction line.
- CLAUDE.md: the "no golden-fixture harness" paragraph is stale (`run-goldens.sh`, R06).
- Canvas file: loads React from unpkg, references a missing `assets/echarts.min.js` (v2 §4 already
  notes this); its tab list lacks Asserts — Server is the 8th tab, after Asserts.

## 9. What v1 should ship, in one list

One `server` gate by default, N declared; reducer × band KPI grammar; `requiresLoad` by reference;
one window with optional ramp exclusion; `across: max|avg`; Prometheus + `file` sources; packs as
authoring; `collectionId`; the three-step choreography; a `WIR.document` assembler; the server chip.

Cut to v2+: pre/cool-down windows, `deltaPre`, `deltaBaseline`, per-instance judging, Datadog/
Elastic/Grafana, env-dependent thresholds, samplers-table column, timeline-marker breaches, Compare
overlays.
