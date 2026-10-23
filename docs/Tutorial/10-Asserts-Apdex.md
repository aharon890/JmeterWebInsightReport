# Asserts & Apdex Score

![Failing report](images/15-asserts-fail.png)

A JMeter run tells you what happened. A **quality gate** tells you whether that was acceptable —
and that is a question only you can answer, so you declare it.

```json
{
  "assertions": [
    {
      "id": "checkout-p95",
      "description": "Payment gateway budget, contract §4.2",
      "target": { "sampler": "POST /api/checkout" },
      "metric": "p95",
      "warn": { "gt": 650 },
      "fail": { "gt": 800 }
    }
  ]
}
```

Save that as `report-annotations.json`, point the run at it with
`-Jwebinsight.report.annotations=<path>`, and the run is judged by it. With no path given, a file
of that name in the report's output directory is picked up too, and the log says so at WARN,
because a file nobody named is deciding the verdict. A `jmeter -g … -o DIR` run always needs the
property: `-o` requires an empty directory, so there is nothing there to pick up.

A path that names no file, or a file that is not valid JSON, is a configuration error: nothing is
judged, the console prints a `CONFIG ERROR` line, and the run exits non-zero under `failonerror`.

## Four principles

**Declared ≡ judged.** Only rules you wrote decide the verdict. Everything else in the report is
data. One rule on one metric of one sampler is a complete, valid gate.

**Assertable ≡ displayed.** Every number the report shows can be asserted — any metric, any
percentile (`p50`, `p99.9`, `p99.99`, any `pN` above 0 and below 100), per sampler, per group, or
for the whole run.

**You write the direction.** Bands are explicit comparisons: `fail: {"gt": 800}` for latency,
`fail: {"lt": 30}` for throughput. There is no per-metric table of which way is bad, which is
why latency, throughput and apdex share one rule shape.

**Mistakes are loud.** A JSON Schema drives editor completion: make
`"$schema": "https://aharon890.github.io/JmeterWebInsightReport/schema/report-annotations.schema.json"`
the file's first key, and an editor that reads JSON Schema (VS Code, IntelliJ IDEA) completes keys
and flags typos as you write. The plugin ignores the key and keeps it when the report saves the
file. Load-time validation names the
rule and the fix. A rule the *file* got wrong is `NOT_JUDGED / INVALID_RULE` and exits the run
non-zero under `failonerror`. A rule that merely matched nothing is `NOT_JUDGED / NO_MATCH` —
reported on every surface, never hidden, but not fatal on its own, because a plan running a
subset is legitimate.

---

## The rule

| Field | Required | Notes |
|---|---|---|
| `id` | no | Unique. Names the JUnit testcase and appears in every failure message. Derived from the target and metric when omitted. |
| `target` | **yes** | Exactly one form — see below. |
| `metric` | **yes** | Case-insensitive. `p95` ≡ `p95.0`, `median` ≡ `p50`. What follows the `p` is read as Java reads a double, so `p95d`, `p+95` and `p9.5e1` are p95 too; the rule is reported under the canonical name, and a percentile below 0.001 is named in E notation: `p0.0001` is reported as `p1.0E-4`. |
| `fail`, `warn` | **at least one** | Predicates. `fail` is tested first. A rule with neither cannot decide anything and is `NOT_JUDGED / INVALID_RULE`. |
| `enabled` | no | `false` parks a rule without deleting it. |
| `description` | no | Carried into failure messages and shown in the report. |

### Targets

A rule's `target` takes exactly one of these forms:

| Selects | `target` |
|---|---|
| one sampler | `{ "sampler": "POST /api/checkout" }` |
| several samplers | `{ "samplers": ["GET /a", "GET /b"] }` |
| a pattern, minus exceptions | `{ "match": "POST /api/*", "exclude": ["POST /api/health"] }` |
| the whole run | `{ "scope": "overall" }` |

**Reach matters.** Naming a sampler reaches it whatever it is — a transaction-controller child,
a hidden utility sampler, a row the report does not display by default. Naming is an unambiguous
request.

A `match` pattern reaches only **top-level, non-hidden** samplers. If patterns reached children,
a blanket rule would start failing on rows whose time is already counted inside their parent.

**Patterns are globs.** `*` is the only special character; everything else is literal, because
sampler names are full of `{id}`, `?`, `+` and query strings of `&`, and you mean them. A glob,
like a regex, must match the **whole** name. Prefix with `regex:` for a full Java regular
expression: `regex:GET /(search|browse).*` selects every search and browse request, while
`regex:^GET /(search|browse)` selects only samplers named exactly `GET /search` or `GET /browse`.

### Predicates

Exactly one operator per band.

| | |
|---|---|
| `gt` / `gte` | breach above — latency, error rate |
| `lt` / `lte` | breach below — throughput, apdex, sample count |

Comparisons are strict where they say so: `{"gt": 800}` does not fire at exactly 800.

There is no corridor operator. A range is **two rules** — `{"lt": 40}` and `{"gt": 80}` — and
written that way each one says which end was missed, and each can show how far past its own
limit the run was. A single `outside: [40, 80]` band could do neither: two thresholds have no
one number to measure against. A band written with `outside` is refused, and the message names
the two rules to write instead.

### Metrics

> ### What `errorRate` counts — read this if you have an error threshold
>
> **`errorRate` and `errorCount` include samples that returned HTTP 200.**
>
> An error, to this report, is any sample JMeter marked `success = false`. That is JMeter's own
> failure signal, and it includes a sample failed by a **Response Assertion**, a JSON Assertion,
> a Duration Assertion, or a JSR223 calling `setSuccessful(false)` — none of which need an HTTP
> error to fire. A checkout that returns `200 OK` with the wrong body is an error here.
>
> This is the right behaviour: a gate that ignored assertion failures would pass a run where
> every response was wrong. But it is easy to miss, so a threshold written as "fail above 2%
> errors" can be read by its author as "2% HTTP failures" while meaning something broader. The
> report *shows* you the split, via
> [error classes](05-Errors-Tab.md#error-classes-where-a-failure-was-decided).
>
> If you want a threshold on one kind, say which:
> `errorRate:class=protocol` for HTTP failures (a status outside 2xx),
> `errorRate:class=transport` for samples that got no HTTP status at all (connection refused, a
> timeout, a failed TLS handshake), and `errorRate:class=nonProtocol` for samples that got a 2xx
> and were failed anyway, by an assertion or a script.

**Per sampler:** `sampleCount` · `errorCount` · `errorRate` (%) · `mean` · `median` · `min` ·
`max` · `stdDev` (ms) · `latency` (ms) · `connectTime` (ms) · `throughput` (/s) ·
`receivedKbPerSec` · `sentKbPerSec` (KB/s) · `apdex` · **any `pN`, 0 < N < 100** (ms)

`latency` is JMeter's Latency field — time to first byte — and `connectTime` its Connect time.
Both are means, as `mean` is. They matter because `elapsed` cannot see them: a connect-time
regression is the signature of a TLS, DNS or connection-pool problem while the total response
time barely moves.

The two byte rates are named in **kilobytes**, matching the `Recv KB/s` column they sit under in
the samplers table. What a rule judges is the number the report shows — otherwise the ⊕ beside
that cell would seed a threshold three orders of magnitude off.

### Gating one kind of error

`errorRate` and `errorCount` accept a selector, so a rule can name a slice of the errors instead
of all of them:

```json
{
  "assertions": [
    { "target": { "sampler": "POST /checkout" },
      "metric": "errorRate:class=nonProtocol", "fail": { "gt": 0 } },

    { "target": { "scope": "overall" },
      "metric": "errorRate:class=transport", "fail": { "gt": 0 } },

    { "target": { "sampler": "POST /checkout" },
      "metric": "errorRate:code=5*", "fail": { "gt": 1 }, "warn": { "gt": 0.2 } }
  ]
}
```

| Selector | Values |
|---|---|
| `class=` | `protocol`, `nonProtocol`, `transport` — see [error classes](05-Errors-Tab.md#error-classes-where-a-failure-was-decided) |
| `code=` | A glob over the response code as JMeter wrote it. `404`, `5*`, `Non HTTP*`. |

A 2% error rate that is all 404s from a stale data file is not the same run as 2% connection
resets under load. One number covered both, so the gate either fired on noise or was set loose
enough to miss a real failure.

**`code=` is a glob and nothing cleverer.** `errorType` *is* the response code, and JMeter writes
`Non HTTP response code: java.net.ConnectException` into that field verbatim. A glob over the
string is the honest expression; a richer taxonomy would be the plugin inventing one JMeter does
not provide. `*` is the only special character; prefix with `regex:` for a full expression
(`code=regex:5[0-9]{2}`). Either way it must match the whole code. A pattern that will not compile makes the rule INVALID at load time,
with the reason, rather than failing later.

**`code=` is case-sensitive; `class=` is not.** `class=NONPROTOCOL` and `class=nonProtocol` are
the same selector, because that vocabulary is closed and there is exactly one thing the writer
can have meant. `code=` is a glob over free text, so it matches literally: `code=non http*`
finds nothing against `Non HTTP response code: …`, and matching nothing is a legitimate answer
for a code glob, so it will not look like a mistake. Use `class=transport` — that is what it is
for, and it is the reason the glob is rarely the right tool for these.

**Seeding one without typing it.** The Errors tab's Class column carries a ⊕ on every row: it
opens the editor with `errorRate:class=…` for that sampler, already filled in. That is the
intended way to reach a class-qualified rule — the metric dropdown lists the plain metrics
only, so a qualified one is written in the file or seeded from a row you are already reading.
It seeds `class=`, never `code=`: a class is a closed vocabulary of three and keeps its meaning
next run, whereas a seeded `code=503` pins one literal string, and 503 is not the failure mode,
it is this run's spelling of it.

Three things worth knowing:

- **A mistyped selector is INVALID, never a silent match.** `errorRate:clss=nonProtocol` and
  `class=funcional` both fail the rule and say what is wrong. Neither falls back to "all errors",
  because a typo that quietly widened a gate is the failure this whole feature exists to prevent.
- **No matching error is zero, and passes.** `errorRate:code=404` on a run with no 404s is `0` —
  the question was asked and answered "none". That is different from a malformed selector, and
  different again from a sampler that recorded nothing, which is `NOT_JUDGED / NO_MATCH`.
- **A qualified metric inherits its base's scope.** `errorRate:…` works whole-run and per sampler;
  `errorCount:…` is per-sampler only, exactly as the plain metrics are.

> The editor's metric dropdown offers the plain names, and an existing qualified rule keeps its
> own entry when you open it — a new one is seeded from an Errors-tab row, as above, or written in
> the file by hand.

**Whole run:** `totalSamples` · `errorRate` (%) · `duration` (s) · `maxThreads` · `mean` ·
`median` · `throughput` (/s) · `apdex` · **any `pN`, 0 < N < 100** (ms)

Arbitrary percentiles ride the t-digest the report already computes — nothing extra is
calculated to be displayed, but any quantile can be asserted. The whole-run figures come from
one shared resolver, so the number in a verdict is the number in the header tile.

---

## Examples

```json
{
  "assertions": [
    { "id": "checkout-p95",
      "target": { "sampler": "POST /api/checkout" },
      "metric": "p95", "warn": { "gt": 650 }, "fail": { "gt": 800 } },

    { "id": "api-errors",
      "target": { "match": "POST /api/*", "exclude": ["POST /api/health-probe"] },
      "metric": "errorRate", "fail": { "gt": 5 } },

    { "id": "run-throughput",
      "target": { "scope": "overall" },
      "metric": "throughput", "fail": { "lt": 30 } },

    { "id": "search-tail",
      "target": { "match": "GET /search*" },
      "metric": "p99.9", "fail": { "gt": 4000 } },

    { "id": "rate-floor",
      "target": { "scope": "overall" },
      "metric": "throughput", "fail": { "lt": 40 } }
  ]
}
```

---

## The statuses

**There are four, and no more.** Every rule ends in exactly one of them, and the counts in the
`gate` block add up to the number declared — `passed + warned + failed + notJudged.total ==
declared`, and `disabled + noMatch + noValue + invalidRule + sourceFailed + lowCoverage +
loadNotAchieved == notJudged.total` — so a pipeline can check its own arithmetic instead of
trusting that nothing fell between the buckets. The last three reasons belong to server
asserts and are always zero on a client rule; they are in the block so it has one shape.

| Status | Gates? | What it means | Typical fix |
|---|---|---|---|
| **PASS** | ✅ yes | Measured; nothing breached. | — |
| **WARN** | ✅ yes | Measured. The `warn` band breached, the `fail` band did not. | Watch it, or tighten. |
| **FAIL** | ✅ yes | Measured. The `fail` band breached. Tested before `warn`, so a value past both is a failure, not a warning. | The system, or the budget. |
| **NOT_JUDGED** | ❌ **never, in either direction** | Nothing was compared to anything. Carries a `reason` — see below. | Depends entirely on the reason. |

Only a comparison makes a status. That is the whole model: three verdicts, and one value for
every way of not reaching one.

### Why a rule was not judged

The first column is the reason as the CI/CD JSON and JUnit write it; the second is the word the
report's tables, chips and filters show for it.

| reason | Page reads | Cause | Fix |
|---|---|---|---|
| `DISABLED` | `DISABLED` | `enabled: false`. | Nothing; it was parked on purpose. |
| `NO_MATCH` | `UNMATCHED` | The target selected nothing — no sampler matched, or every match recorded no samples. | Usually the target: check it against the sampler names the run produced. The report offers a did-you-mean. |
| `NO_VALUE` | `NO VALUE` | The metric is real but this run cannot produce it — an off-grid percentile with no retained distribution, a JTL column the test did not write. On a server assert: no point fell inside the judged window, or a `divideBy` divisor measured zero. | **The run**, not the file. The rule would work against a run carrying the data. |
| `INVALID_RULE` | `INVALID` | The file is wrong: unknown metric, a metric that does not exist in the rule's scope, an unparseable pattern, no bands, an unreadable target. A server assert has causes of its own (an unknown component, a `requiresLoad` naming no throughput rule, a `sustainedFor` the collection's step cannot hold, …), listed on the [Server tab](13-Server-Tab.md#why-a-server-assert-was-not-judged). | The rule. The message names what is wrong and suggests the nearest valid name. |
| `SOURCE_FAILED` | `SOURCE FAILED` | *Server asserts only.* The series could not be collected — the metric source refused it or never answered (authentication, timeout, DNS, a 5xx), a placeholder the collector could not resolve, a catalogue metric with no Prometheus query asked of Prometheus or a recording, or a row of a `--csv` export that could not be read. The reason quotes the collector's error, and the rest of the source may still have answered (`PARTIAL`). | What the reason names: credentials or the endpoint, usually DevOps, not the test author; or, for a CSV row, the file that was exported. |
| `LOW_COVERAGE` | `LOW COVERAGE` | *Server asserts only.* Data arrived, but below the coverage floor — coverage measured over the collected window. | The collector's wait, the source's step, or the floor. |
| `LOAD_NOT_ACHIEVED` | `LOAD NOT ACHIEVED` | *Server asserts only.* The load it depends on was not established: the client load rule it names (`requiresLoad`) FAILED, or was not judged at all, or the rule it divides by (`divideBy`) was not judged — so a band written for the target load says nothing about this run. A `divideBy` rule that FAILED still divides. | A FAIL: re-run the test; the load rule already carries the FAIL. Not judged: that rule's own reason — a renamed load sampler stands the server asserts down without failing the build. |

The three server-only reasons are counted on every gate and are always zero on a client rule; they
are in the vocabulary so a server assert and a client rule that matched nothing say the same word.
`SOURCE_FAILED` and `LOW_COVERAGE` are configuration errors like `INVALID_RULE` — checks the file
asked for that never ran. On the server side none of the three changes the exit code by default
(the server tab is a bonus on the client report); `webinsight.report.server.failonerror=true` makes
them fatal, on every server component. `LOAD_NOT_ACHIEVED` is neither: what went wrong is the load rule's to report. Its FAIL is
the client verdict; an `INVALID_RULE` load rule is a configuration error of its own; a disabled,
unmatched or unmeasurable one reports nothing, and the server asserts that depend on it stand down
without failing the build. Server asserts have their own tab and are grouped by component:
see [Server tab](13-Server-Tab.md).

**`INVALID_RULE` is not a verdict, and it still fails the run.** Those are two different
statements and both are true.

It is not a verdict. A broken rule contributes nothing to `gate.status`, and a run whose only
problem is a broken rule does not report `FAIL`: a red gate there would be a judgement about
performance from a rule that measured no performance. Beside a genuine breach the run reports
`FAIL`, because the verdict is the worst rule that decided something.

It still fails the run, because the *gate was not built from what the file declared*. Your file
asked for six checks and five of them never ran. That is the plugin failing to do what it was
told — the same category as an output directory it could not write to — and it exits non-zero
under the same flag:

```bash
# a rule the file got wrong: exit 1, and a CONFIG ERROR line on stderr
jmeter -n -t plan.jmx -l results.jtl

# report it and carry on: exit 0, marker still printed
jmeter -n -t plan.jmx -l results.jtl -Jwebinsight.report.failonerror=false
```

The distinction that matters: **a breached threshold is yours to act on, a file the plugin could
not honour is not.** One is the answer you asked for; the other is the plugin telling you it never
asked the question.

The report is still written, on both settings. Failing a build must not cost you the artifact that
explains why — and it is reported on every channel that exists: the finding, `jmeter.log`, the
`CONFIG ERROR` console line, the JUnit `<failure>`, the red banner at the top of the Asserts tab,
and `gate.notJudged.invalidRule` in the CI/CD JSON.

**And if you want the verdict itself to fail the run**, there is a flag for that too:

```bash
jmeter -n -t plan.jmx -l results.jtl -Jwebinsight.report.failonassert=true
```

Default off, and it will stay off. It fires on `FAIL` only — not on `WARN`, because a warn band
exists to say "look at this" without stopping anything, and not on `NOT_JUDGED` for any reason.
The FAIL it folds is the client gate's **or that of any server component**. There is no
observe-only component: a server metric you only want to watch gets a warn band and no fail band.

So, by default: a `FAIL` verdict exits 0, and exits non-zero only under `failonassert=true`; a
rule the file got wrong is `NOT_JUDGED / INVALID_RULE`, moves the verdict in neither direction,
and exits non-zero under `failonerror`, which is on unless you set it to `false`. Both flags are
read the same way by the listener (`-n -t`) and by `jmeter -g … -o`. See
[Export & CI/CD](11-Export-Integration.md#gating-on-the-gate).

Plus one state that belongs to the run rather than to a rule:

| | |
|---|---|
| **NOT EVALUATED** | No rule gated. Either nothing was declared, or every declared rule is `NOT_JUDGED`. **This is not a pass.** A run with no gate has not passed one — it was reported, not judged. The CI/CD JSON's `status` is `UNKNOWN` in both cases. With no client rule declared there is no `gate` block, and `"assertsEvaluated"` is `false` unless server asserts were judged; with rules declared and none judged, `gate.status` is `NOT_EVALUATED`. With no client verdict, the header badge reads one of three things: `NO ASSERTS DECLARED` when the file declares neither client rules nor server asserts, `SERVER ASSERTS ONLY` when server asserts were judged (their verdict is the server chip beside it), and `ASSERTS NOT EVALUATED` otherwise. |

> **Why there is no "inconclusive".** In CI such a status has to resolve to pass or fail anyway:
> if it fails the build it is FAIL with extra vocabulary, and if it passes it is a silent green —
> the one outcome this whole feature exists to prevent. Where the honest answer is "the question
> was wrong rather than the system", the report says so as a **fact about the run** — a number, a
> target and a shortfall — not as a verdict that withholds judgement. And where a rule genuinely
> cannot be answered, it is `NOT_JUDGED`, and its reason says which of the cases above applies.

## How a verdict is reached

1. Rules that are `enabled: false` are **DISABLED** and never affect anything.
2. A rule that cannot run is **`NOT_JUDGED / INVALID_RULE`**. It counts as a *configuration
   error*, not a failure: it moves `gate.status` in neither direction, and it exits the run
   non-zero under `failonerror`. A typo'd metric name silently passing is precisely the failure
   a gate exists to prevent — and so is a metric that does not exist at the scope it was asked
   for, such as `stdDev` on a whole-run rule or `maxThreads` on a per-sampler one. So are a band
   with two operators (`{"gt": 100, "lt": 50}`) even beside a readable sibling band, a band
   nothing can breach (`{"lt": -19}` — no metric here is ever negative), a threshold that is not
   a finite number (`{"gt": 1e400}` is past the largest double, so it reads as Infinity), and a
   rule carrying the key `observe` or `onMissing`, which is refused rather than ignored. All are
   rejected before anything is resolved.
3. The target resolves to matched samplers. **Nothing matched — or every matched row recorded
   zero samples** — is **`NOT_JUDGED / NO_MATCH`**, which gates in neither direction: the rule was not
   evaluated, so there is no performance verdict to report. What there *is* is a configuration
   problem, and the report says so as a finding and an unmatched count rather than as a
   verdict — a renamed sampler is not a slow one. A row
   that never ran has undefined latency stored as zero, so judging it would make every "under X"
   threshold pass. The report distinguishes the two cases, because the fixes differ: a name that
   matches nothing is a typo, a name that matches an empty row is a thread group that never
   started or a run that stopped early.
4. Per match: `fail` first, then `warn`, else PASS. A match this run cannot measure (an off-grid
   percentile with no retained distribution, say) is `NOT_JUDGED / NO_VALUE` and decides nothing.
   The rule takes the **worst** match that was judged, and is `NO_VALUE` only when none was. Every
   match is kept, passing ones included.
5. **The run verdict is the worst status across rules that decided something.** `NOT_JUDGED` is
   neutral whatever its reason — it never moves the verdict in either direction. A gate of
   nothing but `NOT_JUDGED` rules reports **NOT EVALUATED**, which is not a pass.

Declaring nothing is not the same as passing. A run with no gate, and no server asserts judged,
reports `"assertsEvaluated": false`; it was reported, not judged.

---

## Load validity: was the run even valid?

Ask for 5 requests a second, achieve 3, and every latency threshold in the file passes
comfortably: the system was never put under the load those thresholds were written for. The run
reports success and means nothing.

JMeter records what was *achieved* and never what was *intended*, so the target has to be
declared — as an ordinary whole-run rule with a floor below the load you asked for:

```json
{
  "assertions": [
    {
      "id": "load:throughput",
      "description": "Asked for 5 requests a second",
      "target": { "scope": "overall" },
      "metric": "throughput",
      "warn": { "lt": 4.75 },
      "fail": { "lt": 4.0 }
    }
  ]
}
```

That is 5/s with 5 % slack before a warning and 20 % before a failure. Being an ordinary rule,
both evaluators judge it, the editor previews it, the charts draw it, and the CI/CD JSON and JUnit
XML carry it. **There is no special status for it**: a shortfall is a fact about the run, and the
rule row already shows the number, the limit and the gap between them.

`throughput` or `maxThreads` are the honest answers to "did we apply the load we asked for", and
any whole-run metric works. The bands are the policy, and you choose it:

- Declare only `warn` and **this rule cannot fail a build**.
- Declare only `fail` and there is no warning stage.
- Declare **both** and you get the policy people usually want — warn when slightly short, fail
  when badly short.

A server assert can name this rule with `requiresLoad`, so that a server band written for the
target load stands down on a run that never reached it — see `LOAD_NOT_ACHIEVED` above.

A file that carries a `loadValidity` block (`metric`, `target`, and `warn` / `fail` each with a
`tolerance` fraction) is still read: at load it becomes exactly this rule, with the id
`load:<metric>` and each band at `lt target × (1 − tolerance)`, and every run reports a WARNING
finding naming the rule until the file is saved from the Asserts tab, which writes the rule and
drops the block. A `tolerance` of 1 or more puts the band at or below zero, which nothing can
breach, so the rule is `NOT_JUDGED / INVALID_RULE` — write a fraction, `0.2`, not `20`. A `target`
or `tolerance` that is not a finite number is refused the same way, in a sentence naming the
field: `loadValidity.target must be a finite number, not NaN`.

---

## Apdex

Apdex scores each sample against a threshold **T**: satisfied at or under T, tolerating at or
under 4×T, frustrated beyond — and a failed sample is frustrated whatever its time. A sampler's
score is `(satisfied + tolerating / 2) / samples`, to two decimals; the whole run's is the
top-level samplers' scores weighted by sample count. The report's T defaults to 500 ms and is set
with `-Jwebinsight.apdex.threshold`.

An apdex assertion therefore asserts that configuration as much as the score — "apdex fell below
0.8" means nothing without knowing T. The report does not print T, so keep it the same for every
run a rule judges.

| Score | Reading |
|---|---|
| 0.95 – 1.00 | Excellent |
| 0.86 – 0.94 | Good |
| 0.71 – 0.85 | Fair |
| 0.51 – 0.70 | Poor |
| 0.50 or below | Unacceptable |

---

## Validation

Every finding lands in three places — the JMeter log, the CI/CD JSON, and a notice in the
report — and names the rule and the fix:

- unknown metric, with the nearest valid name
- **target matched nothing, listing the samplers the run actually contained**
- an invalid glob or regex, with the position
- a band with several operators or one that is not `gt`, `gte`, `lt` or `lte`; a rule with no
  band at all; a non-numeric threshold
- an **unreachable warn band** — `warn > 500` beside `fail > 400` can never fire, because
  `fail` is tested first. Verdicts stay correct, so the loader warns; the report's editor says
  so under the form and still saves the rule.
- **duplicate ids** — an ERROR finding, because ids name JUnit testcases and CI/CD entries and two
  rules sharing one make those ambiguous. Both rules are still judged and the run does not exit
  non-zero over it, so rename one.

**An unrecognised key is not one of them.** This plugin parses one specific shape, and a key
outside it — a field from a newer version, a note you added by hand, something another tool
writes — is not a defect in your file. It is ignored, in silence, and preserved when the report
saves the file back.

That is affordable because an unknown key cannot change a verdict by definition, and because a
key *mistyped* from one of ours still surfaces through what it costs rather than how it is
spelt: `"faill": {"gt": 800}` on its own leaves the rule with no band at all, which is
`INVALID_RULE`, which is loud. The gap is a mistyped band **beside a valid one**: `"warnn"` beside
a valid `fail` runs without a warn band, and `"faill"` beside a valid `warn` runs warn-only and
can never FAIL — both without a word. If a rule never warns, or never fails, when it should, check
the spelling of its band keys first.

**The report is always written.** A rule that cannot run reports `NOT_JUDGED / INVALID_RULE`, and
an annotations file that is missing or will not parse is reported as a finding naming the path
(and the parser's line and column), and either way you still get the HTML, the JSON and the JUnit XML — they are the artifacts you need in order to
fix the config, so producing them is not conditional on the config being right.

**The verdict does not go red, but the run does.** `gate.status` stays clean: a FAIL comes from a
declared assert measured against a real number, and from nothing else. The process exits non-zero
anyway, because a rule the file got wrong means the gate is not checking what its author believes
it is checking. Silence there is how a typo'd metric name goes green for six months.
`-Jwebinsight.report.failonerror=false` buys back the exit code and nothing else — the marker,
the finding and the banner all still appear.

---

## Where to go next

- [Asserts tab](12-Asserts-Tab.md) — reading, tuning and saving rules in the report
- [Server tab](13-Server-Tab.md) — server asserts: the expected server metrics, compared with what
  was collected for the run
- [Export & CI/CD](11-Export-Integration.md) — the `gate` block and the JUnit testcases
