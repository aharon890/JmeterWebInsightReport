package com.jmeterwebinsightreport.report.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.AssertionRule;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.RuleResult;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declared {@code assertions} array actually decides a run.
 *
 * <p>Everything before this milestone was scaffolding: the engine existed, but nothing read the
 * key, so a file full of rules was silently ignored. These tests are the first that would have
 * failed at any earlier point.
 */
class GateEvaluationTest {

    private final ObjectMapper json = new ObjectMapper();

    private ReportAnnotations annotations(String body) throws Exception {
        return json.readValue(body, ReportAnnotations.class);
    }

    private static SamplerStatistics stat(String name, long count, double p95, double errorRate) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(count);
        s.setErrorCount(Math.round(count * errorRate / 100.0));
        s.setErrorRate(errorRate);
        s.setMeanResponseTime(p95 * 0.6);
        s.setMedianResponseTime(p95 * 0.5);
        s.setPercentile90(p95 * 0.9);
        s.setPercentile95(p95);
        s.setPercentile99(p95 * 1.3);
        s.setThroughput(10);
        s.setApdexScore(0.9);
        return s;
    }

    private static ReportData run(SamplerStatistics... stats) {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(stats)));
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        d.setMetadata(m);
        return d;
    }

    @Test
    void aDeclaredRuleDecidesTheRun() throws Exception {
        ReportData data = run(stat("POST /api/checkout", 900, 1120, 5.0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"checkout-p95\",\"target\":{\"sampler\":\"POST /api/checkout\"},"
                + "\"metric\":\"p95\",\"warn\":{\"gt\":650},\"fail\":{\"gt\":800}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertTrue(gate.hasGate());
        assertEquals(AssertionStatus.FAIL, gate.gate().status());
        assertEquals(AssertionStatus.FAIL, gate.overallStatus());
        assertEquals(1, gate.gate().failed());
    }

    @Test
    void theCanonicalCheckFromTheDesign() throws Exception {
        // "p90 of POST /checkout must stay under 1500ms. Nothing else decides whether this run
        // passed." One rule on one metric of one sampler is a complete, valid gate — and the
        // slow sampler beside it is data, not a verdict.
        ReportData data = run(
                stat("POST /checkout", 500, 1200, 0),
                stat("GET /catalogue-that-is-far-slower", 500, 9000, 40.0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"target\":{\"sampler\":\"POST /checkout\"},"
                + "\"metric\":\"p90\",\"fail\":{\"gt\":1500}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(AssertionStatus.PASS, gate.gate().status());
        assertEquals(1, gate.gate().declared(), "only what is declared is judged");
    }

    @Test
    void onlyWhatIsDeclaredUnderAssertionsBecomesARule() throws Exception {
        // A block this version does not recognise sits beside a real rule. Only the rule counts.
        ReportData data = run(stat("A", 100, 300, 0), stat("B", 100, 900, 0));
        ReportAnnotations a = annotations("{"
                + "\"assertions\":[{\"id\":\"a-tail\",\"target\":{\"sampler\":\"A\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":10000}}],"
                + "\"someUnknownBlock\":{\"B\":{\"p95\":500}}}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        // Nothing is translated into a rule behind the reader's back: what the file declares
        // under "assertions" is the whole gate.
        assertEquals(1, gate.gate().declared());
        assertEquals("a-tail", gate.rules().get(0).id());
    }

    /**
     * A legacy {@code loadValidity} block becomes an ordinary rule, not a special one.
     *
     * <p>It used to expand into a rule the report refused to edit, disable or delete, because the
     * rule was derived from a block the GUI could not write. Now it is migrated into
     * {@code assertions} and is indistinguishable from a hand-written rule — which is the whole
     * point: there is nothing left for the GUI to refuse.
     */
    @Test
    void aLegacyLoadValidityBlockBecomesAnOrdinaryRule() throws Exception {
        ReportData data = run(stat("A", 100, 300, 0));
        ReportAnnotations a = annotations(
                "{\"loadValidity\":{\"metric\":\"throughput\",\"target\":10,"
                        + "\"fail\":{\"tolerance\":0.5}}}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(1, gate.gate().declared());
        AssertionRule rule = gate.rules().get(0);
        assertEquals("load:throughput", rule.id());
        assertEquals("throughput", rule.metricName());
        // lt target x (1 - tolerance) = 10 x 0.5
        assertEquals(5.0, rule.fail().value(), 0.0001);
        // The block is consumed, so a save cannot write the same check twice.
        assertNull(a.getLoadValidity());
        assertEquals(1, a.getAssertions().size());
        assertTrue(gate.findings().stream().anyMatch(f -> f.message().contains("migrated")),
                "the tester has to be told the file moved on: " + gate.findings());
    }

    /**
     * The migration notice reaches a real run, which loads its file through AnnotationLoader
     * (GAP-1).
     *
     * <p>The loader migrated the block and threw the id away; the block was consumed, so the
     * second call here returned null and no finding was added. The test above builds its
     * annotations directly and so never took the path every run takes.
     */
    @Test
    void theMigrationNoticeSurvivesTheLoaderPath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.io.File file = tmp.resolve("report-annotations.json").toFile();
        java.nio.file.Files.write(file.toPath(), ("{\"loadValidity\":{\"metric\":\"throughput\","
                + "\"target\":10,\"fail\":{\"tolerance\":0.5}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.jmeterwebinsightreport.report.annotation.AnnotationLoader.Result loaded =
                new com.jmeterwebinsightreport.report.annotation.AnnotationLoader().read(file);

        GateEvaluation gate = GateEvaluation.of(run(stat("A", 100, 300, 0)), loaded.annotations(),
                loaded.problem());

        assertEquals(1, gate.gate().declared(), "precondition: the loader migrated the block");
        assertTrue(gate.findings().stream().anyMatch(f -> f.message().contains("migrated")
                        && f.severity() == com.jmeterwebinsightreport.core.assertion.AssertionValidator.Severity.WARNING),
                "a file loaded the way every run loads it must still be told it moved on: "
                        + gate.findings());
    }

    /**
     * When the migration notice is filed, and what the page knows it by (NIT-3): one WARNING under
     * the id of the rule the block became, whose sentence opens "the legacy loadValidity block was
     * migrated into assertions[] as '&lt;id&gt;'." — and only on a run whose file carried a block that
     * became a rule. A file with no block, a block that declares nothing (no target, or no band) and
     * a block beside the rule it already became are told nothing.
     *
     * <p>The page's findingMark recognises the notice by exactly that id and opening. It stands while
     * the page has no client draft — the next run reads the file this one did, block and all, and
     * files it again — and is fixed once it has one, whose Download writes the migrated rule and no
     * block; never by an edit of the rule, which the next run does not re-check for this.
     */
    @Test
    void theMigrationNoticeIsFiledUnderTheMigratedIdOnlyWhenABlockBecameARule() throws Exception {
        ReportData data = run(stat("A", 100, 300, 0));
        String notice = "loadValidity block was migrated";

        GateEvaluation migrated = GateEvaluation.of(data, annotations(
                "{\"loadValidity\":{\"target\":10,\"fail\":{\"tolerance\":0.5}}}"));
        List<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding> filed = new ArrayList<>();
        migrated.findings().forEach(f -> { if (f.message().contains(notice)) filed.add(f); });
        assertEquals(1, filed.size(), migrated.findings().toString());
        assertEquals(com.jmeterwebinsightreport.core.assertion.AssertionValidator.Severity.WARNING,
                filed.get(0).severity());
        assertEquals("load:throughput", filed.get(0).ruleId());
        assertTrue(filed.get(0).message().startsWith(
                "the legacy loadValidity block was migrated into assertions[] as 'load:throughput'."),
                filed.get(0).message());

        String rule = "{\"id\":\"load:throughput\",\"target\":{\"scope\":\"overall\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":5}}";
        for (String body : new String[] {
                "{\"assertions\":[" + rule + "]}",
                "{\"loadValidity\":{\"target\":10}}",
                "{\"loadValidity\":{\"fail\":{\"tolerance\":0.5}}}",
                "{\"loadValidity\":{\"target\":10,\"fail\":{\"tolerance\":0.5}},\"assertions\":[" + rule + "]}"}) {
            GateEvaluation gate = GateEvaluation.of(data, annotations(body));
            assertTrue(gate.findings().stream().noneMatch(f -> f.message().contains(notice)),
                    body + " was told it moved on: " + gate.findings());
        }
    }

    /** Idempotent: migrating twice must not declare the same check twice. */
    @Test
    void migratingTwiceDoesNotDuplicateTheRule() throws Exception {
        ReportAnnotations a = annotations(
                "{\"loadValidity\":{\"target\":10,\"fail\":{\"tolerance\":0.5}}}");

        a.migrateLegacyBlocks();
        a.migrateLegacyBlocks();

        assertEquals(1, a.getAssertions().size());
        assertNull(a.getLoadValidity());
    }

    /** A block that never declared a consequence declared nothing, and is still consumed. */
    @Test
    void aBlockWithNoBandsProducesNoRuleAndIsStillConsumed() throws Exception {
        ReportAnnotations a = annotations("{\"loadValidity\":{\"target\":10}}");

        assertNull(a.migrateLegacyBlocks());
        assertTrue(a.getAssertions().isEmpty());
        assertNull(a.getLoadValidity(),
                "a block that produces no rule must not survive to be migrated again next run");
    }

    /**
     * A migration that computes a band no measurement can be compared with refuses the rule.
     *
     * <p>{@code lt target × (1 − tolerance)} is NaN when the tolerance is the text "NaN" (Jackson
     * binds it into the Double field) and when an overflowed target meets a tolerance of 1
     * (Infinity × 0). Every comparison against NaN is false, so {@code load:throughput} PASSed
     * every run with no finding and exit 0. The rule is NOT_JUDGED / INVALID_RULE — a CONFIG ERROR —
     * and since L3 in a sentence naming the field the file wrote, which the migration records on
     * the rule: the band reader's own words ("fail lt must be a number, not the number NaN") named
     * a band nobody wrote.
     */
    @Test
    void aLoadValidityThatComputesANonFiniteBandRefusesTheRule() throws Exception {
        String[][] cases = {
                {"{\"loadValidity\":{\"target\":1000,\"fail\":{\"tolerance\":\"NaN\"}}}",
                        "loadValidity.fail.tolerance must be a finite number, not NaN"},
                {"{\"loadValidity\":{\"target\":1e400,\"fail\":{\"tolerance\":1}}}",
                        "loadValidity.target must be a finite number, not Infinity"}};
        for (String[] c : cases) {
            String block = c[0];
            String problem = c[1];
            ReportData data = run(stat("A", 100, 300, 0));
            GateEvaluation gate = GateEvaluation.of(data, annotations(block));

            assertEquals(1, gate.gate().declared(), block);
            RuleResult load = gate.gate().ruleResults().get(0);
            assertEquals("load:throughput", load.rule().id(), block);
            assertEquals(AssertionStatus.NOT_JUDGED, load.status(), block + ": never PASS against lt NaN");
            assertEquals(NotJudged.INVALID_RULE, load.notJudged(), block);
            assertEquals(problem + " — so this rule declares a limit it cannot apply, and would report PASS"
                    + " without ever testing fail", load.notJudgedDetail(), block);
            assertEquals(1, gate.gate().invalidRule(), block);
            assertTrue(gate.findings().stream().anyMatch(f -> "load:throughput".equals(f.ruleId())
                            && f.message().equals(problem)),
                    block + ": " + gate.findings());
        }
    }

    @Test
    void anUnknownMetricIsCountedInsteadOfPassingQuietly() throws Exception {
        ReportData data = run(stat("A", 100, 300, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"typo\",\"target\":{\"sampler\":\"A\"},"
                + "\"metric\":\"p95x\",\"fail\":{\"gt\":100}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        // Not PASS, which is the failure this exists to prevent — and not FAIL either, which
        // is the change A3 made. Nothing was measured, so no verdict comes out of it. The rule
        // is counted on its own so a pipeline can be strict by choice, and the finding names the
        // typo with a suggestion.
        assertNull(gate.gate().status(), "nothing was measured, so nothing was decided");
        assertEquals(1, gate.gate().invalidRule());
        assertEquals(0, gate.gate().passed(), "and above all it must never read as a pass");
        assertTrue(gate.findings().stream().anyMatch(f -> f.message().contains("did you mean 'p95'")),
                gate.findings().toString());
    }

    @Test
    void aTargetThatMatchesNothingFailsAndSaysWhatTheRunContained() throws Exception {
        ReportData data = run(stat("GET /health", 100, 50, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"ghost\",\"target\":{\"sampler\":\"GET /helth\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":100}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        // Not FAIL. Nothing was compared to anything, so no performance status comes out of
        // it — and not PASS either: null renders as NOT EVALUATED. What carries the problem is
        // the count and the finding, both asserted below.
        assertNull(gate.gate().status(),
                "an unmatched rule must not manufacture a verdict in either direction");
        assertEquals(1, gate.gate().noMatch(),
                "the count is what a pipeline gates on by choice");
        assertTrue(gate.findings().stream().anyMatch(
                f -> f.message().contains("Did you mean \"GET /health\"")),
                gate.findings().toString());
    }

    /**
     * A gate whose every rule is unmatched: declared, evaluated, and deciding nothing.
     *
     * <p>The point this test has always made is that the one-word status and the gate block must
     * describe the same run. Only the agreed answer has changed. It used to be FAIL on both
     * sides — the engine deciding a renamed sampler was a failure. It is now null on both sides,
     * rendered as NOT EVALUATED, while {@code hasGate()} stays true because rules WERE declared.
     * That distinction is the whole content of the test: "nothing was judged" is not the same
     * claim as "no gate exists".
     */
    @Test
    void anUnmatchedOnlyGateIsDeclaredButDecidesNothing() throws Exception {
        ReportData data = run(stat("GET /health", 100, 50, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"ghost\",\"target\":{\"sampler\":\"GET /typo\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":100}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertTrue(gate.verdicts().isEmpty(), "an unmatched rule judges no sampler, by definition");
        assertNull(gate.gate().status(), "nothing gated, so there is no verdict");
        assertTrue(gate.hasGate(),
                "assertsEvaluated must still be true — rules were declared, they judged nothing");
        assertNull(gate.overallStatus(),
                "the one-word status must agree with the gate block, and both say NOT EVALUATED");
    }

    /**
     * The one-word status and the gate block agree, because there is now only one of them.
     *
     * <p>This test used to assert they DIFFERED: {@code gate.status()} was INVALID and
     * {@code overallStatus()} was FAIL, because a seven-value rule status had to be translated
     * into the three-value word the badge speaks. The translation is gone, so the two fields
     * cannot drift — which is the whole point of the collapse, and worth pinning from the side
     * that used to prove the opposite.
     */
    @Test
    void theOneWordStatusIsTheGateStatus() throws Exception {
        ReportData data = run(stat("A", 100, 50, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"typo\",\"target\":{\"sampler\":\"A\"},"
                + "\"metric\":\"p95x\",\"fail\":{\"gt\":100}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertNull(gate.gate().status());
        assertEquals(gate.gate().status(), gate.overallStatus(),
                "one vocabulary, so these are the same field twice");
        assertTrue(gate.hasGate(), "rules were declared, they just decided nothing");
    }

    /**
     * A rule the loader could not read is counted, not dropped.
     *
     * <p>Three declared rules used to arrive as one, with {@code failed: 0} — so
     * {@code jq -e '.gate.failed == 0'} passed on a gate that had mostly not run.
     */
    @Test
    void aMalformedTargetIsAnInvalidRuleRatherThanASilentlyMissingOne() throws Exception {
        ReportData data = run(stat("A", 100, 50, 0));
        ReportAnnotations a = annotations("{\"assertions\":["
                + "{\"id\":\"good\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\",\"fail\":{\"gt\":10000}},"
                + "{\"id\":\"no-target\",\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"two-forms\",\"target\":{\"sampler\":\"A\",\"match\":\"B*\"},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":1}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(3, gate.gate().declared(), "every rule the file declared must be counted");
        assertEquals(2, gate.gate().invalidRule());
        // The third rule passed, so the gate says PASS — and the two broken ones are visible
        // beside it rather than folded into the word. `jq -e '.gate.failed == 0'` is no longer
        // enough on its own to call this run clean, which is why invalidRule is published:
        // `jq -e '.gate.failed == 0 and .gate.notJudged.invalidRule == 0'` is.
        assertEquals(AssertionStatus.PASS, gate.gate().status());
        assertEquals(1, gate.gate().passed());

        // And each one is reachable by id — the report table, the JUnit testcase and the
        // assertions array all key off this.
        List<String> ids = new ArrayList<>();
        gate.gate().ruleResults().forEach(r -> ids.add(r.rule().id()));
        assertEquals(Arrays.asList("good", "no-target", "two-forms"), ids);
    }

    @Test
    void anUnreadableTargetIsReportedOnceRatherThanTwice() throws Exception {
        // The placeholder target selects nothing, so the validator's "matched nothing in this
        // run" check fires on top of the loader's message — two findings for one mistake, and
        // the second is a consequence of the first rather than a separate thing to fix.
        ReportData data = run(stat("A", 100, 50, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"no-target\",\"metric\":\"p95\",\"fail\":{\"gt\":1}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(1, gate.findings().size(), gate.findings().toString());
        assertTrue(gate.findings().get(0).message().contains("no target"),
                gate.findings().toString());
    }

    @Test
    void aRuleThatJudgedNothingIsStillADeclaredRule() throws Exception {
        ReportData data = run(stat("A", 100, 9000, 0));
        // Targets a sampler this run does not contain, so there is nothing to judge.
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"watching\",\"target\":{\"sampler\":\"B\"},"
                + "\"metric\":\"p99\",\"fail\":{\"gt\":1}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(1, gate.gate().noMatch());
        assertNull(gate.gate().status(),
                "a gate whose only rule judged nothing has decided nothing, which is not PASS");
        assertNull(gate.overallStatus(),
                "and the legacy status field must not invent one either");
        // hasGate() is "was a gate declared", not "did it decide something" — and here one was.
        // The two facts are reported separately on purpose: `assertsEvaluated: true` with
        // `status: UNKNOWN` says "rules ran, none of them judges", which a pipeline can tell
        // apart from `assertsEvaluated: false` — no rules at all, i.e. the gate is not set up.
        assertTrue(gate.hasGate(), "a rule that judged nothing was still declared");
    }

    @Test
    void noAnnotationsAtAllIsNotAPass() throws Exception {
        GateEvaluation gate = GateEvaluation.of(run(stat("A", 100, 300, 0)), null);

        assertFalse(gate.hasGate());
        assertNull(gate.overallStatus());
        assertEquals(0, gate.gate().declared());
    }

    @Test
    void anEmptyAssertionsArrayIsAlsoNotAPass() throws Exception {
        GateEvaluation gate = GateEvaluation.of(
                run(stat("A", 100, 300, 0)), annotations("{\"assertions\":[]}"));
        assertFalse(gate.hasGate());
    }

    @Test
    void aWholeRunRuleJudgesTheAggregate() throws Exception {
        // 200 samples over 60s is 3.33/s.
        ReportData data = run(stat("A", 100, 300, 0), stat("B", 100, 300, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"run-throughput\",\"target\":{\"scope\":\"overall\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":10}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(AssertionStatus.FAIL, gate.gate().status());
        assertEquals(3.33, gate.gate().ruleResults().get(0).matches().get(0).actual(), 0.01);
        // Read as "Whole run", keyed apart from any sampler that could be called that (CORE-1).
        assertEquals("Whole run", gate.gate().ruleResults().get(0).matches().get(0).label());
        assertEquals(com.jmeterwebinsightreport.core.assertion.AssertionTarget.OVERALL_KEY,
                gate.gate().ruleResults().get(0).matches().get(0).samplerName());
    }

    @Test
    void aPercentileNoOtherToolWouldOfferIsAssertable() throws Exception {
        // The point of retaining the digests: p99.9 is on no model field and no report column.
        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample("S", i, true, 500, 100, 1_700_000_000_000L + i, 5, 10);
        }
        ReportData data = engine.buildReportData("T");
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"tail\",\"target\":{\"sampler\":\"S\"},"
                + "\"metric\":\"p99.9\",\"fail\":{\"gt\":990}}]}");

        GateEvaluation gate = GateEvaluation.of(data, a);

        assertEquals(AssertionStatus.FAIL, gate.gate().status());
        assertEquals(999.0, gate.gate().ruleResults().get(0).matches().get(0).actual(), 3.0);
    }

    @Test
    void findingsSurviveOnTheEvaluationForTheReportAndTheCiJson() throws Exception {
        // Three places have to show them: the log, the CI/CD JSON, and a notice in the report.
        // Keeping them on the evaluation is what makes the other two possible.
        ReportData data = run(stat("A", 100, 300, 0));
        ReportAnnotations a = annotations("{\"assertions\":[{"
                + "\"id\":\"dead-warn\",\"target\":{\"sampler\":\"A\"},\"metric\":\"p95\","
                + "\"warn\":{\"gt\":500},\"fail\":{\"gt\":400}}]}");

        List<String> messages = new ArrayList<>();
        GateEvaluation.of(data, a).findings().forEach(f -> messages.add(f.message()));

        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("Swapped values?"), messages.toString());
    }

    @Test
    void theShippedSchemaIsOnTheClasspathWhereTheJarGuardAllowsIt() {
        // ALLOWED_JAR_ROOTS only permits com/jmeterwebinsightreport/, static/, templates/ and
        // META-INF/. A schema under any other root fails the build with a message about
        // relocations, which would be a confusing way to learn this.
        assertTrue(GateEvaluation.class.getClassLoader()
                        .getResource("static/schema/report-annotations.schema.json") != null,
                "the JSON Schema must ship inside the JAR, under an allowlisted root");
    }
}
