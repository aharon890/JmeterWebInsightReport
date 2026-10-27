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

## Open

- **End of Stage 2 (4.1.7):** full regression green on 2026-10-27, R01–R06 with fixtures regenerated, 1077 Playwright
  passed. That includes `docs-check-bugs.spec.ts` E02 and E25, the two that timed out in the A1+A2 regression, so those
  were flaky. Not pushed: the push waits for the owner.
- **A first `build-and-deploy.sh`** failed in the report module, and two re-runs passed without a change. Not
  investigated.
