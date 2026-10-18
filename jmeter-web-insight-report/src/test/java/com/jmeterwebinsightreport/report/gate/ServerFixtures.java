package com.jmeterwebinsightreport.report.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.model.ReportData;
import com.jmeterwebinsightreport.core.model.SamplerStatistics;
import com.jmeterwebinsightreport.core.model.TestMetadata;
import com.jmeterwebinsightreport.core.servermetrics.collect.ServerMetricsPayload;
import com.jmeterwebinsightreport.report.annotation.ReportAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One place the report-module tests build a server-metrics run from: a payload in the
 * collector's own JSON, an annotations document, and the evaluation over both.
 */
public final class ServerFixtures {

    public static final long START = 1_700_000_000_000L;
    public static final long END = START + 60_000;
    public static final long STEP = 15_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ServerFixtures() {
    }

    /** A run of one sampler, its identity computable, its window {@code [START, END]}. */
    public static ReportData run() {
        ReportData d = new ReportData();
        List<SamplerStatistics> stats = new ArrayList<>();
        SamplerStatistics s = new SamplerStatistics();
        s.setSamplerName("GET /a");
        s.setSampleCount(100);
        s.setMeanResponseTime(100);
        s.setMedianResponseTime(90);
        s.setPercentile90(120);
        s.setPercentile95(150);
        s.setPercentile99(200);
        s.setThroughput(5);
        s.setApdexScore(0.9);
        stats.add(s);
        d.setSamplerStatistics(stats);
        TestMetadata m = new TestMetadata();
        m.setTestName("Server fixtures");
        m.setStartTime(Instant.ofEpochMilli(START));
        m.setEndTime(Instant.ofEpochMilli(END));
        m.setDurationMillis(END - START);
        m.setTotalThreads(10);
        m.setTotalSamples(100);
        d.setMetadata(m);
        return d;
    }

    /**
     * One series over source {@code prom}: one instance, a point every {@link #STEP} from
     * {@link #START}, the values given. {@code status} is a CollectionStatus wire name.
     */
    public static String series(String query, String status, Double coverage, String error, double... values) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) pts.append(',');
            pts.append('[').append(START + i * STEP).append(',').append(values[i]).append(']');
        }
        return "{\"source\":\"metrics\",\"query\":\"" + query + "\",\"resolvedQuery\":\"" + query + "\","
                + "\"status\":\"" + status + "\""
                + (coverage == null ? "" : ",\"coverage\":" + coverage)
                + (error == null ? "" : ",\"error\":\"" + error + "\"")
                + ",\"instances\":" + (values.length == 0 ? "[]"
                : "[{\"labels\":{\"pod\":\"a\"},\"points\":[" + pts + "]}]") + "}";
    }

    /** A complete, OK series with the values given. */
    public static String ok(String query, double... values) {
        return series(query, "OK", 1.0, null, values);
    }

    /** The collector's document for {@code runId}, over source {@code prom}, carrying the series given. */
    public static String payloadJson(String runId, String... series) {
        return "{\"schema\":\"wir-server-metrics/1\",\"runId\":\"" + runId + "\",\"collectionId\":\"c0ffee\","
                + "\"collectedAt\":\"2026-08-22T12:00:00Z\","
                + "\"window\":{\"start\":" + START + ",\"end\":" + END + "},"
                + "\"sources\":[{\"id\":\"metrics\",\"type\":\"prometheus\",\"status\":\"OK\",\"stepMillis\":" + STEP
                + ",\"clockOffsetMs\":0,\"resolvedUrl\":\"https://prom.corp/api/v1\"}],"
                + "\"series\":[" + String.join(",", series) + "]}";
    }

    public static ServerMetricsPayload payload(String json) throws Exception {
        return JSON.readValue(json, ServerMetricsPayload.class);
    }

    public static ReportAnnotations annotations(String json) throws Exception {
        return JSON.readValue(json, ReportAnnotations.class);
    }

    /** The component a fixture assert names when the test names none: one custom component. */
    public static final String DEFAULT_COMPONENT = "server";

    /**
     * A {@code serverMetrics} block with the components and asserts given (raw JSON fragments).
     * {@code componentsJson} null declares {@link #DEFAULT_COMPONENT} alone, as custom.
     */
    public static String block(String componentsJson, String... kpisJson) {
        return "{\"serverMetrics\":{\"components\":"
                + (componentsJson == null ? "{\"" + DEFAULT_COMPONENT + "\":{\"type\":\"custom\"}}" : componentsJson)
                + ",\"asserts\":[" + String.join(",", kpisJson) + "]}}";
    }

    /** One assert: {@code max} of {@code query}, {@code fail: {gt: limit}}, on {@code component} (null: the default). */
    public static String kpi(String id, String query, double failGt, String component) {
        return "{\"id\":\"" + id + "\",\"component\":\"" + (component == null ? DEFAULT_COMPONENT : component)
                + "\",\"query\":\"" + query + "\",\"reduce\":{\"kind\":\"max\"},"
                + "\"fail\":{\"gt\":" + failGt + "}}";
    }

    /** The evaluation: the client gate from the same document, the payload as given (null for none). */
    public static ServerGateEvaluation evaluate(String annotationsJson, String payloadJson, ReportData data) throws Exception {
        ReportAnnotations a = annotations(annotationsJson);
        GateEvaluation client = GateEvaluation.of(data, a);
        return ServerGateEvaluation.of(a, payloadJson == null ? null : payload(payloadJson), client.gate());
    }

    /**
     * The evaluation as the generator builds it when the configured payload was refused: the
     * loader's own answer for a file that does not exist ({@code MISSING}).
     */
    public static ServerGateEvaluation evaluateRefused(String annotationsJson, ReportData data) throws Exception {
        ReportAnnotations a = annotations(annotationsJson);
        GateEvaluation client = GateEvaluation.of(data, a);
        java.io.File absent = new java.io.File(System.getProperty("java.io.tmpdir"),
                "wir-absent-" + System.nanoTime() + ".json");
        com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader.Result refused =
                new com.jmeterwebinsightreport.report.servermetrics.ServerMetricsPayloadLoader()
                        .read(absent, com.jmeterwebinsightreport.core.util.RunIdentity.of(data));
        return ServerGateEvaluation.of(a, refused, client.gate(), data);
    }
}
