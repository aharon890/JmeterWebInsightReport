package com.jmeterwebinsightreport.core.assertion;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per finding class, asserting the message text.
 *
 * <p>The text is the deliverable. A finding that says "invalid rule" sends the reader back to
 * the JSON to work out which one and why; these messages name the rule, say what is wrong, and
 * say what to do about it, and that is worth pinning.
 */
class AssertionValidatorTest {

    private final AssertionValidator validator = new AssertionValidator();

    private static ReportData runWith(String... samplerNames) {
        List<SamplerStatistics> stats = new ArrayList<>();
        for (String name : samplerNames) {
            SamplerStatistics s = new SamplerStatistics();
            s.setSamplerName(name);
            s.setSampleCount(100);
            s.setPercentile95(200);
            stats.add(s);
        }
        ReportData d = new ReportData();
        d.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(60_000);
        d.setMetadata(m);
        return d;
    }

    private static AssertionRule.Builder rule(String id, AssertionTarget target, String metric) {
        return AssertionRule.builder(target, metric).id(id);
    }

    private AssertionValidator.Finding only(List<AssertionValidator.Finding> findings) {
        assertEquals(1, findings.size(), "expected exactly one finding, got: " + findings);
        return findings.get(0);
    }

    @Test
    void anUnknownMetricIsNamedWithASuggestionAndTheValidVocabulary() {
        AssertionRule r = rule("bad-metric", AssertionTarget.sampler("A"), "p90x")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith("A")));

        assertEquals(AssertionValidator.Severity.ERROR, f.severity());
        assertTrue(f.message().contains("unknown metric 'p90x'"), f.message());
        assertTrue(f.message().contains("did you mean 'p90'"), f.message());
        assertTrue(f.message().contains("Valid here:"), f.message());
        assertTrue(f.describe().startsWith("assertion 'bad-metric':"), f.describe());
    }

    @Test
    void aTargetThatMatchedNothingListsTheSamplersTheRunActuallyHad() {
        // The highest-value message in the set: the fix is almost always to copy one of these
        // names, and a bare "matched nothing" sends the reader back to the JTL to find them.
        AssertionRule r = rule("typo", AssertionTarget.sampler("POST /api/carts/add-item"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith("POST /api/cart/add-item", "GET /health")));

        // WARNING, not ERROR: an unmatched rule renders as SKIPPED and counts as nothing, so
        // an ERROR here would assert a verdict the evaluator does not produce. The message is
        // what carries this now — which is why the rest of this test is about the message.
        assertEquals(AssertionValidator.Severity.WARNING, f.severity());
        assertTrue(f.message().contains("matched nothing in this run"), f.message());
        assertTrue(f.message().contains("Did you mean \"POST /api/cart/add-item\""), f.message());
        assertTrue(f.message().contains("This run contained: POST /api/cart/add-item, GET /health"),
                f.message());
    }

    @Test
    void aWildlyWrongNameGetsNoDidYouMean() {
        AssertionRule r = rule("nomatch", AssertionTarget.sampler("zzzzzzzzzzzzzzz"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith("GET /health")));
        assertTrue(!f.message().contains("Did you mean"),
                "a suggestion this far off would undermine the ones that are right: " + f.message());
    }

    @Test
    void aLongPlansSamplerListIsTruncatedRatherThanDumped() {
        String[] many = new String[30];
        for (int i = 0; i < many.length; i++) {
            many[i] = "S" + i;
        }
        AssertionRule r = rule("typo", AssertionTarget.sampler("nope"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith(many)));
        assertTrue(f.message().contains("and 18 more"), f.message());
    }

    @Test
    void anUnreachableWarnBandSaysWhichValuesLookSwapped() {
        // fail is tested first, so warn > 500 beside fail > 400 can never fire. Verdicts stay
        // correct, so this is a warning — but it is never what anyone meant.
        AssertionRule r = rule("dead-warn", AssertionTarget.sampler("A"), "p95")
                .warn(Predicate.of(Predicate.Op.GT, 500))
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith("A")));

        assertEquals(AssertionValidator.Severity.WARNING, f.severity());
        assertTrue(f.message().contains("warn can never fire"), f.message());
        assertTrue(f.message().contains("Swapped values?"), f.message());
    }

    @Test
    void anUnreachableWarnBandIsDetectedForLowerIsWorseMetricsToo() {
        // throughput: fail < 30 fires before warn < 20 could.
        AssertionRule r = rule("dead-warn-lt", AssertionTarget.sampler("A"), "throughput")
                .warn(Predicate.of(Predicate.Op.LT, 20))
                .fail(Predicate.of(Predicate.Op.LT, 30)).build();

        assertTrue(only(validator.validate(Collections.singletonList(r), runWith("A")))
                .message().contains("warn can never fire"));
    }

    @Test
    void aReachableWarnBandIsNotReported() {
        AssertionRule r = rule("fine", AssertionTarget.sampler("A"), "p95")
                .warn(Predicate.of(Predicate.Op.GT, 400))
                .fail(Predicate.of(Predicate.Op.GT, 500)).build();

        assertEquals(Collections.emptyList(),
                validator.validate(Collections.singletonList(r), runWith("A")));
    }

    @Test
    void aRuleWithNoBandsSuggestsObserveInstead() {
        AssertionRule r = rule("bandless", AssertionTarget.sampler("A"), "p95").build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWith("A")));
        assertEquals(AssertionValidator.Severity.ERROR, f.severity());
        assertTrue(f.message().contains("can never decide anything"), f.message());
    }

    @Test
    void aDuplicateIdIsReportedBecauseIdsNameTestcases() {
        AssertionRule a = rule("same", AssertionTarget.sampler("A"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build();
        AssertionRule b = rule("same", AssertionTarget.sampler("A"), "p99")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build();

        List<AssertionValidator.Finding> findings = validator.validate(Arrays.asList(a, b), runWith("A"));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("duplicate id"), findings.get(0).message());
    }

    @Test
    void aBrokenRegexIsReportedWithItsPosition() {
        AssertionRule r = rule("bad-regex", AssertionTarget.match("regex:[unclosed", null), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 1)).build();

        List<AssertionValidator.Finding> findings =
                validator.validate(Collections.singletonList(r), runWith("A"));
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("not a valid expression at index")),
                findings.toString());
    }

    /**
     * A matched row that recorded nothing is a different problem from a name that matched nothing,
     * and used to get the same sentence.
     *
     * <p>Both end up unmatched, because the evaluator drops a zero-sample row from the judgeable
     * set on purpose — its latency is not small, it is undefined and stored as zero, so every
     * "under X" threshold would pass. But the fixes differ: a name that matches nothing is a
     * typo; a name that matches an empty row is a thread group that never started, a disabled
     * controller, or a run that stopped early. Telling the reader "matched nothing" about a
     * sampler sitting in their table is simply false.
     */
    @Test
    void aMatchedRowThatRecordedNoSamplesSaysSoRatherThanClaimingItMatchedNothing() {
        AssertionRule r = rule("never-ran", AssertionTarget.sampler("Idle API"), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        AssertionValidator.Finding f = only(validator.validate(
                Collections.singletonList(r), runWithEmpty("Idle API")));

        assertEquals(AssertionValidator.Severity.WARNING, f.severity());
        assertTrue(f.message().contains("Idle API"), f.message());
        assertTrue(f.message().contains("recorded"), f.message());
        assertFalse(f.message().contains("matched nothing in this run"),
                "a row that IS in the run must not be reported as matching nothing: " + f.message());
    }

    @Test
    void aRowWithSamplesBesideAnEmptyOneIsJudged_soNoFindingIsRaised() {
        // Only an ALL-empty match set is unjudgeable. One live row is enough to judge the rule,
        // so warning about the empty sibling would be noise on a perfectly working gate.
        AssertionRule r = rule("mixed", AssertionTarget.match("*", null), "p95")
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        ReportData mixed = runWith("Live API");
        SamplerStatistics idle = new SamplerStatistics();
        idle.setSamplerName("Idle API");
        idle.setSampleCount(0);
        mixed.getSamplerStatistics().add(idle);

        assertEquals(Collections.emptyList(),
                validator.validate(Collections.singletonList(r), mixed));
    }

    /** A run whose named samplers exist in the statistics but recorded nothing. */
    private static ReportData runWithEmpty(String... samplerNames) {
        ReportData d = runWith(samplerNames);
        for (SamplerStatistics s : d.getSamplerStatistics()) {
            s.setSampleCount(0);
        }
        return d;
    }

    @Test
    void aValidRuleSetProducesNothing() {
        AssertionRule r = rule("good", AssertionTarget.sampler("A"), "p95")
                .warn(Predicate.of(Predicate.Op.GT, 100))
                .fail(Predicate.of(Predicate.Op.GT, 300)).build();

        assertEquals(Collections.emptyList(),
                validator.validate(Collections.singletonList(r), runWith("A")));
    }

    @Test
    void validationWithoutARunStillChecksEverythingThatDoesNotNeedOne() {
        // Useful for the report's editor and for checking a file before a run exists. The
        // unmatched-target check is the only one that needs data, and it is skipped rather
        // than reported as a false positive.
        AssertionRule r = rule("dead-warn", AssertionTarget.sampler("A"), "p95")
                .warn(Predicate.of(Predicate.Op.GT, 500))
                .fail(Predicate.of(Predicate.Op.GT, 400)).build();

        assertEquals(1, validator.validate(Collections.singletonList(r), null).size());
    }
}
