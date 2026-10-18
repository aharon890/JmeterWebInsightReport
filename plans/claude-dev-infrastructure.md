# Claude infrastructure: active testing, design input, and the development cycle

> **Status:** shipped · 2026-08-10 · written 2026-08-07 against `main` @ `<revision>` 3.0.2.
> **What shipped:** `.claude/agents/report-explorer.md`, `spec-author.md`, `design-scout.md`,
> the `explore-report` and `dev-cycle` skills, `test-plans/EXPLORATORY-LOG.md`,
> `scripts/serve-reports.sh`, and CLAUDE.md's "Claude as an active tester" section.
> **Where it fell short, and was corrected on 2026-08-10:** the capability boundaries this plan
> calls the design were only ever *prose*. The agents' `tools:` frontmatter granted
> `spec-author` unscoped `Edit`/`Write` and all three unrestricted `Bash`, so nothing enforced
> "writes only under `test-plans/playwright/`" or "read-only everywhere". Verify against the
> agent files, not against this plan.
> Independent of [`asserts-core-hardening.md`](asserts-core-hardening.md) — this is *how work
> gets done*, that is *what work gets done*.

## What already exists, and the principle it establishes

| | What | Rule it encodes |
|---|---|---|
| `.githooks/pre-push` | refuses a direct push to the primary branch, **runs no tests** | the one thing that must hold *no matter who is pushing* |
| `.claude/skills/run-tests/` | picks sanity vs regression, regenerates stale fixtures, reports real pass/fail | everything test-shaped |
| `.claude/skills/_perf_release/` | the release tier | everything release-shaped |
| `.claude/settings.local.json` | 40 permission allows | reduce prompt friction |

The hook's own header states the division: **deterministic rules go in hooks, judgement goes in
skills.** Everything below respects that. Nothing here adds a second gate.

---

# Part A — Claude as an active tester

## A1. The honest case for it

Playwright asserts *known* invariants. It can only fail on something someone already thought to
assert. That is its strength and its ceiling.

Three defects found today by opening one report in a browser, none of which any of the 543 specs
would ever have caught:

- The header badge said **ASSERTS FAIL** while the gate strip on the same screen said **INVALID**.
  Both correct; nothing compares them.
- The Summary strip counted *"1 skipped"* where the Asserts tab counted *"1 unmatched"* — the same
  rule, two vocabularies, two tabs.
- The rule editor's own copy stated the model's ceiling in a sentence nobody had read as a product
  statement: *"Bands judge the whole-run aggregate."*

**The pattern: each surface is individually correct, and together they are wrong.** No per-surface
spec can see that. This is where Claude beats automation — plus the case you named: a feature
under development that has no automation yet.

## A2. The inverse, stated first so the design respects it

Claude-driven browser testing is **slow, non-deterministic, token-hungry, and unreproducible**.
Two runs of the same mission can differ. It therefore:

- **never gates a push, a commit, or a release**;
- **never replaces a spec**;
- **never runs on every change**.

## A3. The one rule that makes it worth doing

> **Claude explores. Automation pins.**
>
> Every exploratory session ends in one of exactly two things:
> **(a)** a new or changed Playwright spec, or
> **(b)** a line in `test-plans/EXPLORATORY-LOG.md` saying what was found and *why it was not
> automated*.

Without this, browser sessions are theatre: the same defect gets rediscovered every few months and
nothing accumulates. With it, exploration is a **spec factory** — the slow, expensive, judgement-
heavy front end to a fast, cheap, deterministic suite.

## A4. Capability boundaries — the "what gets turned off where" answer

Three subagents in `.claude/agents/`. The tool restrictions are the design, not a detail.

| Agent | Tools | Why restricted |
|---|---|---|
| **`report-explorer`** | browser MCP, `Read`/`Grep`/`Glob`, `Bash` (read-only cmds) | **No `Edit`/`Write`.** An explorer that can edit will *fix* what it was supposed to *report* — and then the finding never reaches you and no spec is ever written. This is the single most important restriction in the document. |
| **`spec-author`** | `Read`/`Grep`/`Glob`, `Edit`/`Write` **only under `test-plans/playwright/`** | Cannot touch `src/main`. Turning a finding into a spec must never quietly change the product to make the spec pass. |
| **`design-scout`** | browser MCP, `Read`/`Grep`/`Glob` | Read-only everywhere. Produces design input, never design changes. |

The main session keeps full capability — these are guardrails for delegated work, not security.

## A5. Infrastructure needed

**1. `scripts/serve-reports.sh`** — *required, not a convenience.* The Chrome extension **refuses
`file://` URLs** (verified: *"Can't interact with browser-internal or unparseable URLs"*). Every
session otherwise rediscovers this. The script serves `test-results/` on a fixed port and prints
the fixture URLs.

> One caveat it must print: **E19/E20 use the external-data sidecar**, whose load path differs
> between `file://` and `http://`. A degraded-state finding on those two must be re-checked over
> `file://` before it is believed.

**2. `.claude/skills/explore-report/`** — drives a session: pick a fixture, state a mission, work
the UI, and **end with the A3 output contract**. The skill's real content is the mission catalogue
(A6) and the anti-patterns (don't narrate every click; don't re-assert what a spec already covers).

**3. `test-plans/EXPLORATORY-LOG.md`** — append-only. Date, fixture, mission, findings, and for
each: *spec written* or *not automated because…*.

## A6. The mission catalogue — what to actually look for

Missions, not "test the report". Each names the thing automation structurally cannot do:

| Mission | Fixture | What only a human-like reader can judge |
|---|---|---|
| **Cross-surface agreement** | E25 | Do the header badge, gate strip, Asserts tab and summary cards describe the same run in the same words? |
| **Degraded and empty states** | E02, E19 | Does the report explain what is missing, or just show less? |
| **Copy as a product claim** | E02 | Does any wording claim something the run did not establish? |
| **First-read comprehension** | E11 | Open cold: can you say what failed and why, in 30 seconds, without the source? |
| **Plausibility of numbers** | any | Not "is it the expected value" but "could this value be real at all" |
| **The unautomated feature** | current branch | Whatever is being built right now |
| **Interaction sequences** | E25 | Edit → filter → sort → drag → undo, in orders nobody scripted |

## A7. When to invoke — and when not to

**Invoke:**
1. A feature is under development and has **no automation yet** — the highest-value case.
2. Immediately **after any change to `report.js`, `report.html` or `report.css`**, *before* writing
   the spec — so the spec is written against observed behaviour, not imagined behaviour.
3. **Before a release**, as a walkthrough of the fixture set.
4. When **a bug escaped the suite** — reproduce by hand, then pin it.

**Do not invoke:** on every commit; inside any hook; as a release gate; to re-verify something a
spec already covers.

---

# Part B — Claude in design and change requests

`design-scout` (A4) exists because design decisions in this project keep being made against the
*source* when they should be made against the *rendered artifact*. Two examples from today:

- The asserts editor's ceiling is visible in its own copy — one screen answered a question that
  took a 4,700-line source read to reach the same place.
- The vocabulary split between tabs is invisible in source and obvious on screen.

**Use it for:** a change request against an existing surface; sizing a UI change; checking whether
a proposed feature has a home in the current layout; producing before/after evidence for a plan.

**Its output is written into the plan file**, not into code. Read-only enforces that.

---

# Part C — The development cycle

Your requirements: after a feature or fix, a **freshly built JAR at a bumped version must be in
`lib/ext`**; `reportExample/` must be **current**; **no direct commits to main**; and **development
must be committed**.

## C1. What is deterministic → hooks and scripts

| Rule | Mechanism | Notes |
|---|---|---|
| No direct **commit** to the primary branch | **new `.githooks/pre-commit`** | The existing hook blocks *pushes*; a commit is earlier and cheaper to stop. Same shape: resolve the branch from `origin/HEAD`, never hardcode. Escape hatch `WIR_ALLOW_MAIN_COMMIT=1`, matching the existing `WIR_ALLOW_MAIN_PUSH=1`. |
| `src/main` changed but `<revision>` did not | **`Stop` hook, warn only** | Deterministic and cheap. **Warn, never block** — a warning mid-flow is useful, a block is infuriating and will get disabled. |
| `lib/ext` holds exactly one plugin JAR, matching `<revision>` | already in `build-and-deploy.sh` | It purges older JARs. What is missing is *running* it, which is C2. |
| `reportExample/` regenerated | **new `scripts/refresh-report-example.sh`** | Runs one named scenario and copies its HTML + annotations into `reportExample/`. Closes the loop on the stale 1.5 MB sample deleted today. |

## C2. What needs judgement → one skill

**`.claude/skills/dev-cycle/`** — invoked when a change is finished:

```
1. Decide the bump level        patch for a deploy, minor belongs to a release
2. Bump <revision>              parent pom only; module poms use ${revision}
3. ./scripts/build-and-deploy.sh
4. Refresh reportExample/       ONLY when report output actually changed
5. run-tests skill              regression when src/main changed
6. Commit                       on a branch, never on main
```

Steps 1, 4 and 6 are judgement — which is exactly why they are a skill and not a hook. A hook
cannot know whether a change altered report *output* or only internals.

## C3. What deliberately stays manual

- **Pushing and PR creation.** The existing pre-push hook is the guard; automating the push would
  route around the one rule the repo has.
- **Releases.** `_perf_release` already owns that and runs to completion without confirmation.
- **Deciding a change is finished.** No hook can know this.

---

# Part D — Deliverables, sequenced

| | Item | Effort | Depends on |
|---|---|---|---|
| D1 | `scripts/serve-reports.sh` | 0.25 d | — |
| D2 | `.claude/agents/report-explorer.md` + `spec-author.md` + `design-scout.md` | 0.5 d | — |
| D3 | `.claude/skills/explore-report/` + the mission catalogue | 0.75 d | D1, D2 |
| D4 | `test-plans/EXPLORATORY-LOG.md` + the output contract | 0.1 d | D3 |
| D5 | `.githooks/pre-commit` (branch guard) + `install-hooks.sh` update | 0.25 d | — |
| D6 | `scripts/refresh-report-example.sh` | 0.4 d | — |
| D7 | `.claude/skills/dev-cycle/` | 0.5 d | D5, D6 |
| D8 | `Stop` hook: warn on `src/main` changed without a bump | 0.25 d | — |
| D9 | CLAUDE.md: a short section pointing at all of the above | 0.25 d | all |

**Total ~3.25 days.** D1+D2+D3+D4 (1.6 d) is the testing half and is independently useful;
D5–D8 (1.4 d) is the cycle half.

**Suggested order:** D5 and D6 first — they are the smallest and they stop the two things that
actually bite (a commit on main, a stale example). Then the testing half.

---

# Part E — Risks

1. **Exploration becomes theatre.** The mitigation is A3, and it only works if it is enforced
   socially — the skill must refuse to end a session without a spec or a logged reason.
2. **Token cost.** A full walkthrough is not cheap. Missions are scoped for this reason; the
   walkthrough belongs before a release, not in a normal loop.
3. **A blocking hook gets disabled.** Hence: the branch guard blocks (it is the one rule), the
   version-bump check only warns.
4. **The extension is fragile.** It returns an empty browser list when Chrome is not paired, and
   blocks `file://`. `serve-reports.sh` and a "check the connection first" step in the skill are
   the whole mitigation.
5. **Subagent restrictions are guardrails, not security.** The main session can still edit
   anything. They shape delegated work; they do not enforce policy.
6. **`reportExample/` regeneration bakes in whatever the current build does.** If the build is
   wrong, the example is confidently wrong. It should be refreshed *after* a green regression,
   never before.

---

# Part F — Decisions before D1

1. **Does `pre-commit` block or warn on a main commit?** I propose **block**, with
   `WIR_ALLOW_MAIN_COMMIT=1` — it matches the existing push rule and is the one thing you named
   twice.
2. **Which scenario becomes `reportExample/`?** It needs many samplers, some errors, and declared
   asserts. E25 renders every rule state but its thresholds are deliberately absurd, so it is a bad
   showcase. A dedicated `EXAMPLE` scenario would cost ~0.25 d more and be honest.
3. **Is `EXPLORATORY-LOG.md` tracked?** I propose yes — a "not automated because…" line is
   accumulated project knowledge, not scratch.
4. **Should `explore-report` run as a subagent or in the main session?** Subagent gets the
   capability boundary; the main session gets your live steering. I propose **subagent for
   missions, main session when you are watching**.
5. **Fixed port for `serve-reports.sh`** — 8777 unless you have a preference.
