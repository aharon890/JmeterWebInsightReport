# Server tab on a real backend: fix tracker

**The plan** is `petstore_ms/tmp/server-eval/server-tab-findings-review-2026-10-27.md`. It is the staged plan (Stages
0–4, Q1–Q7), written after the real-backend evaluation (petstore_ms, 2026-10-24 .. 26) and verified against this
repo's code and recorded decisions. This file only tracks the WIR side.

**Split** (owner, 2026-10-27):
- **This repo's stages:** one chat.
- **petstore's Stage 0 (re-verify harness) and P1:** another chat.

**The plan's rules, followed for every step:**
- red first;
- the expected re-verify diff written before the change, and the observed one equal to it;
- one finding per commit, with a patch bump; one stage per branch;
- `mvn test` and sanity per commit, full regression before a push;
- the owner's go per stage.

Until Stage 0 exists, the diffs are checked here by rebuilding the petstore S4 variants with the dev JAR in this repo's
own JMeter (`tmp/reverify/rebuild.sh <variant>`, compared with S4's 4.1.0 report by S4's `cmp.cjs`).

**Owner decisions** (2026-10-27):
- B2: keep D24's rank and surface hidden WARNs (= Q3).
- B1: keep "a gap is not a zero" and name the gap.
- B3: keep D28's whole-window coverage and document it (= Q2).
- B6: keep STRICT as it is. **Note:** its definition ("no check the file asked for that never ran") does not match
  its NO_MATCH carve-out (N1); the doc sentence at 13:1102 overclaims.

## Stage 2: reader and messages (branch `fix/server-tab-stage2-reader`)

| Step | Item | Status |
|---|---|---|
| 2.1 | A2: an unquoted query with `,` fails by line | **Done, 4.1.1, `6c4df88`.** Red first. Pre-registered: X08 has 4 asserts NO_MATCH → SOURCE_FAILED, source OK → PARTIAL, nothing else. Observed: exactly that (31 + 100 identical); controls M01 and X08b identical. The first attempt moved only 3: a `"` before the `,` is lost in an unquoted cell, so the prefix is now compared without `"`. |
| 2.1b | N4 (optional): a quote-stripped name is said | **Done, 4.1.2, `49530a5`.** Pre-registered: X08's other 7 asserts gain a reason (NO_MATCH → SOURCE_FAILED). Observed against the 4.1.1 rebuild: exactly 7, 28 equal; M01 and X08b identical. |
| 2.2 | A1: one shift per file, "about" | **Done, 4.1.3, `5924909`.** The shift is measured over the series wholly outside the run; centred when that span is at least the run's length, else start to start. Observed: X14 → one hint "about 393s … -393s" on all 31; X14 rebuilt with `clockoffset=-393s` equals A0 (35/35, payload values identical, points 7 s later); X12, X13, X15, M01 unchanged. **Deviation:** X16's outside rows lie before *and* after the run, and the first fix told 3 one-sided series "about 7s". A second red test was added; now those 3 read "export the run's window: the file's rows lie before and after it, so no shift lines them up" (the plan's "after → before and after", in substance). E28's −3h is guarded by its own test. |
| 2.3 | N5 (D1): raw `${}` vs resolved query text | **Done, 4.1.4, `69f48a9`.** Red on 4.1.3 (EMPTY), so kept. A CSV may name an interpolating custom query as the file writes it; the resolved text is tried first. Observed: none (M01 vs 4.1.0; X08, X14 vs 4.1.3). |
| 2.4 | D5: refusal text reaches the page, paths stripped | **Done, 4.1.5, `7162b12`.** The collector's last `wir-collect:` line becomes the reason; every file it was handed is reduced to its name. Observed: W3's one finding names the reason, nothing else; no local path on the page. |
| 2.5 | E4: top-level `sustainedFor` message | **Done, 4.1.6, `a5f218b`.** Vectors first (Java 3 and node 3 red). Observed: C04 1 of 42 differs, reason only. |
| 2.6 | D6 (a, b, d): message wording | **Done, 4.1.7, `7a66da5`.** Observed: X04 31 and X07 1, reason only; **also X11** (16, the DST case, not in the registered "X04/X07" list) names both offsets. |

## Stage 1: words only (branch `fix/server-tab-stage1-words`)

Stage 2 merged first (PR #23, `684dea1`). Re-read before starting (rule 5): nothing in Stage 1 was made moot; the
docs' CSV hint descriptions (16 "Where the metrics come from", 14 "Lining up") already match 4.1.3's wording.

| Step | Items | Expected re-verify diff (written before the build) | Status |
|---|---|---|---|
| 1a | E1, E5, N1 (B6's note), E2 + Collector javadoc (N8's first half), E3 + N7, B3, B5, D2, D4, B8's sentence; plus E1's two other copies (CLAUDE.md, 11:860) | **None.** Docs and a javadoc only: M01, X08, X14 rebuilt with 4.1.8 equal the 4.1.7 rebuilds. | **Done, 4.1.8.** Observed: none. X08 and X14 have no 4.1.7 rebuild, so the four that do were compared instead: M01, X04, X07, X11, all asserts and payload series identical, and the console differs only in the temp payload's name. One pin added (`SourcesAndCollectorTest.aParkedCatalogueAssertsMetricIsStillCollectedOnceAndCostsTheSourceNothing`, green: it holds E2's new sentence). |
| 1b | E7 (3 descriptions), B1 (`up` on postgres/mysql/redis/oracle), B5 (http-service `source`), E6 (schema `dependencies`) | **No verdict or value change.** Catalogue text is not in the snapshot `cmp.cjs` compares, so M01, X08, X14 equal the 4.1.8 rebuilds; the fixture stamp moves, so the full regression regenerates every fixture. | **Done, 4.1.9.** Red first: 3 CatalogueTest pins and `AnnotationsSchemaTest.aQueryAssertMustWriteItsReduce` (its control, the mapper refusing a query assert with no reduce, passed on 4.1.8). Observed: none. M01, X04, X07, X11 equal their 4.1.8 rebuilds, and the new text reaches the page (`chartData.serverCatalogue`). No committed annotations file has a `query` assert without `reduce`. B1's caveat also went into 15's "one database that went down fails `up`" sentence. |

## Stage 3: B4, locks.waiting (branch `fix/server-tab-stage3-locks`)

**3.1, the read-only check (2026-10-27).** Petstore's Prometheus (`localhost:9092`, retention now "2w or 10GiB") restarted
on a fresh volume at 2026-10-26T23:05Z, so R3's window is gone; the check ran over the last 30 idle minutes, step 15 s,
`query_range` only:
- Today's `sum by (instance) (pg_stat_activity_count{wait_event_type="Lock",app="catalog-pg"})`: no series.
- `… or 0 * max by (instance) (pg_up{app="catalog-pg"})`: 1 series, labels `{instance}` only, 121 points, all 0. Without a
  match: 3 series (catalog, identity, i18n), the same.
- The review's `… or on (instance) 0 * pg_up{…}`: 1 series carrying app, job, namespace, pod. That is why it was rejected.
- `connections.active` and `transactions.idle.in.tx` **do not vanish** when idle: postgres_exporter writes a 0 row for
  every state and database (4 per state here). They stay as they are.

**3.2–3.3, expected re-verify diff (written before the change, 4.1.10):**
- S4's 55 CSV variants, through `cmp.cjs`: **none.** Neither S4 CSV has a `locks.waiting` row, and a CSV row is found by
  name, so `catalog-db-locks-waiting` stays NO_MATCH.
- Outside what `cmp.cjs` compares: that assert's `query` in `serverAsserts[]` takes the new text, and every
  `serverComponents[].collectionId` (and the console's `collection` id) changes, because an asserted series' resolved
  query is collectionId material whether or not it has rows.
- C06a, S4's replay payload, recorded with the old query: `catalog-db-locks-waiting` NO_MATCH → INVALID_RULE ("no
  series was collected for this assert's query"). That moves catalog-db's `notJudged` from noMatch to invalidRule and
  adds SERVER NOT JUDGED; exit 0 and every status unchanged. This is how every payload collected before 4.1.10 that
  asserts `locks.waiting` will read, and the reason asks for a re-collection. C06 (refused, another title): unchanged.
- WIR's own suites: none. No fixture or golden asserts `locks.waiting`, and E27's committed payload has no such series.
- Not covered by the plan: postgres_exporter before 0.17.0 writes no `wait_event_type` label, so on it the new query
  reads its fallback, 0, whatever waits, where it read no data before. Said in the description and `source`.

**3.2–3.4, done (4.1.10).** Red first (`CatalogueTest.theLocksWaitingReadsZeroWhileNobodyWaits`). Observed, all as
registered:
- M01 and X04 vs 4.1.9: `cmp.cjs` none. On M01, the assert's `query` is the new text and `collectionId` moved
  9138becc… → 9345a18b…; the assert stays NO_MATCH.
- C06a vs 4.1.0: 34 of 35 asserts equal. `catalog-db-locks-waiting` NO_MATCH → INVALID_RULE ("no series was collected
  for query … re-run wir-collect"), catalog-db `notJudged` noMatch 1 → invalidRule 1, SERVER NOT JUDGED on the console.
  Exit 0, serverStatus FAIL unchanged, payload series identical. C06 unchanged.
- Step 4, **substitute, not the plan's fresh fixture run.** petstore's runner `rm -rf`s `Results/jmeter/<plan>` (the
  other chat's), so no load was run. Instead: a synthetic window-only JTL over 5 idle minutes
  (`tmp/reverify/live-locks/`), `wir-collect --prometheus-url http://localhost:9092/api/v1 --no-wait` with the dev JAR
  and S4's annotations, then `jmeter -g`. Result: `catalog-db-locks-waiting` PASS, actual 0, coverage 1, 1 instance
  `{instance}`; identity-db's series reads 0 too. **15/16** postgres catalogue metrics have data on both components
  (only `replication.slot.retained.bytes`: petstore has no slots), against 14/16 before. A fresh run under load stays
  petstore's (the other chat's) to do.
- Without `--no-wait`, the collector waited (`PrometheusSource` settle loop, 10 min cap per series) on a window that had
  ended 5 min before; it was stopped. Not investigated: whether a series that never reaches the window end should wait
  that long.

## Stage 4: the page (branch `fix/server-tab-stage4-page`)

Stage 3 merged first (PR #25, `eb08dbb`).

**4.1, C5, expected re-verify diff (written before the change, 4.1.11).** Only C08 has capped series in S4 (a scan of
every archived report): app-pods `memory.limit.used.pct`, `restarts` and `restarts.oom`, 26 instances each.
- C08 `memory.limit.used.pct`: the drawn set gains tails-messages (83.30, over warn 80) and gives up the lowest
  instance; `cmp.cjs` shows that one payload series differing (instance hash), asserts 0 differ.
- C08 `restarts`, `restarts.oom`: unchanged when their values tie (ties keep index order); a nonzero instance past
  the first twenty is the deciding one and was kept already.
- Every other S4 variant: none. CI/CD JSON, JUnit and the R06 goldens: none (they read the evaluation).
- WIR fixtures: E26 `many` (21 instances) and E29 (24) may draw a different set; their specs pin counts.

**4.1, done (4.1.11).** Red first (`ServerMetricsPayloadWriterTest.theFreeSlotsGoToTheWorstInstancesNotTheFirstInCollectorOrder`:
pg-1..pg-20 drawn, pg-21 at 83.3 not). `ServerKpiEvaluator.rankedInstances` ranks by the evaluator's own reduction;
`ReportDataSerializerTest.theInstanceCapKeepsTheInstancesTheVerdictComesFrom` pinned the old fill ("the rest fill up in
collector order") and now pins the new one. The N8 javadoc (`decidingInstances`) says what the cap keeps. Observed, as
registered: C08 vs 4.1.0, asserts 35/35 equal, 1 of 104 series differs (`memory.limit.used.pct`: tails-messages 83.3,
tails-realtime x2, traffic-generator, tails-web, web drawn in place of the six lowest, redis 6.0 among them);
`restarts` and `restarts.oom` unchanged. M01 vs 4.1.10: none.

**4.2, C6, expected (written before the build, 4.1.12):** F2 (CSV and replay; inputs read from the Stage 0 harness's
frozen `runs/F2`): its source is OK and its only unusable series is `catalog-jvm/heap.live.growth` INSUFFICIENT, which
no assert reads. So on 4.1.0 the Collection panel opens by itself; on 4.1.12 it stays closed and lists heap.live.growth
marked `(no assert reads it)`. Chart data unchanged (report.js only), so `cmp.cjs` shows none anywhere.

**4.2, done (4.1.12).** Red first (`server-board.spec.ts` "[R] C6: …" on E27: an unasserted INSUFFICIENT `up` opened
the panel). The first cut read `state.kpis`, the baked results, which carry no `source`, so no series counted as
asserted and P3's spec went red; it reads `state.declaredKpis`, as the board does. Observed, as registered: F2 rebuilt
from the frozen inputs, headless: 4.1.0 `open: true`, items `catalog-jvm/heap.live.growth INSUFFICIENT`; 4.1.12
`open: false`, the same item ending `(no assert reads it)`. server-board.spec.ts 24/24 on regenerated E26/E27.
Tutorial 13's "open by default only when the source did not answer in full" predated P3 and now says both.

**4.3, C2, expected (written before the F2 check, 4.1.13):** display only, chart data unchanged. F2's slowest-statement
row (27 instances, 20 drawn, named by SQL text): 4.1.0 draws 4 names, clipped off both edges; 4.1.13 draws all 20,
each inside the canvas, plot at least 40% of the width.

**4.3, done (4.1.13).** Red first (`server-layout.spec.ts` "[R] C2: …", E26 `many-instances` with 120-char names: 10
of 20 names drawn). Every name is drawn (`interval: 0`), cut at the widest name's measured width or 38% of the chart,
the panel is max(260, 22 x bars + 70) px tall, and the grid leaves the label room itself: with `containLabel` the
estimate fell 11 px short of a cut name and every name started at x = -11. Observed on F2 (1280 px): 4.1.0 draws 10
names, all 10 off the canvas, and the plot collapses (negative width); 4.1.13 draws 20, none outside, plot 51%, canvas
377x510. The registered "4 names" came from the evaluation's screenshot at another width; same failure. `cmp.cjs`
vs 4.1.12: none. server-layout, server-board and server-numbers specs: 62/62.

**4.4, C4, N9 first.** F2 (4.1.13, headless): the client Timeline's charts tick by the minute at 1166, 618 and 338 px,
no repeat; the server row (`catalog-jvm-heap-used-pct`, judged window 240 s after the 60 s ramp-up) ticks every 30 s
and repeats 4 labels at 755 and 596 px. So, per the plan, server-only: no `formatClock` change, the client pins stay.
**Expected (written before the F2 check, 4.1.14):** that row 0 repeats at 1280 and 700 px; the Timeline unchanged;
chart data unchanged.

**4.4, done (4.1.14).** Red first, two specs in `server-layout.spec.ts`. The first cut of each passed on 4.1.13, so
neither was the mechanism yet: the time test needed F2's ramp-up (a 300 s window charts as 240 s, and only 240 s ticks
every 30 s), the gap test a 303 px chart (1024 px viewport: 0.46 px between two value labels). Fix: server time axes
take `minInterval: 60000` whenever the visible range is two minutes or more (`serverTimeMinInterval`); the Per instance
value axis takes `splitNumber` from its plot width, one tick per 64 px. A first try, `axisLabel.padding`, does not count
toward `hideOverlap`. Observed on F2, as registered: the row reads `18:35 18:36 18:37 18:38` at 1280 and 700 px, the
Timeline unchanged, `cmp.cjs` none. The value labels' smallest gap is 18.8 px or more from 390 to 1920 px.

**4.5, C1, expected (written before the F2 check, 4.1.15):** display only, chart data unchanged. On F2's CSV rebuild, six
rows have instance cells that read as labels (gateway-route-p95 4/4 `routeId=…`, app-pods x3 6/6, kafka-lag 1/1,
catalog-db-slowest-statement 20/20); their Per instance names lose the `instance=` prefix and nothing else changes.
`gateway-route-p95` reads `routeId=catalog …` as the replay of the same run does.

**4.5, done (4.1.15).** Red first (`server-board.spec.ts` "[R] C1: …", E26 `cpu-headroom` with instance cells
`routeId=catalog` and `plain`). `instanceName` shows a lone `instance` label bare when its value starts `k=`. Observed
on F2, as registered: `cmp.cjs` none; gateway-route-p95 `instance=routeId=catalog` → `routeId=catalog`,
kafka-lag `instance=consumergroup=catalog, topic=order-placed` → `consumergroup=catalog, topic=order-placed`, both now
what the replay of the run shows. Aside, not WIR's: the replay carries a fifth route, `routeId=traffic-generator`,
that the CSV export does not (petstore's exporter). Tutorial 16 (two places) and CLAUDE.md say the value can show bare.

**Outside 4.1's registered list:** that list came from a scan of S4 only. F2's catalog-db slowest-statement custom query
(27 instances, capped) also draws a different twenty under C5 (`cmp.cjs` vs 4.1.0: 1 of 117 series, 0 asserts). The
same rule, on a run the scan did not cover.

**Also outside 4.1's list:** I wrote that E26 `many`'s specs pin counts only. `server-tab.spec.ts` (L9-12) also
pinned *which* twenty: p0 kept, p19 dropped, under the old collector-order fill. E26's values rise p0 → p20, so under
C5 p0 (the lowest) gives its slot up and p19 is drawn. The pin now says that (the test commit after 4.1.13), found
on the first run of that spec over fixtures regenerated after 4.1.11.

**End of Stage 4.** Full regression on 4.1.15 green: R01–R06, fixtures regenerated, 1082 Playwright tests (+5). The
R06 goldens did not move, as registered, since the CI/CD JSON and JUnit read the evaluation. The `explore-report` session
over F2 and F5 at 4.1.15 (EXPLORATORY-LOG 2026-10-27) found C5, C6, C2, C4 and C1 holding, plus 8 findings. One was a
defect of 4.1.13 (names under the cap cut: measured in sans-serif, drawn in Microsoft YaHei), now **4.1.16, `2c3b98e`**,
red first. The other seven are logged with reasons; the owner-facing ones are under Open.

## Stage 5: follow the standard tools (branch `fix/server-tab-stage5-standards`)

**Owner, 2026-10-27:**
- "We do not invent some new logics here for performance reporting. All we do is laying out in one nice report both
  client and server metrics."
- JMeter first: the server side gets the same KPIs, by the same logic, as the client, so the two correlate.

So each open item was settled by what JMeter, Grafana, Prometheus and Datadog actually do: a 3-agent research pass with
sources (`tmp/standards/standards-2026-10-27.json`, gitignored), plus JMeter's own source for percentiles.

| Step | Item | The standard | Change |
|---|---|---|---|
| 5.1 | the tooltip clipped (exploration) | Grafana renders tooltips in a portal; ECharts `appendTo` | Server-tab chart tooltips `appendTo: 'body'` |
| 5.2 | colliding names (exploration) | Grafana legend "Auto": only the labels that differ (findUniqueLabels), keys sorted | with 2+ instances, drop the labels all share |
| 5.3 | the deciding instance buried (exploration) | Datadog Top List, Grafana bar gauge sort | Per instance bars by value: worst first (ascending under min), nulls last |
| 5.4 | C5's `avg` ranking | Datadog top/bottom rank by the reduced value | rank by value, not distance from the mean |
| 5.5 | B2 "surface hidden WARNs" | Grafana RuleStats, the Prometheus alerts page: counts per state beside the worst | a count row beside the chip, fixed order, non-zero states only |
| 5.6 | B1 "name the gap" | Grafana state history, Alertmanager startsAt/endsAt | name from-to of the longest stretch no instance reported; the coverage floor stays the tolerance (Datadog's "missing for N min") |
| 5.7 | Collection says only INSUFFICIENT (exploration) | Grafana "No data", the query inspector's rows returned | "No data" or "12 of 120 points", and the assert(s) reading it |
| 5.8 | Q1 path consistency (N2, N3, B7) | Prometheus/Grafana: NaN is a value drawn as a gap; no rows = NoData; failure = Error | the CSV path treats NaN/±Inf cells as the live path does (gaps), and keeps an all-NaN instance |
| 5.9 | N6 the measured CSV step | Grafana's minimum interval; a sparse series does not coarsen others | the finest instance's spacing, not the widest |
| 5.10 | restarts cannot see a replaced pod (exploration) | kube-state-metrics/Datadog: container restarts | description: "container restarts"; a replaced pod is not counted |

Already matching: Q6 (the chip names the label), top-N selection (Datadog `top20` by the reducer), the palette's
cycling, B5 (docs-only: a CSV carries no bucket bounds).

**5.1, expected (written before the F2 check, 4.1.17):** on F2, the Value over time tooltips of `catalog-db-slowest-statement` and
`app-pods-memory-limit-used-pct`, hovered at the right edge, are fully visible, where 4.1.0 cut them at left -590 and -77 px;
`cmp.cjs` none.

**5.1, done (4.1.17).** Red first (`server-layout.spec.ts` "[R] 5.1: …"). `appendTo: 'body'` alone stopped the card clipping, but F2's
slowest-statement tooltip was 1221 px wide and still began at left -458, off the screen. The spec's names were
lengthened to petstore's (red again: -829), and each row's name is cut at 420 px (`.wir-tip-name`), as Grafana's tooltip
rows are. The Per instance tooltip, one row, wraps its name instead, since the bar's own name is already cut. Observed on
F2: both tooltips on the body, about 518 px wide, at left 245, nothing clipped; `cmp.cjs` none. One spec's tooltip lookup
(server-tab) and three HTML pins (server-numbers) follow the new markup.

**5.2, expected (written before the F2 check, 4.1.18):**
- F2's app-pods rows lose `namespace=petstorems` from every name. `container` differs between the deployments, so it
  stays, and the two catalog pods may still read alike once cut.
- gateway-route-p95 and kafka-lag (one label each) are unchanged, and so is catalog-db-slowest-statement (`query` and
  `queryid` both differ).
- `cmp.cjs`: none.

**5.2, done (4.1.18).** Red first (`server-board.spec.ts` "[R] 5.2: …"). `instanceNames` names a series' instances together.
- **Deviation:** F2's CSV app-pods names did **not** change. Petstore's exporter writes the whole label set into the one
  `instance` cell (`container=catalog, namespace=petstorems, pod=…`), so on the CSV path there is no separate label to
  leave out. Parsing the cell back was rejected in C1, because label values (SQL text) hold `, ` and `=`.
- On F2's **replay**, the names went from `container=catalog, namespace=petstorems, pod=catalog-5d4bd757bd-2rf5p` to
  `container=catalog, pod=catalog-5d4bd757bd-2rf5p`.
- The CSV half belongs to the exporter: write only the labels that differ into the `instance` cell, as Grafana's Auto
  legend does. That is a petstore item (the other chat).
- `cmp.cjs`: none. Server specs: 206/206.

**5.3, expected (written before the F2 check, 4.1.19):** F2's `catalog-db-slowest-statement` Per instance leads with the deciding
statement (`select setting from pg_settings…`, 0.688 ms) and no bar is higher than the one above it; `app-pods-memory-limit-used-pct`
leads with the catalog pod at 91.27 %. `cmp.cjs` none.

**5.3, done (4.1.19).** Red first (js-unit `5.3: …` over `_orderBars` for max/avg/min; `server-board.spec.ts` "[R] 5.3: …" on
E26). Observed on F2, as registered: slowest-statement leads with `select setting from pg_settings…` = 0.687937 and no bar
rises (7 did before); app-pods leads with 91.27 %; `cmp.cjs` none. C1's name pin compares the names as a set.

**5.4, expected (written before the build, 4.1.20):** none anywhere. Only a capped series under `across: avg` can draw another
set, and no S4 variant or F-run has one (C08's and F2's capped series fold `max`).

**5.4, done (4.1.20).** Red first (`ServerKpiEvaluatorTest`'s ranking case, avg now `[5, 1, 3, 0, 4, 2]`). Observed, as
registered: C08 vs 4.1.11 and F2 vs 4.1.19, `cmp.cjs` none.

**5.5, expected (written before the F-run check, 4.1.21):** F6's chip names the same worst component as on 4.1.0, and its
counts now include a `warn` (kafka-lag, hidden behind NOT EVALUATED until now); F2's and F5's chips keep their word, with
the rest counted by state. `serverStatus` and the CI/CD JSON unchanged (`cmp.cjs` none); R06 goldens unchanged.

**5.5, done (4.1.21).** Red first (`ServerGateEvaluationTest.theChipCountsTheOtherComponentsByState…`). Observed, as
registered (chip, 4.1.0 → 4.1.21):
- F6: `Catalog JVM NOT EVALUATED · 14 more` → `· 2 not evaluated · 2 warn · 10 pass`. The two WARNs were hidden.
- F5: `Catalog API (no actuator) FAIL · 14 more` → `· 1 fail · 1 not evaluated · 2 warn · 10 pass`.
- F2: `… WARN · 14 more` → `· 14 pass`.
- `serverStatus` and every assert are unchanged. F2's and F6's one series difference is 4.1.11's capped row.
- Five pins of "N more" now hold the counts; Tutorial 01, 13, FEATURES and CLAUDE.md describe them. The Summary card
  prints the same text (`serverVerdict.othersText`).

  **Missed at 5.5:** js-unit was not run before that commit, so `verdict-block.test.js` (AF5) stayed red on the old
  `· 1 more`. Caught at 5.7's js-unit run and fixed in the test-only commit that follows 4.1.22.

**5.6, expected (written before accepting the goldens, 4.1.22; the goldens were not in the list I wrote before the build,
so this is after the first run and before any accept):** every golden whose `.json`/`.xml` carries a LOW_COVERAGE reason
gains `; no point between <from>Z and <to>Z (<n> s)` on that reason, and nothing else moves. On F5, catalog-db's six
LOW_COVERAGE asserts name the outage, 75 s.

**5.6, done (4.1.22).** Vectors first: the two "below the floor 90%" vectors gain the gap, and the Java and node runners
went red on exactly those two. `Coverage.longestGap` (Java) and `longestGap`/`gapText` (the mirror) use Coverage's slots,
name a stretch of 2+ empty steps (Grafana's two evaluation intervals) and write UTC to the second.
- **Goldens, as registered:** G4, G7 and G10 each gain the suffix on their `sparse` assert (`… (46 s)`), and nothing else
  moved in 18 baselines. Accepted.
- **F5 vs 4.1.15:** six reasons differ, reason text only; payload identical.
  - `up` and connections: `no point between 2026-10-25T17:03:09Z and 2026-10-25T17:04:24Z (75 s)`;
  - cache hit, deadlocks, DB time: 60 s (a rate needs two scrapes after the DB returns);
  - slowest statement: 90 s.
- Server specs 225/225, browser parity included.

**For the owner:**
- **Q5, the y-axis.** Grafana bounds it by the data; WIR includes every band (R3-3) so the drag handles stay on the
  plot.
- **Percentiles.** JMeter's Aggregate Report uses nearest rank, as WIR's server `pN` does. JMeter's dashboard
  interpolates (commons-math LEGACY, `backend_metrics_percentile_estimator`, over a 20000-sample window). Prometheus
  uses R-7. WIR's client side uses a t-digest. Recommendation: keep nearest rank and document it.

## Open

From the Stage 4 exploration. These predate Stage 4 or are design calls, so none is in the staged plan; each needs the
owner's go:
- **The Value over time tooltip is clipped** by the card's overflow when names are long (loses the time and the name
  starts). Candidate: `tooltip.appendToBody`. A real wrong-output case, and small.
- **Cut Per instance names collide** (`container=catalog, n…` twice): a middle ellipsis, or the differing labels first.
- **The deciding instance is not first** in either panel (alphabetical), and 20 series share a 10-colour palette.
- **The Collection list says only INSUFFICIENT**: no coverage, no assert id, raw PromQL for custom series.
- **B1 "name the gap"** (owner, 2026-10-27) is decided but not scheduled: the LOW COVERAGE detail could name the longest
  gap in the judged window. F5 shows why: the DB outage reads as a data problem.
- **kubernetes-pod restarts cannot see a deleted pod** (F2): a catalogue description note.

- **End of Stage 2 (4.1.7):** full regression green on 2026-10-27, R01–R06 with fixtures regenerated, 1077 Playwright
  passed. That includes `docs-check-bugs.spec.ts` E02 and E25, the two that timed out in the A1+A2 regression, so those
  were flaky. Not pushed: the push waits for the owner.
- **A first `build-and-deploy.sh`** failed in the report module, and two re-runs passed without a change. Not
  investigated.
