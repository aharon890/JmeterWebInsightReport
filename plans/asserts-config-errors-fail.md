# A broken gate definition is a config error, not a verdict

> **Status:** shipped · 2026-08-08 · merged `17b4c4f` (implementing commit `1e265d9`) ·
> released as **3.1.0**, with `3.1.1` following for the `NonZeroExit` fix — see §7.
> **Read §7 first.** "What actually happened" records three deviations, including the
> `CONFIG ERROR` marker printing *inside* `failonerror` on one ingestion path only. §1–§6 are
> the plan as written and were deliberately not edited.
> **Follows** [`asserts-core-hardening.md`](asserts-core-hardening.md) (shipped, 3.0.16 at
> `ccc9197`), whose "the exit-code half was never wired and now never will be" this reverses.
> **Branch (merged):** `fix/asserts-config-errors-fail` · estimated 2 days

## The decision

The core plan established: **only a declared assert measured against a real value can produce a
verdict.** That stands, unchanged, and nothing in this plan touches a status, a count or a fold.

What it accidentally also decided is that a rule the *file* got wrong touches nothing at all —
not the exit code, not the JUnit result. That conflated two questions:

| Question | Answer |
|---|---|
| Does a broken rule produce a FAIL **status**? | **No.** Nothing was measured. Unchanged. |
| Does a broken rule mean the plugin **could not do what it was told**? | **Yes** — and that is what `failonerror` has always meant. |

A metric name that does not exist means **your gate is not running while you believe it is**.
That is the canonical way a quality gate dies, and it is the same category as an unwritable output
directory: the tool reporting that it could not carry out the instruction, not the tool judging
your system.

### Why the default matters more than the flag

The failure modes are asymmetric. A false FAIL costs twenty minutes. A false PASS costs an
incident. Of the four ways people consume a verdict, three got quieter under the current
behaviour:

| Consumes the verdict via | Under 3.0.16 |
|---|---|
| The HTML report | fine — banner, findings, budget |
| `jmeter && publish` (exit code) | broken gate **invisible** |
| `jq '.gate.failed == 0'` | silently weakened |
| Jenkins / Azure JUnit | broken rule became a `<skipped>`, which most CI UIs hide |

Precedent: k6 refuses to start on a malformed threshold; Gatling fails on a malformed assertion.
No comparable tool treats a broken gate *definition* as a result.

---

## 1. Exactly what changes

Two rows, and one JUnit branch. **Everything else is untouched.**

| # | Situation | Status (unchanged) | Exit code now | Exit code after | Flag |
|---|---|---|---|---|---|
| 4 | Unknown metric, bad pattern, unreadable target, no bands | `NOT_JUDGED / INVALID_RULE` | 0 | **non-zero** | `failonerror` (already default **true**) |
| 8 | Annotations file configured but missing or unparseable | no rules at all | 0 | **non-zero** | `failonerror` |

| Situation | JUnit now | JUnit after |
|---|---|---|
| `INVALID_RULE` | `<skipped>` | **`<failure>`** |
| `failures=` | `gate.failed()` | `gate.failed() + gate.invalidRule()` |

### What explicitly does NOT change

Listing these because the risk in this change is scope creep into the status model:

- **No status, reason, count, fold or verdict moves.** `gate.status` still comes only from
  `judge()`. The parity vectors should need **zero** edits — if any vector changes, the change
  has gone wrong.
- `NO_MATCH` does **not** fail. A sampler that only runs in some environments is legitimate, and
  since `onMissing: skip` was deleted there is no way to declare a target optional. Failing on it
  would strand those users with no escape but `failonerror=false`, which also disarms row 9.
- `NO_VALUE` does **not** fail. The run could not produce the metric; the file is fine.
- `DISABLED` does not fail. Obviously.
- `WARN` does not fail, and `failonassert` stays **default off** and `FAIL`-only.
- The report, the JSON and the XML are still **all written** when the run fails. Failing a build
  must not cost the artifact that explains why. (Already true for row 9; must stay true here.)
- `failonerror=false` remains the single escape hatch, unchanged.

---

## 2. Implementation

### 2.1 Carry the config-error facts out of the generator — `ReportArtifacts`

`ReportArtifacts` already carries the gate (`gate()` / `recordGate`, :147/:151) and already
answers `anyFailed()` for artifacts. It gains the parallel question for configuration.

```java
/** Rules the FILE got wrong, plus an annotations file that could not be read at all. */
public ReportArtifacts recordConfigProblem(int invalidRules, String loadProblem)
public boolean hasConfigProblem()       // invalidRules > 0 || loadProblem != null
public String  configProblemReason()    // one sentence, for the exception and the console
```

**Deliberately not folded into `anyFailed()`.** That method is documented as "a planned artifact
failed", and `incompleteReason()` builds its sentence from artifact names. Widening it would make
`INCOMPLETE — the JUnit XML could not be written` appear for a typo'd metric. Two questions, two
predicates, one flag.

`configProblemReason()` wording, which a pipeline will see and nothing else explains:

```
2 rule(s) could not run — see findings; the gate did not judge what the file declared
```
```
the annotations file could not be read: /path/report-annotations.json — no gate ran
```

### 2.2 Populate it — `WebInsightReportGenerator`

One line beside the existing `artifacts.recordGate(...)` at the point where `evaluation` and
`loaded.problem()` are both already in scope (~:242):

```java
artifacts.recordConfigProblem(evaluation.gate().invalidRule(), loaded.problem());
```

Both values already exist; nothing new is computed.

### 2.3 Act on it — **both** ingestion paths

This is the step most likely to be done by halves. The two paths fail differently and both must
change, or `-n -t` and `-g -o` disagree about the same file.

**Listener** — `WebInsightReportListener` ~:498, beside the artifact check:

```java
if (artifacts.anyFailed() && failOnError()) {
    failTheRun(new IOException(artifacts.incompleteReason()));
} else if (artifacts.hasConfigProblem() && failOnError()) {
    failTheRun(new IllegalStateException(artifacts.configProblemReason()));
}
```

`failTheRun` is the shutdown-hook + `Runtime.halt` mechanism — rethrowing does not work from
`testEnded`, and the reason why is documented on that method. Reuse it; do not invent a second.

**Exporter** — `WebInsightDataExporter` ~:166, the same shape with `ExportException`, which
propagates and makes JMeter exit 1 on its own.

Both must also print the `WEB-INSIGHT-REPORT:` console marker, so a pipeline grepping one pattern
across both modes still finds it.

### 2.4 JUnit — `JunitXmlWriter`

```java
int failures = gate.failed() + gate.invalidRule();
int skipped  = gate.notJudged() - gate.invalidRule();
```

and in the per-testcase branch, route `NOT_JUDGED / INVALID_RULE` to `<failure>` carrying
`skipMessage(result)` — the sentence already naming the metric and its suggestion — while the
other three reasons stay `<skipped>`.

---

## 3. Tests

### 3.1 New — config scenario `C33`, both directions

The default is the half that would rot silently, so it is asserted first. Model on **C19**, which
proves `failonerror` from both sides.

| Run | Setup | Asserts |
|---|---|---|
| 1 | annotations with one unknown-metric rule, otherwise valid | exit **non-zero**; `gate.notJudged.invalidRule == 1`; **HTML, JSON and XML all present**; `WEB-INSIGHT-REPORT:` marker on the console |
| 2 | same, `-Jwebinsight.report.failonerror=false` | exit **0**; same artifacts; marker still present — the opt-out buys back the status and nothing else |
| 3 | `-Jwebinsight.report.annotations=/no/such/path.json` | exit **non-zero**; `findings[]` present; `assertsEvaluated: false` |
| 4 | a healthy gate that **FAILS** | exit **0** — proves this is about config errors and *not* about verdicts, which is the whole claim |

Run 4 is the one that stops this becoming "everything red fails the build".

### 3.2 New — exporter parity, `N10`

The same unknown-metric annotations through `jmeter -g x.jtl -o dir`: exit non-zero, same
`invalidRule` count. Without it, step 2.3 can be half-done and nothing notices.

### 3.3 Amended — existing stages

| Stage | Why it moves |
|---|---|
| **C22** (missing annotations file) | currently asserts exit 0 implicitly by not checking; must now assert **non-zero**, and keep its `findings[]` check |
| **N08 / N09** (broken rules, listener and exporter) | both currently pass with exit 0; both become non-zero. Their `invalidRule == 5` assertions are unchanged |
| **E25 fixtures** | E25 carries `unknown-metric` deliberately. **Its runner must pass `failonerror=false`**, or every fixture regeneration exits non-zero and `run-all-tests.sh` goes red on a fixture that is red on purpose. *This is the trap in this plan* — see §5. |
| `GateOutputTest.aRuleThatCouldNotRunIsSkippedAndSaysWhy` | becomes `…IsAFailureAndSaysWhy`: `failures="1"`, `skipped="0"`, message still names the metric |
| `GateOutputTest.theGateCountsAccountForEveryDeclaredRule` | unchanged — the JSON counts do not move. Verify, do not edit. |

### 3.4 Unchanged, and verified as such

- `assert-parity-vectors.json` — **zero edits expected**. If a vector needs changing, stop: the
  change has leaked into the status model.
- Every `asserts-*.spec.ts` — the browser knows nothing about exit codes.
- `AssertionEngineTest`, `AssertionParityTest`, `AttainmentTest`, `ErrorSelectorTest`.

### 3.5 Suites to run before merge

`run-regression.sh`, `run-config-tests.sh` (C01–C33), `run-error-tests.sh` (N01–N10),
`run-all-tests.sh` (19 scenarios — the one that catches the E25 trap), `run-docker-tests.sh`.

---

## 4. Docs

| File | Change |
|---|---|
| **`WIKI/UPGRADING-ASSERTS.md`** | Rewrite the **"If you read only one section"** lead. It currently says a mistyped metric no longer fails a build — that becomes *only a mistyped metric that you have opted out of via `failonerror=false`*. State the upgrade impact in one line: **a run with a broken rule that exited 0 on 3.0.16 will exit non-zero.** |
| `WIKI/Tutorial/11-Export-Integration.md` | *Gating on the gate*: the strict `jq` is no longer needed for `invalidRule` — the run has already failed. Keep it for `noMatch`. Update the JUnit section: `INVALID_RULE` is a `<failure>` again, and say why (`<skipped>` is invisible in CI dashboards). Update the exit-code section: `failonerror` covers config errors as well as artifacts. |
| `WIKI/Tutorial/10-Asserts-Apdex.md` | The `INVALID_RULE` row and the "no longer fails a build" paragraph: it does fail, under `failonerror`, because the file is wrong. `failonassert` paragraph unchanged. |
| `WIKI/FEATURES.md` | The `failonassert` bullet gains a sibling: `failonerror` also covers a gate that could not be built. |
| `plans/asserts-core-hardening.md` | The §"Settled 2026-08-08" block records the old ruling. Append the revision and the date rather than rewriting it — the reasoning that produced it is why this plan exists. |
| **`CLAUDE.md`** | `run-config-tests.sh` → C01–C33 / 34 stages; `run-error-tests.sh` → N01–N10. |
| `.claude/skills/run-tests/SKILL.md`, `WIKI/Instructions.md`, `run-regression.sh` footer | the same two ranges |

---

## 5. Risks

| Risk | Why it bites | Mitigation |
|---|---|---|
| **E25 regenerates non-zero and the fixture suite goes red** | E25 declares `unknown-metric` on purpose so every rule state renders. Its run will now exit non-zero and `run_test` records a failure — on a fixture that is *correct* | Pass `failonerror=false` on E25's runner line, with a comment saying the rule is deliberate. **Do this in the same commit as 2.3**, or the tree is red between commits |
| Half-done ingestion paths | `-n -t` fails and `-g -o` does not, for the same file | N10 exists solely for this |
| Scope creep into statuses | The temptation is to "tidy" `NOT_JUDGED` while nearby | The parity vectors are the tripwire: zero edits expected |
| Pipelines that are green today go red | That is the intent, and it is still a surprise | Upgrade note leads with it; `failonerror=false` is one flag |
| `failonerror=false` now disarms more than it used to | Someone opting out of artifact failures silently also opts out of config errors | Accept and document. One flag with one meaning — "do not let the plugin change my exit code" — is easier to reason about than two |

## 6. Order of work

1. `ReportArtifacts` predicates + `WebInsightReportGenerator` wiring (§2.1, §2.2)
2. Both ingestion paths + **E25's `failonerror=false`** in the same commit (§2.3, §5)
3. `JunitXmlWriter` + its two Java tests (§2.4, §3.3)
4. `C33`, `N10`, and the C22 / N08 / N09 amendments (§3.1–3.3)
5. Docs and the range updates (§4)
6. Full suite sweep (§3.5), then merge to local `main`

Steps 1–3 are the change. Steps 4–5 are what make it survivable.

---

## 7. What actually happened — deviations from the plan above

Recorded rather than edited into §1–§6, because a plan that quietly matches its outcome
teaches nothing about where plans go wrong.

### N10 was not written. N09 already was it.

§3.2 asked for a new stage: *"the same unknown-metric annotations through `jmeter -g x.jtl -o
dir`: exit non-zero, same `invalidRule` count."* That is a description of **N09**, which §3.3
already amends on the same line. Two rows of the same table asked for one stage twice, once as
new work and once as an edit.

Writing it would have produced a second stage sharing N09's fixture, its rules file and its
assertions, differing in nothing. The range stays **N01–N09** and the doc updates that §4 asked
for on `N01–N10` were not made.

The mitigation column for *"half-done ingestion paths"* still holds — it just points at N09.

### A stack trace, which the plan did not anticipate and N09 caught

The exporter's config-error branch was first written as `throw new ExportException(...)`,
because that path already threw for artifact failures and JMeter exits 1 on it by itself. N09
failed on a check that was not in the plan's table at all: `log_has_stacktrace`.

JMeter renders that exception as `ERROR o.a.j.JMeter: An error occurred:` over twelve frames
through `ReportGenerator.exportData`. For a typo'd metric name that reads as the plugin
crashing, which is the opposite of what a `CONFIG ERROR` marker is for. Worse, it was
load-bearing for the suite: `log_has_stacktrace` is how N08/N09 catch an exception caught and
logged while leaving a rule silently unjudged, and a trace the suite has to *expect* is a trace
the suite can no longer *notice*.

Fixed by extracting the listener's shutdown-hook mechanism into `NonZeroExit` and using it on
both paths. A genuine artifact failure still throws — that one is an IO error, and JMeter's own
reporting of it is appropriate.

This is the second time in this plan that "the two ingestion paths must not answer differently"
turned out to include *how* they answer, not just *whether*. The first was C33's run 2, below.

### C33 run 2 found the marker inside the flag

The listener printed `CONFIG ERROR` inside `if (failOnError())`; the exporter printed it
outside. So `failonerror=false` silenced the marker as well as the exit code on one path only —
turning the opt-out into "and never tell me", which is not what anyone opts into. C33's second
run is the only thing that looks at this, and it failed on the first attempt.

### Version

Landed as **3.1.0** — a minor, not the usual redeploy patch. An existing annotations file can
change a pipeline's exit code, which is not a patch-level event. `3.1.1` follows it with the
`NonZeroExit` fix above.
