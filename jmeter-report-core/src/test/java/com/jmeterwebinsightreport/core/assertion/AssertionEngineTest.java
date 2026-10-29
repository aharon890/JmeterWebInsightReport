package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour the parity vectors cannot express — parsing, naming, tag selection, and the pieces
 * that only exist Java-side.
 */
class AssertionEngineTest {

    private static SamplerStatistics stat(String name, long count, double p95) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(count);
        s.setPercentile95(p95);
        s.setPercentile90(p95 * 0.9);
        s.setPercentile99(p95 * 1.3);
        s.setMedianResponseTime(p95 * 0.6);
        s.setMeanResponseTime(p95 * 0.7);
        s.setThroughput(5);
        s.setApdexScore(0.9);
        return s;
    }

    private static ReportData data(SamplerStatistics... stats) {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(stats)));
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        d.setMetadata(m);
        return d;
    }

    private static AssertionRule failIfP95Over(String id, AssertionTarget target, double limit) {
        return AssertionRule.builder(target, "p95").id(id)
                .fail(Predicate.of(Predicate.Op.GT, limit)).build();
    }

    // ---------------------------------------------------------------- metric parsing

    @Test
    void percentileSpellingsThatMeanTheSameThingParseTheSame() {
        assertEquals("p95", MetricRef.parse("p95").canonicalName());
        assertEquals("p95", MetricRef.parse("p95.0").canonicalName());
        assertEquals("p95", MetricRef.parse("P95").canonicalName());
        // "median" canonicalises to p50 rather than keeping its own name. It used to keep it,
        // and equivalence was only about the value — which was enough for the engine and not
        // enough for anything that compares metric names. The report's charts select the rules
        // to draw with `rule.metric === chartedMetric(...)`: of the two spellings of one
        // quantile, whichever did not match the chart's spelling silently drew no line.
        assertEquals("p50", MetricRef.parse("median").canonicalName());
        assertEquals("p50", MetricRef.parse("p50").canonicalName());
        assertEquals(MetricRef.parse("p50").percentile(), MetricRef.parse("median").percentile());
        assertEquals("p99.9", MetricRef.parse("p99.9").canonicalName());
        assertEquals(99.9, MetricRef.parse("p99.9").percentile(), 0.0001);
    }

    @Test
    void whatIsNotAPercentileIsNotTreatedAsOne() {
        // p0 and p100 are min and max, which have their own names; a t-digest cannot answer
        // either reliably. "pineapple" is the case that must not become "p" + garbage.
        assertNull(MetricRef.parse("p0"));
        assertNull(MetricRef.parse("p100"));
        assertNull(MetricRef.parse("p"));
        assertNull(MetricRef.parse("pineapple"));
        assertNull(MetricRef.parse("p-5"));
        assertNull(MetricRef.parse(""));
        assertNull(MetricRef.parse(null));
    }

    @Test
    void aNearMissMetricNameGetsASuggestionAndAWildGuessDoesNot() {
        assertEquals("p90", MetricRef.nearestName("p90x", false));
        assertEquals("apdex", MetricRef.nearestName("apdexx", false));
        // Suggesting anything for this would undermine the suggestions that are right.
        assertNull(MetricRef.nearestName("container_cpu_usage_seconds", false));
    }

    @Test
    void scopeDecidesWhichMetricsExist() {
        assertTrue(MetricRef.parse("maxThreads").validForOverall());
        assertFalse(MetricRef.parse("maxThreads").validForSampler());
        assertTrue(MetricRef.parse("stdDev").validForSampler());
        assertFalse(MetricRef.parse("stdDev").validForOverall());
        // Percentiles work in both scopes.
        assertTrue(MetricRef.parse("p99.9").validForOverall());
        assertTrue(MetricRef.parse("p99.9").validForSampler());
    }

    // ---------------------------------------------------------------- globbing

    @Test
    void everythingExceptTheStarIsLiteral() {
        // Sampler names are full of regex metacharacters — {id}, ?, +, & — and a tester who
        // types one means it literally. Handing the string to Pattern would reinterpret half
        // the REST-style names in a typical plan.
        assertTrue(AssertionTarget.compile("GET /api/v2/items/{id}?full=true&x=1")
                .matcher("GET /api/v2/items/{id}?full=true&x=1").matches());
        assertFalse(AssertionTarget.compile("GET /api/items/{id}")
                .matcher("GET /api/items/9").matches());
        assertTrue(AssertionTarget.compile("POST /api/*").matcher("POST /api/checkout").matches());
        assertFalse(AssertionTarget.compile("POST /api/*").matcher("GET /api/checkout").matches());
        assertTrue(AssertionTarget.compile("*").matcher("anything at all").matches());
    }

    @Test
    void aStarMatchesAcrossSlashesBecauseSamplerNamesAreNotPaths() {
        // A tester writing "POST /api/*" means everything under /api, not one segment of it.
        assertTrue(AssertionTarget.compile("POST /api/*")
                .matcher("POST /api/v2/orders/checkout").matches());
    }

    @Test
    void theRegexPrefixOptsOut() {
        assertTrue(AssertionTarget.compile("regex:^GET /(search|browse)")
                .matcher("GET /search").find());
        assertTrue(AssertionTarget.compile("regex:^GET .*").matcher("GET /anything").matches());
    }

    // ---------------------------------------------------------------- ids

    @Test
    void anOmittedIdIsDerivedFromTheTargetAndMetric() {
        // The verb is kept: GET /checkout and POST /checkout are different samplers, and two
        // rules whose ids collided would make the JUnit output ambiguous.
        assertEquals("post-checkout-p95",
                AssertionRule.builder(AssertionTarget.sampler("POST /checkout"), "p95")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build().id());
        assertEquals("run-throughput",
                AssertionRule.builder(AssertionTarget.overall(), "throughput")
                        .fail(Predicate.of(Predicate.Op.LT, 1)).build().id());
        // The dot would make the id awkward in a JUnit testcase name.
        assertEquals("search-p999",
                AssertionRule.builder(AssertionTarget.sampler("search"), "p99.9")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build().id());
    }

    @Test
    void aDerivedIdKeepsTheTailOfALongName() {
        // A REST path's tail identifies it; its host prefix does not. Ids appear in JUnit
        // testcase names, where length is a real cost.
        String id = AssertionRule.builder(AssertionTarget.sampler(
                        "POST /api/v2/enterprise/checkout/payment-gateway/process-transaction"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build().id();
        assertTrue(id.contains("process") || id.contains("transaction"),
                "expected the tail to survive, got: " + id);
        assertTrue(id.length() <= 40, "id is too long for a testcase name: " + id);
    }

    // ---------------------------------------------------------------- percentiles from digests

    @Test
    void aStoredPercentileIsResolvedAndTheDistributionAgreesWithIt() {
        // The rule was written when the overall merge force-compressed each sampler's t-digest, so
        // the digest answered p99 slightly differently from the value stored before that merge.
        // The distributions are exact counts now (B2): the gate still judges the stored number the
        // report displays, and the retained distribution answers the very same number.
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample("S", i, true, 500, 100, 1_700_000_000_000L + i, 5, 10);
        }
        ReportData d = engine.buildReportData("T");
        SamplerStatistics stat = d.getSamplerStatistics().get(0);

        double resolved = MetricRef.parse("p99").valueFor(stat, d);
        assertEquals(stat.getPercentile99(), resolved, 0.0,
                "p99 must come from the statistics, exactly");
        assertEquals(resolved, d.percentileSource("S").percentile(99), 0.0,
                "the retained distribution answers what the table shows");
    }

    @Test
    void aPercentileWithNoStoredValueComesFromTheDigest() {
        LiveStatisticsEngine engine = new LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample("S", i, true, 500, 100, 1_700_000_000_000L + i, 5, 10);
        }
        ReportData d = engine.buildReportData("T");
        // rank round(1000 * 0.999f) = 999: the 999th of 1..1000, exactly
        assertEquals(999.0, MetricRef.parse("p99.9").valueFor(d.getSamplerStatistics().get(0), d), 0.0);
    }

    @Test
    void aPercentileNothingCanAnswerIsNotJudgedRatherThanPassing() {
        // Hand-assembled data has no digest, so p99.9 is unanswerable. Reporting PASS would
        // mean a rule that checked nothing reported success.
        //
        // NO_VALUE, not INVALID_RULE: the rule is well-formed and would work against a run that
        // carried a digest. The fix is the run, not the file, and the two reasons are counted
        // apart so nobody goes looking in the wrong one.
        ReportData d = data(stat("A", 100, 500));
        AssertionRule tail = AssertionRule.builder(AssertionTarget.sampler("A"), "p99.9")
                .id("tail").fail(Predicate.of(Predicate.Op.GT, 100)).build();

        GateSummary gate = new AssertionEvaluator().evaluate(Collections.singletonList(tail), d);

        assertEquals(AssertionStatus.NOT_JUDGED, gate.ruleResults().get(0).status());
        assertEquals(NotJudged.NO_VALUE, gate.ruleResults().get(0).notJudged());
        assertEquals(1, gate.noValue());
        assertEquals(0, gate.invalidRule(), "the rule itself is fine");
        // The match survives, carrying its dash. A rule that selected a sampler and could not
        // measure it is not the same as one that selected nothing, and the report shows both.
        assertEquals(1, gate.ruleResults().get(0).matchedTotal());
    }

    // ---------------------------------------------------------------- results

    @Test
    void everyMatchIsKeptIncludingThePassingOnes() {
        // A verdict alone is lossy: "the rule failed" does not say which of four samplers
        // breached, nor how close the others were, which is exactly what moves a threshold.
        ReportData d = data(stat("GET /a", 100, 100), stat("GET /b", 100, 900), stat("GET /c", 100, 200));
        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(failIfP95Over("r", AssertionTarget.match("GET /*", null), 500)), d);

        RuleResult r = gate.ruleResults().get(0);
        assertEquals(3, r.matches().size());
        assertEquals(AssertionStatus.FAIL, r.status());
        assertEquals("GET /b", r.worst().samplerName());
        assertEquals(900.0, r.worst().actual(), 0.0001);
        assertNotNull(r.worst().breachedBand());
    }

    @Test
    void cappingKeepsTheWorstMatchesAndRemembersTheRealTotal() {
        // A match:"*" rule over a 500-sampler run would otherwise put 500 result objects per
        // rule into the HTML payload. Truncating silently would imply the rule judged three
        // things when it judged five hundred.
        List<SamplerStatistics> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(stat("S" + i, 100, i * 100));
        }
        ReportData d = data(many.toArray(new SamplerStatistics[0]));
        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(failIfP95Over("r", AssertionTarget.match("S*", null), 1500)), d);

        RuleResult capped = gate.ruleResults().get(0).cappedTo(3);
        assertEquals(3, capped.matches().size());
        assertEquals(20, capped.matchedTotal(), "the true total survives the cap");
        for (RuleResult.Match m : capped.matches()) {
            assertEquals(AssertionStatus.FAIL, m.status(), "the worst matches are the ones kept");
        }
    }

    @Test
    void anEmptyRuleSetIsNotAPass() {
        // "No gate configured" and "the gate passed" are different facts, and conflating them
        // is how a typo'd annotations path silently turns the gate off.
        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.<AssertionRule>emptyList(), data(stat("A", 10, 10)));
        assertNull(gate.status());
        assertFalse(gate.hasGate());
        assertEquals(0, gate.declared());
    }

    /**
     * The arithmetic the class comment promises: every rule lands in exactly one bucket.
     *
     * <p>{@code passed + warned + failed + disabled + skipped + invalid == declared}
     * is what lets a pipeline check its own reading of the gate instead of trusting that nothing
     * fell between the counters. It used to be maintained by a hand-written switch with a
     * {@code default: break;} — so a status added to the enum would have been counted nowhere and
     * broken this silently. GateSummary.of counts by EnumMap now, and this asserts the promise
     * over a rule set that produces every bucket at once.
     */
    @Test
    void everyRuleLandsInExactlyOneBucket() {
        List<AssertionRule> rules = Arrays.asList(
                AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id("passes")
                        .fail(Predicate.of(Predicate.Op.GT, 100_000)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id("warns")
                        .warn(Predicate.of(Predicate.Op.GT, 1))
                        .fail(Predicate.of(Predicate.Op.GT, 100_000)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id("fails")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id("parked")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).enabled(false).build(),
                // Unmatched, so NOT_JUDGED / NO_MATCH — no policy to declare any more.
                AssertionRule.builder(AssertionTarget.sampler("Nope"), "p95").id("skips")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "notAMetric").id("broken")
                        .fail(Predicate.of(Predicate.Op.GT, 1)).build());

        GateSummary gate = new AssertionEvaluator().evaluate(rules, data(stat("A", 10, 10)));

        int sum = gate.passed() + gate.warned() + gate.failed()
                + gate.notJudged();
        assertEquals(gate.declared(), sum,
                "a rule fell between the counters — the gate block would not add up");
        assertEquals(rules.size(), gate.declared());
        // Every bucket is genuinely exercised, or the invariant above is vacuous.
        assertEquals(1, gate.passed());
        assertEquals(1, gate.warned());
        assertEquals(1, gate.failed());
        assertEquals(1, gate.disabled());
        assertEquals(1, gate.noMatch());
        assertEquals(1, gate.invalidRule());
        // The second arithmetic the class comment promises: the reasons account for every
        // not-judged rule, so one added later cannot fall between the buckets. Summed from the
        // enum, as the parity and output tests do, so the three copies of this invariant pin
        // the same thing.
        int reasonSum = 0;
        for (NotJudged r : NotJudged.values()) {
            reasonSum += gate.notJudged(r);
        }
        assertEquals(gate.notJudged(), reasonSum);
        // Disabled is excluded from `evaluated`; skipped is not — it was asked and answered.
        assertEquals(rules.size() - 1, gate.evaluated());
    }

    // ------------------------------------------- metrics this run could not measure

    /**
     * A JTL saved without JMeter's optional Connect and Latency columns must make a rule about
     * them NOT_JUDGED, never PASS.
     *
     * <p>This was the cardinal sin, one layer below where the engine defends against it.
     * {@code SamplerAggregator} only assigns these when its guarded count is above zero, and the
     * fields were primitive {@code double}, so an absent column left 0.0 — a legitimate-looking
     * measurement. {@code fail: {gt: 50}} then passed on every run of every environment whose
     * saveservice configuration omitted the column, silently and forever. NaN is what
     * {@code MetricRef} already promised ("zero is a legitimate measurement and a predicate would
     * happily judge it"); this proves the promise now reaches the metric.
     */
    @Test
    void aMetricTheRunNeverMeasuredIsInvalidRatherThanPassing() {
        SamplerStatistics noOptionalColumns = stat("A", 10, 100);
        assertTrue(Double.isNaN(noOptionalColumns.getMeanConnectTime()),
                "an unmeasured connect time must not read as 0.0");

        GateSummary gate = new AssertionEvaluator().evaluate(Arrays.asList(
                AssertionRule.builder(AssertionTarget.sampler("A"), "connectTime").id("connect")
                        .fail(Predicate.of(Predicate.Op.GT, 50)).build(),
                AssertionRule.builder(AssertionTarget.sampler("A"), "latency").id("ttfb")
                        .fail(Predicate.of(Predicate.Op.GT, 50)).build()),
                data(noOptionalColumns));

        assertEquals(AssertionStatus.NOT_JUDGED, statusOf(gate, "connect"));
        assertEquals(AssertionStatus.NOT_JUDGED, statusOf(gate, "ttfb"));
        assertEquals(NotJudged.NO_VALUE, resultOf(gate, "connect").notJudged(),
                "the rule is fine; the RUN could not answer it");
        assertNull(gate.status(),
                "nothing was measured, so the run must not report success either");
        assertEquals(2, gate.noValue());
    }

    /** And a run that DID measure them is judged normally — the fix must not blind the metric. */
    @Test
    void aMeasuredConnectTimeIsStillJudged() {
        SamplerStatistics measured = stat("A", 10, 100);
        measured.setMeanConnectTime(80);

        GateSummary gate = new AssertionEvaluator().evaluate(Arrays.asList(
                AssertionRule.builder(AssertionTarget.sampler("A"), "connectTime").id("connect")
                        .fail(Predicate.of(Predicate.Op.GT, 50)).build()),
                data(measured));

        assertEquals(AssertionStatus.FAIL, statusOf(gate, "connect"));
    }

    /**
     * A sampler literally named "Whole run" and the whole-run verdict are two verdicts (CORE-1).
     *
     * <p>A whole-run match was keyed by its display text, so the sampler table's verdict for a
     * sampler called "Whole run" was the worse of its own rules and every scope-overall rule: a
     * passing sampler read FAIL in assertStatus and JUnit because the run's thread count breached.
     */
    @Test
    void aSamplerNamedWholeRunIsNotTheWholeRun() {
        ReportData d = data(stat("Whole run", 10, 100), stat("A", 10, 100));
        GateSummary gate = new AssertionEvaluator().evaluate(Arrays.asList(
                AssertionRule.builder(AssertionTarget.sampler("Whole run"), "p95").id("sampler")
                        .fail(Predicate.of(Predicate.Op.GT, 1000)).build(),
                AssertionRule.builder(AssertionTarget.overall(), "maxThreads").id("run")
                        .fail(Predicate.of(Predicate.Op.GT, 5)).build()), d);

        assertEquals(AssertionStatus.PASS, statusOf(gate, "sampler"), "precondition");
        assertEquals(AssertionStatus.FAIL, statusOf(gate, "run"), "precondition");

        SamplerVerdicts verdicts = SamplerVerdicts.from(gate);
        assertEquals(AssertionStatus.PASS, verdicts.get("Whole run").getOverallStatus(),
                "the sampler took the whole run's FAIL");
        assertEquals(AssertionStatus.FAIL,
                verdicts.get(AssertionTarget.OVERALL_KEY).getOverallStatus(),
                "the whole run's verdict is kept under its own key");
        assertEquals(2, verdicts.bySampler().size(), verdicts.bySampler().keySet().toString());
        // The display text is unchanged: the run still reads "Whole run" wherever it is shown.
        assertEquals("Whole run", resultOf(gate, "run").matches().get(0).label());
    }

    private static AssertionStatus statusOf(GateSummary gate, String ruleId) {
        return resultOf(gate, ruleId).status();
    }

    private static RuleResult resultOf(GateSummary gate, String ruleId) {
        for (RuleResult r : gate.ruleResults()) {
            if (r.rule().id().equals(ruleId)) {
                return r;
            }
        }
        throw new AssertionError("no result for rule " + ruleId);
    }
}
