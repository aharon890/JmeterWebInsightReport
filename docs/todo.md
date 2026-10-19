# To do: findings from the status audit (2026-10-19)

An audit of every status the product can show (verdicts, NOT_JUDGED reasons, "no verdict"
states, page-only states, collection states, and the console/JSON/JUnit words) found three kinds
of problem. The docs part is fixed: the tutorials, FEATURES.md, Instructions.md and
TEST-COVERAGE-GAPS.md now match the code at 4.0.1. This file holds everything else.

Line numbers are at 4.0.1 (`928c1df`). `report.js` means
`jmeter-web-insight-report/src/main/resources/static/js/report.js`, and `report.css` its stylesheet.
"Rendered" means reproduced in headless Chromium on a copy of the named fixture.

---

## Decisions needed first

These change what the code and the docs should say, so they come before the fixes that depend on
them.

- **D1. Should the client header badge answer for the client rules only?** Today it changes with
  the server step. A file with no client rules reads `ASSERTS NOT EVALUATED` on the interim report
  or over a refused payload, and `SERVER ASSERTS ONLY` once the server asserts are judged (rendered:
  C37 `pipeline/interim` vs `pipeline/report`, G8 vs G5). A file whose client rules all decided
  nothing reads `SERVER ASSERTS ONLY` whenever the server side was judged, so the word claims
  there are no client rules when there are (report.html:79-81, ThymeleafReportRenderer:116-124;
  not rendered, since no fixture has that shape). The server chip beside it already carries the
  server state. Option: the badge reads `NO ASSERTS DECLARED` / `ASSERTS NOT EVALUATED` by the
  client rules alone.
- **D2. Where should an uncollected or refused `NOT EVALUATED` server row sort, and how should it
  look?** Today it ranks 99 (`rowRank`, report.js:17595-17600), below DISABLED, and its chip looks
  exactly like DISABLED (same neutral colours, no outline; rendered on G8). `rowRank`'s own comment
  ranks the two withheld states (NEEDS RE-COLLECTION 2.5, NOT RE-JUDGED 4.5) above PASS because "a
  declared check whose verdict this page cannot show is more interesting than one that passed".
  Option: rank it with them and give it their dashed outline.
- **D3. Should a declared component with no asserts keep making `serverStatus` NOT_EVALUATED?**
  Such a component reads NOT_EVALUATED (ServerGateEvaluation:304-322), which outranks WARN and
  PASS (`rank`, :678-688). So when nothing FAILs, `serverStatus` and the header chip name the empty
  component. It is intended by the code comment and documented in Tutorial 11; no test pins it
  with only PASS/WARN siblings.
- **D4. One spelling per state across surfaces?** NO_VALUE is `NO VALUE` on the page, "not
  measurable" on the console and the gate strip, "could not be measured" in the phrase. WARN is
  `WARNING` in the header badge and Copy verdict. The console names a component by id
  (`Server kafka:`), the chip by its label (`Kafka`). jmeter.log says "Quality gate:" where the
  console says "Asserts:". The Collection panel draws source OK/PARTIAL/FAILED in the
  PASS/WARN/FAIL colours, and Validate query prints the raw series status (FAILED, EMPTY,
  INSUFFICIENT) beside board words for the same row (SOURCE FAILED, UNMATCHED, LOW COVERAGE). The
  JSON and JUnit wording is a consumed contract, so this is probably page and console only.

---

## Product defects

Ranked by value against cost. All are small unless marked.

### High

- **P1. A match the run could not measure shows the raw text `NOT_JUDGED`.** Five sites use
  `chipLabel(m.status)` where the rule row uses `displayStatus`:
  - report.js:11497, the match rows;
  - report.js:13913, the editor's preview rows;
  - report.js:14376, the cell tooltip;
  - report.js:14614, the Samplers badge;
  - report.js:14684, the judging row.

  `chipLabel` has no entry for `NOT_JUDGED`, and the match already carries
  `notJudged: 'NO_VALUE'`, so `displayStatus(m)` would print `NO VALUE`.

  The Samplers badge is also unstyled: `assert-badge-not_judged` has no CSS, so it renders with a
  transparent background. It also appears where Java shows no badge at all. `SamplerVerdicts`
  folds only matches that decided something (SamplerVerdicts.java:127-135), and the page folds the
  NOT_JUDGED ones too (`verdictsBySampler`, report.js:14553-14576).

  Rendered on E25: Edit document…, add
  `{"id":"tail-all","target":{"match":"*API"},"metric":"p99.5","fail":{"gt":999999}}`. The row reads
  `NO VALUE`, and its three match rows and the editor preview rows read `NOT_JUDGED`. The badge and
  the judging row need one sampler's quantile to be null while the others are not; that was
  reproduced by nulling it in chartData. Tutorial 12 ("A sampler no rule judges has no badge")
  describes the intended behaviour.
- **P2. A NOT RE-JUDGED row's chart claims a breach.** `undecided` tests only
  `verdict.status === 'NOT_JUDGED'` (report.js:19839, 20143). A withheld row has `verdict: null`, so
  the chart does three things the row's chip says it cannot do:
  - draws a red breach wash computed from the thinned points against the edited band;
  - draws both band lines at full strength;
  - prints `judged: max across max` (`seriesNote`, report.js:19206).

  Rendered on E26: edit `many-instances` to fail `gt 10`, save, open its chart.

### Medium

- **P3. The Budget cell is memoised by rule id, not by row key** (`attainmentIndex[rule.id]`,
  report.js:10360-10364). Two rules sharing an id show one Budget, one title and one sort key. In
  the rendered case a PASS row read `9300%` with a red bar. `worstMatch` was moved to row keys;
  `attainmentOf` was not.

  Rendered on E25: two id-less rules `{"target":{"sampler":"Fast API"},"metric":"p90","fail":{"gt":1}}`
  and the same with `{"gt":999999}`.
- **P4. The Asserts toolbar counts leave out NO VALUE (and LOAD NOT ACHIEVED)**
  (`toolbarReasonCounts`, report.js:8946-8962). The parts no longer add up to "declared": rendered
  as `14 declared · 13 evaluated · 8 passed · 1 warned · 1 failed · 1 unmatched · 1 invalid (could
  not run) · 1 disabled`, beside a `NO VALUE 1` chip.
- **P5. An INVALID client rule still draws its band on the Timeline, as the bold "binding" fail
  line** (`chartBands` filters only on enabled, metric and visible sampler, report.js:15404-15408).
  The Server tab mutes the bands of a row that decided nothing.

  Rendered on E25: add `"observe": true` to `slow-api-p95`.
- **P6. Fix D1** (the header badge) once decided.
- **P7. Fix D2** (NOT EVALUATED rank and style) once decided.

### Low

- **P8. UNAVAILABLE claims rules exist on a run that declared none.** With the sidecar missing,
  the strip and the Asserts pane say "the assert rules and their verdicts could not be loaded",
  next to a `NO ASSERTS DECLARED` header (report.html:988-992, report.js:16166-16182).

  Rendered on an E19 copy without its `-data.js`.
- **P9. The Server tab's status filter chips are never coloured.** `.wir-filter-chip`
  (report.css:3522) has the same specificity as `.wir-chip-*` (report.css:1896-1939) and comes later,
  so every chip is white whether selected or not. That contradicts the CSS comment at
  report.css:3537. The Asserts tab colours its selected chip.
- **P10. An active Server status filter can lose its chip.** When the filtered status's count drops
  to 0, its chip disappears (report.js:18290) and nothing shows as pressed.

  Rendered on E26: select `SOURCE FAILED 1`, then Disable that row.
- **P11. `wir-count-not_evaluated` has no CSS** (report.css:2016-2021). The Summary server card's
  `(no component) NOT EVALUATED` count renders as bare text. This happens in a real run when an
  assert names an undeclared component.
- **P12. A refused payload is called "nothing collected" on three surfaces**, while the row reasons
  and the banner say "refused":
  - Validate query: "Nothing has been collected for this report yet." (report.js:22199);
  - the toolbar: `Window judged: nothing collected yet` (report.js:17973);
  - the chart note: "No series was collected for this query." (report.js:19185).

  The docs now describe this as it is.
- **P13. A bare `?` is used as a status word:** `WARN → ? if saved`, with the aria-label
  "DB WARN, ? if saved". Only the tooltip explains it (`wouldText`, report.js:18226).
- **P14. The NO VALUE sentence prints raw epoch milliseconds:** "…over the judged window
  [1700000000026, 1700000048490]…" (`ServerKpiEvaluator.describeWindow`). The JSON and JUnit carry
  it too.
- **P15. Validate query ignores the arms that need no series.** It says "reads SOURCE FAILED" for a
  FAILED series even when the row reads DISABLED, INVALID or LOAD NOT ACHIEVED
  (report.js:22231-22241).
- **P16. "Worst breaches" on the gate strip also lists INVALID rules.** Perhaps rename the heading.
- **P17. The Summary server card counts components two ways.** Its sub-line counts the declared
  components only (report.js:17746), while its count chips add the reserved `(no component)` entry
  (report.js:17753).
- **P18. The `(no component)` card has no badge and no `→ if saved`** (`cardHtml(null, null,
  orphans)`, report.js:18771), though the header chip may be named after it.
- **P19. `artifacts.xml.outcome` reads `PLANNED` for an XML that is then written.** The JSON
  manifest is built (WebInsightReportGenerator:947) before the XML is staged (:965-973). Every
  golden shows it.
- **P20. The G2 golden is mislabelled.** Its `offgrid-percentile` case is described "NOT_JUDGED /
  NO_VALUE", but the golden records FAIL (test-plans/goldens/gate-mixed.json:60-64;
  expected G2 json:200-206). Relabel it, or make it a real NO_VALUE.
- **P21. Dead or unreachable code:**
  - `visibleKpis` (report.js:17645-17650) has the non-transitive comparator that `rowRank` was fixed
    to avoid, and reads a retired `f.source`. Only `_visibleKpis` exposes it, and nothing calls it.
  - `Server · nothing judged` and its title, `serverStatus`'s no-component arm, and the chip label's
    `NOT JUDGED` arm (ServerGateEvaluation:835-881) cannot be reached: an evaluated run always has
    a component.
  - The page fallback `'NOT JUDGED'` (report.js:7664) has no CSS for its `not-judged` token.

  Remove the unreachable words together with their docs lines: Tutorial 01 and 13 and FEATURES
  list the "nothing judged" chip.
- **P22. Latent client-side sort and filter gaps.** The Asserts tab never got the Server board's
  hardening. `compareBy` uses `SORT_RANK[d] || 0` (report.js:10475), so a display value with no rank
  sorts with FAIL. `presentStatuses` (report.js:10545-10557) lists only `STATUS_ORDER`, so such a
  value gets no chip and is counted nowhere. No client row can reach such a value today. Port the
  explicit fallback rank and the appended chip.

---

## Test gaps

### Reachable today, but no test reads them
- E27: the header badge `SERVER ASSERTS ONLY`, the chip `Server · Event bus WARN · 1 more`, and the
  Collection panel's source `OK`.
- E25 and E26: a sampler WARN badge.
- E26: the board row chips UNMATCHED and SOURCE FAILED. Only the filter counts are pinned.
- E10: the Summary Asserts card PASS.
- C35 / G8: the reason sentence of a refused board row.

### Weak assertions
- server-tab.spec.ts:397-399 compares positions with `indexOf`, which passes when the chip is
  missing (-1).
- client-fixes.spec.ts:196 and asserts-seeding.spec.ts:263 use regexes that accept any verdict.
- asserts-chart-lines.spec.ts:238 and :248 test `/FAIL/i` and `/PASS/i` against the whole table's
  HTML.
- server-board.spec.ts:193-200 and verdict-block.spec.ts:86-99 compare the page with its own
  `chartData.serverVerdict`, so a wrong Java fold would still pass.
- summary-tab.spec.ts:170-176 asserts nothing.

### No fixture can reach these
- **Interim and refused reports.** Every Playwright check of `NOT EVALUATED` rows, `Server · NOT
  COLLECTED` / `REFUSED` and the NOT COLLECTED / REFUSED Summary card runs on a rewritten (chartData)
  or spliced (chip markup) copy. Java-rendered reports exist in test-results/ (C37/pipeline/interim,
  EXAMPLE, C35/foreign, G8), but no spec opens them.

  Proposal: two cheap `-g -o` variants of E26 from committed inputs, one with no
  `-Jwebinsight.report.servermetrics` (interim) and one with a payload the loader refuses. Each needs
  a `run_test` line, a `FIXTURE_IDS` entry and a `REQUIRED_FIXTURES` entry.
- **NO VALUE**, client and server, on every surface:
  - Server: an E26 assert whose `sustainedFor` exceeds the window reads NO VALUE. Adding one may
    mean re-collecting the committed payload; see test-plans/server-metrics/README.md.
  - Client: it needs a metric Java cannot answer on `-g -o`; G2's attempt drifted to FAIL.
    Investigate first.
- `ASSERTS WARNING` (no client gate is designed to land on WARN), `ASSERTS NOT EVALUATED`, the
  header chip and Summary server card PASS, a component reading NOT EVALUATED, the `(no component)`
  entry, source FAILED, series PARTIAL and NOT_REQUESTED.

### Goldens and runners
- No golden holds any of:
  - a top-level `status` WARN;
  - any NOT_EVALUATED (`gate.status`, `serverStatus`, a component);
  - NO_VALUE (see P20);
  - SOURCE_FAILED;
  - the "load rule was not judged" form of LOAD_NOT_ACHIEVED;
  - the `(no gate)` entry;
  - the interim `<testName> - serverMetrics` suite.
- No runner checks a JUnit `<skipped>` element, or reads a `serverStatus` value.
- No test pins the console labels "not measurable" and "load not achieved", or a WARN/PASS
  verdict clause.

### js-unit
- No tests for the sort, filter and count functions: `rowRank`, `filterChipsHtml`, `compareBy`,
  `presentStatuses`, `toolbarReasonCounts`. These would have caught P4 and P22.
- No check that `SORT_RANK` and `STATUS_ORDER` cover all of `DISPLAY`.
- `dead-code.test.js` checks that styled classes are built, but not that built classes are styled.
  That reverse check would have caught P1 and P11.

---

## A mechanism, so a state cannot go undocumented or untested again

**Why it happened.**
- The uncollected `NOT EVALUATED` row shipped in 3.1.13 with no docs change.
- Tutorial 13 was written later, in a batched docs milestone. Its tables copied the declared lists:
  the `NotJudged` enum (`DISPLAY`) and the two named page constants. The uncollected word is in
  neither; it is only the `chipStatus(null)` fallback.
- The 4.0.0 docs passes checked each claim the docs make against the code. That direction can never
  surface an omission.

The repo already tests other lists against each other in both directions: `AnnotationsSchemaTest`
(schema and bean), the parity vectors (Java and page), and `CatalogueTest` (docs and queries).
Status vocabulary has nothing of the kind.

- **M1. One declared list: `WIR.status.STATES`.** It holds every value a status chip, badge or count
  can show, on both tabs:
  - each entry has `key`, `label`, `cssToken`, `rank`, `chipOrder`, a family (verdict, reason,
    no-verdict, withheld, fallback) and its sides;
  - `DISPLAY`, `SORT_RANK`, `STATUS_ORDER` and `CHIP_LABEL` are derived from it;
  - `displayStatus` and `rowDisplay` return only its keys;
  - the uncollected/refused row becomes an explicit state.

  Medium cost. It is display only, so the parity risk is low.
- **M2. `test-plans/js-unit/status-vocabulary.test.js`**, run in CI by `run_js_unit_tests`. It
  checks:
  - **Completeness.** Every `AssertionStatus` × `NotJudged` value comes from the parity vectors,
    plus null and the stale/withheld/uncollected rows from the `_board()` harness. Each is driven
    through `displayStatus`/`rowDisplay` and must come out as a registry key.
  - **Definition.** Every key has a rank, a chip order, a label with no `_`, and a CSS rule in
    `report.css` for every class family built from it (`wir-chip-`, `wir-count-`,
    `assert-badge-`).
  - **Docs, both ways.** Each enumerating docs table carries a marker such as
    `<!-- status-table: server-board -->`. Its first column must equal the registry's entries for
    that surface, so a state missing from the docs fails, and so does a doc word missing from the
    code.
  - **Tests.** Each entry names the spec(s) that render it, or `unreachable: "<why>"`. The test greps
    those specs for the label, statically.
- **M3. A Java twin, `StatusVocabularyTest`.** It holds `NotJudged`, the header chip states,
  `CollectionStatus` and the JSON status values to the Tutorial 11 and 13 tables. Precedent:
  `CatalogueTest` already reads `docs/`.
- **M4. Process.**
  - CLAUDE.md and the dev-cycle skill: "a new display state is a registry entry; the test fails
    until a docs table and a spec name it".
  - Any docs pass starts code→docs: generate the list of states from the code and look for each in
    the docs. Only then check the docs' claims against the code.

The first run of M2 will fail on the interim/refused fixtures and NO VALUE above, so it doubles as
their acceptance test.
