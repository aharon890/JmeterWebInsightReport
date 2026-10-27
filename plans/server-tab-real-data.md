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

## Open

- **End of Stage 2 (4.1.7):** full regression green on 2026-10-27, R01–R06 with fixtures regenerated, 1077 Playwright
  passed. That includes `docs-check-bugs.spec.ts` E02 and E25, the two that timed out in the A1+A2 regression, so those
  were flaky. Not pushed: the push waits for the owner.
- **A first `build-and-deploy.sh`** failed in the report module, and two re-runs passed without a change. Not
  investigated.
