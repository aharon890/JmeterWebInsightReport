# Server metrics — design v2

**Status:** proposal, 2026-08-20. Not implemented, not agreed.
**Supersedes** §5, §7 and §8 of `plans/design_handoff_asserts/Server Metrics Integration.dc.html`
on three points. §1–§4 and §6 of that canvas (pipeline, environment resolution, windows and the
wait, alignment and resampling, KPI packs) are **unchanged and still stand.**

Three directives drive this revision, plus one collision found while reading the canvas against
the shipped code.

---

## 0. The collision, first — it decides directive 2

The canvas was drawn 2026-08-07. The A-series hardening (`asserts-core-hardening.md`, 3.0.16;
`asserts-config-errors-fail.md`, 3.1.0) landed after it and **already answered the exact question
directive 2 asks**, on the client side.

The shipped model is two fields, not one enum:

| | Values | Source |
|---|---|---|
| `AssertionStatus` | `NOT_JUDGED` (gating=false) · `PASS` · `WARN` · `FAIL` | `core/assertion/AssertionStatus.java` |
| `NotJudged` reason | `DISABLED` · `NO_MATCH` · `NO_VALUE` · `INVALID_RULE` | `core/assertion/NotJudged.java` |

Each reason carries a `phrase()` that reads after a rule id ("checkout-p95 matched nothing in this
run"), a `wireName()` for the JSON/JUnit/payload, and a `countKey()` for the `gate.notJudged`
block. `NOT_JUDGED` is `gating=false`, so it can never turn a build green by accident — which is
the *only* thing INCONCLUSIVE was load-bearing for.

The implementation audit already recorded the canvas's INCONCLUSIVE as **superseded** by this.
So directive 2 is not "invent a better INCONCLUSIVE" — it is **"extend the reason vocabulary that
already exists"**, and the payoff is that a server verdict and a client verdict read in one
vocabulary instead of two.

---

## 1. Directive 1 — two kinds of gate, N gates, no combined

### 1.1 Gates become declared and named

`gate` is already a field on a KPI in the canvas (`"gate": "server"`). The only change is that its
value stops being a fixed enum and becomes **a name the user declares**:

```json
"serverMetrics": {
  "gates": {
    "kafka": { "failsBuild": true,  "label": "Kafka" },
    "db":    { "failsBuild": false, "label": "Database" },
    "jvm":   { "failsBuild": true,  "label": "JVM" }
  },
  "kpis": [
    { "id": "kafka.consumer.lag", "gate": "kafka", "...": "..." }
  ]
}
```

- **The client gate is fixed and singular.** It is the gate that ships today; it is not declarable,
  not renameable, and there is exactly one of it. A tester cannot create a second client gate.
- **Server gates are plural and declared.** A KPI with no `gate` defaults to a gate named after its
  **source** — which is the reading the directive gives ("server even can be more than 1 since we
  can assert from multiple sources"). Declaring gates explicitly lets a team group by *subsystem*
  instead, which is usually what they mean: one Kafka gate fed by Prometheus and Datadog both.
- An undeclared gate name on a KPI is a **config error**, not an implicit creation. Same reasoning
  as an unknown metric name today: a typo'd gate must not silently produce a gate of its own.

### 1.2 There is no combined verdict — but there is still one exit code

This is the part to get right, because "no combined gate" is easy to misread as "nothing decides
the build".

**Combined disappears as a *verdict*. It does not disappear as a *reduction*.**

- No `combined` entry in the CI/CD JSON. No third badge. No "combined" row anywhere in the UI.
- The process still exits once. The rule: **non-zero if any gate with `failsBuild: true` is FAIL**,
  under the existing `failonassert` flag. That is a fold over gates computed at exit time, never
  materialised as a verdict a human reads.

The distinction matters because a displayed combined verdict is what made the canvas's model
confusing: it invited "which gate do I look at?" when the answer should always be "the one that
owns the thing you broke."

### 1.3 What "combined" was actually carrying, and where it goes

One thing genuinely needed both sides: derived KPIs like *lag per achieved RPS* — the canvas's
`ratio` assert type, `metric ÷ achieved client load`.

These are **server KPIs that read a client value as an input.** The subject is a server series; the
client number is a divisor. So `ratio` KPIs belong to whichever server gate owns their subject, and
nothing about them requires a combined gate. The client value they read comes from
`${run.achievedRps}`, which the canvas's §2 variable scope already provides.

### 1.4 Wire shape

`gate` in `web-insight-report.json` **keeps its current meaning and position** — it is the client
gate, exactly as today, byte-for-byte for a run with no server metrics. Server gates arrive as a
new sibling key:

```json
"gate":       { "status": "PASS",  "...": "existing shape, unchanged" },
"serverGates": [
  { "id": "kafka", "label": "Kafka", "status": "FAIL", "failsBuild": true,
    "counts": { "pass": 3, "warn": 0, "fail": 2, "notJudged": 1 },
    "notJudged": { "sourceFailed": 1 } }
]
```

Zero breakage for every pipeline reading `.gate.status` today, which is the constraint that killed
the canvas's `gates: {client, server, combined}` reshape. JUnit XML gains one `<testsuite>` per
gate, suite-named by gate id, so Jenkins shows Kafka and JVM failures separately.

---

## 2. Directive 2 — retire INCONCLUSIVE, split the two questions it conflated

### 2.1 Why INCONCLUSIVE says nothing

INCONCLUSIVE is one word for at least five situations whose fixes have nothing in common:

| What happened | Who fixes it | How |
|---|---|---|
| Source returned HTTP 401 | DevOps | credentials / endpoint |
| Query ran, matched no series | Test author | the query, or the env labels |
| Data arrived, 62% coverage | Whoever owns the wait | `maxWait`, `step`, or the floor |
| Data perfect, run produced 3 RPS of the 5 asked for | Test author | fix the test, re-run |
| KPI declaration can't run at all | Test author | the config file |

A tester reading "INCONCLUSIVE" learns only that they did not get an answer. Every one of those
five rows is a different ticket.

### 2.2 The root confusion: two questions, one column

A server KPI's outcome is **two orthogonal facts**, and the canvas rendered them as one:

1. **Did we get the data?** — a property of a *series*, per source, per window.
2. **What did the KPI decide?** — a property of a *KPI*.

The canvas already has a **Collection status panel** (§8) answering question 1 per source. So
putting collection outcomes into the KPI board's status column duplicates it *and* destroys the
verdict column's meaning. The fix is to keep them separate and let the **reason** be the bridge.

### 2.3 The status model

**`AssertionStatus` is unchanged — four values, server and client alike.** No fifth status.

**`NotJudged` gains three reasons.** Four of the seven are the existing ones, reused verbatim
because the meaning is genuinely identical:

| Reason | New? | Means | Fix |
|---|---|---|---|
| `DISABLED` | reused | `enabled: false`. Parked on purpose. | nothing |
| `INVALID_RULE` | reused | The KPI declaration cannot run — unknown assert type, unreadable band, no assert clause, unknown source, missing query | the config |
| `NO_MATCH` | reused | We asked, and the query matched **no series** | the query, or the env labels |
| `NO_VALUE` | reused | Series exists, reducer produced no number — window empty after scoping, or every point in a gap | scope, or the gaps |
| `SOURCE_FAILED` | **new** | We **never got an answer** — auth, timeout, DNS, 5xx | credentials / endpoint |
| `LOW_COVERAGE` | **new** | Data arrived but below the coverage floor (default 90%) | `maxWait` / `step` / the floor |
| `LOAD_NOT_ACHIEVED` | **new** | Data is fine, the **run** is not — achieved load missed target beyond tolerance and the KPI said `invalidate` | fix the test, re-run |

Three observations on why this is the right cut:

- **`SOURCE_FAILED` vs `NO_MATCH` is the single biggest information gain over INCONCLUSIVE.**
  "We asked and got nothing back" and "we never managed to ask" look identical in a report and are
  fixed by different people. The canvas merged them.
- **`LOAD_NOT_ACHIEVED` is the one that most deserved its own name.** Neither the KPI nor the
  source is broken. A tester told INCONCLUSIVE goes hunting through Prometheus; a tester told
  LOAD_NOT_ACHIEVED re-runs the test. It must also reconcile with the existing client-side
  `loadValidity` concept and `LoadValidityMigration` rather than becoming a second implementation.
- **Four reasons are reused, not renamed.** A server KPI and a client rule that both matched
  nothing say the same word. That is the whole benefit of not adding a fifth status.

### 2.4 "Used / not used" — the axis the directive asks about

Three distinct situations, and only one of them is a status:

| Situation | What it is |
|---|---|
| A series is collected **and** a KPI asserts it | A KPI row with a verdict: PASS / WARN / FAIL |
| A series is collected and **nothing asserts it** | **Not a status.** No KPI ⇒ no row in the KPI board. It still charts, still overlays, still counts toward collection coverage. "Not used" is the *absence of a declaration*, exactly as a sampler with no rule produces no rule row today. |
| A KPI is declared and the series **isn't there** | `NOT_JUDGED` + one of the seven reasons |

That third row is the only place a "we didn't use it" outcome can appear, and it appears with the
reason attached. This is also why the pre-window ("charted, not asserted" in canvas §3) needs no
status at all — it is row two.

### 2.5 Series-level collection status stays separate

A **series** carries its own outcome, in the collection panel, never in the KPI status column:
`OK` · `PARTIAL` (some gaps, above floor) · `INSUFFICIENT` (below floor) · `EMPTY` (no series
matched) · `FAILED` (source errored) · `NOT_REQUESTED`.

The mapping into a KPI reason is deterministic — `FAILED → SOURCE_FAILED`, `EMPTY → NO_MATCH`,
`INSUFFICIENT → LOW_COVERAGE` — which is what keeps the two panels consistent instead of merely
adjacent.

### 2.6 Which reasons fail the build

`failonerror` (default **true**) already means *"the file asked for a check that never ran"*. Four
of the seven are exactly that: `INVALID_RULE`, `SOURCE_FAILED`, `NO_MATCH`, `LOW_COVERAGE`.
`DISABLED` is never an error. `NO_VALUE` follows the client-side precedent.

`LOAD_NOT_ACHIEVED` does not fit either flag cleanly — it is neither a config error nor a verdict
— and is listed as an open decision in §5.

> **Retracted 2026-08-22** (review §2.1–§2.3, delivery plan D3/D4, shipped as
> `NotJudged.isConfigError()` in M1): `NO_MATCH` is **not** under `failonerror` — a query matching
> nothing in one environment is as legitimate as a sampler that only runs in some plans, and the
> client side already decided that. `failonerror` covers `INVALID_RULE`, `SOURCE_FAILED`,
> `LOW_COVERAGE`, the last two subject to the owning gate's `failsBuild`. `LOAD_NOT_ACHIEVED` is
> under neither flag: the load rule it depends on already carries the FAIL.

### 2.7 One evaluator, one usability check

`Usability` is documented in CLAUDE.md as *the single answer to "can this rule decide anything"*,
and it exists because that question was once answered twice and the two answers drifted — a
`loadValidity` tolerance shipped as `lt -19` and reported PASS on every run, because `whyUnusable`
never asked the validator anything.

**Server KPIs go through the same class, or the identical bug recurs on the server side.** A new
check that decides whether a KPI can run belongs in `Usability`, not in a server-side validator
that findings-only consumers read. Findings do not gate.

---

## 3. Directive 3 — server KPIs need their own authoring flow

### 3.1 The absence is real

Canvas §8 is display-only: KPI board, two charts, collection status, KPI detail table. There is
**no create, edit, duplicate or delete affordance anywhere in the server design.** Every server KPI
would have to be hand-written into `report-annotations.json`. That is the same gap phase 2 existed
to close on the client side.

### 3.2 Separate flow — and the field list is why

The directive is right, and this is the concrete justification:

| | Client assert editor (shipped) | Server KPI editor (proposed) |
|---|---|---|
| Subject | target: sampler / samplers / match / overall | **source** + **query** (with `${env.*}` interpolation) |
| Metric | one of a fixed vocabulary | free-form query result + **unit** |
| Instances | n/a | **across**: max / avg / per-instance |
| Window | whole run | **scope**: pre / steadyState / cooldown / whole |
| Judgement | warn/fail bands, `gt gte lt lte` | **one of nine assert types**, each with its own parameter shape |
| Tolerance | n/a | `sustainedFor` / `breachBudget`, `warnAt` |
| Policies | n/a | `onMissing`, `onLoadShortfall` |
| Routing | n/a | `gate`, `overlayOn` |

Roughly five fields against roughly twelve, and the server side needs a **type-dependent sub-form**
(a `range` takes min+max, a `slope` takes a rate and a unit-per-time, a `ratio` takes a divisor).
One editor serving both would be a mode switch that changes most of the form — which is precisely
"too complicated".

So: a **separate slide-over**, its own module (`WIR.serverKpis`, not `WIR.asserts`), its own draft
state and localStorage key, its own unsaved pill. Shared: the slide-over shell, every `report.css`
custom property, the chip/badge/table components, and the interaction grammar — so it *feels* like
the client editor without *being* it.

### 3.3 The hard part: what live preview can honestly promise

The client editor re-evaluates live because `report.js` mirrors the Java evaluator over data
already in the payload. Server KPIs split in two:

- **Editing a band, threshold, `warnAt`, `sustainedFor`, `breachBudget`, `across` or `scope`
  → fully live.** The *series* is snapshotted into the payload at generation time, so every one of
  the nine reducers can re-run in the browser. Drag-to-retune works on server overlay charts
  exactly as it does on client charts.
- **Editing the `source`, the `query`, or anything upstream of collection → cannot be evaluated.**
  A new query needs a new collection run. The editor must **withhold the verdict** and mark the KPI
  `needs re-collection`, not display a stale number next to a new query.

That boundary is a UI state, and it is the single most important thing the server editor gets right
or wrong. A stale verdict beside an edited query is worse than no verdict — it is the server-side
version of the silent-pass failures the whole A-series was about.

### 3.4 Surfaces

- **Server tab toolbar** — mirrors the Asserts tab: verdict badges (one per gate), counts, source
  and gate filters, status filter, `+ Add KPI`, `Generate from sources`, Save menu, unsaved pill.
- **`+ Add KPI` slide-over** — the separate editor above, with a **Validate query** button that
  reports match count and coverage against the *collected* payload, and says plainly when it cannot.
- **Generate from sources** — the analogue of generate-from-run: for each collected series with no
  KPI, offer a pack-default band seeded from the observed value. This is what makes packs usable
  without hand-writing PromQL.
- **Chart authoring** — `+ Assert from chart` on server overlay charts, seeded with source, metric
  and the clicked level. Same pattern as the client charts, different seed.
- **Write-back** — server KPIs live under `serverMetrics.kpis` in the same `report-annotations.json`,
  so the existing whole-document write-back already covers them. One Save menu emits both halves;
  it must not grow a second "server only" copy option that can desync the file.

### 3.5 Non-negotiable, from CLAUDE.md

Any client-side reducer mirror is **a second implementation of a rule set**, exactly like
`report.js`'s copy of the assertion evaluator. It therefore needs its own parity vector file and
three suites running it (Java, node, browser) — same rule as `assert-parity-vectors.json`. Write
the vector first. Nine reducers mirrored without parity vectors is the largest single risk in this
whole design.

---

## 4. What this changes in the canvas

| Canvas section | Fate |
|---|---|
| §1 Pipeline | unchanged |
| §2 Environment resolution | unchanged |
| §3 Windows & the wait | unchanged |
| §4 Alignment & resampling | unchanged |
| §5 KPI assertion model | **revised** — `onMissing` values become the reason vocabulary of §2.3; `gate` becomes a declared name |
| §6 KPI packs | unchanged (packs gain a default gate name) |
| §7 Three gates, three verdicts | **replaced** by §1 — N gates, no combined, INCONCLUSIVE retired |
| §8 The Server tab | **extended** by §3 — display-only becomes authoring |
| §9 Decisions / open | six open questions still stand, see §5 |

Two mechanical notes on the canvas file itself: it loads **React 18.3.1 from unpkg.com** at
runtime (`support.js:1073`), so it needs network to render; and it references
`assets/echarts.min.js`, a directory that **does not exist** in the handoff folder, so §8's two
charts cannot draw. Both need fixing before the canvas can be reviewed visually or updated to
match this document.

---

## 5. Open decisions

1. **Default gate grouping.** A KPI with no `gate`: one gate per *source* (the directive's reading),
   or a single gate named `server` until the user declares otherwise? Per-source is more literal;
   single-until-declared is a gentler default and avoids a report with five gates nobody asked for.
   *Recommendation: single `server` gate by default, per-source available by declaring it.*
2. **`LOAD_NOT_ACHIEVED` and the exit code.** Not a config error, not a verdict. Options: fold into
   `failonerror`, fold into `failonassert`, or its own `failonloadshortfall`. *Recommendation:
   `failonerror`, because the check the file asked for genuinely did not run.*
3. **Header badge with N gates.** One client badge plus N server badges gets crowded past about
   three. Options: client + a server rollup (worst across server gates) in the header with the
   per-gate breakdown in the tab, or all badges always. *Recommendation: client + server rollup —
   note this is a display rollup, not a combined verdict, and it is never written to the JSON.*
4. **The six questions the canvas left open** are untouched by this revision and still block
   implementation: baseline payload retention, Grafana as a single proxy source, payload size cap
   (~10 MB for 2 h × 30 metrics × 20 instances), run tagging vs time-window correlation, which
   clock defines the window under distributed generators, and who owns the packs.

Nothing here is implemented. This document is a proposal for the shape, not a work plan — a
milestone breakdown in the style of `asserts-delivery.md` comes after the decisions in §5 land.
