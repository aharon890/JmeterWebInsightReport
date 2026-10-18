package com.jmeterwebinsightreport.core.assertion;

import com.jmeterwebinsightreport.core.model.ErrorSummary;
import com.jmeterwebinsightreport.core.model.OverallMetrics;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
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
 * Error-classified gate metrics — {@code errorRate:class=…} and {@code errorRate:code=…}.
 *
 * <p>A 2% error rate that is all 404s from a stale data file is not the same run as 2% connection
 * resets under load. One scalar covered both, so the gate either fired on noise or was set loose
 * enough to miss a real failure.
 *
 * <p>Three properties are load-bearing and each has its own section below: a typo is
 * NOT_JUDGED / INVALID_RULE and
 * never a silent match; "none of them" is a real zero and not an error; and the whole-run scope
 * works, because transport errors are not a per-sampler concern.
 */
class ErrorSelectorTest {

    // ---------------------------------------------------------------- the grammar

    @Test
    void aQualifiedMetricParsesAndKeepsItsBaseUnit() {
        MetricRef rate = MetricRef.parse("errorRate:class=nonProtocol");
        assertNotNull(rate);
        assertTrue(rate.hasSelector());
        assertEquals("class", rate.selectorKey());
        assertEquals("nonProtocol", rate.selectorValue());
        assertEquals(MetricRef.Unit.PERCENT, rate.unit());

        MetricRef count = MetricRef.parse("errorCount:code=5*");
        assertNotNull(count);
        assertEquals("code", count.selectorKey());
        assertEquals(MetricRef.Unit.COUNT, count.unit());
    }

    @Test
    void theClassTokenIsCanonicalisedSoSpellingDoesNotMatter() {
        assertEquals("nonProtocol", MetricRef.parse("errorRate:class=NONPROTOCOL").selectorValue());
        assertEquals("transport", MetricRef.parse("errorRate:CLASS=transport").selectorValue());
    }

    @Test
    void aQualifiedMetricInheritsItsBaseScope() {
        // errorRate works whole-run, so errorRate:class=transport does — and that is the rule
        // most people will write, since transport errors are not a per-sampler concern.
        assertTrue(MetricRef.parse("errorRate:class=transport").validForOverall());
        assertTrue(MetricRef.parse("errorRate:class=transport").validForSampler());
        // errorCount is sampler-only, and its qualified form inherits that rather than
        // quietly gaining a scope the plain metric does not have.
        assertFalse(MetricRef.parse("errorCount:class=transport").validForOverall());
        assertTrue(MetricRef.parse("errorCount:class=transport").validForSampler());
    }

    // ------------------------------- constraint 1: a typo is INVALID_RULE, never silent

    /**
     * The whole ballgame. A mistyped selector that fell through to "all errors" would silently
     * loosen the gate, and one that fell through to NaN would be NO_VALUE by accident with a
     * message that says nothing useful. Both are refused at parse, with a sentence.
     */
    @Test
    void aMistypedSelectorKeyIsInvalidAndSaysWhat() {
        assertNull(MetricRef.parse("errorRate:clss=nonProtocol"),
                "a mistyped selector must not resolve to anything");
        String problem = MetricRef.problemWith("errorRate:clss=nonProtocol");
        assertNotNull(problem);
        assertTrue(problem.contains("clss"), problem);
        assertTrue(problem.contains("class=") && problem.contains("code="), problem);
    }

    @Test
    void anUnknownClassNameIsInvalidAndListsTheRealOnes() {
        assertNull(MetricRef.parse("errorRate:class=funcional"));
        String problem = MetricRef.problemWith("errorRate:class=funcional");
        assertTrue(problem.contains("funcional"), problem);
        assertTrue(problem.contains("protocol"), problem);
        assertTrue(problem.contains("nonProtocol"), problem);
        assertTrue(problem.contains("transport"), problem);
    }

    @Test
    void aMetricThatCannotTakeASelectorSaysSo() {
        assertNull(MetricRef.parse("p95:class=nonProtocol"));
        String problem = MetricRef.problemWith("p95:class=nonProtocol");
        assertNotNull(problem);
        assertTrue(problem.contains("errorRate") && problem.contains("errorCount"), problem);
    }

    @Test
    void aMalformedSelectorSaysWhatTheShapeIs() {
        for (String bad : Arrays.asList("errorRate:class", "errorRate:=nonProtocol",
                "errorRate:class=", "errorRate:")) {
            assertNull(MetricRef.parse(bad), bad + " must not parse");
            assertNotNull(MetricRef.problemWith(bad), bad + " must explain itself");
        }
        assertTrue(MetricRef.problemWith("errorRate:class").contains("key=value"));
    }

    @Test
    void aTypoedSelectorReachesTheRuleAsInvalidRule_notAsAQuietPass() {
        AssertionRule rule = AssertionRule.builder(AssertionTarget.sampler("A"),
                        "errorRate:clss=nonProtocol")
                .id("typo").fail(Predicate.of(Predicate.Op.GT, 0)).build();

        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(rule), runWithErrors());

        assertEquals(AssertionStatus.NOT_JUDGED, gate.ruleResults().get(0).status());
        assertEquals(NotJudged.INVALID_RULE, gate.ruleResults().get(0).notJudged());
        assertTrue(gate.ruleResults().get(0).notJudgedDetail().contains("clss"),
                gate.ruleResults().get(0).notJudgedDetail());
        // A typo cannot leave the build green — but it no longer leaves it RED either. Nothing
        // was measured, and a verdict has to come from a measurement. What it leaves is a gate
        // that decided nothing plus a count naming the rule, which is the number a strict
        // pipeline gates on: jq -e '.gate.notJudged.invalidRule == 0'.
        assertNull(gate.status(), "one broken rule and nothing else measured: no verdict");
        assertEquals(1, gate.invalidRule());
        assertEquals(0, gate.passed(), "a mistyped selector must never be counted as a pass");
    }

    @Test
    void theValidatorReportsTheSelectorProblemRatherThanTheGenericOne() {
        AssertionRule rule = AssertionRule.builder(AssertionTarget.sampler("A"),
                        "errorRate:class=funcional")
                .id("typo").fail(Predicate.of(Predicate.Op.GT, 0)).build();

        List<AssertionValidator.Finding> findings =
                new AssertionValidator().validate(Collections.singletonList(rule), runWithErrors());

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).message().contains("unknown error class"),
                findings.get(0).message());
        assertFalse(findings.get(0).message().contains("Valid here:"),
                "the generic list of every metric buries the one thing that is wrong");
    }

    // ------------------------------- constraint 2: "none of them" is an answer, not a failure

    /**
     * Three outcomes that all look like "nothing there" and must not collapse into one:
     * a real zero, a malformed selector, and a sampler that recorded nothing.
     */
    @Test
    void noMatchingErrorsIsZero_andPassesCleanly() {
        ReportData data = runWithErrors();
        SamplerStatistics a = data.getSamplerStatistics().get(0);

        // A run with no 404s. The question was asked and answered "none".
        assertEquals(0.0, MetricRef.parse("errorRate:code=404").valueFor(a, data), 0.0001);
        assertEquals(0.0, MetricRef.parse("errorCount:code=404").valueFor(a, data), 0.0001);

        AssertionRule rule = AssertionRule.builder(AssertionTarget.sampler("A"),
                        "errorRate:code=404")
                .id("no-404s").fail(Predicate.of(Predicate.Op.GT, 1)).build();
        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(rule), data);

        assertEquals(AssertionStatus.PASS, gate.ruleResults().get(0).status(),
                "zero matching errors is a pass, not a not-judged and not a NaN");
    }

    @Test
    void aSamplerThatRecordedNothingIsNotJudged() {
        // Different from a real zero: nothing ran, so there was no question to answer. This is
        // decided before the metric is ever resolved.
        ReportData data = runWithErrors();
        SamplerStatistics idle = new SamplerStatistics();
        idle.setSamplerName("Idle");
        idle.setSampleCount(0);
        data.getSamplerStatistics().add(idle);

        AssertionRule rule = AssertionRule.builder(AssertionTarget.sampler("Idle"),
                        "errorRate:class=transport")
                .id("idle").fail(Predicate.of(Predicate.Op.GT, 0)).build();

        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(rule), data);
        assertEquals(AssertionStatus.NOT_JUDGED, gate.ruleResults().get(0).status());
        assertEquals(NotJudged.NO_MATCH, gate.ruleResults().get(0).notJudged());
    }

    // ---------------------------------------------------------------- the numbers

    @Test
    void classSelectorsPartitionTheErrorsOfOneSampler() {
        ReportData data = runWithErrors();
        SamplerStatistics a = data.getSamplerStatistics().get(0);

        // A: 100 samples, 2 nonProtocol + 3 protocol + 5 transport = 10 errors.
        assertEquals(2, MetricRef.parse("errorCount:class=nonProtocol").valueFor(a, data), 0.0001);
        assertEquals(3, MetricRef.parse("errorCount:class=protocol").valueFor(a, data), 0.0001);
        assertEquals(5, MetricRef.parse("errorCount:class=transport").valueFor(a, data), 0.0001);
        // The classes add up to the total the unqualified metric reports.
        assertEquals(10, MetricRef.parse("errorCount").valueFor(a, data), 0.0001);
        assertEquals(2.0, MetricRef.parse("errorRate:class=nonProtocol").valueFor(a, data), 0.0001);
    }

    @Test
    void aCodeSelectorIsAGlobOverTheCodeString() {
        ReportData data = runWithErrors();
        SamplerStatistics a = data.getSamplerStatistics().get(0);

        assertEquals(3, MetricRef.parse("errorCount:code=5*").valueFor(a, data), 0.0001);
        assertEquals(3, MetricRef.parse("errorCount:code=503").valueFor(a, data), 0.0001);
        // errorType IS the response code, and JMeter writes the exception into it verbatim —
        // so a glob is the honest expression and needs no taxonomy of our own.
        assertEquals(5, MetricRef.parse("errorCount:code=Non HTTP*").valueFor(a, data), 0.0001);
    }

    // -------------------------------------------- constraint 3: the whole-run scope works

    @Test
    void theWholeRunScopeCountsEverySamplerTopLevelOnly() {
        ReportData data = runWithErrors();
        OverallMetrics overall = OverallMetrics.of(data);

        // A has 5 transport errors, B has 1. The child's are already inside its parent's.
        assertEquals(6, MetricRef.parse("errorCount:class=transport")
                .valueForOverall(overall, data), 0.0001);

        AssertionRule rule = AssertionRule.builder(AssertionTarget.overall(),
                        "errorRate:class=transport")
                .id("no-transport").fail(Predicate.of(Predicate.Op.GT, 0)).build();

        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(rule), data);
        assertEquals(AssertionStatus.FAIL, gate.ruleResults().get(0).status(),
                "a whole-run transport rule is the most natural one anyone will write");
    }

    @Test
    void aTransactionChildsErrorsAreNotCountedTwiceAtTheWholeRunScope() {
        // Dividing a child-inclusive numerator by a child-free denominator can push a rate past
        // 100 — the same mistake buildErrorSummaries documents avoiding.
        ReportData data = runWithErrors();
        OverallMetrics overall = OverallMetrics.of(data);
        assertEquals(0, MetricRef.parse("errorCount:code=CHILD-ONLY")
                .valueForOverall(overall, data), 0.0001);
    }


    /**
     * An unusable regex in {@code code=} is INVALID_RULE, and never an exception.
     *
     * <p>This parsed cleanly and then threw {@link java.util.regex.PatternSyntaxException} from
     * inside the per-summary matching loop. {@link AssertionEvaluator} catches that only around
     * target resolution, so it left the evaluator and killed report generation — breaking the
     * promise the docs make outright, that a bad rule renders as broken and the report is still
     * produced.
     *
     * <p>Its trigger profile is the ugly part: the loop is over {@code errorSummaries}, so a
     * clean run never enters it. The rule looked correct in every green build and took the
     * report down on the first run with failures — exactly when the gate is the thing you needed.
     */
    @Test
    void anUnusableRegexInACodeSelectorIsInvalidRatherThanAnException() {
        assertNull(MetricRef.parse("errorRate:code=regex:["),
                "an uncompilable pattern must be refused at parse, like every other typo");
        String problem = MetricRef.problemWith("errorRate:code=regex:[");
        assertNotNull(problem);
        assertTrue(problem.contains("not a valid pattern"), problem);

        AssertionRule rule = AssertionRule.builder(AssertionTarget.sampler("A"),
                        "errorRate:code=regex:[")
                .id("bad-regex").fail(Predicate.of(Predicate.Op.GT, 0)).build();

        // The run HAS errors, which is the only condition under which the old code threw.
        GateSummary gate = new AssertionEvaluator().evaluate(
                Collections.singletonList(rule), runWithErrors());

        assertEquals(AssertionStatus.NOT_JUDGED, gate.ruleResults().get(0).status());
        assertEquals(NotJudged.INVALID_RULE, gate.ruleResults().get(0).notJudged());
        assertTrue(gate.ruleResults().get(0).notJudgedDetail().contains("not a valid pattern"),
                gate.ruleResults().get(0).notJudgedDetail());
    }

    @Test
    void aValidRegexInACodeSelectorStillWorks() {
        // The regex: escape hatch is the reason code= has no closed vocabulary — "5xx except
        // 503" has no glob spelling.
        ReportData data = runWithErrors();
        SamplerStatistics a = data.getSamplerStatistics().get(0);
        assertEquals(3, MetricRef.parse("errorCount:code=regex:5[0-9]{2}").valueFor(a, data),
                0.0001);
    }

    @Test
    void aCodeGlobIsCaseSensitive() {
        // Documented rather than changed: class=transport is the way to select these, so the
        // glob is rarely the tool — and "matched nothing" is a legitimate answer here, so a
        // case-blind match would be a guess at intent rather than a convenience.
        ReportData data = runWithErrors();
        SamplerStatistics a = data.getSamplerStatistics().get(0);
        assertEquals(5, MetricRef.parse("errorCount:code=Non HTTP*").valueFor(a, data), 0.0001);
        assertEquals(0, MetricRef.parse("errorCount:code=non http*").valueFor(a, data), 0.0001);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A: 100 samples with 2×200, 3×503 and 5×"Non HTTP…" errors.
     * B: 50 samples with 1 transport error.
     * B-0: a transaction child of B, whose error must not reach the whole-run scope.
     */
    private static ReportData runWithErrors() {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(
                stat("A", 100, 10), stat("B", 50, 1), child("B-0", "B", 20, 4))));
        d.setErrorSummaries(new ArrayList<>(Arrays.asList(
                summary("A", "200", 2), summary("A", "503", 3),
                summary("A", "Non HTTP response code: java.net.ConnectException", 5),
                summary("B", "Non HTTP response code: java.net.SocketTimeoutException", 1),
                summary("B-0", "CHILD-ONLY", 4))));
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(60_000);
        d.setMetadata(m);
        return d;
    }

    private static SamplerStatistics stat(String name, long samples, long errors) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(samples);
        s.setErrorCount(errors);
        s.setErrorRate(samples > 0 ? (errors * 100.0) / samples : 0);
        return s;
    }

    private static SamplerStatistics child(String name, String parent, long samples, long errors) {
        SamplerStatistics s = stat(name, samples, errors);
        s.setParentSamplerName(parent);
        return s;
    }

    private static ErrorSummary summary(String sampler, String code, long count) {
        ErrorSummary e = new ErrorSummary();
        e.setSamplerName(sampler);
        e.setResponseCode(code);
        e.setOccurrenceCount(count);
        return e;
    }
}
