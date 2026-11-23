package com.jmeterwebinsightreport.core.rundata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterwebinsightreport.core.stats.LiveStatisticsEngine;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Reads run data into the statistics engine (spec 001, data-model.md "Reading order and memory" and "Validation").
 *
 * <p>The file is read twice, as {@code JtlParser} reads a JTL once for its transaction names before parsing it:
 * <ol>
 *   <li>Pass 1 indexes every sample that has an id (its name, and whether it gave an elapsed time and a success) and
 *       adds up each parent's direct children. A sample with children that gives no elapsed time takes their sum, and
 *       no success fails when a child failed, as JMeter's Transaction Controller does (research R4). Nested passes
 *       resolve bottom-up after the pass.</li>
 *   <li>Pass 2 feeds every record in file order through {@link SampleFeed}.</li>
 * </ol>
 * Memory grows with the samples that have an id, not with all samples. Records may come in any order.
 */
public final class RunDataReader {

    /** At most this many problems are reported by line; the rest are counted. */
    static final int MAX_REPORTED = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean failureMessageTypesErrors = true;

    /** What a read gave besides the engine's samples. */
    public static final class Result {
        public RunHeader header;
        public final ToolRecords tool = new ToolRecords();
        public long samples;
        public long skipped;
        /** The first problems, each naming its line. */
        public final List<String> problems = new ArrayList<>();
    }

    /** Whether a failed sample's failureMessage types its error, as on the JTL path (E1). */
    public void setFailureMessageTypesErrors(boolean failureMessageTypesErrors) {
        this.failureMessageTypesErrors = failureMessageTypesErrors;
    }

    /** What pass 1 knows about one sample with an id. */
    private static final class Node {
        String name;
        Long elapsed;
        Boolean success;
        long childElapsed;
        boolean childFailed;
        boolean hasChildren;
        final List<String> unresolvedChildren = new ArrayList<>(0);
        boolean resolving;
        boolean resolved;
    }

    public Result read(File file, LiveStatisticsEngine engine) throws IOException {
        Result result = new Result();
        Map<String, Node> nodes = new HashMap<>();
        indexPass(file, result, nodes);
        for (String id : nodes.keySet()) {
            resolve(id, nodes, new HashSet<>());
        }
        feedPass(file, engine, result, nodes);
        if (result.samples == 0) {
            throw new IOException("Run data with no usable sample: " + file
                    + (result.problems.isEmpty() ? "" : " - " + String.join("; ", result.problems)));
        }
        return result;
    }

    private static BufferedReader open(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        if (file.getName().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    private void indexPass(File file, Result result, Map<String, Node> nodes) throws IOException {
        try (BufferedReader reader = open(file)) {
            String line = firstRecord(reader);
            if (line == null) {
                throw new IOException("Empty run data file: " + file);
            }
            result.header = header(line, file);
            long lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode r;
                try {
                    r = JSON.readTree(line);
                } catch (IOException e) {
                    continue; // counted in pass 2
                }
                if (r == null || !RunDataFormat.TYPE_SAMPLE.equals(text(r, "type"))) {
                    continue;
                }
                String id = text(r, "id");
                if (id != null) {
                    Node n = nodes.computeIfAbsent(id, k -> new Node());
                    n.name = text(r, "name");
                    n.elapsed = longOrNull(r, "elapsed");
                    n.success = r.hasNonNull("success") ? r.get("success").asBoolean() : null;
                }
                String parent = text(r, "parent");
                if (parent != null) {
                    Node p = nodes.computeIfAbsent(parent, k -> new Node());
                    p.hasChildren = true;
                    Long elapsed = longOrNull(r, "elapsed");
                    Boolean success = r.hasNonNull("success") ? r.get("success").asBoolean() : null;
                    if (elapsed != null && success != null) {
                        p.childElapsed += Math.max(0, elapsed);
                        p.childFailed |= !success;
                    } else if (id != null) {
                        p.unresolvedChildren.add(id);
                    }
                }
            }
        }
    }

    /** A sample with children and no elapsed time or success takes them from its children (research R4). */
    private static void resolve(String id, Map<String, Node> nodes, Set<String> path) {
        Node n = nodes.get(id);
        if (n == null || n.resolved) {
            return;
        }
        if (!path.add(id)) {
            return; // a cycle: left unresolved, and the sample is refused in pass 2
        }
        for (String child : n.unresolvedChildren) {
            resolve(child, nodes, path);
            Node c = nodes.get(child);
            if (c != null && c.elapsed != null && c.success != null) {
                n.childElapsed += Math.max(0, c.elapsed);
                n.childFailed |= !c.success;
            }
        }
        if (n.hasChildren) {
            if (n.elapsed == null) {
                n.elapsed = n.childElapsed;
            }
            if (n.success == null) {
                n.success = !n.childFailed;
            }
        }
        n.resolved = true;
        path.remove(id);
    }

    private void feedPass(File file, LiveStatisticsEngine engine, Result result, Map<String, Node> nodes)
            throws IOException {
        try (BufferedReader reader = open(file)) {
            String line = firstRecord(reader);
            long lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode r;
                try {
                    r = JSON.readTree(line);
                } catch (IOException e) {
                    skip(result, lineNo, "not a JSON record (" + firstLine(e.getMessage()) + ")");
                    continue;
                }
                if (r == null || !r.isObject()) {
                    skip(result, lineNo, "not a JSON object");
                    continue;
                }
                String type = text(r, "type");
                try {
                    if (RunDataFormat.TYPE_SAMPLE.equals(type)) {
                        RunSample s = sample(r, nodes, result, lineNo);
                        if (s != null) {
                            Node parent = s.parent == null ? null : nodes.get(s.parent);
                            String parentName = parent == null ? null : parent.name;
                            if (s.parent != null && parentName == null) {
                                problem(result, lineNo, "parent \"" + s.parent + "\" names no sample in the file - "
                                        + "the sample is counted at the top level");
                                s.parent = null;
                                s.topLevel = true;
                            }
                            SampleFeed.feed(engine, s, parentName);
                            result.samples++;
                        }
                    } else if (RunDataFormat.TYPE_USERS.equals(type)) {
                        long time = time(r, "time", result, lineNo);
                        if (time > 0) {
                            engine.updateActiveThreads(r.path("active").asInt(), time);
                        }
                    } else if (RunDataFormat.TYPE_CHECK.equals(type)) {
                        ToolRecords.Check c = new ToolRecords.Check();
                        c.time = r.path("time").asLong();
                        c.name = text(r, "name");
                        c.group = text(r, "group");
                        c.passed = r.path("passed").asBoolean();
                        c.user = text(r, "user");
                        c.iteration = r.hasNonNull("iteration") ? r.get("iteration").asInt() : null;
                        if (c.name != null) {
                            result.tool.addCheck(c);
                        }
                    } else if (RunDataFormat.TYPE_THRESHOLD.equals(type)) {
                        result.tool.addThreshold(new ToolRecords.Threshold(text(r, "metric"), text(r, "expression"),
                                r.path("passed").asBoolean()));
                    } else if (RunDataFormat.TYPE_COUNTER.equals(type)) {
                        if (text(r, "name") != null && r.hasNonNull("value")) {
                            result.tool.addCounter(text(r, "name"), r.get("value").asDouble());
                        }
                    }
                    // an unknown type is skipped in silence: a later minor version's (research R9)
                } catch (Refused e) {
                    skip(result, lineNo, e.getMessage());
                }
            }
        }
    }

    private RunSample sample(JsonNode r, Map<String, Node> nodes, Result result, long lineNo) {
        String name = text(r, "name");
        if (name == null) {
            throw new Refused("a sample with no name");
        }
        RunSample s = new RunSample();
        s.name = name;
        s.start = time(r, "start", result, lineNo);
        s.id = text(r, "id");
        s.parent = text(r, "parent");
        s.link = "subresult".equals(text(r, "link")) ? RunSample.Link.SUBRESULT : RunSample.Link.TRANSACTION;
        s.kind = "transaction".equals(text(r, "kind")) ? RunSample.Kind.TRANSACTION : RunSample.Kind.REQUEST;
        s.elapsed = longOrNull(r, "elapsed");
        s.success = r.hasNonNull("success") ? r.get("success").asBoolean() : null;
        if (s.id != null && (s.elapsed == null || s.success == null)) {
            Node n = nodes.get(s.id);
            if (n != null) {
                s.elapsed = s.elapsed != null ? s.elapsed : n.elapsed;
                s.success = s.success != null ? s.success : n.success;
            }
        }
        if (s.elapsed == null || s.success == null) {
            throw new Refused("sample \"" + name + "\" gives no " + (s.elapsed == null ? "elapsed time" : "success")
                    + " and has no children to take it from");
        }
        s.idle = longOrNull(r, "idle");
        s.wallTime = longOrNull(r, "wallTime");
        if (s.idle == null && s.wallTime != null) {
            s.idle = Math.max(0, s.wallTime - s.elapsed);
        }
        s.code = text(r, "code");
        s.message = text(r, "message");
        s.failureMessage = failureMessageTypesErrors ? text(r, "failureMessage") : null;
        s.user = text(r, "user");
        s.iteration = r.hasNonNull("iteration") ? r.get("iteration").asInt() : null;
        s.topLevel = r.hasNonNull("topLevel") ? r.get("topLevel").asBoolean() : null;
        if (r.hasNonNull("samples")) {
            s.samples = Math.max(1, r.get("samples").asLong());
        }
        s.errors = longOrNull(r, "errors");
        s.bytes = longOrNull(r, "bytes");
        s.sentBytes = longOrNull(r, "sentBytes");
        s.latency = longOrNull(r, "latency");
        s.connect = longOrNull(r, "connect");
        s.threads = r.hasNonNull("threads") ? r.get("threads").asInt() : null;
        s.url = text(r, "url");
        s.method = text(r, "method");
        JsonNode timings = r.get("timings");
        if (timings != null && timings.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = timings.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> t = it.next();
                if (t.getValue().isNumber()) {
                    s.timing(t.getKey(), t.getValue().asLong());
                }
            }
        }
        s.responseBody = text(r, "responseBody");
        return s;
    }

    /** An epoch-milliseconds time: 11 to 15 digits. Seconds (10 digits) and µs or ns (16 or more) are refused. */
    private static long time(JsonNode r, String field, Result result, long lineNo) {
        JsonNode v = r.get(field);
        if (v == null || !v.canConvertToLong()) {
            throw new Refused("no " + field + " in epoch milliseconds");
        }
        long t = v.asLong();
        if (t < 100_000_000_000L) {
            throw new Refused(field + " " + t + " looks like seconds, not epoch milliseconds");
        }
        if (t >= 1_000_000_000_000_000L) {
            throw new Refused(field + " " + t + " looks like micro- or nanoseconds, not epoch milliseconds");
        }
        return t;
    }

    private static String firstRecord(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line.startsWith("﻿") ? line.substring(1) : line;
            }
        }
        return null;
    }

    private static RunHeader header(String line, File file) throws IOException {
        JsonNode r;
        try {
            r = JSON.readTree(line);
        } catch (IOException e) {
            throw new IOException("Not run data: the first line of " + file + " is not a JSON record", e);
        }
        if (r == null || !RunDataFormat.TYPE_RUN.equals(text(r, "type"))) {
            throw new IOException("Not run data: the first record of " + file + " is not the \"run\" record");
        }
        String version = text(r, "formatVersion");
        if (!RunDataFormat.reads(version)) {
            throw new IOException(RunDataFormat.refusal(version) + ": " + file);
        }
        RunHeader h = new RunHeader();
        h.formatVersion = version;
        h.toolName = text(r.path("tool"), "name");
        h.toolVersion = text(r.path("tool"), "version");
        h.testName = text(r, "testName");
        h.producerName = text(r.path("producer"), "name");
        h.producerVersion = text(r.path("producer"), "version");
        h.timeResolution = r.hasNonNull("timeResolution") ? text(r, "timeResolution") : "ms";
        h.notMeasured = new ArrayList<>();
        if (r.path("notMeasured").isArray()) {
            r.get("notMeasured").forEach(f -> h.notMeasured.add(f.asText()));
        }
        if (h.toolName == null || h.testName == null) {
            throw new IOException("Run data without " + (h.toolName == null ? "tool.name" : "testName")
                    + " in its run record: " + file);
        }
        return h;
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long longOrNull(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }

    private static String firstLine(String message) {
        String m = String.valueOf(message);
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private static void skip(Result result, long lineNo, String why) {
        result.skipped++;
        problem(result, lineNo, why + " - skipped");
    }

    private static void problem(Result result, long lineNo, String why) {
        if (result.problems.size() < MAX_REPORTED) {
            result.problems.add("line " + lineNo + ": " + why);
        }
    }

    /** A record the reader skips, with its reason. */
    private static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
