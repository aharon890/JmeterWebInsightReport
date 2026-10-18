package com.jmeterwebinsightreport.report.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jmeterwebinsightreport.core.assertion.AssertionStatus;
import com.jmeterwebinsightreport.core.assertion.AssertionValidator;
import com.jmeterwebinsightreport.core.assertion.GateSummary;
import com.jmeterwebinsightreport.core.assertion.NotJudged;
import com.jmeterwebinsightreport.core.assertion.Predicate;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.servermetrics.KpiResult;
import com.jmeterwebinsightreport.core.servermetrics.KpiUsability;
import com.jmeterwebinsightreport.core.servermetrics.Reducer;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.report.annotation.AnnotationLoader;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;
import com.jmeterwebinsightreport.report.annotation.ServerMetricsMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@code server-parity-vectors.json} through the code that ships: every case's KPI JSON is
 * put in a real annotations document and read by {@code ServerMetricsMapper}, the client load
 * rules by {@code AssertionRuleMapper}, and both are judged by the same two evaluations the
 * generator builds — {@link GateEvaluation} and {@link ServerGateEvaluation}.
 *
 * <p>The same file is run against the report's mirror in node ({@code server-parity.test.js},
 * through the production {@code kpisFromDocument}) and in a real report
 * ({@code server-parity.spec.ts}). Neither side owns the definition of a reducer, a tolerance, the
 * window, {@code requiresLoad} or {@code divideBy}: the vector file does.
 *
 * <p>This used to live in the core module and build each {@code ServerKpi} with a hand-written
 * helper, because the mapper lives here and core cannot depend on it (R2-5). So the vectors pinned
 * the evaluator and the helper, never the parser: {@code over: {}} threw a NullPointerException in
 * the helper, {@code fail: {gt: "50ms"}} read as {@code gt 0}, and a retired KPI-level key was
 * ignored and judged — three answers to shapes the real mapper refuses, and a vector added for any
 * of them would have "agreed" with the mirror while both disagreed with what ships. That is the
 * trap {@code MapperParityTest} records for the client side, where two evaluators were pinned while
 * the two parsers were compared by nothing. The cheapest way to green would have been to grow the
 * helper into a second mapper; there is no helper now.
 *
 * <p>The vector file stays in core's test resources, where the node and browser runners read it,
 * and is read here by path from the reactor root — {@link #theVectorFileIsWhereThisTestThinksItIs}
 * fails loudly if that ever stops being true.
 */
class ServerParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* The mapper the run reads an annotations file with: a number in a text field is read by its
       value there, as the page reads it, and the vectors pin that (M7d X review follow-up). */
    private static final ObjectMapper ANNOTATIONS = AnnotationLoader.newObjectMapper();

    private static Path vectorFile() {
        // Surefire runs with the module directory as the working directory.
        return Paths.get("..", "jmeter-report-core", "src", "test", "resources",
                "servermetrics", "server-parity-vectors.json").normalize();
    }

    private static JsonNode vectors() throws IOException {
        try (InputStream in = Files.newInputStream(vectorFile())) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void theVectorFileIsWhereThisTestThinksItIs() {
        assertTrue(Files.isReadable(vectorFile()),
                "the server parity vectors moved and this suite would have silently covered nothing: "
                        + vectorFile().toAbsolutePath());
    }

    // ---------------------------------------------------------------- the run

    /** The run the client load rules are judged against. Data, not parsing: there is no mapper for it. */
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
            stat.setMeanLatency(s.get("meanLatency").asDouble());
            stat.setMeanConnectTime(s.get("meanConnectTime").asDouble());
            stat.setReceivedBytesPerSec(s.get("receivedBytesPerSec").asDouble());
            stat.setSentBytesPerSec(s.get("sentBytesPerSec").asDouble());
            stats.add(stat);
        }
        ReportData data = new ReportData();
        data.setSamplerStatistics(stats);
        TestMetadata meta = new TestMetadata();
        meta.setTestName("Server parity");
        meta.setDurationMillis(root.get("metadata").get("durationMillis").asLong());
        meta.setTotalThreads(root.get("metadata").get("totalThreads").asInt());
        data.setMetadata(meta);
        return data;
    }

    // ---------------------------------------------------------------- the document

    /**
     * The annotations document a case is judged under, exactly as a user would write it: the
     * client rules as {@code assertions}, and a {@code serverMetrics} block declaring the file's
     * {@code components} (with the case's own merged over them by id), the run-level settings (the
     * file's, then the case's overrides) and the asserts given, under {@code serverMetrics.asserts}
     * — the key D25 gave them. There are no sources in a file (C8): the payload's one source is
     * the collection's, and every assert names it through the mapper.
     */
    static ReportAnnotations document(JsonNode root, JsonNode caseConfig, JsonNode caseComponents,
                                      List<JsonNode> kpis) throws IOException {
        return ANNOTATIONS.treeToValue(documentNode(root, caseConfig, caseComponents, kpis), ReportAnnotations.class);
    }

    static ObjectNode documentNode(JsonNode root, JsonNode caseConfig, JsonNode caseComponents, List<JsonNode> kpis) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.set("assertions", root.get("clientRules"));

        ObjectNode block = doc.putObject("serverMetrics");
        // The components as a user writes them - the same object the node and browser runners
        // declare, so a component one parser refuses and the other reads is a failing case.
        ObjectNode components = block.putObject("components");
        Iterator<Map.Entry<String, JsonNode>> declared = root.get("components").fields();
        while (declared.hasNext()) {
            Map.Entry<String, JsonNode> c = declared.next();
            components.set(c.getKey(), c.getValue().deepCopy());
        }
        if (caseComponents != null) {
            Iterator<Map.Entry<String, JsonNode>> own = caseComponents.fields();
            while (own.hasNext()) {
                Map.Entry<String, JsonNode> c = own.next();
                components.set(c.getKey(), c.getValue().deepCopy());
            }
        }

        JsonNode file = root.get("block");
        ObjectNode collection = block.putObject("collection");
        collection.set("coverageFloor", file.get("coverageFloor"));
        collection.set("excludeRampUp", file.get("excludeRampUp"));
        if (caseConfig != null) {
            // The coverage floor is a COLLECT-time decision, so a case cannot change what the
            // payload says a series' status is - only what the file asks for NOW. That gap is the
            // second form of the INSUFFICIENT sentence, which has no other way to be reached.
            if (caseConfig.hasNonNull("coverageFloor")) collection.set("coverageFloor", caseConfig.get("coverageFloor"));
            if (caseConfig.hasNonNull("excludeRampUp")) collection.set("excludeRampUp", caseConfig.get("excludeRampUp"));
            if (caseConfig.hasNonNull("requiresLoad")) block.set("requiresLoad", caseConfig.get("requiresLoad"));
        }
        ArrayNode list = block.putArray("asserts");
        for (JsonNode k : kpis) {
            list.add(k);
        }
        return doc;
    }

    static ServerMetricsPayload payload(JsonNode root) throws IOException {
        return MAPPER.treeToValue(root.get("payload"), ServerMetricsPayload.class);
    }

    /** The server evaluation the generator builds, over a document carrying the KPIs given. */
    static ServerGateEvaluation evaluate(JsonNode root, JsonNode caseConfig, JsonNode caseComponents,
                                         List<JsonNode> kpis, ReportData data,
                                         ServerMetricsPayload payload) throws IOException {
        ReportAnnotations annotations = document(root, caseConfig, caseComponents, kpis);
        GateEvaluation client = GateEvaluation.of(data, annotations);
        return ServerGateEvaluation.of(annotations, payload, client.gate());
    }

    // ---------------------------------------------------------------- the vectors

    @TestFactory
    Stream<DynamicTest> everyVectorProducesTheDeclaredResult() throws IOException {
        JsonNode root = vectors();
        ServerMetricsPayload payload = payload(root);
        ReportData data = reportData(root);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            final JsonNode testCase = c;
            tests.add(DynamicTest.dynamicTest(testCase.get("name").asText(), () -> {
                List<JsonNode> one = new ArrayList<>();
                one.add(testCase.get("kpi"));
                ServerGateEvaluation evaluation = evaluate(root, testCase.get("config"), testCase.get("components"),
                        one, data, payload);
                assertEquals(1, evaluation.results().size(), "the mapper dropped the KPI: " + evaluation.findings());
                checkResult(evaluation.results().get(0), testCase.get("expect"), evaluation);
            }));
        }
        assertTrue(tests.size() >= 55, "expected the full vector set, got " + tests.size());
        return tests.stream();
    }

    /** One judged KPI against what the vector expects of it. Shared by the single and the document cases. */
    private static void checkResult(KpiResult result, JsonNode expect, ServerGateEvaluation evaluation) {
        String id = result.kpi().id();
        // The id a KPI is judged under is part of the contract: it names the JUnit testcase, the
        // CI/CD entry and every finding, and the browser keys its board on it. Java derives one
        // when the file names none (ServerKpi.deriveId); the mirror used to make that id "".
        if (expect.hasNonNull("id")) {
            assertEquals(expect.get("id").asText(), id, "the id this KPI is judged under");
        }

        assertEquals(expect.get("status").asText(), result.status().name(), "status for " + id
                + (result.notJudgedDetail() != null ? " (" + result.notJudgedDetail() + ")" : ""));
        if (expect.hasNonNull("notJudged")) {
            assertNotNull(result.notJudged(), "no reason given for " + id);
            assertEquals(expect.get("notJudged").asText(), result.notJudged().name(), "reason for " + id);
        } else {
            assertNull(result.notJudged(), "a judged KPI must carry no reason: " + id);
        }
        if (expect.hasNonNull("actual")) {
            assertEquals(expect.get("actual").asDouble(), result.actual(), 1e-6, "actual for " + id);
        } else if (expect.hasNonNull("notJudged")) {
            assertTrue(Double.isNaN(result.actual()), "a not-judged KPI has no number: " + id);
        }
        if (expect.hasNonNull("reason")) {
            assertNotNull(result.notJudgedDetail(), "no detail given for " + id);
            assertTrue(result.notJudgedDetail().contains(expect.get("reason").asText()),
                    "detail for " + id + " was: " + result.notJudgedDetail());
        }
        // The whole sentence, where every character is the contract - a number in it above all.
        if (expect.hasNonNull("detail")) {
            assertEquals(expect.get("detail").asText(), result.notJudgedDetail(), "detail for " + id);
        }
        if (expect.hasNonNull("breached")) {
            String op = expect.get("breached").fieldNames().next();
            assertNotNull(result.breachedBand(), "no band fired for " + id);
            assertEquals(op, result.breachedBand().op().key(), "band for " + id);
            assertEquals(expect.get("breached").get(op).asDouble(), result.breachedBand().value(), 1e-9);
        } else if (result.status() == AssertionStatus.PASS) {
            assertNull(result.breachedBand(), "a PASS names no band: " + id);
        }
        if (expect.hasNonNull("instances")) {
            assertEquals(expect.get("instances").asInt(), result.instances(), "instances for " + id);
        }
        if (expect.hasNonNull("coverage")) {
            assertEquals(expect.get("coverage").asDouble(), result.coverage(), 1e-9, "coverage for " + id);
        }
        // A WARNING decides nothing, so the verdict cannot catch one drifting: the sentence is the
        // contract, and the browser shows the same one in its editor and after a drag (C-11).
        if (expect.has("warnings")) {
            List<String> expected = new ArrayList<>();
            expect.get("warnings").forEach(w -> expected.add(w.asText()));
            List<String> raised = new ArrayList<>();
            for (AssertionValidator.Finding f : evaluation.findings()) {
                if (f.severity() == AssertionValidator.Severity.WARNING && id.equals(f.ruleId())) {
                    raised.add(f.message());
                }
            }
            assertEquals(expected, raised, "WARNING findings for " + id);
        }
        // INVALID_RULE is a defect in the FILE, and the file's defects are the findings list
        // - what the log, the CI/CD JSON and the findings pane print. A refusal that reached
        // the verdict and not the findings would be one only a reader of the gate could see.
        // Under either of the assert's names (SF21): the mapper files a problem under the label the
        // file gives it, serverMetrics.asserts[i] for an id-less one, and the same refusal is said
        // once, not again under the derived id.
        if (result.notJudged() == NotJudged.INVALID_RULE) {
            boolean named = false;
            String label = result.kpi().declarationLabel();
            for (AssertionValidator.Finding f : evaluation.findings()) {
                named |= f.severity() == AssertionValidator.Severity.ERROR
                        && (id.equals(f.ruleId()) || label.equals(f.ruleId()));
            }
            assertTrue(named, "INVALID_RULE on " + id + " reached no ERROR finding: " + evaluation.findings());
        }
    }

    /**
     * Several KPIs in one block, for what a single case cannot show: two KPIs that share an id —
     * written twice, or derived twice from one query. Java accepts both shapes and judges each
     * KPI on its own, in declaration order; the browser used to key its verdicts by id, so the
     * second overwrote the first and both rows showed one verdict.
     */
    @TestFactory
    Stream<DynamicTest> everyDocumentVectorJudgesEachKpiOnItsOwn() throws IOException {
        JsonNode root = vectors();
        ServerMetricsPayload payload = payload(root);
        ReportData data = reportData(root);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode d : root.path("documentCases")) {
            final JsonNode docCase = d;
            tests.add(DynamicTest.dynamicTest("document — " + docCase.get("name").asText(), () -> {
                List<JsonNode> kpis = new ArrayList<>();
                for (JsonNode k : docCase.get("asserts")) {
                    kpis.add(k);
                }
                // A case's own block keys (environments, defaultEnv, collection) beside the file's
                // components and the asserts given: what the mapper reads before any assert.
                ObjectNode doc = documentNode(root, null, docCase.get("components"), kpis);
                if (docCase.has("block")) {
                    ObjectNode block = (ObjectNode) doc.get("serverMetrics");
                    Iterator<Map.Entry<String, JsonNode>> own = docCase.get("block").fields();
                    while (own.hasNext()) {
                        Map.Entry<String, JsonNode> f = own.next();
                        block.set(f.getKey(), f.getValue().deepCopy());
                    }
                }
                ReportAnnotations annotations = ANNOTATIONS.treeToValue(doc, ReportAnnotations.class);
                ServerGateEvaluation evaluation = ServerGateEvaluation.of(annotations, payload,
                        GateEvaluation.of(data, annotations).gate());
                JsonNode expect = docCase.get("expect");
                assertEquals(expect.size(), evaluation.results().size(),
                        "one result per declared KPI: " + evaluation.findings());
                for (int i = 0; i < expect.size(); i++) {
                    checkResult(evaluation.results().get(i), expect.get(i), evaluation);
                }
                // What only a SET can raise: a shared id (R2-2/R3-8) is a WARNING - never a refusal,
                // both asserts are judged above - and the page shows the same finding, live.
                if (docCase.has("warnings")) {
                    List<String> expected = new ArrayList<>();
                    docCase.get("warnings").forEach(w ->
                            expected.add(w.get("id").asText() + " | " + w.get("message").asText()));
                    List<String> raised = new ArrayList<>();
                    for (AssertionValidator.Finding f : evaluation.findings()) {
                        if (f.severity() == AssertionValidator.Severity.WARNING) {
                            raised.add(f.ruleId() + " | " + f.message());
                        }
                    }
                    assertEquals(expected, raised, "the document's WARNING findings");
                }
                // EVERY finding, in Java's phases (SF18) and each mistake once (SF21): what the CI/CD
                // JSON's findings[] carries, and what a live findings list on the page must rebuild
                // for the file the run was generated from.
                if (docCase.has("findings")) {
                    List<String> expected = new ArrayList<>();
                    docCase.get("findings").forEach(f -> expected.add(f.get("severity").asText() + " | "
                            + f.get("id").asText() + " | " + f.get("message").asText()));
                    List<String> raised = new ArrayList<>();
                    for (AssertionValidator.Finding f : evaluation.findings()) {
                        raised.add(f.severity().name() + " | " + f.ruleId() + " | " + f.message());
                    }
                    assertEquals(expected, raised, "the document's findings, in order");
                }
            }));
        }
        assertTrue(tests.size() >= 4, "the document cases are gone: " + tests.size());
        return tests.stream();
    }

    /**
     * What a BLOCK says beside its asserts (D25, D26): the config-level problems the mapper records
     * under {@code serverMetrics} — a leftover {@code kpis} key, a {@code packs} key — compared as
     * the exact, ordered list of sentences, and the asserts that still map beside them. The
     * browser's {@code blockProblems} answers the same list, so the page can say why a document it
     * now holds will be refused without waiting for the next run.
     *
     * <p>Every problem here is also fatal: {@link ServerGateEvaluation#fatalConfigErrors()} counts
     * the block's errors whether or not an assert mapped, so a file that still says {@code kpis}
     * and declares nothing else exits 1 rather than reading as a run with no server asserts.
     */
    @TestFactory
    Stream<DynamicTest> everyBlockVectorRecordsTheDeclaredProblems() throws IOException {
        JsonNode root = vectors();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode b : root.path("blockCases")) {
            final JsonNode blockCase = b;
            tests.add(DynamicTest.dynamicTest("block — " + blockCase.get("name").asText(), () -> {
                ObjectNode doc = documentNode(root, null, null, new ArrayList<JsonNode>());
                ObjectNode block = (ObjectNode) doc.get("serverMetrics");
                block.remove("asserts");
                Iterator<Map.Entry<String, JsonNode>> fields = blockCase.get("block").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    block.set(f.getKey(), f.getValue());
                }
                ReportAnnotations annotations = ANNOTATIONS.treeToValue(doc, ReportAnnotations.class);
                ServerMetricsMapper.Result mapped = new ServerMetricsMapper().map(annotations.getServerMetrics());

                List<String> expected = new ArrayList<>();
                blockCase.get("expect").get("problems").forEach(p -> expected.add(p.asText()));
                List<String> expectedComponents = new ArrayList<>();
                blockCase.get("expect").path("componentProblems").forEach(p ->
                        expectedComponents.add(p.get("id").asText() + " | " + p.get("message").asText()));
                List<String> recorded = new ArrayList<>();
                List<String> recordedComponents = new ArrayList<>();
                for (AssertionValidator.Finding f : mapped.config().problems()) {
                    assertEquals(AssertionValidator.Severity.ERROR, f.severity(), "a block problem is an ERROR: " + f);
                    if (f.ruleId().startsWith(COMPONENTS_PREFIX)) {
                        // A component the mapper refused, filed under the component's own label.
                        recordedComponents.add(f.ruleId() + " | " + f.message());
                    } else {
                        assertEquals(ServerMetricsMapper.BLOCK_ID, f.ruleId(), "a block problem is filed under serverMetrics");
                        recorded.add(f.message());
                    }
                }
                assertEquals(expected, recorded, "the block's config-level problems");
                assertEquals(expectedComponents, recordedComponents, "the components' config-level problems");

                List<String> ids = new ArrayList<>();
                blockCase.get("expect").get("ids").forEach(i -> ids.add(i.asText()));
                List<String> mappedIds = new ArrayList<>();
                mapped.config().kpis().forEach(k -> mappedIds.add(k.id()));
                assertEquals(ids, mappedIds, "the asserts that still map beside the block's problems");

                GateEvaluation client = GateEvaluation.of(reportData(root), annotations);
                ServerGateEvaluation evaluation = ServerGateEvaluation.of(annotations, null, client.gate());
                assertEquals(expected.size() + expectedComponents.size(), evaluation.fatalConfigErrors(),
                        "every block problem is fatal under failonerror, asserts or none");
            }));
        }
        assertTrue(tests.size() >= 8, "the block cases are gone: " + tests.size());
        return tests.stream();
    }

    /** The label {@code ServerMetricsMapper} files a component's problem under. */
    private static final String COMPONENTS_PREFIX = ServerMetricsMapper.BLOCK_ID + ".components.";

    @TestFactory
    Stream<DynamicTest> everyGateVectorProducesTheDeclaredSummary() throws IOException {
        JsonNode root = vectors();
        ServerMetricsPayload payload = payload(root);
        ReportData data = reportData(root);

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode c : root.get("cases")) {
            // An id-less case cannot be named by a gate case — and must not NPE the lookup.
            if (c.get("kpi").hasNonNull("id")) {
                byId.put(c.get("kpi").get("id").asText(), c);
            }
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode g : root.get("gateCases")) {
            final JsonNode gateCase = g;
            tests.add(DynamicTest.dynamicTest("gate — " + gateCase.get("name").asText(), () -> {
                List<JsonNode> kpis = new ArrayList<>();
                for (JsonNode id : gateCase.get("kpiIds")) {
                    JsonNode c = byId.get(id.asText());
                    assertNotNull(c, "gate case names an unknown KPI id: " + id.asText());
                    assertNull(c.get("config"), "a gate case cannot combine cases with their own config: " + id.asText());
                    assertNull(c.get("components"), "a gate case cannot combine cases with their own components: " + id.asText());
                    kpis.add(c.get("kpi"));
                }
                ServerGateEvaluation evaluation = evaluate(root, null, null, kpis, data, payload);
                assertEquals(kpis.size(), evaluation.results().size(), "the mapper dropped a KPI");
                GateSummary gate = GateSummary.of(evaluation.results());
                JsonNode expect = gateCase.get("expect");

                if (expect.get("status").isNull()) {
                    assertNull(gate.status(), "a gate of nothing but NOT_JUDGED KPIs has decided nothing, which is not PASS");
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
                    assertTrue(nj.has(r.countKey()), "gate case must expect notJudged." + r.countKey());
                    assertEquals(nj.get(r.countKey()).asInt(), gate.notJudged(r), "notJudged." + r.countKey());
                    reasonSum += gate.notJudged(r);
                }
                assertEquals(gate.declared(), gate.passed() + gate.warned() + gate.failed() + gate.notJudged(),
                        "the per-status counts must sum to declared");
                assertEquals(gate.notJudged(), reasonSum, "the reasons must sum to notJudged");
                assertEquals(expect.get("configErrors").asInt(), gate.configErrors(), "configErrors");
            }));
        }
        return tests.stream();
    }

    @Test
    void everyReasonPhraseIsTheOneTheVectorFilePins() throws IOException {
        // The load-rule sentences are built from these phrases on both sides, so they are part of
        // the parity contract. They had already drifted on DISABLED ("was parked" here, "parked" in
        // report.js) before anything compared them; server-parity.test.js checks the same map
        // against WIR.status.REASON_PHRASE.
        JsonNode phrases = vectors().get("reasonPhrases");
        assertNotNull(phrases, "the vector file pins no reason phrases");
        for (NotJudged r : NotJudged.values()) {
            assertTrue(phrases.has(r.name()), "no pinned phrase for " + r);
            assertEquals(phrases.get(r.name()).asText(), r.phrase(), "phrase for " + r);
        }
    }

    @Test
    void everyReasonCountsUnderTheKeyAndConfigErrorFlagTheVectorFilePins() throws IOException {
        // A1-9: the browser counts a recomputed gate with REASON_KEY and colours a config error
        // with CONFIG_ERROR_REASONS. Both are copies of decisions this enum makes, and phrase()
        // had already drifted before anything compared it. server-parity.test.js checks the same
        // two entries against WIR.status, so a reason added here without the mirror fails one side.
        JsonNode root = vectors();
        JsonNode keys = root.get("reasonCountKeys");
        assertNotNull(keys, "the vector file pins no reason count keys");
        List<String> configErrors = new ArrayList<>();
        for (JsonNode r : root.get("configErrorReasons")) {
            configErrors.add(r.asText());
        }
        for (NotJudged r : NotJudged.values()) {
            assertTrue(keys.has(r.name()), "no pinned count key for " + r);
            assertEquals(keys.get(r.name()).asText(), r.countKey(), "count key for " + r);
            assertEquals(r.isConfigError(), configErrors.contains(r.name()), "config error for " + r);
        }
    }

    @Test
    void theVectorFileCoversEveryStatusEveryReasonAndEveryReducer() throws IOException {
        // Coverage is what rots: the server-only reasons in particular have no other vector
        // file to live in, and the client file deliberately refuses them.
        JsonNode root = vectors();
        List<String> statuses = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        boolean sustained = false, budget = false, divide = false, requires = false, avgAcross = false;
        for (JsonNode c : root.get("cases")) {
            statuses.add(c.get("expect").get("status").asText());
            if (c.get("expect").hasNonNull("notJudged")) {
                reasons.add(c.get("expect").get("notJudged").asText());
            }
            JsonNode kpi = c.get("kpi");
            for (String band : new String[] {"warn", "fail"}) {
                if (kpi.hasNonNull(band) && kpi.get(band).size() > 0) {
                    ops.add(kpi.get(band).fieldNames().next());
                }
            }
            if (kpi.hasNonNull("reduce")) {
                String kind = kpi.get("reduce").path("kind").asText("");
                kinds.add(kind.startsWith("p") ? "p" : kind);
                sustained |= kpi.get("reduce").path("over").has("sustainedFor");
                budget |= kpi.get("reduce").path("over").has("breachBudget");
            }
            divide |= kpi.has("divideBy");
            requires |= kpi.has("requiresLoad");
            avgAcross |= "avg".equals(kpi.path("across").asText());
        }
        for (AssertionStatus s : AssertionStatus.values()) {
            assertTrue(statuses.contains(s.name()), "no server parity vector produces " + s);
        }
        for (NotJudged r : NotJudged.values()) {
            assertTrue(reasons.contains(r.name()), "no server parity vector produces " + r
                    + (r.serverOnly() ? " - and this file is the only one that can" : ""));
        }
        // Operators, for the same reason as the reducers: the browser mirror reimplements every
        // one of them, and an operator with no vector is the one it can get wrong unseen.
        for (Predicate.Op op : Predicate.Op.values()) {
            assertTrue(ops.contains(op.key()), "no server parity vector uses the " + op.key() + " operator");
        }
        for (Reducer.Kind k : Reducer.Kind.values()) {
            assertTrue(kinds.contains(k.key()), "no server parity vector uses the " + k.key() + " reducer");
        }
        assertTrue(sustained && budget, "both tolerances must be exercised");
        assertTrue(divide && requires && avgAcross, "divideBy, requiresLoad and across: avg must be exercised");
    }

    /**
     * F8: the INVISIBLE_PADDING WARNING's class, code point by code point. The vector file's
     * {@code invisibleEnds.ranges} is the definition; {@code KpiUsability} holds its own copy, and
     * this sweeps every code point (surrogates aside: a lone half is not a character) at both
     * ends of a query, through the two public doors the catalogue and the
     * collector use. Nothing at or below U+0020 is listed: {@code String.trim} has taken it off
     * before anything asks, and "not trimmed like a plain space" is no sentence for a plain space.
     * The page runs the same sweep in {@code server-parity.test.js}.
     *
     * <p>It exists because six code points were named by a review and the class they belong to
     * was not: a WARNING that knew {@code \p{Z}} and four zero-width characters said nothing about
     * a word joiner, a soft hyphen, a bidi mark or a tag character, and its membership moved with
     * the JVM's Unicode tables (U+180E).
     */
    @Test
    void everyCodePointIsAnInvisibleEndExactlyWhenTheVectorFileSaysSo() throws IOException {
        List<int[]> ranges = invisibleRanges(vectors());
        // A floor, so an emptied section cannot pass by sweeping against nothing.
        assertTrue(ranges.size() >= 20, "invisibleEnds.ranges shrank to " + ranges.size());
        for (int cp : new int[] {0x00A0, 0x200B, 0xFEFF}) {
            assertTrue(isListed(ranges, cp), String.format("U+%04X left the class Q2 began with", cp));
        }
        List<String> wrong = new ArrayList<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue;
            }
            boolean listed = isListed(ranges, cp);
            String ch = new String(Character.toChars(cp));
            String name = String.format(java.util.Locale.ROOT, "U+%04X", cp);
            String atEnd = KpiUsability.invisiblePaddingText("x" + ch);
            String atStart = KpiUsability.invisiblePaddingText(ch + "x");
            boolean right = KpiUsability.hasInvisibleEnd("x" + ch) == listed
                    && KpiUsability.hasInvisibleEnd(ch + "x") == listed
                    && (listed
                        ? atEnd != null && atEnd.contains("its end (" + name + ")")
                            && atStart != null && atStart.contains("its start (" + name + ")")
                        : atEnd == null && atStart == null);
            if (!right && wrong.size() < 40) {
                wrong.add(name + (listed ? " (listed, not warned)" : " (warned, not listed)"));
            }
        }
        assertEquals(new ArrayList<String>(), wrong,
                "KpiUsability's invisible class is not the vector file's invisibleEnds.ranges (first 40 shown)");
    }

    /**
     * The other half of reading an end as a code point: a surrogate half that is not one of a
     * pair is the unit itself, as {@link String#codePointAt} and {@link String#codePointBefore}
     * read it. The sweep above skips U+D800-U+DFFF, so without these a page that joined a lone
     * high half to whatever followed it would agree with Java on every case and every code point.
     */
    @Test
    void aLoneSurrogateHalfAtAnEndIsReadAsJavaReadsIt() throws IOException {
        JsonNode cases = vectors().path("invisibleEnds").path("loneSurrogates");
        assertTrue(cases.isArray() && cases.size() >= 4, "invisibleEnds.loneSurrogates shrank");
        for (JsonNode c : cases) {
            StringBuilder query = new StringBuilder();
            for (JsonNode unit : c.path("units")) {
                query.append((char) Integer.parseInt(unit.asText(), 16));
            }
            String expected = c.path("warning").isNull() ? null : c.path("warning").asText();
            assertEquals(expected, KpiUsability.invisiblePaddingText(query.toString()), c.path("name").asText());
        }
    }

    /** {@code invisibleEnds.ranges}: "007F-00A0" or "00AD", ascending, disjoint, all above U+0020. */
    static List<int[]> invisibleRanges(JsonNode root) {
        JsonNode section = root.path("invisibleEnds").path("ranges");
        assertTrue(section.isArray(), "the vector file has no invisibleEnds.ranges section");
        List<int[]> ranges = new ArrayList<>();
        for (JsonNode r : section) {
            String[] ends = r.asText().split("-");
            int[] range = {Integer.parseInt(ends[0], 16), Integer.parseInt(ends[ends.length - 1], 16)};
            assertTrue(range[0] > 0x20 && range[0] <= range[1], "a bad range: " + r.asText());
            assertTrue(ranges.isEmpty() || ranges.get(ranges.size() - 1)[1] + 1 < range[0],
                    "the ranges must be ascending, disjoint and maximal: " + r.asText());
            ranges.add(range);
        }
        return ranges;
    }

    private static boolean isListed(List<int[]> ranges, int cp) {
        for (int[] r : ranges) {
            if (cp >= r[0] && cp <= r[1]) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theVectorFileNamesEveryGateKeyAndKeepsThemSummed() throws IOException {
        JsonNode root = vectors();
        for (JsonNode g : root.get("gateCases")) {
            JsonNode nj = g.get("expect").get("notJudged");
            int sum = 0;
            Iterator<String> keys = nj.fieldNames();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!"total".equals(k)) sum += nj.get(k).asInt();
            }
            assertEquals(nj.get("total").asInt(), sum, "the vector's own reasons must sum to its total: " + g.get("name"));
        }
    }
}
