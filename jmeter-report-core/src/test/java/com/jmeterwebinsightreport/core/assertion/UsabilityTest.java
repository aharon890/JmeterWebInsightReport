package com.jmeterwebinsightreport.core.assertion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One answer to "can this rule decide anything?", and the proof that it stays one.
 *
 * <p>The question used to be answered twice — once by {@link AssertionValidator}, which produced a
 * finding, and once by the evaluator, which decided whether the rule was judged. Findings do not
 * gate, so the validator could be right and inert: a band that could never fire was reported as
 * such and judged anyway, contributing PASS to a green gate with an ERROR beside it saying, in
 * those words, that it would.
 *
 * <p>{@link #everyErrorRefusesAndEveryRefusalIsAnError} is the test that matters here. The rest
 * pin the individual shapes; that one pins the property that stops them drifting apart again.
 */
class UsabilityTest {

    private static AssertionRule.Builder rule(String metric) {
        return AssertionRule.builder(AssertionTarget.sampler("A"), metric).id("r");
    }

    private static Predicate gt(double v) { return Predicate.of(Predicate.Op.GT, v); }

    private static Predicate lt(double v) { return Predicate.of(Predicate.Op.LT, v); }

    // ---- the invariant -------------------------------------------------------

    @Test
    void everyErrorRefusesAndEveryRefusalIsAnError() {
        // A corpus wide enough that a new check cannot be added without meeting one of these
        // rules. If this fails, the two answers have separated — which is the entire failure this
        // class was written to make impossible, so prefer fixing the design over the assertion.
        List<AssertionRule> corpus = java.util.Arrays.asList(
                rule("p95").fail(gt(800)).build(),                       // fine
                rule("p95").warn(gt(900)).fail(gt(800)).build(),         // dead warn: WARNING only
                rule("p95").build(),                                     // no bands
                rule("p95x").fail(gt(1)).build(),                        // unknown metric
                rule("p95").fail(lt(-19)).build(),                       // unreachable band
                rule("p95").warn(gt(1)).declarationProblem(
                        Usability.unreadableBand("fail", "fail has 2 operators")).build(),
                rule("p95").fail(gt(1)).declarationProblem(
                        Usability.retiredKey("observe", "\"observe\" is no longer a modifier"))
                        .build(),
                AssertionRule.builder(AssertionTarget.unusable("no target"), "p95")
                        .id("t").fail(gt(1)).build(),
                AssertionRule.builder(AssertionTarget.overall(), "errorRate")
                        .id("o").fail(gt(0.01)).build());

        for (AssertionRule r : corpus) {
            boolean anyError = false;
            Usability firstError = null;
            for (Usability u : Usability.check(r)) {
                if (u.refuses()) {
                    anyError = true;
                    if (firstError == null) {
                        firstError = u;
                    }
                }
            }
            Usability refusal = Usability.refusal(r);
            assertEquals(anyError, refusal != null,
                    "check() and refusal() disagree about " + r);
            if (refusal != null) {
                assertEquals(firstError.reason(), refusal.reason(),
                        "refusal() is not the first ERROR for " + r);
                assertEquals(firstError.reasonText(), refusal.reasonText(),
                        "refusal() is not the first ERROR for " + r);
                assertNotNull(refusal.reasonText());
                assertFalse(refusal.reasonText().trim().isEmpty(),
                        "a refusal with nothing to say is worse than none: " + r);
            }
        }
    }

    @Test
    void aWarningNeverRefuses() {
        // The severity distinction is the whole reason this returns a list rather than a verdict.
        // A warn band the fail band already covers produces correct verdicts, so refusing it would
        // turn a swapped pair of numbers into a non-zero exit.
        AssertionRule r = rule("p95").warn(gt(900)).fail(gt(800)).build();

        List<Usability> found = Usability.check(r);

        assertEquals(1, found.size(), found.toString());
        assertEquals(Usability.Reason.WARN_COVERED_BY_FAIL, found.get(0).reason());
        assertEquals(AssertionValidator.Severity.WARNING, found.get(0).severity());
        assertNull(Usability.refusal(r));
    }

    // ---- shape A: a band declared and not readable ---------------------------

    @Test
    void aRuleWhoseOtherBandWasUnreadableIsRefusedRatherThanJudgedOnWhatSurvived() {
        // The shape that exits 0 today: fail is unreadable, warn is fine, hasBands() stays true,
        // so the rule is judged on warn alone and the declared fail threshold never applies.
        AssertionRule r = rule("p95")
                .warn(gt(1))
                .declarationProblem(Usability.unreadableBand("fail",
                        "fail has 2 operators (gt, lt) — exactly one is allowed"))
                .build();

        assertTrue(r.hasBands(), "warn survived, so the old check would let this through");

        Usability refusal = Usability.refusal(r);
        assertNotNull(refusal, "a rule that lost a declared limit must not be judged");
        assertEquals(Usability.Reason.BAND_UNREADABLE, refusal.reason());
        assertTrue(refusal.reasonText().contains("2 operators"), refusal.reasonText());
    }

    @Test
    void anUnreadableBandIsNotAlsoReportedAsHavingNoBands() {
        // Two findings for one mistake, and the second is the less useful of the two.
        AssertionRule r = rule("p95")
                .declarationProblem(Usability.unreadableBand("fail", "fail gt must be a number"))
                .build();

        assertFalse(r.hasBands());
        List<Usability> found = Usability.check(r);

        assertEquals(1, found.size(), found.toString());
        assertEquals(Usability.Reason.BAND_UNREADABLE, found.get(0).reason());
    }

    @Test
    void theLoaderReportsItsOwnFindingsAndTheValidatorDoesNotRepeatThem() {
        AssertionRule r = rule("p95")
                .declarationProblem(Usability.unreadableBand("fail", "fail gt must be a number"))
                .build();

        assertTrue(Usability.refusal(r).loaderReported(),
                "the mapper already said this, at the point it happened");
    }

    // ---- shape B: a band nothing can breach ----------------------------------

    @Test
    void aBandNoMeasurementCanBreachRefusesTheRule() {
        // The incident this whole class is named after: a loadValidity tolerance of 20, meant as
        // 20%, shipped as `lt -19` and reported PASS on every run. The check for it existed — in
        // the validator, where it changed nothing.
        AssertionRule r = rule("p95").fail(lt(-19)).build();

        Usability refusal = Usability.refusal(r);

        assertNotNull(refusal, "PASS on every run, including one that measured nothing");
        assertEquals(Usability.Reason.BAND_UNREACHABLE, refusal.reason());
        assertTrue(refusal.reasonText().contains("can never fire"), refusal.reasonText());
    }

    @Test
    void ltZeroIsUnreachableAndLteZeroIsNot() {
        // Nothing is negative, so `lt 0` can never fire — but `lte 0` fires on a metric that
        // measured exactly zero, which is an ordinary thing for a count or an error rate to do.
        assertNotNull(Usability.refusal(rule("errorRate").fail(lt(0)).build()));
        assertNull(Usability.refusal(
                rule("errorRate").fail(Predicate.of(Predicate.Op.LTE, 0)).build()));
        assertNull(Usability.refusal(rule("errorRate").fail(lt(0.001)).build()),
                "the smallest real threshold in this repo's own fixtures");
    }

    @Test
    void nothingIsClaimedAboutUpwardBands() {
        // "1000000 ms is surely impossible" is a guess about the system under test.
        assertNull(Usability.refusal(rule("p95").fail(gt(1_000_000)).build()));
    }

    // ---- shape D2: a key that used to mean something -------------------------

    @Test
    void aRetiredKeyRefusesTheRuleAndNamesItself() {
        AssertionRule r = rule("p95").fail(gt(1))
                .declarationProblem(Usability.retiredKey("observe",
                        "\"observe\" is no longer a rule modifier. It meant \"report this and "
                                + "never gate\""))
                .build();

        Usability refusal = Usability.refusal(r);

        assertNotNull(refusal, "silently dropping it leaves a rule that gates");
        assertEquals(Usability.Reason.RETIRED_KEY, refusal.reason());
        assertTrue(refusal.reasonText().contains("observe"),
                "a message that does not name the key cannot be acted on");
    }

    // ---- order ---------------------------------------------------------------

    @Test
    void anUnreadableTargetIsReportedBeforeAnythingBelowIt() {
        // Nothing under a target the loader could not read can be trusted to be the real
        // complaint, so a second finding about the metric would bury the fault under a
        // consequence of it.
        AssertionRule r = AssertionRule.builder(AssertionTarget.unusable("no target"), "p95x")
                .id("t").build();

        List<Usability> found = Usability.check(r);

        assertEquals(1, found.size(), found.toString());
        assertEquals(Usability.Reason.UNREADABLE_TARGET, found.get(0).reason());
    }

    @Test
    void whatTheLoaderRecordedComesFirst() {
        AssertionRule r = rule("p95x")
                .declarationProblem(Usability.retiredKey("observe", "\"observe\" is gone"))
                .build();

        assertEquals(Usability.Reason.RETIRED_KEY, Usability.check(r).get(0).reason(),
                "what was WRITTEN outranks what could be parsed out of it");
    }

    // ---- the two renderings --------------------------------------------------

    @Test
    void theFindingCanAffordContextTheGateCannot() {
        // Two renderings of one answer, never two answers. The banner over the tab gets the
        // sentence; the log and the CI/CD JSON get the vocabulary as well.
        AssertionRule r = rule("p95x").fail(gt(1)).build();

        Usability refusal = Usability.refusal(r);

        assertTrue(refusal.reasonText().contains("unknown metric 'p95x'"), refusal.reasonText());
        assertFalse(refusal.reasonText().contains("Valid here:"),
                "the gate's sentence stays short enough to render over a tab");
        assertTrue(refusal.explanation().startsWith(refusal.reasonText()),
                "the long form must be the short form plus context, not a second wording");
        assertTrue(refusal.explanation().contains("Valid here:"), refusal.explanation());
    }

    @Test
    void aRuleWithNoProblemsSaysNothing() {
        AssertionRule r = rule("p95").warn(gt(650)).fail(gt(800)).build();

        assertEquals(0, Usability.check(r).size());
        assertNull(Usability.refusal(r));
    }
}
