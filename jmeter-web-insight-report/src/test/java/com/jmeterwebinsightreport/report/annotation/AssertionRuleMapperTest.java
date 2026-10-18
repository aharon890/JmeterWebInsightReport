package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionTarget;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.assertion.Usability;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON shape, and what happens when it is wrong.
 *
 * <p>Jackson can only say "cannot deserialize". These tests pin the messages that say which
 * rule, what is wrong with it, and what to write instead — which is the actual deliverable of
 * load-time validation.
 */
class AssertionRuleMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AssertionRuleMapper mapper = new AssertionRuleMapper();

    private AssertionRuleMapper.Result mapOf(String assertionsJson) throws Exception {
        ReportAnnotations a = json.readValue(
                "{\"assertions\":" + assertionsJson + "}", ReportAnnotations.class);
        return mapper.map(a.getAssertions());
    }

    private static String messages(AssertionRuleMapper.Result r) {
        StringBuilder sb = new StringBuilder();
        for (AssertionValidator.Finding f : r.findings()) {
            sb.append(f.describe()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void theCanonicalRuleParses() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{"
                + "\"id\":\"checkout-p95\","
                + "\"description\":\"Contract clause 4.2\","
                + "\"target\":{\"sampler\":\"POST /api/checkout\"},"
                + "\"metric\":\"p95\","
                + "\"warn\":{\"gt\":650},"
                + "\"fail\":{\"gt\":800}"
                + "}]");

        assertEquals(0, r.findings().size(), messages(r));
        AssertionRule rule = r.rules().get(0);
        assertEquals("checkout-p95", rule.id());
        assertEquals("Contract clause 4.2", rule.description());
        assertEquals(AssertionTarget.Kind.SAMPLER, rule.target().kind());
        assertEquals("p95", rule.metric().canonicalName());
        assertEquals(800.0, rule.fail().value(), 0.0);
        assertEquals(650.0, rule.warn().value(), 0.0);
        assertTrue(rule.enabled());
    }

    @Test
    void everyTargetFormParses() throws Exception {
        AssertionRuleMapper.Result r = mapOf("["
                + "{\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"target\":{\"samplers\":[\"A\",\"B\"]},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"target\":{\"match\":\"POST /api/*\",\"exclude\":[\"POST /api/x\"]},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\",\"fail\":{\"lt\":1}}"
                + "]");

        assertEquals(0, r.findings().size(), messages(r));
        assertEquals(AssertionTarget.Kind.SAMPLER, r.rules().get(0).target().kind());
        assertEquals(AssertionTarget.Kind.SAMPLERS, r.rules().get(1).target().kind());
        assertEquals(AssertionTarget.Kind.MATCH, r.rules().get(2).target().kind());
        assertEquals(1, r.rules().get(2).target().excludes().size());
        assertEquals(AssertionTarget.Kind.OVERALL, r.rules().get(3).target().kind());
    }

    @Test
    void everyOperatorParsesIncludingOutside() throws Exception {
        AssertionRuleMapper.Result r = mapOf("["
                + "{\"id\":\"a\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"b\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gte\":1}},"
                + "{\"id\":\"c\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"lt\":1}},"
                + "{\"id\":\"d\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"lte\":1}}"
                + "]");

        assertEquals(0, r.findings().size(), messages(r));
        assertEquals(Predicate.Op.GT, r.rules().get(0).fail().op());
        assertEquals(Predicate.Op.GTE, r.rules().get(1).fail().op());
        assertEquals(Predicate.Op.LT, r.rules().get(2).fail().op());
        assertEquals(Predicate.Op.LTE, r.rules().get(3).fail().op());
    }

    @Test
    void aBandWithTwoOperatorsIsRejectedRatherThanGuessedAt() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"two-ops\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":100,\"lt\":50}}]");

        assertTrue(messages(r).contains("fail has 2 operators"), messages(r));
        assertNull(r.rules().get(0).fail(), "a band that could not be read is not invented");
    }

    @Test
    void anUnknownOperatorNamesTheOnesThatExist() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"bad-op\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\","
                + "\"fail\":{\"between\":[1,2]}}]");

        assertTrue(messages(r).contains("is not one of gt, gte, lt, lte"), messages(r));
    }

    @Test
    void aNonNumericThresholdSaysWhatItGotInstead() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"texty\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":\"soon\"}}]");

        assertTrue(messages(r).contains("must be a number, not the text \"soon\""), messages(r));
    }

    @Test
    void aQuotedNumberIsAccepted() throws Exception {
        // A common hand-edit, unambiguous in meaning. Failing a build over a pair of quotes
        // would be pedantry, not rigour.
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"quoted\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":\"800\"}}]");

        assertEquals(0, r.findings().size(), messages(r));
        assertEquals(800.0, r.rules().get(0).fail().value(), 0.0);
    }

    @Test
    void aTargetWithTwoFormsIsRejectedRatherThanOneBeingPicked() throws Exception {
        // Choosing silently would make the rule judge something its author did not ask for,
        // which is worse than the rule not running.
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"ambiguous\","
                + "\"target\":{\"sampler\":\"A\",\"match\":\"B*\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":1}}]");

        assertTrue(messages(r).contains("more than one form"), messages(r));
        assertEquals(1, r.rules().size(), "the rule must still exist, so it can be counted");
        assertEquals(AssertionTarget.Kind.INVALID, r.rules().get(0).target().kind());
    }

    /**
     * Every unreadable target still produces a rule.
     *
     * <p>Dropping it took the rule out of {@code gate.declared}, out of every count,
     * out of {@code assertions[]} and out of the JUnit suite at once — so three declared rules
     * could report one, with {@code failed: 0}, and a pipeline gating on
     * {@code jq -e '.gate.failed == 0'} went green on a gate that had mostly not run.
     */
    @Test
    void anUnreadableTargetBecomesAnInvalidRuleRatherThanNoRule() throws Exception {
        String[][] cases = {
            {"[{\"id\":\"no-target\",\"metric\":\"p95\",\"fail\":{\"gt\":1}}]", "no target"},
            {"[{\"id\":\"empty-target\",\"target\":{},\"metric\":\"p95\",\"fail\":{\"gt\":1}}]",
                "target is empty"},
            {"[{\"id\":\"bad-scope\",\"target\":{\"scope\":\"global\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1}}]", "scope must be \"overall\""},
        };

        for (String[] c : cases) {
            AssertionRuleMapper.Result r = mapOf(c[0]);
            assertTrue(messages(r).contains(c[1]), messages(r));
            assertEquals(1, r.rules().size(), "dropped instead of reported: " + c[1]);

            AssertionRule rule = r.rules().get(0);
            assertEquals(AssertionTarget.Kind.INVALID, rule.target().kind());
            // The target text is the problem, so the report, the JUnit failure and the CI JSON
            // all name what to fix rather than saying "something".
            assertTrue(rule.target().describe().contains(c[1]), rule.target().describe());
        }
    }

    @Test
    void anUnreadableTargetKeepsTheFilesOwnLabelAsTheRuleId() throws Exception {
        // Two id-less broken rules would otherwise derive the same id and become one line in
        // every output. The positional label is what the finding already calls them.
        AssertionRuleMapper.Result r = mapOf("["
                + "{\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"metric\":\"p99\",\"fail\":{\"gt\":1}}"
                + "]");

        assertEquals(2, r.rules().size());
        assertEquals("assertions[0]", r.rules().get(0).id());
        assertEquals("assertions[1]", r.rules().get(1).id());
    }

    /**
     * An unknown key is ignored, in silence, and the rule still runs.
     *
     * <p>This asserted the opposite: one WARNING finding per unrecognised key. The policy
     * changed and the reasoning is worth keeping. We parse one specific shape. A key outside it
     * is not a defect in the tester's file — it is a field from a newer plugin, a hand-added
     * note, or something another tool writes — and grading it put noise on the findings pane,
     * the CI/CD JSON and the log of every run forever.
     *
     * <p>Nothing that matters is lost. An unknown key cannot move a verdict by definition, and
     * a key MISTYPED from one of ours still surfaces through its consequence rather than its
     * spelling: see the test below.
     */
    @Test
    void anUnknownKeyIsIgnoredInSilenceAndTheRuleStillRuns() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"future\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1},"
                + "\"over\":{\"sustainedFor\":\"60s\"}}]");

        assertEquals(0, r.findings().size(),
                "an unrecognised key is not a finding: " + messages(r));
        assertEquals(1, r.rules().size(), "and it must not discard the rule either");
    }

    /**
     * A key mistyped from one of ours is still caught — by what it costs, not by its spelling.
     *
     * <p>The reason silence is affordable. {@code faill} is ignored, so the rule has no fail
     * band, so it can never decide anything, so it is INVALID_RULE with a sentence saying so.
     * The tester finds out; they just find out from the consequence rather than from a lecture
     * about every key we did not recognise.
     */
    @Test
    void aMistypedBandKeyStillSurfacesThroughItsConsequence() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"typo\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"faill\":{\"gt\":1}}]");

        assertEquals(1, r.rules().size());
        assertNull(r.rules().get(0).fail(), "the mistyped key must not become a band");
        assertFalse(r.rules().get(0).hasBands(),
                "so the rule cannot decide anything, which the evaluator reports as INVALID_RULE");
    }

    /**
     * A rule written when {@code tags} existed still runs, and says nothing about it.
     *
     * <p>Tag selection is deleted, so a file carrying tags carries an unrecognised key and gets
     * exactly the handling any other one gets. This asserted a WARNING when it was written;
     * under the unknown-key policy it asserts silence.
     *
     * <p>The behaviour change is real and stays real: a rule that
     * {@code -Jwir.asserts.tags} used to deselect now runs. That belongs in a release note, not
     * in a warning on every run of every file that ever used the feature.
     */
    @Test
    void aLegacyTagsKeyIsIgnoredAndTheRuleStillRuns() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"smoke-only\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1},"
                + "\"tags\":[\"smoke\"]}]");

        assertEquals(0, r.findings().size(), messages(r));
        assertEquals(1, r.rules().size(), "the rule runs — tags no longer deselect anything");
    }

    /**
     * A retired operator gets a migration, not just a rejection.
     *
     * <p>{@code outside: [lo, hi]} was real until the corridor was retired, so files in the wild
     * carry it. "not one of gt, gte, lt, lte" is true and useless to their author: it says the
     * file is wrong without saying what to write instead. A corridor is two rules, and the
     * message says which two.
     */
    @Test
    void aRetiredOutsideOperatorSaysWhatToWriteInstead() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"corridor\","
                + "\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\","
                + "\"fail\":{\"outside\":[40,80]}}]");

        String msg = messages(r);
        assertTrue(msg.contains("outside is no longer an operator"), msg);
        assertTrue(msg.contains("\"lt\""), "the message must name the low-bound rule: " + msg);
        assertTrue(msg.contains("\"gt\""), "the message must name the high-bound rule: " + msg);
        // The rule survives with no fail band, same as any other unreadable band — and a rule
        // with no band is INVALID, so it is loudly broken rather than quietly passing.
        assertEquals(1, r.rules().size());
        assertNull(r.rules().get(0).fail(), "a band that could not be read must not be invented");
        assertFalse(r.rules().get(0).hasBands(), "so the rule cannot decide anything");
    }

    @Test
    void oneBadRuleDoesNotTakeTheGoodOnesWithIt() throws Exception {
        AssertionRuleMapper.Result r = mapOf("["
                + "{\"id\":\"good-1\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"broken\",\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"good-2\",\"target\":{\"sampler\":\"B\"},\"metric\":\"p99\",\"fail\":{\"gt\":1}}"
                + "]");

        // All three, in file order — the broken one included, as INVALID. It used to be dropped,
        // which is how a gate quietly stopped being the size the file said it was.
        assertEquals(3, r.rules().size());
        assertEquals("good-1", r.rules().get(0).id());
        assertEquals("broken", r.rules().get(1).id());
        assertEquals(AssertionTarget.Kind.INVALID, r.rules().get(1).target().kind());
        assertEquals("good-2", r.rules().get(2).id());
    }

    @Test
    void aRuleWithNoIdIsReportedByItsPositionInTheFile() throws Exception {
        AssertionRuleMapper.Result r = mapOf("["
                + "{\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"metric\":\"p95\",\"fail\":{\"gt\":1}}"
                + "]");

        assertTrue(messages(r).contains("assertions[1]"),
                "a rule with no id and no usable target has nothing else to be called: "
                        + messages(r));
    }

    @Test
    void unknownTopLevelKeysSurviveARoundTrip() throws Exception {
        // The report saves this file back. Dropping what it did not understand would punish
        // anyone who hand-added a key, or who opened a file written by a newer plugin.
        String original = "{\"version\":\"1.0\",\"assertions\":[],"
                + "\"serverMetrics\":{\"prometheus\":\"http://x/metrics\"},"
                + "\"myTeamsOwnKey\":[1,2,3]}";

        ReportAnnotations a = json.readValue(original, ReportAnnotations.class);
        // serverMetrics is typed since M2 — no longer an unknown key — but it must round-trip
        // all the same, its own unknown keys included (ServerMetricsMapperTest pins the rest).
        assertFalse(a.getUnknownKeys().containsKey("serverMetrics"));
        assertNotNull(a.getServerMetrics());
        assertEquals("http://x/metrics", a.getServerMetrics().getUnknownKeys().get("prometheus"));
        assertTrue(a.getUnknownKeys().containsKey("myTeamsOwnKey"));

        String written = json.writeValueAsString(a);
        assertTrue(written.contains("\"serverMetrics\":{\"prometheus\":\"http://x/metrics\"}"), written);
        assertTrue(written.contains("myTeamsOwnKey"), written);
    }

    @Test
    void aQuotedNaNOrInfinityIsNotAThreshold() throws Exception {
        // Double.parseDouble accepts both. A NaN band never fires — every comparison against
        // NaN is false — so the rule would report PASS on every run with no finding, which is
        // the silent-pass shape the engine exists to refuse.
        for (String bad : new String[] {"NaN", "Infinity", "-Infinity"}) {
            AssertionRuleMapper.Result r = mapOf("[{\"id\":\"x\",\"target\":{\"sampler\":\"A\"},"
                    + "\"metric\":\"p95\",\"fail\":{\"gt\":\"" + bad + "\"}}]");
            assertNull(r.rules().get(0).fail(), bad + " must not become a band");
            assertFalse(r.rules().get(0).declarationProblems().isEmpty(),
                    bad + " must be recorded as an unreadable band, so the rule refuses");
        }
    }

    /**
     * The other arm of the test above. A bare JSON number past {@code Double.MAX_VALUE} overflows
     * to Infinity INSIDE the parser, so it reached this mapper as a Number — and only the text arm
     * refused NaN and the infinities. {@code gt 1e400} could not be breached: the rule PASSed every
     * run with no finding and exit 0, even under failonassert, and {@code lt 1e400} FAILed every
     * run. A band is now read through {@code ServerMetricsMapper.asNumber}, guarded on both arms,
     * and named as its {@code describe} names it — the server's words, for the same overflow its
     * own test of this name pins.
     *
     * <p>Bound through {@link AnnotationLoader#newObjectMapper()}, the run's mapper, not
     * {@link #mapOf}'s plain one. A 401-digit integer binds as a {@code BigInteger}, not a
     * {@code Double}, which is what pins the guard to {@code Number}.
     */
    @Test
    void aBandThatOverflowsToInfinityIsUnreadableEvenAsABareJsonNumber() throws Exception {
        ObjectMapper run = AnnotationLoader.newObjectMapper();
        String tenToThe400 = "1" + String.join("", Collections.nCopies(400, "0"));
        String[][] cases = {
            // the bands, the band refused, the finding
            {"\"fail\":{\"gt\":1e400}", "fail", "fail gt must be a number, not the number Infinity"},
            {"\"fail\":{\"lt\":-1e400}", "fail", "fail lt must be a number, not the number -Infinity"},
            {"\"fail\":{\"gt\":" + tenToThe400 + "}", "fail", "fail gt must be a number, not the number Infinity"},
            {"\"warn\":{\"gt\":1e400},\"fail\":{\"gt\":1}", "warn", "warn gt must be a number, not the number Infinity"},
        };
        for (String[] c : cases) {
            String label = c[0].length() > 60 ? c[0].substring(0, 60) + "…" : c[0];
            ReportAnnotations a = run.readValue("{\"assertions\":[{\"id\":\"x\",\"target\":{\"sampler\":\"A\"},"
                    + "\"metric\":\"p95\"," + c[0] + "}]}", ReportAnnotations.class);
            AssertionRuleMapper.Result r = mapper.map(a.getAssertions());
            AssertionRule rule = r.rules().get(0);

            assertNull("fail".equals(c[1]) ? rule.fail() : rule.warn(),
                    label + ": an infinite threshold is not a threshold");
            Usability refusal = Usability.refusal(rule);
            assertNotNull(refusal, label + ": the rule must be refused, not judged");
            assertEquals(Usability.Reason.BAND_UNREADABLE, refusal.reason(),
                    label + ": unreadable, and above all not merely unreachable");
            assertEquals(c[2] + " — so this rule declares a limit it cannot apply, and would report PASS"
                    + " without ever testing " + c[1], refusal.reasonText(), label);
            assertEquals(1, r.findings().size(), label + ": " + messages(r));
            assertEquals(c[2], r.findings().get(0).message(), label);
        }
    }

    @Test
    void enabledFalseParksTheRule() throws Exception {
        AssertionRuleMapper.Result r = mapOf("[{\"id\":\"parked\","
                + "\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":1},"
                + "\"enabled\":false}]");
        assertFalse(r.rules().get(0).enabled());
    }
}
