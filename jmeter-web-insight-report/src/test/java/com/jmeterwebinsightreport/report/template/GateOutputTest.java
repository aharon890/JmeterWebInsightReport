package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pipeline reads: the {@code gate} block, the {@code assertions} array and the JUnit
 * testsuite the rules get.
 */
class GateOutputTest {

    private final ObjectMapper json = new ObjectMapper();

    private static SamplerStatistics stat(String name, double p95, double errorRate) {
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName(name);
        s.setSampleCount(100);
        s.setErrorCount(Math.round(errorRate));
        s.setErrorRate(errorRate);
        s.setMeanResponseTime(p95 * 0.6);
        s.setMedianResponseTime(p95 * 0.5);
        s.setPercentile90(p95 * 0.9);
        s.setPercentile95(p95);
        s.setPercentile99(p95 * 1.2);
        s.setThroughput(5);
        s.setApdexScore(0.9);
        return s;
    }

    private static ReportData run() {
        ReportData d = new ReportData();
        d.setSamplerStatistics(new ArrayList<>(Arrays.asList(
                stat("GET /fast", 100, 0), stat("GET /slow", 2000, 0))));
        TestMetadata m = new TestMetadata();
        m.setTestName("Gate Output");
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        d.setMetadata(m);
        return d;
    }

    /** One rule of each interesting shape. */
    private GateEvaluation evaluation() throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":["
                + "{\"id\":\"fast-ok\",\"description\":\"budget\",\"target\":{\"sampler\":\"GET /fast\"},"
                + "  \"metric\":\"p95\",\"fail\":{\"gt\":500}},"
                + "{\"id\":\"slow-breach\",\"target\":{\"sampler\":\"GET /slow\"},"
                + "  \"metric\":\"p95\",\"warn\":{\"gt\":500},\"fail\":{\"gt\":1000}},"
                + "{\"id\":\"ghost\",\"target\":{\"sampler\":\"GET /nope\"},"
                + "  \"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"watch\",\"target\":{\"sampler\":\"GET /slow\"},"
                + "  \"metric\":\"p99\",\"warn\":{\"gt\":2000}},"
                + "{\"id\":\"parked\",\"target\":{\"sampler\":\"GET /fast\"},"
                + "  \"metric\":\"p95\",\"fail\":{\"gt\":1},\"enabled\":false},"
                + "{\"id\":\"broken\",\"target\":{\"sampler\":\"GET /fast\"},"
                + "  \"metric\":\"nope\",\"fail\":{\"gt\":1}}"
                + "]}", ReportAnnotations.class);
        return GateEvaluation.of(run(), a);
    }

    // ---------------------------------------------------------------- CI/CD JSON

    /**
     * The budget travels with every banded rule, passing ones included.
     *
     * <p>{@code threshold} is emitted only inside a match that breached, so a green gate carried
     * measured values and nothing to measure them against — a consumer could see p95 = 100 and
     * not tell whether the limit was 500 or 5000. "How close was it?" is the first question asked
     * of a passing gate and the one a trend dashboard is built on.
     */
    @Test
    void everyBandedRuleCarriesItsBudgetEvenWhenItPassed() throws Exception {
        GateEvaluation e = evaluation();
        JsonNode assertions = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()))
                .get("assertions");

        JsonNode passing = entry(assertions, "fast-ok");
        assertEquals("PASS", passing.get("status").asText());
        JsonNode budget = passing.get("attainment");
        assertNotNull(budget, "a passing rule must still report its budget: " + passing);
        assertEquals(100.0, budget.get("actual").asDouble(), 0.001);
        assertEquals(500.0, budget.get("failThreshold").asDouble(), 0.001);
        assertEquals(0.2, budget.get("consumed").asDouble(), 0.001);
        assertFalse(budget.get("breached").asBoolean());

        JsonNode breaching = entry(assertions, "slow-breach");
        assertEquals(2.0, breaching.get("attainment").get("consumed").asDouble(), 0.001,
                "past the limit, consumed must exceed 1 and say by how much");
        assertTrue(breaching.get("attainment").get("breached").asBoolean());

        // Absent, not zeroed. A budget is measured against the FAIL band, so a warn-only rule
        // has none — and `consumed: 0` would read as "comfortably inside" for a rule that has
        // nothing to be inside of. Same for a rule that matched nothing, and an invalid one.
        assertFalse(entry(assertions, "watch").has("attainment"));
        assertFalse(entry(assertions, "ghost").has("attainment"));
        assertFalse(entry(assertions, "broken").has("attainment"));
    }

    /**
     * A threshold below the rounding grain must survive into the artifact.
     *
     * <p>Found by reading a regenerated fixture rather than by a test: E25 declares
     * {@code run-throughput} as {@code fail: {lt: 0.001}}, and rounding the budget to 2dp
     * published {@code failThreshold: 0.0} — a limit of zero, which no throughput can go under
     * and which is not the number anyone wrote. The same rounding flattened {@code consumed} to
     * {@code 0.0} for every band wider than ~50x its measurement, making a comfortable pass
     * indistinguishable from a rule that measured nothing.
     */
    @Test
    void aThresholdFinerThanTheRoundingGrainIsNotFlattenedToZero() throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":["
                + "{\"id\":\"floor\",\"target\":{\"scope\":\"overall\"},"
                + "  \"metric\":\"throughput\",\"fail\":{\"lt\":0.001}}"
                + "]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        JsonNode budget = entry(json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()))
                .get("assertions"), "floor").get("attainment");

        assertEquals(0.001, budget.get("failThreshold").asDouble(), 1e-12,
                "the declared limit must be echoed, not quantised away");
        assertTrue(budget.get("consumed").asDouble() > 0,
                "a ratio this small is still not zero: " + budget);
    }

    private static JsonNode entry(JsonNode assertions, String id) {
        for (JsonNode n : assertions) {
            if (id.equals(n.get("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("no assertion entry for " + id + " in " + assertions);
    }

    @Test
    void theGateCountsAccountForEveryDeclaredRule() throws Exception {
        // The point of publishing counts at all: jq -e '.gate.failed == 0' beats string-matching
        // a status that cannot tell WARN from PASS. That is only trustworthy if nothing falls
        // between the buckets, so a job can check the arithmetic itself.
        GateEvaluation e = evaluation();
        JsonNode gate = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate())).get("gate");

        assertEquals(6, gate.get("declared").asInt());
        JsonNode nj = gate.get("notJudged");
        int sum = gate.get("passed").asInt() + gate.get("warned").asInt()
                + gate.get("failed").asInt() + nj.get("total").asInt();
        assertEquals(gate.get("declared").asInt(), sum,
                "every rule must land in exactly one bucket: " + gate);

        // And the second arithmetic, which is the one that makes notJudged.invalidRule
        // trustworthy as a gating number: the reasons account for every not-judged rule.
        // Summed from the enum, and every key demanded present — the server-only reasons
        // included, as zeroes — because a key that appears only when non-zero is a key a
        // pipeline's own sum check cannot rely on.
        int reasons = 0;
        for (NotJudged r : NotJudged.values()) {
            assertTrue(nj.has(r.countKey()), "gate.notJudged must carry " + r.countKey() + ": " + nj);
            reasons += nj.get(r.countKey()).asInt();
        }
        assertEquals(NotJudged.values().length + 1, nj.size(),
                "gate.notJudged is total plus exactly one key per reason: " + nj);
        assertEquals(nj.get("total").asInt(), reasons,
                "every not-judged rule must land in exactly one reason: " + nj);
    }

    @Test
    void theGateBlockCarriesTheVerdictAndItsMakeUp() throws Exception {
        GateEvaluation e = evaluation();
        JsonNode gate = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate())).get("gate");

        // FAIL, not INVALID. The worst rule that DECIDED anything decides, and `broken` decided
        // nothing — it used to outrank slow-breach and put the word INVALID on a run where a
        // real threshold had really been breached, burying the actionable fact under the typo.
        assertEquals("FAIL", gate.get("status").asText(), "the worst JUDGED rule decides");
        assertEquals(1, gate.get("passed").asInt());
        assertEquals(1, gate.get("failed").asInt(), "slow-breach only");
        assertEquals(1, gate.get("warned").asInt(), "watch is over its warn band");

        // Three rules decided nothing, for three different reasons, and each is counted where
        // its own fix lives: ghost has a typo in its target, parked was parked on purpose,
        // broken has a metric name that does not exist.
        JsonNode nj = gate.get("notJudged");
        assertEquals(3, nj.get("total").asInt());
        assertEquals(1, nj.get("noMatch").asInt(), "ghost");
        assertEquals(1, nj.get("disabled").asInt(), "parked");
        assertEquals(1, nj.get("invalidRule").asInt(), "broken — an unknown metric");
        assertEquals(0, nj.get("noValue").asInt());
        assertEquals(5, gate.get("evaluated").asInt(), "declared minus the parked one");
    }

    @Test
    void everyMatchIsPublishedIncludingThePassingOnes() throws Exception {
        // A verdict alone does not say which of the matched samplers breached, nor how close
        // the others were — which is what a tester needs in order to move a threshold.
        ReportData data = new ReportData();
        data.setSamplerStatistics(new ArrayList<>(Arrays.asList(
                stat("GET /a", 100, 0), stat("GET /b", 2000, 0), stat("GET /c", 300, 0))));
        TestMetadata m = new TestMetadata();
        m.setDurationMillis(60_000);
        data.setMetadata(m);

        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"blanket\","
                + "\"target\":{\"match\":\"GET /*\"},\"metric\":\"p95\",\"fail\":{\"gt\":1000}}]}",
                ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(data, a);

        JsonNode entry = json.readTree(new CiCdJsonSerializer(json)
                .serialize(data, e.verdicts(), e.overallStatus(), null, e.gate()))
                .get("assertions").get(0);

        assertEquals(3, entry.get("matched").asInt());
        assertEquals(3, entry.get("results").size());
        assertEquals("FAIL", entry.get("results").get(1).get("status").asText());
        assertEquals("gt", entry.get("results").get(1).get("predicate").asText());
        assertEquals(1000.0, entry.get("results").get(1).get("threshold").asDouble(), 0.001);
        assertEquals("PASS", entry.get("results").get(0).get("status").asText());
    }

    /**
     * A file carrying no assertions declares NOTHING, and the run says so.
     *
     * <p>Assertions are the one mechanism, so a block this version does not understand produces
     * no assertion entry and no gate — which means the run is honestly NOT EVALUATED rather than
     * quietly passing. That distinction is the whole point: a pipeline that gated yesterday and
     * does not today must be able to tell from the artifact.
     */
    @Test
    void aFileWithNoAssertionsDeclaresNothingAndIsNotEvaluated() throws Exception {
        ReportAnnotations a = json.readValue(
                "{\"someUnknownBlock\":{\"GET /slow\":{\"p95\":500}}}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        assertFalse(e.hasGate(), "an unrecognised block must not declare a gate");

        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()));
        assertFalse(out.has("assertions"), "no rule was declared, so none may be reported");
        assertFalse(out.path("assertsEvaluated").asBoolean(),
                "a run gated by nothing has not been evaluated");
    }

    @Test
    void theGateBlockIsAbsentWhenNothingWasDeclared() throws Exception {
        // A run with no gate has not passed one. An empty gate block would read like it had.
        String out = new CiCdJsonSerializer(json).serialize(run(), null, null, null, null);
        JsonNode parsed = json.readTree(out);
        assertTrue(parsed.path("gate").isMissingNode());
        assertTrue(parsed.path("assertions").isMissingNode());
        assertFalse(parsed.path("assertsEvaluated").asBoolean());
    }

    /**
     * The one-word {@code status} and the {@code gate} block must describe the same run.
     *
     * <p>They did not. {@code SamplerVerdicts} only folds results that have matches, so a gate
     * of one unmatched rule left the map empty — and the file went out with
     * {@code "status": "UNKNOWN"} and {@code "assertsEvaluated": false} three lines above a
     * {@code gate} block reading FAIL, with the JUnit XML failing the build. A pipeline reading
     * the field it has always read was told the gate was not set up.
     */
    @Test
    void theOneWordStatusAgreesWithTheGateBlock() throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"ghost\","
                + "\"target\":{\"sampler\":\"GET /nope\"},\"metric\":\"p95\",\"fail\":{\"gt\":1}}]}",
                ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()));

        // The point of this test is that the two fields AGREE. Only the agreed answer has
        // changed: an unmatched rule decides nothing, so neither side reports a verdict.
        assertEquals("NOT_EVALUATED", out.get("gate").get("status").asText());
        assertEquals("UNKNOWN", out.get("status").asText(),
                "the one-word field must agree with the gate block, not contradict it");
        assertTrue(out.get("assertsEvaluated").asBoolean(),
                "the gate WAS declared and did run — it simply found nothing to judge");
    }

    @Test
    void aPassingRuleCannotMaskRulesThatCouldNotRun() throws Exception {
        // Same seam from the other side: the passing rule folds into a sampler, the two
        // unreadable ones fold into nothing, and `status` used to report only the fold.
        ReportAnnotations a = json.readValue("{\"assertions\":["
                + "{\"id\":\"ok\",\"target\":{\"sampler\":\"GET /fast\"},\"metric\":\"p95\","
                + "  \"fail\":{\"gt\":100000}},"
                + "{\"id\":\"no-target\",\"metric\":\"p95\",\"fail\":{\"gt\":1}},"
                + "{\"id\":\"bad-scope\",\"target\":{\"scope\":\"global\"},\"metric\":\"p95\","
                + "  \"fail\":{\"gt\":1}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()));

        assertEquals(3, out.get("gate").get("declared").asInt(),
                "all three rules must be counted, malformed targets included");
        assertEquals(2, out.get("gate").get("notJudged").get("invalidRule").asInt());
        // The passing rule DOES set the verdict now, and that is the deliberate change: a
        // verdict has to come from a measurement, and only `ok` measured anything. What must
        // not happen is the other two vanishing — they are counted above, and a pipeline that
        // wants them fatal reads that count. The name of this test is the contract: masking
        // means going unreported, not going ungraded.
        assertEquals("PASS", out.get("gate").get("status").asText());
        assertEquals("PASS", out.get("status").asText(),
                "the one-word status and the gate block are the same field twice");
    }

    @Test
    void everyPreExistingKeyKeepsItsPlace() throws Exception {
        // The gate is additive to this file too. Pipelines parse it today.
        //
        // The retired spellings of two of these are gone — the last release
        // in which a reader could have been speaking that dialect on purpose.
        GateEvaluation e = evaluation();
        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(run(), e.verdicts(), e.overallStatus(), null, e.gate()));

        for (String key : new String[]{"testName", "status", "assertsEvaluated", "duration",
                "totalSamples", "totalErrors", "errorRate", "samplers", "assertViolations",
                "generatedAt"}) {
            assertTrue(out.has(key), "pre-existing key '" + key + "' disappeared");
        }
    }

    /**
     * A sampler literally named "Whole run" keeps its own assertStatus, and the whole run's
     * violation still reads "Whole run" (CORE-1). The run's verdict was filed under its display
     * text, so the sampler's PASS read FAIL; {@code assertViolations[].sampler} is a contract
     * pipelines read, and its text does not move.
     */
    @Test
    void aSamplerNamedWholeRunKeepsItsOwnVerdictInTheJson() throws Exception {
        ReportData data = new ReportData();
        data.setSamplerStatistics(new ArrayList<>(Arrays.asList(
                stat("Whole run", 100, 0), stat("GET /slow", 2000, 0))));
        data.setMetadata(run().getMetadata());
        ReportAnnotations a = json.readValue("{\"assertions\":["
                + "{\"id\":\"named\",\"target\":{\"sampler\":\"Whole run\"},\"metric\":\"p95\","
                + "  \"fail\":{\"gt\":500}},"
                + "{\"id\":\"run-tp\",\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\","
                + "  \"fail\":{\"lt\":100}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(data, a);
        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(data, e.verdicts(), e.overallStatus(), null, e.gate()));

        JsonNode named = null;
        for (JsonNode s : out.get("samplers")) {
            if ("Whole run".equals(s.get("name").asText())) named = s;
        }
        assertNotNull(named, out.toString());
        assertEquals("PASS", named.get("assertStatus").asText(),
                "the sampler took the whole run's FAIL: " + named);
        assertEquals(1, out.get("assertViolations").size(), out.get("assertViolations").toString());
        assertEquals("Whole run", out.get("assertViolations").get(0).get("sampler").asText());
        assertEquals("FAIL", out.get("assertViolations").get(0).get("status").asText());
        assertEquals("Whole run", out.get("assertions").get(1).get("results").get(0).get("sampler").asText());
    }

    // ---------------------------------------------------------------- JUnit XML

    @Test
    void everyRuleGetsATestcaseIncludingTheRunLevelOnes(@TempDir Path tmp) throws Exception {
        // A scope:"overall" rule has no sampler, so it could never appear in the per-sampler
        // suite — the gate's broadest checks were the ones JUnit could not express at all.
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"run-tp\","
                + "\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\",\"fail\":{\"lt\":100}}]}",
                ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        String xml = write(tmp, e);

        assertTrue(xml.contains("classname=\"assertions\""));
        assertTrue(xml.contains("name=\"run-tp\""));
        assertTrue(xml.contains("Whole run"), xml);
    }

    @Test
    void aWarnIsRecordedWithoutFailingTheBuild(@TempDir Path tmp) throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"warned\","
                + "\"target\":{\"sampler\":\"GET /slow\"},\"metric\":\"p95\","
                + "\"warn\":{\"gt\":500},\"fail\":{\"gt\":99999}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        String xml = write(tmp, e);
        String suite = xml.substring(xml.indexOf("- assertions"));

        assertTrue(suite.contains("failures=\"0\""),
                "a build that stopped on a warning would make warn bands pointless");
        assertTrue(suite.contains("WARN: Assertion"), "but it must not vanish either: " + suite);
    }

    @Test
    void aDisabledRuleIsSkippedNotPassed(@TempDir Path tmp) throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"parked\","
                + "\"target\":{\"sampler\":\"GET /fast\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1},\"enabled\":false},"
                + "{\"id\":\"live\",\"target\":{\"sampler\":\"GET /fast\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":99999}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        String suite = write(tmp, e);
        assertTrue(suite.contains("<skipped message=\"rule was parked with enabled: false"), suite);
    }

    /**
     * A rule the file got wrong is {@code <failure>}, and the message says what is wrong.
     *
     * <p>It was briefly {@code <skipped>}, on the reasoning that nothing was measured so nothing
     * could have failed. That reasoning is right about the STATUS — {@code gate.status} is
     * untouched by this, and a broken rule still contributes no verdict — and wrong about the
     * artifact: {@code <skipped>} is invisible in the dashboards people actually watch, so a
     * broken rule vanished from Jenkins entirely. A gate that stopped gating and told nobody is
     * the failure this whole feature exists to prevent.
     *
     * <p>The message is the same sentence either way: it names the metric and its suggestion, so
     * a CI report showing only this XML still says what to fix.
     */
    @Test
    void aRuleThatCouldNotRunIsAFailureAndSaysWhy(@TempDir Path tmp) throws Exception {
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"broken\","
                + "\"target\":{\"sampler\":\"GET /fast\"},\"metric\":\"nope\","
                + "\"fail\":{\"gt\":1}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(run(), a);

        String xml = write(tmp, e);
        String suite = xml.substring(xml.indexOf("- assertions"));

        assertTrue(suite.contains("failures=\"1\""),
                "a rule the FILE got wrong must be visible in a CI dashboard: " + suite);
        assertTrue(suite.contains("skipped=\"0\""),
                "and must not also be counted as skipped: " + suite);
        assertTrue(suite.contains("could not run"), suite);
        assertTrue(suite.contains("unknown metric"),
                "the message must name the problem, not just report a failure: " + suite);
        // The STATUS is untouched. This is an artifact-shape decision, not a verdict one.
        assertNull(e.gate().status(),
                "nothing was measured, so the gate still decides nothing");
    }

    /**
     * X1. A sampler whose every sample took 0 ms had a max of 4.9E-324: {@code Double.MIN_VALUE},
     * the smallest positive double, which {@code SamplerAggregator} had taken for the most negative
     * one. So {@code max gt 0} FAILed a sampler that never took a millisecond, the JSON wrote
     * {@code "actual": 5e-324} beside it, and JUnit - whose never-zero guard keeps every digit that
     * separates a value from 0 - printed some 320 zeros. Through the -g -o path's own parser, so
     * the number judged is the one a real JTL produces; the listener's path is the same aggregator,
     * pinned in {@code LiveStatisticsEngineTest}.
     */
    @Test
    void anAllZeroSamplerHasAMaxOfZeroOnEverySurface(@TempDir Path tmp) throws Exception {
        Path jtl = tmp.resolve("zero.jtl");
        Files.write(jtl, ("timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,"
                + "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000000,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000100,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000200,0,ZERO,200,OK,T 1-1,text,true,,100,50,1,1,null,0,0,0\n"
                + "1700000000300,25,REAL,200,OK,T 1-1,text,true,,100,50,1,1,null,20,0,1\n")
                .getBytes(StandardCharsets.UTF_8));
        ReportData data = new com.jmeterwebinsightreport.core.parser.JtlParser().parse(jtl.toFile(), "X1");
        SamplerStatistics zero = data.getSamplerStatistics().stream()
                .filter(s -> "ZERO".equals(s.getSamplerName())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(0.0, zero.getMaxResponseTime(), "no sample took 4.9E-324 ms");
        assertEquals(0.0, zero.getMinResponseTime());

        ReportAnnotations a = json.readValue("{\"assertions\":["
                + "{\"id\":\"zero-max\",\"target\":{\"sampler\":\"ZERO\"},\"metric\":\"max\",\"fail\":{\"gt\":0}},"
                + "{\"id\":\"zero-min\",\"target\":{\"sampler\":\"ZERO\"},\"metric\":\"min\",\"fail\":{\"gt\":0}}"
                + "]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(data, a);
        JsonNode out = json.readTree(new CiCdJsonSerializer(json)
                .serialize(data, e.verdicts(), e.overallStatus(), null, e.gate()));
        assertEquals("PASS", out.get("gate").get("status").asText(), out.toString());
        JsonNode max = out.get("assertions").get(0);
        assertEquals("zero-max", max.get("id").asText());
        assertEquals("PASS", max.get("status").asText(), "max gt 0 does not fire on a sampler at 0 ms: " + max);
        assertEquals(0.0, max.get("results").get(0).get("actual").asDouble(), max.toString());

        String xml = new JunitXmlWriter().render(data, e.verdicts(), "X1", e.gate());
        assertFalse(xml.contains("<failure"), xml);
        assertFalse(xml.contains("0000000000"), "no 4.9E-324 written out in full: " + xml);
    }

    private String write(Path tmp, GateEvaluation e) throws Exception {
        File out = tmp.resolve("junit.xml").toFile();
        new JunitXmlWriter().write(run(), e.verdicts(), "Gate Output", out, e.gate());
        return new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
    }
}
