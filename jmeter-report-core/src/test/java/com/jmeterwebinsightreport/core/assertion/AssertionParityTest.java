package com.jmeterwebinsightreport.core.assertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.PercentileSource;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@code assert-parity-vectors.json} against the Java engine.
 *
 * <p>The same file is run against the report's client-side evaluator by a Playwright spec. That
 * is the whole point: phase 2 lets a tester tune a threshold in the browser, watch the verdict
 * recompute, save the file, and have the next {@code jmeter -g} reproduce it — which is only
 * true if the two evaluators agree, and two independently written implementations drift between
 * the runs that test them. Neither owns the definition; the vector file does.
 */
class AssertionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode vectors() throws IOException {
        try (InputStream in = AssertionParityTest.class.getResourceAsStream(
                "/assertion/assert-parity-vectors.json")) {
            assertNotNull(in, "assert-parity-vectors.json is missing from the test resources");
            return MAPPER.readTree(in);
        }
    }

    // ---------------------------------------------------------------- fixture

    static ReportData reportData(JsonNode root) {
        List<SamplerStatistics> stats = new ArrayList<>();
        for (JsonNode s : root.get("samplers")) {
            SamplerStatistics stat = new SamplerStatistics();
            stat.setSamplerName(s.get("name").asText());
            stat.setSampleCount(s.get("sampleCount").asLong());
            stat.setErrorCount(s.get("errorCount").asLong());
            stat.setErrorRate(s.get("errorRate").asDouble());
            stat.setMeanResponseTime(s.get("mean").asDouble());
            stat.setMedianResponseTime(s.get("median").asDouble());
            stat.setPercentile90(s.get("p90").asDouble());
            stat.setPercentile95(s.get("p95").asDouble());
            stat.setPercentile99(s.get("p99").asDouble());
            stat.setMinResponseTime(s.get("min").asDouble());
            stat.setMaxResponseTime(s.get("max").asDouble());
            stat.setStandardDeviation(s.get("stdDev").asDouble());
            stat.setThroughput(s.get("throughput").asDouble());
            stat.setApdexScore(s.get("apdex").asDouble());
            // Required, not optional: a field absent here would be 0.0 in Java and `undefined`
            // in the browser, and undefined resolves to NaN — so a missing vector field would
            // make the two sides disagree for a reason that has nothing to do with the engine.
            stat.setMeanLatency(s.get("meanLatency").asDouble());
            stat.setMeanConnectTime(s.get("meanConnectTime").asDouble());
            stat.setReceivedBytesPerSec(s.get("receivedBytesPerSec").asDouble());
            stat.setSentBytesPerSec(s.get("sentBytesPerSec").asDouble());
            if (s.hasNonNull("parent")) {
                stat.setParentSamplerName(s.get("parent").asText());
            }
            if (s.path("transactionController").asBoolean(false)) {
                stat.setTransactionController(true);
            }
            stats.add(stat);
        }

        ReportData data = new ReportData();
        data.setSamplerStatistics(stats);

        // The error summaries a class= or code= rule counts. Aggregated from an unbounded
        // counter map in the real engine, and carried into the payload whole — so both sides
        // see the same set and a selector cannot answer differently in the browser.
        java.util.List<com.jmeterwebinsightreport.core.model.ErrorSummary> summaries =
                new java.util.ArrayList<>();
        if (root.hasNonNull("errorSummaries")) {
            for (com.fasterxml.jackson.databind.JsonNode e : root.get("errorSummaries")) {
                com.jmeterwebinsightreport.core.model.ErrorSummary es =
                        new com.jmeterwebinsightreport.core.model.ErrorSummary();
                es.setSamplerName(e.get("samplerName").asText());
                es.setResponseCode(e.get("responseCode").asText());
                es.setOccurrenceCount(e.get("occurrenceCount").asLong());
                summaries.add(es);

                // The vector's errorClass is what the BROWSER is handed — the payload carries
                // the token, and report.js reads it rather than classifying. Java derives it
                // from the code instead, so if a row's stated class and its derived class
                // disagree, the two evaluators are being fed different data by the very file
                // whose job is that they aren't.
                //
                // This was real: {"responseCode": "CHILD-ONLY", "errorClass": "protocol"}
                // derives to transport here and read as protocol there, and no assertion
                // noticed because nothing compared them. Checked at load, so it fails on the
                // vector file rather than on some downstream count.
                if (e.hasNonNull("errorClass")) {
                    org.junit.jupiter.api.Assertions.assertEquals(
                            e.get("errorClass").asText(), es.getErrorClass(),
                            "vector row " + es.getSamplerName() + "/" + es.getResponseCode()
                                    + " states an errorClass the engine does not derive — the "
                                    + "browser would read the stated one and Java the derived "
                                    + "one, so the two sides are not being compared");
                }
            }
        }
        data.setErrorSummaries(summaries);

        List<String> hidden = new ArrayList<>();
        for (JsonNode h : root.get("hiddenSamplers")) {
            hidden.add(h.asText());
        }
        data.setHiddenSamplers(hidden);

        TestMetadata meta = new TestMetadata();
        meta.setTestName("Parity");
        meta.setDurationMillis(root.get("metadata").get("durationMillis").asLong());
        meta.setTotalThreads(root.get("metadata").get("totalThreads").asInt());
        data.setMetadata(meta);

        attachExtraPercentiles(data, root);
        return data;
    }

    /**
     * The off-grid quantiles, as a {@link PercentileSource} per sampler.
     *
     * <p>A lookup table rather than a digest: the vectors have to name exact expected values,
     * and a t-digest's answer depends on how it was fed. A sampler absent from the block has no
     * source at all, which is the case a real run hits when nothing retained its distribution —
     * and which must be INVALID rather than zero.
     */
    private static void attachExtraPercentiles(ReportData data, JsonNode root) {
        JsonNode block = root.get("extraPercentiles");
        if (block == null) {
            return;
        }
        Map<String, PercentileSource> perSampler = new LinkedHashMap<>();
        PercentileSource overall = null;

        java.util.Iterator<String> names = block.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            final JsonNode quantiles = block.get(name);
            PercentileSource source = new PercentileSource() {
                @Override
                public double percentile(double p) {
                    JsonNode value = quantiles.get(percentileKey(p));
                    return value == null ? Double.NaN : value.asDouble();
                }

                @Override
                public long sampleCount() {
                    return 1000;
                }
            };
            if (AssertionTarget.OVERALL_KEY.equals(name)) {
                overall = source;
            } else {
                perSampler.put(name, source);
            }
        }
        data.attachPercentileSources(perSampler, overall);
    }

    /** {@code 85.0 -> "85"}, {@code 97.5 -> "97.5"} — the spelling the vector file uses. */
    private static String percentileKey(double p) {
        return p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p);
    }

    /** Build a rule from the same JSON shape the annotations file uses. */
    static AssertionRule rule(JsonNode r) {
        JsonNode t = r.get("target");
        AssertionTarget target;
        if (t.hasNonNull("scope")) {
            target = AssertionTarget.overall();
        } else if (t.hasNonNull("sampler")) {
            target = AssertionTarget.sampler(t.get("sampler").asText());
        } else if (t.hasNonNull("samplers")) {
            List<String> names = new ArrayList<>();
            for (JsonNode n : t.get("samplers")) names.add(n.asText());
            target = AssertionTarget.samplers(names);
        } else {
            List<String> excludes = new ArrayList<>();
            if (t.hasNonNull("exclude")) {
                for (JsonNode n : t.get("exclude")) excludes.add(n.asText());
            }
            target = AssertionTarget.match(t.get("match").asText(), excludes);
        }

        AssertionRule.Builder b = AssertionRule.builder(target, r.get("metric").asText())
                .id(r.path("id").asText(null))
                .enabled(r.path("enabled").asBoolean(true));
        if (r.hasNonNull("warn")) b.warn(predicate(r.get("warn")));
        if (r.hasNonNull("fail")) b.fail(predicate(r.get("fail")));
        return b.build();
    }

    private static Predicate predicate(JsonNode band) {
        String key = band.fieldNames().next();
        return Predicate.of(Predicate.Op.fromKey(key), band.get(key).asDouble());
    }

    // ---------------------------------------------------------------- the vectors

    @TestFactory
    Stream<DynamicTest> everyVectorProducesTheDeclaredStatus() throws IOException {
        JsonNode root = vectors();
        ReportData data = reportData(root);
        AssertionEvaluator evaluator = new AssertionEvaluator();

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            final JsonNode testCase = c;
            tests.add(DynamicTest.dynamicTest(testCase.get("name").asText(), () -> {
                AssertionRule rule = rule(testCase.get("rule"));
                GateSummary gate = evaluator.evaluate(Collections.singletonList(rule), data);
                RuleResult result = gate.ruleResults().get(0);
                JsonNode expect = testCase.get("expect");

                assertEquals(expect.get("status").asText(), result.status().name(),
                        "status for " + rule.id());
                // The reason is pinned as hard as the status. NOT_JUDGED alone would let the two
                // engines agree that a rule decided nothing while disagreeing about WHY — and
                // "your target matched nothing" versus "your metric name is a typo" send the
                // reader to two different files.
                if (expect.hasNonNull("notJudged")) {
                    assertNotNull(result.notJudged(), "no reason given for " + rule.id());
                    assertEquals(expect.get("notJudged").asText(), result.notJudged().name(),
                            "notJudged reason for " + rule.id());
                } else {
                    assertNull(result.notJudged(),
                            "a judged rule must carry no reason: " + rule.id());
                }
                assertEquals(expect.get("matched").asInt(), result.matchedTotal(),
                        "matched count for " + rule.id());
                if (expect.hasNonNull("unmatched")) {
                    assertEquals(expect.get("unmatched").asBoolean(), result.unmatched(),
                            "unmatched flag for " + rule.id());
                }
                // Why a rule is INVALID, not merely that it is.
                //
                // This was unpinned, and it hid a real divergence: the browser never checked
                // whether a metric exists in the rule's SCOPE, so a whole-run rule naming a
                // per-sampler metric was resolved instead of rejected. It reached INVALID anyway
                // — from a NaN lookup rather than from the scope check — so `status` matched on
                // both sides and only `matched` (0 against 1) gave it away. A substring, because
                // the two sides word the rest of the sentence differently and pinning full
                // prose would fail on a wording change that broke nothing.
                if (expect.hasNonNull("reason")) {
                    assertNotNull(result.notJudgedDetail(), "no detail given for " + rule.id());
                    assertTrue(result.notJudgedDetail().contains(expect.get("reason").asText()),
                            "detail for " + rule.id() + " was: " + result.notJudgedDetail());
                }
                if (expect.hasNonNull("matches")) {
                    assertMatches(expect.get("matches"), result);
                }
                // The name the rule is judged under, whatever the file's spelling (RB8): the one
                // GatePayloadBuilder ships, which the page's ruleFromJson has to arrive at too.
                if (expect.hasNonNull("metric")) {
                    assertNotNull(rule.metric(), "the metric did not parse for " + rule.id());
                    assertEquals(expect.get("metric").asText(), rule.metric().canonicalName(),
                            "canonical metric for " + rule.id());
                }
                // How much of the fail band the rule used (AF5b): Attainment.of here, the page's
                // attainmentFor (the Budget column) in asserts-parity.test.js and the spec.
                if (expect.has("attainment")) {
                    assertAttainment(expect.get("attainment"), Attainment.of(result), rule.id());
                }
            }));
        }
        return tests.stream();
    }

    private static void assertAttainment(JsonNode expected, Attainment actual, String ruleId) {
        if (expected.isNull()) {
            assertNull(actual, "no budget applies to " + ruleId + ", so its attainment is null, not zero");
            return;
        }
        assertNotNull(actual, "attainment for " + ruleId);
        assertEquals(expected.get("actual").asDouble(), actual.actual(), 0.0001, "attainment.actual for " + ruleId);
        assertEquals(expected.get("failThreshold").asDouble(), actual.failThreshold(), 0.0,
                "attainment.failThreshold for " + ruleId);
        assertEquals(expected.get("consumed").asDouble(), actual.consumed(), 0.0001,
                "attainment.consumed for " + ruleId);
        assertEquals(expected.get("breached").asBoolean(), actual.breached(), "attainment.breached for " + ruleId);
    }

    private static void assertMatches(JsonNode expected, RuleResult actual) {
        assertEquals(expected.size(), actual.matches().size(), "number of reported matches");
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            RuleResult.Match m = actual.matches().get(i);
            assertEquals(e.get("sampler").asText(), m.samplerName(), "match " + i + " sampler");
            if (e.get("actual").isNull()) {
                // The run cannot answer this metric. Java carries NaN, the payload and the
                // client evaluator carry null — the same fact in each side's vocabulary.
                assertTrue(Double.isNaN(m.actual()),
                        "match " + i + " should be unanswerable, was " + m.actual());
            } else {
                assertEquals(e.get("actual").asDouble(), m.actual(), 0.0001, "match " + i + " value");
            }
            assertEquals(e.get("status").asText(), m.status().name(), "match " + i + " status");
        }
    }

    @TestFactory
    Stream<DynamicTest> everyGateVectorProducesTheDeclaredSummary() throws IOException {
        JsonNode root = vectors();
        ReportData data = reportData(root);
        AssertionEvaluator evaluator = new AssertionEvaluator();

        // Index the single-rule cases by id so a gate case can name the rules it combines.
        Map<String, AssertionRule> byId = new LinkedHashMap<>();
        for (JsonNode c : root.get("cases")) {
            AssertionRule r = rule(c.get("rule"));
            byId.put(r.id(), r);
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode g : root.get("gateCases")) {
            final JsonNode gateCase = g;
            tests.add(DynamicTest.dynamicTest("gate — " + gateCase.get("name").asText(), () -> {
                List<AssertionRule> rules = new ArrayList<>();
                for (JsonNode id : gateCase.get("ruleIds")) {
                    AssertionRule r = byId.get(id.asText());
                    assertNotNull(r, "gate case names an unknown rule id: " + id.asText());
                    rules.add(r);
                }
                GateSummary gate = evaluator.evaluate(rules, data);
                JsonNode expect = gateCase.get("expect");

                if (expect.get("status").isNull()) {
                    assertNull(gate.status(),
                            "a gate of nothing but NOT_JUDGED rules has decided nothing, "
                                    + "which is not the same as PASS");
                } else {
                    assertEquals(expect.get("status").asText(), gate.status().name());
                }
                assertEquals(expect.get("declared").asInt(), gate.declared(), "declared");
                assertEquals(expect.get("evaluated").asInt(), gate.evaluated(), "evaluated");
                assertEquals(expect.get("passed").asInt(), gate.passed(), "passed");
                assertEquals(expect.get("warned").asInt(), gate.warned(), "warned");
                assertEquals(expect.get("failed").asInt(), gate.failed(), "failed");
                JsonNode nj = expect.get("notJudged");
                assertEquals(nj.get("total").asInt(), gate.notJudged(), "notJudged.total");
                int reasonSum = 0;
                for (NotJudged r : NotJudged.values()) {
                    reasonSum += gate.notJudged(r);
                    if (r.serverOnly()) {
                        // A client evaluator cannot produce these. The vector may name the key
                        // (as zero) to document the wire shape; it may not claim a count.
                        assertEquals(0, gate.notJudged(r), "notJudged." + r.countKey()
                                + " is server-only and must be zero on a client gate");
                        if (nj.has(r.countKey())) {
                            assertEquals(0, nj.get(r.countKey()).asInt(),
                                    "a client vector claims " + r + ", which only a server KPI produces");
                        }
                        continue;
                    }
                    assertTrue(nj.has(r.countKey()),
                            "gate case must expect notJudged." + r.countKey());
                    assertEquals(nj.get(r.countKey()).asInt(), gate.notJudged(r),
                            "notJudged." + r.countKey());
                }

                // Both arithmetics, on every gate case. A bucket that quietly swallows a rule is
                // how a pipeline ends up trusting a gate that skipped half its rules.
                assertEquals(gate.declared(),
                        gate.passed() + gate.warned() + gate.failed() + gate.notJudged(),
                        "the per-status counts must sum to declared");
                assertEquals(gate.notJudged(), reasonSum,
                        "the reasons must sum to notJudged");
            }));
        }
        return tests.stream();
    }

    @org.junit.jupiter.api.Test
    void theVectorFileCoversEveryStatusAndEveryOperator() throws IOException {
        // A parity contract is only as good as its coverage, and coverage is the thing most
        // likely to rot silently as behaviour is added. Fail here rather than discover months
        // later that no vector ever exercised `lte`.
        JsonNode root = vectors();
        List<String> statuses = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            statuses.add(c.get("expect").get("status").asText());
            if (c.get("expect").hasNonNull("notJudged")) {
                reasons.add(c.get("expect").get("notJudged").asText());
            }
            for (String band : Arrays.asList("warn", "fail")) {
                if (c.get("rule").hasNonNull(band)) {
                    operators.add(c.get("rule").get(band).fieldNames().next());
                }
            }
        }
        for (AssertionStatus s : AssertionStatus.values()) {
            assertTrue(statuses.contains(s.name()),
                    "no parity vector produces " + s + " — add one before relying on it");
        }
        // The reasons need the same guard as the statuses, and more: NOT_JUDGED is one enum
        // value covering several situations, so a vector file could satisfy the loop above
        // with a single disabled rule and leave the other paths through both evaluators
        // untested.
        //
        // Split by side. This file is the CLIENT contract — the rules a sampler evaluator
        // judges — and three reasons can only come from a server-metric KPI. Demanding them
        // here would force the client vectors to carry cases neither client evaluator can
        // produce; accepting them here would let a server reason hide in the wrong file. The
        // server parity vectors (delivery plan D21, milestone M4) will carry the other half;
        // until they exist nothing produces these reasons at all.
        for (NotJudged r : NotJudged.values()) {
            if (r.serverOnly()) {
                assertFalse(reasons.contains(r.name()),
                        "the client vectors claim NOT_JUDGED / " + r
                                + ", which only a server KPI produces — it belongs in the server vectors");
                continue;
            }
            assertTrue(reasons.contains(r.name()),
                    "no parity vector produces NOT_JUDGED / " + r + " — add one before relying on it");
        }
        for (Predicate.Op op : Predicate.Op.values()) {
            assertTrue(operators.contains(op.key()),
                    "no parity vector uses the '" + op.key() + "' operator");
        }
        // The Budget column's cases (AF5b): a fraction breached and not, the boundary, and each
        // way the question has no answer - no fail band, a rule that decided nothing, a zero
        // divisor on either side.
        int fractions = 0;
        int nulls = 0;
        for (JsonNode c : root.get("cases")) {
            JsonNode a = c.get("expect").get("attainment");
            if (a == null) continue;
            if (a.isNull()) nulls++; else fractions++;
        }
        assertTrue(fractions >= 6, "the attainment fractions are gone: " + fractions);
        assertTrue(nulls >= 4, "the attainment nulls are gone: " + nulls);
        // REV-18: a corridor - a warn band pointing away from its fail band - judged WARN, with an
        // attainment: the one shape on which "worst" is decided by severity first and then by the
        // band that status was judged against (L13, NIT-2), not by the fail band's direction.
        int corridors = 0;
        for (JsonNode c : root.get("cases")) {
            JsonNode warn = c.get("rule").get("warn");
            JsonNode fail = c.get("rule").get("fail");
            JsonNode a = c.get("expect").get("attainment");
            if (warn == null || fail == null || a == null || a.isNull()) continue;
            boolean warnUp = warn.has("gt") || warn.has("gte");
            boolean failUp = fail.has("gt") || fail.has("gte");
            if (warnUp != failUp && "WARN".equals(c.get("expect").get("status").asText())) corridors++;
        }
        assertTrue(corridors >= 1, "no corridor case carries an attainment: the worst match of a "
                + "corridor is held by no vector");
    }
}
