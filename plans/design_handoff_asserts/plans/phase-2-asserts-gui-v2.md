# Phase 2 — Asserts in the report: see, tune, save

**Status:** design final. Supersedes `phase-2-asserts-in-report-plan.md`. Requires phase 1.
**Visual/behavioral reference:** `Asserts Prototype.dc.html` — every surface below exists
there interactively; its logic class is the reference implementation of the client-side
evaluator (same algorithm as phase 1's Java engine, needed for live re-evaluation).

---

## 1. Goal

Close the loop: run → see every assert and its verdict → adjust a limit against the observed
number → verdict recomputes instantly → save the updated `report-annotations.json` → next run
gates on it. Converging on thresholds takes three runs today because each adjustment is a
hand-edit against numbers read off a screen; this makes it one.

## 2. Constraint

The report is one self-contained HTML file on `file://` — no server, no disk writes. "Save" =
**Download** (Blob + `<a download>`) and **Copy to clipboard** (mechanism already proven by Copy
Summary). Both emit the **whole annotations document** (§6). Anything needing a server: out.

## 3. Surfaces (all in the prototype)

| Surface | Content |
|---|---|
| **Summary tab — gate strip** | Verdict badge + counts (passed/warned/failed/unmatched/observed) + worst 3 breaches with actual vs limit + "Open Asserts →". The gate is the run's headline; it must not hide in a tab. |
| **Asserts tab** (new, after Summary) | Toolbar (verdict, counts, tag filter, **status filter + id/target text search** — suites grow to hundreds of rules, Add assert, Generate from run, Save menu, unsaved pill) · rules table · empty state. |
| **Rules table** | Per rule: status icon+chip, id, description, target chip (kind-labelled: sampler/pattern/overall), metric+unit, bands rendered human (`warn > 650 · fail > 800`), actual (worst match, breach highlighted), matches count. Expand → per-match rows (every match, passing included). Row actions: edit, duplicate, disable/enable, delete. Sort: worst first, then id. UNMATCHED rows render distinctly (dashed accent, "matched nothing", did-you-mean suggestion) — different problem, different fix. DISABLED rows dimmed. OBSERVED show value, no verdict. |
| **Samplers table** | Mini badge click → detail row listing every rule that judged that sampler (actual vs limit per rule). Numeric metric cells get a hover ⊕ "Assert this". |
| **Charts** | Threshold lines generalized: rules targeting the charted metric draw their `fail` band; the lines ARE the rules — **drag a line to retune** that band (draft edit, live verdict while dragging), **+ Assert from chart** places a new line at the clicked level and opens the editor seeded with it, clicking a series point seeds that sampler + metric. ECharts `graphic` drag handles on the existing markLines; no backend change. Chart-authored rules gate the whole-run aggregate in phase 2 (time-windowed clauses are phase 3 — `plans/phase-3-chart-and-server-asserts.md`). When several rules overlap, the strictest is emphasized, others listed in the tooltip. |
| **Header badge** | Unchanged — always the verdict the run was actually gated on, never the draft. |

**Theming:** every new surface styles exclusively through `report.css` custom properties
(`--color-*`, `--badge-*`) so `body.dark-mode`, `body.cb-mode` and their combination work with
zero additional rules — no literal hex anywhere. Full token tables + component specs:
`design_handoff_asserts/README.md`.

## 4. Editing

**Entry points:** ⊕ on a metric cell (pre-filled: that sampler, that metric, seeded bands) ·
Edit on a rule row · Add assert · Generate from run · drag/place a chart threshold line (§3 Charts).

**Editor** — slide-over, one form for every metric (the rule shape is uniform):

- **Target**: segmented mode (One sampler / Several / Pattern / Whole run). Sampler names are
  picked from the loaded run, never typed blind. Pattern mode: glob input + optional excludes +
  **live match preview** ("matches 4: …", or "matches nothing" warning) — kills the typo'd-name
  failure class.
- **Metric**: select grouped by kind (Latency/Errors/Volume/Quality) with units; custom
  percentile input (any pN.N on the payload grid or rule-declared — D-C in phase 1 guarantees
  the value exists client-side).
- **Bands**: warn and fail rows, each `operator (> ≥ < ≤ outside) + value(s)`, independently
  removable; `outside` renders two inputs (lo/hi) so corridor rules authored in the file stay
  fully editable, never silently downgraded. Removing warn reads as a choice, not an accident ("No warn band — rule goes
  straight from pass to fail"). Observe toggle disables both.
- **Meta**: id (auto-suggested, editable), description, onMissing, tags, enabled.
- **Live verdict preview**: every matched sampler with actual value and recomputed status as you
  type, worst-of chip on top, clearly stamped **Preview — unsaved**. This panel is the reason
  phase 2 exists.
- Inline validation on the field (duplicate id, no bands, unreachable warn band with
  "swapped values?" hint, bad pattern), not a wall on submit. The editor blocks saving a dead
  warn band outright — the file loader only warns, but there is no reason to let the GUI author one.

**Seeding** (⊕ and the wizard): fail = observed × (1 + headroom), rounded to a "nice" number
(1/2/2.5/5 × 10^k); warn = 0.8 × fail, same rounding. Default headroom +20%. For
lower-is-breach metrics (throughput, apdex) the direction flips automatically:
fail = observed × (1 − headroom), `lt`.

**Generate from run** — wizard: target scope (all top-level / pattern) → metrics checklist
(default p95, errorRate) → headroom + warn-band sliders → live preview of the rules it will
create → adds them as drafts. First-run adoption path: a tester with zero config gets a
credible gate in four clicks, then prunes.

## 5. Draft state model

- Loaded rules (`origin: file`) vs draft changes (added/edited/deleted) held in
  memory; **unsaved pill** with change count in the toolbar; per-row NEW/EDITED markers.
- Recomputed draft verdict shown *in the tab only*, labelled "if saved"; header badge and gate
  strip keep the gated verdict.
- Drafts persist to `localStorage` keyed by run identity (`RunIdentity` already exists) —
  surviving an accidental refresh matters on `file://`. Discard-drafts action offered.

## 6. Write-back

Emits the **whole document**: every key reproduced — `testNotes`, `verdict`, `timelineMarkers`,
`samplerNotes`, `comparisonThresholds`, unknown keys (phase 1 keeps unknown-key capture) —
with `assertions` replaced (declaration order preserved, new rules appended — diff-friendly). 2-space indent,
stable key order, `$schema` inserted if absent. Save menu: Download file · Copy file ·
Copy `assertions` only.

**Guarantees:** (1) round-trip: open → save with no edits ⇒ equivalent file; (2) next-run
fidelity: a saved file fed to `jmeter -g` on the same data reproduces the previewed verdicts —
guaranteed by evaluator parity, tested end-to-end; (3) no silent loss: anything the UI can't
represent is shown read-only and emitted verbatim, never dropped.

## 7. Needs from phase 1 (already in its §6)

Payload carries: declared rules with predicates (a verdict alone is lossy), per-rule results with
rule ids, the whole annotations document, metric vocabulary (names/units/scopes), percentile grid
+ rule-named quantiles. Predicates are plain comparisons ⇒ JS re-evaluation is trivial.

## 8. Test plan (Playwright)

- **Display**: rules render with correct verdicts/sort; unmatched distinct; empty state; gate
  strip counts.
- **Entry points**: ⊕ pre-fills sampler+metric+seeded bands; badge click lists judging rules.
- **Editor**: match preview counts; inline validation (duplicate id, no bands, bad pattern);
  observe toggle; live verdict flips at the boundary (1500 → FAIL, 2000 → PASS with actual 1873).
- **Drafts**: unsaved pill; localStorage restore after reload; discard.
- **Write-back**: round-trip equivalence; notes/markers/unknown keys retained; assertions-only
  copy; downloaded file re-fed through `jmeter -g` reproduces previewed verdicts.
- **XSS**: sampler names, ids, descriptions, tags through `escapeHtml`; extend
  `xss-escaping.spec.ts`.
- New fixture generated *with* assertion rules; update `FIXTURE_IDS` + `REQUIRED_FIXTURES`
  together.

## 9. Not in this phase

Server/HTTP mode; editing notes, markers, comparison thresholds; baseline-aware rules; group
aggregates. (Same future list as phase 1 §10.)
