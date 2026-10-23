package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.assertion.SamplerVerdicts;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CiCdJsonSerializerTest {

    private CiCdJsonSerializer serializer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serializer = new CiCdJsonSerializer(objectMapper);
    }

    // --- The artifact manifest and the asserts-evaluated flag ---

    @Test
    void theManifestDeclaresWhereEveryArtifactWent() throws JsonProcessingException {
        // A pipeline that wanted the HTML or the JUnit XML had to reimplement the plugin's own
        // filename derivation — ${timestamp} substitution, extension handling and the
        // fallback-on-rejection rules included. Declaring the paths is shorter and correct by
        // construction.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("outputDirectory", "/out");
        Map<String, Object> html = new LinkedHashMap<>();
        html.put("path", "/out/r.html");
        html.put("outcome", "WRITTEN");
        manifest.put("html", html);

        JsonNode json = objectMapper.readTree(serializer.serialize(
                createReportData("T", 1000, 10, 0), null, null, manifest));

        assertEquals("/out", json.path("artifacts").path("outputDirectory").asText());
        assertEquals("/out/r.html", json.path("artifacts").path("html").path("path").asText());
        assertEquals("WRITTEN", json.path("artifacts").path("html").path("outcome").asText());
    }

    @Test
    void theManifestIsOmittedWhenTheCallerHasNoneToDeclare() throws JsonProcessingException {
        JsonNode json = objectMapper.readTree(serializer.serialize(
                createReportData("T", 1000, 10, 0), null, null));
        assertTrue(json.path("artifacts").isMissingNode(),
                "an absent manifest must not appear as null");
    }

    @Test
    void theDocumentIsWrittenWithLfOnEveryOs() throws JsonProcessingException {
        String text = serializer.serialize(createReportData("T", 1000, 10, 0), null, null);
        assertFalse(text.contains("\r"), "a CR made the same run a different file on Windows");
        assertTrue(text.startsWith("{\n  \"runId\" : "), text);
    }

    @Test
    void assertsEvaluatedDistinguishesNoRulesFromNoVerdict() throws JsonProcessingException {
        // "UNKNOWN" conflated the two, so a pipeline could not tell "the gate is not set up" from
        // "the gate ran" — which is how a typo in -Jwebinsight.report.annotations silently turned
        // the quality gate off while still reporting a status.
        JsonNode noSla = objectMapper.readTree(serializer.serialize(
                createReportData("T", 1000, 10, 0), null, null));
        assertEquals("UNKNOWN", noSla.path("status").asText());
        assertFalse(noSla.path("assertsEvaluated").asBoolean(), "no rules were declared");

        SamplerVerdicts.Builder results = SamplerVerdicts.builder();
        results.put("GET /x", AssertionStatus.PASS, Collections.singletonMap("p95", AssertionStatus.PASS));
        JsonNode withSla = objectMapper.readTree(serializer.serialize(
                createReportData("T", 1000, 10, 0), results.build(), AssertionStatus.PASS));
        assertTrue(withSla.path("assertsEvaluated").asBoolean());
        assertEquals("PASS", withSla.path("status").asText());
    }

    /**
     * One vocabulary in the output. No sla* key survives.
     *
     * <p>They were emitted as deprecated aliases for exactly one release, on the reasoning that a
     * pipeline parsing the old names did nothing wrong. That reasoning ended when the old way
     * of declaring a gate was removed: there is no longer a dialect a reader could be speaking
     * on purpose.
     *
     * <p>Asserted as an absence sweep rather than key by key — a new sla* key cannot creep back
     * in under a name this test did not think to list.
     */
    @Test
    void theOutputCarriesNoSlaVocabularyAtAll() throws JsonProcessingException {
        ReportData data = createReportData("T", 1000, 10, 1);
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(createSamplerStats("GET /x", 500, 150.0, 300.0, 500.0, 1.0, 8.33));
        data.setSamplerStatistics(stats);

        SamplerVerdicts.Builder results = SamplerVerdicts.builder();
        results.put("GET /x", AssertionStatus.FAIL,
                Collections.singletonMap("p95", AssertionStatus.FAIL));
        JsonNode json = objectMapper.readTree(
                serializer.serialize(data, results.build(), AssertionStatus.FAIL));

        List<String> offenders = new ArrayList<>();
        collectSlaKeys(json, "", offenders);
        assertTrue(offenders.isEmpty(), "the JSON still speaks the retired dialect: " + offenders);

        // And the replacements are all present, so this is a rename rather than a deletion.
        assertTrue(json.has("assertsEvaluated"));
        assertTrue(json.has("assertViolations"));
        assertEquals("FAIL", json.path("samplers").get(0).path("assertStatus").asText());
    }

    private static void collectSlaKeys(JsonNode node, String path, List<String> out) {
        collectKeys(node, path, name -> name.startsWith("sla"), out);
    }

    /** Every key anywhere in the document whose lower-cased name matches, with its path. */
    private static void collectKeys(JsonNode node, String path, java.util.function.Predicate<String> bad,
                                    List<String> out) {
        if (node.isObject()) {
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (bad.test(name.toLowerCase(java.util.Locale.ROOT))) {
                    out.add(path + "/" + name);
                }
                collectKeys(node.get(name), path + "/" + name, bad, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectKeys(node.get(i), path + "[" + i + "]", bad, out);
            }
        }
    }

    /**
     * D25: "server assert" is the word, on the wire as everywhere a user reads. No key anywhere in
     * the document may contain {@code kpi}, in any case — swept, like the {@code sla} vocabulary
     * above, so a new key cannot bring the internal word back under a name this test did not list.
     *
     * <p>Over a document carrying every server shape the serializer can write: both gates, an
     * observe-only one, an assert with every optional field, a ratio, one that matched nothing,
     * one that could not run, one in no declared gate, a client gate beside them, and the
     * findings a leftover {@code kpis} key produces — a value that names the old key is fine; a
     * key is not.
     */
    @Test
    void theOutputCarriesNoKpiKeyAnywhere() throws Exception {
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String annotations = "{\"assertions\":[{\"id\":\"load:throughput\",\"target\":{\"scope\":\"overall\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":0.001}}],"
                + "\"serverMetrics\":{"
                + "\"components\":{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}},"
                + "\"kpis\":[],"
                + "\"asserts\":["
                + "{\"id\":\"lag\",\"description\":\"d\",\"component\":\"kafka\",\"query\":\"lag\",\"unit\":\"msgs\","
                + "\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"15s\"}},\"across\":\"avg\","
                + "\"requiresLoad\":\"load:throughput\",\"warn\":{\"gt\":100},\"fail\":{\"gt\":500}},"
                + "{\"id\":\"ratio\",\"component\":\"db\",\"query\":\"conn\",\"reduce\":{\"kind\":\"max\"},"
                + "\"divideBy\":\"load:throughput\",\"fail\":{\"gt\":10}},"
                + "{\"id\":\"empty\",\"component\":\"db\",\"query\":\"none\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}},"
                + "{\"id\":\"typo\",\"component\":\"db\",\"query\":\"lag\",\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}},"
                + "{\"id\":\"orphan\",\"component\":\"nope\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}"
                + "]}}";
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700, 700, 700),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("conn", 3, 4),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.series("none", "EMPTY", 0.0, null));
        com.jmeterwebinsightreport.report.annotation.ReportAnnotations a =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(annotations);
        com.jmeterwebinsightreport.report.gate.GateEvaluation client =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data, a);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(annotations, payload, data);
        assertTrue(server.evaluated(), "the sweep must run over an evaluated server block");
        assertFalse(server.orphans().isEmpty(), "the sweep must see an assert in no declared gate");

        List<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding> findings =
                new ArrayList<>(client.findings());
        findings.addAll(server.findings());
        JsonNode json = objectMapper.readTree(serializer.serialize(data, null, AssertionStatus.PASS, null,
                client.gate(), findings, server));

        // The rename is a rename, not a deletion: the list is there, under its new name, whole.
        assertEquals(5, json.path("serverAsserts").size(), json.toString());
        assertTrue(json.has("serverComponents"));
        assertTrue(json.has("gate"), "a client gate rides beside the server block");
        boolean told = false;
        for (JsonNode f : json.path("findings")) {
            told |= f.path("message").asText().startsWith("\"kpis\" is not a serverMetrics key");
        }
        assertTrue(told, "the leftover-key finding reaches the document: " + json.path("findings"));

        List<String> offenders = new ArrayList<>();
        collectKeys(json, "", name -> name.contains("kpi"), offenders);
        assertTrue(offenders.isEmpty(), "the JSON still names a key after the internal word: " + offenders);
    }

    /**
     * SF16, the decision pinned: JUnit and the report write a slope verdict in {@code B/min} and a
     * ratio in {@code ÷ <rule>}, and the JSON's {@code unit} stays the DECLARED one, as F5 kept it
     * for divideBy. It is a consumed contract, and {@code reduce.kind} and {@code divideBy} already
     * tell a machine what the verdict unit says.
     */
    @Test
    void aSlopeOrRatioAssertKeepsItsDeclaredUnit() throws Exception {
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String annotations = "{\"assertions\":[{\"id\":\"load:throughput\",\"target\":{\"sampler\":\"GET /a\"},"
                + "\"metric\":\"throughput\",\"fail\":{\"lt\":1}}],"
                + "\"serverMetrics\":{\"components\":{\"G\":{\"type\":\"custom\"}},\"asserts\":["
                + "{\"id\":\"growth\",\"component\":\"G\",\"query\":\"heap\",\"unit\":\"B\",\"reduce\":{\"kind\":\"slope\"},\"fail\":{\"gt\":50}},"
                + "{\"id\":\"growth-per-rps\",\"component\":\"G\",\"query\":\"heap\",\"unit\":\"B\",\"reduce\":{\"kind\":\"slope\"},"
                + "\"divideBy\":\"load:throughput\",\"fail\":{\"gt\":10}}"
                + "]}}";
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("heap", 0, 15, 30, 45, 60));
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(annotations));
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(annotations, payload, data);

        JsonNode json = objectMapper.readTree(serializer.serialize(data, null, AssertionStatus.PASS, null,
                client.gate(), new ArrayList<>(server.findings()), server));
        JsonNode growth = json.path("serverAsserts").get(0);
        assertEquals("growth", growth.path("id").asText());
        assertEquals("B", growth.path("unit").asText(), "the series' unit, not B/min: " + growth);
        assertEquals("slope", growth.path("reduce").path("kind").asText());
        assertEquals(60, growth.path("actual").asDouble(), 1e-6, "the actual is per minute all the same");
        JsonNode both = json.path("serverAsserts").get(1);
        assertEquals("B", both.path("unit").asText(), "no '/min', no divisor: " + both);
        assertEquals("load:throughput", both.path("divideBy").asText());
        assertEquals(12, both.path("actual").asDouble(), 1e-6);
    }

    @Test
    void anEmptySetOfVerdictsCountsAsNotEvaluated() throws JsonProcessingException {
        // A gate that declared rules but judged nothing has not passed. assertsEvaluated is what a
        // pipeline reads to tell "the gate is not set up" from "the gate ran".
        JsonNode json = objectMapper.readTree(serializer.serialize(
                createReportData("T", 1000, 10, 0), SamplerVerdicts.builder().build(), null));
        assertFalse(json.path("assertsEvaluated").asBoolean());
    }

    // --- Helper methods ---

    private static ReportData createReportData(String testName, long duration,
                                                int totalSamples, int totalErrors) {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName(testName);
        meta.setDurationMillis(duration);
        meta.setTotalSamples(totalSamples);
        meta.setTotalErrors(totalErrors);
        meta.setReportGeneratedAt("2024-01-15T10:30:00");
        data.setMetadata(meta);
        return data;
    }

    private static SamplerStatistics createSamplerStats(String name, long sampleCount,
                                                         double mean, double p95, double p99,
                                                         double errorRate, double throughput) {
        SamplerStatistics stat = new SamplerStatistics();
        stat.setSamplerName(name);
        stat.setSampleCount(sampleCount);
        stat.setMeanResponseTime(mean);
        stat.setPercentile95(p95);
        stat.setPercentile99(p99);
        stat.setErrorRate(errorRate);
        stat.setThroughput(throughput);
        return stat;
    }

    // --- Tests ---

    @Test
    void serialize_producesValidJson() throws JsonProcessingException {
        ReportData data = createReportData("Test Plan", 60000, 1000, 5);

        String json = serializer.serialize(data, null, null);

        assertNotNull(json);
        // Verify it's valid JSON by parsing it
        assertDoesNotThrow(() -> objectMapper.readTree(json));
    }

    @Test
    void serialize_includesTestName() throws JsonProcessingException {
        ReportData data = createReportData("My Performance Test", 30000, 500, 0);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("My Performance Test", root.get("testName").asText());
    }

    @Test
    void serialize_includesOverallStatus() throws JsonProcessingException {
        ReportData data = createReportData("Test", 10000, 100, 0);

        String json = serializer.serialize(data, null, AssertionStatus.PASS);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("PASS", root.get("status").asText());
    }

    @Test
    void serialize_includesDuration() throws JsonProcessingException {
        ReportData data = createReportData("Test", 120000, 500, 0);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(120000, root.get("duration").asLong());
    }

    @Test
    void serialize_includesPerSamplerSummary() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 10);
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(createSamplerStats("GET /api/users", 500, 150.0, 300.0, 500.0, 1.0, 8.33));
        stats.add(createSamplerStats("POST /api/login", 500, 200.0, 400.0, 600.0, 1.0, 8.33));
        data.setSamplerStatistics(stats);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        JsonNode samplers = root.get("samplers");
        assertNotNull(samplers);
        assertTrue(samplers.isArray());
        assertEquals(2, samplers.size());

        JsonNode first = samplers.get(0);
        assertEquals("GET /api/users", first.get("name").asText());
        assertEquals(500, first.get("sampleCount").asLong());
        assertEquals(150.0, first.get("mean").asDouble(), 0.01);
        assertEquals(300.0, first.get("p95").asDouble(), 0.01);
        assertEquals(500.0, first.get("p99").asDouble(), 0.01);
    }

    @Test
    void serialize_includesAssertViolations() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 50);
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(createSamplerStats("GET /api/slow", 500, 800.0, 2000.0, 3000.0, 5.0, 8.33));
        data.setSamplerStatistics(stats);

        // Create assert results with a FAIL for the sampler
        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metricStatuses = new LinkedHashMap<>();
        metricStatuses.put("p95", AssertionStatus.FAIL);
        assertResults.put("GET /api/slow", AssertionStatus.FAIL, metricStatuses);

        String json = serializer.serialize(data, assertResults.build(), AssertionStatus.FAIL);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("FAIL", root.get("status").asText());

        JsonNode violations = root.get("assertViolations");
        assertNotNull(violations);
        assertTrue(violations.isArray());
        assertEquals(1, violations.size());
        assertEquals("GET /api/slow", violations.get(0).get("sampler").asText());
        assertEquals("FAIL", violations.get(0).get("status").asText());
    }

    @Test
    void serialize_statusUnknownWithoutSla() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 0);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("UNKNOWN", root.get("status").asText());
    }

    @Test
    void serialize_includesTimestamp() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 100, 0);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("generatedAt"));
        assertEquals("2024-01-15T10:30:00", root.get("generatedAt").asText());
    }

    @Test
    void serialize_includesErrorRate() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 50);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(5.0, root.get("errorRate").asDouble(), 0.01);
        assertEquals(1000, root.get("totalSamples").asInt());
        assertEquals(50, root.get("totalErrors").asInt());
    }

    @Test
    void serialize_passingSamplerNotInViolations() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 0);
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(createSamplerStats("GET /api/fast", 1000, 50.0, 100.0, 150.0, 0.0, 16.67));
        data.setSamplerStatistics(stats);

        SamplerVerdicts.Builder assertResults = SamplerVerdicts.builder();
        Map<String, AssertionStatus> metricStatuses = new LinkedHashMap<>();
        metricStatuses.put("p95", AssertionStatus.PASS);
        assertResults.put("GET /api/fast", AssertionStatus.PASS, metricStatuses);

        String json = serializer.serialize(data, assertResults.build(), AssertionStatus.PASS);
        JsonNode root = objectMapper.readTree(json);

        JsonNode violations = root.get("assertViolations");
        assertNotNull(violations);
        assertEquals(0, violations.size(), "PASS samplers should not appear in violations");
    }

    @Test
    void serialize_handlesNullMetadata() throws JsonProcessingException {
        ReportData data = new ReportData();
        // metadata is null

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals("Unknown", root.get("testName").asText());
        assertEquals(0, root.get("duration").asLong());
    }

    @Test
    void serialize_includesApdexScore() throws JsonProcessingException {
        ReportData data = createReportData("Apdex Test", 60000, 1000, 0);
        SamplerStatistics stat = createSamplerStats("GET /api/users", 1000,
                100.0, 200.0, 300.0, 0.0, 16.67);
        stat.setApdexScore(0.85);
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(stat);
        data.setSamplerStatistics(stats);

        String json = serializer.serialize(data, null, AssertionStatus.PASS);
        JsonNode root = objectMapper.readTree(json);

        JsonNode samplers = root.get("samplers");
        assertNotNull(samplers);
        assertTrue(samplers.isArray());
        assertEquals(1, samplers.size());

        JsonNode sampler = samplers.get(0);
        assertTrue(sampler.has("apdex"), "Sampler JSON should contain 'apdex' field");
        assertEquals(0.85, sampler.get("apdex").asDouble(), 0.001);
    }

    // --- Population used for the headline totals (CORE-9) ---

    @Test
    void serialize_totalsExcludeTransactionChildren() throws JsonProcessingException {
        // metadata.totalSamples counts the TC parent AND its children (300); the header
        // tiles of the HTML report count only the 100 top-level samples. The pipeline's
        // pass/fail numbers must agree with the report generated from the same run.
        ReportData data = createReportData("TC Run", 60000, 300, 10);

        SamplerStatistics parent = createSamplerStats("Login Flow", 100, 300, 400, 500, 10.0, 1.67);
        parent.setErrorCount(10);
        parent.setTransactionController(true);

        SamplerStatistics child1 = createSamplerStats("POST /login", 100, 150, 200, 250, 0.0, 1.67);
        child1.setParentSamplerName("Login Flow");
        SamplerStatistics child2 = createSamplerStats("GET /profile", 100, 150, 200, 250, 0.0, 1.67);
        child2.setParentSamplerName("Login Flow");

        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(parent);
        stats.add(child1);
        stats.add(child2);
        data.setSamplerStatistics(stats);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(100, root.get("totalSamples").asInt());
        assertEquals(10, root.get("totalErrors").asInt());
        // 10/100, not 10/300 = 3.33
        assertEquals(10.0, root.get("errorRate").asDouble(), 0.01);
    }

    @Test
    void serialize_totalsFallBackToMetadataWithoutSamplerStats() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 50);

        String json = serializer.serialize(data, null, null);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(1000, root.get("totalSamples").asInt());
        assertEquals(50, root.get("totalErrors").asInt());
        assertEquals(5.0, root.get("errorRate").asDouble(), 0.01);
    }

    // --- Run identity (R5.3) ---

    /**
     * A consumer of this file can tell which run produced it without trusting the filename.
     *
     * <p>The four artifacts of one run are bound to each other by name, and names are not
     * unique per run. Handed a {@code .json} out of a shared output directory, a pipeline had no
     * way to know whether it described the HTML sitting beside it — which is the whole reason
     * two reports in one directory could disagree with each other in silence.
     *
     * <p>This is also the value both ingestion paths compare to decide whether an existing
     * report is <em>this</em> run's, so it is not decorative: a wrong id here means a run
     * silently produces no report at all.
     */
    @Test
    void everyRunDeclaresItsIdentity() throws JsonProcessingException {
        ReportData data = createReportData("Test", 60000, 1000, 50);
        JsonNode root = objectMapper.readTree(serializer.serialize(data, null, null));

        assertTrue(root.hasNonNull("runId"), "the CI/CD JSON must declare which run it describes");
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.of(data),
                root.get("runId").asText(),
                "and it must be the same id the HTML and the payload carry — derived from the "
                        + "same data, never plumbed separately");
        assertTrue(root.get("runId").asText().matches("[0-9a-f]{16}"),
                "64 bits of hex: " + root.get("runId").asText());
    }

    @Test
    void twoRunsOfTheSamePlanAreDistinguishable() throws JsonProcessingException {
        // The point of the field. Two runs of one plan produce two files with the same NAME in
        // the same directory; only the content can tell them apart.
        ReportData first = createReportData("Same Plan", 60000, 1000, 50);
        first.getMetadata().setStartTime(java.time.Instant.ofEpochMilli(1_700_000_000_000L));
        ReportData second = createReportData("Same Plan", 60000, 1000, 50);
        second.getMetadata().setStartTime(java.time.Instant.ofEpochMilli(1_700_000_060_000L));

        assertNotEquals(
                objectMapper.readTree(serializer.serialize(first, null, null)).get("runId").asText(),
                objectMapper.readTree(serializer.serialize(second, null, null)).get("runId").asText());
    }

    @Test
    void theIdentityLeadsTheDocument() throws JsonProcessingException {
        // Position is not a contract a parser depends on, but a human reading the file to work
        // out which run it came from should not have to scroll past a sampler array to find out.
        String json = serializer.serialize(createReportData("Test", 60000, 1000, 50), null, null);
        JsonNode root = objectMapper.readTree(json);
        assertEquals("runId", root.fieldNames().next(), json);
    }

    // --- The server gates (M4) ---

    @Test
    void theServerGatesRideBesideTheClientGateInTheSameShape() throws Exception {
        // One arithmetic, one jq idiom: serverComponents[i] (C7) carries {id, label, type,
        // collectionId} and then exactly the client gate block (review §1.2). No failsBuild (C6).
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka"),
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("conn", "conn", 10, "db")),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700),
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("conn", 3, 4)),
                        data);
        JsonNode json = objectMapper.readTree(serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), server));

        assertTrue(json.get("assertsEvaluated").asBoolean(), "a server gate that ran counts as evaluated");
        assertFalse(json.has("gate"), "no client gate was declared");
        JsonNode gates = json.get("serverComponents");
        assertEquals(2, gates.size());
        JsonNode kafka = gates.get(0);
        assertEquals("kafka", kafka.get("id").asText());
        assertEquals("Kafka", kafka.get("label").asText());
        assertEquals("custom", kafka.get("type").asText(), "the component's type (C7)");
        assertFalse(kafka.has("failsBuild"), "every component builds, so nothing says otherwise (C6)");
        assertEquals("c0ffee", kafka.get("collectionId").asText());
        assertEquals("FAIL", kafka.get("status").asText());
        assertEquals(1, kafka.get("declared").asInt());
        assertEquals(1, kafka.get("failed").asInt());
        for (com.jmeterwebinsightreport.core.assertion.NotJudged r : com.jmeterwebinsightreport.core.assertion.NotJudged.values()) {
            assertTrue(kafka.get("notJudged").has(r.countKey()), "the same block as the client gate: " + r.countKey());
        }
        JsonNode db = gates.get(1);
        assertFalse(db.has("failsBuild"), "every component builds (C6)");
        assertEquals("PASS", db.get("status").asText());

        assertFalse(json.has("serverKpis"), "D25: the list is serverAsserts on the wire");
        JsonNode kpis = json.get("serverAsserts");
        assertEquals(2, kpis.size());
        JsonNode lag = kpis.get(0);
        assertEquals("lag", lag.get("id").asText());
        assertEquals("kafka", lag.get("component").asText());
        assertFalse(lag.has("gate"), "the key is component (C7)");
        assertFalse(lag.has("source"), "a collection has one source, never the file's (C8)");
        assertEquals("{\"kind\":\"max\"}", lag.get("reduce").toString(), "the file's grammar, not a sentence (A1-12)");
        assertEquals("FAIL", lag.get("status").asText());
        assertEquals(700.0, lag.get("actual").asDouble());
        assertEquals(500.0, lag.get("fail").get("gt").asDouble());
        assertEquals(500.0, lag.get("breached").get("gt").asDouble());
        assertEquals(1, lag.get("instances").asInt());
        assertFalse(lag.has("notJudged"));

        List<String> offenders = new ArrayList<>();
        collectSlaKeys(json, "", offenders);
        assertTrue(offenders.isEmpty(), "the server block speaks the retired dialect: " + offenders);
    }

    private JsonNode serverJson(String doc, String payload) throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, payload, data);
        return objectMapper.readTree(serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), server));
    }

    @Test
    void serverStatusSitsBesideStatusExactlyWhenServerGatesDoes() throws Exception {
        // D24. The server side's own word, beside the client's and never folded into it: the worst
        // component - kafka FAILs, db PASSes.
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"},\"db\":{\"type\":\"custom\",\"description\":\"DB\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("conn", "conn", 10, "db"));
        JsonNode json = serverJson(doc, com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("conn", 3, 4)));
        assertEquals("FAIL", json.get("serverStatus").asText());
        assertEquals("UNKNOWN", json.get("status").asText(), "the client's word is untouched - no rollup");
        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(keys.indexOf("status") + 1, keys.indexOf("serverStatus"), "beside status: " + keys);

        // ...absent exactly when serverGates is: the interim report, and a run with no block.
        JsonNode interim = serverJson(com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, null)), null);
        assertFalse(interim.has("serverStatus"));
        assertFalse(interim.has("serverComponents"));
        JsonNode none = serverJson("{\"testNotes\":\"n\"}", null);
        assertFalse(none.has("serverStatus"));
    }

    @Test
    void assertsThatNameNoDeclaredGateAreCountedUnderAReservedGateEntry() throws Exception {
        // A1-6. An assert naming no declared gate is INVALID_RULE and exits 1 by default (D1), and it
        // was in serverAsserts[] and in no gate's count - so a pipeline summing
        // serverGates[].notJudged.invalidRule under failonerror=false missed exactly those. They are
        // a "(no gate)" entry now, as they have been a "(no gate)" JUnit suite since M4.
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("typo", "lag", 500, "kafak"),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("unnamed", "lag", 500, null));
        JsonNode json = serverJson(doc, com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100)));

        JsonNode gates = json.get("serverComponents");
        assertEquals(2, gates.size(), gates.toString());
        assertEquals("kafka", gates.get(0).get("id").asText());
        assertEquals("PASS", gates.get(0).get("status").asText());
        JsonNode noGate = gates.get(1);
        assertEquals("(no gate)", noGate.get("id").asText(), "the id is the contract, and keeps its name");
        // The label is what a reader is shown, and it is the Server tab's word for it (SF22).
        assertEquals("(no component)", noGate.get("label").asText());
        assertFalse(noGate.has("type"), "the reserved entry is no component, so it has no type");
        assertEquals("c0ffee", noGate.get("collectionId").asText());
        assertEquals(2, noGate.get("declared").asInt());
        assertEquals("NOT_EVALUATED", noGate.get("status").asText());
        for (com.jmeterwebinsightreport.core.assertion.NotJudged r : com.jmeterwebinsightreport.core.assertion.NotJudged.values()) {
            assertTrue(noGate.get("notJudged").has(r.countKey()), "the same block as every gate: " + r.countKey());
        }
        // The arithmetic a pipeline does: every INVALID_RULE in serverAsserts[] is in some gate's count.
        int summed = 0;
        for (JsonNode g : gates) {
            summed += g.get("notJudged").get("invalidRule").asInt();
        }
        int invalid = 0;
        for (JsonNode a : json.get("serverAsserts")) {
            invalid += "INVALID_RULE".equals(a.path("notJudged").asText()) ? 1 : 0;
        }
        assertEquals(2, invalid);
        assertEquals(invalid, summed, "sum(serverComponents[].notJudged.invalidRule) counts every refused assert");
        // ...and it takes part in serverStatus like any building gate: kafka's PASS does not speak
        // for a build two asserts of which never ran.
        assertEquals("NOT_EVALUATED", json.get("serverStatus").asText());

        // No orphan, no entry.
        JsonNode clean = serverJson(com.jmeterwebinsightreport.report.gate.ServerFixtures.block(
                "{\"kafka\":{\"type\":\"custom\",\"description\":\"Kafka\"}}",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, "kafka")),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100)));
        assertEquals(1, clean.get("serverComponents").size());
        assertEquals("PASS", clean.get("serverStatus").asText());
    }

    @Test
    void reduceIsWrittenInTheFilesGrammarNotAsASentence() throws Exception {
        // A1-12. "max sustained for 10s" made a consumer parse English to recover the reducer, and
        // any rewording of Reducer.describe() moved a consumed contract. The object is the file's own
        // {kind, over?}, spelled the way the editor writes it back; null stays null for a reducer
        // the file declared and Java could not read.
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                "{\"id\":\"plain\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"MAX\"},\"fail\":{\"gt\":1}}",
                "{\"id\":\"held\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"30000ms\"}},\"fail\":{\"gt\":1}}",
                "{\"id\":\"budget\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"min\",\"over\":{\"breachBudget\":0.07}},\"fail\":{\"lt\":1}}",
                "{\"id\":\"pct\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"p97.5\"},\"fail\":{\"gt\":1}}",
                "{\"id\":\"typo\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"maxx\"},\"fail\":{\"gt\":1}}");
        JsonNode asserts = serverJson(doc, com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 1, 2, 3))).get("serverAsserts");
        assertEquals("{\"kind\":\"max\"}", asserts.get(0).get("reduce").toString());
        assertEquals("{\"kind\":\"max\",\"over\":{\"sustainedFor\":\"30s\"}}", asserts.get(1).get("reduce").toString());
        assertEquals("{\"kind\":\"min\",\"over\":{\"breachBudget\":0.07}}", asserts.get(2).get("reduce").toString());
        assertEquals("{\"kind\":\"p97.5\"}", asserts.get(3).get("reduce").toString());
        assertTrue(asserts.get(4).get("reduce").isNull(), "an unreadable reducer is null, not a guess");
        for (JsonNode a : asserts) {
            assertFalse(a.get("reduce").isTextual(), "never prose: " + a);
        }
    }

    @Test
    void anUnevaluatedServerBlockAddsOnlyWhatToCollect() throws Exception {
        // The interim report: declared, no payload. No serverStatus, no serverComponents, no
        // serverAsserts - only serverData, which tells a hook what to export (P2). Without it the
        // document is byte-identical to one with no block at all.
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation declared =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("lag", "lag", 500, null)),
                        null, data);
        String with = serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), declared);
        String without = serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), null);
        com.fasterxml.jackson.databind.JsonNode tree = objectMapper.readTree(with);
        com.fasterxml.jackson.databind.JsonNode serverData = tree.get("serverData");
        assertEquals("NOT_COLLECTED", serverData.get("state").asText());
        assertEquals(1, serverData.get("series").size());
        assertEquals("lag", serverData.get("series").get(0).get("metric").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("serverData");
        assertEquals(objectMapper.readTree(without), tree);
        assertFalse(tree.get("assertsEvaluated").asBoolean());
        assertNull(tree.get("serverStatus"));
        // ...and every document carries the run's window.
        assertTrue(tree.get("window").get("startMillis").isNumber(), tree.toString());
    }

    // --- N4: the wire writes what was measured ---

    /**
     * N4. {@code round4} was {@code Math.round(v * 1e4) / 1e4}, and {@code Math.round} clamps at
     * {@code Long.MAX_VALUE}: every server actual from 9.223372036854776E14 up was written as that
     * one constant. A FAIL at 3E15 against {@code gt 2E15} read as below its own limit, and a PASS
     * at 1.7593E15 (epoch microseconds) against {@code lt 1E15} as breaching it - the document
     * contradicted its own status, while JUnit printed the true number. Reachable by any custom
     * query over a cumulative byte counter, a fleet total or a value exported in microseconds.
     *
     * <p>Past the edge a double's spacing is at least 1/8, so it has no digit beyond the third
     * decimal and there is nothing to round: it is written as measured. A fifth decimal at 1.2E11 is
     * still rounded away. 9.223372036854775E14 pins only that nothing jumps at the edge. An infinite
     * actual (an {@code avg} that overflows) keeps the old path and stays a number.
     *
     * <p>Since S2 a finite server actual goes through {@code Digits.beside} at four places, not
     * round4, and Digits writes a value as it is from 2^53 / 10^4 (9.007E11) up - its own edge,
     * where {@code value * 10^4} stops being exact. So the last double below 2^49, which round4's
     * old expression moves one ulp (to 5.629499534213119E14), is written as measured here now, the
     * number JUnit and the page show. Since L14 {@code attainment.consumed} goes through the same
     * Digits edge, so that ulp is written as measured there too
     * ({@link #aConsumedRatioPastLongsRangeIsWrittenAsMeasured}).
     */
    @Test
    void aServerActualPastLongsRangeIsWrittenAsMeasured() throws Exception {
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                "{\"id\":\"bytes\",\"component\":\"server\",\"query\":\"bytes_total\",\"unit\":\"B\","
                        + "\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":2.0E15}}",
                "{\"id\":\"epoch-us\",\"component\":\"server\",\"query\":\"last_backup_epoch_us\","
                        + "\"reduce\":{\"kind\":\"last\"},\"fail\":{\"lt\":1.0E15}}",
                "{\"id\":\"neg\",\"component\":\"server\",\"query\":\"neg\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"gt\":0}}",
                "{\"id\":\"edge\",\"component\":\"server\",\"query\":\"edge\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1.0E16}}",
                "{\"id\":\"lag\",\"component\":\"server\",\"query\":\"lag\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":500}}",
                "{\"id\":\"fifth\",\"component\":\"server\",\"query\":\"fifth\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1.0E16}}",
                "{\"id\":\"ulp\",\"component\":\"server\",\"query\":\"ulp\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1.0E16}}",
                "{\"id\":\"overflow\",\"component\":\"server\",\"query\":\"overflow\",\"reduce\":{\"kind\":\"avg\"},\"fail\":{\"gt\":1}}");
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("bytes_total", 1.0E15, 2.0E15, 3.0E15),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("last_backup_epoch_us", 1.7593E15, 1.7593E15),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("neg", -1.0E15),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("edge", 9.223372036854775E14),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("lag", 100, 700.123456),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("fifth", 123456789012.123456),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("ulp", Math.nextDown(0x1p49)),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("overflow", 1.7E308, 1.7E308));
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, payload, data);
        JsonNode asserts = objectMapper.readTree(serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), server))
                .get("serverAsserts");
        assertEquals(8, asserts.size(), asserts.toString());

        JsonNode bytes = asserts.get(0);
        assertEquals("bytes", bytes.get("id").asText());
        assertEquals("FAIL", bytes.get("status").asText());
        assertEquals(3.0E15, bytes.get("actual").asDouble(), "written as measured, not clamped: " + bytes);
        assertTrue(bytes.get("actual").asDouble() > bytes.get("fail").get("gt").asDouble(),
                "a FAIL on gt must not read as below its own limit: " + bytes);

        JsonNode epoch = asserts.get(1);
        assertEquals("PASS", epoch.get("status").asText());
        assertEquals(1.7593E15, epoch.get("actual").asDouble());
        assertFalse(epoch.get("actual").asDouble() < epoch.get("fail").get("lt").asDouble(),
                "a PASS must not read as breaching its own lt band: " + epoch);

        assertEquals(-1.0E15, asserts.get(2).get("actual").asDouble(), "the negative side clamped too");
        assertEquals(9.223372036854775E14, asserts.get(3).get("actual").asDouble(),
                "the last double below the edge is unchanged (continuity at the edge, not its place)");
        assertEquals(700.1235, asserts.get(4).get("actual").asDouble(), "below the edge it still rounds to 4dp");
        assertEquals(1.234567890121235E11, asserts.get(5).get("actual").asDouble(),
                "a fifth decimal is still rounded away at 1.2E11 (scaled 1.2E15): " + asserts.get(5));
        assertEquals(Math.nextDown(0x1p49), asserts.get(6).get("actual").asDouble(),
                "past Digits' 2^53 / 10^4 edge the double as measured, not round4's one ulp off: " + asserts.get(6));

        // An avg over two 1.7E308 points overflows to Infinity and FAILs. The old path keeps
        // it a JSON number (Long.MAX_VALUE / 1e4, as before); without the isFinite guard it
        // would be the quoted "Infinity".
        JsonNode overflow = asserts.get(7);
        assertEquals("FAIL", overflow.get("status").asText(), overflow.toString());
        assertTrue(overflow.get("actual").isNumber(), "an infinite actual stays a number: " + overflow);
        assertEquals(Math.round(Double.POSITIVE_INFINITY * 10_000.0) / 10_000.0,
                overflow.get("actual").asDouble(), "the old path, unchanged: " + overflow);

        // The JSON's own builder, past the edge.
        assertEquals(3.0E15, (Double) CiCdJsonSerializer.serverKpiEntries(server).get(0).get("actual"));
        // The page's (chartData.serverKpis) has carried Java's double since F7 and was never
        // clamped; this guards F7 against round4 coming back on that path, not N4.
        assertEquals(3.0E15, (Double) CiCdJsonSerializer.serverKpiEntries(server, true).get(0).get("actual"));
    }

    /**
     * S2 (L20). {@code serverAsserts[].actual} was round4, so a FAIL at 3.00004 under {@code gt 3}
     * was written 3.0 - on the band it breached, beside {@code "breached": {"gt": 3.0}} - and a PASS
     * at 2.99996 under {@code gte 3} was written 3.0, on the band it had not reached, while JUnit's
     * line for the same assert read 3.00004. It goes through {@code Digits.beside} now, the server
     * twin of the client's {@code actualOf}: four places, unless four would put the actual on or
     * across a band the assert declares, warn and fail alike.
     *
     * <p>No band of 0, as the client's JSON has none (Q12): an actual no declared band separates
     * from 0 is still 0.0 at four places, while JUnit, which keeps the never-zero guard, prints it.
     * A declared band at 0 is a band like any other, so a FAIL over {@code gt 0} at 0.00001 is never
     * 0.0, and a FAIL under {@code lt 0} at -0.00003 is never the 0.0 that round4 wrote, which sat on
     * its band.
     */
    @Test
    void aServerActualIsNeverWrittenOnOrAcrossABandItIsNotOn() throws Exception {
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                "{\"id\":\"breach\",\"component\":\"server\",\"query\":\"x\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":3}}",
                "{\"id\":\"short\",\"component\":\"server\",\"query\":\"y\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gte\":3}}",
                "{\"id\":\"warned\",\"component\":\"server\",\"query\":\"w\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"warn\":{\"gt\":2},\"fail\":{\"gt\":10}}",
                "{\"id\":\"floor\",\"component\":\"server\",\"query\":\"f\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":5}}",
                "{\"id\":\"drain\",\"component\":\"server\",\"query\":\"d\",\"reduce\":{\"kind\":\"min\"},\"fail\":{\"lt\":0}}",
                "{\"id\":\"exact\",\"component\":\"server\",\"query\":\"e\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":3}}",
                "{\"id\":\"clear\",\"component\":\"server\",\"query\":\"c\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":500}}",
                "{\"id\":\"tiny\",\"component\":\"server\",\"query\":\"t\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":1}}",
                "{\"id\":\"tiny-breach\",\"component\":\"server\",\"query\":\"t\",\"reduce\":{\"kind\":\"max\"},\"fail\":{\"gt\":0}}");
        String payload = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("x", 1, 3.00004),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("y", 1, 2.99996),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("w", 1, 1.99996),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("f", 9, 4.99996),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("d", 1, -0.00003),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("e", 1, 3),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("c", 1, 700.123456),
                com.jmeterwebinsightreport.report.gate.ServerFixtures.ok("t", 0, 0.00001));
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.evaluate(doc, payload, data);
        JsonNode asserts = objectMapper.readTree(serializer.serialize(data, null, null, null, null,
                Collections.<com.jmeterwebinsightreport.core.assertion.AssertionValidator.Finding>emptyList(), server))
                .get("serverAsserts");
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode a : asserts) {
            byId.put(a.get("id").asText(), a);
        }
        assertEquals(9, byId.size(), asserts.toString());

        // The verdicts are the evaluator's and do not move; only the number written beside them.
        assertEquals("FAIL", byId.get("breach").get("status").asText());
        assertEquals(3.00004, byId.get("breach").get("actual").asDouble(),
                "a FAIL over gt 3 is never written on the band it breached: " + byId.get("breach"));
        assertEquals("PASS", byId.get("short").get("status").asText());
        assertEquals(2.99996, byId.get("short").get("actual").asDouble(),
                "a PASS under gte 3 is never written on the band it did not reach: " + byId.get("short"));
        assertEquals("PASS", byId.get("warned").get("status").asText());
        assertEquals(1.99996, byId.get("warned").get("actual").asDouble(),
                "the warn band counts too (Q1): " + byId.get("warned"));
        assertEquals("FAIL", byId.get("floor").get("status").asText());
        assertEquals(4.99996, byId.get("floor").get("actual").asDouble(),
                "a downward band: a FAIL under lt 5 is never 5.0: " + byId.get("floor"));
        assertEquals("FAIL", byId.get("drain").get("status").asText());
        assertEquals(-0.00003, byId.get("drain").get("actual").asDouble(),
                "a FAIL under lt 0 keeps its sign: " + byId.get("drain"));

        // Exactly on its band is written on it, and clear of every band it is four places.
        assertEquals(3.0, byId.get("exact").get("actual").asDouble());
        assertEquals("PASS", byId.get("exact").get("status").asText());
        assertEquals(700.1235, byId.get("clear").get("actual").asDouble(), "four places is the base");
        // No never-zero guard on the wire (Q12, as the client's actualOf)...
        assertEquals(0.0, byId.get("tiny").get("actual").asDouble(),
                "no declared band separates 0.00001 from 0, and the JSON adds none: " + byId.get("tiny"));
        // ...but a declared band at 0 separates it like any band.
        assertEquals("FAIL", byId.get("tiny-breach").get("status").asText());
        assertEquals(0.00001, byId.get("tiny-breach").get("actual").asDouble(),
                "a FAIL over gt 0 is never written 0.0: " + byId.get("tiny-breach"));

        // JUnit writes the same number for the same assert at its own base, so the two surfaces put
        // the actual on the same side of every band.
        String xml = new JunitXmlWriter().render(data, null, "T", null, server);
        assertTrue(xml.contains("actual:    3.00004\n"), xml);
        assertTrue(xml.contains("actual:    4.99996\n"), xml);
        assertTrue(xml.contains("actual:    -0.00003\n"), xml);
        assertTrue(xml.contains("actual:    0.00001\n"), xml);

        // The page's builder is untouched: Java's own double, its digits the page's to choose (F7).
        for (Map<String, Object> e : CiCdJsonSerializer.serverKpiEntries(server, true)) {
            if ("breach".equals(e.get("id"))) {
                assertEquals(3.00004, (Double) e.get("actual"));
            }
        }
    }

    /**
     * N4, client side: {@code attainment.consumed} went through the same {@code round4}. A fail
     * band of {@code gt 1E-13} over a 100 ms mean consumed 1E15 of its budget and was written as
     * 9.223372036854776E14. Contrived, and the same helper, so it was fixed by the same guard.
     *
     * <p>Since L14 the field is {@code Digits.beside(consumed, 4, 1.0)}, so it is Digits' edge that
     * holds both numbers: past 2^53 / 10^4 a value is written as measured. 1E15 is still 1E15, and the
     * last double below 2^49 - which round4's old expression moved one ulp, to 5.629499534213119E14 -
     * is written as measured too, as the actual beside it is. Below the edge and clear of the line,
     * four places as ever.
     */
    @Test
    void aConsumedRatioPastLongsRangeIsWrittenAsMeasured() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        SamplerStatistics ulp = new SamplerStatistics();
        ulp.setSamplerName("GET /ulp");
        ulp.setSampleCount(100);
        ulp.setMeanResponseTime(Math.nextDown(0x1p49));
        data.getSamplerStatistics().add(ulp);
        String doc = "{\"assertions\":["
                + "{\"id\":\"tiny-budget\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\",\"fail\":{\"gt\":1.0E-13}},"
                + "{\"id\":\"normal\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\",\"fail\":{\"gt\":300}},"
                + "{\"id\":\"ulp-budget\",\"target\":{\"sampler\":\"GET /ulp\"},\"metric\":\"mean\",\"fail\":{\"gt\":1}}"
                + "]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode assertions = objectMapper.readTree(serializer.serialize(data, null, null, null, client.gate(),
                new ArrayList<>(client.findings()), null)).get("assertions");

        JsonNode tiny = assertions.get(0).get("attainment");
        assertEquals(1.0E15, tiny.get("consumed").asDouble(), "written as measured: " + tiny);
        assertEquals(0.3333, assertions.get(1).get("attainment").get("consumed").asDouble(),
                "below the edge it still rounds to 4dp");
        JsonNode ulpBudget = assertions.get(2).get("attainment");
        assertEquals(Math.nextDown(0x1p49), ulpBudget.get("actual").asDouble(),
                "the actual is Digits', as measured past 2^53 / 100: " + ulpBudget);
        assertEquals(Math.nextDown(0x1p49), ulpBudget.get("consumed").asDouble(),
                "Digits' edge: written as measured, not round4's one-ulp-off 5.629499534213119E14: " + ulpBudget);
    }

    /**
     * N4, round2's half of the same guard. Its edge is 9.223372036854776E16, which no client
     * mean, percentile, rate or count reaches on a real run - but the guard is shared, and an
     * unpinned half is one a revert of round2 alone would take back in silence.
     *
     * <p>Since item 3 a client actual reaches {@code results} and {@code attainment} through
     * {@code Digits.beside}, not round2, so those two lines pin Digits' own edge - past 2^53 / 100 a
     * value is written as it is - and stay green with round2's guard reverted. {@code samplers[]}
     * still goes through round2, so its {@code mean} is what pins round2's half now: reverted, it
     * reads 9.223372036854776E16.
     */
    @Test
    void aClientActualPastRound2sEdgeIsWrittenAsMeasured() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        data.getSamplerStatistics().get(0).setMeanResponseTime(1.0E17);
        String doc = "{\"assertions\":[{\"id\":\"huge\",\"target\":{\"sampler\":\"GET /a\"},"
                + "\"metric\":\"mean\",\"fail\":{\"gt\":1}}]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode document = objectMapper.readTree(serializer.serialize(data, null, null, null, client.gate(),
                new ArrayList<>(client.findings()), null));
        JsonNode rule = document.get("assertions").get(0);

        assertEquals("FAIL", rule.get("status").asText(), rule.toString());
        assertEquals(1.0E17, rule.get("results").get(0).get("actual").asDouble(), "written as measured: " + rule);
        assertEquals(1.0E17, rule.get("attainment").get("actual").asDouble(), "written as measured: " + rule);
        assertEquals(1.0E17, document.get("samplers").get(0).get("mean").asDouble(),
                "round2's guard: written as measured, not clamped to Long.MAX_VALUE / 100: " + document.get("samplers"));
    }

    /**
     * L14: {@code attainment.consumed} is a number beside a line, the whole budget at 1.0, and is
     * written as an actual is beside its band ({@code Digits.beside(consumed, 4, 1.0)}). round4 wrote
     * a FAIL at 373.8625 over {@code gt 373.86} - 1.0000067 of its budget - as {@code consumed: 1.0}
     * beside {@code "breached": true}, and a PASS at 211.405 under {@code gte 211.41} - 0.99998 of it -
     * as 1.0 beside {@code "breached": false}: two opposite verdicts, one number, and that number says
     * "exactly on the line" about both. A value clear of the line keeps round4's four places, and one
     * exactly on it (a {@code gte} met to the digit) is 1.0, because it is.
     */
    @Test
    void aConsumedBudgetIsNeverWrittenOnTheLineItIsNotOn() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        data.getSamplerStatistics().get(0).setMeanResponseTime(373.8625);
        data.getSamplerStatistics().get(0).setThroughput(5);
        for (Object[] s : new Object[][] {{"GET /b", 211.405}, {"GET /c", 234.9117}, {"GET /d", 200.0}}) {
            SamplerStatistics stat = new SamplerStatistics();
            stat.setSamplerName((String) s[0]);
            stat.setSampleCount(100);
            stat.setMeanResponseTime((Double) s[1]);
            data.getSamplerStatistics().add(stat);
        }
        String doc = "{\"assertions\":["
                + "{\"id\":\"over\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\",\"fail\":{\"gt\":373.86}},"
                + "{\"id\":\"under-gte\",\"target\":{\"sampler\":\"GET /b\"},\"metric\":\"mean\",\"fail\":{\"gte\":211.41}},"
                + "{\"id\":\"clear\",\"target\":{\"sampler\":\"GET /c\"},\"metric\":\"mean\",\"fail\":{\"gt\":235}},"
                + "{\"id\":\"on\",\"target\":{\"sampler\":\"GET /d\"},\"metric\":\"mean\",\"fail\":{\"gte\":200}},"
                + "{\"id\":\"floor\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":5.00001}}"
                + "]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode assertions = objectMapper.readTree(serializer.serialize(data, null, null, null, client.gate(),
                new ArrayList<>(client.findings()), null)).get("assertions");
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode a : assertions) {
            byId.put(a.get("id").asText(), a.get("attainment"));
        }

        assertEquals(1.00001, byId.get("over").get("consumed").asDouble(), "a breach past the line: " + byId.get("over"));
        assertTrue(byId.get("over").get("breached").asBoolean());
        assertEquals(0.99998, byId.get("under-gte").get("consumed").asDouble(), "a PASS short of it: " + byId.get("under-gte"));
        assertFalse(byId.get("under-gte").get("breached").asBoolean());
        assertEquals(0.9996, byId.get("clear").get("consumed").asDouble(), "clear of the line, four places: " + byId.get("clear"));
        assertEquals(1.0, byId.get("on").get("consumed").asDouble(), "exactly on a gte band it breached: " + byId.get("on"));
        assertTrue(byId.get("on").get("breached").asBoolean());
        assertEquals(1.000002, byId.get("floor").get("consumed").asDouble(), "a downward band, inverted: " + byId.get("floor"));
        assertTrue(byId.get("floor").get("breached").asBoolean());
    }

    /**
     * CORE-1, the JSON's half: an {@code assertViolations[]} entry for the whole run says so in a
     * field of its own, {@code "scope": "overall"}, beside the {@code "sampler": "Whole run"} it has
     * always carried. "Whole run" is display text, and a sampler can be called that; a pipeline that
     * wants the run-level violation reads {@code scope}, not a name. A sampler's entry carries no
     * {@code scope}: its shape is as it was.
     */
    @Test
    void aWholeRunViolationSaysItsScopeBesideItsName() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        String doc = "{\"assertions\":["
                + "{\"id\":\"load\",\"target\":{\"scope\":\"overall\"},\"metric\":\"throughput\",\"fail\":{\"lt\":100000}},"
                + "{\"id\":\"lat\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"mean\",\"fail\":{\"gt\":1}}"
                + "]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode violations = objectMapper.readTree(serializer.serialize(data, client.verdicts(), client.gate().status(),
                null, client.gate(), new ArrayList<>(client.findings()), null)).get("assertViolations");

        assertEquals(2, violations.size(), violations.toString());
        JsonNode run = violations.get(0);
        assertEquals("Whole run", run.get("sampler").asText(), run.toString());
        assertEquals("overall", run.path("scope").asText(), run.toString());
        assertEquals("FAIL", run.get("status").asText());
        assertEquals(java.util.Arrays.asList("sampler", "scope", "status"), fieldNames(run), "the key order: " + run);

        JsonNode sampler = violations.get(1);
        assertEquals("GET /a", sampler.get("sampler").asText());
        assertTrue(sampler.path("scope").isMissingNode(), "a sampler's entry has no scope: " + sampler);
        assertEquals(java.util.Arrays.asList("sampler", "status"), fieldNames(sampler));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * N3's by-product. The per-match {@code threshold} was {@code round2}'d, so a breached
     * {@code lt 0.001} - this repo's own run-throughput floor - published {@code threshold: 0.0}:
     * a limit of zero, which the breach could never have been measured against. The rule's
     * {@code attainment.failThreshold} beside it was fixed for exactly this; the match echoes the
     * same declared constant now, unrounded.
     *
     * <p>{@code lt 0.001} survives a 4dp rounding too, so {@code lt 0.00001} is what tells
     * "unrounded" from "rounded more finely". And a bare {@code 1e400} - Jackson reads it as
     * Infinity - used to be taken as a band: {@code lt Infinity} breached on every run, and
     * {@code attainment.failThreshold} beside it was the quoted "Infinity" in a numeric field. The
     * client mapper now refuses it as the server's does, so the rule is NOT_JUDGED / INVALID_RULE
     * with no match and no attainment, and the whole document is swept for a non-finite number
     * written as text - with two loadValidity documents that compute {@code lt NaN}, so both
     * producers of a client band are swept at the contract's edge.
     */
    @Test
    void aBreachedMatchEchoesItsDeclaredThresholdUnrounded() throws Exception {
        com.jmeterwebinsightreport.core.model.ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        data.getSamplerStatistics().get(0).setThroughput(0.000004);
        String doc = "{\"assertions\":["
                + "{\"id\":\"run-floor\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":0.001}},"
                + "{\"id\":\"fine-floor\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":0.00001}},"
                + "{\"id\":\"overflowed\",\"target\":{\"sampler\":\"GET /a\"},\"metric\":\"throughput\",\"fail\":{\"lt\":1e400}}"
                + "]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode document = objectMapper.readTree(serializer.serialize(data, null, null, null, client.gate(),
                new ArrayList<>(client.findings()), null));
        JsonNode assertions = document.get("assertions");

        JsonNode rule = assertions.get(0);
        assertEquals("FAIL", rule.get("status").asText(), rule.toString());
        JsonNode match = rule.get("results").get(0);
        assertEquals("lt", match.get("predicate").asText());
        assertEquals(0.001, match.get("threshold").asDouble(), "the declared constant, not 0.0: " + match);
        assertEquals(rule.get("attainment").get("failThreshold").asDouble(), match.get("threshold").asDouble(),
                "two echoes of one constant in one document agree");

        JsonNode fine = assertions.get(1);
        assertEquals("FAIL", fine.get("status").asText(), fine.toString());
        assertEquals(1.0E-5, fine.get("results").get(0).get("threshold").asDouble(),
                "unrounded, not 4dp (which would print 0.0): " + fine);

        JsonNode overflowed = assertions.get(2);
        assertEquals("NOT_JUDGED", overflowed.get("status").asText(), overflowed.toString());
        assertEquals("INVALID_RULE", overflowed.get("notJudged").asText(), overflowed.toString());
        assertEquals("fail lt must be a number, not the number Infinity — so this rule declares a limit"
                + " it cannot apply, and would report PASS without ever testing fail",
                overflowed.get("reason").asText());
        assertEquals(0, overflowed.get("matched").asInt(), overflowed.toString());
        assertEquals(0, overflowed.get("results").size(), "a refused rule judged no sampler: " + overflowed);
        assertFalse(overflowed.has("attainment"), "nothing was measured against it: " + overflowed);
        assertEquals(1, document.get("gate").get("notJudged").get("invalidRule").asInt(), document.get("gate").toString());

        List<JsonNode> errors = new ArrayList<>();
        document.get("findings").forEach(f -> {
            if ("error".equals(f.get("severity").asText())) errors.add(f);
        });
        assertEquals(1, errors.size(), "one mistake, one ERROR: " + document.get("findings"));
        assertEquals("overflowed", errors.get(0).get("ruleId").asText());
        assertEquals("fail lt must be a number, not the number Infinity", errors.get(0).get("message").asText());

        List<String> textual = new ArrayList<>();
        collectNonFiniteText(document, "", textual);
        assertTrue(textual.isEmpty(), "a non-finite number written as text: " + textual);

        // The other producer of a client band: the legacy loadValidity block, whose migration
        // computes lt target x (1 - tolerance). A "NaN" tolerance, and an overflowed target
        // meeting a tolerance of 1 (Infinity x 0), compute lt NaN.
        for (String legacy : new String[] {
                "{\"loadValidity\":{\"target\":1000,\"fail\":{\"tolerance\":\"NaN\"}}}",
                "{\"loadValidity\":{\"target\":1e400,\"fail\":{\"tolerance\":1}}}"}) {
            com.jmeterwebinsightreport.report.gate.GateEvaluation migrated = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                    data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(legacy));
            JsonNode written = objectMapper.readTree(serializer.serialize(data, null, null, null, migrated.gate(),
                    new ArrayList<>(migrated.findings()), null));
            JsonNode load = written.get("assertions").get(0);
            assertEquals("load:throughput", load.get("id").asText(), legacy);
            assertEquals("INVALID_RULE", load.path("notJudged").asText(), legacy + ": " + load);
            List<String> found = new ArrayList<>();
            collectNonFiniteText(written, "", found);
            assertTrue(found.isEmpty(), legacy + ": a non-finite number written as text: " + found);
        }
    }

    /**
     * Item 3: a client actual is two decimals plus the digits that keep it on its own side of every
     * band its rule declares, warn and fail (Q1) - {@code Digits.beside}, the number JUnit prints and
     * the rule the page writes by. round2 alone wrote a FAIL at 119.36094674556213 under
     * {@code gt 119.36} as 119.36, on the band it breached and beside {@code "threshold": 119.36}; a
     * PASS at 214.7455621301775 under {@code gte 214.75} as 214.75 beside {@code "breached": false};
     * a throughput FAIL under {@code lt 8.3} as 8.3; and a FAIL over {@code gt 0} at 0.005 % as 0.0,
     * a breach of nothing.
     *
     * <p>Every declared band, not the breached one: a PASS has none, and its fail band is printed
     * beside it all the same. And {@code attainment.actual} is the per-match actual by construction,
     * one function of one value and one set of bands, so a WARN writes one number twice rather than
     * 234.912 in its match and 234.91 in its attainment.
     *
     * <p>Two decisions are pinned green beside the red: a value clear of every band is two decimals,
     * as it always was; and this document has no never-zero guard (Q12) - a 7.7e-6/s throughput
     * under {@code lt 0.001} is written 0.0, because no band separates it from 0. The page and JUnit
     * keep its digits; a one-argument change here if that is ever wanted.
     */
    @Test
    void aClientActualIsNeverWrittenOnOrAcrossItsBand() throws Exception {
        ReportData data = createReportData("T", 60000, 700, 0);
        data.setSamplerStatistics(Arrays.asList(
                createSamplerStats("GET /on-band", 100, 119.36094674556213, 0, 0, 0, 5),
                createSamplerStats("GET /gte", 100, 214.7455621301775, 0, 0, 0, 5),
                createSamplerStats("GET /warns", 100, 234.91166666666666, 0, 0, 0, 5),
                createSamplerStats("GET /slow", 100, 100, 0, 0, 0, 8.29892736363825),
                createSamplerStats("GET /errors", 100, 100, 0, 0, 0.004999750012499375, 5),
                createSamplerStats("GET /clear", 100, 610.5, 0, 0, 0, 5),
                createSamplerStats("GET /trickle", 100, 100, 0, 0, 0, 0.0000077)));
        String doc = "{\"assertions\":["
                + "{\"id\":\"on-band\",\"target\":{\"sampler\":\"GET /on-band\"},\"metric\":\"mean\",\"fail\":{\"gt\":119.36}},"
                + "{\"id\":\"gte-pass\",\"target\":{\"sampler\":\"GET /gte\"},\"metric\":\"mean\",\"fail\":{\"gte\":214.75}},"
                + "{\"id\":\"warns\",\"target\":{\"sampler\":\"GET /warns\"},\"metric\":\"mean\","
                + "\"warn\":{\"gt\":234.91},\"fail\":{\"gt\":100000}},"
                + "{\"id\":\"floor\",\"target\":{\"sampler\":\"GET /slow\"},\"metric\":\"throughput\",\"fail\":{\"lt\":8.3}},"
                + "{\"id\":\"no-errors\",\"target\":{\"sampler\":\"GET /errors\"},\"metric\":\"errorRate\",\"fail\":{\"gt\":0}},"
                + "{\"id\":\"clear\",\"target\":{\"sampler\":\"GET /clear\"},\"metric\":\"mean\",\"fail\":{\"gt\":1000}},"
                + "{\"id\":\"trickle\",\"target\":{\"sampler\":\"GET /trickle\"},\"metric\":\"throughput\",\"fail\":{\"lt\":0.001}}"
                + "]}";
        com.jmeterwebinsightreport.report.gate.GateEvaluation client = com.jmeterwebinsightreport.report.gate.GateEvaluation.of(
                data, com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc));
        JsonNode document = objectMapper.readTree(serializer.serialize(data, null, null, null, client.gate(),
                new ArrayList<>(client.findings()), null));
        Map<String, JsonNode> rules = new LinkedHashMap<>();
        document.get("assertions").forEach(r -> rules.put(r.get("id").asText(), r));

        JsonNode onBand = rules.get("on-band");
        assertEquals("FAIL", onBand.get("status").asText(), onBand.toString());
        assertEquals(119.361, onBand.get("results").get(0).get("actual").asDouble(),
                "a FAIL is never written on the band it breached: " + onBand);
        assertEquals(119.36, onBand.get("results").get(0).get("threshold").asDouble(), "the band, as declared");
        assertEquals(119.361, onBand.get("attainment").get("actual").asDouble(), onBand.toString());

        JsonNode gte = rules.get("gte-pass");
        assertEquals("PASS", gte.get("status").asText(), gte.toString());
        assertEquals(214.746, gte.get("results").get(0).get("actual").asDouble(),
                "a PASS is never written on the fail band it did not reach: " + gte);
        assertEquals(214.746, gte.get("attainment").get("actual").asDouble(), gte.toString());
        assertEquals(214.75, gte.get("attainment").get("failThreshold").asDouble(), gte.toString());
        assertFalse(gte.get("attainment").get("breached").asBoolean(), gte.toString());

        JsonNode warns = rules.get("warns");
        assertEquals("WARN", warns.get("status").asText(), warns.toString());
        assertEquals(234.912, warns.get("results").get(0).get("actual").asDouble(),
                "every declared band counts, the warn band too: " + warns);
        assertEquals(warns.get("results").get(0).get("actual").asDouble(),
                warns.get("attainment").get("actual").asDouble(),
                "one measurement, one number, in the match and in its attainment: " + warns);

        JsonNode floor = rules.get("floor");
        assertEquals("FAIL", floor.get("status").asText(), floor.toString());
        assertEquals(8.299, floor.get("results").get(0).get("actual").asDouble(),
                "a FAIL under lt is never written on its band either: " + floor);
        assertEquals(8.299, floor.get("attainment").get("actual").asDouble(), floor.toString());

        JsonNode noErrors = rules.get("no-errors");
        assertEquals("FAIL", noErrors.get("status").asText(), noErrors.toString());
        assertEquals(0.005, noErrors.get("results").get(0).get("actual").asDouble(),
                "a breach of gt 0 is never written 0.0: " + noErrors);

        JsonNode clear = rules.get("clear");
        assertEquals("PASS", clear.get("status").asText(), clear.toString());
        assertEquals(610.5, clear.get("results").get(0).get("actual").asDouble(), "clear of every band: two decimals");
        assertEquals(610.5, clear.get("attainment").get("actual").asDouble(), clear.toString());

        JsonNode trickle = rules.get("trickle");
        assertEquals("FAIL", trickle.get("status").asText(), trickle.toString());
        assertEquals(0.0, trickle.get("results").get(0).get("actual").asDouble(),
                "no never-zero guard in this document (Q12): no band separates 7.7e-6 from 0: " + trickle);
        assertEquals(0.0, trickle.get("attainment").get("actual").asDouble(), trickle.toString());
    }

    /**
     * Every string VALUE equal to Infinity, -Infinity or NaN, with its path — a sentence naming one
     * is fine. Package-visible for GatePayloadTest, which sweeps the page's payload with it.
     */
    static void collectNonFiniteText(JsonNode node, String path, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            String s = node.asText();
            if (s.equals("Infinity") || s.equals("-Infinity") || s.equals("NaN")) {
                out.add(path + " = \"" + s + "\"");
            }
        } else if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                collectNonFiniteText(node.get(name), path + "/" + name, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectNonFiniteText(node.get(i), path + "[" + i + "]", out);
            }
        }
    }
}
