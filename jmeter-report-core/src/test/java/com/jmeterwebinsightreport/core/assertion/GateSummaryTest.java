package com.jmeterwebinsightreport.core.assertion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GateSummary#configErrors()} is the number the JUnit writer adds to {@code failures} and
 * the number {@code failonerror} fails the process over. It replaced {@code invalidRule()} at
 * both sites, so this pins that the two agree on a client gate and differ exactly by the
 * server-side config errors on a gate that carries them.
 */
class GateSummaryTest {

    private static AssertionRule rule(String id) {
        return AssertionRule.builder(AssertionTarget.sampler("A"), "p95").id(id)
                .fail(Predicate.of(Predicate.Op.GT, 100)).build();
    }

    private static List<RuleResult> oneOfEachReason() {
        List<RuleResult> results = new ArrayList<>();
        for (NotJudged r : NotJudged.values()) {
            results.add(RuleResult.notJudged(rule("r-" + r.name().toLowerCase()), r));
        }
        return results;
    }

    @Test
    void configErrorsCountsExactlyTheReasonsThatAreConfigErrors() {
        GateSummary gate = GateSummary.of(oneOfEachReason());

        int expected = 0;
        for (NotJudged r : NotJudged.values()) {
            if (r.isConfigError()) {
                expected++;
            }
        }
        assertEquals(3, expected, "INVALID_RULE, SOURCE_FAILED, LOW_COVERAGE");
        assertEquals(expected, gate.configErrors());
        // And it is not simply invalidRule() under a new name.
        assertEquals(1, gate.invalidRule());
        assertEquals(gate.invalidRule() + gate.notJudged(NotJudged.SOURCE_FAILED)
                + gate.notJudged(NotJudged.LOW_COVERAGE), gate.configErrors());
    }

    @Test
    void onAGateOfClientReasonsConfigErrorsEqualsInvalidRule() {
        // The client exit path used to read invalidRule() and now reads configErrors(). On every
        // gate a client evaluator can produce the two are the same number, which is what makes
        // the substitution behaviour-neutral for every existing run.
        List<RuleResult> client = new ArrayList<>();
        for (NotJudged r : NotJudged.values()) {
            if (!r.serverOnly()) {
                client.add(RuleResult.notJudged(rule("c-" + r.name().toLowerCase()), r));
            }
        }
        client.add(RuleResult.notJudged(rule("c-invalid-2"), NotJudged.INVALID_RULE));
        GateSummary gate = GateSummary.of(client);

        assertEquals(2, gate.invalidRule());
        assertEquals(gate.invalidRule(), gate.configErrors());
    }

    @Test
    void everyReasonIsCountedAndNoneOfThemGates() {
        GateSummary gate = GateSummary.of(oneOfEachReason());

        assertEquals(NotJudged.values().length, gate.declared());
        assertEquals(NotJudged.values().length, gate.notJudged());
        int sum = 0;
        for (NotJudged r : NotJudged.values()) {
            assertEquals(1, gate.notJudged(r), r.toString());
            sum += gate.notJudged(r);
        }
        assertEquals(gate.notJudged(), sum, "the reasons must sum to notJudged");
        // Seven ways of deciding nothing decide nothing: a status of null, not PASS, and
        // evaluated excludes only the parked one.
        assertNull(gate.status());
        assertEquals(NotJudged.values().length - 1, gate.evaluated());
    }

    @Test
    void aServerReasonDoesNotMoveTheVerdict() {
        List<RuleResult> results = Arrays.asList(
                RuleResult.of(rule("ok"), AssertionStatus.PASS, new ArrayList<>(), 1),
                RuleResult.notJudged(rule("unreachable-source"), NotJudged.SOURCE_FAILED,
                        "prometheus returned 401"));
        GateSummary gate = GateSummary.of(results);

        assertEquals(AssertionStatus.PASS, gate.status(),
                "a check that never ran is counted and exits the process; it is not a FAIL");
        assertEquals(1, gate.configErrors());
        assertEquals(0, gate.failed());
    }
}
