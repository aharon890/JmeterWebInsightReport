---
name: explore-report
description: Drive an exploratory browser session against a generated report, then turn what it finds into Playwright specs. Use when a feature has no automation yet, after changing report.js/report.html/report.css, before a release, or when a bug escaped the suite.
---

# Exploring a report

```
/explore-report                     pick a mission from the catalogue below
/explore-report E25 cross-surface   a named fixture and mission
/explore-report <feature>           whatever is being built right now
```

Automation asserts what someone already thought to assert. This is the other half: a person-like
reader looking at the running product. It is **slow, non-deterministic and expensive**, so it
never gates anything and never runs on every change — and it must leave something permanent
behind, or the same defect gets rediscovered every few months.

## The output contract — this is the whole point

**Every session ends in one of exactly two things per finding:**

**(a) a Playwright spec** that fails on it — delegate to the `spec-author` subagent; or
**(b) a line in `test-plans/EXPLORATORY-LOG.md`** saying what was found and **why it was not
automated**.

There is no third option. A finding that is only in the conversation is gone by tomorrow.
"Nothing found" is a valid result and still gets a log line — it records that the mission was run.

## Steps

**1. Serve the fixtures.** The Chrome extension refuses `file://`.

```bash
./scripts/serve-reports.sh --list     # what exists, and what each one demonstrates
./scripts/serve-reports.sh &
```

**2. Check the fixtures are not stale.** They are generated artifacts of whatever build ran last.
Exploring a stale report finds defects that were fixed weeks ago.

```bash
./test-plans/scripts/run-regression.sh    # regenerates when the stamps no longer match
```

**3. Pick one mission.** One. A session that tries to cover everything covers nothing.

**4. Run it through the `report-explorer` subagent** — it has no `Edit`/`Write`, deliberately, so
it cannot fix what it should report. Drive it yourself in the main session instead when you want
to watch and steer.

**5. Triage.** For each finding decide: pin it, or log why not.

**6. Pin.** Hand each pinnable finding to `spec-author`, which writes only under
`test-plans/playwright/`. **A new spec should fail** — that is what it means for the finding to be
real. Hand it over red.

**7. Log.** Append to `test-plans/EXPLORATORY-LOG.md`.

## The mission catalogue

| Mission | Fixture | What automation structurally cannot see |
|---|---|---|
| **Cross-surface agreement** | E25 | Do the header badge, gate strip, Asserts tab, summary cards and sampler table describe the same run in the same words? Each is individually correct; nothing compares them |
| **Unearned claims** | E02 | Read the copy as a product statement. Does anything assert what the data does not support? |
| **Degraded and empty states** | E02, E19 | Does the report explain what is missing, or just show less? |
| **First-read comprehension** | E11 | Open cold: can you say what failed and why in 30 seconds without the source? |
| **Plausibility of numbers** | any | Not "is this the expected value" but "could this value exist at all" |
| **The unautomated feature** | current branch | Whatever is being built right now — the highest-value mission |
| **Interaction sequences** | E25 | Edit → filter → sort → drag → undo, in orders nobody scripted |
| **Width and overflow** | E05 | 20 samplers: tables, legends, column resize |
| **Release walkthrough** | all | Every fixture, briefly, before a release |

## When to run this

- A feature is under development and **has no automation yet**.
- **After** changing `report.js`, `report.html` or `report.css`, and **before** writing the spec —
  so the spec is written against observed behaviour rather than imagined behaviour.
- **Before a release.**
- **A bug escaped the suite** — reproduce by hand, then pin it.

**Not:** on every commit. Not in a hook. Not as a gate. Not to re-verify something a spec covers.

## Traps

**The E19/E20 caveat.** They load the chart payload as a sibling `.js`, chosen because `fetch()`
is CORS-blocked on `file://`. Over `http://` that loads by a route no real user takes. A
*missing-or-degraded payload* finding on those two is **unverified** until checked over `file://`.

**Stale fixtures.** See step 2. `WIR_SKIP_FIXTURE_CHECK=1` exists for when you already know they
are current — never to silence a real mismatch.

**Re-testing the suite.** If a spec covers it, you are wasting the expensive tool on the cheap
tool's job.

**What a browser cannot see.** A rule that can never fire and that nothing refuses, or one no gate
reads, renders as an ordinary green PASS — the same row, chip and badge as a rule that was tested
and held. (The never-fire shapes the product knows are visible: a downward band at or below zero
is refused as INVALID_RULE, a server band past a catalogue ceiling carries a warning.) No amount
of reading the page tells the two apart, so a clean session is no evidence that a gate works.
That class belongs to the goldens (R06), the parity vectors and the unit tests, not to a mission.

**Padding.** Never invent a finding to justify the session. A clean exploration, logged, is a
better result than a list of nitpicks, and it is what makes the log trustworthy.

**Narrating clicks.** The transcript is not the deliverable. Findings are.
