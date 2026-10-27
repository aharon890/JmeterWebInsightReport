import { test, expect } from '@playwright/test';
import { loadClean } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

/**
 * The browser's server-KPI evaluator must agree with the Java one, case for case.
 *
 * The promise M6 makes is: drag a threshold here, watch the verdict recompute, save the file,
 * and have the next `jmeter -g` reproduce what you were shown. That is only true if two
 * independently written evaluators agree — and two implementations of one rule set drift
 * between the runs that test them.
 *
 * So neither owns the definition. `server-parity-vectors.json` does. `ServerParityTest` runs it
 * in Java, `test-plans/js-unit/server-parity.test.js` runs it in node, and this runs it in a
 * REAL GENERATED REPORT — which is a different claim from the node tier's and worth making
 * separately: it proves the code works when it is loaded out of the file a tester actually
 * opens, inlined, minified by nothing, and sitting beside every other module.
 *
 * It also carries the end-to-end twin the node tier cannot: recompute every one of E26's own
 * KPIs from E26's own annotations against E26's own collected payload, and diff against the
 * verdicts Java baked into the same page.
 */

const VECTORS = path.resolve(
  __dirname, '..', '..', '..',
  'jmeter-report-core/src/test/resources/servermetrics/server-parity-vectors.json');

const vectors = JSON.parse(fs.readFileSync(VECTORS, 'utf-8'));

/* ReportDataSerializer's shape: 2dp for what the tables show, the figures round2 changed again,
   exact, under `exact` (the client evaluator lays them over the 2dp ones), and throughput exact in
   place (R2-1, both halves) — the payload the page really carries. */
const round2 = (x: number) => Math.round(x * 100) / 100;
function carried(target: any, exact: any, key: string, value: number) {
  target[key] = round2(value);
  if (round2(value) !== value) exact[key] = value;
}

/** The vector file's sampler shape → the payload shape the CLIENT evaluator reads. */
function clientPayload(v: any) {
  const samplers = v.samplers.map((s: any) => {
    const out: any = { name: s.name, count: s.sampleCount, errorCount: s.errorCount,
      throughput: s.throughput, apdex: s.apdex };
    const exact: any = {};
    ['errorRate', 'mean', 'median', 'p90', 'p95', 'p99', 'min', 'max', 'stdDev', 'meanLatency',
      'meanConnectTime', 'receivedBytesPerSec', 'sentBytesPerSec']
      .forEach((k) => carried(out, exact, k, s[k]));
    if (Object.keys(exact).length) out.exact = exact;
    return out;
  });
  const totalSamples = v.samplers.reduce((n: number, s: any) => n + s.sampleCount, 0);
  const totalErrors = v.samplers.reduce((n: number, s: any) => n + s.errorCount, 0);
  const durationSeconds = v.metadata.durationMillis / 1000;
  return {
    samplers,
    errorSummaries: [],
    overall: {
      totalSamples,
      errorRate: totalSamples ? (totalErrors * 100) / totalSamples : 0,
      duration: durationSeconds,
      maxThreads: v.metadata.totalThreads,
      mean: totalSamples
        ? v.samplers.reduce((n: number, s: any) => n + s.mean * s.sampleCount, 0) / totalSamples : 0,
      throughput: durationSeconds > 0 ? totalSamples / durationSeconds : 0,
      apdex: totalSamples
        ? Math.round((v.samplers.reduce((n: number, s: any) => n + s.apdex * s.sampleCount, 0)
          / totalSamples) * 100) / 100 : 0,
      median: 0, p90: 0, p95: 0, p99: 0,
    },
    assertPercentiles: {},
  };
}

test.describe('Server parity — the vectors, in a real report', () => {
  test('[U] every vector produces the declared result in the browser', async ({ page }) => {
    await loadClean(page, 'E26');

    const results = await page.evaluate(
      ({ v, client }) => {
        const W = (window as any).WIR;
        const A = W.asserts;
        const SK = W.serverKpis;

        // Live client results — uncapped and full precision. Reading the BAKED
        // chartData.assertions instead would take a divisor rounded to 2dp out of a list
        // capped at 200 matches, and a server KPI would not re-decide when its client load
        // rule was retuned, which is the whole point of a mirror.
        const rules = v.clientRules.map((r: any, i: number) => A._ruleFromJson(r, i));
        const clientResults = A.evaluate(rules, client).rules;

        // The block's declared components - the file's, with a case's own merged over them by id.
        // The same object ServerParityTest declares. The catalogue is the one this report shipped
        // (chartData.serverCatalogue), read from the same files the node tier reads.
        const componentsFor = (c: any) => Object.assign(JSON.parse(JSON.stringify(v.components)),
          c && c.components ? JSON.parse(JSON.stringify(c.components)) : {});

        function configFor(c: any) {
          const block: any = {
            collection: {
              coverageFloor: v.block.coverageFloor,
              excludeRampUp: v.block.excludeRampUp,
            },
          };
          if (c && c.config) {
            if (c.config.excludeRampUp !== undefined) block.collection.excludeRampUp = c.config.excludeRampUp;
            if (c.config.coverageFloor !== undefined) block.collection.coverageFloor = c.config.coverageFloor;
            if (c.config.requiresLoad !== undefined) block.requiresLoad = c.config.requiresLoad;
          }
          return SK.configFromDocument(block);
        }

        const shape = (r: any) => ({
          id: r.id,
          status: r.status,
          notJudged: r.notJudged,
          detail: r.notJudgedDetail,
          actual: isNaN(r.actual) ? null : r.actual,
          coverage: isNaN(r.coverage) ? null : r.coverage,
          instances: r.instances,
          breached: r.breached ? { op: r.breached.op, value: r.breached.value } : null,
          warnings: SK._kpiWarnings(r.kpi),
        });

        // A document case's block: the file's components, its asserts and the case's own block
        // keys (environments, defaultEnv, collection) — what ServerParityTest sets over it.
        const documentBlock = (d: any) => Object.assign({ components: componentsFor(d), asserts: d.asserts },
          d.block ? JSON.parse(JSON.stringify(d.block)) : {});

        return {
          cases: v.cases.map((c: any) => {
            // The PRODUCTION parser, not a hand-written shape — the client side learned that
            // pinning two evaluators while both suites build the rule with a test helper leaves
            // the two PARSERS compared by nothing at all.
            const kpi = SK.kpisFromDocument({ components: componentsFor(c), asserts: [c.kpi] })[0];
            return shape(SK.evaluate([kpi], configFor(c), v.payload, clientResults)[0]);
          }),
          // Several asserts in one block: two sharing an id are still two results, in order.
          documents: (v.documentCases || []).map((d: any) => {
            const kpis = SK.kpisFromDocument(documentBlock(d));
            return SK.evaluate(kpis, configFor(null), v.payload, clientResults).map(shape);
          }),
          // What only a set raises (R2-2/R3-8): the shared-id WARNINGs, then each assert's own.
          documentWarnings: (v.documentCases || []).map((d: any) => {
            const kpis = SK.kpisFromDocument(documentBlock(d));
            const raised = SK.duplicateIdFindings(kpis).map((f: any) => ({ id: f.ruleId, message: f.message }));
            kpis.forEach((k: any) => SK._kpiWarnings(k).forEach((m: string) => raised.push({ id: k.id, message: m })));
            return raised;
          }),
          // EVERY finding, in Java's phases and under its ruleIds (SF18), each mistake once (SF21):
          // the production builder the Server tab's pane draws, over the verdicts just judged.
          documentFindings: (v.documentCases || []).map((d: any) => {
            if (d.findings === undefined) return null;
            const block = documentBlock(d);
            const kpis = SK.kpisFromDocument(block);
            const results = SK.evaluate(kpis, configFor(null), v.payload, clientResults);
            return SK.documentFindings(block, kpis, results, [], null).map((f: any) => ({
              severity: String(f.severity).toUpperCase(), id: f.ruleId, message: f.message }));
          }),
          // NotJudged.phrase(), which the load-rule sentences and the chart notes are built from.
          reasonPhrases: W.status.REASON_PHRASE,
          // What a block says beside its asserts (D25, D26): a leftover `kpis`, a `packs`.
          blocks: (v.blockCases || []).map((b: any) => {
            const block: any = Object.assign({ components: componentsFor(null) }, b.block);
            return {
              problems: SK.blockProblems(block),
              componentProblems: SK.componentProblems(block),
              ids: SK.kpisFromDocument(block).map((k: any) => k.id),
              shape: SK._blockShapeProblem(block),
            };
          }),
        };
      },
      { v: vectors, client: clientPayload(vectors) },
    );

    expect(results.cases.length).toBe(vectors.cases.length);
    expect(results.cases.length).toBeGreaterThanOrEqual(55);
    expect(results.documents.length, 'the document cases are gone').toBeGreaterThanOrEqual(4);

    const check = (got: any, expected: any, name: string) => {
      const where = `${name}${got.detail ? ' (' + got.detail + ')' : ''}`;
      if (expected.id !== undefined) {
        // The id the KPI is judged under — the derived one when the file names none.
        expect(got.id, `id for ${where}`).toBe(expected.id);
      }
      checkOne(got, expected, where, name);
    };

    vectors.cases.forEach((c: any, i: number) => check(results.cases[i], c.expect, c.kpi.id || c.name));
    (vectors.documentCases || []).forEach((d: any, i: number) => {
      expect(results.documents[i].length, `results for ${d.name}`).toBe(d.expect.length);
      d.expect.forEach((e: any, j: number) => check(results.documents[i][j], e, `${d.name} [${j}]`));
      if (d.warnings !== undefined) {
        expect(results.documentWarnings[i], `WARNING findings for ${d.name}`).toEqual(d.warnings);
      }
      if (d.findings !== undefined) {
        expect(results.documentFindings[i], `the findings of ${d.name}, in order`).toEqual(d.findings);
      }
    });
    expect((vectors.documentCases || []).filter((d: any) => d.findings !== undefined).length,
      'the document cases that carry findings are gone').toBeGreaterThanOrEqual(4);
    // Every phrase the vectors pin, and no phrase they do not: the same check the node and Java
    // runners make (SF23 moved SOURCE_FAILED's).
    const pinned = Object.keys(vectors.reasonPhrases || {}).filter((k) => k !== '_comment');
    expect(pinned.length, 'the reason phrases are gone').toBeGreaterThanOrEqual(7);
    pinned.forEach((k) => expect(results.reasonPhrases[k], `phrase for ${k}`).toBe(vectors.reasonPhrases[k]));
    Object.keys(results.reasonPhrases).forEach((k) =>
      expect(pinned, `REASON_PHRASE.${k} is pinned by no vector`).toContain(k));
    expect(results.blocks.length, 'the block cases are gone').toBeGreaterThanOrEqual(8);
    (vectors.blockCases || []).forEach((b: any, i: number) => {
      expect(results.blocks[i].problems, `block problems for ${b.name}`).toEqual(b.expect.problems);
      expect(results.blocks[i].ids, `asserts that still map for ${b.name}`).toEqual(b.expect.ids);
      expect(results.blocks[i].componentProblems, `component problems for ${b.name}`)
        .toEqual(b.expect.componentProblems || []);
      expect(results.blocks[i].shape, `not a whole-file refusal: ${b.name}`).toBeNull();
    });
  });

  test('[U] F8: the invisible-end class is the vector file\'s, at every range edge and one either side',
    async ({ page }) => {
      await loadClean(page, 'E26');
      // The node tier sweeps every code point through the same report.js; this asks the copy a
      // tester opens at the places a range table goes wrong - each edge, and one past it each way -
      // and that a listed character is named by its code point, which a page reading UTF-16 units
      // gets wrong past the BMP (U+E0020 read as U+DC20).
      const section: string[] = (vectors.invisibleEnds && vectors.invisibleEnds.ranges) || [];
      expect(section.length, 'invisibleEnds.ranges shrank').toBeGreaterThanOrEqual(20);
      const ranges = section.map((r) => {
        const ends = r.split('-').map((h) => parseInt(h, 16));
        return [ends[0], ends[ends.length - 1]];
      });
      const listed = (cp: number) => ranges.some((r) => cp >= r[0] && cp <= r[1]);
      const probes: number[] = [];
      ranges.forEach((r) => [r[0] - 1, r[0], r[1], r[1] + 1].forEach((cp) => {
        if (cp > 0x20 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF) && probes.indexOf(cp) < 0) probes.push(cp);
      }));

      const warned = await page.evaluate((cps: number[]) => {
        const SK = (window as any).WIR.serverKpis;
        const one = (q: string) => {
          const w = SK._kpiWarnings({ query: q, reducer: null });
          return w.length ? w[0] : null;
        };
        return cps.map((cp) => {
          const ch = String.fromCodePoint(cp);
          return { cp, atEnd: one('x' + ch), atStart: one(ch + 'x') };
        });
      }, probes);

      expect(warned.length).toBe(probes.length);
      expect(probes.length, 'the probes cover every edge of every range').toBeGreaterThanOrEqual(60);
      const wrong = warned.filter((w) => {
        const name = 'U+' + w.cp.toString(16).toUpperCase().padStart(4, '0');
        return listed(w.cp)
          ? !(w.atEnd && w.atEnd.indexOf(`its end (${name})`) >= 0
            && w.atStart && w.atStart.indexOf(`its start (${name})`) >= 0)
          : w.atEnd !== null || w.atStart !== null;
      }).map((w) => 'U+' + w.cp.toString(16).toUpperCase().padStart(4, '0')
        + (listed(w.cp) ? ' (listed, not named)' : ' (warned, not listed)'));
      expect(wrong, 'the page\'s invisible class differs from invisibleEnds.ranges at these code points').toEqual([]);
    });
});

/** One result against what its vector expects — the single cases and the document cases alike. */
function checkOne(got: any, expected: any, where: string, name: string) {
  expect(got.status, `status for ${where}`).toBe(expected.status);

  if (expected.notJudged !== undefined) {
    expect(got.notJudged, `reason for ${where}`).toBe(expected.notJudged);
  } else {
    expect(got.notJudged, `a judged KPI must carry no reason: ${name}`).toBeNull();
  }
  if (expected.actual !== undefined) {
    expect(Math.abs(got.actual - expected.actual), `actual for ${where}`).toBeLessThanOrEqual(1e-6);
  } else if (expected.notJudged !== undefined) {
    expect(got.actual, `a not-judged KPI has no number: ${name}`).toBeNull();
  }
  if (expected.reason !== undefined) {
    expect(got.detail, `detail for ${name}`).toContain(expected.reason);
  }
  // The whole sentence, where every character is the contract - the numbers above all (M7d X).
  if (expected.detail !== undefined) {
    expect(got.detail, `the whole detail for ${name}`).toBe(expected.detail);
  }
  if (expected.breached !== undefined) {
    const op = Object.keys(expected.breached)[0];
    expect(got.breached, `no band fired for ${name}`).not.toBeNull();
    expect(got.breached!.op).toBe(op);
    expect(Math.abs(got.breached!.value - expected.breached[op])).toBeLessThanOrEqual(1e-9);
  } else if (got.status === 'PASS') {
    expect(got.breached, `a PASS names no band: ${name}`).toBeNull();
  }
  if (expected.instances !== undefined) {
    expect(got.instances, `instances for ${name}`).toBe(expected.instances);
  }
  // KpiUsability's WARNING sentences — what the editor and a drag show (C-11).
  if (expected.warnings !== undefined) {
    expect(got.warnings, `WARNING sentences for ${name}`).toEqual(expected.warnings);
  }
}

/**
 * The page re-judges every server assert of the loaded report and pairs each with the verdict Java baked
 * in, by position: the end-to-end twin's one comparison, on every fixture it runs over (Stage 6).
 */
const judgeTwice = () => {
    const W = (window as any).WIR;
    const data = (window as any).globalChartData;
    const SK = W.serverKpis;
    const A = W.asserts;

    const block = data.annotationsDocument && data.annotationsDocument.serverMetrics;
    const kpis = SK.kpisFromDocument(block);
    const config = SK.configFromDocument(block);

    // The client half re-judged too, so requiresLoad and divideBy resolve against live
    // results — from a payload whose throughput is now carried at full precision (R2-1).
    const rules = (data.annotationsDocument.assertions || [])
      .map((r: any, i: number) => A._ruleFromJson(r, i));
    const clientResults = A.evaluate(rules, data).rules;

    const mine = SK.evaluate(kpis, config, data.serverMetrics, clientResults);
    const baked = data.serverKpis || [];

    // By POSITION, the way the board looks a baked verdict up: serverKpis[i] is the verdict
    // on the file's i-th KPI. Matching by id would pair two KPIs that share one.
    return baked.map((b: any, i: number) => {
      const m = mine[i];
      const kpi = kpis[i];
      const series = kpi ? SK._seriesFor2(data.serverMetrics, kpi.source, kpi.query) : null;
      return {
        id: b.id,
        mineId: m ? m.id : 'MISSING',
        recomputable: SK.recomputable(series),
        bakedStatus: b.status,
        mineStatus: m ? m.status : 'MISSING',
        bakedReason: b.notJudged || null,
        mineReason: m ? m.notJudged : null,
        bakedActual: b.actual === null || b.actual === undefined ? null : b.actual,
        mineActual: m && !isNaN(m.actual) ? m.actual : null,
        bakedDetail: b.reason || null,
        mineDetail: m ? m.notJudgedDetail || null : null,
        bakedInstances: b.instances,
        mineInstances: m ? m.instances : null,
        bakedCoverage: b.coverage === null || b.coverage === undefined ? null : b.coverage,
        mineCoverage: m && !isNaN(m.coverage) ? m.coverage : null,
      };
    });
  };

test.describe('Server parity — the end-to-end twin', () => {
  test('[U] E26 re-judged in the browser matches the verdicts Java baked into the page',
    async ({ page }) => {
      await loadClean(page, 'E26');

      const compared = await page.evaluate(judgeTwice);

      // A guard, so an empty payload cannot make this pass by comparing nothing.
      expect(compared.length, 'E26 carried no server KPIs to compare').toBeGreaterThan(5);
      compared.forEach((c) => expect(c.mineId, 'the baked list is the file, one for one').toBe(c.id));

      // A CAPPED series is excluded, and that exclusion is the finding rather than a caveat.
      // The page carries at most 20 instances per series and thins each to 4000 points, while
      // Java judged the collector's uncapped payload — so on E26's 21-instance `many` series
      // the browser folds 20 pods and gets 31 where Java folded 21 and got 32. Neither is
      // wrong; they are answers to different questions, and a mirror that presented its own as
      // the verdict would quietly contradict the number the build was gated on. That is the
      // rule.results trap in a new place, and no vector can catch it: the vector payload is
      // small by construction.
      const capped = compared.filter((c) => !c.recomputable);
      expect(capped.length, 'E26 no longer carries a capped series — the guard is untested')
        .toBeGreaterThan(0);

      compared.filter((c) => c.recomputable).forEach((c) => {
        expect(c.mineStatus, `status for ${c.id}`).toBe(c.bakedStatus);
        expect(c.mineReason, `reason for ${c.id}`).toBe(c.bakedReason);
        // The SENTENCE too (R3-18): the board shows the mirror's detail beside every live row, and
        // the CI JSON carries Java's. needs-load's read "breached < 100,000 /s" here — the reader's
        // locale — where the build wrote "< 100000 /s".
        expect(c.mineDetail, `detail for ${c.id}`).toBe(c.bakedDetail);
        expect(c.mineInstances, `instances for ${c.id}`).toBe(c.bakedInstances);
        if (c.bakedCoverage !== null) {
          expect(Math.abs(c.mineCoverage! - c.bakedCoverage), `coverage for ${c.id}`)
            .toBeLessThanOrEqual(5e-5);
        } else {
          expect(c.mineCoverage, `coverage for ${c.id}`).toBeNull();
        }
        if (c.bakedActual !== null) {
          // The page is given Java's own double (F7) - the CI/CD JSON keeps 4dp, the page does not -
          // so the mirror must reach the same number, not one within the wire's rounding. A 4dp
          // actual on the page read "2.2415" for a FAIL at 2.24146 under lt 2.24148: the passing
          // side of its own band.
          expect(Math.abs(c.mineActual! - c.bakedActual), `actual for ${c.id}: mine ${c.mineActual}, baked ${c.bakedActual}`)
            .toBeLessThanOrEqual(1e-9 * Math.max(1, Math.abs(c.bakedActual)));
        } else {
          expect(c.mineActual, `actual for ${c.id}`).toBeNull();
        }
      });

      // Not vacuous: an integer compares equal at any precision, so at least one baked actual must
      // carry digits a 4dp wire would have dropped (E26's cpu-per-rps, 2.24146...).
      const finer = compared.filter((c) => c.recomputable && c.bakedActual !== null
        && c.bakedActual !== Math.round(c.bakedActual * 1e4) / 1e4);
      expect(finer.map((c) => c.id), 'no baked actual carries more than 4 decimals - the page is given the wire\'s 4dp')
        .not.toEqual([]);
    });

  test('[U] a capped series is not offered as recomputable', async ({ page }) => {
    await loadClean(page, 'E26');

    // The payload the page carries is capped where the Java evaluator's is not: 20 instances
    // per series, each thinned to 4000 points. E26's `many` series has 21 instances, so it is
    // the one whose recomputed verdict could not be compared with the one the build was gated
    // on. No vector can catch this — the vector payload is small by construction — so it is
    // pinned here, on the fixture built to carry it.
    const verdict = await page.evaluate(() => {
      const W = (window as any).WIR;
      const data = (window as any).globalChartData;
      const series = (data.serverMetrics.series || []);
      const many = series.find((s: any) => s.query === 'many');
      const lag = series.find((s: any) => s.query === 'lag');
      return {
        manyCapped: !!many && many.instancesCapped === true,
        manyRecomputable: W.serverKpis.recomputable(many),
        lagRecomputable: W.serverKpis.recomputable(lag),
      };
    });

    expect(verdict.manyCapped, 'E26 no longer carries a capped series').toBe(true);
    expect(verdict.manyRecomputable).toBe(false);
    expect(verdict.lagRecomputable).toBe(true);
  });

  test('[U] the throughput the mirror divides by is the one Java divided by (R2-1)', async ({ page }) => {
    await loadClean(page, 'E26');

    // E26 ran 1200 samples in 48.464 s: 24.7606… req/s. The payload carried 24.76, so the
    // mirror's cpu-per-rps was cpu / 24.76 where the build's was cpu / 24.7606…; the twin above
    // compared at the wire's 4dp then and could not see it (it compares Java's double now, F7).
    // Rounded at 2dp, a run sitting exactly on a
    // load rule's limit passes it here and is stood down in CI.
    const got = await page.evaluate(() => {
      const data = (window as any).globalChartData;
      return { overall: data.overall.throughput, samplers: data.samplers.map((s: any) => s.throughput) };
    });
    const round2 = (x: number) => Math.round(x * 100) / 100;
    expect(got.overall, 'overall.throughput was rounded for the payload').not.toBe(round2(got.overall));
    expect(got.samplers.some((t: number) => t !== round2(t)),
      'every sampler throughput is a 2dp number — E26 should carry at least one that is not').toBe(true);
  });

  test('[U] a client rule is judged on the exact figure the page carries beside the 2dp one (R2-1)',
    async ({ page }) => {
      await loadClean(page, 'E26');
      // R2-1's client half (M7d X): every other figure a client rule names was 2dp, so a rule
      // re-judged after an edit could flip at an edge the run does not. The payload now carries
      // what round2 changed under `exact`, and the evaluator reads that. For the first sampler
      // whose mean round2 moved, a band ON the shown 2dp mean, on the side the exact one lies,
      // FAILs — judged at 2dp it would sit on the limit and PASS.
      const got = await page.evaluate(() => {
        const W = (window as any).WIR;
        const data = (window as any).globalChartData;
        const s = data.samplers.find((x: any) => x.exact && typeof x.exact.mean === 'number');
        if (!s) return null;
        const op = s.exact.mean > s.mean ? 'gt' : 'lt';
        const rule = W.asserts._ruleFromJson({ id: 'edge', target: { sampler: s.name }, metric: 'mean',
          fail: { [op]: s.mean } }, 0);
        const r = W.asserts.evaluate([rule], data).rules[0];
        return { shown: s.mean, exact: s.exact.mean, status: r.status, actual: r.results[0].actual };
      });
      expect(got, 'E26 carries no sampler whose mean round2 changed').not.toBeNull();
      expect(got!.exact).not.toBe(got!.shown);
      expect(got!.actual, 'the evaluator read the 2dp figure').toBe(got!.exact);
      expect(got!.status).toBe('FAIL');
    });

  // E26 has only custom components and none of the CSV path's sentences. E27 is the catalogue fixture
  // (a query, unit, reduce and across the page resolves from chartData.serverCatalogue); E28 is a real
  // jmeter -g CSV collection carrying Stage 2's and 5.8's reasons. The board shows the mirror's detail
  // beside every live row and the CI/CD JSON Java's: a drift between them is caught here (Stage 6).
  for (const fixture of ['E27', 'E28']) {
    test(`[U] ${fixture} re-judged in the browser matches Java's verdicts, sentence for sentence`, async ({ page }) => {
      await loadClean(page, fixture);
      const compared = await page.evaluate(judgeTwice);
      expect(compared.length, `${fixture} carried no server KPIs to compare`).toBeGreaterThan(1);
      const live = compared.filter((c) => c.recomputable);
      expect(live.length, `${fixture}: nothing the page re-judges`).toBeGreaterThan(0);
      compared.forEach((c) => expect(c.mineId, 'the baked list is the file, one for one').toBe(c.id));
      live.forEach((c) => {
        expect(c.mineStatus, `status for ${c.id}`).toBe(c.bakedStatus);
        expect(c.mineReason, `reason for ${c.id}`).toBe(c.bakedReason);
        expect(c.mineDetail, `detail for ${c.id}`).toBe(c.bakedDetail);
        expect(c.mineInstances, `instances for ${c.id}`).toBe(c.bakedInstances);
        if (c.bakedCoverage !== null) {
          expect(Math.abs(c.mineCoverage! - c.bakedCoverage), `coverage for ${c.id}`).toBeLessThanOrEqual(5e-5);
        } else {
          expect(c.mineCoverage, `coverage for ${c.id}`).toBeNull();
        }
        if (c.bakedActual !== null) {
          expect(Math.abs(c.mineActual! - c.bakedActual), `actual for ${c.id}: mine ${c.mineActual}, baked ${c.bakedActual}`)
            .toBeLessThanOrEqual(1e-9 * Math.max(1, Math.abs(c.bakedActual)));
        } else {
          expect(c.mineActual, `actual for ${c.id}`).toBeNull();
        }
      });
    });
  }

});
