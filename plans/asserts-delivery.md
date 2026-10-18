# Asserts — delivery plan

> **Delivered.** All 13 milestones merged to local main. Milestone status is the checklist below — update it as each
> branch merges to local `main`.
> Source design: `plans/design_handoff_asserts/`

## Progress

| | Milestone | Status |
|---|---|---|
| M0 | Output baseline for the engine swap | ✅ done — 312 tests green |
| M1 | Percentile plumbing + OverallMetrics | ✅ done — any quantile resolvable, 331 tests green |
| M2 | `core/assertion` engine | ✅ done — 38 parity vectors + 31 unit tests, 400 green |
| M3 | One evaluator | ✅ done — every output byte-identical against the M0 baseline |
| M4 | Schema + loader validation | ✅ done — assertions now gate a real run; 421 tests green |
| M5 | Gate outputs + E25 fixture | ✅ done — gate block, additive JUnit suite, E25 covers every state |
| M6 | Phase-2 payload + gate badges | ✅ done — **PHASE 1 COMPLETE**; payload carries rules/results/vocabulary/document |
| M7 | Asserts tab (read-only) | ✅ done — tab + gate strip, 18 specs, 297 browser tests |
| M8 | Client evaluator + editor | ✅ done — 38 parity cases green in-browser; live preview; 355 browser tests |
| M9 | Drafts + write-back | ✅ done — round-trip + next-run fidelity verified through jmeter -g |
| M10 | Seeding entry points | ✅ done — ⊕ cells, judging-rules detail, generate-from-run wizard |
| M11 | Chart integration | ✅ done — **PHASE 2 COMPLETE**; lines are rules, draggable, placeable |
| M12 | Documentation | ✅ done — **FEATURE COMPLETE** |

## Context

The handoff is a closed design for a declarative quality gate: a tester declares in
`report-annotations.json` exactly which checks decide a run — any metric, any
percentile, per sampler / group / whole run, each with its own warn/fail bands — and the
report shows every rule with its verdict, lets the tester tune limits against observed values
with live re-evaluation, and saves the updated file for the next run.

The report displays 13+ metrics per sampler plus run-level stats, almost none of it gateable.
Converging on a threshold currently takes three runs because every adjustment is a hand-edit
against numbers read off a screen.

The handoff ships as three phases. This document is the executable delivery plan for
**phases 1 and 2**; phase 3 (chart-authored server-metric asserts, `over` clauses) stays a
direction note and is explicitly out.

**Answer to "is it doable?": yes.** Thirteen milestones (M0–M12), each its own branch, each
with a test gate that must be green before it merges to **local** `main`. Nothing reaches
`origin/main` without explicit approval — see *Part 3*.

---

## Part 1 — Review of the handoff plan

The design is unusually well-specified and I am not proposing to relitigate its product
decisions. What follows is what does not survive contact with this codebase, ordered by how
much work it moves.

### R1 — D-C (any percentile) has no data behind it *(BLOCKING — resolved, see D3)*

Phase 1 §3 D-C says "the t-digest already computes arbitrary quantiles". True during
aggregation, false afterwards. `SamplerAggregator` (`core/stats/SamplerAggregator.java:120-125`)
reads exactly four quantiles out of the digest and throws it away; `SamplerStatistics` carries
`medianResponseTime`, `percentile90/95/99` and nothing else; `ReportData` carries
`overallMedian/P90/P95/P99` and nothing else. There is no p75, no p99.9, no p99.99 anywhere in
the model — yet p99.9 appears in the schema example, in the prototype's seed data, and in the
payload grid the README promises.

Worse for the "every percentile a rule names" clause: annotations are loaded at
`WebInsightReportGenerator.generate()` step 1, i.e. **after** `ReportData` is fully built in
both ingestion paths. There is no ordering the promise can hang on.

### R2 — D-H and §6 contradict each other on JUnit *(BLOCKING — resolved, see D2)*

D-H: "JUnit text: byte-identical to today." §6: "one testcase per rule". Today
`JunitXmlWriter` emits one `<testcase>` per **top-level sampler** — including samplers with no
threshold at all, which appear as passing tests — and one combined `<failure>` aggregating
p95/p99/mean/errorRate. Rule-shaped output changes `tests=`, every testcase name and every
failure body. Both statements cannot hold.

### R3 — The reference implementation is incomplete

The handoff says "port it, don't reinvent it" of the prototype's logic class. Two holes:

- `band()` (`Asserts Prototype.dc.html:650`) implements `gt/gte/lt/lte` only. **`outside`
  is not implemented** — the spec's headline replacement for the draft's `between`.
- `evalRule()` seeds worst-of from `matches[0]` and never checks `actual === undefined`. An
  unknown metric yields `undefined`, `band()` returns `false`, and the rule reports **PASS**.
  A typo'd metric name silently passes the gate — the exact failure class the design says it
  exists to kill.

The Java engine is normative; the JS must be brought up to it, not the other way round.

### R4 — `observe` + `onMissing` can fail a build from a rule that "never gates"

An `observe: true` rule whose target matches nothing routes through `onMissing`, whose default
is `fail`. "Track, never gate" then fails the build. Unspecified in the plan; the prototype
inherits the bug. **Proposed: `observe` ⇒ unmatched is always neutral (OBSERVED), `onMissing`
ignored** (recorded as D4).

### R5 — Overall metric vocabulary is partly fictional

Phase 1 §4 lists `totalSamples errorRate duration maxThreads mean median throughput apdex` +
`pN.N` for `scope: "overall"`. In the model: `duration`/`maxThreads`/`totalSamples`/`errorRate`
come from `TestMetadata`; overall percentiles exist for 50/90/95/99 only (R1); **overall apdex
does not exist on `ReportData` at all** — it is computed inline in
`ThymeleafReportRenderer:195-201` as a sample-count-weighted mean and never leaves the template
context; overall `throughput` likewise has no model field. Asserting on numbers the CI JSON and
the HTML compute independently is how they drift. These need one shared resolver.

### R6 — `apdex` asserts a configuration that is not in the payload

§4: "an apdex assertion asserts that configuration too, so the report surfaces T next to any
apdex rule." T lives in `ReportConfiguration`/`SamplerAggregator`; it is nowhere in
`buildChartDataMap`. New payload field. Also apdex is rounded to 2 dp at aggregation
(`SamplerAggregator:183`), so band seeding must not assume more precision than that.

### R7 — Payload size is unbudgeted

Phase 2 §7 wants the payload to carry the whole annotations document + declared rules +
per-rule results **with every match, passing ones included** + the metric vocabulary. A
500-sampler run with twenty `match: "*"` rules is 10 000 result objects. The external-data
threshold is 10 MB (`ReportConfiguration:23`); crossing it flips the report to
`web-insight-data.js`, a different code path with its own fixtures (E19/E20). Needs a cap
decision, and a rules-bearing fixture should exercise the external path.

### R8 — Two evaluators need a parity *mechanism*, not a promise

Phase 2's guarantee (2) — "a saved file fed to `jmeter -g` reproduces the previewed verdicts" —
is stated as an end-to-end test. Two independently-written evaluators drift between the runs
that test them. Proposed: one checked-in table of vectors (`assert-parity-vectors.json`:
rules × sampler stats × expected statuses) executed by **both** the JUnit suite and a
Playwright spec. Parity becomes a fixture, not an aspiration.

### R9 — Integration surfaces the handoff assumes are client-side are not

CLAUDE.md says the sampler table is built by `report.js`. It is not: it is Thymeleaf
(`templates/report.html:392-427`), and its mini badge is driven server-side off the
server-computed results. README §4 wants a ⊕ button on every numeric metric cell (13 columns ×
N rows) and a badge whose verdict changes live as drafts are edited. That badge must become
client-updatable. Same for the summary-card mini badges (`report.html:159-173`), which are
Thymeleaf expressions too.

### R10 — `report.js` is 5 862 lines, flat, no module boundary

Phase 2 adds roughly 2 000 more lines of stateful UI. A bundler is a non-starter (the
self-contained-file promise, and the inlining is `th:utext` of a raw file). Proposed: all new
code in one `WIR.asserts` IIFE appended at the end of the file, exposing only what the
template's inline handlers need; `report.css` gets one fenced section. No new top-level
`function` declarations.

### R11 — Repo bookkeeping the plan does not mention

- A new fixture must be added to **four** places, not two: `run-all-tests.sh` (`run_test` line
  *and* `should_run` tier), `FIXTURE_IDS` in `scripts/lib/common.sh:464`, `REQUIRED_FIXTURES`
  in `test-plans/playwright/global-setup.ts:33`, plus the tier prose in CLAUDE.md.
  `verify_fixture_lists` only checks two of them.
- `report-annotations.schema.json` must ship under an allowlisted JAR root —
  `ALLOWED_JAR_ROOTS` is `^(com/jmeterwebinsightreport/|static/|templates/|META-INF/)`
  (`common.sh:249`). A `schema/` directory at the JAR root fails the build.
- Every new text file is LF or S03/R03 fails with `[EOL]`.
- The fixture stamp hashes every file under both modules' `src/main`, so **every** milestone
  that touches Java or the report resources invalidates all fixtures — budget ~5 min for
  regression on those, ~2 min otherwise.

### R12 — Existing specs that will need updating (scope, not bugs)

`asserts-apdex.spec.ts`, `asserts-chart-lines.spec.ts`, `header-global.spec.ts`,
`summary-tab.spec.ts` assert current badge text, the mini-badge classes and the chart mark-line
behaviour. `tab-navigation.spec.ts` enumerates tabs. `xss-escaping.spec.ts` gets new cases.

### R13 — `core/assert/` cannot compile, and an abandoned attempt already proved it

The handoff's §8 deliverables table names the new package `core/assert/`. `assert` has been a
Java keyword since 1.4, so `package com.jmeterwebinsightreport.core.assert;` is a syntax
error. The package is **`core/assertion/`**.

Found the hard way, and worth recording: `run-sanity.sh`'s fixture-stamp comparison at M0
listed files that do not exist in this tree —
`core/assertion/{AssertionEvaluator,AssertionOutcome,AssertionReport,AssertionRule,AssertionTarget,AssertionValidator,Comparison,MetricCatalog}.java`,
`report/annotation/AssertionRules.java`, and `static/schema/report-annotations.schema.json`.
The reflog shows a `feat/assertion-rules` branch checked out on 2026-07-31 and left at the
same commit as `main`; no dangling commit contains any of it. So an earlier attempt wrote
that engine into the working tree, built it — which is what stamped the fixtures — and was
discarded uncommitted. **The code is gone; only the stamp remembers it.**

Two things follow. The `test-results/` fixtures on this machine were generated from a build
that no longer exists in any branch, so they were already stale before this work started and
must be regenerated regardless of what M0 touched. And the earlier attempt independently
landed on `core/assertion/` and on `static/schema/` for the JSON Schema — the same resource
root R11 arrives at from `ALLOWED_JAR_ROOTS`, which is mild corroboration that the path is
right.

### What I am *not* flagging

The product decisions (D-A…D-I), glob-vs-regex, `outside` replacing `between`, the draft/save
model, the `file://` save constraint, the design tokens (all verified present in `report.css`)
and the phase split are sound and I would not change them.

---

## Part 2 — Decisions taken

| # | Decision | Rationale |
|---|---|---|
| D1 | **Scope: phases 1 + 2, sequenced across 13 milestones (M0–M12).** Phase 3 out. | One doc as the goal; branches merged to local main behind test gates. |
| D2 | **JUnit: additive.** The existing `<testsuite>` stays byte-identical (same `tests=`, names, failure text); a second `<testsuite name="assertions">` carries one testcase per declared rule, run-level included. | Makes D-H and §6 both true. Existing pipelines keep working; rules still get first-class testcases. *(agreed)* |
| D3 | **Percentiles: retain the digests.** Per-sampler and merged `TDigestPercentileCalculator`s stay reachable from `ReportData` through a side channel excluded from JSON; the metric resolver answers any quantile at evaluation time. | Honours D-C exactly with no initialisation-order change in either ingestion path. Memory: one MergingDigest (compression 100) per sampler, single-digit KB. *(agreed)* |
| D4 | **`observe` ⇒ unmatched is neutral**, `onMissing` ignored for observe rules. | "Never gates" must mean never gates (R4). |
| D5 | **One shared `OverallMetrics` resolver in core** feeds HTML, CI/CD JSON, JUnit and the payload. | Kills the drift risk in R5; moves the weighted-apdex computation out of the renderer. |
| D6 | **Per-rule results in the payload are capped** (default 200 matches per rule, `matchedTotal` carried alongside, "and N more" in the UI). Cap is a constant, logged when it bites. | R7 — bounded, never silent. |
| D7 | **`assert-parity-vectors.json` is the parity contract**, executed by JUnit and by Playwright. | R8. |
| D8 | **All phase-2 JS lives in one `WIR.asserts` IIFE**; new CSS in one fenced section. | R10. |
| D9 | **The Asserts tab sits last-but-one — after Compare, before Notes**, not after Summary as the handoff says. | Existing users keep the muscle memory of Summary / Timeline / Samplers / Errors in the first four positions. The gate is still the headline: the Summary gate strip is the first element on the first tab, and it links straight through. |
| D10 | **Documentation is its own final milestone (M12), after all implementation.** Only the bookkeeping the test harness *needs* to run (`FIXTURE_IDS`, `REQUIRED_FIXTURES`, `run-all-tests.sh` tiers, CLAUDE.md tier prose) stays in the milestone that introduces it. | Prose written against an implementation that is still moving is prose rewritten twice. Screenshots especially — they are captured from a built report. |

---

## Part 3 — Branch & merge protocol

- One branch per milestone.
- A milestone merges into **local `main`** only after its gate is green, `--no-ff` so each
  milestone stays a reviewable unit.
- **Nothing is pushed to `origin/main`, ever, without explicit approval in that message.**
  The `pre-push` hook already refuses direct pushes to the primary branch; local merges do not
  trip it.
- Consequence to be aware of: local `main` will run ahead of `origin/main`, and when it is time
  to publish it goes as a **pull request from a branch**, per the project rule — not as a
  `git push` of the accumulated local main.
- The `run-tests` skill runs the stated gate before each merge. No merge on a red gate.

---

## Part 4 — Milestones

### Phase 1 — engine

| # | Deliverable | Gate |
|---|---|---|
| **M0** | **Output baseline, captured on the current code, no behaviour change.** A deterministic `ReportData` from a checked-in JTL (`jmeter-report-core/src/test/resources/jtl/`) driven through `CiCdJsonSerializer` / `JunitXmlWriter` for each annotation fixture, recorded so the engine swap in M3 has something to be compared against. `generatedAt` / `reportGeneratedAt` normalised before comparison. | `mvn test`, `run-sanity.sh` |
| **M1** | Percentile plumbing (D3): `PercentileSource` in `core/stats`, digests retained on `ReportData` and excluded from Jackson; `OverallMetrics` resolver (D5) replacing the inline weighted apdex in `ThymeleafReportRenderer:195`; payload gains p75/p99.9 per sampler, a full `overall` block (throughput, apdex, errorRate, duration, maxThreads, totalSamples) and the apdex T (R6). | `mvn test`, `run-regression.sh` |
| **M2** | `core/assertion/`: `AssertionRule`, `Target`, `Predicate` (incl. `outside`, R3), `MetricRef` (name→value + unit table, `p95≡p95.0`, `median≡p50`, unknown metric ⇒ INVALID not PASS), `AssertionStatus`, `RuleResult`, `GateSummary`, `AssertionEvaluator`, `AssertionValidator`. Pure library, no wiring. `assert-parity-vectors.json` authored here (D7). | `mvn test` |
| **M3** | The generator and the renderer fold onto the one engine; the second evaluator is deleted, so a run is judged in exactly one place. | `mvn test` **with the M0 baseline byte-identical**, `run-regression.sh` |
| **M4** | `assertions` on `ReportAnnotations` with `@JsonAnySetter`/`@JsonAnyGetter` unknown-key capture (round-trip; phase 2 needs it); `report-annotations.schema.json` shipped under `static/schema/` (R11); loader validation pass with the full finding set — nearest-name metric suggestion, unmatched target listing the sampler names the run actually contained, bad glob/regex with position, band arity, `observe`+bands, unreachable warn band, duplicate id, unknown keys incl. `apdexThreshold` (D-E). | `mvn test`, `run-sanity.sh` |
| **M5** | `gate` block + `assertions[]` in the CI/CD JSON; second `<testsuite name="assertions">` in the JUnit XML (D2); `-Jwir.asserts.tags` selection; INVALID⇒FAIL. New fixture **E25 "Assertion Rules"** wired into all four places (R11) with a new `test-plans/annotations/asserts.json`. | `mvn test`, `run-all-tests.sh`, `run-regression.sh` |
| **M6** | Payload carries declared rules, per-rule results (capped, D6), metric vocabulary, percentile grid + rule-named quantiles, and the whole annotations document. Header badge / summary mini-badges / sampler badges driven by the gate. *(Prose docs deferred to M12 per D10; only harness bookkeeping lands here.)* | `mvn test`, `run-all-tests.sh`, `run-regression.sh`, `run-config-tests.sh`, `run-error-tests.sh` |

**Phase 1 done.** `jmeter -g` gates on declared rules, pipelines can `jq -e '.gate.failed == 0'`,
and the report UI is unchanged except that the badge tells the truth about a richer gate.

### Phase 2 — GUI

| # | Deliverable | Gate |
|---|---|---|
| **M7** | Summary gate strip; new **Asserts** tab (after Compare, before Notes — D9) read-only: toolbar with verdict/counts/tag+status filter/text search; rules table sorted worst-first with expandable matches; UNMATCHED rows with did-you-mean; DISABLED dimming; OBSERVED rows; empty state. All styling through `report.css` custom properties; `WIR.asserts` IIFE (D8). | `run-regression.sh` + new `asserts-tab.spec.ts` |
| **M8** | Client evaluator (parity-vector-tested, D7/R3); slide-over editor: target segmented control with live match preview, scope-filtered metric select with percentile grid, warn/fail bands incl. `outside` two-input form, observe toggle, meta grid, inline validation (duplicate id, no bands, dead warn band, bad pattern), **Preview — unsaved** live re-evaluation. | `run-regression.sh` + `asserts-editor.spec.ts` + parity spec |
| **M9** | Draft model (NEW/EDITED tags, unsaved pill, "verdict if saved", localStorage keyed by `RunIdentity`, discard); whole-document write-back (Download / Copy file / Copy assertions only) preserving notes, markers, sampler notes, comparison thresholds and unknown keys. | `run-regression.sh` + `asserts-save.spec.ts` + end-to-end "download → `jmeter -g` → same verdicts" |
| **M10** | ⊕ on every numeric metric cell (R9 — client-injected, not 13 more Thymeleaf columns); sampler mini-badge → judging-rules detail row, client-updatable under drafts; nice-number band seeding with direction flip for throughput/apdex; **Generate from run** wizard with live preview. | `run-regression.sh` + `asserts-seeding.spec.ts` |
| **M11** | Rule `markLine`s replacing the static threshold lines, strictest emphasised and the rest in the tooltip; run-aggregate marker; ECharts `graphic` drag handles → draft band edit with live verdict; **+ Assert from chart** crosshair placement seeding the editor. | `run-regression.sh` + `asserts-charts.spec.ts`, existing `asserts-chart-lines.spec.ts` updated |

### Documentation — last, deliberately (D10)

| # | Deliverable | Gate |
|---|---|---|
| **M12** | Every doc surface below, written against the implementation as it actually shipped, plus fresh screenshots. | `run-sanity.sh` (S03 covers `verify_line_endings`), docsify site renders, no dead links |

Held to the end because prose written against a moving implementation is prose written twice —
and the screenshots are captured from a built report, so they can only be taken once the UI is
final. The only doc-shaped work that does **not** wait is the harness bookkeeping the tests
need in order to run at all (`FIXTURE_IDS`, `REQUIRED_FIXTURES`, `run-all-tests.sh` tiers,
CLAUDE.md tier prose); that lands in M5 with the fixture it describes.

**Surfaces, all of which currently describe the old gate:**

*New page*
- `WIKI/Tutorial/12-Asserts-Tab.md` — the new tab, editor, seeding, wizard, drafts, save model.
  **Filename numbering:** the tab sits before Notes in the UI (D9), but the tutorial files are
  numbered 02–11 and Notes is `07`. Inserting there would cascade `07→08 … 11→12` and break every
  inbound link, the sidebar and the screenshot script. So the file takes the next free number and
  **`_sidebar.md` owns the reading order** — the number is a filename, not a position.

*Rewritten*
- `WIKI/Tutorial/10-Asserts-Apdex.md` — full rewrite. Rules replace thresholds; the `assertions`
  schema, targets, metric vocabulary, predicates, `observe`/`enabled`/`tags`/`onMissing`.
- `WIKI/Tutorial/11-Export-Integration.md` — the `gate` block, `assertions[]`,
  `jq -e '.gate.failed == 0'`, and the second `<testsuite name="assertions">` (D2) alongside the
  unchanged first one.

*Amended*
- `WIKI/_sidebar.md` — new entry under **Report Tabs**, placed before Notes.
- `WIKI/Tutorial/01-Overview.md` — the tab list (six → seven).
- `WIKI/Tutorial/02-Summary-Tab.md` — the gate strip as the first element of the tab.
- `WIKI/Tutorial/03-Timeline-Tab.md` — threshold lines are rules now, and draggable.
- `WIKI/Tutorial/04-Samplers-Tab.md` — what the mini badge means now, and the ⊕ cell buttons.
- `WIKI/Tutorial/07-Notes-Tab.md` — "Download annotations" now shares the whole-document
  write-back model with the asserts tab.
- `WIKI/Tutorial/README.md` — reading order.
- `WIKI/FEATURES.md` — new E-numbers (this file is referenced by test names, so the numbers are
  a contract, not decoration).
- `WIKI/Instructions.md` — E25 in the execution matrix.
- `WIKI/TEST-COVERAGE-GAPS.md` — retire what the new specs now cover.
- `README.md` — the annotations section (currently documents four metrics and a `default`
  fallback), the property table (`webinsight.report.annotations` description, new
  `wir.asserts.tags`), and the CI/CD JSON sample (plus `gate`).
- `CLAUDE.md` — "Six tabs" → seven; the rendering split (the sampler table is Thymeleaf, not
  `report.js` — R9, wrong today); the new `core/assertion/` package in Architecture.

*Regenerated*
- `WIKI/Tutorial/take-screenshots.ts` + `WIKI/Tutorial/images/` — new captures for the gate
  strip, the asserts tab, the editor slide-over, the wizard and a dragged chart threshold; plus
  re-captures of any existing shot whose tab bar or sampler badge changed.

*Published*
- The `$schema` URL referenced by `report-annotations.json` must resolve to the schema shipped
  in M4, or `$schema` is decoration and IDE completion never works.

---

## Part 5 — Testing strategy

Two kinds of test at every milestone.

**Progression — proves the new feature works**
- Java: one test class per new `core/assertion/` type; predicates incl. `outside`; fail-before-warn;
  no-warn-band; every target form; glob/regex/exclude; reach rules (transaction children,
  hidden samplers); zero-sample match → `onMissing`; `onMissing` ×3; observe (incl. D4);
  disabled; tag selection; worst-of; INVALID⇒FAIL; pN parsing (`p95≡p95.0`, `median≡p50`);
  one test per validation finding with the message text asserted.
- Playwright: one spec per GUI milestone (M7–M11), listed above.
- End-to-end: `jmeter -g` over the E25 fixture asserting the `gate` block, the assertions
  testsuite and the log lines.

**Regression — proves nothing else moved**
- **M0's recorded outputs are the spine.** Every milestone from M3 on re-runs them; the
  pre-existing `<testsuite>` and every pre-existing CI/CD JSON key must stay byte-identical.
- `mvn test` — the current 290 tests stay green at every milestone.
- `run-regression.sh` — 279 browser tests against regenerated fixtures. Because the stamp
  hashes all of `src/main`, every Java or resource change forces regeneration; that is the
  point, and the ~5 min is the cost of the fixtures meaning anything.
- `run-all-tests.sh` at M5, M6 and before the phase-2 merge — the manual-tier scenarios
  (E01, E03, E04, E13, E15, E20, E22, E24) plus the new E25.
- `run-config-tests.sh` / `run-error-tests.sh` at M6 and at the end.
- S03/R03 static checks ride along in both suites: `bash -n`, `verify_fixture_lists`,
  `verify_script_modes`, `verify_line_endings`, `verify_shaded_jar`.
- `<revision>` gets a PATCH bump on every milestone that changes plugin behaviour, and the JAR
  is redeployed so `lib/ext` holds exactly one plugin JAR.

---

## Part 6 — Verification

```bash
# per milestone
mvn test                                    # 290 + new, both modules
./test-plans/scripts/run-regression.sh      # 279 browser tests

# at M5 / M6 / end of phase 2
./test-plans/scripts/run-all-tests.sh
./test-plans/scripts/run-config-tests.sh
./test-plans/scripts/run-error-tests.sh

# the feature itself, end to end
./scripts/build-and-deploy.sh
jmeter -g test-results/E25/results.jtl -o /tmp/gate-check \
       -Jwebinsight.report.annotations=test-plans/annotations/asserts.json \
       -Jwebinsight.report.junit=true
jq '.gate' /tmp/gate-check/web-insight-report.json
# then open /tmp/gate-check/web-insight-report.html → Asserts tab
```

---

## Part 7 — Deliverable placement

Done at M0. The normative design now lives at `plans/design_handoff_asserts/` and this
file at `plans/asserts-delivery.md`, both tracked — `tmp/` is gitignored, so nothing in it
survives a clone. The originals were copied rather than moved, so anything still open from
`tmp/` keeps working; **`plans/` is the copy that gets updated from here on.**

---

## Part 8 — Open items not decided

1. **R4 / D4** — `observe` + unmatched ⇒ neutral. Taken as a decision; say the word if
   `onMissing` should apply to observe rules after all.
2. **D6 cap value** — 200 matches per rule in the payload. Bounded and logged, but arbitrary.
3. **Where the JSON Schema is published** (M12). `$schema` only earns its keep if the URL
   resolves. Options: GitHub raw on a tag, GitHub Pages next to the docsify site, or
   SchemaStore. Needs a decision before M4 writes the URL into the file.
