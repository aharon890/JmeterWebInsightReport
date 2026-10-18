package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attainment answers "how close was it?", which the outputs could not answer for a passing rule.
 *
 * <p>Everything here goes through the real evaluator: attainment is derived from a
 * {@link RuleResult}, and hand-building one would let a mistake about which match is "worst"
 * pass unnoticed.
 */
class AttainmentTest {

    private static SamplerStatistics stat(String name, double p95, double throughput) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(10);
        s.setPercentile95(p95);
        s.setThroughput(throughput);
        return s;
    }

    private static ReportData data(SamplerStatistics... stats) {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(stats)));
        return d;
    }

    private static RuleResult evaluate(AssertionRule rule, ReportData data) {
        List<RuleResult> results =
                new AssertionEvaluator().evaluate(Arrays.asList(rule), data).ruleResults();
        return results.get(0);
    }

    private static AssertionRule failing(String metric, Predicate.Op op, double limit) {
        return AssertionRule.builder(AssertionTarget.match("*", null), metric)
                .id("r").fail(Predicate.of(op, limit)).build();
    }

    /** The headline case: a rule that PASSED still reports its budget and its headroom. */
    @Test
    void aPassingRuleReportsItsBudget() {
        Attainment a = Attainment.of(
                evaluate(failing("p95", Predicate.Op.GT, 800), data(stat("A", 712, 5))));

        assertNotNull(a, "a passing banded rule has a budget and must report it");
        assertEquals(712, a.actual(), 0.001);
        assertEquals(800, a.failThreshold(), 0.001);
        assertEquals(0.89, a.consumed(), 0.005);
        assertFalse(a.breached());
    }

    /** Over the line, consumed goes past 1 — clamping would hide how badly. */
    @Test
    void aBreachReportsHowFarPastTheLimitItWent() {
        Attainment a = Attainment.of(
                evaluate(failing("p95", Predicate.Op.GT, 800), data(stat("A", 2400, 5))));

        assertEquals(3.0, a.consumed(), 0.001);
        assertTrue(a.breached());
    }

    /**
     * Direction comes from the operator, both in which match is worst and in which way the
     * budget is consumed. For throughput, worse means smaller.
     */
    @Test
    void aDownwardBandTakesTheSmallestMatchAndInvertsTheRatio() {
        Attainment a = Attainment.of(
                evaluate(failing("throughput", Predicate.Op.LT, 30),
                        data(stat("A", 100, 90), stat("B", 100, 45))));

        assertEquals(45, a.actual(), 0.001, "the worst throughput is the lowest, not the highest");
        assertEquals(30, a.failThreshold(), 0.001);
        assertEquals(30.0 / 45.0, a.consumed(), 0.001);
        assertFalse(a.breached());
    }

    /** An upward band takes the largest match — the one nearest the ceiling. */
    @Test
    void anUpwardBandTakesTheLargestMatch() {
        Attainment a = Attainment.of(
                evaluate(failing("p95", Predicate.Op.GT, 800),
                        data(stat("A", 120, 5), stat("B", 640, 5))));

        assertEquals(640, a.actual(), 0.001);
    }

    /**
     * Absent, not zeroed. Each of these measured no budget, and {@code consumed: 0} would read
     * as "comfortably inside" for a rule that checked nothing.
     */
    @Test
    void theQuestionIsRefusedRatherThanAnsweredWithZero() {
        assertNull(Attainment.of(evaluate(
                AssertionRule.builder(AssertionTarget.match("*", null), "p95")
                        .id("warnOnly").warn(Predicate.of(Predicate.Op.GT, 100)).build(),
                data(stat("A", 200, 5)))),
                "no fail band means no line to be measured against");

        assertNull(Attainment.of(evaluate(failing("p95", Predicate.Op.GT, 800), data())),
                "a rule that matched nothing measured nothing");

        assertNull(Attainment.of(evaluate(
                failing("notAMetric", Predicate.Op.GT, 800), data(stat("A", 100, 5)))),
                "an INVALID rule never produced a value");
    }

    /** A zero divisor would put Infinity or NaN into a machine-readable contract. */
    @Test
    void aZeroDivisorIsRefusedRatherThanEmittingInfinity() {
        assertNull(Attainment.of(
                evaluate(failing("p95", Predicate.Op.GT, 0), data(stat("A", 100, 5)))),
                "a threshold of zero has no fraction of itself");

        assertNull(Attainment.of(
                evaluate(failing("throughput", Predicate.Op.LT, 30), data(stat("A", 100, 0)))),
                "a measured zero cannot divide the threshold");
    }

    // --- L13: one "worst", read by the JUnit message and by attainment alike ---

    /**
     * Two FAILs of one rule: the JUnit message named {@code worstJudged()}, the FIRST of them, and
     * attainment the largest — "p95 150ms on A" in the XML beside {@code attainment.actual: 300} in
     * the JSON, two artifacts of one run naming two different matches as the worst.
     * {@code worstJudged()} is the one definition now: the highest severity, then the furthest past
     * the band that status was decided against, the earliest on an exact tie.
     */
    @Test
    void theWorstJudgedMatchIsTheOneAttainmentDescribes() {
        RuleResult up = evaluate(failing("p95", Predicate.Op.GT, 100),
                data(stat("A", 150, 5), stat("B", 300, 5), stat("C", 300, 5)));
        assertEquals("B", up.worstJudged().samplerName(),
                "two FAILs: the furthest past an upward band, the earlier of an exact tie");
        assertEquals(up.worstJudged().actual(), Attainment.of(up).actual(), 0.0);

        RuleResult down = evaluate(failing("throughput", Predicate.Op.LT, 30),
                data(stat("A", 100, 20), stat("B", 100, 10)));
        assertEquals("B", down.worstJudged().samplerName(), "a downward band: the smallest is the worst");
        assertEquals(10, Attainment.of(down).actual(), 0.0);

        RuleResult passing = evaluate(failing("p95", Predicate.Op.GT, 1000),
                data(stat("A", 150, 5), stat("B", 300, 5)));
        assertEquals("B", passing.worstJudged().samplerName(), "all PASS: the nearest its band");
    }

    /**
     * Severity first, so the match the rule's status came from: a warn band pointing away from the
     * fail band (Usability leaves that shape alone) WARNs on A while B sits nearer the fail band and
     * passes. The message names A, because a WARN naming a PASS would contradict itself, and
     * attainment describes the same match rather than a second "worst" of its own.
     */
    @Test
    void severityDecidesTheWorstBeforeTheValueDoes() {
        RuleResult corridor = evaluate(
                AssertionRule.builder(AssertionTarget.match("*", null), "p95").id("corridor")
                        .warn(Predicate.of(Predicate.Op.LT, 10)).fail(Predicate.of(Predicate.Op.GT, 100)).build(),
                data(stat("A", 5, 5), stat("B", 50, 5)));
        assertEquals(AssertionStatus.WARN, corridor.status());
        assertEquals("A", corridor.worstJudged().samplerName());
        Attainment a = Attainment.of(corridor);
        assertEquals(5, a.actual(), 0.0);
        assertEquals(0.05, a.consumed(), 1e-12);
        assertFalse(a.breached());
    }

    /**
     * NIT-2: within one status, the furthest past the band that status was decided against. Two
     * WARNs of a corridor, at 2 and 8 under {@code warn lt 10}, beside a PASS at 50: the worst is the
     * 2, furthest under the warn band. It took the fail band's direction ({@code gt 100}) for every
     * match and named the 8 — the WARN nearest to passing — in the JUnit message and in attainment.
     */
    @Test
    void withinAStatusTheWorstIsFurthestPastTheBandThatStatusWasDecidedAgainst() {
        RuleResult corridor = evaluate(
                AssertionRule.builder(AssertionTarget.match("*", null), "p95").id("corridor")
                        .warn(Predicate.of(Predicate.Op.LT, 10)).fail(Predicate.of(Predicate.Op.GT, 100)).build(),
                data(stat("A", 8, 5), stat("B", 2, 5), stat("C", 50, 5)));
        assertEquals(AssertionStatus.WARN, corridor.status());
        assertEquals("B", corridor.worstJudged().samplerName(), "a WARN is measured by the warn band");
        Attainment a = Attainment.of(corridor);
        assertEquals(2, a.actual(), 0.0);
        assertEquals(0.02, a.consumed(), 1e-12);
        assertFalse(a.breached());

        // A FAIL is still measured by the fail band, whichever way the warn band points.
        RuleResult failing = evaluate(
                AssertionRule.builder(AssertionTarget.match("*", null), "p95").id("corridor-fail")
                        .warn(Predicate.of(Predicate.Op.LT, 10)).fail(Predicate.of(Predicate.Op.GT, 100)).build(),
                data(stat("A", 150, 5), stat("B", 300, 5), stat("C", 2, 5)));
        assertEquals(AssertionStatus.FAIL, failing.status());
        assertEquals("B", failing.worstJudged().samplerName(), "a FAIL is measured by the fail band");
    }
}
