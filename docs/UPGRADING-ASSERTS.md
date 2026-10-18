# Upgrading — what changed about asserts

Read this if you have a `report-annotations.json` in use, or a pipeline reading
`<name>.json`. Nothing here is a bug fix you can ignore; every item changes what an
existing setup does.

The whole change follows from one rule:

> **A status is a judgement, and only a declared assert measured against a real number
> earns one.**

Everything below is that rule applied to a place where it was not true before.

---

## If you read only one section

**Neither of these produces a FAIL any more.** A mistyped metric name, and a rule whose
target matched nothing, are reported rather than judged — `gate.status` no longer goes red
for either. A verdict is a statement about *measured performance*, and neither of those
measured anything.

**A mistyped metric name still exits non-zero.** Not as a verdict — as a configuration
error, under `failonerror`, alongside an output directory the plugin could not write to.
Your file asked for a check that never ran, and that is the plugin failing to do what it
was told. **A run with a broken rule that exited 0 on 3.0.16 will exit non-zero from
3.1.0.** If that is not what you want:

```bash
jmeter -n -t plan.jmx -l results.jtl -Jwebinsight.report.failonerror=false
```

That buys back the exit code and nothing else — the `CONFIG ERROR` marker on stderr, the
`findings[]` entry and the banner in the report all still appear.

**A rule that matched nothing does *not* exit non-zero.** It is the one of the two that
might be legitimate — a blanket rule over a plan that happened to run a subset. If you want
it fatal, that is a `jq` you write yourself:

```bash
jq -e '.gate.notJudged.noMatch == 0' report.json
```

**Four keys were removed from the CI/CD JSON**, and the counts they carried moved into a
nested `gate.notJudged` object. A `jq` expression naming any of the old ones reads `null`, not
`0` — so a pipeline asserting `.gate.invalid == 0` now passes on **every** run, including the
runs it was written to stop.

| Removed | Now |
|---|---|
| `gate.unmatched` | `gate.notJudged.noMatch` |
| `gate.disabled` | `gate.notJudged.disabled` |
| `gate.skipped` | `gate.notJudged.noMatch` — `SKIPPED` was "the target matched nothing" |
| `gate.invalid` | `gate.notJudged.invalidRule` |

`gate.notJudged.total` and `gate.notJudged.noValue` are new and have no predecessor. Check your
expressions with `jq -e 'has("gate") and (.gate|has("notJudged"))'` before trusting a green run.
Full diff below.

---

## The status vocabulary: seven values became four

| Was | Now |
|---|---|
| `PASS` / `WARN` / `FAIL` | unchanged — still the only verdicts |
| `INVALID` | `NOT_JUDGED` + reason `INVALID_RULE` |
| `SKIPPED` | `NOT_JUDGED` + reason `NO_MATCH` |
| `DISABLED` | `NOT_JUDGED` + reason `DISABLED` |
| `OBSERVED` | gone — see *Retired keys* |
| — | `NOT_JUDGED` + reason `NO_VALUE` — **new**, split out of `INVALID` |

`NOT_JUDGED` gates in neither direction. It cannot fail a run and cannot pass one.

`NO_VALUE` is the useful new distinction: **`INVALID_RULE` means fix your file, `NO_VALUE`
means fix your run.** A rule naming `p99.9` against a run that kept no distribution is
well-formed and unanswerable; that used to be reported as if the rule were broken.

### INVALID no longer outranks FAIL

It was a status ranked *above* FAIL, so a run with a real breach **and** a typo reported the
word `INVALID` — putting the typo's word on the run and burying the threshold that had
actually been exceeded. The verdict is now the worst rule that decided something, so the
same run reports `FAIL`.

**You will see this immediately**: a report whose badge said `ASSERTS FAIL` because of a
broken rule now says `ASSERTS NOT EVALUATED`, and one that said `INVALID` beside a genuine
breach now says `ASSERTS FAIL`.

### A part-measured rule no longer loses its verdict

A blanket rule over twelve samplers where one could not produce the metric used to go
`INVALID` overall — **erasing a genuine FAIL** from another sampler the same rule had
judged. It now reports the worst of what it measured.

---

## Retired keys

These are no longer part of the rule shape. A file carrying them still loads and the rest of
the file is judged normally. **Two of them — `observe` and `onMissing` — are now refused by
name**, with a finding that names the key and what to write instead. They do *not* fall through
to the unknown-key policy below, because they are the keys whose removal would invert what the
rule was written to do. `tags` is deliberately not among them: ignoring it runs *more* rules,
never fewer, so the failure mode is more checking rather than less.

⚠️ **Three of them make the rule `NOT_JUDGED / INVALID_RULE`, which exits non-zero under
`failonerror`.** `observe`, `onMissing` and `outside: [lo, hi]` are the three.

`observe` and `onMissing` are refused **by name**: the key's presence alone refuses the rule,
whether or not it still carries usable bands. Drop `observe` silently and a rule written never
to gate starts turning the badge red; drop `onMissing: "fail"` silently and a build that used
to go red goes green. `outside` is refused where bands are read, so it also leaves the rule
with no band.

The rule is not graded and the verdict does not move, but the *run* fails — the plugin will
not pretend it checked something it could not build. Rewrite those rules — each message names
what to write — or pass `-Jwebinsight.report.failonerror=false` while you do. This is the
single most likely way an existing file changes its exit code on this upgrade.

| Key | What happens now |
|---|---|
| `observe: true` | **refused by name.** The rule is `NOT_JUDGED / INVALID_RULE` and the message says so — it meant "report this and never gate", and a rule that keeps its bands without it *does* gate. Delete the bands to stop it judging, or delete `observe` to let it judge. Every metric it tracked is already on the samplers table, for every sampler. |
| `onMissing` | **refused by name.** The rule is `NOT_JUDGED / INVALID_RULE`. An unmatched target is now always `NOT_JUDGED / NO_MATCH` and never fails the run on its own, so a rule relying on `onMissing: "fail"` would pass where it used to fail. Delete the key; if a missing sampler must stop the build, assert on it in the pipeline. The old default was **FAIL**, so *renaming a sampler failed your build*. |
| `tags` | ignored, and `-Jwir.asserts.tags` is gone. **This one gets stricter**: a rule the flag used to deselect now runs. |
| `outside: [lo, hi]` | rejected with a message naming the two rules to write instead — one `lt` at the low bound, one `gt` at the high one. Written that way, each says which end was missed and each can report how far past its own limit the run was; a single corridor band could do neither. |
| the legacy load-validity block | folded into `assertions[]` at load, as one ordinary rule. Save the file to make it permanent. It used to produce a rule the GUI could not edit, disable, delete or retune. |

## Newly refused: four shapes that used to be judged

These were graded before and reported `PASS` or `FAIL`. They are now
`NOT_JUDGED / INVALID_RULE`, which exits non-zero under `failonerror`. **A file that passed
yesterday can fail today** — and in each case the old answer was the dangerous one: each shape
could report PASS on a run it had never actually tested.

| Shape | Example | Why it changed |
|---|---|---|
| One readable band beside an **unreadable** one | `"fail": {"gt": 100, "lt": 50}` next to a valid `warn` | The rule used to be judged on the surviving band alone, its `fail` threshold silently never applied, while an ERROR finding sat in the output saying so and the run exited 0. |
| A band that **can never fire** | `"fail": {"lt": -19}`, or any downward band at or below zero | Every metric here is a duration, count, rate or score, so none can be negative. Such a band reports PASS on every run, including one that measured nothing at all. A tolerance meant as a percentage is written as a fraction: `0.2`, not `20`. |
| A **retired rule-level key** | `"observe": true`, `"onMissing": "fail"` | See *Retired keys* above. Only those two; `tags` is still ignored in silence. |
| A threshold that is **not a finite number** | `"fail": {"gt": 1e400}`, or a legacy `loadValidity` block whose tolerance is `"NaN"` | A JSON number past the largest double (about `1.8e308`) is read as Infinity, and nothing can breach `gt Infinity`: the rule reported PASS on every run, with no finding and exit 0, even under `failonassert`, while `lt 1e400` FAILed every run. The message names it as a server assert's does: `fail gt must be a number, not the number Infinity`. A load-validity block whose band works out as NaN or Infinity — a `"NaN"` tolerance or target, or an overflowed target — did the same, and is refused in a sentence naming the field the file wrote: `loadValidity.target must be a finite number, not NaN`, or `loadValidity.fail.tolerance must be a finite number, not NaN`. A quoted `"Infinity"` or `"NaN"` band was refused already. |

The second has a specific source. A legacy `loadValidity` tolerance of `20` — written by
someone who meant 20 % — used to be clamped to `1`, and `1 − 1` is zero, so the clamp produced
`lt 0`: the most lenient band available, in the feature whose whole job is refusing to pass a
run that never applied its load. The clamp is gone; the raw value reaches the check and the
message shows the mistake in the number.

**A load-validity target or tolerance that is not a finite number, `-Infinity` included, is now a
CONFIG ERROR** — `NOT_JUDGED / INVALID_RULE`, exit non-zero under `failonerror`, in the
`loadValidity.… must be a finite number` sentence above. Two of these used to be no error at all. A
`-Infinity` target was read as a target at or below zero, so the block declared nothing and the run
exited 0. A `-Infinity` tolerance was clamped to `0` and judged as `lt target`, a band of the target
itself.

**Saving from the report keeps the first and the fourth refused.** The Asserts tab's Download
used to write such a rule without the band the run could not read, so a file saved with no edits
at all came back as a rule judged on its surviving band, and the next run passed it with exit 0.
A report now writes the band back as its run read the file: verbatim, a bare `1e400` as the text
`"Infinity"`, and a legacy `loadValidity` block as the rule it was folded into, with its band as
the text Jackson writes, such as `"NaN"`. The next run still refuses each one, and the rule editor
will not save the band away until you replace it or remove it with ×. A legacy block opened into a
report with *Open file…* is read the same way: a target or tolerance that is not a finite number is
refused on the page in the run's sentence, and a Download writes the rule with its band as that
text, so the next run refuses it too.

**`failonassert` is now read on both ingestion paths.** It was listener-only, so
`jmeter -g results.jtl -o out -Jwebinsight.report.failonassert=true` exited 0 on a FAIL gate.
That run now exits non-zero. If a pipeline set the flag on the `-g -o` path and relied on the
exit code staying 0, it will start failing.

---

## Unknown keys are ignored, in silence

This plugin parses one specific shape. A key outside it — a field from a newer version, a
note you added by hand, something another tool writes — is **not** graded, **not** warned
about, and **is preserved** when the report saves the file back.

That is affordable because an unknown key cannot change a verdict, and because a key
*mistyped* from one of ours still surfaces through what it costs: `"faill": {"gt": 800}`
leaves the rule with no band at all, which is `INVALID_RULE`, which is loud.

**The one gap, stated plainly:** a mistyped *optional* key stays quiet. `"warnn"` beside a
valid `fail` runs without a warn band and says nothing.

---

## The CI/CD JSON

```diff
  "gate": {
    "status": "FAIL",
    "declared": 13,
    "evaluated": 12,
    "passed": 8,
    "warned": 1,
    "failed": 1,
-   "unmatched": 1,
-   "disabled": 1,
-   "skipped": 0,
-   "invalid": 1
+   "notJudged": {
+     "total": 3,
+     "disabled": 1,
+     "noMatch": 1,
+     "noValue": 0,
+     "invalidRule": 1,
+     "sourceFailed": 0,
+     "lowCoverage": 0,
+     "loadNotAchieved": 0
+   }
  }
```

Two arithmetics, both closed, both checkable:

```
passed + warned + failed + notJudged.total          == declared
disabled + noMatch + noValue + invalidRule
  + sourceFailed + lowCoverage + loadNotAchieved    == notJudged.total
```

The last three keys are the server-metrics reasons. They are always present and always zero on a
run with no server metrics — added keys, never a changed one, so a reader of the original four is
unaffected and a sum check over all keys still closes.

Also new:

- **`findings[]`** at the top level — what the loader and validator could not accept. It
  used to be emitted nowhere. It appears even when there is no `gate` block, which is the
  case that most needs it: a typo'd annotations path produced a report indistinguishable
  from one that never wanted a gate.
- **`assertions[n].notJudged`** and **`reason`** — which kind of nothing, and why.
- **`assertions[n].resultsCapped`** — the report keeps the worst 200 matches of a rule;
  this file keeps all of them. Both are deliberate, and now both say which they are.

## JUnit XML

A rule that decided nothing is `<skipped>` rather than `<failure>` — parked, unmatched, or a
metric the run could not produce. Those are not failures and a CI dashboard should not paint
them red.

**A rule your file got wrong is the exception: it stays a `<failure>`.** The message names the
problem, the same sentence that goes to `findings[]`. `<skipped>` was the wrong element for it
for a practical reason as much as a principled one — most CI dashboards render a skip as a
grey line nobody clicks, so the one state that needs someone to open the file was the one
state guaranteed to be invisible.

So `failures` counts breached fail bands **plus** checks that could not run, and `skipped`
counts the other reasons. Both are still derivable from the JSON:

```
configErrors == gate.notJudged.invalidRule + gate.notJudged.sourceFailed + gate.notJudged.lowCoverage
failures     == gate.failed + configErrors
skipped      == gate.notJudged.total - configErrors
```

That is the `<testName> - assertions` suite, and on it `sourceFailed` and `lowCoverage` are
always zero — server asserts never enter the client `gate` — so this is the original
`failed + invalidRule` arithmetic, unchanged. (`loadNotAchieved`, the third server key, is never
part of this sum — it is neither a config error nor a verdict.)

Server asserts get **one suite per server component**, `<testName> - <componentId>` (and
`<testName> - (no gate)` for asserts that named no declared component), each derivable from its
`serverComponents[]` entry, and the arithmetic above holds with that entry's counts. Every
component can fail the build, so there is no exception: the observe-only gate, whose FAIL and
collection failures were `<skipped>`, was retired with `failsBuild`. The root `<testsuites>` counts
are the sampler suite's only, as before.

---

## New

**`-Jwebinsight.report.failonassert=true`** — exit non-zero when the client gate says FAIL,
or any server component does.
Default **off**, and it will stay off: deciding what a FAIL means is your job. It does not
fire on WARN, and it does not fire on `NOT_JUDGED` for any reason.

**An Annotations File field in the listener GUI.** Until now there was none, so a GUI run
could not be pointed at an asserts file by any means. `-J` still overrides it; empty still
auto-detects.

**Open a file from the report.** `Open file…` on the Asserts tab loads a
`report-annotations.json` and evaluates it against the run on screen. The header badge keeps
reporting what the run was actually gated on — a file opened afterwards is a question, not
an answer.

**Edit the whole document.** `Edit document…` reaches every block, including keys this build
does not understand. Previously one block of ten was editable.

**A Budget column.** How much of each fail band was used — `24%`, or `212%`. `<1%` when a
real budget rounds away, and empty when there is no budget to measure, because those are
opposite facts.

**A banner for rules that could not run**, at the top of the Asserts tab, not dismissible,
with a Fix button. This is the direct replacement for the verdict a broken rule used to
produce.

---

## Things that read differently but did not change

- **Notes are a draft.** Typing in the Notes tab now shows an unsaved change and can be
  discarded. It always altered the file a Download wrote; nothing said so.
- **Comparison verdicts have their own colours.** `REGRESSION` no longer borrows the assert
  fail class. A comparison verdict is a computed observation; an assert verdict is a
  declared judgement.
- **An id you did not write is not written back.** A rule with no `id` gets one derived for
  display. Saving used to add it to your file.
