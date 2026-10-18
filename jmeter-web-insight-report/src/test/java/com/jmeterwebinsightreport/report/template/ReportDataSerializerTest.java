package com.jmeterwebinsightreport.report.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.*;
import com.jmeterwebinsightreport.report.gate.GateEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReportDataSerializerTest {

    private ReportDataSerializer serializer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        serializer = new ReportDataSerializer();
        objectMapper = new ObjectMapper();
    }

    // --- Run identity, so a mismatched HTML/payload pair is detectable ---

    @Test
    void thePayloadDeclaresWhichRunItBelongsTo() throws JsonProcessingException {
        // Deriving the payload's filename from the report's makes a mismatched pair unlikely; it
        // does not make one detectable. Two reports in one directory used to share a payload, and
        // the page rendered run 2's charts under run 1's header cards with nothing able to notice
        // — the only guard tested window.__WIR_DATA__ for presence, never identity.
        ReportData data = createMinimalReportData();
        data.getMetadata().setTestName("RUN-ALPHA");

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        assertTrue(json.hasNonNull("runId"), "the payload must carry a run id");
        assertEquals("RUN-ALPHA", json.get("runTestName").asText());
        assertEquals(com.jmeterwebinsightreport.core.util.RunIdentity.of(data),
                json.get("runId").asText(),
                "the payload's id must be the one the report computes for the same data");
    }

    @Test
    void twoDifferentRunsProduceDifferentPayloadIdentities() throws JsonProcessingException {
        ReportData alpha = createMinimalReportData();
        alpha.getMetadata().setTestName("RUN-ALPHA");
        ReportData beta = createMinimalReportData();
        beta.getMetadata().setTestName("RUN-BETA");

        String alphaId = objectMapper.readTree(ChartDataJson.of(alpha))
                .get("runId").asText();
        String betaId = objectMapper.readTree(ChartDataJson.of(beta))
                .get("runId").asText();
        assertNotEquals(alphaId, betaId);
    }

    // --- The server-metrics payload (M3) ---

    private static com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload serverPayload(
            int instances, int pointsPerInstance) {
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload p =
                new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload();
        p.setRunId("r");
        p.setCollectionId("c0ffee");
        p.setEnvironment("TEST");
        p.setCollectedAt("2026-08-22T10:00:00Z");
        p.setWindow(new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.Window(0, 60_000, 5_000L));
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.SourceRecord src =
                new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.SourceRecord();
        src.setId("prom");
        src.setType("prometheus");
        src.setStatus("OK");
        src.setStepMillis(15_000);
        src.setResolvedUrl("https://prom/api?token=***");
        p.getSources().add(src);
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.SeriesRecord s =
                new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.SeriesRecord();
        s.setSource("prom");
        s.setQuery("up{c=\"${env.c}\"}");
        s.setResolvedQuery("up{c=\"eu\"}");
        s.setStatus("OK");
        s.setCoverage(1.0);
        for (int i = 0; i < instances; i++) {
            com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.InstanceRecord inst =
                    new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.InstanceRecord();
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("pod", "p" + i);
            inst.setLabels(labels);
            List<com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.Point> points = new ArrayList<>();
            for (int k = 0; k < pointsPerInstance; k++) {
                points.add(new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.Point(k * 1000L, k));
            }
            inst.setPoints(points);
            s.getInstances().add(inst);
        }
        p.getSeries().add(s);
        return p;
    }

    @Test
    void theServerMetricsPayloadEntersTheChartDataWhole() throws JsonProcessingException {
        ReportData data = createMinimalReportData();
        JsonNode json = objectMapper.readTree(serializer.serializeChartData(data, null, null, GateEvaluation.none(),
                serverPayload(2, 5), null));

        JsonNode sm = json.get("serverMetrics");
        assertNotNull(sm, "the payload rides in the one map every output shares");
        assertEquals("c0ffee", sm.get("collectionId").asText());
        assertEquals("TEST", sm.get("environment").asText());
        assertEquals(5000, sm.get("window").get("rampUpEnd").asLong());
        assertEquals("https://prom/api?token=***", sm.get("sources").get(0).get("resolvedUrl").asText());
        JsonNode series = sm.get("series").get(0);
        assertEquals("up{c=\"eu\"}", series.get("resolvedQuery").asText());
        assertEquals("up{c=\"${env.c}\"}", series.get("query").asText(), "the query as written travels too");
        assertEquals(2, series.get("instanceCount").asInt());
        assertEquals(2, series.get("instances").size());
        assertEquals(5, series.get("instances").get(0).get("points").size());
        assertEquals(4.0, series.get("instances").get(0).get("points").get(4).get(1).asDouble());
        assertFalse(series.has("instancesCapped"));
        assertFalse(series.get("instances").get(0).has("pointsCapped"));

        // Absent payload, absent key: a run without server metrics carries no series block (it does
        // carry the metric catalogue, which every page gets - GatePayloadTest, SF5).
        assertFalse(objectMapper.readTree(ChartDataJson.of(data)).has("serverMetrics"));
    }

    @Test
    void theCapsAreStatedInTheDataNeverSilent() throws JsonProcessingException {
        ReportData data = createMinimalReportData();
        JsonNode series = objectMapper.readTree(serializer.serializeChartData(data, null, null, GateEvaluation.none(),
                serverPayload(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5,
                        ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE + 100), null))
                .get("serverMetrics").get("series").get(0);

        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5, series.get("instanceCount").asInt(),
                "the true count travels");
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, series.get("instances").size());
        assertTrue(series.get("instancesCapped").asBoolean());
        JsonNode first = series.get("instances").get(0);
        assertTrue(first.get("pointsCapped").asBoolean());
        assertEquals(ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE + 100, first.get("pointCount").asInt());
        assertEquals(ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE, first.get("points").size());
        // Thinned, not truncated: the first and last points survive, so the shape does.
        assertEquals(0.0, first.get("points").get(0).get(0).asDouble());
        assertEquals((ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE + 99) * 1000.0,
                first.get("points").get(ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE - 1).get(0).asDouble());
    }

    @Test
    void aCappedSeriesCarriesItsTrueMaximumForSeedingNotTheThinnedOne() throws Exception {
        // C-8. Generate from sources seeds a KPI at headroom over the observed maximum, and on a
        // capped series the page sees twenty instances and thinned points. A spike on a dropped
        // instance or a dropped point was invisible to it, so the seed FAILed on its own run.
        int instances = ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5;
        int points = ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE + 100;
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload payload =
                serverPayload(instances, points);
        // A spike on a point the every-k-th thinning dropped, inside the judged window [5000, 60000]
        // (ramp-up excluded by default): index k was dropped when no shipped index rounded onto it.
        double stride = (points - 1) / (double) (ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE - 1);
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (int i = 0; i < ServerMetricsPayloadWriter.MAX_POINTS_PER_INSTANCE; i++) {
            kept.add(Math.round(i * stride));
        }
        int dropped = -1;
        for (int k = 6; k < 60 && dropped < 0; k++) {
            if (!kept.contains((long) k)) dropped = k;
        }
        assertTrue(dropped > 0, "the stride thinning dropped some point inside the window");
        payload.getSeries().get(0).getInstances().get(0).getPoints().set(dropped,
                new com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload.Point(dropped * 1000L, 5000));

        JsonNode series = objectMapper.readTree(serializer.serializeChartData(createMinimalReportData(),
                null, null, GateEvaluation.none(), payload, null)).get("serverMetrics").get("series").get(0);
        assertEquals(5000.0, series.get("judgedMax").asDouble(), "the build's own number travels");
        // Thinned by bucket now (F6), each bucket's highest point kept, so the spike is drawn - and
        // the seed still reads judgedMax, because a thinned copy is not the series (a dropped
        // instance, below, is still invisible to the page).
        double drawnMax = Double.NEGATIVE_INFINITY;
        for (JsonNode inst : series.get("instances")) {
            for (JsonNode p : inst.get("points")) {
                drawnMax = Math.max(drawnMax, p.get(1).asDouble());
            }
        }
        assertEquals(5000.0, drawnMax, 0.0, "the spike a max judged is on the chart");

        // A dropped INSTANCE, too: every pod holds its own index, so the max is the 25th pod -
        // which collector order does not ship - and the ramp-up is not in the window.
        String payloadJson = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                manyInstances("rising", risingValues(instances)));
        JsonNode rising = objectMapper.readTree(serializer.serializeChartData(createMinimalReportData(), null,
                null, GateEvaluation.none(), com.jmeterwebinsightreport.report.gate.ServerFixtures.payload(payloadJson),
                null))
                .get("serverMetrics").get("series").get(0);
        assertEquals(instances - 1.0, rising.get("judgedMax").asDouble());
        assertFalse(drawnPods(objectMapper.readTree(serializer.serializeChartData(createMinimalReportData(), null,
                        null, GateEvaluation.none(),
                        com.jmeterwebinsightreport.report.gate.ServerFixtures.payload(payloadJson), null)),
                "rising").contains("p" + (instances - 1)), "the pod that holds the maximum is not drawn");

        // An uncapped series carries none: the page reads it whole.
        JsonNode small = objectMapper.readTree(serializer.serializeChartData(createMinimalReportData(),
                null, null, GateEvaluation.none(), serverPayload(2, 5), null)).get("serverMetrics").get("series").get(0);
        assertFalse(small.has("judgedMax"));
    }

    private static double[] risingValues(int n) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        return values;
    }

    /** {@code n} instances of one series, pod {@code p<i>} holding the value {@code values[i]} at every point. */
    private static String manyInstances(String query, double... values) {
        StringBuilder instances = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) instances.append(',');
            StringBuilder pts = new StringBuilder();
            for (int t = 0; t < 4; t++) {
                if (t > 0) pts.append(',');
                pts.append('[').append(com.jmeterwebinsightreport.report.gate.ServerFixtures.START
                        + t * com.jmeterwebinsightreport.report.gate.ServerFixtures.STEP).append(',').append(values[i]).append(']');
            }
            instances.append("{\"labels\":{\"pod\":\"p").append(i).append("\"},\"points\":[").append(pts).append("]}");
        }
        return "{\"source\":\"metrics\",\"query\":\"" + query + "\",\"resolvedQuery\":\"" + query + "\",\"status\":\"OK\","
                + "\"coverage\":1.0,\"instances\":[" + instances + "]}";
    }

    private static List<String> drawnPods(JsonNode chartData, String query) {
        List<String> pods = new java.util.ArrayList<>();
        for (JsonNode s : chartData.get("serverMetrics").get("series")) {
            if (query.equals(s.get("query").asText())) {
                for (JsonNode inst : s.get("instances")) {
                    pods.add(inst.get("labels").get("pod").asText());
                }
            }
        }
        return pods;
    }

    @Test
    void theInstanceCapKeepsTheInstancesTheVerdictComesFrom() throws Exception {
        // L9-12. The cap kept the first twenty in collector order, so with across: max a FAIL
        // caused by the 25th pod sat beside a chart with nothing over the band - the chart
        // contradicting the verdict it is drawn under. Keep the deciding instance(s) first.
        int n = ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES + 5;
        double[] rising = new double[n];
        double[] spread = new double[n];
        double[] up = new double[n];
        for (int i = 0; i < n; i++) {
            rising[i] = i;                       // the max is the LAST pod
            spread[i] = i == 22 ? -50 : 10 + i;  // under avg, the last pod pulls up and p22 pulls down
            up[i] = i == 23 ? 0 : 1;             // under min, p23 - the one that went down - decides
        }
        String payloadJson = com.jmeterwebinsightreport.report.gate.ServerFixtures.payloadJson("r",
                manyInstances("rising", rising), manyInstances("spread", spread), manyInstances("up", up));
        String doc = com.jmeterwebinsightreport.report.gate.ServerFixtures.block(null,
                com.jmeterwebinsightreport.report.gate.ServerFixtures.kpi("rising-max", "rising", 20, null),
                "{\"id\":\"spread-avg\",\"component\":\"server\",\"query\":\"spread\",\"reduce\":{\"kind\":\"max\"},"
                        + "\"across\":\"avg\",\"fail\":{\"gt\":1000}}",
                "{\"id\":\"up-min\",\"component\":\"server\",\"query\":\"up\",\"reduce\":{\"kind\":\"min\"},"
                        + "\"across\":\"min\",\"fail\":{\"lt\":1}}");
        ReportData data = com.jmeterwebinsightreport.report.gate.ServerFixtures.run();
        com.jmeterwebinsightreport.report.annotation.ReportAnnotations a =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.annotations(doc);
        com.jmeterwebinsightreport.report.gate.GateEvaluation e =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data, a);
        com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload payload =
                com.jmeterwebinsightreport.report.gate.ServerFixtures.payload(payloadJson);
        com.jmeterwebinsightreport.report.gate.ServerGateEvaluation server =
                com.jmeterwebinsightreport.report.gate.ServerGateEvaluation.of(a, payload, e.gate());
        assertEquals(n - 1.0, server.results().get(0).actual(), "judged over every instance, the last one included");

        JsonNode json = objectMapper.readTree(serializer.serializeChartData(data, a, null, e, payload, server));
        List<String> rising24 = drawnPods(json, "rising");
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, rising24.size(), "still capped");
        assertTrue(rising24.contains("p" + (n - 1)), "the instance the FAIL came from is drawn: " + rising24);
        assertEquals("p0", rising24.get(0), "collector order is kept for what is drawn");
        assertFalse(rising24.contains("p" + (n - 2)), "the rest fill up in collector order: " + rising24);

        List<String> spreadPods = drawnPods(json, "spread");
        assertTrue(spreadPods.contains("p" + (n - 1)) && spreadPods.contains("p22"),
                "under avg, the instances pulling the average furthest each way are kept: " + spreadPods);

        // SF2: under across min the LOW instance is the deciding one. Keeping the high one kept a
        // healthy pod (they all read 1) and dropped the one that went down, beside its FAIL.
        assertEquals("FAIL", server.results().get(2).status().name());
        assertEquals(0.0, server.results().get(2).actual());
        List<String> upPods = drawnPods(json, "up");
        assertEquals(ServerMetricsPayloadWriter.MAX_INSTANCES_PER_SERIES, upPods.size(), "still capped");
        assertTrue(upPods.contains("p23"), "the instance the FAIL came from is drawn: " + upPods);

        // With no evaluation to ask, the cap is collector order, as before.
        JsonNode blind = objectMapper.readTree(serializer.serializeChartData(data, a, null, e, payload, null));
        assertFalse(drawnPods(blind, "rising").contains("p" + (n - 1)));
    }

    @Test
    void theStreamedPayloadCarriesTheSameBlock() throws Exception {
        ReportData data = createMinimalReportData();
        java.io.StringWriter out = new java.io.StringWriter();
        serializer.externalDataJsContent(data, null, null, "window.__WIR_DATA__=", GateEvaluation.none(),
                serverPayload(1, 2), null).writeTo(out);
        String js = out.toString();
        assertTrue(js.startsWith("window.__WIR_DATA__="));
        assertTrue(js.endsWith(";"));
        JsonNode json = objectMapper.readTree(js.substring("window.__WIR_DATA__=".length(), js.length() - 1));
        assertEquals("c0ffee", json.get("serverMetrics").get("collectionId").asText());
    }

    @Test
    void aLiteralSecretInTheAnnotationsReachesNeitherPayloadForm() throws Exception {
        // D10's canary, at the serializer: the annotations document travels whole into the
        // inline and the external payload, and the mapper's refusal of a literal auth value
        // does not take the value back out — ServerMetricsJson.publishable() does, here.
        // L9-2 widened it to every shape the mapper refuses as a credential: userinfo in a URL and
        // a source-level secret-looking key travelled verbatim, because only auth was masked; then
        // a ?api_key= parameter and a credential URL held in environments.*.vars, which the first
        // widening still published.
        com.jmeterwebinsightreport.report.annotation.ReportAnnotations a = objectMapper.readValue(
                "{\"serverMetrics\":{\"sources\":{\"p\":{\"type\":\"prometheus\",\"url\":\"https://p\","
                        + "\"auth\":{\"bearerToken\":\"CANARY-SECRET\",\"apiKeyEnv\":\"PROM_KEY\"}},"
                        + "\"u\":{\"type\":\"prometheus\",\"url\":\"https://alice:CANARY-USERINFO@prom.corp/api\"},"
                        + "\"t\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api\",\"token\":\"CANARY-TOKEN\"},"
                        + "\"q\":{\"type\":\"prometheus\",\"url\":\"https://prom.corp/api/v1?api_key=CANARY-QUERY\"},"
                        + "\"e\":{\"type\":\"prometheus\",\"url\":\"${env.PROM}\"}},"
                        + "\"environments\":{\"prod\":{\"vars\":{\"PROM\":\"https://bob:CANARY-ENV@prom.corp/api/v1\"}}}}}",
                com.jmeterwebinsightreport.report.annotation.ReportAnnotations.class);
        ReportData data = createMinimalReportData();
        com.jmeterwebinsightreport.report.gate.GateEvaluation e =
                com.jmeterwebinsightreport.report.gate.GateEvaluation.of(data, a);

        String inline = serializer.serializeChartData(data, a, null, e, null, null);
        assertFalse(inline.contains("CANARY"), inline);
        assertTrue(inline.contains("PROM_KEY"), "a reference is not a secret");
        assertTrue(inline.contains("prom.corp/api"), "the URL itself still travels, only its userinfo is masked");

        java.io.StringWriter out = new java.io.StringWriter();
        serializer.externalDataJsContent(data, a, null, "window.__WIR_DATA__=", e, null, null).writeTo(out);
        assertFalse(out.toString().contains("CANARY"), out.toString());
        assertTrue(out.toString().contains("PROM_KEY"));
    }

    // --- Audit-fix serialization tests ---

    @Test
    void serializesTotalsAndTruncationFlag() throws JsonProcessingException {
        // A3/C20: total sample/error counts and the truncation flag are serialized for the client.
        ReportData data = createMinimalReportData();
        data.getMetadata().setTotalSamples(4250);
        data.getMetadata().setTotalErrors(4250);
        data.setErrorRecordsTruncated(true);

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        assertEquals(4250, json.get("totalSampleCount").asInt());
        assertEquals(4250, json.get("totalErrorCount").asInt());
        assertTrue(json.get("errorRecordsTruncated").asBoolean());
    }

    @Test
    void serializesOverallPercentiles() throws JsonProcessingException {
        // B11: true overall percentiles are serialized for the Σ Total row / percentile bar.
        ReportData data = createMinimalReportData();
        data.setOverallMedian(120.0);
        data.setOverallP90(300.0);
        data.setOverallP95(420.0);
        data.setOverallP99(900.0);

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        assertNotNull(json.get("overall"));
        assertEquals(420.0, json.get("overall").get("p95").asDouble(), 0.001);
        assertEquals(900.0, json.get("overall").get("p99").asDouble(), 0.001);
    }

    /** A run with an actual population — the overall block is meaningless without one. */
    private static ReportData reportDataWithSamplers() {
        ReportData data = createMinimalReportData();
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName("GET /api/things");
        s.setSampleCount(600);
        s.setErrorCount(30);
        s.setErrorRate(5.0);
        s.setMeanResponseTime(240);
        s.setMedianResponseTime(200);
        s.setPercentile90(400);
        s.setPercentile95(520);
        s.setPercentile99(880);
        s.setThroughput(10);
        s.setApdexScore(0.82);
        data.setSamplerStatistics(new java.util.ArrayList<>(java.util.Arrays.asList(s)));
        return data;
    }

    @Test
    void theOverallBlockCarriesEverythingAScopeOverallRuleCanTarget() throws JsonProcessingException {
        // A rule can gate on {"scope": "overall"}, and the editor seeds its band from the
        // observed value — so it can only offer the metrics the payload actually carries.
        // Percentiles alone were enough while this block only fed the Σ Total row.
        ReportData data = reportDataWithSamplers();

        JsonNode overall = objectMapper.readTree(
                ChartDataJson.of(data)).get("overall");

        assertNotNull(overall, "the overall block must exist whenever there are samplers");
        for (String key : new String[]{"median", "p90", "p95", "p99", "mean", "totalSamples",
                "errorRate", "throughput", "apdex", "duration", "maxThreads"}) {
            assertTrue(overall.has(key), "overall." + key + " is missing from the payload");
        }
    }

    @Test
    void theOverallBlockAgreesWithTheSharedResolver() throws JsonProcessingException {
        // The payload, the header tiles and the CI/CD JSON all go through OverallMetrics. If
        // the payload derived its own totals the editor would seed a band from one number and
        // the gate would judge another.
        ReportData data = reportDataWithSamplers();
        com.jmeterwebinsightreport.core.model.OverallMetrics expected =
                com.jmeterwebinsightreport.core.model.OverallMetrics.of(data);

        JsonNode overall = objectMapper.readTree(
                ChartDataJson.of(data)).get("overall");

        assertEquals(expected.getTotalSamples(), overall.get("totalSamples").asLong());
        assertEquals(expected.getThroughput(), overall.get("throughput").asDouble(), 0.01);
        assertEquals(expected.getApdex(), overall.get("apdex").asDouble(), 0.001);
        assertEquals(expected.getErrorRate(), overall.get("errorRate").asDouble(), 0.01);
    }

    @Test
    void theThroughputTheBrowserJudgesIsCarriedAtFullPrecision() throws JsonProcessingException {
        // R2-1: the Server tab re-judges requiresLoad and divideBy from these two numbers, and
        // Java judges them unrounded. At 2dp a sampler doing 12.496 req/s sat ON a `lt 12.5` load
        // rule and passed it on the board, while the build stood the dependent KPI down; a ratio
        // divided by 12.5 where the build divided by 12.496. The parity vectors 'load:edge' and
        // 'lag-per-edge' pin the judging; this pins the payload they are judged from.
        ReportData data = reportDataWithSamplers();
        SamplerStatistics s = data.getSamplerStatistics().get(0);
        s.setSampleCount(1501);                      // 1501 samples over 60 s: 25.0166… req/s overall
        s.setThroughput(12.496);
        double overallThroughput = com.jmeterwebinsightreport.core.model.OverallMetrics.of(data).getThroughput();
        assertNotEquals(Math.round(overallThroughput * 100.0) / 100.0, overallThroughput,
                "the fixture must carry a throughput that round2 would change, or this pins nothing");

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));

        assertEquals(12.496, json.get("samplers").get(0).get("throughput").asDouble(), 0.0);
        assertEquals(overallThroughput, json.get("overall").get("throughput").asDouble(), 0.0);
        // The figures the tables show are still 2dp — only what is judged moved.
        assertEquals(240.0, json.get("samplers").get(0).get("mean").asDouble(), 0.0);
    }

    @Test
    void everyFigureAClientRuleJudgesIsCarriedExactBesideThe2dpOneTheTablesShow() throws JsonProcessingException {
        // R2-1's client half (M7d X): throughput was carried unrounded, every other figure a
        // client rule can name was not, so a rule re-judged on the Asserts tab after an edit could
        // flip at an edge the run does not - a mean of 12.496 sat ON `lt 12.5` and passed. The
        // 2dp field stays what the charts read; `exact` holds what round2 changed, and only that.
        ReportData data = reportDataWithSamplers();
        SamplerStatistics s = data.getSamplerStatistics().get(0);
        s.setMeanResponseTime(12.496);
        s.setPercentile95(519.996);
        s.setErrorRate(4.99999);

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        JsonNode sampler = json.get("samplers").get(0);

        assertEquals(12.5, sampler.get("mean").asDouble(), 0.0, "the table's figure is still 2dp");
        assertEquals(12.496, sampler.get("exact").get("mean").asDouble(), 0.0);
        assertEquals(519.996, sampler.get("exact").get("p95").asDouble(), 0.0);
        assertEquals(4.99999, sampler.get("exact").get("errorRate").asDouble(), 0.0);
        assertFalse(sampler.get("exact").has("median"), "a figure round2 did not change is not repeated");
        assertFalse(sampler.get("exact").has("throughput"), "throughput is already exact in place");

        JsonNode overall = json.get("overall");
        assertEquals(com.jmeterwebinsightreport.core.model.OverallMetrics.of(data).getMean(),
                overall.get("exact").get("mean").asDouble(), 0.0);
    }

    @Test
    void aRunOfWholeNumbersCarriesNoExactBlock() throws JsonProcessingException {
        JsonNode sampler = objectMapper.readTree(ChartDataJson.of(reportDataWithSamplers()))
                .get("samplers").get(0);
        assertFalse(sampler.has("exact"), "nothing was rounded, so nothing is repeated: " + sampler);
    }

    @Test
    void aThroughputThatIsNotANumberIsStillValidJson() throws JsonProcessingException {
        // Unrounded must not mean unguarded: round2 turned NaN into 0, and Jackson would write a
        // bare NaN token the page cannot parse.
        ReportData data = reportDataWithSamplers();
        data.getSamplerStatistics().get(0).setThroughput(Double.NaN);
        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        assertEquals(0.0, json.get("samplers").get(0).get("throughput").asDouble(), 0.0);
    }

    @Test
    void thePayloadCarriesTheApdexThresholdTheScoresWereComputedAgainst() throws JsonProcessingException {
        // "apdex fell below 0.8" says nothing without T. An apdex rule asserts that
        // configuration as much as the score, so the report shows T beside any such rule —
        // and cannot until the number is in the payload.
        ReportData data = createMinimalReportData();
        data.setApdexThreshold(750.0);

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        assertEquals(750.0, json.get("apdexThreshold").asDouble(), 0.001);
    }

    @Test
    void percentilesBeyondTheStoredFourAppearOnlyWhenADistributionSurvived()
            throws JsonProcessingException {
        // p75 and p99.9 come from the retained digest. A ReportData assembled by hand has no
        // digest, and the keys are omitted rather than filled with a value interpolated from
        // p95 — a wrong tail number is worse than a missing one, because the editor would
        // offer it as a seedable metric.
        JsonNode assembled = objectMapper.readTree(
                ChartDataJson.of(reportDataWithSamplers()));
        assertFalse(assembled.get("samplers").get(0).has("p999"),
                "no digest means no p99.9 — not a guess");

        com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine engine =
                new com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine();
        for (int i = 1; i <= 1000; i++) {
            engine.recordSample("S", i, true, 500, 100, 1_700_000_000_000L + i, 5, 10);
        }
        JsonNode aggregated = objectMapper.readTree(
                ChartDataJson.of(engine.buildReportData("T")));
        JsonNode sampler = aggregated.get("samplers").get(0);

        assertTrue(sampler.has("p75") && sampler.has("p999"));
        assertEquals(750.0, sampler.get("p75").asDouble(), 3.0);
        assertEquals(999.0, sampler.get("p999").asDouble(), 3.0);
        assertEquals(999.0, aggregated.get("overall").get("p999").asDouble(), 3.0);
    }

    @Test
    void serializesAbsentConnectLatencyAsNull() throws JsonProcessingException {
        // B14: buckets without connect/latency data must serialize null, not 0, so the client
        // can skip them instead of averaging in spurious zeros.
        ReportData data = createMinimalReportData();
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(Instant.ofEpochMilli(1700000000000L));
        TimeSeriesBucket.SamplerBucketData sd = new TimeSeriesBucket.SamplerBucketData();
        sd.setSampleCount(5);
        sd.setMeanResponseTime(100.0);
        // connectTime / latency left null (absent)
        Map<String, TimeSeriesBucket.SamplerBucketData> per = new LinkedHashMap<>();
        per.put("GET /api/x", sd);
        bucket.setPerSamplerData(per);
        data.setTimeSeries(new ArrayList<>(Collections.singletonList(bucket)));

        JsonNode json = objectMapper.readTree(ChartDataJson.of(data));
        JsonNode connect = json.get("perSamplerSeries").get("GET /api/x").get("connectTime");
        assertNotNull(connect);
        assertTrue(connect.get(0).isNull(), "absent connect time must serialize as null");
    }

    // --- Helper methods ---

    private static ReportData createMinimalReportData() {
        ReportData data = new ReportData();
        TestMetadata meta = new TestMetadata();
        meta.setTestName("Test Plan");
        meta.setDurationMillis(60000);
        meta.setTotalSamples(100);
        meta.setTotalErrors(0);
        data.setMetadata(meta);
        return data;
    }

    private static SamplerStatistics createSamplerStats(String name, long count, double mean,
                                                         double p95, double errorRate) {
        SamplerStatistics stat = new SamplerStatistics();
        stat.setSamplerName(name);
        stat.setSampleCount(count);
        stat.setMeanResponseTime(mean);
        stat.setMedianResponseTime(mean * 0.9);
        stat.setPercentile90(p95 * 0.9);
        stat.setPercentile95(p95);
        stat.setPercentile99(p95 * 1.2);
        stat.setMinResponseTime(mean * 0.3);
        stat.setMaxResponseTime(p95 * 2.0);
        stat.setErrorRate(errorRate);
        stat.setErrorCount((long) (count * errorRate / 100.0));
        stat.setThroughput(count / 60.0);
        stat.setStandardDeviation(mean * 0.5);
        stat.setReceivedBytesPerSec(1024.0);
        stat.setSentBytesPerSec(512.0);
        stat.setMeanConnectTime(5.0);
        stat.setMeanLatency(mean * 0.8);
        return stat;
    }

    /**
     * A sampler whose run never measured connect time or latency serialises as null, not NaN.
     *
     * <p>Both matter. NaN is not JSON — Jackson writes the bare token {@code NaN}, which
     * {@code JSON.parse} rejects, so one sampler from a JTL saved without the optional columns
     * would take the entire payload down and with it every chart, the gate strip and the whole
     * Asserts tab. And null is what the client already reads as "no value": {@code fmtNum}
     * renders an empty cell for it, which is the honest thing to show for a number the run
     * never took.
     */
    @Test
    void anUnmeasuredConnectTimeSerialisesAsNullRatherThanNaN() throws Exception {
        SamplerStatistics stat = createSamplerStats("GET /x", 10, 100, 200, 0);
        // Back to the model default — what SamplerAggregator leaves when the JTL had no
        // Connect/Latency column, because it only assigns these when its guarded count is > 0.
        stat.setMeanConnectTime(Double.NaN);
        stat.setMeanLatency(Double.NaN);
        ReportData data = new ReportData();
        data.setSamplerStatistics(Collections.singletonList(stat));
        data.setMetadata(new TestMetadata());

        String json = ChartDataJson.of(data);

        assertFalse(json.contains("NaN"), "NaN is not JSON and would break JSON.parse: " + json);
        JsonNode sampler = objectMapper.readTree(json).get("samplers").get(0);
        assertTrue(sampler.get("meanConnectTime").isNull(), sampler.toString());
        assertTrue(sampler.get("meanLatency").isNull(), sampler.toString());
    }

    private static TimeSeriesBucket createBucket(Instant timestamp, String samplerName,
                                                  double meanRT, double throughput,
                                                  double errorRate, int threads) {
        TimeSeriesBucket bucket = new TimeSeriesBucket();
        bucket.setTimestamp(timestamp);
        bucket.setSampleCount(10);
        bucket.setMeanResponseTime(meanRT);
        bucket.setThroughput(throughput);
        bucket.setErrorRate(errorRate);
        bucket.setActiveThreads(threads);

        TimeSeriesBucket.SamplerBucketData sd = new TimeSeriesBucket.SamplerBucketData();
        sd.setMeanResponseTime(meanRT);
        sd.setSampleCount(10);
        sd.setThroughput(throughput);
        sd.setErrorRate(errorRate);

        Map<String, TimeSeriesBucket.SamplerBucketData> perSampler = new LinkedHashMap<>();
        perSampler.put(samplerName, sd);
        bucket.setPerSamplerData(perSampler);

        return bucket;
    }

    private static ReportData createFullReportData() {
        ReportData data = createMinimalReportData();

        // Sampler statistics
        List<SamplerStatistics> stats = new ArrayList<>();
        stats.add(createSamplerStats("GET /api/users", 500, 150.0, 300.0, 1.0));
        stats.add(createSamplerStats("POST /api/login", 300, 200.0, 400.0, 2.0));
        data.setSamplerStatistics(stats);

        // Time series
        Instant base = Instant.ofEpochMilli(1700000000000L);
        List<TimeSeriesBucket> timeSeries = new ArrayList<>();
        timeSeries.add(createBucket(base, "GET /api/users", 120.0, 10.0, 0.0, 5));
        timeSeries.add(createBucket(base.plusSeconds(1), "GET /api/users", 150.0, 12.0, 1.0, 5));
        timeSeries.add(createBucket(base.plusSeconds(2), "GET /api/users", 180.0, 8.0, 0.5, 5));
        data.setTimeSeries(timeSeries);

        // Error summaries
        List<ErrorSummary> errorSummaries = new ArrayList<>();
        ErrorSummary es = new ErrorSummary();
        es.setResponseCode("500");
        es.setResponseMessage("Internal Server Error");
        es.setOccurrenceCount(5);
        es.setPercentageOfErrors(100.0);
        errorSummaries.add(es);
        data.setErrorSummaries(errorSummaries);

        // Error records
        List<ErrorRecord> errorRecords = new ArrayList<>();
        ErrorRecord er = new ErrorRecord();
        er.setTimestamp(1700000001000L);
        er.setSamplerName("GET /api/users");
        er.setResponseCode("500");
        er.setResponseMessage("Internal Server Error");
        er.setThreadName("Thread-1");
        errorRecords.add(er);
        data.setErrorRecords(errorRecords);

        // Hidden samplers
        data.setHiddenSamplers(Arrays.asList("Debug Sampler", "BeanShell Sampler"));

        // Transaction hierarchy
        Map<String, List<String>> hierarchy = new LinkedHashMap<>();
        hierarchy.put("Login Flow", Arrays.asList("GET /api/login-page", "POST /api/login"));
        data.setTransactionHierarchy(hierarchy);

        return data;
    }

    // --- serializeToJson tests ---

    @Test
    void serialize_producesValidJson() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = serializer.serializeToJson(data);

        assertNotNull(json);
        assertDoesNotThrow(() -> objectMapper.readTree(json));
    }

    // --- serializeChartData tests ---

    @Test
    void serialize_includesTimeSeriesArrays() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("timestamps"));
        assertTrue(root.has("meanResponseTimes"));
        assertTrue(root.has("throughputs"));
        assertTrue(root.has("errorRates"));
        assertTrue(root.has("activeThreads"));

        assertTrue(root.get("timestamps").isArray());
        assertEquals(3, root.get("timestamps").size());
        assertEquals(3, root.get("meanResponseTimes").size());
    }

    @Test
    void serialize_includesPerSamplerSeries() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("perSamplerSeries"));
        JsonNode perSampler = root.get("perSamplerSeries");
        assertTrue(perSampler.has("GET /api/users"));

        JsonNode samplerSeries = perSampler.get("GET /api/users");
        assertTrue(samplerSeries.has("responseTime"));
        assertTrue(samplerSeries.has("throughput"));
        assertTrue(samplerSeries.has("errorRate"));
    }

    @Test
    void serialize_includesSamplerSummary() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("samplers"));
        JsonNode samplers = root.get("samplers");
        assertTrue(samplers.isArray());
        assertEquals(2, samplers.size());

        JsonNode first = samplers.get(0);
        assertEquals("GET /api/users", first.get("name").asText());
        assertTrue(first.has("count"));
        assertTrue(first.has("mean"));
        assertTrue(first.has("p95"));
        assertTrue(first.has("p99"));
        assertTrue(first.has("errorRate"));
        assertTrue(first.has("throughput"));
    }

    @Test
    void serialize_includesErrorSummaries() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("errorsByType"));
        JsonNode errorsByType = root.get("errorsByType");
        assertTrue(errorsByType.isArray());
        assertEquals(1, errorsByType.size());
        assertEquals("500", errorsByType.get(0).get("code").asText());
        assertEquals("Internal Server Error", errorsByType.get(0).get("message").asText());
    }

    @Test
    void serialize_includesErrorRecords() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("errorRecords"));
        JsonNode errorRecords = root.get("errorRecords");
        assertTrue(errorRecords.isArray());
        assertEquals(1, errorRecords.size());

        JsonNode record = errorRecords.get(0);
        assertEquals("GET /api/users", record.get("sampler").asText());
        assertEquals("500", record.get("code").asText());
        assertEquals("Thread-1", record.get("thread").asText());
    }

    @Test
    void serialize_includesHiddenSamplers() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("hiddenSamplers"));
        JsonNode hidden = root.get("hiddenSamplers");
        assertTrue(hidden.isArray());
        assertEquals(2, hidden.size());
        assertEquals("Debug Sampler", hidden.get(0).asText());
    }

    @Test
    void serialize_includesTransactionHierarchy() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("transactionHierarchy"));
        JsonNode hierarchy = root.get("transactionHierarchy");
        assertTrue(hierarchy.has("Login Flow"));
        assertEquals(2, hierarchy.get("Login Flow").size());
    }

    @Test
    void serialize_handlesEmptyData() throws JsonProcessingException {
        ReportData data = new ReportData();
        data.setMetadata(new TestMetadata());

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertNotNull(root);
        // Should have timestamp/series arrays (empty)
        assertTrue(root.has("timestamps"));
        assertEquals(0, root.get("timestamps").size());
        assertTrue(root.has("samplers"));
        assertEquals(0, root.get("samplers").size());
    }

    @Test
    void serialize_includesResponseTimeDistribution() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("responseTimeDistribution"));
        JsonNode dist = root.get("responseTimeDistribution");
        assertTrue(dist.has("binLabels"));
        assertTrue(dist.has("perSampler"));
    }

    @Test
    void serialize_omitsTheDeadResponseCodesOverTimePayload() throws JsonProcessingException {
        // Nothing in report.js ever read it, and it was built from the 500-capped error
        // records, so it would have been wrong for any run with more errors than that.
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertFalse(root.has("responseCodesOverTime"));
    }

    @Test
    void serialize_includesErrorsBySampler() throws JsonProcessingException {
        ReportData data = createFullReportData();

        String json = ChartDataJson.of(data);
        JsonNode root = objectMapper.readTree(json);

        assertTrue(root.has("errorsBySampler"));
        JsonNode errorsBySampler = root.get("errorsBySampler");
        assertTrue(errorsBySampler.isArray());
        // At least one sampler has errors
        assertTrue(errorsBySampler.size() > 0);
    }

    // --- External data streaming (LIST-11) ---

    /**
     * The sidecar as the generator stages it, written to a real file: a closed file writer refuses
     * the trailing ";", where a StringWriter would take it and hide the bug the first test is for.
     */
    private String writtenSidecar(ReportData data, String prefix) throws Exception {
        java.io.File out = java.io.File.createTempFile("wir-external", ".js");
        out.deleteOnExit();
        com.jmeterwebinsightreport.core.util.AtomicFileWriter.write(out, serializer.externalDataJsContent(
                data, null, null, prefix, com.jmeterwebinsightreport.report.gate.GateEvaluation.none(),
                null, null));
        return new String(java.nio.file.Files.readAllBytes(out.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theSidecarWrapsThePayloadAndKeepsTheTrailingSemicolon() throws Exception {
        // The old writeChartDataToFile wrote bare JSON with no assignment, so the report's
        // <script src> would have loaded a file that defines nothing. And Jackson closes its
        // target by default, which would silently swallow the trailing ";".
        ReportData data = createFullReportData();

        String js = writtenSidecar(data, "window.__WIR_DATA__=");

        assertTrue(js.startsWith("window.__WIR_DATA__="), "must assign the global the report reads");
        assertTrue(js.endsWith(";"), "trailing semicolon must survive");

        String json = js.substring("window.__WIR_DATA__=".length(), js.length() - 1);
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.has("timestamps"));
        assertTrue(root.has("samplers"));
    }

    @Test
    void theSidecarMatchesTheInlinePayload() throws Exception {
        ReportData data = createFullReportData();

        String js = writtenSidecar(data, "window.__WIR_DATA__=");
        String streamed = js.substring("window.__WIR_DATA__=".length(), js.length() - 1);

        assertEquals(objectMapper.readTree(ChartDataJson.of(data)),
                objectMapper.readTree(streamed),
                "streaming and inline serialization must produce identical data");
    }

    // --- Removed samplers must not crash serialization (review finding on PR #35) ---

    @Test
    void serialize_removedSamplerDoesNotThrow() throws Exception {
        // A sampler present in the baseline but absent from this run yields a diff whose
        // getCurrent() is null. The serializer dereferenced it unguarded, so the whole
        // report failed to generate with a NullPointerException.
        ReportData baseline = createFullReportData();
        ReportData current = createFullReportData();
        current.setSamplerStatistics(new ArrayList<>(
                current.getSamplerStatistics().subList(0, 1)));   // drop POST /api/login

        com.jmeterwebinsightreport.core.comparison.ComparisonResult comparison =
                new com.jmeterwebinsightreport.core.comparison.ComparisonResult(
                        baseline, current,
                        new com.jmeterwebinsightreport.core.comparison.ComparisonThresholds());

        String json = assertDoesNotThrow(
                () -> serializer.serializeChartData(current, null, comparison,
                        com.jmeterwebinsightreport.report.gate.GateEvaluation.none(), null, null));
        JsonNode diffs = objectMapper.readTree(json).get("comparisonDiffs");
        assertNotNull(diffs);

        JsonNode removed = null;
        for (JsonNode d : diffs) {
            if ("POST /api/login".equals(d.get("name").asText())) removed = d;
        }
        assertNotNull(removed, "the removed sampler must still appear in the diff list");
        assertTrue(removed.get("removed").asBoolean());
        assertFalse(removed.has("currSamples"), "there are no current-side figures to report");
        assertEquals(300, removed.get("baseSamples").asLong());
    }
}