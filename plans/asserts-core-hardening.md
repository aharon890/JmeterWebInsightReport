# Asserts — core hardening

> **Status:** shipped · 2026-08-10 · A0–A12 all merged · released as **3.0.16** (`ccc9197`)
> **One decision here was reversed.** §8's ruling that the exit-code half "was never wired and
> now never will be" was overturned the same day: a rule the *file* got wrong is a
> **configuration error**, not a verdict, and exits non-zero under `failonerror` (default
> **true**). The reversal is annotated in place 44 lines below and shipped as **3.1.0** via
> [`asserts-config-errors-fail.md`](asserts-config-errors-fail.md). What did *not* change: a
> status still comes only from a declared assert measured against a real value, and
> `failonassert` is still default-off — now read on **both** ingestion paths (S1, PR #79).
> Written 2026-08-07; header corrected 2026-08-10. The body below is left as written.
> **Supersedes** `temp/ASSERT-SYSTEM-DESIGN.md` and `temp/GUI-MANAGED-ANNOTATIONS-PLAN.md`.
> Advanced features live in [`asserts-future.md`](asserts-future.md) and are **out of scope here**.
> **Diagrams:** [`asserts-flow-diagrams.md`](asserts-flow-diagrams.md) — read those first if the
> prose below is hard to hold in your head.

## Where we are

**A11 and A12 are done. The plan is complete.** A3 and A4 are done: four statuses, and the four channels
that now carry a broken rule instead of a verdict. One A4 item is deliberately left open —
whether an unusable annotations file should set a non-zero exit. It changes exit codes for
existing pipelines, so it wants a decision rather than a guess. See A8, where
`failonassert` raises the same question.

| | Milestone | Status | Days |
|---|---|---|---|
| A0 | Repair the floor — four silent passes, verdict on stdout, `attainment` | ✅ **merged** · PR #75 | — |
| A0b | Retire the legacy threshold block, everywhere | ✅ **merged** · PR #75 | — |
| — | Settle the open decisions | ✅ **done** · §8 (one deferred to A8) | — |
| A1 | Delete `loadValidity` + `Origin` — the rule you could not delete | ✅ **done** | — |
| A2 | Delete `tags`, `observe`, `onMissing`, `OUTSIDE`, `exactly()`, `verdict` | ✅ **done** — all six | — |
| A3 | `AssertionStatus` 7 → 4 — one producer, one fold | ✅ **done** | — |
| A4 | Loudness: findings on gateless runs, `findings[]` in the CI JSON, blocking banner, loader stops swallowing | ◑ **done bar the exit code** — see below | 3 |
| A5 | Draft model → candidate-document diff | ✅ **done** | — |
| A6 | Document editor — every block CRUD-able | ✅ **done** | — |
| A7 | `openFile` import + provenance | ✅ **done** | — |
| A8 | CLI: `failonassert` (default off), GUI annotations field | ✅ **done** | — |
| A9 | Actual vs expected — the Budget column | ✅ **done** | — |
| A10 | Simplify the rules-table filter/sort — wizard and drag-to-retune KEPT | ✅ **done** | — |
| A11 | Robustness sweep | ✅ **done** — all four items | — |
| A12 | Docs, schema, WIKI, fixtures + **the upgrade note** | ✅ **done** | — |

**Done: 14 of 14** — and *this* board, not §5's table, is the file's status. A4’s exit-code
half was settled as *never* on 2026-08-08 and **reversed the same day**. FAIL still means a
declared assert measured against a real value and nothing else; but a rule that could not be
*built* is a config error and exits non-zero under `failonerror`. See the revision block below.

All five rules are met. Docs were kept in step milestone by milestone; the one artifact this
plan owed and had not produced is [`WIKI/UPGRADING-ASSERTS.md`](../WIKI/UPGRADING-ASSERTS.md),
which lists every behaviour change an existing user will hit — two of which used to fail a
build and no longer do.

**Rule 5 is met in full**: author in JSON or the GUI (there is a field for it now), open a
JSON from the report to override, run headless against it, and the Asserts tab shows actual
against expected. A12 has one real deliverable left — a RELEASE NOTE. Docs have been kept in
step milestone by milestone, but nothing yet lists the behaviour changes an existing user
will notice: a typo’d metric no longer fails a build, a renamed sampler no longer fails a
build, four keys became unknown keys, a rule `-Jwir.asserts.tags` used to deselect now runs,
`gate.invalid` moved to `gate.notJudged.invalidRule`, and INVALID no longer outranks FAIL.

**Rule 1 is met**: every block of the document is create/read/update/delete-able from the
report, including keys this build does not model. **Rule 5 is met in the report**: a JSON can
be opened from the GUI and previewed against the run, and the Budget column says how much of
each fail band was used. What is left of rule 5 is the CLI half — A8. The spine is complete —
A5 onward is the CRUD half of rule 1, which is what the plan was written for.

> **Settled 2026-08-08. FAIL means one thing: a declared assert against a measured value.**
>
> Nothing else produces one. An unusable annotations file — missing path, unparseable file —
> is **reported and does not fail the run**. A4 already behaves this way; the exit-code half
> was never wired and now never will be.
>
> The same answer settles `-Jwebinsight.report.failonassert` (A8): build it, **default off**,
> never flip the default. The verdict is the tester's to act on.
>
> And the corollary, which cost real code: **an unknown key is not an invalid file.** We parse
> one specific shape; a key outside it is not ours to grade. The per-rule and per-target
> "unknown key" WARNINGs are deleted — they put noise on the findings pane, the CI/CD JSON and
> the log of every run that carried a field from a newer plugin, a hand-added note, or anything
> another tool writes. An unknown key cannot move a verdict by definition, `@JsonAnySetter`
> still round-trips it, and a key MISTYPED from one of ours still surfaces through its
> consequence: `"faill": {...}` leaves the rule with no bands, which is INVALID_RULE and loud.
>
> *Considered and not taken:* warning only on near-misses (edit distance ≤ 2 of a known key).
> It would catch a typo'd OPTIONAL key — `"warnn"` beside a valid `fail` runs with no warn band
> and says nothing. Left out because the rule asked for is "just ignore that", and a
> half-exception is harder to explain than either whole answer.

> **Revised 2026-08-08, later the same day. The ruling above stands for the VERDICT and is
> reversed for the EXIT CODE.**
>
> Everything above about what a FAIL means is unchanged and was never in question: a status is
> a judgement, and only a declared assert measured against a real number earns one. A broken
> rule still contributes nothing to `gate.status`.
>
> What was wrong was the sentence "the exit-code half was never wired and now never will be".
> It conflated two questions that only look alike. *Is this a verdict?* and *did the plugin do
> what it was told?* have different answers for the same event: a rule naming `p95x` is not a
> performance judgement, and it is also not a gate. The file asked for six checks and five
> never ran, which is the plugin failing at its job — the same category as an output directory
> it could not write to, which `failonerror` has always covered.
>
> Left as settled, the loosening had no floor under it. A typo'd metric name would exit 0 on
> every run forever, and the only thing standing between a team and a gate that silently
> stopped gating was a `jq` expression somebody had to know to write.
>
> So: **a broken gate definition is a configuration error, not a verdict.** It exits non-zero
> under `failonerror`, prints `WEB-INSIGHT-REPORT: CONFIG ERROR` on stderr, and still writes
> every artifact — failing a build must not cost the report that explains why. A rule that
> merely matched nothing is *not* covered: that one can legitimately be a plan running a
> subset, and it stays a `jq` you choose to write.
>
> `failonassert` is untouched by this and stays default-off. It answers the other question.
>
> Planned and executed in [asserts-config-errors-fail.md](asserts-config-errors-fail.md); the
> reasoning above is left intact rather than rewritten, because it is why that plan exists.

Read §5 for what each milestone actually contains and which branch it lands on.

---

## The five rules this plan serves

1. **Full CRUD from the GUI** over the whole asserts JSON and every rule in it. Today
   `loadValidity` produces a rule that cannot be deleted — that is the motivating defect.
2. **Statuses come only from the user's asserts.** No internal logic manufacturing a FAIL.
   Any assert FAIL → FAIL; else any WARN → WARN; else PASS. Nothing cleverer.
3. **No advanced features.** No windows, no baselines, no new metrics, no relative bands.
4. **Simplify and refactor** what is over-engineered rather than building on it.
5. **The flow:** author in JSON or GUI → open a JSON from the GUI to override the current one →
   run headless against that file → the Asserts tab shows **actual vs expected**.

Everything below is subordinate to those. Where a current behaviour is defensible but violates
one of them, the rule wins and the behaviour goes.

## The governing principle

Decided 2026-08-07, and it settles every open question in §8 at once:

> **This is a report, so it reports.**
>
> **For asserts it does one more thing: it lets you manage them — create, read, update, delete.**
>
> **The decisions stay with the tester. What to do about what the report says is not the
> report's call.**

Three consequences, and they decide most of this document.

**A status is a judgement, and only a declared assert earns one.** A metric that does not exist in
this run cannot be interpreted, so **no performance status comes out of it** — not PASS, not FAIL.
It is reported, and what to do about it is the tester's call. The same holds for an unrecognised
key, an unmatched target, and anything else the engine does not understand: **say it, do not grade
it.**

**Managing asserts is the product's job; deciding is not.** Create, read, update and delete — for
every rule and every block — is squarely in scope (§3), because that is the tester declaring what
they want judged. Interpreting the result for them is not.

**This is narrower than "make it simpler".** It is a rule about *who owns a judgement*, which is
why the deletions in §2 are the ones they are: each removes a place where the product graded
something the tester never asked it to grade. It is **not** a licence to remove authoring
features — those are how the tester declares intent in the first place. See §2f.

---

## 1. The status model

This is the heart of rule 2, and it is the change everything else hangs off.

### Today: seven statuses, two vocabularies, four ways to reach FAIL without an assert failing

| Path to FAIL | Where | User declared it? |
|---|---|---|
| A fail band breached | `AssertionEvaluator.judge` | **Yes** — the only legitimate one |
| Unknown metric, bad selector, wrong scope, no bands, unreadable target | `whyUnusable` → INVALID | No — a typo |
| Metric the run cannot produce (`NaN`) | `judge` → INVALID | No — a missing JTL column |
| Target matched nothing | `onMissing` default **FAIL** | No — a renamed sampler |
| INVALID folded into FAIL for the badge | the badge-status fold in `GateEvaluation` | No — a translation |

On top of that, `GateEvaluation.overallStatus()` takes **the worse of two different views** of the
same gate (the per-sampler fold and the gate's own verdict). That is precisely the kind of logic a
user cannot follow, and it exists only because the system speaks two dialects — a seven-value rule
status and a three-value badge status — so every place they meet needs a translation.

The visible symptom, in one file: `status: "FAIL"` beside `gate.status: "INVALID"` beside
`gate.failed: 0`, with no number anywhere that exceeded a limit.

### After: four statuses, one vocabulary, one producer, one fold

| Status | Meaning | Gates? |
|---|---|---|
| `PASS` | Measured. No band breached. | yes |
| `WARN` | Measured. The warn band breached, the fail band did not. | yes |
| `FAIL` | Measured. The fail band breached. | yes |
| `NOT_JUDGED` | Nothing was compared to anything. | **never, in either direction** |

`NOT_JUDGED` carries a machine-readable `reason` and the existing human sentence:

| reason | Replaces | Cause |
|---|---|---|
| `DISABLED` | DISABLED | `enabled: false` |
| `NO_MATCH` | SKIPPED + the whole `onMissing` fan-out | the target selected nothing, or only rows with zero samples |
| `NO_VALUE` | the NaN→INVALID branch | the run cannot produce this metric (off-grid percentile, absent JTL column) |
| `INVALID_RULE` | INVALID | **the file is wrong** — unknown metric, unreadable target, no bands, bad pattern |

**One producer.** Only `AssertionEvaluator.judge` may return PASS, WARN or FAIL, and only by calling
`Predicate.breaches` on a measured value. `fail` is still tested before `warn` — a value past both
bands is a failure, not a warning.

**One fold, twice.** Match → rule: any FAIL → FAIL; else any WARN → WARN; else any PASS → PASS;
else NOT_JUDGED. Rule → run (`GateSummary.of`, the only fold in the system): identical. A gate of
nothing but NOT_JUDGED rules is **NOT EVALUATED**, not PASS — a run that judged nothing has not
passed anything.

> One deliberate improvement falls out: today a single NaN poisons an entire rule to INVALID,
> which **erases a genuine FAIL** from another sampler the same rule judged. Under the new fold a
> part-measured rule reports the worst of its *measured* matches.

### The consequence of the governing principle

**A typo'd metric no longer fails the build — approved 2026-08-07.** A metric the run cannot
produce cannot be interpreted, so no performance status comes out of it. That removes a safety
property, so the reporting has to carry the weight instead. It stays loud on six channels, five
of which already exist:

1. **The finding** — already fully built. `AssertionValidator` raises `Severity.ERROR` for every
   unrunnable reason, with "did you mean 'p95'?". Independent of the verdict; flipping the gating
   removes nothing from it.
2. **jmeter.log** — `logFindings` already logs each at `log.error`.
3. **stdout** — the Phase 0 `verdictLine()` gains the `invalidRule` count.
4. **CI/CD JSON** — `gate.notJudged.invalidRule` as a separate counter, so a pipeline can be strict
   by choice: `jq -e '.gate.failed == 0 and .gate.notJudged.invalidRule == 0'`. Strictness becomes
   the pipeline's opt-in rather than a verdict imposed on everyone. Also **add `findings[]` to the
   CI/CD JSON** — it emits none today.
5. **The HTML findings pane** — plus a one-line bug fix: `GatePayloadBuilder` drops
   `assertFindings` when there are no rules (`:73-75`), which is exactly the case findings exist
   for (typo'd path, unparseable file, everything deselected).
6. **New: a blocking banner** at the top of the Asserts tab — red, not dismissible, listing every
   rule that could not run, with a **Fix** button that opens the editor on it. The tab cannot be
   read as healthy while a rule is broken.

Net: today a typo produces the word FAIL and no number that exceeded anything. After, it produces
a verdict computed only from real asserts **plus** a banner naming the rule and the typo. Louder,
and traceable to a line in your file.

The same reasoning applies to `NO_MATCH`: a rule pointing at a renamed sampler goes quiet in the
verdict and loud in the banner. The engine did not decide the run was fine — it decided it had
nothing to judge, and said so.

---

## 2. The cut list

Grouped by why. Every item was verified in the tree; efforts include tests, fixtures and specs.

### 2a. Deletions that directly serve rules 1 and 2

| Item | Why | Days |
|---|---|---|
| **`loadValidity` + `AssertionRule.Origin` + the four GUI refusals** | The sole producer of a rule the GUI refuses to edit, disable, delete or retune — and the "Duplicate it" escape hatch writes a second copy into `assertions[]` while the block survives, so the file declares the check **twice** | 3 |
| **`onMissing` + the FAIL default** | Internal logic producing FAIL from a renamed sampler. Becomes `NOT_JUDGED / NO_MATCH` | 1.5 |
| **`overallStatus()` / the badge-status fold / `worse()`** | The "worse of two views" reconciliation — the second vocabulary, and where the disagreements live | 1 |
| **`metricVerdicts()` + the two hardcoded p95/p99 summary-card badges** | A third place a verdict is computed | 0.5 |
| **The Thymeleaf sampler-table assert badge** | Server-rendered, then overwritten at runtime by a client fold with different inclusion rules — two engines, one badge | 0.5 |
| **JUnit XML suite 1 (per-sampler) + `buildFailureMessage`** | A second, independent failure count for one run | 0.75 |
| **`AssertionStatus` 7 → 4** | See §1. The keystone | 4 |

### 2b. Features that cost more than they earn

| Item | Why | Days |
|---|---|---|
| **`tags` + `-Jwir.asserts.tags`** | The browser mirror **never consults tags** — on a tag-selected run the Asserts tab silently converts a DISABLED rule into a real verdict on the first unrelated edit. `enabled: false` already parks a rule and is mirrored correctly | 1 |
| **`observe: true` + OBSERVED** | A rule that declares no consequence is not an assert; the samplers table already shows every metric | 1.5 |
| **`Predicate.Op.OUTSIDE`** | A corridor is two asserts written as one; it has no single threshold, so it cannot show actual vs expected | 0.75 |
| **`verdict` (free-text field)** | Loaded, shipped in two payload fields, set as a Thymeleaf variable, **rendered nowhere** | 0.25 |
| **`AssertionTarget.exactly()`** — the producer only | No callers left: it existed for the legacy translator, which A1 deleted. **Revised during A2: the `\Q..\E` *readers* stay.** `unquote()` and `javaRegex()` do not serve the translator, they serve annotations files **already on disk** — 1.4.14 wrote `regex:\QSlow API\E` excludes, and JavaScript has no `\Q..\E`, so `new RegExp("\\QSlow API\\E")` compiles silently to a matcher for the literal `QSlow APIE`. Deleting them would not simplify anything; it would reinstate a measured verdict flip (E12 `legacy:default:mean`, engine PASS vs report FAIL on one run). Delete the writer, keep the reader | 0.25 |
| **Qualified error metrics (`errorRate:class=`, `code=`)** | **DEFER**, not delete — genuinely useful, but it is a metric-vocabulary feature and rule 3 says not now | 1.5 |

### 2c. Robustness fixes (keep the feature, fix the behaviour)

| Item | Why | Days |
|---|---|---|
| **`AnnotationLoader` swallows a missing/unparseable file** | Catches everything and returns EMPTY with a `log.warn`, so a typo'd path is a green run. An **explicitly named** file that is missing or unparseable must be fatal | 1 |
| **`assertFindings` dropped when there are no rules** | One line moved; see §1 | 1 |
| **`AssertionRuleMapper`'s false promise** | Its finding says unknown per-rule keys are "kept when the report saves it". `ruleToJson` drops them | 0.5 |
| **The synthetic `id`** | An id-less rule gains `assertions[3]` as a permanent id on save | (with above) |
| **`MAX_MATCHES_PER_RULE` / `cappedTo`** | Keeps the *worst* matches, so the CI JSON and the payload disagree about **which** matches, not just how many | 1 |
| **`ERRORS DETECTED` in the verdict slot** | An error count is not an assert verdict | 0.25 |
| **Comparison `regression` styled `badge-fail`, apdex bands in assert colours** | Non-assert things wearing assert colours | 0.75 |
| **The legacy threshold block — retired** | See §2e. Not a robustness fix, a removal | ✅ done |

### 2e. The retired threshold block — DONE

Removed from the tree on 2026-08-07 (milestone A0b): the typed field and its accessors, the
bespoke finding, the payload write-back, the dead Thymeleaf variable, the drop-on-save special
case, the schema property and an orphan schema definition nothing referenced, plus every comment,
doc page, tutorial filename, screenshot name, test and fixture that named it.

A file still carrying that key is now **exactly as interesting as a file carrying any other key we
do not recognise**: it falls through `@JsonAnySetter`, round-trips verbatim on save, and declares
nothing. There is no special case left anywhere. Commit history is the only record.

### 2f. The authoring surfaces stay — a correction

An earlier draft of this plan proposed deleting the **"Generate from run" wizard** (~559 lines)
and **drag-to-retune / place-from-chart** (~330 lines) on the grounds that they were the largest
authoring surfaces and the least aligned with "simple and transparent".

**That was wrong, and it misread the requirement.** Rule 4 asks for simplification of *statuses
and the logic behind them* — the places where the product shows the tester something incorrect or
meaningless, like a run whose verdict is the word `INVALID`. It was never about the gestures that
let a tester declare an assert.

Both are kept, and the case for them is stronger than the case that was made against them:

- **Generate from run** turns a completed run into a declared rule set in seconds, *from observed
  values*. Name another performance tool that does this — JMeter itself does not. The alternative
  is reading numbers off a screen and hand-writing JSON, which is the three-run convergence loop
  this whole feature exists to end.
- **Drag-to-retune / place-from-chart** is how you write an assert *by pointing at the chart*.
  That is the shortest possible path from "this looks wrong" to "this is now gated".

**What was actually wrong with them is fixable and is not deletion.** The objection to
drag-to-retune was that it edits a threshold without showing the number it lands on. That is a
readout, not a reason to remove the gesture. Logged for the future, not scheduled here.

**The honest cost of keeping them:** both consult the status vocabulary, so **A3 must carry them
through the 7 → 4 collapse** rather than deleting them along the way. That is real work, and it is
the right trade.

### 2d. Explicitly kept

The client evaluator mirror and `assert-parity-vectors.json` (**the only regression brake the
feature has**); the `#assertsTable` inline-handler invariant;
the ⊕ / cell-verdict / sampler-badge layer; static rule mark-lines on charts; `AssertionValidator`;
`Attainment` (it *is* actual vs expected — rule 5).

---

## 3. Full CRUD

### The gap, measured

Of the ten blocks the document can carry, **one** is editable, and only for one origin.

| Block | C | R | U | D |
|---|---|---|---|---|
| `assertions[]` (`origin: file`) | ✅ | ✅ | ✅ | ✅ |
| `testNotes` | ✅ | ✅ | ✅ | partial — clearing writes `""` |
| `loadValidity` | ❌ | read-only rule row | ❌ | ❌ **← the defect** |
| `samplerNotes`, `timelineMarkers` | ❌ | ✅ | ❌ | ❌ |
| `comparisonThresholds` | ❌ | effective values only | ❌ | ❌ |
| `verdict`, `version`, `$schema` | ❌ | ❌ | ❌ | ❌ |

There is **no import path of any kind** — no file input, no `FileReader`, no drop target, no paste
handler. Rule 5's "open a JSON to override the current one" does not exist.

### What gets built

1. **Delete `loadValidity` as a concept** (§2a). At load, migrate an existing block into one
   ordinary `assertions[]` entry — `{scope: overall}`, the declared metric,
   `fail: {lt: target × (1 − tolerance)}` — with an INFO finding telling the user to save. The
   migration is **idempotent**: a user who never saves gets the same rule every run and loses
   nothing; a user who saves gets a fully CRUD-able rule. This is how the undeletable rule becomes
   deletable, without any file in the wild silently losing a check.
2. **`WIR.asserts.openFile(File)`** — a file picker plus a drop target that replaces the current
   document. Refuses when drafts are pending, naming the conflict and offering *Discard my drafts
   and load the file*. A reloaded file feeds the **draft** path, never `state.gate` — the baked
   verdict is what Java decided and must not be overwritten by a preview.
3. **A document editor** for the remaining blocks, so every key is reachable. This needs the
   editor's five hardcoded sections and `patchLive`'s four hardcoded element ids to gain a kind
   discriminator (§4, keystone-adjacent).
4. **Round-trip honesty**: unknown keys preserved *inside* blocks as well as at the top level, and
   `ruleToJson` stops dropping unknown per-rule keys.

### The draft model has to change

`{rules, deletions}` cannot represent "the `loadValidity` block was cleared" or "a note was typed".
`deletions` is a counter that never reaches `buildDocument`, and a Notes edit is read live off the
DOM so it is neither counted, persisted nor discardable.

**Replace it with one candidate document diffed against the loaded one.** Dirty state, the unsaved
pill, Discard and localStorage all derive from that diff. This is what makes CRUD over *any* block
possible without a second draft mechanism per block.

---

## 4. The flow

### Name the files unambiguously

You wrote "a flag that takes report.json", and the repo already emits `<name>.json` as a CI/CD
**output**. These are two different files and the plan must never conflate them:

| File | Direction | What it is |
|---|---|---|
| `report-annotations.json` | **input** | the asserts you author — the only file the GUI reads or writes |
| `<name>.json` (e.g. `E25-report.json`) | **output** | the CI/CD summary the run produces |
| `<name>.html` | output | the report |
| `<name>.xml` | output | JUnit, when enabled |

**Nothing in the plugin writes an annotations file** — verified. The run already consumes it. Your
"it should not generate a new one" requirement is met today and must stay met.

### The four steps

1. **Author** — in an editor against the published schema, or entirely in the Asserts tab.
2. **Import** — open that JSON from the GUI to override the current document (§3.2).
3. **Run headless** — `-Jwebinsight.report.annotations=/path/report-annotations.json`.
   This exists. What is missing:
   - `AnnotationLoader` must **fail the run** when an explicitly named file is missing or
     unparseable, instead of silently producing a gateless green report.
   - **`-Jwebinsight.report.failonassert=true`** (new) — exit non-zero on a FAIL verdict, and on
     FAIL only. Default off. *Deliberately not wired to `INVALID_RULE`*: a broken rule is a
     configuration error, not a failed assert, and gating on it would reintroduce exactly the
     internal-logic-produces-failure that rule 2 removes.
   - **An annotations-path field on the listener GUI.** Verified: `WebInsightReportListenerGui` has
     four controls and the annotations path is `-J`-only, with no `getPropertyAsString` fallback —
     unlike title/filename/outputdir, which have both. So the gate cannot be declared from the JMX
     at all. One `JTextField`.
   - **Provenance in the payload** — which file, resolved from where, auto-detected or not. Today
     auto-detection reads an *input* out of an *output* directory and only warns in the log.
4. **Read actual vs expected** — the Asserts tab already has Actual and Criteria columns.
   `Attainment` (built, uncommitted) adds `{actual, failThreshold, consumed, breached}` to the CI
   JSON; extend it to the HTML payload and one Asserts-tab column.

---

## 5. Milestones

Branch per milestone, as `plans/asserts-delivery.md` does.

| | Milestone | Branch | Status | Days |
|---|---|---|---|---|
| A0 | Land Phase 0 (see §7) | `fix/asserts-floor` | ✅ merged | — |
| **A0b** | **Retire the legacy threshold block** — code, schema, WIKI, README, CLAUDE.md, fixtures, specs (§2e) | `fix/asserts-floor` | ✅ merged | — |
| A1 | **Delete `loadValidity` + `Origin`**, with the load-time migration | `refactor/asserts-drop-loadvalidity` | ✅ done | — |
| A2 | Delete `tags`, `observe`, `onMissing`, `OUTSIDE`, `exactly()`, `verdict` | `refactor/asserts-drop-modifiers` | ✅ done | — |
| A3 | **`AssertionStatus` 7 → 4**, one producer, one fold; delete the badge-status fold, `worse` and `metricVerdicts` | `refactor/asserts-status-model` | ✅ done | — |
| A4 | Loudness: findings on gateless runs, `findings[]` in the CI JSON, the blocking banner, fatal loader | `feat/asserts-loud-config-errors` | ✅ done — the exit-code half followed in 3.1.0 | — |
| A5 | Draft model → candidate-document diff; `state.deletions` gone | `refactor/asserts-draft-document` | ✅ done | — |
| A6 | Document editor: every block CRUD-able | `feat/asserts-document-crud` | ✅ done | — |
| A7 | `openFile` import + provenance | `feat/asserts-import` | ✅ done | — |
| A8 | CLI: `failonassert`, GUI annotations field | `feat/asserts-cli-contract` | ✅ done | — |
| A9 | Actual vs expected: `Attainment` into the payload + column | `feat/asserts-attainment-ui` | ✅ done | — |
| A10 | Simplify the rules-table filter/sort subsystem. **The wizard and drag-to-retune are kept** — see §2f | `refactor/asserts-filter-sort` | ✅ done | — |
| A11 | Robustness sweep: mapper promise, synthetic id, match cap, badge colours | `fix/asserts-robustness` | ✅ done | — |
| A12 | Docs, schema, WIKI, fixture regeneration, upgrade note | `fix/asserts-robustness` | ✅ done | — |

**Core spine — A1 → A2 → A3 → A4 — is 16.5 days** (A0b is done) and delivers rules 1 (partly), 2 (fully)
and 4. Add A5 → A7 (7.5 d) for rule 1 in full, and A8 → A9 (2.5 d) for rule 5.
**Everything: ~34.5 days.**

Sequencing matters in two places: **A0b goes first** because it is small, self-contained and
removes a special case every later milestone would otherwise have to carry; and **the feature
deletions (A1, A2) go before the status collapse (A3)**, because each removes enum values,
evaluator branches and parity vectors that the collapse would otherwise carry through.

---

## 6. Migration for files in the wild

| Was | Becomes | Signal |
|---|---|---|
| `loadValidity` block | one ordinary `assertions[]` rule, idempotently, at load | INFO finding: save to make it permanent |
| `tags` on a rule | unrecognised key | WARNING. **A tagged rule that used to be deselected now runs** — the one direction that gets stricter; call it out in the release note |
| `observe: true` | unrecognised key → the rule has no bands → `NOT_JUDGED / INVALID_RULE` | WARNING + the "no warn or fail band" sentence |
| `onMissing` | unrecognised key | WARNING; unmatched targets become `NOT_JUDGED / NO_MATCH` |
| `outside: [lo, hi]` | unrecognised operator → `INVALID_RULE` | ERROR naming the two rules to write instead |
| the legacy threshold block | **an unrecognised key** — preserved verbatim on save, never evaluated, no special case anywhere | none; it is not a key we know about |

**Verdicts that move:** an INVALID or unmatched rule stops failing the run. Every affected run goes
from FAIL to a real verdict plus a banner. State this at the top of the release note.

---

## 7. What happens to the Phase 0 branch

`fix/asserts-floor` (v3.0.2, uncommitted, full regression green) fits this plan and should land
first — with one exception:

| Phase 0 item | Fate |
|---|---|
| Verdict on stdout; `logSummary` no longer silent | **keep** — becomes channel 3 of §1 |
| Header badge: no green "ALL PASSED" when nothing was judged | **keep** — it *is* the NOT_EVALUATED rule |
| Asserts tab revealed in an explicit unavailable state | **keep** |
| `loadValidity` into the schema | **superseded** by A1, which deletes the block. Harmless to land; delete with A1 |
| `Attainment` | **keep** — it is rule 5's actual vs expected. A9 extends it |
| `connectTime`/`latency` → NaN instead of a fabricated 0.0 | **keep**, but the consequence changes: NaN becomes `NOT_JUDGED / NO_VALUE`, not INVALID→FAIL |

---

## 8. Decisions — settled 2026-08-07

All but one are closed, and five of them fall out of the same governing principle above.

| | Question | Answer |
|---|---|---|
| 1 | A typo'd metric / an unmatched target stop failing the build | **Approved**, and reframed. A metric that does not exist in the run **cannot be interpreted, so no performance status comes out of it** — it is reported, and the tester decides. See below |
| 2 | The input file name | **`report-annotations.json`**, unchanged. It always was; "report.json" was shorthand in conversation |
| 3 | `-Jwebinsight.report.failonassert` | **Still open.** The only one. See below |
| 4 | Qualified error metrics (`errorRate:class=`, `code=`) | **Defer** — parked in `asserts-future.md` §4, not deleted |
| 5 | The wizard and drag-to-retune | **Kept.** My proposal to delete them was wrong — see §2f |
| 6 | Should an unrecognised key raise a WARNING? | **No warning status.** Mention it to the tester; do not grade it. An INFO-level note, never a severity that could read as a verdict |

### What decision 1 actually changed in this plan

The reframing is sharper than the version it replaces, and it simplifies §1.

`INVALID_RULE` stops being a *status* in any meaningful sense and becomes **a finding attached to a
rule that was not judged**. The engine is not deciding the rule is bad-and-therefore-failing; it is
saying *"I could not interpret this, here is what I could not interpret"*, and stopping. That is
the whole of it.

Concretely, this is unchanged from the design in §1 — four statuses, `NOT_JUDGED` carrying a
reason — but the **justification is now a rule rather than a trade-off**, which matters when the
next edge case turns up: the question to ask is not "should this fail?" but "**did the tester ask
us to judge this?**"

The six loudness channels in §1 stand exactly as written. Reporting is the whole job.

### The one still open

**`-Jwebinsight.report.failonassert`** — should a FAIL verdict set a non-zero exit code, or is the
CI/CD JSON enough for a pipeline to gate on?

It does not block A1, A2 or A3. It is A8. The argument for it: `jq` on a JSON file is one more
moving part in a pipeline, and `failonerror` already exists for artifact failures, so the shape is
familiar. The argument against: the JSON is already the machine-readable contract, and a second
gate is a second thing that can disagree with the first.

**My recommendation: build it, default it off, and write down that the default never flips.** A
default that changes later is how `failonerror=0` became a trap.
