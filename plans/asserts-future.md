# Asserts — deferred features

> **Status:** parked · 2026-08-10 · `<revision>` 3.1.1 · citations re-verified 2026-08-10
> (spot-checked: `TDigestPercentileCalculator.java:57` and the 128-value reservoir still hold;
> item 1's `LiveStatisticsEngine` line numbers have drifted — re-verify before quoting one).
>
> **Unpark when:** the core plan's A1–A9 have landed. **That condition has fired.**
> [`asserts-core-hardening.md`](asserts-core-hardening.md) shipped (3.0.16),
> [`asserts-config-errors-fail.md`](asserts-config-errors-fail.md) shipped (3.1.0), and S1
> landed `Usability` as the one answer to "can this rule decide anything" (PR #79). Nothing here
> is *scheduled* — that is now a decision someone has to take, not a blocker.
>
> **What that unblocks, by the prerequisite each item named:**
> - **Item 1 (time windows)** — needed "the core plan's §1 status model". Landed: four statuses,
>   one producer. Unblocked, and the largest item here.
> - **Item 3 (baseline-relative bands)** — same status-model prerequisite, landed; still gated on
>   its *second* prerequisite, a product decision about owning trend history.
> - **Item 4's qualified error metrics and `fasterThan:`** — only ever blocked by rule 3 ("no new
>   metric vocabulary now"), which expired with the core plan. Decisions, not builds.
> - **Item 2 (sustained breach)** — still blocked, on item 1's window infrastructure rather than
>   on anything in the core plan.
>
> **The rule that put everything in this file:** *do not build advanced features on a framework
> that is not yet stable and robust.* Each item below is recorded so the research behind it is not
> lost, and each names the **prerequisite from the core plan** it cannot be built without.

---

## Why these are deferred, not rejected

Every item here was designed and adversarially reviewed on 2026-08-07 (a 14-agent panel: 7 code
readers, 3 competing designs, 3 judges on separate lenses, a completeness critic). The engineering
is sound. The reason none of it proceeds is that all of it widens a model whose *base* is not yet
trustworthy:

- a rule the GUI cannot delete,
- four ways to reach FAIL without an assert failing,
- two status vocabularies with translations between them,
- a loader that turns a typo'd file path into a green run,
- and no way to load an asserts file from the GUI at all.

Widening a vocabulary while the existing one can return a fabricated `0.0` is building the second
floor on a cracked one. **Revisit this file only after the core plan's A1–A9 have landed.**

---

## 1. Time windows — "p95 under 800 ms *after warm-up*"

**What it gives.** The single most-written assertion in load testing, and today it is unwritable.
The report already knows where warm-up ends (`webinsight.report.rampup`, wired through both
ingestion paths) and uses it to draw a shaded chart overlay and nothing else
(`LiveStatisticsEngine.java:204-206, 293`). Every p95 the gate judges includes the ramp.

**The one design fact worth not re-deriving.** *A window cannot be a fold of the per-bucket series.*
Per-bucket p50/p90/p95/p99 come from a **128-value reservoir sample** per sampler per second
(`SamplerBucketAccumulator.java:10-14, 40-48, 78-85`) that is then discarded — at 500 rps that is
128 of 500 samples — and the mean of per-bucket p95s is not the window's p95. No arithmetic
recovers it. This is the codebase whose `PercentileSource` javadoc establishes that **0.5 ms** is
"exactly large enough to matter as a verdict".

**So a window is a second aggregate, not a fold.** Keep a second `SamplerAggregator` set (real
t-digests) keyed by window, fed from the same sample loop, shipped as a second `samplers`/`overall`
block of identical shape. Then the judging site becomes a source selection and everything below it
is untouched. `O(samplers × windows)`, not `O(samplers × seconds)`. The browser reads a shipped
number instead of a shipped number — the mirror gains no statistic.

**Build it from configuration, not from the annotations file.** The aggregator must be fed from the
first sample, so its bounds must be known at `testStarted`. `rampUpDurationMillis` is; the
annotations file is **not** — auto-detection happens at report-generation time
(`WebInsightReportListener.java:449-461`), which is the route every fixture uses. This project has
already assessed and declined loading annotations before `ReportData` exists.

**Its own failure mode.** `rampUpDurationMillis` is never derived from the JMX thread group, so on
plans that do not set the property a "steady" window silently equals the whole run. That must be a
loud finding, never silent.

**Prerequisite:** the core plan's §1 status model — otherwise a window adds a fifth way to reach a
status nobody declared.

---

## 2. Sustained breach — "errors above 2% for 94 continuous seconds"

**What it gives.** The difference between a blip and an outage, which no whole-run aggregate can
express.

**Design.** Make the reducer yield **seconds**, not a boolean: `reduce: {longestBreach: {gt: 2}}`
with ordinary `warn`/`fail` bands over the resulting duration. A boolean quantifier would bypass
`Predicate.breaches(double) → one verdict for one value`, leave `Match.actual` undefined (the CI
JSON emits it per match), and give the chart no number to draw. As seconds it ships with **no new
`Predicate.Op`** (each measured at ~18 client sites), gets two-tier warn/fail — which neither k6
nor Taurus can express — and draws.

**Constraint.** Fold-based, so percentiles are refused under it (reservoir, above). Permitted on
metrics whose per-bucket value is exact: `errorRate`, `throughput`, `mean`, `sampleCount`.

**Prerequisite:** item 1's window infrastructure, and the core plan's one-producer rule.

---

## 3. Baseline-relative thresholds — "no more than 10% worse than last night"

**What it gives.** A regression gate. A baseline already exists — `ComparisonResult`,
`ComparisonThresholds`, auto-detected baseline JTL — and `isRegression()` reaches the Compare tab
and **never reaches `GateEvaluation`, the CI JSON, the JUnit XML or the exit code**. A regression
cannot fail a build today.

**Design.** Do **not** add operators (`gtPct`, `higherByPct`, …) — ~18 client sites each. Keep one
operator per band and make the operator's *value* an object:

```json
{ "fail": { "gt": { "of": "baseline", "pct": 10 } } }
```

`asNumber` gains an object branch resolved before the judging site. The band map keeps exactly one
entry, so `AssertionRuleMapper.readBand`'s arity check, its verbatim-pinned message and the
schema's `maxProperties: 1` are all untouched.

**Refuse `window` + `baseline` together** until someone defines how two runs' time axes align.

**Prerequisite:** the core plan's status model, and a decision about whether this plugin should
own trend history at all given it produces one self-contained HTML file.

---

## 4. Metric vocabulary

Deferred rather than deleted by the core plan:

- **Qualified error metrics** — `errorRate:class=nonProtocol`, `errorCount:code=5*`. Already built
  and working; the core plan defers them only because they are metric-vocabulary surface and rule 3
  says not now. **Cheapest item here to un-defer** — it is a decision, not a build.
- **Count-based SLIs** — `fasterThan:800` ("what fraction of requests beat 800 ms") via
  `PercentileSource.cdf`. Verified cheap: `cdf` is already implemented at
  `TDigestPercentileCalculator.java:57` and the interface simply omits it. It is **the only latency
  statistic that folds across a time window exactly** (a proportion is a mean of indicators), which
  makes it the principled companion to item 1 rather than a consolation prize.
- **A log-spaced per-sampler CDF ladder in the payload**, so a newly authored threshold can be
  previewed. Also fixes a pre-existing defect: a newly authored off-grid percentile renders INVALID
  because `assertPercentiles` only carries quantiles that existing rules already named.
- **Server metrics** (CPU, memory, external KPIs) — see `plans/design_handoff_asserts/`. The
  hardest part is not the rule model, it is that these have no home in `ReportData`.

---

## 4b. Drag-to-retune: two fixable limits

Both surfaced while deleting `loadValidity` (A1), and neither is a reason to touch the gesture —
which is kept deliberately (`asserts-core-hardening.md` §2f). Recording them so they are not
rediscovered.

**It cannot express a threshold below 0.01.** `draggableValue` is
`Math.round(value * 100) / 100`, so every dragged value is quantised to two decimal places. A
throughput rule at `lt 0.001` — E25 has one — simply cannot be dragged: every position rounds to
`0` or to something two orders of magnitude away. The grain should follow the metric's scale
rather than being a constant, or the drag should refuse visibly instead of silently landing
somewhere else. This is the same rounding-grain defect that made `attainment` publish
`failThreshold: 0.0`, in a different place.

**It does not show the number it lands on.** The original objection, and the one thing genuinely
wrong with the gesture: you drag, and the value is chosen for you without being displayed until
after it is committed. A live readout on the drag handle fixes it. A readout, not a deletion.

## 5. Smaller parked items

| Item | Note |
|---|---|
| **Error-budget / burn-rate framing** | `Attainment` (in the core plan) already yields `consumed`; a budget UI is packaging on top, not new engine work |
| **Coverage drift detection** | `unmatched` fires only when a target matches *nothing*, so a `match: "*"` rule that judged 47 samplers last week and 3 today reports identically. Emit the **resolved match-set size** per rule — one integer, already computed — and drift becomes a two-file diff |
| **Duplicate auto-derived ids** | `deriveId` keeps the last 3 hyphen-segments and truncates at 28 chars, so `GET /api/v1/users` and `POST /api/v1/users` both derive `api-v1-users-p95`; both emit the same `<testcase name=…>` and CI ingesters can collapse one |
| **Distributed mode has no single clock** | Buckets use the remote generator's uncorrected wall clock (`LiveStatisticsEngine.java:89-91`). **Blocks items 1 and 2 in distributed runs** and is unresolved |
| **Payload scale** | `buildPerSamplerSeries` allocates a dense, null-padded, uncapped `Object[12][numBuckets]` per sampler — a 4-hour, 500-sampler run is ~86 M boxed slots against a 10 M-char external-data threshold. Items 1–2 are worth most on exactly the runs where this is already the problem |
| **Appended JTL judged as one run** | `-l results.jtl` appends; nothing notices a file spanning two executions. Every time-axis feature would make a monotonic-timestamp check nearly free |
| **Print / PDF** | `report.css` has one `@media print` block and it hides `.tab-nav`, so "Save as PDF" produces the active tab only — and the Asserts tab is never the active tab on open. Managers circulate PDFs |
| **A copy-pasteable verdict block** | Aimed at the report's *recipient* rather than its author: five selectable lines to drop into a ticket. No engine, schema or parity change |

---

## 5b. Parity gaps the core plan left behind

Both are consequences of the CRUD work, both are the kind of thing that costs someone a day in
six months, and one of them is now half covered. Logged when they were created (2026-08-08); gap 1's JS
half closed 2026-08-10 by S3.1.

| Gap | Why it matters | Cheapest honest fix |
|---|---|---|
| **`ruleFromJson` is a second parser** — **JS half CLOSED 2026-08-10** | The document editor and `openFile` turn FILE-shaped rules into evaluator-shaped ones, so two independent paths reach the internal shape: Java's `AssertionRuleMapper` (via the payload) and this one. `test-plans/js-unit/asserts-parity.test.js` now drives **all 58 vectors** through `_ruleFromJson` — the function that actually runs when a tester opens a file — and then through the same `evaluate`, in node, in a tenth of a second, on every S02 / R02 / CI run. A JS-side parser divergence now fails a test instead of never being looked at | **What is left is the Java side**, the same bug from the other end: `AssertionParityTest.rule(JsonNode)` (`AssertionParityTest.java:186`) still hand-builds the internal shape, so `AssertionRuleMapper` — the parser that reads a real annotations file — is still pinned by nothing. Reroute that helper through the mapper; the vectors already carry the file shape. The browser spec's own `ruleFrom` helper can stay: the node layer covers the parser, and `asserts-parity.spec.ts` is there to prove the code works when loaded from a generated report |
| **`attainmentOf` duplicates `Attainment.of` with nothing comparing them** | The Budget column has to be computed client-side, or it goes stale the moment a threshold is dragged — that part is settled and right. But the arithmetic (which side is the divisor, when the answer is null, the zero-divisor guard) now lives in two languages, and only the Java side has unit tests. The failure mode is quiet: a column that reads 96% in the report and 0.96 in the JSON, or null in one and a number in the other | Add `attainment` to the parity vector `expect` block for the cases that have a fail band, and assert it on both sides. `AttainmentTest` already pins the Java answers, so the vectors only need the numbers copied across |

Neither blocks anything today. Both get cheaper to fix the sooner they are done, because the
vector file is where they belong and it is already loaded by both suites.

---

## 6. Explicitly rejected — do not revisit without new evidence

| Idea | Why not |
|---|---|
| **Boolean composition (`and`/`or`) and quantifiers** ("3 of 5 breached") | Four independent commercial products declined it. What people mean is almost always a *status transition*, which item 2 answers better. A rule that reads as a Boolean cannot be a table row, cannot be ⊕-seeded, cannot be charted |
| **An `objectives[]` block that derives rules** | ~14 coordinated sites and it produces a row nobody can edit. `loadValidity` is that pattern, and deleting it is the core plan's first milestone |
| **A new `AssertionStatus`** | The counts-sum-to-declared invariant is what lets a pipeline check the gate's own arithmetic |
| **Mid-run abort** | Works under `-n -t`, does nothing under `-g -o`. One run, two answers |
| **Rounding the engine to 2 dp** so it matches the display | Quantises the threshold space to 0.01. The repo's own fixture declares `0.002` with bands at `0.0015`/`0.001` — three orders of magnitude below the grain. Ship full precision, format at render time. *(This exact trap was hit and fixed during Phase 0.)* |
| **`agg: "avg"` as a declarable field** | Read the fold direction off the band's operator instead — `gt` implies max, `lt` implies min. Makes "average of p95" unwritable by construction |
