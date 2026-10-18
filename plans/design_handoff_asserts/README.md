# Handoff: Asserts — engine v2 + report GUI

## Overview
Adds a declarative quality gate to the JMeter Web Insight Report plugin. A tester declares
exactly which checks decide a run — any metric, any percentile, per sampler / group /
whole run, each with its own warn/fail criteria — in `report-annotations.json`. The report then
shows every rule with its verdict, lets the tester tune limits against observed values with live
re-evaluation, and saves the updated annotations file for the next run.

Delivery is phased:
- **Phase 1 — engine (backend, Java):** `plans/phase-1-assertion-engine-v2.md`
- **Phase 2 — GUI (report, vanilla JS):** `plans/phase-2-asserts-gui-v2.md`
- **Phase 3 — direction note (charts as authoring surface, server metrics, `over` clauses):** `plans/phase-3-chart-and-server-asserts.md`

All product decisions are closed and recorded with rationale in the plan files. This README
covers what they don't: exact visual/interaction specs for the GUI.

## About the Design Files
`Asserts Prototype.dc.html` and `Asserts Spec.dc.html` are **design references created
in HTML** — interactive prototypes showing intended look and behavior, not production code.

`Server Metrics Integration.dc.html` is the third, and the only one describing work that has
**not** been built. It is the canvas for phase 3 — extending the gate to backend metrics (CPU,
consumer lag, heap, DB) so one verdict covers client and server. Phases 1 and 2 shipped; this
is the open one, and the file is here rather than in someone's scratch folder because
`plans/phase-3-chart-and-server-asserts.md` names it as a companion and a tracked document
must not point at a file only one machine has.

All three load `support.js` from this directory, so open them from here — moving one out on
its own leaves it blank.
The task is to **recreate them in the plugin's existing report environment**: a single
self-contained HTML file, vanilla JavaScript (`report.js` patterns), styled by `report.css`
custom properties, no frameworks, no external network. The prototype's logic class is also the
**reference implementation of the evaluation algorithm** (globs, predicates, worst-of,
onMissing, seeding math) — port it, don't reinvent it. Open the prototypes in a browser
(keep `support.js` next to them).

## Fidelity
**High-fidelity** — layout, spacing, typography, copy and interaction are final and match the
existing report's visual language. One deliberate exception: the prototype hardcodes *light-mode
hex values* inline. Production code must use the `report.css` custom properties listed under
Design Tokens instead — that single rule makes dark mode and colorblind mode work with zero
extra CSS. **Never hardcode a hex in components.**

## Screens / Views

### 1. Summary tab — Quality gate strip
- Position: first element of the Summary tab, above the summary cards. Full-width card:
  `var(--color-surface)` bg, 1px `var(--color-border)` border, radius 8, `var(--shadow)`,
  4px left accent border colored by verdict (`--color-error` / `--color-warning` /
  `--color-success`), padding 18/22.
- Content, left→right (flex, gap 24, wraps):
  - "QUALITY GATE" label (0.72rem/700 uppercase, letter-spacing .05em, `--color-text-secondary`),
    verdict pill (see Badges), "N rules declared — only these decide the verdict" (0.8rem secondary).
    Under it: count chips `2 failed / 2 warned / 2 passed / 1 observed` (0.68rem/700, badge colors).
  - "WORST BREACHES" list: up to 3 rows — mini status chip + rule id (monospace 0.78rem) +
    `metric actual vs limit` (0.78rem secondary).
  - Right: primary button "Open Asserts →".
- The rest of Summary is unchanged.

### 2. Asserts tab (new top-level tab, after Summary)
**Toolbar card:** verdict pill · counts line (0.82rem secondary) · right-aligned: Tags select,
secondary button "Generate from run…", primary "+ Add assert", secondary "Save ▾" with dropdown
(Download report-annotations.json / Copy whole file / Copy assertions array only). Production
adds: status filter + id/target text search (plan §3).

**Draft banner** (only when unsaved changes exist): `--color-bg-accent` bg, 1px dashed
`--color-primary` border, radius 8. Text: "N unsaved changes · Verdict if saved and re-run:
[chip] · The header badge stays on the verdict this run was actually gated on." Buttons:
"Download to apply" (primary, small), "Discard drafts" (outline).

**Rules table card** (overflow-x auto): columns Status / Rule / Target / Metric / Criteria /
Actual / Matches / actions.
- Header: th 0.68rem/700 uppercase, letter-spacing .05em, secondary color, `--color-bg-thead` bg.
- Rows: 1px top border, hover `--color-bg-row-hover`; sorted worst-first (FAIL, WARN, PASS,
  OBSERVED, DISABLED), then id.
- Status: chip (PASS/WARN/FAIL/OBSERVE/DISABLED — badge tokens; OBSERVE uses info badge tokens).
- Rule: id in monospace 0.8rem/600 + optional NEW/EDITED draft tag (0.58rem/700,
  `--color-bg-accent` bg, dashed `--color-primary` border) + description (0.74rem, muted).
- Target: kind chip SAMPLER/LIST/PATTERN/OVERALL (0.58rem/700, `--color-ramp-badge-*` tokens) +
  truncated target text (monospace 0.78rem, ellipsis, `title` = full).
- Criteria: e.g. `warn > 650 ms · fail > 800 ms`, or `observe only` (0.78rem).
- Actual: right-aligned; 700 weight + `--color-error` when FAIL, `--color-warning`-dark when WARN.
- Matches: count button `4 ▾` → expands indented sub-rows (chip + sampler name monospace +
  actual, every match incl. passing), 3px left border.
- Actions: text buttons Edit / Duplicate / Disable·Enable / Delete (0.74rem/600; Edit in
  `--color-primary`, Delete hover `--badge-fail-text`).
- **Unmatched rule**: verdict chip per onMissing, Actual shows "— no data —", plus a follow-up
  row (warn-tinted bg, 3px dashed `--color-warning` left border): "Matched nothing in this run —
  the gate never executed (onMissing: fail). Did you mean "…"?" (nearest-name suggestion).
- **Disabled rule**: row opacity .55.

**Empty state** (no rules): dashed-border card, centered: "No asserts declared" / "This run was
reported, not judged. Declare what matters — only declared checks ever decide a verdict." /
"+ Add your first assert" + "Generate from this run…".

**Footnote** (0.76rem muted): file:// save model — download or clipboard, whole document
preserved, drop next to the JTL.

### 3. Assert editor (slide-over)
Fixed right panel, 470px (max 92vw), full height, `--color-surface`, shadow
`-12px 0 32px rgba(15,23,42,.18)`, slide-in 180ms ease-out; scrim `rgba(15,23,42,.32)`
(click = cancel). Header: "New assert" / "Edit assert — {id}" + ×. Body scrolls, sections
gap 18, labels 0.7rem/700 uppercase secondary:
1. **Target** — segmented control (One sampler / Several / Pattern / Whole run; active =
   `--color-primary` bg, white text). Below, per mode: sampler `<select>` (monospace, names from
   the run); checkbox list (max-height 150, scroll); pattern input + exclude input (monospace,
   placeholder `GET /api/*   or   regex:^POST /(a|b)`); overall = explanation box. Pattern/list
   modes show a **live match preview box**: green tint "Matches 4 samplers in this run" + first
   4 names, or amber "Matches nothing in this run — the rule would fail (onMissing)".
   If an edited rule's sampler is not in the run, prepend option "{name} (not in this run)" —
   never silently retarget.
2. **Metric** — select with unit suffixes, scope-filtered (overall vs sampler vocabularies,
   incl. percentile grid p50…p99.9).
3. **Criteria** — "Observe only (never gates)" checkbox right of the label. WARN row and FAIL
   row: `[WARN chip] if [op select > ≥ < ≤] [value input] [unit] [×]`; removed band becomes a
   dashed "+ Warn band" / "+ Fail band" button. Hint line: "Bands judge the whole-run aggregate —
   the number in the report table. fail is tested first, then warn; the schema also supports
   outside: [lo, hi]." (production editor: `outside` = two inputs). When warn removed: "No warn
   band — the rule goes straight from pass to fail. That is a choice, not an accident."
4. **Preview — unsaved** box: dashed `--color-primary` border, `--color-bg` tint. Header:
   "PREVIEW — UNSAVED" (0.66rem/700 accent) + live verdict chip + "against this run, as you
   type". Rows: chip + sampler (monospace, ellipsis) + actual. Recomputes on every keystroke.
5. **Meta grid** (2-col): Rule id (placeholder = auto slug + "(auto)"), "If target matches
   nothing" select (fail default / warn / skip silently), Description, Tags (comma-separated),
   Enabled checkbox.
- Footer: validation message (0.74rem, `--badge-fail-text`) + Cancel + primary Save (disabled +
  gray when invalid). Validation blocks: duplicate id, no bands (unless observe), **dead warn
  band** ("Warn never fires: fail > 400 is tested first and already covers warn > 500. Swapped
  values?").

### 4. Samplers table integration
- Mini status badge before sampler name = worst verdict of rules judging it (badge tokens,
  0.6rem/700). Click → expandable detail row: per rule `[chip] rule-id  metric = actual · fail
  op limit`; if none: "No rules target this sampler — it is reported, not judged."
- Every numeric metric cell gets a trailing `⊕` button: opacity .18, hover opacity 1 +
  `--color-primary`; title "Assert {metric} of this sampler". Click opens the editor pre-filled
  with that sampler + metric + **seeded bands**: fail = observed × 1.2 rounded up to a "nice"
  number (1/1.5/2/2.5/3/4/5/7.5/10 × 10^k), warn = 0.8 × fail; direction auto-flips for
  lower-is-bad metrics (throughput, apdex: fail = observed × 0.8, op `lt`).

### 5. Generate-from-run wizard (modal)
Centered card 580px, radius 12. Shape segmented (Per sampler × metric / One blanket rule per
metric) · metric checkboxes (p95, p99, errorRate, throughput, apdex; p95 + errorRate default) ·
sliders: Headroom +5…50% (default 20), Warn at 60…95% of fail (default 80) · live preview box
("Will create N draft rules · fail = observed +20% rounded…" + 3 sample rule lines, monospace)
· Cancel / "Add N draft rules". Blanket mode seeds from the worst observed value across targets.

### 6. Timeline charts — threshold lines are the rules
- Every rule targeting the charted metric draws its fail band as a dashed `--color-error` line
  labeled with the rule id; drag handle chip at the right end (`fail < 30 /s · ⇕ drag`).
  **Dragging retunes the band** (live verdict while dragging, release = draft edit).
- "+ Assert from chart" toolbar button → crosshair mode, ghost dashed `--color-primary` line
  follows cursor with value label; click opens the editor seeded with that level (chart context
  decides target: overall chart → scope overall; clicked series → that sampler).
- Run-aggregate marker: thin dashed `--color-success` line ("run aggregate 35.9 /s") — makes the
  aggregate-vs-buckets distinction visible.
- Implementation: ECharts `markLine` for rule lines, `graphic` elements for the drag handle,
  `chart.on('click')` for placement. Strictest line emphasized when several rules overlap;
  others in the tooltip.

### 7. Global
- Header assert badge: **unchanged** — always the gated verdict, never drafts. When drafts exist,
  an "N unsaved changes" pill (0.68rem/700, `--color-bg-accent` bg, dashed `--color-primary`
  border) appears beside it.
- Toast: fixed bottom-right, `--color-bg-deep` bg, light text, 0.82rem, radius 8, ~3s,
  fade/slide-in 200ms. Used for: rule added/updated/deleted (draft), downloaded, copied.

## Interactions & Behavior
- **Evaluation (port from prototype logic class):** target → matched samplers (glob:
  `*` → `.*`, everything else regex-escaped, anchored ^…$; `regex:` prefix passes through;
  excludes filter after). Per match: fail predicate first, then warn, else PASS; rule status =
  worst match; run verdict = worst rule. OBSERVED/DISABLED neutral. No matches or zero-sample
  match → onMissing.
- **Draft model:** loaded rules vs drafts (added/edited/deleted) in memory; unsaved
  pill with count; per-row NEW/EDITED tags; recomputed "verdict if saved" shown in tab + banner
  only. Persist drafts to `localStorage` keyed by run identity (`RunIdentity` exists in core);
  offer Discard.
- **Save:** emits the **whole annotations document** — every key reproduced (`testNotes`,
  `verdict`, `timelineMarkers`, `samplerNotes`, `comparisonThresholds`, unknown keys), with
  `assertions` replaced in declaration order (new rules appended). 2-space indent, stable key
  order, `$schema` retained/inserted. Download = Blob + `<a download>`; Copy = clipboard (both
  proven on file://). Round-trip guarantee: open → save with no edits ⇒ equivalent file.
- **XSS:** sampler names, rule ids, descriptions, tags all pass through the report's existing
  `escapeHtml` before reaching the DOM.
- All test plans (Playwright + JUnit fixtures) are in the phase plan files §7–8.

## State Management
Single state object in report.js scope: `rules[]` (with `origin`, `draftState`),
`expandedRule`, `expandedSampler`, `editor` (mode/sampler/samplers/pattern/exclude/metric/
warn/fail/observe/onMissing/id/description/tags/enabled/editingId), `wizard` (mode/metrics/
headroom/warnPct), `saveMenuOpen`, `tagFilter`, toast. Data source: the phase-1 payload embeds
declared rules + per-rule results + metric vocabulary (names/units/scope) + percentile grid +
the whole annotations document — the GUI never derives these.

## Design Tokens
All from `report.css` `:root`; modes re-assign variables on `body.dark-mode`, `body.cb-mode`,
`body.dark-mode.cb-mode`. Components reference **only** the variables.

| Token | Light | Dark |
|---|---|---|
| --color-primary | #3b82f6 | #60a5fa |
| --color-success | #22c55e | (cb: #2563eb) |
| --color-warning | #f59e0b | — |
| --color-error | #ef4444 | (cb: #ea580c) |
| --color-bg | #f8fafc | #111827 |
| --color-surface | #ffffff | #111827 |
| --color-bg-card | =surface | #1e293b |
| --color-text | #1e293b | #e2e8f0 |
| --color-text-secondary | #64748b | #94a3b8 |
| --color-border | #e2e8f0 | #374151 |
| --color-bg-hover / row-hover | #f1f5f9 | #334155 |
| --color-bg-accent | #eef2ff | #334155 |
| --color-bg-thead | =bg | #0f172a |
| --color-bg-input | =bg | #1e293b |
| --color-bg-deep | #0f172a | #0f172a |
| --badge-pass-bg / -text | #dcfce7 / #166534 | #064e3b / #6ee7b7 |
| --badge-warn-bg / -text | #fef3c7 / #92400e | #78350f / #fbbf24 |
| --badge-fail-bg / -text | #fee2e2 / #991b1b | #7f1d1d / #fca5a5 |
| --badge-info-bg / -text (OBSERVE) | #dbeafe / #1e40af | #1e3a5f / #93c5fd |
| --color-ramp-badge-bg/-text/-border (kind chips) | #f1f5f9 / #64748b / #e2e8f0 | #1e293b / #94a3b8 / #334155 |
| --radius | 8px | — |
| --shadow | 0 1px 3px rgba(0,0,0,.1) | 0 1px 3px rgba(0,0,0,.3) |

Colorblind (`body.cb-mode`): pass badge #dbeafe/#1e40af, fail badge #fff7ed/#9a3412, success
#2563eb, error #ea580c. Dark+cb: pass #1e3a5f/#93c5fd, fail #7c2d12/#fdba74. **New DISABLED
chip** may add tokens `--badge-muted-bg/-text` (light #f1f5f9/#64748b, dark #1e293b/#94a3b8) —
follow the existing naming.

**Typography:** system stack `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
'Helvetica Neue', Arial, sans-serif`; monospace `ui-monospace, SFMono-Regular, Menlo, monospace`
for ids, sampler names, patterns, criteria values. Scale used: section titles 1.125rem/600 ·
card titles 0.9rem/600 · body 0.82rem · table cells 0.78–0.82rem · th + section labels
0.66–0.72rem/700 uppercase ls .05em · chips 0.58–0.68rem/700 · hints 0.72–0.76rem. Line-height
1.6 body. Buttons: 0.78–0.85rem/600, padding 8–9px 14–18px, radius 8; primary = primary/white,
secondary = surface/border/text.

## Assets
None. All glyphs are text characters (⊕ × ▾ ▴ ⇕ →); no icon fonts, no images, no external fonts
or network requests (self-contained-file promise).

## Files
- `Asserts Prototype.dc.html` — interactive GUI reference (needs `support.js` beside it)
- `Asserts Spec.dc.html` — schema, semantics, outputs, validation reference
- `support.js` — runtime for opening the two references in a browser
- `plans/phase-1-assertion-engine-v2.md` — backend engine: schema, translation, outputs, tests
- `plans/phase-2-asserts-gui-v2.md` — GUI scope, draft model, write-back guarantees, tests
- `plans/phase-3-chart-and-server-asserts.md` — future direction (charts, server metrics, `over`)
