import { test, expect } from '@playwright/test';
import { loadClean } from './helpers';
import * as fs from 'fs';
import * as path from 'path';

/**
 * The client evaluator must agree with the Java engine, case for case.
 *
 * Phase 2's whole promise is: tune a threshold here, watch the verdict recompute, save the
 * file, and have the next `jmeter -g` reproduce what you were shown. That is only true if two
 * independently written evaluators agree — and two implementations of the same rules drift
 * between the runs that test them.
 *
 * So neither owns the definition. `assert-parity-vectors.json` does. `AssertionParityTest`
 * runs it in Java; this runs the same file in the browser. A case only one side passes is
 * exactly the bug the file exists to catch.
 */

const VECTORS = path.resolve(
  __dirname, '..', '..', '..',
  'jmeter-report-core/src/test/resources/assertion/assert-parity-vectors.json');

interface Vectors {
  samplers: any[];
  errorSummaries?: any[];
  hiddenSamplers: string[];
  metadata: { durationMillis: number; totalThreads: number };
  extraPercentiles?: Record<string, Record<string, number>>;
  cases: any[];
  gateCases: any[];
}

const vectors: Vectors = JSON.parse(fs.readFileSync(VECTORS, 'utf-8'));

/**
 * The vector file speaks the engine's language: SamplerStatistics fields and the JSON rule
 * shape. The browser reads the report payload. This is the same translation
 * `GatePayloadBuilder` performs, done here so the vectors stay the single source of truth
 * rather than being duplicated in a second fixture.
 */
function payloadFrom(v: Vectors) {
  const samplers = v.samplers.map(s => ({
    name: s.name,
    count: s.sampleCount,
    errorCount: s.errorCount,
    errorRate: s.errorRate,
    mean: s.mean,
    median: s.median,
    p90: s.p90,
    p95: s.p95,
    p99: s.p99,
    min: s.min,
    max: s.max,
    stdDev: s.stdDev,
    throughput: s.throughput,
    apdex: s.apdex,
    // The payload's own field names, carrying bytes/sec — the KB/s division lives in
    // SAMPLER_FIELDS on one side and MetricRef.valueFor on the other, which is exactly the
    // kind of duplicated arithmetic these vectors exist to pin.
    meanLatency: s.meanLatency,
    meanConnectTime: s.meanConnectTime,
    receivedBytesPerSec: s.receivedBytesPerSec,
    sentBytesPerSec: s.sentBytesPerSec,
    ...(s.parent ? { parent: s.parent } : {}),
  }));

  // Top-level only, exactly as OverallMetrics defines the population: a transaction child's
  // time is already inside its parent.
  const topLevel = v.samplers.filter(s => !s.parent);
  const totalSamples = topLevel.reduce((n, s) => n + s.sampleCount, 0);
  const totalErrors = topLevel.reduce((n, s) => n + s.errorCount, 0);
  const durationSeconds = v.metadata.durationMillis / 1000;

  return {
    samplers,
    /* errorClass rides in the payload, computed once in Java — the browser reads the field
       rather than classifying, so there is one classifier and no drift to police. */
    errorSummaries: v.errorSummaries || [],
    hiddenSamplers: v.hiddenSamplers,
    overall: {
      totalSamples,
      errorRate: totalSamples ? (totalErrors * 100) / totalSamples : 0,
      duration: durationSeconds,
      maxThreads: v.metadata.totalThreads,
      mean: totalSamples
        ? topLevel.reduce((n, s) => n + s.mean * s.sampleCount, 0) / totalSamples : 0,
      throughput: durationSeconds > 0 ? totalSamples / durationSeconds : 0,
      apdex: totalSamples
        ? Math.round((topLevel.reduce((n, s) => n + s.apdex * s.sampleCount, 0) / totalSamples)
            * 100) / 100 : 0,
      median: 0, p90: 0, p95: 0, p99: 0,
    },
    // The off-grid quantiles, keyed exactly as GatePayloadBuilder writes them. A sampler
    // absent from the block has no distribution, which must read as INVALID rather than zero.
    assertPercentiles: v.extraPercentiles || {},
  };
}

/** The JSON rule shape the vectors use → the payload rule shape the client evaluator reads. */
function ruleFrom(r: any) {
  const t = r.target;
  let target: any;
  if (t.scope) target = { kind: 'overall', text: 'Whole run' };
  else if (t.sampler) target = { kind: 'sampler', sampler: t.sampler, text: t.sampler };
  else if (t.samplers) target = { kind: 'samplers', samplers: t.samplers, text: t.samplers.join(', ') };
  else target = { kind: 'match', match: t.match, exclude: t.exclude || [], text: t.match };

  const band = (b: any) => {
    if (!b) return undefined;
    const key = Object.keys(b)[0];
    return { op: key, value: b[key] };
  };

  return {
    id: r.id || 'rule',
    description: r.description || '',
    target,
    metric: r.metric,
    unit: '',
    enabled: r.enabled !== false,
    warn: band(r.warn),
    fail: band(r.fail),
  };
}

test.describe('Client evaluator parity with the Java engine', () => {

  for (const c of vectors.cases) {
    test(`case — ${c.name}`, async ({ page }) => {
      await loadClean(page, 'E25');

      // The Budget column's attainment is taken over the evaluated rule in the same page call:
      // Attainment.of in AssertionParityTest, attainmentFor here (AF5b).
      const { actual, attainment } = await page.evaluate(({ rule, data }) => {
        const A = (window as any).WIR.asserts;
        const evaluated = A.evaluate([rule], data);
        return { actual: evaluated, attainment: A._attainment(evaluated.rules[0], data) };
      }, { rule: ruleFrom(c.rule), data: payloadFrom(vectors) });

      const result = actual.rules[0];
      expect(result.status, `status for ${c.rule.id}`).toBe(c.expect.status);
      expect(result.matched, `matched count for ${c.rule.id}`).toBe(c.expect.matched);

      if (c.expect.unmatched !== undefined) {
        expect(result.unmatched).toBe(c.expect.unmatched);
      }
      // WHICH kind of nothing, not merely that nothing happened. NOT_JUDGED alone would let
      // the two engines agree a rule decided nothing while disagreeing about why — and
      // "your target matched nothing" versus "your metric name is a typo" send the reader to
      // two different files.
      if (c.expect.notJudged !== undefined) {
        expect(result.notJudged, `no reason given for ${c.rule.id}`).toBe(c.expect.notJudged);
      } else {
        expect(result.notJudged, `a judged rule must carry no reason: ${c.rule.id}`)
          .toBeUndefined();
      }
      // And the sentence behind it. Leaving this unpinned hid a real divergence: this evaluator
      // never checked whether a metric exists in the rule's SCOPE, so a whole-run rule naming a
      // per-sampler metric got resolved instead of rejected. It reached the same status anyway
      // — from a NaN lookup rather than from the scope check — so only `matched` (1 here
      // against 0 in Java) gave it away.
      if (c.expect.reason !== undefined) {
        expect(result.notJudgedDetail, `no detail given for ${c.rule.id}`).toBeTruthy();
        expect(result.notJudgedDetail).toContain(c.expect.reason);
      }
      if (c.expect.matches) {
        expect(result.results).toHaveLength(c.expect.matches.length);
        c.expect.matches.forEach((m: any, i: number) => {
          expect(result.results[i].sampler).toBe(m.sampler);
          if (m.actual === null) {
            // The run cannot answer this metric. Zero would be a legitimate measurement and
            // would pass every "must be under" band, which is the whole reason it is not zero.
            expect(result.results[i].actual, `match ${i} should be unanswerable`).toBeNull();
          } else {
            expect(result.results[i].actual).toBeCloseTo(m.actual, 4);
          }
          expect(result.results[i].status).toBe(m.status);
        });
      }
      // How much of the fail band the rule used (AF5b): null where the question does not apply —
      // no fail band, a rule that decided nothing, a divisor of zero — never a zeroed budget.
      // Until these vectors the Budget column was held to Java by one fixture's bar colour.
      if (c.expect.attainment !== undefined) {
        if (c.expect.attainment === null) {
          expect(attainment, `no budget applies to ${c.rule.id}, so its attainment is null`).toBeNull();
        } else {
          expect(attainment, `attainment for ${c.rule.id}`).not.toBeNull();
          expect(attainment.actual).toBeCloseTo(c.expect.attainment.actual, 4);
          expect(attainment.failThreshold).toBe(c.expect.attainment.failThreshold);
          expect(attainment.consumed).toBeCloseTo(c.expect.attainment.consumed, 4);
          expect(attainment.breached, `attainment.breached for ${c.rule.id}`).toBe(c.expect.attainment.breached);
        }
      }
      // The name the rule is judged under, whatever the file's spelling (RB8): MetricRef's
      // canonical one, which GatePayloadBuilder ships, reached through the page's own parser.
      if (c.expect.metric !== undefined) {
        const name = await page.evaluate(
          (rule) => (window as any).WIR.asserts._ruleFromJson(rule, 0).metric, c.rule);
        expect(name, `the name ${JSON.stringify(c.rule.metric)} is judged under`).toBe(c.expect.metric);
      }
    });
  }

  for (const g of vectors.gateCases) {
    test(`gate — ${g.name}`, async ({ page }) => {
      await loadClean(page, 'E25');

      const byId = new Map<string, any>();
      vectors.cases.forEach(c => byId.set(c.rule.id || 'rule', ruleFrom(c.rule)));
      const rules = g.ruleIds.map((id: string) => {
        const rule = byId.get(id);
        if (!rule) throw new Error(`gate case names an unknown rule id: ${id}`);
        return rule;
      });

      const actual = await page.evaluate(
        ({ rules, data }) => (window as any).WIR.asserts.evaluate(rules, data),
        { rules, data: payloadFrom(vectors) });

      const gate = actual.gate;
      expect(gate.status).toBe(g.expect.status === null ? null : g.expect.status);
      for (const key of ['declared', 'evaluated', 'passed', 'warned', 'failed']) {
        expect(gate[key], `${key} for "${g.name}"`).toBe(g.expect[key]);
      }
      // Every key the vector expects, and every key the gate carries. The block is one shape
      // for client and server gates alike, so the three server-only reasons are present here
      // as zeroes — a key that appears only when non-zero is a key a sum check cannot rely on.
      for (const key of Object.keys(g.expect.notJudged)) {
        expect(gate.notJudged[key], `notJudged.${key} for "${g.name}"`)
          .toBe(g.expect.notJudged[key]);
      }
      for (const key of ['sourceFailed', 'lowCoverage', 'loadNotAchieved']) {
        expect(gate.notJudged[key], `server-only notJudged.${key} on a client gate "${g.name}"`)
          .toBe(0);
      }

      // Both arithmetics, exactly as the Java suite asserts them: every declared rule lands in
      // one bucket, and every not-judged rule lands in one reason. A consumer can check the
      // gate's own sums rather than trusting them. The reason sum is over every key the gate
      // carries, not a hand-written four — that list was how a fifth reason went uncounted.
      expect(gate.passed + gate.warned + gate.failed + gate.notJudged.total).toBe(gate.declared);
      const reasonSum = Object.keys(gate.notJudged)
        .filter(k => k !== 'total')
        .reduce((sum, k) => sum + gate.notJudged[k], 0);
      expect(reasonSum, `reasons must sum to total for "${g.name}"`).toBe(gate.notJudged.total);
    });
  }

  test('[U] every target case is read as AssertionRuleMapper reads it (M7d X)', async ({ page }) => {
    // How a TARGET is read, and so what an id-less rule is called: a blank or empty value is no
    // form, two forms are INVALID, scope is case-insensitive. MapperParityTest runs the same cases
    // through the mapper and asserts-parity.test.js through ruleFromJson in node; this runs the
    // ruleFromJson a tester's page loads.
    const cases = (vectors as any).targetCases as any[];
    expect(cases.length, 'the target cases are gone').toBeGreaterThanOrEqual(10);
    await loadClean(page, 'E25');
    const read = await page.evaluate((cs) => cs.map((c: any) => {
      const A = (window as any).WIR.asserts;
      const rule = A._ruleFromJson(c.rule, c.index || 0);
      return { kind: rule.target.kind, id: rule.id, problem: rule.target.kind === 'invalid' ? rule.target.text : null,
               why: A._whyUnusable(rule) };
    }), cases);
    cases.forEach((c, i) => {
      expect(read[i].kind, `target kind for "${c.name}"`).toBe(c.expect.kind);
      expect(read[i].id, `the id for "${c.name}"`).toBe(c.expect.id);
      if (c.expect.problem !== undefined) {
        expect(read[i].problem, `the refusal for "${c.name}"`).toBe(c.expect.problem);
        expect(read[i].why, `the INVALID_RULE detail for "${c.name}"`).toBe(c.expect.problem);
      }
    });
  });

  test('[U] a refusal is the whole INVALID_RULE sentence Java writes, character for character (M7d X review)', async ({ page }) => {
    // The whole INVALID_RULE sentence: the unreachable-band refusal wrote its limit with the
    // table's display formatter — '< -1,234 ms' for -1234.5, '< -0.2 /s' for -0.25, grouped by the
    // reader's locale — where the CI/CD JSON wrote Predicate.describe's '< -1234.5 ms'; and every
    // unreadable band lacked Usability.unreadableBand's ending. Here the unit comes from the page's
    // own assertMetrics vocabulary, which the node runner has to be handed; MapperParityTest runs
    // the same cases through the mapper and Usability.refusal. A bare 1e400 in the vector file is
    // Infinity after JSON.parse, and page.evaluate hands it over as the number it is.
    const cases = (vectors as any).detailCases as any[];
    expect(cases.length, 'the detail cases are gone').toBeGreaterThanOrEqual(18);
    await loadClean(page, 'E25');
    const read = await page.evaluate((cs) => cs.map((c: any) => {
      const A = (window as any).WIR.asserts;
      const rule = A._ruleFromJson(c.rule, c.index || 0);
      const result = A.evaluate([rule], (window as any).globalChartData).rules[0];
      return { why: A._whyUnusable(rule), reason: result.notJudged, detail: result.notJudgedDetail };
    }), cases);
    cases.forEach((c, i) => {
      expect(read[i].why, `whyUnusable for "${c.name}"`).toBe(c.expect.detail);
      expect(read[i].reason, `refused: "${c.name}"`).toBe('INVALID_RULE');
      expect(read[i].detail, `the evaluator's detail for "${c.name}"`).toBe(c.expect.detail);
    });
  });

  test('[U] a legacy loadValidity block is migrated as the run migrates it (M5 exploratory F1)',
    async ({ page }) => {
      // The page read a file's assertions[] and nothing else, so Open file… on E25's own
      // annotations file showed one rule fewer than the report, and a Download dropped the load
      // check. MapperParityTest runs the same documents through AnnotationLoader and
      // LoadValidityMigration; this runs the migration a tester's page loads. By value: Java
      // writes every unset field as null.
      //
      // L3 / CN26: a non-finite target or tolerance builds the rule and refuses it in a sentence
      // naming the field. Its band is written as documentOf writes it, as Jackson's text ("NaN"),
      // through WIR.javaText.jacksonCopy — JSON.stringify would write null. The refusal rides the
      // migrated entry where no JSON can carry it, so the rule is parsed (ruleFromJson) inside the
      // page call, and its whyUnusable is the vector's `detail`.
      const cases = (vectors as any).migrationCases as any[];
      expect(cases.length, 'the migration cases are gone').toBeGreaterThanOrEqual(18);
      await loadClean(page, 'E25');
      const read = await page.evaluate((cs) => cs.map((c: any) => {
        const W = (window as any).WIR;
        const entries = W.asserts._migrateLoadValidity(c.document);
        const last = entries.length - 1;
        return {
          migrated: JSON.parse(JSON.stringify(W.javaText.jacksonCopy(entries))),
          detail: last >= 0 ? W.asserts._whyUnusable(W.asserts._ruleFromJson(entries[last], last)) : null,
        };
      }), cases);
      const migrated = read.map((r: any) => r.migrated);
      const byValue = (v: any): any => {
        if (v === null || v === undefined) return null;
        if (Array.isArray(v)) return v.map(byValue);
        if (typeof v !== 'object') return v;
        const out: any = {};
        Object.keys(v).sort().forEach((k) => { if (v[k] !== null && v[k] !== undefined) out[k] = byValue(v[k]); });
        return out;
      };
      cases.forEach((c, i) => {
        expect(byValue(migrated[i]), `the assertions "${c.name}" ends with`).toEqual(byValue(c.expect.assertions));
        if (c.expect.detail !== undefined) {
          expect(read[i].detail, `the migrated rule's INVALID_RULE detail for "${c.name}"`).toBe(c.expect.detail);
        }
      });
    });

  test('[U] a band written as text is read as Double.parseDouble reads it (L2, CN25)', async ({ page }) => {
    // AssertionRuleMapper reads a quoted band through ServerMetricsMapper.asNumber: String.trim,
    // Double.parseDouble, NaN and the infinities refused. The page's bandNumber read it with
    // trim() and Number(), so "0x10" was a band of 16 here and a CONFIG ERROR in the run, and an
    // unedited Save turned the row into a verdict. MapperParityTest runs the same cases through
    // the mapper, asserts-parity.test.js through ruleFromJson in node; this runs the ruleFromJson
    // a tester's page loads.
    const cases = (vectors as any).bandTextCases as any[];
    expect(cases.length, 'the band-text cases are gone').toBeGreaterThanOrEqual(20);
    await loadClean(page, 'E25');
    const read = await page.evaluate((cs) => cs.map((c: any) => {
      const A = (window as any).WIR.asserts;
      const rule = A._ruleFromJson({ id: 'band-text', target: { sampler: 'GET /api/products' },
        metric: 'mean', fail: { gt: c.text } }, 0);
      return { problems: rule.declarationProblems.length, fail: rule.fail === undefined ? null : rule.fail,
               why: A._whyUnusable(rule) };
    }), cases);
    cases.forEach((c, i) => {
      if (c.expect.value !== undefined) {
        expect(read[i].problems, `Java reads ${JSON.stringify(c.text)}: ${read[i].why}`).toBe(0);
        expect(read[i].fail, `the band read from ${JSON.stringify(c.text)}`).toEqual({ op: 'gt', value: c.expect.value });
      } else {
        expect(c.expect.refused).toBe(true);
        expect(read[i].fail, `a band was read from ${JSON.stringify(c.text)}`).toBeNull();
        // ServerMetricsMapper.describe quotes the text as it is, nothing escaped.
        expect(read[i].why, `the refusal of ${JSON.stringify(c.text)}`).toBe('fail gt must be a number, not the text "'
          + c.text + '" — so this rule declares a limit it cannot apply, and would report PASS'
          + ' without ever testing fail');
      }
    });
  });

  test('an exclude exempts the same sampler in the browser as in the engine',
      async ({ page }) => {
    // The end-to-end version of the vectors above, against a real report. E12 declares blanket
    // rules over `*` that exclude the samplers carrying their own rule, so both evaluators have
    // to agree on what an exclude removes. They did not once: 1.4.14 wrote excludes as
    // `regex:\Q…\E`, which Java reads and JavaScript cannot, so the client judged the excluded
    // samplers and re-scored the rule — PASS from the engine, FAIL in the report.
    await loadClean(page, 'E12');

    const drift = await page.evaluate(() => {
      const data = (window as any).globalChartData;
      const recomputed = (window as any).WIR.asserts.evaluate(data.assertions, data);
      return data.assertions
        .map((r: any, i: number) => ({
          id: r.id,
          bakedStatus: r.status, clientStatus: recomputed.rules[i].status,
          bakedMatched: r.matched, clientMatched: recomputed.rules[i].matched,
        }))
        .filter((r: any) => r.bakedStatus !== r.clientStatus || r.bakedMatched !== r.clientMatched);
    });

    expect(drift, `the client disagrees with the engine: ${JSON.stringify(drift)}`).toEqual([]);
  });

  test('the client reproduces the verdicts the report was built with', async ({ page }) => {
    // The end-to-end version of the same claim, against a real generated report rather than a
    // synthetic fixture: re-evaluating E25's own rules against E25's own data must reproduce
    // exactly the statuses the Java engine baked into the payload.
    await loadClean(page, 'E25');

    const comparison = await page.evaluate(() => {
      const data = (window as any).globalChartData;
      const recomputed = (window as any).WIR.asserts.evaluate(data.assertions, data);
      return data.assertions.map((r: any, i: number) => ({
        id: r.id,
        baked: r.status,
        recomputed: recomputed.rules[i].status,
      }));
    });

    expect(comparison.length).toBeGreaterThan(5);
    for (const row of comparison) {
      expect(row.recomputed, `rule ${row.id} re-evaluates differently in the browser`)
        .toBe(row.baked);
    }
  });
});
