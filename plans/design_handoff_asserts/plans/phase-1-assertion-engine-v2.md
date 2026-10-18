# Phase 1 — Assertion engine v2

**Status:** design final. Supersedes `phase-1-assertion-rules-plan.md`.
**Design artifacts:** `Asserts Spec.dc.html` (schema + semantics), `Asserts Prototype.dc.html` (phase-2 target UI; its logic class is the reference evaluation algorithm).

---

## 1. Goal

A tester declares exactly which checks decide a run, each with its own pass/warn/fail
criteria, in `report-annotations.json`. Only what is declared is judged. The canonical check:

> "p90 of `POST /checkout` must stay under 1500ms. Nothing else decides whether this run passed."

## 2. Decisions (all closed)

| # | Decision | Choice | Why |
|---|---|---|---|
| D-A | Engine architecture | **One engine.** One rule model, one evaluator, one place a run is judged. | Phase 2 must display and edit ONE rule model. Two engines means every GUI surface (list, editor, chart lines, JSON, JUnit) is built twice. |
| D-B | Pattern syntax | **Glob default, `regex:` prefix opt-in**, plus `exclude` list. | `POST /api/*` is what testers type; regex remains the ceiling. `exclude` keeps a blanket pattern off the samplers that carry their own rule. |
| D-C | Percentiles | **Any `pN` / `pN.N`** (p50–p99.99) per-sampler and overall. | The t-digest already computes arbitrary quantiles; refusing `p99.9` in a tail-latency tool is self-inflicted. Report payload carries the standard grid (p50 p75 p90 p95 p99 p99.9) + every percentile named by a rule, so phase-2 live re-eval always has the number. |
| D-D | `onMissing` | Per rule: `fail` \| `warn` \| `skip`. **Default `fail`.** | A gate that never ran must not pass silently — same logic as a JUnit test that didn't execute. |
| D-E | `apdexThreshold` | Stays inert, but now **reported** as an ignored key by validation (log WARN + report notice). | Doesn't flip green pipelines red; stops being silent. |
| D-F | Beyond the draft schema | **`observe: true`** rules (track, never gate), **`enabled: false`** (park a rule), **`tags`** (+ `wir.asserts.tags` property to select rule sets, e.g. smoke vs full). | All three are converging-on-thresholds workflow, not decoration. Named threshold profiles: rejected for v2 (indirection before there's demand). Cross-sampler expressions, group aggregation, baseline-aware rules: future. |
| D-G | Run-level metric set | Everything the data has: `totalSamples errorRate duration maxThreads mean median p90 p95 p99 pN.N throughput apdex`. | Merged-distribution percentiles already exist in `ReportData`; no reason to hide `median`/`p90`. |
| D-H | JUnit text | Byte-identical to today. New rules add new testcases (including run-level). | Compatibility is a test, not an aspiration. |
| D-I | Config errors | **Loud but non-fatal**: report still generates; invalid rules render as INVALID, count as FAIL in the gate, log ERROR. | The report is the artifact you need in order to debug the config. |

## 3. Schema

New top-level `assertions` array in `report-annotations.json`. Full annotated reference in
`Asserts Spec.dc.html`; summary:

```json
{
  "$schema": "https://…/report-annotations.schema.json",
  "assertions": [
    {
      "id": "checkout-p95",
      "description": "Payment gateway limit from contract §4.2",
      "target": { "sampler": "POST /api/checkout" },
      "metric": "p95",
      "warn": { "gt": 650 },
      "fail": { "gt": 800 },
      "onMissing": "fail",
      "enabled": true,
      "tags": ["smoke", "full"]
    },
    { "id": "api-errors", "target": { "match": "POST /api/*", "exclude": ["POST /api/health-probe"] },
      "metric": "errorRate", "fail": { "gt": 5 } },
    { "id": "run-throughput", "target": { "scope": "overall" }, "metric": "throughput", "fail": { "lt": 30 } },
    { "id": "search-tail", "target": { "match": "GET /search*" }, "metric": "p99.9", "observe": true }
  ]
}
```

**Fields**

| Field | Req | Notes |
|---|---|---|
| `id` | no | Unique. Auto-derived `slug(target)-metric` when omitted; appears in CI/CD JSON, JUnit testcase names, error messages. |
| `target` | yes | Exactly one of: `{"sampler": s}` · `{"samplers": [s…]}` · `{"match": glob-or-"regex:…", "exclude": [glob…]?}` · `{"scope": "overall"}`. |
| `metric` | yes | §4. Case-insensitive. |
| `fail`, `warn` | ≥1 unless `observe` | Predicate objects. `fail` tested first. Omit `warn` ⇒ no warn band. |
| `observe` | no | `true` ⇒ no bands allowed; value is reported, never gates. |
| `onMissing` | no | `fail` (default) \| `warn` \| `skip`. |
| `enabled` | no | `false` ⇒ rule skipped, listed as DISABLED. Default `true`. |
| `tags` | no | Strings. Runtime selection: `-Jwir.asserts.tags=smoke,ci` runs rules having any listed tag **plus untagged rules**; property absent ⇒ all. |
| `description` | no | Carried into failure messages and the report. |

**Predicates:** `{"gt": n}` `{"gte": n}` `{"lt": n}` `{"lte": n}` `{"outside": [lo, hi]}` — exactly
one key per band. The tester writes the direction; the engine has no per-metric direction table.
(`outside` replaces the draft's `between`, which read backwards: "fail between 40 and 80" meant
the opposite of what it said.)

## 4. Metric vocabulary

Assertable = present in the payload. Units are fixed per metric (ms, %, /s, count, s, score).

- **per-sampler:** `sampleCount errorCount errorRate mean median min max stdDev throughput apdex` + `pN[.N]`
- **overall:** `totalSamples errorRate duration maxThreads mean median throughput apdex` + `pN[.N]`

`p95` ≡ `p95.0`; `median` ≡ `p50`. `apdex` is computed against the report's configured apdex
threshold (default 500 ms) — an apdex assertion asserts that configuration too, so the report
surfaces T next to any apdex rule. Percentile grid emitted in the payload: p50 p75 p90 p95 p99
p99.9 + every percentile any rule names.

## 5. Evaluation semantics

1. Rules with `enabled:false` or deselected by tags → skipped (DISABLED), never affect the verdict.
2. `target` resolves to matched samplers. **Reach:** naming a sampler (`sampler`/`samplers`)
   reaches transaction children and hidden rows — naming is an explicit request. Transaction
   controller parent rows are ordinary targets. `match` patterns see only top-level, non-hidden
   samplers. `scope:"overall"` uses run-level stats.
3. No match — or a matched row with **zero samples** (latency metrics undefined; a plan entry
   that never ran must not pass silently) — routes through `onMissing` (UNMATCHED status mapped
   to fail/warn/skip).
4. Per matched sampler: `fail` predicate first, then `warn`, else PASS. Rule status = worst match;
   every match is kept in the output, passing ones included.
5. `observe` rules record the value with status OBSERVED; excluded from the gate.
6. **Run verdict = worst status across evaluated rules.** Nothing undeclared is ever judged.
   No rules at all ⇒ `assertsEvaluated:false` as today.

Statuses: `PASS < OBSERVED/DISABLED (neutral) < WARN < FAIL`; INVALID counts as FAIL (D-I).

## 6. Outputs

- **CI/CD JSON** — existing keys untouched; add `gate` `{status, declared, evaluated, passed,
  warned, failed, unmatched, observed, disabled, invalid}` and `assertions[]` `{id, description?,
  origin, target, metric, unit, status, onMissing?, matched, results[]: {sampler, actual,
  threshold, predicate, status}}`. `assertions[]` is emitted in declaration order
  (diff-friendly). Pipelines assert `jq -e '.gate.failed == 0'`. The plugin never alters
  JMeter's exit code — gating is the pipeline's read of these artifacts.
- **JUnit XML** — one testcase per rule (run-level included); failure text carries metric, actual,
  predicate, threshold, matched sampler. Pre-existing cases keep today's text (D-H).
- **HTML** — combined verdict drives the existing badges; the payload embeds the full annotations
  document + declared rules + per-rule results + metric vocabulary (phase 2 consumes these; ship
  them now so phase 2 is UI-only).
- **Log** — one WARN per validation finding; one INFO summarizing the gate.

## 7. Validation & discoverability

- **JSON Schema** shipped in the JAR (allowlisted resource path), published, referenced via
  `$schema` → completion + squiggles in IDEs. Loader validation stays hand-written (no new
  dependency past the shading allowlist).
- Load-time messages name the rule and the fix: unknown metric (with nearest-name suggestion),
  bad glob/regex (with position), band with no/multiple operators, `observe` + bands, an
  **unreachable warn band** (warn ≥ fail for gt/gte, warn ≤ fail for lt/lte — fail is tested
  first, so warn can never fire; almost always swapped values, and the message says so;
  warning-level finding since verdicts stay correct),
  non-numeric threshold, `outside` not `[lo, hi]`, duplicate id, unknown keys (incl.
  `apdexThreshold`, D-E), and — highest value — **unmatched target, listing the sampler names the
  run actually contained**.
- Findings surface in all three places: log, CI/CD JSON (`gate.invalid` + per-rule status),
  report validation notice.

## 8. Deliverables

| | |
|---|---|
| New | `core/assert/`: `AssertionRule`, `Target`, `Predicate`, `MetricRef` (name→value resolver, unit table), `AssertionEvaluator`, `AssertionValidator`, `report-annotations.schema.json` |
| Changed | `ReportAnnotations` (+`assertions`, keep unknown-key capture for round-trip), generator (evaluate → verdict), `CiCdJsonSerializer` (+`gate`, `assertions`), `JunitXmlWriter` (+rule testcases), HTML payload (+rules/results/vocabulary/document), `AnnotationLoader` (validation pass) |
| Docs | `WIKI/Tutorial/10-Asserts-Apdex.md` rewrite, `11-Export-Integration.md` (gate block), schema URL |

## 9. Test plan

- **Compatibility** — every existing annotation fixture: identical verdicts, identical
  CI/CD JSON keys/values, identical JUnit bytes, before/after the engine swap.
- **Engine** — each predicate; fail-before-warn; no-warn-band; every target form; glob/regex/
  exclude; reach rules; zero-sample matched row → onMissing; onMissing ×3; observe; disabled;
  tags selection; worst-of; INVALID→FAIL.
- **Percentiles** — pN parsing (p95 ≡ p95.0, median ≡ p50), payload grid + rule-named quantiles.
- **Validation** — one test per finding class, message text asserted.
- **End-to-end** — `jmeter -g` with a rules file → assert JSON, JUnit, log lines.

## 10. Not in this phase

Report UI for asserts (phase 2). Time-windowed / sustained (`over`) clauses — every metric in
phases 1–2 judges the whole-run aggregate, deterministic post-run; duration semantics stay a
phase-3 option alongside server metrics (`plans/phase-3-chart-and-server-asserts.md`).
Group-aggregate stats (`combined errorRate of matched set`),
cross-sampler expressions, baseline/comparison-aware rules, named threshold profiles.
