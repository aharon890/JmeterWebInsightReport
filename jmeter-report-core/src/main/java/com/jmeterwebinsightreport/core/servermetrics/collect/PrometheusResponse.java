package com.jmeterwebinsightreport.core.servermetrics.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Prometheus {@code query_range} response into instances — shared by the HTTP source
 * and the {@code file} source, which is exactly why the file source records that shape and not
 * one of its own: a recording is a response, so replaying it exercises the same parser a live
 * query does.
 *
 * <pre>{"status":"success","data":{"resultType":"matrix",
 *   "result":[{"metric":{"pod":"a"},"values":[[1700000000,"12.5"], …]}, …]}}</pre>
 *
 * <p>Timestamps are seconds (possibly fractional); values are strings, because Prometheus
 * writes every sample as text so that {@code NaN} and {@code +Inf} survive JSON. A non-finite
 * sample is dropped here rather than carried: nothing downstream can judge it, and a gap is the
 * honest rendering.
 */
public final class PrometheusResponse {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PrometheusResponse() {
    }

    /** What the response said, or why it could not be read. */
    public static final class Parsed {
        private final List<ServerMetricsPayload.InstanceRecord> instances;
        private final String error;

        private Parsed(List<ServerMetricsPayload.InstanceRecord> instances, String error) {
            this.instances = instances;
            this.error = error;
        }

        public List<ServerMetricsPayload.InstanceRecord> instances() { return instances; }

        /** Non-null when the response was not a successful matrix — the source FAILED. */
        public String error() { return error; }

        public boolean failed() { return error != null; }
    }

    /**
     * @param clockOffsetMs added to every timestamp — the source's declared skew, applied on
     *        read and never guessed
     */
    public static Parsed parse(String body, long clockOffsetMs) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            // The parser's own sentence, without Jackson's appended location block: that is a
            // second line ("\n at [Source: ...") in a message read on one line - a console line
            // for grep, a panel cell, a JUnit attribute.
            String why = e instanceof com.fasterxml.jackson.core.JsonProcessingException
                    ? ((com.fasterxml.jackson.core.JsonProcessingException) e).getOriginalMessage()
                    : e.getMessage();
            return new Parsed(new ArrayList<ServerMetricsPayload.InstanceRecord>(),
                    "response is not JSON: " + why);
        }
        if (root == null || !root.isObject()) {
            return new Parsed(new ArrayList<ServerMetricsPayload.InstanceRecord>(), "response is not a JSON object");
        }
        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            String errorType = root.path("errorType").asText("");
            String error = root.path("error").asText("");
            return new Parsed(new ArrayList<ServerMetricsPayload.InstanceRecord>(),
                    "source answered status=" + (status.isEmpty() ? "?" : status)
                            + (errorType.isEmpty() ? "" : " " + errorType)
                            + (error.isEmpty() ? "" : ": " + error));
        }
        JsonNode data = root.path("data");
        String resultType = data.path("resultType").asText("");
        if (!"matrix".equals(resultType)) {
            return new Parsed(new ArrayList<ServerMetricsPayload.InstanceRecord>(),
                    "resultType is '" + resultType + "', not 'matrix' - query_range, not query");
        }
        List<ServerMetricsPayload.InstanceRecord> instances = new ArrayList<>();
        for (JsonNode entry : data.path("result")) {
            ServerMetricsPayload.InstanceRecord inst = new ServerMetricsPayload.InstanceRecord();
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode metric = entry.path("metric");
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                labels.put(f.getKey(), f.getValue().asText());
            }
            inst.setLabels(labels);
            List<ServerMetricsPayload.Point> points = new ArrayList<>();
            for (JsonNode sample : entry.path("values")) {
                if (!sample.isArray() || sample.size() != 2) {
                    continue;
                }
                double seconds = sample.get(0).asDouble(Double.NaN);
                double value;
                try {
                    value = Double.parseDouble(sample.get(1).asText());
                } catch (NumberFormatException e) {
                    value = Double.NaN;
                }
                if (Double.isNaN(seconds) || Double.isNaN(value) || Double.isInfinite(value)) {
                    continue;   // a gap, not a number
                }
                points.add(new ServerMetricsPayload.Point(Math.round(seconds * 1000) + clockOffsetMs, value));
            }
            inst.setPoints(points);
            instances.add(inst);
        }
        return new Parsed(instances, null);
    }
}
