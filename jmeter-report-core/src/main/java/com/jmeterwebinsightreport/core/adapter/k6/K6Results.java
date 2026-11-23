package com.jmeterwebinsightreport.core.adapter.k6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.rundata.RunDataWriter;
import com.jmeterwebinsightreport.core.rundata.RunHeader;
import com.jmeterwebinsightreport.core.rundata.RunSample;
import com.jmeterwebinsightreport.core.rundata.ToolRecords;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * k6's own files as run data: the report's k6 adapter (spec 001, decision 8, contracts/k6-adapter.md).
 *
 * <p>It keeps the rules of the research's reference converter ({@code test-plans/run-data/tools/k6_to_jtl.py}, which
 * matched JMeter's Aggregate Report in 198 of 198 referee checks), and writes run data instead of a JTL: a group pass's
 * elapsed time and verdict are left to the report's engine (research R4). The CSV is read in one pass. A group pass is
 * held with its requests until it closes ({@code group_duration}), so memory grows with the passes open at once, not
 * with the run.
 */
public final class K6Results {

    /** What k6 does not record per request: only these read "not measured" (data-model.md Run.notMeasured). */
    static final List<String> NOT_MEASURED = List.of("bytes", "sentBytes", "latency", "connect");

    private static final Map<String, String> TIMINGS = new LinkedHashMap<>();

    static {
        TIMINGS.put("http_req_blocked", "blocked");
        TIMINGS.put("http_req_connecting", "connecting");
        TIMINGS.put("http_req_tls_handshaking", "tlsHandshaking");
        TIMINGS.put("http_req_sending", "sending");
        TIMINGS.put("http_req_waiting", "waiting");
        TIMINGS.put("http_req_receiving", "receiving");
    }

    /** What a conversion wrote. */
    public static final class Result {
        public long requests;
        public long passes;
        public long cutOffPasses;
        public boolean wholeSeconds;
        public boolean withoutUsers;
    }

    private K6Results() {
    }

    /**
     * Writes k6's results CSV (and its end-of-test summary, when given) as run data.
     *
     * @param results  k6's {@code --out csv} file, {@code .gz} or plain
     * @param summary  k6's end-of-test summary (handleSummary's data, or {@code --summary-export}), or null
     * @param testName the report title: it enters the run's identity
     * @param runData  where the run data goes ({@code .gz} by its name)
     */
    public static Result toRunData(File results, File summary, String testName, File runData) throws IOException {
        Conversion c = new Conversion();
        c.read(results);
        RunHeader header = new RunHeader("k6", null, testName);
        header.notMeasured.addAll(NOT_MEASURED);
        if (!c.hasMetadata) {
            header.notMeasured.add("user");
            header.notMeasured.add("iteration");
        }
        if (c.wholeSeconds) {
            header.timeResolution = "s";
        }
        try (RunDataWriter w = new RunDataWriter(runData, header)) {
            c.write(w);
            if (summary != null) {
                writeSummary(summary, w);
            }
        }
        Result r = new Result();
        r.requests = c.requests;
        r.passes = c.completePasses;
        r.cutOffPasses = c.cutOff;
        r.wholeSeconds = c.wholeSeconds;
        r.withoutUsers = !c.hasMetadata;
        return r;
    }

    /** Thresholds and counters from k6's end-of-test summary. Its checks come from the CSV, one per pass. */
    static void writeSummary(File summary, RunDataWriter w) throws IOException {
        JsonNode root = new ObjectMapper().readTree(summary);
        JsonNode metrics = root.path("metrics");
        Iterator<Map.Entry<String, JsonNode>> it = metrics.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> m = it.next();
            JsonNode thresholds = m.getValue().path("thresholds");
            Iterator<Map.Entry<String, JsonNode>> t = thresholds.fields();
            while (t.hasNext()) {
                Map.Entry<String, JsonNode> th = t.next();
                w.threshold(new ToolRecords.Threshold(m.getKey(), th.getKey(), th.getValue().path("ok").asBoolean()));
            }
        }
        counter(w, metrics, "iterations", "count");
        counter(w, metrics, "dropped_iterations", "count");
        counter(w, metrics, "vus_max", "max");
    }

    private static void counter(RunDataWriter w, JsonNode metrics, String metric, String value) throws IOException {
        JsonNode v = metrics.path(metric).path("values").path(value);
        if (v.isNumber()) {
            w.counter(metric, v.asDouble());
        } else if (metric.equals("dropped_iterations")) {
            w.counter(metric, 0); // k6 leaves the metric out when no iteration was dropped
        }
    }

    /** One request or group pass, with what it needs until it is written. */
    private static final class Node {
        final boolean pass;
        RunSample sample;
        String path;
        String vu;
        String iter;
        long end;
        boolean cut;
        final List<Node> kids = new ArrayList<>();

        Node(boolean pass) {
            this.pass = pass;
        }
    }

    /** The CSV, read once, into what the converter builds. */
    private static final class Conversion {
        boolean hasMetadata;
        boolean wholeSeconds;
        long requests;
        long completePasses;
        long cutOff;
        long nextId;
        final List<Node> top = new ArrayList<>();
        /** Open passes by vu, iter and group path: closed by their group_duration row. */
        final Map<String, Node> open = new LinkedHashMap<>();
        final Set<String> completedNames = new HashSet<>();
        final List<long[]> users = new ArrayList<>();
        final List<ToolRecords.Check> checks = new ArrayList<>();
        /** Rows written as soon as they are whole: requests outside groups, closed top-level passes. */
        RunDataWriter early;
        final List<Node> ready = new ArrayList<>();

        void read(File results) throws IOException {
            try (BufferedReader reader = open(results)) {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    throw new IOException("Empty k6 results file: " + results);
                }
                Map<String, Integer> cols = columns(headerLine);
                for (String required : new String[]{"metric_name", "timestamp", "metric_value"}) {
                    if (!cols.containsKey(required)) {
                        throw new IOException("Not a k6 CSV results file (no " + required + " column): " + results);
                    }
                }
                Row req = null;
                Map<String, Double> reqValues = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    Row r = new Row(splitCsv(line), cols);
                    String metric = r.get("metric_name");
                    if ("http_reqs".equals(metric)) {
                        // a request starts here; it ends at the next http_reqs or any other metric
                        if (req != null) {
                            request(req, reqValues);
                        }
                        req = r;
                        reqValues = new HashMap<>();
                        continue;
                    }
                    if (metric.startsWith("http_req_") && req != null) {
                        reqValues.put(metric, Double.parseDouble(r.get("metric_value")));
                        continue;
                    }
                    if (req != null) {
                        request(req, reqValues);
                        req = null;
                    }
                    if ("vus".equals(metric)) {
                        users.add(new long[]{ms(r.get("timestamp")), Math.round(Double.parseDouble(r.get("metric_value")))});
                    } else if ("group_duration".equals(metric)) {
                        groupClosed(r);
                    } else if ("checks".equals(metric)) {
                        check(r);
                    }
                }
                if (req != null) {
                    request(req, reqValues);
                }
            }
            endOfFile();
        }

        private void request(Row r, Map<String, Double> v) {
            requests++;
            String[] meta = meta(r.get("metadata"));
            if (meta[0] != null) {
                hasMetadata = true;
            }
            long end = ms(r.get("timestamp"));
            long elapsed = (long) Math.rint(v.getOrDefault("http_req_duration", 0.0));
            String expected = r.get("expected_response");
            String status = r.get("status");
            String error = r.get("error");
            boolean ok;
            if (!expected.isEmpty()) {
                ok = "true".equals(expected);
            } else if (v.containsKey("http_req_failed")) {
                ok = v.get("http_req_failed") == 0.0;
            } else {
                ok = status.matches("\\d+") && Integer.parseInt(status) >= 200 && Integer.parseInt(status) < 400
                        && error.isEmpty();
            }
            RunSample s = new RunSample(r.get("name"), end - elapsed, elapsed, ok);
            s.code = !status.isEmpty() && !"0".equals(status) ? status
                    : (r.get("error_code").isEmpty() ? "0" : r.get("error_code"));
            s.message = !error.isEmpty() ? error : (ok ? "OK" : "");
            s.url = emptyToNull(r.get("url"));
            s.method = emptyToNull(r.get("method"));
            s.user = user(meta[0], r.get("group"));
            s.iteration = meta[1] == null ? null : Integer.valueOf(meta[1]);
            for (Map.Entry<String, String> t : TIMINGS.entrySet()) {
                Double d = v.get(t.getKey());
                if (d != null) {
                    s.timing(t.getValue(), (long) Math.rint(d));
                }
            }
            Node n = new Node(false);
            n.sample = s;
            n.vu = meta[0];
            n.iter = meta[1];
            n.path = r.get("group");
            n.end = end;
            attach(n, n.path);
        }

        private void check(Row r) {
            String[] meta = meta(r.get("metadata"));
            ToolRecords.Check c = new ToolRecords.Check();
            c.time = ms(r.get("timestamp"));
            c.name = r.get("check");
            c.group = r.get("group");
            c.passed = Double.parseDouble(r.get("metric_value")) != 0.0;
            c.user = meta[0] == null ? null : "VU " + meta[0];
            c.iteration = meta[1] == null ? null : Integer.valueOf(meta[1]);
            checks.add(c);
        }

        /** A group pass's group_duration row: it closes the open pass of that vu, iteration and path. */
        private void groupClosed(Row r) {
            String[] meta = meta(r.get("metadata"));
            String path = r.get("group");
            long end = ms(r.get("timestamp"));
            long dur = Math.round(Double.parseDouble(r.get("metric_value")));
            completedNames.add(name(path));
            completePasses++;
            String key = key(meta[0], meta[1], path);
            Node p = open.remove(key);
            if (p == null) {
                p = newPass(meta[0], meta[1], path, end - dur, end);
            }
            // A complete pass spans its group_duration, as the converter makes it; finish() then starts it no later
            // than its first request and ends it after its last one starts
            p.sample.start = end - dur;
            p.end = end;
            p.cut = false;
            placeClosed(p);
        }

        private Node newPass(String vu, String iter, String path, long start, long end) {
            Node p = new Node(true);
            RunSample s = new RunSample();
            s.name = name(path);
            s.start = start;
            s.kind = RunSample.Kind.TRANSACTION;
            s.user = user(vu, path);
            s.iteration = iter == null ? null : Integer.valueOf(iter);
            p.sample = s;
            p.path = path;
            p.vu = vu;
            p.iter = iter;
            p.end = end;
            p.cut = true;
            return p;
        }

        /** Put x (a request, or a closed pass) into the open pass for {@code path} of its vu and iteration. */
        private void attach(Node x, String path) {
            if (path == null || path.isEmpty() || "::setup".equals(path) || "::teardown".equals(path) || x.vu == null) {
                top(x);
                return;
            }
            String key = key(x.vu, x.iter, path);
            Node p = open.get(key);
            if (p == null) {
                p = newPass(x.vu, x.iter, path, x.sample.start, x.end);
                open.put(key, p);
            }
            // A pass cut off by the test's end has no group_duration: its requests span it
            p.sample.start = Math.min(p.sample.start, x.sample.start);
            p.end = Math.max(p.end, x.end);
            p.kids.add(x);
        }

        /** A closed pass goes into its parent pass, or out as a whole subtree when it is a top-level one. */
        private void placeClosed(Node p) {
            int cut = p.path.lastIndexOf("::");
            String parentPath = cut > 0 ? p.path.substring(0, cut) : "";
            attach(p, parentPath);
        }

        private void top(Node x) {
            top.add(x);
        }

        /** Passes still open are cut off by the end of the test. */
        private void endOfFile() {
            List<Node> still = new ArrayList<>(open.values());
            open.clear();
            // inner first, so a cut-off inner pass finds its cut-off parent
            still.sort((a, b) -> Integer.compare(depth(b.path), depth(a.path)));
            for (Node p : still) {
                if (completedNames.contains(name(p.path))) {
                    cutOff++;
                    placeClosed(p);
                } else {
                    // a group that never completed anywhere: its requests are plain rows, as JMeter cannot tell them
                    for (Node k : p.kids) {
                        top(k);
                    }
                }
            }
        }

        /** Assigns ids, works out each pass's start and wall time as the converter does, and writes every sample. */
        void write(RunDataWriter w) throws IOException {
            for (long[] u : users) {
                w.users(u[0], (int) u[1]);
            }
            top.sort((a, b) -> Long.compare(a.end, b.end));
            for (Node x : top) {
                if (x.pass) {
                    finish(x);
                }
                emit(w, x, null);
            }
            for (ToolRecords.Check c : checks) {
                w.check(c);
            }
        }

        /** Bottom-up: a pass starts no later than its first child, and ends at least 1 ms after its last child starts. */
        private void finish(Node p) {
            long last = Long.MIN_VALUE;
            for (Node k : p.kids) {
                if (k.pass) {
                    finish(k);
                }
                p.sample.start = Math.min(p.sample.start, k.sample.start);
                last = Math.max(last, k.sample.start + 1);
            }
            p.kids.sort((a, b) -> Long.compare(a.sample.start, b.sample.start));
            if (last != Long.MIN_VALUE) {
                p.end = Math.max(p.end, last);
            }
            p.sample.wallTime = p.end - p.sample.start;
            if (p.cut) {
                // a pass the test's end cut off is written as JMeter writes it: no "transaction" marker, counted as a
                // request with a code (200, or its first failed request's), and still the parent of its requests
                p.sample.kind = RunSample.Kind.REQUEST;
                p.sample.code = "200";
                for (Node k : p.kids) {
                    Boolean ok = k.pass ? null : k.sample.success;
                    if (Boolean.FALSE.equals(ok)) {
                        p.sample.code = k.sample.code;
                        break;
                    }
                }
            }
        }

        private void emit(RunDataWriter w, Node x, String parentId) throws IOException {
            RunSample s = x.sample;
            s.parent = parentId;
            s.topLevel = parentId == null;
            if (x.pass) {
                s.id = "g" + (++nextId);
                if (x.kids.isEmpty()) {
                    // a pass with only think time: nothing to take its time from
                    s.elapsed = 0L;
                    s.success = true;
                }
            }
            w.sample(s);
            for (Node k : x.kids) {
                emit(w, k, s.id);
            }
        }

        private String user(String vu, String path) {
            if (vu != null) {
                return "VU " + vu;
            }
            String p = path == null ? "" : path.replace("::", "");
            return p.isEmpty() ? "k6" : p;
        }

        private long ms(String ts) {
            long n = Long.parseLong(ts.trim());
            if (n < 100_000_000_000L) {
                wholeSeconds = true;
                return n * 1000;
            }
            return n;
        }
    }

    private static String key(String vu, String iter, String path) {
        return vu + "\u0000" + iter + "\u0000" + path;
    }

    private static String name(String path) {
        int cut = path.lastIndexOf("::");
        return cut >= 0 ? path.substring(cut + 2) : path;
    }

    private static int depth(String path) {
        int d = 0;
        for (int i = path.indexOf("::"); i >= 0; i = path.indexOf("::", i + 2)) {
            d++;
        }
        return d;
    }

    /** vu and iter from k6's metadata column ({@code vu=3&iter=0}, in any order). */
    private static String[] meta(String metadata) {
        String vu = null;
        String iter = null;
        if (metadata != null) {
            for (String part : metadata.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String k = part.substring(0, eq);
                    if ("vu".equals(k)) {
                        vu = part.substring(eq + 1);
                    } else if ("iter".equals(k)) {
                        iter = part.substring(eq + 1);
                    }
                }
            }
        }
        return new String[]{vu, iter};
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static BufferedReader open(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        if (file.getName().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    private static Map<String, Integer> columns(String header) {
        Map<String, Integer> cols = new HashMap<>();
        List<String> names = splitCsv(header.startsWith("﻿") ? header.substring(1) : header);
        for (int i = 0; i < names.size(); i++) {
            cols.put(names.get(i).trim(), i);
        }
        return cols;
    }

    /** One CSV record (k6 quotes a field holding a comma or a quote). */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** A CSV row by column name; a column the file lacks reads empty. */
    private static final class Row {
        private final List<String> fields;
        private final Map<String, Integer> cols;

        Row(List<String> fields, Map<String, Integer> cols) {
            this.fields = fields;
            this.cols = cols;
        }

        String get(String column) {
            Integer i = cols.get(column);
            return i == null || i >= fields.size() ? "" : fields.get(i);
        }
    }
}
