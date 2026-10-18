# Stabilization — make the floor hold before the next features

> **Status:** shipped · 2026-08-10 · every phase delivered
> **Landed:** S0 (PR #77) · S3.3 minimal CI (PR #78) · S1 gate integrity (PR #79) · S3.1 the JS
> unit layer (PR #80) · S2, S3.2, S3.4, S3.5, S4, S5 on `fix/asserts-two-sided`.
> **Verified:** 653 Java tests · 85 JS-unit assertions · regression **6/6** with 568 browser
> tests · C01–C34 35/35 · N01–N09 9/9 · CI green.
> **Decision 6 (does the client-side verdict mirror stay?) remains open** — see §5. It no longer
> blocks anything, because the condition attached to the recommendation is now met: S2.1 and
> S3.1 both landed, so the mirror is a pinned feature rather than a liability. It should be
> re-asked when sustained breach is scheduled.
> **What §6 excludes is still excluded** — those are decisions, not leftovers.
> **Sources:** `temp/session-summary-2026-08-08.md`, `temp/code-review-and-summary-verification-2026-08-08.md`,
> and a 16-agent read-only sweep run 2026-08-09 that re-derived every reported finding against HEAD
> and audited the areas nobody had opened.
> **Follows:** [`asserts-core-hardening.md`](asserts-core-hardening.md) and
> [`asserts-config-errors-fail.md`](asserts-config-errors-fail.md), both shipped.
> **Precedes:** [`asserts-future.md`](asserts-future.md) — §7 revises its ordering.

---

## 1. What this plan is answering

The asserts rebuild landed with every tier green: 574 Java tests, 568 Playwright tests, 19
scenarios, 35 config stages, 9 error stages, 2 docker stages. A re-verification sweep then found
roughly a hundred defects, and the one number that matters is this:

> **Zero of the thirteen review findings would have been caught by any current suite.**

That is not an argument for more tests. It is the diagnosis: the suite asserts what someone
thought to assert, the refactor moved things nobody had thought to assert about, and the
mechanisms that were supposed to make a silent move loud — golden fixtures, parity vectors, the
XSS sweep — each have a hole exactly where the change landed.

So stabilization here means four things, in order, and **not** a general quality campaign:

1. Fix the defects that make **a gate lie** — a declared threshold that never gates, or a report
   and a pipeline that disagree about the same file.
2. Close the **mechanisms** that let those be invisible, so the next refactor is loud.
3. Make **docs, plans and the Claude flow true**, because they are what the next work is planned
   against and three of them do not survive being cloned.
4. **Stop.** §6 lists what was found and deliberately not scheduled. It is a third of the sweep.

Verification method note: every finding below was re-derived at HEAD by an agent that quoted the
actual line. The original review's own caveat was that its adversarial pass never ran — that pass
ran now, and it both **removed** claims (see §6) and **found the review understated several**.

---

## 2. The headline: four shapes of "broken gate definition", one policy that covers one

`asserts-config-errors-fail.md` established the policy that makes this project's gate trustworthy:

> A broken gate definition is a **configuration error, not a verdict**. It contributes nothing to
> `gate.status`, and it exits the process non-zero, because the file asked for a check that never ran.

The policy is right. Its coverage is one shape out of four. These four are one cluster, they share
one root cause — *`whyUnusable` and `hasConfigProblem` disagree about what "the rule can never
decide anything" means* — and they are the highest-value work in this document.

| # | Shape | What happens today | Where |
|---|---|---|---|
| **A** | One readable band, one unreadable band | `readBand` returns null with an ERROR finding, but `hasBands()` is still true via the good band → not `INVALID_RULE` → `hasConfigProblem()` false → **the declared FAIL threshold never gates, exit 0** | `AssertionRuleMapper.java:184` |
| **B** | A band that can never fire (`fail: {lt: -19}`) | The validator raises ERROR and says so in prose — *"The rule reports PASS on every run, including one that measured nothing at all"* — and then `whyUnusable` never checks reachability, so the rule **is judged and contributes PASS**, exit 0 | `AssertionValidator.java:182`, `AssertionEvaluator.java:180` |
| ~~**C**~~ | ~~A metric whose JTL column is absent~~ | **Does not reproduce — see below. There are three shapes, not four.** | — |
| **D** | The browser's own parser | `bandFromJson` accepts a retired operator; `breaches()` hits `default: return false` → **PASS in the report** while Java calls the same file `INVALID_RULE` and exits non-zero | `report.js:8305` |

D is the worst of the four, because it is the two halves of the product disagreeing about the same
file — the report says the run is green, the pipeline fails the build, and no test in either suite
is looking. It is also the reason §4.2 exists.

**C was reopened on 2026-08-10, as decision 5 required, and it does not reproduce.** GAP-4 claimed
that `latency`/`connectTime`/`maxThreads` read a silent `0` against a JTL lacking those columns, so
a well-formed rule passed vacuously. Three checks, all negative:

1. **The `-g -o` path cannot be reached.** JMeter's own report generator refuses a JTL with no
   `Latency` column before our exporter runs at all: *"No column &lt;Latency&gt; found in sample
   metadata … check #jmeter.save.saveservice.* properties"*. The scenario the finding describes
   aborts one layer above us.
2. **A present-but-zero column is a real measurement.** Against E25's JTL (which carries `Latency`
   with genuine zeros, because the plans are timer-driven simulations), `latency fail gt 1` returns
   PASS — which is correct. The sampler measured zero; the plugin did not invent it.
3. **The model already carries the absence.** `SamplerStatistics.meanLatency` and `meanConnectTime`
   default to `Double.NaN`, and `SamplerAggregator` only assigns them when its guarded count is
   above zero — a comment on the field says exactly that. So an unmeasured latency is NaN, and
   `AssertionEvaluator.judge` already routes NaN to `NOT_JUDGED / NO_VALUE`. The mechanism the
   finding asked for is the one that is already there.

So the "fifth shape would be one line" claim was never tested, because there was no fifth shape.
That is a better outcome than the one predicted and it costs an item off the list — but it is worth
recording that §2.1's central claim remains **unproven** rather than proven.

**D's fate depends on decision 6** (§5): if the client-side verdict mirror goes, shape D goes with
it by construction. That decision has to be made before S2 is scoped.

### 2.1 · The root cause is one design gap, not four bugs

A and B are the same defect wearing two hats, and naming it changes what S1 should be.

**"Can this rule decide anything?" is answered twice, in two classes, and nothing compares the
answers.** `AssertionValidator` answers it one way and emits an ERROR finding; `AssertionEvaluator.whyUnusable`
answers it another way and decides whether the rule is judged. Findings do not gate. So the
validator can be completely right and completely inert.

The proof is in the tree, in the validator's own javadoc (`AssertionValidator.java:151-164`), which
exists *because of this exact bug class*:

> "…that is how a `loadValidity` tolerance of 20 — written by someone who meant 20% — shipped as
> `lt -19 × target` and reported PASS on every run, in the feature whose whole job is refusing to
> pass a run that never applied its load."

The fix for that was written. It went into the validator. `whyUnusable` never asks the validator
anything, so the rule is still judged, still returns PASS, and still exits 0 — which is finding
CORE-2, the same bug the comment describes, surviving the fix written for it.

**So S1's first move is to make one function the single answer**, used by both the validator (to
explain) and `whyUnusable` (to decide). That closes A and B together, makes D's server half
structural, and — this is the point — makes any *fifth* shape a one-line addition that cannot be
half-done. Fixing the four shapes separately leaves the gap that produced them.

**Sequencing warning, from the critic pass.** A and B both convert "a file that passed yesterday"
into "a build that fails today", and so does the `observe: true` decision in §3.6. Landing all
three in one release without saying so is how a stabilization pass becomes an incident. They need
**one shared upgrade note** and a config stage **per shape**, not one stage for the pair.

---

### 2.2 · Why none of the automation saw it — and what that changes

The project has a scenario suite, a config suite, an error suite, 568 browser tests, parity vectors,
and a Claude agent that drives the real report in a real browser. All of it was green. Five separate
reasons, each of which is structural rather than an oversight:

**1. Every surface grades the report, not the gate's reasoning.** A rule that could never have
failed renders as a green PASS row — which is pixel-identical to a rule that passed. The Chrome
exploration inherits exactly this blindness: there is nothing to *see*. You cannot observe the
absence of a possible failure. This is worth writing into the `explore-report` skill, because
otherwise browser exploration keeps being trusted for the one class it structurally cannot cover.

**2. Every fixture input is well-formed.** E25 is the only `assertions` array in the scenario tier
and its rules are deliberately extreme-but-valid. N01–N09 covers malformed *files* — unreadable,
bad JSON, missing. Nothing covers a **well-formed file whose rule is meaningless**. That whole class
has no fixture, so no suite could have failed on it.

**3. The one mechanism built for this bypasses the half where the bugs live.** The parity vectors
pin the two *evaluators* — through two *test helpers* that normalise the rule first. All four shapes
are parse/validate concerns. And parity only proves the two sides **agree**: `observe` is wrong on
both sides identically, so parity would stay green forever.

**4. The design gap in §2.1** — validation and evaluation answering one question twice, with
findings that do not gate.

**5. The exit-code policy keys on a single enum constant.** `hasConfigProblem()` ← `gate.invalidRule()`.
Any way of being broken that does not route through `INVALID_RULE` is invisible to the policy *by
construction*. When the policy was written every shape routed through it; each new shape has to be
remembered by a person.

Behind all five, one habit worth naming: **a green suite was read as a verdict.** It was evidence
that nothing *previously asserted* had broken — which is a different and much weaker claim.

**What this changes in the plan** (these are not extra work, they are the same work aimed better):

- §2.1's single usability answer, so a new shape cannot be half-implemented.
- A **negative fixture** whose entire purpose is rules that must be refused — this is golden G3 in
  S3.2, and it gives the class a permanent home.
- Routing the vectors through the **production** parsers (S2.1), so parity covers the half where
  these live.
- The Node harness (S3.1), which makes "can this band ever fire" a millisecond unit test instead of
  a browser run — the reason such tests do not exist today is that the cheapest one costs a browser.

## 3. The work

Effort is engineer-days, assuming the existing test discipline (every fix pinned, or a written
reason why not). Refs are the sweep's finding ids; the full rows live in
`temp/` alongside the two source documents.

### S0 — Land what exists · ✅ **done 2026-08-09**

*Corrected twice: first I called it a blocker (it was not — the challenge was right), then it was
done anyway because the reconciliation turned out to be free.*

PR #77 merged; `origin/main` and local `main` are both **`1a22330`**, zero divergence. It
fast-forwarded exactly as predicted, because local `main` was byte-identical to
`origin/feat/asserts-refactoring`.

Still outstanding from this phase: **tag the release** (§5 decision 1 — 3.2.0, staying on the
current scheme). Not urgent, and not blocking anything.

**Do not** push `main` directly; the hook refuses it, correctly.

### S1 — Gate integrity · 2–3 d · the four shapes plus the flags around them

**✅ Done 2026-08-10** on `fix/asserts-gate-integrity` (`e4ade7f`, `673d975`, `d49c016`).

Measured before, on one `jmeter -g` over four rules: gate FAIL, `invalidRule 0`, **exit 0**, shapes
A and B reporting PASS and shape D2 supplying the only red — with two ERROR findings in the output
changing nothing, one of which says in those words that the rule *"reports PASS on every run"*.
After: all three `NOT_JUDGED / INVALID_RULE`, `invalidRule 3`, **exit 1**, one finding per mistake,
and the gate itself back to PASS because the FAIL had been manufactured by a rule whose retired key
meant *never gates*.

Three things the work established that the plan did not predict:

- **The tests found the membership rule for retired keys.** `tags` broke a test whose own message
  explains why — *"the rule runs — tags no longer deselect anything"*. So a retired key refuses only
  when ignoring it **inverts** the rule: `observe` (a silent rule becomes able to turn the badge
  red) and `onMissing` (a red build goes green) qualify; `tags` does not, because ignoring it runs
  more rules rather than fewer.
- **Reporting and refusing are different jobs.** The first cut had the validator render
  loader-found problems and double-reported them, breaking `anUnreadableTargetIsReportedOnceRatherThanTwice`.
  `Usability.loaderReported()` keeps one finding per mistake while the fact still refuses the rule.
- **`LoadValidityMigration`'s clamp was a fix that was not one.** It existed to prevent an
  unreachable band and could not: clamping tolerance to 1 yields `lt target × 0`, which is `lt 0`
  and equally unreachable — and is the *most lenient* outcome available, the exact "accept any
  throughput at all" its javadoc said it ruled out. Removed, now that a real check exists.

**Do §2.1 first.** One `usability` answer shared by the validator and `whyUnusable`; then A and B
are each one line inside it rather than two independent repairs.

| Ref | Item | Sev | Eff |
|---|---|---|---|
| §2.1 | **One answer to "can this rule decide anything"**, used by both the validator and `whyUnusable` | high | M |
| P1-6 | Shape **A** — a partially readable band must be `INVALID_RULE` (falls out of §2.1) | high | S |
| CORE-2 | Shape **B** — an unreachable band must be `INVALID_RULE` (falls out of §2.1) | high | S |
| ~~GAP-4~~ | ~~Shape **C**~~ — reopened and **closed as not reproducible**, 2026-08-10 | — | — |
| D2 | `observe` becomes a **named retired key** — refuse the rule and say so, both with and without bands (§5 decision 2) | high | S |
| CORE-1 | Overall-scope matches are keyed by the display string `"Whole run"`, not `OVERALL_KEY` — so `assertViolations[].sampler` names a sampler absent from `samplers[]`, and a plan with a sampler literally called "Whole run" merges two verdicts into one | high | S |
| P1-7 / RPT-1 | `failonassert` is listener-only — same FAIL verdict exits 1 under `-n -t`, 0 under `-g -o` | high | S |
| P1-8 / RPT-9 | `failonassert=1` silently disarms — the only boolean without `BooleanSetting.warningFor` | medium | XS |
| P1-5 / RPT-2 | `NonZeroExit` blames `failonerror` for an exit `failonassert` armed | medium | S |
| P1-4 / RPT-6 | `clearGui()` does not reset the annotations field → one configured path leaks into every listener added that session **and is saved into the JMX** | high | XS |
| P1-9 / RPT-7 | The GUI annotations path is absent from the listener→exporter handshake → a combined `-n -t -e -o` run emits two artifact sets that disagree about whether a gate existed | high | M |
| GAP-1 / RPT-3 | The `loadValidity` migration notice is **dead on every real run** — `AnnotationLoader` consumes the block before `GateEvaluation` can report it. Fixing this at `GateEvaluation` would be fixing the wrong file | medium | S |
| GAP-2 / RPT-4 | `CONFIG ERROR` is suppressed whenever an artifact also failed — and the two ingestion paths suppress it under **opposite** flag settings | medium | XS |

**P1-9 — verified live, 2026-08-09. It is real, and worse than the finding described.**

A `quick-smoke-test.jmx` carrying `WebInsightReport.annotationsFile` on the listener element (what
the GUI saves), run as `jmeter -n -t … -l … -e -o …` against `asserts-strict.json`:

```
listener-out  runId=cd2d214d98666113  assertsEvaluated=true   gate.status=FAIL, 4 declared / 4 failed, 6 violations
exporter-out  runId=cd2d214d98666113  assertsEvaluated=false  gate=null
EXIT=0
```

The exporter announces it on the console — `Asserts: NOT EVALUATED — nothing was declared, so
nothing was judged` — on a run that declared four asserts and failed all four.

The aggravating detail nobody predicted: **the `runId` is identical.** These are not two reports
that happen to disagree; they are two artifacts carrying the same fingerprint, which by
`RunIdentity`'s own contract means *the same run*, giving opposite answers to "was this run gated".
A pipeline globbing the `-o` directory — the conventional place, since `-o` is what CI passes —
reads the gateless one and sees a green build. The exists-policy did not skip the exporter because
the output directories differ, exactly as predicted.

`run-family-cd-tests.sh` D01–D06 is the only existing coverage of the combined handshake — and it
is in a runner no documented tier invokes (§3.5), which is why this survived.

### S2 — The two-sided disagreements · 2–3 d

Everything here is the report and the engine answering the same question differently.

**S2.1 · The second parser.** `ruleFromJson` is not one second parser — it is one of **four**
JSON-to-rule paths, and the 127 parity tests exercise **neither production one**: both harnesses
normalise the vector's rule through a *test helper* before handing it to the evaluators. Eight live
divergences between `ruleFromJson` and `AssertionRuleMapper` were enumerated at HEAD.

The cheap route, and it is much cheaper than it first looked:

1. Fix the eight JS parser divergences (two-operator band, retired `outside`, non-numeric value,
   multi-form target, blank/empty target, scope case, `id: null`, id derivation). The last one
   needs `AssertionTarget.slug()` ported — ~15 lines and the highest-value of the eight, because
   today **opening the document editor renames every id-less rule**, rekeying drafts, chart lines,
   sampler badges and findings.
2. **Then** delete `AssertionParityTest.rule()` and `asserts-parity.spec.ts:ruleFrom()` and route
   the existing `cases[].rule` through `AssertionRuleMapper` and `_ruleFromJson`. This converts all
   58 existing evaluator vectors into parser vectors **for free**. Doing it before step 1 fails 58
   tests for unrelated reasons.
3. Add a small `parseCases[]` block only for the shapes a well-formed vector cannot express.

Effort **S, not L** — the original framing ("write ~20 new vectors") was the expensive route.

> **Pay for it first:** `asserts-parity.spec.ts` currently does a full `loadClean(page,'E25')` per
> vector — 64 loads of a 1.7 MB report. Collapse the block to one `page.evaluate` returning an
> array. That alone funds the extension.

**S2.2 · Attainment, then the Budget column — in this order.** Three findings interlock and the
order is not free:

1. **Memoise** — a per-render `attainmentIndex`, invalidated beside `verdictIndex = null`.
2. **Re-route** `attainmentOf` off the capped `rule.results` and onto `verdictsBySampler`, and fix
   the second divergence the review missed: `breached` is `rule.status === 'FAIL'` client-side
   against `fail.breaches(worst)` in `Attainment.java:121`.
3. **Then** enable the Budget sort (`SORT_LABEL` + `SORT_DEFAULT_DIR`; `compareBy`'s budget case
   and `consumedOrFloor` are already correct and must not be deleted as dead).

Reversed, step 3 turns an array scan into O(N log N) full re-evaluations on exactly the rule sets
the cap exists for. Add `attainment` to the vector `expect` block as a **fraction** — the
"96% vs 0.96" framing is presentation, not defect.

**S2.3 · The XSS gap.** `renderBrokenBanner` interpolates a rule id into an inline `onclick` via
`esc()`, which does not escape `'`. Use `jsArg()`, and **widen `xss-escaping.spec.ts` from
`#assertsTable [onclick]` to `#tab-asserts [onclick]`** — the banner sat outside the sweep's scope,
which is why a structural check missed a structural violation. Rule ids reach the file from sampler
names via the ⊕ buttons and the wizard, so this is JTL-derived content.

*(Correction worth recording: the "61 unescaped `innerHTML` sites" reading is wrong. There are 61
write sites; exactly **one** is context-unsafe and it is this one. A `setHtml()` helper is not the
buy — this is a missing call, not a missing abstraction.)*

**S2.4 · Round-trip key preservation.** The promise is "an unknown key is ignored *and*
preserved". Four ways it leaks, and they must be sequenced:

- `loadedEntryOf` can **never** match an id-less rule with a valid target, so a plain Download
  destroys its unknown keys with no rename and no edit (P1-13b — this is the correct description;
  "renaming orphans the entry" is a strict subset that understates it).
- `editorRule()` drops `idDeclared`, so any save writes the plugin's derived id into the tester's file.
- `AssertionRuleMapper` marks an id-less unusable-target rule as `idDeclared`, defeating the flag
  exactly where it matters (P1-13d) — **and until this lands, the interim fix for P1-13e is a
  no-op that reads as fixed.** Either do 13d → 13e, or skip to 13b's `declaredIndex` and delete 13e.
- `restoreDrafts` does not restore `docBase`, so after a refresh a Download writes a hybrid document.

**S2.5 · One-line data loss, listed here because it is real and cheap.** `persistDrafts` stringifies
`state.rules` **including uncapped `results`** into localStorage on every keystroke and swallows
`QuotaExceededError`. At 200 rules × 500 samplers that is ~6 MB against a 5 MB budget: the tester's
unsaved thresholds are gone on refresh, silently. Strip `results` before stringify —
`restoreDrafts` calls `recomputeDraftGate` anyway, so storing them buys nothing.

### S3 — Make the invisibility impossible · 3–4 d · *the part that pays forward*

**S3.1 · A Node unit harness for `report.js`.** ✅ **Done 2026-08-10** (`0dacd92`) — and it was the
highest-leverage item found, as predicted, for a reason the estimate did not include: **it made the
JS half of parity gap #1 nearly free.**

`asserts-parity.test.js` runs all 58 vectors through the **production** `ruleFromJson` in
**0.071 s**. The browser spec drives the same vectors but builds the internal rule shape with a
hand-written helper — exactly as `AssertionParityTest.rule()` does on the Java side — so the
vectors pinned the two *evaluators* while the two *parsers* were compared by nothing at all. All
58 agree, so the reroute is safe on the Java side too, which is what makes S2.1 cheap now rather
than L-effort.

Also confirmed: **the JS half of S2.1 is done, and it is the half that could not be seen.** What
remains of S2.1 is the Java reroute plus the `attainment` vectors.

Measured shape of the module, for the record: 25 pure-function assertions plus 58 vectors = **83
assertions in 0.18 s**, against ~2 minutes for the browser suite. The document stub **refuses**
rather than no-ops — every method throws with a message naming Playwright — so "no DOM in here" is
enforced rather than remembered.

Measured, not estimated: the only module-level DOM contact in all 11,341 lines is
`document.addEventListener('DOMContentLoaded', …)` at `report.js:5944`. Test seams are already
exported (`evaluate`, `_formatValue`, `_editorRule`, `_state`). `WIR.asserts` is 5,338 lines, of
which **3,414 are code** and **1,995 (58%, 152 of 210 functions) are pure or markup-only**.

A **~30-line harness** — stub `window`/`document`, eval the file, require the vectors — makes that
58% unit-testable in milliseconds, with **zero product change, zero build step, no bundler**. Every
parser finding in this document lives inside that 58%. Today their only runner is a browser booting
against a 1.7 MB fixture.

Precondition: rename one of the two `formatValue` functions (top-level chart formatter at 2704 vs
asserts formatter at 6714, incompatible contracts, no error if it rebinds). It is the one name
collision between the top level and the IIFE, and it sits directly on the path of any extraction.

**S3.2 · Golden artifacts, automated — as an `R06` stage.** The documented manual substitute
("regenerate everything, diff the `.json` with `runId` stripped, expect empty") **cannot work as
written**: the fixtures are live timer-driven runs, so `duration`, `totalSamples`, per-sampler
means and `generatedAt` all move. That is why it has never been run.

The contract *same JTL ⇒ same runId* names the fix: build goldens on a **fixed, committed JTL
through the `-g -o` exporter**, never on a live listener run.

- Four inputs: plain run · a document exercising PASS/WARN/FAIL and all four `NOT_JUDGED` reasons ·
  a config-error document · a run with a baseline.
- Compare the `.json` and `.xml` only — not the 1.7 MB HTML, whose meaningful content the browser
  specs already assert on.
- Canonicalise: drop `runId` (a fingerprint designed to change — pinning it asserts the opposite of
  its contract), drop `generatedAt`, basename the artifact paths, drop JUnit timestamps.
  **Sort nothing** — key order is part of the contract.
- Baselines **committed** under `test-plans/goldens/expected/`, ~100 KB total. This is the point at
  which `test-results/` being gitignored stops mattering, and it restores the property the deleted
  snapshots had: *the diff appears in the PR*.
- `run-regression.sh --accept-goldens` regenerates; R06 fails if `expected/` is dirty while the
  stage passes, so accepting is deliberate and never a side effect.
- Cost ~40 s: +38% on the cheap regression path, +7% on the regenerating one.

**S3.3 · A minimal CI — restore the job that was deleted as collateral.** ✅ **Done** —
`.github/workflows/ci.yml` on `chore/minimal-ci`. `git show 4a72faf^:.github/workflows/ci.yml`
is the file. It ran `mvn verify` plus the static checks and its own header says "about a minute".
It was deleted in the same commit as `regression.yml`, and the measured 15m48s objection is
**entirely about the wall-clock-bound fixture tier** — the fast tier is 574 tests summing to **2.7 s
of Surefire time** plus five greps, which run at the same speed on any machine. That is the
definition of machine-independent.

Three edits to restore it: swap the inline exec-bit block for `verify_script_modes` (which did not
exist then), add `verify_line_endings`, keep the permissions/concurrency/failure-only-upload shape.
It closes the hole CLAUDE.md names explicitly — *"any manual `git push` reaches the remote untested;
there is no later gate that catches it"* — for the majority of changes.

**Measured, PR #78: ~55 s warm** (estimate was ~1m40s), and it earned its keep on the first run it
ever performed:

```
ReportArtifactsTest.anInvalidPathIsNamedAsSuch:255
  Expected java.nio.file.InvalidPathException to be thrown, but nothing was thrown.
```

The test obtained its exception by asking the filesystem to parse `D:\out\rep:ort.html`. A colon
inside a path segment is **illegal on Windows and an ordinary filename character on Linux**, so the
test asserted a property of the *platform* rather than of `describe()`. One failure out of 574, in
the module that had never once been built on Linux. Fixed at `1f36eee` by constructing the
exception directly — what it means to pin is unchanged, and is now pinned on every platform.

That is the case for this tier in a single test: the suite was green for exactly as long as the
only machine that ran it was this one.

Two conditions, so this does not become the thing the no-CI decision rejected: **keep
`regression.yml` deleted**, and **add no required status check slower than this job**. The pre-push
hook keeps its one rule and runs no tests.

It also fixes `verify_shaded_jar`'s silent no-op (`command -v jar || return 0`, `common.sh:253`),
because `jar` is guaranteed on PATH under `setup-java`. Fix the local no-op too — a check that
passes when it cannot run is worse than no check.

**S3.4 · Four grep-shaped static checks in S03/R03.** Each one would have caught a finding above:

1. every `getElementById('literal')` resolves to an id in `report.html` or one `report.js` emits;
2. every inline `WIR.asserts` handler interpolation goes through `jsArg`;
3. the literal `'wir-chip-'` appears only inside `chipClass`;
4. `assertsTable` is never an argument to the generic table helpers *(currently true, unpinned)*.

**S3.5 · Point the tiers at the runners that exist.** The repo ships **11 runners and ~223 stages**;
four are documented and seven are invoked by no tier and named in no doc — including **B12**, which
CLAUDE.md cites as a load-bearing exemption, and **D01–D06**, the only coverage of the combined
`-n -t -e -o` handshake where S1's P1-9 lives. A documented exemption defended by a stage nobody
runs is not defended. Also: `run-config-tests.sh` costs **13m22s** strictly serially — 63% process
overhead — and no doc states it, which is why it gets skipped before releases.

### S4 — The no-BS pass · 2 d · bounded deliberately

The sweep found far more removable code than is listed here. What survived the cut is what a
**future change would trip over**; the rest is in §6.

| Ref | Item | Why it makes the cut |
|---|---|---|
| NEW-judging-row-blind | Four `samplerTable` DOM walkers do not know about `.wir-judging-row`, the detail row the rebuild added | The asserts work taught the sampler table a new row type and told none of the walkers. **Every new feature adds row types** |
| NEW-double-detail-row | `toggleSamplerDetail`'s collapse guard checks only `nextElementSibling`, so an open judging row makes it build a **second** chart panel — up to 7 orphaned ECharts instances, leaked again per gesture | Same root cause, and it leaks memory |
| NEW-dup-table-helpers | Two parallel table-helper families, ~330 near-duplicate lines; the divergence between them **is** where the two findings above live | Fixing the symptom twice is the alternative |
| NEW-formatvalue-shadow | Two `formatValue`s with incompatible contracts | Precondition for S3.1 |
| CORE-3 | `JtlParser.consume` swallows every `RuntimeException` across 45 lines **including engine mutation**, and `describe(e)` — written to render the reason — has no caller. The recovery path can double-count a sample already recorded | The last `ComparisonThresholdResolver`-shaped swallow in core. That shape is the one this project has already decided is a defect |
| CORE-6 / P-STALE-1..3 | Retired `INVALID`/`SKIPPED` vocabulary as present-tense claims in six core files | The repo's own stated failure mode is planning the next change against comments like these |
| CORE-4/5, RPT-10..14 | Dead API in `core/assertion`, dead serializer overloads, `evaluateSla()` with a false javadoc, the renderer's second `GateEvaluation.of` call site, duplicate imports | Named, small, and each is a trap for a consolidation pass |
| GAP-6 | Two `serializeChartData` overloads silently produce a **gateless payload** and have no production caller | A loaded gun aimed precisely at a "collapse these overloads" cleanup — i.e. at this phase |
| RPT-13 | The listener publishes `configuration` half-built, two lines below the javadoc forbidding exactly that | The documented concurrency shape is the thing being violated |
| NEW-dead-css, NEW-dead-markdown-path, NEW-dead-template-ids, NEW-dead-functions | ~120 lines of CSS matching nothing, two permanently no-op init functions, six unread template ids, two uncalled functions | Cheap, and `.wir-legacy-head` reads as evidence a deleted panel still exists |

Two more with a real user-visible edge, folded in here: `NO VALUE` chips build
`class="wir-chip-no value"` — two junk classes, no styling, on any run with an unmeasurable rule
(`chipClass` does it correctly three lines away); and `showAllCharts` is document-wide while its two
siblings scope to their tab, so un-hiding on Timeline silently un-hides Compare and Errors and
leaves their buttons stale.

### S5 — Docs, plans and the Claude flow · 2 d

**S5.1 · The Claude agents' limits are prose, not capability. Fix that; the tracking can wait.**

Two separate problems were found here, and only one is scheduled.

**Not scheduled — single-machine tracking.** `.gitignore:111` is `.claude/*` with only
`!.claude/skills/` re-included, so the three subagents and the `.claude/settings.json` that
registers the Stop hook are untracked and exist on one machine. **Accepted 2026-08-09:** local-only
is fine while it works locally. Recorded so that "the design lives on one disk" is a known
condition — it becomes real work the day a second machine or a second contributor appears.

**Scheduled — the capability limits, which are broken on *this* machine too.** The restrictions
CLAUDE.md calls "the design, not a detail" are enforced by nothing:

- `spec-author` has **unscoped `Edit`/`Write`**, so it can change the product to make a spec pass —
  the exact failure its own file describes, and the one thing the agent exists to make impossible.
- All three hold **unrestricted `Bash`**, so `design-scout` is not "read-only everywhere" and
  `report-explorer` can edit through a shell regardless of having no `Edit` tool.

This is worth fixing on its own terms: an explorer that can edit fixes what it should report, and
then no spec is ever written. One commit, `.claude/agents/*.md` frontmatter only.

**S5.2 · The skills.** `_perf_release` runs 4 of 11 runners, so **8 scenarios never execute for a
release** (including E20, the only external-data payload check); it names the config tier as
`C01-C16`; and it has no rule for `<revision>` running ahead of the last tag, which is the exact
state the repo is in today. `run-tests` hardcodes 224 Java / 275 browser tests against real numbers
of 574 / 568 — and the two browser agents state a **third** number, 543. No skill covers moving a
finished branch to `origin/main`, which is why S0 exists as a manual step.

**S5.3 · CLAUDE.md.** Seventeen falsifiable claims have drifted. The two that matter most:

- *"E25 carries the only `assertions` array"* — **three other fixtures carry one**, and E10/E11 are
  in `FIXTURE_IDS`. Anyone planning assert work concludes a verdict move on E10–E12 is unreachable
  by the fixtures. It is not.
- The single largest structural fact about the asserts system — **that the rule engine is
  implemented twice and held in step by one vector file** — is **absent from the architecture doc**.
  A change to `AssertionEvaluator` planned against CLAUDE.md will not know it must land in
  `report.js` too, nor which test says so.

Then: rule states still listed as seven; the external payload documented as the fixed name
`web-insight-data.js` when it is derived from the report name; the staging filename pattern missing
its `owner` segment; *"the sweep predicate is process liveness, not age"* when a 6-hour age backstop
exists (and that sentence sits in a list whose purpose is to stop people "fixing" those behaviours);
"all three" static checks when four run; `.githooks/pre-push` called a "Regression gate" in Key
Directories, which is the belief the entire no-CI section exists to destroy; `~28 innerHTML places`
for 61; the 35th config stage attributed to C19 when it is **C17b**; four runners listed where
eleven exist; and `scripts/list-scenarios.sh` — **the generator that answers every stage-range
question** — unmentioned, which is precisely why those ranges drift.

On deriving numbers rather than typing them: **decide per fact, not as policy.** Stage counts, tab
ids and the rule-state vocabulary have a single authoritative command and should be generated. A
count of `innerHTML` sinks should simply **not be written down** — it is not a number anyone plans
against, and a stale one sets a scope estimate for the one review that must be exhaustive.

**S5.4 · WIKI.** Fourteen drifted claims. `Instructions.md` is the most dangerous file in the repo
to leave stale — CLAUDE.md forwards to it, and three of its errors compose into a developer running
the wrong commands and getting a green suite: 9 fixture scenarios instead of 11, "E19 was deleted"
while its own table and the runner both use it, and the **five-file fixture-stamp description**
that CLAUDE.md records as the bug that let a `core/stats` change leave every stamp matching.

Then: `FEATURES.md` still says the report has **six tabs and omits Asserts**, and its only CI/CD
JSON sample predates the gate block; `UPGRADING-ASSERTS.md` says **one** key moved when **four**
did — the section it tells you to read if you read nothing else, under-reporting the breakage 5:1,
while a pipeline running `jq -e '.gate.unmatched == 0'` now fails on every run; and
`Tutorial/10` and `UPGRADING` give **opposite instructions about `loadValidity`** on the same site,
which is worse than either being wrong alone because a reader cannot pick.

**S5.5 · The plans folder, and the convention that stops it rotting.** Five of six plan files have
a status header that is wrong; the sixth has none. Nothing is archived, there is no index, and
CLAUDE.md never mentions the folder.

- `asserts-config-errors-fail.md` says *"not started"* — its own §7, 278 lines below, says
  *"Landed as 3.1.0"*.
- `asserts-core-hardening.md` says **"Done: 14 of 14"** nine lines above a table with **five rows
  marked "not started"**, both written the same day. Its header also states the exit-code policy as
  settled *"never"* — which the same file reverses 44 lines later, and the reversal is what shipped.
  A reader who stops at the status board leaves believing the opposite of the product's behaviour.
- `asserts-flow-diagrams.md` **D4's caption states the exit-code policy backwards**.
- `design_handoff_asserts/` is labelled "the normative design" and several of its closed decisions
  were reversed — it is the only live *instruction* in the folder and it is wrong.
- `asserts-future.md` is a genuinely valuable backlog whose **unpark condition has fired**, and two
  of its items are already satisfied by shipped code.

One file is the worked example of doing this right: the config-errors plan's implementing commit is
**+53 lines, 0 deletions** — §1–§6 byte-identical, one appended §7 recording where the plan was
wrong. That is the pattern to codify.

**Proposed convention** (paste into CLAUDE.md as a `## Plans` section; full text in the sweep notes):
every plan opens with `> **Status:** <state> · <date> · <version or sha>` where `<state>` is one of
**draft / accepted / in-flight / shipped / parked**; **one status, one place** (at most one
milestone table per file, derived from the header); a **shipped plan moves to `plans/archive/` in
the merge commit that completes it**, so `plans/` answers the only question it exists to answer —
*what is not done*; history is preserved by **appending a `## What actually happened` section, never
by editing**; a reversed decision is **annotated in place**; a parked plan carries an unpark
condition and a `Citations re-verified` date, because citations rot in days — two of
`asserts-future.md`'s decayed within 48 hours of being written.

Enforced by `verify_plan_headers` in the S03/R03 static block: four greps, no judgement. The
judgement half — *did this change complete a milestone?* — belongs in the `dev-cycle` skill, for the
same reason the version bump does: a hook cannot know which milestone a diff finished.

---

## 4. Sequencing, and why this order

```
S0 land ──▶ S1 gate integrity ──▶ S2 two-sided disagreements ──▶ S3 mechanisms ──▶ S6 features
                     │                      │                          ▲
                     └──────────────────────┴── S4 no-BS, S5 docs ─────┘  (parallel, low risk)
```

- **S1 before S2** because a gate that lies is worse than two surfaces that disagree about a gate.
- **S2 before S3.2/S3.3** because the parser fixes *are* what the extended vectors pin; goldens
  built on today's behaviour would enshrine four of the divergences.
- **S3.1 before everything else in S3** — the Node harness is what makes S2's fixes cheap to keep.
- **S4 and S5 are parallelisable** and carry almost no regression risk, but S4 must come *after*
  S2's parser work, not before: the "dead" code in the asserts module includes the Budget
  comparator, which is correct and merely unreachable.

### 4.1 · Fund it in two tranches, not as one block

**Do not commit 10–13 days up front.** Fund the first tranche, then checkpoint.

**Tranche 1 — S0 + S1 + S3.1 · ~3–4 days.** Land PR #77, the gate-integrity batch starting with
§2.1, and the Node harness. That is where the *"a gate lies"* risk lives, and S3.1 is what makes
everything after it cheap. **Then stop and reassess** — S1's findings will change the price of what
follows, and decision 6 has to be ruled on before S2 can be scoped at all.

> **Tranche 1 progress, 2026-08-10**
> - ✅ **S0** — PR #77 merged, `origin/main` == local `main` == `1a22330`. Release tag outstanding.
> - ✅ **S3.3** — PR **#78** merged, green in ~55 s, and it caught a platform-dependent test on its
>   first run.
> - ✅ **P1-9 homework** — reproduced, then fixed; see S1.
> - ✅ **S1** — on `fix/asserts-gate-integrity`, three commits. §2.1 (`Usability`), shapes A/B/D2,
>   the JS mirror, the migration conflict, and every flag row. Verified end to end against a saved
>   before-state. **594 Java tests, regression 5/5 with 568 browser tests, N01–N09 9/9, C01–C34
>   35/35.**
> - ✅ **S3.1** — on `feat/js-unit-harness`. 30-line harness, two suites, **83 assertions in
>   0.18 s**. It also closed **parity gap #1 on the JS side** (below), which was S2.1 work that
>   turned out to be nearly free once the harness existed.
>
> **Shape C is now reopenable** (decision 5): §2.1's shared answer exists, so routing "JTL column
> absent" into `NOT_JUDGED / NO_VALUE` is the one-line addition the plan predicted. It is the test
> of whether the design claim was true.

**The homework is done.** P1-9 was the one reasoned-not-observed row, and a manual `-n -t -e -o` run
on 2026-08-09 **confirmed it** — two artifacts, the same `runId`, opposite answers about whether the
run was gated, exit 0. Full evidence in S1 above. The M effort is warranted, and the fix now has a
second requirement the finding did not state: **two artifacts that share a `runId` must not be able
to disagree about the gate.** That is the invariant to pin, not just the missing handshake.

> **Tranche 2 progress, 2026-08-10** — on `fix/asserts-two-sided`
> - ✅ **S2** — parity gap #1 closed on **both** sides (`MapperParityTest` 59/59 through the real
>   `AssertionRuleMapper`; the Node suite 58/58 through the real `ruleFromJson`), the XSS gap and
>   the sweep's blind spot, attainment memoise→reroute→sort in the mandatory order, the
>   localStorage quota data-loss, the NO VALUE chips, the ragged detail rows, `color-mix`.
> - ✅ **S3.4** — three source rules, each **proved to fire** by re-introducing the real defect.
>   A fourth was written and deliberately removed; the reasoning sits where it would have been.
> - ✅ **S4** — bounded to traps: `NotJudged.countKey()`'s `default` arm (which would have broken
>   both closed arithmetics on a fifth reason), `evaluateSla`, and the last stale status wording.
>   `src/main` now contains no SLA identifier.
> - ✅ **S5** — 48 verified doc edits. `UPGRADING-ASSERTS.md` was under-reporting a breaking
>   change **5:1**; two WIKI pages gave opposite instructions about `loadValidity`; the dual
>   evaluator was absent from CLAUDE.md entirely; and the Claude agents' capability limits are
>   now **enforced by their frontmatter** rather than asserted in prose.
> - ✅ **S3.5** — the release tier named 5 of 13 runners; it names all of them now, and
>   `verify_release_tier` fails S03/R03 if a new one is ever added outside it. Two of the eight
>   were load-bearing: B12 (a CLAUDE.md-cited exemption) and D01–D06 (the only `-n -t -e -o`
>   coverage, which is where the two-artifact disagreement was found).
> - ✅ **S3.2** — golden artifacts as **R06**. Three cases from a committed fixed JTL through
>   `-g -o`, ~30 s. **Proved** by silently un-gating an unreachable band: G3 moves `passed 1 → 2`
>   and the stage fails with the diff. Regression is now **6/6**.
>
> **The whole plan is delivered.** §6's deliberate exclusions still stand and are not work.

**Tranche 2 — S2 + S3.2–3.5 + S4 + S5 · ~7–9 days**, scoped after the checkpoint and after
decision 6. S3.3 is already done.

Total was **10–13 engineer-days** end to end; S0 and S3.3 are done, so **~9–12 remain**. The point
is that the second half is not committed until the first half has told you what it costs.

---

## 5. Decisions — five settled 2026-08-09, two open

**Open and blocking:** decision 7 (the shape of §2.1's shared answer) blocks S1's first commit;
decision 6 (does the client-side verdict mirror stay) blocks S2 being scoped. Nothing else waits
on anything.

1. **Release number: stay on the current scheme. 3.2.0, not 4.0.0.** The CI/CD JSON key removal is
   a breaking change by semver, but there are no external consumers and the numbers are internal.
   Not worth a major. `UPGRADING-ASSERTS.md` still has to lead with the four removed keys (S5.4) —
   that part was never about the version number.

2. **`observe` becomes a named retired key.** The question was narrower than it read, and the
   answer settles both halves of it.

   `observe` **is** deprecated — deleted in milestone A2, along with `tags`, `onMissing`, `outside`,
   `exactly()` and `verdict`. The open question was only *what a legacy file containing it should
   do now*, and there are two shapes:

   | Legacy shape | Old behaviour | Today | Verdict |
   |---|---|---|---|
   | `observe: true`, no bands (the only *legal* shape — observe forbade bands) | reported, never gated | unknown key ignored → no bands → `INVALID_RULE` → config error, non-zero | **correct**, and exactly what `asserts-core-hardening.md:460` planned |
   | `observe: true` **with** bands (illegal but real — a rule someone parked without deleting its limits) | rejected **loudly**, and never gated either way | key silently dropped, **bands survive and gate** — the rule can now flip the badge to ASSERTS FAIL | **wrong** — "never gates" inverted into "gates", in silence |

   So the decision: `observe` joins `outside` as a retired key the mapper **names**. Refuse the rule
   with a message that says the key is gone and what to write instead, in both shapes. That also
   fixes the second half of the same finding — today the bandless case reports *"no warn or fail
   band"* and never mentions `observe`, while `UPGRADING-ASSERTS.md` promises a retired key names
   its replacement. Only `outside` got that treatment (`AssertionRuleMapper.java:169`).

3. **Minimal CI: in.** ✅ Implemented on `chore/minimal-ci` — see S3.3.

4. **Feature order: hold.** No swap for now; revisit when feature work is actually scheduled. The
   analysis stays in §7 so it does not have to be re-derived, and nothing in this plan depends on
   the order.

5. **GAP-4 (shape C, absent JTL column reads `0`): reopened 2026-08-10 as required — and closed as
   NOT REPRODUCIBLE.** The reopening was the right call and the finding was wrong: JMeter's own
   generator refuses a column-less JTL before this plugin runs, a present-but-zero column is a real
   measurement, and the model already defaults those fields to `NaN` with a comment saying so.
   Evidence in §2. No work to do.

6. **Does the client-side verdict mirror stay? — OPEN. Must be ruled on before S2 is funded.**

   This is the largest structural question in the codebase and it was an unexamined default in the
   first draft of this plan: S2 spends 2–3 days making the mirror *honest* without ever asking
   whether it should exist.

   | | Keep the mirror | Delete the verdict half |
   |---|---|---|
   | Browser | full CRUD + wizard + drag-to-retune **+ live verdicts** | full CRUD + wizard + drag, **no verdict computed** |
   | S2 cost | 2–3 d (production parsers under the vectors, attainment reroute, localStorage) | ~1–2 d, and it is **deletion** |
   | Shape **D** | fixed and pinned | **gone by construction** |
   | S2.2 / S2.5 | required | **gone** |
   | Recurring | a **permanent parity tax** — the vector file churned **+2,300 / −1,682 in ten days**, and that recurs with every rule-model change | none |
   | Lost | — | the live preview: *converge thresholds in one run instead of three* — the tab's core interaction |

   **Recommendation: keep it, conditional on S2.1 and S3.1 landing in the same pass.** Those two are
   what turn the mirror from a liability into a pinned feature — production parsers under the
   vectors, and a Node harness that makes the pure half testable in milliseconds. Unconditionally
   keeping it just renews the tax. And the wizard and drag-to-retune were kept on an explicit
   instruction; both are worth substantially less without live verdicts, so deleting the verdict
   half quietly devalues a decision already made.

   Note the interaction with decision 2: **sustained breach is the feature that makes this
   reconsider itself** (§7) — it is a real algorithm written twice over a series the mirror does not
   receive. If the answer here is "keep", that is a keep-for-now, re-asked when sustained breach is
   scheduled, not a keep-forever.

7. **What shape does §2.1's shared answer take? — SETTLED 2026-08-10: option B, the shared type.**

   §2.1 says "one function, both callers". There are two ways to build it and they are not
   equivalent:

   | | **A · the evaluator owns it** | **B · a shared `Usability` type owns it** |
   |---|---|---|
   | Authority | `whyUnusable` is the single answer; the validator calls it and renders the reason as finding text | a small type both call, returning *reason + explanation* |
   | Size | smallest change | more code, one new class |
   | Where reachability lives | moves into the evaluator, which today has no concept of it (unreachable bands, dead warn are validator-only ideas) | stays in one place designed to hold it, beside the "did you mean" prose |
   | Shape C later (decision 5) | another branch in the evaluator | genuinely one line |
   | Mirrors to JS | the evaluator is already mirrored, so the new logic must be mirrored too | same, but the mirrored surface is one named type rather than a widened `whyUnusable` |

   **Chosen: B.** It is the one that makes §2.1's own claim — *"any fifth shape is a one-line
   addition that cannot be half-done"* — actually true rather than nearly true. A is cheaper today
   and re-creates the same drift the moment a sixth shape appears, because the evaluator would keep
   accumulating validator-shaped knowledge with nothing saying where it belongs.

   The constraint that shapes the implementation: **the evaluator lives in `jmeter-report-core` and
   the mapper lives in `jmeter-web-insight-report`**, so the shared type belongs in
   `core/assertion` and shape A's *"this band was declared but could not be read"* fact has to cross
   that module boundary — which means it wants a home on the model, not a side channel.

---

## 6. Deliberately not doing

The sweep produced roughly a hundred rows. These were cut, and the reasons are worth keeping so
they are not re-proposed:

- **A spec for the short detail rows.** They are 8 cells in a 9-column table, but Budget was
  inserted *after* Actual, so nothing misaligns — the amber unmatched strip just stops one column
  short. The proposed cell-counting spec is more code than the bug and needs maintenance on exactly
  the event it claims to protect against. Change the three literals; write no test.
- **A static check for un-fallbacked modern CSS.** `color-mix()` is one 1px divider. Replace it with
  an 8-digit hex and move on — the four static checks that exist are there because their failure
  modes are silent and catastrophic. A missing border is neither.
- **Reflection binding the JSON schema to the parser.** Fix the schema — the dangling `$ref` is a
  one-hunk revert of `894c031`, which deleted one bracket pair too many and produced all three
  reported symptoms at once, and a strict validator currently **hard-errors on every valid
  document**. But the schema has *zero runtime effect*: no validator is a dependency anywhere. A
  ~15-line "every `$ref` resolves" check is enough; a permanent reflection mechanism is not.
- **Splitting `report.js` into modules.** Measured above: the seam the features need is already
  exported, the evaluator core is ~344 of 11,341 lines, and there is no build step — splitting means
  either adding a bundler to the one artifact whose self-containment is a hard product promise, or
  losing the IIFE that keeps `state` private. **Not a prerequisite for any of the four features.**
  Revisit only if sustained breach lands (§7).
- **A `setHtml()` wrapper.** One unsafe site, and a wrapper would not have caught it.
- **Deleting JUnit suite 1.** `asserts-core-hardening.md` §2a lists it as cut; it was never
  executed, and `asserts-delivery.md` D2 decided the *opposite*. Annotate the row (S5.5); do not
  act on it until the two plans are reconciled.
- **C22/C33-run-3 de-duplication and eight existence-only scenarios.** Real waste (~30 s and ~70 s),
  but the config runner's 13m22s problem is process overhead, not these. Fold into S3.5 if
  convenient; do not schedule.

---

## 7. After stabilization — one revision to the feature order

`asserts-future.md` orders the work **windows → baseline → sustained breach → vocabulary**.
Technically that is the most expensive order, and I would swap the first two:

- **Baseline-relative thresholds need no `ReportData` change.** The baseline is a whole-run
  aggregate of exactly the shape the evaluator already consumes: a signature change on
  `GateEvaluation.of → AssertionEvaluator.evaluate → MetricRef.valueFor`, plus an object branch in
  `asNumber`. It also surfaces a real trap early — **a relative band is destroyed on the next
  Download**, because the payload ships resolved scalars and `buildDocumentFrom` rebuilds
  `warn`/`fail` from them. `{"fail":{"gt":{"of":"baseline","pct":10}}}` silently becomes
  `{"fail":{"gt":880}}`, and the regression gate becomes a fixed threshold pinned to last night.
- **Time windows are the only one of the four that requires a model change** — a second aggregate
  set on `ReportData` fed from `recordSample`, touching both ingestion paths and every serializer.
  `percentileSources` is keyed by sampler name alone and per-bucket percentiles are **128-value
  reservoir estimates**, so a windowed percentile is not merely unimplemented, it is unanswerable
  from today's model.

If the order stays as written, nail down one thing before a line is written: the per-bucket p95
series is already in the payload, already indexed by time, and **already looks exactly like the
answer**. It is a reservoir. Someone will fold it, every test will pass, and the verdict will be a
number no artifact can reproduce.

**Only two things in this plan are strict prerequisites for feature work** — the schema's dangling
`target` `$ref` and putting the second parser under the parity vectors (S2.1) — because both
features 1 and 2 add *new declaration syntax* at exactly the key where the schema is unresolvable
and the browser parser is unpinned. That is ~1.5 days. Everything else in S3–S5 is worth doing and
is not blocking.

Two further notes for whoever picks up the features:

- **Sustained breach is the one that forces the dual-evaluator question.** `longestBreach` is a real
  algorithm written twice over a series the mirror does not receive — and the payload rounds every
  per-bucket value to 2dp while the engine folds at full precision, so the two sides would disagree
  about the *duration*, which is the rule's whole point. It needs no `ReportData` change; it needs
  the extraction §6 defers.
- **A metric addition touches eight hand-maintained tables**, six of them in `report.js`, with
  nothing asserting they agree with the Java vocabulary. "A decision, not a build" is true only for
  the qualified error metrics that already parse.

---

## 8. What the sweep confirmed is sound

Recorded so it is not re-audited: the status collapse and both closed arithmetics hold structurally
(`RuleResult.of()` is package-private and only ever called with a gating status, so a `NOT_JUDGED`
result always carries a reason); one producer and one fold are real; unknown keys are ignored *and*
preserved at the model layer; the publish path's three deliberately-odd behaviours are intact and
every `core/util` catch is narrow and justified; both ingestion paths run the **same** T-Digest
(`JtlParser` constructs a `LiveStatisticsEngine`) — the divergences are in the inputs, not the
engine; the hooks are `100755` **in the index**; and the Assertion Rules table does **not** fall into
the documented generic-table-helper trap.

Three claims from the review that did **not** reproduce, and should not be spent on: hooks committed
non-executable; `Attainment` computed from a capped result *on the Java side* (it is uncapped there —
the divergence is client-side only); and reservoir histogram bins reaching a chart unscaled.
