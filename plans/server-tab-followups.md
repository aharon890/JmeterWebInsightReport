# Server-tab follow-ups after the components redesign

Status: **built 2026-10-04 at 3.1.37, gate green, not pushed.** Batches 0-7 are each one commit
plus review follow-ups (from Batch 5 on, one review over Batches 5-7, `5874e10`). Full gate on
`f61c6d3`: regression 6/6 (923 browser tests, R06) and the config tier 39/39, after a C38 harness
fix. Screenshots and reportExample retaken (`4cffbcb`). Each batch's "As built" note below is the
record. Decisions taken without Aaron are listed at the end of §5.

An external review of the branch at `a237ad7` followed on 2026-10-05. It was triaged, accepted in
full, and built as Batches A-G (`6c9e625`..`b0e3e08`, 3.1.38-3.1.43). §6 is its record. Its final
gate (full regression, config tier) had not run when §6 was written.

Approved 2026-10-03 ("implement"), built on `fix/server-tab-followups` (cut from
`feat/server-components` at `4a1c10c`, per Q1). No answers were given to §5, so every
recommendation there is the decision. Two placements moved while scheduling: Q9's E27 data lands
first, in Batch 1, because it regenerates every fixture; and SF39 moves to Batch 7 beside SF42,
since Q5(b) makes them one control. Written 2026-10-03 against 3.1.30. Covers item 1 (the open
Server-tab bugs) and item 3 (an exploration session over E26 and E27) from the end-of-step-4
summary.

How it was found: a research round (6 agents re-checking every open item at 3.1.30, 1 sweep for open
items nobody listed, 3 headless explorers over copies of E26/E27 and variants built in scratch), then
an adversarial round (7 agents told to *refute* each claim on freshly regenerated fixtures, 1 explorer
for the paths nobody had opened: external data, the listener's interim report, a comparison report).
Every row below survived the second round; severities are the second round's. Refuted and intended
items are in §3 so they are not re-litigated. Raw evidence: `tmp/server-tab-followups-evidence/`
(gitignored).

## 0. A correction to the step-4 summary

Its "item 2 — client-side bugs (Group A)" is not open. Group A was fixed in **3.1.23** (`54ef055`)
and is pinned in `client-fixes.spec.ts`: B1–B4, B8, B10, U2 (undo, both tabs), U3, U4 (both
editors), U5, U9a–c. U10 moved to the redesign and is still open (SF35–SF38).

## 0a. N6's regression ran on a mixed classpath — rerun clean, still green

N6's regression (16:07–16:15 on 2026-10-03) ran with **two** plugin JARs in `lib/ext`, 3.1.29 and
3.1.30: the deploy's purge had failed (VS Code holding the JAR, the known trap), and JMeter loaded
3.1.29's `report.js`. Every fixture therefore inlined a `report.js` one edit behind the tree (the 4c
Validate sentence) while its `.fixture-stamp` said "current". The config run at 16:18 caught it —
`wir-collect` refuses two JARs — and the old JAR was moved out by hand, but the regression was never
rerun. Established from the previous session's transcript and the JARs' contents.

**Rerun 2026-10-03 with `--force-fixtures`: 6/6, 749 browser tests, R06 green**, and E26/E27 now
carry 3.1.30's sentence. N6's verdict stands. The hole that let it happen does not:

| # | Hole | Where | Fix |
|---|---|---|---|
| I1 | A failed `rm` of a stale plugin JAR is ignored: "Removed N stale plugin JAR(s)" prints, `deploy_plugin` returns 0, and R01 **passes** with two JARs in `lib/ext`. | `scripts/lib/common.sh:305` (`rm -f "$jar"`, status unchecked) | After the purge, re-list `jmeter-web-insight-report-*.jar`; anything but `$keep` fails `deploy_plugin` (so R01/S01 and `build-and-deploy.sh`) with a sentence naming the file and the VS Code lock (`Move-Item` works where `rm` fails). |
| I2 | The fixture stamp hashes the tree at stamp time, not what produced the report, so a fixture rendered by another JAR stamps "current". | `write_fixture_stamp` (`common.sh:479-483`), `fixtures_current`, `global-setup.ts` | `fixtures_current` refuses while `lib/ext` holds more than one plugin JAR, and the stamp also records the md5 of the deployed JAR's `static/js/report.js`, so a mismatch names its cause. |

`check-deploy-freshness.sh` (the Stop hook) already checks for two JARs, but it only warns, and only
when a turn ends. By design it never blocks, so it cannot stand in for I1.

**As built (Batch 0, 2026-10-03, at 3.1.30; no `src/main` change, so no bump).**
- **I1.** `purge_old_plugin_jars` lists `lib/ext` again after the loop and believes that over `rm`.
  "Removed N" counts only files that are gone. Anything left prints an ERROR naming each file, the
  VS Code cause and a `Move-Item` line per file, and returns 1. `deploy_plugin` propagates it, so
  `build-and-deploy.sh`, R01 and sanity's S04 (sanity deploys in S04, not S01) fail.
  `selftest_purge_old_plugin_jars` is in S03, R03 and CI. It deploys into a scratch `lib/ext` and
  checks two things: a removable stale JAR still goes, and a stale JAR whose `rm` fails is refused
  by name. It is red against the old purge, and separately against the old `deploy_plugin`.
- **I2.** The stamp now ends with one record per resource its listing names,
  `@deployed:<entry> <TAB> <md5 | none | unavailable> <TAB> <JAR name or reason>`: the md5 of that
  entry unzipped from the one plugin JAR in `lib/ext`, for everything under either module's
  `src/main/resources` except `META-INF/` (shade-written). `none` means no JAR, two or more JARs,
  or the entry missing from the JAR. `unavailable` means no `unzip`. `fixtures_current` reads the
  stamp with builtins and refuses, silently, while `lib/ext` holds more than one plugin JAR.
  `fixtures_current` and `stale_fixture_reason` compare each record with the stamp's own tree line
  for that file and name the cause and the files. A resource without a record is stale.
  `unavailable` skips only that comparison and says so once. `global-setup.ts` gets its whole
  verdict from a new CommonJS `test-plans/playwright/fixture-stamp.js`, using the same constants
  and the same sentences.
  - Pins: `selftest_fixture_stamps` (S03, R03, CI; red against HEAD's three stamp functions), and
    `js-unit/fixture-stamp.test.js`, which also holds the module's key and sentences to
    `common.sh`'s. Neither pin was in the batch brief. They were added because "every fix ends
    pinned" applies to I2 too.
  - Every fixture was stale by design and was regenerated once (13/13 plus CMP). A spec run before
    that refused all 14 fixtures as "stamped before stamps recorded which JAR rendered the report".
  - Docs: CLAUDE.md (Build & Deploy, the fixture-stamp paragraph, the static-check list),
    `WIKI/Instructions.md`, and the `dev-cycle` skill.
- **Log.** The 2026-10-03 entry has one row per SF plus I1, I2 and §3. The 2026-10-02 B5…U9d line
  is rewritten as asked, and its U10 line likewise (U10a/b/d → SF35/37/38, U10c intended), since it
  made the same "redesign replaces it" claim.
- **Review follow-ups (one commit after Batch 0).**
  - The first record held `report.js` alone. A JAR whose template, CSS, ECharts or catalogue was not
    the tree's stamped current, and CLAUDE.md blamed that on classes, which it was not. The record is
    widened to every byte-copied resource, as above. Only Java is beyond it now, and the docs say
    so. Every fixture is stale by design again (the first shape reads as "stamped before…").
  - `require_deployed_plugin`, the `SKIP_BUILD=1` path's check, which has no purge, refuses two
    plugin JARs before anything runs. `fixtures_current` no longer prints the two-JAR sentence,
    which sanity printed twice; `stale_fixture_reason` gives it, and sanity's S05 says to clear
    `lib/ext` first when that is the cause. `stdin_md5` is gone (no caller).
  - `checkFixture` and the problem wording moved from `global-setup.ts` into `fixture-stamp.js`
    (`assessFixtures`), so js-unit runs them over a scratch tree.
  - New pins. Purge cases: an `rm` that returns 0 and leaves the file, and `require_deployed_plugin`
    with two JARs and with one. Stamp cases: a CSS-only difference, two differences ("are"), the
    first record's shape, a `none` record read on one JAR, `unavailable` read (current, noted
    once) and written (via `_have_unzip`), an unreadable JAR, the writer's md5 per entry read back
    through `_read_fixture_stamp` (three entries re-hashed by `file_md5`), and silence on two JARs.
    In js-unit: `checkFixture`/`assessFixtures` and global-setup's wiring. 17 mutations of the
    purge, the reader, the writer, the module and global-setup each turned a pin red.
    `selftest_fixture_stamps` runs in a subshell that hashes the tree once, so on Git Bash it takes
    about 7s against the previous 8s despite twice the cases.
  - Docs: CLAUDE.md, `WIKI/Instructions.md` (the deploy now has four steps, including the purge and
    its failure), global-setup's hint (it names no fixture ids now), and the log (I1, I2, SF45 and
    the 2026-10-02 B5…U9d line).
- **Code-review follow-ups (2026-10-05, Batch A of `tmp/server-tab-review-triage.md`: F3, F10; no
  `src/main` change, so no bump).** I2's claim that the records name what rendered the report was
  false on one path, and the module's tree comparison was half of the shell's.
  - **F3.** A regeneration whose JMeter rendered nothing kept the previous cycle's report under a
    fresh stamp: `run_test_jmeter` stamped unconditionally, `_execute_scenario` cleared only
    `results.jtl`, `validate_results` is `[[ -f ]]`, and `generate-cmp-fixture.sh` stamped right
    after recording its own FAIL. So R04 said "regenerated" and R05 tested the previous build; a
    failure confined to the listener's render path went fully green. Now `run_test_jmeter` removes
    its report, `.json`, `.xml`, sidecar and stamp first, refuses (ERROR, return 1) when the report
    survives `rm`, and stamps only when the report exists afterwards; every scenario and CMP start
    from an `rm -rf`'d directory, which also clears the auto-detected `report-annotations.json` and
    `baseline.jtl`. JMeter's exit code is never the signal. Pin: `selftest_failed_regeneration` (S03,
    R03, CI), with a pre-flight that refuses to run the runners unless a child shell resolves
    `RESULTS_DIR` to its scratch directory.
  - **F3, after review.** The still-there guards were unpinned (nothing made `rm` fail), and E20,
    E26 and E27, which `rm -rf` their own directory for `-g -o`, had none: under a real Windows lock
    the old E27 report PASSed and was stamped current. Now those three refuse a report `rm` left
    (`_report_survived_rm`), and a `run_test_jmeter` refusal reaches the verdict (`<id>.failed`
    beside the `.done` markers; `validate_results` read only the files, so it said PASS). The
    selftest replaces `rm` with one that fails under `run_test_jmeter` (and one that returns 0 and
    leaves the file), E05, E26, E27 and CMP; its stub exits 0 having written nothing and 1 beside a
    report, so keying on JMeter's exit code fails it too. Red against the pre-fix scripts, against
    the pre-review fix, and against 19 single-part mutants. E20's guard is unpinned: the selftest
    cannot reach it without Python. `files_md5`'s BSD branch had the same GNU-only `\t` as
    `stale_fixture_reason`, so on macOS the listing had no path/md5 separator at all.
  - **F10.** `fixture-stamp.js` looked each stamp line up in the tree, so a file added since
    stamping read "ok" to Playwright while `fixtures_current` called it stale; the JAR comparison
    skipped an added resource too. It now walks `STAMP_ROOTS` (the directories
    `_fixture_stamp_files` hands to `find`) once and compares the listing whole. js-unit holds
    `STAMP_ROOTS` to that `find`, and its scratch tree moved under the real stamped directories;
    `selftest_fixture_stamps` gained an added-file and a deleted-file row, which pin the shell side
    (red against a per-line `fixtures_current`). `stale_fixture_reason`'s sed uses a literal tab,
    since `\t` there is GNU-only. A direct Playwright run now refuses while an untracked file sits
    under a stamped directory, as the shell already did.

## 1. Findings, by batch

`SF` = server follow-up. Original ids kept in brackets. Sev is after verification: **major** = a
wrong verdict, a wrong number on a verdict surface, a silent non-gating path, data loss, or a flow
that cannot be completed.

### Batch 1 — silent passes, a wrong verdict, data loss

> **As built (3.1.31).** Q9 landed first: `e27-metrics.csv` gained `database.size.growth` on pg-1 and
> pg-2 and one `up` = 0 on pg-1 at 22:13:44; the payload was re-collected (runId unchanged, 9 series).
> Vectors first — four `U7:` and five `SF2:` cases, eight of which failed on the old Java. SF1:
> `ServerKpi` trims the query; the catalogue refuses a padded template rather than trimming it; a
> query with a no-break or zero-width end is a WARNING (`INVISIBLE_PADDING`, Q2), mirrored word for
> word in `kpiWarnings`; `kpiFromJson` trims through `javaTrim`, and an unedited entry still writes
> back as the file spelled it. SF2: `Across.MIN`, declared on `up` and `cache.hit.pct`;
> `decidingInstances` keeps the low instance; the mirror's fold, the catalogue read and the editor's
> select take `min`; every refusal says "max, min, avg". The picker's "low" is already the number
> Java compares for those two (reduce min, across min), so it needed no change; the general
> `observedOf` is Batch 2. SF3: `WIR.asserts.init` keeps `unavailable()`'s reason, `WIR.document`
> neither restores, persists nor downloads while a slice is unavailable (Notes' Save is disabled
> with the reason as its title, every Download and Copy toasts it), drafts key on `pageRunId`, and
> the Summary server card states the reason when the header has a chip. SF4: trailing JSON is
> refused by both mappers. SF5: `serverCatalogue` on every page; "→ … if saved" only once the
> server slice is edited, or anything is edited while a server assert is declared (a client rule an
> assert's `requiresLoad` names re-decides it). SF6: a components-only file is collected. SF7:
> docs only. R06 did not move. E27 moved deliberately: the Generate review no longer offers `up`
> (it FAILs its own run) and offers `database.size.growth`.
>
> **Review follow-ups (3.1.31).** SF2's writer compared `across` with `'max'`, so an explicit max on
> `up` or `cache.hit.pct` was dropped and the board showed PASS over a file that FAILed; it now
> compares with the KPI's own default (`acrossDefault`, the catalogue's for a resolved metric). SF3:
> `WIR.asserts.render` carries `init`'s guard, so a public `rerender()` keeps the reason. SF1: the
> collector prints U+00A0 as its escape and a `WARNING` for a padded resolved query, the one place an
> `${env.*}`-padded query can be seen; Validate words its line from the warning; one more `U7:`
> vector (the warning on a no-band assert); the vectors spell invisible characters as JSON escapes.
> SF6: an empty payload names the catalogue only when a catalogue component is declared. SF7: a
> component with no asserts is not in `notJudged` (NOT_EVALUATED, `declared` 0), in all four copies.
> SF5's second projection clause is kept and pinned. Deferred: screenshots 68–70, re-taken after
> Batch 2 so 70 does not capture SF9's band; the board's "declares server-metrics sources" sentence
> on a collected components-only page is SF20 (Batch 4).

| # | Sev | What is wrong | Cause | Fix |
|---|---|---|---|---|
| SF1 [U7] | **major** | A custom query with surrounding whitespace keys its series by the padded text. On the CSV and recording backends nothing matches, the assert is NO_MATCH (non-fatal by design), and a run that should FAIL exits **0 under `failonassert`**. Reproduced end to end: `lag` → FAIL, exit 1; `lag ` → PASS, "1 unmatched", exit 0. The page shows NEEDS RE-COLLECTION, and re-collecting does not help. | Every assert text field is trimmed except `query` (`ServerMetricsMapper.java:376,380`, `ServerKpi.java:58`); `CsvSource` trims its cells but looks rows up with the untrimmed key (`:72`, `:241-243`). | **Vectors first.** Trim in `ServerKpi`'s constructor, which covers mapper, collector, evaluator, writer, JSON and JUnit. In the mirror, `kpiFromJson` uses `javaTrim`, never `String.prototype.trim` (that one also strips U+00A0 and would split the two sides). Also trim or refuse a padded query in `CatalogueMetric`, because `collectCatalogue` bypasses `ServerKpi`. A query padded with NBSP or a zero-width space still passes silently after the trim; see Q2. |
| SF2 | **major** | A lower-is-worse catalogue metric is folded across instances with **max**, so the healthy instance hides the dead one. Postgres `up` with its own safe default `fail < 1` **PASSes while one of two databases is down**, and `cache.hit.pct` likewise. Java and the page agree, so both are wrong. | `Across` has only MAX/AVG, default MAX (`Across.java:40-46`); `postgres.json` declares no `across` on `up`/`cache.hit.pct`. `across: min` was deferred to the v2 backlog for hand-written asserts (`server-metrics-delivery.md:887`), but the catalogue later shipped a default that depends on it. | **Vectors first.** Add `Across.MIN`. Declare `"across": "min"` on `up` and `cache.hit.pct` in `postgres.json`, explicitly per metric rather than derived from `direction`: the file is the one copy both sides read. Call sites: `decidingInstances` must keep the LOW instance under MIN, or the instance cap drops the deciding one; the mirror's fold and its `across` select; every "is not one of max, avg" sentence (`Catalogue.java:177`, `ServerMetricsMapper.java:440`, `report.js:16672`, `KpiUsabilityTest:44`, `server-drafts.spec.ts:708`). R06 and E27's numbers do not move. |
| SF3 | **major** | An external-data report opened without its `-data.js` sidecar (someone forwards only the HTML): the Asserts tab says "No asserts declared — reported, not judged" under an **ASSERTS FAIL** header, and **Download writes `{"assertions":[]}` with no `serverMetrics`**, so saving overwrites the team's file with an empty one. Drafts are keyed `wir_doc_drafts_unknown`, so a rule typed on one such report shows up on another. The Summary server card is blank beside a `SERVER · KAFKA FAIL` chip. | `WIR.asserts.init` resets its state from the inline `{}` *after* `wirAssertsUnavailable()` filled the tab (`report.js:12681-12712`). `WIR.serverKpis.init` has the guard (`:19601`, R3-15) and its comment wrongly says the asserts module has one too. `draftKey()` falls back to `unknown` although the template carries `pageRunId`. `renderSummaryCard` has no unavailable branch. | Give `WIR.asserts.init` the same guard; key drafts on `pageRunId`; refuse Download, or download nothing, while unavailable; give the Summary server card the unavailable sentence. Pin over `file://` in `external-data.spec.ts`, beside R3-15. |
| SF4 | minor | Java ignores anything after the first JSON value: `cat a.json b.json`, or a `>>` append, and the second document's rules never gate (exit 0, nothing logged). The page refuses the same file. The payload loader behaves the same way. | `AnnotationLoader.newObjectMapper()` (`:80-87`); `ServerMetricsPayloadLoader` (`:99,117`). | Enable `FAIL_ON_TRAILING_TOKENS` on both mappers. |
| SF5 [SWEEP-1] | **major** | No catalogue on a page whose file declares no server asserts. A client-only report's empty Server tab offers only "Custom". A file with components but no asserts yet shows two false ERRORs ("type 'postgres' is not one of custom"), "+ metric" opens a dead end ("orders-db (cannot be used)"), and the untouched page reads "→ NOT EVALUATED if saved". | `GatePayloadBuilder.java:107-117` returns before `serverCatalogue` is put. | Emit `serverCatalogue` unconditionally, as `assertMetrics` already is. It is 8.6 KB against a 10 MB `externalDataThreshold`, and nothing asserts its absence. Fix the three comments that promise "byte-identical". Show "→ … if saved" only when `WIR.document.countOf('server') > 0`. See Q4. |
| SF6 [SWEEP-2] | minor | `wir-collect` exits 1 on a file with components and no asserts, so D1's "collect the whole catalogue, then pick from this run" cannot start. | `WirCollect.java:183-185`. | A declared catalogue-typed component counts as something to collect. Keep exit 1 for a file with nothing collectable at all. |
| SF7 | minor (docs) | The DEFAULT rationale in `jq-gate.txt` and `server-metrics-delivery.md:656` say a component that judged nothing "already exits 1 through failonerror". False for NO_MATCH, DISABLED and empty components, which exit 0. Combined with SF1, that was the silent path. | — | Fix the comments, not the programs. The jq lines themselves are unchanged. |

### Batch 2 — run-derived numbers (picker, Set thresholds, Generate)

> **As built (3.1.32).** Java: `ServerKpiEvaluator.judgedActual` (the evaluator's own reduction,
> window and step check; null where Java has no number) and `ServerMetricsPayloadWriter` ships it
> on every capped series of a catalogue metric, beside `judgedMax`, which keeps its contract (the
> level's maximum, a custom seed's actual) and is now `judgedActual(max, max)`. Chart data only:
> R06 did not move, and no fixture's chart data gains the field (E26's one capped series is
> custom, E27's are uncapped). Page: `observedOfSeries` is the one number — `reduceAcross` with the
> catalogue's reducer and across on a recomputable series, `judgedActual` on a capped one, null
> without it — and `runOf` returns it as `observed` beside a line folded with the metric's across
> (the Batch 1 carry-over: `up`'s line now dips), the collector's count and `capped`; its cache is
> keyed on the component's type and match and the judged window. It feeds the picker's list and
> facts, the thresholds step's column (renamed **Actual**, "peak 3 s", "low 97 %", "slope … B/min"),
> `prefillBands` and Generate (`seedCatalogueKpi`; custom seeds keep `judgedMax`). `prefillBands`
> drops a run band `negativeOnlyWarning` flags and falls back to the safe default under every rule,
> else asks; the chips are Suggested / 20% headroom / 50% headroom. `bandBox` is the one band-box
> reader (the editor's too). The review's emptied fail is no fail band (warn-only), refused when no
> band would remain; a typed fail takes the metric's direction (`seedFailOp`). Both steps judge
> through `draftVerdicts` = `rowsFor` + `rowDisplay` + `rowReason` (the board's reason sentences,
> extracted so one copy serves both) and show `kpiWarnings` without blocking; the review gained a
> Verdict column. Validate and the picker count `instanceCount` (`instanceTally`, shared with the
> board's Instances cell). A capped Generate seed, which cannot be re-judged, is held against the
> build's number it was taken from. Pinned in `server-prefill.test.js` (new) and the picker, board,
> seeding and editor specs; the capped pins patch E27's chart data in an init script, before the
> first render. One existing expectation moved: the picker spec clicked "Run + 20%".
>
> **Review follow-ups (3.1.32).** The build's number is per metric: `judgedActual` became
> `judgedActuals {metric: number}`, one for every catalogue metric of the component whose query
> `findByQuery` resolves to that series, because Kafka's `consumer.lag.total` (max) and
> `consumer.lag.final` (last) are one query and the collector writes one series for both — the
> single number under the recorded name was shown and banded for the other. The page reads
> `judgedActuals[kpi.metric]` and nothing else. A build number (`judgedActuals`, a custom seed's
> `judgedMax`) answers only for the window the build judged: when an opened or edited file moves
> `collection.excludeRampUp` over a payload with a ramp-up, it is withheld (`buildNumberHolds`), so
> nothing is proposed or seeded from it. The picker's Suggested line says why it proposes nothing
> (no data, no number, or a band that could never fire), and the review says under a capped seed's
> NOT RE-JUDGED chip what it was held against. The cache key, the review's warnings and verdict
> derivation, the capped seed check and the off-step guard are pinned.

One model fixes all of these. **`observedOf(component, metric)` = the number Java would compare for
this assert**: the catalogue's reducer, `across` and `over`, over the judged window, on the full
series. It feeds the picker's list and facts, the Peak column, `prefillBands` and the seeds.
- On a recomputable series it is the mirror's `reduceAcross`, which is already parity-pinned. No E27
  number moves.
- On a capped series it is a Java number shipped in chart data only (`judgedActual`, an S–M Java
  change; the CI/CD JSON does not move). Until that exists, the run prefill is withheld and the
  catalogue's safe default is kept.
- A run-derived band that `negativeOnlyWarning` would flag is dropped, with fallback to the safe
  default, else the row asks.
- The row verdict goes through `rowsFor`/`rowDisplay` (the board's derivation), never
  `evaluateKpis` directly.
- One band-box parser (`javaTrim` + `javaNumber`), and `kpiWarnings` shown without blocking.

It depends on SF2: until then, "observed" for a multi-instance min metric is the best instance's.

| # | Sev | What is wrong | Cause |
|---|---|---|---|
| SF8 | **major** | On a capped catalogue series the picker, Set thresholds and Generate read the thinned copy. They show peak 3 where Java's actual is 120, judge it live as PASS, and seed `fail > 4.3`, which **FAILs the very run it was suggested from**. | `runOf` (`report.js:18995-19030`) folds `series.instances` and never reads `judgedMax`/`recomputable()`; `rowVerdict` re-judges thinned points. CLAUDE.md: a capped series keeps Java's verdict. |
| SF9 | **major** | A slope metric (`database.size.growth`) is seeded from the raw level: `fail > 7,572,000,000 B` on a 60 MB/min growth, a band that can never fire, under the review's "every one passes it by construction". | `prefillBands` seeds from `run.peak` whatever the reducer; the C-7 wrong-space mistake again. |
| SF10 | **major** | A lower-is-worse metric whose run touched 0 is prefilled `fail < 0`, a band that never fires. It shows PASS with no warning on the page; Java warns on the *next* run. Kafka `brokers` reads PASS with every broker gone. | `prefillBands` (`:19074-19082`): headroom away from 0 is 0. |
| SF11 | minor | Set thresholds' Peak column shows the *best* value for lower metrics while the bands come from the hidden low. "Run + 20%" on a lower metric reads as above the run: the direction is right, but the label is wrong. | `report.js:19377`, `:19348`. |
| SF12 | minor | A band box holding only spaces is saved as 0 (Set thresholds and the review). An erased fail in the review keeps the seeded fail. (Latent: `addSeeds` writes `gt` whatever the direction.) | No shared band parser. |
| SF13 [U8f] | minor | Set thresholds judges with `evaluateKpis` directly. A no-data row reads INVALID, with a tooltip claiming the file "did not declare this server assert". After Add, the board calls the same assert NEEDS RE-COLLECTION: two derivations of one assert. The step and the review also never show the `KpiUsability` warnings the editor shows. | `rowVerdict` (`:19110-19118`). |
| SF14 [B5] | minor | Validate query and the picker count the 20 drawn instances, not the true 21. | `report.js:17602`, `:19022` read `series.instances.length` where `instanceCount` exists. |

**Pin trap (verified):** `runOf` caches per component and metric, and the cache is filled at first
render by the toolbar's Generate count. A spec that mutates the series after load is therefore
invisible to the picker. Mutate before first render (an init script), or add fixture data (Q9).

### Batch 3 — numbers and units

> **As built (3.1.33).** Java (SF16): `ServerKpi.verdictUnit()` — the declared unit, `/min` added
> for a `slope` (`/min` alone with no unit, the catalogue's deadlocks spelling), then ` ÷ <rule>` for
> a `divideBy` — is used by JUnit's message, `criteria:` and `actual:`, and by both `KpiUsability`
> sentences that quote a band; JUnit's testcase body gains `divideBy:`. The JSON's `unit` stays
> declared (F5). The `SF16:` vectors first, red in Java and node before the fix. R06 did not move
> (G4's one ratio passes with an empty testcase; no golden has a slope). Page: `verdictUnit` gained
> the slope arm and reads a declared assert's `reducer` and a baked entry's wire `reduce` alike, so
> it serves the board, the Summary card (SF16's divideBy leftover), the picker's facts, Set
> thresholds (`metricVerdictUnit`), the Generate review, the held-against line and a new unit
> beside the editor's band boxes, kept live as the unit, reducer or divisor change; the mirror's
> two band sentences quote it, in Java's words. The Per instance panel has its own frame
> (`instanceFrame`): verdict-unit axis, bands on a slope, a slope ratio's bars divided. That is
> SF36's slope half. SF15: `formatValue` is two decimals in every unit, and the never-zero guard
> covers every branch; `describeBand` writes a band through `formatBand`, its shortest digits,
> grouped, never rounded. SF17: every value is grouped, not exact integers only, so the live slope
> reads `60,000,000` beside `fail > 50,000,000`; the picker's Suggested line is written as the
> board writes a band, in the unit of the line above it; both server charts tick with `axisTick`
> (SI prefix from a million) and the Per instance axis hides overlapping ticks. Left: the server
> reason's `100000 /s` is Java's client-rule sentence, mirrored verbatim (U8's family, Batch 4);
> the per-instance `wafail` band labels and the clipped axis *name* are SF35 — and since the Per
> instance axis is named in the verdict unit, `B/min ÷ load:throughput` is SF35's main clipped
> case. Pinned in `server-format.test.js` (new) and `server-numbers.spec.ts` (new); three
> picker-spec Suggested strings moved deliberately.
>
> **Review follow-ups (still 3.1.33).** The commit title's "one throughput formatter" was not yet
> true: the first pass left the client card's `24.8 /s` beside the Thymeleaf `24.76/s`, though V2's
> fixAssessment asked for exactly that fix. Now `WIR.asserts.formatValue`'s `/s` is two decimals and
> grouped, the Throughput card's `#,##0.##` (`value-formatting.spec.ts`'s `2000 /s` moved to
> `2,000 /s` deliberately). **Decision (deviation 4, both tabs):** digits are en-US in every reader's
> locale — `,` groups, `.` is the decimal point — as FormatUtil writes the Thymeleaf cards
> (US-fixed), as the band boxes and Java's sentences are written, on the client formatter and the
> server's alike. The first pass took the reader's locale for decimals as well as grouping, so a
> German board read `fail > 3,6 s` beside a band box holding `3.6`; the client's old locale grouping
> wrote ten thousand as `10.000` one cell from `2.95`. Server milliseconds are two decimals like
> every unit (a server ms is a custom query's number, not a response time: `2.95 ms` read `3 ms`
> beside `fail > 3 ms`). An Actual beside its bands goes through `formatActual`, which adds digits
> only when two decimals would put it on a band it did not reach, or across one (`3.004 s`, not
> `3 s`, beside `breached > 3 s`). An axis picks its SI prefix once (`axisTicker`), so one axis no
> longer mixes `500,000` and `1M`, and P and E follow T. The slope note names the verdict unit,
> divisor included; the picker's no-band branch writes its dropped band as the board does. JUnit's
> `formatValue` keeps a small actual off zero (`max 0.004 s`, not `0.00 s`). SF36's NOT_JUDGED
> muting is done here rather than in Batch 6, because drawing a slope's bands had widened it to
> every NOT_JUDGED slope row. Deferred: the Per instance axis name overrun (SF35, Batch 6); JUnit's
> `%.2f` actual beside a band (`3.00 s breached > 3 s`), logged on the SF15 row.

| # | Sev | What is wrong | Cause / fix |
|---|---|---|---|
| SF15 | **major** | Server values and bands in `s` (and `ms`) are rounded to whole units on verdict surfaces. Warn 0.4 s / fail 0.6 s reads "warn > 0 s · fail > 1 s"; 3.6 and 4.3 both read "4 s"; an actual of 2.95 reads "3 s vs > 3 s". | `WIR.serverKpis.formatValue` (`report.js:13464`): the integer branch for `s`/`ms` bypasses the never-zero guard. Fix: Java's digits (`Predicate.trim` for bands), the board's grouping (pinned at `server-tab.spec.ts:437`), and never round a band. |
| SF16 [B9 + F5 leftovers] | minor | A slope verdict never says "/min": board, Summary card, Per-instance axis, Set thresholds, review, JUnit. A divideBy verdict loses its divisor on the Summary card and in JUnit, and slope + divideBy loses both. | `verdictUnit` (`:14653`) knows divideBy only. Route every verdict number through it, add `ServerKpi.verdictUnit()` for JUnit and the `KpiUsability` sentence (mirror in Java's words), and keep the declared `unit` in the JSON (F5's precedent). Show the verdict unit beside the editor's band inputs, for every assert. |
| SF17 | cosmetic | One throughput reads 24.76/s and 24.8 /s on one screen. The picker's detail pane uses two formatters. Large values: clipped y-labels, overprinted per-instance labels, different grouping in Actual and Criteria. | `WIR.asserts.formatValue` `/s` branch (`:7831`); the picker's raw `Suggested` strings. |

### Batch 4 — findings, words, names

> **As built (3.1.34).** Java first, vectors first. SF20: U8b's negative-only warning ends "a band is
> written in the unit the values are judged in" (server only), `"0s"` holds a level for no time at
> all (U8c), the whole-step refusal names "the collection's 15s steps" and `wir-collect --step` (U8d);
> U8a stays. SF21: `KpiUsability` skips "no query" beside an undeclared component, and the mapper
> stamps `ServerKpi.declarationLabel()` so a refusal is said once. SF22: `NO_GATE_LABEL` "(no
> component)" on the chip, console, log and the JSON `label`; the id `(no gate)` stays on the wire;
> `verdictLine` walks `reportedGates()`; `chartData.serverVerdict` ships the fold's word. SF23:
> SOURCE_FAILED "could not be collected". SF25: "of the collected window" in JUnit and the console,
> `component/query` lines, `ascii()` escapes control characters. R06 moved only the `coverage:` lines
> of G4/G5/G7.xml. B3 left as logged (SF17 row). The page: the mirror in Java's words; SF18's live
> findings list in Java's phases and ruleIds (`documentFindings`), headed and scoped on
> `countOf('server')`, and the Asserts tab's findings marked per rule, never by text; SF19 a chosen
> tolerance is always written; U8e/h/i and SWEEP-9 (d) page copy; "Generate from this run". Step 3:
> the Summary card prints `serverVerdict` and counts the reserved entry — `GATE_RANK` is gone; one
> naming rule, `assertNaming` (Q11), on the board, the Summary card, the editor's title and the Delete
> toast — a catalogue assert by its label with its id as a dim line, a custom one by its id, a
> component by its description; the card badge is the verdict (its aria-label names the component);
> the Metric select is labels in category groups, Custom query… last, with `metric: <id>` under it,
> and no select at all on a component with no catalogue; the Query box is a soft-wrapping textarea
> that refuses Enter; the review's query cell breaks anywhere and its aria-label is cut at 60
> characters. SF24: Instances follows Coverage. SF27: an empty Id derives Java's id. No
> committed fixture carries a long query, so the editor and review pins set one in the page.
>
> **Review follow-ups (same version).** SF18: the block's environments, defaultEnv and coverage floor
> are read again (`mapperFrameFindings`, vectors first: two document cases with a `block`, now run
> with their `findings` by all three runners, `reasonPhrases` too); only the ramp-up and payload
> findings stay baked, plus the environments' own while the draft holds the environments the build
> read (the page sees a credential var masked: `bob:pw@host` ships as `***@host`, which the exact
> mirror no longer refuses), and the *as generated* mark names `serverMetrics.collection`. A capped row is
> asked every arm before its points (`verdictBeforePoints` — refused, load rule, collection status,
> whole steps), so an edited capped assert the step cannot hold reads INVALID, not NOT RE-JUDGED; a
> NEEDS RE-COLLECTION row adds to the pane what no collection answers (`seriesFree`), never "no
> series was collected". This refines V5's "capped rows keep their baked finding; stale rows add
> nothing": a capped row keeps the build's finding by deciding the same exact arms live. Asserts
> tab: an ERROR is fixed only when the rule is usable (disabled is not — the validator checks it),
> refused-for-another-reason, another unmatched target and a detected rename are *edited* and
> counted, and a settled list over a draft with broken or unmatched rules of its own no longer says
> the draft has none. SF26: the Query box also strips line breaks from inserted text. SF27:
> `__proto__` is no longer refused — + Add component defines the key as JSON.parse would. SF22: the
> interim/refused log line reads "Server: NOT EVALUATED". README and Tutorial 13 no longer call
> SOURCE FAILED "a source that never answered".

| # | Sev | What is wrong | Fix |
|---|---|---|---|
| SF18 [B6] | minor | The findings banner is baked once: a finding the draft fixed stays, a problem the draft introduces never appears, and errors are unstyled. The Asserts tab's findings have the same defect. | Rebuild live, **in Java's phase order** (mapper → usability → ramp-up → payload → runtime) and under Java's ruleIds (label for mapper problems, `kpi.id` for usability and runtime). Take the runtime part from live INVALID_RULE board rows; capped rows keep their baked finding. Scope line keyed on `countOf('server')`, not `count()`. On the client, mark a finding per rule, never by text equality. Restyle errors like `.wir-findings`. See Q8. |
| SF19 [B7] | minor | A tolerance chosen and left empty saves as no tolerance. This is never a silent pass: it becomes the stricter plain reducer. | Write `over` whenever a tolerance is chosen, so the parser refuses it in Java's words. Removal stays explicit: Tolerance → none. Placeholders become "e.g. 30s". |
| SF20 [U8b–i] | minor / cosmetic | Server sentences in client or retired words. **U8b**: "a tolerance meant as a percentage is written 0.2, not 20"; taking that advice writes a band that almost never fires. **U8c**: "0s" called "not a duration". **U8d**: "the source's 1s steps". **U8e**: "The source or query changed" for a brand-new assert. **U8f**: see SF13. **U8g**: blank → 0. **U8h**: "Generate from sources", "declares server-metrics sources" (C8), and the interim sentence. **U8i**: "fix it with Edit document…", which is not on the Server tab. Also "these edits" on an unedited page. | U8b/c/d are mirror-of-Java: Java, the vector and the mirror move together, and `server-editor.spec.ts:362` pins U8d. The rest are page-only. U8a ("would report PASS") is deliberate rationale and stays. |
| SF21 | minor | One assert problem, several findings. A catalogue assert on an undeclared component adds a false "no query — nothing to collect" ERROR, and with a derived id the component finding appears twice. After Delete, the derived-id twin stays (an F15 regression). | `ServerGateEvaluation:230-238`: skip NO_QUERY when the metric failed to resolve because of the component; dedupe on the assert, not the ruleId. |
| SF22 | minor | The reserved entry has three names: "(no gate)" in the chip, JSON and JUnit; "(no component)" on the board; "block" on the console. The console verdict line omits it, and with zero components says nothing about the server at all. The Summary card re-folds the verdict itself, a second fold (D24); verifiers down-rated this from major, since the only reachable divergence is PASS/WARN beside a NOT EVALUATED chip. | Display "(no component)" everywhere and keep the wire id `(no gate)`. `verdictLine` reads `reportedGates()`. Ship the fold's word in chartData so the card stops folding. |
| SF23 | minor | A SOURCE FAILED row says "the source never answered" beside a PARTIAL source that did answer (chart note and JUnit). | Reword. Parity `reasonPhrases` move; no golden does (none carries SOURCE_FAILED). |
| SF24 | minor | A LOW COVERAGE row shows Instances "—" while the JSON and JUnit say 1. | — |
| SF25 | cosmetic | D28's "of the collected window" is missing from JUnit's `coverage:` line and the collector console. The collector names a custom series by its bare query, so two components print identical lines. `WirCollect.ascii()` passes tabs and newlines through. | — |
| SF26 | cosmetic | Naming: the Metric select lists raw ids, flat, with "Custom query…" first; a catalogue assert is named by its derived id on the board, Summary and editor title; the card header says the label twice; the Summary card mixes labels and ids; a custom component gets a one-option Metric select; a long custom query is an unbreakable token. Two of the proposed fixes would name the Summary line differently, so one rule is needed. | One naming rule, Q11. |
| SF27 | minor | The editor refuses an id-less assert, while the picker and Generate add id-less ones with derived ids (Java derives them, `ServerKpi.java:73-97`). A `__proto__` component id is dropped silently. | Auto id in the editor; update `server-editor.spec.ts:63-69`, whose premise is false. |

### Batch 5 — dialogs, focus, keyboard

> **As built, step 1 (SF28, 3.1.35).** Top-level helpers beside `wireDialog`, one copy for the three
> slide-overs: `captureDialogPlace`/`restoreDialogPlace` (body scroll and every `[data-wir-scroll]`
> list's, the focused control remembered as its node and its `dialogNodeKey` — id, data-wir-dlg,
> data-field, data-metric, data-seed, else onclick, with the tag — its caret, and the panel as the
> fallback, never `<body>`); both editors' identical `captureEditorPlace`/`capturePlace` copies are
> gone and call these. `patchSlideover` patches the heading's text, the body and the foot in place
> (`patchDialogChildren`: keyed match, unkeyed by position and tag, attributes and text set,
> unmatched nodes inserted or removed, `data-wir-opaque` left whole, charts under a removed node
> disposed); `stepSlideover` replaces body and foot for a step and focuses the step's first control,
> as an opening does. `renderDialog`: one panel per opening (`dialogShell` {seq, step}, `seq` from
> the module counter `dialogSeq`, reset on close), wired once; a redraw patches, a step re-steps,
> `syncDialogControls` sets every `data-wir-dlg` control from the dialog's model (a typed box no
> longer follows its attribute), and close disposes the dialog's charts. `drawFocusChart` keeps one
> instance per container and redraws only when the focused metric changes. Pinned in
> `server-dialogs.spec.ts` (new): Add component, a second opening, the picker (scroll at 1440×520),
> Set thresholds and Back, the review, and a flat ECharts registry — each red on 3.1.34's E27. Because
> the activated control is patched rather than replaced, a type card, Advanced, a category, a metric
> name and a prefill chip now keep focus (SF29's a–d and f); a removed control (a chip's ×, SF29 e)
> falls back to the panel. **Left for step 2:** SF29's opener after Add component → picker Cancel (g)
> and after Add N (h), the board and client-table focus, Save in both editors; SF30; SF31 (its
> scroll half is done here).
>
> **As built, step 2 (SF29–SF31, 3.1.35).** Top-level, beside the dialog helpers: `focusKey`
> (dialogNodeKey plus the first class, so the ▸ and the name, which both call `toggle('k1')`,
> stay apart, and a chip switched on is still the chip that was pressed) and
> `capturePageFocus`/`restorePageFocus` (the node, else its twin by key, else the control now at its
> index; nothing when focus has moved on purpose). The server board's `render()` and the client's
> `renderTable()` and `render()` wrap their rebuild in them. Openers are remembered as {node, tag,
> key} and `returnDialogFocus(name, preferred)` re-finds a replaced one. Save closes onto the saved
> row's Edit (server: by key, after the commit; client: `render()` first, then `closeEditorOnto` the
> row found by the saved id). + Add component hands the new card's "+ metric" to the picker, the
> custom editor and `pickCustom` take over the opener, and Generate's Add falls back to the first
> added row. Delete (both tabs): `showUndoToast(…, {focus, back})` puts focus on Undo, ignores a
> repeated key, sends Tab/Escape to the next row's ▸ or Edit (`boardNeighbour`,
> `neighbourRuleId`), and Undo returns to the restored row's Delete. The faded toast is
> `visibility: hidden`, so a stale Undo is out of the tab order. The verify round recommended not
> auto-focusing Undo; the brief's "put focus on the toast's Undo" overrides it, and the repeat
> guard and the Tab/Escape route answer its Enter-Enter worry. SF30: `fillKeepingFocus` and
> `replaceKeepingFocus` rebuild around the focused control and never detach it (the verify's BETTER
> fix). `selectMorph` leaves options alone unless they differ, and removes only the dropped ones
> where it can. They are used by the server editor's body and foot and the client's
> `replaceSection`. The arrow reset follows the brief ("defaults on commit"), not K5's "only while
> they equal the previous metric's": `noteMetricKey` marks an arrow, Home/End or Page key, or a
> typed letter, on the closed Metric select as browsing, and the metric follows. The catalogue's
> reduce/across are applied on Enter or when the select is left (focusout, rendered after focus
> arrives) on a metric other than the one the browse began from. A list click or the `set` seam is
> a pick at once. SF31: `aria-pressed` on categories, `aria-current` on the described metric's
> name, a `--color-primary` edge on a `--color-bg-accent` fill (≥ 3.2:1 both ways, both themes),
> counts over every filter but the category, `(0)` kept, and `d.focus` follows the list. Pinned
> in `keyboard-focus.spec.ts` (new) and `server-dialogs.spec.ts`, each red on step 1's fixtures. Tutorial 13 says how the counts, the detail pane, the Metric select and Delete's
> Undo behave.

| # | Sev | What is wrong | Cause / fix |
|---|---|---|---|
| SF28 | minor (a11y) | Every `#serverDialog` dialog (Add component, picker, Set thresholds, Generate review) loses `aria-modal`, Escape and its Tab trap after the first redraw, and its slide-in replays on every keystroke. A dialog opened a second time is never wired. The picker leaks an ECharts instance per redraw (2 → 12 after ten). | `report.js:19142` replaces the panel's innerHTML; `wireDialog` runs only when `fresh`; `closeDialog` nulls `dialog`, so `seq` restarts at 1. Fix: a shell per opening plus region patches, a real seq, scroll kept, `disposeChartIn` on redraw and close. **Careful (verified):** a naive "shell once per seq" lands focus on `<body>` on pick → thresholds and Back. The U4 specs cover only the two editors, so this needs its own keyboard pins. One shared helper beside `wireDialog` can serve all three slide-overs. |
| SF29 | minor | Focus drops to `<body>` after every board action (▸, chips, Expand all, Disable, Delete). After a Delete, Undo is 86 Tabs away and the toast hides in 8 s. The same happens in the client Asserts table, on Save in both editors, and on dialog buttons. | Re-render without restoring focus; on the client, `applyEdit` focuses Edit and then `render()` replaces it. |
| SF30 | minor | Selects rebuilt on every change: multi-letter type-ahead commits the wrong option ("tr" → `rollback.ratio.pct`), and arrowing through the Metric select resets the reducer and across. | Never detach a focused select; morph its options. |
| SF31 | cosmetic | Picker state is invisible: no `aria-pressed`/`aria-current`, and the focus/selection marker is 1.04:1 in light mode and absent in dark. A click resets the scroll. Category counts ignore the other filters. | — |

### Batch 6 — layout and charts

> **As built (3.1.36).** SF36 was done in Batch 3 and not redone. SF32 (CSS): the board's table
> minimum is 940px, and from 1240px down Actual 100, Criteria 180, Coverage and Instances 84 and
> the actions 120 on two lines (V4's BETTER fix, not K5's 1140 minimum, which pushed the actions
> off the card at 1024): the Server assert column is 202px at 1024 and below, 326 at 1180, every
> row action on the card at 1024. SF33: the picker's list has a floor, 16rem, and half the window
> once the columns stack (the floor alone showed two rows at 390, where each name wraps beside its
> sparkline); Set thresholds' table and the review's sit in a `.table-wrapper`. SF34: the cards'
> wrapper is a size container and an open row's panels are `100cqw - 20px`, sticky at the left (V4
> C.css); R3-4 now bounds by the wrapper's visible edge. SF35: `layoutHandles` (with HANDLE_W/H/GAP)
> is top level and lays out the server's handles, anchor unchanged; a Value-over-time band carries
> no label where its handle names it (`show: false`, formatter kept); the Per instance labels sit at
> the top, the lower band's to the left of its line and the higher's to the right
> (`instanceLabelAligns`), with room above the plot; the y name is `nameGap 8`, left-aligned (V4's
> measured choice over `grid.top 32`); the Per instance name is under its axis, centred; the picker
> chart hides overlapping time labels, its tooltip is the row charts'; the toolbar wraps. SF37:
> top-level `bandColor(kind)` (light #dc2626/#b45309, dark #f87171/#fbbf24, colour-blind fail
> #c2410c/#fb923c, each 4.5:1 on white, #111827 and #1e293b) and `handleStyle(kind)` (the band colour
> at 0.14 and 0.9) for both tabs' handles, the server's lines, labels and wash, and the client's warn
> line — the warning token is 2.15:1 on white at full strength; the client fail line stays on
> `--color-error`. `token()` reads body. Every client label at opacity 1; warn lines at 1; the
> non-strictest fail lines stay at 0.45 (strictestFirst, by decision). A markLine label takes its
> line's opacity unless told, so both label kinds say `opacity: 1`. Dark charts are transparent
> (`createChart`, and `drawFocusChart`, which replaces its option); a dark PNG or SVG export paints
> the card it sat on. `DARK_PALETTES` swaps every palette colour under 3:1 on #111827 or #1e293b
> while dark mode is on (`chartColors()`): the verify round's four high-contrast colours, plus
> #CC0066 and #666600, which fall under 3:1 once the chart is transparent, and Okabe-Ito's blue and
> black; PALETTES and CHART_COLORS stay the reader's pick, and the dark-mode toggle rebuilds the
> filter bar so its dots stay the colours of the lines they key. SF38: `zoneLabel` and `formatTimeZoned`
> (top level; formatTimeRangeFull is untouched because the Time Range inputs parse it back) on the
> window line, the collection line and both tooltips; the collected-at time is printed once, on the
> Collection line with the raw stamp in its title, the footer keeping the id; the header's
> Generated is written in the reader's zone from `data-generated-at`, which needed one model field,
> `TestMetadata.reportGeneratedAtIso`, stamped by `LiveStatisticsEngine` from the same instant as the
> formatted text. The JSON's `generatedAt` is that text, unchanged; R06 did not move. Pinned in
> `server-layout.spec.ts` (new), `asserts-charts.spec.ts`, `server-tab.spec.ts`,
> `header-global.spec.ts`, `js-unit/chart-style.test.js` (new) and two Java tests, each red on
> 3.1.35. **Left:** the Asserts tab's rule labels under F11's handles (U10a's client half); the
> handle there does not say what the label does. Logged on the SF35 row.

| # | Sev | What is wrong | Cause / fix |
|---|---|---|---|
| SF32 | minor | Below about 1180 px the board's "Server assert" column collapses (40 px at 1024, 0 at 900), so ids wrap a letter per line. The F9 spec stays green. | 4a's fixed table gives the name `<col>` no width (`report.css:3726-3736`). Give it a floor and raise the table's min-width; pin a minimum name-column width. |
| SF33 | minor | Below 1000 px wide, and at 900×900, the picker's list shrinks to 8 px and shows 0 rows. At 390 px the thresholds table is 645 px wide in a 359 px panel. | `report.css:3974-4018`: the list is the only grid row with a zero minimum. |
| SF34 | minor | At 900 px and below, an open row's charts are 880 px wide in a 684 px card, so handles, labels and actions sit off-screen. The R3-4 spec stays green because it bounds by the chart's width, not the wrapper's. | — |
| SF35 [U10a, …] | cosmetic | Band labels sit under the handles. A handle at the axis minimum hangs half below the plot (the server never got F11's layout). Per-instance labels overprint ("wafail"). The y-axis name is clipped at the top, and the Per-instance x-axis name runs off the canvas (a divideBy unit is almost entirely cut). The picker chart's time labels are crushed. The 390 px toolbar cannot wrap. | Hoist `layoutHandles` to top level and use it on the server; hide a band's label when its handle shows it. A 390 pin must use an absolute bound: every tab already scrolls at 390. |
| SF36 | minor | A NOT_JUDGED row's Per-instance panel draws its limits at full strength, which reads as a breach (F4). A slope's Per-instance bars draw no bands and label the raw unit; slope + divideBy bars are not divided. | `initInstanceChartFor` (`:15393`) never reads the row's verdict. |
| SF37 [U10b] | minor | Dark mode: handle borders, band labels and the client rule colours fall below contrast. Client `token()` reads `documentElement`, not `body`, so colour-blind mode never reaches rule lines, although the comment says it does. Four high-contrast palette colours are under 3:1 on dark. | A theme-aware stroke. Raising alpha alone leaves light warn at 2.0:1. |
| SF38 [U10d] | cosmetic | No Server-tab time carries a zone, and the collection time is printed twice. | Append `UTC±hh:mm`; keep one stamp. |
| SF39 | minor | Granularity (including auto-detect) re-buckets the server charts although D4 hides the bar. At a bucket as long as the window, the chart is one red wash claiming a 48 s breach for a 10 s spike. Two code comments contradict each other (`report.js:415-416` vs `:15135-15139`). | Decision, Q5. |

### Batch 7 — design residue and the document (most need a decision)

> **As built, step 1 (SF39–SF42, SF46, SF47; 3.1.37).** SF47 (Q7, yes): `headerChip()` returns
> `Server · NOT COLLECTED` (interim) or `Server · REFUSED` (a refused payload) in `badge-none`
> whenever a server assert is declared and nothing was judged, null only with none declared; its
> javadoc and the class's "No payload is not an error" now say so, and `serverStatus` stays absent.
> JUnit gets one `<testName> - serverMetrics` suite on those runs: `serverMetrics.payload` is a
> `<failure>` carrying the CI/CD JSON's ERROR finding when `fatalPayloadProblem()` holds, a
> `<skipped>` otherwise. **Deviation:** Q7 says "one `<failure>` naming the refusal"; the interim
> report has no refusal and by D11 no error, and a `<failure>` there would mark every step-1 build
> UNSTABLE in Jenkins, so its payload testcase is `<skipped>` ("Nothing collected yet: … Run
> wir-collect …"). NF4's interim CONFIG ERROR still reaches JUnit: one `<failure>` per assert the file
> makes unrunnable (`ServerGateEvaluation.unrunnableResults()`, the set `blockConfigErrors` counts,
> in declaration order, each with step 3's message and body). The CI/CD JSON did not move; R06 moved
> G8.xml only, by that suite. SF46: `exporter` in `postgres.json`/`kafka.json` (required by
> `Catalogue.readType`, `CatalogueType.exporter()`), named on both type-card renderers as "Needs …";
> the `CatalogueType` javadoc no longer describes a drift check; the Collection drops Type and URL
> (the summary line too) — they are the Source cell's tooltip — and keeps the step and clock offset;
> `filterSource` and `filters.source` are gone; the reduce line is skipped at `kpiRow` when
> `!k.reducer` (`describeReducer` untouched, V6). SF39 + SF42 (Q5 b): Value over time has its own
> select (`granularitySelectHtml`, `granularityFor`, `setGranularity`; collection step by default,
> rungs of the report's ladder above the step and shorter than the judged window, one choice for
> every open row, bound through the module's delegated listener, the select in hand never rebuilt);
> `initChartFor` never reads `chartGranularity`; the D4 comment and the initChartFor comment agree.
> SF40: the chips sit in `#serverFilterChips`, redrawn on every keystroke. SF41: "FAIL 1", `All 12`,
> `aria-pressed`; no "Not judged ▾". Pinned in `server-board.spec.ts`, `server-picker.spec.ts`,
> `JunitXmlWriterTest`, `ServerGateEvaluationTest`, `CatalogueTest` and `ServerMetricsGenerationTest`.
> **Left for step 2:** SF43, SF44, SF45.
>
> **As built, step 2 (SF43–SF45; 3.1.37).** Page only; no Java moved, and R06 moved nothing beyond
> step 1's G8.xml. SF43 (Q6): the editor reads Component, Metric/Query, the validate line, Warn,
> Fail, **If saved**, Reduce, Tolerance, Across, Unit, Requires load, Divide by, Description, Id —
> Fail's bottom is inside the body at 1440×900 on E27 — and no skeleton. *If saved* is
> `editorVerdict`: `rowsFor([copy])` under the edited row's own key, file entry and base (a new
> assert gets `draft:editor`), then `rowDisplay` and `rowReason` (V5's correction), so an unedited
> capped series shows the build's verdict and an edited one NOT RE-JUDGED; refreshed by
> `refreshEditorLive`; a form Save refuses gets no chip. SF44: the card's ⋯ is a disclosure
> (`aria-expanded`, two buttons in the card's flow — the card clips — Escape and an outside click
> close it), only on a component the document declares. **Delete component cascades** to every
> assert naming it, chosen over refusing, with the count on the button and an Undo; Edit
> description / match is the component dialog in edit mode (id and type kept: Edit document…). Both
> write through `WIR.document.replace`, and their Undo replaces the captured document
> (`restoreDocument`): capture/reinstate could not, since a record from an unedited page has no
> base and the slice refuses a snapshot whose frame moved. Two consequences found by the pins:
> `rowsFor` now calls a shipped row stale when its **frame** moved (`frameMoved`, judgedFrameOf) and
> nothing collected answers it — a new `match` read Java's "no series was collected" INVALID where an
> edited assert reads NEEDS RE-COLLECTION; and a card the build judged whose component is gone keeps
> its badge (SWEEP-9) but no + metric or ⋯, and says so. The toolbar's **Edit document…** is
> `WIR.asserts.openDocument()`; the editor's host moves into the active pane, and the doc editor got
> U4's contract (`wireDialog`, opener back). U8i points at "this tab's toolbar". Cancel (or Escape)
> on the picker — or the custom editor — that + Add component opened shows an unfocused Undo toast
> (`freshComponent`, `offerUndoFreshComponent`), so SF29's focus on the new card's + metric holds.
> SF45: `watchNotes` calls `WIR.serverKpis.refreshPill` (the pill sits in `#serverUnsavedSlot`);
> `unownedChanges` reads `DOCUMENT_DEFAULTS` (`withDefault`); a reload fills
> `lastRestore` — `restoredCount(name)` for the Server tab's *Draft restored* line with the
> Asserts tab's Discard, and `dropped()` from a new slice method `held` (`snapshotChanges`: what a
> stale snapshot held against this page's baseline, 0 for a client-only record) for
> `#serverDroppedDrafts` and `#assertsDroppedDrafts`, closed together by Dismiss for the page. The
> stored record is left alone, so the interim report still restores its draft. Pinned in
> `server-editor`, `server-components`, `server-picker`, `server-drafts` and `xss-escaping`, each
> red on c5f621f's report.js.
>
> **As built, review follow-ups (Batches 5–7; 3.1.37, no bump).** Three reviewers over
> 8db6f21..66b6c9a; every finding applied, none rejected or deferred. Amends three notes above:
> SF31's metric row is marked by its `--color-primary` ring alone (the accent fill took its 12px
> secondary text to 4.26/4.04:1; the category keeps its fill); SF37's colour-blind warn is fuchsia
> (#86198f / #f0abfc) — amber beside the darkened orange fail was ΔE 0.7 under deuteranopia — and
> colour-blind dark mode's `--color-success` is #60a5fa (the run-aggregate line was 2.83:1); SF45's
> "the stored record is left alone" was true only until the next persist, which rebuilt the record
> from this page's slices and wrote its unedited server block over the interim's — now
> `WIR.document` holds a stale snapshot that held edits (`heldStale`, with its half of the stored
> base) and writes it back until a server edit on this page replaces it, `dropped()` reads what is
> held, and the notice says "still saved for that report … the first server-assert edit on this
> page replaces it". SF29: `swallowHeldKey` after a keyboard Undo; `returnDialogFocus(name,
> preferred, fallback)` and both Saves fall back to Clear filters (with a toast) when the filters
> hide the saved row; both Delete fallbacks end on + Add your first assert / + Add component. SF30:
> the server foot's Cancel has an id (`serverEditorCancel`); the Asserts table is rebuilt with
> `fillKeepingFocus` (its thead sort listener bound once; `selectMorph` gives a kept button its new
> label). SF35/SF34: a row's time axis hides overlapping labels. SF37 also: the dark-mode colour
> picker marks the palette slot's swatch, and picking it keeps the slot. SF41: the component badge
> carries `aria-pressed`. SF42: rungs spelled "1min" as the client's (`granularityLabel`). SF43:
> "judged live" only beside a verdict. SF44: the empty-component toast is owned and taken back once
> an assert names the component (`hideUndoToast`); Edit document…'s textarea is listener-bound.
> SF46/SF47 (Java): JUnit drops the reduce line for an unparsed reducer (R06 moved G4.xml by that
> line only, re-accepted), the interim suite lists the asserts on a component of unknown type
> (`unrunnableResultsWithUnknownTypes`), the console and log say NOT COLLECTED / REFUSED; the chip
> carries `data-wir-chip-state` and the sidecar-less card states that state; `--color-ramp-badge-text`
> is #475569. Tooling: `run-all-tests.sh` refuses a trailing `--only`/`--suite` and an empty
> `--only`. Pins listed on each row of the 2026-10-03 EXPLORATORY-LOG entry; each red on 66b6c9a
> (or on ba97d2a / 8db6f21 for the pins of earlier, unpinned fixes).

| # | Sev | What is wrong |
|---|---|---|
| SF40 [U6 residue] | minor | Chip counts and "Clear filters" do not follow the text filter while rows still match. |
| SF41 | cosmetic | Chips read "FAIL (1)" where the Asserts tab says "FAIL 1", with no `aria-pressed`. §3's "Not judged ▾" contradicts its own "shaped like the Asserts tab", which has no ▾ (Q12). |
| SF42 | minor | An open row's two panels have no selector, although Aaron's 2026-10-03 sentence asks for "a selector like the Samplers tab's". It is the same sentence that created the two panels, so it is not superseded (Q5). |
| SF43 [SWEEP-8] | minor | The server editor is still 13 flat fields: Id first and required, no live verdict, Fail below the fold. Unbuilt design rather than a regression. Any preview must use the board's derivation (SF13's lesson) (Q6). |
| SF44 | minor | Component cards have no ⋯ menu, so a component cannot be edited or removed on the Server tab. "Edit document…", which the editor tells you to use, is not there. Cancel on the picker that "+ Add component" opened leaves an empty component that reads NOT EVALUATED next run (intended for an empty component, so ask on Cancel or offer Undo). |
| SF45 | minor | Document edges: the Server pill goes stale on a notes edit. Opening a file without `"version"` over-counts by one, and every committed server-era file lacks it. A restored server draft is announced only on the Asserts tab. Server drafts made on the interim report are dropped on the step-3 report **without a word**, while the client half is restored. The drop is intended (D12); the silence is not. |
| SF46 | cosmetic | The Collection panel shows Type and URL ("no provenance in the GUI"?). The type cards never name the exporter for Kafka. Dead code: `filterSource`. The `CatalogueType` javadoc describes a drift check that does not exist. An INVALID row prints "? across max". |
| SF47 | question | On interim and refused runs, JUnit carries no server suite, so a CONFIG ERROR there never reaches a Jenkins `junit` step. The header chip is silent on those runs (a recorded decision, `headerChip` javadoc) while the Summary card states the case (Q7). |

## 2. Pins

Every SF ends as a spec or a log line (CLAUDE.md). Each batch carries its own pins. Java and wire
changes are pinned with vectors and unit tests first, then specs. Batch 0 appends this session to
`EXPLORATORY-LOG.md` (one entry for E26/E27 and the scratch variants, with each SF's outcome), and
replaces line 42's "not automated: code about to be deleted", which the redesign has made untrue.
`SF14`, the 4c Validate sentence (a stale-fixture canary), `SF32`'s name-column width and `SF34`'s
wrapper bound fill gaps the current specs are green over.

## 3. Refuted, intended or closed — do not reopen

- **Slope (and divideBy without a divisor) have no drag handle.** Intended and pinned (`d284ac3`,
  `chartFrame` javadoc, Tutorial 13: retune it in the editor). A fit-line overlay is an optional
  extra (Q13).
- **U10c, the axis stretched to the band.** It is R3-3's decision. The proposed hybrid would break
  `asserts-charts.spec.ts:325` and push realistic asserts off-scale (E27's consumer lag sits at 27%
  of plot height, E26's lag-sustained at 15%). Recommend keeping R3-3 (Q10).
- **"Run + 20%" sits below the run for a lower metric.** That is the correct direction; only the
  label misleads (SF11).
- **NOT EVALUATED for a component with no asserts.** Intended (`ServerGateEvaluation:278-279`).
- **The chip naming the first component on a tie.** Documented (`worstBuildingGate`).
- **B7 is not a silent pass:** an erased tolerance is stricter.
- **U8a's "would report PASS"** is deliberate rationale, the same sentence CLAUDE.md uses.
- **U8k** was fixed in 4c, and the fixtures now show it.
- **"Failing cards open by default"** is superseded by "no row opens by itself" (Aaron,
  2026-10-03). **"Monitoring level" in the picker** belongs to the MSK large-catalogue work.
  **§5a**, keeping the catalogue current, was never scheduled; only the javadoc residue is fixed
  (SF46).
- **Paths that work:** server metrics with external data (server series count toward the
  threshold, and a foreign sidecar is refused), the listener's interim report (identical to `-g
  -o`'s), and a comparison report beside the Server tab, apart from SF3 and SF45. Escaping in every
  new dialog held, and no console errors appeared in any session. Inline and sidecar render
  identically for every file this session opened, but **not** for a `"__proto__"` key: the sidecar
  is a JS object literal, which turns that key into a prototype. That is N2, open, fixed after the
  merge (§6). Until the 2026-10-05 review this line said "inline and sidecar render identically"
  with no exception.

## 4. Order and size

| Batch | Scope | Size | Touches | Gate |
|---|---|---|---|---|
| 0 | I1, I2, the log entry | S | scripts, `global-setup.ts` | the selftests, S03 |
| 1 | Q9's E27 data, SF1–SF7 | M | Java + vectors + mirror; one page guard; E27 inputs | `mvn test`, parity in all three runners, R06 (unmoved) |
| 2 | SF8–SF14 | M | page; `judgedActual` in Java (chart data only) | server-picker/seeding/editor specs, js-unit |
| 3 | SF15–SF17 | M | page; `ServerKpi.verdictUnit()` | server specs, JUnit tests |
| 4 | SF18–SF27 | M–L | page; small Java wording and finding fixes + vectors | parity, server specs, R06 |
| 5 | SF28–SF31 | M | page | new keyboard pins, `client-fixes` U4 |
| 6 | SF32–SF38 | M | CSS + charts | width pins, chart specs |
| 7 | SF39–SF47 | M–L | page; JUnit for SF47 | server specs, R06 (G8 re-accepted) |

Each batch is one commit with a patch bump, deployed, tested on what it touched. Regression and the
config tier run **once**, after the last batch. Batch 2 needs SF2 merged first. Batches 3–7 are
independent of each other.

## 5. Decisions for Aaron

**Need your answer:**

- **Q1 — Where this lands.** Recommend: `feat/server-components` is reviewed, pushed and merged as
  it is (its evidence is now clean, §0a), and all of this goes on one new branch,
  `fix/server-tab-followups`, cut from it, starting with Batch 0. The alternative is to put Batch 0
  on `feat/server-components` before its push.
- **Q3 — `across: min` (SF2).** Pull it forward from the v2 backlog for catalogue metrics, declared
  per metric in the catalogue file? Recommend yes. It is a judging change: vectors first, and
  `up`/`cache.hit.pct` verdicts change on multi-instance components.
- **Q4 — The catalogue on every report (SF5).** This gives up the property that a run with no
  server block is byte-identical in chart data. Recommend yes, as the client's vocabulary already
  did.
- **Q5 — Granularity and the panel selector (SF39, SF42).**
  (a) Server charts always draw at the collection step.
  (b) "Value over time" gets its own granularity selector, which would also be the "selector like
  the Samplers tab's".
  (c) Keep the coupling and print the bucket in the note.
  Recommend (b).
- **Q6 — The editor (SF43).** Is the TARGET / METRIC / CRITERIA / PREVIEW / DETAILS skeleton still
  wanted, now that the picker and Set thresholds cover catalogue authoring? Recommend auto id + a
  live verdict now (SF27), and the skeleton only if you still want it.
  **Answered 2026-10-04: on hold.** Aaron: "way too much over-engineered". The idea is simple. After
  the JMeter run, wait for the server metrics, pull them from wherever they live (a CSV, Prometheus
  or anything else) into one agreed file, and let the report read that. The auto id and the live
  verdict stay; the skeleton is not built.
- **Q7 — Interim and refused runs on the wire (SF47).** Give JUnit a server suite with one
  `<failure>` naming the refusal, and let the chip say NOT COLLECTED / REFUSED? Recommend yes to
  both. G8's `.xml` is re-accepted with the change.
- **Q9 — E27 data.** Add the slope metric (`database.size.growth`) and an `up` dip to 0 on one
  instance to `e27-metrics.csv`, so SF2, SF9, SF10 and SF16 are pinned on real payload data? It
  regenerates every fixture through the stamp, and E27's counts move. Recommend yes. A capped
  catalogue series stays an in-page mutation made before first render.
- **Q10 — U10c.** Keep R3-3 (close it as intended)? Recommend yes.

**Defaults I'll take unless you say otherwise:**

- Q2: SF1 trims silently, plus a WARNING finding (findings do not gate) for a query padded with
  Unicode space.
- Q8: SF18 is one live findings list per tab, headed by which document it describes.
- Q11 naming:
  - Catalogue asserts are label-first on display, with the assert id as a dim second line. The id
    is the JUnit testcase name, the thing grepped in a CI log.
  - The wire keeps ids. The reserved entry reads "(no component)" everywhere on display.
  - "Generate from sources" becomes "Generate from this run".
  - The Metric select shows labels grouped by category, with "Custom query…" last.
- Q12: Server chips adopt "FAIL 1" and `aria-pressed`. No "Not judged ▾" on either tab.
- Q13: No slope handle; bands on the Per-instance panel only. The fit line only if you want it.
- U8 wording: server sentences diverge from the client's only for U8b; the rest are page-only fixes.
- SF7: fix the docs, not the programs.
- SF15: Java's digits, the board's grouping, never a rounded band.
- SF16: `unit` stays the series' unit, "/min" is appended wherever a verdict number shows, and the
  JSON is unchanged.
- SF19: refuse an empty tolerance.
- SF37: dark-mode charts get a transparent background, and a NOT_JUDGED row's label stays legible
  while its line stays muted.
- SF38: times carry `UTC±hh:mm`. The header's "Generated" follows the reader's zone from an ISO
  attribute, and the JSON's `generatedAt` is untouched.
- SF44: the card ⋯ menu gets "Delete component" (with Undo) and "Edit description / match", and the
  Server toolbar gets "Edit document…".
- SF46: name the exporter on the type cards (a prerequisite, not provenance); the Collection panel
  drops Type and URL from its default view and keeps the step and clock offset.

**Taken while building, without Aaron (2026-10-04). Each is easy to reverse:**

- Digits are en-US in every locale, on both tabs (Batch 3 follow-ups). A de-DE reader's client
  integers move from `10.000` to `10,000`. Nothing moves in an English browser.
- The client `/s` formatter writes two decimals, grouped, matching the Thymeleaf cards' `24.76/s`
  (Batch 3 follow-ups). `value-formatting.spec.ts` moved `2000 /s` to `2,000 /s`.
- SF47 on interim runs: the server suite's payload testcase is `<skipped>` ("Nothing collected
  yet"), not a `<failure>`, because by D11 no payload is not an error, and a `<failure>` would
  mark every step-1 Jenkins build UNSTABLE. Refused runs follow Q7: a `<failure>` when the payload
  was needed.
- SF44: Delete component cascades to its asserts, with one Undo, rather than refusing.
- The colour-blind warn colour is fuchsia (`#86198f` light, `#f0abfc` dark); §5 never chose a hue
  (review follow-ups, `5874e10`).
- Not done, logged: the client half of U10a (Asserts-tab rule labels under handles); the needs-load
  reason row on the board quotes Java's client sentence with an ungrouped `100000 /s` (the Summary
  card groups it); JUnit writes `3.00 s breached > 3 s` for a 3.004 s FAIL.
- **F9 (review fixes B, 3.1.38, 2026-10-06): Discard drafts keeps what the record holds for another
  collection.** This reverses an implementer choice made while building `5874e10`, not one of yours:
  Discard removed the stored record whole, so the interim report's server draft went with a
  discarded note while the notice beside the button said it was still saved for that report.
  `WIR.document.discard()` now drops this page's slices, keeps `heldStale` and the reader's Dismiss,
  and calls `persist()`, which writes the held snapshot back or removes the record when nothing is
  held. The notice adds "Discard drafts and Dismiss leave it saved.", and the toast's count is exact.
  If a way to clear those edits from the step-3 page is wanted, it would be its own counted button
  on the notice, never a side effect of Discard.
- **F5 (review fixes B, 3.1.38, 2026-10-06): SF44's owner-toast mechanism is retired** for one
  general guard. SF44 took the empty-component toast back only when an assert named that component
  (`owner`, `hideUndoToast`, `componentHasAssert`, the loop in `commit()`); every other edit made
  under that toast, and under the other four (client Delete, server Delete, Discard drafts, Delete
  component), was written over by its Undo. `WIR.document` now keeps a `revision` that every write
  bumps; `showUndoToast` stamps it, the toast is withdrawn the moment it moves (focus to the toast's
  `back()`), and a click after a move writes nothing. Rejected: a targeted inverse Undo that
  survives unrelated edits (L-sized, and it reopens the frame problems SF44 avoided).
- **N1's applier contract reaches the server slice too (review fixes B, 3.1.38, 2026-10-06).** The
  triage left the server applier as it was; the batch's review showed `adoptBlock` keeping the rows
  and seeds the last render cached, so a chart toggle straight after a `replace()` drew the removed
  assert again. `adoptBlock` drops both now, which is what `replace()`'s javadoc already promised.
  N5's markers pair rules sharing an id by occurrence (`loadedEntryFor`'s rule): keyed by id alone,
  an Edit document… that retuned another rule drew the first of two id-less `a-p95` rules EDITED.

## 6. The external review round (2026-10-05)

An external code review of this branch at `a237ad7` (3.1.37) raised ten findings, F1-F10, and a
few loose items: C1(a) and (b), R1, R2 and U1-U3. C1(b) and R1 are one item: which id an id-less
assert's findings are filed under. It was triaged into `tmp/server-tab-review-triage.md`
(gitignored). Each finding went to one agent that tried to refute it and one that designed the
fix, and a judge settled between them. The judges reproduced every finding, on copies of E25-E27
in headless Chromium or through the js-unit harness. A critic applied the B batch together and ran
146 Playwright tests over it. A second round adversarially verified five defects the review had
missed, N1-N5. The evidence is in that session's scratchpad.

The triage found every finding real. It differed on scope, on severity (F5 and F9 are data loss,
so they block), and on most of the fixes. Merge-blocking: F1, F2, F3, F5, F9 and N1; F10 rode with
F3.

**Decisions.** On 2026-10-05 Aaron accepted the triage in full and left its §6 questions to its own
recommendations. As taken:
- Q1: Batches A-G all land on this branch, before the merge, with one final gate.
- Q2: F2(b), a `BAND_ABOVE_CEILING` warning for a hand-written band, is not built now. It comes
  after the merge.
- Q3: N4 is built here as Batch G, with N3's per-match `threshold` echo, in one commit named as a
  contract fix. An actual below 5e-5 keeps printing `0.0` in the JSON; since F7 the page shows its
  real digits.
- Q4: N2 is fixed after the merge, with the computed-key `FilterWriter`. Its two records are
  corrected now: SF27's log line and §3's "inline and sidecar render identically". The log's
  paths-that-work row made the same claim and is corrected with them.
- Q5: N3's page half and the JSON's option (c) are not built now. They are the first branch after
  the merge.
- Q6: Q2 stands. Invisible padding stays a WARNING that does not gate; F8 only widens the class.
- Q7: N5 folds into Batch B.

**Batches.** One commit each, in the triage's order, built overnight from 2026-10-05 into
2026-10-06 (the commits are dated 00:20-04:30, +0300). Each commit message records what was wrong
(with the pins that were red), what changed, the deviations from the triage, and the review's
findings, taken or not.

| Batch | Commit | Version | Items | The triage's verdict |
|---|---|---|---|---|
| A | `6c9e625` | 3.1.37 (no `src/main`) | F3, F10 | F3 was wider than stated: a regeneration that rendered nothing was stamped current, and the specs then passed on the previous build's report. F10 accepted. As built: under I2 in §0a. |
| B | `a376cdd` | 3.1.38 | F9, F1, F5, N1, N5 | F1's fix changed: the page's own record and the held map are captured apart. F5 is wider (all five Undo toasts) and blocks. F9: fix Discard, not the words. N1 (new, blocking): every Server-tab document write left every client row NOT EVALUATED. N5 (new) folded in. Three implementer choices are in §5. |
| C | `52eeffd` | 3.1.39 | F2 (a) | Fix changed: a catalogue `ceiling` (named so it is not read as `reduce: max`), headroom capped by the room under it, and checked after rounding. |
| D | `2b042ed` | 3.1.40 | F4, U1, U2, U3 | F4's trigger is narrower and it is never a wrong verdict. U1 is latent. U2 cannot be reached by hand. U3 is real. |
| E | `5115102` | 3.1.41 | F7, C1(a), F6 | F7 is worse than stated: a rounded actual could land on the passing side of a band it FAILed. C1(a) is reachable: actuals of 1e9 and up are common for bytes, though landing within 0.005 of a band takes a hand-typed one. F6 is wider, with two causes. |
| F | `0960a9f` | 3.1.42 | F8, C1(b) | F8 is a class, not six code points: one range table both sides hold, read by code point. C1(b) (R1) is intended but was unpinned, so a document case pins it. |
| G | `b0e3e08` | 3.1.43 | N4, N3's `threshold` echo | N4 (new): `round4` clamped every server actual from 9.22e14 up (`round2` from 9.22e16). A contract fix, and no golden moved. |

R2 (the fail-line label's contrast) is intended, so nothing changed.

The final gate is the triage's §4: the full regression with `--force-fixtures`, the config tier
(C38), and then reportExample and the screenshots as their own commit. It had not run when this
section was written. Each batch ran only its targeted tests, and its commit lists them.

**Taken while building, without Aaron (Batches C-G).** These are deviations from the triage that
are decisions, and each is easy to reverse. Batch B's three are at the end of §5.
- C: a band at 0 stands at a run of 0 in both directions, as SF10's slope pin already had it.
  `"ceiling": null` means no ceiling. CLAUDE.md says "an upward one" because `up`'s safe `fail < 1`
  sits exactly on its ceiling of 1, and that is correct.
- D: F4's residual is wider than the triage's one sentence. When one apply changes both of two
  same-id asserts on one component (both retuned and reordered, or one moved while the other is
  retuned), they can swap rows. The cost is a label, a withheld verdict or a count, never a wrong
  verdict, and the same edits made one apply at a time pair correctly. U1's optional extra is not
  built, so a THEAD or TR container takes the plain replace.
- E: C1(a)'s digit cap is 17, not 15, so a terabyte FAIL at 1e12 + 0.004 reads apart from its
  band. The cost: from about 8.8e12 a double's step is itself at the third decimal, so one step of
  float noise can show as ".002".
- F: the class keeps the variation selectors and tag characters. A query ending in an emoji
  presentation selector (U+FE0F), or in a flag's U+E007F, therefore gets the file-only WARNING even
  when its series comes back. Validate query and `wir-collect` no longer state the padding sentence
  for a series that returned points. The editor and the JSON's finding cannot see the data, and
  that is accepted under Q6.
- G: a finite per-match `threshold` echoes the declared constant. An infinite one (a bare
  `lt: 1e400`) keeps round2's finite number, so the field never becomes the quoted "Infinity".

**Deferred to after the merge, by the decisions above:**
- **N2.** The external-data sidecar reads a `"__proto__":` key as the prototype. On a sidecar copy
  of E26 with `db` renamed `__proto__`, all 8 of its asserts read INVALID ("not declared"), and
  Download writes them without their component, so the next run is a CONFIG ERROR. The inline page
  is correct. The fix: one `FilterWriter`, which both writers go through
  (`WebInsightReportGenerator` and `ReportDataSerializer`'s streaming path), rewrites the key token
  into the computed key `["__proto__"]:`. Every payload without the key stays byte-identical.
- **N3's page half (M).** Client actuals and bands are rounded onto their own band ("667 ms vs >
  667 ms" on a FAIL). The fix: a shared `WIR.digits`, a client `formatActual`, and
  `GatePayloadBuilder` shipping the unrounded match actual. With it comes the client `actual` in the
  JSON by option (c): two decimals, unless that lands on or across the breached band, so R06 does
  not move. That also closes the item logged in §5: JUnit writes `3.00 s breached > 3 s` for a
  3.004 s FAIL.
- **F2(b).** `BAND_ABOVE_CEILING`: a server WARNING for a typed or dragged band past a catalogue
  ceiling. Vectors first, `slope` and `divideBy` exempt, and worded conditionally, since
  `connections.used.pct`'s 100 is nominal. CLAUDE.md's "nothing is claimed about upward bands" gains
  a server-side exception.
- **F6's residual.** `thin()` can still drop a deciding point above 4,000 points per instance
  (about 16.7 h at 15 s).
- **F8's mirror cases.** Nothing warns about invisible characters inside a component's `match`, or
  about a CSV metric cell padded the same way beside a clean query.

**Logged by the batch reviews, for the same follow-ups:**
- Batch D: a `"__proto__"` key one level down is still dropped by plain assignment: inside
  `serverMetrics` (`buildBlock`), and inside an assert after an in-page edit (`kpiToJson`, and
  `canonicalJson`'s extra copy). U3 covers top-level keys only. It goes beside N2.
- Batch G: `AssertionRuleMapper.asNumber` refuses NaN and Infinity on its String arm only. A bare
  `fail: {gt: 1e400}` loads as Infinity with no finding and PASSes every run, while the page refuses
  it (NOT_JUDGED / INVALID_RULE): a silent pass and a parity split. Vectors first, then guard the
  Number arm as `ServerMetricsMapper.asNumber` does. Batch G's `1e400` threshold case is the one to
  rewrite when it lands. It goes beside F2(b) and N2.
- Batch E: F6's other limit. Under `across: avg`, no 20 drawn instances out of N can reproduce the
  average.
- Batch B: the Asserts table's handlers are keyed by rule id, so two id-less rules that derive the
  same id (`a-p95` twice) are one row to every button: `WIR.asserts.toggleEnabled('a-p95')` disables
  both. The server board moved to per-row keys long ago (R1-2); the client table did not. Pre-existing.
  `draftCount` also still keys by id with the last rule winning, where `markDrafts` now pairs by
  occurrence; no disagreement was observed, so nothing is claimed either way.
