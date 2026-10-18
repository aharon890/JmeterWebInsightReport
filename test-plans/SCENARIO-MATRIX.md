# Output Artifact & Configuration Scenarios — method and grounding

**The scenario list is not a file — it is the runners.** Run
`./scripts/list-scenarios.sh` to see every scenario that executes, read from the `record`
calls themselves; `--csv` if you want it in a spreadsheet.

There used to be a `tmp/SCENARIO-MATRIX.csv` here, 127 rows with `Actual` and `Verdict`
filled in by hand. It is gone, and the reason is worth keeping: it drifted. **43 of its rows
named scenarios no runner executed, and 24 scenarios the runners execute appeared nowhere in
it** — and because it lived outside version control, nothing could see either number. That is
the same failure the fixture lists have `verify_fixture_lists` for, and the same reason
CLAUDE.md refuses to write test counts into documentation: a hand-maintained copy of
something executable is wrong within a month and looks authoritative the whole time.

Tracking it would not have fixed that. It would have preserved the drift with better
provenance. A list that can be generated should be generated.

The 43 scenarios that were designed and never automated *could* not be regenerated — they
describe work nobody did — so they were moved to
[TEST-COVERAGE-GAPS.md § 13](../docs/TEST-COVERAGE-GAPS.md), where the rest of this project's
known gaps already live.

This file holds only what a spreadsheet cell is a bad home for: the code facts every
`Expected` was derived from, and the method behind the `Pre` column.

Written against `v2.1.0` (`main` @ `0b7130a`).

---

## CSV columns

| Column | Meaning |
|---|---|
| `ID` | Stable scenario id. `T*` ids are implemented in `run-title-junit-tests.sh`. |
| `Family` | A-K grouping (see below). |
| `Type` | **`automation`** — runs headlessly, Claude can execute unattended. **`manual`** — needs a human at the GUI or a visual judgement. |
| `Entry` | `GUI`, `CLI-listener`, `CLI-exporter`, `CLI-both`, `Docker`, `any`. |
| `Pre` | Required starting state of the output folder — see *Preconditions*. |
| `Scenario` | Setup and action. |
| `Expected` | Derived from the source, grounded in the `Facts` column. |
| `Facts` | `F1`-`F18` references into the table below. |
| `ExistingCover` | Existing suite scenario already covering this (`C18`, `N06`, `E20`…) — cheap confirmations, not new work. |
| `Runner` | Script implementing it, blank if not yet automated. |
| `Actual` / `Verdict` / `Notes` | **Yours to fill.** |

`Verdict`: `MATCH` · `DIFF` (behaves differently — record both) · `BUG` · `BLOCKED` · `N/A`.

### Current shape

| | Count |
|---|---|
| Total scenarios | **110** |
| `automation` | 97 |
| `manual` | 13 |
| Already implemented (`T01`-`T29`) | 29 |

| Family | n | |
|---|---|---|
| A | 12 | Listener path, repeated runs |
| B | 12 | CLI `-n -t` |
| C | 8 | CLI `-g` exporter |
| D | 6 | Both paths active |
| E | 7 | **Concurrency — no existing coverage** |
| F | 6 | External data / large payloads |
| G | 5 | Docker, shared host mounts |
| H | 7 | Negative and edge |
| I | 27 | Report title |
| J | 14 | JUnit XML |
| K | 6 | GUI-only (all manual) |

---

## Preconditions — why `Pre` is part of the test, not hygiene

**A scenario is only meaningful relative to the state of the output folder when it starts.**
Many rows deliberately share one directory, so the starting state is part of the definition.
Get it wrong and a row asserts against the *previous* row's artifacts and returns a
confident, wrong answer.

| `Pre` | Meaning | Get it wrong and… |
|---|---|---|
| `clean` | Empty or non-existent at start | a leftover report satisfies a "report exists" assertion this run never earned |
| `seed:<x>` | **Pre-populated** — the pre-existing state *is* the scenario | with a clean dir the test silently becomes a different, passing scenario |
| `carry` | Deliberately inherits the previous run's output | cleaning between runs destroys the collision being tested |

`seed` and `carry` rows are the dangerous ones: their failure mode is a **false pass**, not
an error. `C02` is the clearest — on a clean directory the exporter has nothing to skip, so
it generates a report and the row passes as an entirely different scenario.

### Seeds must be distinguishable

A seed resembling what the run would produce anyway proves nothing. If run 2 overwrites an
identical report, the directory afterwards is byte-identical to one where run 2 *skipped* —
and mtimes are too coarse to separate them.

**Use the report title as the provenance marker.** It is the one field reaching all three
artifacts (**F16**) and is settable from either surface:

```bash
run … -Jwebinsight.report.title=SEED-RUN-1 -Jwebinsight.report.output="$SHARED"   # seed
run … -Jwebinsight.report.title=RUN-2      -Jwebinsight.report.output="$SHARED"   # scenario
#   testName == RUN-2      -> run 2 overwrote  (listener, F2)
#   testName == SEED-RUN-1 -> run 2 skipped    (exporter, F8)
```

That turns `B01`, `C02`, `C06`, `D05`, `F04` and `G05` from timestamp guesswork into one
string comparison, and makes the two ingestion paths' opposite behaviour directly visible.

### Assert the precondition, don't assume it

Verify the starting state rather than trusting the previous row to have cleaned up — an
aborted run leaves debris exactly when you least expect it. `run_case` in
`run-title-junit-tests.sh` removes and recreates each case directory and checks it is empty
before starting. Rows with `seed:` or `carry` must **not** use that helper.

---

## Grounding facts

Every `Expected` rests on these. If one is wrong, everything built on it is wrong — which is
not hypothetical: **F11 and F12 were both wrong** and were caught by running the F-family
rows rather than by re-reading the code.

- **F12** claimed no `-J` property existed for the external-data threshold. It does. The
  property list it was derived from came from a grep for `"webinsight\.report\.[a-z]+"`,
  which stops at the first dot and so could never have matched
  `webinsight.report.externaldata.threshold`. The method was wrong, not just the conclusion.
- **F11** claimed the template checks the payload for presence but never identity. That was
  true once and the code comment still describes it as the bug it fixed; identity **is**
  checked now. This one matters most, because F11 was the basis for predicting that the
  `E04` HTML-vs-payload skew would be silent. It would not be.
- **F11a** then recorded that the identity check *warns* — that stale charts with a banner
  beat no charts. That trade has since been reversed in the template, deliberately and with
  the reasoning written into the code: a chart from another run is wrong output, and a banner
  above a plausible-looking chart is read by nobody once the numbers below it look reasonable.
  A mismatch is now **fatal** (**F11b**). This is not a correction of a mis-read, unlike F11
  and F12 — the fact was true when it was written and the product changed under it, which is
  the other way a grounding fact goes stale and is worth distinguishing.

All three are struck through below rather than deleted, so a row citing the old number still
leads somewhere.

| # | Fact | Source |
|---|---|---|
| F1 | Default HTML name `web-insight-report.html`; JSON/XML are **siblings** of the resolved HTML name | `WebInsightReportGenerator.java:40,96,97` |
| F2 | Overwriting is the **intended default**, and logs `WARN Overwriting an existing report` | `:141-143`, N06 |
| F3 | `${timestamp}` is `yyyyMMdd_HHmmss` — **second** granularity | `:339-340` |
| F4 | Filename allowlist `[\p{L}\p{N}._\- ]+`; anything else falls back to the default **with a warning** | `:359` |
| F5 | External payload name derives from the HTML name (`a.html` → `a-data.js`) — **except** the default name, which keeps `web-insight-data.js` | `:299-303` |
| F6 | `WRITTEN_THIS_JVM` is **static** — catches two listeners in one JVM, blind across processes | `:53` |
| F7 | HTML is written **before** the payload, so a failed HTML write leaves the previous run alone | `:195-203` |
| F8 | The `-g` exporter **skips** when it finds a report matching the expected glob, and warns | `WebInsightDataExporter.java:94-105` |
| F8a | …**except** when the resolved name has no literal part (`${timestamp}` as the *whole* name). Then it deliberately **generates rather than skips**, because "an existing report cannot be identified by name" and the alternative is silently no output. | `:335-340` |
| F8b | The exporter's output directory comes from `jmeter.reportgenerator.outputdir` (what `-o` sets) — it does **not** read `webinsight.report.output`, which is the listener's setting only. `-o` refuses a non-empty directory, so seeded/carry exporter rows must set `-Jjmeter.reportgenerator.outputdir` and omit `-o`. | `:173-178` |
| F8c | Two `-g` runs into one folder cannot reach the skip check at all: JMeter's own `JsonExporter` refuses the non-empty directory before any registered exporter runs. Only a **listener**-seeded folder reaches it. | observed, C06 |
| F9 | The exporter prefers the filename the listener resolved (a JVM property) over `-J` | `:90-93` |
| F10 | `AtomicFileWriter` stages at `.<targetname>.tmp` — **deterministic, not process-unique** | `AtomicFileWriter.java:89-95` |
| ~~F11~~ | ~~The external-data guard tests `window.__WIR_DATA__` for presence, never identity~~ **WRONG — corrected below as F11a** | |
| ~~F11a~~ | ~~The template does check identity, and **warns** rather than refuses — stale charts with a banner beat no charts~~ **NO LONGER TRUE — the trade was reversed; see F11b** | |
| F11b | Identity is checked and a mismatch is **fatal**. The page inserts an error block carrying `data-wir-payload-error="mismatch"` whose first line is "This report cannot show its charts.", names **both** runs (its own `runTestName`/`runId` and the payload's), rewrites every `.chart-container` to "Chart withheld — `<datafile>` belongs to a different test run.", and **never calls `initCharts`**. The server-rendered half — header cards, sampler and error tables — is this run's and still stands. A **missing** payload is unchanged in meaning and is a separate kind, `data-wir-payload-error="missing"`, still worded "charts unavailable". | `templates/report.html:797-853` |
| F11c | `runId` is **16** hex characters, SHA-256 over the run's fingerprint truncated to 64 bits — widened from the 8-character `String.hashCode`. Anything that fabricates or matches an id (a spec's foreign id, a grep) must not assume the old width. | `RunIdentity.java:72,124-143` |
| ~~F12~~ | ~~No `-J` property for the external-data threshold~~ **WRONG — corrected below as F12a** | |
| F12a | `-Jwebinsight.report.externaldata.threshold` **exists and works**. A non-numeric or zero value is refused with a named WARN and the 10,000,000 default is kept. | `WebInsightReportListener.java:191`, `WebInsightDataExporter.java:267` |
| F13 | Title chain is `-J` → JMX → hardcoded default. An **empty** `-J` falls **through** rather than winning | `WebInsightReportListener.java:118-125` |
| F14 | `-J` values are raw strings via `JMeterUtils.getProperty` — **never** function-evaluated | `:118` |
| F15 | The JMX/GUI field is read with `getPropertyAsString` on a `FunctionProperty`, so `${__P()}` **is** evaluated | `:120` |
| F16 | The resolved title reaches **three** artifacts: HTML `<title>`, JSON `"testName"`, JUnit `<testsuites name>` | `:342`, JUnit serializer `:85` |
| F17 | JUnit resolution has **no else branch**: only `"true"` enables, only `null`/empty consults the checkbox. Any other non-empty value matches neither | `:206-214` |
| F18 | `isEmpty()` not `isBlank()` — a whitespace-only title is not empty and survives | `:119,122` |

---

## Fixtures and runners

| File | Listener config |
|---|---|
| `title-function-test.jmx` | title = `${__P(wir.jmx.title,JMX Fallback Title)}`, JUnit off |
| `title-partial-test.jmx` | title = `Load ${__P(wir.env,dev)} build ${__P(wir.build,0)}`, JUnit off |
| `title-blank-test.jmx` | title field **empty**, JUnit off |
| `title-unicode-test.jmx` | title = non-ASCII literal (UTF-8 in the file, no argv involved) |
| `junit-jmx-enabled-test.jmx` | title = `JMX Literal Title`, **JUnit checkbox true** |
| `gui-filename-test.jmx` | *(existing)* title = `GUI-CONFIGURED`, filename `gui-name.html` |
| `filename-jmx-test.jmx` | filename = `${__P(wir.jmx.filename,jmx-name.html)}`, title empty, JUnit off |
| `multi-listener-distinct-test.jmx` | two listeners, filenames `listener-a.html` / `listener-b.html`, titles `LISTENER-A` / `LISTENER-B` |

`test-plans/scripts/run-title-junit-tests.sh` implements `T01`-`T29`.
`test-plans/scripts/run-family-ab-tests.sh` implements `A01`-`A12` and `B01`-`B12`.

The A/B runner cannot use `run_case`: more than half its rows are `carry` or `seed:`, and a
helper that removes and recreates the directory destroys the very state those rows are about.
It has `clean_dir` for the `clean` rows, and the `carry`/`seed:` rows clean **once** and then
assert the precondition landed — the seed's title marker is read back before run 2 starts.

`filename-jmx-test.jmx` is what makes the A-series filename rows drivable from a shell at
all. The field holds a single `__P()` reference, so the PreCompiler turns it into a
`FunctionProperty` and `getPropertyAsString` evaluates it (**F15**) — the same mechanism
`title-function-test.jmx` uses for the title. Its value is *not* re-evaluated afterwards, so
`-Jwir.jmx.filename='run-${timestamp}.html'` reaches the plugin's own resolver intact and the
JMX surface can be exercised without one fixture per filename. Do **not** pass
`-Jwebinsight.report.filename` alongside it, or `-J` wins (**F13**) and the row silently
stops testing the surface it names — the same trap `multi-listener-distinct-test.jmx`
carries, where a `-J` filename collapses its two listeners back onto one path and turns
`A08` into `A07`.

> **Writing a plan comment?** `${...}` inside `TestPlan.comments` is **evaluated** by
> JMeter's PreCompiler. A `${__P()}` written as prose stopped all four fixtures compiling
> and no run started at all. Write function names without the `${}` wrapper.

---

## What this is probing

1. **Sequential shared-folder reuse is lossy but consistent.** Overwrite is intentional (F2)
   and the only signal is a WARN in a log with no console appender, truncated every run.
2. **The two ingestion paths disagree about an existing report.** The listener overwrites;
   the exporter skips (F8). A stale report can suppress a fresh one and still look current.
3. **Concurrency into one folder is unguarded.** Per-JVM detector (F6), non-unique staging
   file (F10), separate writes nothing can reconcile afterwards (F7). The reader is no longer
   *unaware* of the result — F11b catches the pair at load time — but catching is not
   reconciling: the page that catches it shows no charts. Family E has no existing coverage.
4. **The two config surfaces disagree about JMeter functions.** Evaluated in the JMX (F15),
   not via `-J` (F14) — and `-J` wins (F13), so the documented override is also the way to
   print a literal `${__P(...)}`.
5. **JUnit resolution has a hole.** No else branch (F17), so `-Jwebinsight.report.junit=yes`
   silently suppresses a checked box.

Smallest useful fixes if E and J confirm: a process-unique temp sibling (PID/UUID) turns
mutual corruption into ordinary last-writer-wins, and an else branch that warns on an
unrecognised JUnit value. Neither changes documented behaviour. Neither fixes the
HTML-vs-payload skew in `E04`. The payload *is* identity-stamped now (F11b), which is what
made the skew detectable — but detection is a seatbelt, not a fix: the report it produces is
correct in what it says and empty where its charts were. Closing the skew itself still needs
an output-directory lock, or one output directory per run.
