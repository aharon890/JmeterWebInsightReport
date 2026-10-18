package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.PercentileSource;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the report can see.
 *
 * <p>The page is one self-contained file on {@code file://} — there is no server to ask. So
 * anything the Asserts tab needs and the payload does not carry is simply unavailable,
 * for good. These tests are about that boundary.
 */
class GatePayloadTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ReportDataSerializer serializer = new ReportDataSerializer();

    private static ReportData run(int samplerCount) {
        ReportData d = new ReportData();
        java.util.List<SamplerStatistics> stats = new ArrayList<>();
        for (int i = 0; i < samplerCount; i++) {
            SamplerStatistics s = new SamplerStatistics();
            s.setSamplerName("GET /s" + i);
            s.setSampleCount(100);
            s.setErrorRate(1.0);
            s.setMeanResponseTime(100 + i);
            s.setMedianResponseTime(90 + i);
            s.setPercentile90(120 + i);
            s.setPercentile95(150 + i);
            s.setPercentile99(200 + i);
            s.setThroughput(5);
            s.setApdexScore(0.9);
            stats.add(s);
        }
        d.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setTestName("Payload");
        m.setDurationMillis(60_000);
        m.setTotalThreads(10);
        d.setMetadata(m);
        return d;
    }

    private JsonNode payload(ReportData data, String annotationsJson) throws Exception {
        ReportAnnotations a = json.readValue(annotationsJson, ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(data, a);
        return json.readTree(serializer.serializeChartData(data, a, null, e, null, null));
    }

    /** A distribution that answers any quantile, so off-grid percentiles resolve. */
    private static PercentileSource distribution() {
        return new PercentileSource() {
            @Override
            public double percentile(double p) {
                return p * 10;
            }

            @Override
            public long sampleCount() {
                return 100;
            }
        };
    }

    /**
     * The browser looks a quantile up by the key this builder writes, and the two spellings
     * have to be the same string.
     *
     * <p>They were not: {@code String.valueOf(85.0)} is {@code "85.0"} and {@code String(85)}
     * in JavaScript is {@code "85"}. The lookup missed, a miss is NaN, and a {@code p85} rule
     * the engine had PASSed rendered INVALID in the report. Only a whole number shows it —
     * {@code p97.5} spells identically on both sides, which is exactly why the parity vectors
     * could not see the drift.
     *
     * <p>The client also reads the old spelling now, so this asserts the emitter directly.
     * Without it a regression in {@code percentileKey} would be absorbed silently by that
     * tolerance and no test anywhere would fail.
     */
    @Test
    void anOffGridPercentileIsKeyedTheWayTheBrowserSpellsIt() throws Exception {
        ReportData data = run(1);
        java.util.Map<String, PercentileSource> perSampler = new java.util.LinkedHashMap<>();
        perSampler.put("GET /s0", distribution());
        data.attachPercentileSources(perSampler, distribution());

        JsonNode extra = payload(data, "{\"assertions\":["
                + "{\"id\":\"tail\",\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p85\","
                + " \"fail\":{\"gt\":99999}},"
                + "{\"id\":\"tail2\",\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p97.5\","
                + " \"fail\":{\"gt\":99999}}]}").get("assertPercentiles");

        JsonNode forSampler = extra.get("GET /s0");
        assertTrue(forSampler.has("85"), "the browser looks this up as \"85\": " + forSampler);
        assertFalse(forSampler.has("85.0"), "\"85.0\" is a key the browser never asks for");
        assertEquals(850.0, forSampler.get("85").asDouble(), 0.001);
        // The fractional branch keeps its decimal — the key is not simply truncated.
        assertTrue(forSampler.has("97.5"), forSampler.toString());
        // The whole-run block goes through the same key function.
        assertTrue(extra.get(GatePayloadBuilder.OVERALL_KEY).has("85"), extra.toString());
    }

    /**
     * A quantile the digest cannot answer is shipped as null, never as 0.0 (plan L16).
     *
     * <p>{@code judged} sent NaN through round2, and {@code Math.round(NaN)} is 0, so a rule-named
     * percentile the run could not measure reached the page as a measured 0 ms: a {@code lt} rule
     * over it re-judged FAIL after any edit, a {@code gt} rule PASS, where Java had said NO_VALUE.
     */
    @Test
    void aQuantileTheDigestCannotAnswerIsShippedAsNullNotZero() throws Exception {
        ReportData data = run(1);
        PercentileSource unanswerable = new PercentileSource() {
            @Override
            public double percentile(double p) {
                return Double.NaN;
            }

            @Override
            public long sampleCount() {
                return 100;
            }
        };
        java.util.Map<String, PercentileSource> perSampler = new java.util.LinkedHashMap<>();
        perSampler.put("GET /s0", unanswerable);
        data.attachPercentileSources(perSampler, unanswerable);

        JsonNode extra = payload(data, "{\"assertions\":["
                + "{\"id\":\"tail\",\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p85\","
                + " \"fail\":{\"lt\":5}}]}").get("assertPercentiles");

        JsonNode forSampler = extra.get("GET /s0");
        assertTrue(forSampler.has("85"), "the key is still written, so the page knows it was asked: "
                + forSampler);
        assertTrue(forSampler.get("85").isNull(), "NaN must reach the page as null, not "
                + forSampler.get("85"));
        assertTrue(extra.get(GatePayloadBuilder.OVERALL_KEY).get("85").isNull(), extra.toString());
    }

    @Test
    void aRuleIsCarriedAsDeclaredNotJustAsAVerdict() throws Exception {
        // The editor has to load a band back into two form fields, and the client evaluator has
        // to re-run it as the user types. A rendered string like "fail > 800 ms" can do neither.
        JsonNode rule = payload(run(1), "{\"assertions\":[{"
                + "\"id\":\"r\",\"description\":\"why\",\"target\":{\"sampler\":\"GET /s0\"},"
                + "\"metric\":\"p95\",\"warn\":{\"gt\":100},\"fail\":{\"gt\":10000}"
                + "}]}").get("assertions").get(0);

        assertEquals("r", rule.get("id").asText());
        assertEquals("why", rule.get("description").asText());
        assertEquals("p95", rule.get("metric").asText());
        assertEquals("ms", rule.get("unit").asText());
        assertEquals("gt", rule.get("fail").get("op").asText());
        assertEquals(10000.0, rule.get("fail").get("value").asDouble(), 0.001);
        assertEquals("gt", rule.get("warn").get("op").asText());
        assertEquals("sampler", rule.get("target").get("kind").asText());
        assertEquals("GET /s0", rule.get("target").get("sampler").asText());
        assertEquals("WARN", rule.get("status").asText());
    }

    /**
     * A band that overflows a double reaches the page refused — never as the text "Infinity".
     *
     * <p>A bare {@code 1e400} is Infinity by the time the mapper sees it, and the client mapper took
     * it as a band: Jackson wrote it here as {@code "fail": {"op": "gt", "value": "Infinity"}}, the
     * page showed Java's PASS on load ({@code fail > ∞ ms}, header ASSERTS PASS), and an unrelated
     * edit re-judged it to PASS again, because {@code breaches()} coerces the string. The mapper now
     * refuses it, so the rule ships with no band and Java's sentence as its first
     * {@code declarationProblems} entry — the one thing the page's {@code whyUnusable} reads first.
     * js-unit's asserts-pure.test.js holds the page to that shape.
     *
     * <p>{@code annotationsDocument} is left out of the sweep on purpose: it re-serialises the file
     * as Jackson bound it, and the overflowed literal was lost at parse time (plan L7).
     */
    @Test
    void anOverflowedBandReachesThePageRefusedNotAsText() throws Exception {
        String sentence = "fail gt must be a number, not the number Infinity — so this rule declares a limit"
                + " it cannot apply, and would report PASS without ever testing fail";
        JsonNode page = payload(run(1), "{\"assertions\":[{\"id\":\"overflowed\","
                + "\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"mean\",\"fail\":{\"gt\":1e400}}]}");
        JsonNode rule = page.get("assertions").get(0);

        assertEquals("NOT_JUDGED", rule.get("status").asText(), rule.toString());
        assertEquals("INVALID_RULE", rule.get("notJudged").asText(), rule.toString());
        assertEquals(sentence, rule.get("notJudgedDetail").asText());
        assertFalse(rule.has("fail"), "an unreadable band is no band: " + rule);
        assertEquals(sentence, rule.get("declarationProblems").get(0).asText(),
                "the sentence the page's whyUnusable reads first");

        List<String> textual = new ArrayList<>();
        CiCdJsonSerializerTest.collectNonFiniteText(page.get("assertions"), "/assertions", textual);
        CiCdJsonSerializerTest.collectNonFiniteText(page.get("gate"), "/gate", textual);
        assertTrue(textual.isEmpty(), "a non-finite number reached the page as text: " + textual);
    }

    @Test
    void aPatternTargetKeepsItsPatternAndExcludes() throws Exception {
        JsonNode target = payload(run(3), "{\"assertions\":[{\"id\":\"p\","
                + "\"target\":{\"match\":\"GET /*\",\"exclude\":[\"GET /s2\"]},"
                + "\"metric\":\"p95\",\"fail\":{\"gt\":99999}}]}")
                .get("assertions").get(0).get("target");

        assertEquals("match", target.get("kind").asText());
        assertEquals("GET /*", target.get("match").asText());
        assertEquals("GET /s2", target.get("exclude").get(0).asText());
    }

    @Test
    void manyMatchesAreCappedButTheTrueTotalSurvives() throws Exception {
        // A match:"*" rule over a 500-sampler run would otherwise put 500 result objects into
        // the HTML for every rule of that shape. Truncating silently would make the rule look
        // like it had judged a handful.
        int samplers = GatePayloadBuilder.MAX_MATCHES_PER_RULE + 37;
        JsonNode rule = payload(run(samplers), "{\"assertions\":[{\"id\":\"blanket\","
                + "\"target\":{\"match\":\"GET /*\"},\"metric\":\"p95\",\"fail\":{\"gt\":99999}}]}")
                .get("assertions").get(0);

        assertEquals(samplers, rule.get("matched").asInt(),
                "the true total must survive so the UI can say 'and N more'");
        assertEquals(GatePayloadBuilder.MAX_MATCHES_PER_RULE, rule.get("results").size());
    }

    @Test
    void theMetricVocabularyComesFromTheEngineNotFromTheJavaScript() throws Exception {
        // Hardcoding it in report.js would let the editor offer a metric the engine rejects,
        // which the tester discovers on the next run, after saving.
        JsonNode vocab = payload(run(1), "{\"assertions\":[{\"id\":\"r\","
                + "\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":99999}}]}").get("assertMetrics");

        assertTrue(vocab.get("sampler").size() > 5);
        assertTrue(vocab.get("overall").size() > 5);
        // The grid arrives as named metrics in those two lists, which is the form the editor
        // reads. A separate `percentileGrid` array shipped the same six quantiles a second
        // time in a second shape, and nothing ever read it.
        assertTrue(vocab.path("percentileGrid").isMissingNode(),
                "the payload should not carry the grid twice");
        boolean hasP999 = false;
        for (JsonNode m : vocab.get("sampler")) {
            if ("p99.9".equals(m.get("name").asText())) {
                hasP999 = true;
            }
        }
        assertTrue(hasP999, "the grid percentiles must still be offered by name");

        boolean hasStdDevPerSampler = false;
        for (JsonNode m : vocab.get("sampler")) {
            if ("stdDev".equals(m.get("name").asText())) {
                hasStdDevPerSampler = true;
                assertEquals("ms", m.get("unit").asText());
                assertEquals("latency", m.get("family").asText());
            }
        }
        assertTrue(hasStdDevPerSampler);

        for (JsonNode m : vocab.get("overall")) {
            assertFalse("stdDev".equals(m.get("name").asText()),
                    "stdDev has no whole-run value, so the editor must not offer it there");
        }
    }

    @Test
    void theWholeAnnotationsDocumentTravelsIncludingKeysWeDoNotUnderstand() throws Exception {
        // The report writes this file back. Dropping the unknown would punish anyone who
        // hand-added a key or opened a file written by a newer plugin — which is exactly how
        // people learn not to trust an editor.
        JsonNode doc = payload(run(1), "{"
                + "\"testNotes\":\"notes\",\"verdict\":\"v\","
                + "\"assertions\":[{\"id\":\"r\",\"target\":{\"sampler\":\"GET /s0\"},"
                + "  \"metric\":\"p95\",\"fail\":{\"gt\":99999}}],"
                + "\"samplerNotes\":{\"GET /s0\":\"note\"},"
                + "\"myTeamsOwnKey\":[1,2,3],"
                + "\"serverMetrics\":{\"prometheus\":\"http://x\","
                + "  \"sources\":{\"p\":{\"type\":\"prometheus\",\"url\":\"http://p\","
                + "    \"auth\":{\"bearerToken\":\"CANARY-SECRET\",\"apiKeyEnv\":\"PROM_KEY\"}},"
                + "  \"u\":{\"type\":\"prometheus\",\"url\":\"http://alice:CANARY-USERINFO@p/api\"},"
                + "  \"t\":{\"type\":\"prometheus\",\"url\":\"http://p\",\"password\":\"CANARY-PASSWORD\"},"
                + "  \"q\":{\"type\":\"prometheus\",\"url\":\"http://p/api?api_key=CANARY-QUERY&x=1\"},"
                + "  \"e\":{\"type\":\"prometheus\",\"url\":\"${env.PROM}\"}},"
                + "  \"environments\":{\"prod\":{\"vars\":{\"PROM\":\"https://bob:CANARY-ENV@prom.corp/api/v1\"}}}}}")
                .get("annotationsDocument");

        assertEquals("notes", doc.get("testNotes").asText());
        assertEquals("note", doc.get("samplerNotes").get("GET /s0").asText());
        // A genuinely unknown top-level key — the document-level round-trip this test is about.
        assertTrue(doc.has("myTeamsOwnKey"), "an unknown key must reach the page: " + doc);
        assertEquals(3, doc.get("myTeamsOwnKey").size());
        // A typed block travels whole, its own unknown keys included; serverMetrics stopped
        // being an unknown key in M2 and must still reach the page exactly as written...
        assertTrue(doc.has("serverMetrics"), "the typed block must reach the page: " + doc);
        assertEquals("http://x", doc.get("serverMetrics").get("prometheus").asText());
        // ...except a literal secret, which the mapper refuses AND the document masks. The
        // refusal tells the author; masking is what keeps the value out of every artifact the
        // run produces. An env-var NAME is not a secret and must round-trip untouched.
        JsonNode auth = doc.get("serverMetrics").get("sources").get("p").get("auth");
        assertEquals("***", auth.get("bearerToken").asText(), "a literal secret is masked: " + auth);
        assertEquals("PROM_KEY", auth.get("apiKeyEnv").asText(), "a reference is not a secret");
        assertFalse(doc.toString().contains("CANARY-SECRET"), "the secret must not appear anywhere: " + doc);
        // ...and every other shape the mapper refuses as a credential (L9-2): URL userinfo and a
        // source-level secret-looking key were published verbatim while only auth was masked.
        JsonNode sources = doc.get("serverMetrics").get("sources");
        assertEquals("http://***@p/api", sources.get("u").get("url").asText());
        assertEquals("***", sources.get("t").get("password").asText());
        // ...and the two the first widening still missed: a credential query parameter, and a
        // credential held in environments.*.vars and reached through ${env.*}.
        assertEquals("http://p/api?api_key=***&x=1", sources.get("q").get("url").asText());
        assertEquals("https://***@prom.corp/api/v1", doc.get("serverMetrics").get("environments")
                .get("prod").get("vars").get("PROM").asText());
        assertFalse(doc.toString().contains("CANARY"), "no credential may appear anywhere: " + doc);
        assertEquals(1, doc.get("assertions").size());
    }

    @Test
    void validationFindingsReachThePageSoItCanShowTheSameNoticeTheLogDoes() throws Exception {
        JsonNode findings = payload(run(1), "{\"assertions\":[{\"id\":\"typo\","
                + "\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p95x\","
                + "\"fail\":{\"gt\":1}}]}").get("assertFindings");

        assertEquals(1, findings.size());
        assertEquals("error", findings.get(0).get("severity").asText());
        assertEquals("typo", findings.get(0).get("ruleId").asText());
        assertTrue(findings.get(0).get("message").asText().contains("did you mean 'p95'"));
    }

    @Test
    void aRunWithNoGateCarriesNoGateBlockButStillCarriesItsDocument() throws Exception {
        // Notes and markers are still editable and saveable without any rules, and dropping
        // unknown keys on that path would be the same data loss by another route.
        JsonNode out = payload(run(1), "{\"testNotes\":\"just notes\",\"myKey\":1}");

        assertTrue(out.path("gate").isMissingNode());
        assertTrue(out.path("assertions").isMissingNode());
        assertEquals("just notes", out.get("annotationsDocument").get("testNotes").asText());
        assertTrue(out.get("annotationsDocument").has("myKey"));
    }

    @Test
    void aRunWithNoGateStillCarriesTheMetricVocabulary() throws Exception {
        // The vocabulary describes what the engine CAN judge, not what this run declared, so it
        // belongs above the no-gate early return rather than below it. Withholding it said "there
        // are no metrics" when the truth was "there are no rules", and the editor — which offers
        // exactly this list — opened with an empty dropdown on the one report where a tester has
        // everything still to declare.
        JsonNode metrics = payload(run(1), "{}").path("assertMetrics");

        assertFalse(metrics.isMissingNode(),
                "a report with no rules is where the metric list is needed most");
        assertTrue(metrics.path("sampler").size() >= 1, "sampler-scoped metrics must be offered");
        assertTrue(metrics.path("overall").size() >= 1, "overall-scoped metrics must be offered");

        // The same list the gated path publishes — one vocabulary, not two.
        JsonNode gated = payload(run(1), "{\"assertions\":[{\"id\":\"r\","
                + "\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":1}}]}").path("assertMetrics");
        assertEquals(gated, metrics);
    }

    @Test
    void aRunWithNoAnnotationsFileAtAllStillCarriesTheMetricVocabulary() throws Exception {
        // The empty-directory case: no annotations argument at all, not merely an empty one. This
        // is the report the "declare your first assert" flow exists for.
        Map<String, Object> chartData = new LinkedHashMap<>();
        GatePayloadBuilder.addTo(chartData, GateEvaluation.of(run(1), null), null, run(1));

        assertTrue(chartData.containsKey("assertMetrics"),
                "the metric vocabulary must survive a run with no annotations file");
        assertFalse(chartData.containsKey("gate"), "but no gate was declared, so none is claimed");
        assertFalse(chartData.containsKey("assertions"));
    }

    @Test
    void theExternalDataPathCarriesTheGateToo() throws Exception {
        // Large runs write the payload to web-insight-data.js instead of inlining it. A gate
        // that appeared only in the inline path would vanish on exactly the runs big enough to
        // need one.
        ReportData data = run(2);
        ReportAnnotations a = json.readValue("{\"assertions\":[{\"id\":\"r\","
                + "\"target\":{\"sampler\":\"GET /s0\"},\"metric\":\"p95\","
                + "\"fail\":{\"gt\":99999}}]}", ReportAnnotations.class);
        GateEvaluation e = GateEvaluation.of(data, a);

        java.io.StringWriter out = new java.io.StringWriter();
        serializer.externalDataJsContent(data, a, null, "window.__WIR_DATA__=", e, null, null).writeTo(out);

        String js = out.toString();
        assertTrue(js.contains("\"gate\""), "the external payload must carry the gate");
        assertTrue(js.contains("\"assertMetrics\""));
        assertTrue(js.contains("\"annotationsDocument\""));
    }

    /**
     * The payload carries no legacy origin, because nothing can produce one.
     *
     * <p>The Asserts tab used to show a read-only panel of dead thresholds with a Convert
     * button. Both are gone: there is one origin a person authors ({@code file}), one derived
     * from {@code loadValidity}, and no third dialect to convert out of.
     */
    @Test
    void anSlaThresholdsBlockPutsNoRuleInThePayload() throws Exception {
        JsonNode payload = payload(run(1), "{\"someUnknownBlock\":{\"GET /s0\":{\"p95\":10}}}");

        JsonNode rules = payload.get("assertions");
        assertTrue(rules == null || rules.size() == 0,
                "an unrecognised block must not reach the client as rules: " + rules);
    }

    // ---- the server gates in the page's payload (M4, M5's inputs) --------------------

    @Test
    void theServerGatesReachThePageFromTheSameBuildersAsTheJson() throws Exception {
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, null));
        ReportAnnotations a = json.readValue(doc, ReportAnnotations.class);
        GateEvaluation client = GateEvaluation.of(data, a);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700)), data);

        JsonNode chart = json.readTree(serializer.serializeChartData(data, a, null, client, server.payload(), server));
        assertTrue(chart.get("serverDeclared").asBoolean());
        assertTrue(chart.get("serverEvaluated").asBoolean());
        assertEquals("server", chart.get("serverGates").get(0).get("id").asText());
        assertEquals("FAIL", chart.get("serverGates").get(0).get("status").asText());
        assertEquals("lag", chart.get("serverKpis").get(0).get("id").asText());
        assertEquals(700.0, chart.get("serverKpis").get(0).get("actual").asDouble());
        assertTrue(chart.has("serverFindings"));
        assertTrue(chart.has("serverMetrics"), "the series are there too, for the charts");

        // Declared but not evaluated: the page is told so, and gets no gates to draw.
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation interim =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, null, data);
        JsonNode interimChart = json.readTree(serializer.serializeChartData(data, a, null, client, null, interim));
        assertTrue(interimChart.get("serverDeclared").asBoolean());
        assertFalse(interimChart.get("serverEvaluated").asBoolean());
        assertFalse(interimChart.has("serverGates"));

        // No block at all: no server verdict and no findings - only the catalogue (below).
        JsonNode none = json.readTree(serializer.serializeChartData(data, null, null, GateEvaluation.none(), null,
                com.jmeterwebinsightreport.report.gate.ServerGateEvaluation.none()));
        assertFalse(none.has("serverDeclared"));
        assertFalse(none.has("serverFindings"));
        assertFalse(interimChart.has("serverVerdict"), "nothing judged: no word, as the chip has none");
        assertFalse(none.has("serverVerdict"));
    }

    /**
     * F7. The page's server-assert entries come from the CI/CD JSON's own builder, so the page was
     * handed the JSON's 4dp {@code actual}: 100.00004 arrived as 100, and 0.00003 as 0. The board,
     * the Summary card and the editor's "If saved" line write a baked actual through formatActual,
     * which adds digits only where two decimals would put an actual on or across its band (SF15) -
     * and it cannot add digits a rounded number no longer has. A FAIL at 2.24146 under
     * {@code lt 2.24148} read "2.2415", on the passing side of its own band.
     *
     * <p>The JSON keeps four places: it is a consumed contract, held by the goldens (R06). Since S2
     * it adds the digits that keep an actual off a band it is not on - 100.00004 over {@code gt 100}
     * is 100.00004 there too - but it never adds a never-zero guard, and clear of every band it is
     * four places. The page gets Java's double. Still ONE builder: every other key of the two entries
     * is the same, so the page and the JSON cannot drift into two shapes of one verdict.
     */
    @Test
    void thePageIsGivenJavasActualWhileTheJsonKeepsFourDecimals() throws Exception {
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("near", "lag", 100, null),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("tiny", "tiny", 1, null),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("gone", "missing", 0, null),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("clear", "clear", 1000, null));
        ReportAnnotations a = json.readValue(doc, ReportAnnotations.class);
        GateEvaluation client = GateEvaluation.of(data, a);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 100.00004),
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("tiny", 0.00003),
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("clear", 100.123456)), data);

        JsonNode page = json.readTree(serializer.serializeChartData(data, a, null, client, server.payload(), server))
                .get("serverKpis");
        JsonNode wire = json.readTree(new CiCdJsonSerializer(json).serialize(data, null, null, null, null,
                java.util.Collections.emptyList(), server)).get("serverAsserts");
        assertEquals(4, page.size(), page.toString());
        assertEquals(4, wire.size(), wire.toString());

        // fail gt 100, judged on 100.00004: a FAIL, which 100 is not - and the JSON no longer writes
        // it on the band it breached (S2).
        assertEquals("FAIL", wire.get(0).get("status").asText());
        assertEquals(100.00004, wire.get(0).get("actual").asDouble(), 0.0, "never on its band (S2)");
        assertEquals(100.00004, page.get(0).get("actual").asDouble(), 0.0,
                "the page gets the number Java judged");
        // fail gt 1, judged on 0.00003: a PASS no band separates from 0. The JSON has no never-zero
        // guard (Q12), so it writes 0.0; the page never writes a zero (SF15's guard), which it can
        // only do from Java's double.
        assertEquals("PASS", wire.get(1).get("status").asText());
        assertEquals(0.0, wire.get(1).get("actual").asDouble(), 0.0, "the JSON keeps its four places");
        assertEquals(3.0E-5, page.get(1).get("actual").asDouble(), 0.0,
                "the page gets the number Java judged, not the JSON's four places of it");
        // Nothing collected for it: NOT_JUDGED, and no number on either side. The page has its own
        // branch for that - without it Jackson writes the NaN as the string "NaN".
        assertEquals("NOT_JUDGED", wire.get(2).get("status").asText());
        assertTrue(wire.get(2).get("actual").isNull(), "the JSON writes no number: " + wire.get(2));
        assertTrue(page.get(2).get("actual").isNull(), "nor does the page: " + page.get(2));
        // Clear of every band: four places on the wire, every digit on the page.
        assertEquals(100.1235, wire.get(3).get("actual").asDouble(), 0.0, "the JSON keeps its four places");
        assertEquals(100.123456, page.get(3).get("actual").asDouble(), 0.0,
                "the page gets the number Java judged, not the JSON's four places of it");

        // One builder: every other key the same, in the same order.
        for (int i = 0; i < page.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode p = ((com.fasterxml.jackson.databind.node.ObjectNode) page.get(i)).deepCopy();
            com.fasterxml.jackson.databind.node.ObjectNode w = ((com.fasterxml.jackson.databind.node.ObjectNode) wire.get(i)).deepCopy();
            java.util.List<String> pageKeys = new ArrayList<>();
            p.fieldNames().forEachRemaining(pageKeys::add);
            java.util.List<String> wireKeys = new ArrayList<>();
            w.fieldNames().forEachRemaining(wireKeys::add);
            assertEquals(wireKeys, pageKeys, "the same keys in the same order");
            p.remove("actual");
            w.remove("actual");
            assertEquals(w, p, "every key but actual is the JSON's");
        }
    }

    /**
     * N3, the page half: F7's client twin. A match's {@code actual} reached the page as round2 of
     * it, so a FAIL at 373.8625 under {@code gt 373.86} arrived as 373.86 - ON the band it breached.
     * The Budget bar judges that number ({@code breaches(rule.fail, worst)}), so it drew the FAIL
     * green on load, red after any unrelated edit (which re-judges the exact run) and green again
     * after Discard drafts, which put the payload's number back. Sorting by Budget and the
     * Summary's "Worst breaches" moved with it.
     *
     * <p>The page now gets the number Java judged, as it gets a rule-named percentile
     * ({@code judged()}); how many digits to WRITE is the page's question (WIR.digits). A match
     * with no value is still no number.
     */
    @Test
    void aClientMatchActualOnThePageIsJavasDouble() throws Exception {
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        data.getSamplerStatistics().get(0).setMeanResponseTime(373.8625);
        JsonNode rules = payload(data, "{\"assertions\":["
                + "{\"id\":\"on-band\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\","
                + " \"fail\":{\"gt\":373.86}},"
                + "{\"id\":\"no-value\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"p85\","
                + " \"fail\":{\"gt\":1}}]}").get("assertions");

        JsonNode onBand = rules.get(0);
        assertEquals("FAIL", onBand.get("status").asText(), onBand.toString());
        assertEquals(373.8625, onBand.get("results").get(0).get("actual").asDouble(), 0.0,
                "the page gets the number Java judged, not two decimals of it: " + onBand);

        // p85 with no distribution: this run cannot answer it. Jackson would write a NaN as the
        // string "NaN"; the page is handed null, which it reads as no value.
        JsonNode noValue = rules.get(1).get("results").get(0);
        assertEquals("NO_VALUE", noValue.get("notJudged").asText(), noValue.toString());
        assertTrue(noValue.get("actual").isNull(), "a match with no value is no number: " + noValue);
    }

    @Test
    void thePageIsGivenTheFoldsWordSoTheSummaryCardNeverFoldsASecondTime() throws Exception {
        // SF22 / D24. The Summary card folded chartData.serverGates itself - the DECLARED components
        // only - so on a run whose assert named an undeclared component it read WARN beside a
        // "(no component) NOT EVALUATED" chip and a NOT_EVALUATED serverStatus. One fold: the page
        // is handed its word, and the entry the chip names.
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 500, "kafka"),
                "{\"id\":\"w\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"warn\":{\"gt\":1},\"fail\":{\"gt\":9999}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("typo", "lag", 500, "kafak"));
        ReportAnnotations a = json.readValue(doc, ReportAnnotations.class);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100)), data);
        JsonNode chart = json.readTree(serializer.serializeChartData(data, a, null, GateEvaluation.of(data, a),
                server.payload(), server));

        JsonNode verdict = chart.get("serverVerdict");
        assertNotNull(verdict, chart.toString());
        assertEquals(server.serverStatus(), verdict.get("status").asText(), "the CI/CD JSON's serverStatus, verbatim");
        assertEquals("NOT_EVALUATED", verdict.get("status").asText(), "not db's WARN: the reserved entry decided nothing");
        assertEquals("(no gate)", verdict.get("component").asText(), "the chip's entry, by its wire id");
        assertEquals("(no component)", verdict.get("label").asText(), "and by the word a reader sees");
        assertEquals(2, verdict.get("others").asInt(), "kafka and db, as the chip counts them");
        JsonNode reserved = verdict.get("reserved");
        assertEquals("(no gate)", reserved.get("id").asText());
        assertEquals("(no component)", reserved.get("label").asText());
        assertEquals(1, reserved.get("notJudged").get("invalidRule").asInt());
        // The strip's list is unchanged: the declared components, and no badge for the reserved one.
        assertEquals(2, chart.get("serverGates").size());

        // A run with no orphan names a declared component and carries no reserved entry.
        String clean = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("k", "lag", 50, "kafka"));
        ReportAnnotations ca = json.readValue(clean, ReportAnnotations.class);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation cleanServer =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(clean,
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100)), data);
        JsonNode cleanVerdict = json.readTree(serializer.serializeChartData(data, ca, null, GateEvaluation.of(data, ca),
                cleanServer.payload(), cleanServer)).get("serverVerdict");
        assertEquals("FAIL", cleanVerdict.get("status").asText());
        assertEquals("kafka", cleanVerdict.get("component").asText());
        assertEquals("Kafka", cleanVerdict.get("label").asText());
        assertFalse(cleanVerdict.has("reserved"));
    }

    @Test
    void theCatalogueReachesEveryPageAsTheVocabularyDoes() throws Exception {
        // SF5. Put after the no-block exit, the catalogue was missing from the one page with
        // everything still to declare: a client-only report's empty Server tab offered only
        // "Custom", and a file with postgres/kafka components and no asserts yet read "type
        // 'postgres' is not one of custom" twice, with "+ metric" opening a dead end.
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        java.util.Map<String, Object> expected = com.jmeterwebinsightreport.core.servermetrics.catalogue.Catalogue
                .builtIn().pageJson();
        JsonNode expectedJson = json.valueToTree(expected);

        // No serverMetrics block at all, and no evaluation to hand.
        JsonNode none = json.readTree(serializer.serializeChartData(data, null, null, GateEvaluation.none(), null,
                com.jmeterwebinsightreport.report.gate.ServerGateEvaluation.none()));
        assertEquals(expectedJson, none.get("serverCatalogue"), "a report with no server block carries the catalogue");
        JsonNode noEvaluation = json.readTree(serializer.serializeChartData(data, null, null, GateEvaluation.none(),
                null, null));
        assertEquals(expectedJson, noEvaluation.get("serverCatalogue"));

        // Components and no asserts yet: declares nothing, still the catalogue.
        String componentsOnly = "{\"serverMetrics\":{\"components\":{\"orders-db\":{\"type\":\"postgres\"},"
                + "\"events\":{\"type\":\"kafka\"}},\"asserts\":[]}}";
        ReportAnnotations a = json.readValue(componentsOnly, ReportAnnotations.class);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(componentsOnly, null, data);
        assertFalse(server.declared(), "no assert, so nothing is declared to judge");
        JsonNode chart = json.readTree(serializer.serializeChartData(data, a, null, GateEvaluation.of(data, a),
                null, server));
        assertEquals(expectedJson, chart.get("serverCatalogue"));
        assertTrue(chart.get("serverCatalogue").get("order").toString().contains("postgres"));
    }
}
